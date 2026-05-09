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
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Establishes a per-method hidden key local for JVM obfuscation passes.
 *
 * <p>The first implementation deliberately keeps method descriptors stable.
 * It models the ZKM-style "current method key" as a local value derived from
 * the build root, owner, method descriptor, and method bytecode shape. Later
 * passes can use the recorded local as the current key and can extend this pass
 * with signature or edge rewriting without changing the consumer contract.</p>
 */
public final class JvmKeyDispatchPass implements TransformPass {
    public static final String ID = "keyDispatch";
    static final String LOCAL_BY_METHOD = "keyDispatch.localByMethod";
    static final String SEED_BY_METHOD = "keyDispatch.seedByMethod";
    static final String CFF_LOCAL_BY_METHOD = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String PREPARED = "keyDispatch.preparedImplWrappers";
    private static final String IMPL_BY_CALL = "keyDispatch.implByCall";
    private static final String IMPL_BY_METHOD = "keyDispatch.implByMethod";

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
        if (!(ctx instanceof PipelineContext pctx)) return;
        if (Boolean.TRUE.equals(ctx.getPassData(PREPARED))) return;
        ctx.putPassData(PREPARED, Boolean.TRUE);

        for (L1Class clazz : new ArrayList<>(pctx.classMap().values())) {
            prepareClassKeyImplementations(pctx, clazz);
        }
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;

        if (!isKeyCandidate(pctx, clazz, method)) {
            JvmObfuscationCoverage.get(ctx).notApplicable(id(), clazz.name(), method.name(),
                method.descriptor(), "method-shape-not-keyed");
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

        ImplTarget selfImpl = implByMethod(ctx).get(methodKey);
        int keyLocal = selfImpl != null ? selfImpl.hiddenKeySlot() : mn.maxLocals;
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        if (selfImpl != null) {
            emitIncomingKeyInit(prologue, keyLocal, seed);
        } else {
            emitKeyInit(prologue, keyLocal, seed, 0x4E4B4F4A564D4B31L);
        }

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return;
        mn.instructions.insertBefore(first, prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        rewriteApplicationCalls(ctx, clazz, mn, keyLocal);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(ctx, methodKey, keyLocal, seed);
        JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
            method.descriptor(), "method-key-local");
    }

    static boolean isKeyCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (method.isAbstract() || method.isNative()) return false;
        if (TransformGuards.hasStackIntrospection(method)) return false;
        return !TransformGuards.isReflectionShapeSensitive(pctx, clazz);
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
        ImplTarget selfImpl = implByMethod(pctx).get(key);
        int keyLocal = selfImpl != null ? selfImpl.hiddenKeySlot() : mn.maxLocals;
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        if (selfImpl != null) {
            emitIncomingKeyInit(prologue, keyLocal, seed);
        } else {
            emitKeyInit(prologue, keyLocal, seed, 0x6A766D4B65794C31L);
        }

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return -1;
        mn.instructions.insertBefore(first, prologue);
        mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        mn.maxStack = Math.max(mn.maxStack, 6);
        rewriteApplicationCalls(pctx, clazz, mn, keyLocal);
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

    static void emitIncomingKeyInit(InsnList insns, int keyLocal, long seed) {
        insns.add(new org.objectweb.asm.tree.VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LADD));
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new org.objectweb.asm.tree.InsnNode(Opcodes.LUSHR));
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

    private void prepareClassKeyImplementations(PipelineContext pctx, L1Class clazz) {
        if (clazz == null || TransformGuards.isRuntimeClass(clazz) || clazz.isInterface()) return;
        if (TransformGuards.isReflectionShapeSensitive(pctx, clazz)) return;
        List<MethodNode> originals = new ArrayList<>(clazz.asmNode().methods);
        for (MethodNode original : originals) {
            L1Method method = new L1Method(clazz, original);
            if (!isImplementationCandidate(pctx, clazz, method)) continue;
            String originalKey = coverageKey(clazz, method);
            if (implByCall(pctx).containsKey(callKey(clazz.name(), original.name, original.desc))) continue;

            String implName = uniqueImplName(clazz, original.name);
            String implDesc = appendLongArgument(original.desc);
            int hiddenSlot = parameterLocalLimit(original.access, original.desc);
            MethodNode impl = cloneMethod(original, implName, implDesc);
            shiftLocalsForHiddenKey(impl, hiddenSlot);
            impl.access |= Opcodes.ACC_SYNTHETIC;
            impl.maxLocals = Math.max(impl.maxLocals + 2, hiddenSlot + 2);
            clearDebugLocals(impl);
            removeFrames(impl);

            rewriteWrapperBody(clazz.name(), original, implName, implDesc, hiddenSlot);
            clearDebugLocals(original);
            removeFrames(original);

            clazz.asmNode().methods.add(impl);
            ImplTarget target = new ImplTarget(clazz.name(), original.name, original.desc,
                implName, implDesc, original.access, hiddenSlot);
            implByCall(pctx).put(callKey(clazz.name(), original.name, original.desc), target);
            implByMethod(pctx).put(clazz.name() + "." + implName + implDesc, target);
            clazz.markDirty();
        }
    }

    private boolean isImplementationCandidate(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (!isKeyCandidate(pctx, clazz, method)) return false;
        if (method.isConstructor() || method.isClassInit()) return false;
        if (method.name().contains("$neko$key")) return false;
        return true;
    }

    private MethodNode cloneMethod(MethodNode original, String name, String desc) {
        MethodNode copy = new MethodNode(original.access, name, desc, original.signature,
            original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        original.accept(copy);
        copy.name = name;
        copy.desc = desc;
        return copy;
    }

    private void rewriteWrapperBody(String owner, MethodNode wrapper, String implName, String implDesc, int hiddenSlot) {
        wrapper.instructions.clear();
        wrapper.tryCatchBlocks.clear();
        InsnList insns = wrapper.instructions;
        emitLoadOriginalArguments(insns, (wrapper.access & Opcodes.ACC_STATIC) != 0, wrapper.desc);
        JvmPassBytecode.pushLong(insns, JvmPassBytecode.mix(owner.hashCode(), wrapper.name.hashCode() ^ wrapper.desc.hashCode()));
        int opcode = (wrapper.access & Opcodes.ACC_STATIC) != 0
            ? Opcodes.INVOKESTATIC
            : ((wrapper.access & Opcodes.ACC_PRIVATE) != 0 ? Opcodes.INVOKESPECIAL : Opcodes.INVOKEVIRTUAL);
        insns.add(new MethodInsnNode(opcode, owner, implName, implDesc, false));
        emitReturn(insns, Type.getReturnType(wrapper.desc));
        wrapper.maxLocals = Math.max(wrapper.maxLocals, hiddenSlot);
        wrapper.maxStack = Math.max(wrapper.maxStack, 8);
    }

    private void emitLoadOriginalArguments(InsnList insns, boolean isStatic, String desc) {
        int local = 0;
        if (!isStatic) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            local = 1;
        }
        for (Type argument : Type.getArgumentTypes(desc)) {
            insns.add(new VarInsnNode(argument.getOpcode(Opcodes.ILOAD), local));
            local += argument.getSize();
        }
    }

    private void emitReturn(InsnList insns, Type returnType) {
        insns.add(new org.objectweb.asm.tree.InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
    }

    private static void rewriteApplicationCalls(TransformContext ctx, L1Class callerClass, MethodNode method, int keyLocal) {
        boolean changed = false;
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.name.contains("$neko$key") || call.getOpcode() == Opcodes.INVOKEINTERFACE) continue;
            ImplTarget target = implByCall(ctx).get(callKey(call.owner, call.name, call.desc));
            if (target == null) continue;
            if (!canCallImplementation(callerClass, target)) continue;
            InsnList keyArg = new InsnList();
            keyArg.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            method.instructions.insertBefore(call, keyArg);
            call.name = target.implName();
            call.desc = target.implDesc();
            if ((target.access() & Opcodes.ACC_STATIC) != 0) {
                call.setOpcode(Opcodes.INVOKESTATIC);
            } else if ((target.access() & Opcodes.ACC_PRIVATE) != 0) {
                call.setOpcode(Opcodes.INVOKESPECIAL);
            }
            changed = true;
        }
        if (changed) {
            method.maxStack = Math.max(method.maxStack, 8);
        }
    }

    private static boolean canCallImplementation(L1Class callerClass, ImplTarget target) {
        if (callerClass.name().equals(target.owner())) return true;
        if ((target.access() & Opcodes.ACC_PRIVATE) != 0) return false;
        if ((target.access() & Opcodes.ACC_PUBLIC) != 0) return true;
        String callerPackage = packageName(callerClass.name());
        String targetPackage = packageName(target.owner());
        return callerPackage.equals(targetPackage);
    }

    private static String packageName(String internalName) {
        int slash = internalName.lastIndexOf('/');
        return slash < 0 ? "" : internalName.substring(0, slash);
    }

    private void shiftLocalsForHiddenKey(MethodNode method, int hiddenSlot) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode var && var.var >= hiddenSlot) {
                var.var += 2;
            } else if (insn instanceof IincInsnNode iinc && iinc.var >= hiddenSlot) {
                iinc.var += 2;
            }
        }
        if (method.localVariables != null) {
            for (var local : method.localVariables) {
                if (local.index >= hiddenSlot) local.index += 2;
            }
        }
    }

    private void clearDebugLocals(MethodNode method) {
        method.localVariables = null;
        method.visibleLocalVariableAnnotations = null;
        method.invisibleLocalVariableAnnotations = null;
    }

    private void removeFrames(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (insn instanceof FrameNode) method.instructions.remove(insn);
            insn = next;
        }
    }

    private String uniqueImplName(L1Class clazz, String baseName) {
        String candidate = baseName + "$neko$key";
        int index = 0;
        while (hasMethodNamed(clazz, candidate)) {
            candidate = baseName + "$neko$key$" + (++index);
        }
        return candidate;
    }

    private boolean hasMethodNamed(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private static int parameterLocalLimit(int access, String desc) {
        int slot = (access & Opcodes.ACC_STATIC) != 0 ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(desc)) {
            slot += argument.getSize();
        }
        return slot;
    }

    private static String appendLongArgument(String desc) {
        int close = desc.indexOf(')');
        return desc.substring(0, close) + "J" + desc.substring(close);
    }

    private static String callKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ImplTarget> implByCall(TransformContext ctx) {
        Map<String, ImplTarget> map = ctx.getPassData(IMPL_BY_CALL);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(IMPL_BY_CALL, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ImplTarget> implByMethod(TransformContext ctx) {
        Map<String, ImplTarget> map = ctx.getPassData(IMPL_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(IMPL_BY_METHOD, map);
        }
        return map;
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return mn.instructions.getFirst();
    }

    private record ImplTarget(String owner, String originalName, String originalDesc,
            String implName, String implDesc, int access, int hiddenKeySlot) {}
}
