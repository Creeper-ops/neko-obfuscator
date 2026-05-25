package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JvmMethodParameterObfuscationIntegrationTest {
    @Test
    void methodParameterObfuscationPacksEligibleMethodsIntoObjectArray() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-method-parameters"));
        Path source = work.resolve("ParameterShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("parameter-shapes.jar");
        writeJar(inputJar, classes, "ParameterShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("parameter-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("PARAMETER OBF OK"), obfuscated);
        assertPackedDescriptors(outputJar);
        assertCallsUsePackedDescriptors(outputJar);
        assertCarrierIndexMarkersRemoved(outputJar);
        assertHiddenKeyCarrierReadsUseDecodedIndexes(outputJar);
        assertCarrierStoresUseDecodedIndexes(outputJar);
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("methodParameterObfuscation", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("constantObfuscation", new TransformConfig(true, 1.0));
        transforms.put("stringObfuscation", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x4D504152414D31L);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void assertPackedDescriptors(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if ("<clinit>".equals(method.name)) continue;
                if ("<init>".equals(method.name)) continue;
                if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) continue;
                if (method.name.startsWith("__neko_")) continue;
                assertTrue(
                    isPackedParameterDescriptor(method.desc),
                    clazz.name() + "." + method.name + method.desc + " was not packed"
                );
            }
        }
    }

    private void assertCallsUsePackedDescriptors(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    if (!call.owner.startsWith("ParameterShapes")) continue;
                    if ("<clinit>".equals(call.name)) continue;
                    if ("<init>".equals(call.name)) continue;
                    if (call.name.startsWith("__neko_")) continue;
                    assertTrue(
                        isPackedParameterDescriptor(call.desc),
                        "application call was not packed: " + call.owner + "." + call.name + call.desc
                    );
                }
            }
        }
    }

    private void assertCarrierIndexMarkersRemoved(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                if ("main".equals(method.name) && "([Ljava/lang/String;)V".equals(method.desc)) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    assertTrue(
                        !"dev/nekoobfuscator/runtime/CarrierIndex".equals(call.owner),
                        "carrier index marker leaked into generated jar: " +
                            clazz.name() + "." + method.name + method.desc
                    );
                }
            }
        }
    }

    private void assertHiddenKeyCarrierReadsUseDecodedIndexes(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        int decodedHiddenKeyReads = 0;
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null || !isPackedParameterDescriptor(method.desc)) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof TypeInsnNode cast) || !"java/lang/Long".equals(cast.desc)) continue;
                    AbstractInsnNode load = previousReal(insn.getPrevious());
                    if (!(load instanceof InsnNode aaload) || aaload.getOpcode() != Opcodes.AALOAD) continue;
                    AbstractInsnNode index = previousReal(load.getPrevious());
                    assertTrue(
                        index == null || !isIntConstant(index),
                        "hidden key carrier read still uses a literal index in " +
                            clazz.name() + "." + method.name + method.desc
                    );
                    assertTrue(
                        hasClassKeyObjectFieldLoadBefore(load),
                        "hidden key carrier read does not use class-key table material in " +
                            clazz.name() + "." + method.name + method.desc
                    );
                    decodedHiddenKeyReads++;
                }
            }
        }
        assertTrue(decodedHiddenKeyReads > 0, "no decoded hidden key carrier reads were found");
    }

    private void assertCarrierStoresUseDecodedIndexes(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        int decodedStores = 0;
        for (L1Class clazz : input.classMap().values()) {
            if (!clazz.name().startsWith("ParameterShapes")) continue;
            for (MethodNode method : clazz.asmNode().methods) {
                if (method.instructions == null) continue;
                if ("<clinit>".equals(method.name) || method.name.startsWith("__neko_")) continue;
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InsnNode store) || store.getOpcode() != Opcodes.AASTORE) continue;
                    AbstractInsnNode index = previousCarrierStoreIndex(store);
                    if (index == null) continue;
                    if (!isIntConstant(index) && hasClassKeyObjectFieldLoadBefore(store)) {
                        decodedStores++;
                    }
                }
            }
        }
        assertTrue(decodedStores >= 8, "expected decoded carrier stores for direct, virtual, MethodHandle, and reflection paths");
    }

    private AbstractInsnNode previousCarrierStoreIndex(AbstractInsnNode store) {
        AbstractInsnNode scan = store.getPrevious();
        for (int seen = 0; scan != null && seen++ < 96; scan = scan.getPrevious()) {
            if (scan.getOpcode() < 0) continue;
            if (!(scan instanceof InsnNode dup) || dup.getOpcode() != Opcodes.DUP) continue;
            AbstractInsnNode index = nextReal(dup.getNext());
            if (index == null || index == store) continue;
            AbstractInsnNode cursor = nextReal(index.getNext());
            while (cursor != null && cursor != store) {
                if (cursor.getOpcode() == Opcodes.AASTORE) break;
                cursor = nextReal(cursor.getNext());
            }
            if (cursor == store) return index;
        }
        return null;
    }

    private AbstractInsnNode nextReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private AbstractInsnNode previousReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private boolean hasClassKeyObjectFieldLoadBefore(AbstractInsnNode anchor) {
        int scanned = 0;
        for (AbstractInsnNode insn = anchor.getPrevious(); insn != null && scanned++ < 48; insn = insn.getPrevious()) {
            if (insn instanceof FieldInsnNode field
                && field.getOpcode() == Opcodes.GETSTATIC
                && "[Ljava/lang/Object;".equals(field.desc)) {
                return true;
            }
        }
        return false;
    }

    private boolean isIntConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) return true;
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return true;
        return insn instanceof org.objectweb.asm.tree.LdcInsnNode ldc && ldc.cst instanceof Integer;
    }

    private boolean isPackedParameterDescriptor(String desc) {
        return desc.startsWith("([Ljava/lang/Object;)")
            || desc.startsWith("([Ljava/lang/Object;J)");
    }

    private void writeJar(Path jar, Path classes, String mainClass) throws Exception {
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

    private String runJar(Path jar) throws Exception {
        return run(List.of("java", "-XX:-UsePerfData", "-jar", jar.toString()), Duration.ofSeconds(30));
    }

    private String run(List<String> command, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        boolean exited = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(exited, "command timed out: " + command);
        assertEquals(0, process.exitValue(), "command failed: " + command + "\n" + output);
        return output;
    }

    private String sourceText() {
        return """
            import java.lang.reflect.Constructor;
            import java.lang.reflect.Method;
            import java.lang.invoke.MethodHandle;
            import java.lang.invoke.MethodHandles;
            import java.lang.invoke.MethodType;
            import java.util.Arrays;

            public class ParameterShapes {
                interface Worker {
                    int work(int value, String text);
                }

                static class Impl implements Worker {
                    public int work(int value, String text) {
                        return value + text.length();
                    }
                }

                static class Box {
                    private final int base;
                    private final String tag;

                    Box(int base, String tag) {
                        this.base = base;
                        this.tag = tag;
                    }

                    int mix(int a, long b, double c, Object[] values) {
                        return base + tag.length() + a + (int) b + (int) c + values.length;
                    }
                }

                public static void main(String[] args) throws Throwable {
                    ParameterShapes shapes = new ParameterShapes();
                    Worker worker = new Impl();
                    Box box = new Box(3, "xy");
                    int total = add(4, 5)
                        + shapes.noArg()
                        + shapes.overload(7)
                        + shapes.overload("abcd")
                        + shapes.overload(2, 6)
                        + worker.work(8, "abc")
                        + box.mix(9, 10L, 11.0d, new Object[] {"z"});

                    Method method = ParameterShapes.class.getDeclaredMethod("reflectTarget", String.class, int.class);
                    method.setAccessible(true);
                    total += ((Integer) method.invoke(null, new Object[] {"qr", 12})).intValue();

                    for (Method candidate : ParameterShapes.class.getDeclaredMethods()) {
                        if (candidate.getName().equals("reflectTarget")) {
                            candidate.setAccessible(true);
                            total += ((Integer) candidate.invoke(null, new Object[] {"uv", 15})).intValue();
                            break;
                        }
                    }

                    Constructor<Box> ctor = Box.class.getDeclaredConstructor(int.class, String.class);
                    ctor.setAccessible(true);
                    Box reflected = ctor.newInstance(new Object[] {13, "rs"});
                    total += reflected.mix(1, 2L, 3.0d, new Object[] {"a", "b"});

                    MethodHandle handle = MethodHandles.lookup().findStatic(
                        ParameterShapes.class,
                        "methodHandleTarget",
                        MethodType.methodType(int.class, String.class, int.class)
                    );
                    total += (int) handle.invokeExact("mh", 14);

                    String out = join("total", total, Arrays.asList(args).isEmpty());
                    System.out.println(out);
                    if (!out.equals("total:158:true")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("PARAMETER OBF OK");
                }

                static int add(int left, int right) {
                    return left + right;
                }

                int noArg() {
                    return 6;
                }

                int overload(int value) {
                    return value + 1;
                }

                int overload(String value) {
                    return value.length() + 2;
                }

                int overload(int left, int right) {
                    return left * right;
                }

                static int reflectTarget(String text, int value) {
                    return text.length() + value;
                }

                static int methodHandleTarget(String text, int value) {
                    return text.length() + value;
                }

                static String join(String prefix, int value, boolean flag) {
                    return prefix + ":" + value + ":" + flag;
                }
            }
            """;
    }
}
