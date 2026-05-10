package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;

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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
                assertTrue(
                    method.desc.startsWith("([Ljava/lang/Object;)"),
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
                for (var insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    if (!call.owner.startsWith("ParameterShapes")) continue;
                    if ("<clinit>".equals(call.name)) continue;
                    if ("<init>".equals(call.name)) continue;
                    assertTrue(
                        call.desc.startsWith("([Ljava/lang/Object;)"),
                        "application call was not packed: " + call.owner + "." + call.name + call.desc
                    );
                    assertFalse(call.desc.contains("J)"), "hidden long key leaked outside Object[] descriptor");
                }
            }
        }
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

                public static void main(String[] args) throws Exception {
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

                    Constructor<Box> ctor = Box.class.getDeclaredConstructor(int.class, String.class);
                    ctor.setAccessible(true);
                    Box reflected = ctor.newInstance(new Object[] {13, "rs"});
                    total += reflected.mix(1, 2L, 3.0d, new Object[] {"a", "b"});

                    String out = join("total", total, Arrays.asList(args).isEmpty());
                    System.out.println(out);
                    if (!out.equals("total:125:true")) {
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

                static String join(String prefix, int value, boolean flag) {
                    return prefix + ":" + value + ":" + flag;
                }
            }
            """;
    }
}
