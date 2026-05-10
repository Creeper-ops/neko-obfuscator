package dev.nekoobfuscator.transforms.jvm;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Establishes hidden key material for JVM obfuscation passes.
 *
 * <p>The pass is ABI preserving: it never changes method descriptors or call
 * descriptors. Each concrete application method seeds a local key in its own
 * frame, and CFF consumes that key without exposing a Java-level parameter.</p>
 */
public final class JvmKeyDispatchPass implements TransformPass {
    public static final String ID = "keyDispatch";
    static final String LOCAL_BY_METHOD = "keyDispatch.localByMethod";
    static final String SEED_BY_METHOD = "keyDispatch.seedByMethod";
    static final String CFF_LOCAL_BY_METHOD = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String PREPARED = "keyDispatch.preparedInPlaceSignatures";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Key Dispatch";
    }

    @Override
    public TransformPhase phase() {
        return TransformPhase.PRE_TRANSFORM;
    }

    @Override
    public IRLevel requiredLevel() {
        return IRLevel.L1;
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (Boolean.TRUE.equals(ctx.getPassData(PREPARED))) return;
        ctx.putPassData(PREPARED, Boolean.TRUE);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;

        if (!isKeyCandidate(pctx, clazz, method)) {
            return;
        }

        MethodNode mn = method.asmNode();
        String methodKey = coverageKey(clazz, method);
        Map<String, Integer> locals = localMap(ctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(methodKey);
        if (existing != null) {
            publishControlFlowLocal(ctx, methodKey, existing);
            JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
                method.descriptor(), "key-local-already-present");
            return;
        }

        int keyLocal = mn.maxLocals;
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        emitKeyInit(prologue, keyLocal, seed, 0x4E4B4F4A564D4B31L);

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return;
        mn.instructions.insertBefore(first, prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(ctx, methodKey, keyLocal, seed);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), "abi-preserving-method-key-local");
    }

    static boolean isKeyCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (method.isAbstract() || method.isNative()) return false;
        return true;
    }

    static int ensureMethodKeyLocal(PipelineContext pctx, L1Class clazz, L1Method method) {
        String key = coverageKey(clazz, method);
        Map<String, Integer> locals = localMap(pctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(key);
        if (existing != null) {
            publishControlFlowLocal(pctx, key, existing);
            return existing;
        }

        MethodNode mn = method.asmNode();
        int keyLocal = mn.maxLocals;
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        emitKeyInit(prologue, keyLocal, seed, 0x6A766D4B65794C31L);

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return -1;
        mn.instructions.insertBefore(first, prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(pctx, key, keyLocal, seed);
        return keyLocal;
    }

    static Integer findMethodKeyLocal(TransformContext ctx, String methodKey) {
        return localMap(ctx, LOCAL_BY_METHOD).get(methodKey);
    }

    static Long findMethodSeed(TransformContext ctx, String methodKey) {
        return seedMap(ctx).get(methodKey);
    }

    static void recordMethodKeyLocal(TransformContext ctx, String methodKey, int keyLocal, long seed) {
        localMap(ctx, LOCAL_BY_METHOD).put(methodKey, keyLocal);
        seedMap(ctx).put(methodKey, seed);
        publishControlFlowLocal(ctx, methodKey, keyLocal);
    }

    static void emitKeyInit(InsnList insns, int keyLocal, long seed, long mask) {
        JvmPassBytecode.pushLong(insns, seed ^ mask);
        JvmPassBytecode.pushLong(insns, mask);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LXOR));
        insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    static String coverageKey(L1Class clazz, L1Method method) {
        return clazz.name() + "." + method.name() + method.descriptor();
    }

    static long methodSeed(long masterSeed, L1Class clazz, L1Method method, MethodNode mn) {
        long h = masterSeed ^ 0x9E3779B97F4A7C15L;
        h = JvmPassBytecode.mix(h, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, mn.instructions == null ? 0 : mn.instructions.size());
        h = JvmPassBytecode.mix(h, mn.maxLocals);
        return h == 0L ? 0x5DEECE66DL : h;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Integer> localMap(TransformContext ctx, String key) {
        Map<String, Integer> map = ctx.getPassData(key);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(key, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Long> seedMap(TransformContext ctx) {
        Map<String, Long> map = ctx.getPassData(SEED_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(SEED_BY_METHOD, map);
        }
        return map;
    }

    private static void publishControlFlowLocal(TransformContext ctx, String methodKey, int keyLocal) {
        localMap(ctx, CFF_LOCAL_BY_METHOD).put(methodKey, keyLocal);
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return mn.instructions.getFirst();
    }
}
