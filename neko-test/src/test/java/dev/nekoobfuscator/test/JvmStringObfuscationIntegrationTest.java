package dev.nekoobfuscator.test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
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
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Exercises AES/DES plus XOR string obfuscation bound to CFF live key state.
 */
public class JvmStringObfuscationIntegrationTest {
    private static final List<String> SECRET_STRINGS = List.of(
        "alpha-secret-cff-flow-17",
        "omega-flow-tail-23",
        "beta-state-keydispatch-29",
        "gamma-aes-des-xor-31",
        "delta-domain-token-43",
        "epsilon-live-key-47",
        "STRING OBF OK",
        "alpha-secret-cff-flow-17|tail-23|beta-state-keydispatch-29|gamma-aes-des-xor-31:delta-domain-token-43:epsilon-live-key-47"
    );

    @Test
    void stringObfuscationUsesCffKeyedAesDesXorWithoutHelpers() throws Exception {
        Path projectRoot = Path.of(System.getProperty("neko.test.projectRoot", System.getProperty("user.dir")));
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-strings"));
        Path source = work.resolve("StringShapes.java");
        Files.writeString(source, sourceText(), StandardCharsets.UTF_8);

        Path classes = Files.createDirectories(work.resolve("classes"));
        run(List.of("javac", "-d", classes.toString(), source.toString()), Duration.ofSeconds(30));

        Path inputJar = work.resolve("string-shapes.jar");
        writeJar(inputJar, classes, "StringShapes");
        String original = runJar(inputJar);

        Path outputJar = work.resolve("string-shapes-obf.jar");
        runObfuscation(inputJar, outputJar);
        String obfuscated = runJar(outputJar);

        assertEquals(original, obfuscated);
        assertTrue(obfuscated.contains("STRING OBF OK"), obfuscated);
        assertPlaintextRemoved(outputJar);
        assertAesDesXorDecodeUsesCffState(outputJar);
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        transforms.put("constantObfuscation", new TransformConfig(true, 1.0));
        transforms.put("stringObfuscation", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x51724E6B5A11CFFL);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
    }

    private void assertPlaintextRemoved(Path jar) throws Exception {
        byte[] classBytes = NativeObfuscationHelper.extractEntry(jar, "StringShapes.class");
        String classText = new String(classBytes, StandardCharsets.ISO_8859_1);
        for (String secret : SECRET_STRINGS) {
            assertFalse(classText.contains(secret), "plaintext string remained in class bytes: " + secret);
        }

        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("StringShapes");
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                    assertFalse(SECRET_STRINGS.contains(value), "secret String LDC survived: " + value);
                }
                if (insn instanceof InvokeDynamicInsnNode indy) {
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof String value) {
                            assertFalse(SECRET_STRINGS.contains(value), "secret indy bootstrap string survived: " + value);
                        }
                    }
                }
            }
        }

        for (String entry : NativeObfuscationHelper.jarEntries(jar)) {
            assertFalse(entry.startsWith("dev/nekoobfuscator/runtime/"), "runtime helper class injected: " + entry);
        }
    }

    private void assertAesDesXorDecodeUsesCffState(Path jar) throws Exception {
        JarInput input = new JarInput(jar);
        L1Class clazz = input.classMap().get("StringShapes");
        boolean sawAes = false;
        boolean sawDes = false;
        boolean sawCipher = false;
        boolean sawSecretKeySpec = false;
        boolean sawDoFinal = false;
        boolean sawUtf8 = false;
        boolean sawClassKeyTableLoad = false;
        boolean sawMethodKeyLoad = false;
        boolean sawCipherCacheField = false;
        boolean sawCipherMonitor = false;
        boolean sawXor = false;
        boolean sawRotate = false;
        boolean sawStringConcatFactoryRecipe = false;
        int getInstanceCalls = 0;
        int clinitGetInstanceCalls = 0;

        for (var field : clazz.asmNode().fields) {
            if ("Ljavax/crypto/Cipher;".equals(field.desc)) {
                sawCipherCacheField = true;
            }
        }
        for (var method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof InvokeDynamicInsnNode indy
                    && indy.bsm != null
                    && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
                    && "makeConcatWithConstants".equals(indy.bsm.getName())) {
                    sawStringConcatFactoryRecipe = true;
                }
                if (insn instanceof LdcInsnNode ldc && "AES/ECB/NoPadding".equals(ldc.cst)) {
                    sawAes = true;
                }
                if (insn instanceof LdcInsnNode ldc && "DES/ECB/NoPadding".equals(ldc.cst)) {
                    sawDes = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "javax/crypto/Cipher".equals(call.owner)
                    && "getInstance".equals(call.name)) {
                    sawCipher = true;
                    getInstanceCalls++;
                    if ("<clinit>".equals(method.name)) {
                        clinitGetInstanceCalls++;
                    }
                }
                if (insn instanceof MethodInsnNode call
                    && "javax/crypto/spec/SecretKeySpec".equals(call.owner)
                    && "<init>".equals(call.name)) {
                    sawSecretKeySpec = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "javax/crypto/Cipher".equals(call.owner)
                    && "doFinal".equals(call.name)) {
                    sawDoFinal = true;
                }
                if (insn instanceof FieldInsnNode field
                    && "java/nio/charset/StandardCharsets".equals(field.owner)
                    && "UTF_8".equals(field.name)) {
                    sawUtf8 = true;
                }
                if (insn instanceof FieldInsnNode field
                    && "[I".equals(field.desc)
                    && "StringShapes".equals(field.owner)) {
                    sawClassKeyTableLoad = true;
                }
                if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.LLOAD) {
                    sawMethodKeyLoad = true;
                }
                if (insn.getOpcode() == Opcodes.IXOR) {
                    sawXor = true;
                }
                if (insn.getOpcode() == Opcodes.MONITORENTER || insn.getOpcode() == Opcodes.MONITOREXIT) {
                    sawCipherMonitor = true;
                }
                if (insn instanceof MethodInsnNode call
                    && "java/lang/Integer".equals(call.owner)
                    && ("rotateLeft".equals(call.name) || "rotateRight".equals(call.name))) {
                    sawRotate = true;
                }
            }
        }

        assertTrue(sawAes, "string pass should emit AES decode sites");
        assertTrue(sawDes, "string pass should emit DES decode sites");
        assertTrue(sawCipher, "string pass should use JCE Cipher without helper injection");
        assertTrue(sawCipherCacheField, "Cipher instances should be cached in class static state");
        assertTrue(sawCipherMonitor, "shared Cipher cache should be guarded for concurrent callers");
        assertEquals(clinitGetInstanceCalls, getInstanceCalls, "Cipher.getInstance should be limited to <clinit> cache setup");
        assertTrue(getInstanceCalls <= 2, "Cipher.getInstance should be per class algorithm, not per string site");
        assertTrue(sawSecretKeySpec, "string pass should build keys inline");
        assertTrue(sawDoFinal, "string pass should decrypt inline");
        assertTrue(sawUtf8, "decoded bytes should construct UTF-8 strings without UTF-8 LDC");
        assertTrue(sawClassKeyTableLoad, "decode should depend on CFF class key table");
        assertTrue(sawMethodKeyLoad, "decode should depend on keyDispatch/CFF method key local");
        assertTrue(sawXor, "decode should include XOR stream unmasking");
        assertFalse(sawRotate, "string decode must not use rotate self-cancelling masks");
        assertFalse(sawStringConcatFactoryRecipe, "concat recipe strings should be rewritten and encrypted");
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
            public class StringShapes {
                public static void main(String[] args) {
                    String a = "alpha-secret-cff-flow-17";
                    String b = "omega-flow-tail-23";
                    String c = "beta-state-keydispatch-29";
                    String d = "gamma-aes-des-xor-31";
                    String e = "delta-domain-token-43";
                    String f = "epsilon-live-key-47";
                    String out = a + "|" + b.substring(11) + "|" + c + "|" + d + ":" + e + ":" + f;
                    System.out.println(out);
                    if (!out.equals("alpha-secret-cff-flow-17|tail-23|beta-state-keydispatch-29|gamma-aes-des-xor-31:delta-domain-token-43:epsilon-live-key-47")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("STRING OBF OK");
                }
            }
            """;
    }
}
