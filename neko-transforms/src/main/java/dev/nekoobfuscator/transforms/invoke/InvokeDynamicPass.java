package dev.nekoobfuscator.transforms.invoke;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.data.NumberEncryptionPass;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import dev.nekoobfuscator.transforms.key.KeyDispatcherSupport;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * InvokeDynamic Obfuscation: wraps method calls in invokedynamic instructions.
 * The target metadata is encrypted and recovered in the bootstrap method.
 */
public final class InvokeDynamicPass implements TransformPass {

    public static final String HARDEN_GENERATED_HELPERS_KEY = "invokeDynamic.hardenGeneratedHelpers";
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String SKIP_TRY_CATCH_METHODS_OPTION = "skipMethodsWithTryCatch";
    private static final String SKIP_SWITCH_METHODS_OPTION = "skipMethodsWithSwitches";
    private static final String SKIP_MONITOR_METHODS_OPTION = "skipMethodsWithMonitors";
    private static final String SKIP_SENSITIVE_API_METHODS_OPTION = "skipSensitiveApiMethods";
    private static final String SKIP_PRIMITIVE_LOOP_CALLS_OPTION = "skipPrimitiveLoopCalls";
    private static final String USE_CONTROL_FLOW_KEY_OPTION = "useControlFlowKey";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BRANCH_COUNT_OPTION = "maxBranchCount";
    private static final String WRAP_SPECIAL_CALLS_OPTION = "wrapSpecialCalls";
    private static final String BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;IIIIIIIII)Ljava/lang/invoke/CallSite;";
    private static final String LOCAL_BOOTSTRAP_PREFIX = "__neko_b";
    private static final String LOCAL_METADATA_PREFIX = "__neko_m";
    private static final String KEY_OWNER = "dev/nekoobfuscator/runtime/NekoKeyDerivation";
    private static final String STRING_DECRYPT_OWNER = "dev/nekoobfuscator/runtime/NekoStringDecryptor";
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final int CLASS_METADATA_ACCESS =
        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    private static final int INTERFACE_METADATA_ACCESS =
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;

    @Override public String id() { return "invokeDynamic"; }
    @Override public String name() { return "InvokeDynamic Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() {
        return Set.of("controlFlowFlattening", "stringEncryption", "numberEncryption");
    }

    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;
    private int targetCounter;
    private int metadataFieldCounter;
    private final Map<String, BootstrapProfile> bootstrapProfiles = new HashMap<>();
    private String metadataHelperName;
    private String metadataFieldPrefix;

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
        classKey = keyEngine.deriveClassKey(pctx.currentL1Class());
        targetCounter = 0;
        metadataFieldPrefix = uniqueMetadataFieldPrefix(pctx.currentL1Class(), pctx);
        metadataFieldCounter = countExistingMetadataFields(pctx.currentL1Class());
        bootstrapProfiles.clear();
        metadataHelperName = uniqueMethodName(pctx.currentL1Class(),
            LOCAL_METADATA_PREFIX + Integer.toUnsignedString(pctx.random().nextInt(), 36));
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        IdentityHashMap<AbstractInsnNode, Long> flowKeyValues = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        TransformConfig config = pctx.config().transforms().get("invokeDynamic");
        if (!method.hasCode()) return;
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)
                || (TransformGuards.isGeneratedMethod(method) && !isGeneratedInvokeTarget(method, hardenGeneratedHelpers))) {
            JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                "guarded-runtime-or-generated");
            return;
        }
        if (TransformGuards.isReflectionShapeSensitive(pctx, clazz)
                || TransformGuards.hasStackIntrospection(method)) {
            insertSafeInvokeGate(pctx, clazz, method, "reflection-or-stack-observer-safe-tier");
            return;
        }
        MethodRiskStats stats = analyzeMethod(method.instructions());
        double intensity = pctx.config().getTransformIntensity("invokeDynamic");
        InsnList insns = method.instructions();
        int methodNameHash = method.name().hashCode();
        int methodDescHash = method.descriptor().hashCode();
        long methodKey = DynamicKeyDerivationEngine.mix(
            DynamicKeyDerivationEngine.mix(classKey, methodNameHash), methodDescHash);

        List<MethodInsnNode> targets = new ArrayList<>();
        int safeTierCalls = 0;
        boolean constructorReady = !method.isConstructor();
        boolean wrapSpecialCalls = booleanOption(config, WRAP_SPECIAL_CALLS_OPTION, true);
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi) {
                if (method.isConstructor() && !constructorReady) {
                    if (mi.getOpcode() == Opcodes.INVOKESPECIAL && "<init>".equals(mi.name)) {
                        constructorReady = true;
                    }
                    continue;
                }
                int op = mi.getOpcode();
                if (op == Opcodes.INVOKEVIRTUAL || op == Opcodes.INVOKESTATIC || op == Opcodes.INVOKEINTERFACE
                        || (wrapSpecialCalls && op == Opcodes.INVOKESPECIAL)) {
                    if (!"<init>".equals(mi.name) && !"<clinit>".equals(mi.name) && !mi.owner.startsWith("[")
                            && !TransformGuards.isSupportCall(mi)) {
                        if (isSensitiveApiCall(mi)) {
                            safeTierCalls++;
                            continue;
                        }
                        if (!shouldSkipCall(method, mi, stats, pctx.config().transforms().get("invokeDynamic"))
                                && pctx.random().nextDouble() <= intensity) {
                            targets.add(mi);
                        }
                    }
                }
            }
        }

        if (targets.isEmpty()) {
            if (safeTierCalls > 0) {
                insertSafeInvokeGate(pctx, clazz, method, "sensitive-call-safe-tier");
            } else {
                JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                    "no-legal-method-calls");
            }
            return;
        }
        KeyDispatcherSupport.Profile keyDispatcher = KeyDispatcherSupport.enabled(config)
            ? KeyDispatcherSupport.getOrCreate(pctx, clazz, config, "invoke", methodNameHash)
            : null;
        BootstrapProfile bootstrapProfile = getOrCreateBootstrap(clazz, pctx, keyDispatcher);

        MethodNode mn = method.asmNode();
        for (MethodInsnNode mi : targets) {
            int targetId = targetCounter++;
            int siteSalt = pctx.random().nextInt();
            boolean useFlowKey = booleanOption(config, USE_CONTROL_FLOW_KEY_OPTION, false)
                && flowKeyValues != null
                && flowKeyValues.containsKey(mi);
            long flowKey = useFlowKey ? flowKeyValues.get(mi) : 0L;

            int siteId = metadataFieldCounter++;
            long siteBaseKey = deriveSiteBaseKey(methodKey, siteSalt, targetId, mi.getOpcode(), flowKey, useFlowKey);
            int keyComponent = siteId ^ siteSalt ^ targetId;
            long effectiveSiteBaseKey = keyDispatcher != null
                ? KeyDispatcherSupport.dispatch(keyDispatcher, siteBaseKey, keyComponent)
                : siteBaseKey;
            addEncryptedMetadataField(clazz, siteId, 0, mi.owner, deriveMetadataKey(effectiveSiteBaseKey, 1));
            addEncryptedMetadataField(clazz, siteId, 1, mi.name, deriveMetadataKey(effectiveSiteBaseKey, 2));
            addEncryptedMetadataField(clazz, siteId, 2, mi.desc, deriveMetadataKey(effectiveSiteBaseKey, 3));

            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                opaqueCallsiteName(pctx),
                invokedynamicDescriptor(mi),
                new Handle(Opcodes.H_INVOKESTATIC, clazz.name(), bootstrapProfile.methodName(),
                    BOOTSTRAP_DESC, (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0),
                siteId,
                methodNameHash,
                methodDescHash,
                siteSalt,
                mi.getOpcode(),
                targetId,
                useFlowKey ? 1 : 0,
                keyDispatcher != null ? 1 : 0,
                keyComponent
            );
            insns.set(mi, indy);
        }

        // Regenerate the metadata helper so its switch covers every site we've
        // added so far (including any added during this method).
        if (!targets.isEmpty()) {
            ensureMetadataHelper(clazz);
        }
        pctx.currentL1Class().markDirty();
        JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(),
            "local-bootstrap-invokedynamic-sites=" + targets.size());
    }

    private String invokedynamicDescriptor(MethodInsnNode mi) {
        if (mi.getOpcode() == Opcodes.INVOKESTATIC) {
            return mi.desc;
        }
        Type[] args = Type.getArgumentTypes(mi.desc);
        Type[] indyArgs = new Type[args.length + 1];
        indyArgs[0] = Type.getObjectType(mi.owner);
        System.arraycopy(args, 0, indyArgs, 1, args.length);
        return Type.getMethodDescriptor(Type.getReturnType(mi.desc), indyArgs);
    }

    private void insertSafeInvokeGate(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        LabelNode body = new LabelNode();
        LabelNode dflt = new LabelNode();
        int state = pctx.random().nextInt();
        int mask = pctx.random().nextInt() | 1;
        InsnList gate = new InsnList();
        gate.add(pushInt(state ^ mask));
        gate.add(pushInt(mask));
        gate.add(new InsnNode(Opcodes.IXOR));
        gate.add(new LookupSwitchInsnNode(dflt, new int[] { state }, new LabelNode[] { body }));
        gate.add(dflt);
        gate.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        gate.add(new InsnNode(Opcodes.DUP));
        gate.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        gate.add(new InsnNode(Opcodes.ATHROW));
        gate.add(body);
        if (method.isConstructor()) {
            AbstractInsnNode anchor = firstConstructorInitCall(method);
            if (anchor == null) {
                JvmObfuscationCoverage.get(pctx).failClosed(id(), clazz.name(), method.name(), method.descriptor(),
                    "constructor-safe-invoke-gate-no-init-anchor");
                throw new IllegalStateException("Cannot place invokeDynamic safe gate in "
                    + clazz.name() + "." + method.name() + method.descriptor());
            }
            method.instructions().insert(anchor, gate);
        } else {
            method.instructions().insert(gate);
        }
        method.asmNode().maxStack = Math.max(method.asmNode().maxStack, 4);
        clazz.markDirty();
        JvmObfuscationCoverage.get(pctx).safe(id(), clazz.name(), method.name(), method.descriptor(), reason);
        pctx.invalidate(method);
    }


    private AbstractInsnNode firstConstructorInitCall(L1Method method) {
        if (!method.isConstructor()) return null;
        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode mi
                    && mi.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(mi.name)) {
                return insn;
            }
        }
        return null;
    }

    private boolean isMethodEligible(PipelineContext pctx, L1Method method, MethodRiskStats stats) {
        TransformConfig config = pctx.config().transforms().get("invokeDynamic");
        if (!method.tryCatchBlocks().isEmpty() && booleanOption(config, SKIP_TRY_CATCH_METHODS_OPTION, false)) {
            return false;
        }
        if (stats.hasSwitch() && booleanOption(config, SKIP_SWITCH_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasMonitor() && booleanOption(config, SKIP_MONITOR_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasSensitiveApi() && booleanOption(config, SKIP_SENSITIVE_API_METHODS_OPTION, true)) {
            return false;
        }
        if (method.instructionCount() > intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 260)) {
            return false;
        }
        return stats.branchCount() <= intOption(config, MAX_BRANCH_COUNT_OPTION, 24);
    }

    private boolean shouldSkipCall(L1Method method, MethodInsnNode mi, MethodRiskStats stats, TransformConfig config) {
        if (mi.owner.startsWith("dev/nekoobfuscator/runtime/")) {
            return true;
        }
        if (mi.name.startsWith("__neko_")) {
            return true;
        }
        if (stats.backwardBranches() > 0 && booleanOption(config, SKIP_PRIMITIVE_LOOP_CALLS_OPTION, false)
                && hasPrimitiveSignature(mi.desc)) {
            return false;
        }
        return false;
    }

    private boolean isGeneratedInvokeTarget(L1Method method, boolean hardenGeneratedHelpers) {
        return hardenGeneratedHelpers && method.name().startsWith("__neko_o");
    }

    private MethodRiskStats analyzeMethod(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        int branchCount = 0;
        int backwardBranches = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;
        boolean hasSensitiveApi = false;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                branchCount++;
                Integer from = positions.get(insn);
                Integer target = positions.get(jump.label);
                if (from != null && target != null && target <= from) {
                    backwardBranches++;
                }
                continue;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                continue;
            }
            if (insn instanceof MethodInsnNode mi && isSensitiveApiCall(mi)) {
                hasSensitiveApi = true;
            }
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
        }
        return new MethodRiskStats(branchCount, backwardBranches, hasSwitch, hasMonitor, hasSensitiveApi);
    }

    private boolean hasPrimitiveSignature(String descriptor) {
        for (Type argType : Type.getArgumentTypes(descriptor)) {
            if (isPrimitive(argType)) return true;
        }
        return isPrimitive(Type.getReturnType(descriptor));
    }

    private boolean isPrimitive(Type type) {
        int sort = type.getSort();
        return sort != Type.OBJECT && sort != Type.ARRAY && sort != Type.METHOD && sort != Type.VOID;
    }

    private boolean isHotPrimitiveOwner(String methodOwner, String targetOwner) {
        return false;
    }

    private boolean isSensitiveApiCall(MethodInsnNode mi) {
        String owner = mi.owner;
        if (owner.startsWith("java/lang/reflect/")) return true;
        if (owner.startsWith("java/lang/annotation/")) return true;
        if (owner.equals("java/lang/Class") || owner.startsWith("java/lang/ClassLoader")) return true;
        if (owner.equals("java/lang/invoke/MethodHandles") || owner.startsWith("java/lang/invoke/MethodHandle")) return true;
        if (owner.equals("java/lang/StackWalker")) return true;
        return (owner.equals("java/lang/Thread") || owner.equals("java/lang/Throwable"))
            && "getStackTrace".equals(mi.name);
    }

    private boolean isNekoSupportMethod(L1Method method) {
        if (method.owner().name().startsWith("dev/nekoobfuscator/runtime/")) {
            return true;
        }
        return method.name().startsWith("__neko_");
    }

    private long deriveSiteBaseKey(long methodKey, int siteSalt, int targetId, int invokeType,
            long flowKey, boolean useFlowKey) {
        long siteKey = DynamicKeyDerivationEngine.mix(methodKey, siteSalt);
        siteKey = DynamicKeyDerivationEngine.mix(siteKey, targetId);
        siteKey = DynamicKeyDerivationEngine.mix(siteKey, invokeType);
        if (useFlowKey) {
            siteKey = DynamicKeyDerivationEngine.mix(siteKey, flowKey);
        }
        return siteKey;
    }

    private long deriveMetadataKey(long siteBaseKey, int componentId) {
        return DynamicKeyDerivationEngine.finalize_(DynamicKeyDerivationEngine.mix(siteBaseKey, componentId));
    }

    private void addEncryptedMetadataField(L1Class clazz, int siteId, int component, String value, long key) {
        String fieldName = metadataFieldName(siteId, component);
        byte[] encrypted = DynamicKeyDerivationEngine.encrypt(value.getBytes(StandardCharsets.UTF_8), key);
        FieldNode fn = new FieldNode(
            metadataAccess(clazz.asmNode()),
            fieldName, "Ljava/lang/String;", null,
            new String(encrypted, StandardCharsets.ISO_8859_1));
        clazz.asmNode().fields.add(fn);
    }

    private int countExistingMetadataFields(L1Class clazz) {
        int maxSiteId = -1;
        for (FieldNode fn : clazz.asmNode().fields) {
            if (!fn.name.startsWith(metadataFieldPrefix)
                    || (!"[B".equals(fn.desc) && !"Ljava/lang/String;".equals(fn.desc))) {
                continue;
            }
            int suffixStart = metadataFieldPrefix.length();
            int suffixEnd = fn.name.indexOf('_', suffixStart);
            if (suffixEnd < 0) {
                suffixEnd = fn.name.length() - 1;
            }
            if (suffixEnd <= suffixStart) continue;
            try {
                int siteId = Integer.parseInt(fn.name.substring(suffixStart, suffixEnd));
                maxSiteId = Math.max(maxSiteId, siteId);
            } catch (NumberFormatException ignored) {
            }
        }
        return maxSiteId + 1;
    }

    private String metadataFieldName(int siteId, int component) {
        return metadataFieldPrefix + siteId + "_" + component;
    }

    private String uniqueMetadataFieldPrefix(L1Class clazz, PipelineContext pctx) {
        String prefix;
        do {
            prefix = "m" + Integer.toUnsignedString(pctx.random().nextInt(), 36) + "_";
        } while (hasFieldPrefix(clazz, prefix));
        return prefix;
    }

    private boolean hasFieldPrefix(L1Class clazz, String prefix) {
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.name.startsWith(prefix)) return true;
        }
        return false;
    }

    private String opaqueCallsiteName(PipelineContext pctx) {
        return "_" + Integer.toUnsignedString(pctx.random().nextInt(), 36);
    }

    private int metadataAccess(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0
            ? INTERFACE_METADATA_ACCESS
            : CLASS_METADATA_ACCESS;
    }

    private BootstrapProfile getOrCreateBootstrap(L1Class clazz, PipelineContext pctx,
            KeyDispatcherSupport.Profile keyDispatcher) {
        String profileKey = keyDispatcher == null ? "plain" : keyDispatcher.methodName();
        BootstrapProfile existing = bootstrapProfiles.get(profileKey);
        if (existing != null) return existing;

        ensureMetadataHelper(clazz);
        String methodName = uniqueMethodName(clazz,
            LOCAL_BOOTSTRAP_PREFIX + Integer.toUnsignedString(pctx.random().nextInt(), 36));
        BootstrapProfile profile = new BootstrapProfile(methodName, keyDispatcher);
        emitBootstrap(clazz, profile);
        bootstrapProfiles.put(profileKey, profile);
        clazz.markDirty();
        return profile;
    }

    /**
     * Generate the per-class metadata-decrypt helper. Builds a switch on
     * (siteId * 4 + component) -> direct GETSTATIC of the encrypted field,
     * eliminating the plain LDC of the per-class field prefix that older
     * versions exposed via StringBuilder. Regenerated each time fields are
     * added so the switch covers all current sites.
     */
    private void ensureMetadataHelper(L1Class clazz) {
        clazz.asmNode().methods.removeIf(m ->
            m.name.equals(metadataHelperName) && "(IIJI)Ljava/lang/String;".equals(m.desc));

        List<int[]> sites = new ArrayList<>();
        for (FieldNode f : clazz.asmNode().fields) {
            if (!f.name.startsWith(metadataFieldPrefix)) continue;
            if (!"Ljava/lang/String;".equals(f.desc)) continue;
            String suffix = f.name.substring(metadataFieldPrefix.length());
            int sep = suffix.indexOf('_');
            if (sep <= 0) continue;
            try {
                int siteId = Integer.parseInt(suffix.substring(0, sep));
                int component = Integer.parseInt(suffix.substring(sep + 1));
                sites.add(new int[] { siteId, component });
            } catch (NumberFormatException ignored) {}
        }
        sites.sort((a, b) -> {
            int c = Integer.compare(a[0], b[0]);
            return c != 0 ? c : Integer.compare(a[1], b[1]);
        });

        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        boolean itf = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
        access |= itf ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode method = new MethodNode(access, metadataHelperName,
            "(IIJI)Ljava/lang/String;", null, null);
        InsnList insns = method.instructions;

        // long key = finalize_(mix(siteKey, componentId))
        insns.add(new VarInsnNode(Opcodes.LLOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "finalize_", "(J)J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, 5));

        int byteArrayLocal = 7;

        if (!sites.isEmpty()) {
            int[] keys = new int[sites.size()];
            LabelNode[] labels = new LabelNode[sites.size()];
            for (int i = 0; i < sites.size(); i++) {
                keys[i] = sites.get(i)[0] * 4 + sites.get(i)[1];
                labels[i] = new LabelNode();
            }
            LabelNode defaultLabel = new LabelNode();
            LabelNode afterSwitch = new LabelNode();

            // switchKey = siteId * 4 + component
            insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
            insns.add(new IntInsnNode(Opcodes.SIPUSH, 4));
            insns.add(new InsnNode(Opcodes.IMUL));
            insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));

            for (int i = 0; i < sites.size(); i++) {
                insns.add(labels[i]);
                String fieldName = metadataFieldPrefix + sites.get(i)[0] + "_" + sites.get(i)[1];
                insns.add(new FieldInsnNode(Opcodes.GETSTATIC, clazz.name(),
                    fieldName, "Ljava/lang/String;"));
                insns.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets",
                    "ISO_8859_1", "Ljava/nio/charset/Charset;"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String",
                    "getBytes", "(Ljava/nio/charset/Charset;)[B", false));
                insns.add(new VarInsnNode(Opcodes.ASTORE, byteArrayLocal));
                insns.add(new JumpInsnNode(Opcodes.GOTO, afterSwitch));
            }

            insns.add(defaultLabel);
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
            insns.add(new VarInsnNode(Opcodes.ASTORE, byteArrayLocal));
            insns.add(afterSwitch);
        } else {
            insns.add(new InsnNode(Opcodes.ICONST_0));
            insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
            insns.add(new VarInsnNode(Opcodes.ASTORE, byteArrayLocal));
        }

        insns.add(new VarInsnNode(Opcodes.ALOAD, byteArrayLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 5));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, STRING_DECRYPT_OWNER, "decrypt",
            "([BJ)Ljava/lang/String;", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxStack = 6;
        method.maxLocals = 8;
        clazz.asmNode().methods.add(method);
    }

    private void emitBootstrap(L1Class clazz, BootstrapProfile profile) {
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        boolean itf = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
        access |= itf ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode method = new MethodNode(access, profile.methodName(), BOOTSTRAP_DESC, null,
            new String[] { "java/lang/Throwable" });
        InsnList insns = method.instructions;

        int callerClassLocal = 12;
        int siteKeyLocal = 13;
        int ownerLocal = 15;
        int nameLocal = 16;
        int descLocal = 17;
        int ownerClassLocal = 18;
        int targetTypeLocal = 19;
        int targetLookupLocal = 20;
        int handleLocal = 21;

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, callerClassLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, callerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "classKey", "(Ljava/lang/Class;)J", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 5));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));

        mixSiteKey(insns, siteKeyLocal, 6);
        mixSiteKey(insns, siteKeyLocal, 8);
        mixSiteKey(insns, siteKeyLocal, 7);

        LabelNode noFlow = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, 9));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, noFlow));
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteKeyLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER, "flowKey", "()J", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));
        insns.add(noFlow);

        if (profile.keyDispatcher() != null) {
            LabelNode noKey = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ILOAD, 10));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, noKey));
            insns.add(new VarInsnNode(Opcodes.LLOAD, siteKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, 11));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name(),
                profile.keyDispatcher().methodName(), "(JI)J", itf));
            insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));
            insns.add(noKey);
        }

        emitMetadataCall(insns, clazz, 0, 1, ownerLocal, itf);
        emitMetadataCall(insns, clazz, 1, 2, nameLocal, itf);
        emitMetadataCall(insns, clazz, 2, 3, descLocal, itf);

        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, '/'));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, '.'));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace",
            "(CC)Ljava/lang/String;", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, callerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
            "()Ljava/lang/ClassLoader;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Class", "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, ownerClassLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, descLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/Class", "getClassLoader",
            "()Ljava/lang/ClassLoader;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/invoke/MethodType",
            "fromMethodDescriptorString",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, targetTypeLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ASTORE, targetLookupLocal));

        LabelNode staticLabel = new LabelNode();
        LabelNode specialLabel = new LabelNode();
        LabelNode virtualLabel = new LabelNode();
        LabelNode afterResolve = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.INVOKESTATIC));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, staticLabel));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 7));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, Opcodes.INVOKESPECIAL));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, specialLabel));
        insns.add(new JumpInsnNode(Opcodes.GOTO, virtualLabel));

        insns.add(staticLabel);
        emitLookupCall(insns, targetLookupLocal, ownerClassLocal, nameLocal, targetTypeLocal,
            "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(specialLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, targetLookupLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerClassLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, targetTypeLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, callerClassLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            "findSpecial",
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;",
            false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(virtualLabel);
        emitLookupCall(insns, targetLookupLocal, ownerClassLocal, nameLocal, targetTypeLocal,
            "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(afterResolve);

        LabelNode fixed = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "isVarargsCollector", "()Z", false));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, fixed));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "asFixedArity", "()Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(fixed);

        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/invoke/ConstantCallSite"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandle",
            "asType", "(Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/invoke/ConstantCallSite",
            "<init>", "(Ljava/lang/invoke/MethodHandle;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxStack = 8;
        method.maxLocals = 22;
        clazz.asmNode().methods.add(method);
    }

    private void mixSiteKey(InsnList insns, int siteKeyLocal, int intLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, intLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteKeyLocal));
    }

    private void emitMetadataCall(InsnList insns, L1Class clazz, int component, int componentId,
            int targetLocal, boolean itf) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.ICONST_0 + component));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 13));
        insns.add(new InsnNode(Opcodes.ICONST_0 + componentId));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name(), metadataHelperName,
            "(IIJI)Ljava/lang/String;", itf));
        insns.add(new VarInsnNode(Opcodes.ASTORE, targetLocal));
    }

    private void emitLookupCall(InsnList insns, int lookupLocal, int ownerClassLocal,
            int nameLocal, int targetTypeLocal, String methodName, String desc) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, lookupLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerClassLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, targetTypeLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/invoke/MethodHandles$Lookup",
            methodName, desc, false));
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String name = base;
        int suffix = 0;
        while (hasMethod(clazz, name)) {
            name = base + "_" + (++suffix);
        }
        return name;
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private boolean hasMethod(L1Class clazz, String name, String desc) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name) && method.desc.equals(desc)) return true;
        }
        return false;
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int intOption(TransformConfig config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? number.intValue() : defaultValue;
    }

    private AbstractInsnNode pushInt(int value) {
        return NumberEncryptionPass.generatedInt(value);
    }

    private void boxIfNeeded(InsnList insns, Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Boolean", "valueOf", "(Z)Ljava/lang/Boolean;", false));
            case Type.BYTE -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Byte", "valueOf", "(B)Ljava/lang/Byte;", false));
            case Type.CHAR -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Character", "valueOf", "(C)Ljava/lang/Character;", false));
            case Type.SHORT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Short", "valueOf", "(S)Ljava/lang/Short;", false));
            case Type.INT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Integer", "valueOf", "(I)Ljava/lang/Integer;", false));
            case Type.FLOAT -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Float", "valueOf", "(F)Ljava/lang/Float;", false));
            case Type.LONG -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Long", "valueOf", "(J)Ljava/lang/Long;", false));
            case Type.DOUBLE -> insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Double", "valueOf", "(D)Ljava/lang/Double;", false));
            default -> {
            }
        }
    }

    private void adaptReturnValue(InsnList insns, Type returnType) {
        switch (returnType.getSort()) {
            case Type.VOID -> insns.add(new InsnNode(Opcodes.POP));
            case Type.BOOLEAN -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Boolean"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Boolean", "booleanValue", "()Z", false));
            }
            case Type.BYTE -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Byte"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Byte", "byteValue", "()B", false));
            }
            case Type.CHAR -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Character"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Character", "charValue", "()C", false));
            }
            case Type.SHORT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Short"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Short", "shortValue", "()S", false));
            }
            case Type.INT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Integer", "intValue", "()I", false));
            }
            case Type.FLOAT -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Float"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Float", "floatValue", "()F", false));
            }
            case Type.LONG -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Long", "longValue", "()J", false));
            }
            case Type.DOUBLE -> {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Double"));
                insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
                    "java/lang/Double", "doubleValue", "()D", false));
            }
            default -> insns.add(new TypeInsnNode(Opcodes.CHECKCAST, returnType.getInternalName()));
        }
    }

    private record MethodRiskStats(int branchCount, int backwardBranches, boolean hasSwitch,
                                   boolean hasMonitor, boolean hasSensitiveApi) {}
    private record BootstrapProfile(String methodName, KeyDispatcherSupport.Profile keyDispatcher) {}
}
