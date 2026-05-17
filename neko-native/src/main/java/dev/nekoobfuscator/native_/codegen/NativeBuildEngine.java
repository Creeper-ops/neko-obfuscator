package dev.nekoobfuscator.native_.codegen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * Builds native libraries from generated C source files using zig cc.
 */
public final class NativeBuildEngine {
    private static final Logger log = LoggerFactory.getLogger(NativeBuildEngine.class);
    private static final String RAW_IMPL_PREFIX = "NEKO_FLATTEN NEKO_HOT static ";
    private static final Pattern RAW_IMPL_PROTO = Pattern.compile("(?m)^static (.+?\\s+neko_native_impl_\\d+\\([^;]+;)$");

    private final String zigPath;

    public NativeBuildEngine(String zigPath) {
        this.zigPath = zigPath;
    }

    public Map<String, byte[]> build(String cSource, String headerSource, List<String> targets) throws IOException {
        Path tempDir = workspaceBuildDir();
        Map<String, byte[]> results = new LinkedHashMap<>();
        Files.createDirectories(tempDir);
        {
            Path srcFile = tempDir.resolve("neko_native.c");
            Path hdrFile = tempDir.resolve("neko_native.h");
            Path manifestFile = tempDir.resolve("neko_native_build_manifest.properties");
            Files.writeString(srcFile, cSource);
            Files.writeString(hdrFile, headerSource);
            Properties manifest = new Properties();
            manifest.setProperty("generated.c.path", srcFile.toString());
            manifest.setProperty("generated.header.path", hdrFile.toString());
            manifest.setProperty("debug.build", Boolean.toString(System.getenv("NEKO_NATIVE_DEBUG") != null));

            // Find JNI headers
            String javaHome = System.getProperty("java.home");
            Path jniInclude = Path.of(javaHome, "include");
            Path jniPlatformInclude = findPlatformInclude(jniInclude);

            for (String target : targets) {
                String zigTarget = mapTarget(target);
                String ext = target.contains("WINDOWS") ? ".dll" : target.contains("MACOS") ? ".dylib" : ".so";
                String libName = "libneko_" + target.toLowerCase(Locale.ROOT) + ext;
                Path outputLib = tempDir.resolve(libName);

                boolean debugBuild = System.getenv("NEKO_NATIVE_DEBUG") != null;
                List<String> compileFlags = compileFlags(target, zigTarget, jniInclude, jniPlatformInclude, debugBuild);
                List<Path> objectInputs = structuredObjectInputs(cSource, tempDir, compileFlags, manifest);
                List<String> cmd = linkCommand(zigTarget, outputLib, objectInputs);
                String targetKey = "target." + target + '.';
                manifest.setProperty(targetKey + "zig.target", zigTarget);
                manifest.setProperty(targetKey + "library.path", outputLib.toString());
                manifest.setProperty(targetKey + "command.line", String.join(" ", cmd));

                log.info("Building native for {}: {}", target, String.join(" ", cmd));
                log.info("Native build manifest for {}: {}", target, manifestFile);
                ProcessResult build = run(cmd);
                manifest.setProperty(targetKey + "exit.code", Integer.toString(build.exitCode()));
                manifest.setProperty(targetKey + "compiler.output", build.output());

                if (build.exitCode() == 0 && Files.exists(outputLib)) {
                    results.put("neko/native/" + libName, Files.readAllBytes(outputLib));
                    manifest.setProperty(targetKey + "library.size.bytes", Long.toString(Files.size(outputLib)));
                    log.info("Built {} ({} bytes)", libName, Files.size(outputLib));
                } else {
                    log.warn("Failed to build for {}: exit={}\n{}", target, build.exitCode(), build.output());
                }
                try (OutputStream out = Files.newOutputStream(manifestFile)) {
                    manifest.store(out, "Neko native build manifest");
                }
            }
        }
        return results;
    }

    private List<String> compileFlags(String target, String zigTarget, Path jniInclude, Path jniPlatformInclude, boolean debugBuild) {
        List<String> flags = new ArrayList<>(List.of(
            debugBuild ? "-O1" : "-O3",
            "-std=c11", "-Wall", "-Wextra",
            "-target", zigTarget,
            "-I", jniInclude.toString()
        ));
        if (!debugBuild) {
            String archFlag = switch (target) {
                case "LINUX_X64", "WINDOWS_X64", "MACOS_X64" -> "-march=x86_64_v3";
                case "LINUX_AARCH64" -> "-march=armv8-a";
                case "MACOS_AARCH64" -> "-mcpu=apple_m1";
                default -> null;
            };
            if (archFlag != null) flags.add(archFlag);
            flags.addAll(List.of("-fno-plt", "-fno-semantic-interposition", "-fmerge-all-constants", "-funroll-loops"));
        }
        if (debugBuild) flags.addAll(List.of("-g", "-fno-omit-frame-pointer"));
        if (jniPlatformInclude != null) flags.addAll(List.of("-I", jniPlatformInclude.toString()));
        return flags;
    }

    private List<Path> structuredObjectInputs(String source, Path dir, List<String> compileFlags, Properties manifest) throws IOException {
        List<FunctionRegion> functions = rawImplFunctions(source);
        if (functions.size() < 4 || System.getenv("NEKO_NATIVE_MONOLITHIC") != null) {
            Path obj = dir.resolve("neko_native.o");
            compileObject(dir.resolve("neko_native.c"), obj, compileFlags);
            manifest.setProperty("generated.source.count", "1");
            return List.of(obj);
        }

        int requested = configuredSplitCount();
        int chunks = Math.max(1, Math.min(functions.size(), requested));
        int firstStart = functions.get(0).start();
        int lastEnd = functions.get(functions.size() - 1).end();
        String prefix = source.substring(0, firstStart);
        String suffix = source.substring(lastEnd);
        String commonPrefix = externalizeRawImplPrototypes(prefix);
        String commonSource = commonPrefix + suffix;
        Path commonC = dir.resolve("neko_native_common.c");
        Path commonO = dir.resolve("neko_native_common.o");
        Files.writeString(commonC, commonSource);

        String chunkPrefix = externalizeForImplChunk(prefix);
        List<Path> sources = new ArrayList<>();
        sources.add(commonC);
        int perChunk = (functions.size() + chunks - 1) / chunks;
        for (int i = 0; i < chunks; i++) {
            int from = i * perChunk;
            if (from >= functions.size()) break;
            int to = Math.min(functions.size(), from + perChunk);
            StringBuilder chunk = new StringBuilder(chunkPrefix.length() + 4096);
            chunk.append(chunkPrefix);
            for (int fn = from; fn < to; fn++) {
                FunctionRegion region = functions.get(fn);
                chunk.append('\n').append(externalizeRawImplDefinition(source.substring(region.start(), region.end()))).append('\n');
            }
            Path chunkC = dir.resolve("neko_native_impl_" + i + ".c");
            Files.writeString(chunkC, chunk.toString());
            sources.add(chunkC);
        }

        List<Path> objects = new ArrayList<>();
        objects.add(commonO);
        for (int i = 1; i < sources.size(); i++) {
            objects.add(dir.resolve(sources.get(i).getFileName().toString().replace(".c", ".o")));
        }
        compileObjectsParallel(sources, objects, compileFlags);
        manifest.setProperty("generated.source.count", Integer.toString(sources.size()));
        for (int i = 0; i < sources.size(); i++) {
            manifest.setProperty("generated.source." + i + ".path", sources.get(i).toString());
        }
        return objects;
    }

    private void compileObjectsParallel(List<Path> sources, List<Path> objects, List<String> flags) throws IOException {
        int threads = Math.min(sources.size(), Math.max(1, Runtime.getRuntime().availableProcessors()));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<ProcessResult>> futures = new ArrayList<>();
            for (int i = 0; i < sources.size(); i++) {
                Path source = sources.get(i);
                Path object = objects.get(i);
                futures.add(pool.submit(() -> compileObject(source, object, flags)));
            }
            StringBuilder failures = new StringBuilder();
            for (Future<ProcessResult> future : futures) {
                ProcessResult result;
                try {
                    result = future.get();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("Interrupted compiling native source", e);
                } catch (ExecutionException e) {
                    throw new IOException("Failed compiling native source", e.getCause());
                }
                if (result.exitCode() != 0) failures.append(result.output()).append('\n');
            }
            if (!failures.isEmpty()) throw new IOException("Native object compilation failed\n" + failures);
        } finally {
            pool.shutdownNow();
        }
    }

    private ProcessResult compileObject(Path source, Path object, List<String> flags) throws IOException {
        List<String> cmd = new ArrayList<>();
        cmd.add(zigPath);
        cmd.add("cc");
        cmd.add("-c");
        cmd.addAll(flags);
        cmd.addAll(List.of("-o", object.toString(), source.toString()));
        log.info("Compiling native object: {}", String.join(" ", cmd));
        return run(cmd);
    }

    private List<String> linkCommand(String zigTarget, Path outputLib, List<Path> objects) {
        List<String> cmd = new ArrayList<>(List.of(zigPath, "cc", "-shared", "-target", zigTarget, "-o", outputLib.toString()));
        for (Path object : objects) cmd.add(object.toString());
        return cmd;
    }

    private int configuredSplitCount() {
        String configured = System.getenv("NEKO_NATIVE_SPLIT_UNITS");
        if (configured != null && !configured.isBlank()) {
            try { return Math.max(1, Integer.parseInt(configured)); } catch (NumberFormatException ignored) { }
        }
        return Math.max(1, Runtime.getRuntime().availableProcessors());
    }

    private List<FunctionRegion> rawImplFunctions(String source) {
        List<FunctionRegion> regions = new ArrayList<>();
        int index = 0;
        while ((index = source.indexOf(RAW_IMPL_PREFIX, index)) >= 0) {
            int bodyStart = source.indexOf('{', index);
            if (bodyStart < 0) break;
            int end = findFunctionEnd(source, bodyStart);
            if (end < 0) break;
            regions.add(new FunctionRegion(index, end));
            index = end;
        }
        return regions;
    }

    private int findFunctionEnd(String source, int bodyStart) {
        int depth = 0;
        boolean string = false;
        boolean character = false;
        boolean lineComment = false;
        boolean blockComment = false;
        for (int i = bodyStart; i < source.length(); i++) {
            char c = source.charAt(i);
            char next = i + 1 < source.length() ? source.charAt(i + 1) : '\0';
            if (lineComment) { if (c == '\n') lineComment = false; continue; }
            if (blockComment) { if (c == '*' && next == '/') { blockComment = false; i++; } continue; }
            if (string) { if (c == '\\') i++; else if (c == '"') string = false; continue; }
            if (character) { if (c == '\\') i++; else if (c == '\'') character = false; continue; }
            if (c == '/' && next == '/') { lineComment = true; i++; continue; }
            if (c == '/' && next == '*') { blockComment = true; i++; continue; }
            if (c == '"') { string = true; continue; }
            if (c == '\'') { character = true; continue; }
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return i + 1;
        }
        return -1;
    }

    private String externalizeRawImplPrototypes(String prefix) {
        return RAW_IMPL_PROTO.matcher(prefix).replaceAll("__attribute__((visibility(\\\"hidden\\\"))) extern $1");
    }

    private String externalizeRawImplDefinition(String body) {
        return body.replaceFirst(Pattern.quote(RAW_IMPL_PREFIX), "NEKO_FLATTEN NEKO_HOT __attribute__((visibility(\"hidden\"))) ");
    }

    private String externalizeForImplChunk(String prefix) {
        String transformed = externalizeRawImplPrototypes(prefix);
        StringBuilder out = new StringBuilder(transformed.length());
        String[] lines = transformed.split("\\R", -1);
        boolean skippingInitializer = false;
        for (String line : lines) {
            String trimmed = line.trim();
            if (skippingInitializer) {
                if (trimmed.endsWith("};")) skippingInitializer = false;
                continue;
            }
            if (trimmed.contains("__attribute__((alias(")) {
                int alias = line.indexOf("__attribute__((alias(");
                out.append(line, 0, alias).append(';').append('\n');
                continue;
            }
            if (trimmed.startsWith("static ") && !trimmed.contains("(") && trimmed.contains("=")) {
                int eq = line.indexOf('=');
                int semi = line.lastIndexOf(';');
                if (semi > eq) {
                    out.append(line, 0, eq).append(';').append('\n');
                } else {
                    out.append(line, 0, eq).append(';').append('\n');
                    skippingInitializer = true;
                }
                continue;
            }
            if (trimmed.startsWith("static ") && !trimmed.contains("(") && trimmed.endsWith(";")) {
                out.append(line.replaceFirst("static\\s+", "extern ")).append('\n');
                continue;
            }
            if (trimmed.startsWith("__attribute__((visibility(\"hidden\")))") && !trimmed.contains("extern ")) {
                String hiddenPrefix = "__attribute__((visibility(\"hidden\")))";
                String rest = trimmed.substring(hiddenPrefix.length()).trim();
                if (rest.contains("(") && (rest.endsWith("{") || rest.endsWith(";"))) {
                    out.append(line.replaceFirst("__attribute__\\(\\(visibility\\(\\\"hidden\\\"\\)\\)\\)\\s+", "static ")).append('\n');
                } else if (rest.contains("=")) {
                    int eq = line.indexOf('=');
                    int semi = line.lastIndexOf(';');
                    if (semi > eq) out.append("extern ").append(line, hiddenPrefix.length(), eq).append(';').append('\n');
                    else { out.append("extern ").append(line, hiddenPrefix.length(), eq).append(';').append('\n'); skippingInitializer = true; }
                } else {
                    out.append(line).append('\n');
                }
                continue;
            }
            out.append(line).append('\n');
        }
        return out.toString();
    }

    private ProcessResult run(List<String> cmd) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode;
        try {
            exitCode = proc.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            exitCode = -1;
        }
        return new ProcessResult(exitCode, output);
    }

    private Path workspaceBuildDir() throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path dir = root.resolve("build").resolve("neko-native-work");
        Files.createDirectories(dir);
        return dir.resolve("run-" + Long.toUnsignedString(System.nanoTime()));
    }

    private String mapTarget(String target) {
        return switch (target) {
            case "LINUX_X64" -> "x86_64-linux-gnu";
            case "LINUX_AARCH64" -> "aarch64-linux-gnu";
            case "WINDOWS_X64" -> "x86_64-windows-gnu";
            case "MACOS_X64" -> "x86_64-macos-none";
            case "MACOS_AARCH64" -> "aarch64-macos-none";
            default -> target.toLowerCase(Locale.ROOT);
        };
    }

    private Path findPlatformInclude(Path jniInclude) {
        String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
        String platform = os.contains("win") ? "win32" : os.contains("mac") ? "darwin" : "linux";
        Path p = jniInclude.resolve(platform);
        return Files.isDirectory(p) ? p : null;
    }

    private record FunctionRegion(int start, int end) {}
    private record ProcessResult(int exitCode, String output) {}
}
