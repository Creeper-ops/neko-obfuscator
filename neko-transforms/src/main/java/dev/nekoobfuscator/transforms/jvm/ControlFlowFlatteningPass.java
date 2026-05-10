package dev.nekoobfuscator.transforms.jvm;

import static dev.nekoobfuscator.transforms.jvm.ControlFlowFlatteningLr.*;
import static dev.nekoobfuscator.transforms.jvm.ControlFlowFlatteningVerify.*;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.ir.l2.BytecodeFrameAnalysis;
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
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.BasicValue;

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
        int keyLocal =
            recordedKeyLocal != null
                ? recordedKeyLocal
                : JvmKeyDispatchPass.ensureMethodKeyLocal(pctx, clazz, method);
        if (keyLocal < 0) {
            throw new IllegalStateException(
                "CFF requires an initialized method key for " +
                    clazz.name() +
                    "." +
                    method.name() +
                    method.descriptor()
            );
        }
        long methodSeed =
            JvmKeyDispatchPass.findMethodSeed(pctx, methodKey) != null
                ? JvmKeyDispatchPass.findMethodSeed(pctx, methodKey)
                : JvmKeyDispatchPass.methodSeed(
                      pctx.masterSeed(),
                      clazz,
                      method,
                      mn
                  );
        LabelNode protectedStart = protectedStartLabel(
            clazz,
            method,
            mn,
            keyLocal
        );
        if (protectedStart == null) return;

        rewriteInjectedFieldReflection(pctx, mn);
        List<ProtectedTryCatch> protectedTryCatches =
            captureProtectedTryCatches(mn);
        List<HandlerBridge> handlerBridges = splitExceptionHandlers(mn);
        Set<LabelNode> handlerBodies = handlerBodyLabels(handlerBridges);
        BytecodeFrameAnalysis frames = BytecodeFrameAnalysis.analyze(
            clazz.name(),
            mn
        );
        if (normalizeNonZeroStackGotoTargets(pctx, mn, protectedStart, frames)) {
            frames = BytecodeFrameAnalysis.analyze(clazz.name(), mn);
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

        int pcLocal = mn.maxLocals;
        int guardLocal = pcLocal + 1;
        int pathKeyLocal = pcLocal + 2;
        int blockKeyLocal = pcLocal + 3;
        int domainLocal = pcLocal + 4;
        int exceptionLocal = handlerBridges.isEmpty() ? -1 : pcLocal + 5;
        int keyTmpLocal = pcLocal + 5 + (handlerBridges.isEmpty() ? 0 : 1);
        mn.maxLocals = keyTmpLocal + 4;

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
            handlerReachableDomains(mn, blocks, blockPlan.aliases(), handlerBodies)
        );
        for (Map.Entry<LabelNode, LabelNode> alias : blockPlan
            .aliases()
            .entrySet()) {
            LabelNode canonical = alias.getValue();
            stateByLabel.put(alias.getKey(), stateByLabel.get(canonical));
            dispatchPlan
                .targets()
                .put(alias.getKey(), dispatchPlan.targets().get(canonical));
        }
        Map<LabelNode, CffBlockKeyState> keyStateByLabel =
            buildBlockKeyStates(blocks, blockPlan.aliases(), stateByLabel, dispatchPlan.targets(), salt);
        rewriteKeyedCallTransfers(
            pctx,
            mn,
            blocks,
            keyLocal,
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
                methodSeed,
                salt
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
            stateByLabel,
            keyStateByLabel,
            dispatchPlan.targets(),
            methodSeed,
            salt
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
            stateByLabel,
            keyStateByLabel,
            dispatchPlan,
            exceptionLocal,
            externalEntrySeed,
            methodSeed,
            salt
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

    private void rewriteInjectedFieldReflection(
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
            if (!isFieldReflectionCall(call)) continue;
            InsnList filter = injectedFieldFilter(mn, tables);
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
        return aliases.getOrDefault(label, label);
    }

    private BlockPlan buildBlocks(
        MethodNode mn,
        LabelNode start,
        Set<LabelNode> extraLeaders,
        Set<LabelNode> zeroStackLabels,
        Set<LabelNode> linearLeaders,
        BytecodeFrameAnalysis frames
    ) {
        Set<AbstractInsnNode> leaders = new HashSet<>();
        leaders.add(start);
        leaders.addAll(extraLeaders);
        leaders.addAll(linearLeaders);
        Set<LabelNode> handlerLabels = new HashSet<>();
        if (mn.tryCatchBlocks != null) {
            for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
                leaders.add(tcb.start);
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
                boolean targetZero = zeroStackLabels.contains(jump.label);
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

    private Set<LabelNode> linearZeroStackLeaders(
        MethodNode mn,
        LabelNode start,
        BytecodeFrameAnalysis frames
    ) {
        Set<LabelNode> leaders = new HashSet<>();
        AbstractInsnNode[] insns = mn.instructions.toArray();
        boolean active = false;
        for (AbstractInsnNode insn : insns) {
            if (insn == start) active = true;
            if (
                !active || insn.getOpcode() < 0 || isControlTransfer(insn)
            ) continue;
            AbstractInsnNode next = nextReal(insn.getNext());
            if (frames.isZeroStack(next)) {
                leaders.add(ensureLabelBefore(mn, next));
            }
        }
        return leaders;
    }

    private boolean normalizeNonZeroStackGotoTargets(
        PipelineContext pctx,
        MethodNode mn,
        LabelNode start,
        BytecodeFrameAnalysis frames
    ) {
        Map<LabelNode, StackSpill> spills = new IdentityHashMap<>();
        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            if (!(insn instanceof JumpInsnNode jump) || jump.getOpcode() != Opcodes.GOTO) {
                continue;
            }
            List<BasicValue> stack = frames.stackValues(jump.label);
            if (stack.isEmpty()) {
                continue;
            }
            spills.computeIfAbsent(jump.label, ignored -> allocateStackSpill(mn, stack));
        }
        if (spills.isEmpty()) {
            return false;
        }

        for (
            AbstractInsnNode insn = start;
            insn != null;
            insn = insn.getNext()
        ) {
            if (insn instanceof JumpInsnNode jump && jump.getOpcode() == Opcodes.GOTO) {
                StackSpill spill = spills.get(jump.label);
                if (spill != null) {
                    InsnList stores = spillStores(spill);
                    JvmKeyDispatchPass.markGenerated(pctx, stores);
                    mn.instructions.insertBefore(jump, stores);
                }
            }
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
        for (StackSpill spill : spills.values()) {
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
        if (!zeroStackLabels.contains(dflt)) return false;
        for (LabelNode label : labels) {
            if (!zeroStackLabels.contains(label)) return false;
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
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        Map<LabelNode, DispatchTarget> dispatchByLabel,
        long methodSeed,
        long salt
    ) {
        if (handlerBridges.isEmpty()) return;
        for (HandlerBridge bridge : handlerBridges) {
            LabelNode handler = bridge.handler();
            LabelNode body = bridge.body();
            Integer bodyState = stateByLabel.get(body);
            DispatchTarget bodyTarget = dispatchByLabel.get(body);
            long edgeSeed = edgeSeed(salt, handler, body, 0x45584348414E444CL);
            InsnList prefix = new InsnList();
            prefix.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
            JvmKeyDispatchPass.emitKeyInit(
                prefix,
                keyLocal,
                methodSeed,
                0x4346464B65794C31L
            );
            emitInitKeys(
                prefix,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyLocal,
                methodSeed ^ salt
            );
            CffBlockKeyState handlerSourceKeys = syntheticHandlerSourceKey(
                methodSeed,
                salt,
                bridge.handler()
            );
            emitInstallBlockKeys(
                prefix,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                handlerSourceKeys
            );
            if (bridge.catchLocal() >= 0) {
                prefix.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
                prefix.add(new VarInsnNode(Opcodes.ASTORE, bridge.catchLocal()));
            }
            prefix.add(
                transition(
                    requireState(body, bodyState),
                    requireTarget(body, bodyTarget),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    handlerSourceKeys,
                    requireBlockKey(body, keyStateByLabel.get(body)),
                    methodSeed,
                    edgeSeed,
                    true,
                    EdgeRole.HANDLER
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
        Map<LabelNode, Integer> stateByLabel,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        DispatchPlan dispatchPlan,
        int exceptionLocal,
        boolean externalEntrySeed,
        long methodSeed,
        long salt
    ) {
        for (IslandGroup group : dispatchPlan.groups()) {
            Block entryBlock = group.blocks().get(0);
            InsnList insns = new InsnList();
            LabelNode poison = new LabelNode();

            if (entryBlock == firstNonHandler(blocks)) {
                if (exceptionLocal >= 0) {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
                }
                DispatchTarget entryTarget = requireTarget(
                    entryBlock.label(),
                    dispatchPlan.targets().get(entryBlock.label())
                );
                if (!externalEntrySeed) {
                    JvmKeyDispatchPass.emitKeyInit(
                        insns,
                        keyLocal,
                        methodSeed,
                        0x4346464D45544831L
                    );
                }
                emitInitKeys(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    keyLocal,
                    group.salt()
                );
                emitInstallBlockKeys(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    requireBlockKey(
                        entryBlock.label(),
                        keyStateByLabel.get(entryBlock.label())
                    )
                );
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
                if (entryTarget.islandLabels().length == 1) {
                    insns.add(
                        new JumpInsnNode(
                            Opcodes.GOTO,
                            entryTarget.islandLabels()[entryTarget.island()]
                        )
                    );
                } else {
                    emitStoreDomain(
                        insns,
                        domainLocal,
                        guardLocal,
                        pathKeyLocal,
                        blockKeyLocal,
                        keyLocal,
                        entryTarget.island(),
                        entryTarget.domainToken(),
                        requireBlockKey(
                            entryBlock.label(),
                            keyStateByLabel.get(entryBlock.label())
                        ),
                        methodSeed,
                        entryTarget.domainSeed()
                    );
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
                        salt
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
        long methodSeed,
        long salt
    ) {
        AbstractInsnNode last = lastRealBefore(mn, block.endExclusive());
        if (last == null || before(last, block.label())) return;
        int opcode = last.getOpcode();
        if (terminates(opcode)) return;

        if (last instanceof JumpInsnNode jump) {
            if (opcode == Opcodes.GOTO) {
                Integer targetState = stateByLabel.get(jump.label);
                DispatchTarget target = dispatchByLabel.get(jump.label);
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
                        requireBlockKey(jump.label, keyStateByLabel.get(jump.label)),
                        methodSeed,
                        edgeSeed(salt, block.label(), jump.label, opcode),
                        true,
                        EdgeRole.GOTO
                    )
                );
                mn.instructions.remove(last);
                return;
            }
            Integer targetState = stateByLabel.get(jump.label);
            Integer fallthroughState =
                next == null ? null : stateByLabel.get(next);
            DispatchTarget target = dispatchByLabel.get(jump.label);
            DispatchTarget fallthrough =
                next == null ? null : dispatchByLabel.get(next);
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
                        requireBlockKey(next, keyStateByLabel.get(next)),
                        methodSeed,
                        falseSeed,
                        true,
                        EdgeRole.CONDITIONAL_FALSE
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
                        requireBlockKey(jump.label, keyStateByLabel.get(jump.label)),
                        methodSeed,
                        trueSeed,
                        true,
                        EdgeRole.CONDITIONAL_TRUE
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
                        requireBlockKey(jump.label, keyStateByLabel.get(jump.label)),
                        methodSeed,
                        trueSeed,
                        true,
                        EdgeRole.CONDITIONAL_TRUE
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
                        requireBlockKey(next, keyStateByLabel.get(next)),
                        methodSeed,
                        falseSeed,
                        true,
                        EdgeRole.CONDITIONAL_FALSE
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
                block.label(),
                methodSeed,
                salt
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
                block.label(),
                methodSeed,
                salt
            );
            return;
        }
        if (next != null) {
            Integer nextState = stateByLabel.get(next);
            DispatchTarget nextTarget = dispatchByLabel.get(next);
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
                    requireBlockKey(next, keyStateByLabel.get(next)),
                    methodSeed,
                    edgeSeed(salt, block.label(), next, 0x46414C4C),
                    true,
                    EdgeRole.FALLTHROUGH
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
        LabelNode source,
        long methodSeed,
        long salt
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
                    stateByLabel.get(originalDefault)
                ),
                requireTarget(
                    originalDefault,
                    dispatchByLabel.get(originalDefault)
                ),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                requireBlockKey(source, keyStateByLabel.get(source)),
                requireBlockKey(originalDefault, keyStateByLabel.get(originalDefault)),
                methodSeed,
                edgeSeed(salt, source, originalDefault, 0x53574446),
                true,
                EdgeRole.SWITCH_DEFAULT
            )
        );
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(
                transition(
                    requireState(
                        originalTarget,
                        stateByLabel.get(originalTarget)
                    ),
                    requireTarget(
                        originalTarget,
                        dispatchByLabel.get(originalTarget)
                    ),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    requireBlockKey(source, keyStateByLabel.get(source)),
                    requireBlockKey(originalTarget, keyStateByLabel.get(originalTarget)),
                    methodSeed,
                    edgeSeed(
                        salt,
                        source,
                        originalTarget,
                        ls.keys.get(i) ^ 0x53574C53
                    ),
                    true,
                    EdgeRole.SWITCH_CASE
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
        LabelNode source,
        long methodSeed,
        long salt
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
                    stateByLabel.get(originalDefault)
                ),
                requireTarget(
                    originalDefault,
                    dispatchByLabel.get(originalDefault)
                ),
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                domainLocal,
                keyTmpLocal,
                requireBlockKey(source, keyStateByLabel.get(source)),
                requireBlockKey(originalDefault, keyStateByLabel.get(originalDefault)),
                methodSeed,
                edgeSeed(salt, source, originalDefault, 0x54534446),
                true,
                EdgeRole.SWITCH_DEFAULT
            )
        );
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(
                transition(
                    requireState(
                        originalTarget,
                        stateByLabel.get(originalTarget)
                    ),
                    requireTarget(
                        originalTarget,
                        dispatchByLabel.get(originalTarget)
                    ),
                    keyLocal,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    pcLocal,
                    domainLocal,
                    keyTmpLocal,
                    requireBlockKey(source, keyStateByLabel.get(source)),
                    requireBlockKey(originalTarget, keyStateByLabel.get(originalTarget)),
                    methodSeed,
                    edgeSeed(
                        salt,
                        source,
                        originalTarget,
                        (ts.min + i) ^ 0x54534C53
                    ),
                    true,
                    EdgeRole.SWITCH_CASE
                )
            );
        }
        mn.instructions.insert(ts, tail);
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
            throw new IllegalStateException(
                "CFF target has no dispatch target: " + label.getLabel()
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
        EdgeRole role
    ) {
        InsnList insns = new InsnList();
        long stepSeed = transitionKeySeed(edgeSeed, state, target, role);
        if (updateGuard) {
            emitDecodeBlockKeys(
                insns,
                keyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                keyTmpLocal,
                keyTmpLocal + 3,
                sourceKeys,
                targetKeys,
                methodSeed,
                stepSeed,
                role
            );
        }
        switch (chooseEdgeKind(edgeSeed, role, target)) {
            case DIRECT_ISLAND -> {
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
                insns.add(
                    new JumpInsnNode(
                        Opcodes.GOTO,
                        target.islandLabels()[target.island()]
                    )
                );
            }
            case ALIAS_HUB -> {
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
                insns.add(
                    new JumpInsnNode(
                        Opcodes.GOTO,
                        selectAliasHub(target, edgeSeed)
                    )
                );
            }
            case HUB -> {
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
                insns.add(new JumpInsnNode(Opcodes.GOTO, target.hub()));
            }
        }
        return insns;
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
        long salt
    ) {
        InsnList insns = new InsnList();
        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        Map<LabelNode, LabelNode> stubs = new IdentityHashMap<>();
        List<LabelNode> fakes = new ArrayList<>();
        int firstState = 0;
        boolean first = true;
        for (Block block : group.blocks()) {
            Integer blockIsland = group.islands().get(block.label());
            if (blockIsland != null && blockIsland == island) {
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
                cases.put(blockKeys.pcToken(), stub);
                if (first) {
                    firstState = state;
                    first = false;
                }
            }
        }
        if (first) return insns;
        int fakeCount = fakeCaseCount(group.salt() ^ salt ^ island);
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
            cases,
            poison,
            group.salt() ^ island
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
                firstState,
                island,
                keyStateByLabel,
                methodSeed,
                fakeSeed
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
        int state,
        int island,
        Map<LabelNode, CffBlockKeyState> keyStateByLabel,
        long methodSeed,
        long seed
    ) {
        LabelNode hop = new LabelNode();
        LabelNode pass = new LabelNode();
        CffBlockKeyState bounceKeys = firstIslandKeyState(
            group,
            island,
            keyStateByLabel
        );
        LabelNode bounceLabel = firstIslandLabel(group, island);
        emitInstallBlockKeys(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            bounceKeys
        );
        switch ((int) ((seed >>> 37) & 3L)) {
            case 0 -> {
                insns.add(new JumpInsnNode(Opcodes.GOTO, bounceLabel));
            }
            case 1 -> {
                insns.add(new JumpInsnNode(Opcodes.GOTO, hop));
                insns.add(hop);
                insns.add(new JumpInsnNode(Opcodes.GOTO, bounceLabel));
            }
            case 2 -> {
                emitKeyPredicate(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    seed
                );
                insns.add(new JumpInsnNode(Opcodes.IFNE, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, bounceLabel));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, bounceLabel));
            }
            default -> {
                emitKeyPredicate(
                    insns,
                    guardLocal,
                    pathKeyLocal,
                    blockKeyLocal,
                    seed ^ 0x504154484F504151L
                );
                insns.add(new JumpInsnNode(Opcodes.IFEQ, hop));
                insns.add(new JumpInsnNode(Opcodes.GOTO, bounceLabel));
                insns.add(hop);
                insns.add(new JumpInsnNode(Opcodes.GOTO, bounceLabel));
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
        boolean reverse = ((orderSeed >>> 11) & 1L) != 0L;
        for (int n = 0; n < islandLabels.length; n++) {
            int i = reverse ? islandLabels.length - 1 - n : n;
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
            JvmPassBytecode.pushInt(insns, domainToken(orderSeed, i));
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
            insns.add(new JumpInsnNode(Opcodes.GOTO, islandLabels[i]));
            insns.add(next);
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, poison));
    }

    private void emitTokenDispatch(
        InsnList insns,
        int pcLocal,
        TreeMap<Integer, LabelNode> cases,
        LabelNode poison,
        long orderSeed
    ) {
        List<Map.Entry<Integer, LabelNode>> entries = new ArrayList<>(
            cases.entrySet()
        );
        if (((orderSeed >>> 17) & 1L) != 0L) {
            Collections.reverse(entries);
        }
        for (int n = 0; n < entries.size(); n++) {
            Map.Entry<Integer, LabelNode> entry = entries.get(n);
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
            JvmPassBytecode.pushInt(insns, entry.getKey());
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
            insns.add(new JumpInsnNode(Opcodes.GOTO, entry.getValue()));
            insns.add(next);
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, poison));
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

    private void emitEncryptedToken(
        InsnList insns,
        int token,
        CffBlockKeyState expectedKeys,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        long seed
    ) {
        int encrypted = token ^ controlTokenMask(expectedKeys, seed);
        JvmPassBytecode.pushInt(insns, encrypted);
        emitControlTokenMask(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            seed
        );
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitInstallBlockKeys(
        InsnList insns,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        CffBlockKeyState keyState
    ) {
        JvmPassBytecode.pushInt(insns, keyState.guardKey());
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
        JvmPassBytecode.pushInt(insns, keyState.pathKey());
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathKeyLocal));
        JvmPassBytecode.pushInt(insns, keyState.blockKey());
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockKeyLocal));
    }

    private void emitDecodeBlockKeys(
        InsnList insns,
        int keyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int keyTmpLocal,
        int keyBaseLocal,
        CffBlockKeyState sourceKeys,
        CffBlockKeyState targetKeys,
        long methodSeed,
        long seed,
        EdgeRole role
    ) {
        long baseSeed = seed ^ 0x5452414E534B4559L ^ role.ordinal();
        emitControlTokenBase(
            insns,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyBaseLocal,
            baseSeed
        );
        emitDecodeBlockKeyWord(
            insns,
            keyTmpLocal,
            targetKeys.guardKey(),
            sourceKeys,
            baseSeed,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyBaseLocal,
            seed ^ 0x47554152444B31L ^ role.ordinal()
        );
        emitDecodeBlockKeyWord(
            insns,
            keyTmpLocal + 1,
            targetKeys.pathKey(),
            sourceKeys,
            baseSeed,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyBaseLocal,
            seed ^ 0x504154484B455931L ^ role.ordinal()
        );
        emitDecodeBlockKeyWord(
            insns,
            keyTmpLocal + 2,
            targetKeys.blockKey(),
            sourceKeys,
            baseSeed,
            keyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            keyBaseLocal,
            seed ^ 0x424C4F434B4B31L ^ role.ordinal()
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal));
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal + 1));
        insns.add(new VarInsnNode(Opcodes.ISTORE, pathKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, keyTmpLocal + 2));
        insns.add(new VarInsnNode(Opcodes.ISTORE, blockKeyLocal));
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
        return x;
    }

    private int controlTokenMaskFromBase(int base, long seed) {
        int x = base ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D534B31L));
        x += nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D414431L)) | 1;
        x ^= x >>> shift(seed, 13);
        return x;
    }

    private int controlTokenMask(
        CffBlockKeyState keyState,
        long seed
    ) {
        int x = keyState.guardKey() ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354474D31L));
        x += keyState.pathKey() *
            (nonZeroInt(JvmPassBytecode.mix(seed, 0x4354504D31L)) | 1);
        x ^= keyState.blockKey() +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D31L));
        x ^= x >>> shift(seed, 13);
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
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4354474D31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4354504D31L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x4354424D31L)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 13));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitControlTokenBase(
        InsnList insns,
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
        BytecodeFrameAnalysis frames,
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
            CffBlockKeyState canonical = keyStates.get(alias.getValue());
            if (canonical != null) keyStates.put(alias.getKey(), canonical);
        }
        return keyStates;
    }

    private void rewriteKeyedCallTransfers(
        PipelineContext pctx,
        MethodNode mn,
        List<Block> blocks,
        int keyLocal,
        long salt
    ) {
        for (Block block : blocks) {
            for (
                AbstractInsnNode insn = block.label();
                insn != null && insn != block.endExclusive();
                insn = insn.getNext()
            ) {
                Long targetSeed = keyedTargetSeed(pctx, insn);
                if (targetSeed == null) continue;
                AbstractInsnNode keyLoad = previousReal(insn.getPrevious());
                if (!isGeneratedKeyLoad(pctx, keyLoad, keyLocal)) continue;
                long rawSeed = incomingRawForCanonical(targetSeed);
                InsnList replacement = new InsnList();
                emitStaticDecodedLong(
                    replacement,
                    rawSeed,
                    salt ^ targetSeed ^ System.identityHashCode(insn)
                );
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(keyLoad, replacement);
                mn.instructions.remove(keyLoad);
            }
        }
    }

    private Long keyedTargetSeed(PipelineContext pctx, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            if ("<init>".equals(call.name)) return null;
            if (!isExactKeyedTarget(pctx, call.owner, call.name, call.desc)) {
                return null;
            }
            return JvmKeyDispatchPass.findMethodSeed(
                pctx,
                JvmKeyDispatchPass.coverageKey(call.owner, call.name, call.desc)
            );
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            for (Object arg : indy.bsmArgs) {
                if (!(arg instanceof Handle handle)) continue;
                if (!isExactKeyedTarget(
                    pctx,
                    handle.getOwner(),
                    handle.getName(),
                    handle.getDesc()
                )) {
                    continue;
                }
                Long seed = JvmKeyDispatchPass.findMethodSeed(
                    pctx,
                    JvmKeyDispatchPass.coverageKey(
                        handle.getOwner(),
                        handle.getName(),
                        handle.getDesc()
                    )
                );
                if (seed != null) return seed;
            }
        }
        return null;
    }

    private boolean isExactKeyedTarget(
        PipelineContext pctx,
        String owner,
        String name,
        String desc
    ) {
        L1Class targetClass = pctx.classMap().get(owner);
        if (targetClass == null) return false;
        L1Method targetMethod = targetClass.findMethod(name, desc);
        if (targetMethod == null) return false;
        return usesExternalEntrySeed(pctx, targetClass, targetMethod);
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

    private void emitStaticDecodedLong(
        InsnList insns,
        long value,
        long seed
    ) {
        long mask = nonZeroLong(JvmPassBytecode.mix(seed, 0x524157534545444CL));
        JvmPassBytecode.pushLong(insns, value ^ mask);
        JvmPassBytecode.pushLong(insns, mask);
        insns.add(new InsnNode(Opcodes.LXOR));
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
            methodKeyFromBlock(guardKey, pathKey, blockKey, methodSalt),
            methodSalt
        );
    }

    private long methodKeyFromBlock(
        int guardKey,
        int pathKey,
        int blockKey,
        long methodSalt
    ) {
        long high = ((long) guardKey) << 32;
        long low = ((long) pathKey) & 0xFFFFFFFFL;
        return nonZeroLong((high ^ low) + (((long) blockKey) ^ methodSalt));
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
