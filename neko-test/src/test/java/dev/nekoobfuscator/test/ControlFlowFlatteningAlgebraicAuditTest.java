package dev.nekoobfuscator.test;

import dev.nekoobfuscator.api.config.ObfuscationConfig;
import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.core.jar.JarInput;
import dev.nekoobfuscator.core.pipeline.ObfuscationPipeline;
import dev.nekoobfuscator.core.pipeline.PassRegistry;
import dev.nekoobfuscator.transforms.jvm.StandardJvmPasses;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.jar.JarEntry;
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
        Path work = Files.createDirectories(projectRoot.resolve("build/tmp/neko-test-cff-audit"));
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
        assertRuntimeTokenDecodingUsesClassKeyTable(outputJar);

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

    private static void assertRuntimeTokenDecodingUsesClassKeyTable(Path jar)
        throws Exception {
        JarInput input = new JarInput(jar);
        for (var clazz : input.classes()) {
            for (var method : clazz.asmNode().methods) {
                if (method.instructions == null || "<clinit>".equals(method.name)) {
                    continue;
                }
                for (
                    AbstractInsnNode insn = method.instructions.getFirst();
                    insn != null;
                    insn = insn.getNext()
                ) {
                    if (!(insn instanceof FieldInsnNode field) ||
                        field.getOpcode() != Opcodes.GETSTATIC ||
                        !"[I".equals(field.desc)) {
                        continue;
                    }
                    if (hasNearbyIntArrayLoad(field)) {
                        return;
                    }
                }
            }
        }
        throw new AssertionError(
            "CFF runtime token decode did not use the class key table"
        );
    }

    private static boolean hasNearbyIntArrayLoad(AbstractInsnNode start) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = start.getNext();
            scan != null && scanned++ < 24;
            scan = scan.getNext()
        ) {
            if (scan.getOpcode() == Opcodes.IALOAD) {
                return true;
            }
        }
        return false;
    }

    private void runObfuscation(Path input, Path output) throws Exception {
        ObfuscationConfig config = new ObfuscationConfig();
        config.setInputJar(input);
        config.setOutputJar(output);
        Map<String, TransformConfig> transforms = new LinkedHashMap<>();
        transforms.put("keyDispatch", new TransformConfig(true, 1.0));
        transforms.put("controlFlowFlattening", new TransformConfig(true, 1.0));
        config.setTransforms(transforms);
        config.keyConfig().setMasterSeed(0x5EEDCFFAL);

        PassRegistry registry = new PassRegistry();
        StandardJvmPasses.register(registry);
        new ObfuscationPipeline(config, registry).execute(input, output);
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

    private static String sourceText() {
        return """
            public class CffAuditShapes {
                public static void main(String[] args) {
                    CffAuditShapes shapes = new CffAuditShapes();
                    int a = shapes.value(7, 11);
                    int b = shapes.value(19, 5);
                    int c = shapes.nested(13);
                    String out = a + ":" + b + ":" + c;
                    System.out.println(out);
                    if (!out.equals("63:97:58")) {
                        throw new AssertionError(out);
                    }
                    System.out.println("CFF AUDIT OK");
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

    private static final class StackUnderflow extends RuntimeException {}
}
