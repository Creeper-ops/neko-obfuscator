package dev.nekoobfuscator.transforms.jvm.parameters;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.jar.ClassHierarchy;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.IntInsnNode;
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
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SourceInterpreter;
import org.objectweb.asm.tree.analysis.SourceValue;

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
    public static final String CFF_KEY_LOAD_TARGET_SEED = "controlFlowFlattening.generatedKeyLoadTargetSeed";
    public static final String CFF_PACKED_CALL_TARGET_SEED = "controlFlowFlattening.packedCallTargetSeed";
    public static final String CARRIER_INDEX_PLAN_BY_FINAL_KEY = "methodParameterObfuscation.carrierIndexPlanByFinalKey";
    public static final String CARRIER_INDEX_DECODE_SITES = "methodParameterObfuscation.carrierIndexDecodeSites";
    private static final String CARRIER_INDEX_MARKER_OWNER = "dev/nekoobfuscator/runtime/CarrierIndex";
    private static final String CARRIER_INDEX_MARKER_NAME = "__neko_carrier_index";
    private static final String CARRIER_INDEX_MARKER_DESC = "()I";
    private static final Type OBJECT_ARRAY_TYPE = Type.getType(Object[].class);
    private static final int CARRIER_INDEX_CLASS_WORDS = 64;
    private static final int CARRIER_INDEX_DOMAIN = 0x4F424649;

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

    public void finalizeOutput(PipelineContext pctx, List<L1Class> classes, ClassHierarchy hierarchy) {
        for (L1Class clazz : classes) {
            for (L1Method method : clazz.methods()) {
                MethodNode mn = method.asmNode();
                if (mn == null || mn.instructions == null) continue;
                for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (isCarrierIndexMarker(insn)) {
                        throw new IllegalStateException(
                            "Unreplaced carrier index decode marker in " +
                                clazz.name() + "." + mn.name + mn.desc
                        );
                    }
                }
            }
        }
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
        rewriteStaticVoidTailSelfRecursion(pctx, mn, plan);
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

    private void prepare(PipelineContext pctx) {
        Map<String, MethodPlan> oldPlans = planByOldKey(pctx);
        Map<String, MethodPlan> finalPlans = planByFinalKey(pctx);
        Map<String, MethodPlan> directPlans = planByOwnerNameDesc(pctx);
        Set<String> indyHandleTargets = indyHandleTargets(pctx);
        Set<String> indySamTargets = indySamTargets(pctx);
        Set<String> reflectiveLookupTargets = reflectiveLookupTargets(pctx);
        List<MethodPlan> plans = new ArrayList<>();
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (!isEligible(pctx, clazz, method)) continue;
                if (indyHandleTargets.contains(key(clazz.name(), method.name(), method.descriptor()))) continue;
                if (indySamTargets.contains(key(clazz.name(), method.name(), method.descriptor()))) continue;
                MethodNode mn = method.asmNode();
                String oldName = mn.name;
                String oldDesc = mn.desc;
                Type[] argumentTypes = Type.getArgumentTypes(oldDesc);
                int[] argumentLocals = argumentLocals(mn.access, oldDesc);
                Integer keyLocal = findRecordedKeyLocal(pctx, clazz.name(), oldName, oldDesc);
                String oldKey = key(clazz.name(), oldName, oldDesc);
                boolean reflectionKeyed = JvmKeyDispatchPass.isReflectiveKeyedEntry(pctx, oldKey);
                boolean reflectionLookupTarget = reflectiveLookupTargets.contains(oldKey)
                    || reflectiveLookupTargets.contains(clazz.name() + "." + oldName)
                    || reflectiveLookupTargets.contains("*." + oldName)
                    || reflectiveLookupTargets.contains(clazz.name() + ".*");
                boolean splitHiddenKey = false;
                String packedDesc = packedDescriptor(oldDesc, splitHiddenKey);
                MethodPlan plan = new MethodPlan(
                    clazz.name(),
                    oldName,
                    oldDesc,
                    oldName,
                    packedDesc,
                    argumentTypes,
                    argumentLocals,
                    keyLocal,
                    splitHiddenKey,
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
        Map<String, CarrierIndexPlan> carrierPlansByFamily = new HashMap<>();
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
            cleanupParameterMetadata(mn);
            clazz.markDirty();
            String finalKey = key(plan.owner(), plan.finalName(), plan.packedDesc());
            oldPlans.put(oldKey, plan);
            finalPlans.put(finalKey, plan);
            directPlans.put(oldKey, plan);
            migrateKeyDispatchMetadata(pctx, oldKey, finalKey);
            if (abstractSeed != null) {
                JvmKeyDispatchPass.recordMethodSeed(pctx, finalKey, abstractSeed);
            }
            long targetSeed = requireMethodSeed(pctx, plan, finalKey);
            String carrierFamily = carrierIndexFamily(plan, mn.access, finalKey);
            long carrierSeed = ((mn.access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) == 0)
                ? JvmPassBytecode.mix(pctx.masterSeed() ^ CARRIER_INDEX_DOMAIN, carrierFamily.hashCode())
                : targetSeed;
            CarrierIndexPlan carrierIndexPlan = carrierPlansByFamily.computeIfAbsent(
                carrierFamily,
                ignored -> createCarrierIndexPlan(plan, carrierSeed, carrierFamily)
            );
            plan.carrierIndexPlan(carrierIndexPlan);
            plan.carrierIndexKeySeed(carrierIndexKeySeed(carrierIndexPlan));
            carrierIndexPlans(pctx).put(finalKey, carrierIndexPlan);
        }
    }

    private static String carrierIndexFamily(MethodPlan plan, int access, String finalKey) {
        if ((access & (Opcodes.ACC_STATIC | Opcodes.ACC_PRIVATE)) != 0) {
            return "M:" + finalKey;
        }
        return "V:" + plan.oldName() + plan.oldDesc();
    }

    private boolean isEligible(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (clazz.isAnnotation()) return false;
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
                            if (isLambdaMetafactory(indy) &&
                                pctx.classMap().containsKey(handle.getOwner()) &&
                                isUnboundLambdaMethodReference(indy, handle)) {
                                addVirtualFamilyTargets(pctx, targets, handle.getOwner(), handle.getName(), handle.getDesc());
                            }
                        }
                    }
                }
            }
        }
        pctx.putPassData(INDY_HANDLE_TARGETS, targets);
        return targets;
    }

    private static boolean isUnboundLambdaMethodReference(InvokeDynamicInsnNode indy, Handle handle) {
        return handle.getTag() != Opcodes.H_INVOKESTATIC &&
            Type.getArgumentTypes(indy.desc).length == 0;
    }

    private static void addVirtualFamilyTargets(
        PipelineContext pctx,
        Set<String> targets,
        String owner,
        String name,
        String desc
    ) {
        for (L1Class clazz : pctx.classMap().values()) {
            if (!isSubtypeOf(pctx, clazz, owner)) continue;
            for (L1Method method : clazz.methods()) {
                if (method.name().equals(name) && method.descriptor().equals(desc)) {
                    targets.add(key(clazz.name(), method.name(), method.descriptor()));
                }
            }
        }
    }

    private static boolean isSubtypeOf(PipelineContext pctx, L1Class clazz, String targetOwner) {
        if (clazz == null) return false;
        if (clazz.name().equals(targetOwner)) return true;
        if (clazz.interfaces().contains(targetOwner)) return true;
        for (String iface : clazz.interfaces()) {
            if (isSubtypeOf(pctx, pctx.classMap().get(iface), targetOwner)) return true;
        }
        return isSubtypeOf(pctx, pctx.classMap().get(clazz.superName()), targetOwner);
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

    private static Set<String> reflectiveLookupTargets(PipelineContext pctx) {
        Set<String> targets = new java.util.LinkedHashSet<>();
        for (L1Class clazz : pctx.classMap().values()) {
            for (L1Method method : clazz.methods()) {
                if (!method.hasCode()) continue;
                for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call)) continue;
                    if (isReflectionLookup(call)) {
                        MethodHandleLookupTarget target = previousReflectiveLookupTarget(method.asmNode(), call);
                        if (target == null) continue;
                        if (target.name() == null) {
                            if (target.owner() != null &&
                                !addExactReflectiveLookupTargets(pctx, targets, call, target.owner())) {
                                targets.add(target.owner() + ".*");
                            }
                        } else {
                            targets.add((target.owner() == null ? "*" : target.owner()) + "." + target.name());
                        }
                    } else if (isReflectionMethodArrayLookup(call)) {
                        String owner = previousClassLiteral(call);
                        if (owner != null) {
                            targets.add(owner + ".*");
                        }
                    }
                }
            }
        }
        return targets;
    }

    private static boolean addExactReflectiveLookupTargets(
        PipelineContext pctx,
        Set<String> targets,
        MethodInsnNode call,
        String owner
    ) {
        Type[] parameterTypes = previousReflectiveParameterTypes(pctx, call);
        if (parameterTypes == null) return false;
        L1Class targetClass = pctx.classMap().get(owner);
        if (targetClass == null) return false;
        boolean added = false;
        for (L1Method candidate : targetClass.methods()) {
            Type[] candidateArgs = Type.getArgumentTypes(candidate.descriptor());
            if (candidateArgs.length != parameterTypes.length) continue;
            boolean same = true;
            for (int i = 0; i < candidateArgs.length; i++) {
                if (!candidateArgs[i].equals(parameterTypes[i])) {
                    same = false;
                    break;
                }
            }
            if (!same) continue;
            targets.add(key(owner, candidate.name(), candidate.descriptor()));
            added = true;
        }
        return added;
    }

    private static Type[] previousReflectiveParameterTypes(PipelineContext pctx, MethodInsnNode call) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = call.getPrevious();
            scan != null && scanned++ < 256;
            scan = scan.getPrevious()
        ) {
            if (JvmKeyDispatchPass.isGeneratedNode(pctx, scan)) continue;
            if (scan.getOpcode() == Opcodes.ACONST_NULL) return new Type[0];
            if (!(scan instanceof TypeInsnNode array) ||
                array.getOpcode() != Opcodes.ANEWARRAY ||
                !"java/lang/Class".equals(array.desc)) {
                continue;
            }
            Integer count = intConstant(previousNonGenerated(pctx, array.getPrevious()));
            if (count == null || count < 0) continue;
            Type[] types = new Type[count];
            AbstractInsnNode cursor = array.getNext();
            int stores = 0;
            while (cursor != null && cursor != call && stores < count) {
                if (JvmKeyDispatchPass.isGeneratedNode(pctx, cursor)) {
                    cursor = cursor.getNext();
                    continue;
                }
                if (cursor.getOpcode() == Opcodes.AASTORE) {
                    AbstractInsnNode valueInsn = previousNonGenerated(pctx, cursor.getPrevious());
                    AbstractInsnNode indexInsn = valueInsn == null
                        ? null
                        : previousNonGenerated(pctx, valueInsn.getPrevious());
                    Integer index = intConstant(indexInsn);
                    Type type = classConstant(valueInsn);
                    if (index == null || index < 0 || index >= count || type == null) {
                        return null;
                    }
                    types[index] = type;
                    stores++;
                }
                cursor = cursor.getNext();
            }
            for (Type type : types) {
                if (type == null) return null;
            }
            return types;
        }
        return null;
    }

    private static AbstractInsnNode previousNonGenerated(PipelineContext pctx, AbstractInsnNode start) {
        for (AbstractInsnNode scan = start; scan != null; scan = scan.getPrevious()) {
            if (scan.getOpcode() < 0 || JvmKeyDispatchPass.isGeneratedNode(pctx, scan)) continue;
            return scan;
        }
        return null;
    }

    private static Integer intConstant(AbstractInsnNode insn) {
        if (insn == null) return null;
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_0 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return ((IntInsnNode) insn).operand;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer value) {
            return value;
        }
        return null;
    }

    private static Type classConstant(AbstractInsnNode insn) {
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Type type) {
            return type;
        }
        if (!(insn instanceof FieldInsnNode field) ||
            field.getOpcode() != Opcodes.GETSTATIC ||
            !"TYPE".equals(field.name) ||
            !"Ljava/lang/Class;".equals(field.desc)) {
            return null;
        }
        return switch (field.owner) {
            case "java/lang/Boolean" -> Type.BOOLEAN_TYPE;
            case "java/lang/Character" -> Type.CHAR_TYPE;
            case "java/lang/Byte" -> Type.BYTE_TYPE;
            case "java/lang/Short" -> Type.SHORT_TYPE;
            case "java/lang/Integer" -> Type.INT_TYPE;
            case "java/lang/Float" -> Type.FLOAT_TYPE;
            case "java/lang/Long" -> Type.LONG_TYPE;
            case "java/lang/Double" -> Type.DOUBLE_TYPE;
            case "java/lang/Void" -> Type.VOID_TYPE;
            default -> null;
        };
    }

    private static boolean isReflectionLookup(MethodInsnNode call) {
        return "java/lang/Class".equals(call.owner)
            && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
            && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private static boolean isReflectionMethodArrayLookup(MethodInsnNode call) {
        return "java/lang/Class".equals(call.owner)
            && ("getMethods".equals(call.name) || "getDeclaredMethods".equals(call.name))
            && "()[Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private static MethodHandleLookupTarget previousReflectiveLookupTarget(MethodInsnNode call) {
        String name = null;
        String owner = null;
        int scanned = 0;
        for (AbstractInsnNode scan = call.getPrevious(); scan != null && scanned++ < 160; scan = scan.getPrevious()) {
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
        return name == null && owner == null ? null : new MethodHandleLookupTarget(owner, name);
    }

    private static MethodHandleLookupTarget previousReflectiveLookupTarget(MethodNode mn, MethodInsnNode call) {
        MethodHandleLookupTarget scanned = previousReflectiveLookupTarget(call);
        if (scanned != null && scanned.owner() != null) return scanned;
        MethodHandleLookupTarget sourced = sourceReflectiveLookupTarget(mn, call);
        if (sourced == null) return scanned;
        if (scanned == null) return sourced;
        String owner = scanned.owner() == null ? sourced.owner() : scanned.owner();
        String name = scanned.name() == null ? sourced.name() : scanned.name();
        return owner == null && name == null ? null : new MethodHandleLookupTarget(owner, name);
    }

    private static MethodHandleLookupTarget sourceReflectiveLookupTarget(MethodNode mn, MethodInsnNode call) {
        if (mn == null || mn.instructions == null) return null;
        int index = mn.instructions.indexOf(call);
        if (index < 0) return null;
        try {
            Analyzer<SourceValue> analyzer = new Analyzer<>(new SourceInterpreter());
            Frame<SourceValue>[] frames = analyzer.analyze("java/lang/Object", mn);
            Frame<SourceValue> frame = frames[index];
            if (frame == null || frame.getStackSize() < 3) return null;
            int top = frame.getStackSize();
            String owner = literalObjectClass(mn, frames, frame.getStack(top - 3), 0);
            String name = literalString(mn, frames, frame.getStack(top - 2), 0);
            return owner == null && name == null ? null : new MethodHandleLookupTarget(owner, name);
        } catch (AnalyzerException | RuntimeException ignored) {
            return null;
        }
    }

    private static String literalObjectClass(
        MethodNode mn,
        Frame<SourceValue>[] frames,
        SourceValue value,
        int depth
    ) {
        if (value == null || depth > 4) return null;
        String owner = null;
        for (AbstractInsnNode insn : value.insns) {
            if (insn instanceof LdcInsnNode ldc &&
                ldc.cst instanceof Type type &&
                type.getSort() == Type.OBJECT) {
                if (owner != null && !owner.equals(type.getInternalName())) return null;
                owner = type.getInternalName();
                continue;
            }
            if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                String stored = literalObjectClass(mn, frames, storedLocalValue(mn, frames, var), depth + 1);
                if (stored == null) continue;
                if (owner != null && !owner.equals(stored)) return null;
                owner = stored;
            }
        }
        return owner;
    }

    private static String literalString(
        MethodNode mn,
        Frame<SourceValue>[] frames,
        SourceValue value,
        int depth
    ) {
        if (value == null || depth > 4) return null;
        String string = null;
        for (AbstractInsnNode insn : value.insns) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String valueString) {
                if (string != null && !string.equals(valueString)) return null;
                string = valueString;
                continue;
            }
            if (insn instanceof VarInsnNode var && var.getOpcode() == Opcodes.ALOAD) {
                String stored = literalString(mn, frames, storedLocalValue(mn, frames, var), depth + 1);
                if (stored == null) continue;
                if (string != null && !string.equals(stored)) return null;
                string = stored;
            }
        }
        return string;
    }

    private static SourceValue storedLocalValue(
        MethodNode mn,
        Frame<SourceValue>[] frames,
        VarInsnNode load
    ) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = load.getPrevious();
            scan != null && scanned++ < 256;
            scan = scan.getPrevious()
        ) {
            if (!(scan instanceof VarInsnNode store) ||
                store.getOpcode() != Opcodes.ASTORE ||
                store.var != load.var) {
                continue;
            }
            int storeIndex = mn.instructions.indexOf(store);
            if (storeIndex < 0 || storeIndex >= frames.length) return null;
            Frame<SourceValue> frame = frames[storeIndex];
            if (frame == null || frame.getStackSize() == 0) return null;
            return frame.getStack(frame.getStackSize() - 1);
        }
        return null;
    }

    private static String previousClassLiteral(MethodInsnNode call) {
        int scanned = 0;
        for (AbstractInsnNode scan = call.getPrevious(); scan != null && scanned++ < 160; scan = scan.getPrevious()) {
            if (scan instanceof LdcInsnNode ldc && ldc.cst instanceof Type type && type.getSort() == Type.OBJECT) {
                return type.getInternalName();
            }
        }
        return null;
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

    private static String packedDescriptor(String desc, boolean splitHiddenKey) {
        return splitHiddenKey
            ? Type.getMethodDescriptor(Type.getReturnType(desc), OBJECT_ARRAY_TYPE, Type.LONG_TYPE)
            : Type.getMethodDescriptor(Type.getReturnType(desc), OBJECT_ARRAY_TYPE);
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
        int packedArgumentLocal = (mn.access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
        int packedArgumentSlots = 1 + (plan.splitHiddenKey() ? Type.LONG_TYPE.getSize() : 0);
        mn.maxLocals = Math.max(mn.maxLocals, packedArgumentLocal + packedArgumentSlots);
        if (plan.splitHiddenKey() && plan.keyLocal() != null) {
            mn.maxLocals = Math.max(mn.maxLocals, plan.keyLocal() + Type.LONG_TYPE.getSize());
        }
        int argsLocal = mn.maxLocals++;
        int hiddenKeyIndex = packedHiddenKeyArgumentIndex(plan);
        Integer incomingKeyTemp = null;
        if (hiddenKeyIndex >= 0) {
            incomingKeyTemp = mn.maxLocals;
            mn.maxLocals += 2;
        }
        Integer carrierIndexKeyTemp = null;
        if (hiddenKeyIndex >= 0) {
            carrierIndexKeyTemp = mn.maxLocals;
            mn.maxLocals += 2;
        }
        InsnList prologue = new InsnList();
        if (plan.splitHiddenKey() && incomingKeyTemp != null) {
            int primitiveKeyLocal = packedArgumentLocal + 1;
            prologue.add(new VarInsnNode(Opcodes.LLOAD, primitiveKeyLocal));
            prologue.add(new VarInsnNode(Opcodes.LSTORE, incomingKeyTemp));
        }
        prologue.add(new VarInsnNode(Opcodes.ALOAD, packedArgumentLocal));
        prologue.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));
        if (hiddenKeyIndex >= 0 && carrierIndexKeyTemp != null) {
            emitCarrierArrayLoad(
                pctx,
                prologue,
                argsLocal,
                plan,
                carrierIndexKeyLogicalIndex(plan),
                -1,
                false,
                plan.carrierIndexKeySeed()
            );
            emitUnboxOrCast(prologue, Type.LONG_TYPE);
            prologue.add(new VarInsnNode(Opcodes.LSTORE, carrierIndexKeyTemp));
        }
        if (!plan.splitHiddenKey() && hiddenKeyIndex >= 0 && incomingKeyTemp != null) {
            emitCarrierArrayLoad(
                pctx,
                prologue,
                argsLocal,
                plan,
                hiddenKeyIndex,
                carrierIndexKeyTemp == null ? -1 : carrierIndexKeyTemp,
                false,
                plan.carrierIndexKeySeed()
            );
            emitUnboxOrCast(prologue, Type.LONG_TYPE);
            prologue.add(new VarInsnNode(Opcodes.LSTORE, incomingKeyTemp));
        }
        int carrierIndex = 0;
        for (int i = 0; i < plan.argumentTypes().length; i++) {
            Type type = plan.argumentTypes()[i];
            int targetLocal = plan.argumentLocals()[i];
            if (plan.splitHiddenKey() && i == hiddenKeyIndex) {
                continue;
            }
            if (!plan.splitHiddenKey() && i == hiddenKeyIndex) {
                carrierIndex++;
                continue;
            }
            emitCarrierArrayLoad(
                pctx,
                prologue,
                argsLocal,
                plan,
                carrierIndex++,
                carrierIndexKeyTemp == null ? -1 : carrierIndexKeyTemp,
                false,
                plan.carrierIndexKeySeed()
            );
            int storeLocal = i == hiddenKeyIndex ? incomingKeyTemp : targetLocal;
            emitUnboxOrCast(prologue, type);
            prologue.add(new VarInsnNode(type.getOpcode(Opcodes.ISTORE), storeLocal));
        }
        AbstractInsnNode first = firstRealInstruction(mn);
        if (first == null) {
            mn.instructions.add(prologue);
        } else {
            mn.instructions.insertBefore(first, prologue);
        }
        if (incomingKeyTemp != null) {
            rewriteFirstKeyDispatchLoad(pctx, mn, plan, plan.keyLocal(), incomingKeyTemp);
        }
        JvmKeyDispatchPass.markGenerated(pctx, prologue);
    }

    private static int packedHiddenKeyArgumentIndex(MethodPlan plan) {
        int byLocal = hiddenKeyArgumentIndex(plan.argumentTypes(), plan.argumentLocals(), plan.keyLocal());
        if (byLocal >= 0) return byLocal;
        if (!plan.hasCode()
            && plan.argumentTypes().length > 0
            && Type.LONG_TYPE.equals(plan.argumentTypes()[plan.argumentTypes().length - 1])) {
            return plan.argumentTypes().length - 1;
        }
        return -1;
    }

    private static int hiddenKeyArgumentIndex(Type[] argumentTypes, int[] argumentLocals, Integer keyLocal) {
        if (keyLocal == null) return -1;
        for (int i = 0; i < argumentLocals.length; i++) {
            if (argumentLocals[i] == keyLocal
                && Type.LONG_TYPE.equals(argumentTypes[i])) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isPackedDescriptor(MethodPlan plan, String desc) {
        if (!plan.packedDesc().equals(desc)) return false;
        Type[] args = Type.getArgumentTypes(desc);
        return args.length >= 1 && OBJECT_ARRAY_TYPE.equals(args[0]);
    }

    private static boolean canUseSplitHiddenKeyAbi(L1Class clazz, MethodNode mn) {
        return (mn.access & Opcodes.ACC_STATIC) != 0
            || (mn.access & Opcodes.ACC_PRIVATE) != 0;
    }

    private static int carrierArgumentCount(MethodPlan plan) {
        return carrierValueCount(plan) + (packedHiddenKeyArgumentIndex(plan) >= 0 ? 1 : 0);
    }

    private static int carrierValueCount(MethodPlan plan) {
        return plan.argumentTypes().length - (plan.splitHiddenKey() ? 1 : 0);
    }

    private static int carrierIndexKeyLogicalIndex(MethodPlan plan) {
        if (packedHiddenKeyArgumentIndex(plan) < 0) {
            throw new IllegalStateException(
                "Carrier index key requires hidden key metadata for " +
                    plan.owner() + "." + plan.finalName() + plan.packedDesc()
            );
        }
        return carrierValueCount(plan);
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

    private static boolean rewriteStaticVoidTailSelfRecursion(
        PipelineContext pctx,
        MethodNode mn,
        MethodPlan plan
    ) {
        if ((mn.access & Opcodes.ACC_STATIC) == 0) return false;
        if (!Type.VOID_TYPE.equals(Type.getReturnType(plan.oldDesc()))) return false;
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) return false;

        AbstractInsnNode bodyStart = firstApplicationInstruction(pctx, mn, plan.keyLocal());
        if (bodyStart == null) return false;
        LabelNode loopStart = new LabelNode();
        mn.instructions.insertBefore(bodyStart, loopStart);
        mn.instructions.insert(loopStart, new InsnNode(Opcodes.NOP));

        boolean changed = false;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            if (call.getOpcode() != Opcodes.INVOKESTATIC) continue;
            if (!plan.owner().equals(call.owner) ||
                !plan.oldName().equals(call.name) ||
                !plan.oldDesc().equals(call.desc)) {
                continue;
            }
            AbstractInsnNode next = nextRealInstruction(call.getNext());
            if (next == null || next.getOpcode() != Opcodes.RETURN) continue;

            InsnList replacement = new InsnList();
            Type[] args = plan.argumentTypes();
            int[] locals = plan.argumentLocals();
            for (int i = args.length - 1; i >= 0; i--) {
                replacement.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), locals[i]));
            }
            replacement.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
            mn.instructions.insertBefore(call, replacement);
            mn.instructions.remove(call);
            changed = true;
        }
        return changed;
    }

    private static AbstractInsnNode firstApplicationInstruction(
        PipelineContext pctx,
        MethodNode mn,
        Integer keyLocal
    ) {
        boolean afterKeyInit = keyLocal == null;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (
                !afterKeyInit &&
                insn instanceof VarInsnNode var &&
                var.getOpcode() == Opcodes.LSTORE &&
                var.var == keyLocal
            ) {
                afterKeyInit = true;
                continue;
            }
            if (!afterKeyInit) continue;
            if (insn.getOpcode() < 0) continue;
            if (JvmKeyDispatchPass.isGeneratedNode(pctx, insn)) continue;
            return insn;
        }
        return null;
    }

    private static AbstractInsnNode nextRealInstruction(AbstractInsnNode insn) {
        for (AbstractInsnNode scan = insn; scan != null; scan = scan.getNext()) {
            if (scan.getOpcode() >= 0) return scan;
        }
        return null;
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
            if (rewriteReflectionCall(pctx, mn, call, callerKeyLocal)) {
                changed = true;
                callerClass.markDirty();
                continue;
            }
            MethodPlan plan = resolvePlan(pctx, call.owner, call.name, call.desc);
            if (plan == null) continue;
            cffPackedCallTargetSeeds(pctx).put(
                JvmKeyDispatchPass.coverageKey(call.owner, plan.finalName(), plan.packedDesc()),
                seedForPlan(pctx, plan)
            );
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
        MethodPlan plan = target == null ? null : resolveMethodHandleLookupPlan(pctx, target);
        if (plan == null) return false;
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
        if (plan.splitHiddenKey()) {
            JvmPassBytecode.pushInt(before, 2);
            before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
            before.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(before, 0);
            before.add(new LdcInsnNode(OBJECT_ARRAY_TYPE));
            before.add(new InsnNode(Opcodes.AASTORE));
            before.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(before, 1);
            emitClassConstant(before, Type.LONG_TYPE);
            before.add(new InsnNode(Opcodes.AASTORE));
            before.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodType",
                "methodType",
                "(Ljava/lang/Class;[Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
                false
            ));
        } else {
            before.add(new LdcInsnNode(OBJECT_ARRAY_TYPE));
            before.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/invoke/MethodType",
                "methodType",
                "(Ljava/lang/Class;Ljava/lang/Class;)Ljava/lang/invoke/MethodType;",
                false
            ));
        }
        JvmKeyDispatchPass.markGenerated(pctx, before);
        mn.instructions.insertBefore(call, before);
        return true;
    }

    private static void emitClassConstant(InsnList out, Type type) {
        if (type.getSort() == Type.LONG) {
            out.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                "java/lang/Long",
                "TYPE",
                "Ljava/lang/Class;"
            ));
            return;
        }
        out.add(new LdcInsnNode(type));
    }

    private boolean rewriteMethodHandleInvoke(PipelineContext pctx, MethodNode mn, MethodInsnNode call, Integer callerKeyLocal) {
        if (!"java/lang/invoke/MethodHandle".equals(call.owner)
            || (!"invoke".equals(call.name) && !"invokeExact".equals(call.name))) {
            return false;
        }
        MethodPlan plan = methodHandleLookupPlanBefore(pctx, mn, call);
        if (plan == null) return false;
        Type[] args = Type.getArgumentTypes(call.desc);
        if (isPackedDescriptor(plan, call.desc)) return false;
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
        JvmPassBytecode.pushInt(before, carrierArgumentCount(plan));
        before.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        emitCarrierIndexKeyStore(pctx, before, plan, callerKeyLocal, "MethodHandle target");
        int sourceIndex = 0;
        int carrierIndex = 0;
        for (int i = 0; i < packedArgs.length; i++) {
            if (plan.splitHiddenKey() && isHiddenKeyArgument(plan, i)) {
                continue;
            }
            before.add(new InsnNode(Opcodes.DUP));
            emitCarrierIndexDecodeMarker(
                pctx,
                before,
                plan,
                carrierIndex++,
                callerKeyLocal,
                true,
                plan.carrierIndexKeySeed()
            );
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
        if (plan.splitHiddenKey()) {
            if (callerKeyLocal == null) {
                throw new IllegalStateException(
                    "Missing caller key for methodParameterObfuscation MethodHandle target " +
                        plan.owner() + "." + plan.finalName() + plan.packedDesc()
                );
            }
            VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, callerKeyLocal);
            before.add(keyLoad);
            cffKeyLoadTargetSeeds(pctx).put(keyLoad, seedForPlan(pctx, plan));
            call.desc = Type.getMethodDescriptor(returnType, OBJECT_ARRAY_TYPE, Type.LONG_TYPE);
        } else {
            call.desc = Type.getMethodDescriptor(returnType, OBJECT_ARRAY_TYPE);
        }
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
        MethodPlan matched = null;
        for (MethodPlan plan : planByOwnerNameDesc(pctx).values()) {
            if (target.name() == null) continue;
            if (!plan.oldName().equals(target.name()) && !plan.finalName().equals(target.name())) continue;
            if (target.owner() != null && !plan.owner().equals(target.owner())) continue;
            if (target.owner() != null) return plan;
            if (matched != null) return null;
            matched = plan;
        }
        return matched;
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

    private boolean rewriteReflectionCall(PipelineContext pctx, MethodNode mn, MethodInsnNode call, Integer callerKeyLocal) {
        if ("java/lang/Class".equals(call.owner)
            && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
            && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc)) {
            InsnList before = rewriteMethodLookup(mn, resolveReflectiveLookupPlan(pctx, mn, call));
            mn.instructions.insertBefore(call, before);
            return true;
        }
        if ("java/lang/reflect/Method".equals(call.owner)
            && "invoke".equals(call.name)
            && "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) {
            InsnList before = rewriteReflectiveInvoke(
                pctx,
                mn,
                true,
                resolveReflectiveInvokePlan(pctx, mn, call),
                runtimeReflectiveInvokeCandidates(pctx, mn, call),
                callerKeyLocal
            );
            mn.instructions.insertBefore(call, before);
            return true;
        }
        return false;
    }

    private MethodPlan resolveReflectiveLookupPlan(PipelineContext pctx, MethodNode mn, MethodInsnNode call) {
        MethodHandleLookupTarget target = previousReflectiveLookupTarget(mn, call);
        if (target == null) return null;
        return resolveMethodHandleLookupPlan(pctx, target);
    }

    private MethodPlan resolveReflectiveInvokePlan(PipelineContext pctx, MethodNode mn, MethodInsnNode call) {
        MethodInsnNode lookup = previousReflectiveLookup(call);
        return lookup == null ? null : resolveReflectiveLookupPlan(pctx, mn, lookup);
    }

    private MethodInsnNode previousReflectiveLookup(MethodInsnNode invokeCall) {
        int scanned = 0;
        for (AbstractInsnNode scan = invokeCall.getPrevious(); scan != null && scanned++ < 256; scan = scan.getPrevious()) {
            if (!(scan instanceof MethodInsnNode call)) continue;
            if ("java/lang/Class".equals(call.owner)
                && ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name))
                && "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private InsnList rewriteMethodLookup(MethodNode mn, MethodPlan plan) {
        int paramsLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int nameLocal = allocateLocal(mn, Type.getType(String.class));
        int classLocal = allocateLocal(mn, Type.getType(Class.class));
        InsnList out = new InsnList();
        out.add(new VarInsnNode(Opcodes.ASTORE, paramsLocal));
        out.add(new VarInsnNode(Opcodes.ASTORE, nameLocal));
        out.add(new VarInsnNode(Opcodes.ASTORE, classLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        emitParameterTypes(out, plan == null
            ? new Type[] { OBJECT_ARRAY_TYPE }
            : Type.getArgumentTypes(plan.packedDesc()));
        return out;
    }

    private InsnList rewriteReflectiveInvoke(
        PipelineContext pctx,
        MethodNode mn,
        boolean hasTarget,
        MethodPlan plan,
        List<MethodPlan> runtimeCandidates,
        Integer callerKeyLocal
    ) {
        int argsLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int targetLocal = hasTarget ? allocateLocal(mn, Type.getType(Object.class)) : -1;
        int memberLocal = allocateLocal(mn, Type.getType(Object.class));
        int innerLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int outerLocal = allocateLocal(mn, OBJECT_ARRAY_TYPE);
        int splitKeyLocal = plan != null && plan.splitHiddenKey()
            ? allocateLocal(mn, Type.getType(Object.class))
            : -1;
        InsnList out = new InsnList();
        out.add(new VarInsnNode(Opcodes.ASTORE, argsLocal));
        if (hasTarget) {
            out.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
        }
        out.add(new VarInsnNode(Opcodes.ASTORE, memberLocal));
        if (plan == null) {
            emitDefaultReflectiveCarrier(out, argsLocal, innerLocal);
            emitRuntimeReflectiveCarrierSelection(
                pctx,
                out,
                memberLocal,
                argsLocal,
                innerLocal,
                runtimeCandidates,
                callerKeyLocal
            );
        } else {
            emitReflectiveCarrierForPlan(
                pctx,
                out,
                plan,
                argsLocal,
                innerLocal,
                splitKeyLocal,
                callerKeyLocal
            );
        }
        JvmPassBytecode.pushInt(out, plan != null && plan.splitHiddenKey() ? 2 : 1);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        out.add(new VarInsnNode(Opcodes.ASTORE, outerLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, outerLocal));
        JvmPassBytecode.pushInt(out, 0);
        out.add(new VarInsnNode(Opcodes.ALOAD, innerLocal));
        out.add(new InsnNode(Opcodes.AASTORE));
        if (plan != null && plan.splitHiddenKey()) {
            out.add(new VarInsnNode(Opcodes.ALOAD, outerLocal));
            JvmPassBytecode.pushInt(out, 1);
            out.add(new VarInsnNode(Opcodes.ALOAD, splitKeyLocal));
            out.add(new InsnNode(Opcodes.AASTORE));
        }
        out.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        if (hasTarget) {
            out.add(new VarInsnNode(Opcodes.ALOAD, targetLocal));
        }
        out.add(new VarInsnNode(Opcodes.ALOAD, outerLocal));
        return out;
    }

    private void emitDefaultReflectiveCarrier(InsnList out, int argsLocal, int innerLocal) {
        LabelNode copyExisting = new LabelNode();
        LabelNode done = new LabelNode();
        out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        out.add(new JumpInsnNode(Opcodes.IFNONNULL, copyExisting));
        JvmPassBytecode.pushInt(out, 0);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        out.add(new VarInsnNode(Opcodes.ASTORE, innerLocal));
        out.add(new JumpInsnNode(Opcodes.GOTO, done));
        out.add(copyExisting);
        out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        out.add(new VarInsnNode(Opcodes.ASTORE, innerLocal));
        out.add(done);
    }

    private void emitReflectiveCarrierForPlan(
        PipelineContext pctx,
        InsnList out,
        MethodPlan plan,
        int argsLocal,
        int innerLocal,
        int splitKeyLocal,
        Integer callerKeyLocal
    ) {
        JvmPassBytecode.pushInt(out, carrierArgumentCount(plan));
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        out.add(new VarInsnNode(Opcodes.ASTORE, innerLocal));
        out.add(new VarInsnNode(Opcodes.ALOAD, innerLocal));
        emitCarrierIndexKeyStore(pctx, out, plan, callerKeyLocal, "reflective target");
        out.add(new InsnNode(Opcodes.POP));
        int sourceIndex = 0;
        int carrierIndex = 0;
        Type[] args = plan.argumentTypes();
        for (int i = 0; i < args.length; i++) {
            if (isHiddenKeyArgument(plan, i)) {
                if (callerKeyLocal == null) {
                    throw new IllegalStateException(
                        "Missing caller key for reflective methodParameterObfuscation target " +
                            plan.owner() + "." + plan.finalName() + plan.packedDesc()
                    );
                }
                VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, callerKeyLocal);
                cffKeyLoadTargetSeeds(pctx).put(keyLoad, seedForPlan(pctx, plan));
                if (plan.splitHiddenKey()) {
                    out.add(keyLoad);
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false));
                    out.add(new VarInsnNode(Opcodes.ASTORE, splitKeyLocal));
                } else {
                    out.add(new VarInsnNode(Opcodes.ALOAD, innerLocal));
                    emitCarrierIndexDecodeMarker(
                        pctx,
                        out,
                        plan,
                        carrierIndex++,
                        callerKeyLocal,
                        true,
                        plan.carrierIndexKeySeed()
                    );
                    out.add(keyLoad);
                    out.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Long", "valueOf",
                        "(J)Ljava/lang/Long;", false));
                    out.add(new InsnNode(Opcodes.AASTORE));
                }
                continue;
            }
            out.add(new VarInsnNode(Opcodes.ALOAD, innerLocal));
            emitCarrierIndexDecodeMarker(
                pctx,
                out,
                plan,
                carrierIndex++,
                callerKeyLocal,
                true,
                plan.carrierIndexKeySeed()
            );
            out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
            JvmPassBytecode.pushInt(out, sourceIndex++);
            out.add(new InsnNode(Opcodes.AALOAD));
            out.add(new InsnNode(Opcodes.AASTORE));
        }
    }

    private void emitRuntimeReflectiveCarrierSelection(
        PipelineContext pctx,
        InsnList out,
        int memberLocal,
        int argsLocal,
        int innerLocal,
        List<MethodPlan> runtimeCandidates,
        Integer callerKeyLocal
    ) {
        if (callerKeyLocal == null) return;
        List<MethodPlan> candidates = new ArrayList<>();
        for (MethodPlan candidate : runtimeCandidates) {
            if (!candidate.hasCode() || candidate.splitHiddenKey()) continue;
            if (packedHiddenKeyArgumentIndex(candidate) < 0) continue;
            candidates.add(candidate);
        }
        if (candidates.isEmpty()) return;

        LabelNode done = new LabelNode();
        for (MethodPlan candidate : candidates) {
            LabelNode next = new LabelNode();
            emitRuntimeMethodMatch(out, memberLocal, candidate, next);
            emitReflectiveCarrierForPlan(
                pctx,
                out,
                candidate,
                argsLocal,
                innerLocal,
                -1,
                callerKeyLocal
            );
            out.add(new JumpInsnNode(Opcodes.GOTO, done));
            out.add(next);
        }
        out.add(done);
    }

    private List<MethodPlan> runtimeReflectiveInvokeCandidates(PipelineContext pctx, MethodNode mn, MethodInsnNode invokeCall) {
        MethodInsnNode lookupCall = previousReflectiveLookup(invokeCall);
        if (lookupCall != null) {
            MethodHandleLookupTarget target = previousReflectiveLookupTarget(mn, lookupCall);
            if (target != null) {
                List<MethodPlan> plans = plansMatchingReflectionTarget(pctx, target.owner(), target.name());
                if (!plans.isEmpty()) return plans;
            }
        }
        String arrayOwner = previousMethodArrayOwner(invokeCall);
        if (arrayOwner != null) {
            List<MethodPlan> plans = plansMatchingReflectionTarget(pctx, arrayOwner, null);
            if (!plans.isEmpty()) return plans;
        }
        if (lookupCall != null) {
            MethodHandleLookupTarget target = previousReflectiveLookupTarget(mn, lookupCall);
            if (target != null && target.name() != null) {
                return plansMatchingReflectionTarget(pctx, null, target.name());
            }
        }
        return List.of();
    }

    private List<MethodPlan> plansMatchingReflectionTarget(PipelineContext pctx, String owner, String name) {
        List<MethodPlan> plans = new ArrayList<>();
        for (MethodPlan plan : planByFinalKey(pctx).values()) {
            if (owner != null && !owner.equals(plan.owner())) continue;
            if (name != null && !name.equals(plan.oldName()) && !name.equals(plan.finalName())) continue;
            plans.add(plan);
        }
        return plans;
    }

    private String previousMethodArrayOwner(MethodInsnNode invokeCall) {
        int scanned = 0;
        for (AbstractInsnNode scan = invokeCall.getPrevious(); scan != null && scanned++ < 512; scan = scan.getPrevious()) {
            if (!(scan instanceof MethodInsnNode call) || !isReflectionMethodArrayLookup(call)) continue;
            String owner = previousClassLiteral(call);
            if (owner != null) return owner;
        }
        return null;
    }

    private static void emitRuntimeMethodMatch(
        InsnList out,
        int memberLocal,
        MethodPlan candidate,
        LabelNode next
    ) {
        out.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/reflect/Method"));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
            "getDeclaringClass", "()Ljava/lang/Class;", false));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class",
            "getName", "()Ljava/lang/String;", false));
        out.add(new LdcInsnNode(candidate.owner().replace('/', '.')));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
            "equals", "(Ljava/lang/Object;)Z", false));
        out.add(new JumpInsnNode(Opcodes.IFEQ, next));

        out.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/reflect/Method"));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
            "getName", "()Ljava/lang/String;", false));
        out.add(new LdcInsnNode(candidate.finalName()));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
            "equals", "(Ljava/lang/Object;)Z", false));
        out.add(new JumpInsnNode(Opcodes.IFEQ, next));

        Type[] packedArgs = Type.getArgumentTypes(candidate.packedDesc());
        out.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/reflect/Method"));
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
            "getParameterCount", "()I", false));
        JvmPassBytecode.pushInt(out, packedArgs.length);
        out.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
        for (int i = 0; i < packedArgs.length; i++) {
            out.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
            out.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/reflect/Method"));
            out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/reflect/Method",
                "getParameterTypes", "()[Ljava/lang/Class;", false));
            JvmPassBytecode.pushInt(out, i);
            out.add(new InsnNode(Opcodes.AALOAD));
            emitClassConstant(out, packedArgs[i]);
            out.add(new JumpInsnNode(Opcodes.IF_ACMPNE, next));
        }
    }

    private void emitPackedParameterTypes(InsnList out) {
        emitParameterTypes(out, new Type[] { OBJECT_ARRAY_TYPE });
    }

    private void emitParameterTypes(InsnList out, Type[] parameterTypes) {
        JvmPassBytecode.pushInt(out, parameterTypes.length);
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Class"));
        for (int i = 0; i < parameterTypes.length; i++) {
            out.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(out, i);
            emitClassConstant(out, parameterTypes[i]);
            out.add(new InsnNode(Opcodes.AASTORE));
        }
    }

    private InsnList packCallArguments(
        PipelineContext pctx,
        MethodNode mn,
        MethodPlan plan,
        Integer callerKeyLocal
    ) {
        Type[] args = plan.argumentTypes();
        int[] locals = new int[args.length];
        for (int i = args.length - 1; i >= 0; i--) {
            locals[i] = allocateLocal(mn, args[i]);
        }
        InsnList out = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), locals[i]));
        }
        JvmPassBytecode.pushInt(out, carrierArgumentCount(plan));
        out.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        emitCarrierIndexKeyStore(pctx, out, plan, callerKeyLocal, "target");
        int carrierIndex = 0;
        for (int i = 0; i < args.length; i++) {
            if (plan.splitHiddenKey() && isHiddenKeyArgument(plan, i)) {
                continue;
            }
            out.add(new InsnNode(Opcodes.DUP));
            emitCarrierIndexDecodeMarker(
                pctx,
                out,
                plan,
                carrierIndex++,
                callerKeyLocal,
                true,
                plan.carrierIndexKeySeed()
            );
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
        if (plan.splitHiddenKey()) {
            if (callerKeyLocal == null) {
                throw new IllegalStateException(
                    "Missing caller key for methodParameterObfuscation target " +
                        plan.owner() + "." + plan.finalName() + plan.packedDesc()
                );
            }
            VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, callerKeyLocal);
            out.add(keyLoad);
            cffKeyLoadTargetSeeds(pctx).put(keyLoad, seedForPlan(pctx, plan));
        }
        JvmKeyDispatchPass.markGenerated(pctx, out);
        return out;
    }

    private static boolean isHiddenKeyArgument(MethodPlan plan, int index) {
        return packedHiddenKeyArgumentIndex(plan) == index;
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

    private static long requireMethodSeed(PipelineContext pctx, MethodPlan plan, String finalKey) {
        Long seed = JvmKeyDispatchPass.findMethodSeed(pctx, finalKey);
        if (seed == null) {
            throw new IllegalStateException(
                "Missing keyDispatch seed for methodParameterObfuscation carrier index plan " +
                    plan.owner() + "." + plan.finalName() + plan.packedDesc()
            );
        }
        return seed;
    }

    private static long carrierIndexKeySeed(CarrierIndexPlan plan) {
        long seed = JvmPassBytecode.mix(plan.seed(), 0x4341525249455249L);
        return seed == 0L ? 0x4341525249455249L : seed;
    }

    private static long incomingRawForCanonical(long targetSeed) {
        return JvmKeyDispatchPass.incomingRawForCanonical(targetSeed);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Long> cffPackedCallTargetSeeds(PipelineContext pctx) {
        Map<String, Long> map = pctx.getPassData(CFF_PACKED_CALL_TARGET_SEED);
        if (map == null) {
            map = new LinkedHashMap<>();
            pctx.putPassData(CFF_PACKED_CALL_TARGET_SEED, map);
        }
        return map;
    }

    private static void emitObfuscatedLong(InsnList out, long value, long seed) {
        long mask = JvmPassBytecode.mix(seed, value);
        JvmPassBytecode.pushLong(out, value ^ mask);
        JvmPassBytecode.pushLong(out, mask);
        out.add(new InsnNode(Opcodes.LXOR));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, CarrierIndexPlan> carrierIndexPlans(PipelineContext pctx) {
        Map<String, CarrierIndexPlan> map = pctx.getPassData(CARRIER_INDEX_PLAN_BY_FINAL_KEY);
        if (map == null) {
            map = new LinkedHashMap<>();
            pctx.putPassData(CARRIER_INDEX_PLAN_BY_FINAL_KEY, map);
        }
        return map;
    }

    @SuppressWarnings("unchecked")
    private static Map<AbstractInsnNode, CarrierIndexDecodeSite> carrierIndexDecodeSites(PipelineContext pctx) {
        Map<AbstractInsnNode, CarrierIndexDecodeSite> map = pctx.getPassData(CARRIER_INDEX_DECODE_SITES);
        if (map == null) {
            map = new IdentityHashMap<>();
            pctx.putPassData(CARRIER_INDEX_DECODE_SITES, map);
        }
        return map;
    }

    private static CarrierIndexPlan createCarrierIndexPlan(MethodPlan plan, long targetSeed, String carrierFamily) {
        int count = carrierArgumentCount(plan);
        long seed = JvmPassBytecode.mix(targetSeed ^ CARRIER_INDEX_DOMAIN, carrierFamily.hashCode());
        seed = JvmPassBytecode.mix(seed, plan.packedDesc().hashCode());
        seed = JvmPassBytecode.mix(seed, plan.oldDesc().hashCode());
        seed = JvmPassBytecode.mix(seed, count);
        int step = carrierPermutationStep(count, seed);
        int encodedStep = step ^ carrierIndexWord(seed, 0x53544550);
        int encodedCount = count ^ carrierIndexWord(seed, 0x434F554E);
        int classKeyIdentity = carrierIndexWord(seed, 0x4B594944)
            ^ plan.owner().hashCode()
            ^ plan.packedDesc().hashCode();
        int classWordIndex = Math.floorMod(
            carrierIndexWord(seed ^ classKeyIdentity, 0x574F5244),
            CARRIER_INDEX_CLASS_WORDS
        );
        CarrierIndexCell[] cells = new CarrierIndexCell[count];
        for (int logical = 0; logical < count; logical++) {
            int physicalSlot = count == 0 ? 0 : Math.floorMod(logical * step, count);
            long cellSeed = JvmPassBytecode.mix(seed, logical ^ 0x43454C4CL);
            int guardMask = carrierIndexWord(cellSeed ^ classKeyIdentity, 0x47554152);
            cells[logical] = new CarrierIndexCell(
                logical,
                physicalSlot,
                Math.floorMod(classWordIndex + logical, CARRIER_INDEX_CLASS_WORDS),
                carrierIndexWord(cellSeed, 0x4D41534B),
                guardMask | 1
            );
        }
        return new CarrierIndexPlan(count, seed, encodedCount, encodedStep, classKeyIdentity, classWordIndex, cells);
    }

    private static int carrierPermutationStep(int count, long seed) {
        if (count <= 1) return 1;
        int candidate = Math.floorMod(carrierIndexWord(seed, 0x53544550), count);
        if (candidate == 0) candidate = 1;
        for (int i = 0; i < count; i++) {
            int step = 1 + Math.floorMod(candidate + i, count);
            if (gcd(step, count) == 1) return step;
        }
        return 1;
    }

    private static int gcd(int a, int b) {
        a = Math.abs(a);
        b = Math.abs(b);
        while (b != 0) {
            int next = a % b;
            a = b;
            b = next;
        }
        return a == 0 ? 1 : a;
    }

    private static int carrierIndexWord(long seed, int domain) {
        long mixed = JvmPassBytecode.mix(seed, domain ^ 0x49445831L);
        return (int) mixed ^ Integer.rotateLeft((int) (mixed >>> 32), 13);
    }

    public static int rewriteCarrierIndexDecodeSites(
        PipelineContext pctx,
        MethodNode mn,
        ControlFlowFlatteningPass.CffClassKeyTable table,
        int keyLocal
    ) {
        Map<AbstractInsnNode, CarrierIndexDecodeSite> sites = carrierIndexDecodeSites(pctx);
        int replaced = 0;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; ) {
            AbstractInsnNode next = insn.getNext();
            if (isCarrierIndexMarker(insn)) {
                CarrierIndexDecodeSite site = sites.remove(insn);
                if (site == null) {
                    throw new IllegalStateException("Unregistered carrier index decode marker");
                }
                InsnList replacement = new InsnList();
                emitDecodedCarrierIndex(
                    pctx,
                    replacement,
                    site.plan(),
                    site.logicalIndex(),
                    site.keyLocal() < 0 ? keyLocal : site.keyLocal(),
                    site.keyLocal() >= 0,
                    site.rewriteKeyToTarget(),
                    table,
                    site.targetSeed()
                );
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                replaced++;
            }
            insn = next;
        }
        return replaced;
    }

    private static boolean isCarrierIndexMarker(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call
            && call.getOpcode() == Opcodes.INVOKESTATIC
            && CARRIER_INDEX_MARKER_OWNER.equals(call.owner)
            && CARRIER_INDEX_MARKER_NAME.equals(call.name)
            && CARRIER_INDEX_MARKER_DESC.equals(call.desc);
    }

    private static void emitDecodedCarrierIndex(
        PipelineContext pctx,
        InsnList out,
        CarrierIndexPlan plan,
        int logicalIndex,
        int keyLocal,
        boolean useLiveKey,
        boolean rewriteKeyToTarget,
        ControlFlowFlatteningPass.CffClassKeyTable table,
        long targetSeed
    ) {
        if (useLiveKey && keyLocal < 0) {
            throw new IllegalStateException("Carrier index decoding requires a live method key local");
        }
        CarrierIndexCell cell = plan.cell(logicalIndex);
        int classWord = table.values()[cell.classWordIndex()];
        long expectedKey = useLiveKey ? JvmKeyDispatchPass.incomingRawForCanonical(targetSeed) : targetSeed;
        int mask = carrierIndexRuntimeMask(plan, cell, classWord, expectedKey, useLiveKey);
        JvmPassBytecode.pushInt(out, cell.physicalSlot() ^ mask);
        emitCarrierIndexRuntimeMask(pctx, out, plan, cell, keyLocal, useLiveKey, rewriteKeyToTarget, table, targetSeed);
        out.add(new InsnNode(Opcodes.IXOR));
    }

    private static void emitCarrierIndexRuntimeMask(
        PipelineContext pctx,
        InsnList out,
        CarrierIndexPlan plan,
        CarrierIndexCell cell,
        int keyLocal,
        boolean useLiveKey,
        boolean rewriteKeyToTarget,
        ControlFlowFlatteningPass.CffClassKeyTable table,
        long targetSeed
    ) {
        out.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(out, ControlFlowFlatteningPass.CLASS_KEY_WORDS_SLOT);
        out.add(new InsnNode(Opcodes.AALOAD));
        out.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        JvmPassBytecode.pushInt(out, cell.classWordIndex());
        out.add(new InsnNode(Opcodes.IALOAD));
        JvmPassBytecode.pushInt(out, ControlFlowFlatteningPass.CLASS_KEY_WORD_SEAL);
        out.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(out, cell.mixSalt());
        out.add(new InsnNode(Opcodes.IXOR));
        if (useLiveKey) {
            VarInsnNode lowKeyLoad = new VarInsnNode(Opcodes.LLOAD, keyLocal);
            out.add(lowKeyLoad);
            if (rewriteKeyToTarget) {
                cffKeyLoadTargetSeeds(pctx).put(lowKeyLoad, targetSeed);
            }
            out.add(new InsnNode(Opcodes.L2I));
        } else {
            JvmPassBytecode.pushInt(out, (int) targetSeed);
        }
        out.add(new InsnNode(Opcodes.IADD));
        out.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(out, 13);
        out.add(new InsnNode(Opcodes.IUSHR));
        out.add(new InsnNode(Opcodes.IXOR));
        if (useLiveKey) {
            VarInsnNode highKeyLoad = new VarInsnNode(Opcodes.LLOAD, keyLocal);
            out.add(highKeyLoad);
            if (rewriteKeyToTarget) {
                cffKeyLoadTargetSeeds(pctx).put(highKeyLoad, targetSeed);
            }
            JvmPassBytecode.pushInt(out, 32);
            out.add(new InsnNode(Opcodes.LUSHR));
            out.add(new InsnNode(Opcodes.L2I));
        } else {
            JvmPassBytecode.pushInt(out, (int) (targetSeed >>> 32));
        }
        JvmPassBytecode.pushInt(out, carrierIndexWord(plan.seed(), 0x48494748));
        out.add(new InsnNode(Opcodes.IXOR));
        out.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(out, cell.guardMask());
        out.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(out, carrierIndexWord(plan.seed() ^ plan.classKeyIdentity(), 0x47554152) | 1);
        out.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(out, plan.encodedStep() ^ carrierIndexWord(plan.seed(), 0x53544550));
        out.add(new InsnNode(Opcodes.IXOR));
        out.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(out, 17);
        out.add(new InsnNode(Opcodes.IUSHR));
        out.add(new InsnNode(Opcodes.IADD));
    }

    private static int carrierIndexRuntimeMask(
        CarrierIndexPlan plan,
        CarrierIndexCell cell,
        int classWord,
        long key,
        boolean useLiveKey
    ) {
        int x = classWord ^ cell.mixSalt();
        x += (int) key;
        x ^= x >>> 13;
        x += ((int) (key >>> 32)) ^ carrierIndexWord(plan.seed(), 0x48494748);
        x ^= cell.guardMask();
        x *= carrierIndexWord(plan.seed() ^ plan.classKeyIdentity(), 0x47554152) | 1;
        x ^= plan.encodedStep() ^ carrierIndexWord(plan.seed(), 0x53544550);
        x += x >>> 17;
        return x;
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

    private static void emitCarrierArrayLoad(
        PipelineContext pctx,
        InsnList out,
        int argsLocal,
        MethodPlan plan,
        int logicalIndex,
        int keyLocal,
        boolean rewriteKeyToTarget,
        long targetSeed
    ) {
        out.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        emitCarrierIndexDecodeMarker(pctx, out, plan, logicalIndex, keyLocal, rewriteKeyToTarget, targetSeed);
        out.add(new InsnNode(Opcodes.AALOAD));
    }

    private static void emitCarrierIndexKeyStore(
        PipelineContext pctx,
        InsnList out,
        MethodPlan plan,
        Integer callerKeyLocal,
        String targetKind
    ) {
        if (packedHiddenKeyArgumentIndex(plan) < 0) return;
        if (callerKeyLocal == null) {
            throw new IllegalStateException(
                "Missing caller key for methodParameterObfuscation carrier index key " + targetKind + " " +
                    plan.owner() + "." + plan.finalName() + plan.packedDesc()
            );
        }
        out.add(new InsnNode(Opcodes.DUP));
        emitCarrierIndexDecodeMarker(
            pctx,
            out,
            plan,
            carrierIndexKeyLogicalIndex(plan),
            -1,
            false,
            plan.carrierIndexKeySeed()
        );
        VarInsnNode keyLoad = new VarInsnNode(Opcodes.LLOAD, callerKeyLocal);
        out.add(keyLoad);
        cffKeyLoadTargetSeeds(pctx).put(keyLoad, plan.carrierIndexKeySeed());
        emitBox(out, Type.LONG_TYPE);
        out.add(new InsnNode(Opcodes.AASTORE));
    }

    private static void emitCarrierIndexDecodeMarker(
        PipelineContext pctx,
        InsnList out,
        MethodPlan plan,
        int logicalIndex,
        Integer keyLocal,
        boolean rewriteKeyToTarget,
        long targetSeed
    ) {
        CarrierIndexPlan indexPlan = plan.carrierIndexPlan();
        if (indexPlan == null) {
            throw new IllegalStateException(
                "Missing carrier index plan for " + plan.owner() + "." + plan.finalName() + plan.packedDesc()
            );
        }
        MethodInsnNode marker = new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            CARRIER_INDEX_MARKER_OWNER,
            CARRIER_INDEX_MARKER_NAME,
            CARRIER_INDEX_MARKER_DESC,
            false
        );
        carrierIndexDecodeSites(pctx).put(marker, new CarrierIndexDecodeSite(
            indexPlan,
            logicalIndex,
            plan.owner(),
            plan.finalName(),
            plan.packedDesc(),
            targetSeed,
            keyLocal == null ? -1 : keyLocal,
            rewriteKeyToTarget
        ));
        out.add(marker);
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
        mn.signature = null;
        mn.parameters = null;
        mn.visibleParameterAnnotations = null;
        mn.invisibleParameterAnnotations = null;
        mn.visibleTypeAnnotations = null;
        mn.invisibleTypeAnnotations = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        if (mn.localVariables == null) return;
        for (LocalVariableNode local : mn.localVariables) {
            if (local.index > 0 || (mn.access & Opcodes.ACC_STATIC) != 0) {
                local.desc = "Ljava/lang/Object;";
                local.signature = null;
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
        private final boolean splitHiddenKey;
        private final boolean hasCode;
        private CarrierIndexPlan carrierIndexPlan;
        private long carrierIndexKeySeed;

        MethodPlan(
            String owner,
            String oldName,
            String oldDesc,
            String finalName,
            String packedDesc,
            Type[] argumentTypes,
            int[] argumentLocals,
            Integer keyLocal,
            boolean splitHiddenKey,
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
            this.splitHiddenKey = splitHiddenKey;
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
        CarrierIndexPlan carrierIndexPlan() { return carrierIndexPlan; }
        void carrierIndexPlan(CarrierIndexPlan value) { this.carrierIndexPlan = value; }
        long carrierIndexKeySeed() { return carrierIndexKeySeed; }
        void carrierIndexKeySeed(long value) { this.carrierIndexKeySeed = value; }
        boolean splitHiddenKey() { return splitHiddenKey; }
        boolean hasCode() { return hasCode; }
    }

    public record CarrierIndexPlan(
        int carrierCount,
        long seed,
        int encodedCount,
        int encodedStep,
        int classKeyIdentity,
        int classWordIndex,
        CarrierIndexCell[] cells
    ) {
        CarrierIndexCell cell(int logicalIndex) {
            if (logicalIndex < 0 || logicalIndex >= cells.length) {
                throw new IllegalStateException(
                    "Invalid carrier logical index " + logicalIndex + " for " + carrierCount
                );
            }
            return cells[logicalIndex];
        }
    }

    public record CarrierIndexCell(
        int logicalIndex,
        int physicalSlot,
        int classWordIndex,
        int mixSalt,
        int guardMask
    ) {}

    public record CarrierIndexDecodeSite(
        CarrierIndexPlan plan,
        int logicalIndex,
        String owner,
        String name,
        String desc,
        long targetSeed,
        int keyLocal,
        boolean rewriteKeyToTarget
    ) {}

    private record MethodHandleLookupTarget(String owner, String name) {}
}
