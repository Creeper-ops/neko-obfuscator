package dev.nekoobfuscator.transforms.flow;

import dev.nekoobfuscator.api.config.TransformConfig;
import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.jar.ClassHierarchy;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.Interpreter;

import java.util.*;

/**
 * Control Flow Flattening: converts method CFG into a state-machine dispatcher.
 * Each basic block becomes a case in a switch statement.
 */
public final class ControlFlowFlatteningPass implements TransformPass {
    private static final String FLATTENED_METHODS_KEY = "controlFlowFlattening.methods";
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    public static final String FLOW_KEY_LOCAL_BY_METHOD_KEY = "controlFlowFlattening.flowKeyLocalByMethod";
    public static final String HARDEN_GENERATED_HELPERS_KEY = "controlFlowFlattening.hardenGeneratedHelpers";
    private static final String INTEGER_OWNER = "java/lang/Integer";
    private static final String CONTEXT_OWNER = "dev/nekoobfuscator/runtime/NekoContext";
    private static final String ZKM_STYLE_OPTION = "zkmStyle";
    private static final String TAIL_CHAIN_INTENSITY_OPTION = "tailChainIntensity";
    private static final String TRY_CATCH_TAIL_CHAIN_MULTIPLIER_OPTION = "tryCatchTailChainMultiplier";
    private static final String ALLOW_TRY_CATCH_METHODS_OPTION = "allowTryCatchMethods";
    private static final String TRY_CATCH_MAIN_ONLY_OPTION = "tryCatchMainOnly";
    private static final String MAX_TRY_CATCH_BLOCKS_OPTION = "maxTryCatchBlocks";
    private static final String TRY_CATCH_BRANCH_BONUS_OPTION = "tryCatchBranchBonus";
    private static final String TRY_CATCH_INSTRUCTION_BONUS_OPTION = "tryCatchInstructionBonus";
    private static final String ENTRYPOINT_TAIL_CHAIN_MULTIPLIER_OPTION = "entrypointTailChainMultiplier";
    private static final String ENTRYPOINT_MAX_TRY_CATCH_BLOCKS_OPTION = "entrypointMaxTryCatchBlocks";
    private static final String ENTRYPOINT_BRANCH_BONUS_OPTION = "entrypointBranchBonus";
    private static final String ENTRYPOINT_INSTRUCTION_BONUS_OPTION = "entrypointInstructionBonus";
    private static final String ALLOW_SWITCH_METHODS_OPTION = "allowSwitchMethods";
    private static final String ALLOW_MONITOR_METHODS_OPTION = "allowMonitorMethods";
    private static final String MAX_INSTRUCTION_COUNT_OPTION = "maxApplicableInstructionCount";
    private static final String MAX_BACKWARD_BRANCHES_OPTION = "maxBackwardBranches";
    private static final String MAX_BRANCHES_OPTION = "maxBranchCount";
    private static final String DISPATCHER_DEPTH_OPTION = "dispatcherDepth";
    private static final String DISPATCHER_SHAPE_VARIATION_OPTION = "dispatcherShapeVariation";
    private static final String EDGE_KEYED_OPTION = "edgeKeyed";
    private static final String DISPATCHER_FRAGMENTS_OPTION = "dispatcherFragments";
    private static final String MAX_EDGE_CLONE_BLOCKS_OPTION = "maxEdgeCloneBlocks";
    private static final String LINEAR_CHUNK_SIZE_OPTION = "linearChunkSize";
    private static final String LOOP_FAST_PATH_INSTRUCTION_THRESHOLD_OPTION = "loopFastPathInstructionThreshold";
    private static final String LOOP_FAST_PATH_BACKWARD_BRANCH_THRESHOLD_OPTION = "loopFastPathBackwardBranchThreshold";
    private static final InsnNode AFTER_HANDLER_SYNC_ANCHOR = new InsnNode(Opcodes.NOP);

    @Override public String id() { return "controlFlowFlattening"; }
    @Override public String name() { return "Control Flow Flattening"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("stackObfuscation"); }

    private dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine keyEngine;

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }
    }

    @Override
    public boolean isApplicable(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)) return false;
        if (method == null) return true;
        if (TransformGuards.isGeneratedMethod(method) && !hardenGeneratedHelpers) return false;
        return method.hasCode();
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();

        if (method == null || !method.hasCode()) return;
        boolean hardenGeneratedHelpers = Boolean.TRUE.equals(pctx.getPassData(HARDEN_GENERATED_HELPERS_KEY));
        if (TransformGuards.isRuntimeClass(clazz)
                || (TransformGuards.isGeneratedMethod(method) && !hardenGeneratedHelpers)) {
            recordNotApplicable(pctx, clazz, method, "guarded-runtime-or-generated");
            return;
        }

        if (method.isConstructor()) {
            if (!insertConstructorFlowGate(pctx, method)) {
                recordFailClosed(pctx, clazz, method, "constructor-has-no-post-init-anchor");
                throw new IllegalStateException("Cannot insert constructor post-init CFF gate for "
                    + methodKey(method));
            }
            clazz.markDirty();
            flattenedMethods(pctx).add(methodKey(method));
            recordSafe(pctx, clazz, method, "constructor-post-init-gate");
            pctx.invalidate(method);
            return;
        }

        double intensity = pctx.config().getTransformIntensity("controlFlowFlattening");
        if (pctx.random().nextDouble() > intensity) {
            recordNotApplicable(pctx, clazz, method, "intensity-gate");
            return;
        }

        if (method.instructionCount() <= 10 || !isStructureSafe(method, pctx)) {
            insertMinimalVerifiedGate(pctx, clazz, method, "minimal-verified-gate");
            return;
        }


        ControlFlowGraph cfg = pctx.getCFG(method);
        MethodNode mn = method.asmNode();
        int originalMaxLocalsValue = mn.maxLocals;
        int originalMaxStackValue = mn.maxStack;
        List<TryCatchBlockNode> originalTryCatchBlocks = mn.tryCatchBlocks == null
            ? null
            : new ArrayList<>(mn.tryCatchBlocks);
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzedFrames = analyzeFrames(clazz.name(), mn, pctx.hierarchy());
        List<BasicBlock> blocks = new ArrayList<>(cfg.blocks());
        List<BasicBlock> dispatchBlocks = new ArrayList<>();
        List<BasicBlock> handlerBlocks = new ArrayList<>();
        partitionBlocks(blocks, dispatchBlocks, handlerBlocks);
        BasicBlock entryBlock = cfg.entryBlock();
        if (dispatchBlocks.size() < 3) {
            List<BasicBlock> linearBlocks = splitLinearDispatchBlocks(method, dispatchBlocks,
                handlerBlocks, analyzedFrames, pctx);
            if (linearBlocks == null || linearBlocks.size() < 3) {
                insertMinimalVerifiedGate(pctx, clazz, method, "minimal-verified-gate-small-cfg");
                return;
            }
            blocks = linearBlocks;
            dispatchBlocks = new ArrayList<>(linearBlocks);
            handlerBlocks = new ArrayList<>();
            entryBlock = linearBlocks.get(0);
        }

        long classKey = keyEngine.deriveClassKey(clazz);
        long methodKey = keyEngine.deriveMethodKey(method, classKey);
        long methodFlowSeed = deriveMethodFlowSeed(methodKey);
        int stateMask = foldMethodKey(methodKey ^ 0x4E454B4F4C4FL);
        int stateDelta = foldMethodKey(Long.rotateLeft(methodKey, 19) ^ 0xC0DEC0DE5EEDL);
        int stateRotate = 5 + Math.floorMod((int) (methodKey >>> 11), 19);
        boolean zkmStyle = isZkmStyleEnabled(pctx);
        double tailChainIntensity = tailChainIntensity(pctx, method);

        // Assign random state numbers
        Map<BasicBlock, Integer> stateMap = new HashMap<>();
        Set<Integer> usedStates = new HashSet<>();
        for (BasicBlock block : dispatchBlocks) {
            int state = pctx.random().nextIntExcluding(usedStates);
            stateMap.put(block, state);
            usedStates.add(state);
        }

        Map<BasicBlock, Long> flowKeyMap = new HashMap<>();
        for (BasicBlock block : blocks) {
            Integer state = stateMap.get(block);
            long flowSeed = state != null
                ? deriveBlockFlowKey(methodFlowSeed, state)
                : dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
                    dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodFlowSeed,
                        0x4E454B4F00000000L ^ block.id()));
            flowKeyMap.put(block, flowSeed);
        }
        if (booleanOption(pctx.config().transforms().get("controlFlowFlattening"), EDGE_KEYED_OPTION, true)) {
            applySinglePredecessorEdgeKeys(blocks, entryBlock, flowKeyMap, methodFlowSeed);
        }

        int initialState = stateMap.get(entryBlock);
        long initialFlowKey = flowKeyMap.getOrDefault(entryBlock, 0L);

        IdentityHashMap<AbstractInsnNode, Integer> stackHeights = extractStackHeights(analyzedFrames);
        Map<BasicBlock, List<StackSlotKind>> blockEntryStacks = analyzeBlockEntryStacks(dispatchBlocks, analyzedFrames);
        Map<BasicBlock, List<LocalSlotState>> blockEntryLocals = analyzeBlockEntryLocals(blocks, dispatchBlocks, analyzedFrames, originalMaxLocals(method), method);
        InsnList newInsns = new InsnList();
        int originalMaxLocals = mn.maxLocals;
        int nextLocal = mn.maxLocals;
        int flowKeyVar = nextLocal;
        nextLocal += 2;
        int flowMixVar = nextLocal++;
        int encodedStateVar = nextLocal++;
        int dispatchStateVar = nextLocal++;
        int stateMaskVar = nextLocal++;
        int stateDeltaVar = nextLocal++;
        int tailSeedVar = nextLocal++;
        int tailFlagVar = nextLocal++;
        Map<BasicBlock, Integer> blockSpillBases = new IdentityHashMap<>();
        nextLocal = allocateSpillLocals(dispatchBlocks, blockEntryStacks, blockSpillBases, nextLocal);
        Map<BasicBlock, Integer> blockLocalSpillBases = new IdentityHashMap<>();
        nextLocal = allocateLocalSpillLocals(dispatchBlocks, blockEntryLocals, blockLocalSpillBases, nextLocal);
        int transformedMaxLocals = nextLocal;

        LabelNode loopStart = new LabelNode();
        LabelNode loopEnd = new LabelNode();

        // Label remap for internal jumps within blocks
        Map<LabelNode, LabelNode> labelRemap = new HashMap<>();
        for (BasicBlock block : blocks) {
            for (AbstractInsnNode insn : block.instructions()) {
                if (insn instanceof LabelNode origLabel) {
                    labelRemap.put(origLabel, new LabelNode());
                }
            }
        }

        emitOriginalLocalInitialization(newInsns, method, originalMaxLocals);
        initializeSyntheticSpillLocals(newInsns, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        spillLocalsForTarget(newInsns, entryBlock, blockEntryLocals, blockLocalSpillBases);

        // Prologue masks are split into 2-LDC XOR so static analysis can't read
        // mask/delta/rot as a single literal at the method head and reverse the
        // state-encoding function in one pass.
        emitSplitIntStore(newInsns, stateMask, stateMaskVar, methodKey);
        emitSplitIntStore(newInsns, stateDelta, stateDeltaVar,
            Long.rotateLeft(methodKey, 7) ^ 0x6B6F4D5A4D533132L);
        int tailSeedValue = foldMethodKey(Long.rotateRight(methodKey, 27) ^ 0x5A4B4D7E1F2DL);
        emitSplitIntStore(newInsns, tailSeedValue, tailSeedVar,
            Long.rotateLeft(methodKey, 19) ^ 0x4D734E7E5443BFL);
        newInsns.add(new InsnNode(Opcodes.ICONST_0));
        newInsns.add(new VarInsnNode(Opcodes.ISTORE, tailFlagVar));
        emitFlowKeyAbsolute(newInsns, methodKey, initialFlowKey, flowKeyVar, flowMixVar, 0);
        emitEncodedStateStore(newInsns, initialState, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, 0);

        newInsns.add(loopStart);
        emitRuntimeFlowContextSync(newInsns, flowKeyVar);
        emitStateDecode(newInsns, encodedStateVar, dispatchStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate);
        newInsns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));

        // Build sorted lookupswitch
        int[] keys = new int[dispatchBlocks.size()];
        LabelNode[] switchLabels = new LabelNode[dispatchBlocks.size()];
        Map<BasicBlock, LabelNode> blockCaseLabels = new HashMap<>();

        List<Map.Entry<BasicBlock, Integer>> entries = new ArrayList<>(stateMap.entrySet());
        entries.sort(Comparator.comparingInt(Map.Entry::getValue));

        for (int i = 0; i < entries.size(); i++) {
            keys[i] = entries.get(i).getValue();
            LabelNode label = new LabelNode();
            switchLabels[i] = label;
            blockCaseLabels.put(entries.get(i).getKey(), label);
        }

        LabelNode dispatcherDefault = blockCaseLabels.getOrDefault(entryBlock, switchLabels[0]);
        emitDispatcherSwitch(newInsns, pctx, methodKey, dispatchStateVar, keys, switchLabels, dispatcherDefault);

        List<TailChain> tailChains = new ArrayList<>();
        if (!handlerBlocks.isEmpty()) {
            Set<BasicBlock> handlerBlockSet = new HashSet<>(handlerBlocks);
            boolean previousWasDispatch = false;
            for (BasicBlock block : blocks) {
                if (handlerBlockSet.contains(block)) {
                    if (previousWasDispatch) {
                        newInsns.add(new JumpInsnNode(Opcodes.GOTO, loopEnd));
                    }
                    emitHandlerBlock(newInsns, block, labelRemap, pctx, flowKeyMap, methodKey,
                        flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                        stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                        tailChains, loopStart, loopEnd, stackHeights, blockEntryStacks, blockSpillBases,
                        blockEntryLocals, blockLocalSpillBases);
                    previousWasDispatch = false;
                } else {
                    emitDispatchBlock(newInsns, block, blockCaseLabels, labelRemap, pctx, flowKeyMap,
                        flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                        stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                        tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases,
                        blockEntryLocals, blockLocalSpillBases);
                    previousWasDispatch = true;
                }
            }
        } else {
            int[] emissionOrder = blockEmissionOrder(pctx, dispatchBlocks.size(), false);
            for (int index : emissionOrder) {
                BasicBlock block = dispatchBlocks.get(index);
                emitDispatchBlock(newInsns, block, blockCaseLabels, labelRemap, pctx, flowKeyMap,
                    flowKeyVar, flowMixVar, stateMap, encodedStateVar, stateMaskVar, stateDeltaVar,
                    stateRotate, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                    tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases,
                    blockEntryLocals, blockLocalSpillBases);
            }
        }

        if (!tailChains.isEmpty()) {
            int[] tailOrder = pctx.random().randomPermutation(tailChains.size());
            for (int index : tailOrder) {
                TailChain chain = tailChains.get(index);
                newInsns.add(chain.entry());
                newInsns.add(chain.body());
            }
        }

        newInsns.add(loopEnd);
        emitSafetyReturn(newInsns, method.returnType());

        List<TryCatchBlockNode> transformedTryCatchBlocks;
        // Preserve try-catch blocks with remapped labels
        if (mn.tryCatchBlocks != null && !mn.tryCatchBlocks.isEmpty()) {
            IdentityHashMap<AbstractInsnNode, Integer> originalInstructionPositions = codePositions(mn.instructions);
            IdentityHashMap<LabelNode, Integer> emittedLabelPositions = labelCodePositions(newInsns);
            List<TryCatchBlockNode> remappedTryCatch = new ArrayList<>();
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                List<RemappedTryCatchRange> remappedRanges = remapTryCatchRanges(tcb, labelRemap,
                    originalInstructionPositions, emittedLabelPositions, newInsns);
                for (RemappedTryCatchRange remapped : remappedRanges) {
                    remappedTryCatch.add(new TryCatchBlockNode(remapped.start(), remapped.end(), remapped.handler(), tcb.type));
                }
            }
            transformedTryCatchBlocks = remappedTryCatch;
        } else {
            transformedTryCatchBlocks = new ArrayList<>();
        }

        int transformedMaxStack = Math.max(mn.maxStack, 8);
        MethodNode probe = methodProbe(mn, newInsns, transformedTryCatchBlocks,
            transformedMaxLocals, transformedMaxStack);
        if (!canComputeFrames(clazz, probe, pctx.hierarchy())) {
            mn.maxLocals = originalMaxLocalsValue;
            mn.maxStack = originalMaxStackValue;
            mn.tryCatchBlocks = originalTryCatchBlocks;
            insertMinimalVerifiedGate(pctx, clazz, method, "minimal-verified-gate-after-full-frame-reject");
            return;
        }

        mn.instructions = newInsns;
        mn.tryCatchBlocks = transformedTryCatchBlocks;
        mn.localVariables = null;
        mn.maxLocals = transformedMaxLocals;
        mn.maxStack = transformedMaxStack;

        clazz.markDirty();
        flattenedMethods(pctx).add(methodKey(method));
        flowKeyLocalByMethod(pctx).put(methodKey(method), flowKeyVar);
        recordFull(pctx, clazz, method, "state-machine");
        pctx.invalidate(method);
    }

    private Map<String, Integer> flowKeyLocalByMethod(PipelineContext pctx) {
        Map<String, Integer> map = pctx.getPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY);
        if (map == null) {
            map = new HashMap<>();
            pctx.putPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY, map);
        }
        return map;
    }

    private void insertMinimalVerifiedGate(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        MethodNode mn = method.asmNode();
        LabelNode body = new LabelNode();
        LabelNode dflt = new LabelNode();
        int state = pctx.random().nextInt();
        int mask = pctx.random().nextInt() | 1;
        InsnList gate = new InsnList();
        gate.add(AsmUtil.pushIntAny(state ^ mask));
        gate.add(AsmUtil.pushIntAny(mask));
        gate.add(new InsnNode(Opcodes.IXOR));
        gate.add(new LookupSwitchInsnNode(dflt, new int[] { state }, new LabelNode[] { body }));
        gate.add(dflt);
        gate.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        gate.add(new InsnNode(Opcodes.DUP));
        gate.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>", "()V", false));
        gate.add(new InsnNode(Opcodes.ATHROW));
        gate.add(body);

        if (method.isConstructor()) {
            AbstractInsnNode initCall = firstConstructorInitCall(method);
            if (initCall == null) {
                recordFailClosed(pctx, clazz, method, "constructor-minimal-gate-no-init-anchor");
                throw new IllegalStateException("Cannot place constructor CFF safe gate for " + methodKey(method));
            }
            method.instructions().insert(initCall, gate);
        } else {
            method.instructions().insert(gate);
        }
        mn.maxStack = Math.max(mn.maxStack, 4);
        clazz.markDirty();
        flattenedMethods(pctx).add(methodKey(method));
        recordSafe(pctx, clazz, method, reason);
        pctx.invalidate(method);
    }

    private void recordFull(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordSafe(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).safe(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordNotApplicable(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private void recordFailClosed(PipelineContext pctx, L1Class clazz, L1Method method, String reason) {
        JvmObfuscationCoverage.get(pctx).failClosed(id(), clazz.name(), method.name(), method.descriptor(), reason);
    }

    private MethodNode methodProbe(MethodNode original, InsnList instructions,
            List<TryCatchBlockNode> tryCatchBlocks, int maxLocals, int maxStack) {
        MethodNode probe = new MethodNode(original.access, original.name, original.desc,
            original.signature, original.exceptions == null ? null : original.exceptions.toArray(String[]::new));
        probe.instructions = instructions;
        probe.tryCatchBlocks = tryCatchBlocks;
        probe.maxLocals = maxLocals;
        probe.maxStack = maxStack;
        return probe;
    }

    private boolean canComputeFrames(L1Class clazz, MethodNode method, ClassHierarchy hierarchy) {
        ClassNode owner = clazz.asmNode();
        ClassNode probe = new ClassNode();
        probe.version = owner.version;
        probe.access = owner.access;
        probe.name = owner.name;
        probe.signature = owner.signature;
        probe.superName = owner.superName;
        probe.interfaces = owner.interfaces == null ? new ArrayList<>() : new ArrayList<>(owner.interfaces);
        probe.fields = owner.fields == null ? new ArrayList<>() : new ArrayList<>(owner.fields);
        probe.methods = new ArrayList<>();
        // Include all sibling methods (unchanged) so the probe constant pool
        // matches the actual write more closely. Replace the single method
        // we're testing with the rewritten copy.
        for (MethodNode sibling : owner.methods) {
            if (sibling.name.equals(method.name) && sibling.desc.equals(method.desc)) {
                probe.methods.add(method);
            } else {
                probe.methods.add(sibling);
            }
        }
        try {
            new Analyzer<>(new BasicInterpreter()).analyze(owner.name, method);
            ClassWriter cw = new HierarchyAwareClassWriter(hierarchy);
            probe.accept(cw);
            byte[] bytes = cw.toByteArray();
            // Run CheckClassAdapter against the freshly-emitted bytes. ASM's
            // COMPUTE_FRAMES path can leak illegal opcode bytes when methods
            // grow past certain sizes; the bytes look fine to ClassReader's
            // tolerant parser but the JVM verifier rejects them at load time.
            // CheckClassAdapter walks the actual bytecode and reports any
            // verification failure, so we can hard-reject the CFF result
            // instead of shipping unverifiable bytecode downstream.
            java.io.StringWriter sw = new java.io.StringWriter();
            try {
                org.objectweb.asm.util.CheckClassAdapter.verify(
                    new org.objectweb.asm.ClassReader(bytes),
                    false,
                    new java.io.PrintWriter(sw));
            } catch (Throwable verifyError) {
                return false;
            }
            String diagnostics = sw.toString();
            boolean structurallyOk = diagnostics.isBlank() || containsOnlyClassNotFoundErrors(diagnostics);
            if (!structurallyOk) {
                return false;
            }
            if (codeAttributeHasIllegalOpcodes(bytes)) {
                return false;
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    /**
     * Walk every Code attribute byte-by-byte and verify each opcode is a known
     * JVM opcode. ASM's COMPUTE_FRAMES path occasionally writes an illegal
     * byte (0xF6–0xFD) that ClassReader silently maps to a -1 InsnNode but
     * which the JVM verifier rejects with "Bad instruction". Catching this in
     * our probe lets the caller revert to the original, unflattened method.
     */
    private static boolean codeAttributeHasIllegalOpcodes(byte[] bytes) {
        try {
            org.objectweb.asm.ClassReader reader = new org.objectweb.asm.ClassReader(bytes);
            final boolean[] bad = { false };
            reader.accept(new org.objectweb.asm.ClassVisitor(Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(int access, String name, String desc,
                        String signature, String[] exceptions) {
                    return new org.objectweb.asm.MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitInsn(int opcode) {
                            if (!isValidOpcode(opcode)) bad[0] = true;
                        }
                    };
                }
            }, 0);
            if (bad[0]) return true;

            // ClassReader strips illegal bytes silently. Walk the raw class
            // file, find each Code attribute, and validate each instruction's
            // opcode byte directly.
            return scanRawBytecode(bytes);
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean isValidOpcode(int op) {
        return op >= 0 && op <= 201;
    }

    /**
     * Minimal class-file walker that locates each method's Code attribute and
     * validates that every instruction starts with a recognised opcode. We
     * accept the JVMS-defined range 0x00–0xC9 plus 0xC4 (wide), 0xC5 (multianewarray),
     * 0xC6/0xC7 (ifnull/ifnonnull), 0xC8/0xC9 (goto_w/jsr_w). 0xCA–0xFF are
     * reserved/illegal and indicate ASM emission corruption.
     */
    private static boolean scanRawBytecode(byte[] bytes) {
        try {
            java.nio.ByteBuffer buf = java.nio.ByteBuffer.wrap(bytes);
            buf.getInt(); // magic
            buf.getShort(); // minor
            buf.getShort(); // major
            int cpCount = buf.getShort() & 0xFFFF;
            String[] utf8 = new String[cpCount];
            int i = 1;
            while (i < cpCount) {
                int tag = buf.get() & 0xFF;
                switch (tag) {
                    case 1: // Utf8
                        int len = buf.getShort() & 0xFFFF;
                        byte[] str = new byte[len];
                        buf.get(str);
                        utf8[i] = new String(str, java.nio.charset.StandardCharsets.UTF_8);
                        break;
                    case 5: case 6: // Long, Double - takes 2 slots
                        buf.getLong();
                        i++;
                        break;
                    case 7: case 8: case 16: case 19: case 20:
                        buf.getShort();
                        break;
                    case 3: case 4:
                        buf.getInt();
                        break;
                    case 9: case 10: case 11: case 12: case 17: case 18:
                        buf.getInt();
                        break;
                    case 15:
                        buf.get();
                        buf.getShort();
                        break;
                    default:
                        return true; // unknown tag, fail safe
                }
                i++;
            }
            buf.getShort(); // access
            buf.getShort(); // this_class
            buf.getShort(); // super_class
            int ifaceCount = buf.getShort() & 0xFFFF;
            buf.position(buf.position() + ifaceCount * 2);
            // fields
            int fieldCount = buf.getShort() & 0xFFFF;
            for (int f = 0; f < fieldCount; f++) {
                buf.getShort(); buf.getShort(); buf.getShort();
                int attrs = buf.getShort() & 0xFFFF;
                for (int a = 0; a < attrs; a++) {
                    buf.getShort();
                    int attrLen = buf.getInt();
                    buf.position(buf.position() + attrLen);
                }
            }
            // methods
            int methodCount = buf.getShort() & 0xFFFF;
            for (int m = 0; m < methodCount; m++) {
                buf.getShort(); buf.getShort(); buf.getShort();
                int attrs = buf.getShort() & 0xFFFF;
                for (int a = 0; a < attrs; a++) {
                    int attrName = buf.getShort() & 0xFFFF;
                    int attrLen = buf.getInt();
                    int attrEnd = buf.position() + attrLen;
                    if ("Code".equals(utf8[attrName])) {
                        buf.getShort(); // max_stack
                        buf.getShort(); // max_locals
                        int codeLen = buf.getInt();
                        int codeStart = buf.position();
                        if (!walkCode(bytes, codeStart, codeLen)) {
                            return true;
                        }
                    }
                    buf.position(attrEnd);
                }
            }
            return false;
        } catch (Throwable t) {
            return true;
        }
    }

    private static boolean walkCode(byte[] bytes, int start, int length) {
        int p = start;
        int end = start + length;
        while (p < end) {
            int op = bytes[p] & 0xFF;
            int size = opcodeSize(bytes, p, op);
            if (size <= 0) return false;
            p += size;
        }
        return p == end;
    }

    private static int opcodeSize(byte[] bytes, int pos, int op) {
        // Returns total instruction size, or -1 if illegal.
        switch (op) {
            case 0x00: case 0x01: case 0x02: case 0x03: case 0x04: case 0x05: case 0x06: case 0x07:
            case 0x08: case 0x09: case 0x0a: case 0x0b: case 0x0c: case 0x0d: case 0x0e: case 0x0f:
                return 1;
            case 0x10: case 0x12: return 2; // bipush, ldc
            case 0x11: case 0x13: case 0x14: return 3; // sipush, ldc_w, ldc2_w
            case 0x15: case 0x16: case 0x17: case 0x18: case 0x19: return 2; // *load N
            case 0x1a: case 0x1b: case 0x1c: case 0x1d: case 0x1e: case 0x1f: case 0x20: case 0x21:
            case 0x22: case 0x23: case 0x24: case 0x25: case 0x26: case 0x27: case 0x28: case 0x29:
            case 0x2a: case 0x2b: case 0x2c: case 0x2d: case 0x2e: case 0x2f: case 0x30: case 0x31:
            case 0x32: case 0x33: case 0x34: case 0x35:
                return 1; // *load_X, *aload
            case 0x36: case 0x37: case 0x38: case 0x39: case 0x3a: return 2; // *store N
            case 0x3b: case 0x3c: case 0x3d: case 0x3e: case 0x3f: case 0x40: case 0x41: case 0x42:
            case 0x43: case 0x44: case 0x45: case 0x46: case 0x47: case 0x48: case 0x49: case 0x4a:
            case 0x4b: case 0x4c: case 0x4d: case 0x4e: case 0x4f: case 0x50: case 0x51: case 0x52:
            case 0x53: case 0x54: case 0x55: case 0x56:
                return 1; // *store_X, *astore
            case 0x57: case 0x58: case 0x59: case 0x5a: case 0x5b: case 0x5c: case 0x5d: case 0x5e:
            case 0x5f:
                return 1; // pop, dup, swap, etc
            case 0x60: case 0x61: case 0x62: case 0x63: case 0x64: case 0x65: case 0x66: case 0x67:
            case 0x68: case 0x69: case 0x6a: case 0x6b: case 0x6c: case 0x6d: case 0x6e: case 0x6f:
            case 0x70: case 0x71: case 0x72: case 0x73: case 0x74: case 0x75: case 0x76: case 0x77:
            case 0x78: case 0x79: case 0x7a: case 0x7b: case 0x7c: case 0x7d: case 0x7e: case 0x7f:
            case 0x80: case 0x81: case 0x82: case 0x83:
                return 1; // arithmetic
            case 0x84: return 3; // iinc
            case 0x85: case 0x86: case 0x87: case 0x88: case 0x89: case 0x8a: case 0x8b: case 0x8c:
            case 0x8d: case 0x8e: case 0x8f: case 0x90: case 0x91: case 0x92: case 0x93:
                return 1; // conversions
            case 0x94: case 0x95: case 0x96: case 0x97: case 0x98:
                return 1; // lcmp/fcmp/dcmp
            case 0x99: case 0x9a: case 0x9b: case 0x9c: case 0x9d: case 0x9e: case 0x9f: case 0xa0:
            case 0xa1: case 0xa2: case 0xa3: case 0xa4: case 0xa5: case 0xa6:
                return 3; // if* with 2-byte offset
            case 0xa7: case 0xa8: return 3; // goto, jsr
            case 0xa9: return 2; // ret
            case 0xaa: { // tableswitch
                int padding = (4 - ((pos + 1) & 3)) & 3;
                int start = pos + 1 + padding;
                if (start + 12 > bytes.length) return -1;
                int low = ((bytes[start + 4] & 0xFF) << 24) | ((bytes[start + 5] & 0xFF) << 16)
                    | ((bytes[start + 6] & 0xFF) << 8) | (bytes[start + 7] & 0xFF);
                int high = ((bytes[start + 8] & 0xFF) << 24) | ((bytes[start + 9] & 0xFF) << 16)
                    | ((bytes[start + 10] & 0xFF) << 8) | (bytes[start + 11] & 0xFF);
                if (high < low) return -1;
                long count = (long) high - low + 1;
                if (count < 0 || count > 100000) return -1;
                return 1 + padding + 12 + (int) (count * 4);
            }
            case 0xab: { // lookupswitch
                int padding = (4 - ((pos + 1) & 3)) & 3;
                int start = pos + 1 + padding;
                if (start + 8 > bytes.length) return -1;
                int npairs = ((bytes[start + 4] & 0xFF) << 24) | ((bytes[start + 5] & 0xFF) << 16)
                    | ((bytes[start + 6] & 0xFF) << 8) | (bytes[start + 7] & 0xFF);
                if (npairs < 0 || npairs > 100000) return -1;
                return 1 + padding + 8 + npairs * 8;
            }
            case 0xac: case 0xad: case 0xae: case 0xaf: case 0xb0: case 0xb1:
                return 1; // *return
            case 0xb2: case 0xb3: case 0xb4: case 0xb5: case 0xb6: case 0xb7: case 0xb8:
                return 3; // get/put*field/static, invokevirtual/special/static
            case 0xb9: return 5; // invokeinterface
            case 0xba: return 5; // invokedynamic
            case 0xbb: case 0xbd: case 0xc0: case 0xc1:
                return 3; // new, anewarray, checkcast, instanceof
            case 0xbc: return 2; // newarray
            case 0xbe: case 0xbf: return 1; // arraylength, athrow
            case 0xc2: case 0xc3: return 1; // monitorenter/exit
            case 0xc4: { // wide
                if (pos + 1 >= bytes.length) return -1;
                int wOp = bytes[pos + 1] & 0xFF;
                if (wOp == 0x84) return 6; // wide iinc
                return 4; // wide *load/*store/ret
            }
            case 0xc5: return 4; // multianewarray
            case 0xc6: case 0xc7: return 3; // ifnull, ifnonnull
            case 0xc8: case 0xc9: return 5; // goto_w, jsr_w
            default: return -1; // illegal opcode
        }
    }

    private static boolean containsOnlyClassNotFoundErrors(String diagnostics) {
        if (diagnostics.isBlank()) return true;
        String lower = diagnostics.toLowerCase(java.util.Locale.ROOT);
        // Reject if structural issues are reported.
        if (lower.contains("bad instruction")
            || lower.contains("bad type on operand stack")
            || lower.contains("inconsistent stackmap")
            || lower.contains("expecting a stackmap frame")
            || lower.contains("expecting type")
            || lower.contains("get long/double overflows")
            || lower.contains("bad local variable type")
            || lower.contains("invalid opcode")) {
            return false;
        }
        // CheckClassAdapter prints AnalyzerException with cause; if every cause
        // is ClassNotFoundException we treat the diagnostic as benign.
        for (String line : diagnostics.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.contains("AnalyzerException")
                && trimmed.contains("ClassNotFoundException")) {
                continue;
            }
            if (trimmed.startsWith("at ")) continue;
            if (trimmed.startsWith("Caused by")
                && trimmed.contains("ClassNotFoundException")) {
                continue;
            }
            return false;
        }
        return true;
    }

    private static boolean hasOnlyValidOpcodes(MethodNode method) {
        if (method.instructions == null) {
            return true;
        }
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int op = insn.getOpcode();
            if (op == -1) {
                continue;
            }
            if (op < 0 || op > 201) {
                return false;
            }
        }
        return true;
    }

    private static final class FrameProbeClassWriter extends ClassWriter {
        private FrameProbeClassWriter() {
            super(ClassWriter.COMPUTE_FRAMES);
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            return "java/lang/Object";
        }
    }

    private static final class HierarchyAwareClassWriter extends ClassWriter {
        private final ClassHierarchy hierarchy;

        HierarchyAwareClassWriter(ClassHierarchy hierarchy) {
            super(ClassWriter.COMPUTE_FRAMES);
            this.hierarchy = hierarchy;
        }

        @Override
        protected String getCommonSuperClass(String type1, String type2) {
            if (hierarchy != null) {
                String result = hierarchy.getCommonSuperClass(type1, type2);
                if (result != null && !result.equals("java/lang/Object")) {
                    return result;
                }
            }
            try {
                return super.getCommonSuperClass(type1, type2);
            } catch (Throwable t) {
                return "java/lang/Object";
            }
        }
    }

    private boolean isTerminator(AbstractInsnNode insn, BasicBlock block) {
        AbstractInsnNode last = block.lastInsn();
        if (insn != last) return false;
        int opcode = insn.getOpcode();
        return opcode == Opcodes.GOTO || opcode == 200
            || AsmUtil.isConditionalJump(opcode)
            || insn instanceof TableSwitchInsnNode
            || insn instanceof LookupSwitchInsnNode;
    }

    private void emitDispatcherSwitch(InsnList insns, PipelineContext pctx, long methodKey, int dispatchStateVar,
            int[] keys, LabelNode[] switchLabels, LabelNode dispatcherDefault) {
        int fragments = dispatcherFragments(pctx, keys.length);
        if (fragments > 1 && keys.length >= fragments * 2) {
            DispatcherShape fragmentShape = dispatcherShape(pctx,
                methodKey ^ 0x465241474D454E54L);
            Map<Integer, List<Integer>> fragmentIndexes = new TreeMap<>();
            for (int i = 0; i < keys.length; i++) {
                int fragment = Math.floorMod(keys[i] ^ fragmentShape.salt(), fragments);
                fragmentIndexes.computeIfAbsent(fragment, ignored -> new ArrayList<>()).add(i);
            }

            int[] fragmentKeys = new int[fragmentIndexes.size()];
            LabelNode[] fragmentLabels = new LabelNode[fragmentIndexes.size()];
            int fragmentIndex = 0;
            for (Integer fragment : fragmentIndexes.keySet()) {
                fragmentKeys[fragmentIndex] = fragment;
                fragmentLabels[fragmentIndex] = new LabelNode();
                fragmentIndex++;
            }

            insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
            insns.add(AsmUtil.pushIntAny(fragmentShape.salt()));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(AsmUtil.pushIntAny(fragments));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Math", "floorMod", "(II)I", false));
            insns.add(new LookupSwitchInsnNode(dispatcherDefault, fragmentKeys, fragmentLabels));

            fragmentIndex = 0;
            for (List<Integer> indexes : fragmentIndexes.values()) {
                indexes.sort(Comparator.comparingInt(i -> keys[i]));
                int[] innerKeys = new int[indexes.size()];
                LabelNode[] innerLabels = new LabelNode[indexes.size()];
                for (int i = 0; i < indexes.size(); i++) {
                    int originalIndex = indexes.get(i);
                    innerKeys[i] = keys[originalIndex];
                    innerLabels[i] = switchLabels[originalIndex];
                }
                insns.add(fragmentLabels[fragmentIndex++]);
                insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
                insns.add(new LookupSwitchInsnNode(dispatcherDefault, innerKeys, innerLabels));
            }
            return;
        }

        int depth = dispatcherDepth(pctx, keys.length);
        if (depth <= 1 || keys.length < 4) {
            insns.add(new LookupSwitchInsnNode(dispatcherDefault, keys, switchLabels));
            return;
        }

        int bucketMask = depth - 1;
        DispatcherShape shape = dispatcherShape(pctx, methodKey);
        Map<Integer, List<Integer>> bucketIndexes = new TreeMap<>();
        for (int i = 0; i < keys.length; i++) {
            bucketIndexes.computeIfAbsent(dispatchBucket(keys[i], bucketMask, shape), ignored -> new ArrayList<>()).add(i);
        }

        int[] outerKeys = new int[bucketIndexes.size()];
        LabelNode[] outerLabels = new LabelNode[bucketIndexes.size()];
        int outerIndex = 0;
        for (Integer bucket : bucketIndexes.keySet()) {
            outerKeys[outerIndex] = bucket;
            outerLabels[outerIndex] = new LabelNode();
            outerIndex++;
        }

        emitDispatchBucketValue(insns, dispatchStateVar, bucketMask, shape);
        insns.add(new LookupSwitchInsnNode(dispatcherDefault, outerKeys, outerLabels));

        outerIndex = 0;
        for (List<Integer> indexes : bucketIndexes.values()) {
            indexes.sort(Comparator.comparingInt(i -> keys[i]));
            int[] innerKeys = new int[indexes.size()];
            LabelNode[] innerLabels = new LabelNode[indexes.size()];
            for (int i = 0; i < indexes.size(); i++) {
                int originalIndex = indexes.get(i);
                innerKeys[i] = keys[originalIndex];
                innerLabels[i] = switchLabels[originalIndex];
            }
            insns.add(outerLabels[outerIndex++]);
            insns.add(new VarInsnNode(Opcodes.ILOAD, dispatchStateVar));
            insns.add(new LookupSwitchInsnNode(dispatcherDefault, innerKeys, innerLabels));
        }
    }

    private void emitDispatchBlock(InsnList insns, BasicBlock block,
            Map<BasicBlock, LabelNode> blockCaseLabels, Map<LabelNode, LabelNode> labelRemap,
            PipelineContext pctx, Map<BasicBlock, Long> flowKeyMap,
            int flowKeyVar, int flowMixVar, Map<BasicBlock, Integer> stateMap,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            int tailSeedVar, int tailFlagVar, boolean zkmStyle, double tailChainIntensity,
            List<TailChain> tailChains, LabelNode loopStart, LabelNode loopEnd,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        LabelNode caseLabel = blockCaseLabels.get(block);
        insns.add(caseLabel);
        insns.add(new InsnNode(Opcodes.NOP));
        restoreBlockEntryLocals(insns, block, blockEntryLocals, blockLocalSpillBases);
        restoreBlockEntryStack(insns, block, blockEntryStacks, blockSpillBases);

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof FrameNode) continue;
            if (insn instanceof LineNumberNode) continue;
            if (isTerminator(insn, block)) continue;

            if (insn instanceof LabelNode origLabel) {
                LabelNode remapped = labelRemap.get(origLabel);
                if (remapped != null) insns.add(remapped);
                continue;
            }

            AbstractInsnNode clone = insn.clone(labelRemap);
            insns.add(clone);
            recordInstructionFlowKey(pctx, clone, flowKeyMap.getOrDefault(block, 0L));
            // No mid-block flowKey resync: flowKey is updated only at block-exit transitions,
            // so it stays valid throughout the block body. Removing this sync eliminates the
            // ThreadLocal hot-path tax on every MethodInsn / InvokeDynamicInsn.
        }

        emitStateTransition(insns, block, stateMap,
            flowKeyMap, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
            tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
            tailChains, loopStart, loopEnd, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private void emitStateTransition(InsnList insns, BasicBlock block,
            Map<BasicBlock, Integer> stateMap,
            Map<BasicBlock, Long> flowKeyMap, int flowKeyVar, int flowMixVar,
            int encodedStateVar, int stateMaskVar, int stateDeltaVar, int stateRotate,
            int tailSeedVar, int tailFlagVar, boolean zkmStyle, double tailChainIntensity,
            List<TailChain> tailChains, LabelNode loopStart, LabelNode loopEnd,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {

        List<CFGEdge> outEdges = normalOutEdges(block);
        if (outEdges.isEmpty()) return;

        long sourceFlowKey = flowKeyMap.getOrDefault(block, 0L);

        AbstractInsnNode lastReal = findLastRealInsn(block);
        if (lastReal == null) {
                emitUnconditionalTransition(insns, outEdges.get(0).target(), stateMap,
                    flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                    tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                    loopStart, 0, blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }

        int opcode = lastReal.getOpcode();

        if (AsmUtil.isReturn(opcode) || opcode == Opcodes.ATHROW) return;

        if (AsmUtil.isConditionalJump(opcode)) {
            CFGEdge trueEdge = null, falseEdge = null;
            for (CFGEdge edge : outEdges) {
                if (edge.type() == CFGEdge.Type.CONDITIONAL_TRUE) trueEdge = edge;
                else if (edge.type() == CFGEdge.Type.CONDITIONAL_FALSE) falseEdge = edge;
                else if (trueEdge == null) trueEdge = edge;
                else if (falseEdge == null) falseEdge = edge;
            }
            if (trueEdge == null || falseEdge == null) {
                emitUnconditionalTransition(insns, outEdges.get(0).target(), stateMap,
                    flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                    tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                    loopStart, block.id(), blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
                return;
            }

            int trueState = requiredState(stateMap, trueEdge.target());
            int falseState = requiredState(stateMap, falseEdge.target());

            LabelNode trueLabel = new LabelNode();
            LabelNode joinLabel = new LabelNode();
            insns.add(new JumpInsnNode(opcode, trueLabel));

            spillStackForTarget(insns, falseEdge.target(), blockEntryStacks, blockSpillBases);
            spillLocalsForTarget(insns, falseEdge.target(), blockEntryLocals, blockLocalSpillBases);
            emitFlowKeyDelta(insns, sourceFlowKey, flowKeyMap.getOrDefault(falseEdge.target(), 0L),
                flowKeyVar, flowMixVar);
            emitEncodedStateStore(insns, falseState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, block.id() + 1);
            insns.add(new JumpInsnNode(Opcodes.GOTO, joinLabel));

            insns.add(trueLabel);
            spillStackForTarget(insns, trueEdge.target(), blockEntryStacks, blockSpillBases);
            spillLocalsForTarget(insns, trueEdge.target(), blockEntryLocals, blockLocalSpillBases);
            emitFlowKeyDelta(insns, sourceFlowKey, flowKeyMap.getOrDefault(trueEdge.target(), 0L),
                flowKeyVar, flowMixVar);
            emitEncodedStateStore(insns, trueState, encodedStateVar, stateMaskVar, stateDeltaVar,
                flowMixVar, stateRotate, block.id());
            insns.add(joinLabel);
            emitLoopReentry(insns, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
                tailChains, loopStart, block.id());
            return;
        }

        if (lastReal instanceof TableSwitchInsnNode tableSwitch) {
            emitTableSwitchTransition(insns, outEdges, stateMap, flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar,
                encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, tableSwitch, block.id(),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }

        if (lastReal instanceof LookupSwitchInsnNode lookupSwitch) {
            emitLookupSwitchTransition(insns, outEdges, stateMap, flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar,
                encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, lookupSwitch, block.id(),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }

        for (CFGEdge edge : outEdges) {
            emitUnconditionalTransition(insns, edge.target(), stateMap,
                flowKeyMap, sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate,
                tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity, tailChains,
                loopStart, block.id(), blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
            return;
        }
    }

    private void emitUnconditionalTransition(InsnList insns, BasicBlock target,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            long sourceFlowKey,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, int variantSeed,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        int nextState = requiredState(stateMap, target);
        spillStackForTarget(insns, target, blockEntryStacks, blockSpillBases);
        spillLocalsForTarget(insns, target, blockEntryLocals, blockLocalSpillBases);
        emitFlowKeyDelta(insns, sourceFlowKey, flowKeyMap.getOrDefault(target, 0L),
            flowKeyVar, flowMixVar);
        emitEncodedStateStore(insns, nextState, encodedStateVar, stateMaskVar, stateDeltaVar,
            flowMixVar, stateRotate, variantSeed);
        emitLoopReentry(insns, tailSeedVar, tailFlagVar, zkmStyle, tailChainIntensity,
            tailChains, loopStart, variantSeed);
    }

    private void emitTableSwitchTransition(InsnList insns, List<CFGEdge> outEdges,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            long sourceFlowKey,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart,
            TableSwitchInsnNode tableSwitch, int variantSeed,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        BasicBlock defaultTarget = null;
        Map<Integer, BasicBlock> targets = new TreeMap<>();
        for (CFGEdge edge : outEdges) {
            if (edge.type() == CFGEdge.Type.SWITCH_DEFAULT) {
                defaultTarget = edge.target();
            } else if (edge.type() == CFGEdge.Type.SWITCH_CASE) {
                targets.put(edge.switchKey(), edge.target());
            }
        }
        if (defaultTarget == null && !outEdges.isEmpty()) {
            defaultTarget = outEdges.get(0).target();
        }
        if (defaultTarget == null) return;

        LabelNode defaultLabel = new LabelNode();
        LabelNode[] labels = new LabelNode[tableSwitch.max - tableSwitch.min + 1];
        List<Map.Entry<Integer, LabelNode>> caseLabels = new ArrayList<>();
        for (int key = tableSwitch.min; key <= tableSwitch.max; key++) {
            if (targets.containsKey(key)) {
                LabelNode label = new LabelNode();
                labels[key - tableSwitch.min] = label;
                caseLabels.add(Map.entry(key, label));
            } else {
                labels[key - tableSwitch.min] = defaultLabel;
            }
        }
        insns.add(new TableSwitchInsnNode(tableSwitch.min, tableSwitch.max, defaultLabel, labels));
        for (Map.Entry<Integer, LabelNode> entry : caseLabels) {
            insns.add(entry.getValue());
            emitUnconditionalTransition(insns, targets.get(entry.getKey()), stateMap, flowKeyMap,
                sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + entry.getKey(),
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        }
        insns.add(defaultLabel);
        emitUnconditionalTransition(insns, defaultTarget, stateMap, flowKeyMap,
            sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
            stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + 31,
            blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private void emitLookupSwitchTransition(InsnList insns, List<CFGEdge> outEdges,
            Map<BasicBlock, Integer> stateMap, Map<BasicBlock, Long> flowKeyMap,
            long sourceFlowKey,
            int flowKeyVar, int flowMixVar, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart,
            LookupSwitchInsnNode lookupSwitch, int variantSeed,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks, Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals, Map<BasicBlock, Integer> blockLocalSpillBases) {
        BasicBlock defaultTarget = null;
        Map<Integer, BasicBlock> targets = new TreeMap<>();
        for (CFGEdge edge : outEdges) {
            if (edge.type() == CFGEdge.Type.SWITCH_DEFAULT) {
                defaultTarget = edge.target();
            } else if (edge.type() == CFGEdge.Type.SWITCH_CASE) {
                targets.put(edge.switchKey(), edge.target());
            }
        }
        if (defaultTarget == null && !outEdges.isEmpty()) {
            defaultTarget = outEdges.get(0).target();
        }
        if (defaultTarget == null) return;

        LabelNode defaultLabel = new LabelNode();
        List<Integer> sortedKeys = new ArrayList<>(targets.keySet());
        Collections.sort(sortedKeys);
        int[] keys = new int[sortedKeys.size()];
        LabelNode[] labels = new LabelNode[sortedKeys.size()];
        for (int i = 0; i < sortedKeys.size(); i++) {
            keys[i] = sortedKeys.get(i);
            labels[i] = new LabelNode();
        }
        insns.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));
        for (int i = 0; i < sortedKeys.size(); i++) {
            insns.add(labels[i]);
            emitUnconditionalTransition(insns, targets.get(sortedKeys.get(i)), stateMap, flowKeyMap,
                sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
                stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
                zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + i,
                blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
        }
        insns.add(defaultLabel);
        emitUnconditionalTransition(insns, defaultTarget, stateMap, flowKeyMap,
            sourceFlowKey, flowKeyVar, flowMixVar, encodedStateVar,
            stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, variantSeed + 29,
            blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private int requiredState(Map<BasicBlock, Integer> stateMap, BasicBlock target) {
        Integer state = stateMap.get(target);
        if (state == null) {
            throw new IllegalStateException("Missing dispatch state for block " + target.id());
        }
        return state;
    }

    private void emitLoopReentry(InsnList insns, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, int variantSeed) {
        if (!shouldUseTailChain(zkmStyle, tailChainIntensity, variantSeed)) {
            insns.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
            return;
        }

        LabelNode tailEntry = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.GOTO, tailEntry));
        tailChains.add(new TailChain(tailEntry,
            buildTailChain(loopStart, tailSeedVar, tailFlagVar, variantSeed)));
    }

    private InsnList buildTailChain(LabelNode loopStart, int tailSeedVar, int tailFlagVar, int variantSeed) {
        InsnList tail = new InsnList();
        LabelNode fallback = new LabelNode();
        tail.add(new IincInsnNode(tailSeedVar, 1 + Math.floorMod(variantSeed, 7)));
        tail.add(new VarInsnNode(Opcodes.ILOAD, tailSeedVar));
        tail.add(AsmUtil.pushIntAny(foldMethodKey(0x5F3759DFL ^ (variantSeed * 0x45D9F3B))));
        tail.add(new InsnNode(Opcodes.IXOR));
        tail.add(new InsnNode(Opcodes.ICONST_1));
        tail.add(new InsnNode(Opcodes.IOR));
        tail.add(new InsnNode(Opcodes.DUP));
        tail.add(new VarInsnNode(Opcodes.ISTORE, tailFlagVar));
        tail.add(new JumpInsnNode(Opcodes.IFEQ, fallback));
        tail.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        tail.add(fallback);
        tail.add(new JumpInsnNode(Opcodes.GOTO, loopStart));
        return tail;
    }

    private void emitStateDecode(InsnList insns, int encodedStateVar, int dispatchStateVar,
            int stateMaskVar, int stateDeltaVar, int flowMixVar, int stateRotate) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, encodedStateVar));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateDeltaVar));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(AsmUtil.pushIntAny(stateRotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
            "rotateRight", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowMixVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dispatchStateVar));
    }

    private void emitEncodedStateStore(InsnList insns, int decodedState, int encodedStateVar,
            int stateMaskVar, int stateDeltaVar, int flowMixVar, int stateRotate, int variantSeed) {
        emitEncodedStateValue(insns, decodedState, stateMaskVar, stateDeltaVar, flowMixVar, stateRotate, variantSeed);
        insns.add(new VarInsnNode(Opcodes.ISTORE, encodedStateVar));
    }

    private void emitEncodedStateValue(InsnList insns, int decodedState, int stateMaskVar,
            int stateDeltaVar, int flowMixVar, int stateRotate, int variantSeed) {
        int variant = Math.floorMod(variantSeed, 3);
        if (variant == 1) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
            insns.add(AsmUtil.pushIntAny(decodedState));
        } else {
            insns.add(AsmUtil.pushIntAny(decodedState));
            insns.add(new VarInsnNode(Opcodes.ILOAD, stateMaskVar));
        }
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowMixVar));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(AsmUtil.pushIntAny(stateRotate));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
            "rotateLeft", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateDeltaVar));
        if (variant == 2) {
            insns.add(new InsnNode(Opcodes.INEG));
            insns.add(new InsnNode(Opcodes.ISUB));
        } else {
            insns.add(new InsnNode(Opcodes.IADD));
        }
    }

    /**
     * Real edge-keyed flowKey transition: flowKey ^= delta(source -> target).
     * The delta is the only LDC, and it's meaningless unless the running flowKey is correct,
     * so static analysis cannot recover flowKey at any point without symbolically simulating
     * the entire CFG from method entry.
     */
    private void emitFlowKeyDelta(InsnList insns, long sourceFlowKey, long targetFlowKey,
            int flowKeyVar, int flowMixVar) {
        long delta = sourceFlowKey ^ targetFlowKey;
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowKeyVar));
        insns.add(new LdcInsnNode(delta));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitStoreFlowKeyAndUpdateMix(insns, flowKeyVar, flowMixVar);
    }

    /**
     * Absolute flowKey assignment. Used at method entry and exception-handler entry where
     * predecessor flowKey is not predictable. Splits the value into two LDCs combined via
     * LXOR so neither LDC equals the target flowKey directly.
     */
    private void emitFlowKeyAbsolute(InsnList insns, long methodKey, long targetFlowKey,
            int flowKeyVar, int flowMixVar, int splitHint) {
        long splitSeed = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(
                methodKey ^ 0x4E454B0F4D4F4F4EL, splitHint));
        long maskA = splitSeed | 1L;
        long maskB = targetFlowKey ^ maskA;
        insns.add(new LdcInsnNode(maskA));
        insns.add(new LdcInsnNode(maskB));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitStoreFlowKeyAndUpdateMix(insns, flowKeyVar, flowMixVar);
    }

    /**
     * Backwards-compatible facade used only where source flowKey is unknown (legacy paths).
     * Prefer emitFlowKeyDelta / emitFlowKeyAbsolute. Embeds the value as a two-LDC XOR split
     * so the literal target value never appears as a single LDC.
     */
    private void emitFlowKeyStore(InsnList insns, long flowKey, int flowKeyVar, int flowMixVar) {
        emitFlowKeyAbsolute(insns, flowKey ^ 0xC0FFEE5EEDC0DEFFL, flowKey, flowKeyVar, flowMixVar, 0);
    }

    /**
     * Bytecode equivalent of: flowKey = (top of stack); flowMix = (int)(flowKey ^ (flowKey >>> 32)) | 1.
     * Consumes the long on top of stack, stores into flowKeyVar, and writes flowMixVar.
     */
    private void emitStoreFlowKeyAndUpdateMix(InsnList insns, int flowKeyVar, int flowMixVar) {
        // stack: [..., long newFlowKey]
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new VarInsnNode(Opcodes.LSTORE, flowKeyVar));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(AsmUtil.pushIntAny(32));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, flowMixVar));
    }

    private void emitRuntimeFlowContextSync(InsnList insns, int flowKeyVar) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowKeyVar));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CONTEXT_OWNER,
            "setCurrentFlowKey", "(J)V", false));
    }

    /**
     * Emit `value = a ^ b; ISTORE slot` as a 2-LDC XOR split, where the salt
     * derives a deterministic mask so neither LDC equals the literal value.
     */
    private void emitSplitIntStore(InsnList insns, int value, int slot, long salt) {
        int maskA = (int) (salt ^ (salt >>> 32)) | 1;
        int maskB = value ^ maskA;
        insns.add(AsmUtil.pushIntAny(maskA));
        insns.add(AsmUtil.pushIntAny(maskB));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
    }

    private void recordInstructionFlowKey(PipelineContext pctx, AbstractInsnNode insn, long flowKey) {
        flowKeyValues(pctx).put(insn, flowKey);
    }

    private Set<String> flattenedMethods(PipelineContext pctx) {
        Set<String> flattenedMethods = pctx.getPassData(FLATTENED_METHODS_KEY);
        if (flattenedMethods == null) {
            flattenedMethods = new HashSet<>();
            pctx.putPassData(FLATTENED_METHODS_KEY, flattenedMethods);
        }
        return flattenedMethods;
    }

    private IdentityHashMap<AbstractInsnNode, Long> flowKeyValues(PipelineContext pctx) {
        IdentityHashMap<AbstractInsnNode, Long> flowKeys = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        if (flowKeys == null) {
            flowKeys = new IdentityHashMap<>();
            pctx.putPassData(FLOW_KEY_VALUES_KEY, flowKeys);
        }
        return flowKeys;
    }

    private void emitOriginalLocalInitialization(InsnList insns, L1Method method, int originalMaxLocals) {
        if (originalMaxLocals <= 0) return;

        LocalInitKind[] kinds = inferOriginalLocalKinds(method, originalMaxLocals);
        for (int slot = parameterSlotCount(method); slot < originalMaxLocals; slot++) {
            LocalInitKind kind = kinds[slot];
            switch (kind) {
                case REFERENCE -> {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
                }
                case INT -> {
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
                }
                case FLOAT -> {
                    insns.add(new InsnNode(Opcodes.FCONST_0));
                    insns.add(new VarInsnNode(Opcodes.FSTORE, slot));
                }
                case LONG -> {
                    insns.add(new InsnNode(Opcodes.LCONST_0));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
                    slot++;
                }
                case DOUBLE -> {
                    insns.add(new InsnNode(Opcodes.DCONST_0));
                    insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
                    slot++;
                }
                default -> {
                }
            }
        }
    }

    private LocalInitKind[] inferOriginalLocalKinds(L1Method method, int originalMaxLocals) {
        LocalInitKind[] kinds = new LocalInitKind[originalMaxLocals];
        LocalInitKind[] firstSeenKinds = new LocalInitKind[originalMaxLocals];
        Arrays.fill(kinds, LocalInitKind.UNKNOWN);
        Arrays.fill(firstSeenKinds, LocalInitKind.UNKNOWN);

        int slot = 0;
        if (!method.isStatic() && originalMaxLocals > 0) {
            kinds[0] = LocalInitKind.REFERENCE;
            firstSeenKinds[0] = LocalInitKind.REFERENCE;
            slot = 1;
        }
        for (Type argumentType : method.argumentTypes()) {
            if (slot >= originalMaxLocals) break;
            markTypeKind(kinds, slot, argumentType);
            recordTypeKind(firstSeenKinds, slot, argumentType);
            slot += argumentType.getSize();
        }

        for (LocalVariableNode localVariable : method.localVariables()) {
            if (localVariable.index < 0 || localVariable.index >= originalMaxLocals) continue;
            markTypeKind(kinds, localVariable.index, Type.getType(localVariable.desc));
            recordTypeKind(firstSeenKinds, localVariable.index, Type.getType(localVariable.desc));
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode varInsn) {
                markOpcodeKind(kinds, varInsn.getOpcode(), varInsn.var);
                recordOpcodeKind(firstSeenKinds, varInsn.getOpcode(), varInsn.var);
            } else if (insn instanceof IincInsnNode iincInsn) {
                markLocalKind(kinds, iincInsn.var, LocalInitKind.INT);
                recordPreferredKind(firstSeenKinds, iincInsn.var, LocalInitKind.INT);
            }
        }

        for (int i = 0; i < kinds.length; i++) {
            if ((kinds[i] == LocalInitKind.UNKNOWN || kinds[i] == LocalInitKind.CONFLICT)
                    && firstSeenKinds[i] != LocalInitKind.UNKNOWN
                    && firstSeenKinds[i] != LocalInitKind.CONFLICT
                    && firstSeenKinds[i] != LocalInitKind.RESERVED) {
                kinds[i] = firstSeenKinds[i];
                if ((firstSeenKinds[i] == LocalInitKind.LONG || firstSeenKinds[i] == LocalInitKind.DOUBLE)
                        && i + 1 < kinds.length
                        && (kinds[i + 1] == LocalInitKind.UNKNOWN || kinds[i + 1] == LocalInitKind.CONFLICT)) {
                    kinds[i + 1] = LocalInitKind.RESERVED;
                }
            }
        }
        return kinds;
    }

    private int parameterSlotCount(L1Method method) {
        int slots = method.isStatic() ? 0 : 1;
        for (Type argumentType : method.argumentTypes()) {
            slots += argumentType.getSize();
        }
        return slots;
    }

    private void markOpcodeKind(LocalInitKind[] kinds, int opcode, int slot) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> markLocalKind(kinds, slot, LocalInitKind.INT);
            case Opcodes.FLOAD, Opcodes.FSTORE -> markLocalKind(kinds, slot, LocalInitKind.FLOAT);
            case Opcodes.LLOAD, Opcodes.LSTORE -> markWideLocalKind(kinds, slot, LocalInitKind.LONG);
            case Opcodes.DLOAD, Opcodes.DSTORE -> markWideLocalKind(kinds, slot, LocalInitKind.DOUBLE);
            case Opcodes.ALOAD, Opcodes.ASTORE -> markLocalKind(kinds, slot, LocalInitKind.REFERENCE);
            default -> {
            }
        }
    }

    private void recordOpcodeKind(LocalInitKind[] kinds, int opcode, int slot) {
        switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> recordPreferredKind(kinds, slot, LocalInitKind.INT);
            case Opcodes.FLOAD, Opcodes.FSTORE -> recordPreferredKind(kinds, slot, LocalInitKind.FLOAT);
            case Opcodes.LLOAD, Opcodes.LSTORE -> recordPreferredWideKind(kinds, slot, LocalInitKind.LONG);
            case Opcodes.DLOAD, Opcodes.DSTORE -> recordPreferredWideKind(kinds, slot, LocalInitKind.DOUBLE);
            case Opcodes.ALOAD, Opcodes.ASTORE -> recordPreferredKind(kinds, slot, LocalInitKind.REFERENCE);
            default -> {
            }
        }
    }

    private void markTypeKind(LocalInitKind[] kinds, int slot, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> markWideLocalKind(kinds, slot, LocalInitKind.LONG);
            case Type.DOUBLE -> markWideLocalKind(kinds, slot, LocalInitKind.DOUBLE);
            case Type.FLOAT -> markLocalKind(kinds, slot, LocalInitKind.FLOAT);
            case Type.ARRAY, Type.OBJECT -> markLocalKind(kinds, slot, LocalInitKind.REFERENCE);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                markLocalKind(kinds, slot, LocalInitKind.INT);
            default -> {
            }
        }
    }

    private void recordTypeKind(LocalInitKind[] kinds, int slot, Type type) {
        switch (type.getSort()) {
            case Type.LONG -> recordPreferredWideKind(kinds, slot, LocalInitKind.LONG);
            case Type.DOUBLE -> recordPreferredWideKind(kinds, slot, LocalInitKind.DOUBLE);
            case Type.FLOAT -> recordPreferredKind(kinds, slot, LocalInitKind.FLOAT);
            case Type.ARRAY, Type.OBJECT -> recordPreferredKind(kinds, slot, LocalInitKind.REFERENCE);
            case Type.BOOLEAN, Type.BYTE, Type.CHAR, Type.SHORT, Type.INT ->
                recordPreferredKind(kinds, slot, LocalInitKind.INT);
            default -> {
            }
        }
    }

    private void markWideLocalKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        markLocalKind(kinds, slot, kind);
        if (slot + 1 < kinds.length) {
            LocalInitKind existing = kinds[slot + 1];
            if (existing == LocalInitKind.UNKNOWN || existing == LocalInitKind.RESERVED) {
                kinds[slot + 1] = LocalInitKind.RESERVED;
            } else if (existing != kind) {
                kinds[slot + 1] = LocalInitKind.CONFLICT;
            }
        }
    }

    private void recordPreferredWideKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        recordPreferredKind(kinds, slot, kind);
        if (slot + 1 < kinds.length && kinds[slot + 1] == LocalInitKind.UNKNOWN) {
            kinds[slot + 1] = LocalInitKind.RESERVED;
        }
    }

    private void markLocalKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        if (slot < 0 || slot >= kinds.length) return;
        LocalInitKind existing = kinds[slot];
        if (existing == LocalInitKind.UNKNOWN || existing == kind) {
            kinds[slot] = kind;
            return;
        }
        if (existing == LocalInitKind.RESERVED && kind == LocalInitKind.RESERVED) {
            return;
        }
        kinds[slot] = LocalInitKind.CONFLICT;
    }

    private void recordPreferredKind(LocalInitKind[] kinds, int slot, LocalInitKind kind) {
        if (slot < 0 || slot >= kinds.length) {
            return;
        }
        if (kinds[slot] == LocalInitKind.UNKNOWN) {
            kinds[slot] = kind;
        }
    }

    private int[] blockEmissionOrder(PipelineContext pctx, int blockCount, boolean preserveTryCatchOrder) {
        if (!preserveTryCatchOrder) {
            return pctx.random().randomPermutation(blockCount);
        }

        int[] order = new int[blockCount];
        for (int i = 0; i < blockCount; i++) {
            order[i] = i;
        }
        return order;
    }

    private void partitionBlocks(List<BasicBlock> blocks, List<BasicBlock> dispatchBlocks, List<BasicBlock> handlerBlocks) {
        for (BasicBlock block : blocks) {
            if (block.isExceptionHandler()) {
                handlerBlocks.add(block);
            } else {
                dispatchBlocks.add(block);
            }
        }
    }

    private List<BasicBlock> splitLinearDispatchBlocks(L1Method method, List<BasicBlock> dispatchBlocks,
            List<BasicBlock> handlerBlocks, IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames,
            PipelineContext pctx) {
        if (!handlerBlocks.isEmpty() || !method.tryCatchBlocks().isEmpty() || dispatchBlocks.size() != 1) {
            return null;
        }
        if (!isEligibleEntryPoint(method)) {
            return null;
        }

        BasicBlock original = dispatchBlocks.get(0);
        int realInsns = 0;
        for (AbstractInsnNode insn : original.instructions()) {
            if (AsmUtil.isRealInstruction(insn)) realInsns++;
        }
        int chunkSize = Math.max(8, intOption(pctx.config().transforms().get("controlFlowFlattening"),
            LINEAR_CHUNK_SIZE_OPTION, 24));
        if (realInsns < chunkSize * 2) return null;

        List<BasicBlock> split = new ArrayList<>();
        BasicBlock current = new BasicBlock(0);
        int currentRealInsns = 0;
        int nextId = 1;

        for (AbstractInsnNode insn : original.instructions()) {
            if (!current.instructions().isEmpty()
                    && currentRealInsns >= chunkSize
                    && isLinearSplitPoint(insn, frames)) {
                split.add(current);
                current = new BasicBlock(nextId++);
                currentRealInsns = 0;
            }
            current.addInstruction(insn);
            if (AsmUtil.isRealInstruction(insn)) {
                currentRealInsns++;
            }
        }
        if (!current.instructions().isEmpty()) {
            split.add(current);
        }
        if (split.size() < 3) return null;

        for (int i = 0; i + 1 < split.size(); i++) {
            CFGEdge edge = new CFGEdge(split.get(i), split.get(i + 1), CFGEdge.Type.FALL_THROUGH);
            split.get(i).addOutEdge(edge);
            split.get(i + 1).addInEdge(edge);
        }
        return split;
    }

    private boolean isLinearSplitPoint(AbstractInsnNode insn,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames) {
        if (!AsmUtil.isRealInstruction(insn)) return false;
        Frame<BasicValue> frame = frames.get(insn);
        return frame != null && frame.getStackSize() == 0;
    }

    private IdentityHashMap<LabelNode, Integer> labelCodePositions(InsnList insns) {
        IdentityHashMap<LabelNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                positions.put(label, index);
            }
            if (isBytecodeInsn(insn)) {
                index++;
            }
        }
        return positions;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> codePositions(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index);
            if (isBytecodeInsn(insn)) {
                index++;
            }
        }
        return positions;
    }

    private boolean isBytecodeInsn(AbstractInsnNode insn) {
        return !(insn instanceof LabelNode)
            && !(insn instanceof FrameNode)
            && !(insn instanceof LineNumberNode);
    }

    private IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzeFrames(String ownerName, MethodNode mn,
            ClassHierarchy hierarchy) {
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn = new IdentityHashMap<>();
        if (analyzeWithInterpreter(ownerName, mn, framesByInsn, new RefTypedInterpreter(hierarchy))) {
            return framesByInsn;
        }
        framesByInsn.clear();
        analyzeWithInterpreter(ownerName, mn, framesByInsn, new BasicInterpreter());
        return framesByInsn;
    }

    private boolean analyzeWithInterpreter(String ownerName, MethodNode mn,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn,
            Interpreter<BasicValue> interpreter) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(interpreter);
            Frame<BasicValue>[] frames = analyzer.analyze(ownerName, mn);
            AbstractInsnNode[] instructions = mn.instructions.toArray();
            for (int i = 0; i < instructions.length; i++) {
                Frame<BasicValue> frame = frames[i];
                if (frame != null) {
                    framesByInsn.put(instructions[i], frame);
                }
            }
            return true;
        } catch (AnalyzerException | RuntimeException ignored) {
            return false;
        }
    }

    private IdentityHashMap<AbstractInsnNode, Integer> extractStackHeights(
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        IdentityHashMap<AbstractInsnNode, Integer> stackHeights = new IdentityHashMap<>();
        for (Map.Entry<AbstractInsnNode, Frame<BasicValue>> entry : framesByInsn.entrySet()) {
            stackHeights.put(entry.getKey(), entry.getValue().getStackSize());
        }
        return stackHeights;
    }

    private Map<BasicBlock, List<StackSlotKind>> analyzeBlockEntryStacks(List<BasicBlock> blocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn) {
        Map<BasicBlock, List<StackSlotKind>> blockEntryStacks = new HashMap<>();
        for (BasicBlock block : blocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                blockEntryStacks.put(block, List.of());
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getStackSize() == 0) {
                blockEntryStacks.put(block, List.of());
                continue;
            }
            List<StackSlotKind> stackKinds = new ArrayList<>(frame.getStackSize());
            for (int i = 0; i < frame.getStackSize(); i++) {
                stackKinds.add(stackSlotKind(frame.getStack(i)));
            }
            blockEntryStacks.put(block, List.copyOf(stackKinds));
        }
        return blockEntryStacks;
    }

    private Map<BasicBlock, List<LocalSlotState>> analyzeBlockEntryLocals(List<BasicBlock> allBlocks,
            List<BasicBlock> dispatchBlocks,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> framesByInsn, int originalMaxLocals,
            L1Method method) {
        Map<BasicBlock, List<LocalSlotState>> blockEntryLocals = new HashMap<>();
        if (originalMaxLocals <= 0 || allBlocks.isEmpty() || dispatchBlocks.isEmpty()) {
            return blockEntryLocals;
        }

        Map<BasicBlock, LocalFlowSummary> localFlowByBlock = new IdentityHashMap<>();
        Map<BasicBlock, Frame<BasicValue>> entryFrames = new IdentityHashMap<>();

        for (BasicBlock block : allBlocks) {
            AbstractInsnNode firstInsn = firstExecutableInsn(block);
            if (firstInsn == null) {
                localFlowByBlock.put(block, new LocalFlowSummary(new BitSet(), new BitSet()));
                continue;
            }
            Frame<BasicValue> frame = framesByInsn.get(firstInsn);
            if (frame == null || frame.getLocals() == 0 || originalMaxLocals <= 0) {
                localFlowByBlock.put(block, new LocalFlowSummary(new BitSet(), new BitSet()));
                continue;
            }
            entryFrames.put(block, frame);
            int upperBound = Math.min(originalMaxLocals, frame.getLocals());
            localFlowByBlock.put(block, summarizeLocalFlow(block, upperBound));
        }

        BitSet everWritten = new BitSet();
        for (LocalFlowSummary flow : localFlowByBlock.values()) {
            if (flow != null) {
                everWritten.or(flow.defs());
            }
        }

        Map<BasicBlock, BitSet> liveIn = new IdentityHashMap<>();
        Map<BasicBlock, BitSet> liveOut = new IdentityHashMap<>();
        for (BasicBlock block : allBlocks) {
            LocalFlowSummary flow = localFlowByBlock.get(block);
            liveIn.put(block, copyBitSet(flow == null ? null : flow.uses()));
            liveOut.put(block, new BitSet());
        }

        boolean changed;
        do {
            changed = false;
            for (int i = allBlocks.size() - 1; i >= 0; i--) {
                BasicBlock block = allBlocks.get(i);
                LocalFlowSummary flow = localFlowByBlock.get(block);
                if (flow == null) {
                    continue;
                }

                BitSet newOut = new BitSet();
                for (CFGEdge edge : block.outEdges()) {
                    BitSet successorLiveIn = liveIn.get(edge.target());
                    if (successorLiveIn != null) {
                        newOut.or(successorLiveIn);
                    }
                }

                BitSet newIn = copyBitSet(newOut);
                newIn.andNot(flow.defs());
                newIn.or(flow.uses());

                if (!newOut.equals(liveOut.get(block))) {
                    liveOut.put(block, newOut);
                    changed = true;
                }
                if (!newIn.equals(liveIn.get(block))) {
                    liveIn.put(block, newIn);
                    changed = true;
                }
            }
        } while (changed);

        IdentityHashMap<AbstractInsnNode, Integer> originalPositions = computeInsnPositions(method);
        for (BasicBlock block : dispatchBlocks) {
            Frame<BasicValue> frame = entryFrames.get(block);
            if (frame == null || frame.getLocals() == 0) {
                blockEntryLocals.put(block, List.of());
                continue;
            }
            int upperBound = Math.min(originalMaxLocals, frame.getLocals());
            BitSet entryLive = copyBitSet(liveIn.get(block));
            entryLive.and(everWritten);
            // Always include `this` so the dispatcher restore can CHECKCAST
            // back to the declared owner type even though the bytecode never
            // explicitly writes slot 0.
            if (!method.isStatic() && upperBound > 0) {
                BasicValue slot0 = frame.getLocal(0);
                if (slot0 != null && slot0 != BasicValue.UNINITIALIZED_VALUE) {
                    entryLive.set(0);
                }
            }
            AbstractInsnNode entryPoint = firstExecutableInsn(block);
            blockEntryLocals.put(block,
                materializeLiveInLocals(entryLive, frame, upperBound, method, entryPoint, originalPositions));
        }
        return blockEntryLocals;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> computeInsnPositions(L1Method method) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        InsnList insns = method.instructions();
        if (insns == null) {
            return positions;
        }
        int idx = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, idx++);
        }
        return positions;
    }

    private String inferReferenceType(L1Method method, int slot, AbstractInsnNode position,
            IdentityHashMap<AbstractInsnNode, Integer> positions) {
        if (slot == 0 && !method.isStatic()) {
            return method.owner().name();
        }

        int currentSlot = method.isStatic() ? 0 : 1;
        for (Type argType : method.argumentTypes()) {
            if (currentSlot == slot) {
                if (argType.getSort() == Type.OBJECT) {
                    return argType.getInternalName();
                }
                if (argType.getSort() == Type.ARRAY) {
                    return argType.getDescriptor();
                }
                return null;
            }
            currentSlot += argType.getSize();
            if (currentSlot > slot) {
                return null;
            }
        }

        if (position == null) {
            return null;
        }
        Integer positionIdx = positions.get(position);
        if (positionIdx == null) {
            return null;
        }

        LocalVariableNode best = null;
        int bestScopeSize = Integer.MAX_VALUE;
        for (LocalVariableNode lv : method.localVariables()) {
            if (lv.index != slot) continue;
            Integer startIdx = positions.get(lv.start);
            Integer endIdx = positions.get(lv.end);
            if (startIdx == null || endIdx == null) continue;
            if (positionIdx < startIdx || positionIdx > endIdx) continue;
            Type t = Type.getType(lv.desc);
            if (t.getSort() != Type.OBJECT && t.getSort() != Type.ARRAY) continue;
            int scopeSize = endIdx - startIdx;
            if (scopeSize < bestScopeSize) {
                bestScopeSize = scopeSize;
                best = lv;
            }
        }
        if (best == null) {
            return null;
        }
        Type t = Type.getType(best.desc);
        if (t.getSort() == Type.OBJECT) {
            return t.getInternalName();
        }
        if (t.getSort() == Type.ARRAY) {
            return t.getDescriptor();
        }
        return null;
    }

    private LocalFlowSummary summarizeLocalFlow(BasicBlock block, int upperBound) {
        if (upperBound <= 0) {
            return new LocalFlowSummary(new BitSet(), new BitSet());
        }

        boolean[] written = new boolean[upperBound];
        BitSet uses = new BitSet(upperBound);
        BitSet defs = new BitSet(upperBound);

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }

            if (insn instanceof IincInsnNode iinc) {
                if (iinc.var < upperBound && !written[iinc.var]) {
                    uses.set(iinc.var);
                }
                markWritten(written, iinc.var, 1);
                defs.set(iinc.var);
                continue;
            }

            if (!(insn instanceof VarInsnNode varInsn)) {
                continue;
            }

            int slot = varInsn.var;
            if (slot >= upperBound) {
                continue;
            }

            int opcode = varInsn.getOpcode();
            if (isLocalLoadOpcode(opcode)) {
                if (!written[slot]) {
                    uses.set(slot);
                }
                continue;
            }

            if (isLocalStoreOpcode(opcode)) {
                int size = localSlotSize(opcode);
                markWritten(written, slot, size);
                defs.set(slot);
                if (size == 2 && slot + 1 < upperBound) {
                    defs.set(slot + 1);
                }
            }
        }

        return new LocalFlowSummary(uses, defs);
    }

    private List<LocalSlotState> materializeLiveInLocals(BitSet liveIn,
            Frame<BasicValue> entryFrame, int upperBound, L1Method method,
            AbstractInsnNode entryPoint, IdentityHashMap<AbstractInsnNode, Integer> positions) {
        if (liveIn == null || liveIn.isEmpty() || upperBound <= 0) {
            return List.of();
        }

        List<LocalSlotState> locals = new ArrayList<>();
        for (int slot = liveIn.nextSetBit(0); slot >= 0 && slot < upperBound; slot = liveIn.nextSetBit(slot + 1)) {
            BasicValue value = entryFrame.getLocal(slot);
            if (!isInitializedLocalValue(value)) {
                continue;
            }
            StackSlotKind kind = stackSlotKind(value);
            String referenceType = null;
            if (kind == StackSlotKind.REFERENCE) {
                if (value instanceof RefTypedValue typed) {
                    referenceType = typed.referenceType;
                }
                if (referenceType == null) {
                    referenceType = inferReferenceType(method, slot, entryPoint, positions);
                }
            }
            locals.add(new LocalSlotState(slot, kind, referenceType));
        }
        return locals.isEmpty() ? List.of() : List.copyOf(locals);
    }

    private BitSet copyBitSet(BitSet source) {
        return source == null ? new BitSet() : (BitSet) source.clone();
    }

    private void markWritten(boolean[] written, int slot, int size) {
        if (slot < 0 || slot >= written.length) {
            return;
        }
        written[slot] = true;
        if (size == 2 && slot + 1 < written.length) {
            written[slot + 1] = true;
        }
    }

    private boolean isLocalLoadOpcode(int opcode) {
        return opcode == Opcodes.ILOAD
            || opcode == Opcodes.LLOAD
            || opcode == Opcodes.FLOAD
            || opcode == Opcodes.DLOAD
            || opcode == Opcodes.ALOAD;
    }

    private boolean isLocalStoreOpcode(int opcode) {
        return opcode == Opcodes.ISTORE
            || opcode == Opcodes.LSTORE
            || opcode == Opcodes.FSTORE
            || opcode == Opcodes.DSTORE
            || opcode == Opcodes.ASTORE;
    }

    private int localSlotSize(int opcode) {
        return opcode == Opcodes.LLOAD || opcode == Opcodes.DLOAD
            || opcode == Opcodes.LSTORE || opcode == Opcodes.DSTORE ? 2 : 1;
    }

    private AbstractInsnNode firstExecutableInsn(BasicBlock block) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            return insn;
        }
        return null;
    }

    private int allocateSpillLocals(List<BasicBlock> dispatchBlocks,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases, int nextLocal) {
        for (BasicBlock block : dispatchBlocks) {
            List<StackSlotKind> stackKinds = blockEntryStacks.get(block);
            if (stackKinds == null || stackKinds.isEmpty()) {
                continue;
            }
            blockSpillBases.put(block, nextLocal);
            for (StackSlotKind kind : stackKinds) {
                nextLocal += kind.slotSize();
            }
        }
        return nextLocal;
    }

    private int allocateLocalSpillLocals(List<BasicBlock> dispatchBlocks,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases, int nextLocal) {
        for (BasicBlock block : dispatchBlocks) {
            List<LocalSlotState> locals = blockEntryLocals.get(block);
            if (locals == null || locals.isEmpty()) {
                continue;
            }
            blockLocalSpillBases.put(block, nextLocal);
            for (LocalSlotState local : locals) {
                nextLocal += local.kind().slotSize();
            }
        }
        return nextLocal;
    }

    private void initializeSyntheticSpillLocals(InsnList insns,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        for (Map.Entry<BasicBlock, Integer> entry : blockSpillBases.entrySet()) {
            initializeSpillRange(insns, blockEntryStacks.get(entry.getKey()), entry.getValue());
        }
        for (Map.Entry<BasicBlock, Integer> entry : blockLocalSpillBases.entrySet()) {
            initializeLocalSpillRange(insns, blockEntryLocals.get(entry.getKey()), entry.getValue());
        }
    }

    private void initializeSpillRange(InsnList insns, List<StackSlotKind> stackKinds, int spillBase) {
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        int offset = 0;
        for (StackSlotKind kind : stackKinds) {
            emitDefaultStore(insns, kind, spillBase + offset);
            offset += kind.slotSize();
        }
    }

    private void initializeLocalSpillRange(InsnList insns, List<LocalSlotState> locals, int spillBase) {
        if (locals == null || locals.isEmpty()) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            emitDefaultStore(insns, local.kind(), spillBase + offset);
            offset += local.kind().slotSize();
        }
    }

    private void emitDefaultStore(InsnList insns, StackSlotKind kind, int slot) {
        switch (kind) {
            case REFERENCE -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new VarInsnNode(Opcodes.ASTORE, slot));
            }
            case INT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new VarInsnNode(Opcodes.ISTORE, slot));
            }
            case FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new VarInsnNode(Opcodes.FSTORE, slot));
            }
            case LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new VarInsnNode(Opcodes.LSTORE, slot));
            }
            case DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new VarInsnNode(Opcodes.DSTORE, slot));
            }
        }
    }

    private boolean isInitializedLocalValue(BasicValue value) {
        return value != null && value != BasicValue.UNINITIALIZED_VALUE;
    }

    private int originalMaxLocals(L1Method method) {
        return method.asmNode().maxLocals;
    }

    private void restoreBlockEntryStack(InsnList insns, BasicBlock block,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases) {
        List<StackSlotKind> stackKinds = blockEntryStacks.get(block);
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        Integer spillBase = blockSpillBases.get(block);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (StackSlotKind kind : stackKinds) {
            insns.add(new VarInsnNode(kind.loadOpcode(), spillBase + offset));
            offset += kind.slotSize();
        }
    }

    private void restoreBlockEntryLocals(InsnList insns, BasicBlock block,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        List<LocalSlotState> locals = blockEntryLocals.get(block);
        if (locals == null || locals.isEmpty()) {
            return;
        }
        Integer spillBase = blockLocalSpillBases.get(block);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            insns.add(new VarInsnNode(local.kind().loadOpcode(), spillBase + offset));
            if (local.kind() == StackSlotKind.REFERENCE
                    && local.referenceType() != null
                    && !"java/lang/Object".equals(local.referenceType())) {
                insns.add(new TypeInsnNode(Opcodes.CHECKCAST, local.referenceType()));
            }
            insns.add(new VarInsnNode(local.kind().storeOpcode(), local.slot()));
            offset += local.kind().slotSize();
        }
    }

    private void spillStackForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases) {
        List<StackSlotKind> stackKinds = blockEntryStacks.get(target);
        if (stackKinds == null || stackKinds.isEmpty()) {
            return;
        }
        Integer spillBase = blockSpillBases.get(target);
        if (spillBase == null) {
            return;
        }
        int[] offsets = new int[stackKinds.size()];
        int offset = 0;
        for (int i = 0; i < stackKinds.size(); i++) {
            offsets[i] = offset;
            offset += stackKinds.get(i).slotSize();
        }
        for (int i = stackKinds.size() - 1; i >= 0; i--) {
            StackSlotKind kind = stackKinds.get(i);
            insns.add(new VarInsnNode(kind.storeOpcode(), spillBase + offsets[i]));
        }
    }

    private void spillLocalsForTarget(InsnList insns, BasicBlock target,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        List<LocalSlotState> locals = blockEntryLocals.get(target);
        if (locals == null || locals.isEmpty()) {
            return;
        }
        Integer spillBase = blockLocalSpillBases.get(target);
        if (spillBase == null) {
            return;
        }
        int offset = 0;
        for (LocalSlotState local : locals) {
            insns.add(new VarInsnNode(local.kind().loadOpcode(), local.slot()));
            insns.add(new VarInsnNode(local.kind().storeOpcode(), spillBase + offset));
            offset += local.kind().slotSize();
        }
    }

    private StackSlotKind stackSlotKind(BasicValue value) {
        if (value == BasicValue.LONG_VALUE) {
            return StackSlotKind.LONG;
        }
        if (value == BasicValue.DOUBLE_VALUE) {
            return StackSlotKind.DOUBLE;
        }
        if (value == BasicValue.FLOAT_VALUE) {
            return StackSlotKind.FLOAT;
        }
        if (value != null && value.isReference()) {
            return StackSlotKind.REFERENCE;
        }
        return StackSlotKind.INT;
    }

    private List<RemappedTryCatchRange> remapTryCatchRanges(TryCatchBlockNode tcb,
            Map<LabelNode, LabelNode> labelRemap,
            IdentityHashMap<AbstractInsnNode, Integer> originalInstructionPositions,
            IdentityHashMap<LabelNode, Integer> emittedLabelPositions,
            InsnList emittedInsns) {
        LabelNode newHandler = labelRemap.get(tcb.handler);
        if (newHandler == null || !emittedLabelPositions.containsKey(newHandler)) {
            return List.of();
        }

        Integer originalStartPos = originalInstructionPositions.get(tcb.start);
        Integer originalEndPos = originalInstructionPositions.get(tcb.end);
        if (originalStartPos == null || originalEndPos == null || originalStartPos >= originalEndPos) {
            return List.of();
        }

        Set<LabelNode> protectedLabels = Collections.newSetFromMap(new IdentityHashMap<>());

        for (AbstractInsnNode insn = tcb.start; insn != null; insn = insn.getNext()) {
            Integer pos = originalInstructionPositions.get(insn);
            if (pos == null || pos >= originalEndPos) {
                break;
            }
            if (insn instanceof LabelNode originalLabel) {
                LabelNode emittedLabel = labelRemap.get(originalLabel);
                Integer emittedPos = emittedLabel == null ? null : emittedLabelPositions.get(emittedLabel);
                if (emittedPos == null) {
                    continue;
                }
                if (emittedLabel == newHandler) {
                    continue;
                }
                protectedLabels.add(emittedLabel);
            }
        }

        if (protectedLabels.isEmpty()) {
            return List.of();
        }

        List<RemappedTryCatchRange> remappedRanges = new ArrayList<>();
        LabelNode segmentStart = null;
        LabelNode segmentLastProtected = null;
        int segmentStartPos = Integer.MIN_VALUE;
        int segmentLastProtectedPos = Integer.MIN_VALUE;

        for (AbstractInsnNode insn = emittedInsns.getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof LabelNode label)) {
                continue;
            }
            Integer pos = emittedLabelPositions.get(label);
            if (pos == null) {
                continue;
            }
            boolean isProtected = protectedLabels.contains(label);

            if (isProtected) {
                if (segmentStart == null) {
                    segmentStart = label;
                    segmentStartPos = pos;
                }
                segmentLastProtected = label;
                segmentLastProtectedPos = pos;
                continue;
            }

            if (segmentStart != null && pos > segmentLastProtectedPos) {
                appendRemappedTryCatchRange(remappedRanges, segmentStart, segmentStartPos, label, pos, newHandler);
                segmentStart = null;
                segmentLastProtected = null;
                segmentStartPos = Integer.MIN_VALUE;
                segmentLastProtectedPos = Integer.MIN_VALUE;
            }
        }

        if (segmentStart != null && segmentLastProtected != null) {
            LabelNode segmentEnd = nextEmittedLabelSkippingHandler(emittedInsns, segmentLastProtected, emittedLabelPositions, newHandler);
            Integer segmentEndPos = segmentEnd == null ? null : emittedLabelPositions.get(segmentEnd);
            if (segmentEndPos != null) {
                appendRemappedTryCatchRange(remappedRanges, segmentStart, segmentStartPos, segmentEnd, segmentEndPos, newHandler);
            }
        }

        return remappedRanges.isEmpty() ? List.of() : List.copyOf(remappedRanges);
    }

    private void appendRemappedTryCatchRange(List<RemappedTryCatchRange> remappedRanges,
            LabelNode start, int startPos, LabelNode end, Integer endPos, LabelNode handler) {
        if (start == null || end == null || endPos == null) {
            return;
        }
        if (start == handler || end == handler || start == end || startPos >= endPos) {
            return;
        }
        remappedRanges.add(new RemappedTryCatchRange(start, end, handler));
    }

    private LabelNode nextEmittedLabelSkippingHandler(InsnList insns, LabelNode from,
            IdentityHashMap<LabelNode, Integer> positions, LabelNode handler) {
        Integer fromPos = positions.get(from);
        if (fromPos == null) return null;
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                Integer pos = positions.get(label);
                if (pos != null && pos > fromPos && label != handler) {
                    return label;
                }
            }
        }
        return null;
    }

    private LabelNode nextEmittedLabel(InsnList insns, LabelNode from,
            IdentityHashMap<LabelNode, Integer> positions) {
        Integer fromPos = positions.get(from);
        if (fromPos == null) return null;
        for (AbstractInsnNode insn = from.getNext(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label) {
                Integer pos = positions.get(label);
                if (pos != null && pos > fromPos) {
                    return label;
                }
            }
        }
        return null;
    }

    private List<CFGEdge> normalOutEdges(BasicBlock block) {
        List<CFGEdge> normalEdges = new ArrayList<>();
        for (CFGEdge edge : block.outEdges()) {
            if (edge.type() != CFGEdge.Type.EXCEPTION) {
                normalEdges.add(edge);
            }
        }
        return normalEdges;
    }

    private void emitHandlerBlock(InsnList insns, BasicBlock handlerBlock,
            Map<LabelNode, LabelNode> labelRemap, PipelineContext pctx,
            Map<BasicBlock, Long> flowKeyMap, long methodKey,
            int flowKeyVar, int flowMixVar,
            Map<BasicBlock, Integer> stateMap, int encodedStateVar, int stateMaskVar,
            int stateDeltaVar, int stateRotate, int tailSeedVar, int tailFlagVar,
            boolean zkmStyle, double tailChainIntensity, List<TailChain> tailChains,
            LabelNode loopStart, LabelNode loopEnd,
            IdentityHashMap<AbstractInsnNode, Integer> stackHeights,
            Map<BasicBlock, List<StackSlotKind>> blockEntryStacks,
            Map<BasicBlock, Integer> blockSpillBases,
            Map<BasicBlock, List<LocalSlotState>> blockEntryLocals,
            Map<BasicBlock, Integer> blockLocalSpillBases) {
        boolean requiresStateTransition = !normalOutEdges(handlerBlock).isEmpty();
        AbstractInsnNode syncAnchor = requiresStateTransition
            ? findHandlerSyncAnchor(handlerBlock, stackHeights)
            : null;
        boolean syncedFlowKey = false;
        boolean emittedRealInsn = false;
        boolean waitingForExceptionConsumption = requiresStateTransition
            && handlerBlock.isExceptionHandler()
            && syncAnchor == null;
        long handlerFlowKey = flowKeyMap.getOrDefault(handlerBlock, 0L);
        int handlerSplitHint = handlerBlock.id();
        for (AbstractInsnNode insn : handlerBlock.instructions()) {
            if (insn instanceof FrameNode) continue;
            if (insn instanceof LineNumberNode) continue;

            if (insn instanceof LabelNode origLabel) {
                LabelNode remapped = labelRemap.get(origLabel);
                if (remapped != null) insns.add(remapped);
                continue;
            }

            if (isTerminator(insn, handlerBlock)) continue;

            if (insn == syncAnchor && !syncedFlowKey) {
                emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
                emitRuntimeFlowContextSync(insns, flowKeyVar);
                syncedFlowKey = true;
            }

            AbstractInsnNode clone = insn.clone(labelRemap);
            insns.add(clone);
            recordInstructionFlowKey(pctx, clone, handlerFlowKey);
            emittedRealInsn = true;

            if (!syncedFlowKey && !waitingForExceptionConsumption) {
                emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
                emitRuntimeFlowContextSync(insns, flowKeyVar);
                syncedFlowKey = true;
            }
            // No mid-block flowKey resync after MethodInsn / InvokeDynamicInsn — flowKey is
            // updated only at block-exit transitions.
        }

        if (syncAnchor == AFTER_HANDLER_SYNC_ANCHOR && !syncedFlowKey) {
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
            syncedFlowKey = true;
            waitingForExceptionConsumption = false;
        }

        if (!syncedFlowKey && !emittedRealInsn) {
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
        } else if (!syncedFlowKey && !waitingForExceptionConsumption) {
            emitFlowKeyAbsolute(insns, methodKey, handlerFlowKey, flowKeyVar, flowMixVar, handlerSplitHint);
            emitRuntimeFlowContextSync(insns, flowKeyVar);
        }

        emitStateTransition(insns, handlerBlock, stateMap, flowKeyMap, flowKeyVar, flowMixVar,
            encodedStateVar, stateMaskVar, stateDeltaVar, stateRotate, tailSeedVar, tailFlagVar,
            zkmStyle, tailChainIntensity, tailChains, loopStart, loopEnd,
            blockEntryStacks, blockSpillBases, blockEntryLocals, blockLocalSpillBases);
    }

    private AbstractInsnNode findHandlerSyncAnchor(BasicBlock handlerBlock,
            IdentityHashMap<AbstractInsnNode, Integer> stackHeights) {
        if (!handlerBlock.isExceptionHandler()) {
            return null;
        }

        for (AbstractInsnNode insn : handlerBlock.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            Integer stackSize = stackHeights.get(insn);
            if (stackSize == null) {
                continue;
            }
            if (isTerminator(insn, handlerBlock)) {
                if (stackSize == 0) {
                    return AFTER_HANDLER_SYNC_ANCHOR;
                }
                continue;
            }
            if (stackSize == 0) {
                return insn;
            }
        }
        return null;
    }

    private boolean requiresFlowKeyResync(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode
            || insn instanceof InvokeDynamicInsnNode;
    }

    private String methodKey(L1Method method) {
        return method.owner().name() + '.' + method.name() + method.descriptor();
    }

    private boolean isZkmStyleEnabled(PipelineContext pctx) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        if (config == null) return true;
        Object option = config.options().get(ZKM_STYLE_OPTION);
        return !(option instanceof Boolean enabled) || enabled;
    }

    private double tailChainIntensity(PipelineContext pctx, L1Method method) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        double intensity = 0.7;
        if (config != null) {
            Object option = config.options().get(TAIL_CHAIN_INTENSITY_OPTION);
            if (option instanceof Number number) {
                intensity = Math.max(0.0, Math.min(1.0, number.doubleValue()));
            }
        }
        if (!method.tryCatchBlocks().isEmpty()) {
            double multiplier = doubleOption(config, TRY_CATCH_TAIL_CHAIN_MULTIPLIER_OPTION, 0.35);
            intensity *= multiplier;
            if (isEligibleEntryPoint(method)) {
                intensity *= doubleOption(config, ENTRYPOINT_TAIL_CHAIN_MULTIPLIER_OPTION, 0.08);
            }
        }
        return Math.max(0.0, Math.min(1.0, intensity));
    }

    private boolean shouldUseTailChain(boolean zkmStyle, double tailChainIntensity, int variantSeed) {
        if (!zkmStyle || tailChainIntensity <= 0.0) return false;
        int bucket = Math.floorMod(variantSeed * 1103515245 + 12345, 1000);
        return bucket < (int) Math.round(tailChainIntensity * 1000.0);
    }

    private int dispatcherDepth(PipelineContext pctx, int stateCount) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        int requested = intOption(config, DISPATCHER_DEPTH_OPTION, 1);
        if (requested <= 1 || stateCount < 4) return 1;

        int depth = 1;
        int cap = Math.min(8, Math.min(requested, stateCount));
        while ((depth << 1) <= cap) {
            depth <<= 1;
        }
        return depth;
    }

    private int dispatcherFragments(PipelineContext pctx, int stateCount) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        int requested = intOption(config, DISPATCHER_FRAGMENTS_OPTION, 1);
        if (requested <= 1 || stateCount < 6) return 1;
        return Math.max(1, Math.min(Math.min(8, stateCount), requested));
    }

    private DispatcherShape dispatcherShape(PipelineContext pctx, long methodKey) {
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        if (!booleanOption(config, DISPATCHER_SHAPE_VARIATION_OPTION, true)) {
            return new DispatcherShape(0, 0, 0);
        }
        int mode = Math.floorMod((int) (methodKey ^ (methodKey >>> 32)), 3);
        int salt = foldMethodKey(Long.rotateLeft(methodKey, 13) ^ 0x4B44535053484150L);
        int rotate = 1 + Math.floorMod((int) (methodKey >>> 23), 15);
        return new DispatcherShape(mode, salt, rotate);
    }

    private int dispatchBucket(int state, int bucketMask, DispatcherShape shape) {
        int value = switch (shape.mode()) {
            case 1 -> state ^ shape.salt();
            case 2 -> Integer.rotateRight(state ^ shape.salt(), shape.rotate());
            default -> state;
        };
        return value & bucketMask;
    }

    private void emitDispatchBucketValue(InsnList insns, int dispatchStateVar, int bucketMask,
            DispatcherShape shape) {
        if (shape.mode() == 1) {
            insns.add(AsmUtil.pushIntAny(shape.salt()));
            insns.add(new InsnNode(Opcodes.IXOR));
        } else if (shape.mode() == 2) {
            insns.add(AsmUtil.pushIntAny(shape.salt()));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(AsmUtil.pushIntAny(shape.rotate()));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, INTEGER_OWNER,
                "rotateRight", "(II)I", false));
        }
        insns.add(AsmUtil.pushIntAny(bucketMask));
        insns.add(new InsnNode(Opcodes.IAND));
    }

    private boolean isStructureSafe(L1Method method, PipelineContext pctx) {
        MethodSafetyStats stats = analyzeMethodStructure(method.instructions());
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        boolean hasTryCatch = !method.tryCatchBlocks().isEmpty();
        boolean isEntryPoint = isEligibleEntryPoint(method);

        if (hasTryCatch && !booleanOption(config, ALLOW_TRY_CATCH_METHODS_OPTION, true)) return false;
        if (hasTryCatch && callsGeneratedHelper(method)) return false;
        if (hasTryCatch && booleanOption(config, TRY_CATCH_MAIN_ONLY_OPTION, false) && !isEntryPoint) {
            return false;
        }

        int maxTryCatchBlocks = intOption(config, MAX_TRY_CATCH_BLOCKS_OPTION, 18);
        if (isEntryPoint) {
            maxTryCatchBlocks = Math.max(maxTryCatchBlocks,
                intOption(config, ENTRYPOINT_MAX_TRY_CATCH_BLOCKS_OPTION, 64));
        }
        if (method.tryCatchBlocks().size() > maxTryCatchBlocks) return false;
        if (hasReferenceLocalFrameConflicts(method)) return false;

        if (!isEntryPoint && stats.hasSwitch() && !booleanOption(config, ALLOW_SWITCH_METHODS_OPTION, false)) return false;
        if (!isEntryPoint && stats.hasMonitor() && !booleanOption(config, ALLOW_MONITOR_METHODS_OPTION, false)) return false;

        int maxInstructionCount = intOption(config, MAX_INSTRUCTION_COUNT_OPTION, 180);
        if (hasTryCatch) {
            maxInstructionCount += intOption(config, TRY_CATCH_INSTRUCTION_BONUS_OPTION, 160);
        }
        if (isEntryPoint) {
            maxInstructionCount += intOption(config, ENTRYPOINT_INSTRUCTION_BONUS_OPTION, 0);
        }
        if (method.instructionCount() > maxInstructionCount) return false;

        int maxBackward = intOption(config, MAX_BACKWARD_BRANCHES_OPTION, 2);
        if (isEntryPoint) {
            maxBackward = Math.max(maxBackward, 8);
        }
        if (stats.backwardBranches() > maxBackward) return false;

        int maxBranchCount = intOption(config, MAX_BRANCHES_OPTION, 16);
        if (hasTryCatch) {
            int bonusPerTryCatch = intOption(config, TRY_CATCH_BRANCH_BONUS_OPTION, 2);
            maxBranchCount += method.tryCatchBlocks().size() * bonusPerTryCatch;
        }
        if (isEntryPoint) {
            maxBranchCount += intOption(config, ENTRYPOINT_BRANCH_BONUS_OPTION, 0);
        }
        return stats.branchCount() <= maxBranchCount;
    }

    private boolean callsGeneratedHelper(L1Method method) {
        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call && call.name.startsWith("__neko_")) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldUseLoopFastPath(PipelineContext pctx, L1Method method, MethodSafetyStats stats) {
        if (isEligibleEntryPoint(method)) return false;
        if (stats.backwardBranches() <= 0) return false;
        TransformConfig config = pctx.config().transforms().get("controlFlowFlattening");
        int insnThreshold = intOption(config, LOOP_FAST_PATH_INSTRUCTION_THRESHOLD_OPTION, 220);
        int backwardThreshold = intOption(config, LOOP_FAST_PATH_BACKWARD_BRANCH_THRESHOLD_OPTION, 2);
        return method.instructionCount() >= insnThreshold || stats.backwardBranches() >= backwardThreshold;
    }

    private void insertLoopFastPathGate(PipelineContext pctx, L1Method method) {
        LabelNode body = new LabelNode();
        int state = pctx.random().nextInt();
        InsnList gate = new InsnList();
        gate.add(AsmUtil.pushIntAny(state));
        gate.add(new LookupSwitchInsnNode(body, new int[] { state }, new LabelNode[] { body }));
        gate.add(body);
        method.instructions().insert(gate);
    }

    private boolean insertConstructorFlowGate(PipelineContext pctx, L1Method method) {
        AbstractInsnNode anchor = firstConstructorInitCall(method);
        if (anchor == null || anchor.getNext() == null) return false;

        MethodNode mn = method.asmNode();
        int keyLocal = mn.maxLocals;
        int stateLocal = keyLocal + 2;
        mn.maxLocals = stateLocal + 1;

        long seedKey = pctx.random().nextLong();
        long caseKey = seedKey ^ pctx.random().nextLong();
        int decodedState = pctx.random().nextInt();
        int encodedState = decodedState ^ foldFlowKey(seedKey);
        int falseState = decodedState ^ foldMethodKey(caseKey);
        long caseSalt = pctx.random().nextLong();
        long defaultSalt = pctx.random().nextLong();

        LabelNode defaultLabel = new LabelNode();
        LabelNode caseLabel = new LabelNode();
        LabelNode body = new LabelNode();
        InsnList gate = new InsnList();
        gate.add(new LdcInsnNode(seedKey));
        gate.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        gate.add(AsmUtil.pushIntAny(encodedState));
        gate.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));

        gate.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        gate.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        gate.add(new InsnNode(Opcodes.L2I));
        gate.add(new InsnNode(Opcodes.IXOR));
        gate.add(new LookupSwitchInsnNode(defaultLabel, new int[] { decodedState }, new LabelNode[] { caseLabel }));

        gate.add(defaultLabel);
        gate.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        gate.add(new LdcInsnNode(defaultSalt));
        gate.add(new InsnNode(Opcodes.LXOR));
        gate.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        gate.add(AsmUtil.pushIntAny(falseState));
        gate.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
        gate.add(new JumpInsnNode(Opcodes.GOTO, body));

        gate.add(caseLabel);
        gate.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        gate.add(new LdcInsnNode(caseSalt));
        gate.add(new InsnNode(Opcodes.LXOR));
        gate.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        gate.add(body);

        method.instructions().insert(anchor, gate);
        mn.maxStack = Math.max(mn.maxStack, 6);
        return true;
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

    private boolean hasReferenceLocalFrameConflicts(L1Method method) {
        Map<Integer, String> seen = new HashMap<>();

        int slot = 0;
        if (!method.isStatic()) {
            seen.put(0, method.owner().name());
            slot = 1;
        }
        for (Type argumentType : method.argumentTypes()) {
            if (argumentType.getSort() == Type.OBJECT || argumentType.getSort() == Type.ARRAY) {
                seen.put(slot, localTypeName(argumentType));
            }
            slot += argumentType.getSize();
        }

        for (LocalVariableNode localVariable : method.localVariables()) {
            if (localVariable.index < 0) continue;
            Type type = Type.getType(localVariable.desc);
            if ((type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)
                    && recordReferenceLocalType(seen, localVariable.index, localTypeName(type))) {
                return true;
            }
        }

        for (AbstractInsnNode insn = method.instructions().getFirst(); insn != null; insn = insn.getNext()) {
            if (!(insn instanceof FrameNode frame) || frame.local == null) {
                continue;
            }
            int frameSlot = 0;
            for (Object local : frame.local) {
                if (local instanceof String typeName) {
                    if (recordReferenceLocalType(seen, frameSlot, typeName)) {
                        return true;
                    }
                    frameSlot++;
                    continue;
                }
                if (local instanceof LabelNode) {
                    return true;
                }
                if (local == Opcodes.LONG || local == Opcodes.DOUBLE) {
                    frameSlot += 2;
                } else {
                    frameSlot++;
                }
            }
        }
        return false;
    }

    private boolean recordReferenceLocalType(Map<Integer, String> seen, int slot, String typeName) {
        if (typeName == null || "null".equals(typeName)) {
            return false;
        }
        String previous = seen.putIfAbsent(slot, typeName);
        return previous != null && !previous.equals(typeName);
    }

    private String localTypeName(Type type) {
        return type.getSort() == Type.ARRAY ? type.getDescriptor() : type.getInternalName();
    }

    private MethodSafetyStats analyzeMethodStructure(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }

        int branchCount = 0;
        int backwardBranches = 0;
        boolean hasSwitch = false;
        boolean hasMonitor = false;

        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT) {
                hasMonitor = true;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) {
                hasSwitch = true;
                branchCount++;
                continue;
            }
            if (insn instanceof JumpInsnNode jump) {
                branchCount++;
                Integer from = positions.get(insn);
                Integer target = positions.get(jump.label);
                if (from != null && target != null && target <= from) {
                    backwardBranches++;
                }
            }
        }

        return new MethodSafetyStats(branchCount, backwardBranches, hasSwitch, hasMonitor);
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

    private double doubleOption(TransformConfig config, String key, double defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(0.0, number.doubleValue()) : defaultValue;
    }

    private boolean isEligibleEntryPoint(L1Method method) {
        return method.isStatic()
            && "main".equals(method.name())
            && "([Ljava/lang/String;)V".equals(method.descriptor());
    }

    private long deriveMethodFlowSeed(long methodKey) {
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodKey ^ 0x4E454B4F464C4F57L,
                0x13579BDF2468ACE0L));
    }

    private long deriveBlockFlowKey(long methodFlowSeed, int state) {
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(
            dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(methodFlowSeed, state));
    }

    private void applySinglePredecessorEdgeKeys(List<BasicBlock> blocks, BasicBlock entryBlock,
            Map<BasicBlock, Long> flowKeyMap, long methodFlowSeed) {
        for (BasicBlock block : blocks) {
            if (block == entryBlock) continue;
            List<CFGEdge> incoming = normalInEdges(block);
            if (incoming.size() != 1) continue;
            CFGEdge edge = incoming.get(0);
            Long sourceKey = flowKeyMap.get(edge.source());
            if (sourceKey == null) continue;
            flowKeyMap.put(block, deriveEdgeFlowKey(sourceKey, edge, methodFlowSeed));
        }
    }

    private List<CFGEdge> normalInEdges(BasicBlock block) {
        List<CFGEdge> normalEdges = new ArrayList<>();
        for (CFGEdge edge : block.inEdges()) {
            if (edge.type() != CFGEdge.Type.EXCEPTION) {
                normalEdges.add(edge);
            }
        }
        return normalEdges;
    }

    private long deriveEdgeFlowKey(long sourceKey, CFGEdge edge, long methodFlowSeed) {
        long key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(sourceKey, edge.source().id());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key, edge.target().id());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key, edge.type().ordinal());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key, edge.switchKey());
        key = dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.mix(key,
            methodFlowSeed ^ ((long) edge.source().id() << 32) ^ edge.target().id());
        return dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine.finalize_(key);
    }

    private int foldFlowKey(long flowKey) {
        return foldMethodKey(flowKey ^ Long.rotateLeft(flowKey, 17));
    }

    private int foldMethodKey(long value) {
        int mixed = (int) (value ^ (value >>> 32));
        mixed ^= Integer.rotateLeft(mixed, 13);
        mixed ^= Integer.rotateRight(mixed, 7);
        return mixed != 0 ? mixed : 0x13579BDF;
    }

    private enum LocalInitKind {
        UNKNOWN,
        INT,
        FLOAT,
        LONG,
        DOUBLE,
        REFERENCE,
        RESERVED,
        CONFLICT
    }

    private enum StackSlotKind {
        INT(Opcodes.ILOAD, Opcodes.ISTORE, 1),
        FLOAT(Opcodes.FLOAD, Opcodes.FSTORE, 1),
        LONG(Opcodes.LLOAD, Opcodes.LSTORE, 2),
        DOUBLE(Opcodes.DLOAD, Opcodes.DSTORE, 2),
        REFERENCE(Opcodes.ALOAD, Opcodes.ASTORE, 1);

        private final int loadOpcode;
        private final int storeOpcode;
        private final int slotSize;

        StackSlotKind(int loadOpcode, int storeOpcode, int slotSize) {
            this.loadOpcode = loadOpcode;
            this.storeOpcode = storeOpcode;
            this.slotSize = slotSize;
        }

        int loadOpcode() {
            return loadOpcode;
        }

        int storeOpcode() {
            return storeOpcode;
        }

        int slotSize() {
            return slotSize;
        }
    }

    private record LocalFlowSummary(BitSet uses, BitSet defs) {}

    private record LocalSlotState(int slot, StackSlotKind kind, String referenceType) {
        LocalSlotState(int slot, StackSlotKind kind) { this(slot, kind, null); }
    }

    private record MethodSafetyStats(int branchCount, int backwardBranches, boolean hasSwitch, boolean hasMonitor) {}

    private record TailChain(LabelNode entry, InsnList body) {}

    private record DispatcherShape(int mode, int salt, int rotate) {}

    private record RemappedTryCatchRange(LabelNode start, LabelNode end, LabelNode handler) {}

    /**
     * BasicValue that also tracks the reference type (internal name or array
     * descriptor) so we can emit CHECKCAST after CFF spill/restore. Falls back
     * to plain BasicValue.REFERENCE_VALUE behavior for non-reference values.
     */
    private static final class RefTypedValue extends BasicValue {
        final String referenceType;

        RefTypedValue(Type type, String referenceType) {
            super(type);
            this.referenceType = referenceType;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof RefTypedValue other)) return false;
            return java.util.Objects.equals(getType(), other.getType())
                && java.util.Objects.equals(referenceType, other.referenceType);
        }

        @Override
        public int hashCode() {
            return java.util.Objects.hash(getType(), referenceType);
        }
    }

    /**
     * Custom interpreter that tracks reference types beyond BasicInterpreter's
     * single-Object value. Uses the project's ClassHierarchy for LCM merges
     * when paths produce different reference types.
     */
    private static final class RefTypedInterpreter extends BasicInterpreter {
        private final ClassHierarchy hierarchy;

        RefTypedInterpreter(ClassHierarchy hierarchy) {
            super(Opcodes.ASM9);
            this.hierarchy = hierarchy;
        }

        private static BasicValue typedRef(Type type, String name) {
            return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), name);
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type == null) {
                return BasicValue.UNINITIALIZED_VALUE;
            }
            switch (type.getSort()) {
                case Type.VOID:
                    return null;
                case Type.OBJECT:
                    return typedRef(type, type.getInternalName());
                case Type.ARRAY:
                    return typedRef(type, type.getDescriptor());
                default:
                    return super.newValue(type);
            }
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.ACONST_NULL:
                    return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), null);
                case Opcodes.LDC:
                    Object cst = ((LdcInsnNode) insn).cst;
                    if (cst instanceof String) {
                        return typedRef(Type.getObjectType("java/lang/String"), "java/lang/String");
                    }
                    if (cst instanceof Type t) {
                        if (t.getSort() == Type.METHOD) {
                            return typedRef(Type.getObjectType("java/lang/invoke/MethodType"),
                                "java/lang/invoke/MethodType");
                        }
                        return typedRef(Type.getObjectType("java/lang/Class"), "java/lang/Class");
                    }
                    if (cst instanceof Handle) {
                        return typedRef(Type.getObjectType("java/lang/invoke/MethodHandle"),
                            "java/lang/invoke/MethodHandle");
                    }
                    return super.newOperation(insn);
                case Opcodes.NEW: {
                    String desc = ((TypeInsnNode) insn).desc;
                    return typedRef(Type.getObjectType(desc), desc);
                }
                default:
                    return super.newOperation(insn);
            }
        }

        @Override
        public BasicValue copyOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            return value;
        }

        @Override
        public BasicValue unaryOperation(AbstractInsnNode insn, BasicValue value) throws AnalyzerException {
            switch (insn.getOpcode()) {
                case Opcodes.CHECKCAST: {
                    String desc = ((TypeInsnNode) insn).desc;
                    if (desc.startsWith("[")) {
                        return typedRef(Type.getType(desc), desc);
                    }
                    return typedRef(Type.getObjectType(desc), desc);
                }
                case Opcodes.GETFIELD: {
                    Type ft = Type.getType(((FieldInsnNode) insn).desc);
                    if (ft.getSort() == Type.OBJECT) {
                        return typedRef(ft, ft.getInternalName());
                    }
                    if (ft.getSort() == Type.ARRAY) {
                        return typedRef(ft, ft.getDescriptor());
                    }
                    return super.unaryOperation(insn, value);
                }
                case Opcodes.ANEWARRAY: {
                    String elem = ((TypeInsnNode) insn).desc;
                    String arrayDesc = elem.startsWith("[")
                        ? "[" + elem
                        : "[L" + elem + ";";
                    return typedRef(Type.getType(arrayDesc), arrayDesc);
                }
                case Opcodes.NEWARRAY: {
                    int operand = ((IntInsnNode) insn).operand;
                    String desc = switch (operand) {
                        case Opcodes.T_BOOLEAN -> "[Z";
                        case Opcodes.T_CHAR -> "[C";
                        case Opcodes.T_FLOAT -> "[F";
                        case Opcodes.T_DOUBLE -> "[D";
                        case Opcodes.T_BYTE -> "[B";
                        case Opcodes.T_SHORT -> "[S";
                        case Opcodes.T_INT -> "[I";
                        case Opcodes.T_LONG -> "[J";
                        default -> null;
                    };
                    if (desc != null) {
                        return typedRef(Type.getType(desc), desc);
                    }
                    return super.unaryOperation(insn, value);
                }
                default:
                    return super.unaryOperation(insn, value);
            }
        }

        @Override
        public BasicValue binaryOperation(AbstractInsnNode insn, BasicValue value1, BasicValue value2)
                throws AnalyzerException {
            if (insn.getOpcode() == Opcodes.AALOAD) {
                if (value1 instanceof RefTypedValue arr && arr.referenceType != null
                        && arr.referenceType.startsWith("[")) {
                    String elem = arr.referenceType.substring(1);
                    if (elem.startsWith("L") && elem.endsWith(";")) {
                        String name = elem.substring(1, elem.length() - 1);
                        return typedRef(Type.getObjectType(name), name);
                    }
                    if (elem.startsWith("[")) {
                        return typedRef(Type.getType(elem), elem);
                    }
                }
                return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), "java/lang/Object");
            }
            return super.binaryOperation(insn, value1, value2);
        }

        @Override
        public BasicValue naryOperation(AbstractInsnNode insn, List<? extends BasicValue> values)
                throws AnalyzerException {
            if (insn instanceof MethodInsnNode mi) {
                Type rt = Type.getReturnType(mi.desc);
                if (rt.getSort() == Type.OBJECT) {
                    return typedRef(rt, rt.getInternalName());
                }
                if (rt.getSort() == Type.ARRAY) {
                    return typedRef(rt, rt.getDescriptor());
                }
            } else if (insn instanceof InvokeDynamicInsnNode id) {
                Type rt = Type.getReturnType(id.desc);
                if (rt.getSort() == Type.OBJECT) {
                    return typedRef(rt, rt.getInternalName());
                }
                if (rt.getSort() == Type.ARRAY) {
                    return typedRef(rt, rt.getDescriptor());
                }
            } else if (insn.getOpcode() == Opcodes.MULTIANEWARRAY) {
                String desc = ((MultiANewArrayInsnNode) insn).desc;
                return typedRef(Type.getType(desc), desc);
            }
            return super.naryOperation(insn, values);
        }

        @Override
        public BasicValue merge(BasicValue v1, BasicValue v2) {
            if (v1 == v2 || v1.equals(v2)) {
                return v1;
            }
            if (v1 instanceof RefTypedValue r1 && v2 instanceof RefTypedValue r2
                    && java.util.Objects.equals(r1.getType(), r2.getType())) {
                String t1 = r1.referenceType;
                String t2 = r2.referenceType;
                if (t1 == null) return r2;
                if (t2 == null) return r1;
                if (t1.equals(t2)) return r1;
                String lcm;
                if (t1.startsWith("[") || t2.startsWith("[")) {
                    lcm = t1.equals(t2) ? t1 : "java/lang/Object";
                } else {
                    lcm = hierarchy != null ? hierarchy.getCommonSuperClass(t1, t2) : "java/lang/Object";
                    if (lcm == null) {
                        lcm = "java/lang/Object";
                    }
                }
                return new RefTypedValue(BasicValue.REFERENCE_VALUE.getType(), lcm);
            }
            return super.merge(v1, v2);
        }
    }

    private AbstractInsnNode findLastRealInsn(BasicBlock block) {
        for (int i = block.instructions().size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private void emitSafetyReturn(InsnList insns, org.objectweb.asm.Type retType) {
        switch (retType.getSort()) {
            case org.objectweb.asm.Type.VOID -> insns.add(new InsnNode(Opcodes.RETURN));
            case org.objectweb.asm.Type.INT, org.objectweb.asm.Type.BOOLEAN,
                 org.objectweb.asm.Type.BYTE, org.objectweb.asm.Type.CHAR,
                 org.objectweb.asm.Type.SHORT -> {
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new InsnNode(Opcodes.IRETURN));
            }
            case org.objectweb.asm.Type.LONG -> {
                insns.add(new InsnNode(Opcodes.LCONST_0));
                insns.add(new InsnNode(Opcodes.LRETURN));
            }
            case org.objectweb.asm.Type.FLOAT -> {
                insns.add(new InsnNode(Opcodes.FCONST_0));
                insns.add(new InsnNode(Opcodes.FRETURN));
            }
            case org.objectweb.asm.Type.DOUBLE -> {
                insns.add(new InsnNode(Opcodes.DCONST_0));
                insns.add(new InsnNode(Opcodes.DRETURN));
            }
            default -> {
                insns.add(new InsnNode(Opcodes.ACONST_NULL));
                insns.add(new InsnNode(Opcodes.ARETURN));
            }
        }
    }
}
