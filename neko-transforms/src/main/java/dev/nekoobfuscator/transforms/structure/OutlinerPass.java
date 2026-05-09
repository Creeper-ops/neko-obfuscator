package dev.nekoobfuscator.transforms.structure;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.ir.l2.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.core.util.AsmUtil;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.*;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.AnalyzerException;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;
import org.objectweb.asm.tree.analysis.SimpleVerifier;

import java.util.*;

/**
 * Outliner: extracts basic blocks into separate private static methods.
 * This splits the control flow across methods, making analysis harder.
 */
public final class OutlinerPass implements TransformPass {

    private static final String MIN_BLOCK_SIZE_OPTION = "minBlockSize";

    @Override public String id() { return "outliner"; }
    @Override public String name() { return "Outliner"; }
    @Override public TransformPhase phase() { return TransformPhase.PRE_TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }

    private int outlinedMethodCounter;

    @Override
    public void transformClass(TransformContext ctx) {
        outlinedMethodCounter = 0;
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        if (!method.hasCode() || method.isConstructor() || method.isClassInit()) return;
        if (TransformGuards.hasStackIntrospection(method)
                || TransformGuards.isReflectionShapeSensitive(pctx, clazz)) return;

        double intensity = pctx.config().getTransformIntensity("outliner");
        if (pctx.random().nextDouble() > intensity) return;
        int minBlockSize = intOption(pctx, MIN_BLOCK_SIZE_OPTION, 3);

        ControlFlowGraph cfg = pctx.getCFG(method);
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames = analyzeFrames(clazz.name(), method.asmNode());
        if (frames.isEmpty()) return;

        List<OutlineCandidate> candidates = new ArrayList<>();
        for (BasicBlock block : cfg.blocks()) {
            int realInsns = 0;
            for (AbstractInsnNode insn : block.instructions()) {
                if (AsmUtil.isRealInstruction(insn)) realInsns++;
            }
            Set<Integer> reads = new LinkedHashSet<>();
            Set<Integer> writes = new LinkedHashSet<>();
            analyzeLocals(block, reads, writes);
            if (realInsns >= minBlockSize
                && !block.isExceptionHandler()
                && block.successors().size() <= 1
                && writes.size() <= 1
                && isOutlineSafe(block, frames)) {
                candidates.add(new OutlineCandidate(block, realInsns, writes.isEmpty() ? 0 : 1));
            }
        }

        if (candidates.isEmpty()) return;
        candidates.sort(Comparator.comparingInt(OutlineCandidate::score).reversed());

        // Outline selected blocks
        int maxOutline = Math.min(candidates.size(), 3); // limit per method
        for (int i = 0; i < maxOutline; i++) {
            BasicBlock block = candidates.get(i).block();
            if (outlineBlock(clazz, method, block, frames)) {
                clazz.markDirty();
            }
        }

        pctx.invalidate(method);
    }

    private boolean outlineBlock(L1Class clazz, L1Method method, BasicBlock block,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames) {
        MethodNode mn = method.asmNode();
        String outlinedName = "__neko_o" + (outlinedMethodCounter++);

        Set<Integer> reads = new LinkedHashSet<>();
        Set<Integer> writes = new LinkedHashSet<>();
        analyzeLocals(block, reads, writes);
        if (writes.size() > 1) return false;
        Integer writtenLocal = writes.isEmpty() ? null : writes.iterator().next();

        List<Integer> paramLocals = new ArrayList<>(reads);
        Map<Integer, Type> localTypes = localTypes(method, block, frames, paramLocals);
        if (localTypes.size() != paramLocals.size()) return false;
        Type returnType = writtenLocal != null ? typeForWrittenLocal(method, block, frames, writtenLocal) : Type.VOID_TYPE;
        if (returnType == null || returnType == Type.VOID_TYPE && writtenLocal != null) return false;

        StringBuilder descBuilder = new StringBuilder("(");
        for (int local : paramLocals) {
            descBuilder.append(localTypes.get(local).getDescriptor());
        }
        descBuilder.append(')').append(returnType.getDescriptor());
        String desc = descBuilder.toString();

        MethodNode outlined = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            outlinedName, desc, null, null);
        outlined.instructions = new InsnList();

        Map<Integer, Integer> localMap = new HashMap<>();
        int paramIdx = 0;
        for (int local : paramLocals) {
            localMap.put(local, paramIdx++);
            paramIdx += localTypes.get(local).getSize() - 1;
        }
        if (writtenLocal != null && !localMap.containsKey(writtenLocal)) {
            localMap.put(writtenLocal, paramIdx);
            paramIdx += returnType.getSize();
        }

        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof LabelNode || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                continue;
            }
            if (isTrailingGoto(block, insn) || isTrailingVoidReturn(block, insn)) continue;

            AbstractInsnNode cloned = insn.clone(Map.of());
            if (cloned instanceof VarInsnNode var) {
                Integer mapped = localMap.get(var.var);
                if (mapped != null) var.var = mapped;
            } else if (cloned instanceof IincInsnNode iinc) {
                Integer mapped = localMap.get(iinc.var);
                if (mapped != null) iinc.var = mapped;
            }
            outlined.instructions.add(cloned);
        }
        if (writtenLocal != null) {
            outlined.instructions.add(new VarInsnNode(returnType.getOpcode(Opcodes.ILOAD), localMap.get(writtenLocal)));
            outlined.instructions.add(new InsnNode(returnType.getOpcode(Opcodes.IRETURN)));
        } else {
            outlined.instructions.add(new InsnNode(Opcodes.RETURN));
        }
        outlined.maxStack = mn.maxStack;
        outlined.maxLocals = paramIdx;

        clazz.asmNode().methods.add(outlined);

        InsnList call = new InsnList();
        for (int local : paramLocals) {
            Type type = localTypes.get(local);
            call.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), local));
        }
        call.add(new MethodInsnNode(Opcodes.INVOKESTATIC, clazz.name(), outlinedName, desc, false));
        if (writtenLocal != null) {
            call.add(new VarInsnNode(returnType.getOpcode(Opcodes.ISTORE), writtenLocal));
        }

        InsnList insns = mn.instructions;
        List<AbstractInsnNode> toRemove = new ArrayList<>();
        boolean inBlock = false;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (block.instructions().contains(insn)) {
                boolean replaceable = AsmUtil.isRealInstruction(insn)
                    && !isTrailingGoto(block, insn)
                    && !isTrailingVoidReturn(block, insn);
                if (!inBlock && replaceable) {
                    insns.insertBefore(insn, call);
                    inBlock = true;
                }
                if (replaceable || insn instanceof FrameNode || insn instanceof LineNumberNode) {
                    toRemove.add(insn);
                }
            }
        }
        for (AbstractInsnNode insn : toRemove) {
            insns.remove(insn);
        }
        return true;
    }

    private void analyzeLocals(BasicBlock block, Set<Integer> reads, Set<Integer> writes) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (insn instanceof VarInsnNode var) {
                if (AsmUtil.isLoad(var.getOpcode())) {
                    reads.add(var.var);
                } else if (AsmUtil.isStore(var.getOpcode())) {
                    writes.add(var.var);
                }
            } else if (insn instanceof IincInsnNode iinc) {
                reads.add(iinc.var);
                writes.add(iinc.var);
            }
        }
    }

    private int intOption(PipelineContext pctx, String key, int defaultValue) {
        var config = pctx.config().transforms().get("outliner");
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Number number ? Math.max(1, number.intValue()) : defaultValue;
    }

    private IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> analyzeFrames(String owner, MethodNode method) {
        IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> result = new IdentityHashMap<>();
        try {
            SimpleVerifier verifier = new SimpleVerifier(Type.getObjectType(owner),
                Type.getObjectType("java/lang/Object"), List.of(), false);
            Frame<BasicValue>[] frames = new Analyzer<>(verifier).analyze(owner, method);
            AbstractInsnNode[] insns = method.instructions.toArray();
            for (int i = 0; i < insns.length && i < frames.length; i++) {
                if (frames[i] != null) result.put(insns[i], frames[i]);
            }
        } catch (AnalyzerException ignored) {
            try {
                Frame<BasicValue>[] frames = new Analyzer<>(new BasicInterpreter()).analyze(owner, method);
                AbstractInsnNode[] insns = method.instructions.toArray();
                for (int i = 0; i < insns.length && i < frames.length; i++) {
                    if (frames[i] != null) result.put(insns[i], frames[i]);
                }
            } catch (AnalyzerException ignoredAgain) {
            }
        }
        return result;
    }

    private boolean isOutlineSafe(BasicBlock block, IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames) {
        AbstractInsnNode first = firstReal(block);
        if (first == null) return false;
        Frame<BasicValue> entry = frames.get(first);
        if (entry == null || entry.getStackSize() != 0) return false;

        for (AbstractInsnNode insn : block.instructions()) {
            if (!AsmUtil.isRealInstruction(insn)) continue;
            if (insn instanceof IincInsnNode) return false;
            int opcode = insn.getOpcode();
            if (opcode == Opcodes.PUTFIELD || opcode == Opcodes.PUTSTATIC
                    || opcode == Opcodes.IASTORE || opcode == Opcodes.LASTORE || opcode == Opcodes.FASTORE
                    || opcode == Opcodes.DASTORE || opcode == Opcodes.AASTORE || opcode == Opcodes.BASTORE
                    || opcode == Opcodes.CASTORE || opcode == Opcodes.SASTORE
                    || opcode == Opcodes.MONITORENTER || opcode == Opcodes.MONITOREXIT
                    || opcode == Opcodes.ATHROW
                    || (AsmUtil.isReturn(opcode) && !isTrailingVoidReturn(block, insn))) {
                return false;
            }
            if (insn instanceof TableSwitchInsnNode || insn instanceof LookupSwitchInsnNode) return false;
            if (insn instanceof JumpInsnNode && !isTrailingGoto(block, insn)) return false;
        }

        AbstractInsnNode last = lastReal(block);
        if (last == null) return false;
        if (isTrailingGoto(block, last) || isTrailingVoidReturn(block, last)) {
            Frame<BasicValue> endFrame = frames.get(last);
            return endFrame != null && endFrame.getStackSize() == 0;
        }
        Frame<BasicValue> afterFrame = nextFrameAfter(block, frames);
        return afterFrame != null && afterFrame.getStackSize() == 0;
    }

    private Map<Integer, Type> localTypes(L1Method method, BasicBlock block,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames, List<Integer> locals) {
        Map<Integer, Type> types = new HashMap<>();
        AbstractInsnNode anchor = firstReal(block);
        Frame<BasicValue> frame = frames.get(anchor);
        if (frame == null) return types;
        for (int local : locals) {
            if (local < 0 || local >= frame.getLocals()) continue;
            BasicValue value = frame.getLocal(local);
            Type type = value != null ? value.getType() : null;
            type = refineLocalType(method, local, type, anchor);
            if (type == null || type == Type.VOID_TYPE) continue;
            if (type.getSort() == Type.OBJECT && "java/lang/Object".equals(type.getInternalName())
                    && !hasExactScopedReferenceType(method, local, anchor)) {
                continue;
            }
            types.put(local, type);
        }
        return types;
    }

    private Type refineLocalType(L1Method method, int local, Type analyzerType, AbstractInsnNode anchor) {
        if (analyzerType == null || analyzerType.getSort() != Type.OBJECT
                || !"java/lang/Object".equals(analyzerType.getInternalName())) {
            return analyzerType;
        }
        for (LocalVariableNode localVariable : method.localVariables()) {
            if (localVariable.index == local && localVariable.desc != null
                    && localVariableCovers(method, localVariable, anchor)) {
                Type candidate = Type.getType(localVariable.desc);
                if (candidate.getSort() == Type.OBJECT || candidate.getSort() == Type.ARRAY) {
                    return candidate;
                }
            }
        }
        return analyzerType;
    }

    private Type typeForWrittenLocal(L1Method method, BasicBlock block,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames, int local) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (!(insn instanceof VarInsnNode var) || var.var != local || !AsmUtil.isStore(var.getOpcode())) {
                continue;
            }
            Frame<BasicValue> frame = frames.get(insn);
            if (frame != null && frame.getStackSize() > 0) {
                BasicValue value = frame.getStack(frame.getStackSize() - 1);
                Type type = value != null ? value.getType() : null;
                type = refineLocalType(method, local, type, insn);
                if (type != null && type.getSort() == Type.OBJECT
                        && "java/lang/Object".equals(type.getInternalName())) {
                    Type producerType = typeFromStoreProducer(block, insn);
                    if (producerType != null) {
                        type = producerType;
                    }
                }
                if (type != null && type != Type.VOID_TYPE) {
                    return type;
                }
            }
            Type fromLocalTable = refineLocalType(method, local, Type.getObjectType("java/lang/Object"), insn);
            if (fromLocalTable != null && !"java/lang/Object".equals(fromLocalTable.getInternalName())) {
                return fromLocalTable;
            }
            Type producerType = typeFromStoreProducer(block, insn);
            if (producerType != null) {
                return producerType;
            }
            return switch (var.getOpcode()) {
                case Opcodes.ISTORE -> Type.INT_TYPE;
                case Opcodes.LSTORE -> Type.LONG_TYPE;
                case Opcodes.FSTORE -> Type.FLOAT_TYPE;
                case Opcodes.DSTORE -> Type.DOUBLE_TYPE;
                case Opcodes.ASTORE -> null;
                default -> null;
            };
        }
        return null;
    }

    private boolean hasExactScopedReferenceType(L1Method method, int local, AbstractInsnNode anchor) {
        Type type = refineLocalType(method, local, Type.getObjectType("java/lang/Object"), anchor);
        return type != null
            && (type.getSort() == Type.OBJECT || type.getSort() == Type.ARRAY)
            && !"java/lang/Object".equals(type.getInternalName());
    }

    private boolean localVariableCovers(L1Method method, LocalVariableNode localVariable, AbstractInsnNode anchor) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = instructionPositions(method.asmNode().instructions);
        Integer start = positions.get(localVariable.start);
        Integer end = positions.get(localVariable.end);
        Integer point = positions.get(anchor);
        return start != null && end != null && point != null && start <= point && point < end;
    }

    private IdentityHashMap<AbstractInsnNode, Integer> instructionPositions(InsnList insns) {
        IdentityHashMap<AbstractInsnNode, Integer> positions = new IdentityHashMap<>();
        int index = 0;
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            positions.put(insn, index++);
        }
        return positions;
    }

    private Type typeFromStoreProducer(BasicBlock block, AbstractInsnNode storeInsn) {
        AbstractInsnNode previous = previousRealInBlock(block, storeInsn);
        if (previous instanceof TypeInsnNode typeInsn) {
            if (typeInsn.getOpcode() == Opcodes.CHECKCAST || typeInsn.getOpcode() == Opcodes.NEW) {
                return Type.getObjectType(typeInsn.desc);
            }
            if (typeInsn.getOpcode() == Opcodes.ANEWARRAY) {
                return Type.getType("[L" + typeInsn.desc + ";");
            }
        }
        if (previous instanceof MultiANewArrayInsnNode multiArray) {
            return Type.getType(multiArray.desc);
        }
        if (previous instanceof MethodInsnNode methodInsn) {
            Type returnType = Type.getReturnType(methodInsn.desc);
            if (returnType != Type.VOID_TYPE) {
                return returnType;
            }
        }

        int seen = 0;
        for (int i = block.instructions().indexOf(storeInsn) - 1; i >= 0 && seen < 80; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (!AsmUtil.isRealInstruction(insn)) continue;
            seen++;
            if (insn instanceof TypeInsnNode typeInsn && typeInsn.getOpcode() == Opcodes.NEW) {
                return Type.getObjectType(typeInsn.desc);
            }
            if (insn instanceof JumpInsnNode || insn instanceof TableSwitchInsnNode
                    || insn instanceof LookupSwitchInsnNode || AsmUtil.isReturn(insn.getOpcode())
                    || insn.getOpcode() == Opcodes.ATHROW) {
                break;
            }
        }
        return null;
    }

    private AbstractInsnNode previousRealInBlock(BasicBlock block, AbstractInsnNode insn) {
        int index = block.instructions().indexOf(insn);
        for (int i = index - 1; i >= 0; i--) {
            AbstractInsnNode candidate = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private boolean isTrailingGoto(BasicBlock block, AbstractInsnNode insn) {
        return insn == lastReal(block) && insn.getOpcode() == Opcodes.GOTO;
    }

    private boolean isTrailingVoidReturn(BasicBlock block, AbstractInsnNode insn) {
        return insn == lastReal(block) && insn.getOpcode() == Opcodes.RETURN;
    }

    private AbstractInsnNode firstReal(BasicBlock block) {
        for (AbstractInsnNode insn : block.instructions()) {
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private AbstractInsnNode lastReal(BasicBlock block) {
        for (int i = block.instructions().size() - 1; i >= 0; i--) {
            AbstractInsnNode insn = block.instructions().get(i);
            if (AsmUtil.isRealInstruction(insn)) return insn;
        }
        return null;
    }

    private Frame<BasicValue> nextFrameAfter(BasicBlock block,
            IdentityHashMap<AbstractInsnNode, Frame<BasicValue>> frames) {
        AbstractInsnNode cursor = block.lastInsn() != null ? block.lastInsn().getNext() : null;
        while (cursor != null) {
            Frame<BasicValue> frame = frames.get(cursor);
            if (frame != null) return frame;
            cursor = cursor.getNext();
        }
        return null;
    }

    private record OutlineCandidate(BasicBlock block, int realInsns, int writes) {
        int score() {
            return writes * 1000 + realInsns;
        }
    }
}
