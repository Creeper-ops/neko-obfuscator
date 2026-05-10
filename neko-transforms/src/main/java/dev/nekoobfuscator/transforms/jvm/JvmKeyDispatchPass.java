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
import org.objectweb.asm.Handle;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Establishes hidden key material for JVM obfuscation passes.
 *
 * <p>Application methods whose descriptor can be changed receive a real trailing
 * {@code long} key parameter. JVM ABI entry points that cannot be changed keep
 * their original descriptor, create a local key in the original method body, and
 * pass that key onward to keyed application calls. No runtime carrier or bridge
 * method is generated.</p>
 */
public final class JvmKeyDispatchPass implements TransformPass {
    public static final String ID = "keyDispatch";
    static final String LOCAL_BY_METHOD = "keyDispatch.localByMethod";
    static final String SEED_BY_METHOD = "keyDispatch.seedByMethod";
    static final String CFF_LOCAL_BY_METHOD = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String PREPARED = "keyDispatch.preparedInPlaceSignatures";
    private static final String KEYED_DESC_BY_METHOD = "keyDispatch.keyedDescByMethod";

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
        if (ctx instanceof PipelineContext pctx) {
            prepareKeyedDescriptors(pctx);
        }
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
        String originalDesc = mn.desc;
        String originalMethodKey = coverageKey(clazz.name(), mn.name, originalDesc);
        boolean keyedDescriptor = keyedDescMap(ctx).containsKey(originalMethodKey);
        int incomingKeyLocal = -1;
        if (keyedDescriptor) {
            incomingKeyLocal = addTrailingLongParameter(clazz, mn);
        }

        String methodKey = coverageKey(clazz, method);
        Map<String, Integer> locals = localMap(ctx, LOCAL_BY_METHOD);
        Integer existing = locals.get(methodKey);
        if (existing != null) {
            publishControlFlowLocal(ctx, methodKey, existing);
            JvmObfuscationCoverage.get(ctx).safe(id(), clazz.name(), method.name(),
                method.descriptor(), "key-local-already-present");
            return;
        }

        int keyLocal = incomingKeyLocal >= 0 ? incomingKeyLocal : mn.maxLocals;
        long seed = methodSeed(pctx.masterSeed(), clazz, method, mn);
        InsnList prologue = new InsnList();
        if (incomingKeyLocal >= 0) {
            emitIncomingKeyMix(prologue, keyLocal, seed, 0x4E4B4F4A564D4B31L);
        } else {
            emitKeyInit(prologue, keyLocal, seed, 0x4E4B4F4A564D4B31L);
        }

        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) return;
        mn.instructions.insertBefore(first, prologue);
        if (incomingKeyLocal < 0) {
            mn.maxLocals = Math.max(mn.maxLocals, keyLocal + 2);
        }
        mn.maxStack = Math.max(mn.maxStack, 8);
        int transfers = instrumentKeyTransfers(pctx, mn, keyLocal);
        clazz.markDirty();
        pctx.invalidate(method);

        recordMethodKeyLocal(ctx, methodKey, keyLocal, seed);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), transfers == 0
                ? "method-key-local"
                : "method-key-local+long-key-callsite-transfer");
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
        instrumentKeyTransfers(pctx, mn, keyLocal);
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
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    static String coverageKey(L1Class clazz, L1Method method) {
        return coverageKey(clazz.name(), method.name(), method.descriptor());
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

    @SuppressWarnings("unchecked")
    private static Map<String, String> keyedDescMap(TransformContext ctx) {
        Map<String, String> map = ctx.getPassData(KEYED_DESC_BY_METHOD);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(KEYED_DESC_BY_METHOD, map);
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

    private static void prepareKeyedDescriptors(PipelineContext pctx) {
        Map<String, String> keyed = keyedDescMap(pctx);
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) continue;
                if (clazz.isAnnotation() || !canReceiveLongKey(method) || method.isNative()) continue;
                if (overridesExternalMethod(pctx, clazz, method.asmNode(), method.descriptor())) continue;
                String original = method.descriptor();
                String keyedDesc = appendLongParameter(original);
                keyed.put(coverageKey(clazz.name(), method.name(), original), keyedDesc);
                if (!method.hasCode()) {
                    method.asmNode().desc = keyedDesc;
                    clazz.markDirty();
                }
            }
        }
    }

    private static boolean canReceiveLongKey(L1Method method) {
        if (method.isConstructor() || method.isClassInit()) return false;
        if ("main".equals(method.name()) && "([Ljava/lang/String;)V".equals(method.descriptor())
            && (method.access() & Opcodes.ACC_STATIC) != 0) {
            return false;
        }
        return true;
    }

    private static boolean overridesExternalMethod(PipelineContext pctx, L1Class clazz, MethodNode mn,
            String originalDesc) {
        return overridesExternalIn(pctx, clazz.superName(), mn.name, originalDesc)
            || overridesExternalInInterfaces(pctx, clazz.interfaces(), mn.name, originalDesc);
    }

    private static boolean overridesExternalIn(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return false;
        L1Class appClass = pctx.classMap().get(owner);
        if (appClass != null) {
            if (appClass.findMethod(name, desc) != null) return false;
            return overridesExternalIn(pctx, appClass.superName(), name, desc)
                || overridesExternalInInterfaces(pctx, appClass.interfaces(), name, desc);
        }
        return externalClassDeclares(owner, name, desc);
    }

    private static boolean overridesExternalInInterfaces(PipelineContext pctx, List<String> interfaces,
            String name, String desc) {
        for (String iface : interfaces) {
            L1Class appInterface = pctx.classMap().get(iface);
            if (appInterface != null) {
                if (appInterface.findMethod(name, desc) != null) return false;
                if (overridesExternalInInterfaces(pctx, appInterface.interfaces(), name, desc)) return true;
                continue;
            }
            if (externalClassDeclares(iface, name, desc)) return true;
        }
        return false;
    }

    private static boolean externalClassDeclares(String owner, String name, String desc) {
        try {
            Class<?> type = Class.forName(owner.replace('/', '.'), false, JvmKeyDispatchPass.class.getClassLoader());
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) {
                    return true;
                }
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int addTrailingLongParameter(L1Class clazz, MethodNode mn) {
        int insertLocal = argumentLocalSize(mn.access, mn.desc);
        shiftLocals(mn, insertLocal, 2);
        mn.desc = appendLongParameter(mn.desc);
        mn.maxLocals = Math.max(mn.maxLocals + 2, insertLocal + 2);
        clazz.markDirty();
        return insertLocal;
    }

    private static int argumentLocalSize(int access, String desc) {
        int size = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (Type arg : Type.getArgumentTypes(desc)) {
            size += arg.getSize();
        }
        return size;
    }

    private static void shiftLocals(MethodNode mn, int from, int amount) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode var && var.var >= from) {
                var.var += amount;
            } else if (insn instanceof IincInsnNode iinc && iinc.var >= from) {
                iinc.var += amount;
            }
        }
        if (mn.localVariables != null) {
            for (LocalVariableNode local : mn.localVariables) {
                if (local.index >= from) {
                    local.index += amount;
                }
            }
        }
    }

    private static int instrumentKeyTransfers(PipelineContext pctx, MethodNode mn, int keyLocal) {
        int transfers = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                if (rewriteInvokeDynamic(pctx, indy)) {
                    InsnList before = new InsnList();
                    before.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                    mn.instructions.insertBefore(indy, before);
                    transfers++;
                }
                continue;
            }
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (isMethodLookup(call)) {
                rewriteMethodLookup(mn, call);
                transfers++;
                continue;
            }
            if (isMethodInvoke(call)) {
                rewriteMethodInvoke(mn, call, keyLocal);
                transfers++;
                continue;
            }
            if ("<init>".equals(call.name)) continue;
            String keyedDesc = resolveKeyedDescriptor(pctx, call.owner, call.name, call.desc);
            if (keyedDesc == null) continue;
            call.desc = keyedDesc;
            insnInsertBefore(mn, call, keyLocal);
            transfers++;
        }
        if (transfers > 0) {
            mn.maxStack = Math.max(mn.maxStack, 8);
        }
        return transfers;
    }

    private static boolean rewriteInvokeDynamic(PipelineContext pctx, InvokeDynamicInsnNode indy) {
        boolean changed = false;
        for (int i = 0; i < indy.bsmArgs.length; i++) {
            Object arg = indy.bsmArgs[i];
            if (!(arg instanceof Handle handle)) continue;
            String keyedDesc = keyedDescMap(pctx).get(coverageKey(handle.getOwner(), handle.getName(), handle.getDesc()));
            if (keyedDesc == null) continue;
            indy.bsmArgs[i] = new Handle(handle.getTag(), handle.getOwner(), handle.getName(),
                keyedDesc, handle.isInterface());
            changed = true;
        }
        if (changed) {
            indy.desc = appendLongParameter(indy.desc);
        }
        return changed;
    }

    private static String resolveKeyedDescriptor(PipelineContext pctx, String owner, String name, String desc) {
        String direct = keyedDescMap(pctx).get(coverageKey(owner, name, desc));
        if (direct != null) return direct;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        String fromSuper = resolveKeyedDescriptorInSuper(pctx, clazz.superName(), name, desc);
        if (fromSuper != null) return fromSuper;
        return resolveKeyedDescriptorInInterfaces(pctx, clazz.interfaces(), name, desc);
    }

    private static String resolveKeyedDescriptorInSuper(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return null;
        String direct = keyedDescMap(pctx).get(coverageKey(owner, name, desc));
        if (direct != null) return direct;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        String fromSuper = resolveKeyedDescriptorInSuper(pctx, clazz.superName(), name, desc);
        if (fromSuper != null) return fromSuper;
        return resolveKeyedDescriptorInInterfaces(pctx, clazz.interfaces(), name, desc);
    }

    private static String resolveKeyedDescriptorInInterfaces(PipelineContext pctx, List<String> interfaces,
            String name, String desc) {
        for (String iface : interfaces) {
            String direct = keyedDescMap(pctx).get(coverageKey(iface, name, desc));
            if (direct != null) return direct;
            L1Class clazz = pctx.classMap().get(iface);
            if (clazz == null) continue;
            String nested = resolveKeyedDescriptorInInterfaces(pctx, clazz.interfaces(), name, desc);
            if (nested != null) return nested;
        }
        return null;
    }

    private static boolean isMethodLookup(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/Class".equals(call.owner)
            && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
            && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private static boolean isMethodInvoke(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/reflect/Method".equals(call.owner)
            && "invoke".equals(call.name)
            && "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc);
    }

    private static void rewriteMethodLookup(MethodNode mn, MethodInsnNode call) {
        int paramsLocal = allocateLocal(mn, Type.getType(Class[].class));
        int nameLocal = allocateLocal(mn, Type.getType(String.class));
        int classLocal = allocateLocal(mn, Type.getType(Class.class));
        int newParamsLocal = allocateLocal(mn, Type.getType(Class[].class));
        LabelNode copyExisting = new LabelNode();
        LabelNode storeLongType = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, paramsLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, nameLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, classLocal));

        before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
        before.add(new JumpInsnNode(Opcodes.IFNONNULL, copyExisting));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        before.add(new VarInsnNode(Opcodes.ASTORE, newParamsLocal));
        before.add(new JumpInsnNode(Opcodes.GOTO, storeLongType));

        before.add(copyExisting);
        before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.IADD));
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        before.add(new VarInsnNode(Opcodes.ASTORE, newParamsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
        JvmPassBytecode.pushInt(before, 0);
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        JvmPassBytecode.pushInt(before, 0);
        before.add(new VarInsnNode(Opcodes.ALOAD, paramsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
            "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

        before.add(storeLongType);
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.ISUB));
        before.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/lang/Long", "TYPE", "Ljava/lang/Class;"));
        before.add(new InsnNode(Opcodes.AASTORE));

        before.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newParamsLocal));
        mn.instructions.insertBefore(call, before);
    }

    private static void rewriteMethodInvoke(MethodNode mn, MethodInsnNode call, int keyLocal) {
        int argsLocal = allocateLocal(mn, Type.getType(Object[].class));
        int targetLocal = allocateLocal(mn, Type.getType(Object.class));
        int methodLocal = allocateLocal(mn, Type.getType(Object.class));
        int newArgsLocal = allocateLocal(mn, Type.getType(Object[].class));
        LabelNode copyExisting = new LabelNode();
        LabelNode storeKey = new LabelNode();
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, methodLocal));

        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        before.add(new JumpInsnNode(Opcodes.IFNONNULL, copyExisting));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        before.add(new VarInsnNode(Opcodes.ASTORE, newArgsLocal));
        before.add(new JumpInsnNode(Opcodes.GOTO, storeKey));

        before.add(copyExisting);
        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.IADD));
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        before.add(new VarInsnNode(Opcodes.ASTORE, newArgsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        JvmPassBytecode.pushInt(before, 0);
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        JvmPassBytecode.pushInt(before, 0);
        before.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/System", "arraycopy",
            "(Ljava/lang/Object;ILjava/lang/Object;II)V", false));

        before.add(storeKey);
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        before.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(before, 1);
        before.add(new InsnNode(Opcodes.ISUB));
        before.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        before.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
            "(J)Ljava/lang/Long;", false));
        before.add(new InsnNode(Opcodes.AASTORE));

        before.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, targetLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, newArgsLocal));
        mn.instructions.insertBefore(call, before);
    }

    private static void insnInsertBefore(MethodNode mn, MethodInsnNode call, int keyLocal) {
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        mn.instructions.insertBefore(call, before);
    }

    private static int allocateLocal(MethodNode mn, Type type) {
        int local = mn.maxLocals;
        mn.maxLocals += type.getSize();
        return local;
    }

    private static void emitIncomingKeyMix(InsnList insns, int keyLocal, long seed, long mask) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed ^ mask);
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, mask);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private static String appendLongParameter(String desc) {
        Type returnType = Type.getReturnType(desc);
        Type[] args = Type.getArgumentTypes(desc);
        Type[] keyed = new Type[args.length + 1];
        System.arraycopy(args, 0, keyed, 0, args.length);
        keyed[args.length] = Type.LONG_TYPE;
        return Type.getMethodDescriptor(returnType, keyed);
    }

    private static String coverageKey(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }
}
