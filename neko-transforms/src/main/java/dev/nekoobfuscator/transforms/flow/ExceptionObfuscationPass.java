package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.util.*;

/**
 * Exception Obfuscation: replaces some unconditional jumps with throw/catch patterns.
 * A custom exception carries an encrypted state number to the handler which dispatches.
 */
public final class ExceptionObfuscationPass implements TransformPass {

    private static final String EXCEPTION_CLASS = "dev/nekoobfuscator/runtime/NekoFlowException";
    private static final String ROUTE_METHOD = "__neko_route";
    private static final String FLATTENED_METHODS_KEY = "controlFlowFlattening.methods";
    private static final String SKIP_FLATTENED_OPTION = "skipFlattenedMethods";
    private static final String FLATTENED_INTENSITY_OPTION = "flattenedIntensityMultiplier";
    private static final String SKIP_TRY_CATCH_METHODS_OPTION = "skipMethodsWithTryCatch";
    private static final String SKIP_SWITCH_METHODS_OPTION = "skipMethodsWithSwitches";
    private static final String SKIP_MONITOR_METHODS_OPTION = "skipMethodsWithMonitors";
    private static final String SKIP_NON_VOID_METHODS_OPTION = "skipNonVoidMethods";
    private static final String SKIP_BACKWARD_GOTOS_OPTION = "skipBackwardGotos";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_GOTOS_OPTION = "maxEligibleGotos";

    @Override public String id() { return "exceptionObfuscation"; }
    @Override public String name() { return "Exception Obfuscation"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("controlFlowFlattening"); }

    @Override
    public void transformClass(TransformContext ctx) {
        // Ensure exception class exists - we inject it into the output JAR
        PipelineContext pctx = (PipelineContext) ctx;
        if (pctx.getPassData("exceptionClassInjected") == null) {
            injectExceptionClass(pctx);
            pctx.putPassData("exceptionClassInjected", Boolean.TRUE);
        }
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;
        if (!isMethodEligible(pctx, method)) return;

        double intensity = effectiveIntensity(pctx, method);
        if (intensity <= 0.0) return;
        InsnList insns = method.instructions();
        TransformConfig config = pctx.config().transforms().get("exceptionObfuscation");
        IdentityHashMap<AbstractInsnNode, Integer> positions = instructionPositions(insns);
        boolean skipBackwardGotos = booleanOption(config, SKIP_BACKWARD_GOTOS_OPTION, true);

        // Find GOTO instructions that we can replace with throw/catch
        List<JumpInsnNode> gotos = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.GOTO && insn instanceof JumpInsnNode jump) {
                if (skipBackwardGotos && isBackwardGoto(jump, positions)) {
                    continue;
                }
                if (pctx.random().nextDouble() <= intensity) {
                    gotos.add(jump);
                }
            }
        }

        if (gotos.isEmpty()) return;

        for (JumpInsnNode gotoInsn : gotos) {
            LabelNode originalTarget = gotoInsn.label;
            int routeKey = pctx.random().nextInt();

            InsnList replacement = new InsnList();
            pushInt(replacement, routeKey);
            replacement.add(new LookupSwitchInsnNode(originalTarget,
                new int[] { routeKey },
                new LabelNode[] { originalTarget }));

            insns.insertBefore(gotoInsn, replacement);
            insns.remove(gotoInsn);
        }

        method.asmNode().maxStack = Math.max(method.asmNode().maxStack, 2);
        pctx.currentL1Class().markDirty();
    }

    private double effectiveIntensity(PipelineContext pctx, L1Method method) {
        double intensity = pctx.config().getTransformIntensity("exceptionObfuscation");
        if (!isFlattenedMethod(pctx, method)) return intensity;

        TransformConfig config = pctx.config().transforms().get("exceptionObfuscation");
        if (config == null) return intensity;

        Object skipFlattened = config.options().get(SKIP_FLATTENED_OPTION);
        if (skipFlattened instanceof Boolean enabled && enabled) {
            return 0.0;
        }

        Object multiplier = config.options().get(FLATTENED_INTENSITY_OPTION);
        if (multiplier instanceof Number number) {
            double adjusted = intensity * number.doubleValue();
            return Math.max(0.0, Math.min(1.0, adjusted));
        }

        return intensity;
    }

    private boolean isFlattenedMethod(PipelineContext pctx, L1Method method) {
        Set<String> flattenedMethods = pctx.getPassData(FLATTENED_METHODS_KEY);
        return flattenedMethods != null && flattenedMethods.contains(methodKey(method));
    }

    private String methodKey(L1Method method) {
        return method.owner().name() + '.' + method.name() + method.descriptor();
    }

    private boolean isMethodEligible(PipelineContext pctx, L1Method method) {
        TransformConfig config = pctx.config().transforms().get("exceptionObfuscation");
        MethodRiskStats stats = analyzeMethod(method.instructions());

        if (!method.tryCatchBlocks().isEmpty() && booleanOption(config, SKIP_TRY_CATCH_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasSwitch() && booleanOption(config, SKIP_SWITCH_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.hasMonitor() && booleanOption(config, SKIP_MONITOR_METHODS_OPTION, true)) {
            return false;
        }
        if (method.returnType().getSort() != org.objectweb.asm.Type.VOID
                && booleanOption(config, SKIP_NON_VOID_METHODS_OPTION, true)) {
            return false;
        }
        if (stats.backwardGotoCount() > 0 && booleanOption(config, SKIP_BACKWARD_GOTOS_OPTION, true)) {
            return false;
        }
        if (method.instructionCount() > intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 260)) {
            return false;
        }
        return stats.gotoCount() <= intOption(config, MAX_GOTOS_OPTION, 24);
    }

    private MethodRiskStats analyzeMethod(InsnList insns) {
        int gotoCount = 0;
        int backwardGotoCount = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;
        IdentityHashMap<AbstractInsnNode, Integer> positions = instructionPositions(insns);

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.GOTO && insn instanceof JumpInsnNode jump) {
                gotoCount++;
                if (isBackwardGoto(jump, positions)) {
                    backwardGotoCount++;
                }
            }
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
            }
        }

        return new MethodRiskStats(gotoCount, backwardGotoCount, hasSwitch, hasMonitor);
    }

    private IdentityHashMap<AbstractInsnNode, Integer> instructionPositions(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }
        return positions;
    }

    private boolean isBackwardGoto(JumpInsnNode jump, IdentityHashMap<AbstractInsnNode, Integer> positions) {
        Integer source = positions.get(jump);
        Integer target = positions.get(jump.label);
        return source != null && target != null && target <= source;
    }

    private boolean booleanOption(TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean enabled ? enabled : defaultValue;
    }

    private int intOption(TransformConfig config, String key, int defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(0, number.intValue()) : defaultValue;
    }

    private void pushInt(InsnList insns, int value) {
        if (value >= -1 && value <= 5) {
            insns.add(new InsnNode(Opcodes.ICONST_0 + value));
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.BIPUSH, value));
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            insns.add(new IntInsnNode(Opcodes.SIPUSH, value));
        } else {
            insns.add(new LdcInsnNode(value));
        }
    }

    private void injectExceptionClass(PipelineContext ctx) {
        // Create a minimal exception class for flow obfuscation
        ClassNode cn = new ClassNode();
        cn.version = Opcodes.V1_8;
        cn.access = Opcodes.ACC_PUBLIC | Opcodes.ACC_SUPER | Opcodes.ACC_SYNTHETIC;
        cn.name = EXCEPTION_CLASS;
        cn.superName = "java/lang/RuntimeException";
        cn.interfaces = List.of();

        // Default constructor
        MethodNode init = new MethodNode(Opcodes.ACC_PUBLIC, "<init>", "()V", null, null);
        init.instructions = new InsnList();
        init.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        init.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/RuntimeException", "<init>", "()V", false));
        init.instructions.add(new InsnNode(Opcodes.RETURN));
        init.maxStack = 1;
        init.maxLocals = 1;
        cn.methods.add(init);

        // Override fillInStackTrace to return null (performance: skip stack trace)
        MethodNode fillIn = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_SYNCHRONIZED,
            "fillInStackTrace", "()Ljava/lang/Throwable;", null, null);
        fillIn.instructions = new InsnList();
        fillIn.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
        fillIn.instructions.add(new InsnNode(Opcodes.ARETURN));
        fillIn.maxStack = 1;
        fillIn.maxLocals = 1;
        cn.methods.add(fillIn);

        MethodNode route = new MethodNode(Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            ROUTE_METHOD, "(I)I", null, null);
        route.instructions = new InsnList();
        LabelNode tryStart = new LabelNode();
        LabelNode tryEnd = new LabelNode();
        LabelNode handlerStart = new LabelNode();
        route.instructions.add(new TypeInsnNode(Opcodes.NEW, EXCEPTION_CLASS));
        route.instructions.add(new InsnNode(Opcodes.DUP));
        route.instructions.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, EXCEPTION_CLASS, "<init>", "()V", false));
        route.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        route.instructions.add(tryStart);
        route.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Thread", "currentThread", "()Ljava/lang/Thread;", false));
        LabelNode fastReturn = new LabelNode();
        route.instructions.add(new JumpInsnNode(Opcodes.IFNONNULL, fastReturn));
        route.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
        route.instructions.add(new InsnNode(Opcodes.ATHROW));
        route.instructions.add(fastReturn);
        route.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        route.instructions.add(new InsnNode(Opcodes.IRETURN));
        route.instructions.add(tryEnd);
        route.instructions.add(new InsnNode(Opcodes.ICONST_0));
        route.instructions.add(new InsnNode(Opcodes.IRETURN));
        route.instructions.add(handlerStart);
        route.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
        route.instructions.add(new VarInsnNode(Opcodes.ILOAD, 0));
        route.instructions.add(new InsnNode(Opcodes.IRETURN));
        route.tryCatchBlocks.add(new TryCatchBlockNode(tryStart, tryEnd, handlerStart, EXCEPTION_CLASS));
        route.maxStack = 2;
        route.maxLocals = 2;
        cn.methods.add(route);

        L1Class l1 = new L1Class(cn);
        ctx.classMap().put(EXCEPTION_CLASS, l1);
    }

    private record MethodRiskStats(int gotoCount, int backwardGotoCount, boolean hasSwitch, boolean hasMonitor) {}
}
