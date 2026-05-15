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


abstract class CffTransitionOutliner extends CffKeyTransferRewriter {

    protected final class TransitionOutliner {
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
}
