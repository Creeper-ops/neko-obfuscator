package dev.nekoobfuscator.transforms.jvm;

import static dev.nekoobfuscator.transforms.jvm.ControlFlowFlatteningLr.*;
import static dev.nekoobfuscator.transforms.jvm.ControlFlowFlatteningVerify.*;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
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
import org.objectweb.asm.tree.MultiANewArrayInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

/**
 * Direct keyed control-flow flattening over the original method body.
 *
 * <p>The pass keeps bytecode in the original method and rewrites basic-block
 * exits to store an encoded state and return to a target-local dispatcher.
 * Constructors keep the mandatory this/super initialization prefix untouched;
 * flattening starts immediately after that prefix.</p>
 */
public final class ControlFlowFlatteningPass implements TransformPass {

    public static final String ID = "controlFlowFlattening";
    static final String METHOD_METADATA =
        "controlFlowFlattening.methodMetadata";
    private static final String CLASS_KEY_TABLES =
        "controlFlowFlattening.classKeyTables";
    private static final String CLASS_KEY_TABLES_PREPARED =
        "controlFlowFlattening.classKeyTablesPrepared";
    private static final String STRING_CONSTANT_VALUES_LOWERED =
        "controlFlowFlattening.stringConstantValuesLowered";
    private static final int CLASS_KEY_TABLE_SIZE = 64;
    private static final int DISPATCH_OUTLINER_ESTIMATED_CODE_PRESSURE = 4_000;
    private static final int DISPATCH_OUTLINER_BLOCK_THRESHOLD = 12;
    private static final int DISPATCH_OUTLINER_EDGE_THRESHOLD = 16;
    private static final int DISPATCH_OUTLINER_HANDLER_THRESHOLD = 4;
    private static final int TRANSITION_OUTLINER_ESTIMATED_CODE_PRESSURE = 52_000;
    private static final int TRANSITION_OUTLINER_BLOCK_THRESHOLD = 96;
    private static final int TRANSITION_OUTLINER_EDGE_THRESHOLD = 160;
    private static final int TRANSITION_OUTLINER_HANDLER_THRESHOLD = 16;
    private static final long METHOD_KEY_PC_MIX = 0x9E3779B97F4A7C15L;
    private CffClassKeyTable activeKeyTable;

    @Override
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

        rewriteInjectedMemberReflection(pctx, mn);
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
        BlockPlan blockPlan = buildBlocks(
            mn,
            protectedStart,
            handlerBodies,
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
        boolean outlineTransitions = useTransitionOutliner(
            mn,
            blocks,
            handlerBridges
        );
        boolean outlineDispatchers = outlineTransitions;
        int transitionOutLocal = outlineDispatchers ? mn.maxLocals++ : -1;
        TransitionOutliner dispatcherOutliner = outlineDispatchers
            ? new TransitionOutliner(pctx, clazz, transitionOutLocal)
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
        String fieldName =
            uniqueFieldName(clazz, "$" + Integer.toUnsignedString((int) seed, 36));
        int[] table = classKeyTable(seed);
        int fieldAccess =
            (clazz.isInterface() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE) |
            Opcodes.ACC_STATIC |
            Opcodes.ACC_FINAL |
            Opcodes.ACC_SYNTHETIC;
        FieldNode field = new FieldNode(
            fieldAccess,
            fieldName,
            "[I",
            null,
            null
        );
        clazz.asmNode().fields.add(field);

        boolean generatedClinit = findClassInit(clazz) == null;
        CffClassKeyTable data = new CffClassKeyTable(
            clazz.name(),
            fieldName,
            table,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C494E49544B31L)),
            new LabelNode(),
            new LabelNode(),
            generatedClinit
        );
        installClassKeyTableInit(pctx, clazz, data);
        tables.put(clazz.name(), data);
        clazz.markDirty();
        return data;
    }

    @SuppressWarnings("unchecked")
    private List<CffClassKeyTable> classKeyTables(PipelineContext pctx) {
        Map<String, CffClassKeyTable> tables = pctx.getPassData(
            CLASS_KEY_TABLES
        );
        if (tables == null || tables.isEmpty()) return List.of();
        return List.copyOf(tables.values());
    }

    private void rewriteInjectedMemberReflection(
        PipelineContext pctx,
        MethodNode mn
    ) {
        List<CffClassKeyTable> tables = classKeyTables(pctx);
        if (tables.isEmpty()) return;
        for (
            AbstractInsnNode insn = mn.instructions.getFirst();
            insn != null;
            insn = insn.getNext()
        ) {
            if (!(insn instanceof MethodInsnNode call)) continue;
            InsnList filter = null;
            if (isFieldReflectionCall(call)) {
                filter = injectedFieldFilter(mn, tables);
            } else if (isMethodArrayReflectionCall(call)) {
                filter = injectedMethodFilter(mn);
            }
            if (filter == null) continue;
            JvmKeyDispatchPass.markGenerated(pctx, filter);
            mn.instructions.insert(call, filter);
        }
    }

    private boolean isFieldReflectionCall(MethodInsnNode call) {
        return (
            call.getOpcode() == Opcodes.INVOKEVIRTUAL &&
            "java/lang/Class".equals(call.owner) &&
            ("getFields".equals(call.name) ||
                "getDeclaredFields".equals(call.name)) &&
            "()[Ljava/lang/reflect/Field;".equals(call.desc)
        );
    }

    private boolean isMethodArrayReflectionCall(MethodInsnNode call) {
        return (
            call.getOpcode() == Opcodes.INVOKEVIRTUAL &&
            "java/lang/Class".equals(call.owner) &&
            ("getMethods".equals(call.name) ||
                "getDeclaredMethods".equals(call.name)) &&
            "()[Ljava/lang/reflect/Method;".equals(call.desc)
        );
    }

    private InsnList injectedFieldFilter(
        MethodNode mn,
        List<CffClassKeyTable> tables
    ) {
        int sourceLocal = mn.maxLocals++;
        int filteredLocal = mn.maxLocals++;
        int indexLocal = mn.maxLocals++;
        int writeLocal = mn.maxLocals++;
        int fieldLocal = mn.maxLocals++;
        LabelNode loop = new LabelNode();
        LabelNode keep = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode end = new LabelNode();
        InsnList insns = new InsnList();

        insns.add(new VarInsnNode(Opcodes.ASTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/reflect/Field"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, filteredLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, writeLocal));

        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new VarInsnNode(Opcodes.ASTORE, fieldLocal));
        LabelNode syntheticDone = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Field",
            "isSynthetic",
            "()Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, syntheticDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Field",
            "getModifiers",
            "()I",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/reflect/Modifier",
            "isStatic",
            "(I)Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        insns.add(syntheticDone);
        emitInjectedFieldTest(insns, fieldLocal, tables, skip);
        insns.add(keep);
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(new IincInsnNode(writeLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(skip);
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));

        insns.add(end);
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Arrays",
                "copyOf",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;",
                false
            )
        );
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/reflect/Field;"));
        mn.maxStack = Math.max(mn.maxStack, 6);
        return insns;
    }

    private InsnList injectedMethodFilter(MethodNode mn) {
        int sourceLocal = mn.maxLocals++;
        int filteredLocal = mn.maxLocals++;
        int indexLocal = mn.maxLocals++;
        int writeLocal = mn.maxLocals++;
        int methodLocal = mn.maxLocals++;
        LabelNode loop = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode end = new LabelNode();
        InsnList insns = new InsnList();

        insns.add(new VarInsnNode(Opcodes.ASTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/reflect/Method"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, filteredLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, writeLocal));

        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new VarInsnNode(Opcodes.ASTORE, methodLocal));
        LabelNode syntheticDone = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "isSynthetic",
                "()Z",
                false
            )
        );
        insns.add(new JumpInsnNode(Opcodes.IFEQ, syntheticDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "getModifiers",
                "()I",
                false
            )
        );
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/reflect/Modifier",
                "isStatic",
                "(I)Z",
                false
            )
        );
        insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        insns.add(syntheticDone);
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "getName",
                "()Ljava/lang/String;",
                false
            )
        );
        insns.add(new LdcInsnNode("__neko_"));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "startsWith",
                "(Ljava/lang/String;)Z",
                false
            )
        );
        insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(new IincInsnNode(writeLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(skip);
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));

        insns.add(end);
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(
            new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/util/Arrays",
                "copyOf",
                "([Ljava/lang/Object;I)[Ljava/lang/Object;",
                false
            )
        );
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/reflect/Method;"));
        mn.maxStack = Math.max(mn.maxStack, 6);
        return insns;
    }

    private void emitInjectedFieldTest(
        InsnList insns,
        int fieldLocal,
        List<CffClassKeyTable> tables,
        LabelNode skip
    ) {
        for (CffClassKeyTable table : tables) {
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/reflect/Field",
                    "getName",
                    "()Ljava/lang/String;",
                    false
                )
            );
            insns.add(new LdcInsnNode(table.fieldName()));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    false
                )
            );
            insns.add(new JumpInsnNode(Opcodes.IFEQ, next));
            insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/reflect/Field",
                    "getDeclaringClass",
                    "()Ljava/lang/Class;",
                    false
                )
            );
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/Class",
                    "getName",
                    "()Ljava/lang/String;",
                    false
                )
            );
            insns.add(new LdcInsnNode(table.owner().replace('/', '.')));
            insns.add(
                new MethodInsnNode(
                    Opcodes.INVOKEVIRTUAL,
                    "java/lang/String",
                    "equals",
                    "(Ljava/lang/Object;)Z",
                    false
                )
            );
            insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
            insns.add(next);
        }
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

    private int[] classKeyTable(long seed) {
        int[] table = new int[CLASS_KEY_TABLE_SIZE];
        long state = seed;
        for (int i = 0; i < table.length; i++) {
            state = JvmPassBytecode.mix(state, i ^ 0x5441424C454B31L);
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
        int arrayLocal = clinit.maxLocals;
        init.add(table.initStart());
        JvmPassBytecode.pushInt(init, table.values().length);
        init.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        init.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
        for (int i = 0; i < table.values().length; i++) {
            init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            JvmPassBytecode.pushInt(init, i);
            JvmPassBytecode.pushInt(init, table.values()[i] ^ table.clinitMask());
            JvmPassBytecode.pushInt(init, table.clinitMask());
            init.add(new InsnNode(Opcodes.IXOR));
            init.add(new InsnNode(Opcodes.IASTORE));
        }
        init.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        init.add(
            new FieldInsnNode(
                Opcodes.PUTSTATIC,
                clazz.name(),
                table.fieldName(),
                "[I"
            )
        );
        init.add(table.initEnd());
        JvmKeyDispatchPass.markGenerated(pctx, init);
        AbstractInsnNode first = firstReal(clinit);
        if (first == null) {
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            clinit.instructions.insertBefore(first, init);
        }
        clinit.maxLocals = Math.max(clinit.maxLocals, arrayLocal + 1);
        clinit.maxStack = Math.max(clinit.maxStack, 6);
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
    static Map<String, CffMethodMetadata> methodMetadata(TransformContext ctx) {
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
                methodSeed ^ salt
            );
            CffBlockKeyState initialHandlerKeys = initialKeyState(methodSeed, methodSeed ^ salt);
            emitEncryptedToken(
                prefix,
                initialHandlerKeys.pcToken(),
                initialHandlerKeys,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                edgeSeed ^ 0x48494E4954504331L
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
                edgeSeed ^ 0x48414E44504331L
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
                bodyTarget.domainSeed()
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
                    entrySeed
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
                    entryTarget.selectorSeed()
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
                    entryTarget.domainSeed()
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
                        dispatcherOutliner,
                        transitionOutliner
                    )
                );
            }
            if (dispatcherOutliner != null) {
                insns.add(dispatcherOutliner.emitResultRouter(group, keyTmpLocal, poison));
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
            role
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
        EdgeRole role
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
        emitStorePc(
            insns,
            pcLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyLocal,
            state,
            targetKeys,
            methodSeed,
            target.selectorSeed()
        );
        emitStoreMethodKey(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            targetKeys
        );
        emitStoreDomain(
            insns,
            domainLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyLocal,
            target.island(),
            target.domainToken(),
            targetKeys,
            methodSeed,
            target.domainSeed()
        );
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
            dispatchSeed
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
                    bounceTarget.selectorSeed()
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
                    domainSeed
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
                    bounceTarget.selectorSeed()
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
                    domainSeed
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
                    domainSeed
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
                    bounceTarget.selectorSeed()
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
                    bounceTarget.selectorSeed()
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
                    domainSeed
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
                    domainSeed
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
        long seed
    ) {
        int[] keys = new int[cases.size()];
        LabelNode[] labels = new LabelNode[cases.size()];
        int index = 0;
        for (Map.Entry<Integer, LabelNode> entry : cases.entrySet()) {
            keys[index] = entry.getKey();
            labels[index] = entry.getValue();
            index++;
        }
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
        insns.add(new LookupSwitchInsnNode(poison, keys, labels));
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
        int x = keyState.guardKey() ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4454475541524431L));
        x += keyState.pathKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x44545041544831L)) | 1);
        x ^= keyState.blockKey() +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4454424C4F434B31L));
        x ^= methodKeyFold(keyState.methodKey(), seed ^ 0x44544D45544831L);
        x += token *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x44545043544F4B31L)) | 1);
        x ^= x >>> shift(seed, 17);
        return x + nonZeroInt(JvmPassBytecode.mix(seed, 0x445446494E414C31L));
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
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4454475541524431L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x44545041544831L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4454424C4F434B31L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitMethodKeyFold(insns, keyLocal, seed ^ 0x44544D45544831L);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x44545043544F4B31L)) | 1
        );
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x445446494E414C31L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
    }

    private void emitInitKeys(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyLocal,
        long seed
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
            seed ^ 0x434C4153534B31L
        );
    }

    private void emitClassKeyMixIntoLocals(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyLocal,
        long seed
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return;
        int token = table.token(nonZeroInt(seed), seed);
        emitClassKeyWord(insns, table, keyLocal, token, seed);

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
        long seed
    ) {
        insns.add(
            new FieldInsnNode(
                Opcodes.GETSTATIC,
                table.owner(),
                table.fieldName(),
                "[I"
            )
        );
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
        long selectorSeed
    ) {
        emitEncryptedToken(
            insns,
            targetKeys.pcToken(),
            targetKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            selectorSeed ^ state ^ 0x5043544F4B454E31L
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
        long domainSeed
    ) {
        emitEncryptedToken(
            insns,
            domainToken,
            targetKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            domainSeed ^ island ^ 0x444F4D544F4B31L
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, domainLocal));
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
        long seed = targetKeys.methodKey() ^
            targetKeys.methodSalt() ^
            0x53544F524D53414CL;
        long encrypted = targetKeys.methodKey() ^ methodKeyLongMask(targetKeys, seed);
        JvmPassBytecode.pushLong(insns, encrypted);
        emitMethodKeyLongMask(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            seed
        );
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
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
        long seed
    ) {
        int encrypted = token ^
            classTokenMask(expectedKeys, seed) ^
            controlTokenMask(expectedKeys, seed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitClassTokenMask(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed);
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
        return word ^ (word >>> shift(classSeed, 23));
    }

    private void emitClassTokenMask(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        CffClassKeyTable table = activeKeyTable;
        if (table == null) return;
        long classSeed = seed ^ 0x434646434C544B31L;
        insns.add(
            new FieldInsnNode(
                Opcodes.GETSTATIC,
                table.owner(),
                table.fieldName(),
                "[I"
            )
        );
        emitClassStateTableIndex(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            classSeed
        );
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitClassStateDigest(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            classSeed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(classSeed, 23));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int classStateTableIndex(CffBlockKeyState keyState, long seed) {
        int value = keyState.guardKey() ^ keyState.pathKey();
        value += nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535449445831L));
        value ^= value >>> shift(seed, 5);
        value += keyState.blockKey() ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C5354424C4B31L));
        return value & (CLASS_KEY_TABLE_SIZE - 1);
    }

    private void emitClassStateTableIndex(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535449445831L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C5354424C4B31L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, CLASS_KEY_TABLE_SIZE - 1);
        insns.add(new InsnNode(Opcodes.IAND));
    }

    private int classStateDigest(CffBlockKeyState keyState, long seed) {
        int value = keyState.blockKey() + keyState.pathKey();
        value ^= keyState.guardKey();
        value += nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535444494731L));
        return value ^ (value >>> shift(seed, 11));
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
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x434C535444494731L))
        );
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 11));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
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
        long baseSeed = seed ^ 0x5452414E534B4559L ^ role.ordinal();
        emitCompactControlTokenBase(
            insns,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyBaseLocal,
            baseSeed
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
        int x = keyState.guardKey() +
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
        long seed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(
            insns,
            nonZeroInt(JvmPassBytecode.mix(seed, 0x43475041544831L))
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
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
                    emitDynamicBoundDecodedLong(
                        replacement,
                        incomingRawForCanonical(generatedTargetSeed),
                        requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                        keyLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        salt ^ generatedTargetSeed ^ System.identityHashCode(insn)
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
                emitDynamicBoundDecodedLong(
                    replacement,
                    rawSeed,
                    requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    salt ^ targetSeed ^ System.identityHashCode(insn)
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
            keyStateByLabel,
            salt
        );
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
            if (!isGeneratedKeyLoad(pctx, scan, keyLocal)) continue;
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
                load.var != storedLocal ||
                !JvmKeyDispatchPass.isGeneratedNode(pctx, scan)) {
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
            emitDynamicBoundDecodedLong(
                replacement,
                incomingRawForCanonical(targetSeed),
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(keyLoad)
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
            emitDynamicBoundDecodedLong(
                replacement,
                incomingRawForCanonical(targetSeed),
                requireBlockKey(block.label(), keyStateByLabel.get(block.label())),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                salt ^ targetSeed ^ System.identityHashCode(insn)
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

    private long incomingRawForCanonical(long targetSeed) {
        return (targetSeed - JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK) ^
            (targetSeed ^ JvmKeyDispatchPass.INCOMING_KEY_MIX_MASK);
    }

    private void emitDynamicBoundDecodedLong(
        InsnList insns,
        long value,
        CffBlockKeyState expectedKeys,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        emitEncryptedBoundToken(
            insns,
            (int) (value >>> 32),
            expectedKeys,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x42444849474831L
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
            seed ^ 0x42444C4F5731L
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
        long seed
    ) {
        int encrypted = token ^
            classTokenMask(expectedKeys, seed) ^
            controlTokenMask(expectedKeys, seed) ^
            methodKeyFold(expectedKeys.methodKey(), seed ^ 0x42444D45544831L);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitClassTokenMask(insns, guardLocal, pathKeyLocal, blockKeyLocal, seed);
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
        long seed
    ) {
        emitEncryptedToken(
            insns,
            (int) (value >>> 32),
            expectedKeys,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed ^ 0x5241574849474831L
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
            seed ^ 0x5241574C4F5731L
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
        JvmPassBytecode.pushInt(insns, 6);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
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
        emitTransitionOutStore(insns, outLocal, 0, guardLocal);
        emitTransitionOutStore(insns, outLocal, 1, pathKeyLocal);
        emitTransitionOutStore(insns, outLocal, 2, blockKeyLocal);
        emitTransitionOutStore(insns, outLocal, 3, pcLocal);
        emitTransitionOutStore(insns, outLocal, 4, domainLocal);
    }

    private void emitTransitionOutStore(
        InsnList insns,
        int outLocal,
        int index,
        int valueLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new VarInsnNode(Opcodes.ILOAD, valueLocal));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    private void emitTransitionOutStoreConst(
        InsnList insns,
        int outLocal,
        int index,
        int value
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        JvmPassBytecode.pushInt(insns, value);
        insns.add(new InsnNode(Opcodes.IASTORE));
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
        emitTransitionOutLoad(insns, outLocal, 0, guardLocal);
        emitTransitionOutLoad(insns, outLocal, 1, pathKeyLocal);
        emitTransitionOutLoad(insns, outLocal, 2, blockKeyLocal);
        emitTransitionOutLoad(insns, outLocal, 3, pcLocal);
        emitTransitionOutLoad(insns, outLocal, 4, domainLocal);
    }

    private void emitTransitionOutLoad(
        InsnList insns,
        int outLocal,
        int index,
        int dstLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, outLocal));
        JvmPassBytecode.pushInt(insns, index);
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dstLocal));
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

    private final class TransitionOutliner {
        private static final String DESC = "(JIIIII[I)J";
        private final PipelineContext pctx;
        private final L1Class clazz;
        private final String owner;
        private final boolean interfaceOwner;
        private final int outLocal;
        private final Map<IslandGroup, RouterState> routers = new IdentityHashMap<>();
        private int counter;

        TransitionOutliner(PipelineContext pctx, L1Class clazz, int outLocal) {
            this.pctx = pctx;
            this.clazz = clazz;
            this.owner = clazz.asmNode().name;
            this.interfaceOwner = clazz.isInterface();
            this.outLocal = outLocal;
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
            Map<LabelNode, Integer> resultTokens = new IdentityHashMap<>();
            long resultSeed = group.salt() ^ 0x4F55544449535031L ^ island;
            for (int i = 0; i < islandBlocks.size(); i++) {
                Block block = islandBlocks.get(i);
                int token = uniqueResultToken(
                    router,
                    resultSeed,
                    block.label(),
                    requireState(block.label(), stateByLabel.get(block.label())) ^ i
                );
                router.resultCases.put(token, block.label());
                resultTokens.put(block.label(), token);
            }
            int bounceToken = uniqueResultToken(
                router,
                resultSeed ^ 0x424F554E43454B31L,
                group.hub(),
                fakeCount ^ firstState
            );
            router.resultCases.put(bounceToken, group.hub());
            String helperName = createIslandDispatchHelper(
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
                salt
            );

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
            emitTransitionOutLoad(insns, outLocal, 5, keyTmpLocal);
            insns.add(new JumpInsnNode(Opcodes.GOTO, router.label));
            JvmKeyDispatchPass.markGenerated(pctx, insns);
            return insns;
        }

        private RouterState router(IslandGroup group) {
            return routers.computeIfAbsent(group, ignored -> new RouterState());
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
            return token;
        }

        InsnList emitResultRouter(
            IslandGroup group,
            int keyTmpLocal,
            LabelNode poison
        ) {
            InsnList insns = new InsnList();
            RouterState router = routers.get(group);
            if (router == null || router.resultCases.isEmpty()) return insns;
            int[] keys = new int[router.resultCases.size()];
            LabelNode[] labels = new LabelNode[router.resultCases.size()];
            int index = 0;
            for (Map.Entry<Integer, LabelNode> entry : router.resultCases.entrySet()) {
                keys[index] = entry.getKey();
                labels[index] = entry.getValue();
                index++;
            }
            insns.add(router.label);
            insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal));
            insns.add(new LookupSwitchInsnNode(poison, keys, labels));
            JvmKeyDispatchPass.markGenerated(pctx, insns);
            return insns;
        }

        private String createIslandDispatchHelper(
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
            long salt
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

            TreeMap<Integer, LabelNode> cases = new TreeMap<>();
            List<LabelNode> realLabels = new ArrayList<>();
            for (Block block : islandBlocks) {
                LabelNode caseLabel = new LabelNode();
                realLabels.add(caseLabel);
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
                    caseLabel
                );
            }
            List<LabelNode> fakeLabels = new ArrayList<>();
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
                cases.put(fakeToken, fake);
            }
            LabelNode poisonLabel = new LabelNode();
            emitTokenDispatch(
                helper.instructions,
                helperPcLocal,
                helperKeyLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
                cases,
                poisonLabel,
                dispatchSeed
            );
            for (int i = 0; i < islandBlocks.size(); i++) {
                Block block = islandBlocks.get(i);
                helper.instructions.add(realLabels.get(i));
                finishOutlinedDispatchReturn(
                    helper.instructions,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
                    helperPcLocal,
                    helperDomainLocal,
                    helperOutLocal,
                    resultTokens.get(block.label())
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
                emitStepKeys(
                    helper.instructions,
                    helperKeyLocal,
                    helperGuardLocal,
                    helperPathLocal,
                    helperBlockLocal,
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
                    bounceToken
                );
            }
            helper.instructions.add(poisonLabel);
            long poisonSeed = edgeSeed(
                salt,
                group.hub(),
                group.islandLabels()[island],
                0x4F5554504F49534FL ^ island
            );
            emitStepKeys(
                helper.instructions,
                helperKeyLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
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
            return helperName;
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
            int bounceToken
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
                        bounceTarget.selectorSeed()
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
                        domainSeed
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
                        bounceTarget.selectorSeed()
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
                        domainSeed
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
                        domainSeed
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
                        bounceTarget.selectorSeed()
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
                        bounceTarget.selectorSeed()
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
                        domainSeed
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
                        domainSeed
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
                bounceToken
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
            int resultToken
        ) {
            emitTransitionOutStores(
                insns,
                outLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal
            );
            emitTransitionOutStoreConst(insns, outLocal, 5, resultToken);
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
            String helperName = createHelper(
                state,
                target,
                edgeKind,
                sourceKeys,
                targetKeys,
                methodSeed,
                stepSeed,
                role
            );
            InsnList insns = new InsnList();
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
            emitTransitionCore(
                helper.instructions,
                state,
                target,
                edgeKind,
                helperKeyLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
                helperPcLocal,
                helperDomainLocal,
                helperKeyTmpLocal,
                sourceKeys,
                targetKeys,
                methodSeed,
                stepSeed,
                role
            );
            emitTransitionOutStores(
                helper.instructions,
                helperOutLocal,
                helperGuardLocal,
                helperPathLocal,
                helperBlockLocal,
                helperPcLocal,
                helperDomainLocal
            );
            helper.instructions.add(new VarInsnNode(Opcodes.LLOAD, helperKeyLocal));
            helper.instructions.add(new InsnNode(Opcodes.LRETURN));
            helper.maxLocals = 14;
            helper.maxStack = 32;
            JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
            clazz.asmNode().methods.add(helper);
            clazz.markDirty();
            return helperName;
        }

        private final class RouterState {
            final LabelNode label = new LabelNode();
            final TreeMap<Integer, LabelNode> resultCases = new TreeMap<>();
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

    private static final class TypeTrackingInterpreter extends BasicInterpreter {
        TypeTrackingInterpreter() {
            super(Opcodes.ASM9);
        }

        @Override
        public BasicValue newValue(Type type) {
            if (type == null) return BasicValue.UNINITIALIZED_VALUE;
            return switch (type.getSort()) {
                case Type.VOID -> null;
                case Type.BOOLEAN,
                    Type.CHAR,
                    Type.BYTE,
                    Type.SHORT,
                    Type.INT -> BasicValue.INT_VALUE;
                case Type.FLOAT -> BasicValue.FLOAT_VALUE;
                case Type.LONG -> BasicValue.LONG_VALUE;
                case Type.DOUBLE -> BasicValue.DOUBLE_VALUE;
                case Type.ARRAY, Type.OBJECT -> new BasicValue(type);
                default -> BasicValue.UNINITIALIZED_VALUE;
            };
        }

        @Override
        public BasicValue newOperation(AbstractInsnNode insn) throws AnalyzerException {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.NEW) {
                return newValue(Type.getObjectType(((TypeInsnNode) insn).desc));
            }
            if (opcode == Opcodes.GETSTATIC) {
                return newValue(Type.getType(((FieldInsnNode) insn).desc));
            }
            if (opcode == Opcodes.LDC && insn instanceof LdcInsnNode ldc) {
                Object cst = ldc.cst;
                if (cst instanceof Type type) {
                    int sort = type.getSort();
                    if (sort == Type.OBJECT || sort == Type.ARRAY) {
                        return BasicValue.REFERENCE_VALUE;
                    }
                    if (sort == Type.METHOD) {
                        return BasicValue.REFERENCE_VALUE;
                    }
                } else if (cst instanceof String) {
                    return newValue(Type.getType(String.class));
                }
            }
            return super.newOperation(insn);
        }

        @Override
        public BasicValue unaryOperation(
            AbstractInsnNode insn,
            BasicValue value
        ) throws AnalyzerException {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.CHECKCAST) {
                String desc = ((TypeInsnNode) insn).desc;
                return newValue(
                    desc.startsWith("[")
                        ? Type.getType(desc)
                        : Type.getObjectType(desc)
                );
            }
            if (opcode == Opcodes.ANEWARRAY) {
                String desc = ((TypeInsnNode) insn).desc;
                Type element = desc.startsWith("[")
                    ? Type.getType(desc)
                    : Type.getObjectType(desc);
                return newValue(Type.getType("[" + element.getDescriptor()));
            }
            if (opcode == Opcodes.NEWARRAY && insn instanceof IntInsnNode array) {
                return newValue(primitiveArrayType(array.operand));
            }
            if (opcode == Opcodes.GETFIELD) {
                return newValue(Type.getType(((FieldInsnNode) insn).desc));
            }
            return super.unaryOperation(insn, value);
        }

        @Override
        public BasicValue naryOperation(
            AbstractInsnNode insn,
            List<? extends BasicValue> values
        ) throws AnalyzerException {
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.MULTIANEWARRAY) {
                return newValue(Type.getType(((MultiANewArrayInsnNode) insn).desc));
            }
            if (insn instanceof MethodInsnNode method) {
                return newValue(Type.getReturnType(method.desc));
            }
            if (insn instanceof InvokeDynamicInsnNode indy) {
                return newValue(Type.getReturnType(indy.desc));
            }
            return super.naryOperation(insn, values);
        }

        @Override
        public BasicValue merge(BasicValue left, BasicValue right) {
            if (left.equals(right)) return left;
            Type leftType = left.getType();
            Type rightType = right.getType();
            if (isReference(leftType) && isReference(rightType)) {
                return BasicValue.REFERENCE_VALUE;
            }
            return super.merge(left, right);
        }

        private boolean isReference(Type type) {
            return type != null &&
                (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY);
        }

        private boolean isNullReference(Type type) {
            return type != null && "null".equals(type.getInternalName());
        }

        private Type primitiveArrayType(int operand) {
            return switch (operand) {
                case Opcodes.T_BOOLEAN -> Type.getType("[Z");
                case Opcodes.T_CHAR -> Type.getType("[C");
                case Opcodes.T_FLOAT -> Type.getType("[F");
                case Opcodes.T_DOUBLE -> Type.getType("[D");
                case Opcodes.T_BYTE -> Type.getType("[B");
                case Opcodes.T_SHORT -> Type.getType("[S");
                case Opcodes.T_INT -> Type.getType("[I");
                case Opcodes.T_LONG -> Type.getType("[J");
                default -> Type.getType("[Ljava/lang/Object;");
            };
        }
    }

    private static final class CffFrameAnalysis {
        private final MethodNode method;
        private final Frame<BasicValue>[] frames;
        private final Map<AbstractInsnNode, Integer> instructionIndex;

        private CffFrameAnalysis(
            MethodNode method,
            Frame<BasicValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndex
        ) {
            this.method = method;
            this.frames = frames;
            this.instructionIndex = instructionIndex;
        }

        static CffFrameAnalysis analyze(String owner, MethodNode method) {
            try {
                Analyzer<BasicValue> analyzer = new Analyzer<>(new TypeTrackingInterpreter());
                Frame<BasicValue>[] frames = analyzer.analyze(owner, method);
                Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
                AbstractInsnNode[] insns = method.instructions.toArray();
                for (int i = 0; i < insns.length; i++) {
                    index.put(insns[i], i);
                }
                return new CffFrameAnalysis(method, frames, index);
            } catch (Exception e) {
                throw new IllegalStateException(
                    "Cannot analyze verifier frames for " + owner + "." + method.name + method.desc,
                    e
                );
            }
        }

        Set<LabelNode> zeroStackLabels() {
            Set<LabelNode> labels = new HashSet<>();
            for (
                AbstractInsnNode insn = method.instructions.getFirst();
                insn != null;
                insn = insn.getNext()
            ) {
                if (insn instanceof LabelNode label && isZeroStack(label)) {
                    labels.add(label);
                }
            }
            return labels;
        }

        boolean isZeroStack(AbstractInsnNode insn) {
            Frame<BasicValue> frame = frameAt(insn);
            return frame != null && frame.getStackSize() == 0;
        }

        List<BasicValue> stackValues(AbstractInsnNode insn) {
            Frame<BasicValue> frame = frameAt(insn);
            if (frame == null) {
                throw new IllegalStateException("CFF control target has no frame");
            }
            List<BasicValue> values = new ArrayList<>();
            for (int i = 0; i < frame.getStackSize(); i++) {
                values.add(frame.getStack(i));
            }
            return values;
        }

        List<BasicValue> localValues(LabelNode label) {
            Frame<BasicValue> frame = frameAt(label);
            if (frame == null) {
                throw new IllegalStateException(
                    "CFF island target has no frame: " + label.getLabel()
                );
            }
            List<BasicValue> values = new ArrayList<>();
            for (int i = 0; i < frame.getLocals(); i++) {
                values.add(frame.getLocal(i));
            }
            return values;
        }

        String localsSignature(LabelNode label) {
            Frame<BasicValue> frame = frameAt(label);
            if (frame == null) {
                throw new IllegalStateException(
                    "CFF island target has no frame: " + label.getLabel()
                );
            }
            StringBuilder sb = new StringBuilder(frame.getLocals() * 3);
            for (int i = 0; i < frame.getLocals(); i++) {
                BasicValue value = frame.getLocal(i);
                if (value == null || value == BasicValue.UNINITIALIZED_VALUE) {
                    sb.append('.');
                } else if (value == BasicValue.REFERENCE_VALUE) {
                    sb.append("R#").append(i).append('@')
                        .append(System.identityHashCode(label));
                } else if (value.getType() == null) {
                    sb.append(value);
                } else {
                    sb.append(value.getType().getDescriptor());
                }
                sb.append(';');
            }
            return sb.toString();
        }

        private Frame<BasicValue> frameAt(AbstractInsnNode insn) {
            if (insn == null) return null;
            Integer index = instructionIndex.get(insn);
            if (index != null && index >= 0 && index < frames.length && frames[index] != null) {
                return frames[index];
            }
            if (insn instanceof LabelNode) {
                AbstractInsnNode real = nextReal(insn.getNext());
                index = real == null ? null : instructionIndex.get(real);
                if (index != null && index >= 0 && index < frames.length) {
                    return frames[index];
                }
            }
            return null;
        }

        private static AbstractInsnNode nextReal(AbstractInsnNode start) {
            for (
                AbstractInsnNode insn = start;
                insn != null;
                insn = insn.getNext()
            ) {
                if (insn.getOpcode() >= 0) return insn;
            }
            return null;
        }
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

    record CffMethodMetadata(
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

    record CffInstructionState(
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

    record CffClassKeyTable(
        String owner,
        String fieldName,
        int[] values,
        int clinitMask,
        LabelNode initStart,
        LabelNode initEnd,
        boolean generatedClinit
    ) {
        int token(int value, long siteSeed) {
            long mixed = JvmPassBytecode.mix(siteSeed, value);
            return (int) (mixed & (values.length - 1));
        }
    }
}
