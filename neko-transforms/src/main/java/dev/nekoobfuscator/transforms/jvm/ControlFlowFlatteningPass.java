package dev.nekoobfuscator.transforms.jvm;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.VarInsnNode;
import org.objectweb.asm.tree.analysis.Analyzer;
import org.objectweb.asm.tree.analysis.BasicInterpreter;
import org.objectweb.asm.tree.analysis.BasicValue;
import org.objectweb.asm.tree.analysis.Frame;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Direct keyed control-flow flattening over the original method body.
 *
 * <p>The pass keeps bytecode in the original method and rewrites basic-block
 * exits to store an encoded state and return to a dispatcher. Constructors keep
 * the mandatory this/super initialization prefix untouched; flattening starts
 * immediately after that prefix.</p>
 */
public final class ControlFlowFlatteningPass implements TransformPass {
    public static final String ID = "controlFlowFlattening";

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
        // Method-local transform.
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;
        if (!isApplicationMethod(pctx, clazz, method)) return;

        MethodNode mn = method.asmNode();
        LabelNode protectedStart = protectedStartLabel(clazz, method, mn);
        if (protectedStart == null) return;

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        long methodSeed = JvmKeyDispatchPass.methodSeed(pctx.masterSeed(), clazz, method, mn);
        int keyLocal = mn.maxLocals;
        int[] localKinds = collectLocalKinds(mn, protectedStart, keyLocal);
        Map<LabelNode, LabelNode> handlerBodies = splitExceptionHandlers(mn);
        Set<LabelNode> zeroStackLabels = zeroStackLabels(clazz.name(), mn);
        Set<LabelNode> linearLeaders = hasConflictingLocalKinds(localKinds)
            ? Set.of()
            : linearZeroStackLeaders(clazz.name(), mn, protectedStart);
        List<Block> blocks = buildBlocks(mn, protectedStart, new HashSet<>(handlerBodies.values()),
            zeroStackLabels, linearLeaders);
        if (blocks.isEmpty()) return;

        int stateLocal = keyLocal + 2;
        int exceptionLocal = handlerBodies.isEmpty() ? -1 : stateLocal + 1;
        mn.maxLocals = stateLocal + 1 + (handlerBodies.isEmpty() ? 0 : 1);
        JvmKeyDispatchPass.recordMethodKeyLocal(pctx, methodKey, keyLocal, methodSeed);

        long salt = JvmPassBytecode.mix(pctx.masterSeed(), methodKey.hashCode());
        int stateMask = (int) salt;
        int[] states = uniqueStates((int) (salt >>> 32), blocks.size());
        Map<LabelNode, Integer> stateByLabel = new IdentityHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            stateByLabel.put(blocks.get(i).label(), states[i]);
        }

        LabelNode dispatcher = new LabelNode();
        int initializedLocalFloor = method.isConstructor() ? keyLocal : parameterLocalLimit(method);
        InsnList dispatch = buildDispatcher(blocks, keyLocal, stateLocal, methodSeed, stateMask,
            states, dispatcher, localKinds, initializedLocalFloor, exceptionLocal);
        mn.instructions.insertBefore(protectedStart, dispatch);
        insertHandlerBridges(mn, handlerBodies, exceptionLocal, dispatcher, keyLocal, stateLocal,
            stateMask, stateByLabel, methodSeed);

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            LabelNode next = i + 1 < blocks.size() ? blocks.get(i + 1).label() : null;
            rewriteBlockExit(mn, block, next, dispatcher, keyLocal, stateLocal, stateMask, stateByLabel);
        }

        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = Math.max(mn.maxStack + 6, 8);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), "direct-keyed-block-dispatcher-" + blocks.size());
    }

    private boolean isApplicationMethod(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (method.isAbstract() || method.isNative()) return false;
        if (TransformGuards.hasStackIntrospection(method)) return false;
        return !TransformGuards.isReflectionShapeSensitive(pctx, clazz);
    }

    private LabelNode protectedStartLabel(L1Class clazz, L1Method method, MethodNode mn) {
        if (method.isConstructor()) {
            AbstractInsnNode init = constructorInitInsn(clazz, mn);
            AbstractInsnNode next = init == null ? firstReal(mn) : nextReal(init.getNext());
            if (next == null) return null;
            return ensureLabelBefore(mn, next);
        }
        AbstractInsnNode first = firstReal(mn);
        return first == null ? null : ensureLabelBefore(mn, first);
    }

    private AbstractInsnNode constructorInitInsn(L1Class clazz, MethodNode mn) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode call
                    && call.getOpcode() == Opcodes.INVOKESPECIAL
                    && "<init>".equals(call.name)
                    && (clazz.name().equals(call.owner) || clazz.superName().equals(call.owner))) {
                return insn;
            }
        }
        return null;
    }

    private Map<LabelNode, LabelNode> splitExceptionHandlers(MethodNode mn) {
        Map<LabelNode, LabelNode> bodies = new HashMap<>();
        if (mn.tryCatchBlocks == null) return bodies;
        for (TryCatchBlockNode tcb : mn.tryCatchBlocks) {
            if (bodies.containsKey(tcb.handler)) continue;
            AbstractInsnNode bodyStart = nextReal(tcb.handler.getNext());
            if (bodyStart == null) continue;
            LabelNode body = ensureLabelBefore(mn, bodyStart);
            bodies.put(tcb.handler, body);
        }
        return bodies;
    }

    private List<Block> buildBlocks(MethodNode mn, LabelNode start, Set<LabelNode> extraLeaders,
            Set<LabelNode> zeroStackLabels, Set<LabelNode> linearLeaders) {
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
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn instanceof JumpInsnNode jump) {
                if (zeroStackLabels.contains(jump.label)) leaders.add(jump.label);
                AbstractInsnNode next = nextReal(insn.getNext());
                if (next != null && jump.getOpcode() != Opcodes.GOTO) {
                    leaders.add(ensureLabelBefore(mn, next));
                }
            } else if (insn instanceof TableSwitchInsnNode ts) {
                if (zeroStackLabels.contains(ts.dflt)) leaders.add(ts.dflt);
                for (LabelNode label : ts.labels) {
                    if (zeroStackLabels.contains(label)) leaders.add(label);
                }
                AbstractInsnNode next = nextReal(insn.getNext());
                if (next != null) leaders.add(ensureLabelBefore(mn, next));
            } else if (insn instanceof LookupSwitchInsnNode ls) {
                if (zeroStackLabels.contains(ls.dflt)) leaders.add(ls.dflt);
                for (LabelNode label : ls.labels) {
                    if (zeroStackLabels.contains(label)) leaders.add(label);
                }
                AbstractInsnNode next = nextReal(insn.getNext());
                if (next != null) leaders.add(ensureLabelBefore(mn, next));
            } else if (terminates(insn.getOpcode())) {
                AbstractInsnNode next = nextReal(insn.getNext());
                if (next != null) leaders.add(ensureLabelBefore(mn, next));
            }
        }

        List<LabelNode> ordered = new ArrayList<>();
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label && leaders.contains(label)) {
                ordered.add(label);
            }
        }
        List<Block> blocks = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            LabelNode label = ordered.get(i);
            AbstractInsnNode endExclusive = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            blocks.add(new Block(label, endExclusive, handlerLabels.contains(label)));
        }
        return blocks;
    }

    private Set<LabelNode> linearZeroStackLeaders(String owner, MethodNode mn, LabelNode start) {
        Set<LabelNode> leaders = new HashSet<>();
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(owner, mn);
            AbstractInsnNode[] insns = mn.instructions.toArray();
            Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
            for (int i = 0; i < insns.length; i++) {
                index.put(insns[i], i);
            }
            boolean active = false;
            for (int i = 0; i < insns.length - 1; i++) {
                if (insns[i] == start) active = true;
                if (!active || insns[i].getOpcode() < 0 || isControlTransfer(insns[i])) continue;
                AbstractInsnNode next = nextReal(insns[i].getNext());
                if (next == null) continue;
                Integer nextIndex = index.get(next);
                if (nextIndex == null || frames[nextIndex] == null) continue;
                if (frames[nextIndex].getStackSize() == 0) {
                    leaders.add(ensureLabelBefore(mn, next));
                }
            }
        } catch (Exception ignored) {
            // If analysis cannot prove stack-empty boundaries, existing CFG
            // leaders are still flattened. No guessed split points are added.
        }
        return leaders;
    }

    private Set<LabelNode> zeroStackLabels(String owner, MethodNode mn) {
        Set<LabelNode> labels = new HashSet<>();
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            Frame<BasicValue>[] frames = analyzer.analyze(owner, mn);
            AbstractInsnNode[] insns = mn.instructions.toArray();
            for (int i = 0; i < insns.length; i++) {
                if (insns[i] instanceof LabelNode label
                        && frames[i] != null
                        && frames[i].getStackSize() == 0) {
                    labels.add(label);
                }
            }
        } catch (Exception ignored) {
            for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (insn instanceof LabelNode label) labels.add(label);
            }
        }
        return labels;
    }

    private void insertHandlerBridges(MethodNode mn, Map<LabelNode, LabelNode> handlerBodies,
            int exceptionLocal, LabelNode dispatcher, int keyLocal, int stateLocal, int stateMask,
            Map<LabelNode, Integer> stateByLabel, long methodSeed) {
        if (handlerBodies.isEmpty()) return;
        for (Map.Entry<LabelNode, LabelNode> entry : handlerBodies.entrySet()) {
            LabelNode handler = entry.getKey();
            LabelNode body = entry.getValue();
            Integer bodyState = stateByLabel.get(body);
            InsnList prefix = new InsnList();
            JvmKeyDispatchPass.emitKeyInit(prefix, keyLocal, methodSeed, 0x4346464B65794C31L);
            prefix.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
            prefix.add(transition(bodyState, dispatcher, keyLocal, stateLocal, stateMask));
            mn.instructions.insert(handler, prefix);

            InsnList reload = new InsnList();
            reload.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
            mn.instructions.insert(body, reload);
        }
    }

    private InsnList buildDispatcher(List<Block> blocks, int keyLocal, int stateLocal, long methodSeed,
            int stateMask, int[] states, LabelNode dispatcher, int[] localKinds, int initializedLocalFloor,
            int exceptionLocal) {
        InsnList insns = new InsnList();
        LabelNode dflt = new LabelNode();
        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        List<LabelNode> trampolines = new ArrayList<>();
        for (int i = 0; i < blocks.size(); i++) {
            LabelNode trampoline = new LabelNode();
            trampolines.add(trampoline);
            if (!blocks.get(i).handler()) {
                cases.put(states[i], trampoline);
            }
        }

        JvmKeyDispatchPass.emitKeyInit(insns, keyLocal, methodSeed, 0x4346464B65794C31L);
        emitLocalDefaults(insns, localKinds, initializedLocalFloor, keyLocal);
        if (exceptionLocal >= 0) {
            insns.add(new InsnNode(Opcodes.ACONST_NULL));
            insns.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
        }
        emitStoreEncodedState(insns, stateLocal, keyLocal, states[0], stateMask);
        insns.add(new JumpInsnNode(Opcodes.GOTO, dispatcher));
        insns.add(dispatcher);
        emitDecodeState(insns, stateLocal, keyLocal, stateMask);
        insns.add(new LookupSwitchInsnNode(dflt,
            cases.keySet().stream().mapToInt(Integer::intValue).toArray(),
            cases.values().toArray(LabelNode[]::new)));

        insns.add(dflt);
        insns.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/IllegalStateException", "<init>", "()V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));

        for (int i = 0; i < blocks.size(); i++) {
            insns.add(trampolines.get(i));
            insns.add(new JumpInsnNode(Opcodes.GOTO, blocks.get(i).label()));
        }
        return insns;
    }

    private int[] collectLocalKinds(MethodNode mn, LabelNode protectedStart, int keyLocal) {
        int[] kinds = new int[Math.max(keyLocal, mn.maxLocals) + 2];
        boolean active = false;
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn == protectedStart) active = true;
            if (!active) continue;
            if (insn instanceof VarInsnNode varInsn) {
                int kind = localKind(varInsn.getOpcode());
                if (kind != 0 && varInsn.var < keyLocal) {
                    mergeLocalKind(kinds, varInsn.var, kind);
                }
            } else if (insn instanceof org.objectweb.asm.tree.IincInsnNode iinc && iinc.var < keyLocal) {
                mergeLocalKind(kinds, iinc.var, 1);
            }
        }
        return kinds;
    }

    private int localKind(int opcode) {
        return switch (opcode) {
            case Opcodes.ILOAD, Opcodes.ISTORE -> 1;
            case Opcodes.LLOAD, Opcodes.LSTORE -> 2;
            case Opcodes.FLOAD, Opcodes.FSTORE -> 3;
            case Opcodes.DLOAD, Opcodes.DSTORE -> 4;
            case Opcodes.ALOAD, Opcodes.ASTORE -> 5;
            default -> 0;
        };
    }

    private void mergeLocalKind(int[] kinds, int local, int kind) {
        if (kinds[local] == 0) {
            kinds[local] = kind;
        } else if (kinds[local] != kind) {
            kinds[local] = 6;
        }
    }

    private void emitLocalDefaults(InsnList insns, int[] localKinds, int from, int to) {
        for (int local = Math.max(0, from); local < to && local < localKinds.length; local++) {
            switch (localKinds[local]) {
                case 1, 6 -> {
                    insns.add(new InsnNode(Opcodes.ICONST_0));
                    insns.add(new VarInsnNode(Opcodes.ISTORE, local));
                }
                case 2 -> {
                    insns.add(new InsnNode(Opcodes.LCONST_0));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, local));
                    local++;
                }
                case 3 -> {
                    insns.add(new InsnNode(Opcodes.FCONST_0));
                    insns.add(new VarInsnNode(Opcodes.FSTORE, local));
                }
                case 4 -> {
                    insns.add(new InsnNode(Opcodes.DCONST_0));
                    insns.add(new VarInsnNode(Opcodes.DSTORE, local));
                    local++;
                }
                case 5 -> {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, local));
                }
                default -> { }
            }
        }
    }

    private boolean hasConflictingLocalKinds(int[] localKinds) {
        for (int kind : localKinds) {
            if (kind == 6) return true;
        }
        return false;
    }

    private int parameterLocalLimit(L1Method method) {
        int slot = method.isStatic() || method.isClassInit() ? 0 : 1;
        for (Type argument : Type.getArgumentTypes(method.descriptor())) {
            slot += argument.getSize();
        }
        return slot;
    }

    private void rewriteBlockExit(MethodNode mn, Block block, LabelNode next, LabelNode dispatcher,
            int keyLocal, int stateLocal, int stateMask, Map<LabelNode, Integer> stateByLabel) {
        AbstractInsnNode last = lastRealBefore(block.endExclusive());
        if (last == null || before(last, block.label())) return;
        int opcode = last.getOpcode();
        if (terminates(opcode)) return;

        if (last instanceof JumpInsnNode jump) {
            if (opcode == Opcodes.GOTO) {
                Integer targetState = stateByLabel.get(jump.label);
                if (targetState == null) return;
                mn.instructions.insertBefore(last, transition(targetState, dispatcher, keyLocal, stateLocal, stateMask));
                mn.instructions.remove(last);
                return;
            }
            LabelNode taken = new LabelNode();
            Integer targetState = stateByLabel.get(jump.label);
            Integer fallthroughState = next == null ? null : stateByLabel.get(next);
            if (targetState == null || fallthroughState == null) return;
            JumpInsnNode replacement = new JumpInsnNode(opcode, taken);
            mn.instructions.insertBefore(last, replacement);
            mn.instructions.insertBefore(last, transition(fallthroughState, dispatcher, keyLocal, stateLocal, stateMask));
            mn.instructions.insertBefore(last, taken);
            mn.instructions.insertBefore(last, transition(targetState, dispatcher, keyLocal, stateLocal, stateMask));
            mn.instructions.remove(last);
            return;
        }
        if (last instanceof LookupSwitchInsnNode ls) {
            rewriteLookupSwitch(mn, ls, dispatcher, keyLocal, stateLocal, stateMask, stateByLabel);
            return;
        }
        if (last instanceof TableSwitchInsnNode ts) {
            rewriteTableSwitch(mn, ts, dispatcher, keyLocal, stateLocal, stateMask, stateByLabel);
            return;
        }
        if (next != null) {
            Integer nextState = stateByLabel.get(next);
            if (nextState == null) return;
            mn.instructions.insert(last, transition(nextState, dispatcher, keyLocal, stateLocal, stateMask));
        }
    }

    private void rewriteLookupSwitch(MethodNode mn, LookupSwitchInsnNode ls, LabelNode dispatcher,
            int keyLocal, int stateLocal, int stateMask, Map<LabelNode, Integer> stateByLabel) {
        LabelNode defaultSet = new LabelNode();
        List<LabelNode> setLabels = new ArrayList<>();
        for (int i = 0; i < ls.labels.size(); i++) setLabels.add(new LabelNode());
        List<LabelNode> originalTargets = new ArrayList<>(ls.labels);
        LabelNode originalDefault = ls.dflt;
        if (!stateByLabel.containsKey(originalDefault)) return;
        for (LabelNode target : originalTargets) {
            if (!stateByLabel.containsKey(target)) return;
        }
        ls.labels.clear();
        ls.labels.addAll(setLabels);
        ls.dflt = defaultSet;
        InsnList tail = new InsnList();
        tail.add(defaultSet);
        tail.add(transition(stateByLabel.get(originalDefault), dispatcher, keyLocal, stateLocal, stateMask));
        for (int i = 0; i < setLabels.size(); i++) {
            tail.add(setLabels.get(i));
            tail.add(transition(stateByLabel.get(originalTargets.get(i)), dispatcher, keyLocal, stateLocal, stateMask));
        }
        mn.instructions.insert(ls, tail);
    }

    private void rewriteTableSwitch(MethodNode mn, TableSwitchInsnNode ts, LabelNode dispatcher,
            int keyLocal, int stateLocal, int stateMask, Map<LabelNode, Integer> stateByLabel) {
        LabelNode defaultSet = new LabelNode();
        List<LabelNode> setLabels = new ArrayList<>();
        for (int i = 0; i < ts.labels.size(); i++) setLabels.add(new LabelNode());
        List<LabelNode> originalTargets = new ArrayList<>(ts.labels);
        LabelNode originalDefault = ts.dflt;
        if (!stateByLabel.containsKey(originalDefault)) return;
        for (LabelNode target : originalTargets) {
            if (!stateByLabel.containsKey(target)) return;
        }
        ts.labels.clear();
        ts.labels.addAll(setLabels);
        ts.dflt = defaultSet;
        InsnList tail = new InsnList();
        tail.add(defaultSet);
        tail.add(transition(stateByLabel.get(originalDefault), dispatcher, keyLocal, stateLocal, stateMask));
        for (int i = 0; i < setLabels.size(); i++) {
            tail.add(setLabels.get(i));
            tail.add(transition(stateByLabel.get(originalTargets.get(i)), dispatcher, keyLocal, stateLocal, stateMask));
        }
        mn.instructions.insert(ts, tail);
    }

    private InsnList transition(Integer state, LabelNode dispatcher, int keyLocal, int stateLocal, int stateMask) {
        InsnList insns = new InsnList();
        if (state == null) {
            insns.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>", "()V", false));
            insns.add(new InsnNode(Opcodes.ATHROW));
            return insns;
        }
        emitStoreEncodedState(insns, stateLocal, keyLocal, state, stateMask);
        insns.add(new JumpInsnNode(Opcodes.GOTO, dispatcher));
        return insns;
    }

    private void emitStoreEncodedState(InsnList insns, int stateLocal, int keyLocal, int state, int mask) {
        JvmPassBytecode.pushInt(insns, state ^ mask);
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
    }

    private void emitDecodeState(InsnList insns, int stateLocal, int keyLocal, int mask) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        JvmPassBytecode.pushInt(insns, mask);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int[] uniqueStates(int seed, int count) {
        int[] states = new int[count];
        Set<Integer> used = new HashSet<>();
        long state = seed;
        for (int i = 0; i < count; i++) {
            int candidate;
            do {
                state = JvmPassBytecode.mix(state, i + 0x51ED2705L);
                candidate = (int) state;
            } while (!used.add(candidate));
            states[i] = candidate;
        }
        return states;
    }

    private LabelNode ensureLabelBefore(MethodNode mn, AbstractInsnNode node) {
        AbstractInsnNode previous = node.getPrevious();
        if (previous instanceof LabelNode label) return label;
        LabelNode label = new LabelNode();
        mn.instructions.insertBefore(node, label);
        return label;
    }

    private AbstractInsnNode firstReal(MethodNode mn) {
        return nextReal(mn.instructions.getFirst());
    }

    private AbstractInsnNode nextReal(AbstractInsnNode start) {
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private AbstractInsnNode lastRealBefore(AbstractInsnNode endExclusive) {
        AbstractInsnNode insn = endExclusive == null ? null : endExclusive.getPrevious();
        if (insn == null) return null;
        for (; insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() >= 0) return insn;
        }
        return null;
    }

    private boolean before(AbstractInsnNode left, AbstractInsnNode right) {
        for (AbstractInsnNode insn = left; insn != null; insn = insn.getNext()) {
            if (insn == right) return true;
        }
        return false;
    }

    private boolean terminates(int opcode) {
        return (opcode >= Opcodes.IRETURN && opcode <= Opcodes.RETURN)
            || opcode == Opcodes.ATHROW;
    }

    private boolean isControlTransfer(AbstractInsnNode insn) {
        return insn instanceof JumpInsnNode
            || insn instanceof TableSwitchInsnNode
            || insn instanceof LookupSwitchInsnNode
            || terminates(insn.getOpcode());
    }

    private record Block(LabelNode label, AbstractInsnNode endExclusive, boolean handler) {}
}
