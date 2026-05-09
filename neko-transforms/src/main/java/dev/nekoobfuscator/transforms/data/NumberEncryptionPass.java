package dev.nekoobfuscator.transforms.data;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import dev.nekoobfuscator.transforms.key.KeyDispatcherSupport;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;

import javax.crypto.Cipher;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Replaces numeric constants with obfuscated expressions.
 * Uses XOR, arithmetic, and table-lookup patterns with dynamic keys.
 */
public final class NumberEncryptionPass implements TransformPass {
    public static final String EXCLUDED_NUMERIC_INSNS_KEY = "numberEncryption.excludedNumericInsns";
    public static final String HARDEN_GENERATED_HELPERS_KEY = "numberEncryption.hardenGeneratedHelpers";
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String FLOW_KEY_LOCAL_BY_METHOD_KEY = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String BOOTSTRAP_CLASS = "dev/nekoobfuscator/runtime/NekoBootstrap";
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final String KEY_OWNER = "dev/nekoobfuscator/runtime/NekoKeyDerivation";
    private static final String BOOTSTRAP_METHOD = "bsmNumber";
    private static final String BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;JI)Ljava/lang/invoke/CallSite;";
    private static final Handle BSM_HANDLE = new Handle(
        Opcodes.H_INVOKESTATIC, BOOTSTRAP_CLASS, BOOTSTRAP_METHOD, BOOTSTRAP_DESC, false);
    private static final String ALGORITHM_OPTION = "algorithm";
    private static final String MODE_OPTION = "mode";
    private static final String USE_CONTROL_FLOW_KEY_OPTION = "useControlFlowKey";
    private static final String SKIP_TRY_CATCH_METHODS_OPTION = "skipMethodsWithTryCatch";
    private static final String SKIP_SWITCH_METHODS_OPTION = "skipMethodsWithSwitches";
    private static final String SKIP_MONITOR_METHODS_OPTION = "skipMethodsWithMonitors";
    private static final String SKIP_SENSITIVE_API_METHODS_OPTION = "skipSensitiveApiMethods";
    private static final String SKIP_SMALL_LOOP_CONSTANTS_OPTION = "skipSmallLoopConstants";
    private static final String MAX_PLAIN_LOOP_CONSTANT_OPTION = "maxPlainLoopConstant";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BRANCH_COUNT_OPTION = "maxBranchCount";


    @Override public String id() { return "numberEncryption"; }
    @Override public String name() { return "Number Encryption"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("controlFlowFlattening", "stringEncryption"); }

    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;
    private int aesFieldCounter;

    public static void excludeGeneratedNumericInsns(PipelineContext pctx, InsnList insns) {
        Set<AbstractInsnNode> excluded = excludedNumericInsns(pctx);
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            excluded.add(insn);
        }
    }

    public static void excludeGeneratedNumericInsn(PipelineContext pctx, AbstractInsnNode insn) {
        excludedNumericInsns(pctx).add(insn);
    }

    public static AbstractInsnNode generatedInt(int value) {
        return new GeneratedLdcInsnNode(value);
    }

    public static AbstractInsnNode generatedLong(long value) {
        return new GeneratedLdcInsnNode(value);
    }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
        classKey = keyEngine.deriveClassKey(pctx.currentL1Class());
        aesFieldCounter = countExistingNumberFields(pctx.currentL1Class());
        materializeConstantNumberFields(pctx.currentL1Class());
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        if (!method.hasCode()) return;
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)
                || (TransformGuards.isGeneratedMethod(method) && !hardenGeneratedHelpers)) {
            JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                "guarded-runtime-or-generated");
            return;
        }
        MethodRiskStats stats = analyzeMethod(method.instructions());
        TransformConfig config = pctx.config().transforms().get("numberEncryption");
        Algorithm algorithm = algorithm(config);
        IdentityHashMap<AbstractInsnNode, Long> flowKeyValues = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        Map<String, Integer> flowKeyLocalByMethod = pctx.getPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY);
        boolean useControlFlowKey = booleanOption(config, USE_CONTROL_FLOW_KEY_OPTION, true);
        boolean generatedHelperHardening = hardenGeneratedHelpers && TransformGuards.isGeneratedMethod(method);
        boolean reflectionShapeSensitive = TransformGuards.isReflectionShapeSensitive(pctx, clazz);
        KeyDispatcherSupport.Profile keyDispatcher = !generatedHelperHardening && !reflectionShapeSensitive && KeyDispatcherSupport.enabled(config)
            ? KeyDispatcherSupport.getOrCreate(pctx, clazz, config, "number", method.name().hashCode())
            : null;

        long methodKey = keyEngine.deriveMethodKey(method, classKey);
        InsnList insns = method.instructions();

        String methodKeyId = clazz.name() + "." + method.name() + method.descriptor();
        Integer flowKeyLocalSlot = flowKeyLocalByMethod == null
            ? null : flowKeyLocalByMethod.get(methodKeyId);

        List<AbstractInsnNode> targets = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (!isGeneratedNumericInsn(insn) && !isExcludedGeneratedNumeric(pctx, insn) && numericLiteral(insn) != null) {
                targets.add(insn);
            }
        }

        if (targets.isEmpty()) {
            JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                "no-numeric-literals");
            return;
        }

        int insnIdx = 0;
        boolean changed = false;
        for (AbstractInsnNode insn : targets) {
            NumericLiteral literal = numericLiteral(insn);
            if (literal == null || isGeneratedNumericInsn(insn) || isExcludedGeneratedNumeric(pctx, insn)) {
                continue;
            }
            int salt = pctx.random().nextInt();
            long insnKey = keyEngine.deriveInsnKey(methodKey, insnIdx++, salt);
            // Real flowKey usage: only enable if the CFF edge-XOR chain is wired up for
            // this method (flowKeyLocalSlot != null). Without CFF, ThreadLocal flowKey is
            // unreliable since we no longer resync it after every method call.
            Algorithm siteAlgorithm = algorithm == Algorithm.AES && method.isClassInit()
                ? Algorithm.XOR
                : algorithm;
            boolean useFlowKey = siteAlgorithm == Algorithm.XOR
                && !TransformGuards.hasStackIntrospection(method)
                && useControlFlowKey
                && flowKeyValues != null
                && flowKeyValues.containsKey(insn)
                && flowKeyLocalSlot != null;
            long keyState = useFlowKey
                ? DynamicKeyDerivationEngine.mix(insnKey, flowKeyValues.get(insn))
                : insnKey;
            int keyComponent = salt ^ insnIdx;
            long effectiveKey = keyDispatcher != null
                ? KeyDispatcherSupport.dispatch(keyDispatcher, keyState, keyComponent)
                : keyState;
            InsnList replacement = switch (siteAlgorithm) {
                case XOR -> xorReplacement(literal, effectiveKey, pctx, clazz, keyDispatcher,
                    insnKey, keyComponent, useFlowKey, flowKeyLocalSlot);
                case AES -> aesReplacement(clazz, literal, effectiveKey, keyDispatcher, keyState, keyComponent);
                case INDY -> indyReplacement(literal, effectiveKey, pctx);
            };
            if (replacement != null) {
                insns.insertBefore(insn, replacement);
                insns.remove(insn);
                changed = true;
            }
        }

        if (changed) {
            clazz.markDirty();
            JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(),
                algorithm == Algorithm.AES && method.isClassInit() ? "aes-clinit-inline-xor-tier" : algorithm.name().toLowerCase(Locale.ROOT));
        }
    }

    private boolean isMethodEligible(PipelineContext pctx, L1Method method, MethodRiskStats stats) {
        TransformConfig config = pctx.config().transforms().get("numberEncryption");
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
        if (method.instructionCount() > intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 220)) {
            return false;
        }
        return stats.branchCount() <= intOption(config, MAX_BRANCH_COUNT_OPTION, 18);
    }

    private boolean shouldKeepPlain(AbstractInsnNode insn, MethodRiskStats stats, TransformConfig config) {
        if (!booleanOption(config, SKIP_SMALL_LOOP_CONSTANTS_OPTION, true) || stats.backwardBranches() == 0) {
            return false;
        }
        NumericLiteral literal = numericLiteral(insn);
        if (literal == null || literal.type().getSort() != Type.INT) {
            return false;
        }
        int value = getIntValue(insn);
        return Math.abs(value) <= intOption(config, MAX_PLAIN_LOOP_CONSTANT_OPTION, 16);
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
        boolean hasArrayAccess = false;
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
            if (opcode >= Opcodes.IALOAD && opcode <= Opcodes.SALOAD
                    || opcode >= Opcodes.IASTORE && opcode <= Opcodes.SASTORE
                    || opcode == Opcodes.ARRAYLENGTH) {
                hasArrayAccess = true;
            }
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
        }
        return new MethodRiskStats(branchCount, backwardBranches, hasSwitch, hasMonitor, hasSensitiveApi, hasArrayAccess);
    }

    private boolean canUseRuntimeFlowKey(MethodRiskStats stats) {
        return stats.backwardBranches() == 0 && !stats.hasArrayAccess();
    }

    private boolean isSensitiveApiCall(MethodInsnNode mi) {
        String owner = mi.owner;
        if (owner.startsWith("java/lang/reflect/")) return true;
        if (owner.startsWith("java/lang/annotation/")) return true;
        if (owner.equals("java/lang/Class") || owner.startsWith("java/lang/ClassLoader")) return true;
        if (owner.equals("java/lang/StackWalker")) return true;
        return (owner.equals("java/lang/Thread") || owner.equals("java/lang/Throwable"))
            && "getStackTrace".equals(mi.name);
    }

    private static Set<AbstractInsnNode> excludedNumericInsns(PipelineContext pctx) {
        Set<AbstractInsnNode> excluded = pctx.getPassData(EXCLUDED_NUMERIC_INSNS_KEY);
        if (excluded == null) {
            excluded = Collections.newSetFromMap(new IdentityHashMap<>());
            pctx.putPassData(EXCLUDED_NUMERIC_INSNS_KEY, excluded);
        }
        return excluded;
    }

    private boolean isExcludedGeneratedNumeric(PipelineContext pctx, AbstractInsnNode insn) {
        Set<AbstractInsnNode> excluded = pctx.getPassData(EXCLUDED_NUMERIC_INSNS_KEY);
        return excluded != null && excluded.contains(insn);
    }

    private boolean isGeneratedNumericInsn(AbstractInsnNode insn) {
        return insn instanceof GeneratedNumericInsn;
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

    private int getIntValue(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        } else if (insn instanceof IntInsnNode intInsn) {
            return intInsn.operand;
        } else if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Integer i) {
            return i;
        }
        return 0;
    }

    private Algorithm algorithm(TransformConfig config) {
        String raw = stringOption(config, ALGORITHM_OPTION, stringOption(config, MODE_OPTION, "xor"));
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "aes" -> Algorithm.AES;
            case "indy", "invokedynamic" -> Algorithm.INDY;
            default -> Algorithm.XOR;
        };
    }

    private NumericLiteral numericLiteral(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return new NumericLiteral(Type.INT_TYPE, opcode - Opcodes.ICONST_0);
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return new NumericLiteral(Type.INT_TYPE, ((IntInsnNode) insn).operand);
        }
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) {
            return new NumericLiteral(Type.LONG_TYPE, opcode == Opcodes.LCONST_0 ? 0L : 1L);
        }
        if (opcode == Opcodes.FCONST_0 || opcode == Opcodes.FCONST_1 || opcode == Opcodes.FCONST_2) {
            return new NumericLiteral(Type.FLOAT_TYPE, Float.floatToRawIntBits((float) (opcode - Opcodes.FCONST_0)));
        }
        if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) {
            return new NumericLiteral(Type.DOUBLE_TYPE, Double.doubleToRawLongBits((double) (opcode - Opcodes.DCONST_0)));
        }
        if (insn instanceof LdcInsnNode ldc) {
            if (ldc.cst instanceof Integer i) return new NumericLiteral(Type.INT_TYPE, i.longValue());
            if (ldc.cst instanceof Long l) return new NumericLiteral(Type.LONG_TYPE, l);
            if (ldc.cst instanceof Float f) return new NumericLiteral(Type.FLOAT_TYPE, Float.floatToRawIntBits(f));
            if (ldc.cst instanceof Double d) return new NumericLiteral(Type.DOUBLE_TYPE, Double.doubleToRawLongBits(d));
        }
        return null;
    }

    private InsnList xorReplacement(NumericLiteral literal, long key, PipelineContext pctx,
            L1Class clazz, KeyDispatcherSupport.Profile keyDispatcher, long baseKey,
            int keyComponent, boolean useFlowKey, Integer flowKeyLocalSlot) {
        InsnList replacement = new InsnList();
        int sort = literal.type().getSort();
        if (sort == Type.INT || sort == Type.FLOAT) {
            int keyInt = (int) key;
            int encrypted = ((int) literal.bits()) ^ keyInt;
            replacement.add(new LdcInsnNode(encrypted));
            if (keyDispatcher != null || useFlowKey) {
                emitRuntimeKey(replacement, clazz, keyDispatcher, baseKey, keyComponent,
                    useFlowKey, flowKeyLocalSlot);
                replacement.add(new InsnNode(Opcodes.L2I));
            } else {
                emitIntKey(replacement, keyInt, pctx);
            }
            replacement.add(new InsnNode(Opcodes.IXOR));
            if (sort == Type.FLOAT) {
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Float", "intBitsToFloat", "(I)F", false));
            }
            return replacement;
        }

        long encrypted = literal.bits() ^ key;
        replacement.add(new LdcInsnNode(encrypted));
        if (keyDispatcher != null || useFlowKey) {
            emitRuntimeKey(replacement, clazz, keyDispatcher, baseKey, keyComponent,
                useFlowKey, flowKeyLocalSlot);
        } else {
            emitLongKey(replacement, key, pctx);
        }
        replacement.add(new InsnNode(Opcodes.LXOR));
        if (sort == Type.DOUBLE) {
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Double", "longBitsToDouble", "(J)D", false));
        }
        return replacement;
    }

    private void emitRuntimeKey(InsnList insns, L1Class clazz, KeyDispatcherSupport.Profile keyDispatcher,
            long baseKey, int keyComponent, boolean useFlowKey, Integer flowKeyLocalSlot) {
        insns.add(new LdcInsnNode(baseKey));
        if (useFlowKey) {
            // Read flowKey from CFF's per-method local slot. The runtime value at this
            // point is whatever the edge-XOR chain accumulated, so the effective key is
            // not reconstructible without CFG simulation. ThreadLocal NekoContext is no
            // longer used for number decryption.
            if (flowKeyLocalSlot != null) {
                insns.add(new VarInsnNode(Opcodes.LLOAD, flowKeyLocalSlot));
            } else {
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER, "flowKey", "()J", false));
            }
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, KEY_OWNER, "mix", "(JJ)J", false));
        }
        if (keyDispatcher != null) {
            KeyDispatcherSupport.emitDispatchCall(insns, clazz, keyDispatcher, keyComponent);
        }
    }

    private InsnList indyReplacement(NumericLiteral literal, long ignoredKey, PipelineContext pctx) {
        int contextKey = pctx.random().nextInt();
        long runtimeKey = classKey ^ (long) contextKey;
        long encrypted = literal.bits() ^ runtimeKey;
        InsnList replacement = new InsnList();
        replacement.add(new InvokeDynamicInsnNode("n", "()" + literal.type().getDescriptor(),
            BSM_HANDLE, encrypted, contextKey));
        return replacement;
    }

    private InsnList aesReplacement(L1Class clazz, NumericLiteral literal, long key,
            KeyDispatcherSupport.Profile keyDispatcher, long baseKey, int keyComponent) {
        String valueField = "__neko_n" + (aesFieldCounter++);
        FieldNode field = new FieldNode(numberFieldAccess(clazz.asmNode()), valueField,
            literal.type().getDescriptor(), null, null);
        clazz.asmNode().fields.add(field);

        long[] ciphertext = aesEncryptNumber(literal.bits(), key);
        MethodNode clinit = ensureClassInit(clazz);
        InsnList init = new InsnList();
        init.add(new LdcInsnNode(ciphertext[0]));
        init.add(new LdcInsnNode(ciphertext[1]));
        if (keyDispatcher != null) {
            init.add(new LdcInsnNode(baseKey));
            KeyDispatcherSupport.emitDispatchCall(init, clazz, keyDispatcher, keyComponent);
        } else {
            init.add(new LdcInsnNode(key));
        }
        init.add(new LdcInsnNode(literal.type().getSort() == Type.INT || literal.type().getSort() == Type.FLOAT ? 32 : 64));
        init.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "dev/nekoobfuscator/runtime/NekoKeyDerivation",
            "decryptNumberAes", "(JJJI)J", false));
        switch (literal.type().getSort()) {
            case Type.INT -> init.add(new InsnNode(Opcodes.L2I));
            case Type.FLOAT -> {
                init.add(new InsnNode(Opcodes.L2I));
                init.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "java/lang/Float", "intBitsToFloat", "(I)F", false));
            }
            case Type.DOUBLE -> init.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "java/lang/Double", "longBitsToDouble", "(J)D", false));
            default -> {
            }
        }
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), valueField, literal.type().getDescriptor()));
        insertBeforeReturn(clinit, init);
        clinit.maxStack = Math.max(clinit.maxStack, 8);

        InsnList replacement = new InsnList();
        replacement.add(new FieldInsnNode(Opcodes.GETSTATIC, clazz.name(), valueField, literal.type().getDescriptor()));
        return replacement;
    }

    private void emitIntKey(InsnList insns, int key, PipelineContext pctx) {
        int mask = pctx.random().nextInt() | 1;
        int delta = pctx.random().nextInt();
        int base = (key - delta) ^ mask;
        insns.add(new LdcInsnNode(base));
        insns.add(new LdcInsnNode(mask));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new LdcInsnNode(delta));
        insns.add(new InsnNode(Opcodes.IADD));
    }

    private void emitLongKey(InsnList insns, long key, PipelineContext pctx) {
        long mask = pctx.random().nextLong() | 1L;
        long delta = pctx.random().nextLong();
        long base = (key - delta) ^ mask;
        insns.add(new LdcInsnNode(base));
        insns.add(new LdcInsnNode(mask));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new LdcInsnNode(delta));
        insns.add(new InsnNode(Opcodes.LADD));
    }

    private long[] aesEncryptNumber(long bits, long key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/ECB/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(expandAesKey(key), "AES"));
            byte[] plain = ByteBuffer.allocate(16)
                .putLong(bits)
                .putLong(bits ^ 0xA5A5A5A55A5A5A5AL)
                .array();
            ByteBuffer encrypted = ByteBuffer.wrap(cipher.doFinal(plain));
            return new long[] { encrypted.getLong(), encrypted.getLong() };
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt numeric AES constant", e);
        }
    }

    private byte[] expandAesKey(long key) {
        ByteBuffer bytes = ByteBuffer.allocate(16);
        bytes.putLong(key);
        bytes.putLong(DynamicKeyDerivationEngine.finalize_(DynamicKeyDerivationEngine.mix(key, 0xC2FBA1B5E84F7233L)));
        return bytes.array();
    }

    private MethodNode ensureClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                return method;
            }
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions = new InsnList();
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private void insertBeforeReturn(MethodNode method, InsnList init) {
        AbstractInsnNode target = null;
        for (AbstractInsnNode insn = method.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                target = insn;
                break;
            }
        }
        if (target == null) {
            method.instructions.add(init);
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            method.instructions.insertBefore(target, init);
        }
    }

    private void materializeConstantNumberFields(L1Class clazz) {
        InsnList init = new InsnList();
        boolean changed = false;
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.value == null) continue;
            AbstractInsnNode push = constantPush(field.value);
            if (push == null) continue;
            field.value = null;
            init.add(push);
            init.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), field.name, field.desc));
            changed = true;
        }
        if (!changed) return;

        MethodNode clinit = ensureClassInit(clazz);
        insertBeforeReturn(clinit, init);
        clinit.maxStack = Math.max(clinit.maxStack, 2);
        clazz.markDirty();
    }

    private AbstractInsnNode constantPush(Object value) {
        if (value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof Double) {
            return new LdcInsnNode(value);
        }
        return null;
    }

    private int countExistingNumberFields(L1Class clazz) {
        int count = 0;
        for (FieldNode fn : clazz.asmNode().fields) {
            if (fn.name.startsWith("__neko_n")) count++;
        }
        return count;
    }

    private int numberFieldAccess(ClassNode classNode) {
        if ((classNode.access & Opcodes.ACC_INTERFACE) != 0) {
            return Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        }
        return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    }

    private String stringOption(TransformConfig config, String key, String defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof String text ? text : defaultValue;
    }

    private record MethodRiskStats(int branchCount, int backwardBranches, boolean hasSwitch,
                                   boolean hasMonitor, boolean hasSensitiveApi, boolean hasArrayAccess) {}
    private record NumericLiteral(Type type, long bits) {}
    private enum Algorithm { XOR, AES, INDY }

    private interface GeneratedNumericInsn {}

    private static final class GeneratedLdcInsnNode extends LdcInsnNode implements GeneratedNumericInsn {
        private GeneratedLdcInsnNode(Object value) {
            super(value);
        }

        @Override
        public AbstractInsnNode clone(Map<LabelNode, LabelNode> clonedLabels) {
            return new GeneratedLdcInsnNode(cst).cloneAnnotations(this);
        }
    }
}
