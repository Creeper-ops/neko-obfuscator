package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.core.util.RandomUtil;
import dev.nekoobfuscator.transforms.data.NumberEncryptionPass;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

/**
 * Generates opaque predicate expressions that always evaluate to true or false
 * but appear non-trivial to static analysis.
 */
public final class OpaquePredicateGenerator {
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final String KEY_OWNER = "dev/nekoobfuscator/runtime/NekoKeyDerivation";

    private final RandomUtil random;

    public OpaquePredicateGenerator(RandomUtil random) {
        this.random = random;
    }

    /**
     * Generate an opaque predicate that always evaluates to true.
     * Pushes 1 (true) on the stack for IFEQ-style conditionals.
     *
     * The pool spans flow-key, arithmetic identities, JDK invariants,
     * boolean reductions, and bit-fiddling tautologies so static analyzers
     * cannot collapse the predicate via a single template-recognition pass.
     */
    public InsnList generateAlwaysTrue() {
        return switch (random.nextInt(13)) {
            case 0, 1 -> runtimeFlowTrue();
            case 2 -> arithmeticTrue();
            case 3 -> hashCodeTrue();
            case 4 -> threadTrue();
            case 5 -> selfXorIdentityTrue();
            case 6 -> doubleNegationTrue();
            case 7 -> modSelfTrue();
            case 8 -> bitCountTrue();
            case 9 -> longSignTrue();
            case 10 -> stringEmptyTrue();
            case 11 -> mathAbsTrue();
            default -> systemNanoTrue();
        };
    }

    /**
     * Generate an opaque predicate that always evaluates to false.
     */
    public InsnList generateAlwaysFalse() {
        InsnList insns = generateAlwaysTrue();
        // Negate: push 1, XOR with the true result
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IXOR));
        return insns;
    }

    // (x * x + x) % 2 == 0 is always true for any integer x
    private InsnList arithmeticTrue() {
        InsnList insns = new InsnList();
        int x = random.nextInt();
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.IMUL));   // x * x
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.IADD));    // x * x + x
        insns.add(new InsnNode(Opcodes.ICONST_2));
        insns.add(new InsnNode(Opcodes.IREM));    // % 2
        // result is 0 (always true for "== 0" check)
        // We want to push 1 for "true", so: result == 0 ? 1 : 0
        LabelNode nonZero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    private InsnList runtimeFlowTrue() {
        InsnList insns = new InsnList();
        long salt = random.nextLong();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER, "flowKey", "()J", false));
        insns.add(NumberEncryptionPass.generatedLong(salt));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        LabelNode nonZero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // new int[0].length >= 0 is always true
    private InsnList arrayLengthTrue() {
        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.ICONST_1)); // small array
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        // length >= 0 is always true, length is 1
        // Push 1 directly since array.length > 0
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        return insns;
    }

    // System.identityHashCode(new Object()) | 1 != 0 is always true
    private InsnList hashCodeTrue() {
        InsnList insns = new InsnList();
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/Object", "<init>", "()V", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
            "identityHashCode", "(Ljava/lang/Object;)I", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        // result is always != 0 (OR with 1 ensures bit 0 is set)
        // Convert to boolean 1
        LabelNode zero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFEQ, zero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(zero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // Thread.currentThread() != null is always true
    private InsnList threadTrue() {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Thread",
            "currentThread", "()Ljava/lang/Thread;", false));
        LabelNode isNull = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNULL, isNull));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(isNull);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // (x ^ x) == 0 — push 1 if zero
    private InsnList selfXorIdentityTrue() {
        InsnList insns = new InsnList();
        int x = random.nextInt();
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.IXOR));
        LabelNode nonZero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // -(-x) == x — push 1 always
    private InsnList doubleNegationTrue() {
        InsnList insns = new InsnList();
        int x = random.nextInt();
        // avoid INT_MIN where -INT_MIN == INT_MIN due to overflow
        if (x == Integer.MIN_VALUE) x++;
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.INEG));
        insns.add(new InsnNode(Opcodes.INEG));
        LabelNode neq = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, neq));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(neq);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // (x % 1) == 0 — always true
    private InsnList modSelfTrue() {
        InsnList insns = new InsnList();
        int x = random.nextInt();
        insns.add(new LdcInsnNode(x));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IREM));
        LabelNode nonZero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // Integer.bitCount(0) == 0 — always true
    private InsnList bitCountTrue() {
        InsnList insns = new InsnList();
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer",
            "bitCount", "(I)I", false));
        LabelNode nonZero = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // Long.MAX_VALUE > 0 — always true
    private InsnList longSignTrue() {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(Long.MAX_VALUE - random.nextInt(1024)));
        insns.add(new LdcInsnNode(0L));
        insns.add(new InsnNode(Opcodes.LCMP));
        LabelNode notPositive = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFLE, notPositive));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(notPositive);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // !"".isEmpty() ? true (string is empty) — always true after negation
    private InsnList stringEmptyTrue() {
        InsnList insns = new InsnList();
        insns.add(new LdcInsnNode(""));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
            "isEmpty", "()Z", false));
        LabelNode notEmpty = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFEQ, notEmpty));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(notEmpty);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // Math.abs(-x) >= 0 — always true (modulo INT_MIN edge)
    private InsnList mathAbsTrue() {
        InsnList insns = new InsnList();
        int x = random.nextInt(0x40000000) | 1;
        insns.add(new LdcInsnNode(-x));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math",
            "abs", "(I)I", false));
        LabelNode neg = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFLT, neg));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(neg);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }

    // System.nanoTime() != Long.MIN_VALUE — virtually always true at runtime
    private InsnList systemNanoTrue() {
        InsnList insns = new InsnList();
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System",
            "nanoTime", "()J", false));
        insns.add(new LdcInsnNode(Long.MIN_VALUE));
        insns.add(new InsnNode(Opcodes.LCMP));
        LabelNode equal = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFEQ, equal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, end));
        insns.add(equal);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(end);
        return insns;
    }
}
