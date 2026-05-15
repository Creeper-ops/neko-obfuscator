package dev.nekoobfuscator.transforms.jvm.cff;

import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.*;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningVerify.*;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
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
class CffTransformEngine {

    static final String METHOD_METADATA =
        "controlFlowFlattening.methodMetadata";
    private static final Logger log = LoggerFactory.getLogger(CffTransformEngine.class);
    public static final String ID = "controlFlowFlattening";
    private static final String CLASS_KEY_TABLES =
        "controlFlowFlattening.classKeyTables";
    private static final String CLASS_KEY_TABLES_PREPARED =
        "controlFlowFlattening.classKeyTablesPrepared";
    private static final String SHARED_CLASS_HELPERS =
        "controlFlowFlattening.sharedClassHelpers";
    private static final String STRING_CONSTANT_VALUES_LOWERED =
        "controlFlowFlattening.stringConstantValuesLowered";
    private static final int CLASS_KEY_TABLE_SIZE = 64;
    private static final int TOKEN_MATERIAL_TABLE_SIZE = 16_384;
    private static final int TOKEN_MATERIAL_ROW_WORDS = 13;
    private static final int TOKEN_MATERIAL_ROW_LONGS = (TOKEN_MATERIAL_ROW_WORDS + 1) / 2;
    private static final int TRANSITION_MATERIAL_TABLE_SIZE = 16_384;
    private static final int TRANSITION_MATERIAL_ROW_WORDS = 37;
    private static final int TRANSITION_MATERIAL_ROW_LONGS = (TRANSITION_MATERIAL_ROW_WORDS + 1) / 2;
    private static final int TOKEN_MATERIAL_WORDS_SLOT = CLASS_KEY_TABLE_SIZE;
    public static final int CLASS_KEY_WORDS_SLOT = CLASS_KEY_TABLE_SIZE + 1;
    public static final int STRING_MATERIAL_SLOT = CLASS_KEY_TABLE_SIZE + 2;
    public static final int INDY_MATERIAL_SLOT = CLASS_KEY_TABLE_SIZE + 3;
    public static final int STRING_MATERIAL_ALIAS_SLOT = CLASS_KEY_TABLE_SIZE + 4;
    public static final int STRING_MATERIAL_SELECTOR_SLOT = CLASS_KEY_TABLE_SIZE + 5;
    public static final int INDY_MATERIAL_ALIAS_SLOT = CLASS_KEY_TABLE_SIZE + 6;
    public static final int INDY_MATERIAL_SELECTOR_SLOT = CLASS_KEY_TABLE_SIZE + 7;
    public static final int CLASS_KEY_WORDS_ALIAS_SLOT = CLASS_KEY_TABLE_SIZE + 8;
    public static final int CLASS_KEY_WORDS_SELECTOR_SLOT = CLASS_KEY_TABLE_SIZE + 9;
    public static final int INDY_CACHE_SLOT = CLASS_KEY_TABLE_SIZE + 10;
    private static final int TRANSITION_MATERIAL_SLOT = CLASS_KEY_TABLE_SIZE + 11;
    private static final int STEP_MATERIAL_SLOT = CLASS_KEY_TABLE_SIZE + 12;
    private static final int CFF_ISLAND_MATERIAL_SLOT = CLASS_KEY_TABLE_SIZE + 13;
    private static final int TOKEN_MATERIAL_CARRIER_SIZE = CLASS_KEY_TABLE_SIZE + 14;
    private static final int TOKEN_MATERIAL_INIT_CHUNK_SIZE = 1024;
    private static final int TRANSITION_MATERIAL_INIT_CHUNK_SIZE = 192;
    private static final int STEP_MATERIAL_TABLE_SIZE = 8_192;
    private static final int STEP_MATERIAL_ROW_WORDS = 8;
    private static final int STEP_MATERIAL_ROW_LONGS = STEP_MATERIAL_ROW_WORDS / 2;
    private static final int STEP_MATERIAL_INIT_CHUNK_LONGS = 512;
    private static final int CFF_ISLAND_MATERIAL_TABLE_SIZE = 16_384;
    private static final int CFF_ISLAND_MATERIAL_INIT_CHUNK_SIZE = 128;
    private static final String TRANSITION_MATERIAL_HELPER_DESC =
        "(JIII[Ljava/lang/Object;II[J)J";
    private static final String STEP_MATERIAL_HELPER_DESC =
        "(JIII[Ljava/lang/Object;I[J)J";
    private static final String KEY_TRANSFER_MATERIAL_HELPER_DESC =
        "(JIII[Ljava/lang/Object;II)J";
    private static final String CFF_ISLAND_MATERIAL_HELPER_DESC =
        "(JIII[Ljava/lang/Object;III)I";
    private static final String CFF_ISLAND_RUNTIME_SOURCE_HELPER_DESC =
        "(JIIIIII)I";
    private static final String CFF_ISLAND_MATERIAL_UNPACK_HELPER_DESC =
        "([Ljava/lang/String;)[I";
    private static final long KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED =
        0x4B58464552484931L;
    private static final long KEY_TRANSFER_MATERIAL_LOW_METHOD_SEED =
        0x4B584645524C4F31L;
    private static final int KEY_TRANSFER_RUNTIME_SOURCE_NONE = 0;
    private static final int KEY_TRANSFER_RUNTIME_SOURCE_THREAD = 1;
    private static final int KEY_TRANSFER_RUNTIME_SOURCE_STACK = 2;
    private static final int KEY_TRANSFER_RUNTIME_SOURCE_BUCKETS = 4;
    private static final int KEY_TRANSFER_CURSOR_MODE_SHIFT = 24;
    private static final int KEY_TRANSFER_CURSOR_INDEX_MASK =
        (1 << KEY_TRANSFER_CURSOR_MODE_SHIFT) - 1;
    private static final int CFF_ISLAND_RUNTIME_SOURCE_NONE = 0;
    private static final int CFF_ISLAND_RUNTIME_SOURCE_THREAD = 1;
    private static final int CFF_ISLAND_RUNTIME_SOURCE_STACK = 2;
    private static final int CFF_ISLAND_RUNTIME_SOURCE_BUCKETS = 4;
    private static final int CFF_ISLAND_CURSOR_MODE_SHIFT = 24;
    private static final int CFF_ISLAND_CURSOR_INDEX_MASK =
        (1 << CFF_ISLAND_CURSOR_MODE_SHIFT) - 1;
    private static final int TRANSITION_MATERIAL_BASE_CLASS_INDEX = 0;
    private static final int TRANSITION_MATERIAL_BASE_CLASS_BLOCK = 1;
    private static final int TRANSITION_MATERIAL_BASE_CLASS_DIGEST = 2;
    private static final int TRANSITION_MATERIAL_BASE_PATH = 3;
    private static final int TRANSITION_MATERIAL_BASE_BLOCK = 4;
    private static final int TRANSITION_MATERIAL_BASE_METHOD_HIGH = 5;
    private static final int TRANSITION_MATERIAL_BASE_METHOD_ADD = 6;
    private static final int TRANSITION_MATERIAL_BASE_METHOD_SHIFT = 7;
    private static final int TRANSITION_MATERIAL_BASE_SHIFT = 8;
    private static final int TRANSITION_MATERIAL_WORDS_BASE = 9;
    private static final int TRANSITION_MATERIAL_WORD_STRIDE = 4;
    private static final int TRANSITION_MATERIAL_GUARD_WORD = 0;
    private static final int TRANSITION_MATERIAL_PATH_WORD = 1;
    private static final int TRANSITION_MATERIAL_BLOCK_WORD = 2;
    private static final int TRANSITION_MATERIAL_PC_WORD = 3;
    private static final int TRANSITION_MATERIAL_METHOD_HIGH_WORD = 4;
    private static final int TRANSITION_MATERIAL_METHOD_LOW_WORD = 5;
    private static final int TRANSITION_MATERIAL_DOMAIN_WORD = 6;
    private static final int TRANSITION_MATERIAL_ENCRYPTED = 0;
    private static final int TRANSITION_MATERIAL_MASK = 1;
    private static final int TRANSITION_MATERIAL_ADD = 2;
    private static final int TRANSITION_MATERIAL_SHIFT = 3;
    private static final int DISPATCH_OUTLINER_ESTIMATED_CODE_PRESSURE = 4_000;
    private static final int DISPATCH_OUTLINER_BLOCK_THRESHOLD = 12;
    private static final int DISPATCH_OUTLINER_EDGE_THRESHOLD = 16;
    private static final int DISPATCH_OUTLINER_HANDLER_THRESHOLD = 4;
    private static final int TRANSITION_OUTLINER_ESTIMATED_CODE_PRESSURE = 24_000;
    private static final int TRANSITION_OUTLINER_BLOCK_THRESHOLD = 48;
    private static final int TRANSITION_OUTLINER_EDGE_THRESHOLD = 80;
    private static final int TRANSITION_OUTLINER_HANDLER_THRESHOLD = 8;
    private static final int SMALL_TOKEN_DISPATCH_CASES = 4;
    private static final int LARGE_METHOD_TOKEN_DISPATCH_CODE_PRESSURE = 60_000;
    private static final int LARGE_METHOD_SMALL_TOKEN_DISPATCH_CASES = 5;
    private static final long METHOD_KEY_PC_MIX = 0x9E3779B97F4A7C15L;
    private static final int CFF_ISLAND_REAL_DISPATCH_ROW_WORDS = 12;
    private static final int CFF_ISLAND_FAKE_DISPATCH_ROW_WORDS = 14;
    private static final int CFF_ISLAND_RESULT_ROW_WORDS = 10;
    private static final int CFF_ISLAND_FAKE_BOUNCE_ROW_WORDS = 16;
    private static final int CFF_ISLAND_POISON_ROW_WORDS = 10;
    private static final int CFF_ISLAND_DENSE_ROUTER_ROW_WORDS = 2;
    private static final int CFF_ISLAND_SPARSE_ROUTER_ROW_WORDS = 3;
    private static final int CFF_ISLAND_SHARED_CALLSITE_EXTRA_INSNS = 3;
    private static final int CFF_ISLAND_SHARED_HELPER_FIXED_INSNS = 96;
    private static final int CFF_ISLAND_SHARED_DENSE_ROUTER_INSNS = 42;
    private static final int CFF_ISLAND_SHARED_SPARSE_ROUTER_INSNS = 58;
    private static final int CFF_ISLAND_COMPRESSED_BLOB_CHUNK_BYTES = 8192;
    private static final int CFF_ISLAND_COMPRESSED_BLOB_CHUNK_CHARS = 8192;
    private static final int CFF_ISLAND_COMPRESSED_INIT_FIXED_INSNS = 96;
    private static final int CFF_ISLAND_COMPRESSED_INIT_CHUNK_INSNS = 24;
    private static final int CFF_ISLAND_COMPRESSED_UNPACK_FIXED_INSNS = 180;
    private static final int CFF_ISLAND_COMPRESSED_UNPACK_CHUNK_INSNS = 6;
    private CffClassKeyTable activeKeyTable;

    
    public String id() {
        return ID;
    }

    
    public String name() {
        return "Control Flow Flattening";
    }

    
    public TransformPhase phase() {
        return TransformPhase.TRANSFORM;
    }

    
    public IRLevel requiredLevel() {
        return IRLevel.L1;
    }

    
    public Set<String> dependsOn() {
        return Set.of(JvmKeyDispatchPass.ID);
    }

    
    public void transformClass(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        lowerStringConstantValuesForStringPass(pctx);
        prepareClassKeyTables(pctx);
    }

    
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
        TransitionOutliner dispatcherOutliner = outlineDispatchers
            ? new TransitionOutliner(
                pctx,
                clazz,
                transitionOutLocal,
                smallTokenDispatchCases,
                materializeDirectIslandTransitions
            )
            : null;
        TransitionOutliner transitionOutliner = outlineTransitions
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

    private void logIslandDryRunMethodStats(PipelineContext pctx, String methodKey) {
        CffIslandDryRunStats stats = pctx.getPassData(CFF_ISLAND_DRY_RUN_STATS);
        if (stats == null) return;
        CffIslandDryRunMethodStats methodStats = stats.methods().get(methodKey);
        if (methodStats == null || methodStats.helpers() == 0) return;
        log.info(
            "CFF island dry-run: method={} helpers={} trivialCandidates={} fakeHelpers={} multiRealHelpers={} denseRouters={} sparseRouters={} helperInsns={} callSiteInsns={} minCallerGrowthInsns={} maxCallerGrowthInsns={} helperInsnMin={} helperInsnMax={}",
            methodKey,
            methodStats.helpers(),
            methodStats.trivialCandidates(),
            methodStats.helpersWithFakeCases(),
            methodStats.helpersWithMultipleRealBlocks(),
            methodStats.denseResultRouters(),
            methodStats.sparseResultRouters(),
            methodStats.helperInstructions(),
            methodStats.callSiteInstructions(),
            methodStats.minimumCallerGrowthInstructions(),
            methodStats.maxMinimumCallerGrowthInstructions(),
            methodStats.minHelperInstructions(),
            methodStats.maxHelperInstructions()
        );
        log.info(
            "CFF island prototype dry-run: method={} dispatchCases={} resultTokens={} fakeCases={} realBlocks={} maxDispatchCases={} maxResultTokens={} maxRealBlocks={}",
            methodKey,
            methodStats.dispatchCases(),
            methodStats.resultTokens(),
            methodStats.fakeCases(),
            methodStats.realBlocks(),
            methodStats.maxDispatchCases(),
            methodStats.maxResultTokens(),
            methodStats.maxRealBlocks()
        );
        log.info(
            "CFF island material-layout dry-run: method={} materialRows={} materialWords={} dispatchRows={} resultRows={} fakeBounceRows={} poisonRows={} routerRows={} callerDeltaInsns={} sharedHelperInsns={} maxMaterialWords={} maxCallerDeltaInsns={} maxSharedHelperInsns={}",
            methodKey,
            methodStats.projectedMaterialRows(),
            methodStats.projectedMaterialWords(),
            methodStats.projectedDispatchRows(),
            methodStats.projectedResultRows(),
            methodStats.projectedFakeBounceRows(),
            methodStats.projectedPoisonRows(),
            methodStats.projectedRouterRows(),
            methodStats.projectedCallerDeltaInstructions(),
            methodStats.projectedSharedHelperInstructions(),
            methodStats.maxProjectedMaterialWords(),
            methodStats.maxProjectedCallerDeltaInstructions(),
            methodStats.maxProjectedSharedHelperInstructions()
        );
        long compressedRawBytes = methodStats.projectedMaterialWords() * Integer.BYTES;
        long compressedChunks = ceilDiv(
            compressedRawBytes,
            CFF_ISLAND_COMPRESSED_BLOB_CHUNK_BYTES
        );
        long rowStoreLongStores = ceilDiv(methodStats.projectedMaterialWords(), 2L);
        long rejectedRowStoreInsns = rowStoreLongStores * 4L;
        long projectedCompressedInitInsns =
            CFF_ISLAND_COMPRESSED_INIT_FIXED_INSNS +
                (compressedChunks * CFF_ISLAND_COMPRESSED_INIT_CHUNK_INSNS);
        long projectedCompressedUnpackInsns =
            CFF_ISLAND_COMPRESSED_UNPACK_FIXED_INSNS +
                (compressedChunks * CFF_ISLAND_COMPRESSED_UNPACK_CHUNK_INSNS);
        log.info(
            "CFF island compressed-material dry-run: method={} rawBytes={} blobChunkBytes={} blobChunks={} rowStoreLongStores={} rejectedRowStoreInsns={} projectedBlobInitInsns={} projectedUnpackHelperInsns={} materialRows={} materialWords={} maxMaterialWords={}",
            methodKey,
            compressedRawBytes,
            CFF_ISLAND_COMPRESSED_BLOB_CHUNK_BYTES,
            compressedChunks,
            rowStoreLongStores,
            rejectedRowStoreInsns,
            projectedCompressedInitInsns,
            projectedCompressedUnpackInsns,
            methodStats.projectedMaterialRows(),
            methodStats.projectedMaterialWords(),
            methodStats.maxProjectedMaterialWords()
        );
        CffIslandMaterialOpDryRunStats opStats = pctx.getPassData(
            CFF_ISLAND_MATERIAL_OP_DRY_RUN_STATS
        );
        if (opStats == null) return;
        CffIslandMaterialOpDryRunMethodStats opMethodStats = opStats
            .methods()
            .get(methodKey);
        if (opMethodStats == null || opMethodStats.helpers() == 0) return;
        log.info(
            "CFF island material-op dry-run: method={} helpers={} fakeStepRows={} poisonStepRows={} firstTinyUpdates={} secondTinyUpdates={} methodKeyUpdates={} fakeBounceRows={} bouncePredicateRows={} denseResultRows={} sparseResultRows={} hardFailRows={} maxFakeStepRows={} maxSecondTinyUpdates={} maxMethodKeyUpdates={} maxBouncePredicateRows={}",
            methodKey,
            opMethodStats.helpers(),
            opMethodStats.fakeStepRows(),
            opMethodStats.poisonStepRows(),
            opMethodStats.firstTinyUpdates(),
            opMethodStats.secondTinyUpdates(),
            opMethodStats.methodKeyUpdates(),
            opMethodStats.fakeBounceRows(),
            opMethodStats.bouncePredicateRows(),
            opMethodStats.denseResultRows(),
            opMethodStats.sparseResultRows(),
            opMethodStats.hardFailRows(),
            opMethodStats.maxFakeStepRows(),
            opMethodStats.maxSecondTinyUpdates(),
            opMethodStats.maxMethodKeyUpdates(),
            opMethodStats.maxBouncePredicateRows()
        );
        long projectedSharedHelpers = methodStats.helpers() == 0 ? 0L : 1L;
        long projectedHelperReduction = Math.max(
            0L,
            methodStats.helpers() - projectedSharedHelpers
        );
        long liveDispatchTokenRows = methodStats.dispatchCases();
        long staticDispatchTokenRows = 0L;
        long missingFakeStepRows = Math.max(
            0L,
            opMethodStats.fakeBounceRows() - opMethodStats.fakeStepRows()
        );
        long missingPoisonStepRows = Math.max(
            0L,
            opMethodStats.hardFailRows() - opMethodStats.poisonStepRows()
        );
        long missingBounceRows = Math.max(
            0L,
            opMethodStats.fakeBounceRows() - methodStats.fakeCases()
        );
        long fakeSourceKeyProofRows = opMethodStats.fakeBounceRows();
        long missingFakeSourceKeyProofRows = Math.max(
            0L,
            opMethodStats.fakeBounceRows() - fakeSourceKeyProofRows
        );
        long semanticSwitchBlockedFakeRows = missingFakeSourceKeyProofRows;
        log.info(
            "CFF island shared-interpreter readiness dry-run: method={} readyHelpers={} currentHelpers={} projectedSharedHelpers={} projectedHelperReduction={} liveDispatchTokenRows={} staticDispatchTokenRows={} realRows={} fakeRows={} poisonRows={} fakeStepRows={} fakeBounceRows={} hardFailRows={} denseResultRows={} sparseResultRows={} missingFakeStepRows={} missingPoisonStepRows={} missingBounceRows={} fakeSourceKeyProofRows={} missingFakeSourceKeyProofRows={} semanticSwitchBlockedFakeRows={}",
            methodKey,
            opMethodStats.helpers(),
            methodStats.helpers(),
            projectedSharedHelpers,
            projectedHelperReduction,
            liveDispatchTokenRows,
            staticDispatchTokenRows,
            methodStats.realBlocks(),
            methodStats.fakeCases(),
            opMethodStats.hardFailRows(),
            opMethodStats.fakeStepRows(),
            opMethodStats.fakeBounceRows(),
            opMethodStats.hardFailRows(),
            opMethodStats.denseResultRows(),
            opMethodStats.sparseResultRows(),
            missingFakeStepRows,
            missingPoisonStepRows,
            missingBounceRows,
            fakeSourceKeyProofRows,
            missingFakeSourceKeyProofRows,
            semanticSwitchBlockedFakeRows
        );
    }

    private static long ceilDiv(long value, long divisor) {
        if (value <= 0L) {
            return 0L;
        }
        return ((value - 1L) / divisor) + 1L;
    }

    private boolean isApplicationMethod(
        PipelineContext pctx,
        L1Class clazz,
        L1Method method
    ) {
        if (
            TransformGuards.isRuntimeClass(clazz) ||
            TransformGuards.isGeneratedMethod(method)
        ) return false;
        if (method.isClassInit() && isGeneratedTableClassInit(pctx, clazz)) {
            return false;
        }
        if (method.isAbstract() || method.isNative()) return false;
        return true;
    }

    private boolean hasApplicationCode(PipelineContext pctx, L1Class clazz) {
        if (
            TransformGuards.isRuntimeClass(clazz) ||
            clazz.isInterface() ||
            clazz.isAnnotation()
        ) return false;
        for (L1Method method : clazz.methods()) {
            if (method.hasCode() && isApplicationMethod(pctx, clazz, method)) {
                return true;
            }
        }
        return false;
    }

    private void lowerStringConstantValuesForStringPass(PipelineContext pctx) {
        if (!pctx.config().isTransformEnabled(JvmStringObfuscationPass.ID)) {
            return;
        }
        if (Boolean.TRUE.equals(pctx.getPassData(STRING_CONSTANT_VALUES_LOWERED))) {
            return;
        }
        for (L1Class clazz : pctx.classMap().values()) {
            lowerStringConstantValues(clazz);
        }
        pctx.putPassData(STRING_CONSTANT_VALUES_LOWERED, Boolean.TRUE);
    }

    private void lowerStringConstantValues(L1Class clazz) {
        if (TransformGuards.isRuntimeClass(clazz) || clazz.isAnnotation()) return;
        if (clazz.asmNode().fields == null) return;
        MethodNode clinit = null;
        int moved = 0;
        for (FieldNode field : clazz.asmNode().fields) {
            if (!isStringConstantValue(field)) continue;
            String value = (String) field.value;
            field.value = null;
            if (clinit == null) {
                clinit = findOrCreateClassInit(clazz);
            }
            InsnList assignment = new InsnList();
            assignment.add(new LdcInsnNode(value));
            assignment.add(new FieldInsnNode(
                Opcodes.PUTSTATIC,
                clazz.name(),
                field.name,
                field.desc
            ));
            AbstractInsnNode first = firstReal(clinit);
            if (first == null) {
                clinit.instructions.add(assignment);
                clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            } else {
                clinit.instructions.insertBefore(first, assignment);
            }
            moved++;
        }
        if (moved > 0) {
            clinit.maxStack = Math.max(clinit.maxStack, 1);
            clazz.markDirty();
        }
    }

    private boolean isStringConstantValue(FieldNode field) {
        return (field.access & Opcodes.ACC_STATIC) != 0
            && "Ljava/lang/String;".equals(field.desc)
            && field.value instanceof String;
    }

    private void prepareClassKeyTables(PipelineContext pctx) {
        if (Boolean.TRUE.equals(pctx.getPassData(CLASS_KEY_TABLES_PREPARED))) {
            return;
        }
        for (L1Class clazz : pctx.classMap().values()) {
            if (hasApplicationCode(pctx, clazz)) {
                ensureClassKeyTable(pctx, clazz);
            }
        }
        pctx.putPassData(CLASS_KEY_TABLES_PREPARED, Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    private CffClassKeyTable ensureClassKeyTable(
        PipelineContext pctx,
        L1Class clazz
    ) {
        Map<String, CffClassKeyTable> tables = pctx.getPassData(
            CLASS_KEY_TABLES
        );
        if (tables == null) {
            tables = new LinkedHashMap<>();
            pctx.putPassData(CLASS_KEY_TABLES, tables);
        }
        CffClassKeyTable existing = tables.get(clazz.name());
        if (existing != null) return existing;

        long seed = JvmPassBytecode.mix(
            pctx.masterSeed() ^ 0x434646434C415353L,
            clazz.name().hashCode()
        );
        int[] table = classKeyTable(seed);
        String objectFieldName =
            uniqueFieldName(clazz, "$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4F424A54424C31L), 36));
        int[] objectTable = classKeyObjectTable(seed, table);
        String objectHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_obj$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4F424A48454C5031L), 36),
            "([Ljava/lang/Object;IIIIIIIII)I"
        );
        String controlHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_ctl$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4354524C48454C50L), 36),
            "(IIIIII)I"
        );
        String tokenHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_tok$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x544F4B48454C5031L), 36),
            "([I[Ljava/lang/Object;IIIIIIIIIIIIIIII)I"
        );
        String digestHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_dig$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x44494748454C5031L), 36),
            "(IIIIII)I"
        );
        String dispatchHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_dsp$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x44535048454C5031L), 36),
            "(IIIIIII)I"
        );
        CffSharedClassHelpers sharedHelpers = ensureSharedClassHelpers(pctx, clazz, seed);
        int fieldAccess =
            (clazz.isInterface() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE) |
            Opcodes.ACC_STATIC |
            Opcodes.ACC_FINAL |
            Opcodes.ACC_SYNTHETIC;
        clazz.asmNode().fields.add(new FieldNode(
            fieldAccess,
            objectFieldName,
            "[Ljava/lang/Object;",
            null,
            null
        ));

        boolean generatedClinit = findClassInit(clazz) == null;
        MethodNode clinit = findOrCreateClassInit(clazz);
        int initCarrierLocal = clinit.maxLocals;
        CffClassKeyTable data = new CffClassKeyTable(
            clazz.name(),
            pctx,
            clazz,
            objectFieldName,
            sharedHelpers.intHelperName(),
            sharedHelpers.intHelperOwner(),
            sharedHelpers.intHelperInterfaceOwner(),
            objectHelperName,
            controlHelperName,
            tokenHelperName,
            sharedHelpers.tokenMaterialHelperName(),
            sharedHelpers.tokenMaterialHelperOwner(),
            sharedHelpers.tokenMaterialHelperInterfaceOwner(),
            sharedHelpers.transitionMaterialHelperName(),
            sharedHelpers.transitionMaterialHelperOwner(),
            sharedHelpers.transitionMaterialHelperInterfaceOwner(),
            sharedHelpers.stepMaterialHelperName(),
            sharedHelpers.stepMaterialHelperOwner(),
            sharedHelpers.stepMaterialHelperInterfaceOwner(),
            sharedHelpers.keyTransferMaterialHelperName(),
            sharedHelpers.keyTransferMaterialHelperOwner(),
            sharedHelpers.keyTransferMaterialHelperInterfaceOwner(),
            sharedHelpers.islandRuntimeSourceHelperName(),
            sharedHelpers.islandRuntimeSourceHelperOwner(),
            sharedHelpers.islandRuntimeSourceHelperInterfaceOwner(),
            sharedHelpers.islandMaterialHelperName(),
            sharedHelpers.islandMaterialHelperOwner(),
            sharedHelpers.islandMaterialHelperInterfaceOwner(),
            sharedHelpers.islandMaterialUnpackHelperName(),
            sharedHelpers.islandMaterialUnpackHelperOwner(),
            sharedHelpers.islandMaterialUnpackHelperInterfaceOwner(),
            digestHelperName,
            dispatchHelperName,
            sharedHelpers.methodKeyHelperName(),
            sharedHelpers.methodKeyHelperOwner(),
            sharedHelpers.methodKeyHelperInterfaceOwner(),
            new int[1],
            new ArrayList<>(),
            new int[1],
            new ArrayList<>(),
            new int[1],
            new ArrayList<>(),
            new int[1],
            new ArrayList<>(),
            table,
            objectTable,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C494E49544B31L)),
            initCarrierLocal,
            new LabelNode(),
            new LabelNode(),
            generatedClinit,
            clazz.isInterface()
        );
        installClassKeyTableInit(pctx, clazz, data);
        tables.put(clazz.name(), data);
        clazz.markDirty();
        return data;
    }

    @SuppressWarnings("unchecked")
    private CffSharedClassHelpers ensureSharedClassHelpers(
        PipelineContext pctx,
        L1Class clazz,
        long seed
    ) {
        Map<String, CffSharedClassHelpers> helpers = pctx.getPassData(SHARED_CLASS_HELPERS);
        if (helpers == null) {
            helpers = new HashMap<>();
            pctx.putPassData(SHARED_CLASS_HELPERS, helpers);
        }
        String key = packageName(clazz.name());
        CffSharedClassHelpers existing = helpers.get(key);
        if (existing != null) {
            return existing;
        }
        String intHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_int$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x494E544853484831L), 36),
            "([IIIIIII)I"
        );
        String tokenMaterialHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_tmat$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x544D415453484831L), 36),
            "([Ljava/lang/Object;IIII)I"
        );
        String transitionMaterialHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_xmat$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x584D415453484831L), 36),
            TRANSITION_MATERIAL_HELPER_DESC
        );
        String keyTransferMaterialHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_kxfer$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4B58464552485031L), 36),
            KEY_TRANSFER_MATERIAL_HELPER_DESC
        );
        String stepMaterialHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_step$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x5354455053484831L), 36),
            STEP_MATERIAL_HELPER_DESC
        );
        String islandMaterialHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_imat$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x494D415453484831L), 36),
            CFF_ISLAND_MATERIAL_HELPER_DESC
        );
        String islandRuntimeSourceHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_isrc$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4953524353484831L), 36),
            CFF_ISLAND_RUNTIME_SOURCE_HELPER_DESC
        );
        String islandMaterialUnpackHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_iunpack$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x49554E5041434B31L), 36),
            CFF_ISLAND_MATERIAL_UNPACK_HELPER_DESC
        );
        String methodKeyHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_mkey$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4D4B48454C534831L), 36),
            "(IIIIJJ)J"
        );
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        if (clazz.isInterface()) {
            access |= Opcodes.ACC_PUBLIC;
        }
        installClassKeyIntHelper(pctx, clazz, intHelperName, access);
        installEncryptedTokenMaterialHelper(pctx, clazz, tokenMaterialHelperName, access);
        installTransitionMaterialHelper(
            pctx,
            clazz,
            transitionMaterialHelperName,
            access,
            clazz.name(),
            intHelperName,
            clazz.isInterface()
        );
        installKeyTransferMaterialHelper(
            pctx,
            clazz,
            keyTransferMaterialHelperName,
            access,
            clazz.name(),
            tokenMaterialHelperName,
            clazz.isInterface()
        );
        installStepMaterialHelper(pctx, clazz, stepMaterialHelperName, access);
        installCffIslandRuntimeSourceHelper(pctx, clazz, islandRuntimeSourceHelperName, access);
        installCompressedIslandMaterialHelper(pctx, clazz, islandMaterialHelperName, access);
        installCompressedIslandMaterialUnpackHelper(
            pctx,
            clazz,
            islandMaterialUnpackHelperName,
            access
        );
        installMethodKeyFromStateHelper(pctx, clazz, methodKeyHelperName, access);
        CffSharedClassHelpers created = new CffSharedClassHelpers(
            clazz.name(),
            intHelperName,
            clazz.isInterface(),
            clazz.name(),
            tokenMaterialHelperName,
            clazz.isInterface(),
            clazz.name(),
            transitionMaterialHelperName,
            clazz.isInterface(),
            clazz.name(),
            keyTransferMaterialHelperName,
            clazz.isInterface(),
            clazz.name(),
            stepMaterialHelperName,
            clazz.isInterface(),
            clazz.name(),
            islandRuntimeSourceHelperName,
            clazz.isInterface(),
            clazz.name(),
            islandMaterialHelperName,
            clazz.isInterface(),
            clazz.name(),
            islandMaterialUnpackHelperName,
            clazz.isInterface(),
            clazz.name(),
            methodKeyHelperName,
            clazz.isInterface()
        );
        helpers.put(key, created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private List<CffClassKeyTable> classKeyTables(PipelineContext pctx) {
        Map<String, CffClassKeyTable> tables = pctx.getPassData(
            CLASS_KEY_TABLES
        );
        if (tables == null || tables.isEmpty()) return List.of();
        return List.copyOf(tables.values());
    }

    private Set<LabelNode> rewriteInjectedMemberReflection(
        PipelineContext pctx,
        MethodNode mn
    ) {
        return CffReflectionMemberFilters.rewrite(pctx, mn, classKeyTables(pctx));
    }

    @SuppressWarnings("unchecked")
    private boolean isGeneratedTableClassInit(
        PipelineContext pctx,
        L1Class clazz
    ) {
        Map<String, CffClassKeyTable> tables = pctx.getPassData(
            CLASS_KEY_TABLES
        );
        CffClassKeyTable table = tables == null ? null : tables.get(clazz.name());
        return table != null && table.generatedClinit();
    }

    private String uniqueFieldName(L1Class clazz, String base) {
        String candidate = base;
        int suffix = 0;
        while (clazz.findField(candidate, "[I") != null) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private String uniqueMethodName(L1Class clazz, String base, String desc) {
        String candidate = base;
        int suffix = 0;
        while (clazz.findMethod(candidate, desc) != null || hasAsmMethod(clazz, candidate, desc)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private String packageName(String owner) {
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? "" : owner.substring(0, slash);
    }

    private boolean hasAsmMethod(L1Class clazz, String name, String desc) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }

    private int[] classKeyTable(long seed) {
        int[] table = new int[CLASS_KEY_TABLE_SIZE];
        long state = seed;
        for (int i = 0; i < table.length; i++) {
            state = JvmPassBytecode.mix(state, i ^ 0x5441424C454B31L);
            table[i] = nonZeroInt(state);
        }
        return table;
    }

    private int[] classKeyObjectTable(long seed, int[] classWords) {
        int[] table = new int[classWords.length];
        long state = seed ^ 0x4346464F424A5431L;
        for (int i = 0; i < table.length; i++) {
            state = JvmPassBytecode.mix(state ^ classWords[i], i ^ 0x4F424A54424C31L);
            table[i] = nonZeroInt(state);
        }
        return table;
    }

    private void installClassKeyTableInit(
        PipelineContext pctx,
        L1Class clazz,
        CffClassKeyTable table
    ) {
        MethodNode clinit = findOrCreateClassInit(clazz);
        InsnList init = new InsnList();
        int arrayLocal = table.initCarrierLocal();
        int classWordsLocal = arrayLocal + 1;
        init.add(table.initStart());
        JvmPassBytecode.pushInt(init, table.values().length);
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        init.add(new VarInsnNode(Opcodes.ASTORE, classWordsLocal));
        for (int i = 0; i < table.values().length; i++) {
            init.add(new VarInsnNode(Opcodes.ALOAD, classWordsLocal));
            JvmPassBytecode.pushInt(init, i);
            JvmPassBytecode.pushInt(init, table.values()[i] ^ table.clinitMask());
            JvmPassBytecode.pushInt(init, table.clinitMask());
            init.add(new InsnNode(Opcodes.IXOR));
            init.add(new InsnNode(Opcodes.IASTORE));
        }
        JvmPassBytecode.pushInt(init, TOKEN_MATERIAL_CARRIER_SIZE);
        init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        init.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
        for (int i = 0; i < table.objectValues().length; i++) {
            int epoch = cffObjectCellEpoch(table.clinitMask(), i);
            int encoded = table.objectValues()[i] ^ cffObjectCellMask(epoch);
            long packed = (((long) encoded) << 32) ^ Integer.toUnsignedLong(epoch);
            init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            JvmPassBytecode.pushInt(init, i);
            init.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/atomic/AtomicLong"));
            init.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushLong(init, packed);
            init.add(new MethodInsnNode(
                Opcodes.INVOKESPECIAL,
                "java/util/concurrent/atomic/AtomicLong",
                "<init>",
                "(J)V",
                false
            ));
            init.add(new InsnNode(Opcodes.AASTORE));
        }
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, TOKEN_MATERIAL_WORDS_SLOT);
        JvmPassBytecode.pushInt(init, TOKEN_MATERIAL_TABLE_SIZE * TOKEN_MATERIAL_ROW_LONGS);
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, TRANSITION_MATERIAL_SLOT);
        JvmPassBytecode.pushInt(init, TRANSITION_MATERIAL_TABLE_SIZE * TRANSITION_MATERIAL_ROW_WORDS);
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, STEP_MATERIAL_SLOT);
        JvmPassBytecode.pushInt(init, STEP_MATERIAL_TABLE_SIZE * STEP_MATERIAL_ROW_LONGS);
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, CFF_ISLAND_MATERIAL_SLOT);
        JvmPassBytecode.pushInt(init, CFF_ISLAND_MATERIAL_TABLE_SIZE);
        init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, CLASS_KEY_WORDS_SLOT);
        init.add(new VarInsnNode(Opcodes.ALOAD, classWordsLocal));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, CLASS_KEY_WORDS_ALIAS_SLOT);
        init.add(new VarInsnNode(Opcodes.ALOAD, classWordsLocal));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(init, CLASS_KEY_WORDS_SELECTOR_SLOT);
        JvmPassBytecode.pushInt(init, 2);
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new InsnNode(Opcodes.ICONST_0));
        JvmPassBytecode.pushInt(init, CLASS_KEY_WORDS_SLOT);
        init.add(new InsnNode(Opcodes.IASTORE));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new InsnNode(Opcodes.ICONST_1));
        JvmPassBytecode.pushInt(init, CLASS_KEY_WORDS_ALIAS_SLOT);
        init.add(new InsnNode(Opcodes.IASTORE));
        init.add(new InsnNode(Opcodes.AASTORE));
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        init.add(new FieldInsnNode(
            Opcodes.PUTSTATIC,
            clazz.name(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        init.add(table.initEnd());
        JvmKeyDispatchPass.markGenerated(pctx, init);
        AbstractInsnNode first = firstReal(clinit);
        if (first == null) {
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            clinit.instructions.insertBefore(first, init);
        }
        clinit.maxLocals = Math.max(clinit.maxLocals, classWordsLocal + 1);
        clinit.maxStack = Math.max(clinit.maxStack, 6);
        clinit.maxStack = Math.max(clinit.maxStack, 6);
    }

    private void installClassKeyIntHelper(
        PipelineContext pctx,
        L1Class clazz,
        String intHelperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            intHelperName,
            "([IIIIIII)I",
            null,
            null
        );
        int tableLocal = 0;
        int guardLocal = 1;
        int pathLocal = 2;
        int blockLocal = 3;
        int indexMixLocal = 4;
        int blockMixLocal = 5;
        int digestMixLocal = 6;
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexMixLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockMixLocal));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_TABLE_SIZE - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, digestMixLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 7;
        helper.maxStack = 8;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
    }

    private void installEncryptedTokenMaterialHelper(
        PipelineContext pctx,
        L1Class clazz,
        String tokenMaterialHelperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            tokenMaterialHelperName,
            "([Ljava/lang/Object;IIII)I",
            null,
            null
        );
        int materialLocal = 0;
        int indexLocal = 1;
        int guardLocal = 2;
        int pathLocal = 3;
        int blockLocal = 4;
        int rowLocal = 5;
        int accumulatorLocal = 6;
        int objectIndexLocal = 7;
        int packedLocal = 8;
        int epochLocal = 10;
        int encodedLocal = 11;
        int objectResultLocal = 12;
        int nextEpochLocal = 13;
        int nextEncodedLocal = 14;
        int objectCellLocal = 15;
        int currentMaskLocal = 16;
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        JvmPassBytecode.pushInt(insns, TOKEN_MATERIAL_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, rowLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, indexLocal);
        emitTokenMaterialClassMask(insns, materialLocal, rowLocal, indexLocal, guardLocal, pathLocal, blockLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, accumulatorLocal));
        emitTokenMaterialObjectMask(
            insns,
            materialLocal,
            rowLocal,
            indexLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            objectIndexLocal,
            packedLocal,
            epochLocal,
            encodedLocal,
            objectResultLocal,
            nextEpochLocal,
            nextEncodedLocal,
            objectCellLocal,
            currentMaskLocal
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, accumulatorLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTokenMaterialControlMask(insns, rowLocal, indexLocal, guardLocal, pathLocal, blockLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 17;
        helper.maxStack = 24;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
    }

    private void installStepMaterialHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            helperName,
            STEP_MATERIAL_HELPER_DESC,
            null,
            null
        );
        int keyLocal = 0;
        int guardLocal = 2;
        int pathLocal = 3;
        int blockLocal = 4;
        int materialLocal = 5;
        int rowLocal = 6;
        int outLocal = 7;
        int wordsLocal = 8;
        int baseLocal = 9;
        int flagsLocal = 10;
        int valueLocal = 11;
        int indexLocal = 12;
        int sourceIndexLocal = 13;
        int opLocal = 14;
        int decodeBaseLocal = 15;
        int runtimeSourceLocal = 16;
        int threadLocal = 17;
        int stackLocal = 18;
        int stackLengthLocal = 19;
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        JvmPassBytecode.pushInt(insns, STEP_MATERIAL_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, wordsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rowLocal));
        JvmPassBytecode.pushInt(insns, STEP_MATERIAL_ROW_LONGS);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, baseLocal));
        emitStepMaterialDecodeBase(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            wordsLocal,
            baseLocal
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, decodeBaseLocal));
        emitStepMaterialRuntimeSource(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            threadLocal,
            stackLocal,
            stackLengthLocal
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, runtimeSourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, decodeBaseLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, runtimeSourceLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, decodeBaseLocal));
        emitStepMaterialDecodedWordLoad(
            insns,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            0
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, flagsLocal));
        emitStepTinyUpdateFromMaterial(
            insns,
            flagsLocal,
            0,
            2,
            4,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            1,
            guardLocal,
            pathLocal,
            blockLocal,
            valueLocal,
            indexLocal,
            sourceIndexLocal,
            opLocal
        );
        LabelNode noSecondTiny = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, 1 << 6);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, noSecondTiny));
        emitStepTinyUpdateFromMaterial(
            insns,
            flagsLocal,
            8,
            10,
            12,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            2,
            guardLocal,
            pathLocal,
            blockLocal,
            valueLocal,
            indexLocal,
            sourceIndexLocal,
            opLocal
        );
        insns.add(noSecondTiny);
        LabelNode noMethodKey = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, 1 << 7);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, noMethodKey));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, 10);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceIndexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, 12);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, opLocal));
        emitLoadStepIndexedInt(insns, sourceIndexLocal, guardLocal, pathLocal, blockLocal);
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        emitStepMaterialMethodConstantLoad(
            insns,
            wordsLocal,
            baseLocal,
            decodeBaseLocal
        );
        emitStepMaterialMethodKeyUpdate(insns, keyLocal, valueLocal, opLocal);
        insns.add(noMethodKey);
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathLocal);
        emitTransitionOutHighStore(insns, outLocal, 1, blockLocal);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.LRETURN));
        helper.maxLocals = 20;
        helper.maxStack = 32;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        publishGeneratedHelperFlowKey(
            pctx,
            clazz.name(),
            helperName,
            STEP_MATERIAL_HELPER_DESC,
            keyLocal
        );
    }

    private void emitStepMaterialWordLoad(
        InsnList insns,
        int wordsLocal,
        int baseLocal,
        int word
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, wordsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, baseLocal));
        JvmPassBytecode.pushInt(insns, word / 2);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.LALOAD));
        if ((word & 1) == 0) {
            JvmPassBytecode.pushInt(insns, 32);
            insns.add(new InsnNode(Opcodes.LUSHR));
        }
        insns.add(new InsnNode(Opcodes.L2I));
    }

    private void emitStepMaterialDecodedWordLoad(
        InsnList insns,
        int wordsLocal,
        int baseLocal,
        int decodeBaseLocal,
        int word
    ) {
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, word);
        emitStepMaterialWordMask(insns, wordsLocal, baseLocal, decodeBaseLocal, word);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStepMaterialDecodeBase(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int wordsLocal,
        int baseLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IADD));
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, 5);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, 6);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, 7);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStepMaterialRuntimeSource(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int threadLocal,
        int stackLocal,
        int stackLengthLocal
    ) {
        LabelNode stackElementTwoDone = new LabelNode();
        LabelNode stackElementThreeDone = new LabelNode();
        JvmPassBytecode.pushInt(insns, 0x53544550);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "identityHashCode",
            "(Ljava/lang/Object;)I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Object",
            "getClass",
            "()Ljava/lang/Class;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Class",
            "getName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getStackTrace",
            "()[Ljava/lang/StackTraceElement;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stackLengthLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, stackElementTwoDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(stackElementTwoDone);
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, stackElementThreeDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(stackElementThreeDone);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStepMaterialWordMask(
        InsnList insns,
        int wordsLocal,
        int baseLocal,
        int decodeBaseLocal,
        int word
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, decodeBaseLocal));
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, 5);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, 6);
        JvmPassBytecode.pushInt(insns, word + 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitStepMaterialWordLoad(insns, wordsLocal, baseLocal, 7);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B * (word + 1));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStepTinyUpdateFromMaterial(
        InsnList insns,
        int flagsLocal,
        int dstShift,
        int sourceShift,
        int opShift,
        int wordsLocal,
        int baseLocal,
        int decodeBaseLocal,
        int constantWord,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int valueLocal,
        int indexLocal,
        int sourceIndexLocal,
        int opLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, dstShift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, sourceShift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceIndexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flagsLocal));
        JvmPassBytecode.pushInt(insns, opShift);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, opLocal));
        emitStepMaterialTinyUpdate(
            insns,
            indexLocal,
            sourceIndexLocal,
            opLocal,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            constantWord,
            guardLocal,
            pathLocal,
            blockLocal,
            valueLocal
        );
    }

    private void emitStepMaterialTinyUpdate(
        InsnList insns,
        int dstIndexLocal,
        int sourceIndexLocal,
        int opLocal,
        int wordsLocal,
        int baseLocal,
        int decodeBaseLocal,
        int constantWord,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int valueLocal
    ) {
        LabelNode case0 = new LabelNode();
        LabelNode case1 = new LabelNode();
        LabelNode case2 = new LabelNode();
        LabelNode case3 = new LabelNode();
        LabelNode done = new LabelNode();
        emitLoadStepIndexedInt(insns, dstIndexLocal, guardLocal, pathLocal, blockLocal);
        emitLoadStepIndexedInt(insns, sourceIndexLocal, guardLocal, pathLocal, blockLocal);
        emitStepMaterialDecodedWordLoad(
            insns,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            constantWord
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, opLocal));
        insns.add(new TableSwitchInsnNode(0, 3, case3, case0, case1, case2, case3));
        insns.add(case0);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(case1);
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(case2);
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(case3);
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitStepMaterialDecodedWordLoad(
            insns,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            constantWord
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(done);
        emitStoreStepIndexedInt(insns, dstIndexLocal, guardLocal, pathLocal, blockLocal, valueLocal);
    }

    private void emitLoadStepIndexedInt(
        InsnList insns,
        int indexLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal
    ) {
        LabelNode guard = new LabelNode();
        LabelNode path = new LabelNode();
        LabelNode block = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new TableSwitchInsnNode(0, 2, guard, guard, path, block));
        insns.add(guard);
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(path);
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(block);
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(done);
    }

    private void emitStoreStepIndexedInt(
        InsnList insns,
        int indexLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int valueLocal
    ) {
        LabelNode guard = new LabelNode();
        LabelNode path = new LabelNode();
        LabelNode block = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new TableSwitchInsnNode(0, 2, guard, guard, path, block));
        insns.add(guard);
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(path);
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(block);
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockLocal));
        insns.add(done);
    }

    private void emitStepMaterialMethodConstantLoad(
        InsnList insns,
        int wordsLocal,
        int baseLocal,
        int decodeBaseLocal
    ) {
        emitStepMaterialDecodedWordLoad(
            insns,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            3
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitStepMaterialDecodedWordLoad(
            insns,
            wordsLocal,
            baseLocal,
            decodeBaseLocal,
            4
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private void emitStepMaterialMethodKeyUpdate(
        InsnList insns,
        int keyLocal,
        int sourceLocal,
        int opLocal
    ) {
        LabelNode case0 = new LabelNode();
        LabelNode case1 = new LabelNode();
        LabelNode case2 = new LabelNode();
        LabelNode case3 = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, opLocal));
        insns.add(new TableSwitchInsnNode(0, 3, case3, case0, case1, case2, case3));
        insns.add(case0);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(case1);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(case2);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(case3);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        insns.add(done);
    }

    private void installTransitionMaterialHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access,
        String intHelperOwner,
        String intHelperName,
        boolean intHelperInterfaceOwner
    ) {
        MethodNode helper = new MethodNode(
            access,
            helperName,
            TRANSITION_MATERIAL_HELPER_DESC,
            null,
            null
        );
        int keyLocal = 0;
        int guardLocal = 2;
        int pathLocal = 3;
        int blockLocal = 4;
        int objectMaterialLocal = 5;
        int rowLocal = 6;
        int domainLocal = 7;
        int outLocal = 8;
        int pcLocal = 9;
        int materialLocal = 10;
        int baseLocal = 11;
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, objectMaterialLocal));
        JvmPassBytecode.pushInt(insns, TRANSITION_MATERIAL_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, materialLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rowLocal));
        JvmPassBytecode.pushInt(insns, TRANSITION_MATERIAL_ROW_WORDS);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, rowLocal));
        emitTransitionMaterialBase(
            insns,
            objectMaterialLocal,
            intHelperOwner,
            intHelperName,
            intHelperInterfaceOwner,
            materialLocal,
            rowLocal,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            baseLocal
        );
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_GUARD_WORD
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_PATH_WORD
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathLocal));
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_BLOCK_WORD
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockLocal));
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_PC_WORD
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, pcLocal));
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_METHOD_HIGH_WORD
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_METHOD_LOW_WORD
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        emitTransitionMaterialDecodedWord(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            TRANSITION_MATERIAL_DOMAIN_WORD
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, domainLocal));
        emitTransitionOutStores(
            insns,
            outLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            pcLocal,
            domainLocal,
            true
        );
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.LRETURN));
        helper.maxLocals = 11;
        helper.maxStack = 32;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        publishGeneratedHelperFlowKey(
            pctx,
            clazz.name(),
            helperName,
            TRANSITION_MATERIAL_HELPER_DESC,
            keyLocal
        );
    }

    private void installKeyTransferMaterialHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access,
        String tokenMaterialHelperOwner,
        String tokenMaterialHelperName,
        boolean tokenMaterialHelperInterfaceOwner
    ) {
        MethodNode helper = new MethodNode(
            access,
            helperName,
            KEY_TRANSFER_MATERIAL_HELPER_DESC,
            null,
            null
        );
        int keyLocal = 0;
        int guardLocal = 2;
        int pathLocal = 3;
        int blockLocal = 4;
        int materialLocal = 5;
        int highCursorLocal = 6;
        int lowCursorLocal = 7;
        int highWordLocal = 8;
        int baseCursorLocal = 9;
        int modeLocal = 10;
        int sourceLocal = 11;
        int threadLocal = 12;
        int stackLocal = 13;
        int stackLengthLocal = 14;
        InsnList insns = helper.instructions;
        insnDecodeKeyTransferWord(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            materialLocal,
            highCursorLocal,
            baseCursorLocal,
            modeLocal,
            sourceLocal,
            threadLocal,
            stackLocal,
            stackLengthLocal,
            tokenMaterialHelperOwner,
            tokenMaterialHelperName,
            tokenMaterialHelperInterfaceOwner
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, highWordLocal));
        insnDecodeKeyTransferWord(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            materialLocal,
            lowCursorLocal,
            baseCursorLocal,
            modeLocal,
            sourceLocal,
            threadLocal,
            stackLocal,
            stackLengthLocal,
            tokenMaterialHelperOwner,
            tokenMaterialHelperName,
            tokenMaterialHelperInterfaceOwner
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, materialLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, highWordLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, materialLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LRETURN));
        helper.maxLocals = 15;
        helper.maxStack = 24;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        publishGeneratedHelperFlowKey(
            pctx,
            clazz.name(),
            helperName,
            KEY_TRANSFER_MATERIAL_HELPER_DESC,
            keyLocal
        );
    }

    private void insnDecodeKeyTransferWord(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int materialLocal,
        int cursorLocal,
        int baseCursorLocal,
        int modeLocal,
        int sourceLocal,
        int threadLocal,
        int stackLocal,
        int stackLengthLocal,
        String tokenMaterialHelperOwner,
        String tokenMaterialHelperName,
        boolean tokenMaterialHelperInterfaceOwner
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        JvmPassBytecode.pushInt(insns, KEY_TRANSFER_CURSOR_INDEX_MASK);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, baseCursorLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        JvmPassBytecode.pushInt(insns, KEY_TRANSFER_CURSOR_MODE_SHIFT);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, modeLocal));
        emitKeyTransferRuntimeSourceCursor(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            baseCursorLocal,
            modeLocal,
            cursorLocal,
            sourceLocal,
            threadLocal,
            stackLocal,
            stackLengthLocal
        );
        emitKeyTransferMaterialDecodedWord(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            materialLocal,
            cursorLocal,
            KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED,
            tokenMaterialHelperOwner,
            tokenMaterialHelperName,
            tokenMaterialHelperInterfaceOwner
        );
    }

    private void installCompressedIslandMaterialHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            helperName,
            CFF_ISLAND_MATERIAL_HELPER_DESC,
            null,
            null
        );
        int keyLocal = 0;
        int guardLocal = 2;
        int pathLocal = 3;
        int blockLocal = 4;
        int materialLocal = 5;
        int sourceLocal = 6;
        int cursorLocal = 7;
        int wordLocal = 8;
        int entriesLocal = 9;
        int wordsLocal = 10;
        int valueLocal = 11;
        int maskLocal = 12;
        int classWordsLocal = 13;
        int modeLocal = 14;
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_CURSOR_MODE_SHIFT);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, modeLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_CURSOR_INDEX_MASK);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        emitCffIslandRuntimeSourceCursorFromLocal(
            insns,
            cursorLocal,
            modeLocal,
            sourceLocal
        );
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_MATERIAL_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, entriesLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, entriesLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, wordsLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, wordsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
        JvmPassBytecode.pushInt(insns, 0x119DE1F3);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, maskLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, classWordsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, maskLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classWordsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, maskLocal));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_TABLE_SIZE - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, maskLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, maskLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 15;
        helper.maxStack = 16;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        publishGeneratedHelperFlowKey(
            pctx,
            clazz.name(),
            helperName,
            CFF_ISLAND_MATERIAL_HELPER_DESC,
            keyLocal
        );
    }
    private void installCffIslandRuntimeSourceHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            helperName,
            CFF_ISLAND_RUNTIME_SOURCE_HELPER_DESC,
            null,
            null
        );
        int keyLocal = 0;
        int guardLocal = 2;
        int pathLocal = 3;
        int blockLocal = 4;
        int cursorLocal = 5;
        int modeLocal = 6;
        int sourceLocal = 7;
        int threadLocal = 8;
        int stackLocal = 9;
        int stackLengthLocal = 10;
        InsnList insns = helper.instructions;
        emitCffIslandRuntimeSourceCursor(
            insns,
            keyLocal,
            guardLocal,
            pathLocal,
            blockLocal,
            cursorLocal,
            modeLocal,
            sourceLocal,
            threadLocal,
            stackLocal,
            stackLengthLocal
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        insns.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 11;
        helper.maxStack = 10;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        publishGeneratedHelperFlowKey(
            pctx,
            clazz.name(),
            helperName,
            CFF_ISLAND_RUNTIME_SOURCE_HELPER_DESC,
            keyLocal
        );
    }


    private void emitCffIslandRuntimeSourceCursorFromLocal(
        InsnList insns,
        int cursorLocal,
        int modeLocal,
        int sourceLocal
    ) {
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, done));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_RUNTIME_SOURCE_BUCKETS - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        insns.add(done);
    }

    private void emitCffIslandCallsiteRuntimeSource(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int pcLocal,
        int domainLocal,
        int encodedCursor
    ) {
        int mode = encodedCursor >>> CFF_ISLAND_CURSOR_MODE_SHIFT;
        if (mode == CFF_ISLAND_RUNTIME_SOURCE_NONE) {
            JvmPassBytecode.pushInt(insns, 0);
            return;
        }
        int cursor = encodedCursor & CFF_ISLAND_CURSOR_INDEX_MASK;
        JvmPassBytecode.pushInt(
            insns,
            0x43464953 ^ (mode * 0x45D9F3B) ^ (cursor * 0x119DE1F3)
        );
        if ((mode & CFF_ISLAND_RUNTIME_SOURCE_THREAD) != 0) {
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Thread",
                "currentThread",
                "()Ljava/lang/Thread;",
                false
            ));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/System",
                "identityHashCode",
                "(Ljava/lang/Object;)I",
                false
            ));
            insns.add(new InsnNode(Opcodes.IXOR));
        }
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitCffIslandRuntimeSourceCursor(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int cursorLocal,
        int modeLocal,
        int sourceLocal,
        int threadLocal,
        int stackLocal,
        int stackLengthLocal
    ) {
        LabelNode computeSource = new LabelNode();
        LabelNode threadDone = new LabelNode();
        LabelNode stackDone = new LabelNode();
        LabelNode stackElementTwoDone = new LabelNode();
        LabelNode stackElementThreeDone = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNE, computeSource));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));

        insns.add(computeSource);
        JvmPassBytecode.pushInt(insns, 0x43464953);
        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_RUNTIME_SOURCE_THREAD);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, threadDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "identityHashCode",
            "(Ljava/lang/Object;)I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(threadDone);

        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_RUNTIME_SOURCE_STACK);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, stackDone));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getStackTrace",
            "()[Ljava/lang/StackTraceElement;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stackLengthLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, stackElementTwoDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(stackElementTwoDone);
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, stackElementThreeDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(stackElementThreeDone);
        insns.add(stackDone);

        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, CFF_ISLAND_RUNTIME_SOURCE_BUCKETS - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        insns.add(done);
    }

    private void installCompressedIslandMaterialUnpackHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            helperName,
            CFF_ISLAND_MATERIAL_UNPACK_HELPER_DESC,
            null,
            null
        );
        int chunksLocal = 0;
        int totalLocal = 1;
        int indexLocal = 2;
        int chunkLocal = 3;
        int offsetLocal = 4;
        int outLocal = 5;
        int wordLocal = 6;
        int byteLocal = 7;
        int valueLocal = 8;
        int lengthLocal = 9;
        LabelNode countLoop = new LabelNode();
        LabelNode countDone = new LabelNode();
        LabelNode outerLoop = new LabelNode();
        LabelNode returnLabel = new LabelNode();
        LabelNode innerLoop = new LabelNode();
        LabelNode nextChunk = new LabelNode();
        LabelNode skipStore = new LabelNode();
        InsnList insns = helper.instructions;

        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, totalLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(countLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, chunksLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, countDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, totalLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, chunksLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "length",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, totalLocal));
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, countLoop));

        insns.add(countDone);
        insns.add(new VarInsnNode(Opcodes.ILOAD, totalLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, wordLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, byteLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));

        insns.add(outerLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, chunksLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, returnLabel));
        insns.add(new VarInsnNode(Opcodes.ALOAD, chunksLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new VarInsnNode(Opcodes.ASTORE, chunkLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "length",
            "()I",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ISTORE, lengthLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, offsetLocal));

        insns.add(innerLoop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lengthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, nextChunk));
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        JvmPassBytecode.pushInt(insns, 8);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new VarInsnNode(Opcodes.ALOAD, chunkLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, offsetLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "charAt",
            "(I)C",
            false
        ));
        JvmPassBytecode.pushInt(insns, 0xFF);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(new IincInsnNode(byteLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, byteLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFNE, skipStore));
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new IincInsnNode(wordLocal, 1));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, valueLocal));
        insns.add(skipStore);
        insns.add(new IincInsnNode(offsetLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, innerLoop));

        insns.add(nextChunk);
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, outerLoop));
        insns.add(returnLabel);
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        insns.add(new InsnNode(Opcodes.ARETURN));
        helper.maxLocals = 10;
        helper.maxStack = 6;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
    }

    private void emitKeyTransferRuntimeSourceCursor(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int baseCursorLocal,
        int modeLocal,
        int cursorLocal,
        int sourceLocal,
        int threadLocal,
        int stackLocal,
        int stackLengthLocal
    ) {
        LabelNode computeSource = new LabelNode();
        LabelNode threadDone = new LabelNode();
        LabelNode stackDone = new LabelNode();
        LabelNode stackElementTwoDone = new LabelNode();
        LabelNode stackElementThreeDone = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNE, computeSource));
        insns.add(new VarInsnNode(Opcodes.ILOAD, baseCursorLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));

        insns.add(computeSource);
        JvmPassBytecode.pushInt(insns, 0x4B584653);
        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        JvmPassBytecode.pushInt(insns, KEY_TRANSFER_RUNTIME_SOURCE_THREAD);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, threadDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "identityHashCode",
            "(Ljava/lang/Object;)I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(threadDone);

        insns.add(new VarInsnNode(Opcodes.ILOAD, modeLocal));
        JvmPassBytecode.pushInt(insns, KEY_TRANSFER_RUNTIME_SOURCE_STACK);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, stackDone));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getStackTrace",
            "()[Ljava/lang/StackTraceElement;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stackLengthLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, stackElementTwoDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(stackElementTwoDone);
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLengthLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, stackElementThreeDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(stackElementThreeDone);
        insns.add(stackDone);

        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, KEY_TRANSFER_RUNTIME_SOURCE_BUCKETS - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        JvmPassBytecode.pushInt(insns, TOKEN_MATERIAL_ROW_LONGS * 2);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, baseCursorLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, cursorLocal));
        insns.add(done);
    }

    private void emitKeyTransferMaterialDecodedWord(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int materialLocal,
        int cursorLocal,
        long methodSeed,
        String tokenMaterialHelperOwner,
        String tokenMaterialHelperName,
        boolean tokenMaterialHelperInterfaceOwner
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            tokenMaterialHelperOwner,
            tokenMaterialHelperName,
            "([Ljava/lang/Object;IIII)I",
            tokenMaterialHelperInterfaceOwner
        ));
        emitMethodKeyFold(insns, keyLocal, methodSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitTransitionMaterialBase(
        InsnList insns,
        int objectMaterialLocal,
        String intHelperOwner,
        String intHelperName,
        boolean intHelperInterfaceOwner,
        int materialLocal,
        int rowLocal,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int baseLocal
    ) {
        JvmPassBytecode.pushInt(insns, 0);
        emitClassKeyWordsLoad(insns, objectMaterialLocal);
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_CLASS_INDEX
        );
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_CLASS_BLOCK
        );
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_CLASS_DIGEST
        );
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            intHelperOwner,
            intHelperName,
            "([IIIIIII)I",
            intHelperInterfaceOwner
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_PATH
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_BLOCK
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTransitionMaterialMethodKeyFold(
            insns,
            materialLocal,
            rowLocal,
            keyLocal
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_SHIFT
        );
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, baseLocal));
    }

    private void emitTransitionMaterialMethodKeyFold(
        InsnList insns,
        int materialLocal,
        int rowLocal,
        int keyLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_METHOD_HIGH
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_METHOD_ADD
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            TRANSITION_MATERIAL_BASE_METHOD_SHIFT
        );
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitTransitionMaterialDecodedWord(
        InsnList insns,
        int materialLocal,
        int rowLocal,
        int baseLocal,
        int word
    ) {
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            transitionMaterialWordOffset(word, TRANSITION_MATERIAL_ENCRYPTED)
        );
        emitTransitionMaterialMaskFromBase(
            insns,
            materialLocal,
            rowLocal,
            baseLocal,
            word
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitTransitionMaterialMaskFromBase(
        InsnList insns,
        int materialLocal,
        int rowLocal,
        int baseLocal,
        int word
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, baseLocal));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            transitionMaterialWordOffset(word, TRANSITION_MATERIAL_MASK)
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            transitionMaterialWordOffset(word, TRANSITION_MATERIAL_ADD)
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitTransitionMaterialWordLoad(
            insns,
            materialLocal,
            rowLocal,
            transitionMaterialWordOffset(word, TRANSITION_MATERIAL_SHIFT)
        );
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitTransitionMaterialWordLoad(
        InsnList insns,
        int materialLocal,
        int rowLocal,
        int offset
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rowLocal));
        JvmPassBytecode.pushInt(insns, offset);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IALOAD));
    }

    private void emitTokenMaterialWordLoad(InsnList insns, int rowLocal, int cursorLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, rowLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, cursorLocal));
        emitPackedMaterialWordLoad(insns);
        insns.add(new IincInsnNode(cursorLocal, 1));
    }

    private void emitPackedMaterialWordLoad(InsnList insns) {
        LabelNode lowWord = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFNE, lowWord));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.LALOAD));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(lowWord);
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(done);
    }

    private void emitTokenMaterialClassMask(
        InsnList insns,
        int materialLocal,
        int rowLocal,
        int baseLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, 63);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitTokenMaterialObjectMask(
        InsnList insns,
        int materialLocal,
        int rowLocal,
        int baseLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int indexLocal,
        int packedLocal,
        int epochLocal,
        int encodedLocal,
        int resultLocal,
        int nextEpochLocal,
        int nextEncodedLocal,
        int cellLocal,
        int currentMaskLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_TABLE_SIZE - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, materialLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/concurrent/atomic/AtomicLong"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cellLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cellLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicLong",
            "getPlain",
            "()J",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.LSTORE, packedLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, packedLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, epochLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, packedLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, encodedLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, epochLocal));
        emitCffObjectCellMask(insns);
        insns.add(new VarInsnNode(Opcodes.ISTORE, currentMaskLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, encodedLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, currentMaskLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, epochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, nextEpochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, encodedLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, currentMaskLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, nextEpochLocal));
        emitCffObjectCellMask(insns);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, nextEncodedLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, nextEncodedLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, nextEpochLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicLong",
            "setPlain",
            "(J)V",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
    }

    private void emitTokenMaterialControlMask(
        InsnList insns,
        int rowLocal,
        int baseLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        emitTokenMaterialWordLoad(insns, rowLocal, baseLocal);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int registerEncryptedTokenMaterial(
        CffClassKeyTable table,
        int encrypted,
        long seed
    ) {
        int index = table.tokenHelperCounter()[0]++;
        if (index >= TOKEN_MATERIAL_TABLE_SIZE) {
            throw new IllegalStateException(
                "CFF token material table exhausted for " + table.owner()
            );
        }
        long classSeed = seed ^ 0x434646434C544B31L;
        long objectSeed = seed ^ 0x4346464F544B31L;
        int[] values = new int[] {
            encrypted,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535449445831L)),
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C5354424C4B31L)) | 1,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535444494731L)),
            nonZeroInt(JvmPassBytecode.mix(objectSeed, 0x434C535449445831L)),
            nonZeroInt(JvmPassBytecode.mix(objectSeed, 0x434C5354424C4B31L)) | 1,
            nonZeroInt(JvmPassBytecode.mix(objectSeed, 0x434C535444494731L)),
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4346464F455031L)),
            shift(seed, 11),
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4346464F455032L)) | 1,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354504D31L)),
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D31L)),
            shift(seed, 9)
        };
        MethodNode initHelper = tokenMaterialInitHelper(
            table,
            index / TOKEN_MATERIAL_INIT_CHUNK_SIZE
        );
        InsnList init = new InsnList();
        emitPackedMaterialLongStores(init, 1, index * TOKEN_MATERIAL_ROW_LONGS, values);
        JvmKeyDispatchPass.markGenerated(table.pctx(), init);
        initHelper.instructions.insertBefore(initHelper.instructions.getLast(), init);
        initHelper.maxStack = Math.max(initHelper.maxStack, 4);
        table.clazz().markDirty();
        return index * TOKEN_MATERIAL_ROW_LONGS * 2;
    }

    private MethodNode tokenMaterialInitHelper(
        CffClassKeyTable table,
        int chunk
    ) {
        while (table.tokenMaterialInitHelpers().size() <= chunk) {
            int next = table.tokenMaterialInitHelpers().size();
            String desc = "([Ljava/lang/Object;)V";
            String helperName = uniqueMethodName(
                table.clazz(),
                "__neko_cff_tmat_init$" + Integer.toUnsignedString(next, 36),
                desc
            );
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            access |= table.interfaceOwner() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
            MethodNode helper = new MethodNode(access, helperName, desc, null, null);
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            JvmPassBytecode.pushInt(helper.instructions, TOKEN_MATERIAL_WORDS_SLOT);
            helper.instructions.add(new InsnNode(Opcodes.AALOAD));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
            helper.instructions.add(new InsnNode(Opcodes.RETURN));
            helper.maxLocals = 2;
            helper.maxStack = 2;
            table.tokenMaterialInitHelpers().add(helper);
            table.clazz().asmNode().methods.add(helper);

            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, table.initCarrierLocal()));
            call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.owner(),
                helperName,
                desc,
                table.interfaceOwner()
            ));
            JvmKeyDispatchPass.markGenerated(table.pctx(), call);
            MethodNode clinit = findOrCreateClassInit(table.clazz());
            clinit.instructions.insertBefore(table.initEnd(), call);
            clinit.maxStack = Math.max(clinit.maxStack, 1);
            table.clazz().markDirty();
        }
        return table.tokenMaterialInitHelpers().get(chunk);
    }

    private int registerTransitionMaterialRow(
        CffClassKeyTable table,
        int state,
        DispatchTarget target,
        EdgeKind edgeKind,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long stepSeed,
        EdgeRole role
    ) {
        int index = table.transitionMaterialCounter()[0]++;
        if (index >= TRANSITION_MATERIAL_TABLE_SIZE) {
            throw new IllegalStateException(
                "CFF transition material table exhausted for " + table.owner()
            );
        }
        int base = index * TRANSITION_MATERIAL_ROW_WORDS;
        int[] values = transitionMaterialValues(
            state,
            target,
            edgeKind,
            sourceKeys,
            targetKeys,
            methodSeed,
            stepSeed,
            role
        );
        MethodNode initHelper = transitionMaterialInitHelper(
            table,
            index / TRANSITION_MATERIAL_INIT_CHUNK_SIZE
        );
        InsnList init = new InsnList();
        for (int i = 0; i < values.length; i++) {
            init.add(new VarInsnNode(Opcodes.ALOAD, 1));
            JvmPassBytecode.pushInt(init, base + i);
            JvmPassBytecode.pushInt(init, values[i]);
            init.add(new InsnNode(Opcodes.IASTORE));
        }
        JvmKeyDispatchPass.markGenerated(table.pctx(), init);
        initHelper.instructions.insertBefore(initHelper.instructions.getLast(), init);
        initHelper.maxStack = Math.max(initHelper.maxStack, 3);
        table.clazz().markDirty();
        return index;
    }

    private void emitPackedMaterialLongStores(
        InsnList init,
        int arrayLocal,
        int base,
        int[] values
    ) {
        for (int i = 0; i < values.length; i += 2) {
            long packed = ((long) values[i] << 32);
            if (i + 1 < values.length) {
                packed |= ((long) values[i + 1]) & 0xffffffffL;
            }
            init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            JvmPassBytecode.pushInt(init, base + (i / 2));
            JvmPassBytecode.pushLong(init, packed);
            init.add(new InsnNode(Opcodes.LASTORE));
        }
    }

    private int[] transitionMaterialValues(
        int state,
        DispatchTarget target,
        EdgeKind edgeKind,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long stepSeed,
        EdgeRole role
    ) {
        int[] values = new int[TRANSITION_MATERIAL_ROW_WORDS];
        long baseSeed = transitionBaseSeed(stepSeed, role);
        long classSeed =
            (baseSeed ^ 0x4347434C41535331L) ^ 0x434646434C544B31L;
        values[TRANSITION_MATERIAL_BASE_CLASS_INDEX] =
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535449445831L));
        values[TRANSITION_MATERIAL_BASE_CLASS_BLOCK] =
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C5354424C4B31L)) | 1;
        values[TRANSITION_MATERIAL_BASE_CLASS_DIGEST] =
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535444494731L));
        values[TRANSITION_MATERIAL_BASE_PATH] =
            nonZeroInt(JvmPassBytecode.mix(baseSeed, 0x43475041544831L));
        values[TRANSITION_MATERIAL_BASE_BLOCK] =
            nonZeroInt(JvmPassBytecode.mix(baseSeed, 0x4347424C4F434B31L)) | 1;
        long methodFoldSeed = baseSeed ^ 0x43474D45544831L;
        values[TRANSITION_MATERIAL_BASE_METHOD_HIGH] =
            (int) (methodFoldSeed >>> 32);
        values[TRANSITION_MATERIAL_BASE_METHOD_ADD] =
            nonZeroInt(JvmPassBytecode.mix(methodFoldSeed, 0x4D4B464F4C4431L));
        values[TRANSITION_MATERIAL_BASE_METHOD_SHIFT] =
            shift(methodFoldSeed, 13);
        values[TRANSITION_MATERIAL_BASE_SHIFT] = shift(baseSeed, 11);
        int sourceBase = compactControlTokenBase(sourceKeys, baseSeed);
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_GUARD_WORD,
            targetKeys.guardKey(),
            sourceBase,
            stepSeed ^ 0x47554152444B31L ^ role.ordinal()
        );
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_PATH_WORD,
            targetKeys.pathKey(),
            sourceBase,
            stepSeed ^ 0x504154484B455931L ^ role.ordinal()
        );
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_BLOCK_WORD,
            targetKeys.blockKey(),
            sourceBase,
            stepSeed ^ 0x424C4F434B4B31L ^ role.ordinal()
        );
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_PC_WORD,
            targetKeys.pcToken(),
            sourceBase,
            target.selectorSeed() ^ state ^ 0x5043544F4B454E31L
        );
        long methodWordSeed =
            stepSeed ^ 0x4D45544844454331L ^ role.ordinal();
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_METHOD_HIGH_WORD,
            (int) (targetKeys.methodKey() >>> 32),
            sourceBase,
            methodWordSeed ^ 0x4849474831L
        );
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_METHOD_LOW_WORD,
            (int) targetKeys.methodKey(),
            sourceBase,
            methodWordSeed ^ 0x4C4F5731L
        );
        putTransitionMaterialWord(
            values,
            TRANSITION_MATERIAL_DOMAIN_WORD,
            target.domainToken(),
            sourceBase,
            target.domainSeed() ^ target.island() ^ 0x444F4D544F4B31L
        );
        return values;
    }

    private void putTransitionMaterialWord(
        int[] values,
        int word,
        int targetWord,
        int sourceBase,
        long seed
    ) {
        int offset = transitionMaterialWordOffset(word, 0);
        values[offset + TRANSITION_MATERIAL_ENCRYPTED] =
            targetWord ^ controlTokenMaskFromBase(sourceBase, seed);
        values[offset + TRANSITION_MATERIAL_MASK] =
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D534B31L));
        values[offset + TRANSITION_MATERIAL_ADD] =
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D414431L)) | 1;
        values[offset + TRANSITION_MATERIAL_SHIFT] = shift(seed, 13);
    }

    private int transitionMaterialWordOffset(int word, int part) {
        return TRANSITION_MATERIAL_WORDS_BASE +
            (word * TRANSITION_MATERIAL_WORD_STRIDE) +
            part;
    }

    private MethodNode transitionMaterialInitHelper(
        CffClassKeyTable table,
        int chunk
    ) {
        while (table.transitionMaterialInitHelpers().size() <= chunk) {
            int next = table.transitionMaterialInitHelpers().size();
            String desc = "([Ljava/lang/Object;)V";
            String helperName = uniqueMethodName(
                table.clazz(),
                "__neko_cff_xmat_init$" + Integer.toUnsignedString(next, 36),
                desc
            );
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            access |= table.interfaceOwner() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
            MethodNode helper = new MethodNode(access, helperName, desc, null, null);
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            JvmPassBytecode.pushInt(helper.instructions, TRANSITION_MATERIAL_SLOT);
            helper.instructions.add(new InsnNode(Opcodes.AALOAD));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
            helper.instructions.add(new InsnNode(Opcodes.RETURN));
            helper.maxLocals = 2;
            helper.maxStack = 2;
            table.transitionMaterialInitHelpers().add(helper);
            table.clazz().asmNode().methods.add(helper);

            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, table.initCarrierLocal()));
            call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.owner(),
                helperName,
                desc,
                table.interfaceOwner()
            ));
            JvmKeyDispatchPass.markGenerated(table.pctx(), call);
            MethodNode clinit = findOrCreateClassInit(table.clazz());
            clinit.instructions.insertBefore(table.initEnd(), call);
            clinit.maxStack = Math.max(clinit.maxStack, 1);
            table.clazz().markDirty();
        }
        return table.transitionMaterialInitHelpers().get(chunk);
    }

    private int registerStepMaterialRow(
        CffClassKeyTable table,
        long seed,
        EdgeRole role
    ) {
        int index = table.stepMaterialCounter()[0]++;
        if (index >= STEP_MATERIAL_TABLE_SIZE) {
            throw new IllegalStateException(
                "CFF step material table exhausted for " + table.owner()
            );
        }
        int[] values = stepMaterialValues(seed, role);
        MethodNode initHelper = stepMaterialInitHelper(
            table,
            (index * STEP_MATERIAL_ROW_LONGS) / STEP_MATERIAL_INIT_CHUNK_LONGS
        );
        InsnList init = new InsnList();
        emitPackedMaterialLongStores(
            init,
            1,
            index * STEP_MATERIAL_ROW_LONGS,
            values
        );
        JvmKeyDispatchPass.markGenerated(table.pctx(), init);
        initHelper.instructions.insertBefore(initHelper.instructions.getLast(), init);
        initHelper.maxStack = Math.max(initHelper.maxStack, 4);
        table.clazz().markDirty();
        return index;
    }

    private int[] stepMaterialValues(long seed, EdgeRole role) {
        int[] values = new int[STEP_MATERIAL_ROW_WORDS];
        long roleSeed = seed ^ ((long) role.ordinal() * 0x9E3779B97F4A7C15L);
        int firstIndex = selectStepKeyIndex(roleSeed);
        int firstSourceIndex = selectDifferentStepKeyIndex(
            firstIndex,
            roleSeed ^ 0x4653544B455931L
        );
        int firstOp = (int) ((roleSeed >>> 45) & 3L);
        int flags =
            firstIndex |
                (firstSourceIndex << 2) |
                (firstOp << 4);
        values[1] = nonZeroInt(JvmPassBytecode.mix(roleSeed, 0x54494E594B455931L));

        long secondSeed = JvmPassBytecode.mix(roleSeed, 0x5345434F4E444B31L);
        if (((roleSeed >>> 61) & 1L) != 0L) {
            if (((roleSeed >>> 59) & 1L) == 0L) {
                int secondIndex = selectDifferentStepKeyIndex(firstIndex, secondSeed);
                int secondSourceIndex = (((secondSeed >>> 23) & 1L) == 0L)
                    ? firstIndex
                    : selectDifferentStepKeyIndex(
                          secondIndex,
                          secondSeed ^ 0x5345435352434B31L
                      );
                int secondOp = (int) ((secondSeed >>> 45) & 3L);
                flags |=
                    (1 << 6) |
                        (secondIndex << 8) |
                        (secondSourceIndex << 10) |
                        (secondOp << 12);
                values[2] = nonZeroInt(
                    JvmPassBytecode.mix(secondSeed, 0x54494E594B455931L)
                );
            } else {
                int methodOp = (int) ((secondSeed >>> 51) & 3L);
                long methodConst = nonZeroLong(
                    JvmPassBytecode.mix(secondSeed, 0x4D4554484B455931L)
                );
                flags |= (1 << 7) | (firstIndex << 10) | (methodOp << 12);
                values[3] = (int) (methodConst >>> 32);
                values[4] = (int) methodConst;
            }
        }
        values[0] = flags;
        long maskSeed = JvmPassBytecode.mix(
            roleSeed ^ ((long) flags << 32),
            0x535445504D41534BL
        );
        values[5] = nonZeroInt(JvmPassBytecode.mix(maskSeed, 0x53544D41444431L));
        values[6] = nonZeroInt(JvmPassBytecode.mix(maskSeed, 0x53544D4D554C31L)) | 1;
        values[7] = shift(maskSeed, 9);
        int probeBase = stepMaterialDecodeBase(0L, 0, 0, 0, values[5], values[6], values[7]);
        for (int i = 0; i < 5; i++) {
            values[i] ^= stepMaterialWordMask(
                probeBase,
                values[5],
                values[6],
                values[7],
                i
            );
        }
        return values;
    }

    private int stepMaterialDecodeBase(
        long key,
        int guard,
        int path,
        int block,
        int add,
        int multiply,
        int shift
    ) {
        int x = (guard ^ path) + block;
        x ^= (int) key;
        x += (int) (key >>> 32);
        x ^= add;
        x *= multiply;
        x ^= x >>> shift;
        return x;
    }

    private int stepMaterialWordMask(
        int base,
        int add,
        int multiply,
        int shift,
        int word
    ) {
        int x = base ^ add;
        x += multiply * (word + 1);
        x ^= x >>> shift;
        x ^= 0x45D9F3B * (word + 1);
        return x;
    }

    private MethodNode stepMaterialInitHelper(
        CffClassKeyTable table,
        int chunk
    ) {
        while (table.stepMaterialInitHelpers().size() <= chunk) {
            int next = table.stepMaterialInitHelpers().size();
            String desc = "([Ljava/lang/Object;)V";
            String helperName = uniqueMethodName(
                table.clazz(),
                "__neko_cff_step_init$" + Integer.toUnsignedString(next, 36),
                desc
            );
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            access |= table.interfaceOwner() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
            MethodNode helper = new MethodNode(access, helperName, desc, null, null);
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            JvmPassBytecode.pushInt(helper.instructions, STEP_MATERIAL_SLOT);
            helper.instructions.add(new InsnNode(Opcodes.AALOAD));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
            helper.instructions.add(new InsnNode(Opcodes.RETURN));
            helper.maxLocals = 2;
            helper.maxStack = 2;
            table.stepMaterialInitHelpers().add(helper);
            table.clazz().asmNode().methods.add(helper);

            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, table.initCarrierLocal()));
            call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.owner(),
                helperName,
                desc,
                table.interfaceOwner()
            ));
            JvmKeyDispatchPass.markGenerated(table.pctx(), call);
            MethodNode clinit = findOrCreateClassInit(table.clazz());
            clinit.instructions.insertBefore(table.initEnd(), call);
            clinit.maxStack = Math.max(clinit.maxStack, 1);
            table.clazz().markDirty();
        }
        return table.stepMaterialInitHelpers().get(chunk);
    }

    private int registerCompressedIslandMaterialBlob(
        CffClassKeyTable table,
        CompressedIslandMaterialBlob blob,
        long seed
    ) {
        int runtimeSourceMode = cffIslandRuntimeSourceMode(blob.words().length);
        int bucketCount = cffIslandRuntimeSourceBucketCount(runtimeSourceMode);
        int index = table.islandMaterialCounter()[0];
        table.islandMaterialCounter()[0] += bucketCount;
        if (index + bucketCount > CFF_ISLAND_MATERIAL_TABLE_SIZE) {
            throw new IllegalStateException(
                "CFF island material table exhausted for " + table.owner()
            );
        }
        MethodNode initHelper = islandMaterialInitHelper(
            table,
            index / CFF_ISLAND_MATERIAL_INIT_CHUNK_SIZE
        );
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int bucketIndex = index + bucket;
            String[] chunks = encodeCompressedIslandMaterialBlob(table, blob, seed, bucketIndex);
            InsnList init = new InsnList();
            init.add(new VarInsnNode(Opcodes.ALOAD, 1));
            JvmPassBytecode.pushInt(init, bucketIndex);
            JvmPassBytecode.pushInt(init, chunks.length);
            init.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/String"));
            for (int i = 0; i < chunks.length; i++) {
                init.add(new InsnNode(Opcodes.DUP));
                JvmPassBytecode.pushInt(init, i);
                init.add(new LdcInsnNode(chunks[i]));
                init.add(new InsnNode(Opcodes.AASTORE));
            }
            init.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.islandMaterialUnpackHelperOwner(),
                table.islandMaterialUnpackHelperName(),
                CFF_ISLAND_MATERIAL_UNPACK_HELPER_DESC,
                table.islandMaterialUnpackHelperInterfaceOwner()
            ));
            init.add(new InsnNode(Opcodes.AASTORE));
            JvmKeyDispatchPass.markGenerated(table.pctx(), init);
            initHelper.instructions.insertBefore(initHelper.instructions.getLast(), init);
        }
        initHelper.maxStack = Math.max(initHelper.maxStack, 6);
        table.clazz().markDirty();
        return encodeCffIslandMaterialCursor(index, runtimeSourceMode);
    }

    private int cffIslandRuntimeSourceMode(int materialWords) {
        return CFF_ISLAND_RUNTIME_SOURCE_THREAD;
    }

    private int cffIslandRuntimeSourceBucketCount(int runtimeSourceMode) {
        return runtimeSourceMode == CFF_ISLAND_RUNTIME_SOURCE_NONE
            ? 1
            : CFF_ISLAND_RUNTIME_SOURCE_BUCKETS;
    }

    private int encodeCffIslandMaterialCursor(
        int cursor,
        int runtimeSourceMode
    ) {
        if ((cursor & ~CFF_ISLAND_CURSOR_INDEX_MASK) != 0) {
            throw new IllegalStateException(
                "CFF island material cursor exceeds encoded range: " + cursor
            );
        }
        return cursor | (runtimeSourceMode << CFF_ISLAND_CURSOR_MODE_SHIFT);
    }

    private String[] encodeCompressedIslandMaterialBlob(
        CffClassKeyTable table,
        CompressedIslandMaterialBlob blob,
        long seed,
        int cursor
    ) {
        int[] words = blob.words();
        CffBlockKeyState[] decodeStates = blob.decodeStates();
        List<String> chunks = new ArrayList<>();
        StringBuilder chunk = new StringBuilder(
            Math.min(CFF_ISLAND_COMPRESSED_BLOB_CHUNK_CHARS, Math.max(16, words.length * 4))
        );
        for (int wordIndex = 0; wordIndex < words.length; wordIndex++) {
            CffBlockKeyState decodeState = decodeStates[wordIndex];
            int mask = decodeState == null
                ? compressedIslandMaterialStaticMask(seed, wordIndex)
                : compressedIslandMaterialRuntimeMask(table, decodeState, cursor, wordIndex);
            int encrypted = words[wordIndex] ^ mask;
            for (int shift = 24; shift >= 0; shift -= 8) {
                if (chunk.length() >= CFF_ISLAND_COMPRESSED_BLOB_CHUNK_CHARS) {
                    chunks.add(chunk.toString());
                    chunk = new StringBuilder(CFF_ISLAND_COMPRESSED_BLOB_CHUNK_CHARS);
                }
                int encodedByte = (encrypted >>> shift) & 0xFF;
                chunk.append((char) encodedByte);
            }
        }
        chunks.add(chunk.toString());
        return chunks.toArray(new String[0]);
    }

    private int compressedIslandMaterialRuntimeMask(
        CffClassKeyTable table,
        CffBlockKeyState keyState,
        int cursor,
        int wordIndex
    ) {
        int mask = (keyState.guardKey() ^ keyState.pathKey()) + keyState.blockKey();
        mask ^= (int) keyState.methodKey();
        mask += (int) (keyState.methodKey() >>> 32);
        mask ^= cursor * 0x45D9F3B;
        mask += wordIndex * 0x119DE1F3;
        mask ^= table.values()[mask & (CLASS_KEY_TABLE_SIZE - 1)];
        mask ^= mask >>> 16;
        return mask;
    }

    private int compressedIslandMaterialStaticMask(long seed, int wordIndex) {
        long mixed = JvmPassBytecode.mix(
            seed ^ ((long) wordIndex * 0x9E3779B97F4A7C15L),
            0x434646494D41544CL ^ wordIndex
        );
        int mask = ((int) mixed) ^ ((int) (mixed >>> 32));
        mask ^= mask >>> 15;
        mask *= 0x45D9F3B;
        mask ^= mask >>> 16;
        return mask;
    }

    private void emitCompressedIslandMaterialWordDecode(
        InsnList insns,
        CffClassKeyTable table,
        int keyLocal,
        int guardLocal,
        int pathLocal,
        int blockLocal,
        int sourceLocal,
        int cursor,
        int wordIndex,
        int resultLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        JvmPassBytecode.pushInt(insns, cursor);
        JvmPassBytecode.pushInt(insns, wordIndex);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.islandMaterialHelperOwner(),
            table.islandMaterialHelperName(),
            CFF_ISLAND_MATERIAL_HELPER_DESC,
            table.islandMaterialHelperInterfaceOwner()
        ));
        insns.add(new VarInsnNode(Opcodes.ISTORE, resultLocal));
    }

    private static void addMaterialLong(List<Integer> words, long value) {
        words.add((int) (value >>> 32));
        words.add((int) value);
    }

    private static void addMaterialKeyState(
        List<Integer> words,
        CffBlockKeyState keyState
    ) {
        words.add(keyState.guardKey());
        words.add(keyState.pathKey());
        words.add(keyState.blockKey());
        words.add(keyState.pcToken());
        addMaterialLong(words, keyState.methodKey());
        addMaterialLong(words, keyState.methodSalt());
    }

    private static void addMaterialWords(List<Integer> words, int[] values) {
        for (int value : values) {
            words.add(value);
        }
    }

    private static void addLiveMaterialKeyState(
        List<Integer> words,
        Map<Integer, CffBlockKeyState> decodeStates,
        CffBlockKeyState keyState
    ) {
        addLiveDecodedMaterialWord(words, decodeStates, keyState, keyState.guardKey());
        addLiveDecodedMaterialWord(words, decodeStates, keyState, keyState.pathKey());
        addLiveDecodedMaterialWord(words, decodeStates, keyState, keyState.blockKey());
        addLiveDecodedMaterialWord(words, decodeStates, keyState, keyState.pcToken());
        addLiveDecodedMaterialWord(
            words,
            decodeStates,
            keyState,
            (int) (keyState.methodKey() >>> 32)
        );
        addLiveDecodedMaterialWord(words, decodeStates, keyState, (int) keyState.methodKey());
        addLiveDecodedMaterialWord(
            words,
            decodeStates,
            keyState,
            (int) (keyState.methodSalt() >>> 32)
        );
        addLiveDecodedMaterialWord(words, decodeStates, keyState, (int) keyState.methodSalt());
    }

    private static void addLiveDecodedMaterialWord(
        List<Integer> words,
        Map<Integer, CffBlockKeyState> decodeStates,
        CffBlockKeyState keyState,
        int value
    ) {
        decodeStates.put(words.size(), keyState);
        words.add(value);
    }

    private MethodNode islandMaterialInitHelper(
        CffClassKeyTable table,
        int chunk
    ) {
        while (table.islandMaterialInitHelpers().size() <= chunk) {
            int next = table.islandMaterialInitHelpers().size();
            String desc = "([Ljava/lang/Object;)V";
            String helperName = uniqueMethodName(
                table.clazz(),
                "__neko_cff_imat_init$" + Integer.toUnsignedString(next, 36),
                desc
            );
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            access |= table.interfaceOwner() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
            MethodNode helper = new MethodNode(access, helperName, desc, null, null);
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, 0));
            JvmPassBytecode.pushInt(helper.instructions, CFF_ISLAND_MATERIAL_SLOT);
            helper.instructions.add(new InsnNode(Opcodes.AALOAD));
            helper.instructions.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, 1));
            helper.instructions.add(new InsnNode(Opcodes.RETURN));
            helper.maxLocals = 2;
            helper.maxStack = 2;
            table.islandMaterialInitHelpers().add(helper);
            table.clazz().asmNode().methods.add(helper);

            InsnList call = new InsnList();
            call.add(new VarInsnNode(Opcodes.ALOAD, table.initCarrierLocal()));
            call.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.owner(),
                helperName,
                desc,
                table.interfaceOwner()
            ));
            JvmKeyDispatchPass.markGenerated(table.pctx(), call);
            MethodNode clinit = findOrCreateClassInit(table.clazz());
            clinit.instructions.insertBefore(table.initEnd(), call);
            clinit.maxStack = Math.max(clinit.maxStack, 1);
            table.clazz().markDirty();
        }
        return table.islandMaterialInitHelpers().get(chunk);
    }

    private void installMethodKeyFromStateHelper(
        PipelineContext pctx,
        L1Class clazz,
        String methodKeyHelperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            access,
            methodKeyHelperName,
            "(IIIIJJ)J",
            null,
            null
        );
        int guardLocal = 0;
        int pathLocal = 1;
        int blockLocal = 2;
        int pcLocal = 3;
        int saltMaskedLocal = 4;
        int saltMaskLocal = 6;
        LabelNode nonZero = new LabelNode();
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new VarInsnNode(Opcodes.LLOAD, saltMaskedLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, saltMaskLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        JvmPassBytecode.pushLong(insns, METHOD_KEY_PC_MIX);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.POP2));
        JvmPassBytecode.pushLong(insns, 0xD1B54A32D192ED03L);
        insns.add(nonZero);
        insns.add(new InsnNode(Opcodes.LRETURN));
        helper.maxLocals = 8;
        helper.maxStack = 8;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
    }

    private MethodNode findOrCreateClassInit(L1Class clazz) {
        MethodNode existing = findClassInit(clazz);
        if (existing != null) return existing;
        MethodNode clinit = new MethodNode(
            Opcodes.ACC_STATIC,
            "<clinit>",
            "()V",
            null,
            null
        );
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clinit.maxStack = 0;
        clinit.maxLocals = 0;
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private MethodNode findClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name)) return method;
        }
        return null;
    }

    private LabelNode protectedStartLabel(
        L1Class clazz,
        L1Method method,
        MethodNode mn,
        int keyLocal
    ) {
        if (method.isConstructor()) {
            AbstractInsnNode init = constructorInitInsn(clazz, mn);
            AbstractInsnNode next =
                init == null ? firstReal(mn) : nextReal(init.getNext());
            if (next == null) return null;
            return ensureLabelBefore(mn, next);
        }
        if (method.isClassInit()) {
            AbstractInsnNode afterKey = firstRealAfterKeyInit(mn, keyLocal);
            LabelNode tableEnd =
                activeKeyTable == null ? null : activeKeyTable.initEnd();
            AbstractInsnNode afterTable =
                tableEnd == null ? null : nextReal(tableEnd.getNext());
            if (afterKey == null) return afterTable == null
                ? null
                : ensureLabelBefore(mn, afterTable);
            if (afterTable == null) return ensureLabelBefore(mn, afterKey);
            return ensureLabelBefore(
                mn,
                before(afterKey, afterTable) ? afterTable : afterKey
            );
        }
        AbstractInsnNode first = firstRealAfterKeyInit(mn, keyLocal);
        return first == null ? null : ensureLabelBefore(mn, first);
    }

    private AbstractInsnNode firstRealAfterKeyInit(
        MethodNode mn,
        int keyLocal
    ) {
        for (
            AbstractInsnNode insn = mn.instructions.getFirst();
            insn != null;
            insn = insn.getNext()
        ) {
            if (
                insn instanceof VarInsnNode var &&
                var.getOpcode() == Opcodes.LSTORE &&
                var.var == keyLocal
            ) {
                AbstractInsnNode next = nextReal(insn.getNext());
                return next == null ? firstReal(mn) : next;
            }
        }
        return firstReal(mn);
    }

    @SuppressWarnings("unchecked")
    public static Map<String, CffMethodMetadata> methodMetadata(TransformContext ctx) {
        Map<String, CffMethodMetadata> metadata = ctx.getPassData(
            METHOD_METADATA
        );
        if (metadata == null) {
            metadata = new LinkedHashMap<>();
            ctx.putPassData(METHOD_METADATA, metadata);
        }
        return metadata;
    }

    private void publishMethodMetadata(
        PipelineContext pctx,
        L1Class clazz,
        L1Method method,
        long methodSeed,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        List<Block> blocks,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        CffClassKeyTable classKeyTable
    ) {
        Set<AbstractInsnNode> applicationInstructions =
            Collections.newSetFromMap(new IdentityHashMap<>());
        Map<AbstractInsnNode, CffInstructionState> instructionStates =
            new IdentityHashMap<>();
        for (int blockIndex = 0; blockIndex < blocks.size(); blockIndex++) {
            Block block = blocks.get(blockIndex);
            int state = requireState(
                block.label(),
                stateByLabel.get(block.label())
            );
            DispatchTarget target = dispatchByLabel.get(block.label());
            if (target == null) continue;
            CffBlockKeyState blockKeys = requireBlockKey(
                block.label(),
                keyStateByLabel.get(block.label())
            );
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                if (insn.getOpcode() < 0) continue;
                if (JvmKeyDispatchPass.isGeneratedNode(pctx, insn)) continue;
                applicationInstructions.add(insn);
                instructionStates.put(
                    insn,
                    new CffInstructionState(
                        blockIndex,
                        state,
                        target.selectorSeed(),
                        blockKeys.guardKey(),
                        blockKeys.pathKey(),
                        blockKeys.blockKey(),
                        blockKeys.pcToken(),
                        blockKeys.methodKey(),
                        blockKeys.methodSalt()
                    )
                );
            }
        }
        methodMetadata(pctx).put(
            JvmKeyDispatchPass.coverageKey(clazz, method),
            new CffMethodMetadata(
                methodSeed,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                applicationInstructions,
                instructionStates,
                classKeyTable
            )
        );
    }

    private AbstractInsnNode constructorInitInsn(L1Class clazz, MethodNode mn) {
        for (
            AbstractInsnNode insn = mn.instructions.getFirst();
            insn != null;
            insn = insn.getNext()
        ) {
            if (
                insn instanceof MethodInsnNode call &&
                call.getOpcode() == Opcodes.INVOKESPECIAL &&
                "<init>".equals(call.name) &&
                (clazz.name().equals(call.owner) ||
                    clazz.superName().equals(call.owner))
            ) {
                return insn;
            }
        }
        return null;
    }

    private Map<LabelNode, String> handlerReachableDomains(
        MethodNode mn,
        List<Block> blocks,
        Map<LabelNode, LabelNode> aliases,
        Set<LabelNode> handlerBodies
    ) {
        Set<LabelNode> blockLabels = Collections.newSetFromMap(
            new IdentityHashMap<>()
        );
        Map<LabelNode, Block> byLabel = new IdentityHashMap<>();
        Map<LabelNode, LabelNode> nextByLabel = new IdentityHashMap<>();
        LabelNode normalEntry = null;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (!block.handler() && normalEntry == null) {
                normalEntry = block.label();
            }
            blockLabels.add(block.label());
            byLabel.put(block.label(), block);
            if (i + 1 < blocks.size()) {
                nextByLabel.put(block.label(), blocks.get(i + 1).label());
            }
        }
        Set<LabelNode> normalReachable = reachableFrom(
            mn,
            normalEntry,
            blockLabels,
            byLabel,
            nextByLabel,
            aliases
        );

        Map<LabelNode, String> domains = new IdentityHashMap<>();
        List<LabelNode> bodies = new ArrayList<>(handlerBodies);
        for (
            int handlerIndex = 0;
            handlerIndex < bodies.size();
            handlerIndex++
        ) {
            String token = "H" + handlerIndex;
            Set<LabelNode> reachable = Collections.newSetFromMap(
                new IdentityHashMap<>()
            );
            List<LabelNode> work = new ArrayList<>();
            LabelNode canonical = canonicalLabel(
                bodies.get(handlerIndex),
                aliases
            );
            if (blockLabels.contains(canonical) && reachable.add(canonical)) {
                addHandlerDomain(domains, canonical, token);
                work.add(canonical);
            }
            for (int i = 0; i < work.size(); i++) {
                LabelNode label = work.get(i);
                Block block = byLabel.get(label);
                if (block == null) continue;
                for (LabelNode successor : blockSuccessors(mn, block, nextByLabel)) {
                    canonical = canonicalLabel(successor, aliases);
                    if (
                        blockLabels.contains(canonical) &&
                        !normalReachable.contains(canonical) &&
                        reachable.add(canonical)
                    ) {
                        addHandlerDomain(domains, canonical, token);
                        work.add(canonical);
                    }
                }
            }
        }
        return domains;
    }

    private void addHandlerDomain(
        Map<LabelNode, String> domains,
        LabelNode label,
        String token
    ) {
        String existing = domains.get(label);
        domains.put(label, existing == null ? token : existing + "," + token);
    }

    private Set<LabelNode> reachableFrom(
        MethodNode mn,
        LabelNode start,
        Set<LabelNode> blockLabels,
        Map<LabelNode, Block> byLabel,
        Map<LabelNode, LabelNode> nextByLabel,
        Map<LabelNode, LabelNode> aliases
    ) {
        Set<LabelNode> reachable = Collections.newSetFromMap(
            new IdentityHashMap<>()
        );
        if (start == null) return reachable;
        List<LabelNode> work = new ArrayList<>();
        LabelNode canonicalStart = canonicalLabel(start, aliases);
        if (
            blockLabels.contains(canonicalStart) &&
            reachable.add(canonicalStart)
        ) {
            work.add(canonicalStart);
        }
        for (int i = 0; i < work.size(); i++) {
            LabelNode label = work.get(i);
            Block block = byLabel.get(label);
            if (block == null) continue;
            for (LabelNode successor : blockSuccessors(mn, block, nextByLabel)) {
                LabelNode canonical = canonicalLabel(successor, aliases);
                if (
                    blockLabels.contains(canonical) &&
                    reachable.add(canonical)
                ) {
                    work.add(canonical);
                }
            }
        }
        return reachable;
    }

    private List<LabelNode> blockSuccessors(
        MethodNode mn,
        Block block,
        Map<LabelNode, LabelNode> nextByLabel
    ) {
        AbstractInsnNode last = lastRealBefore(mn, block.endExclusive());
        if (last == null || terminates(last.getOpcode())) {
            return Collections.emptyList();
        }
        List<LabelNode> successors = new ArrayList<>();
        if (last instanceof JumpInsnNode jump) {
            successors.add(jump.label);
            if (last.getOpcode() != Opcodes.GOTO) {
                LabelNode next = nextByLabel.get(block.label());
                if (next != null) successors.add(next);
            }
        } else if (last instanceof LookupSwitchInsnNode ls) {
            successors.add(ls.dflt);
            successors.addAll(ls.labels);
        } else if (last instanceof TableSwitchInsnNode ts) {
            successors.add(ts.dflt);
            successors.addAll(ts.labels);
        } else {
            LabelNode next = nextByLabel.get(block.label());
            if (next != null) successors.add(next);
        }
        return successors;
    }

    private LabelNode canonicalLabel(
        LabelNode label,
        Map<LabelNode, LabelNode> aliases
    ) {
        LabelNode current = label;
        Set<LabelNode> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        while (current != null && seen.add(current)) {
            LabelNode next = aliases.get(current);
            if (next == null || next == current) break;
            current = next;
        }
        return current == null ? label : current;
    }

    private void completeBlockLabelAliases(
        MethodNode mn,
        LabelNode start,
        List<Block> blocks,
        Map<LabelNode, LabelNode> aliases
    ) {
        Set<LabelNode> blockLabels = Collections.newSetFromMap(
            new IdentityHashMap<>()
        );
        Map<AbstractInsnNode, LabelNode> firstRealOwners = new IdentityHashMap<>();
        for (Block block : blocks) {
            blockLabels.add(block.label());
            AbstractInsnNode first = nextReal(block.label());
            if (first != null) firstRealOwners.put(first, block.label());
        }
        for (Block block : blocks) {
            for (
                AbstractInsnNode scan = block.label();
                scan != null && scan.getOpcode() < 0;
                scan = scan.getPrevious()
            ) {
                if (scan instanceof LabelNode label && label != block.label()) {
                    aliases.put(label, block.label());
                }
            }
            for (
                AbstractInsnNode scan = block.label();
                scan != null && scan.getOpcode() < 0;
                scan = scan.getNext()
            ) {
                if (scan instanceof LabelNode label && label != block.label()) {
                    aliases.put(label, block.label());
                }
            }
        }
        for (AbstractInsnNode scan = start; scan != null; scan = scan.getNext()) {
            if (!(scan instanceof LabelNode label) || blockLabels.contains(label)) {
                continue;
            }
            if (aliases.containsKey(label)) continue;
            AbstractInsnNode first = nextReal(label.getNext());
            LabelNode owner = first == null ? null : firstRealOwners.get(first);
            if (owner != null) aliases.put(label, owner);
        }
        for (Map.Entry<LabelNode, LabelNode> alias : new ArrayList<>(aliases.entrySet())) {
            LabelNode canonical = canonicalLabel(alias.getValue(), aliases);
            if (canonical != null && canonical != alias.getKey()) {
                aliases.put(alias.getKey(), canonical);
            }
        }
    }

    private boolean isZeroStackLabel(
        LabelNode label,
        Set<LabelNode> zeroStackLabels
    ) {
        if (zeroStackLabels.contains(label)) return true;
        for (
            AbstractInsnNode scan = label.getPrevious();
            scan != null && scan.getOpcode() < 0;
            scan = scan.getPrevious()
        ) {
            if (scan instanceof LabelNode alias && zeroStackLabels.contains(alias)) {
                return true;
            }
        }
        for (
            AbstractInsnNode scan = label.getNext();
            scan != null && scan.getOpcode() < 0;
            scan = scan.getNext()
        ) {
            if (scan instanceof LabelNode alias && zeroStackLabels.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    private BlockPlan buildBlocks(
        MethodNode mn,
        LabelNode start,
        Set<LabelNode> extraLeaders,
        Set<LabelNode> zeroStackLabels,
        Set<LabelNode> linearLeaders,
        CffFrameAnalysis frames
    ) {
        Set<AbstractInsnNode> leaders = new HashSet<>();
        leaders.add(start);
        leaders.addAll(extraLeaders);
        leaders.addAll(linearLeaders);
        Set<LabelNode> handlerLabels = new HashSet<>();
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                if (frames.isZeroStack(tcb.start)) {
                    leaders.add(tcb.start);
                }
                leaders.add(tcb.handler);
                handlerLabels.add(tcb.handler);
            }
        }
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn instanceof JumpInsnNode jump) {
                AbstractInsnNode next = nextReal(insn.getNext());
                boolean targetZero = isZeroStackLabel(jump.label, zeroStackLabels);
                if (jump.getOpcode() == Opcodes.GOTO) {
                    if (targetZero) leaders.add(jump.label);
                } else if (
                    targetZero && frames.isZeroStack(next)
                ) {
                    leaders.add(jump.label);
                    leaders.add(ensureLabelBefore(mn, next));
                }
            } else if (insn instanceof TableSwitchInsnNode ts) {
                if (allSwitchTargetsZero(ts.dflt, ts.labels, zeroStackLabels)) {
                    leaders.add(ts.dflt);
                    leaders.addAll(ts.labels);
                }
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                if (allSwitchTargetsZero(ls.dflt, ls.labels, zeroStackLabels)) {
                    leaders.add(ls.dflt);
                    leaders.addAll(ls.labels);
                }
            } else if (terminates(insn.getOpcode())) {
                AbstractInsnNode next = nextReal(insn.getNext());
                if (frames.isZeroStack(next)) leaders.add(
                    ensureLabelBefore(mn, next)
                );
            }
        }
        List<LabelNode> ordered = new ArrayList<>();
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn instanceof LabelNode label && leaders.contains(label)) {
                ordered.add(label);
            }
        }
        List<Block> blocks = new ArrayList<>();
        Map<LabelNode, LabelNode> aliases = new IdentityHashMap<>();
        Map<Integer, LabelNode> canonicalByIndex = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            LabelNode label = ordered.get(i);
            AbstractInsnNode endExclusive =
                i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            if (hasRealInstruction(label, endExclusive)) {
                blocks.add(
                    new Block(
                        label,
                        endExclusive,
                        handlerLabels.contains(label)
                    )
                );
                canonicalByIndex.put(i, label);
            }
        }
        LabelNode nextCanonical = null;
        for (int i = ordered.size() - 1; i >= 0; i--) {
            LabelNode label = ordered.get(i);
            LabelNode canonical = canonicalByIndex.get(i);
            if (canonical != null) {
                nextCanonical = canonical;
            } else if (nextCanonical != null) {
                aliases.put(label, nextCanonical);
            }
        }
        return new BlockPlan(blocks, aliases);
    }

    private boolean hasRealInstruction(
        LabelNode label,
        AbstractInsnNode endExclusive
    ) {
        for (
            AbstractInsnNode insn = label;
            insn != null && insn != endExclusive;
            insn = insn.getNext()
        ) {
            if (insn.getOpcode() >= 0) return true;
        }
        return false;
    }

    private boolean useSubdispatcherOutliner(
        MethodNode mn,
        List<Block> blocks,
        List<HandlerBridge> handlerBridges
    ) {
        return useOutliner(
            mn,
            blocks,
            handlerBridges,
            DISPATCH_OUTLINER_BLOCK_THRESHOLD,
            DISPATCH_OUTLINER_EDGE_THRESHOLD,
            DISPATCH_OUTLINER_HANDLER_THRESHOLD,
            DISPATCH_OUTLINER_ESTIMATED_CODE_PRESSURE
        );
    }

    private boolean useTransitionOutliner(
        MethodNode mn,
        List<Block> blocks,
        List<HandlerBridge> handlerBridges
    ) {
        if (
            hasBackwardBlockEdge(mn, blocks) &&
            estimatedOutlinerCodePressure(mn, blocks, handlerBridges) >= DISPATCH_OUTLINER_ESTIMATED_CODE_PRESSURE
        ) {
            return true;
        }
        return useOutliner(
            mn,
            blocks,
            handlerBridges,
            TRANSITION_OUTLINER_BLOCK_THRESHOLD,
            TRANSITION_OUTLINER_EDGE_THRESHOLD,
            TRANSITION_OUTLINER_HANDLER_THRESHOLD,
            TRANSITION_OUTLINER_ESTIMATED_CODE_PRESSURE
        );
    }

    private boolean useOutliner(
        MethodNode mn,
        List<Block> blocks,
        List<HandlerBridge> handlerBridges,
        int blockThreshold,
        int edgeThreshold,
        int handlerThreshold,
        int codePressureThreshold
    ) {
        int nonHandlerBlocks = 0;
        int estimatedEdges = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            nonHandlerBlocks++;
            AbstractInsnNode last = lastRealBefore(mn, block.endExclusive());
            if (last instanceof LookupSwitchInsnNode ls) {
                estimatedEdges += 1 + ls.labels.size();
            } else if (last instanceof TableSwitchInsnNode ts) {
                estimatedEdges += 1 + ts.labels.size();
            } else if (last instanceof JumpInsnNode jump) {
                estimatedEdges += jump.getOpcode() == Opcodes.GOTO ? 1 : 2;
            } else if (last != null && !terminates(last.getOpcode()) && i + 1 < blocks.size()) {
                estimatedEdges++;
            }
        }
        int protectedHandlerCost = handlerBridges.size() * 3;
        int codeBytes = JvmCodeSizeEstimator.estimateMethodBytes(mn);
        int sizePressure = codeBytes + estimatedEdges * 220 + protectedHandlerCost * 180;
        return nonHandlerBlocks >= blockThreshold ||
            estimatedEdges >= edgeThreshold ||
            handlerBridges.size() >= handlerThreshold ||
            sizePressure >= codePressureThreshold;
    }

    private int estimatedOutlinerCodePressure(
        MethodNode mn,
        List<Block> blocks,
        List<HandlerBridge> handlerBridges
    ) {
        int estimatedEdges = 0;
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            AbstractInsnNode last = lastRealBefore(mn, block.endExclusive());
            if (last instanceof LookupSwitchInsnNode ls) {
                estimatedEdges += 1 + ls.labels.size();
            } else if (last instanceof TableSwitchInsnNode ts) {
                estimatedEdges += 1 + ts.labels.size();
            } else if (last instanceof JumpInsnNode jump) {
                estimatedEdges += jump.getOpcode() == Opcodes.GOTO ? 1 : 2;
            } else if (last != null && !terminates(last.getOpcode()) && i + 1 < blocks.size()) {
                estimatedEdges++;
            }
        }
        return JvmCodeSizeEstimator.estimateMethodBytes(mn) +
            estimatedEdges * 220 +
            handlerBridges.size() * 3 * 180;
    }

    private int smallTokenDispatchCaseLimit(
        MethodNode mn,
        List<Block> blocks,
        List<HandlerBridge> handlerBridges
    ) {
        return estimatedOutlinerCodePressure(mn, blocks, handlerBridges) >=
            LARGE_METHOD_TOKEN_DISPATCH_CODE_PRESSURE
            ? LARGE_METHOD_SMALL_TOKEN_DISPATCH_CASES
            : SMALL_TOKEN_DISPATCH_CASES;
    }

    private boolean hasBackwardBlockEdge(MethodNode mn, List<Block> blocks) {
        Map<LabelNode, Integer> blockIndex = new IdentityHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            blockIndex.put(blocks.get(i).label(), i);
        }
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            AbstractInsnNode last = lastRealBefore(mn, block.endExclusive());
            if (last instanceof JumpInsnNode jump) {
                if (isBackwardBlockTarget(blockIndex, i, jump.label)) return true;
            } else if (last instanceof LookupSwitchInsnNode lookup) {
                if (isBackwardBlockTarget(blockIndex, i, lookup.dflt)) return true;
                for (LabelNode label : lookup.labels) {
                    if (isBackwardBlockTarget(blockIndex, i, label)) return true;
                }
            } else if (last instanceof TableSwitchInsnNode table) {
                if (isBackwardBlockTarget(blockIndex, i, table.dflt)) return true;
                for (LabelNode label : table.labels) {
                    if (isBackwardBlockTarget(blockIndex, i, label)) return true;
                }
            }
        }
        return false;
    }

    private boolean isBackwardBlockTarget(
        Map<LabelNode, Integer> blockIndex,
        int sourceIndex,
        LabelNode target
    ) {
        Integer targetIndex = blockIndex.get(target);
        return targetIndex != null && targetIndex <= sourceIndex;
    }

    private Set<LabelNode> linearZeroStackLeaders(
        MethodNode mn,
        LabelNode start,
        CffFrameAnalysis frames
    ) {
        Set<LabelNode> leaders = new HashSet<>();
        boolean active = false;
        for (
            AbstractInsnNode insn = mn.instructions.getFirst();
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn == start) active = true;
            if (!active || insn.getOpcode() < 0 || isControlTransfer(insn)) {
                continue;
            }
            AbstractInsnNode next = nextReal(insn.getNext());
            if (frames.isZeroStack(next)) {
                leaders.add(ensureLabelBefore(mn, next));
            }
        }
        return leaders;
    }

    private boolean normalizeNonZeroStackControlTargets(
        PipelineContext pctx,
        MethodNode mn,
        LabelNode start,
        CffFrameAnalysis frames
    ) {
        Map<LabelNode, StackSpill> spills = new IdentityHashMap<>();
        Map<String, StackSpill> spillsByShape = new LinkedHashMap<>();
        Map<AbstractInsnNode, EdgeStackSpill> outgoingSpills = new IdentityHashMap<>();

        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            EdgeTargets targets = controlEdgeTargets(mn, insn);
            if (targets.labels().isEmpty()) continue;
            StackSpill edgeSpill = null;
            for (EdgeTarget edgeTarget : targets.labels()) {
                List<BasicValue> stack = frames.stackValues(edgeTarget.framePoint());
                if (stack.isEmpty()) continue;
                StackSpill spill = spillForStackShape(mn, stack, spillsByShape);
                spills.put(edgeTarget.label(), spill);
                if (edgeSpill == null) {
                    edgeSpill = spill;
                } else if (edgeSpill != spill) {
                    throw new IllegalStateException(
                        "CFF cannot normalize divergent non-empty stack edge shapes in " +
                            mn.name +
                            mn.desc
                    );
                }
            }
            if (edgeSpill != null) {
                outgoingSpills.put(
                    insn,
                    new EdgeStackSpill(edgeSpill, targets.consumedValues())
                );
            }
        }

        if (spills.isEmpty()) {
            return false;
        }

        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            EdgeStackSpill edgeSpill = outgoingSpills.get(insn);
            if (edgeSpill == null) continue;
            InsnList stores = spillStoresBeforeControl(
                mn,
                insn,
                edgeSpill.spill(),
                edgeSpill.consumedValues()
            );
            JvmKeyDispatchPass.markGenerated(pctx, stores);
            mn.instructions.insertBefore(insn, stores);
        }

        for (Map.Entry<LabelNode, StackSpill> entry : spills.entrySet()) {
            LabelNode target = entry.getKey();
            StackSpill spill = entry.getValue();
            AbstractInsnNode previous = previousReal(target.getPrevious());
            if (previous != null && !isControlTransfer(previous)) {
                InsnList stores = spillStores(spill);
                JvmKeyDispatchPass.markGenerated(pctx, stores);
                mn.instructions.insertBefore(target, stores);
            }
            InsnList loads = spillLoads(spill);
            JvmKeyDispatchPass.markGenerated(pctx, loads);
            AbstractInsnNode real = nextReal(target.getNext());
            if (real == null) {
                mn.instructions.add(loads);
            } else {
                mn.instructions.insertBefore(real, loads);
            }
        }
        mn.maxStack += Math.max(1, maxSpillSlots(spills));
        return true;
    }

    private EdgeTargets controlEdgeTargets(
        MethodNode mn,
        AbstractInsnNode insn
    ) {
        int opcode = insn.getOpcode();
        if (insn instanceof JumpInsnNode jump) {
            List<EdgeTarget> labels = new ArrayList<>();
            labels.add(new EdgeTarget(jump.label, jump.label));
            if (opcode != Opcodes.GOTO) {
                AbstractInsnNode next = nextReal(insn.getNext());
                if (next != null) {
                    labels.add(new EdgeTarget(ensureLabelBefore(mn, next), next));
                }
            }
            return new EdgeTargets(labels, consumedStackValueCount(opcode));
        }
        if (insn instanceof LookupSwitchInsnNode ls) {
            List<EdgeTarget> labels = new ArrayList<>();
            labels.add(new EdgeTarget(ls.dflt, ls.dflt));
            for (LabelNode label : ls.labels) {
                labels.add(new EdgeTarget(label, label));
            }
            return new EdgeTargets(labels, 1);
        }
        if (insn instanceof TableSwitchInsnNode ts) {
            List<EdgeTarget> labels = new ArrayList<>();
            labels.add(new EdgeTarget(ts.dflt, ts.dflt));
            for (LabelNode label : ts.labels) {
                labels.add(new EdgeTarget(label, label));
            }
            return new EdgeTargets(labels, 1);
        }
        return new EdgeTargets(List.of(), 0);
    }

    private int consumedStackValueCount(int opcode) {
        return switch (opcode) {
            case Opcodes.GOTO -> 0;
            case Opcodes.IFEQ,
                Opcodes.IFNE,
                Opcodes.IFLT,
                Opcodes.IFGE,
                Opcodes.IFGT,
                Opcodes.IFLE,
                Opcodes.IFNULL,
                Opcodes.IFNONNULL -> 1;
            case Opcodes.IF_ICMPEQ,
                Opcodes.IF_ICMPNE,
                Opcodes.IF_ICMPLT,
                Opcodes.IF_ICMPGE,
                Opcodes.IF_ICMPGT,
                Opcodes.IF_ICMPLE,
                Opcodes.IF_ACMPEQ,
                Opcodes.IF_ACMPNE -> 2;
            default -> 0;
        };
    }

    private StackSpill spillForStackShape(
        MethodNode mn,
        List<BasicValue> stack,
        Map<String, StackSpill> spillsByShape
    ) {
        String signature = stackShapeSignature(stack);
        StackSpill existing = spillsByShape.get(signature);
        if (existing != null) return existing;
        StackSpill spill = allocateStackSpill(mn, stack);
        spillsByShape.put(signature, spill);
        return spill;
    }

    private String stackShapeSignature(List<BasicValue> stack) {
        StringBuilder signature = new StringBuilder();
        for (BasicValue value : stack) {
            Type type = value.getType();
            signature
                .append(value.getSize())
                .append(':')
                .append(type == null ? "?" : type.getDescriptor())
                .append(';');
        }
        return signature.toString();
    }

    private InsnList spillStoresBeforeControl(
        MethodNode mn,
        AbstractInsnNode control,
        StackSpill spill,
        int consumedValues
    ) {
        BranchOperand[] operands = branchOperands(control, consumedValues);
        int[] operandLocals = allocateOperandLocals(mn, operands);
        InsnList insns = new InsnList();
        for (int i = operands.length - 1; i >= 0; i--) {
            insns.add(new VarInsnNode(operands[i].storeOpcode(), operandLocals[i]));
        }
        insns.add(spillStores(spill));
        for (int i = 0; i < operands.length; i++) {
            insns.add(new VarInsnNode(operands[i].loadOpcode(), operandLocals[i]));
        }
        return insns;
    }

    private BranchOperand[] branchOperands(AbstractInsnNode control, int consumedValues) {
        int opcode = control.getOpcode();
        if (consumedValues == 0) return new BranchOperand[0];
        if (control instanceof LookupSwitchInsnNode || control instanceof TableSwitchInsnNode) {
            return new BranchOperand[] { BranchOperand.INT };
        }
        return switch (opcode) {
            case Opcodes.IFEQ,
                Opcodes.IFNE,
                Opcodes.IFLT,
                Opcodes.IFGE,
                Opcodes.IFGT,
                Opcodes.IFLE -> new BranchOperand[] { BranchOperand.INT };
            case Opcodes.IFNULL,
                Opcodes.IFNONNULL -> new BranchOperand[] { BranchOperand.REF };
            case Opcodes.IF_ICMPEQ,
                Opcodes.IF_ICMPNE,
                Opcodes.IF_ICMPLT,
                Opcodes.IF_ICMPGE,
                Opcodes.IF_ICMPGT,
                Opcodes.IF_ICMPLE -> new BranchOperand[] {
                    BranchOperand.INT,
                    BranchOperand.INT,
                };
            case Opcodes.IF_ACMPEQ,
                Opcodes.IF_ACMPNE -> new BranchOperand[] {
                    BranchOperand.REF,
                    BranchOperand.REF,
                };
            default -> throw new IllegalStateException(
                "CFF cannot normalize unsupported non-empty stack branch opcode: " +
                    opcode
            );
        };
    }

    private int[] allocateOperandLocals(MethodNode mn, BranchOperand[] operands) {
        int[] locals = new int[operands.length];
        int nextLocal = mn.maxLocals;
        for (int i = 0; i < operands.length; i++) {
            locals[i] = nextLocal;
            nextLocal += operands[i].size();
        }
        mn.maxLocals = Math.max(mn.maxLocals, nextLocal);
        return locals;
    }

    private StackSpill allocateStackSpill(MethodNode mn, List<BasicValue> stack) {
        int[] locals = new int[stack.size()];
        int nextLocal = mn.maxLocals;
        for (int i = 0; i < stack.size(); i++) {
            BasicValue value = stack.get(i);
            locals[i] = nextLocal;
            nextLocal += Math.max(1, value.getSize());
        }
        mn.maxLocals = Math.max(mn.maxLocals, nextLocal);
        return new StackSpill(List.copyOf(stack), locals);
    }

    private InsnList spillStores(StackSpill spill) {
        InsnList insns = new InsnList();
        for (int i = spill.values().size() - 1; i >= 0; i--) {
            insns.add(new VarInsnNode(storeOpcode(spill.values().get(i)), spill.locals()[i]));
        }
        return insns;
    }

    private InsnList spillLoads(StackSpill spill) {
        InsnList insns = new InsnList();
        for (int i = 0; i < spill.values().size(); i++) {
            insns.add(new VarInsnNode(loadOpcode(spill.values().get(i)), spill.locals()[i]));
        }
        return insns;
    }

    private int maxSpillSlots(Map<LabelNode, StackSpill> spills) {
        int max = 0;
        Set<StackSpill> seen = Collections.newSetFromMap(new IdentityHashMap<StackSpill, Boolean>());
        for (StackSpill spill : spills.values()) {
            if (!seen.add(spill)) continue;
            int slots = 0;
            for (BasicValue value : spill.values()) {
                slots += Math.max(1, value.getSize());
            }
            max = Math.max(max, slots);
        }
        return max;
    }

    private int storeOpcode(BasicValue value) {
        return typedOpcode(value, Opcodes.ISTORE);
    }

    private int loadOpcode(BasicValue value) {
        return typedOpcode(value, Opcodes.ILOAD);
    }

    private int typedOpcode(BasicValue value, int baseOpcode) {
        Type type = value.getType();
        if (type == null) {
            return baseOpcode;
        }
        return type.getOpcode(baseOpcode);
    }

    private boolean allSwitchTargetsZero(
        LabelNode dflt,
        List<LabelNode> labels,
        Set<LabelNode> zeroStackLabels
    ) {
        if (!isZeroStackLabel(dflt, zeroStackLabels)) return false;
        for (LabelNode label : labels) {
            if (!isZeroStackLabel(label, zeroStackLabels)) return false;
        }
        return true;
    }

    private void insertHandlerBridges(
        MethodNode mn,
        List<HandlerBridge> handlerBridges,
        int exceptionLocal,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        int methodSeedLocal,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        Set<LabelNode> runtimeKeyLabels,
        long methodSeed,
        long salt,
        TransitionOutliner dispatcherOutliner,
        TransitionOutliner transitionOutliner
    ) {
        if (handlerBridges.isEmpty()) return;
        for (HandlerBridge bridge : handlerBridges) {
            LabelNode handler = bridge.handler();
            LabelNode body = bridge.body();
            Integer bodyState = labelValue(stateByLabel, body);
            DispatchTarget bodyTarget = requireTarget(body, labelValue(dispatchByLabel, body));
            long edgeSeed = edgeSeed(salt, handler, body, 0x45584348414E444CL);
            InsnList prefix = new InsnList();
            prefix.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
            prefix.add(new VarInsnNode(Opcodes.LLOAD, methodSeedLocal));
            prefix.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
            emitInitKeys(
                prefix,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyLocal,
                methodSeed ^ salt,
                keyTmpLocal
            );
            CffBlockKeyState initialHandlerKeys = initialKeyState(methodSeed, methodSeed ^ salt);
            emitEncryptedToken(
                prefix,
                initialHandlerKeys.pcToken(),
                initialHandlerKeys,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                edgeSeed ^ 0x48494E4954504331L,
                keyTmpLocal
            );
            prefix.add(new VarInsnNode(Opcodes.ISTORE, pcLocal));
            emitStoreMethodKey(
                prefix,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                initialHandlerKeys
            );
            CffBlockKeyState handlerSourceKeys = syntheticHandlerSourceKey(
                methodSeed,
                salt,
                bridge.handler()
            );
            emitDecodeBlockKeys(
                prefix,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                keyTmpLocal,
                keyTmpLocal + 3,
                initialHandlerKeys,
                handlerSourceKeys,
                methodSeed,
                edgeSeed ^ 0x48414E444C455249L,
                EdgeRole.HANDLER
            );
            emitEncryptedToken(
                prefix,
                handlerSourceKeys.pcToken(),
                handlerSourceKeys,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                edgeSeed ^ 0x48414E44504331L,
                keyTmpLocal
            );
            prefix.add(new VarInsnNode(Opcodes.ISTORE, pcLocal));
            emitStoreMethodKey(
                prefix,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                handlerSourceKeys
            );
            if (bridge.catchLocal() >= 0) {
                prefix.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
                prefix.add(new VarInsnNode(Opcodes.ASTORE, bridge.catchLocal()));
            }
            emitStoreDomain(
                prefix,
                domainLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyLocal,
                bodyTarget.island(),
                bodyTarget.domainToken(),
                handlerSourceKeys,
                methodSeed,
                bodyTarget.domainSeed(),
                keyTmpLocal
            );
            if (dispatcherOutliner != null) {
                emitInitTransitionOut(prefix, dispatcherOutliner.outLocal());
            }
            prefix.add(
                transition(
                    requireState(body, bodyState),
                    bodyTarget,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    handlerSourceKeys,
                    requireBlockKey(body, labelValue(keyStateByLabel, body)),
                    methodSeed,
                    edgeSeed,
                    runtimeKeyLabels.contains(body),
                    EdgeRole.HANDLER,
                    transitionOutliner
                )
            );
            mn.instructions.insert(handler, prefix);
        }
    }

    private void insertIslandDispatchers(
        MethodNode mn,
        List<Block> blocks,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        int methodSeedLocal,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        DispatchPlan dispatchPlan,
        int exceptionLocal,
        boolean externalEntrySeed,
        long methodSeed,
        long salt,
        int smallTokenDispatchCases,
        TransitionOutliner dispatcherOutliner,
        TransitionOutliner transitionOutliner
    ) {
        for (IslandGroup group : dispatchPlan.groups()) {
            Block entryBlock = group.blocks().get(0);
            InsnList insns = new InsnList();
            LabelNode poison = new LabelNode();

            if (entryBlock == firstNonHandler(blocks)) {
                if (dispatcherOutliner != null) {
                    emitInitTransitionOut(insns, dispatcherOutliner.outLocal());
                }
                if (exceptionLocal >= 0) {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
                }
                DispatchTarget entryTarget = requireTarget(
                    entryBlock.label(),
                    dispatchPlan.targets().get(entryBlock.label())
                );
                long entrySeed = entryInitSeed(
                    group.salt(),
                    externalEntrySeed,
                    methodSeed
                );
                emitInitKeys(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    entrySeed,
                    keyTmpLocal
                );
                if (methodSeedLocal >= 0) {
                    insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, methodSeedLocal));
                }
                emitStorePc(
                    insns,
                    pcLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    requireState(
                        entryBlock.label(),
                        stateByLabel.get(entryBlock.label())
                    ),
                    requireBlockKey(
                        entryBlock.label(),
                        keyStateByLabel.get(entryBlock.label())
                    ),
                    methodSeed,
                    entryTarget.selectorSeed(),
                    keyTmpLocal
                );
                CffBlockKeyState entryKeys = requireBlockKey(
                    entryBlock.label(),
                    keyStateByLabel.get(entryBlock.label())
                );
                emitStoreMethodKey(
                    insns,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    entryKeys
                );
                emitStoreDomain(
                    insns,
                    domainLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    entryTarget.island(),
                    entryTarget.domainToken(),
                    entryKeys,
                    methodSeed,
                    entryTarget.domainSeed(),
                    keyTmpLocal
                );
                if (entryTarget.islandLabels().length == 1) {
                    insns.add(
                        new JumpInsnNode(
                            Opcodes.GOTO,
                            entryTarget.islandLabels()[entryTarget.island()]
                        )
                    );
                } else {
                    insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                }
            }

            for (int alias = 0; alias < group.aliasHubs().length; alias++) {
                insns.add(
                    aliasHub(
                        group,
                        alias,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal
                    )
                );
            }
            insns.add(group.hub());
            emitDomainDispatch(
                insns,
                domainLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                group,
                poison
            );
            for (
                int island = 0;
                island < group.islandLabels().length;
                island++
            ) {
                insns.add(
                    buildIslandDispatcher(
                        group,
                        stateByLabel,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        poison,
                        island,
                        keyStateByLabel,
                        methodSeed,
                        salt,
                        smallTokenDispatchCases,
                        dispatcherOutliner,
                        transitionOutliner
                    )
                );
            }
            if (dispatcherOutliner != null) {
                insns.add(
                    dispatcherOutliner.emitResultRouter(
                        group,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        poison
                    )
                );
            }
            insns.add(poison);
            long poisonSeed = edgeSeed(
                salt,
                entryBlock.label(),
                entryBlock.label(),
                0x504F49534F4E4B31L
            );
            emitStepKeys(
                insns,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                poisonSeed,
                EdgeRole.POISON
            );
            insns.add(
                new org.objectweb.asm.tree.TypeInsnNode(
                    Opcodes.NEW,
                    "java/lang/IllegalStateException"
                )
            );
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/IllegalStateException",
                    "<init>",
                    "()V",
                    false
                )
            );
            insns.add(new InsnNode(Opcodes.ATHROW));
            mn.instructions.insertBefore(entryBlock.label(), insns);
        }
    }

    private void rewriteBlockExit(
        MethodNode mn,
        Block block,
        LabelNode next,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        int keyLocal,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        Set<LabelNode> runtimeKeyLabels,
        long methodSeed,
        long salt,
        TransitionOutliner transitionOutliner
    ) {
        AbstractInsnNode last = lastRealBefore(mn, block.endExclusive());
        if (last == null || before(last, block.label())) return;
        int opcode = last.getOpcode();
        if (terminates(opcode)) return;

        if (last instanceof JumpInsnNode jump) {
            if (opcode == Opcodes.GOTO) {
                Integer targetState = labelValue(stateByLabel, jump.label);
                DispatchTarget target = labelValue(dispatchByLabel, jump.label);
                if (targetState == null || target == null) {
                    throw new IllegalStateException(
                        "CFF goto target has no dispatch state in " +
                            mn.name +
                            mn.desc +
                            ": " +
                            jump.label.getLabel()
                    );
                }
                mn.instructions.insertBefore(
                    last,
                    transition(
                        targetState,
                        target,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        requireBlockKey(jump.label, labelValue(keyStateByLabel, jump.label)),
                        methodSeed,
                        edgeSeed(salt, block.label(), jump.label, opcode),
                        runtimeKeyLabels.contains(jump.label),
                        EdgeRole.GOTO,
                        transitionOutliner
                    )
                );
                mn.instructions.remove(last);
                return;
            }
            Integer targetState = labelValue(stateByLabel, jump.label);
            Integer fallthroughState =
                next == null ? null : labelValue(stateByLabel, next);
            DispatchTarget target = labelValue(dispatchByLabel, jump.label);
            DispatchTarget fallthrough =
                next == null ? null : labelValue(dispatchByLabel, next);
            if (next == null) {
                throw new IllegalStateException(
                    "CFF conditional block has no verifier-safe fallthrough target"
                );
            }
            long trueSeed = edgeSeed(
                salt,
                block.label(),
                jump.label,
                opcode ^ 0x54525545
            );
            long falseSeed = edgeSeed(
                salt,
                block.label(),
                next,
                opcode ^ 0x46534C53
            );
            if ((trueSeed & 1L) == 0L) {
                LabelNode taken = new LabelNode();
                mn.instructions.insertBefore(
                    last,
                    new JumpInsnNode(opcode, taken)
                );
                mn.instructions.insertBefore(
                    last,
                    transition(
                        requireState(next, fallthroughState),
                        requireTarget(next, fallthrough),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        requireBlockKey(next, labelValue(keyStateByLabel, next)),
                        methodSeed,
                        falseSeed,
                        runtimeKeyLabels.contains(next),
                        EdgeRole.CONDITIONAL_FALSE,
                        transitionOutliner
                    )
                );
                mn.instructions.insertBefore(last, taken);
                mn.instructions.insertBefore(
                    last,
                    transition(
                        requireState(jump.label, targetState),
                        requireTarget(jump.label, target),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        requireBlockKey(jump.label, labelValue(keyStateByLabel, jump.label)),
                        methodSeed,
                        trueSeed,
                        runtimeKeyLabels.contains(jump.label),
                        EdgeRole.CONDITIONAL_TRUE,
                        transitionOutliner
                    )
                );
            } else {
                LabelNode fallthroughLabel = new LabelNode();
                mn.instructions.insertBefore(
                    last,
                    new JumpInsnNode(invertJumpOpcode(opcode), fallthroughLabel)
                );
                mn.instructions.insertBefore(
                    last,
                    transition(
                        requireState(jump.label, targetState),
                        requireTarget(jump.label, target),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        requireBlockKey(jump.label, labelValue(keyStateByLabel, jump.label)),
                        methodSeed,
                        trueSeed,
                        runtimeKeyLabels.contains(jump.label),
                        EdgeRole.CONDITIONAL_TRUE,
                        transitionOutliner
                    )
                );
                mn.instructions.insertBefore(last, fallthroughLabel);
                mn.instructions.insertBefore(
                    last,
                    transition(
                        requireState(next, fallthroughState),
                        requireTarget(next, fallthrough),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        domainLocal,
                        keyTmpLocal,
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        requireBlockKey(next, labelValue(keyStateByLabel, next)),
                        methodSeed,
                        falseSeed,
                        runtimeKeyLabels.contains(next),
                        EdgeRole.CONDITIONAL_FALSE,
                        transitionOutliner
                    )
                );
            }
            mn.instructions.remove(last);
            return;
        }
        if (last instanceof LookupSwitchInsnNode ls) {
            rewriteLookupSwitch(
                mn,
                ls,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                stateByLabel,
                keyStateByLabel,
                dispatchByLabel,
                runtimeKeyLabels,
                block.label(),
                methodSeed,
                salt,
                transitionOutliner
            );
            return;
        }
        if (last instanceof TableSwitchInsnNode ts) {
            rewriteTableSwitch(
                mn,
                ts,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                stateByLabel,
                keyStateByLabel,
                dispatchByLabel,
                runtimeKeyLabels,
                block.label(),
                methodSeed,
                salt,
                transitionOutliner
            );
            return;
        }
        if (next != null) {
            Integer nextState = labelValue(stateByLabel, next);
            DispatchTarget nextTarget = labelValue(dispatchByLabel, next);
            mn.instructions.insert(
                last,
                transition(
                    requireState(next, nextState),
                    requireTarget(next, nextTarget),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                    requireBlockKey(next, labelValue(keyStateByLabel, next)),
                    methodSeed,
                    edgeSeed(salt, block.label(), next, 0x46414C4C),
                    runtimeKeyLabels.contains(next),
                    EdgeRole.FALLTHROUGH,
                    transitionOutliner
                )
            );
        }
    }

    private void rewriteLookupSwitch(
        MethodNode mn,
        LookupSwitchInsnNode ls,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        Set<LabelNode> runtimeKeyLabels,
        LabelNode source,
        long methodSeed,
        long salt,
        TransitionOutliner transitionOutliner
    ) {
        LabelNode defaultSet = new LabelNode();
        List<LabelNode> setLabels = new ArrayList<>();
        for (int i = 0; i < ls.labels.size(); i++) setLabels.add(
            new LabelNode()
        );
        List<LabelNode> originalTargets = new ArrayList<>(ls.labels);
        LabelNode originalDefault = ls.dflt;
        ls.labels.clear();
        ls.labels.addAll(setLabels);
        ls.dflt = defaultSet;
        InsnList tail = new InsnList();
        tail.add(defaultSet);
        tail.add(
            transition(
                requireState(
                    originalDefault,
                    labelValue(stateByLabel, originalDefault)
                ),
                requireTarget(
                    originalDefault,
                    labelValue(dispatchByLabel, originalDefault)
                ),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                requireBlockKey(source, keyStateByLabel.get(source)),
                requireBlockKey(originalDefault, labelValue(keyStateByLabel, originalDefault)),
                methodSeed,
                edgeSeed(salt, source, originalDefault, 0x53574446),
                runtimeKeyLabels.contains(originalDefault),
                EdgeRole.SWITCH_DEFAULT,
                transitionOutliner
            )
        );
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(
                transition(
                    requireState(
                        originalTarget,
                        labelValue(stateByLabel, originalTarget)
                    ),
                    requireTarget(
                        originalTarget,
                        labelValue(dispatchByLabel, originalTarget)
                    ),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    requireBlockKey(source, keyStateByLabel.get(source)),
                    requireBlockKey(originalTarget, labelValue(keyStateByLabel, originalTarget)),
                    methodSeed,
                    edgeSeed(
                        salt,
                        source,
                        originalTarget,
                        ls.keys.get(i) ^ 0x53574C53
                    ),
                    runtimeKeyLabels.contains(originalTarget),
                    EdgeRole.SWITCH_CASE,
                    transitionOutliner
                )
            );
        }
        mn.instructions.insert(ls, tail);
    }

    private void rewriteTableSwitch(
        MethodNode mn,
        TableSwitchInsnNode ts,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        Set<LabelNode> runtimeKeyLabels,
        LabelNode source,
        long methodSeed,
        long salt,
        TransitionOutliner transitionOutliner
    ) {
        LabelNode defaultSet = new LabelNode();
        List<LabelNode> setLabels = new ArrayList<>();
        for (int i = 0; i < ts.labels.size(); i++) setLabels.add(
            new LabelNode()
        );
        List<LabelNode> originalTargets = new ArrayList<>(ts.labels);
        LabelNode originalDefault = ts.dflt;
        ts.labels.clear();
        ts.labels.addAll(setLabels);
        ts.dflt = defaultSet;
        InsnList tail = new InsnList();
        tail.add(defaultSet);
        tail.add(
            transition(
                requireState(
                    originalDefault,
                    labelValue(stateByLabel, originalDefault)
                ),
                requireTarget(
                    originalDefault,
                    labelValue(dispatchByLabel, originalDefault)
                ),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                requireBlockKey(source, keyStateByLabel.get(source)),
                requireBlockKey(originalDefault, labelValue(keyStateByLabel, originalDefault)),
                methodSeed,
                edgeSeed(salt, source, originalDefault, 0x54534446),
                runtimeKeyLabels.contains(originalDefault),
                EdgeRole.SWITCH_DEFAULT,
                transitionOutliner
            )
        );
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(
                transition(
                    requireState(
                        originalTarget,
                        labelValue(stateByLabel, originalTarget)
                    ),
                    requireTarget(
                        originalTarget,
                        labelValue(dispatchByLabel, originalTarget)
                    ),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    requireBlockKey(source, keyStateByLabel.get(source)),
                    requireBlockKey(originalTarget, labelValue(keyStateByLabel, originalTarget)),
                    methodSeed,
                    edgeSeed(
                        salt,
                        source,
                        originalTarget,
                        (ts.min + i) ^ 0x54534C53
                    ),
                    runtimeKeyLabels.contains(originalTarget),
                    EdgeRole.SWITCH_CASE,
                    transitionOutliner
                )
            );
        }
        mn.instructions.insert(ts, tail);
    }

    private <T> T labelValue(Map<LabelNode, T> values, LabelNode label) {
        T value = values.get(label);
        if (value != null) return value;
        for (
            AbstractInsnNode scan = label.getPrevious();
            scan != null && scan.getOpcode() < 0;
            scan = scan.getPrevious()
        ) {
            if (scan instanceof LabelNode alias) {
                value = values.get(alias);
                if (value != null) return value;
            }
        }
        for (
            AbstractInsnNode scan = label.getNext();
            scan != null && scan.getOpcode() < 0;
            scan = scan.getNext()
        ) {
            if (scan instanceof LabelNode alias) {
                value = values.get(alias);
                if (value != null) return value;
            }
        }
        return null;
    }

    private int requireState(LabelNode target, Integer state) {
        if (state == null) {
            throw new IllegalStateException(
                "CFF target has no state: " + target.getLabel()
            );
        }
        return state;
    }

    private DispatchTarget requireTarget(
        LabelNode label,
        DispatchTarget target
    ) {
        if (target == null) {
            StackTraceElement caller = Thread.currentThread().getStackTrace().length > 2
                ? Thread.currentThread().getStackTrace()[2]
                : null;
            throw new IllegalStateException(
                "CFF target has no dispatch target at " +
                    (caller == null ? "<unknown>" : caller.getMethodName() + ":" + caller.getLineNumber()) +
                    ": " +
                    label.getLabel()
            );
        }
        return target;
    }

    private CffBlockKeyState requireBlockKey(
        LabelNode label,
        CffBlockKeyState keyState
    ) {
        if (keyState == null) {
            throw new IllegalStateException(
                "CFF target has no key state: " + label.getLabel()
            );
        }
        return keyState;
    }

    private InsnList transition(
        int state,
        DispatchTarget target,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long edgeSeed,
        boolean updateGuard,
        EdgeRole role,
        TransitionOutliner transitionOutliner
    ) {
        long stepSeed = transitionKeySeed(edgeSeed, state, target, role);
        EdgeKind edgeKind = chooseEdgeKind(edgeSeed, role, target);
        LabelNode jumpTarget = transitionJumpTarget(target, edgeKind, edgeSeed);
        if (transitionOutliner != null) {
            return transitionOutliner.emitCall(
                state,
                target,
                edgeKind,
                jumpTarget,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                sourceKeys,
                targetKeys,
                methodSeed,
                stepSeed,
                role
            );
        }
        InsnList insns = new InsnList();
        emitTransitionCore(
            insns,
            state,
            target,
            edgeKind,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            keyTmpLocal,
            sourceKeys,
            targetKeys,
            methodSeed,
            stepSeed,
            role,
            updateGuard
        );
        insns.add(new JumpInsnNode(Opcodes.GOTO, jumpTarget));
        return insns;
    }

    private LabelNode transitionJumpTarget(
        DispatchTarget target,
        EdgeKind edgeKind,
        long edgeSeed
    ) {
        return switch (edgeKind) {
            case DIRECT_ISLAND -> target.islandLabels()[target.island()];
            case ALIAS_HUB -> selectAliasHub(target, edgeSeed);
            case HUB -> target.hub();
        };
    }

    private void emitTransitionCore(
        InsnList insns,
        int state,
        DispatchTarget target,
        EdgeKind edgeKind,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long stepSeed,
        EdgeRole role,
        boolean updateGuard
    ) {
        emitDecodeBlockKeys(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            keyTmpLocal,
            keyTmpLocal + 3,
            sourceKeys,
            targetKeys,
            methodSeed,
            stepSeed,
            role
        );
        long transitionBaseSeed = transitionBaseSeed(stepSeed, role);
        emitStoreTransitionBaseToken(
            insns,
            pcLocal,
            targetKeys.pcToken(),
            sourceKeys,
            keyTmpLocal + 3,
            transitionBaseSeed,
            target.selectorSeed() ^ state ^ 0x5043544F4B454E31L
        );
        emitStoreMethodKeyFromBase(
            insns,
            keyLocal,
            keyTmpLocal + 3,
            sourceKeys,
            targetKeys,
            transitionBaseSeed,
            stepSeed ^ 0x4D45544844454331L ^ role.ordinal()
        );
        if (edgeKind != EdgeKind.DIRECT_ISLAND) {
            emitStoreTransitionBaseToken(
                insns,
                domainLocal,
                target.domainToken(),
                sourceKeys,
                keyTmpLocal + 3,
                transitionBaseSeed,
                target.domainSeed() ^ target.island() ^ 0x444F4D544F4B31L
            );
        }
    }

    private InsnList buildIslandDispatcher(
        IslandGroup group,
        Map<LabelNode, Integer> stateByLabel,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        LabelNode poison,
        int island,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long methodSeed,
        long salt,
        int smallTokenDispatchCases,
        TransitionOutliner dispatcherOutliner,
        TransitionOutliner transitionOutliner
    ) {
        InsnList insns = new InsnList();
        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        Map<LabelNode, LabelNode> stubs = new IdentityHashMap<>();
        List<LabelNode> fakes = new ArrayList<>();
        List<Block> islandBlocks = new ArrayList<>();
        int firstState = 0;
        boolean first = true;
        long dispatchSeed = tokenDispatchSeed(group, island, keyStateByLabel);
        for (Block block : group.blocks()) {
            Integer blockIsland = group.islands().get(block.label());
            if (blockIsland != null && blockIsland == island) {
                islandBlocks.add(block);
                LabelNode stub = new LabelNode();
                stubs.put(stub, block.label());
                int state = requireState(
                    block.label(),
                    stateByLabel.get(block.label())
                );
                CffBlockKeyState blockKeys = requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
                cases.put(
                    maskedDispatchToken(
                        blockKeys.pcToken(),
                        blockKeys,
                        dispatchSeed
                    ),
                    stub
                );
                if (first) {
                    firstState = state;
                    first = false;
                }
            }
        }
        if (first) return insns;
        int fakeCount = fakeCaseCount(group.salt() ^ salt ^ island);
        if (dispatcherOutliner != null) {
            return dispatcherOutliner.emitIslandDispatchCall(
                group,
                island,
                islandBlocks,
                firstState,
                fakeCount,
                dispatchSeed,
                stateByLabel,
                keyStateByLabel,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                poison,
                methodSeed,
                salt
            );
        }
        for (int fakeIndex = 0; fakeIndex < fakeCount; fakeIndex++) {
            LabelNode fake = new LabelNode();
            fakes.add(fake);
            int fakeState = fakeState(
                salt,
                firstState ^ island ^ (fakeIndex * 0x45D9F3B)
            );
            while (cases.containsKey(fakeState)) {
                fakeState = fakeState(
                    salt ^ 0x9E3779B97F4A7C15L,
                    fakeState + fakeIndex + 1
                );
            }
            int fakeToken = fakeDispatchToken(group.salt(), fakeState, island, fakeIndex);
            while (cases.containsKey(fakeToken)) {
                fakeToken = nonZeroInt(JvmPassBytecode.mix(fakeToken, fakeIndex + 1L));
            }
            cases.put(fakeToken, fake);
        }
        insns.add(group.islandLabels()[island]);
        emitTokenDispatch(
            insns,
            pcLocal,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            cases,
            poison,
            dispatchSeed,
            keyTmpLocal,
            smallTokenDispatchCases
        );
        for (Map.Entry<LabelNode, LabelNode> stub : stubs.entrySet()) {
            insns.add(stub.getKey());
            insns.add(new JumpInsnNode(Opcodes.GOTO, stub.getValue()));
        }
        for (int fakeIndex = 0; fakeIndex < fakes.size(); fakeIndex++) {
            insns.add(fakes.get(fakeIndex));
            long fakeSeed = edgeSeed(
                salt,
                group.hub(),
                group.islandLabels()[island],
                0x46414B4549534C45L ^ island ^ fakeIndex
            );
            emitStepKeys(
                insns,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                fakeSeed,
                EdgeRole.FAKE
            );
            emitFakeCaseBounce(
                insns,
                group,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                firstState,
                island,
                keyStateByLabel,
                methodSeed,
                fakeSeed,
                transitionOutliner
            );
        }
        return insns;
    }

    private void emitFakeCaseBounce(
        InsnList insns,
        IslandGroup group,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyTmpLocal,
        int state,
        int island,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long methodSeed,
        long seed,
        TransitionOutliner transitionOutliner
    ) {
        LabelNode hop = new LabelNode();
        LabelNode pass = new LabelNode();
        CffBlockKeyState bounceKeys = firstIslandKeyState(
            group,
            island,
            keyStateByLabel
        );
        DispatchTarget bounceTarget = new DispatchTarget(
            group.hub(),
            group.islandLabels(),
            group.aliasHubs(),
            island,
            group.salt() ^ island,
            domainSeed(group),
            domainToken(group.salt(), island)
        );
        long domainSeed = domainSeed(group);
        if (transitionOutliner != null) {
            long fakeStepSeed = transitionKeySeed(seed, state, bounceTarget, EdgeRole.FAKE);
            EdgeKind fakeEdgeKind = chooseEdgeKind(seed, EdgeRole.FAKE, bounceTarget);
            LabelNode jumpTarget = transitionJumpTarget(bounceTarget, fakeEdgeKind, seed);
            insns.add(
                transitionOutliner.emitCall(
                    state,
                    bounceTarget,
                    fakeEdgeKind,
                    jumpTarget,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    bounceKeys,
                    bounceKeys,
                    methodSeed,
                    fakeStepSeed,
                    EdgeRole.FAKE
                )
            );
            return;
        }
        switch ((int) ((seed >>> 37) & 3L)) {
            case 0 -> {
                emitStorePc(
                    insns,
                    pcLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    state,
                    bounceKeys,
                    methodSeed,
                    bounceTarget.selectorSeed(),
                    keyTmpLocal
                );
                emitStoreMethodKey(
                    insns,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    bounceKeys
                );
                emitStoreDomain(
                    insns,
                    domainLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    island,
                    bounceTarget.domainToken(),
                    bounceKeys,
                    methodSeed,
                    domainSeed,
                    keyTmpLocal
                );
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 1 -> {
                emitStorePc(
                    insns,
                    pcLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    state,
                    bounceKeys,
                    methodSeed,
                    bounceTarget.selectorSeed(),
                    keyTmpLocal
                );
                emitStoreMethodKey(
                    insns,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    bounceKeys
                );
                insns.add(new JumpInsnNode(Opcodes.GOTO, hop));
                insns.add(hop);
                emitStoreDomain(
                    insns,
                    domainLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    island,
                    bounceTarget.domainToken(),
                    bounceKeys,
                    methodSeed,
                    domainSeed,
                    keyTmpLocal
                );
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 2 -> {
                emitStoreDomain(
                    insns,
                    domainLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    island,
                    bounceTarget.domainToken(),
                    bounceKeys,
                    methodSeed,
                    domainSeed,
                    keyTmpLocal
                );
                emitStorePc(
                    insns,
                    pcLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    state,
                    bounceKeys,
                    methodSeed,
                    bounceTarget.selectorSeed(),
                    keyTmpLocal
                );
                emitStoreMethodKey(
                    insns,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    bounceKeys
                );
                emitKeyPredicate(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    seed
                );
                insns.add(new JumpInsnNode(Opcodes.IFNE, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            default -> {
                emitStorePc(
                    insns,
                    pcLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    state,
                    bounceKeys,
                    methodSeed,
                    bounceTarget.selectorSeed(),
                    keyTmpLocal
                );
                emitStoreMethodKey(
                    insns,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    bounceKeys
                );
                emitKeyPredicate(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    seed ^ 0x504154484F504151L
                );
                insns.add(new JumpInsnNode(Opcodes.IFEQ, hop));
                emitStoreDomain(
                    insns,
                    domainLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    island,
                    bounceTarget.domainToken(),
                    bounceKeys,
                    methodSeed,
                    domainSeed,
                    keyTmpLocal
                );
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(hop);
                emitStoreDomain(
                    insns,
                    domainLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    island,
                    bounceTarget.domainToken(),
                    bounceKeys,
                    methodSeed,
                    domainSeed,
                    keyTmpLocal
                );
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
        }
    }

    private long caseSelectorSeed(
        IslandGroup group,
        LabelNode label,
        int state,
        int island
    ) {
        long seed = JvmPassBytecode.mix(
            group.salt() ^ 0x4341534553454C31L ^ island,
            state
        );
        seed = JvmPassBytecode.mix(seed, System.identityHashCode(label));
        return seed == 0L ? group.salt() ^ 0x53454C45435431L : seed;
    }

    private long fakeCaseSelectorSeed(
        IslandGroup group,
        int fakeState,
        int island,
        int fakeIndex
    ) {
        long seed = JvmPassBytecode.mix(
            group.salt() ^ 0x46414B4553454C31L ^ island,
            fakeState
        );
        seed = JvmPassBytecode.mix(seed, fakeIndex);
        return seed == 0L ? group.salt() ^ 0x46414B45534C31L : seed;
    }

    private InsnList aliasHub(
        IslandGroup group,
        int alias,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal
    ) {
        InsnList insns = new InsnList();
        LabelNode aliasLabel = group.aliasHubs()[alias];
        long seed = group.salt() ^ 0x414C494153485542L ^ alias;
        insns.add(aliasLabel);
        switch ((int) ((seed >>> 7) & 7L)) {
            case 0 -> insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            case 1 -> {
                LabelNode hop = new LabelNode();
                insns.add(new JumpInsnNode(Opcodes.GOTO, hop));
                insns.add(hop);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 2, 4, 6 -> emitOpaqueHubBranch(
                insns,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                seed,
                group.hub()
            );
            case 5 -> {
                LabelNode hopA = new LabelNode();
                LabelNode hopB = new LabelNode();
                insns.add(new JumpInsnNode(Opcodes.GOTO, hopA));
                insns.add(hopB);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(hopA);
                insns.add(new JumpInsnNode(Opcodes.GOTO, hopB));
            }
            default -> emitOpaqueHubBranch(
                insns,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                seed ^ 0x4855424252414E43L,
                group.hub()
            );
        }
        return insns;
    }

    private void emitOpaqueHubBranch(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        LabelNode hub
    ) {
        LabelNode left = new LabelNode();
        LabelNode right = new LabelNode();
        emitKeyPredicate(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed);
        insns.add(
            new JumpInsnNode(
                ((seed >>> 3) & 1L) == 0L ? Opcodes.IFEQ : Opcodes.IFNE,
                left
            )
        );
        insns.add(new JumpInsnNode(Opcodes.GOTO, right));
        insns.add(left);
        insns.add(new JumpInsnNode(Opcodes.GOTO, hub));
        insns.add(right);
        insns.add(new JumpInsnNode(Opcodes.GOTO, hub));
    }

    private void emitDomainDispatch(
        InsnList insns,
        int domainLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        IslandGroup group,
        LabelNode poison
    ) {
        LabelNode[] islandLabels = group.islandLabels();
        if (islandLabels.length == 1) {
            insns.add(new JumpInsnNode(Opcodes.GOTO, islandLabels[0]));
            return;
        }
        emitEncodedDomainIfChain(
            insns,
            domainLocal,
            islandLabels,
            poison,
            group.salt()
        );
    }

    private void emitEncodedDomainIfChain(
        InsnList insns,
        int domainLocal,
        LabelNode[] islandLabels,
        LabelNode poison,
        long orderSeed
    ) {
        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        for (int i = 0; i < islandLabels.length; i++) {
            cases.put(domainToken(orderSeed, i), islandLabels[i]);
        }
        int[] keys = new int[cases.size()];
        LabelNode[] labels = new LabelNode[cases.size()];
        int index = 0;
        for (Map.Entry<Integer, LabelNode> entry : cases.entrySet()) {
            keys[index] = entry.getKey();
            labels[index] = entry.getValue();
            index++;
        }
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new LookupSwitchInsnNode(poison, keys, labels));
    }

    private void emitTokenDispatch(
        InsnList insns,
        int pcLocal,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        TreeMap<Integer, LabelNode> cases,
        LabelNode poison,
        long seed,
        int scratchLocal,
        int smallTokenDispatchCases
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        emitDispatchTokenMask(
            insns,
            pcLocal,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        if (cases.size() <= smallTokenDispatchCases) {
            emitSmallTokenDispatch(insns, cases, poison, seed);
            return;
        }
        int[] keys = new int[cases.size()];
        LabelNode[] labels = new LabelNode[cases.size()];
        int index = 0;
        for (Map.Entry<Integer, LabelNode> entry : cases.entrySet()) {
            keys[index] = entry.getKey();
            labels[index] = entry.getValue();
            index++;
        }
        insns.add(new LookupSwitchInsnNode(poison, keys, labels));
    }

    private void emitSmallTokenDispatch(
        InsnList insns,
        TreeMap<Integer, LabelNode> cases,
        LabelNode poison,
        long seed
    ) {
        if (cases.size() == 1) {
            Map.Entry<Integer, LabelNode> entry = cases.firstEntry();
            JvmPassBytecode.pushInt(insns, entry.getKey());
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, poison));
            insns.add(new JumpInsnNode(Opcodes.GOTO, entry.getValue()));
            return;
        }
        List<Map.Entry<Integer, LabelNode>> ordered = new ArrayList<>(cases.entrySet());
        ordered.sort((left, right) -> Long.compare(
            JvmPassBytecode.mix(seed, left.getKey()),
            JvmPassBytecode.mix(seed, right.getKey())
        ));
        List<LabelNode> matches = new ArrayList<>(ordered.size() - 1);
        for (int i = 0; i < ordered.size() - 1; i++) {
            Map.Entry<Integer, LabelNode> entry = ordered.get(i);
            LabelNode match = new LabelNode();
            matches.add(match);
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, entry.getKey());
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, match));
        }
        Map.Entry<Integer, LabelNode> last = ordered.get(ordered.size() - 1);
        JvmPassBytecode.pushInt(insns, last.getKey());
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, poison));
        insns.add(new JumpInsnNode(Opcodes.GOTO, last.getValue()));
        for (int i = 0; i < matches.size(); i++) {
            insns.add(matches.get(i));
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new JumpInsnNode(Opcodes.GOTO, ordered.get(i).getValue()));
        }
    }

    private long tokenDispatchSeed(long groupSalt, int island) {
        return JvmPassBytecode.mix(groupSalt ^ 0x544F4B4449535031L, island);
    }

    private long tokenDispatchSeed(
        IslandGroup group,
        int island,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel
    ) {
        long seed = tokenDispatchSeed(group.salt(), island);
        for (int attempt = 0; attempt < 32; attempt++) {
            Set<Integer> seen = new HashSet<>();
            boolean collision = false;
            for (Block block : group.blocks()) {
                Integer blockIsland = group.islands().get(block.label());
                if (blockIsland == null || blockIsland != island) {
                    continue;
                }
                CffBlockKeyState keyState = requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
                int masked = maskedDispatchToken(keyState.pcToken(), keyState, seed);
                if (!seen.add(masked)) {
                    collision = true;
                    break;
                }
            }
            if (!collision) {
                return seed;
            }
            seed = JvmPassBytecode.mix(seed, attempt + 1L);
        }
        throw new IllegalStateException("CFF token dispatch seed collision for island");
    }

    private int maskedDispatchToken(
        int token,
        CffBlockKeyState keyState,
        long seed
    ) {
        return token ^ dispatchTokenMask(token, keyState, seed);
    }

    private int dispatchTokenMask(
        int token,
        CffBlockKeyState keyState,
        long seed
    ) {
        int x = keyState.guardKey() +
            (keyState.pathKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x44545041544831L)));
        x ^= keyState.blockKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x4454424C4F434B31L)) | 1);
        x ^= token + nonZeroInt(JvmPassBytecode.mix(seed, 0x44545043544F4B31L));
        return x;
    }

    private int dispatchMethodKeyFold(long keyValue, long seed) {
        return ((int) keyValue) ^
            ((int) (keyValue >>> 32)) ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x444953504D4B31L));
    }

    private void emitDispatchTokenMask(
        InsnList insns,
        int pcLocal,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x44545041544831L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4454424C4F434B31L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x44545043544F4B31L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitDispatchMethodKeyFold(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x444953504D4B31L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitInitKeys(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyLocal,
        long seed,
        int scratchLocal
    ) {
        emitInitGuard(insns, guardLocal, keyLocal, seed);
        emitInitPathKey(
            insns,
            pathKeyLocal,
            keyLocal,
            seed ^ 0x504154484B455931L
        );
        emitInitBlockKey(
            insns,
            blockKeyLocal,
            guardLocal,
            keyLocal,
            seed ^ 0x424C4F434B455931L
        );
        emitClassKeyMixIntoLocals(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyLocal,
            seed ^ 0x434C4153534B31L,
            scratchLocal
        );
    }

    private void emitClassKeyMixIntoLocals(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyLocal,
        long seed,
        int scratchLocal
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return;
        int token = table.token(nonZeroInt(seed), seed);
        emitClassKeyWord(insns, table, keyLocal, token, seed, scratchLocal);

        // guard = guard + (classWord ^ c)
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x47554152444D4958L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));

        // path = (path ^ guard) + c
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x504154484D49584BL))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathKeyLocal));

        // block = (block + path) ^ classWord
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockKeyLocal));
    }

    private void emitClassKeyWord(
        InsnList insns,
        CffClassKeyTable table,
        int keyLocal,
        int token,
        long seed,
        int scratchLocal
    ) {
        emitClassKeyWordsLoad(insns, table);
        emitKeyedTableIndex(insns, keyLocal, token, seed);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitKeyMixInt(insns, keyLocal, seed ^ 0x574F52444B455931L);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 23));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitInitPathKey(
        InsnList insns,
        int pathKeyLocal,
        int keyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathKeyLocal));
    }

    private void emitInitBlockKey(
        InsnList insns,
        int blockKeyLocal,
        int guardLocal,
        int keyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (int) (seed >>> 32));
        insns.add(new InsnNode(Opcodes.IXOR));
        foldTopInt16(insns);
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockKeyLocal));
    }

    private void emitInitGuard(
        InsnList insns,
        int guardLocal,
        int keyLocal,
        long seed
    ) {
        // fold32(long): compute the method guard once from the incoming key.
        switch ((int) ((seed >>> 53) & 3L)) {
            case 0 -> emitInitGuardHighLow(insns, keyLocal);
            case 1 -> emitInitGuardLowHigh(insns, keyLocal);
            case 2 -> emitInitGuardSeededXor(insns, keyLocal, seed);
            default -> emitInitGuardSeededAdd(insns, keyLocal, seed);
        }
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
    }

    private void emitInitGuardHighLow(InsnList insns, int keyLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        foldTopInt16(insns);
    }

    private void emitInitGuardLowHigh(InsnList insns, int keyLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        foldTopInt16(insns);
    }

    private void emitInitGuardSeededXor(
        InsnList insns,
        int keyLocal,
        long seed
    ) {
        emitInitGuardHighLow(insns, keyLocal);
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        foldTopInt16(insns);
    }

    private void emitInitGuardSeededAdd(
        InsnList insns,
        int keyLocal,
        long seed
    ) {
        emitInitGuardLowHigh(insns, keyLocal);
        JvmPassBytecode.pushInt(insns, (int) (seed >>> 32));
        insns.add(new InsnNode(Opcodes.IADD));
        foldTopInt16(insns);
    }

    private void foldTopInt16(InsnList insns) {
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStorePc(
        InsnList insns,
        int pcLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyLocal,
        int state,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long selectorSeed,
        int scratchLocal
    ) {
        emitEncryptedToken(
            insns,
            targetKeys.pcToken(),
            targetKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            selectorSeed ^ state ^ 0x5043544F4B454E31L,
            scratchLocal
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, pcLocal));
    }

    private void emitStoreDomain(
        InsnList insns,
        int domainLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyLocal,
        int island,
        int domainToken,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long domainSeed,
        int scratchLocal
    ) {
        emitEncryptedToken(
            insns,
            domainToken,
            targetKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            domainSeed ^ island ^ 0x444F4D544F4B31L,
            scratchLocal
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, domainLocal));
    }

    private long routeTokenSeed(
        long methodSeed,
        long stepSeed,
        int state,
        DispatchTarget target
    ) {
        long seed = stepSeed ^ methodSeed ^ 0x52544F4B42415331L;
        seed = JvmPassBytecode.mix(seed, target.selectorSeed() ^ state);
        seed = JvmPassBytecode.mix(
            seed,
            target.domainSeed() ^ ((long) target.island() << 32) ^ target.domainToken()
        );
        return seed;
    }

    private int routeTokenBase(CffBlockKeyState keyState, long seed) {
        int x = classTokenMask(keyState, seed ^ 0x5254434C41535331L);
        x ^= keyState.guardKey() +
            (keyState.pathKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x52545041544831L)));
        x ^= keyState.blockKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x5254424C4F434B31L)) | 1);
        x ^= x >>> shift(seed, 7);
        return x;
    }

    private int routeTokenMask(
        CffBlockKeyState keyState,
        long routeSeed,
        long tokenSeed
    ) {
        return routeTokenMaskFromBase(routeTokenBase(keyState, routeSeed), tokenSeed);
    }

    private int routeTokenMaskFromBase(int base, long tokenSeed) {
        int x = base ^
            nonZeroInt(JvmPassBytecode.mix(tokenSeed, 0x52544D534B31L));
        x += nonZeroInt(JvmPassBytecode.mix(tokenSeed, 0x525441444431L)) | 1;
        x ^= x >>> shift(tokenSeed, 13);
        return x;
    }

    private void emitRouteTokenBase(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int routeBaseLocal,
        long seed,
        int scratchLocal
    ) {
        if (activeKeyTable == null) {
            JvmPassBytecode.pushInt(insns, 0);
        } else {
            JvmPassBytecode.pushInt(insns, 0);
            emitClassTokenMask(
                insns,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                seed ^ 0x5254434C41535331L,
                scratchLocal
            );
        }
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x52545041544831L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x5254424C4F434B31L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 7));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, routeBaseLocal));
    }

    private void emitStoreRouteToken(
        InsnList insns,
        int dstLocal,
        int token,
        CffBlockKeyState targetKeys,
        int routeBaseLocal,
        long routeSeed,
        long tokenSeed
    ) {
        int encrypted = token ^ routeTokenMask(targetKeys, routeSeed, tokenSeed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitRouteTokenMaskFromBase(insns, routeBaseLocal, tokenSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    private void emitStoreTransitionBaseToken(
        InsnList insns,
        int dstLocal,
        int token,
        CffBlockKeyState sourceKeys,
        int keyBaseLocal,
        long baseSeed,
        long tokenSeed
    ) {
        int encrypted =
            token ^
            controlTokenMaskFromBase(compactControlTokenBase(sourceKeys, baseSeed), tokenSeed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitControlTokenMaskFromBase(insns, keyBaseLocal, tokenSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    private void emitRouteTokenMaskFromBase(
        InsnList insns,
        int routeBaseLocal,
        long tokenSeed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, routeBaseLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(tokenSeed, 0x52544D534B31L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(tokenSeed, 0x525441444431L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(tokenSeed, 13));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStoreMethodKey(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        CffBlockKeyState targetKeys
    ) {
        emitMethodKeyFromDecodedState(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            targetKeys.methodSalt()
        );
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitStoreMethodKeyFromBase(
        InsnList insns,
        int keyLocal,
        int keyBaseLocal,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long baseSeed,
        long seed
    ) {
        emitDecodedMethodKeyWordFromBase(
            insns,
            (int) (targetKeys.methodKey() >>> 32),
            sourceKeys,
            baseSeed,
            keyBaseLocal,
            seed ^ 0x4849474831L
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitDecodedMethodKeyWordFromBase(
            insns,
            (int) targetKeys.methodKey(),
            sourceKeys,
            baseSeed,
            keyBaseLocal,
            seed ^ 0x4C4F5731L
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitDecodedMethodKeyWordFromBase(
        InsnList insns,
        int targetWord,
        CffBlockKeyState sourceKeys,
        long baseSeed,
        int keyBaseLocal,
        long seed
    ) {
        int encrypted =
            targetWord ^
            controlTokenMaskFromBase(compactControlTokenBase(sourceKeys, baseSeed), seed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitControlTokenMaskFromBase(insns, keyBaseLocal, seed);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitMethodKeyFromDecodedState(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        long methodSalt
    ) {
        long saltMask = JvmPassBytecode.mix(methodSalt, 0x4D4B46524F4D5354L);
        CffClassKeyTable table = activeKeyTable;
        if (table != null) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
            JvmPassBytecode.pushLong(insns, methodSalt ^ saltMask);
            JvmPassBytecode.pushLong(insns, saltMask);
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.methodKeyHelperOwner(),
                table.methodKeyHelperName(),
                "(IIIIJJ)J",
                table.methodKeyHelperInterfaceOwner()
            ));
            return;
        }
        LabelNode nonZero = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, methodSalt ^ saltMask);
        JvmPassBytecode.pushLong(insns, saltMask);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        JvmPassBytecode.pushLong(insns, METHOD_KEY_PC_MIX);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.POP2));
        JvmPassBytecode.pushLong(insns, 0xD1B54A32D192ED03L);
        insns.add(nonZero);
    }

    private long methodKeyLongMask(CffBlockKeyState keyState, long seed) {
        int high = keyState.guardKey() +
            (keyState.pathKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B4850415448L)));
        high ^= keyState.blockKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B48424C4F43L)) | 1);
        high ^= keyState.pcToken() +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B48504331L));
        high ^= high >>> shift(seed, 9);
        int low = keyState.blockKey() +
            keyState.pcToken() *
                (nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B4C504331L)) | 1);
        low ^= keyState.pathKey() ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B4C50415448L));
        low += keyState.guardKey();
        low ^= low >>> shift(seed, 15);
        return (((long) high) << 32) | (((long) low) & 0xFFFFFFFFL);
    }

    private void emitMethodKeyLongMask(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B4850415448L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B48424C4F43L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B48504331L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 9));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));

        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B4C504331L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B4C50415448L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 15));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private void emitEncryptedToken(
        InsnList insns,
        int token,
        CffBlockKeyState expectedKeys,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        int encrypted = token ^
            classTokenMask(expectedKeys, seed) ^
            classObjectTokenMask(expectedKeys, seed) ^
            controlTokenMask(expectedKeys, seed);
        CffClassKeyTable table = activeKeyTable;
        if (table != null) {
            insns.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                table.owner(),
                table.objectFieldName(),
                "[Ljava/lang/Object;"
            ));
            JvmPassBytecode.pushInt(insns, registerEncryptedTokenMaterial(table, encrypted, seed));
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                table.tokenMaterialHelperOwner(),
                table.tokenMaterialHelperName(),
                "([Ljava/lang/Object;IIII)I",
                table.tokenMaterialHelperInterfaceOwner()
            ));
            return;
        }
        JvmPassBytecode.pushInt(insns, encrypted);
        emitClassTokenMask(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed, scratchLocal);
        emitClassObjectTokenMaskAndUpdate(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed, scratchLocal);
        emitControlTokenMask(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int classTokenMask(CffBlockKeyState keyState, long seed) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return 0;
        long classSeed = seed ^ 0x434646434C544B31L;
        int word = table.values()[classStateTableIndex(keyState, classSeed)] ^
            classStateDigest(keyState, classSeed);
        return word;
    }

    private int classObjectTokenMask(CffBlockKeyState keyState, long seed) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return 0;
        long classSeed = seed ^ 0x4346464F544B31L;
        int word = table.objectValues()[classStateTableIndex(keyState, classSeed)] ^
            classStateDigest(keyState, classSeed);
        return word;
    }

    private void emitClassTokenMask(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return;
        long classSeed = seed ^ 0x434646434C544B31L;
        emitClassKeyWordsLoad(insns, table);
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535449445831L))
        );
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C5354424C4B31L)) | 1
        );
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535444494731L))
        );
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.intHelperOwner(),
            table.intHelperName(),
            "([IIIIIII)I",
            table.intHelperInterfaceOwner()
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitClassKeyWordsLoad(InsnList insns, CffClassKeyTable table) {
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
    }

    private void emitClassKeyWordsLoad(InsnList insns, int objectMaterialLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, objectMaterialLocal));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
    }

    private void emitClassObjectTokenMaskAndUpdate(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return;
        long classSeed = seed ^ 0x4346464F544B31L;
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, table.owner(), table.objectFieldName(), "[Ljava/lang/Object;"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535449445831L))
        );
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C5354424C4B31L)) | 1
        );
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(classSeed, 0x434C535444494731L))
        );
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4346464F455031L)));
        JvmPassBytecode.pushInt(insns, shift(seed, 11));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4346464F455032L)) | 1);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.owner(),
            table.objectHelperName(),
            "([Ljava/lang/Object;IIIIIIIII)I",
            table.interfaceOwner()
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int cffObjectCellEpoch(int mask, int index) {
        return nonZeroInt(JvmPassBytecode.mix(mask, index ^ 0x4346464F45504F43L));
    }

    private int cffObjectCellMask(int epoch) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(0x4346464F4D415331L, 0x43454C4C31L));
        x ^= x >>> 9;
        x *= nonZeroInt(JvmPassBytecode.mix(0x4346464F4D554C31L, 0x43454C4C31L)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(0x4346464F46494E31L, 0x43454C4C31L));
    }

    private void emitCffObjectCellMask(InsnList insns) {
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x4346464F4D415331L, 0x43454C4C31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 9);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x4346464F4D554C31L, 0x43454C4C31L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x4346464F46494E31L, 0x43454C4C31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int classStateTableIndex(CffBlockKeyState keyState, long seed) {
        int value = keyState.guardKey() +
            (keyState.pathKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535449445831L)));
        value += keyState.blockKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x434C5354424C4B31L)) | 1);
        return value & (CLASS_KEY_TABLE_SIZE - 1);
    }

    private void emitClassStateTableIndex(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535449445831L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C5354424C4B31L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_TABLE_SIZE - 1);
        insns.add(new InsnNode(Opcodes.IAND));
    }

    private int classStateDigest(CffBlockKeyState keyState, long seed) {
        return (keyState.blockKey() ^ keyState.pathKey()) +
            (keyState.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535444494731L)));
    }

    private void emitClassStateDigest(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535444494731L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
    }

    private CffBlockKeyState initialKeyState(long keyValue, long seed) {
        int guardKey = initialGuardKey(keyValue, seed);
        int pathKey = initialPathKey(keyValue, seed ^ 0x504154484B455931L);
        int blockKey = initialBlockKey(keyValue, guardKey, seed ^ 0x424C4F434B455931L);
        CffClassKeyTable table = activeKeyTable;
        if (table != null) {
            long classSeed = seed ^ 0x434C4153534B31L;
            int classWord = classKeyWord(table, keyValue, classSeed);
            guardKey += classWord ^ nonZeroInt(JvmPassBytecode.mix(classSeed, 0x47554152444D4958L));
            pathKey = (pathKey ^ guardKey) + nonZeroInt(JvmPassBytecode.mix(classSeed, 0x504154484D49584BL));
            blockKey = (blockKey + pathKey) ^ classWord;
        }
        long methodSalt = nonZeroLong(JvmPassBytecode.mix(seed, 0x494E49544D455448L));
        return new CffBlockKeyState(
            guardKey,
            pathKey,
            blockKey,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x494E49545043544BL)),
            methodKeyFromBlock(
                guardKey,
                pathKey,
                blockKey,
                nonZeroInt(JvmPassBytecode.mix(seed, 0x494E49545043544BL)),
                methodSalt
            ),
            methodSalt
        );
    }

    private int initialGuardKey(long keyValue, long seed) {
        int value;
        switch ((int) ((seed >>> 53) & 3L)) {
            case 0 -> value = foldInt16((int) (keyValue ^ (keyValue >>> 32)));
            case 1 -> value = foldInt16(((int) keyValue) ^ (int) (keyValue >>> 32));
            case 2 -> {
                value = foldInt16((int) (keyValue ^ (keyValue >>> 32)));
                value ^= (int) seed;
                value = foldInt16(value);
            }
            default -> {
                value = foldInt16(((int) keyValue) ^ (int) (keyValue >>> 32));
                value += (int) (seed >>> 32);
                value = foldInt16(value);
            }
        }
        return value;
    }

    private int foldInt16(int value) {
        return value ^ (value >>> 16);
    }

    private int initialPathKey(long keyValue, long seed) {
        int value = ((int) keyValue) ^ (int) seed;
        return value ^ (value >>> shift(seed, 5));
    }

    private int initialBlockKey(long keyValue, int guardKey, long seed) {
        int value = ((int) (keyValue >>> 32)) ^ guardKey ^ (int) (seed >>> 32);
        return value ^ (value >>> 16);
    }

    private int classKeyWord(CffClassKeyTable table, long keyValue, long seed) {
        int token = table.token(nonZeroInt(seed), seed);
        int index = (keyMixInt(keyValue, seed ^ 0x4944584B455931L) ^ token) &
            (CLASS_KEY_TABLE_SIZE - 1);
        int value = table.values()[index] ^
            keyMixInt(keyValue, seed ^ 0x574F52444B455931L);
        return value ^ (value >>> shift(seed, 23));
    }

    private int keyMixInt(long keyValue, long siteSeed) {
        int value = ((int) keyValue) ^ (int) (keyValue >>> 32);
        value += nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x4B45594D49584B31L));
        return value ^ (value >>> shift(siteSeed, 5));
    }

    private int methodKeyFold(long keyValue, long seed) {
        int value = ((int) keyValue) ^ (int) (keyValue >>> 32);
        value ^= (int) (seed >>> 32);
        value += nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B464F4C4431L));
        value ^= value >>> shift(seed, 13);
        return value;
    }

    private void emitMethodKeyFold(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (int) (seed >>> 32));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4D4B464F4C4431L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 13));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitDecodeBlockKeys(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int keyTmpLocal,
        int keyBaseLocal,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long seed,
        EdgeRole role
    ) {
        long baseSeed = transitionBaseSeed(seed, role);
        emitCompactControlTokenBase(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyBaseLocal,
            baseSeed,
            keyTmpLocal
        );
        emitDecodeBlockKeyWordCompact(
            insns,
            keyTmpLocal,
            targetKeys.guardKey(),
            sourceKeys,
            baseSeed,
            keyBaseLocal,
            seed ^ 0x47554152444B31L ^ role.ordinal()
        );
        emitDecodeBlockKeyWordCompact(
            insns,
            keyTmpLocal + 1,
            targetKeys.pathKey(),
            sourceKeys,
            baseSeed,
            keyBaseLocal,
            seed ^ 0x504154484B455931L ^ role.ordinal()
        );
        emitDecodeBlockKeyWordCompact(
            insns,
            keyTmpLocal + 2,
            targetKeys.blockKey(),
            sourceKeys,
            baseSeed,
            keyBaseLocal,
            seed ^ 0x424C4F434B4B31L ^ role.ordinal()
        );
        emitCommitDecodedKeys(
            insns,
            keyTmpLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal
        );
    }

    private long transitionBaseSeed(long seed, EdgeRole role) {
        return seed ^ 0x5452414E534B4559L ^ role.ordinal();
    }

    private void emitDecodeBlockKeyWordCompact(
        InsnList insns,
        int dstLocal,
        int targetWord,
        CffBlockKeyState sourceKeys,
        long baseSeed,
        int keyBaseLocal,
        long seed
    ) {
        int encrypted =
            targetWord ^
            controlTokenMaskFromBase(compactControlTokenBase(sourceKeys, baseSeed), seed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitControlTokenMaskFromBase(insns, keyBaseLocal, seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    private void emitCommitDecodedKeys(
        InsnList insns,
        int keyTmpLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal + 1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal + 2));
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockKeyLocal));
    }

    private CffBlockKeyState transitionBridgeKeyState(
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long seed,
        EdgeRole role
    ) {
        long bridgeSeed = JvmPassBytecode.mix(
            seed ^ 0x4252494447454B31L ^ role.ordinal(),
            sourceKeys.methodSalt() ^ targetKeys.methodSalt() ^ methodSeed
        );
        bridgeSeed = JvmPassBytecode.mix(
            bridgeSeed,
            (((long) sourceKeys.guardKey()) << 32) ^
                (((long) targetKeys.pathKey()) & 0xFFFFFFFFL)
        );
        bridgeSeed = JvmPassBytecode.mix(
            bridgeSeed,
            (((long) sourceKeys.blockKey()) << 32) ^
                (((long) targetKeys.guardKey()) & 0xFFFFFFFFL)
        );
        int guardKey = nonZeroInt(
            JvmPassBytecode.mix(bridgeSeed, 0x4252475541524431L)
        );
        int pathKey = nonZeroInt(
            JvmPassBytecode.mix(bridgeSeed, 0x42525041544831L)
        );
        int blockKey = nonZeroInt(
            JvmPassBytecode.mix(bridgeSeed, 0x4252424C4F434B31L)
        );
        int pcToken = nonZeroInt(
            targetKeys.pcToken() ^ JvmPassBytecode.mix(bridgeSeed, 0x42525043544F4B31L)
        );
        long methodSalt = nonZeroLong(
            JvmPassBytecode.mix(bridgeSeed, 0x42524D45544831L)
        );
        return new CffBlockKeyState(
            guardKey,
            pathKey,
            blockKey,
            pcToken,
            methodKeyFromBlock(guardKey, pathKey, blockKey, pcToken, methodSalt),
            methodSalt
        );
    }

    private void emitDecodeBlockKeyWord(
        InsnList insns,
        int dstLocal,
        int targetWord,
        CffBlockKeyState sourceKeys,
        long baseSeed,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyBaseLocal,
        long seed
    ) {
        int encrypted =
            targetWord ^
            controlTokenMaskFromBase(controlTokenBase(sourceKeys, baseSeed), seed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitControlTokenMaskFromBase(insns, keyBaseLocal, seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    private int controlTokenBase(
        CffBlockKeyState keyState,
        long seed
    ) {
        int x = keyState.guardKey() ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43544241534731L));
        x += keyState.pathKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x43544241535031L)) | 1);
        x ^= keyState.blockKey() +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43544241534231L));
        x ^= x >>> shift(seed, 11);
        x += methodKeyFold(keyState.methodKey(), seed ^ 0x4354424D45544831L);
        x ^= x >>> shift(seed, 17);
        return x;
    }

    private int controlTokenMaskFromBase(int base, long seed) {
        int x = base ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D534B31L));
        x += nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D414431L)) | 1;
        x ^= x >>> shift(seed, 13);
        return x;
    }

    private int compactControlTokenBase(
        CffBlockKeyState keyState,
        long seed
    ) {
        int x = classTokenMask(keyState, seed ^ 0x4347434C41535331L);
        x ^= keyState.guardKey() +
            (keyState.pathKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x43475041544831L)));
        x ^= keyState.blockKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x4347424C4F434B31L)) | 1);
        x += methodKeyFold(keyState.methodKey(), seed ^ 0x43474D45544831L);
        x ^= x >>> shift(seed, 11);
        return x;
    }

    private void emitCompactControlTokenBase(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyBaseLocal,
        long seed,
        int scratchLocal
    ) {
        if (activeKeyTable == null) {
            JvmPassBytecode.pushInt(insns, 0);
        } else {
            JvmPassBytecode.pushInt(insns, 0);
            emitClassTokenMask(
                insns,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                seed ^ 0x4347434C41535331L,
                scratchLocal
            );
        }
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43475041544831L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4347424C4F434B31L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitMethodKeyFold(insns, keyLocal, seed ^ 0x43474D45544831L);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 11));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, keyBaseLocal));
    }

    private int controlTokenMask(
        CffBlockKeyState keyState,
        long seed
    ) {
        int x = keyState.guardKey() +
            (keyState.pathKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x4354504D31L)));
        x ^= keyState.blockKey() +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D31L));
        x ^= x >>> shift(seed, 9);
        return x;
    }

    private void emitControlTokenMask(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4354504D31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D31L)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 9));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitControlTokenBase(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyBaseLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43544241534731L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43544241535031L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43544241534231L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 11));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitMethodKeyFold(insns, keyLocal, seed ^ 0x4354424D45544831L);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, keyBaseLocal));
    }

    private void emitControlTokenMaskFromBase(
        InsnList insns,
        int keyBaseLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyBaseLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D534B31L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D414431L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 13));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitEncodedStateValue(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int state,
        long selectorSeed
    ) {
        emitEncodedKeyedValue(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            state,
            selectorSeed ^ 0x53544154454B5631L
        );
    }

    private void emitEncodedDomainValue(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int island,
        long domainSeed
    ) {
        emitEncodedKeyedValue(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            island,
            domainSeed ^ 0x444F4D41494B5631L
        );
    }

    private void emitKeyPredicate(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        emitKeyDigest(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x5052454449434154L
        );
    }

    private void emitEncodedKeyedValue(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int value,
        long seed
    ) {
        switch ((int) ((seed >>> 41) & 3L)) {
            case 0 -> {
                emitClassDecodedInt(insns, value + (int) seed, seed);
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
                emitClassDecodedInt(
                    insns,
                    (int) (seed >>> 32),
                    seed ^ 0x484947484B31L
                );
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            case 1 -> {
                insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
                emitClassDecodedInt(insns, (int) seed, seed);
                insns.add(new InsnNode(Opcodes.IADD));
                emitClassDecodedInt(
                    insns,
                    value ^ (int) (seed >>> 32),
                    seed ^ 0x535441544B31L
                );
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
                emitClassDecodedInt(
                    insns,
                    (int) JvmPassBytecode.mix(seed, 0x50415448L),
                    seed ^ 0x504154484B31L
                );
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            case 2 -> {
                insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
                emitClassDecodedInt(
                    insns,
                    value + (int) (seed >>> 32),
                    seed ^ 0x56414C324B31L
                );
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new InsnNode(Opcodes.DUP));
                JvmPassBytecode.pushInt(insns, shift(seed, 7));
                insns.add(new InsnNode(Opcodes.IUSHR));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
                emitClassDecodedInt(
                    insns,
                    (int) JvmPassBytecode.mix(seed, 0x424C4F43L),
                    seed ^ 0x424C4F434B31L
                );
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new InsnNode(Opcodes.IADD));
            }
            default -> {
                emitClassDecodedInt(
                    insns,
                    value ^ (int) JvmPassBytecode.mix(seed, 0x56414C5545L),
                    seed ^ 0x56414C554B31L
                );
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
                emitClassDecodedInt(
                    insns,
                    (int) (seed >>> 32),
                    seed ^ 0x444546484B31L
                );
                insns.add(new InsnNode(Opcodes.IADD));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
                insns.add(new InsnNode(Opcodes.IADD));
                emitClassDecodedInt(insns, (int) seed, seed ^ 0x4445464B31L);
                insns.add(new InsnNode(Opcodes.IXOR));
            }
        }
    }

    private void emitClassDecodedInt(
        InsnList insns,
        int value,
        long siteSeed
    ) {
        JvmPassBytecode.pushInt(insns, value);
    }

    private void emitKeyedTableIndex(
        InsnList insns,
        int keyLocal,
        int token,
        long siteSeed
    ) {
        emitKeyMixInt(insns, keyLocal, siteSeed ^ 0x4944584B455931L);
        JvmPassBytecode.pushInt(insns, token);
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_TABLE_SIZE - 1);
        insns.add(new InsnNode(Opcodes.IAND));
    }

    private void emitKeyMixInt(InsnList insns, int keyLocal, long siteSeed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x4B45594D49584B31L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitKeyDigest(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 13));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            (int) JvmPassBytecode.mix(seed, 0x44494745L)
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitMaterializedStepKeys(
        InsnList insns,
        CffClassKeyTable table,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int outLocal,
        long seed,
        EdgeRole role
    ) {
        int row = registerStepMaterialRow(table, seed, role);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, row);
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.stepMaterialHelperOwner(),
            table.stepMaterialHelperName(),
            STEP_MATERIAL_HELPER_DESC,
            table.stepMaterialHelperInterfaceOwner()
        ));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
        emitTransitionOutPairLoad(
            insns,
            outLocal,
            0,
            guardLocal,
            pathKeyLocal
        );
        emitTransitionOutHighLoad(insns, outLocal, 1, blockKeyLocal);
    }

    private void emitStepKeys(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        EdgeRole role
    ) {
        long roleSeed = seed ^ ((long) role.ordinal() * 0x9E3779B97F4A7C15L);
        int firstIndex = selectStepKeyIndex(roleSeed);
        int firstLocal = stepKeyLocal(
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            firstIndex
        );
        int firstSource = stepSourceKeyLocal(
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            firstIndex,
            roleSeed ^ 0x4653544B455931L
        );
        emitStoreKeyTiny(insns, firstLocal, firstSource, roleSeed);

        long secondSeed = JvmPassBytecode.mix(roleSeed, 0x5345434F4E444B31L);
        if (((roleSeed >>> 61) & 1L) != 0L) {
            if (((roleSeed >>> 59) & 1L) == 0L) {
                int secondIndex = selectDifferentStepKeyIndex(
                    firstIndex,
                    secondSeed
                );
                int secondLocal = stepKeyLocal(
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    secondIndex
                );
                int secondSource = (((secondSeed >>> 23) & 1L) == 0L)
                    ? firstLocal
                    : stepSourceKeyLocal(
                          guardLocal,
                          pathKeyLocal,
                          blockKeyLocal,
                          secondIndex,
                          secondSeed ^ 0x5345435352434B31L
                      );
                emitStoreKeyTiny(insns, secondLocal, secondSource, secondSeed);
            } else {
                emitStepMethodKeyTiny(insns, keyLocal, firstLocal, secondSeed);
            }
        }
    }

    private StepDryRun stepDryRun(long seed, EdgeRole role) {
        long roleSeed = seed ^ ((long) role.ordinal() * 0x9E3779B97F4A7C15L);
        int firstTinyUpdates = 1;
        int secondTinyUpdates = 0;
        int methodKeyUpdates = 0;
        if (((roleSeed >>> 61) & 1L) != 0L) {
            if (((roleSeed >>> 59) & 1L) == 0L) {
                secondTinyUpdates = 1;
            } else {
                methodKeyUpdates = 1;
            }
        }
        return new StepDryRun(firstTinyUpdates, secondTinyUpdates, methodKeyUpdates);
    }

    private void emitStoreKeyTiny(
        InsnList insns,
        int dstLocal,
        int sourceLocal,
        long seed
    ) {
        int c = nonZeroInt(JvmPassBytecode.mix(seed, 0x54494E594B455931L));
        switch ((int) ((seed >>> 45) & 3L)) {
            case 0 -> {
                // dst = dst + (source ^ c)
                insns.add(new VarInsnNode(Opcodes.ILOAD, dstLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                JvmPassBytecode.pushInt(insns, c);
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new InsnNode(Opcodes.IADD));
            }
            case 1 -> {
                // dst = (dst ^ c) + source
                insns.add(new VarInsnNode(Opcodes.ILOAD, dstLocal));
                JvmPassBytecode.pushInt(insns, c);
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.IADD));
            }
            case 2 -> {
                // dst = (dst + source) ^ c
                insns.add(new VarInsnNode(Opcodes.ILOAD, dstLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.IADD));
                JvmPassBytecode.pushInt(insns, c);
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            default -> {
                // dst = (dst ^ source) + c
                insns.add(new VarInsnNode(Opcodes.ILOAD, dstLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                JvmPassBytecode.pushInt(insns, c);
                insns.add(new InsnNode(Opcodes.IADD));
            }
        }
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    private int selectStepKeyIndex(long seed) {
        return (int) Long.remainderUnsigned(seed >>> 54, 3L);
    }

    private int selectDifferentStepKeyIndex(int firstIndex, long seed) {
        int offset = 1 + (int) ((seed >>> 57) & 1L);
        return (firstIndex + offset) % 3;
    }

    private int stepKeyLocal(
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int index
    ) {
        return switch (index) {
            case 0 -> guardLocal;
            case 1 -> pathKeyLocal;
            default -> blockKeyLocal;
        };
    }

    private int stepSourceKeyLocal(
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int dstIndex,
        long seed
    ) {
        int sourceIndex = selectDifferentStepKeyIndex(dstIndex, seed);
        return stepKeyLocal(
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            sourceIndex
        );
    }

    private void emitStepMethodKeyTiny(
        InsnList insns,
        int keyLocal,
        int sourceLocal,
        long seed
    ) {
        long c = nonZeroLong(JvmPassBytecode.mix(seed, 0x4D4554484B455931L));
        switch ((int) ((seed >>> 51) & 3L)) {
            case 0 -> {
                // key = key + (source & 0xffffffffL) ^ c
                insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.I2L));
                JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
                insns.add(new InsnNode(Opcodes.LAND));
                insns.add(new InsnNode(Opcodes.LADD));
                JvmPassBytecode.pushLong(insns, c);
                insns.add(new InsnNode(Opcodes.LXOR));
            }
            case 1 -> {
                // key = (key ^ c) + source
                insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                JvmPassBytecode.pushLong(insns, c);
                insns.add(new InsnNode(Opcodes.LXOR));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.I2L));
                insns.add(new InsnNode(Opcodes.LADD));
            }
            case 2 -> {
                // key = key ^ ((long) source << 32) + c
                insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.I2L));
                JvmPassBytecode.pushInt(insns, 32);
                insns.add(new InsnNode(Opcodes.LSHL));
                insns.add(new InsnNode(Opcodes.LXOR));
                JvmPassBytecode.pushLong(insns, c);
                insns.add(new InsnNode(Opcodes.LADD));
            }
            default -> {
                // key = key + c ^ source
                insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
                JvmPassBytecode.pushLong(insns, c);
                insns.add(new InsnNode(Opcodes.LADD));
                insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
                insns.add(new InsnNode(Opcodes.I2L));
                insns.add(new InsnNode(Opcodes.LXOR));
            }
        }
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x6D2B79F5 : v;
    }

    private long nonZeroLong(long value) {
        return value == 0L ? 0xD1B54A32D192ED03L : value;
    }

    private DispatchPlan buildDispatchPlan(
        List<Block> blocks,
        CffFrameAnalysis frames,
        long salt,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, String> handlerDomains
    ) {
        // Split dispatchers must not merge blocks that require different local
        // initialization states. This preserves verifier compatibility without
        // falling back to unflattened bytecode.
        Map<String, List<Block>> byFrame = new LinkedHashMap<>();
        for (Block block : blocks) {
            if (block.handler()) continue;
            String signature =
                handlerDomains.getOrDefault(block.label(), "N") +
                ':' +
                frames.localsSignature(block.label());
            byFrame
                .computeIfAbsent(signature, ignored -> new ArrayList<>())
                .add(block);
        }

        List<IslandGroup> groups = new ArrayList<>();
        Map<LabelNode, DispatchTarget> targets = new IdentityHashMap<>();
        int groupIndex = 0;
        for (Map.Entry<String, List<Block>> entry : byFrame.entrySet()) {
            List<Block> groupBlocks = entry.getValue();
            int islandCount = islandCount(groupBlocks.size());
            LabelNode hub = new LabelNode();
            LabelNode[] islandLabels = new LabelNode[islandCount];
            for (int i = 0; i < islandCount; i++) {
                islandLabels[i] = new LabelNode();
            }
            LabelNode[] aliasHubs = new LabelNode[aliasHubCount(
                groupBlocks.size()
            )];
            for (int i = 0; i < aliasHubs.length; i++) {
                aliasHubs[i] = new LabelNode();
            }
            Map<LabelNode, Integer> islands = new IdentityHashMap<>();
            long groupSalt = JvmPassBytecode.mix(
                salt ^ entry.getKey().hashCode(),
                groupIndex++ ^ groupBlocks.size()
            );
            long groupDomainSeed = groupSalt ^ 0x444F4D41494E4B31L;
            for (int i = 0; i < groupBlocks.size(); i++) {
                Block block = groupBlocks.get(i);
                int island = islandFor(i, groupBlocks.size(), islandCount);
                int state = requireState(block.label(), stateByLabel.get(block.label()));
                islands.put(block.label(), island);
                targets.put(
                    block.label(),
                    new DispatchTarget(
                        hub,
                        islandLabels,
                        aliasHubs,
                        island,
                        caseSelectorSeed(
                            groupSalt,
                            block.label(),
                            state,
                            island
                        ),
                        groupDomainSeed,
                        domainToken(groupSalt, island)
                    )
                );
            }
            groups.add(
                new IslandGroup(
                    hub,
                    islandLabels,
                    aliasHubs,
                    groupBlocks,
                    islands,
                    groupSalt
                )
            );
        }
        return new DispatchPlan(groups, targets);
    }

    private Map<LabelNode, CffBlockKeyState> buildBlockKeyStates(
        List<Block> blocks,
        Map<LabelNode, LabelNode> aliases,
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        long salt
    ) {
        Map<LabelNode, CffBlockKeyState> keyStates = new IdentityHashMap<>();
        Set<Integer> usedPcTokens = new HashSet<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            Integer state = stateByLabel.get(block.label());
            DispatchTarget target = dispatchByLabel.get(block.label());
            if (state == null || target == null) continue;
            long seed = JvmPassBytecode.mix(
                salt ^ 0x424C4F434B535431L,
                state ^ i
            );
            seed = JvmPassBytecode.mix(seed, System.identityHashCode(block.label()));
            int pcToken = nonZeroInt(JvmPassBytecode.mix(seed, 0x5043544F4B31L));
            while (!usedPcTokens.add(pcToken)) {
                pcToken = nonZeroInt(JvmPassBytecode.mix(pcToken, usedPcTokens.size() + 1L));
            }
            keyStates.put(
                block.label(),
                blockKeyState(seed, pcToken)
            );
        }
        for (Map.Entry<LabelNode, LabelNode> alias : aliases.entrySet()) {
            LabelNode canonicalLabel = canonicalLabel(alias.getValue(), aliases);
            CffBlockKeyState canonical = keyStates.get(canonicalLabel);
            if (canonical != null) keyStates.put(alias.getKey(), canonical);
        }
        return keyStates;
    }

    private void installEntryKeyState(
        List<Block> blocks,
        DispatchPlan dispatchPlan,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long methodSeed,
        boolean externalEntrySeed
    ) {
        Block entry = firstNonHandler(blocks);
        if (entry == null) return;
        for (IslandGroup group : dispatchPlan.groups()) {
            if (!group.blocks().contains(entry)) continue;
            keyStateByLabel.put(
                entry.label(),
                initialKeyState(
                    methodSeed,
                    entryInitSeed(group.salt(), externalEntrySeed, methodSeed)
                )
            );
            return;
        }
    }

    private long entryInitSeed(
        long groupSalt,
        boolean externalEntrySeed,
        long methodSeed
    ) {
        long contextSeed = JvmPassBytecode.mix(
            groupSalt ^ 0x454E545259435458L,
            methodSeed
        );
        if (!externalEntrySeed) return nonZeroLong(contextSeed);
        long seed = JvmPassBytecode.mix(
            contextSeed ^ 0x45585445524B4559L,
            0x4B4559454E545259L
        );
        return nonZeroLong(seed);
    }

    private Set<LabelNode> runtimeKeyLabels(
        PipelineContext pctx,
        MethodNode mn,
        List<Block> blocks,
        Map<LabelNode, LabelNode> aliases
    ) {
        Set<LabelNode> labels = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                if (insn.getOpcode() < 0 || JvmKeyDispatchPass.isGeneratedNode(pctx, insn)) {
                    continue;
                }
                if (requiresRuntimeKeys(pctx, insn)) {
                    labels.add(block.label());
                    break;
                }
            }
        }
        for (Map.Entry<LabelNode, LabelNode> alias : aliases.entrySet()) {
            if (labels.contains(alias.getValue())) {
                labels.add(alias.getKey());
            }
        }
        return labels;
    }

    private boolean requiresRuntimeKeys(PipelineContext pctx, AbstractInsnNode insn) {
        if (
            pctx.config().isTransformEnabled(JvmConstantObfuscationPass.ID) &&
            isNumericConstantSite(insn)
        ) {
            return true;
        }
        if (!pctx.config().isTransformEnabled(JvmStringObfuscationPass.ID)) {
            return false;
        }
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
            return true;
        }
        return insn instanceof InvokeDynamicInsnNode indy &&
            indy.bsm != null &&
            "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner()) &&
            "makeConcatWithConstants".equals(indy.bsm.getName()) &&
            indy.bsmArgs.length > 0 &&
            indy.bsmArgs[0] instanceof String &&
            Type.getReturnType(indy.desc).equals(Type.getType(String.class));
    }

    private boolean isNumericConstantSite(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (insn instanceof IincInsnNode) return true;
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.DCONST_1) return true;
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) return true;
        return insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number;
    }

    private void rewriteKeyedCallTransfers(
        PipelineContext pctx,
        MethodNode mn,
        List<Block> blocks,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        Map<AbstractInsnNode, Long> generatedKeyLoadSeeds = generatedKeyLoadTargetSeeds(pctx);
        Map<AbstractInsnNode, Block> blockByInstruction = instructionBlockMap(blocks);
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                Long generatedTargetSeed = generatedKeyLoadSeeds.get(insn);
                if (generatedTargetSeed != null) {
                    InsnList replacement = new InsnList();
                    emitMaterializedDynamicBoundDecodedLong(
                        replacement,
                        incomingRawForCanonical(generatedTargetSeed),
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        salt ^ generatedTargetSeed ^ System.identityHashCode(insn),
                        insn,
                        keyTmpLocal
                    );
                    JvmKeyDispatchPass.markGenerated(pctx, replacement);
                    mn.instructions.insertBefore(insn, replacement);
                    mn.instructions.remove(insn);
                    continue;
                }
                Long targetSeed = keyedTargetSeed(pctx, insn);
                if (targetSeed == null) continue;
                AbstractInsnNode keyLoad = previousReal(insn.getPrevious());
                long rawSeed = incomingRawForCanonical(targetSeed);
                InsnList replacement = new InsnList();
                emitMaterializedDynamicBoundDecodedLong(
                    replacement,
                    rawSeed,
                    requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    salt ^ targetSeed ^ System.identityHashCode(insn),
                    insn,
                    keyTmpLocal
                );
                if (isGeneratedKeyLoad(pctx, keyLoad, keyLocal)) {
                    JvmKeyDispatchPass.markGenerated(pctx, replacement);
                    mn.instructions.insertBefore(keyLoad, replacement);
                    mn.instructions.remove(keyLoad);
                    continue;
                }
                rewritePackedGeneratedKeyLoads(
                    pctx,
                    mn,
                    insn,
                    keyLocal,
                    replacement
                );
            }
        }
        rewriteDetachedGeneratedKeyLoads(
            pctx,
            mn,
            generatedKeyLoadSeeds,
            blockByInstruction,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
        rewriteReflectiveGeneratedKeyLoads(
            pctx,
            mn,
            generatedKeyLoadSeeds,
            blockByInstruction,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
        rewriteDetachedPackedKeyedCallTransfers(
            pctx,
            mn,
            blockByInstruction,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyTmpLocal,
            keyStateByLabel,
            salt
        );
    }

    private void rewriteDetachedPackedKeyedCallTransfers(
        PipelineContext pctx,
        MethodNode mn,
        Map<AbstractInsnNode, Block> blockByInstruction,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            Long targetSeed = keyedTargetSeed(pctx, insn);
            if (targetSeed == null) continue;
            Block block = nearbyBlock(insn, blockByInstruction);
            if (block == null) continue;
            long rawSeed = incomingRawForCanonical(targetSeed);
            InsnList replacement = new InsnList();
            emitMaterializedDynamicBoundDecodedLong(
                replacement,
                rawSeed,
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(insn),
                insn,
                keyTmpLocal
            );
            rewritePackedGeneratedKeyLoads(
                pctx,
                mn,
                insn,
                keyLocal,
                replacement
            );
        }
    }

    @SuppressWarnings("unchecked")
    private Map<AbstractInsnNode, Long> generatedKeyLoadTargetSeeds(PipelineContext pctx) {
        Map<AbstractInsnNode, Long> map = pctx.getPassData(
            JvmMethodParameterObfuscationPass.CFF_KEY_LOAD_TARGET_SEED
        );
        return map == null ? Map.of() : map;
    }

    private void rewritePackedGeneratedKeyLoads(
        PipelineContext pctx,
        MethodNode mn,
        AbstractInsnNode call,
        int keyLocal,
        InsnList replacementTemplate
    ) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = call.getPrevious();
            scan != null && scanned++ < 160;
            scan = scan.getPrevious()
        ) {
            if (!isGeneratedKeyLoad(pctx, scan, keyLocal) && !isKeyLocalLoad(scan, keyLocal)) continue;
            AbstractInsnNode next = nextReal(scan.getNext());
            if (next instanceof VarInsnNode store &&
                store.getOpcode() == Opcodes.LSTORE &&
                rewriteStoredPackedGeneratedKeyLoad(pctx, mn, call, store.var, replacementTemplate)) {
                return;
            }
            if (!(next instanceof MethodInsnNode box) ||
                box.getOpcode() != Opcodes.INVOKESTATIC ||
                !"java/lang/Long".equals(box.owner) ||
                !"valueOf".equals(box.name) ||
                !"(J)Ljava/lang/Long;".equals(box.desc)) {
                continue;
            }
            InsnList replacement = cloneInsnList(replacementTemplate);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(scan, replacement);
            mn.instructions.remove(scan);
            return;
        }
    }

    private boolean rewriteStoredPackedGeneratedKeyLoad(
        PipelineContext pctx,
        MethodNode mn,
        AbstractInsnNode call,
        int storedLocal,
        InsnList replacementTemplate
    ) {
        int scanned = 0;
        for (
            AbstractInsnNode scan = call.getPrevious();
            scan != null && scanned++ < 160;
            scan = scan.getPrevious()
        ) {
            if (!(scan instanceof VarInsnNode load) ||
                load.getOpcode() != Opcodes.LLOAD ||
                load.var != storedLocal) {
                continue;
            }
            AbstractInsnNode next = nextReal(scan.getNext());
            if (!(next instanceof MethodInsnNode box) ||
                box.getOpcode() != Opcodes.INVOKESTATIC ||
                !"java/lang/Long".equals(box.owner) ||
                !"valueOf".equals(box.name) ||
                !"(J)Ljava/lang/Long;".equals(box.desc)) {
                continue;
            }
            InsnList replacement = cloneInsnList(replacementTemplate);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(scan, replacement);
            mn.instructions.remove(scan);
            return true;
        }
        return false;
    }

    private Map<AbstractInsnNode, Block> instructionBlockMap(List<Block> blocks) {
        Map<AbstractInsnNode, Block> out = new IdentityHashMap<>();
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                out.put(insn, block);
            }
        }
        return out;
    }

    private void rewriteDetachedGeneratedKeyLoads(
        PipelineContext pctx,
        MethodNode mn,
        Map<AbstractInsnNode, Long> generatedKeyLoadSeeds,
        Map<AbstractInsnNode, Block> blockByInstruction,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        if (generatedKeyLoadSeeds.isEmpty()) return;
        for (Map.Entry<AbstractInsnNode, Long> entry : new ArrayList<>(generatedKeyLoadSeeds.entrySet())) {
            AbstractInsnNode keyLoad = entry.getKey();
            if (!isLiveInstruction(mn, keyLoad)) continue;
            Block block = nearbyBlock(keyLoad, blockByInstruction);
            if (block == null) continue;
            long targetSeed = entry.getValue();
            InsnList replacement = new InsnList();
            emitMaterializedDynamicBoundDecodedLong(
                replacement,
                incomingRawForCanonical(targetSeed),
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(keyLoad),
                keyLoad,
                keyTmpLocal
            );
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(keyLoad, replacement);
            mn.instructions.remove(keyLoad);
        }
    }

    private boolean isLiveInstruction(MethodNode mn, AbstractInsnNode insn) {
        return insn != null &&
            (insn == mn.instructions.getFirst() ||
                insn.getPrevious() != null ||
                insn.getNext() != null);
    }

    private Block nearbyBlock(
        AbstractInsnNode insn,
        Map<AbstractInsnNode, Block> blockByInstruction
    ) {
        Block block = blockByInstruction.get(insn);
        if (block != null) return block;
        for (AbstractInsnNode next = nextReal(insn.getNext()); next != null; next = nextReal(next.getNext())) {
            block = blockByInstruction.get(next);
            if (block != null) return block;
        }
        for (AbstractInsnNode prev = previousReal(insn.getPrevious()); prev != null; prev = previousReal(prev.getPrevious())) {
            block = blockByInstruction.get(prev);
            if (block != null) return block;
        }
        return null;
    }

    private void rewriteReflectiveGeneratedKeyLoads(
        PipelineContext pctx,
        MethodNode mn,
        Map<AbstractInsnNode, Long> generatedKeyLoadSeeds,
        Map<AbstractInsnNode, Block> blockByInstruction,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long salt
    ) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (generatedKeyLoadSeeds.containsKey(insn)) continue;
            if (!isGeneratedKeyLoad(pctx, insn, keyLocal)) continue;
            AbstractInsnNode next = nextReal(insn.getNext());
            if (!(next instanceof MethodInsnNode box) ||
                box.getOpcode() != Opcodes.INVOKESTATIC ||
                !"java/lang/Long".equals(box.owner) ||
                !"valueOf".equals(box.name) ||
                !"(J)Ljava/lang/Long;".equals(box.desc)) {
                continue;
            }
            Long targetSeed = reflectivePackedTargetSeed(pctx, insn);
            if (targetSeed == null) continue;
            Block block = nearbyBlock(insn, blockByInstruction);
            if (block == null) continue;
            InsnList replacement = new InsnList();
            emitMaterializedDynamicBoundDecodedLong(
                replacement,
                incomingRawForCanonical(targetSeed),
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(insn),
                insn,
                keyTmpLocal
            );
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            AbstractInsnNode previous = insn.getPrevious();
            mn.instructions.remove(insn);
            insn = previous;
        }
    }

    private Long reflectivePackedTargetSeed(PipelineContext pctx, AbstractInsnNode keyLoad) {
        MethodInsnNode invoke = nextReflectiveInvoke(keyLoad);
        if (invoke == null) return null;
        MethodInsnNode lookup = previousReflectiveLookup(invoke);
        if (lookup == null) return null;
        ReflectiveTarget target = reflectiveTarget(lookup);
        if (target == null) return null;
        L1Class clazz = pctx.classMap().get(target.owner());
        if (clazz == null) return null;
        L1Method matched = null;
        for (L1Method method : clazz.methods()) {
            if (!method.name().equals(target.name()) || !method.hasCode()) continue;
            Type[] args = Type.getArgumentTypes(method.descriptor());
            if (args.length != 1 || !Type.getType(Object[].class).equals(args[0])) continue;
            if (matched != null) return null;
            matched = method;
        }
        if (matched == null) return null;
        Long seed = JvmKeyDispatchPass.findMethodSeed(
            pctx,
            JvmKeyDispatchPass.coverageKey(clazz.name(), matched.name(), matched.descriptor())
        );
        return seed != null ? seed : JvmKeyDispatchPass.methodSeed(
            pctx,
            clazz,
            matched,
            matched.asmNode()
        );
    }

    private MethodInsnNode nextReflectiveInvoke(AbstractInsnNode keyLoad) {
        int scanned = 0;
        for (AbstractInsnNode scan = keyLoad.getNext(); scan != null && scanned++ < 512; scan = scan.getNext()) {
            if (!(scan instanceof MethodInsnNode call)) continue;
            if ("java/lang/reflect/Method".equals(call.owner) &&
                "invoke".equals(call.name) &&
                "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private MethodInsnNode previousReflectiveLookup(MethodInsnNode invoke) {
        int scanned = 0;
        for (AbstractInsnNode scan = invoke.getPrevious(); scan != null && scanned++ < 1024; scan = scan.getPrevious()) {
            if (!(scan instanceof MethodInsnNode call)) continue;
            if ("java/lang/Class".equals(call.owner) &&
                ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name)) &&
                "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc)) {
                return call;
            }
        }
        return null;
    }

    private ReflectiveTarget reflectiveTarget(MethodInsnNode lookup) {
        String name = null;
        String owner = null;
        int scanned = 0;
        for (AbstractInsnNode scan = lookup.getPrevious(); scan != null && scanned++ < 256; scan = scan.getPrevious()) {
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
        return name != null && owner != null ? new ReflectiveTarget(owner, name) : null;
    }

    private record ReflectiveTarget(String owner, String name) {
    }

    private InsnList cloneInsnList(InsnList source) {
        InsnList out = new InsnList();
        Map<LabelNode, LabelNode> labels = new IdentityHashMap<>();
        for (AbstractInsnNode insn = source.getFirst(); insn != null; insn = insn.getNext()) {
            out.add(insn.clone(labels));
        }
        return out;
    }

    private Long keyedTargetSeed(PipelineContext pctx, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            if ("<init>".equals(call.name)) return null;
            return keyedTargetSeed(pctx, call.owner, call.name, call.desc);
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            for (Object arg : indy.bsmArgs) {
                if (!(arg instanceof Handle handle)) continue;
                Long seed = keyedTargetSeed(
                    pctx,
                    handle.getOwner(),
                    handle.getName(),
                    handle.getDesc()
                );
                if (seed != null) return seed;
            }
        }
        return null;
    }

    private Long keyedTargetSeed(
        PipelineContext pctx,
        String owner,
        String name,
        String desc
    ) {
        Long packed = packedCallTargetSeed(pctx, owner, name, desc);
        if (packed != null) return packed;
        Long recorded = JvmKeyDispatchPass.findMethodSeed(
            pctx,
            JvmKeyDispatchPass.coverageKey(owner, name, desc)
        );
        if (recorded != null) return recorded;
        L1Class targetClass = pctx.classMap().get(owner);
        if (targetClass == null) return null;
        L1Method targetMethod = findAsmMethod(targetClass, name, desc);
        if (targetMethod == null) {
            return null;
        }
        if (!targetMethod.hasCode() || isVirtualFamilyMethod(targetClass, targetMethod)) {
            return JvmKeyDispatchPass.methodSeed(
                pctx,
                targetClass,
                targetMethod,
                targetMethod.asmNode()
            );
        }
        if (!usesExternalEntrySeed(pctx, targetClass, targetMethod)) return null;
        return JvmKeyDispatchPass.methodSeed(
            pctx,
            targetClass,
            targetMethod,
            targetMethod.asmNode()
        );
    }

    @SuppressWarnings("unchecked")
    private Long packedCallTargetSeed(PipelineContext pctx, String owner, String name, String desc) {
        Map<String, Long> seeds = pctx.getPassData(
            JvmMethodParameterObfuscationPass.CFF_PACKED_CALL_TARGET_SEED
        );
        return seeds == null ? null : seeds.get(JvmKeyDispatchPass.coverageKey(owner, name, desc));
    }

    private boolean isVirtualFamilyMethod(L1Class clazz, L1Method method) {
        MethodNode mn = method.asmNode();
        if (mn == null) return false;
        if ("<init>".equals(mn.name) || "<clinit>".equals(mn.name)) return false;
        if ((mn.access & Opcodes.ACC_STATIC) != 0) return false;
        return (mn.access & Opcodes.ACC_PRIVATE) == 0;
    }

    private L1Method findAsmMethod(L1Class clazz, String name, String desc) {
        L1Method direct = clazz.findMethod(name, desc);
        if (direct != null) return direct;
        for (L1Method method : clazz.methods()) {
            MethodNode node = method.asmNode();
            if (node != null && name.equals(node.name) && desc.equals(node.desc)) {
                return method;
            }
        }
        return null;
    }

    private boolean usesExternalEntrySeed(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (JvmKeyDispatchPass.isReflectiveKeyedEntry(
            pctx,
            JvmKeyDispatchPass.coverageKey(clazz.name(), method.name(), method.descriptor())
        )) {
            return false;
        }
        int access = method.access();
        if ((access & Opcodes.ACC_STATIC) != 0) return true;
        if ((access & Opcodes.ACC_PRIVATE) != 0) return true;
        if ((access & Opcodes.ACC_FINAL) != 0) return true;
        return (clazz.asmNode().access & Opcodes.ACC_FINAL) != 0;
    }

    private AbstractInsnNode previousReal(AbstractInsnNode start) {
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getPrevious()
        ) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private boolean isGeneratedKeyLoad(
        PipelineContext pctx,
        AbstractInsnNode insn,
        int keyLocal
    ) {
        return insn instanceof VarInsnNode var &&
            var.getOpcode() == Opcodes.LLOAD &&
            var.var == keyLocal &&
            JvmKeyDispatchPass.isGeneratedNode(pctx, insn);
    }

    private boolean isKeyLocalLoad(AbstractInsnNode insn, int keyLocal) {
        return insn instanceof VarInsnNode var &&
            var.getOpcode() == Opcodes.LLOAD &&
            var.var == keyLocal;
    }

    private long incomingRawForCanonical(long targetSeed) {
        return (targetSeed - JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK) ^
            (targetSeed ^ JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK);
    }

    private void emitMaterializedDynamicBoundDecodedLong(
        InsnList insns,
        long value,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        AbstractInsnNode sourceInsn,
        int scratchLocal
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) {
            throw new IllegalStateException("CFF key-transfer material helper requires a class key table");
        }
        long sourceSeed = keyTransferSourceSeed(sourceInsn);
        int runtimeSourceMode = keyTransferRuntimeSourceMode(sourceInsn);
        int highCursor = registerKeyTransferMaterialWord(
            table,
            (int) (value >>> 32),
            expectedKeys,
            seed ^ sourceSeed ^ 0x4B58464849474831L,
            KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED,
            runtimeSourceMode
        );
        int lowCursor = registerKeyTransferMaterialWord(
            table,
            (int) value,
            expectedKeys,
            seed ^ sourceSeed ^ 0x4B58464C4F5731L,
            KEY_TRANSFER_MATERIAL_HIGH_METHOD_SEED,
            runtimeSourceMode
        );
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, highCursor);
        JvmPassBytecode.pushInt(insns, lowCursor);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            table.keyTransferMaterialHelperOwner(),
            table.keyTransferMaterialHelperName(),
            KEY_TRANSFER_MATERIAL_HELPER_DESC,
            table.keyTransferMaterialHelperInterfaceOwner()
        ));
    }

    private long keyTransferSourceSeed(AbstractInsnNode insn) {
        AbstractInsnNode context = keyTransferSourceContext(insn);
        if (context instanceof InvokeDynamicInsnNode indy) {
            long source = isLambdaMetafactory(indy)
                ? 0x4B54584C414D4241L
                : 0x4B5458494E445931L;
            Type returnType = Type.getReturnType(indy.desc);
            if (returnType.getSort() == Type.OBJECT && isAsyncCarrierType(returnType.getInternalName())) {
                source ^= 0x4B54584153594E43L;
            }
            source ^= indy.name.hashCode();
            source ^= indy.desc.hashCode();
            if (indy.bsm != null) {
                source ^= indy.bsm.getOwner().hashCode();
                source ^= indy.bsm.getName().hashCode();
                source ^= indy.bsm.getDesc().hashCode();
            }
            return JvmPassBytecode.mix(source, 0x4B54585352434931L);
        }
        if (context instanceof MethodInsnNode call) {
            long source = 0x4B54584D45544831L;
            if (isReflectiveInvokeCall(call)) {
                source ^= 0x4B54585245464C31L;
            }
            if (isReflectiveLookupCall(call)) {
                source ^= 0x4B54584C4F4F4B31L;
            }
            if (isAsyncBoundaryCall(call)) {
                source ^= 0x4B54584153594E43L;
            }
            if (isStackTraceBoundaryCall(call)) {
                source ^= 0x4B5458535441434BL;
            }
            if (isExceptionBoundaryCall(call)) {
                source ^= 0x4B54584558435031L;
            }
            source ^= call.owner.hashCode();
            source ^= call.name.hashCode();
            source ^= call.desc.hashCode();
            source ^= call.getOpcode();
            return JvmPassBytecode.mix(source, 0x4B54585352434D31L);
        }
        return JvmPassBytecode.mix(0x4B54585352434E31L, System.identityHashCode(insn));
    }

    private AbstractInsnNode keyTransferSourceContext(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) {
            return insn;
        }
        int scanned = 0;
        for (
            AbstractInsnNode scan = nextReal(insn == null ? null : insn.getNext());
            scan != null && scanned++ < 96;
            scan = nextReal(scan.getNext())
        ) {
            if (isLongBoxCall(scan)) continue;
            if (scan instanceof InvokeDynamicInsnNode || scan instanceof MethodInsnNode) {
                return scan;
            }
        }
        return insn;
    }

    private boolean isLongBoxCall(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call &&
            call.getOpcode() == Opcodes.INVOKESTATIC &&
            "java/lang/Long".equals(call.owner) &&
            "valueOf".equals(call.name) &&
            "(J)Ljava/lang/Long;".equals(call.desc);
    }

    private boolean isLambdaMetafactory(InvokeDynamicInsnNode indy) {
        return indy.bsm != null &&
            "java/lang/invoke/LambdaMetafactory".equals(indy.bsm.getOwner()) &&
            ("metafactory".equals(indy.bsm.getName()) ||
                "altMetafactory".equals(indy.bsm.getName()));
    }

    private boolean isReflectiveInvokeCall(MethodInsnNode call) {
        return "java/lang/reflect/Method".equals(call.owner) &&
            "invoke".equals(call.name) &&
            "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;".equals(call.desc);
    }

    private boolean isReflectiveLookupCall(MethodInsnNode call) {
        return "java/lang/Class".equals(call.owner) &&
            ("getMethod".equals(call.name) || "getDeclaredMethod".equals(call.name)) &&
            "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private boolean isAsyncBoundaryCall(MethodInsnNode call) {
        if ("java/lang/Thread".equals(call.owner)) {
            return "start".equals(call.name) || "<init>".equals(call.name) || "ofVirtual".equals(call.name);
        }
        if ("java/util/concurrent/Executor".equals(call.owner)) {
            return "execute".equals(call.name);
        }
        if ("java/util/concurrent/ExecutorService".equals(call.owner)) {
            return "execute".equals(call.name)
                || "submit".equals(call.name)
                || "invokeAll".equals(call.name)
                || "invokeAny".equals(call.name);
        }
        if ("java/util/concurrent/ForkJoinPool".equals(call.owner)) {
            return "execute".equals(call.name)
                || "submit".equals(call.name)
                || "invoke".equals(call.name);
        }
        if ("java/util/concurrent/CompletableFuture".equals(call.owner)) {
            return call.name.endsWith("Async")
                || "runAsync".equals(call.name)
                || "supplyAsync".equals(call.name);
        }
        if ("java/util/concurrent/Executors".equals(call.owner)) {
            return call.name.startsWith("new")
                || "callable".equals(call.name)
                || "privilegedCallable".equals(call.name)
                || "privilegedCallableUsingCurrentClassLoader".equals(call.name);
        }
        return false;
    }

    private boolean isStackTraceBoundaryCall(MethodInsnNode call) {
        if ("java/lang/Throwable".equals(call.owner)) {
            return "getStackTrace".equals(call.name)
                || "setStackTrace".equals(call.name)
                || "fillInStackTrace".equals(call.name)
                || "printStackTrace".equals(call.name);
        }
        if ("java/lang/Thread".equals(call.owner)) {
            return "getStackTrace".equals(call.name)
                || "getAllStackTraces".equals(call.name)
                || "dumpStack".equals(call.name);
        }
        return "java/lang/StackWalker".equals(call.owner)
            || call.owner.startsWith("java/lang/StackTraceElement");
    }

    private boolean isExceptionBoundaryCall(MethodInsnNode call) {
        return "java/lang/Throwable".equals(call.owner)
            || call.owner.endsWith("Exception")
            || call.owner.endsWith("Error");
    }

    private boolean isAsyncCarrierType(String internalName) {
        return "java/lang/Runnable".equals(internalName)
            || "java/util/concurrent/Callable".equals(internalName)
            || "java/util/concurrent/Future".equals(internalName)
            || "java/util/concurrent/CompletableFuture".equals(internalName)
            || "java/util/concurrent/CompletionStage".equals(internalName);
    }

    private int keyTransferRuntimeSourceMode(AbstractInsnNode insn) {
        AbstractInsnNode context = keyTransferSourceContext(insn);
        int mode = KEY_TRANSFER_RUNTIME_SOURCE_NONE;
        if (context instanceof InvokeDynamicInsnNode indy) {
            Type returnType = Type.getReturnType(indy.desc);
            if (returnType.getSort() == Type.OBJECT && isAsyncCarrierType(returnType.getInternalName())) {
                mode |= KEY_TRANSFER_RUNTIME_SOURCE_THREAD;
            }
            if (isLambdaMetafactory(indy)) {
                for (Object arg : indy.bsmArgs) {
                    if (arg instanceof Type type &&
                        type.getSort() == Type.OBJECT &&
                        isAsyncCarrierType(type.getInternalName())) {
                        mode |= KEY_TRANSFER_RUNTIME_SOURCE_THREAD;
                    }
                }
            }
        } else if (context instanceof MethodInsnNode call) {
            if (isAsyncBoundaryCall(call)) {
                mode |= KEY_TRANSFER_RUNTIME_SOURCE_THREAD;
            }
            if (isStackTraceBoundaryCall(call) || isExceptionBoundaryCall(call)) {
                mode |= KEY_TRANSFER_RUNTIME_SOURCE_STACK;
            }
        }
        return mode;
    }

    private int registerKeyTransferMaterialWord(
        CffClassKeyTable table,
        int word,
        CffBlockKeyState expectedKeys,
        long materialSeed,
        long methodSeed,
        int runtimeSourceMode
    ) {
        int bucketCount = keyTransferRuntimeSourceBucketCount(runtimeSourceMode);
        int baseCursor = -1;
        for (int bucket = 0; bucket < bucketCount; bucket++) {
            int cursor = registerKeyTransferMaterialWordBucket(
                table,
                word,
                expectedKeys,
                keyTransferRuntimeSourceBucketSeed(
                    materialSeed,
                    runtimeSourceMode,
                    bucket
                ),
                methodSeed
            );
            if (bucket == 0) {
                baseCursor = cursor;
            } else {
                int expectedCursor = baseCursor + bucket * TOKEN_MATERIAL_ROW_LONGS * 2;
                if (cursor != expectedCursor) {
                    throw new IllegalStateException(
                        "CFF key-transfer runtime source rows are not contiguous for " +
                            table.owner()
                    );
                }
            }
        }
        return encodeKeyTransferMaterialCursor(baseCursor, runtimeSourceMode);
    }

    private int registerKeyTransferMaterialWordBucket(
        CffClassKeyTable table,
        int word,
        CffBlockKeyState expectedKeys,
        long materialSeed,
        long methodSeed
    ) {
        int materialToken = word ^ methodKeyFold(expectedKeys.methodKey(), methodSeed);
        int encrypted = materialToken ^
            classTokenMask(expectedKeys, materialSeed) ^
            classObjectTokenMask(expectedKeys, materialSeed) ^
            controlTokenMask(expectedKeys, materialSeed);
        return registerEncryptedTokenMaterial(table, encrypted, materialSeed);
    }

    private int keyTransferRuntimeSourceBucketCount(int runtimeSourceMode) {
        return runtimeSourceMode == KEY_TRANSFER_RUNTIME_SOURCE_NONE
            ? 1
            : KEY_TRANSFER_RUNTIME_SOURCE_BUCKETS;
    }

    private long keyTransferRuntimeSourceBucketSeed(
        long materialSeed,
        int runtimeSourceMode,
        int bucket
    ) {
        long seed = materialSeed ^
            (((long) runtimeSourceMode) << 40) ^
            (((long) bucket) << 32) ^
            0x4B58525352433131L;
        return JvmPassBytecode.mix(seed, 0x4B58525352433231L);
    }

    private int encodeKeyTransferMaterialCursor(
        int cursor,
        int runtimeSourceMode
    ) {
        if ((cursor & ~KEY_TRANSFER_CURSOR_INDEX_MASK) != 0) {
            throw new IllegalStateException(
                "CFF key-transfer material cursor exceeds encoded range: " + cursor
            );
        }
        return cursor | (runtimeSourceMode << KEY_TRANSFER_CURSOR_MODE_SHIFT);
    }

    private void emitDynamicBoundDecodedLong(
        InsnList insns,
        long value,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        emitEncryptedBoundToken(
            insns,
            (int) (value >>> 32),
            expectedKeys,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x42444849474831L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitEncryptedBoundToken(
            insns,
            (int) value,
            expectedKeys,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x42444C4F5731L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private void emitEncryptedBoundToken(
        InsnList insns,
        int token,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        int encrypted = token ^
            classTokenMask(expectedKeys, seed) ^
            controlTokenMask(expectedKeys, seed) ^
            methodKeyFold(expectedKeys.methodKey(), seed ^ 0x42444D45544831L);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitClassTokenMask(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed, scratchLocal);
        emitControlTokenMask(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed
        );
        emitMethodKeyFold(insns, keyLocal, seed ^ 0x42444D45544831L);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitDynamicDecodedLong(
        InsnList insns,
        long value,
        CffBlockKeyState expectedKeys,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed,
        int scratchLocal
    ) {
        emitEncryptedToken(
            insns,
            (int) (value >>> 32),
            expectedKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x5241574849474831L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitEncryptedToken(
            insns,
            (int) value,
            expectedKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x5241574C4F5731L,
            scratchLocal
        );
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private CffBlockKeyState syntheticHandlerSourceKey(
        long methodSeed,
        long salt,
        LabelNode handler
    ) {
        long seed = JvmPassBytecode.mix(
            methodSeed ^ salt ^ 0x48414E444C45524BL,
            System.identityHashCode(handler)
        );
        return blockKeyState(seed ^ 0x48414E444C455252L, nonZeroInt(JvmPassBytecode.mix(seed, 0x48544331L)));
    }

    private CffBlockKeyState blockKeyState(long seed, int pcToken) {
        int guardKey = nonZeroInt(JvmPassBytecode.mix(seed, 0x47554152444B31L));
        int pathKey = nonZeroInt(JvmPassBytecode.mix(seed, 0x504154484B31L));
        int blockKey = nonZeroInt(JvmPassBytecode.mix(seed, 0x424C4F434B31L));
        long methodSalt = nonZeroLong(JvmPassBytecode.mix(seed, 0x4D4554484F444B31L));
        return new CffBlockKeyState(
            guardKey,
            pathKey,
            blockKey,
            pcToken,
            methodKeyFromBlock(guardKey, pathKey, blockKey, pcToken, methodSalt),
            methodSalt
        );
    }

    private long methodKeyFromBlock(
        int guardKey,
        int pathKey,
        int blockKey,
        int pcToken,
        long methodSalt
    ) {
        long high = ((long) guardKey) << 32;
        long low = ((long) pathKey) & 0xFFFFFFFFL;
        long pc = ((long) pcToken) & 0xFFFFFFFFL;
        return nonZeroLong((high ^ low) + (((long) blockKey) ^ methodSalt) ^ (pc * METHOD_KEY_PC_MIX));
    }

    private CffBlockKeyState firstIslandKeyState(
        IslandGroup group,
        int island,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel
    ) {
        for (Block block : group.blocks()) {
            Integer blockIsland = group.islands().get(block.label());
            if (blockIsland != null && blockIsland == island) {
                return requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
            }
        }
        throw new IllegalStateException("CFF island has no block key state");
    }

    private LabelNode firstIslandLabel(IslandGroup group, int island) {
        for (Block block : group.blocks()) {
            Integer blockIsland = group.islands().get(block.label());
            if (blockIsland != null && blockIsland == island) {
                return block.label();
            }
        }
        throw new IllegalStateException("CFF island has no block label");
    }

    private int domainToken(long groupSalt, int island) {
        return nonZeroInt(
            JvmPassBytecode.mix(groupSalt ^ 0x444F4D544F4B31L, island)
        );
    }

    private int fakeDispatchToken(
        long groupSalt,
        int fakeState,
        int island,
        int fakeIndex
    ) {
        long seed = JvmPassBytecode.mix(
            groupSalt ^ 0x46414B45544F4B31L ^ island,
            fakeState
        );
        return nonZeroInt(JvmPassBytecode.mix(seed, fakeIndex));
    }

    private long caseSelectorSeed(
        long groupSalt,
        LabelNode label,
        int state,
        int island
    ) {
        long seed = JvmPassBytecode.mix(
            groupSalt ^ 0x4341534553454C31L ^ island,
            state
        );
        seed = JvmPassBytecode.mix(seed, System.identityHashCode(label));
        return seed == 0L ? groupSalt ^ 0x53454C45435431L : seed;
    }

    private Block firstNonHandler(List<Block> blocks) {
        for (Block block : blocks) {
            if (!block.handler()) return block;
        }
        return null;
    }

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30);
    }

    private LabelNode ensureLabelBefore(MethodNode mn, AbstractInsnNode node) {
        for (
            AbstractInsnNode previous = node.getPrevious();
            previous != null && previous.getOpcode() < 0;
            previous = previous.getPrevious()
        ) {
            if (previous instanceof LabelNode label) return label;
        }
        LabelNode label = new LabelNode();
        mn.instructions.insertBefore(node, label);
        return label;
    }

    private LabelNode ensureLabelAfter(MethodNode mn, AbstractInsnNode node) {
        AbstractInsnNode next = node.getNext();
        if (next instanceof LabelNode label) return label;
        LabelNode label = new LabelNode();
        mn.instructions.insert(node, label);
        return label;
    }

    private AbstractInsnNode firstReal(MethodNode mn) {
        return nextReal(mn.instructions.getFirst());
    }

    private AbstractInsnNode nextReal(AbstractInsnNode start) {
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private AbstractInsnNode lastRealBefore(MethodNode mn, AbstractInsnNode endExclusive) {
        AbstractInsnNode insn = endExclusive == null
            ? mn.instructions.getLast()
            : endExclusive.getPrevious();
        if (insn == null) return null;
        for (; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private boolean before(AbstractInsnNode left, AbstractInsnNode right) {
        for (
            AbstractInsnNode insn = left;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn == right) return true;
        }
        return false;
    }

    private boolean terminates(int opcode) {
        return (
            (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN) ||
            opcode == Opcodes.ATHROW
        );
    }

    private boolean isControlTransfer(AbstractInsnNode insn) {
        return (
            insn instanceof JumpInsnNode ||
            insn instanceof TableSwitchInsnNode ||
            insn instanceof LookupSwitchInsnNode ||
            terminates(insn.getOpcode())
        );
    }

    private int invertJumpOpcode(int opcode) {
        return switch (opcode) {
            case Opcodes.IFEQ -> Opcodes.IFNE;
            case Opcodes.IFNE -> Opcodes.IFEQ;
            case Opcodes.IFLT -> Opcodes.IFGE;
            case Opcodes.IFGE -> Opcodes.IFLT;
            case Opcodes.IFGT -> Opcodes.IFLE;
            case Opcodes.IFLE -> Opcodes.IFGT;
            case Opcodes.IF_ICMPEQ -> Opcodes.IF_ICMPNE;
            case Opcodes.IF_ICMPNE -> Opcodes.IF_ICMPEQ;
            case Opcodes.IF_ICMPLT -> Opcodes.IF_ICMPGE;
            case Opcodes.IF_ICMPGE -> Opcodes.IF_ICMPLT;
            case Opcodes.IF_ICMPGT -> Opcodes.IF_ICMPLE;
            case Opcodes.IF_ICMPLE -> Opcodes.IF_ICMPGT;
            case Opcodes.IF_ACMPEQ -> Opcodes.IF_ACMPNE;
            case Opcodes.IF_ACMPNE -> Opcodes.IF_ACMPEQ;
            case Opcodes.IFNULL -> Opcodes.IFNONNULL;
            case Opcodes.IFNONNULL -> Opcodes.IFNULL;
            default -> throw new IllegalStateException(
                "Unsupported conditional opcode for inversion: " + opcode
            );
        };
    }

    private void emitInitTransitionOut(InsnList insns, int outLocal) {
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outLocal));
    }

    private void emitTransitionOutStores(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal
    ) {
        emitTransitionOutStores(
            insns,
            outLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            true
        );
    }

    private void emitTransitionOutStores(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        boolean includeDomain
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        if (includeDomain) {
            emitTransitionOutHighStore(insns, outLocal, 2, domainLocal);
        }
    }

    private void emitTransitionOutStoresWithResult(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int resultToken
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        emitTransitionOutPairStoreConstLow(insns, outLocal, 2, domainLocal, resultToken);
    }

    private void emitTransitionOutStoresWithResultLocal(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int resultLocal
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        emitTransitionOutPairStoreLocalLow(insns, outLocal, 2, domainLocal, resultLocal);
    }

    private void emitTransitionOutStoresWithMaskedResult(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyLocal,
        int resultOrdinal,
        long resultMaskSeed
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        JvmPassBytecode.pushInt(insns, resultOrdinal);
        emitResultRouteMask(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            resultMaskSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitTransitionOutStoresWithMaskedResultLocal(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        int keyLocal,
        int resultLocal,
        long resultMaskSeed
    ) {
        emitTransitionOutPairStore(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairStore(insns, outLocal, 1, blockKeyLocal, pcLocal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, resultLocal));
        emitResultRouteMask(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            resultMaskSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitResultRouteMask(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitTransitionOutPairStore(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lowLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitTransitionOutHighStore(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitTransitionOutPairStoreConstLow(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int low
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        JvmPassBytecode.pushInt(insns, low);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitTransitionOutPairStoreLocalLow(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, highLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lowLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitTransitionOutLoads(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal
    ) {
        emitTransitionOutLoads(
            insns,
            outLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            domainLocal,
            true
        );
    }

    private void emitTransitionOutLoads(
        InsnList insns,
        int outLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        boolean includeDomain
    ) {
        emitTransitionOutPairLoad(insns, outLocal, 0, guardLocal, pathKeyLocal);
        emitTransitionOutPairLoad(insns, outLocal, 1, blockKeyLocal, pcLocal);
        if (includeDomain) {
            emitTransitionOutHighLoad(insns, outLocal, 2, domainLocal);
        }
    }

    private void emitTransitionOutPairLoad(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, highLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, lowLocal));
    }

    private void emitTransitionOutHighLoad(
        InsnList insns,
        int outLocal,
        int index,
        int highLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.LALOAD));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, highLocal));
    }

    private void emitTransitionOutLowLoad(
        InsnList insns,
        int outLocal,
        int index,
        int lowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, lowLocal));
    }

    private void emitPackedTransitionOutLoads(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal
    ) {
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 0, guardLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 1, pathKeyLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 2, blockKeyLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 3, pcLocal);
        emitPackedTransitionOutLoad(insns, outLocal, tokenLocal, 4, domainLocal);
        emitPackedTransitionOutValue(insns, outLocal, tokenLocal, 6);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitPackedTransitionOutValue(insns, outLocal, tokenLocal, 7);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitPackedTransitionOutLoad(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index,
        int dstLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
    }

    private void emitPackedTransitionOutValue(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitPackedTransitionOutStore(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index,
        int valueLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    private void emitPackedTransitionOutStoreConst(
        InsnList insns,
        int outLocal,
        int tokenLocal,
        int index,
        int value
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        JvmPassBytecode.pushInt(insns, value);
        emitTransitionTokenMask(insns, tokenLocal, index);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    private void emitTransitionTokenMask(
        InsnList insns,
        int tokenLocal,
        int index
    ) {
        long seed = JvmPassBytecode.mix(0x535542444953504CL, index);
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 23 + (index & 7));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
    }

    public static final String CFF_ISLAND_DRY_RUN_STATS =
        "controlFlowFlattening.islandDryRunStats";
    public static final String CFF_ISLAND_MATERIAL_OP_DRY_RUN_STATS =
        "controlFlowFlattening.islandMaterialOpDryRunStats";

    private final class TransitionOutliner {
        private static final String DESC = "(JIIIII[J)J";
        private final PipelineContext pctx;
        private final L1Class clazz;
        private final String owner;
        private final boolean interfaceOwner;
        private final int outLocal;
        private final int smallTokenDispatchCases;
        private final boolean materializeDirectIslandTransitions;
        private final Map<IslandGroup, RouterState> routers = new IdentityHashMap<>();
        private int counter;

        TransitionOutliner(
            PipelineContext pctx,
            L1Class clazz,
            int outLocal,
            int smallTokenDispatchCases,
            boolean materializeDirectIslandTransitions
        ) {
            this.pctx = pctx;
            this.clazz = clazz;
            this.owner = clazz.asmNode().name;
            this.interfaceOwner = clazz.isInterface();
            this.outLocal = outLocal;
            this.smallTokenDispatchCases = smallTokenDispatchCases;
            this.materializeDirectIslandTransitions = materializeDirectIslandTransitions;
        }

        int outLocal() {
            return outLocal;
        }

        InsnList emitIslandDispatchCall(
            IslandGroup group,
            int island,
            List<Block> islandBlocks,
            int firstState,
            int fakeCount,
            long dispatchSeed,
            Map<LabelNode, Integer> stateByLabel,
            Map<LabelNode, CffBlockKeyState> keyStateByLabel,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            int domainLocal,
            int keyTmpLocal,
            LabelNode poison,
            long methodSeed,
            long salt
        ) {
            RouterState router = router(group);
            boolean denseResultRouter = useDenseResultRouter(group);
            router.denseResultRouter = denseResultRouter;
            Map<LabelNode, Integer> resultTokens = new IdentityHashMap<>();
            long resultMaskSeed = resultRouteMaskSeed(group);
            for (int i = 0; i < islandBlocks.size(); i++) {
                Block block = islandBlocks.get(i);
                int token = denseResultRouter
                    ? addResultCase(router, block.label())
                    : uniqueResultToken(
                        router,
                        resultMaskSeed ^ island,
                        block.label(),
                        requireState(block.label(), stateByLabel.get(block.label())) ^ i
                    );
                resultTokens.put(block.label(), token);
            }
            int bounceToken = denseResultRouter
                ? addResultCase(router, group.hub())
                : uniqueResultToken(
                    router,
                    resultMaskSeed ^ 0x424F554E43454B31L ^ island,
                    group.hub(),
                    fakeCount ^ firstState
                );
            IslandDispatchHelperPlan helperPlan = createIslandDispatchHelper(
                group,
                island,
                islandBlocks,
                firstState,
                fakeCount,
                dispatchSeed,
                stateByLabel,
                keyStateByLabel,
                resultTokens,
                bounceToken,
                methodSeed,
                salt,
                resultMaskSeed,
                denseResultRouter
            );
            String helperName = helperPlan.name();

            InsnList insns = new InsnList();
            insns.add(group.islandLabels()[island]);
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
            insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    owner,
                    helperName,
                    DESC,
                    interfaceOwner
                )
            );
            insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
            emitTransitionOutLoads(
                insns,
                outLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal
            );
            emitTransitionOutLowLoad(insns, outLocal, 2, keyTmpLocal);
            insns.add(new JumpInsnNode(Opcodes.GOTO, router.label));
            JvmKeyDispatchPass.markGenerated(pctx, insns);
            recordIslandDryRun(
                islandBlocks.size(),
                fakeCount,
                denseResultRouter,
                resultTokens.size(),
                helperPlan.dispatchCases(),
                helperPlan.helperInstructions(),
                insns.size()
            );
            recordIslandMaterialOpDryRun(
                group,
                island,
                fakeCount,
                denseResultRouter,
                resultTokens.size(),
                salt
            );
            return insns;
        }

        private RouterState router(IslandGroup group) {
            return routers.computeIfAbsent(group, ignored -> new RouterState());
        }

        private int addResultCase(RouterState router, LabelNode label) {
            int token = router.resultCases.size();
            router.resultCases.put(token, label);
            return token;
        }

        private int uniqueResultToken(
            RouterState router,
            long seed,
            LabelNode label,
            int discriminator
        ) {
            int token = nonZeroInt(
                JvmPassBytecode.mix(
                    seed ^ System.identityHashCode(label),
                    discriminator ^ 0x52455431L
                )
            );
            int attempt = 0;
            while (router.resultCases.containsKey(token)) {
                token = nonZeroInt(
                    JvmPassBytecode.mix(token, ++attempt ^ 0x554E49515545L)
                );
            }
            router.resultCases.put(token, label);
            return token;
        }

        InsnList emitResultRouter(
            IslandGroup group,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            int domainLocal,
            int keyTmpLocal,
            LabelNode poison
        ) {
            InsnList insns = new InsnList();
            RouterState router = routers.get(group);
            if (router == null || router.resultCases.isEmpty()) return insns;
            LabelNode[] labels = new LabelNode[router.resultCases.size()];
            int index = 0;
            for (Map.Entry<Integer, LabelNode> entry : router.resultCases.entrySet()) {
                labels[index] = entry.getValue();
                index++;
            }
            insns.add(router.label);
            insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal));
            if (router.denseResultRouter) {
                emitResultRouteMask(
                    insns,
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    resultRouteMaskSeed(group)
                );
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new TableSwitchInsnNode(0, labels.length - 1, poison, labels));
            } else {
                int[] keys = new int[router.resultCases.size()];
                index = 0;
                for (Map.Entry<Integer, LabelNode> entry : router.resultCases.entrySet()) {
                    keys[index++] = entry.getKey();
                }
                insns.add(new LookupSwitchInsnNode(poison, keys, labels));
            }
            JvmKeyDispatchPass.markGenerated(pctx, insns);
            return insns;
        }

        private long resultRouteMaskSeed(IslandGroup group) {
            return JvmPassBytecode.mix(
                group.salt() ^ 0x524553524F555445L,
                group.blocks().size()
            );
        }

        private boolean useDenseResultRouter(IslandGroup group) {
            return group.blocks().size() >= 8;
        }

        private IslandDispatchHelperPlan createIslandDispatchHelper(
            IslandGroup group,
            int island,
            List<Block> islandBlocks,
            int firstState,
            int fakeCount,
            long dispatchSeed,
            Map<LabelNode, Integer> stateByLabel,
            Map<LabelNode, CffBlockKeyState> keyStateByLabel,
            Map<LabelNode, Integer> resultTokens,
            int bounceToken,
            long methodSeed,
            long salt,
            long resultMaskSeed,
            boolean denseResultRouter
        ) {
            String helperName = nextHelperName();
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            access |= interfaceOwner ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
            MethodNode helper = new MethodNode(
                access,
                helperName,
                DESC,
                null,
                null
            );
            int helperKeyLocal = 0;
            int helperGuardLocal = 2;
            int helperPathLocal = 3;
            int helperBlockLocal = 4;
            int helperPcLocal = 5;
            int helperDomainLocal = 6;
            int helperOutLocal = 7;
            int helperKeyTmpLocal = 8;
            int helperMaterialWordLocal = 11;
            int helperSourceLocal = 12;
            CffClassKeyTable stepTable = ensureClassKeyTable(pctx, clazz);

            TreeMap<Integer, LabelNode> cases = new TreeMap<>();
            List<LabelNode> realLabels = new ArrayList<>();
            List<Integer> realDispatchTokens = new ArrayList<>();
            for (Block block : islandBlocks) {
                LabelNode caseLabel = new LabelNode();
                realLabels.add(caseLabel);
                CffBlockKeyState blockKeys = requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
                int realDispatchToken = maskedDispatchToken(
                    blockKeys.pcToken(),
                    blockKeys,
                    dispatchSeed
                );
                realDispatchTokens.add(realDispatchToken);
                cases.put(realDispatchToken, caseLabel);
            }
            List<LabelNode> fakeLabels = new ArrayList<>();
            List<Integer> fakeTokens = new ArrayList<>();
            List<Integer> fakeStates = new ArrayList<>();
            for (int fakeIndex = 0; fakeIndex < fakeCount; fakeIndex++) {
                LabelNode fake = new LabelNode();
                fakeLabels.add(fake);
                int fakeState = fakeState(
                    salt,
                    firstState ^ island ^ (fakeIndex * 0x45D9F3B)
                );
                while (cases.containsKey(fakeState)) {
                    fakeState = fakeState(
                        salt ^ 0x9E3779B97F4A7C15L,
                        fakeState + fakeIndex + 1
                    );
                }
                int fakeToken = fakeDispatchToken(group.salt(), fakeState, island, fakeIndex);
                while (cases.containsKey(fakeToken)) {
                    fakeToken = nonZeroInt(JvmPassBytecode.mix(fakeToken, fakeIndex + 1L));
                }
                fakeStates.add(fakeState);
                fakeTokens.add(fakeToken);
            }
            List<Integer> realResultWordIndexes = new ArrayList<>();
            int islandMaterialCursor = registerCompressedIslandMaterialBlob(
                stepTable,
                compressedIslandMaterialWords(
                    group,
                    island,
                    islandBlocks,
                    firstState,
                    fakeCount,
                    dispatchSeed,
                    realDispatchTokens,
                    fakeTokens,
                    fakeStates,
                    keyStateByLabel,
                    resultTokens,
                    realResultWordIndexes,
                    bounceToken,
                    methodSeed,
                    salt,
                    resultMaskSeed,
                    denseResultRouter
                ),
                salt ^ dispatchSeed ^ methodSeed ^ resultMaskSeed ^ 0x494D415449534C31L
            );
            LabelNode poisonLabel = new LabelNode();
            LabelNode dispatchMissLabel = fakeLabels.isEmpty()
                ? poisonLabel
                : new LabelNode();
            emitCffIslandCallsiteRuntimeSource(
                helper.instructions,
                helperKeyLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
                helperPcLocal,
                helperDomainLocal,
                islandMaterialCursor
            );
            helper.instructions.add(new VarInsnNode(Opcodes.ISTORE, helperSourceLocal));
            emitTokenDispatch(
                helper.instructions,
                helperPcLocal,
                helperKeyLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
                cases,
                dispatchMissLabel,
                dispatchSeed,
                helperKeyTmpLocal,
                smallTokenDispatchCases
            );
            for (int i = 0; i < islandBlocks.size(); i++) {
                Block block = islandBlocks.get(i);
                helper.instructions.add(realLabels.get(i));
                emitCompressedIslandMaterialWordDecode(
                    helper.instructions,
                    stepTable,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
                    helperSourceLocal,
                    islandMaterialCursor,
                    realResultWordIndexes.get(i),
                    helperMaterialWordLocal
                );
                finishOutlinedDispatchReturnFromLocal(
                    helper.instructions,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
                    helperPcLocal,
                    helperDomainLocal,
                    helperOutLocal,
                    helperMaterialWordLocal,
                    resultMaskSeed,
                    denseResultRouter
                );
            }
            if (!fakeLabels.isEmpty()) {
                helper.instructions.add(dispatchMissLabel);
                emitDynamicFakeSourceRouter(
                    helper.instructions,
                    fakeLabels,
                    poisonLabel,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
                    helperPcLocal,
                    group.salt(),
                    island
                );
            }
            for (int fakeIndex = 0; fakeIndex < fakeLabels.size(); fakeIndex++) {
                helper.instructions.add(fakeLabels.get(fakeIndex));
                long fakeSeed = edgeSeed(
                    salt,
                    group.hub(),
                    group.islandLabels()[island],
                    0x46414B4549534C45L ^ island ^ fakeIndex
                );
                emitMaterializedStepKeys(
                    helper.instructions,
                    stepTable,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
                    helperOutLocal,
                    fakeSeed,
                    EdgeRole.FAKE
                );
                emitOutlinedFakeCaseBounce(
                    helper.instructions,
                    group,
                    island,
                    firstState,
                    keyStateByLabel,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
                    helperPcLocal,
                    helperDomainLocal,
                    helperOutLocal,
                    methodSeed,
                    fakeSeed,
                    bounceToken,
                    resultMaskSeed,
                    denseResultRouter
                );
            }
            helper.instructions.add(poisonLabel);
            long poisonSeed = edgeSeed(
                salt,
                group.hub(),
                group.islandLabels()[island],
                0x4F5554504F49534FL ^ island
            );
            emitMaterializedStepKeys(
                helper.instructions,
                stepTable,
                helperKeyLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
                helperOutLocal,
                poisonSeed,
                EdgeRole.POISON
            );
            helper.instructions.add(
                new TypeInsnNode(
                    Opcodes.NEW,
                    "java/lang/IllegalStateException"
                )
            );
            helper.instructions.add(new InsnNode(Opcodes.DUP));
            helper.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESPECIAL,
                    "java/lang/IllegalStateException",
                    "<init>",
                    "()V",
                    false
                )
            );
            helper.instructions.add(new InsnNode(Opcodes.ATHROW));
            helper.maxLocals = 14;
            helper.maxStack = 32;
            JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
            clazz.asmNode().methods.add(helper);
            clazz.markDirty();
            publishGeneratedHelperFlowKey(pctx, owner, helperName, DESC, helperKeyLocal);
            return new IslandDispatchHelperPlan(
                helperName,
                cases.size(),
                helper.instructions.size()
            );
        }

        private void emitDynamicFakeSourceRouter(
            InsnList insns,
            List<LabelNode> fakeLabels,
            LabelNode poisonLabel,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            long groupSalt,
            int island
        ) {
            int bucketCount = 1;
            while (bucketCount < fakeLabels.size() + 1) {
                bucketCount <<= 1;
            }
            LabelNode outOfRange = new LabelNode();
            LabelNode[] labels = fakeLabels.toArray(new LabelNode[0]);
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            insns.add(new InsnNode(Opcodes.L2I));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            JvmPassBytecode.pushInt(insns, 32);
            insns.add(new InsnNode(Opcodes.LUSHR));
            insns.add(new InsnNode(Opcodes.L2I));
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
            insns.add(new InsnNode(Opcodes.IXOR));
            JvmPassBytecode.pushInt(
                insns,
                nonZeroInt(JvmPassBytecode.mix(groupSalt ^ 0x46414B4553524331L, island))
            );
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, 16);
            insns.add(new InsnNode(Opcodes.IUSHR));
            insns.add(new InsnNode(Opcodes.IXOR));
            JvmPassBytecode.pushInt(insns, bucketCount - 1);
            insns.add(new InsnNode(Opcodes.IAND));
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, fakeLabels.size());
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, outOfRange));
            insns.add(new TableSwitchInsnNode(0, fakeLabels.size() - 1, poisonLabel, labels));
            insns.add(outOfRange);
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new JumpInsnNode(Opcodes.GOTO, poisonLabel));
        }

        private CompressedIslandMaterialBlob compressedIslandMaterialWords(
            IslandGroup group,
            int island,
            List<Block> islandBlocks,
            int firstState,
            int fakeCount,
            long dispatchSeed,
            List<Integer> realDispatchTokens,
            List<Integer> fakeTokens,
            List<Integer> fakeStates,
            Map<LabelNode, CffBlockKeyState> keyStateByLabel,
            Map<LabelNode, Integer> resultTokens,
            List<Integer> realResultWordIndexes,
            int bounceToken,
            long methodSeed,
            long salt,
            long resultMaskSeed,
            boolean denseResultRouter
        ) {
            List<Integer> words = new ArrayList<>();
            Map<Integer, CffBlockKeyState> decodeStates = new HashMap<>();
            words.add(0x4346494D);
            words.add(1);
            words.add(island);
            words.add(firstState);
            words.add(fakeCount);
            words.add(denseResultRouter ? 1 : 0);
            words.add(islandBlocks.size());
            words.add(resultTokens.size());
            words.add(bounceToken);
            addMaterialLong(words, dispatchSeed);
            addMaterialLong(words, methodSeed);
            addMaterialLong(words, salt);
            addMaterialLong(words, resultMaskSeed);
            addMaterialLong(words, group.salt());
            for (int i = 0; i < islandBlocks.size(); i++) {
                Block block = islandBlocks.get(i);
                CffBlockKeyState blockKeys = requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
                words.add(1);
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    blockKeys,
                    realDispatchTokens.get(i)
                );
                addLiveDecodedMaterialWord(words, decodeStates, blockKeys, blockKeys.pcToken());
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    blockKeys,
                    nonZeroInt(JvmPassBytecode.mix(dispatchSeed, 0x44545041544831L))
                );
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    blockKeys,
                    nonZeroInt(JvmPassBytecode.mix(dispatchSeed, 0x4454424C4F434B31L)) | 1
                );
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    blockKeys,
                    nonZeroInt(JvmPassBytecode.mix(dispatchSeed, 0x44545043544F4B31L))
                );
                realResultWordIndexes.add(words.size());
                decodeStates.put(words.size(), blockKeys);
                words.add(resultTokens.get(block.label()));
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    blockKeys,
                    (int) resultMaskSeed
                );
                addMaterialKeyState(words, blockKeys);
            }
            CffBlockKeyState bounceKeys = firstIslandKeyState(
                group,
                island,
                keyStateByLabel
            );
            int bounceDomainToken = domainToken(group.salt(), island);
            long bounceDomainSeed = domainSeed(group);
            for (int fakeIndex = 0; fakeIndex < fakeCount; fakeIndex++) {
                long fakeSeed = edgeSeed(
                    salt,
                    group.hub(),
                    group.islandLabels()[island],
                    0x46414B4549534C45L ^ island ^ fakeIndex
                );
                words.add(2);
                words.add(fakeIndex);
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    bounceKeys,
                    fakeStates.get(fakeIndex)
                );
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    bounceKeys,
                    fakeTokens.get(fakeIndex)
                );
                words.add((int) ((fakeSeed >>> 37) & 3L));
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    bounceKeys,
                    firstState
                );
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    bounceKeys,
                    bounceToken
                );
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    bounceKeys,
                    bounceDomainToken
                );
                addLiveDecodedMaterialWord(
                    words,
                    decodeStates,
                    bounceKeys,
                    (int) resultMaskSeed
                );
                addMaterialLong(words, fakeSeed);
                addMaterialLong(words, bounceDomainSeed);
                addLiveMaterialKeyState(words, decodeStates, bounceKeys);
                addMaterialWords(words, stepMaterialValues(fakeSeed, EdgeRole.FAKE));
            }
            long poisonSeed = edgeSeed(
                salt,
                group.hub(),
                group.islandLabels()[island],
                0x4F5554504F49534FL ^ island
            );
            words.add(3);
            words.add(island);
            words.add((int) ((poisonSeed >>> 37) & 3L));
            addMaterialLong(words, poisonSeed);
            addMaterialWords(words, stepMaterialValues(poisonSeed, EdgeRole.POISON));
            for (Block block : islandBlocks) {
                CffBlockKeyState blockKeys = requireBlockKey(
                    block.label(),
                    keyStateByLabel.get(block.label())
                );
                words.add(4);
                words.add(resultTokens.get(block.label()));
                words.add(blockKeys.pcToken());
                words.add(blockKeys.guardKey());
                words.add(blockKeys.pathKey());
                words.add(blockKeys.blockKey());
            }
            int[] material = new int[words.size()];
            for (int i = 0; i < words.size(); i++) {
                material[i] = words.get(i);
            }
            CffBlockKeyState[] states = new CffBlockKeyState[material.length];
            for (Map.Entry<Integer, CffBlockKeyState> entry : decodeStates.entrySet()) {
                states[entry.getKey()] = entry.getValue();
            }
            return new CompressedIslandMaterialBlob(material, states);
        }

        private void recordIslandDryRun(
            int realBlocks,
            int fakeCount,
            boolean denseResultRouter,
            int resultTokens,
            int dispatchCases,
            int helperInstructions,
            int callSiteInstructions
        ) {
            CffIslandDryRunStats stats = cffIslandDryRunStats(pctx);
            boolean trivialCandidate = realBlocks == 1 && fakeCount == 0;
            int minimumCallerGrowthInstructions = Math.max(
                0,
                helperInstructions - callSiteInstructions
            );
            int dispatchRows = dispatchCases;
            int resultRows = resultTokens;
            int fakeBounceRows = fakeCount;
            int poisonRows = 1;
            int routerRows = Math.max(1, resultTokens);
            long materialWords =
                ((long) realBlocks * CFF_ISLAND_REAL_DISPATCH_ROW_WORDS) +
                    ((long) fakeCount * CFF_ISLAND_FAKE_DISPATCH_ROW_WORDS) +
                    ((long) resultTokens * CFF_ISLAND_RESULT_ROW_WORDS) +
                    ((long) fakeCount * CFF_ISLAND_FAKE_BOUNCE_ROW_WORDS) +
                    CFF_ISLAND_POISON_ROW_WORDS +
                    ((long) routerRows *
                        (denseResultRouter
                                ? CFF_ISLAND_DENSE_ROUTER_ROW_WORDS
                                : CFF_ISLAND_SPARSE_ROUTER_ROW_WORDS));
            long materialRows =
                (long) dispatchRows + resultRows + fakeBounceRows + poisonRows + routerRows;
            int projectedCallerDeltaInstructions =
                CFF_ISLAND_SHARED_CALLSITE_EXTRA_INSNS;
            int projectedSharedHelperInstructions =
                CFF_ISLAND_SHARED_HELPER_FIXED_INSNS +
                    (denseResultRouter
                            ? CFF_ISLAND_SHARED_DENSE_ROUTER_INSNS
                            : CFF_ISLAND_SHARED_SPARSE_ROUTER_INSNS);
            stats.record(
                currentMethodKey(),
                trivialCandidate,
                realBlocks,
                fakeCount,
                denseResultRouter,
                resultTokens,
                dispatchCases,
                helperInstructions,
                callSiteInstructions,
                minimumCallerGrowthInstructions,
                dispatchRows,
                resultRows,
                fakeBounceRows,
                poisonRows,
                routerRows,
                materialRows,
                materialWords,
                projectedCallerDeltaInstructions,
                projectedSharedHelperInstructions
            );
        }

        private void recordIslandMaterialOpDryRun(
            IslandGroup group,
            int island,
            int fakeCount,
            boolean denseResultRouter,
            int resultTokens,
            long salt
        ) {
            int fakeStepRows = fakeCount;
            int poisonStepRows = 1;
            int firstTinyUpdates = 0;
            int secondTinyUpdates = 0;
            int methodKeyUpdates = 0;
            int bouncePredicateRows = 0;
            for (int fakeIndex = 0; fakeIndex < fakeCount; fakeIndex++) {
                long fakeSeed = edgeSeed(
                    salt,
                    group.hub(),
                    group.islandLabels()[island],
                    0x46414B4549534C45L ^ island ^ fakeIndex
                );
                StepDryRun fakeStep = stepDryRun(fakeSeed, EdgeRole.FAKE);
                firstTinyUpdates += fakeStep.firstTinyUpdates();
                secondTinyUpdates += fakeStep.secondTinyUpdates();
                methodKeyUpdates += fakeStep.methodKeyUpdates();
                int bounceMode = (int) ((fakeSeed >>> 37) & 3L);
                if (bounceMode == 2 || bounceMode == 3) {
                    bouncePredicateRows++;
                }
            }
            long poisonSeed = edgeSeed(
                salt,
                group.hub(),
                group.islandLabels()[island],
                0x4F5554504F49534FL ^ island
            );
            StepDryRun poisonStep = stepDryRun(poisonSeed, EdgeRole.POISON);
            firstTinyUpdates += poisonStep.firstTinyUpdates();
            secondTinyUpdates += poisonStep.secondTinyUpdates();
            methodKeyUpdates += poisonStep.methodKeyUpdates();
            CffIslandMaterialOpDryRunStats stats = cffIslandMaterialOpDryRunStats(pctx);
            stats.record(
                currentMethodKey(),
                fakeStepRows,
                poisonStepRows,
                firstTinyUpdates,
                secondTinyUpdates,
                methodKeyUpdates,
                fakeCount,
                bouncePredicateRows,
                denseResultRouter ? resultTokens : 0,
                denseResultRouter ? 0 : resultTokens,
                1
            );
        }

        private String currentMethodKey() {
            String name = pctx.currentMethodName();
            String desc = pctx.currentMethodDesc();
            if (name == null || desc == null) {
                return owner + ".<class>";
            }
            return owner + "." + name + desc;
        }

        private void emitOutlinedFakeCaseBounce(
            InsnList insns,
            IslandGroup group,
            int island,
            int state,
            Map<LabelNode, CffBlockKeyState> keyStateByLabel,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            int domainLocal,
            int outLocal,
            long methodSeed,
            long seed,
            int bounceToken,
            long resultMaskSeed,
            boolean denseResultRouter
        ) {
            LabelNode hop = new LabelNode();
            LabelNode pass = new LabelNode();
            LabelNode done = new LabelNode();
            CffBlockKeyState bounceKeys = firstIslandKeyState(
                group,
                island,
                keyStateByLabel
            );
            DispatchTarget bounceTarget = new DispatchTarget(
                group.hub(),
                group.islandLabels(),
                group.aliasHubs(),
                island,
                group.salt() ^ island,
                domainSeed(group),
                domainToken(group.salt(), island)
            );
            long domainSeed = domainSeed(group);
            switch ((int) ((seed >>> 37) & 3L)) {
                case 0 -> {
                    emitStorePc(
                        insns,
                        pcLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        state,
                        bounceKeys,
                        methodSeed,
                        bounceTarget.selectorSeed(),
                        8
                    );
                    emitStoreMethodKey(
                        insns,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        bounceKeys
                    );
                    emitStoreDomain(
                        insns,
                        domainLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        island,
                        bounceTarget.domainToken(),
                        bounceKeys,
                        methodSeed,
                        domainSeed,
                        8
                    );
                }
                case 1 -> {
                    emitStorePc(
                        insns,
                        pcLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        state,
                        bounceKeys,
                        methodSeed,
                        bounceTarget.selectorSeed(),
                        8
                    );
                    emitStoreMethodKey(
                        insns,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        bounceKeys
                    );
                    insns.add(new JumpInsnNode(Opcodes.GOTO, hop));
                    insns.add(hop);
                    emitStoreDomain(
                        insns,
                        domainLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        island,
                        bounceTarget.domainToken(),
                        bounceKeys,
                        methodSeed,
                        domainSeed,
                        8
                    );
                }
                case 2 -> {
                    emitStoreDomain(
                        insns,
                        domainLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        island,
                        bounceTarget.domainToken(),
                        bounceKeys,
                        methodSeed,
                        domainSeed,
                        8
                    );
                    emitStorePc(
                        insns,
                        pcLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        state,
                        bounceKeys,
                        methodSeed,
                        bounceTarget.selectorSeed(),
                        8
                    );
                    emitStoreMethodKey(
                        insns,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        bounceKeys
                    );
                    emitKeyPredicate(
                        insns,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        seed
                    );
                    insns.add(new JumpInsnNode(Opcodes.IFNE, pass));
                    insns.add(new JumpInsnNode(Opcodes.GOTO, done));
                    insns.add(pass);
                    insns.add(new JumpInsnNode(Opcodes.GOTO, done));
                    insns.add(done);
                }
                default -> {
                    emitStorePc(
                        insns,
                        pcLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        state,
                        bounceKeys,
                        methodSeed,
                        bounceTarget.selectorSeed(),
                        8
                    );
                    emitStoreMethodKey(
                        insns,
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        pcLocal,
                        bounceKeys
                    );
                    emitKeyPredicate(
                        insns,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        seed ^ 0x504154484F504151L
                    );
                    insns.add(new JumpInsnNode(Opcodes.IFEQ, hop));
                    emitStoreDomain(
                        insns,
                        domainLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        island,
                        bounceTarget.domainToken(),
                        bounceKeys,
                        methodSeed,
                        domainSeed,
                        8
                    );
                    insns.add(new JumpInsnNode(Opcodes.GOTO, done));
                    insns.add(hop);
                    emitStoreDomain(
                        insns,
                        domainLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        island,
                        bounceTarget.domainToken(),
                        bounceKeys,
                        methodSeed,
                        domainSeed,
                        8
                    );
                    insns.add(done);
                }
            }
            finishOutlinedDispatchReturn(
                insns,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                outLocal,
                bounceToken,
                resultMaskSeed,
                denseResultRouter
            );
        }

        private void finishOutlinedDispatchReturn(
            InsnList insns,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            int domainLocal,
            int outLocal,
            int resultToken,
            long resultMaskSeed,
            boolean denseResultRouter
        ) {
            if (denseResultRouter) {
                emitTransitionOutStoresWithMaskedResult(
                    insns,
                    outLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyLocal,
                    resultToken,
                    resultMaskSeed
                );
            } else {
                emitTransitionOutStoresWithResult(
                    insns,
                    outLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    resultToken
                );
            }
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            insns.add(new InsnNode(Opcodes.LRETURN));
        }

        private void finishOutlinedDispatchReturnFromLocal(
            InsnList insns,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            int domainLocal,
            int outLocal,
            int resultLocal,
            long resultMaskSeed,
            boolean denseResultRouter
        ) {
            if (denseResultRouter) {
                emitTransitionOutStoresWithMaskedResultLocal(
                    insns,
                    outLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyLocal,
                    resultLocal,
                    resultMaskSeed
                );
            } else {
                emitTransitionOutStoresWithResultLocal(
                    insns,
                    outLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    resultLocal
                );
            }
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            insns.add(new InsnNode(Opcodes.LRETURN));
        }

        private void emitSubdispatchPackedToken(
            InsnList insns,
            int keyLocal,
            int resultToken,
            int tokenLocal
        ) {
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            JvmPassBytecode.pushLong(
                insns,
                JvmPassBytecode.mix(0x5355424449535031L, resultToken)
            );
            insns.add(new InsnNode(Opcodes.LXOR));
            insns.add(new InsnNode(Opcodes.DUP2));
            JvmPassBytecode.pushInt(insns, 29);
            insns.add(new InsnNode(Opcodes.LUSHR));
            insns.add(new InsnNode(Opcodes.LXOR));
            JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
            insns.add(new InsnNode(Opcodes.LMUL));
            JvmPassBytecode.pushLong(
                insns,
                (Integer.toUnsignedLong(resultToken) << 32) ^
                    Integer.toUnsignedLong(resultToken * 0x45D9F3B)
            );
            insns.add(new InsnNode(Opcodes.LXOR));
            insns.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
        }

        InsnList emitCall(
            int state,
            DispatchTarget target,
            EdgeKind edgeKind,
            LabelNode jumpTarget,
            int keyLocal,
            int guardLocal,
            int pathKeyLocal,
            int blockKeyLocal,
            int pcLocal,
            int domainLocal,
            int keyTmpLocal,
            CffBlockKeyState sourceKeys,
            CffBlockKeyState targetKeys,
            long methodSeed,
            long stepSeed,
            EdgeRole role
        ) {
            boolean materialTransition = true;
            CffClassKeyTable table = null;
            int rowBase = -1;
            String helperName = null;
            if (materialTransition) {
                table = ensureClassKeyTable(pctx, clazz);
                rowBase = registerTransitionMaterialRow(
                    table,
                    state,
                    target,
                    edgeKind,
                    sourceKeys,
                    targetKeys,
                    methodSeed,
                    stepSeed,
                    role
                );
            } else {
                helperName = createHelper(
                    state,
                    target,
                    edgeKind,
                    sourceKeys,
                    targetKeys,
                    methodSeed,
                    stepSeed,
                    role
                );
            }
            InsnList insns = new InsnList();
            insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
            if (materialTransition) {
                insns.add(new FieldInsnNode(
                    Opcodes.GETSTATIC,
                    table.owner(),
                    table.objectFieldName(),
                    "[Ljava/lang/Object;"
                ));
                JvmPassBytecode.pushInt(insns, rowBase);
            } else {
                insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
            }
            insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
            insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
            if (materialTransition) {
                insns.add(
                    new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        table.transitionMaterialHelperOwner(),
                        table.transitionMaterialHelperName(),
                        TRANSITION_MATERIAL_HELPER_DESC,
                        table.transitionMaterialHelperInterfaceOwner()
                    )
                );
            } else {
                insns.add(
                    new MethodInsnNode(
                        Opcodes.INVOKESTATIC,
                        owner,
                        helperName,
                        DESC,
                        interfaceOwner
                    )
                );
            }
            insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
            emitTransitionOutLoads(
                insns,
                outLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                edgeKind != EdgeKind.DIRECT_ISLAND
            );
            insns.add(new JumpInsnNode(Opcodes.GOTO, jumpTarget));
            JvmKeyDispatchPass.markGenerated(pctx, insns);
            return insns;
        }

        private String createHelper(
            int state,
            DispatchTarget target,
            EdgeKind edgeKind,
            CffBlockKeyState sourceKeys,
            CffBlockKeyState targetKeys,
            long methodSeed,
            long stepSeed,
            EdgeRole role
        ) {
            CffClassKeyTable table = ensureClassKeyTable(pctx, clazz);
            int rowBase = registerTransitionMaterialRow(
                table,
                state,
                target,
                edgeKind,
                sourceKeys,
                targetKeys,
                methodSeed,
                stepSeed,
                role
            );
            String helperName = nextHelperName();
            int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
            access |= interfaceOwner ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
            MethodNode helper = new MethodNode(
                access,
                helperName,
                DESC,
                null,
                null
            );
            int helperKeyLocal = 0;
            int helperGuardLocal = 2;
            int helperPathLocal = 3;
            int helperBlockLocal = 4;
            int helperPcLocal = 5;
            int helperDomainLocal = 6;
            int helperOutLocal = 7;
            helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, helperKeyLocal));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, helperGuardLocal));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, helperPathLocal));
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, helperBlockLocal));
            helper.instructions.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                table.owner(),
                table.objectFieldName(),
                "[Ljava/lang/Object;"
            ));
            JvmPassBytecode.pushInt(helper.instructions, rowBase);
            helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, helperDomainLocal));
            helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, helperOutLocal));
            helper.instructions.add(
                new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    table.transitionMaterialHelperOwner(),
                    table.transitionMaterialHelperName(),
                    TRANSITION_MATERIAL_HELPER_DESC,
                    table.transitionMaterialHelperInterfaceOwner()
                )
            );
            helper.instructions.add(new InsnNode(Opcodes.LRETURN));
            helper.maxLocals = 8;
            helper.maxStack = 32;
            JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
            clazz.asmNode().methods.add(helper);
            clazz.markDirty();
            publishGeneratedHelperFlowKey(pctx, owner, helperName, DESC, helperKeyLocal);
            return helperName;
        }

        private final class RouterState {
            final LabelNode label = new LabelNode();
            final TreeMap<Integer, LabelNode> resultCases = new TreeMap<>();
            boolean denseResultRouter;
        }

        private String nextHelperName() {
            String base = "__neko_cff$";
            String candidate;
            do {
                candidate = base + Integer.toUnsignedString(counter++, 36);
            } while (helperExists(candidate));
            return candidate;
        }

        private boolean helperExists(String name) {
            for (MethodNode method : clazz.asmNode().methods) {
                if (name.equals(method.name) && DESC.equals(method.desc)) {
                    return true;
                }
            }
            return false;
        }
    }

    private static CffIslandDryRunStats cffIslandDryRunStats(PipelineContext pctx) {
        CffIslandDryRunStats stats = pctx.getPassData(CFF_ISLAND_DRY_RUN_STATS);
        if (stats == null) {
            stats = new CffIslandDryRunStats();
            pctx.putPassData(CFF_ISLAND_DRY_RUN_STATS, stats);
        }
        return stats;
    }

    private static CffIslandMaterialOpDryRunStats cffIslandMaterialOpDryRunStats(
        PipelineContext pctx
    ) {
        CffIslandMaterialOpDryRunStats stats = pctx.getPassData(
            CFF_ISLAND_MATERIAL_OP_DRY_RUN_STATS
        );
        if (stats == null) {
            stats = new CffIslandMaterialOpDryRunStats();
            pctx.putPassData(CFF_ISLAND_MATERIAL_OP_DRY_RUN_STATS, stats);
        }
        return stats;
    }


    @SuppressWarnings("unchecked")
    private void publishGeneratedHelperFlowKey(
        PipelineContext pctx,
        String owner,
        String name,
        String desc,
        int keyLocal
    ) {
        Map<String, Integer> locals = pctx.getPassData(JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD);
        if (locals == null) {
            locals = new LinkedHashMap<>();
            pctx.putPassData(JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD, locals);
        }
        locals.put(owner + "." + name + desc, keyLocal);
    }



    record BranchOperand(int storeOpcode, int loadOpcode, int size) {
        static final BranchOperand INT = new BranchOperand(
            Opcodes.ISTORE,
            Opcodes.ILOAD,
            1
        );
        static final BranchOperand REF = new BranchOperand(
            Opcodes.ASTORE,
            Opcodes.ALOAD,
            1
        );
    }

    record EdgeTarget(LabelNode label, AbstractInsnNode framePoint) {}

    record EdgeTargets(List<EdgeTarget> labels, int consumedValues) {}

    record EdgeStackSpill(StackSpill spill, int consumedValues) {}

    public record CffMethodMetadata(
        long methodSeed,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int domainLocal,
        Set<AbstractInsnNode> applicationInstructions,
        Map<AbstractInsnNode, CffInstructionState> instructionStates,
        CffClassKeyTable classKeyTable
    ) {}

    public record CffInstructionState(
        int blockIndex,
        int state,
        long selectorSeed,
        int guardKey,
        int pathKey,
        int blockKey,
        int pcToken,
        long methodKey,
        long methodSalt
    ) {}

    record CffBlockKeyState(
        int guardKey,
        int pathKey,
        int blockKey,
        int pcToken,
        long methodKey,
        long methodSalt
    ) {}

    record StackSpill(List<BasicValue> values, int[] locals) {}

    public record CffClassKeyTable(
        String owner,
        PipelineContext pctx,
        L1Class clazz,
        String objectFieldName,
        String intHelperName,
        String intHelperOwner,
        boolean intHelperInterfaceOwner,
        String objectHelperName,
        String controlHelperName,
        String tokenHelperName,
        String tokenMaterialHelperName,
        String tokenMaterialHelperOwner,
        boolean tokenMaterialHelperInterfaceOwner,
        String transitionMaterialHelperName,
        String transitionMaterialHelperOwner,
        boolean transitionMaterialHelperInterfaceOwner,
        String stepMaterialHelperName,
        String stepMaterialHelperOwner,
        boolean stepMaterialHelperInterfaceOwner,
        String keyTransferMaterialHelperName,
        String keyTransferMaterialHelperOwner,
        boolean keyTransferMaterialHelperInterfaceOwner,
        String islandRuntimeSourceHelperName,
        String islandRuntimeSourceHelperOwner,
        boolean islandRuntimeSourceHelperInterfaceOwner,
        String islandMaterialHelperName,
        String islandMaterialHelperOwner,
        boolean islandMaterialHelperInterfaceOwner,
        String islandMaterialUnpackHelperName,
        String islandMaterialUnpackHelperOwner,
        boolean islandMaterialUnpackHelperInterfaceOwner,
        String digestHelperName,
        String dispatchHelperName,
        String methodKeyHelperName,
        String methodKeyHelperOwner,
        boolean methodKeyHelperInterfaceOwner,
        int[] tokenHelperCounter,
        List<MethodNode> tokenMaterialInitHelpers,
        int[] transitionMaterialCounter,
        List<MethodNode> transitionMaterialInitHelpers,
        int[] stepMaterialCounter,
        List<MethodNode> stepMaterialInitHelpers,
        int[] islandMaterialCounter,
        List<MethodNode> islandMaterialInitHelpers,
        int[] values,
        int[] objectValues,
        int clinitMask,
        int initCarrierLocal,
        LabelNode initStart,
        LabelNode initEnd,
        boolean generatedClinit,
        boolean interfaceOwner
    ) {
        int token(int value, long siteSeed) {
            long mixed = JvmPassBytecode.mix(siteSeed, value);
            return (int) (mixed & (values.length - 1));
        }
    }

    record CffSharedClassHelpers(
        String intHelperOwner,
        String intHelperName,
        boolean intHelperInterfaceOwner,
        String tokenMaterialHelperOwner,
        String tokenMaterialHelperName,
        boolean tokenMaterialHelperInterfaceOwner,
        String transitionMaterialHelperOwner,
        String transitionMaterialHelperName,
        boolean transitionMaterialHelperInterfaceOwner,
        String keyTransferMaterialHelperOwner,
        String keyTransferMaterialHelperName,
        boolean keyTransferMaterialHelperInterfaceOwner,
        String stepMaterialHelperOwner,
        String stepMaterialHelperName,
        boolean stepMaterialHelperInterfaceOwner,
        String islandRuntimeSourceHelperOwner,
        String islandRuntimeSourceHelperName,
        boolean islandRuntimeSourceHelperInterfaceOwner,
        String islandMaterialHelperOwner,
        String islandMaterialHelperName,
        boolean islandMaterialHelperInterfaceOwner,
        String islandMaterialUnpackHelperOwner,
        String islandMaterialUnpackHelperName,
        boolean islandMaterialUnpackHelperInterfaceOwner,
        String methodKeyHelperOwner,
        String methodKeyHelperName,
        boolean methodKeyHelperInterfaceOwner
    ) {}

    record StepDryRun(
        int firstTinyUpdates,
        int secondTinyUpdates,
        int methodKeyUpdates
    ) {}

    record CompressedIslandMaterialBlob(
        int[] words,
        CffBlockKeyState[] decodeStates
    ) {}

    record IslandDispatchHelperPlan(
        String name,
        int dispatchCases,
        int helperInstructions
    ) {}
}
