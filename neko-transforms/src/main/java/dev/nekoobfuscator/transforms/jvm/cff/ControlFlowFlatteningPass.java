package dev.nekoobfuscator.transforms.jvm.cff;

import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.*;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningVerify.*;

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
import dev.nekoobfuscator.transforms.jvm.internal.JvmCodeSizeEstimator;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import dev.nekoobfuscator.transforms.jvm.strings.JvmStringObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.constants.JvmConstantObfuscationPass;
import dev.nekoobfuscator.transforms.jvm.parameters.JvmMethodParameterObfuscationPass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.objectweb.asm.Type;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * Direct keyed control-flow flattening over the original method body.
 *
 * <p>The pass keeps bytecode in the original method and rewrites basic-block
 * exits to store an encoded state and return to a target-local dispatcher.
 * Constructors keep the mandatory this/super initialization prefix untouched;
 * flattening starts immediately after that prefix.</p>
 */
public final class ControlFlowFlatteningPass extends CffTransitionOutliner implements TransformPass {

    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "Control Flow Flattening";
    }

    @Override
    public TransformPhase phase() {
        return TransformPhase.TRANSFORM;
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
        finalizeClassCodeIntegrity(pctx, classes, hierarchy);
    }

    @Override
    public void transformClass(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        lowerStringConstantValuesForStringPass(pctx);
        prepareClassKeyTables(pctx);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;
        if (!isApplicationMethod(pctx, clazz, method)) return;
        prepareClassKeyTables(pctx);
        activeKeyTable = ensureClassKeyTable(pctx, clazz);
        boolean externalEntrySeed = usesExternalEntrySeed(pctx, clazz, method);

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        MethodNode mn = method.asmNode();
        Integer recordedKeyLocal = JvmKeyDispatchPass.findMethodKeyLocal(
            pctx,
            methodKey
        );
        Long recordedMethodSeed = JvmKeyDispatchPass.findMethodSeed(pctx, methodKey);
        if (recordedKeyLocal == null || recordedMethodSeed == null) {
            int ensuredLocal = JvmKeyDispatchPass.ensureMethodKeyLocal(
                pctx,
                clazz,
                method
            );
            recordedKeyLocal = ensuredLocal >= 0 ? ensuredLocal : null;
            recordedMethodSeed = JvmKeyDispatchPass.findMethodSeed(pctx, methodKey);
        }
        if (recordedKeyLocal == null || recordedMethodSeed == null) {
            throw new IllegalStateException(
                "CFF cannot install a verified method key for " +
                    clazz.name() +
                    "." +
                    method.name() +
                    method.descriptor()
            );
        }
        int keyLocal = recordedKeyLocal;
        long methodSeed = recordedMethodSeed;
        LabelNode protectedStart = protectedStartLabel(
            clazz,
            method,
            mn,
            keyLocal
        );
        if (protectedStart == null) return;

        Set<LabelNode> injectedReflectionLeaders = rewriteInjectedMemberReflection(pctx, mn);
        List<ProtectedTryCatch> protectedTryCatches =
            captureProtectedTryCatches(mn);
        List<HandlerBridge> handlerBridges = splitExceptionHandlers(mn);
        Set<LabelNode> handlerBodies = handlerBodyLabels(handlerBridges);
        CffFrameAnalysis frames = CffFrameAnalysis.analyze(
            clazz.name(),
            mn
        );
        if (normalizeNonZeroStackControlTargets(pctx, mn, protectedStart, frames)) {
            frames = CffFrameAnalysis.analyze(clazz.name(), mn);
        }
        Set<LabelNode> zeroStackLabels = frames.zeroStackLabels();
        Set<LabelNode> linearLeaders = linearZeroStackLeaders(
            mn,
            protectedStart,
            frames
        );
        Set<LabelNode> extraLeaders = new HashSet<>(handlerBodies);
        extraLeaders.addAll(injectedReflectionLeaders);
        BlockPlan blockPlan = buildBlocks(
            mn,
            protectedStart,
            extraLeaders,
            zeroStackLabels,
            linearLeaders,
            frames
        );
        List<Block> blocks = blockPlan.blocks();
        if (blocks.isEmpty()) return;
        Map<LabelNode, LabelNode> blockAliases = new IdentityHashMap<>(
            blockPlan.aliases()
        );
        completeBlockLabelAliases(mn, protectedStart, blocks, blockAliases);

        int pcLocal = mn.maxLocals;
        int guardLocal = pcLocal + 1;
        int pathKeyLocal = pcLocal + 2;
        int blockKeyLocal = pcLocal + 3;
        int domainLocal = pcLocal + 4;
        int exceptionLocal = handlerBridges.isEmpty() ? -1 : pcLocal + 5;
        int keyTmpLocal = pcLocal + 5 + (handlerBridges.isEmpty() ? 0 : 1);
        int methodSeedLocal = handlerBridges.isEmpty() ? -1 : keyTmpLocal + 4;
        mn.maxLocals = keyTmpLocal + 4 + (handlerBridges.isEmpty() ? 0 : 2);
        int smallTokenDispatchCases = smallTokenDispatchCaseLimit(
            mn,
            blocks,
            handlerBridges
        );
        boolean outlineTransitions = useTransitionOutliner(
            mn,
            blocks,
            handlerBridges
        );
        boolean materializeDirectIslandTransitions =
            estimatedOutlinerCodePressure(mn, blocks, handlerBridges) >=
                TRANSITION_OUTLINER_ESTIMATED_CODE_PRESSURE;
        boolean outlineDispatchers = outlineTransitions;
        int transitionOutLocal = outlineDispatchers ? mn.maxLocals++ : -1;
        CffTransitionOutliner.TransitionOutliner dispatcherOutliner = outlineDispatchers
            ? new TransitionOutliner(
                pctx,
                clazz,
                transitionOutLocal,
                smallTokenDispatchCases,
                materializeDirectIslandTransitions
            )
            : null;
        CffTransitionOutliner.TransitionOutliner transitionOutliner = outlineTransitions
            ? dispatcherOutliner
            : null;

        long salt = JvmPassBytecode.mix(
            pctx.masterSeed(),
            methodKey.hashCode()
        );
        int[] states = uniqueStates((int) (salt >>> 32), blocks.size());
        Map<LabelNode, Integer> stateByLabel = new IdentityHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            stateByLabel.put(blocks.get(i).label(), states[i]);
        }
        // Dispatcher hubs are grouped by verifier frame shape. A transition may
        // jump to a hub only when every block behind that hub has compatible
        // locals and an empty stack.
        DispatchPlan dispatchPlan = buildDispatchPlan(
            blocks,
            frames,
            salt,
            stateByLabel,
            handlerReachableDomains(mn, blocks, blockAliases, handlerBodies)
        );
        for (Map.Entry<LabelNode, LabelNode> alias : blockAliases.entrySet()) {
            LabelNode canonical = canonicalLabel(alias.getValue(), blockAliases);
            Integer aliasState = stateByLabel.get(canonical);
            DispatchTarget aliasTarget = dispatchPlan.targets().get(canonical);
            if (aliasState == null || aliasTarget == null) continue;
            stateByLabel.put(alias.getKey(), aliasState);
            dispatchPlan.targets().put(alias.getKey(), aliasTarget);
        }
        Map<LabelNode, CffBlockKeyState> keyStateByLabel =
            buildBlockKeyStates(blocks, blockAliases, stateByLabel, dispatchPlan.targets(), salt);
        installEntryKeyState(blocks, dispatchPlan, keyStateByLabel, methodSeed, externalEntrySeed);
        Set<LabelNode> runtimeKeyLabels = runtimeKeyLabels(
            pctx,
            mn,
            blocks,
            blockAliases
        );
        rewriteKeyedCallTransfers(
            pctx,
            mn,
            blocks,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
        publishMethodMetadata(
            pctx,
            clazz,
            method,
            methodSeed,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            blocks,
            stateByLabel,
            dispatchPlan.targets(),
            keyStateByLabel,
            activeKeyTable
        );

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            LabelNode next =
                i + 1 < blocks.size() && !blocks.get(i + 1).handler()
                    ? blocks.get(i + 1).label()
                    : null;
            rewriteBlockExit(
                mn,
                block,
                next,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                keyLocal,
                stateByLabel,
                keyStateByLabel,
                dispatchPlan.targets(),
                runtimeKeyLabels,
                methodSeed,
                salt,
                transitionOutliner
            );
        }
        insertHandlerBridges(
            mn,
            handlerBridges,
            exceptionLocal,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            keyTmpLocal,
            methodSeedLocal,
            stateByLabel,
            keyStateByLabel,
            dispatchPlan.targets(),
            runtimeKeyLabels,
            methodSeed,
            salt,
            dispatcherOutliner,
            transitionOutliner
        );
        insertIslandDispatchers(
            mn,
            blocks,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            keyTmpLocal,
            methodSeedLocal,
            stateByLabel,
            keyStateByLabel,
            dispatchPlan,
            exceptionLocal,
            externalEntrySeed,
            methodSeed,
            salt,
            smallTokenDispatchCases,
            dispatcherOutliner,
            transitionOutliner
        );
        rebuildProtectedTryCatches(mn, protectedTryCatches);

        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = Math.max(mn.maxStack + 16, 18);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(
            id(),
            clazz.name(),
            method.name(),
            method.descriptor(),
            "direct-keyed-island-dispatchers-" + dispatchPlan.groups().size()
        );
        logIslandDryRunMethodStats(
            pctx,
            clazz.name() + "." + method.name() + method.descriptor()
        );
    }
}
