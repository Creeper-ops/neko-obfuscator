package dev.nekoobfuscator.transforms.jvm.cff;

import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningLr.*;
import static dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningVerify.*;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
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


abstract class CffClassSetup extends CffSharedState {

    protected void logIslandDryRunMethodStats(PipelineContext pctx, String methodKey) {
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

    protected static long ceilDiv(long value, long divisor) {
        if (value <= 0L) {
            return 0L;
        }
        return ((value - 1L) / divisor) + 1L;
    }

    protected boolean isApplicationMethod(
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

    protected boolean hasApplicationCode(PipelineContext pctx, L1Class clazz) {
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

    protected void lowerStringConstantValuesForStringPass(PipelineContext pctx) {
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

    protected void lowerStringConstantValues(L1Class clazz) {
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

    protected boolean isStringConstantValue(FieldNode field) {
        return (field.access & Opcodes.ACC_STATIC) != 0
            && "Ljava/lang/String;".equals(field.desc)
            && field.value instanceof String;
    }

    protected void prepareClassKeyTables(PipelineContext pctx) {
        if (Boolean.TRUE.equals(pctx.getPassData(CLASS_KEY_TABLES_PREPARED))) {
            return;
        }
        for (L1Class clazz : new ArrayList<>(pctx.classMap().values())) {
            if (hasApplicationCode(pctx, clazz)) {
                ensureClassKeyTable(pctx, clazz);
            }
        }
        pctx.putPassData(CLASS_KEY_TABLES_PREPARED, Boolean.TRUE);
    }

    @SuppressWarnings("unchecked")
    protected CffG18GlobalState ensureG18GlobalState(PipelineContext pctx, L1Class requestingClass, long seed) {
        CffG18GlobalState existing = pctx.getPassData(G18_GLOBAL_STATE);
        if (existing != null) return existing;
        L1Class host = selectG18GlobalHost(pctx, requestingClass);
        if ((host.asmNode().access & Opcodes.ACC_PUBLIC) == 0) {
            host.asmNode().access =
                (host.asmNode().access & ~(Opcodes.ACC_PRIVATE | Opcodes.ACC_PROTECTED)) |
                Opcodes.ACC_PUBLIC;
        }
        long hostSeed = JvmPassBytecode.mix(
            pctx.masterSeed() ^ 0x473138474C4F424CL,
            host.name().hashCode()
        );
        String globalFieldName = uniqueFieldName(
            host,
            "$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(hostSeed, 0x4731384741525259L), 36)
        );
        String nodeFieldName = globalFieldName;
        String ownerRegistryFieldName = globalFieldName;
        String helperName = uniqueMethodName(
            host,
            "__neko_g18$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(hostSeed, 0x47313848454C504CL), 36),
            G18_GLOBAL_HELPER_DESC
        );
        int capacity = Math.max(1, pctx.classMap().size() + 16);
        long rootMask = nonZeroLong(JvmPassBytecode.mix(hostSeed, 0x473138524F4F544DL));
        long globalInitial = nonZeroLong(JvmPassBytecode.mix(hostSeed, 0x47313847494E4954L));
        long globalMutationMask = nonZeroLong(JvmPassBytecode.mix(hostSeed, 0x473138474D555441L));
        long nodeMutationMask = nonZeroLong(JvmPassBytecode.mix(hostSeed, 0x4731384E4D555441L));
        int fieldAccess = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        host.asmNode().fields.add(new FieldNode(fieldAccess, globalFieldName, "Ljava/lang/Object;", null, null));
        installG18GlobalHelper(
            pctx,
            host,
            globalFieldName,
            nodeFieldName,
            ownerRegistryFieldName,
            helperName,
            capacity,
            globalInitial,
            rootMask,
            globalMutationMask,
            nodeMutationMask
        );
        CffG18GlobalState created = new CffG18GlobalState(
            host.name(),
            globalFieldName,
            nodeFieldName,
            ownerRegistryFieldName,
            helperName,
            host.isInterface(),
            capacity,
            rootMask,
            globalInitial,
            globalMutationMask,
            nodeMutationMask,
            new int[1]
        );
        pctx.putPassData(G18_GLOBAL_STATE, created);
        host.markDirty();
        return created;
    }

    private L1Class selectG18GlobalHost(PipelineContext pctx, L1Class defaultClass) {
        L1Class publicBest = null;
        L1Class anyBest = null;
        for (L1Class candidate : pctx.classMap().values()) {
            if (candidate.isInterface()) continue;
            if (anyBest == null || candidate.name().compareTo(anyBest.name()) < 0) {
                anyBest = candidate;
            }
            if ((candidate.asmNode().access & Opcodes.ACC_PUBLIC) == 0) continue;
            if (publicBest == null || candidate.name().compareTo(publicBest.name()) < 0) {
                publicBest = candidate;
            }
        }
        if (publicBest != null) return publicBest;
        return anyBest != null ? anyBest : defaultClass;
    }

    private void installG18GlobalHelper(
        PipelineContext pctx,
        L1Class host,
        String globalFieldName,
        String nodeFieldName,
        String ownerRegistryFieldName,
        String helperName,
        int capacity,
        long globalInitial,
        long rootMask,
        long globalMutationMask,
        long nodeMutationMask
    ) {
        MethodNode helper = new MethodNode(
            Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNCHRONIZED | Opcodes.ACC_SYNTHETIC,
            helperName,
            G18_GLOBAL_HELPER_DESC,
            null,
            null
        );
        int indexLocal = 0;
        int initialLocal = 1;
        int deltaLocal = 3;
        int ownerLocal = 5;
        int carrierLocal = 6;
        int globalCellLocal = 7;
        int nodesLocal = 8;
        int nodeLocal = 9;
        int rootLocal = 11;
        int globalOldLocal = 13;
        int ownerHashLocal = 15;
        int registryLocal = 16;
        int nodeCellLocal = 17;
        InsnList insns = helper.instructions;
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            host.name(),
            globalFieldName,
            "Ljava/lang/Object;"
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
        insns.add(new InsnNode(Opcodes.DUP));
        LabelNode carrierReady = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, carrierReady));
        insns.add(new InsnNode(Opcodes.POP));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, carrierLocal));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/atomic/AtomicLong"));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushLong(insns, globalInitial);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "java/util/concurrent/atomic/AtomicLong",
            "<init>",
            "(J)V",
            false
        ));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/ArrayList"));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, capacity);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "java/util/ArrayList",
            "<init>",
            "(I)V",
            false
        ));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.ICONST_2));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/Vector"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "java/util/Vector",
            "<init>",
            "()V",
            false
        ));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new FieldInsnNode(
            Opcodes.PUTSTATIC,
            host.name(),
            globalFieldName,
            "Ljava/lang/Object;"
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(carrierReady);
        insns.add(new VarInsnNode(Opcodes.ASTORE, carrierLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        LabelNode ownerNull = new LabelNode();
        LabelNode ownerHashReady = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNULL, ownerNull));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
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
        insns.add(new JumpInsnNode(Opcodes.GOTO, ownerHashReady));
        insns.add(ownerNull);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(ownerHashReady);
        insns.add(new VarInsnNode(Opcodes.ISTORE, ownerHashLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/concurrent/atomic/AtomicLong"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, globalCellLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/ArrayList"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, nodesLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.ICONST_2));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/Vector"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, registryLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, registryLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/Vector",
            "add",
            "(Ljava/lang/Object;)Z",
            false
        ));
        insns.add(new InsnNode(Opcodes.POP));
        LabelNode growList = new LabelNode();
        LabelNode listReady = new LabelNode();
        insns.add(growList);
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodesLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/ArrayList",
            "size",
            "()I",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGT, listReady));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodesLocal));
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/ArrayList",
            "add",
            "(Ljava/lang/Object;)Z",
            false
        ));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, growList));
        insns.add(listReady);
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodesLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/ArrayList",
            "get",
            "(I)Ljava/lang/Object;",
            false
        ));
        insns.add(new InsnNode(Opcodes.DUP));
        LabelNode nodeReady = new LabelNode();
        LabelNode nodeLoaded = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, nodeReady));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/util/concurrent/atomic/AtomicLong"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.LLOAD, initialLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "java/util/concurrent/atomic/AtomicLong",
            "<init>",
            "(J)V",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, nodeCellLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodesLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodeCellLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/ArrayList",
            "set",
            "(ILjava/lang/Object;)Ljava/lang/Object;",
            false
        ));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, nodeLoaded));
        insns.add(nodeReady);
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/util/concurrent/atomic/AtomicLong"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, nodeCellLocal));
        insns.add(nodeLoaded);
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodeCellLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicLong",
            "get",
            "()J",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.LSTORE, nodeLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, globalCellLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicLong",
            "get",
            "()J",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.LSTORE, globalOldLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, nodeLocal));
        JvmPassBytecode.pushLong(insns, rootMask);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, ownerHashLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        emitG18Projection(insns);
        insns.add(new VarInsnNode(Opcodes.LSTORE, rootLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, globalCellLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, globalOldLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, rootLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, deltaLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, ownerHashLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, globalMutationMask);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicLong",
            "set",
            "(J)V",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nodeCellLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, nodeLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, deltaLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, globalOldLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, rootLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, ownerHashLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, nodeMutationMask);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/util/concurrent/atomic/AtomicLong",
            "set",
            "(J)V",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.LLOAD, rootLocal));
        insns.add(new InsnNode(Opcodes.LRETURN));
        helper.maxLocals = 18;
        helper.maxStack = 12;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        host.asmNode().methods.add(helper);
    }

    private void emitG18Projection(InsnList insns) {
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 33);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0xff51afd7ed558ccdL);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 29);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0xc4ceb9fe1a85ec53L);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x0000FFFFFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
    }

    @SuppressWarnings("unchecked")
    protected CffClassKeyTable ensureClassKeyTable(
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
        CffG18GlobalState g18GlobalState = ensureG18GlobalState(pctx, clazz, seed);
        int g18ClassIndex = g18GlobalState.allocateClassIndex();
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
            clazz.isInterface(),
            g18GlobalState,
            g18ClassIndex
        );
        installClassKeyTableInit(pctx, clazz, data);
        tables.put(clazz.name(), data);
        clazz.markDirty();
        return data;
    }

    protected void installStackWalkerMixHelper(
        PipelineContext pctx,
        L1Class clazz,
        String helperName,
        int access
    ) {
        MethodNode helper = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            helperName,
            CFF_STACK_MIX_HELPER_DESC,
            null,
            null
        );
        int sourceLocal = 0;
        int streamLocal = 1;
        int iteratorLocal = 2;
        int frameLocal = 3;
        int indexLocal = 4;
        int firstDepthLocal = 5;
        int secondDepthLocal = 6;
        InsnList insns = helper.instructions;
        insns.add(new VarInsnNode(Opcodes.ALOAD, streamLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/util/stream/Stream",
            "iterator",
            "()Ljava/util/Iterator;",
            true
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, iteratorLocal));
        emitRuntimeStackDepths(insns, sourceLocal, firstDepthLocal, secondDepthLocal);
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode mixFrame = new LabelNode();
        LabelNode skipFrame = new LabelNode();
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ALOAD, iteratorLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/util/Iterator",
            "hasNext",
            "()Z",
            true
        ));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, done));
        insns.add(new VarInsnNode(Opcodes.ALOAD, iteratorLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/util/Iterator",
            "next",
            "()Ljava/lang/Object;",
            true
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/StackWalker$StackFrame"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, frameLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, firstDepthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, mixFrame));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, secondDepthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, skipFrame));
        insns.add(mixFrame);
        emitStackWalkerFrameMix(insns, sourceLocal, frameLocal);
        insns.add(skipFrame);
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, secondDepthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, loop));
        insns.add(done);
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Integer",
            "valueOf",
            "(I)Ljava/lang/Integer;",
            false
        ));
        insns.add(new InsnNode(Opcodes.ARETURN));
        helper.maxLocals = 7;
        helper.maxStack = 8;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
    }

    protected void emitRuntimeStackDepths(
        InsnList insns,
        int sourceLocal,
        int firstDepthLocal,
        int secondDepthLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, 7);
        insns.add(new InsnNode(Opcodes.IAND));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, firstDepthLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, firstDepthLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        JvmPassBytecode.pushInt(insns, 5);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, secondDepthLocal));
    }

    protected void emitStackWalkerFrameMix(
        InsnList insns,
        int sourceLocal,
        int frameLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/lang/StackWalker$StackFrame",
            "getDeclaringClass",
            "()Ljava/lang/Class;",
            true
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Object",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/lang/StackWalker$StackFrame",
            "getMethodName",
            "()Ljava/lang/String;",
            true
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEINTERFACE,
            "java/lang/StackWalker$StackFrame",
            "getByteCodeIndex",
            "()I",
            true
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
    }

    protected void emitRuntimeStackSourceMix(
        InsnList insns,
        int sourceLocal,
        int firstDepthLocal,
        int stackLocal,
        int secondDepthLocal,
        String stackMixOwner,
        String stackMixName,
        boolean stackMixInterfaceOwner
    ) {
        LabelNode legacyStackTrace = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new LdcInsnNode("java.specification.version"));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "getProperty",
            "(Ljava/lang/String;)Ljava/lang/String;",
            false
        ));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "charAt",
            "(I)C",
            false
        ));
        JvmPassBytecode.pushInt(insns, '1');
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, legacyStackTrace));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            "java/lang/StackWalker$Option",
            "RETAIN_CLASS_REFERENCE",
            "Ljava/lang/StackWalker$Option;"
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/StackWalker",
            "getInstance",
            "(Ljava/lang/StackWalker$Option;)Ljava/lang/StackWalker;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InvokeDynamicInsnNode(
            "apply",
            "(I)Ljava/util/function/Function;",
            new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/LambdaMetafactory",
                "metafactory",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodType;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
            ),
            Type.getType("(Ljava/lang/Object;)Ljava/lang/Object;"),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                stackMixOwner,
                stackMixName,
                CFF_STACK_MIX_HELPER_DESC,
                stackMixInterfaceOwner
            ),
            Type.getType("(Ljava/util/stream/Stream;)Ljava/lang/Integer;")
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackWalker",
            "walk",
            "(Ljava/util/function/Function;)Ljava/lang/Object;",
            false
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Integer"));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Integer",
            "intValue",
            "()I",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(legacyStackTrace);
        emitRuntimeStackTraceSourceMix(
            insns,
            sourceLocal,
            firstDepthLocal,
            stackLocal,
            secondDepthLocal
        );
        insns.add(done);
    }

    protected void emitRuntimeStackTraceSourceMix(
        InsnList insns,
        int sourceLocal,
        int firstDepthLocal,
        int stackLocal,
        int secondDepthLocal
    ) {
        LabelNode firstDone = new LabelNode();
        LabelNode secondDone = new LabelNode();
        emitRuntimeStackDepths(insns, sourceLocal, firstDepthLocal, secondDepthLocal);
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
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new VarInsnNode(Opcodes.ILOAD, firstDepthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, firstDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, firstDepthLocal));
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
        insns.add(firstDone);
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new VarInsnNode(Opcodes.ILOAD, secondDepthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, secondDone));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, secondDepthLocal));
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
        insns.add(secondDone);
    }

    @SuppressWarnings("unchecked")
    protected CffSharedClassHelpers ensureSharedClassHelpers(
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
        String stackMixHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_stack$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x535441434B534831L), 36),
            CFF_STACK_MIX_HELPER_DESC
        );
        String methodKeyHelperName = uniqueMethodName(
            clazz,
            "__neko_cff_mkey$" + Integer.toUnsignedString((int) JvmPassBytecode.mix(seed, 0x4D4B48454C534831L), 36),
            "(IIIIJJ)J"
        );
        int access = Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
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
            clazz.isInterface(),
            clazz.name(),
            stackMixHelperName,
            clazz.isInterface()
        );
        installStepMaterialHelper(
            pctx,
            clazz,
            stepMaterialHelperName,
            access,
            clazz.name(),
            stackMixHelperName,
            clazz.isInterface()
        );
        installCffIslandRuntimeSourceHelper(
            pctx,
            clazz,
            islandRuntimeSourceHelperName,
            access,
            clazz.name(),
            stackMixHelperName,
            clazz.isInterface()
        );
        installStackWalkerMixHelper(pctx, clazz, stackMixHelperName, access);
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
    protected List<CffClassKeyTable> classKeyTables(PipelineContext pctx) {
        Map<String, CffClassKeyTable> tables = pctx.getPassData(
            CLASS_KEY_TABLES
        );
        if (tables == null || tables.isEmpty()) return List.of();
        return List.copyOf(tables.values());
    }

    protected Set<LabelNode> rewriteInjectedMemberReflection(
        PipelineContext pctx,
        MethodNode mn
    ) {
        return CffReflectionMemberFilters.rewrite(pctx, mn, classKeyTables(pctx));
    }

    @SuppressWarnings("unchecked")
    protected boolean isGeneratedTableClassInit(
        PipelineContext pctx,
        L1Class clazz
    ) {
        Map<String, CffClassKeyTable> tables = pctx.getPassData(
            CLASS_KEY_TABLES
        );
        CffClassKeyTable table = tables == null ? null : tables.get(clazz.name());
        return table != null && table.generatedClinit();
    }

    protected String uniqueFieldName(L1Class clazz, String base) {
        String candidate = base;
        int suffix = 0;
        while (clazz.findField(candidate, "[I") != null) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    protected String uniqueMethodName(L1Class clazz, String base, String desc) {
        String candidate = base;
        int suffix = 0;
        while (clazz.findMethod(candidate, desc) != null || hasAsmMethod(clazz, candidate, desc)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    protected String packageName(String owner) {
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? "" : owner.substring(0, slash);
    }

    protected boolean hasAsmMethod(L1Class clazz, String name, String desc) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (name.equals(method.name) && desc.equals(method.desc)) {
                return true;
            }
        }
        return false;
    }

    protected int[] classKeyTable(long seed) {
        int[] table = new int[CLASS_KEY_TABLE_SIZE];
        long state = seed;
        for (int i = 0; i < table.length; i++) {
            state = JvmPassBytecode.mix(state, i ^ 0x5441424C454B31L);
            table[i] = nonZeroInt(state);
        }
        return table;
    }

    protected int[] classKeyObjectTable(long seed, int[] classWords) {
        int[] table = new int[classWords.length];
        long state = seed ^ 0x4346464F424A5431L;
        for (int i = 0; i < table.length; i++) {
            state = JvmPassBytecode.mix(state ^ classWords[i], i ^ 0x4F424A54424C31L);
            table[i] = nonZeroInt(state);
        }
        return table;
    }

    protected MethodNode findOrCreateClassInit(L1Class clazz) {
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

    protected MethodNode findClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name)) return method;
        }
        return null;
    }

    protected LabelNode protectedStartLabel(
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

    protected AbstractInsnNode firstRealAfterKeyInit(
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

    protected void publishMethodMetadata(
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
}
