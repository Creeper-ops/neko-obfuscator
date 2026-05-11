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
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LocalVariableNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Replaces eligible method parameter lists with a single Object[] carrier.
 */
public final class JvmMethodParameterObfuscationPass implements TransformPass {
    public static final String ID = "methodParameterObfuscation";
    private static final String PREPARED = "methodParameterObfuscation.prepared";
    private static final String PLAN_BY_OLD_KEY = "methodParameterObfuscation.planByOldKey";
    private static final String PLAN_BY_FINAL_KEY = "methodParameterObfuscation.planByFinalKey";
    private static final String PLAN_BY_OWNER_NAME_DESC = "methodParameterObfuscation.planByOwnerNameDesc";
    private static final String INDY_HANDLE_TARGETS = "methodParameterObfuscation.indyHandleTargets";
    private static final String INDY_SAM_TARGETS = "methodParameterObfuscation.indySamTargets";
    static final String CFF_KEY_LOAD_TARGET_SEED = "controlFlowFlattening.generatedKeyLoadTargetSeed";
    private static final Type OBJECT_ARRAY_TYPE = Type.getType(Object[].class);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Method Parameter Obfuscation";
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
    public Set<String> dependsOn() {
        return Set.of(JvmKeyDispatchPass.ID);
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        if (Boolean.TRUE.equals(ctx.getPassData(PREPARED))) return;
        prepare(pctx);
        ctx.putPassData(PREPARED, Boolean.TRUE);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;

        MethodNode mn = method.asmNode();
        MethodPlan plan = planByFinalKey(pctx).get(JvmKeyDispatchPass.coverageKey(clazz, method));
        if (plan == null) {
            if (rewriteCallsites(pctx, clazz, mn, callerKeyLocal(pctx, clazz, mn))) {
                mn.maxStack = Math.max(mn.maxStack, 32);
                clazz.markDirty();
                pctx.invalidate(method);
                JvmObfuscationCoverage.get(ctx).safe(
                    id(),
                    clazz.name(),
                    method.name(),
                    method.descriptor(),
                    "callsite-parameter-carriers"
                );
            }
            return;
        }

        installUnpackPrologue(pctx, mn, plan);
        rewriteCallsites(pctx, clazz, mn, callerKeyLocal(pctx, clazz, mn));
        cleanupParameterMetadata(mn);
        mn.maxStack = Math.max(mn.maxStack, 32);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(
            id(),
            clazz.name(),
            method.name(),
            method.descriptor(),
            "object-array-parameters-" + plan.argumentTypes().length
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MethodPlan> planByOldKey(TransformContext ctx) {
        Map<String, MethodPlan> map = ctx.getPassData(PLAN_BY_OLD_KEY);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(PLAN_BY_OLD_KEY, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MethodPlan> planByFinalKey(TransformContext ctx) {
        Map<String, MethodPlan> map = ctx.getPassData(PLAN_BY_FINAL_KEY);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(PLAN_BY_FINAL_KEY, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, MethodPlan> planByOwnerNameDesc(TransformContext ctx) {
        Map<String, MethodPlan> map = ctx.getPassData(PLAN_BY_OWNER_NAME_DESC);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(PLAN_BY_OWNER_NAME_DESC, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static <T> Map<String, T> passMap(TransformContext ctx, String key) {
        Map<String, T> map = ctx.getPassData(key);
        if (map == null) {
            map = new LinkedHashMap<>();
            ctx.putPassData(key, map);
        }
        return map;
    }

    private void prepare(PipelineContext pctx) {
        Map<String, MethodPlan> oldPlans = planByOldKey(pctx);
        Map<String, MethodPlan> finalPlans = planByFinalKey(pctx);
        Map<String, MethodPlan> directPlans = planByOwnerNameDesc(pctx);
        Set<String> indyHandleTargets = indyHandleTargets(pctx);
        Set<String> indySamTargets = indySamTargets(pctx);
        List<MethodPlan> plans = new ArrayList<>();
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (!isEligible(pctx, clazz, method)) continue;
                if (indyHandleTargets.contains(key(clazz.name(), method.name(), method.descriptor()))) continue;
                if (indySamTargets.contains(key(clazz.name(), method.name(), method.descriptor()))) continue;
                MethodNode mn = method.asmNode();
                String oldName = mn.name;
                String oldDesc = mn.desc;
                String packedDesc = packedDescriptor(oldDesc);
                MethodPlan plan = new MethodPlan(
                    clazz.name(),
                    oldName,
                    oldDesc,
                    oldName,
                    packedDesc,
                    Type.getArgumentTypes(oldDesc),
                    argumentLocals(mn.access, oldDesc),
                    findRecordedKeyLocal(pctx, clazz.name(), oldName, oldDesc),
                    method.hasCode()
                );
                plans.add(plan);
            }
        }

        Map<String, List<MethodPlan>> collisionGroups = new HashMap<>();
        for (MethodPlan plan : plans) {
            collisionGroups.computeIfAbsent(
                plan.owner() + "." + plan.oldName() + plan.packedDesc(),
                ignored -> new ArrayList<>()
            ).add(plan);
        }
        for (List<MethodPlan> group : collisionGroups.values()) {
            if (group.size() <= 1) continue;
            for (MethodPlan plan : group) {
                plan.finalName(plan.oldName() + "$nkop$" + Integer.toHexString(plan.oldDesc().hashCode()));
            }
        }

        plans.sort(Comparator.comparing(MethodPlan::owner).thenComparing(MethodPlan::oldName).thenComparing(MethodPlan::oldDesc));
        for (MethodPlan plan : plans) {
            L1Class clazz = pctx.classMap().get(plan.owner());
            if (clazz == null) continue;
            MethodNode mn = findMethodNode(clazz, plan.oldName(), plan.oldDesc());
            if (mn == null) continue;
            String oldKey = key(plan.owner(), plan.oldName(), plan.oldDesc());
            L1Method seedMethod = plan.hasCode() ? null : findAsmMethod(clazz, plan.oldName(), plan.oldDesc());
            Long abstractSeed = seedMethod == null
                ? null
                : JvmKeyDispatchPass.methodSeed(pctx, clazz, seedMethod, mn);
            mn.name = plan.finalName();
            mn.desc = plan.packedDesc();
            clazz.markDirty();
            String finalKey = key(plan.owner(), plan.finalName(), plan.packedDesc());
            oldPlans.put(oldKey, plan);
            finalPlans.put(finalKey, plan);
            directPlans.put(oldKey, plan);
            migrateKeyDispatchMetadata(pctx, oldKey, finalKey);
            if (abstractSeed != null) {
                JvmKeyDispatchPass.recordMethodSeed(pctx, finalKey, abstractSeed);
            }
        }
    }

    private boolean isEligible(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (clazz.isAnnotation()) return false;
        if (TransformGuards.classHasStackIntrospection(clazz)) return false;
        if (TransformGuards.isReflectionShapeSensitive(pctx, clazz)) return false;
        if (TransformGuards.isReflectionMethodNamePinned(pctx, clazz, method)) return false;
        if (method.isConstructor() || method.isClassInit() || method.isNative()) return false;
        if ("main".equals(method.name()) && "([Ljava/lang/String;)V".equals(method.descriptor()) && method.isStatic()) {
            return false;
        }
        if (overridesExternalMethod(pctx, clazz, method.asmNode(), method.descriptor())) return false;
        return true;
    }

    private static L1Method findAsmMethod(L1Class clazz, String name, String desc) {
        L1Method direct = clazz.findMethod(name, desc);
        if (direct != null) return direct;
        for (L1Method method : clazz.methods()) {
            MethodNode mn = method.asmNode();
            if (mn != null && name.equals(mn.name) && desc.equals(mn.desc)) return method;
        }
        return null;
    }

    private static Set<String> indyHandleTargets(PipelineContext pctx) {
        Set<String> targets = pctx.getPassData(INDY_HANDLE_TARGETS);
        if (targets != null) return targets;
        targets = new java.util.LinkedHashSet<>();
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                    for (Object arg : indy.bsmArgs) {
                        if (arg instanceof Handle handle) {
                            targets.add(key(handle.getOwner(), handle.getName(), handle.getDesc()));
                        }
                    }
                }
            }
        }
        pctx.putPassData(INDY_HANDLE_TARGETS, targets);
        return targets;
    }

    private static boolean isMethodHandleLookup(MethodInsnNode call) {
        return "java/lang/invoke/MethodHandles$Lookup".equals(call.owner)
            && ("findStatic".equals(call.name)
                || "findVirtual".equals(call.name)
                || "findSpecial".equals(call.name))
            && call.desc.startsWith("(Ljava/lang/Class;Ljava/lang/String;");
    }

    private static MethodHandleLookupTarget previousMethodHandleLookupTarget(MethodInsnNode call) {
        String name = null;
        String owner = null;
        int scanned = 0;
        for (AbstractInsnNode scan = call.getPrevious(); scan != null && scanned++ < 48; scan = scan.getPrevious()) {
            if (!(scan instanceof LdcInsnNode ldc)) continue;
            if (name == null && ldc.cst instanceof String value) {
                name = value;
                continue;
            }
            if (name != null && owner == null && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
                owner = type.getInternalName();
            }
            if (name != null && owner != null) break;
        }
        return name == null ? null : new MethodHandleLookupTarget(owner, name);
    }

    private static Set<String> indySamTargets(PipelineContext pctx) {
        Set<String> targets = pctx.getPassData(INDY_SAM_TARGETS);
        if (targets != null) return targets;
        targets = new java.util.LinkedHashSet<>();
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof InvokeDynamicInsnNode indy)) continue;
                    if (!isLambdaMetafactory(indy) || indy.bsmArgs.length == 0 || !(indy.bsmArgs[0] instanceof Type samType)) {
                        continue;
                    }
                    Type owner = Type.getReturnType(indy.desc);
                    if (owner.getSort() == Type.OBJECT) {
                        targets.add(key(owner.getInternalName(), indy.name, samType.getDescriptor()));
                    }
                }
            }
        }
        pctx.putPassData(INDY_SAM_TARGETS, targets);
        return targets;
    }

    private static boolean isLambdaMetafactory(InvokeDynamicInsnNode indy) {
        return indy.bsm != null
            && "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner())
            && ("metafactory".equals(indy.bsm.getName()) || "altMetafactory".equals(indy.bsm.getName()));
    }

    private static MethodNode findMethodNode(L1Class clazz, String name, String desc) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return method;
        }
        return null;
    }

    private static Integer findRecordedKeyLocal(PipelineContext pctx, String owner, String name, String desc) {
        Map<String, Integer> locals = pctx.getPassData(JvmKeyDispatchPass.LOCAL_BY_METHOD);
        return locals == null ? null : locals.get(key(owner, name, desc));
    }

    private static void migrateKeyDispatchMetadata(PipelineContext pctx, String oldKey, String finalKey) {
        migrateMapKey(pctx, JvmKeyDispatchPass.LOCAL_BY_METHOD, oldKey, finalKey);
        migrateMapKey(pctx, JvmKeyDispatchPass.SEED_BY_METHOD, oldKey, finalKey);
        migrateMapKey(pctx, JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD, oldKey, finalKey);
        JvmKeyDispatchPass.migrateReflectiveKeyedEntry(pctx, oldKey, finalKey);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static void migrateMapKey(PipelineContext pctx, String mapKey, String oldKey, String finalKey) {
        Map map = pctx.getPassData(mapKey);
        if (map == null || !map.containsKey(oldKey)) return;
        Object value = map.remove(oldKey);
        map.put(finalKey, value);
    }

    private static String packedDescriptor(String desc) {
        return Type.getMethodDescriptor(Type.getReturnType(desc), OBJECT_ARRAY_TYPE);
    }

    private static int[] argumentLocals(int access, String desc) {
        Type[] args = Type.getArgumentTypes(desc);
        int[] locals = new int[args.length];
        int local = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        for (int i = 0; i < args.length; i++) {
            locals[i] = local;
            local += args[i].getSize();
        }
        return locals;
    }

    private void installUnpackPrologue(PipelineContext pctx, MethodNode mn, MethodPlan plan) {
        int argsLocal = mn.maxLocals++;
        int hiddenKeyIndex = packedHiddenKeyArgumentIndex(plan);
        Integer incomingKeyTemp = null;
        if (hiddenKeyIndex >= 0) {
            incomingKeyTemp = mn.maxLocals;
            mn.maxLocals += 2;
        }
        InsnList prologue = new InsnList();
        int packedArgumentLocal = (mn.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        prologue.add(new VarInsnNode(Opcodes.ALOAD, packedArgumentLocal));
        prologue.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));
        for (int i = 0; i < plan.argumentTypes().length; i++) {
            Type type = plan.argumentTypes()[i];
            int targetLocal = plan.argumentLocals()[i];
            emitArrayLoad(prologue, argsLocal, i);
            int storeLocal = i == hiddenKeyIndex ? incomingKeyTemp : targetLocal;
            emitUnboxOrCast(prologue, type);
            prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), storeLocal));
        }
        JvmKeyDispatchPass.markGenerated(pctx, prologue);
        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) {
            mn.instructions.add(prologue);
        } else {
            mn.instructions.insertBefore(first, prologue);
        }
        if (incomingKeyTemp != null) {
            rewriteFirstKeyDispatchLoad(pctx, mn, plan, plan.keyLocal(), incomingKeyTemp);
        }
    }

    private static int packedHiddenKeyArgumentIndex(MethodPlan plan) {
        if (plan.keyLocal() == null) return -1;
        for (int i = 0; i < plan.argumentLocals().length; i++) {
            if (plan.argumentLocals()[i] == plan.keyLocal()
                && Type.LONG_TYPE.equals(plan.argumentTypes()[i])) {
                return i;
            }
        }
        return -1;
    }

    private static void rewriteFirstKeyDispatchLoad(
        PipelineContext pctx,
        MethodNode mn,
        MethodPlan plan,
        int keyLocal,
        int incomingKeyTemp
    ) {
        long seed = seedForPlan(pctx, plan);
        int scanned = 0;
        for (
            AbstractInsnNode insn = mn.instructions.getFirst();
            insn != null && scanned++ < 128;
            insn = insn.getNext()
        ) {
            if (!JvmKeyDispatchPass.isGeneratedNode(pctx, insn)) continue;
            if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.LLOAD && var.var == keyLocal) {
                var.var = incomingKeyTemp;
                return;
            }
            if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.LSTORE && var.var == keyLocal) {
                if (replaceStaticKeyInit(pctx, mn, insn, incomingKeyTemp, keyLocal, seed)) {
                    return;
                }
                InsnList mix = new InsnList();
                mix.add(new InsnNode(Opcodes.POP2));
                emitIncomingKeyMixValue(
                    mix,
                    incomingKeyTemp,
                    seed,
                    JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK
                );
                JvmKeyDispatchPass.markGenerated(pctx, mix);
                mn.instructions.insertBefore(insn, mix);
                return;
            }
        }
        throw new IllegalStateException(
            "Packed hidden key cannot bind keyDispatch prologue"
        );
    }

    private static void emitIncomingKeyMixValue(
        InsnList insns,
        int sourceLocal,
        long seed,
        long mask
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, sourceLocal));
        JvmPassBytecode.pushLong(insns, seed ^ mask);
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, mask);
        insns.add(new InsnNode(Opcodes.LADD));
    }

    private static boolean replaceStaticKeyInit(
        PipelineContext pctx,
        MethodNode mn,
        AbstractInsnNode store,
        int incomingKeyTemp,
        int keyLocal,
        long seed
    ) {
        AbstractInsnNode xor = previousReal(store.getPrevious());
        AbstractInsnNode mask = xor == null ? null : previousReal(xor.getPrevious());
        AbstractInsnNode encoded = mask == null ? null : previousReal(mask.getPrevious());
        if (xor == null || mask == null || encoded == null) return false;
        if (xor.getOpcode() != Opcodes.LXOR) return false;
        if (!isLongPush(encoded) || !isLongPush(mask)) return false;

        InsnList mix = new InsnList();
        JvmKeyDispatchPass.emitIncomingKeyMix(
            mix,
            incomingKeyTemp,
            keyLocal,
            seed,
            JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK
        );
        JvmKeyDispatchPass.markGenerated(pctx, mix);
        mn.instructions.insertBefore(encoded, mix);
        mn.instructions.remove(encoded);
        mn.instructions.remove(mask);
        mn.instructions.remove(xor);
        mn.instructions.remove(store);
        return true;
    }

    private static boolean isLongPush(AbstractInsnNode insn) {
        if (insn == null) return false;
        int opcode = insn.getOpcode();
        return opcode == Opcodes.LCONST_0 ||
            opcode == Opcodes.LCONST_1 ||
            (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Long);
    }

    private static AbstractInsnNode previousReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private Integer callerKeyLocal(PipelineContext pctx, L1Class clazz, MethodNode mn) {
        return JvmKeyDispatchPass.findMethodKeyLocal(pctx, key(clazz.name(), mn.name, mn.desc));
    }

    private boolean rewriteCallsites(PipelineContext pctx, L1Class callerClass, MethodNode mn, Integer callerKeyLocal) {
        boolean changed = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (insn instanceof InvokeDynamicInsnNode indy) {
                rewriteInvokeDynamic(pctx, indy);
                continue;
            }
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (rewriteMethodHandleLookup(pctx, mn, call)) {
                changed = true;
                callerClass.markDirty();
                continue;
            }
            if (rewriteMethodHandleInvoke(pctx, mn, call, callerKeyLocal)) {
                changed = true;
                callerClass.markDirty();
                continue;
            }
            if (rewriteReflectionCall(pctx, mn, call)) {
                changed = true;
                callerClass.markDirty();
                continue;
            }
            MethodPlan plan = resolvePlan(pctx, call.owner, call.name, call.desc);
            if (plan == null) continue;
            InsnList pack = packCallArguments(pctx, mn, plan, callerKeyLocal);
            call.name = plan.finalName();
            call.desc = plan.packedDesc();
            mn.instructions.insertBefore(call, pack);
            changed = true;
            callerClass.markDirty();
        }
        return changed;
    }

    private boolean rewriteMethodHandleLookup(PipelineContext pctx, MethodNode mn, MethodInsnNode call) {
        if (!isMethodHandleLookup(call)) return false;
        MethodHandleLookupTarget target = previousMethodHandleLookupTarget(call);
        if (target == null || resolveMethodHandleLookupPlan(pctx, target) == null) return false;
        int methodTypeLocal = allocateLocal(mn, Type.getType("Ljava/lang/invoke/MethodType;"));
        int nameLocal = allocateLocal(mn, Type.getType(String.class));
        int classLocal = allocateLocal(mn, Type.getType(Class.class));
        InsnList before = new InsnList();
        before.add(new VarInsnNode(Opcodes.ASTORE, methodTypeLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, nameLocal));
        before.add(new VarInsnNode(Opcodes.ASTORE, classLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        before.add(new VarInsnNode(Opcodes.ALOAD, methodTypeLocal));
        before.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/invoke/MethodType",
            "returnType",
            "()Ljava/lang/Class;",
            false
        ));
        before.add(new LdcInsnNode(OBJECT_ARRAY_TYPE));
        before.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/invoke/MethodType",
            "methodType",
            "(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
            false
        ));
        JvmKeyDispatchPass.markGenerated(pctx, before);
        mn.instructions.insertBefore(call, before);
        return true;
    }

    private boolean rewriteMethodHandleInvoke(PipelineContext pctx, MethodNode mn, MethodInsnNode call, Integer callerKeyLocal) {
        if (!"java/lang/invoke/MethodHandle".equals(call.owner)
            || (!"invoke".equals(call.name) && !"invokeExact".equals(call.name))) {
            return false;
        }
        MethodPlan plan = methodHandleLookupPlanBefore(pctx, mn, call);
        if (plan == null) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        if (args.length == 1 && OBJECT_ARRAY_TYPE.equals(args[0])) return false;
        Type returnType = Type.getReturnType(call.desc);
        int[] locals = new int[args.length];
        for (int i = args.length - 1; i >= 0; i--) {
            locals[i] = allocateLocal(mn, args[i]);
        }
        InsnList before = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            before.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), locals[i]));
        }
        Type[] packedArgs = plan.argumentTypes();
        JvmPassBytecode.pushInt(before, packedArgs.length);
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        int sourceIndex = 0;
        for (int i = 0; i < packedArgs.length; i++) {
            before.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(before, i);
            if (isHiddenKeyArgument(plan, i)) {
                if (callerKeyLocal == null) {
                    throw new IllegalStateException(
                        "Missing caller key for methodParameterObfuscation MethodHandle target " +
                            plan.owner() + "." + plan.finalName() + plan.packedDesc()
                    );
                }
                VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, callerKeyLocal);
                before.add(keyLoad);
                cffKeyLoadTargetSeeds(pctx).put(keyLoad, seedForPlan(pctx, plan));
            } else {
                if (sourceIndex >= args.length) {
                    throw new IllegalStateException(
                        "Cannot map MethodHandle.invoke argument " + sourceIndex + " for " +
                            plan.owner() + "." + plan.finalName() + plan.packedDesc()
                    );
                }
                before.add(new VarInsnNode(args[sourceIndex].getOpcode(Opcodes.ILOAD), locals[sourceIndex]));
                sourceIndex++;
            }
            emitBox(before, packedArgs[i]);
            before.add(new InsnNode(Opcodes.AASTORE));
        }
        call.desc = Type.getMethodDescriptor(returnType, OBJECT_ARRAY_TYPE);
        JvmKeyDispatchPass.markGenerated(pctx, before);
        mn.instructions.insertBefore(call, before);
        return true;
    }

    private MethodPlan methodHandleLookupPlanBefore(PipelineContext pctx, MethodNode mn, AbstractInsnNode stop) {
        MethodPlan matched = null;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null && insn != stop; insn = insn.getNext()) {
            if (!(insn instanceof MethodInsnNode call) || !isMethodHandleLookup(call)) continue;
            MethodHandleLookupTarget target = previousMethodHandleLookupTarget(call);
            if (target != null) {
                MethodPlan plan = resolveMethodHandleLookupPlan(pctx, target);
                if (plan != null) matched = plan;
            }
        }
        return matched;
    }

    private MethodPlan resolveMethodHandleLookupPlan(PipelineContext pctx, MethodHandleLookupTarget target) {
        for (MethodPlan plan : planByOwnerNameDesc(pctx).values()) {
            if (!plan.oldName().equals(target.name()) && !plan.finalName().equals(target.name())) continue;
            if (target.owner() != null && !plan.owner().equals(target.owner())) continue;
            return plan;
        }
        return null;
    }

    private void rewriteInvokeDynamic(PipelineContext pctx, InvokeDynamicInsnNode indy) {
        for (Object arg : indy.bsmArgs) {
            if (arg instanceof Handle handle && resolvePlan(pctx, handle.getOwner(), handle.getName(), handle.getDesc()) != null) {
                throw new IllegalStateException(
                    "methodParameterObfuscation cannot rewrite invokedynamic handle without adapter: " +
                        handle.getOwner() + "." + handle.getName() + handle.getDesc()
                );
            }
        }
    }

    private boolean rewriteReflectionCall(PipelineContext pctx, MethodNode mn, MethodInsnNode call) {
        if ("java/lang/Class".equals(call.owner)
            && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
            && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc)) {
            InsnList before = rewriteMethodLookup(mn);
            JvmKeyDispatchPass.markGenerated(pctx, before);
            mn.instructions.insertBefore(call, before);
            return true;
        }
        if ("java/lang/reflect/Method".equals(call.owner)
            && "invoke".equals(call.name)
            && "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) {
            InsnList before = rewriteReflectiveInvoke(mn, true);
            JvmKeyDispatchPass.markGenerated(pctx, before);
            mn.instructions.insertBefore(call, before);
            return true;
        }
        return false;
    }

    private InsnList rewriteMethodLookup(MethodNode mn) {
        int paramsLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int nameLocal = allocateLocal(mn, Type.getType(String.class));
        int classLocal = allocateLocal(mn, Type.getType(Class.class));
        InsnList out = new InsnList();
        out.add(new VarInsnNode(Opcodes.ASTORE, paramsLocal));
        out.add(new VarInsnNode(Opcodes.ASTORE, nameLocal));
        out.add(new VarInsnNode(Opcodes.ASTORE, classLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        emitPackedParameterTypes(out);
        return out;
    }

    private InsnList rewriteReflectiveInvoke(MethodNode mn, boolean hasTarget) {
        int argsLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int targetLocal = hasTarget ? allocateLocal(mn, Type.getType(Object.class)) : -1;
        int memberLocal = allocateLocal(mn, Type.getType(Object.class));
        int innerLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int outerLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        LabelNode copyExisting = new LabelNode();
        LabelNode storeOuter = new LabelNode();
        InsnList out = new InsnList();
        out.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));
        if (hasTarget) {
            out.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
        }
        out.add(new VarInsnNode(Opcodes.ASTORE, memberLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        out.add(new JumpInsnNode(Opcodes.IFNONNULL, copyExisting));
        JvmPassBytecode.pushInt(out, 0);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        out.add(new VarInsnNode(Opcodes.ASTORE, innerLocal));
        out.add(new JumpInsnNode(Opcodes.GOTO, storeOuter));
        out.add(copyExisting);
        out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        out.add(new VarInsnNode(Opcodes.ASTORE, innerLocal));
        out.add(storeOuter);
        JvmPassBytecode.pushInt(out, 1);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        out.add(new VarInsnNode(Opcodes.ASTORE, outerLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, outerLocal));
        JvmPassBytecode.pushInt(out, 0);
        out.add(new VarInsnNode(Opcodes.ALOAD, innerLocal));
        out.add(new InsnNode(Opcodes.AASTORE));
        out.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        if (hasTarget) {
            out.add(new VarInsnNode(Opcodes.ALOAD, targetLocal));
        }
        out.add(new VarInsnNode(Opcodes.ALOAD, outerLocal));
        return out;
    }

    private void emitPackedParameterTypes(InsnList out) {
        JvmPassBytecode.pushInt(out, 1);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        out.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(out, 0);
        out.add(new LdcInsnNode(OBJECT_ARRAY_TYPE));
        out.add(new InsnNode(Opcodes.AASTORE));
    }

    private InsnList packCallArguments(PipelineContext pctx, MethodNode mn, MethodPlan plan, Integer callerKeyLocal) {
        Type[] args = plan.argumentTypes();
        int[] locals = new int[args.length];
        for (int i = args.length - 1; i >= 0; i--) {
            locals[i] = allocateLocal(mn, args[i]);
        }
        InsnList out = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), locals[i]));
        }
        JvmPassBytecode.pushInt(out, args.length);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < args.length; i++) {
            out.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(out, i);
            if (isHiddenKeyArgument(plan, i)) {
                if (callerKeyLocal == null) {
                    throw new IllegalStateException(
                        "Missing caller key for methodParameterObfuscation target " +
                            plan.owner() + "." + plan.finalName() + plan.packedDesc()
                    );
                }
                VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, callerKeyLocal);
                out.add(keyLoad);
                cffKeyLoadTargetSeeds(pctx).put(keyLoad, seedForPlan(pctx, plan));
            } else {
                out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ILOAD), locals[i]));
            }
            emitBox(out, args[i]);
            out.add(new InsnNode(Opcodes.AASTORE));
        }
        JvmKeyDispatchPass.markGenerated(pctx, out);
        return out;
    }

    private static boolean isHiddenKeyArgument(MethodPlan plan, int index) {
        Type[] args = plan.argumentTypes();
        return plan.keyLocal() != null
            && index == args.length - 1
            && Type.LONG_TYPE.equals(args[index]);
    }

    private static boolean shouldUseCanonicalRawSeed(PipelineContext pctx, MethodPlan plan) {
        if (plan.keyLocal() == null) return false;
        if ("<init>".equals(plan.finalName())) return false;
        L1Class clazz = pctx.classMap().get(plan.owner());
        if (clazz == null) return false;
        MethodNode method = findMethodNode(clazz, plan.finalName(), plan.packedDesc());
        if (method == null) return false;
        int access = method.access;
        return (access & Opcodes.ACC_STATIC) != 0
            || (access & Opcodes.ACC_PRIVATE) != 0
            || (access & Opcodes.ACC_FINAL) != 0
            || (clazz.asmNode().access & Opcodes.ACC_FINAL) != 0;
    }

    private static long seedForPlan(PipelineContext pctx, MethodPlan plan) {
        Long seed = JvmKeyDispatchPass.findMethodSeed(
            pctx,
            key(plan.owner(), plan.finalName(), plan.packedDesc())
        );
        if (seed == null) {
            throw new IllegalStateException(
                "Missing keyDispatch seed for methodParameterObfuscation target " +
                    plan.owner() + "." + plan.finalName() + plan.packedDesc()
            );
        }
        return seed;
    }

    private static long incomingRawForCanonical(long targetSeed) {
        return (targetSeed - JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK) ^
            (targetSeed ^ JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK);
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractInsnNode, Long> cffKeyLoadTargetSeeds(PipelineContext pctx) {
        Map<AbstractInsnNode, Long> map = pctx.getPassData(CFF_KEY_LOAD_TARGET_SEED);
        if (map == null) {
            map = new IdentityHashMap<>();
            pctx.putPassData(CFF_KEY_LOAD_TARGET_SEED, map);
        }
        return map;
    }

    private static void emitObfuscatedLong(InsnList out, long value, long seed) {
        long mask = JvmPassBytecode.mix(seed, value);
        JvmPassBytecode.pushLong(out, value ^ mask);
        JvmPassBytecode.pushLong(out, mask);
        out.add(new InsnNode(Opcodes.LXOR));
    }

    private MethodPlan resolvePlan(PipelineContext pctx, String owner, String name, String desc) {
        MethodPlan direct = planByOwnerNameDesc(pctx).get(key(owner, name, desc));
        if (direct != null) return direct;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        MethodPlan fromSuper = resolveInSuper(pctx, clazz.superName(), name, desc);
        if (fromSuper != null) return fromSuper;
        return resolveInInterfaces(pctx, clazz.interfaces(), name, desc);
    }

    private MethodPlan resolveInSuper(PipelineContext pctx, String owner, String name, String desc) {
        if (owner == null) return null;
        MethodPlan direct = planByOwnerNameDesc(pctx).get(key(owner, name, desc));
        if (direct != null) return direct;
        L1Class clazz = pctx.classMap().get(owner);
        if (clazz == null) return null;
        MethodPlan fromSuper = resolveInSuper(pctx, clazz.superName(), name, desc);
        if (fromSuper != null) return fromSuper;
        return resolveInInterfaces(pctx, clazz.interfaces(), name, desc);
    }

    private MethodPlan resolveInInterfaces(PipelineContext pctx, List<String> interfaces, String name, String desc) {
        for (String iface : interfaces) {
            MethodPlan direct = planByOwnerNameDesc(pctx).get(key(iface, name, desc));
            if (direct != null) return direct;
            L1Class clazz = pctx.classMap().get(iface);
            if (clazz == null) continue;
            MethodPlan nested = resolveInInterfaces(pctx, clazz.interfaces(), name, desc);
            if (nested != null) return nested;
        }
        return null;
    }

    private static void emitArrayLoad(InsnList out, int argsLocal, int index) {
        out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        JvmPassBytecode.pushInt(out, index);
        out.add(new InsnNode(Opcodes.AALOAD));
    }

    private static void emitUnboxOrCast(InsnList out, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.CHAR -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Character", "charValue", "()C", false));
            }
            case Type.BYTE -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.SHORT -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.FLOAT -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.LONG -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Long", "longValue", "()J", false));
            }
            case Type.DOUBLE -> {
                out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Double", "doubleValue", "()D", false));
            }
            case Type.ARRAY -> out.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getDescriptor()));
            case Type.OBJECT -> out.add(new TypeInsnNode(Opcodes.CHECKCAST, type.getInternalName()));
            default -> throw new IllegalStateException("Unsupported parameter type: " + type);
        }
    }

    private static void emitBox(InsnList out, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.CHAR -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.BYTE -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.SHORT -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.FLOAT -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.LONG -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.DOUBLE -> out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            case Type.ARRAY, Type.OBJECT -> {
            }
            default -> throw new IllegalStateException("Unsupported parameter type: " + type);
        }
    }

    private static int allocateLocal(MethodNode mn, Type type) {
        int local = mn.maxLocals;
        mn.maxLocals += type.getSize();
        return local;
    }

    private static AbstractInsnNode firstRealInstruction(MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return mn.instructions.getFirst();
    }

    private static void cleanupParameterMetadata(MethodNode mn) {
        mn.parameters = null;
        mn.visibleParameterAnnotations = null;
        mn.invisibleParameterAnnotations = null;
        if (mn.localVariables == null) return;
        for (LocalVariableNode local : mn.localVariables) {
            if (local.index > 0 || (mn.access & Opcodes.ACC_STATIC) != 0) {
                local.desc = "Ljava/lang/Object;";
            }
        }
    }

    private static boolean overridesExternalMethod(PipelineContext pctx, L1Class clazz, MethodNode mn, String desc) {
        return overridesExternalIn(pctx, clazz.superName(), mn.name, desc)
            || overridesExternalInInterfaces(pctx, clazz.interfaces(), mn.name, desc);
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

    private static boolean overridesExternalInInterfaces(PipelineContext pctx, List<String> interfaces, String name, String desc) {
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
            Class<?> type = Class.forName(owner.replace('/', '.'), false, JvmMethodParameterObfuscationPass.class.getClassLoader());
            for (java.lang.reflect.Method method : type.getDeclaredMethods()) {
                if (method.getName().equals(name) && Type.getMethodDescriptor(method).equals(desc)) return true;
            }
            for (java.lang.reflect.Constructor<?> ctor : type.getDeclaredConstructors()) {
                if ("<init>".equals(name) && Type.getConstructorDescriptor(ctor).equals(desc)) return true;
            }
            return false;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static String key(String owner, String name, String desc) {
        return owner + "." + name + desc;
    }

    private static final class MethodPlan {
        private final String owner;
        private final String oldName;
        private final String oldDesc;
        private String finalName;
        private final String packedDesc;
        private final Type[] argumentTypes;
        private final int[] argumentLocals;
        private final Integer keyLocal;
        private final boolean hasCode;

        MethodPlan(
            String owner,
            String oldName,
            String oldDesc,
            String finalName,
            String packedDesc,
            Type[] argumentTypes,
            int[] argumentLocals,
            Integer keyLocal,
            boolean hasCode
        ) {
            this.owner = owner;
            this.oldName = oldName;
            this.oldDesc = oldDesc;
            this.finalName = finalName;
            this.packedDesc = packedDesc;
            this.argumentTypes = argumentTypes;
            this.argumentLocals = argumentLocals;
            this.keyLocal = keyLocal;
            this.hasCode = hasCode;
        }

        String owner() { return owner; }
        String oldName() { return oldName; }
        String oldDesc() { return oldDesc; }
        String finalName() { return finalName; }
        void finalName(String value) { this.finalName = value; }
        String packedDesc() { return packedDesc; }
        Type[] argumentTypes() { return argumentTypes; }
        int[] argumentLocals() { return argumentLocals; }
        Integer keyLocal() { return keyLocal; }
        boolean hasCode() { return hasCode; }
    }

    private record MethodHandleLookupTarget(String owner, String name) {}
}
