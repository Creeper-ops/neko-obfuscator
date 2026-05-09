package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.config.ConfigParser;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.ir.lift.*;
import dev.nekoobfuscator.core.jar.*;
import dev.nekoobfuscator.core.pipeline.*;
import org.objectweb.asm.tree.*;
import org.junit.jupiter.api.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for NekoObfuscator.
 */
public class ObfuscationIntegrationTest {

    private static Path testJar;
    private static Path tempDir;

    @BeforeAll
    static void setup() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path testTmpRoot = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test"));
        tempDir = Files.createTempDirectory(testTmpRoot, "neko_test_");

        // Compile TestSample.java to a JAR
        Path srcFile = tempDir.resolve("TestSample.java");
        try (InputStream is = ObfuscationIntegrationTest.class.getResourceAsStream("/TestSample.java")) {
            if (is != null) {
                Files.copy(is, srcFile, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Path fallbackRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
                Path fallback = fallbackRoot.resolve("neko-test/src/test/resources/TestSample.java");
                assertTrue(Files.exists(fallback), "TestSample.java resource not found at classpath or fallback path: " + fallback);
                Files.copy(fallback, srcFile, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        // Compile
        Path classDir = tempDir.resolve("classes");
        Files.createDirectories(classDir);
        ProcessBuilder javac = new ProcessBuilder("javac", "-d", classDir.toString(), srcFile.toString());
        javac.redirectErrorStream(true);
        Process proc = javac.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "javac failed: " + output);

        // Create JAR
        testJar = tempDir.resolve("test-sample.jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Main-Class", "TestSample");

        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(testJar.toFile()), mf)) {
            Path classFile = classDir.resolve("TestSample.class");
            jos.putNextEntry(new JarEntry("TestSample.class"));
            jos.write(Files.readAllBytes(classFile));
            jos.closeEntry();
        }

        // Verify JAR is readable
        JarInput verifyInput = new JarInput(testJar);
        assertFalse(verifyInput.classes().isEmpty(), "Test JAR should contain classes");
    }

    @AfterAll
    static void cleanup() {
        // Leave temp dir for debugging
    }

    @Test
    void testJarRoundTrip() throws Exception {
        // Read JAR, write it back, verify identical behavior
        JarInput input = new JarInput(testJar);
        assertFalse(input.classes().isEmpty(), "No classes loaded");
        assertEquals(1, input.classes().size());
        assertEquals("TestSample", input.classes().get(0).name());

        // Write back
        Path outputJar = tempDir.resolve("roundtrip.jar");
        ClassHierarchy hierarchy = new ClassHierarchy();
        for (L1Class l1 : input.classes()) hierarchy.addClass(l1);
        new ClasspathResolver(List.of()).populateHierarchy(hierarchy);

        new JarOutput(hierarchy).write(outputJar, input.classes(), input.resources(), input.manifest());

        // Verify roundtripped JAR is readable and has same classes
        JarInput roundtripped = new JarInput(outputJar);
        assertEquals(input.classes().size(), roundtripped.classes().size(), "Roundtrip should preserve class count");
        assertEquals(input.classes().get(0).name(), roundtripped.classes().get(0).name());
    }

    @Test
    void testCFGConstruction() throws Exception {
        JarInput input = new JarInput(testJar);
        L1Class clazz = input.classes().get(0);
        L1Method fibonacci = clazz.findMethod("fibonacci", "(I)I");
        assertNotNull(fibonacci, "fibonacci method not found");

        ControlFlowGraph cfg = ControlFlowGraph.build(fibonacci);
        assertNotNull(cfg);
        assertTrue(cfg.blockCount() >= 2, "Expected at least 2 blocks in fibonacci CFG");
        assertNotNull(cfg.entryBlock());
    }

    @Test
    void testSSAConstruction() throws Exception {
        JarInput input = new JarInput(testJar);
        L1Class clazz = input.classes().get(0);
        L1Method fibonacci = clazz.findMethod("fibonacci", "(I)I");
        assertNotNull(fibonacci, "fibonacci method not found");

        // Test CFG first
        ControlFlowGraph cfg = ControlFlowGraph.build(fibonacci);
        assertNotNull(cfg);
        assertTrue(cfg.blockCount() >= 2, "Expected at least 2 blocks");

        // Test SSA with timeout protection
        L1ToL2Lifter lifter = new L1ToL2Lifter();
        SSAForm ssa = lifter.lift(fibonacci);
        assertNotNull(ssa);
        assertNotNull(ssa.cfg());
    }

    @Test
    void testStringEncryption() throws Exception {
        Path outputJar = tempDir.resolve("string-encrypted.jar");
        runObfuscation(testJar, outputJar, Map.of(
            "stringEncryption", new TransformConfig(true, 1.0)
        ));

        // Verify obfuscated JAR contains encrypted fields
        JarInput obfuscated = new JarInput(outputJar);
        L1Class clazz = obfuscated.classes().get(0);
        boolean hasEncFields = clazz.asmNode().fields.stream()
            .anyMatch(f -> f.name.startsWith("__e")
                && ("[B".equals(f.desc) || "Ljava/lang/String;".equals(f.desc)));
        assertTrue(hasEncFields, "No encrypted string metadata fields found after string encryption");
    }

    @Test
    void testNumberEncryption() throws Exception {
        Path outputJar = tempDir.resolve("number-encrypted.jar");
        runObfuscation(testJar, outputJar, Map.of(
            "numberEncryption", new TransformConfig(true, 1.0)
        ));

        // Just verify it produces a valid JAR
        JarInput obfuscated = new JarInput(outputJar);
        assertFalse(obfuscated.classes().isEmpty());
    }

    @Test
    void testNumberEncryptionAlgorithmsCoverPrimitiveConstantsWithoutNumberIndy() throws Exception {
        Path numericJar = compileSourceJar("NumericSample", """
            public class NumericSample {
                public static void main(String[] args) {
                    int i = 123456789;
                    long l = 0x1020304050607080L;
                    float f = 13.25f;
                    double d = -91.5d;
                    if (i == 123456789
                            && l == 0x1020304050607080L
                            && Float.floatToRawIntBits(f) == Float.floatToRawIntBits(13.25f)
                            && Double.doubleToRawLongBits(d) == Double.doubleToRawLongBits(-91.5d)) {
                        System.out.println("NUMERIC OK");
                    } else {
                        throw new AssertionError(i + ":" + l + ":" + f + ":" + d);
                    }
                }
            }
            """);

        Path xorJar = tempDir.resolve("number-xor-runtime.jar");
        runObfuscation(numericJar, xorJar, Map.of(
            "numberEncryption", new TransformConfig(true, 1.0, Map.of(
                "algorithm", "xor",
                "skipSmallLoopConstants", false
            ))
        ));
        assertTrue(runJar(xorJar).contains("NUMERIC OK"));
        assertFalse(hasNumberBootstrapInvoke(xorJar), "xor mode must not emit bsmNumber invokedynamic");
        assertFalse(hasClass(xorJar, "dev/nekoobfuscator/runtime/NekoKeyDerivation"));

        Path aesJar = tempDir.resolve("number-aes-runtime.jar");
        runObfuscation(numericJar, aesJar, Map.of(
            "numberEncryption", new TransformConfig(true, 1.0, Map.of(
                "algorithm", "aes",
                "skipSmallLoopConstants", false
            ))
        ));
        assertTrue(runJar(aesJar).contains("NUMERIC OK"));
        assertFalse(hasNumberBootstrapInvoke(aesJar), "aes mode must not emit bsmNumber invokedynamic");
        assertTrue(hasClass(aesJar, "dev/nekoobfuscator/runtime/NekoKeyDerivation"));
        assertTrue(hasFieldPrefix(aesJar, "NumericSample", "__neko_n"));
    }

    @Test
    void testControlFlowFlattening() throws Exception {
        Path outputJar = tempDir.resolve("cf-flattened.jar");
        runObfuscation(testJar, outputJar, Map.of(
            "controlFlowFlattening", new TransformConfig(true, 1.0)
        ));

        JarInput obfuscated = new JarInput(outputJar);
        assertFalse(obfuscated.classes().isEmpty());
    }

    @Test
    void testOutlinerHandlesTypedLocalsAndControlFlowOutput() throws Exception {
        Path typedJar = compileSourceJar("OutlinerTypedSample", """
            public class OutlinerTypedSample {
                public static void main(String[] args) {
                    emit("typed", 7L, 3.5d);
                    try {
                        risky(1);
                    } catch (IllegalArgumentException e) {
                        System.out.println(e.getMessage());
                    }
                    System.out.println("OUTLINER TYPED OK");
                }
                static void emit(String text, long count, double ratio) {
                    System.out.println(text);
                    System.out.println(count);
                    System.out.println(ratio);
                }
                static void risky(int value) {
                    if (value > 0) {
                        throw new IllegalArgumentException("try-catch-ok");
                    }
                }
            }
            """);

        Path outlinerJar = tempDir.resolve("outliner-typed.jar");
        runObfuscation(typedJar, outlinerJar, Map.of(
            "outliner", new TransformConfig(true, 1.0, Map.of("minBlockSize", 3))
        ));
        String outlinerOutput = runJar(outlinerJar);
        assertTrue(outlinerOutput.contains("OUTLINER TYPED OK"));
        assertTrue(outlinerOutput.contains("try-catch-ok"));
        assertTrue(hasMethodPrefix(outlinerJar, "OutlinerTypedSample", "__neko_o"));

        Path cffOutlinerJar = tempDir.resolve("outliner-typed-cff.jar");
        runObfuscation(typedJar, cffOutlinerJar, Map.of(
            "controlFlowFlattening", new TransformConfig(true, 1.0, Map.of(
                "dispatcherDepth", 2,
                "allowSwitchMethods", true,
                "maxApplicableInstructionCount", 400,
                "maxBranchCount", 64
            )),
            "outliner", new TransformConfig(true, 1.0, Map.of("minBlockSize", 3))
        ));
        String cffOutlinerOutput = runJar(cffOutlinerJar);
        assertTrue(cffOutlinerOutput.contains("OUTLINER TYPED OK"));
        assertTrue(cffOutlinerOutput.contains("try-catch-ok"));
    }

    @Test
    void testRuntimeInjectionIsRenamedAndControlFlowProcessed() throws Exception {
        Path outputJar = tempDir.resolve("runtime-api-obfuscated.jar");
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("renamer", new TransformConfig(true, 1.0, Map.of(
            "renameClasses", true,
            "renameMembers", true,
            "renameRuntime", true
        )));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0, Map.of(
            "dispatcherDepth", 2,
            "allowSwitchMethods", true,
            "maxApplicableInstructionCount", 500,
            "maxBranchCount", 96
        )));
        transforms.put("invokeDynamic", new TransformConfig(true, 1.0, Map.of(
            "keyDispatcher", true,
            "useControlFlowKey", false
        )));
        transforms.put("stringEncryption", new TransformConfig(true, 1.0, Map.of(
            "directRuntime", true,
            "keyDispatcher", true,
            "useControlFlowKey", false
        )));
        transforms.put("numberEncryption", new TransformConfig(true, 1.0, Map.of(
            "algorithm", "xor",
            "keyDispatcher", true,
            "useControlFlowKey", true,
            "skipSmallLoopConstants", false
        )));

        runObfuscation(testJar, outputJar, transforms);
        assertTrue(runJar(outputJar).contains("ALL TESTS PASSED"));

        JarInput input = new JarInput(outputJar);
        assertFalse(hasClass(outputJar, "dev/nekoobfuscator/runtime/NekoBootstrap"));
        assertTrue(input.classes().stream().anyMatch(clazz -> clazz.name().startsWith("r/")),
            "Expected renamed runtime package classes");

        Set<String> forbidden = Set.of(
            "NekoBootstrap", "NekoContext", "NekoKeyDerivation", "NekoStringDecryptor",
            "NekoFlowException", "NekoReturnFlowException", "decryptString", "bsmInvoke",
            "bsmString", "setCurrentFlowKey", "flowKey", "classKey", "MASTER_SEED"
        );
        for (L1Class clazz : input.classes()) {
            assertFalse(clazz.name().startsWith("dev/nekoobfuscator/runtime/"), clazz.name());
            ClassNode node = clazz.asmNode();
            boolean runtimeClass = clazz.name().startsWith("r/");
            if (runtimeClass) {
                assertNull(node.sourceFile, () -> "Runtime source file leaked for " + clazz.name());
                assertNull(node.sourceDebug, () -> "Runtime source debug leaked for " + clazz.name());
            }
            for (String token : forbidden) {
                assertFalse(clazz.name().contains(token), () -> "Forbidden runtime class token leaked: " + token);
                if (node.sourceFile != null) {
                    assertFalse(node.sourceFile.contains(token), () -> "Forbidden source token leaked: " + token);
                }
            }
            for (MethodNode method : node.methods) {
                for (String token : forbidden) {
                    assertFalse(method.name.contains(token),
                        () -> "Forbidden runtime method token leaked: " + token + " in " + clazz.name() + "." + method.name);
                }
                if (runtimeClass) {
                    assertTrue(method.localVariables == null || method.localVariables.isEmpty(),
                        () -> "Runtime local variable debug table leaked in " + clazz.name() + "." + method.name);
                }
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call) {
                        assertFalse(call.owner.startsWith("dev/nekoobfuscator/runtime/"),
                            () -> "Original runtime owner leaked in method call: " + call.owner);
                        for (String token : forbidden) {
                            assertFalse(call.name.contains(token),
                                () -> "Forbidden runtime call token leaked: " + token);
                        }
                    } else if (insn instanceof InvokeDynamicInsnNode indy && indy.bsm != null) {
                        assertFalse(indy.bsm.getOwner().startsWith("dev/nekoobfuscator/runtime/"),
                            () -> "Original runtime owner leaked in indy bootstrap: " + indy.bsm.getOwner());
                        for (String token : forbidden) {
                            assertFalse(indy.bsm.getName().contains(token),
                                () -> "Forbidden runtime bootstrap token leaked: " + token);
                        }
                    }
                }
            }
        }
    }

    @Test
    void testLocalStateInvokeStringAndFlowShape() throws Exception {
        Path invokeJar = compileSourceJar("LocalStateInvokeSample", """
            public class LocalStateInvokeSample {
                LocalStateInvokeSample() {
                    afterInit();
                }
                void afterInit() {
                    System.out.println("CTOR INDY OK");
                }
                static void helper() {
                    System.out.println("STATIC INDY OK");
                }
                public static void main(String[] args) {
                    new LocalStateInvokeSample();
                    helper();
                }
            }
            """);

        Path invokeOut = tempDir.resolve("local-state-invoke.jar");
        runObfuscation(invokeJar, invokeOut, Map.of(
            "invokeDynamic", new TransformConfig(true, 1.0, Map.of(
                "skipMethodsWithTryCatch", false,
                "skipMethodsWithSwitches", false,
                "skipPrimitiveLoopCalls", false,
                "wrapSpecialCalls", true,
                "useControlFlowKey", false
            ))
        ));
        assertTrue(runJar(invokeOut).contains("STATIC INDY OK"));
        assertTrue(constructorHasLocalIndy(invokeOut, "LocalStateInvokeSample"));
        assertFalse(hasIndyBootstrapOwnerPrefix(invokeOut, "dev/nekoobfuscator/runtime/"));

        Path stringJar = compileSourceJar("LocalStringSample", """
            public class LocalStringSample {
                public static void main(String[] args) {
                    System.out.println("STRING SINGLE OK");
                }
            }
            """);
        Path stringOut = tempDir.resolve("local-string.jar");
        runObfuscation(stringJar, stringOut, Map.of(
            "stringEncryption", new TransformConfig(true, 1.0, Map.of(
                "directRuntime", true,
                "keyDispatcher", true,
                "useControlFlowKey", false
            )),
            "invokeDynamic", new TransformConfig(true, 1.0, Map.of(
                "skipPrimitiveLoopCalls", false
            ))
        ));
        assertTrue(runJar(stringOut).contains("STRING SINGLE OK"));
        assertTrue(hasMethodPrefix(stringOut, "LocalStringSample", "__neko_s"));
        assertTrue(applicationCallsLocalStringCodec(stringOut, "LocalStringSample"));
        assertFalse(generatedMethodsContainIndy(stringOut, "LocalStringSample"));

        Path flowJar = compileSourceJar("FlowInvokeSample", """
            public class FlowInvokeSample {
                static int value(int x) {
                    return x + 1;
                }
                public static void main(String[] args) {
                    int total = 0;
                    for (int i = 0; i < 3; i++) {
                        total += value(i);
                    }
                    System.out.println(total);
                }
            }
            """);
        Path flowOut = tempDir.resolve("flow-invoke.jar");
        runObfuscation(flowJar, flowOut, Map.of(
            "controlFlowFlattening", new TransformConfig(true, 1.0, Map.of(
                "dispatcherDepth", 2,
                "dispatcherFragments", 2,
                "allowSwitchMethods", true,
                "maxApplicableInstructionCount", 500,
                "maxBranchCount", 64
            )),
            "invokeDynamic", new TransformConfig(true, 1.0, Map.of(
                "useControlFlowKey", true,
                "skipMethodsWithSwitches", false,
                "skipPrimitiveLoopCalls", false,
                "maxApplicableInstructionCount", 500,
                "maxBranchCount", 64
            ))
        ));
        assertTrue(runJar(flowOut).contains("6"));
    }

    private void runObfuscation(Path input, Path output, Map<String, TransformConfig> transforms)
            throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        config.setTransforms(new LinkedHashMap<>(transforms));
        config.keyConfig().setMasterSeed(12345678L);

        PassRegistry registry = new PassRegistry();

        ObfuscationPipeline pipeline = new ObfuscationPipeline(config, registry);
        pipeline.execute(input, output);
    }

    private String runJar(Path jar) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("java", "-jar", jar.toString());
        pb.redirectErrorStream(true);
        Process proc = pb.start();
        String output = new String(proc.getInputStream().readAllBytes());
        proc.waitFor();
        return output;
    }

    private Path compileSourceJar(String className, String source) throws Exception {
        Path srcDir = Files.createDirectories(tempDir.resolve("src-" + className));
        Path classDir = Files.createDirectories(tempDir.resolve("classes-" + className));
        Path srcFile = srcDir.resolve(className + ".java");
        Files.writeString(srcFile, source);

        ProcessBuilder javac = new ProcessBuilder("javac", "-d", classDir.toString(), srcFile.toString());
        javac.redirectErrorStream(true);
        Process proc = javac.start();
        String output = new String(proc.getInputStream().readAllBytes());
        int exitCode = proc.waitFor();
        assertEquals(0, exitCode, "javac failed: " + output);

        Path jar = tempDir.resolve(className + ".jar");
        Manifest mf = new Manifest();
        mf.getMainAttributes().putValue("Manifest-Version", "1.0");
        mf.getMainAttributes().putValue("Main-Class", className);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), mf)) {
            jos.putNextEntry(new JarEntry(className + ".class"));
            jos.write(Files.readAllBytes(classDir.resolve(className + ".class")));
            jos.closeEntry();
        }
        return jar;
    }

    private boolean hasNumberBootstrapInvoke(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && indy.bsm != null
                            && "bsmNumber".equals(indy.bsm.getName())) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasClass(Path jar, String className) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (className.equals(clazz.name())) return true;
        }
        return false;
    }

    private boolean hasFieldPrefix(Path jar, String className, String prefix) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            return clazz.asmNode().fields.stream().anyMatch(field -> field.name.startsWith(prefix));
        }
        return false;
    }

    private boolean hasMethodPrefix(Path jar, String className, String prefix) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            return clazz.asmNode().methods.stream().anyMatch(method -> method.name.startsWith(prefix));
        }
        return false;
    }

    private boolean hasAnyMethodPrefix(Path jar, String prefix) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (clazz.asmNode().methods.stream().anyMatch(method -> method.name.startsWith(prefix))) {
                return true;
            }
        }
        return false;
    }

    private boolean constructorHasLocalIndy(Path jar, String className) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (!"<init>".equals(method.name) || method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && indy.bsm != null
                            && className.equals(indy.bsm.getOwner())
                            && indy.bsm.getName().startsWith("__neko_b")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean hasIndyBootstrapOwnerPrefix(Path jar, String prefix) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && indy.bsm != null
                            && indy.bsm.getOwner().startsWith(prefix)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean applicationCallsLocalStringCodec(Path jar, String className) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.name.startsWith("__neko_") || method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof MethodInsnNode call
                            && className.equals(call.owner)
                            && call.name.startsWith("__neko_s")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private boolean generatedMethodsContainIndy(Path jar, String className) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (!method.name.startsWith("__neko_") || method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode) return true;
                }
            }
        }
        return false;
    }

    private boolean hasInvokeDynamicFlowMode(Path jar, String className) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            boolean hasLocalInvokeDynamic = false;
            boolean bootstrapReadsFlowKey = false;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && indy.bsmArgs != null
                            && indy.bsmArgs.length >= 7
                            && ("1".equals(String.valueOf(indy.bsmArgs[6]))
                                || (indy.bsmArgs[6] instanceof Number number && number.intValue() == 1))) {
                        return true;
                    }
                    if (insn instanceof InvokeDynamicInsnNode indy
                            && indy.bsm != null
                            && className.equals(indy.bsm.getOwner())
                            && indy.bsm.getName().startsWith("__neko_b")) {
                        hasLocalInvokeDynamic = true;
                    }
                    if (method.name.startsWith("__neko_b")
                            && insn instanceof MethodInsnNode call
                            && "dev/nekoobfuscator/runtime/NekoContext".equals(call.owner)
                            && "flowKey".equals(call.name)) {
                        bootstrapReadsFlowKey = true;
                    }
                }
            }
            return hasLocalInvokeDynamic && bootstrapReadsFlowKey;
        }
        return false;
    }

    private int countLookupSwitches(Path jar, String className) throws Exception {
        int count = 0;
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classes()) {
            if (!className.equals(clazz.name())) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LookupSwitchInsnNode) count++;
                }
            }
        }
        return count;
    }
}
