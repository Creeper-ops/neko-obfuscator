package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.ByteArrayOutputStream;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class ControlFlowFlatteningAlgebraicAuditTest {
    @Test
    void symbolicAuditRecognizesSelfCancelingAndLinearKeyShapes() {
        MethodNode method = syntheticLeakingMethod();
        List<Finding> findings = AlgebraicAudit.audit("Synthetic", method);

        assertTrue(
            findings.stream().anyMatch(f -> f.kind() == FindingKind.XOR_SELF_CANCELLATION),
            () -> "expected xor self-cancellation finding, got " + findings
        );
        assertTrue(
            findings.stream().anyMatch(f -> f.kind() == FindingKind.LINEAR_KEY_OVERWRITE),
            () -> "expected linear key overwrite finding, got " + findings
        );
    }

    @Test
    void cffOutputDoesNotExposeLinearOrSelfCancelingDispatcherAlgebra()
        throws Exception {
        Path projectRoot = Path.of(
            System.getProperty("neko.test.projectRoot", System.getProperty("user.dir"))
        );
        Path work = recreateWork(projectRoot.resolve("build/tmp/neko-test-cff-audit"));
        Path source = work.resolve("CffAuditShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("cff-audit-shapes.jar");
        writeJar(inputJar, classes, "CffAuditShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("cff-audit-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);
        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("CFF AUDIT OK"), obfuscated);
        Path tamperedJar = work.resolve("cff-audit-shapes-obf-tampered.jar");
        tamperMainMethodCode(outputJar, tamperedJar);
        assertTamperedJarPoisonsProtectedFlow(tamperedJar);
        assertClassIntegrityCodeRootPoisonsWithoutStandaloneVerifier(outputJar);
        assertGeneratedCffPoisonDoesNotThrow(outputJar);
        assertRuntimeTokenDecodingUsesClassKeyTables(outputJar);
        assertStepMaterialHelperUsesLiveKeyTableDispatch(outputJar);

        List<Finding> findings = auditJar(outputJar);
        assertFalse(
            findings.isEmpty(),
            "audit fixture did not exercise CFF integer dispatcher/key algebra"
        );
        List<Finding> rejected = findings
            .stream()
            .filter(Finding::rejectsVariant)
            .toList();
        assertTrue(
            rejected.isEmpty(),
            () -> "CFF emitted algebraically collapsible dispatcher/key shapes:\n" +
                summarize(rejected)
        );
    }

    @Test
    void packedObjectArrayMethodTamperFailsClassCodeIntegrity()
        throws Exception {
        Path projectRoot = Path.of(
            System.getProperty("neko.test.projectRoot", System.getProperty("user.dir"))
        );
        Path work = recreateWork(projectRoot.resolve("build/tmp/neko-test-cff-packed-integrity"));
        Path source = work.resolve("CffAuditShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("cff-audit-shapes.jar");
        writeJar(inputJar, classes, "CffAuditShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("cff-audit-shapes-obf.jar");
        runPackedObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);
        assertEquals(original, obfuscated);

        Path tamperedJar = work.resolve("cff-audit-shapes-obf-packed-tampered.jar");
        tamperFirstPackedApplicationMethod(outputJar, tamperedJar);
        assertTamperedJarPoisonsProtectedFlow(tamperedJar);
        assertGeneratedCffPoisonDoesNotThrow(outputJar);
    }

    @Test
    void directStaticProtectedMethodPatchFailsClassCodeIntegrity()
        throws Exception {
        Path projectRoot = Path.of(
            System.getProperty("neko.test.projectRoot", System.getProperty("user.dir"))
        );
        Path work = recreateWork(projectRoot.resolve("build/tmp/neko-test-cff-direct-integrity"));
        Path source = work.resolve("CffAuditShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("cff-audit-shapes.jar");
        writeJar(inputJar, classes, "CffAuditShapes");

        Path outputJar = work.resolve("cff-audit-shapes-obf.jar");
        runPackedObfuscation(inputJar, outputJar);
        assertTrue(runJar(outputJar).contains("CFF AUDIT OK"));

        Path tamperedJar = work.resolve("cff-audit-shapes-obf-direct-tampered.jar");
        tamperFirstStaticBooleanApplicationMethod(outputJar, tamperedJar);
        assertTamperedJarPoisonsProtectedFlow(tamperedJar);
        assertGeneratedCffPoisonDoesNotThrow(outputJar);
    }

    @Test
    void wrongClassIntegrityLoadOrderPoisonsKeyTable()
        throws Exception {
        Path projectRoot = Path.of(
            System.getProperty("neko.test.projectRoot", System.getProperty("user.dir"))
        );
        Path work = recreateWork(projectRoot.resolve("build/tmp/neko-test-cff-order-poison"));
        Path source = work.resolve("CffAuditShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("cff-audit-shapes.jar");
        writeJar(inputJar, classes, "CffAuditShapes");

        Path outputJar = work.resolve("cff-audit-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        assertTrue(runJar(outputJar).contains("CFF AUDIT OK"));
        assertGeneratedCffPoisonDoesNotThrow(outputJar);

        assertWrongPreloadPoisonsMain(outputJar, "CffAuditPeerB", "CffAuditShapes");
    }

    @Test
    void validationSinkHardeningRemovesPlainStringEqualsTarget()
        throws Exception {
        Path projectRoot = Path.of(
            System.getProperty("neko.test.projectRoot", System.getProperty("user.dir"))
        );
        Path work = recreateWork(projectRoot.resolve("build/tmp/neko-test-validation-sink"));
        Path source = work.resolve("ValidationSinkShape.java");
        Files.writeString(source, validationSinkSourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("validation-sink-shape.jar");
        writeJar(inputJar, classes, "ValidationSinkShape");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("validation-sink-shape-obf.jar");
        runValidationSinkObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("VALIDATION SINK OK"), obfuscated);
        assertValidationSinkUsesKeyedTag(outputJar);
    }

    private static MethodNode syntheticLeakingMethod() {
        MethodNode method = new MethodNode(
            Opcodes.ACC_STATIC,
            "synthetic",
            "()I",
            null,
            null
        );
        InsnList insns = method.instructions;
        LabelNode ok = new LabelNode();
        LabelNode poison = new LabelNode();

        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new LdcInsnNode(0x11223344));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 10));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 10));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new LdcInsnNode(0x55667788));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 8));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new LdcInsnNode(0x36FD211E));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 11));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 11));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new LdcInsnNode(0x36FD211E));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new LookupSwitchInsnNode(poison, new int[] {0}, new LabelNode[] {ok}));
        insns.add(ok);
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(poison);
        insns.add(new InsnNode(Opcodes.ICONST_M1));
        insns.add(new InsnNode(Opcodes.IRETURN));

        method.maxLocals = 12;
        method.maxStack = 4;
        return method;
    }

    private static List<Finding> auditJar(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        List<Finding> findings = new ArrayList<>();
        for (var clazz : input.classes()) {
            ClassNode node = clazz.asmNode();
            for (var method : node.methods) {
                findings.addAll(AlgebraicAudit.audit(node.name, method));
            }
        }
        return findings;
    }

    private static void tamperMainMethodCode(Path inputJar, Path outputJar)
        throws Exception {
        boolean[] tampered = { false };
        try (JarFile jar = new JarFile(inputJar.toFile());
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar.toFile()))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] data;
                try (var in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (!tampered[0] && entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassNode node = new ClassNode();
                    reader.accept(node, ClassReader.EXPAND_FRAMES);
                    MethodNode target = null;
                    for (MethodNode method : node.methods) {
                        if ("main".equals(method.name)
                            && method.instructions != null
                            && method.instructions.size() > 0) {
                            target = method;
                            break;
                        }
                    }
                    if (target != null) {
                        target.instructions.insert(new InsnNode(Opcodes.NOP));
                        ClassWriter writer = new ClassWriter(0);
                        node.accept(writer);
                        data = writer.toByteArray();
                        tampered[0] = true;
                    }
                }
                JarEntry out = new JarEntry(entry.getName());
                jos.putNextEntry(out);
                jos.write(data);
                jos.closeEntry();
            }
        }
        assertTrue(tampered[0], "tamper fixture did not find a main method");
    }

    private static void tamperFirstPackedApplicationMethod(Path inputJar, Path outputJar)
        throws Exception {
        boolean[] tampered = { false };
        try (JarFile jar = new JarFile(inputJar.toFile());
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar.toFile()))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                byte[] data;
                try (var in = jar.getInputStream(entry)) {
                    data = in.readAllBytes();
                }
                if (!tampered[0] && entry.getName().endsWith(".class")) {
                    ClassReader reader = new ClassReader(data);
                    ClassNode node = new ClassNode();
                    reader.accept(node, ClassReader.EXPAND_FRAMES);
                    MethodNode target = null;
                    for (MethodNode method : node.methods) {
                        if (method.name.startsWith("__neko_")) continue;
                        if (!method.desc.startsWith("([Ljava/lang/Object;)")) continue;
                        if (method.instructions == null || method.instructions.size() == 0) continue;
                        target = method;
                        break;
                    }
                    if (target != null) {
                        target.instructions.insert(new InsnNode(Opcodes.NOP));
                        ClassWriter writer = new ClassWriter(0);
                        node.accept(writer);
                        data = writer.toByteArray();
                        tampered[0] = true;
                    }
                }
                JarEntry out = new JarEntry(entry.getName());
                jos.putNextEntry(out);
                jos.write(data);
                jos.closeEntry();
            }
        }
        assertTrue(tampered[0], "tamper fixture did not find a packed Object[] application method");
    }

    private static void tamperFirstStaticBooleanApplicationMethod(Path inputJar, Path outputJar)
        throws Exception {
        boolean[] tampered = { false };
        try (JarFile jar = new JarFile(inputJar.toFile());
             JarOutputStream jos = new JarOutputStream(new FileOutputStream(outputJar.toFile()))) {
            var entries = jar.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                jos.putNextEntry(new JarEntry(entry.getName()));
                byte[] bytes = jar.getInputStream(entry).readAllBytes();
                if (!tampered[0] && entry.getName().endsWith(".class")) {
                    ClassNode node = new ClassNode();
                    new ClassReader(bytes).accept(node, 0);
                    for (MethodNode method : node.methods) {
                        if ((method.access & Opcodes.ACC_STATIC) == 0) continue;
                        if (!method.desc.endsWith(")Z")) continue;
                        if ("main".equals(method.name) || "<clinit>".equals(method.name)) continue;
                        if (method.name.startsWith("__neko_")) continue;
                        if (method.instructions == null || method.instructions.size() == 0) continue;
                        method.instructions.clear();
                        method.instructions.add(new InsnNode(Opcodes.ICONST_1));
                        method.instructions.add(new InsnNode(Opcodes.IRETURN));
                        method.tryCatchBlocks.clear();
                        method.localVariables = null;
                        method.maxStack = Math.max(method.maxStack, 1);
                        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
                        node.accept(writer);
                        bytes = writer.toByteArray();
                        tampered[0] = true;
                        break;
                    }
                }
                jos.write(bytes);
                jos.closeEntry();
            }
        }
        assertTrue(tampered[0], "tamper fixture did not find a static boolean application method");
    }

    private static void assertTamperedJarPoisonsProtectedFlow(Path jar) throws Exception {
        Process process = new ProcessBuilder(
            "java",
            "-XX:-UsePerfData",
            "-jar",
            jar.toString()
        ).redirectErrorStream(true).start();
        boolean exited = process.waitFor(30, TimeUnit.SECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "tampered command timed out");
        assertTrue(
            process.exitValue() != 0 || !output.contains("CFF AUDIT OK"),
            "tampered jar unexpectedly preserved the protected result:\n" + output
        );
    }

    private static void assertClassIntegrityCodeRootPoisonsWithoutStandaloneVerifier(Path jar)
        throws Exception {
        JarInput input = new JarInput(jar);
        boolean sawClassIntegrityRootHelper = false;
        for (var clazz : input.classes()) {
            for (MethodNode method : clazz.asmNode().methods) {
                assertFalse(
                    isStandaloneClassCodeVerifier(method),
                    "class-code integrity must be rooted in the class-integrity helper, not a standalone verifier"
                );
                if (!isClassIntegrityCodeRootHelper(method)) continue;
                sawClassIntegrityRootHelper = true;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    assertTrue(
                        insn.getOpcode() != Opcodes.ATHROW,
                        "class-integrity class-code root must poison key material instead of throwing"
                    );
                    if (insn instanceof TypeInsnNode type && type.getOpcode() == Opcodes.NEW) {
                        assertTrue(
                            !"java/lang/IllegalStateException".equals(type.desc),
                            "class-integrity class-code root must not construct a manual mismatch exception"
                        );
                    }
                }
            }
        }
        assertTrue(sawClassIntegrityRootHelper, "class-integrity class-code root helper was not generated");
    }

    private static void assertGeneratedCffPoisonDoesNotThrow(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (var clazz : input.classes()) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                boolean constructsIllegalState = false;
                boolean throwsValue = false;
                for (
                    AbstractInsnNode insn = method.instructions.getFirst();
                    insn != null;
                    insn = insn.getNext()
                ) {
                    if (insn instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.NEW
                        && "java/lang/IllegalStateException".equals(type.desc)) {
                        constructsIllegalState = true;
                    }
                    if (insn.getOpcode() == Opcodes.ATHROW) {
                        throwsValue = true;
                    }
                }
                assertFalse(
                    constructsIllegalState && throwsValue,
                    "generated CFF output must divert poison flow instead of throwing IllegalStateException in "
                        + clazz.name() + "." + method.name + method.desc
                );
            }
        }
    }

    private static boolean isStandaloneClassCodeVerifier(MethodNode method) {
        if (!"(Ljava/lang/Class;JJ)J".equals(method.desc) || method.instructions == null) return false;
        return methodContainsClassResourceRead(method);
    }

    private static boolean isClassIntegrityCodeRootHelper(MethodNode method) {
        if (!"(IJJLjava/lang/Class;JJ)J".equals(method.desc) || method.instructions == null) return false;
        return methodContainsClassResourceRead(method);
    }

    private static boolean methodContainsClassResourceRead(MethodNode method) {
        boolean sawClassResource = false;
        boolean sawReadAllBytes = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                && "java/lang/Class".equals(call.owner)
                && "getResourceAsStream".equals(call.name)
                && "(Ljava/lang/String;)Ljava/io/InputStream;".equals(call.desc)) {
                sawClassResource = true;
            }
            if (insn instanceof MethodInsnNode call
                && "java/io/InputStream".equals(call.owner)
                && "readAllBytes".equals(call.name)
                && "()[B".equals(call.desc)) {
                sawReadAllBytes = true;
            }
        }
        return sawClassResource && sawReadAllBytes;
    }

    private static void assertWrongPreloadPoisonsMain(Path jar, String preloadClass, String mainClass)
        throws Exception {
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] { jar.toUri().toURL() },
            ClassLoader.getPlatformClassLoader()
        )) {
            Class.forName(preloadClass, true, loader);
            Class<?> main = Class.forName(mainClass, true, loader);
            Method entry = main.getMethod("main", String[].class);
            PrintStream originalOut = System.out;
            ByteArrayOutputStream captured = new ByteArrayOutputStream();
            Throwable failure = null;
            try {
                System.setOut(new PrintStream(captured, true, StandardCharsets.UTF_8));
                entry.invoke(null, (Object) new String[0]);
            } catch (Throwable thrown) {
                failure = thrown;
            } finally {
                System.setOut(originalOut);
            }
            String output = captured.toString(StandardCharsets.UTF_8);
            assertTrue(
                failure != null || !output.contains("CFF AUDIT OK"),
                "wrong class-load order unexpectedly preserved the protected result:\n" + output
            );
        }
    }

    private static void assertRuntimeTokenDecodingUsesClassKeyTables(Path jar)
        throws Exception {
        JarInput input = new JarInput(jar);
        boolean sawIntTableField = false;
        boolean sawObjectSidecarField = false;
        boolean sawRuntimeIntTableLoad = false;
        boolean sawRuntimeSidecarHelperCall = false;
        boolean sawSidecarHelperUpdate = false;
        for (var clazz : input.classes()) {
            for (var field : clazz.asmNode().fields) {
                if ("[I".equals(field.desc)) {
                    sawIntTableField = true;
                }
                if ("[Ljava/lang/Object;".equals(field.desc)) {
                    sawObjectSidecarField = true;
                }
            }
            for (var method : clazz.asmNode().methods) {
                if (method.instructions == null || "<clinit>".equals(method.name)) {
                    continue;
                }
                for (
                    AbstractInsnNode insn = method.instructions.getFirst();
                    insn != null;
                    insn = insn.getNext()
                ) {
                    if (insn instanceof FieldInsnNode field
                        && field.getOpcode() == Opcodes.GETSTATIC
                        && "[I".equals(field.desc)
                        && (hasNearbyIntArrayLoad(field) || hasNearbyEncryptedTokenHelperCall(field))) {
                            sawRuntimeIntTableLoad = true;
                    }
                    if (insn instanceof TypeInsnNode type
                        && type.getOpcode() == Opcodes.CHECKCAST
                        && "[I".equals(type.desc)) {
                        sawRuntimeIntTableLoad = true;
                    }
                    if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(clazz.asmNode().name)
                        && "([Ljava/lang/Object;IIIIIIIII)I".equals(call.desc)) {
                        sawRuntimeSidecarHelperCall = true;
                    }
                    if (insn instanceof MethodInsnNode call
                        && call.getOpcode() == Opcodes.INVOKESTATIC
                        && call.owner.equals(clazz.asmNode().name)
                        && "([I[Ljava/lang/Object;IIIIIIIIIIIIIIII)I".equals(call.desc)) {
                        sawRuntimeIntTableLoad = true;
                    }
                }
                if (updatesSidecarCell(method)) {
                    sawSidecarHelperUpdate = true;
                }
            }
        }
        assertFalse(sawIntTableField, "CFF class key int[] table should be coalesced into the Object[] sidecar");
        assertTrue(sawObjectSidecarField, "CFF class should register an Object[] sidecar key table");
        assertTrue(sawRuntimeIntTableLoad, "CFF runtime token decode did not use the int[] class key table");
        assertTrue(
            sawRuntimeSidecarHelperCall || sawSidecarHelperUpdate,
            "CFF runtime token decode did not use the Object[] sidecar path"
        );
        assertTrue(sawSidecarHelperUpdate, "CFF sidecar helper did not update the Object[] sidecar key cell");
    }

    private static void assertStepMaterialHelperUsesLiveKeyTableDispatch(Path jar)
        throws Exception {
        assertStepMaterialHelperUsesRuntimeSources(jar);
        try (URLClassLoader loader = new URLClassLoader(
            new URL[] {jar.toUri().toURL()},
            ClassLoader.getPlatformClassLoader()
        )) {
            Class<?> clazz = Class.forName("CffAuditShapes", true, loader);
            Object[] carrier = cffObjectCarrier(clazz);
            Method helper = cffStepMaterialHelper(clazz);
            StepMaterialResult zero = invokeStepMaterialHelper(
                helper,
                carrier,
                0L,
                0,
                0,
                0
            );
            assertTrue(
                zero.hasNonZeroState(),
                "step-material helper did not execute a materialized row"
            );
            StepMaterialResult live = invokeStepMaterialHelper(
                helper,
                carrier,
                0x1122334455667788L,
                0x13579BDF,
                0x2468ACE0,
                0x10203040
            );
            assertTrue(
                !zero.equals(live),
                "step-material helper output did not depend on live key/control state"
            );
            StepMaterialResult pooled = invokeStepMaterialHelperFromThreadPool(
                helper,
                carrier,
                0x1122334455667788L,
                0x13579BDF,
                0x2468ACE0,
                0x10203040
            );
            assertTrue(
                pooled.hasNonZeroState(),
                "thread-pool path did not execute the step-material helper"
            );
        }
    }

    private static void assertStepMaterialHelperUsesRuntimeSources(Path jar)
        throws Exception {
        JarInput input = new JarInput(jar);
        boolean sawCurrentThread = false;
        boolean sawIdentityHash = false;
        boolean sawThreadName = false;
        boolean sawStackTrace = false;
        boolean sawStackElementHash = false;
        for (var clazz : input.classes()) {
            for (var method : clazz.asmNode().methods) {
                if (!"(JIII[Ljava/lang/Object;I[J)J".equals(method.desc)) {
                    continue;
                }
                for (
                    AbstractInsnNode insn = method.instructions.getFirst();
                    insn != null;
                    insn = insn.getNext()
                ) {
                    if (!(insn instanceof MethodInsnNode call)) {
                        continue;
                    }
                    sawCurrentThread |= call.owner.equals("java/lang/Thread")
                        && call.name.equals("currentThread")
                        && call.desc.equals("()Ljava/lang/Thread;");
                    sawIdentityHash |= call.owner.equals("java/lang/System")
                        && call.name.equals("identityHashCode")
                        && call.desc.equals("(Ljava/lang/Object;)I");
                    sawThreadName |= call.owner.equals("java/lang/Thread")
                        && call.name.equals("getName")
                        && call.desc.equals("()Ljava/lang/String;");
                    sawStackTrace |= call.owner.equals("java/lang/Thread")
                        && call.name.equals("getStackTrace")
                        && call.desc.equals("()[Ljava/lang/StackTraceElement;");
                    sawStackElementHash |= call.owner.equals("java/lang/StackTraceElement")
                        && call.name.equals("hashCode")
                        && call.desc.equals("()I");
                }
            }
        }
        assertTrue(sawCurrentThread, "step-material helper does not read current thread");
        assertTrue(sawIdentityHash, "step-material helper does not fold thread identity");
        assertTrue(sawThreadName, "step-material helper does not fold thread name");
        assertTrue(sawStackTrace, "step-material helper does not read stack trace");
        assertTrue(sawStackElementHash, "step-material helper does not fold stack frame hash");
    }

    private static Object[] cffObjectCarrier(Class<?> clazz) throws Exception {
        List<Field> carriers = new ArrayList<>();
        for (Field field : clazz.getDeclaredFields()) {
            if (field.getType().equals(Object[].class)
                && Modifier.isStatic(field.getModifiers())) {
                carriers.add(field);
            }
        }
        assertEquals(1, carriers.size(), "expected one generated Object[] carrier field");
        Field carrier = carriers.get(0);
        carrier.setAccessible(true);
        return (Object[]) carrier.get(null);
    }

    private static Method cffStepMaterialHelper(Class<?> clazz) {
        Class<?>[] expected = new Class<?>[] {
            long.class,
            int.class,
            int.class,
            int.class,
            Object[].class,
            int.class,
            long[].class
        };
        List<Method> helpers = new ArrayList<>();
        for (Method method : clazz.getDeclaredMethods()) {
            if (method.getReturnType().equals(long.class)
                && java.util.Arrays.equals(method.getParameterTypes(), expected)) {
                helpers.add(method);
            }
        }
        assertEquals(1, helpers.size(), "expected one generated step-material helper");
        Method helper = helpers.get(0);
        helper.setAccessible(true);
        return helper;
    }

    private static StepMaterialResult invokeStepMaterialHelper(
        Method helper,
        Object[] carrier,
        long key,
        int guard,
        int path,
        int block
    ) throws Exception {
        long[] out = new long[3];
        long resultKey = (Long) helper.invoke(
            null,
            key,
            guard,
            path,
            block,
            carrier,
            0,
            out
        );
        return new StepMaterialResult(resultKey, out[0], out[1]);
    }

    private static StepMaterialResult invokeStepMaterialHelperFromThreadPool(
        Method helper,
        Object[] carrier,
        long key,
        int guard,
        int path,
        int block
    ) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "neko-cff-step-material-proof");
            thread.setDaemon(true);
            return thread;
        });
        try {
            Future<StepMaterialResult> future = executor.submit(
                () -> invokeStepMaterialHelper(
                    helper,
                    carrier,
                    key,
                    guard,
                    path,
                    block
                )
            );
            return future.get(30, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }
    }

    private static boolean hasNearbyIntArrayLoad(AbstractInsnNode start) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = start.getNext();
            scan != null && scanned++ < 96;
            scan = scan.getNext()
        ) {
            if (scan.getOpcode() == Opcodes.IALOAD) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbySidecarHelperCall(AbstractInsnNode start) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = start.getNext();
            scan != null && scanned++ < 24;
            scan = scan.getNext()
        ) {
            if (scan instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && "([Ljava/lang/Object;IIIIIIIII)I".equals(call.desc)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasNearbyEncryptedTokenHelperCall(AbstractInsnNode start) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = start.getNext();
            scan != null && scanned++ < 32;
            scan = scan.getNext()
        ) {
            if (scan instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && ("([I[Ljava/lang/Object;IIIIIIIIIIIIIIII)I".equals(call.desc)
                    || "([Ljava/lang/Object;IIII)I".equals(call.desc)
                    || "(IIII)I".equals(call.desc))) {
                return true;
            }
        }
        return false;
    }

    private static boolean updatesSidecarCell(MethodNode method) {
        if (!("([Ljava/lang/Object;IIIIIIIII)I".equals(method.desc)
            || "([Ljava/lang/Object;IIII)I".equals(method.desc))
            || method.instructions == null) {
            return false;
        }
        boolean sawAaload = false;
        boolean sawLongCellCast = false;
        boolean sawAtomicLongCellCast = false;
        boolean sawAtomicLongRead = false;
        boolean sawAtomicLongWrite = false;
        int stores = 0;
        for (
            AbstractInsnNode scan = method.instructions.getFirst();
            scan != null;
            scan = scan.getNext()
        ) {
            if (scan.getOpcode() == Opcodes.AALOAD) {
                sawAaload = true;
            }
            if (scan instanceof TypeInsnNode type
                && scan.getOpcode() == Opcodes.CHECKCAST
                && "java/lang/Long".equals(type.desc)) {
                sawLongCellCast = true;
            }
            if (scan instanceof TypeInsnNode type
                && scan.getOpcode() == Opcodes.CHECKCAST
                && "java/util/concurrent/atomic/AtomicLong".equals(type.desc)) {
                sawAtomicLongCellCast = true;
            }
            if (scan instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/util/concurrent/atomic/AtomicLong".equals(call.owner)
                && ("get".equals(call.name) || "getPlain".equals(call.name))
                && "()J".equals(call.desc)) {
                sawAtomicLongRead = true;
            }
            if (scan instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/util/concurrent/atomic/AtomicLong".equals(call.owner)
                && ("lazySet".equals(call.name) || "set".equals(call.name) || "setPlain".equals(call.name))
                && "(J)V".equals(call.desc)) {
                sawAtomicLongWrite = true;
            }
            if (scan.getOpcode() == Opcodes.AASTORE) {
                stores++;
            }
        }
        return sawAaload
            && ((sawLongCellCast && stores >= 1)
                || (sawAtomicLongCellCast && sawAtomicLongRead && sawAtomicLongWrite));
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        runObfuscation(input, output, false);
    }

    private void runPackedObfuscation(Path input, Path output) throws Exception {
        runObfuscation(input, output, true);
    }

    private void runValidationSinkObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("validationSinkHardening", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x515EED51A11L);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void runObfuscation(Path input, Path output, boolean packedParameters) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        if (packedParameters) {
            transforms.put("methodParameterObfuscation", new TransformConfig(true, 1.0));
        }
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x5EEDCFFAL);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private static void assertValidationSinkUsesKeyedTag(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("ValidationSinkShape");
        MethodNode check = null;
        for (MethodNode method : clazz.asmNode().methods) {
            if ("check".equals(method.name) && method.desc.startsWith("(Ljava/lang/String;")) {
                check = method;
                break;
            }
        }
        assertTrue(check != null, "missing validation sink fixture method");
        String helperName = null;
        for (AbstractInsnNode insn = check.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && "swordfish-validated-flow".equals(ldc.cst)) {
                throw new AssertionError("validation sink retained plaintext target in check method");
            }
            if (insn instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/String".equals(call.owner)
                && "equals".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                throw new AssertionError("validation sink retained String.equals compare");
            }
            if (insn instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKESTATIC
                && "ValidationSinkShape".equals(call.owner)
                && "(Ljava/lang/String;JJI)Z".equals(call.desc)) {
                helperName = call.name;
            }
        }
        assertTrue(helperName != null, "validation sink did not call keyed tag helper");
        assertValidationSinkHelperHasNoPlainTarget(clazz, helperName);
        assertValidationSinkUsesFormulaVariants(clazz);
        assertValidationSinkHasNoStandaloneTargetCarriers(clazz);
    }

    private static void assertValidationSinkHelperHasNoPlainTarget(L1Class clazz, String helperName) {
        MethodNode helper = null;
        for (MethodNode method : clazz.asmNode().methods) {
            if (helperName.equals(method.name) && "(Ljava/lang/String;JJI)Z".equals(method.desc)) {
                helper = method;
                break;
            }
        }
        assertTrue(helper != null, "missing validation sink helper body");
        for (AbstractInsnNode insn = helper.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && "swordfish-validated-flow".equals(ldc.cst)) {
                throw new AssertionError("validation sink helper retained plaintext target");
            }
            if (insn instanceof MethodInsnNode call
                && call.getOpcode() == Opcodes.INVOKEVIRTUAL
                && "java/lang/String".equals(call.owner)
                && "equals".equals(call.name)
                && "(Ljava/lang/Object;)Z".equals(call.desc)) {
                throw new AssertionError("validation sink helper retained String.equals compare");
            }
        }
    }

    private static void assertValidationSinkUsesFormulaVariants(L1Class clazz) {
        boolean sawVariantZero = false;
        boolean sawVariantOne = false;
        Set<String> protectedTargets = Set.of(
            "swordfish-validated-flow",
            "swordfish-variant-two"
        );
        for (MethodNode method : clazz.asmNode().methods) {
            if (!"(Ljava/lang/String;JJI)Z".equals(method.desc)) continue;
            sawVariantZero |= method.name.startsWith("__neko_vsink0$");
            sawVariantOne |= method.name.startsWith("__neko_vsink1$");
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && protectedTargets.contains(ldc.cst)) {
                    throw new AssertionError("validation sink variant helper retained plaintext target");
                }
            }
        }
        assertTrue(sawVariantZero, "validation sink did not emit formula variant 0 helper");
        assertTrue(sawVariantOne, "validation sink did not emit formula variant 1 helper");
    }

    private static void assertValidationSinkHasNoStandaloneTargetCarriers(L1Class clazz) {
        for (FieldNode field : clazz.asmNode().fields) {
            boolean generatedStatic = (field.access & Opcodes.ACC_SYNTHETIC) != 0
                && (field.access & Opcodes.ACC_STATIC) != 0;
            if (!generatedStatic) continue;
            assertFalse(
                "[B".equals(field.desc)
                    || "J".equals(field.desc)
                    || "Ljava/lang/String;".equals(field.desc)
                    || "[Ljava/lang/String;".equals(field.desc),
                "validation sink emitted standalone target carrier field: " + field.name + field.desc
            );
        }
    }

    private static Path recreateWork(Path work) throws Exception {
        if (Files.exists(work)) {
            try (var stream = Files.walk(work)) {
                for (Path path : stream.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            }
        }
        return Files.createDirectories(work);
    }

    private static void writeJar(Path jar, Path classes, String mainClass)
        throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Main-Class", mainClass);
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jar.toFile()), manifest)) {
            List<Path> classFiles = new ArrayList<>();
            try (var stream = Files.walk(classes)) {
                stream.filter(path -> path.toString().endsWith(".class")).forEach(classFiles::add);
            }
            for (Path classFile : classFiles) {
                String name = classes.relativize(classFile).toString().replace('\\', '/');
                jos.putNextEntry(new JarEntry(name));
                jos.write(Files.readAllBytes(classFile));
                jos.closeEntry();
            }
        }
    }

    private static String runJar(Path jar) throws Exception {
        return run(
            List.of("java", "-XX:-UsePerfData", "-jar", jar.toString()),
            Duration.ofSeconds(30)
        );
    }

    private static String run(List<String> command, Duration timeout)
        throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "command timed out: " + command);
        assertEquals(0, process.exitValue(), "command failed: " + command + "\n" + output);
        return output;
    }

    private static String summarize(List<Finding> findings) {
        int limit = Math.min(20, findings.size());
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            out.append(findings.get(i)).append('\n');
        }
        if (findings.size() > limit) {
            out.append("... ").append(findings.size() - limit).append(" more\n");
        }
        return out.toString();
    }

    private static String validationSinkSourceText() {
        return """
            public class ValidationSinkShape {
                public static void main(String[] args) {
                    if (!check("swordfish-validated-flow")) {
                        throw new AssertionError("accepted value rejected");
                    }
                    if (check("swordfish-validated-flaw")) {
                        throw new AssertionError("wrong value accepted");
                    }
                    if (!checkAlt("swordfish-variant-two")) {
                        throw new AssertionError("variant value rejected");
                    }
                    if (checkAlt("swordfish-variant-too")) {
                        throw new AssertionError("wrong variant accepted");
                    }
                    System.out.println("VALIDATION SINK OK");
                }

                static boolean check(String value) {
                    int noise = value.length() * 17;
                    if ((noise & 3) == 1) {
                        noise ^= 0x13579BDF;
                    }
                    return value.equals("swordfish-validated-flow");
                }

                static boolean checkAlt(String value) {
                    int noise = value.length() * 31;
                    if ((noise & 5) == 4) {
                        noise ^= 0x2468ACE0;
                    }
                    return value.equals("swordfish-variant-two");
                }
            }
            """;
    }

    private static String sourceText() {
        return """
            public class CffAuditShapes {
                public static void main(String[] args) {
                    CffAuditShapes shapes = new CffAuditShapes();
                    int a = shapes.value(7, 11);
                    int b = shapes.value(19, 5);
                    int c = shapes.nested(13);
                    int d = CffAuditPeerA.peer(7);
                    int e = CffAuditPeerB.peer(5);
                    String out = a + ":" + b + ":" + c + ":" + d + ":" + e;
                    System.out.println(out);
                    if (!check(out)) {
                        throw new AssertionError(out);
                    }
                    System.out.println("CFF AUDIT OK");
                }

                private static boolean check(String value) {
                    return value.equals("63:97:58:628:3104");
                }

                int value(int x, int y) {
                    int r = x * 3 + y;
                    for (int i = 0; i < 5; i++) {
                        if (((r ^ i) & 1) == 0) {
                            r += y + i;
                        } else {
                            r ^= x + i;
                        }
                    }
                    switch (r & 3) {
                        case 0:
                            return r + 17;
                        case 1:
                            return r - 9;
                        case 2:
                            return r ^ 0x55;
                        default:
                            return r + x - y;
                    }
                }

                int nested(int seed) {
                    int acc = seed;
                    for (int i = 0; i < 4; i++) {
                        switch ((acc + i) & 3) {
                            case 0:
                                acc += i * 7;
                                break;
                            case 1:
                                acc ^= i + 31;
                                break;
                            case 2:
                                acc -= i + 5;
                                break;
                            default:
                                acc += seed ^ i;
                                break;
                        }
                    }
                    return acc;
                }
            }

            class CffAuditPeerA {
                static int peer(int value) {
                    int out = value;
                    for (int i = 0; i < 4; i++) {
                        out = out * 3 + i;
                    }
                    return out + 43;
                }
            }

            class CffAuditPeerB {
                static int peer(int value) {
                    int out = value;
                    for (int i = 0; i < 4; i++) {
                        out = out * 5 - i;
                    }
                    return out + 17;
                }
            }
            """;
    }

    private enum FindingKind {
        XOR_SELF_CANCELLATION,
        ADDITIVE_SELF_CANCELLATION,
        LINEAR_KEY_OVERWRITE,
        AUDITED_INTEGER_ALGEBRA
    }

    private record Finding(
        FindingKind kind,
        String owner,
        String method,
        int instruction,
        String detail
    ) {
        boolean rejectsVariant() {
            return kind != FindingKind.AUDITED_INTEGER_ALGEBRA;
        }

        @Override
        public String toString() {
            return owner + "." + method + " @" + instruction + " " + kind + ": " + detail;
        }
    }

    private static final class AlgebraicAudit {
        private static List<Finding> audit(String owner, MethodNode method) {
            List<Finding> findings = new ArrayList<>();
            if (method.instructions == null || method.instructions.size() == 0) {
                return findings;
            }
            auditLinearKeyOverwrite(owner, method, findings);
            auditSymbolic(owner, method, findings);
            return findings;
        }

        private static void auditLinearKeyOverwrite(
            String owner,
            MethodNode method,
            List<Finding> findings
        ) {
            List<AbstractInsnNode> insns = realInstructions(method);
            int highLocalStart = Math.max(0, method.maxLocals - 8);
            for (int i = 5; i < insns.size(); i++) {
                AbstractInsnNode n0 = insns.get(i - 5);
                AbstractInsnNode n1 = insns.get(i - 4);
                AbstractInsnNode n2 = insns.get(i - 3);
                AbstractInsnNode n3 = insns.get(i - 2);
                AbstractInsnNode n4 = insns.get(i - 1);
                AbstractInsnNode n5 = insns.get(i);
                if (!(n0 instanceof VarInsnNode left) || left.getOpcode() != Opcodes.ILOAD) continue;
                if (!(n1 instanceof VarInsnNode right) || right.getOpcode() != Opcodes.ILOAD) continue;
                if (n2.getOpcode() != Opcodes.IXOR) continue;
                if (intConstant(n3) == null) continue;
                if (n4.getOpcode() != Opcodes.IXOR) continue;
                if (!(n5 instanceof VarInsnNode dst) || dst.getOpcode() != Opcodes.ISTORE) continue;
                if (dst.var < highLocalStart || left.var < highLocalStart || right.var < highLocalStart) continue;
                if (dst.var == left.var || dst.var == right.var) continue;
                findings.add(
                    new Finding(
                        FindingKind.LINEAR_KEY_OVERWRITE,
                        owner,
                        method.name + method.desc,
                        i,
                        "v" + dst.var + " = v" + left.var + " ^ v" + right.var +
                            " ^ const loses old v" + dst.var
                    )
                );
            }
        }

        private static void auditSymbolic(
            String owner,
            MethodNode method,
            List<Finding> findings
        ) {
            ArrayDeque<Expr> stack = new ArrayDeque<>();
            Map<Integer, Expr> locals = new HashMap<>();
            List<AbstractInsnNode> insns = realInstructions(method);
            for (int index = 0; index < insns.size(); index++) {
                AbstractInsnNode insn = insns.get(index);
                Integer constant = intConstant(insn);
                if (constant != null) {
                    stack.push(Expr.constant(constant));
                    continue;
                }
                int opcode = insn.getOpcode();
                try {
                    switch (opcode) {
                        case Opcodes.ILOAD -> {
                            int local = ((VarInsnNode) insn).var;
                            stack.push(locals.getOrDefault(local, Expr.local(local)));
                        }
                        case Opcodes.ISTORE -> locals.put(((VarInsnNode) insn).var, pop(stack));
                        case Opcodes.LLOAD -> stack.push(Expr.term("l" + ((VarInsnNode) insn).var, Set.of()));
                        case Opcodes.LSTORE -> pop(stack);
                        case Opcodes.L2I -> stack.push(Expr.term("l2i(" + pop(stack) + ")", Set.of()));
                        case Opcodes.LUSHR, Opcodes.LSHR, Opcodes.LSHL, Opcodes.LXOR -> binaryGeneric(stack, opcodeName(opcode));
                        case Opcodes.IXOR -> {
                            Expr right = pop(stack);
                            Expr left = pop(stack);
                            Set<String> repeated = left.repeatedXorTerms(right);
                            String cancellation = repeated.isEmpty()
                                ? null
                                : left + " ^ " + right + " repeats " + repeated;
                            stack.push(left.xor(right, cancellation));
                            findings.add(
                                new Finding(
                                    FindingKind.AUDITED_INTEGER_ALGEBRA,
                                    owner,
                                    method.name + method.desc,
                                    index,
                                    "ixor"
                                )
                            );
                        }
                        case Opcodes.IADD -> {
                            Expr right = pop(stack);
                            Expr left = pop(stack);
                            stack.push(Expr.op("add", left, right));
                            findings.add(audited(owner, method, index, "iadd"));
                        }
                        case Opcodes.ISUB -> {
                            Expr right = pop(stack);
                            Expr left = pop(stack);
                            if (left.equals(right) || left.addContains(right)) {
                                findings.add(
                                    new Finding(
                                        FindingKind.ADDITIVE_SELF_CANCELLATION,
                                        owner,
                                        method.name + method.desc,
                                        index,
                                        left + " - " + right
                                    )
                                );
                            }
                            stack.push(Expr.op("sub", left, right));
                            findings.add(audited(owner, method, index, "isub"));
                        }
                        case Opcodes.IMUL, Opcodes.IDIV, Opcodes.IREM, Opcodes.IAND,
                            Opcodes.IOR, Opcodes.ISHL, Opcodes.ISHR, Opcodes.IUSHR -> {
                            binaryGeneric(stack, opcodeName(opcode));
                            findings.add(audited(owner, method, index, opcodeName(opcode)));
                        }
                        case Opcodes.INEG -> stack.push(Expr.op("neg", pop(stack)));
                        case Opcodes.IINC -> locals.remove(((org.objectweb.asm.tree.IincInsnNode) insn).var);
                        case Opcodes.DUP -> stack.push(peek(stack));
                        case Opcodes.DUP2 -> stack.push(peek(stack));
                        case Opcodes.POP -> pop(stack);
                        case Opcodes.POP2 -> pop(stack);
                        case Opcodes.IRETURN, Opcodes.ARETURN, Opcodes.FRETURN -> pop(stack);
                        case Opcodes.LRETURN, Opcodes.DRETURN -> pop(stack);
                        case Opcodes.IFNULL, Opcodes.IFNONNULL, Opcodes.IFEQ, Opcodes.IFNE,
                            Opcodes.IFLT, Opcodes.IFGE, Opcodes.IFGT, Opcodes.IFLE -> pop(stack);
                        case Opcodes.IF_ICMPEQ, Opcodes.IF_ICMPNE, Opcodes.IF_ICMPLT,
                            Opcodes.IF_ICMPGE, Opcodes.IF_ICMPGT, Opcodes.IF_ICMPLE,
                            Opcodes.IF_ACMPEQ, Opcodes.IF_ACMPNE -> {
                            pop(stack);
                            pop(stack);
                        }
                        case Opcodes.TABLESWITCH, Opcodes.LOOKUPSWITCH -> {
                            Expr selector = pop(stack);
                            if (selector.xorCancellation() != null) {
                                findings.add(
                                    new Finding(
                                        FindingKind.XOR_SELF_CANCELLATION,
                                        owner,
                                        method.name + method.desc,
                                        index,
                                        selector.xorCancellation()
                                    )
                                );
                            }
                        }
                        default -> {
                            if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD) {
                                pop(stack);
                                pop(stack);
                                stack.push(Expr.unknown("arrayLoad"));
                            } else if (opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE) {
                                pop(stack);
                                pop(stack);
                                pop(stack);
                            }
                        }
                    }
                } catch (StackUnderflow ignored) {
                    stack.clear();
                }
            }
        }

        private static Finding audited(
            String owner,
            MethodNode method,
            int instruction,
            String detail
        ) {
            return new Finding(
                FindingKind.AUDITED_INTEGER_ALGEBRA,
                owner,
                method.name + method.desc,
                instruction,
                detail
            );
        }

        private static void binaryGeneric(ArrayDeque<Expr> stack, String op) {
            Expr right = pop(stack);
            Expr left = pop(stack);
            stack.push(Expr.op(op, left, right));
        }

        private static Expr pop(ArrayDeque<Expr> stack) {
            Expr value = stack.pollFirst();
            if (value == null) throw new StackUnderflow();
            return value;
        }

        private static Expr peek(ArrayDeque<Expr> stack) {
            Expr value = stack.peekFirst();
            if (value == null) throw new StackUnderflow();
            return value;
        }

        private static List<AbstractInsnNode> realInstructions(MethodNode method) {
            List<AbstractInsnNode> insns = new ArrayList<>();
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn.getOpcode() >= 0) {
                    insns.add(insn);
                }
            }
            return insns;
        }

        private static Integer intConstant(AbstractInsnNode insn) {
            int opcode = insn.getOpcode();
            return switch (opcode) {
                case Opcodes.ICONST_M1 -> -1;
                case Opcodes.ICONST_0 -> 0;
                case Opcodes.ICONST_1 -> 1;
                case Opcodes.ICONST_2 -> 2;
                case Opcodes.ICONST_3 -> 3;
                case Opcodes.ICONST_4 -> 4;
                case Opcodes.ICONST_5 -> 5;
                case Opcodes.BIPUSH, Opcodes.SIPUSH -> ((IntInsnNode) insn).operand;
                case Opcodes.LDC -> ((LdcInsnNode) insn).cst instanceof Integer i ? i : null;
                default -> null;
            };
        }

        private static String opcodeName(int opcode) {
            return "op" + opcode;
        }
    }

    private record Expr(
        boolean linearXor,
        TreeSet<String> xorTerms,
        int xorConstant,
        String op,
        List<Expr> args,
        String display,
        String xorCancellation
    ) {
        static Expr constant(int value) {
            return new Expr(true, new TreeSet<>(), value, "const", List.of(), "#" + value, null);
        }

        static Expr local(int local) {
            return term("v" + local, Set.of("v" + local));
        }

        static Expr unknown(String label) {
            return term(label + "#" + System.identityHashCode(new Object()), Set.of());
        }

        static Expr term(String term, Set<String> refs) {
            TreeSet<String> terms = new TreeSet<>();
            String token = token(term);
            terms.add(token);
            return new Expr(true, terms, 0, "term", List.of(), token, null);
        }

        static Expr op(String op, Expr... args) {
            StringBuilder display = new StringBuilder(op).append('(');
            for (int i = 0; i < args.length; i++) {
                if (i > 0) display.append(',');
                display.append(args[i]);
            }
            display.append(')');
            TreeSet<String> terms = new TreeSet<>();
            String token = token(display.toString());
            terms.add(token);
            return new Expr(true, terms, 0, op, List.of(args), token, null);
        }

        Expr xor(Expr other, String cancellation) {
            TreeSet<String> terms = new TreeSet<>(xorTerms);
            for (String term : other.xorTerms) {
                if (!terms.add(term)) {
                    terms.remove(term);
                }
            }
            return new Expr(
                true,
                terms,
                xorConstant ^ other.xorConstant,
                "xor",
                List.of(this, other),
                formatXor(terms, xorConstant ^ other.xorConstant),
                cancellation != null ? cancellation : firstNonNull(xorCancellation, other.xorCancellation)
            );
        }

        Set<String> repeatedXorTerms(Expr other) {
            if (!linearXor || !other.linearXor) return Set.of();
            Set<String> repeated = new LinkedHashSet<>(xorTerms);
            repeated.retainAll(other.xorTerms);
            repeated.removeIf(term -> term.startsWith("#"));
            return repeated;
        }

        boolean addContains(Expr right) {
            return "add".equals(op) && args.contains(right);
        }

        @Override
        public String toString() {
            return display;
        }

        private static String formatXor(TreeSet<String> terms, int constant) {
            List<String> pieces = new ArrayList<>(terms);
            if (constant != 0 || pieces.isEmpty()) {
                pieces.add("#" + constant);
            }
            return token(String.join("^", pieces));
        }

        private static String token(String text) {
            if (text.length() <= 96) return text;
            return text.substring(0, 64) + "~" + Integer.toHexString(text.hashCode());
        }

        private static String firstNonNull(String first, String second) {
            return first != null ? first : second;
        }
    }

    record StepMaterialResult(long key, long out0, long out1) {
        boolean hasNonZeroState() {
            return key != 0L || out0 != 0L || out1 != 0L;
        }
    }

    private static final class StackUnderflow extends RuntimeException {}
}
