package dev.nekoobfuscator.test;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NativeObfuscationPerfTest {
    private static final Pattern BUILD_MANIFEST_PATTERN = Pattern.compile("Native build manifest for ([^:]+): (\\S+)");
    private static final Pattern PERF_TOPIC_PATTERN = Pattern.compile("(?i)(matrix|seq|thread|virtual|parallel|vthreads)");
    private static final Pattern PERF_TIME_PATTERN = Pattern.compile("(?i)\\b\\d+(?:\\.\\d+)?\\s*(?:ns|us|micros?|ms|milliseconds?|s|seconds?)\\b");

    @BeforeAll
    static void prepareFixtures() throws Exception {
        NativeObfuscationHelper.ensureObfuscatedFixtures();
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void nativeObfuscation_TEST_obfuscationSpeedUnder60s() throws Exception {
        NativeObfuscationHelper.ObfuscationRunResult result = NativeObfuscationHelper.obfuscateJar(
            NativeObfuscationHelper.jarsDir().resolve("TEST.jar"),
            NativeObfuscationHelper.nativeWorkDir().resolve("perf-TEST-native.jar"),
            NativeObfuscationHelper.configsDir().resolve("native-test.yml"),
            Duration.ofMinutes(2)
        );

        assertTrue(result.duration().toSeconds() < 60, () -> "TEST obfuscation exceeded 60s: " + result.duration() + "\n" + result.combinedOutput());
    }

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void nativeObfuscation_obfusjack_obfuscationSpeedUnder120s() throws Exception {
        NativeObfuscationHelper.ObfuscationRunResult result = NativeObfuscationHelper.obfuscateJar(
            NativeObfuscationHelper.jarsDir().resolve("obfusjack-test21.jar"),
            NativeObfuscationHelper.nativeWorkDir().resolve("perf-obfusjack-native.jar"),
            NativeObfuscationHelper.configsDir().resolve("native-obfusjack.yml"),
            Duration.ofMinutes(3)
        );

        assertTrue(result.duration().toSeconds() < 120, () -> "obfusjack obfuscation exceeded 120s: " + result.duration() + "\n" + result.combinedOutput());
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void nativeObfuscation_SnakeGame_obfuscationSpeedUnder30s() throws Exception {
        NativeObfuscationHelper.ObfuscationRunResult result = NativeObfuscationHelper.obfuscateJar(
            NativeObfuscationHelper.jarsDir().resolve("SnakeGame.jar"),
            NativeObfuscationHelper.nativeWorkDir().resolve("perf-SnakeGame-native.jar"),
            NativeObfuscationHelper.configsDir().resolve("native-snake.yml"),
            Duration.ofMinutes(2)
        );

        assertTrue(result.duration().toSeconds() < 30, () -> "SnakeGame obfuscation exceeded 30s: " + result.duration() + "\n" + result.combinedOutput());
    }

    @Test
    void nativeObfuscation_TEST_sharedLibrarySizeWithinSanityBounds() throws Exception {
        String libEntry = NativeObfuscationHelper.platformLibraryEntryName();
        byte[] libraryBytes = NativeObfuscationHelper.extractEntry(NativeObfuscationHelper.artifact("TEST").outputJar(), libEntry);

        assertTrue(libraryBytes.length >= 50 * 1024, () -> "Expected native library to be at least 50KB but was " + libraryBytes.length + " bytes");
        assertTrue(libraryBytes.length <= 5 * 1024 * 1024, () -> "Expected native library to be at most 5MB but was " + libraryBytes.length + " bytes");
    }

    @Test
    @Timeout(value = 2, unit = TimeUnit.MINUTES)
    void nativeObfuscation_TEST_calcBenchmarkMedianUnder150ms() throws Exception {
        Path jar = NativeObfuscationHelper.artifact("TEST").outputJar();
        List<Long> measurements = new ArrayList<>();

        for (int i = 0; i < 5; i++) {
            Path stdout = NativeObfuscationHelper.nativeWorkDir().resolve("calc-bench-" + i + ".stdout.log");
            Path stderr = NativeObfuscationHelper.nativeWorkDir().resolve("calc-bench-" + i + ".stderr.log");
            NativeObfuscationHelper.JarRunResult run = NativeObfuscationHelper.runJar(jar, List.of(), stdout, stderr, Duration.ofMinutes(2));
            assertEquals(0, run.exitCode(), () -> run.combinedOutput());
            NativeObfuscationHelper.assertNoFatalNativeCrash(run);
            measurements.add(NativeObfuscationHelper.parseCalcMillis(run.combinedOutput()));
        }

        List<Long> steadyState = new ArrayList<>(measurements.subList(2, measurements.size()));
        Collections.sort(steadyState);
        long median = steadyState.get(steadyState.size() / 2);

        assertTrue(median < 150, () -> "Expected median Calc time < 150ms but got " + median + "ms from measurements " + measurements);
    }

    @Test
    @Timeout(value = 6, unit = TimeUnit.MINUTES)
    void nativeObfuscation_captureNativePathPerformanceBaseline() throws Exception {
        NativeObfuscationHelper.NativeArtifact testArtifact = NativeObfuscationHelper.artifact("TEST");
        NativeObfuscationHelper.NativeArtifact obfusjackArtifact = NativeObfuscationHelper.artifact("obfusjack");

        NativeBuildCapture testBuild = captureBuild("TEST", testArtifact);
        NativeBuildCapture obfusjackBuild = captureBuild("obfusjack", obfusjackArtifact);
        List<BaselineRun> testRuns = captureRuns("TEST", testArtifact.outputJar(), true);
        List<BaselineRun> obfusjackRuns = captureRuns("obfusjack", obfusjackArtifact.outputJar(), false);
        List<Path> hsErrFiles = findHsErrFiles();

        Path report = NativeObfuscationHelper.nativeWorkDir().resolve("native-performance-baseline.json");
        Files.writeString(
            report,
            renderBaselineReport(testBuild, obfusjackBuild, testRuns, obfusjackRuns, hsErrFiles),
            StandardCharsets.UTF_8
        );

        assertTrue(Files.exists(report), () -> "Missing native performance baseline report: " + report);
        assertTrue(testBuild.targets().stream().allMatch(target -> !target.nativeCompilerCommandLine().isBlank()),
            () -> "TEST baseline missing compiler command line in " + testBuild.manifestPath());
        assertTrue(obfusjackBuild.targets().stream().allMatch(target -> !target.nativeCompilerCommandLine().isBlank()),
            () -> "obfusjack baseline missing compiler command line in " + obfusjackBuild.manifestPath());
    }

    private static List<BaselineRun> captureRuns(String fixture, Path jar, boolean parseCalc) throws Exception {
        List<BaselineRun> runs = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            final int runIndex = i;
            Path stdout = NativeObfuscationHelper.nativeWorkDir().resolve("baseline-" + fixture + '-' + i + ".stdout.log");
            Path stderr = NativeObfuscationHelper.nativeWorkDir().resolve("baseline-" + fixture + '-' + i + ".stderr.log");
            NativeObfuscationHelper.JarRunResult run = NativeObfuscationHelper.runJar(jar, List.of(), stdout, stderr, Duration.ofMinutes(2));
            String combined = run.combinedOutput();
            assertEquals(0, run.exitCode(), () -> fixture + " baseline run " + runIndex + "\n" + combined);
            NativeObfuscationHelper.assertNoFatalNativeCrash(run);
            Long calcMillis = parseCalc ? NativeObfuscationHelper.parseCalcMillis(combined) : null;
            if (!parseCalc) {
                assertTrue(combined.contains("=== All tests completed ==="), () -> fixture + " baseline run did not complete\n" + combined);
            }
            runs.add(new BaselineRun(
                fixture,
                runIndex,
                run.stdoutPath(),
                run.stderrPath(),
                run.exitCode(),
                run.duration().toMillis(),
                calcMillis,
                extractPerformanceTimingLines(combined)
            ));
        }
        return runs;
    }

    private static NativeBuildCapture captureBuild(String fixture, NativeObfuscationHelper.NativeArtifact artifact) throws Exception {
        String logs = Files.readString(artifact.obfuscationStdout()) + Files.readString(artifact.obfuscationStderr());
        Matcher matcher = BUILD_MANIFEST_PATTERN.matcher(logs);
        Path manifestPath = null;
        while (matcher.find()) {
            manifestPath = Path.of(matcher.group(2));
        }
        assertTrue(manifestPath != null && Files.exists(manifestPath),
            () -> "Missing native build manifest for " + fixture + "\n" + logs);
        final Path buildManifestPath = manifestPath;

        Properties properties = new Properties();
        try (var input = Files.newInputStream(buildManifestPath)) {
            properties.load(input);
        }

        String cPath = requiredProperty(properties, "generated.c.path", buildManifestPath);
        String headerPath = requiredProperty(properties, "generated.header.path", buildManifestPath);
        assertTrue(Files.exists(Path.of(cPath)), () -> "Generated C path recorded but missing: " + cPath);

        List<NativeBuildTargetCapture> targets = properties.stringPropertyNames().stream()
            .filter(name -> name.startsWith("target.") && name.endsWith(".command.line"))
            .map(name -> name.substring("target.".length(), name.length() - ".command.line".length()))
            .sorted()
            .map(target -> captureTarget(properties, buildManifestPath, target, cPath, headerPath))
            .toList();
        assertTrue(!targets.isEmpty(), () -> "No native build targets recorded in " + buildManifestPath);

        String libEntry = NativeObfuscationHelper.platformLibraryEntryName();
        long jarLibrarySize = NativeObfuscationHelper.extractEntry(artifact.outputJar(), libEntry).length;
        assertTrue(jarLibrarySize > 0, () -> "Platform native library was empty in " + artifact.outputJar());
        return new NativeBuildCapture(fixture, buildManifestPath, targets, jarLibrarySize);
    }

    private static NativeBuildTargetCapture captureTarget(
        Properties properties,
        Path manifestPath,
        String target,
        String cPath,
        String headerPath
    ) {
        String prefix = "target." + target + '.';
        String commandLine = requiredProperty(properties, prefix + "command.line", manifestPath);
        String libraryPath = requiredProperty(properties, prefix + "library.path", manifestPath);
        long librarySize = Long.parseLong(requiredProperty(properties, prefix + "library.size.bytes", manifestPath));
        int exitCode = Integer.parseInt(requiredProperty(properties, prefix + "exit.code", manifestPath));
        assertEquals(0, exitCode, () -> "Native compiler failed for " + target + " in " + manifestPath);
        assertTrue(librarySize > 0, () -> "Native library size was not recorded for " + target + " in " + manifestPath);
        return new NativeBuildTargetCapture(target, cPath, headerPath, libraryPath, commandLine, librarySize, exitCode);
    }

    private static String requiredProperty(Properties properties, String key, Path manifestPath) {
        String value = properties.getProperty(key);
        assertTrue(value != null && !value.isBlank(), () -> "Missing `" + key + "` in " + manifestPath);
        return value;
    }

    private static List<String> extractPerformanceTimingLines(String output) {
        return output.lines()
            .filter(line -> PERF_TOPIC_PATTERN.matcher(line).find())
            .filter(line -> PERF_TIME_PATTERN.matcher(line).find())
            .toList();
    }

    private static List<Path> findHsErrFiles() throws Exception {
        List<Path> files = new ArrayList<>();
        try (var stream = Files.walk(NativeObfuscationHelper.projectRoot(), 4)) {
            stream
                .filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().startsWith("hs_err_pid"))
                .filter(path -> path.getFileName().toString().endsWith(".log"))
                .forEach(files::add);
        }
        files.sort(Comparator.comparing(Path::toString));
        return files;
    }

    private static String renderBaselineReport(
        NativeBuildCapture testBuild,
        NativeBuildCapture obfusjackBuild,
        List<BaselineRun> testRuns,
        List<BaselineRun> obfusjackRuns,
        List<Path> hsErrFiles
    ) {
        StringBuilder sb = new StringBuilder(8192);
        sb.append("{\n");
        sb.append("  \"capturedAt\": ").append(json(Instant.now().toString())).append(",\n");
        sb.append("  \"jdk\": {\n");
        sb.append("    \"javaVersion\": ").append(json(System.getProperty("java.version"))).append(",\n");
        sb.append("    \"javaVendor\": ").append(json(System.getProperty("java.vendor"))).append(",\n");
        sb.append("    \"javaVmName\": ").append(json(System.getProperty("java.vm.name"))).append(",\n");
        sb.append("    \"javaRuntimeVersion\": ").append(json(System.getProperty("java.runtime.version"))).append("\n");
        sb.append("  },\n");
        sb.append("  \"artifacts\": [\n");
        appendBuild(sb, testBuild, "    ");
        sb.append(",\n");
        appendBuild(sb, obfusjackBuild, "    ");
        sb.append("\n  ],\n");
        sb.append("  \"runs\": {\n");
        sb.append("    \"TEST\": ");
        appendRuns(sb, testRuns);
        sb.append(",\n");
        sb.append("    \"obfusjack\": ");
        appendRuns(sb, obfusjackRuns);
        sb.append("\n  },\n");
        sb.append("  \"hsErr\": {\n");
        sb.append("    \"found\": ").append(!hsErrFiles.isEmpty()).append(",\n");
        sb.append("    \"paths\": ");
        appendPathArray(sb, hsErrFiles);
        sb.append("\n  }\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static void appendBuild(StringBuilder sb, NativeBuildCapture build, String indent) {
        sb.append(indent).append("{\n");
        sb.append(indent).append("  \"fixture\": ").append(json(build.fixture())).append(",\n");
        sb.append(indent).append("  \"manifestPath\": ").append(json(build.manifestPath().toString())).append(",\n");
        sb.append(indent).append("  \"jarLibrarySizeBytes\": ").append(build.jarLibrarySizeBytes()).append(",\n");
        sb.append(indent).append("  \"nativeBuildTargets\": [\n");
        for (int i = 0; i < build.targets().size(); i++) {
            NativeBuildTargetCapture target = build.targets().get(i);
            sb.append(indent).append("    {\n");
            sb.append(indent).append("      \"target\": ").append(json(target.target())).append(",\n");
            sb.append(indent).append("      \"generatedCPath\": ").append(json(target.generatedCPath())).append(",\n");
            sb.append(indent).append("      \"generatedHeaderPath\": ").append(json(target.generatedHeaderPath())).append(",\n");
            sb.append(indent).append("      \"generatedLibraryPath\": ").append(json(target.generatedLibraryPath())).append(",\n");
            sb.append(indent).append("      \"generatedLibrarySizeBytes\": ").append(target.generatedLibrarySizeBytes()).append(",\n");
            sb.append(indent).append("      \"nativeCompilerCommandLine\": ").append(json(target.nativeCompilerCommandLine())).append(",\n");
            sb.append(indent).append("      \"compilerExitCode\": ").append(target.compilerExitCode()).append("\n");
            sb.append(indent).append("    }");
            if (i + 1 < build.targets().size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append(indent).append("  ]\n");
        sb.append(indent).append('}');
    }

    private static void appendRuns(StringBuilder sb, List<BaselineRun> runs) {
        sb.append("[\n");
        for (int i = 0; i < runs.size(); i++) {
            BaselineRun run = runs.get(i);
            sb.append("      {\n");
            sb.append("        \"fixture\": ").append(json(run.fixture())).append(",\n");
            sb.append("        \"runIndex\": ").append(run.runIndex()).append(",\n");
            sb.append("        \"stdoutPath\": ").append(json(run.stdoutPath().toString())).append(",\n");
            sb.append("        \"stderrPath\": ").append(json(run.stderrPath().toString())).append(",\n");
            sb.append("        \"exitCode\": ").append(run.exitCode()).append(",\n");
            sb.append("        \"durationMillis\": ").append(run.durationMillis()).append(",\n");
            sb.append("        \"calcMillis\": ").append(run.calcMillis() == null ? "null" : run.calcMillis()).append(",\n");
            sb.append("        \"parsedMatrixThreadTimingLines\": ");
            appendStringArray(sb, run.parsedMatrixThreadTimingLines());
            sb.append("\n      }");
            if (i + 1 < runs.size()) {
                sb.append(',');
            }
            sb.append('\n');
        }
        sb.append("    ]");
    }

    private static void appendPathArray(StringBuilder sb, List<Path> paths) {
        appendStringArray(sb, paths.stream().map(Path::toString).toList());
    }

    private static void appendStringArray(StringBuilder sb, List<String> values) {
        sb.append('[');
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(json(values.get(i)));
        }
        sb.append(']');
    }

    private static String json(String value) {
        if (value == null) {
            return "null";
        }
        StringBuilder sb = new StringBuilder(value.length() + 2);
        sb.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    private record NativeBuildCapture(
        String fixture,
        Path manifestPath,
        List<NativeBuildTargetCapture> targets,
        long jarLibrarySizeBytes
    ) {}

    private record NativeBuildTargetCapture(
        String target,
        String generatedCPath,
        String generatedHeaderPath,
        String generatedLibraryPath,
        String nativeCompilerCommandLine,
        long generatedLibrarySizeBytes,
        int compilerExitCode
    ) {}

    private record BaselineRun(
        String fixture,
        int runIndex,
        Path stdoutPath,
        Path stderrPath,
        int exitCode,
        long durationMillis,
        Long calcMillis,
        List<String> parsedMatrixThreadTimingLines
    ) {}
}
