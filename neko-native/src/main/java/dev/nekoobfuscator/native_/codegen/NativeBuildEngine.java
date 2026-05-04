package dev.nekoobfuscator.native_.codegen;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.*;

/**
 * Builds native libraries from generated C source files using zig cc.
 */
public final class NativeBuildEngine {
    private static final Logger log = LoggerFactory.getLogger(NativeBuildEngine.class);

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
            Files.writeString(srcFile, cSource);
            Files.writeString(hdrFile, headerSource);

            // Find JNI headers
            String javaHome = System.getProperty("java.home");
            Path jniInclude = Path.of(javaHome, "include");
            Path jniPlatformInclude = findPlatformInclude(jniInclude);

            for (String target : targets) {
                String zigTarget = mapTarget(target);
                String ext = target.contains("WINDOWS") ? ".dll" : target.contains("MACOS") ? ".dylib" : ".so";
                String libName = "libneko_" + target.toLowerCase() + ext;
                Path outputLib = tempDir.resolve(libName);

                /* NEKO_NATIVE_DEBUG=1 keeps function symbols + frame pointers
                 * + DWARF in libneko so that gdb / addr2line can resolve
                 * trampoline / dispatcher / impl_fn frames after a crash.
                 * Default off (size-optimized release build). */
                boolean debugBuild = System.getenv("NEKO_NATIVE_DEBUG") != null;
                List<String> cmd = new ArrayList<>(List.of(
                    zigPath, "cc",
                    "-shared",
                    debugBuild ? "-O1" : "-O3",
                    "-std=c11", "-Wall", "-Wextra",
                    "-target", zigTarget,
                    "-I", jniInclude.toString()
                ));
                if (!debugBuild) {
                    /* Pick a modern microarch baseline so generated translated
                     * methods get AVX/BMI2/FMA, not SSE2. The baselines are
                     * ten-plus years old (Intel Haswell 2013 / Apple M1 / armv8-a)
                     * and apply uniformly to every translated method, not
                     * benchmark-specific. */
                    String archFlag = switch (target) {
                        case "LINUX_X64", "WINDOWS_X64", "MACOS_X64" -> "-march=x86_64_v3";
                        case "LINUX_AARCH64" -> "-march=armv8-a";
                        case "MACOS_AARCH64" -> "-mcpu=apple_m1";
                        default -> null;
                    };
                    if (archFlag != null) {
                        cmd.add(archFlag);
                    }
                    /* -fno-plt removes one indirection on libc calls;
                     * -fno-semantic-interposition lets the compiler inline
                     * across same-DSO references. Both are generic. */
                    cmd.addAll(List.of("-fno-plt", "-fno-semantic-interposition"));
                }
                if (debugBuild) {
                    cmd.addAll(List.of("-g", "-fno-omit-frame-pointer"));
                }
                if (jniPlatformInclude != null) {
                    cmd.addAll(List.of("-I", jniPlatformInclude.toString()));
                }
                cmd.addAll(List.of(
                    "-o", outputLib.toString(),
                    srcFile.toString()
                ));

                log.info("Building native for {}: {}", target, String.join(" ", cmd));
                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Process proc = pb.start();
                String output = new String(proc.getInputStream().readAllBytes());
                int exitCode;
                try { exitCode = proc.waitFor(); } catch (InterruptedException e) { exitCode = -1; }

                if (exitCode == 0 && Files.exists(outputLib)) {
                    results.put("neko/native/" + libName, Files.readAllBytes(outputLib));
                    log.info("Built {} ({} bytes)", libName, Files.size(outputLib));
                } else {
                    log.warn("Failed to build for {}: exit={}\n{}", target, exitCode, output);
                }
            }
        }
        return results;
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
            default -> target.toLowerCase();
        };
    }

    private Path findPlatformInclude(Path jniInclude) {
        String os = System.getProperty("os.name").toLowerCase();
        String platform = os.contains("win") ? "win32" : os.contains("mac") ? "darwin" : "linux";
        Path p = jniInclude.resolve(platform);
        return Files.isDirectory(p) ? p : null;
    }

}
