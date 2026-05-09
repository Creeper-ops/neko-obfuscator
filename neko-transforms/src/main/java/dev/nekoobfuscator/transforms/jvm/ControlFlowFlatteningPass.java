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
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
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
 * exits to store an encoded state and return to a target-local dispatcher.
 * Constructors keep the mandatory this/super initialization prefix untouched;
 * flattening starts immediately after that prefix.</p>
 */
public final class ControlFlowFlatteningPass implements TransformPass {
    public static final String ID = "controlFlowFlattening";
    private static final long CFF_KEY_STEP = 0x9E3779B97F4A7C15L;

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

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        MethodNode mn = method.asmNode();
        Integer recordedKeyLocal = JvmKeyDispatchPass.findMethodKeyLocal(pctx, methodKey);
        int keyLocal = recordedKeyLocal != null
            ? recordedKeyLocal
            : JvmKeyDispatchPass.ensureMethodKeyLocal(pctx, clazz, method);
        if (keyLocal < 0) {
            throw new IllegalStateException("CFF requires an initialized method key for "
                + clazz.name() + "." + method.name() + method.descriptor());
        }
        long methodSeed = JvmKeyDispatchPass.findMethodSeed(pctx, methodKey) != null
            ? JvmKeyDispatchPass.findMethodSeed(pctx, methodKey)
            : JvmKeyDispatchPass.methodSeed(pctx.masterSeed(), clazz, method, mn);
        LabelNode protectedStart = protectedStartLabel(clazz, method, mn, keyLocal);
        if (protectedStart == null) return;

        Map<LabelNode, LabelNode> handlerBodies = splitExceptionHandlers(mn);
        Frame<BasicValue>[] frames = analyzeFrames(clazz.name(), mn);
        Map<AbstractInsnNode, Integer> instructionIndex = instructionIndex(mn);
        Set<LabelNode> zeroStackLabels = zeroStackLabels(mn, frames, instructionIndex);
        Set<LabelNode> linearLeaders = linearZeroStackLeaders(mn, protectedStart, frames, instructionIndex);
        BlockPlan blockPlan = buildBlocks(mn, protectedStart, new HashSet<>(handlerBodies.values()),
            zeroStackLabels, linearLeaders, frames, instructionIndex);
        List<Block> blocks = blockPlan.blocks();
        if (blocks.isEmpty()) return;

        int stateLocal = mn.maxLocals;
        int exceptionLocal = handlerBodies.isEmpty() ? -1 : stateLocal + 1;
        mn.maxLocals = stateLocal + 1 + (handlerBodies.isEmpty() ? 0 : 1);

        long salt = JvmPassBytecode.mix(pctx.masterSeed(), methodKey.hashCode());
        int methodSalt = (int) salt;
        int[] states = uniqueStates((int) (salt >>> 32), blocks.size());
        Map<LabelNode, Integer> stateByLabel = new IdentityHashMap<>();
        Map<LabelNode, LabelNode> dispatcherByLabel = new IdentityHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            stateByLabel.put(block.label(), states[i]);
            if (!block.handler()) {
                dispatcherByLabel.put(block.label(), new LabelNode());
            }
        }
        for (Map.Entry<LabelNode, LabelNode> alias : blockPlan.aliases().entrySet()) {
            LabelNode canonical = alias.getValue();
            stateByLabel.put(alias.getKey(), stateByLabel.get(canonical));
            dispatcherByLabel.put(alias.getKey(), dispatcherByLabel.get(canonical));
        }

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            LabelNode next = i + 1 < blocks.size() && !blocks.get(i + 1).handler()
                ? blocks.get(i + 1).label()
                : null;
            rewriteBlockExit(mn, block, next, keyLocal, stateLocal, methodSalt,
                stateByLabel, dispatcherByLabel, salt);
        }
        insertHandlerBridges(mn, handlerBodies, exceptionLocal, keyLocal, stateLocal,
            methodSalt, stateByLabel, dispatcherByLabel, methodSeed, salt);
        insertBlockDispatchers(mn, blocks, dispatcherByLabel, keyLocal, stateLocal, methodSeed,
            methodSalt, states, exceptionLocal, salt);

        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = Math.max(mn.maxStack + 10, 12);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), "direct-keyed-split-dispatchers-" + dispatcherByLabel.size());
    }

    private boolean isApplicationMethod(PipelineContext pctx, L1Class clazz, L1Method method) {
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return false;
        if (method.isAbstract() || method.isNative()) return false;
        if (TransformGuards.hasStackIntrospection(method)) return false;
        return !TransformGuards.isReflectionShapeSensitive(pctx, clazz);
    }

    private LabelNode protectedStartLabel(L1Class clazz, L1Method method, MethodNode mn, int keyLocal) {
        if (method.isConstructor()) {
            AbstractInsnNode init = constructorInitInsn(clazz, mn);
            AbstractInsnNode next = init == null ? firstReal(mn) : nextReal(init.getNext());
            if (next == null) return null;
            return ensureLabelBefore(mn, next);
        }
        AbstractInsnNode first = firstRealAfterKeyInit(mn, keyLocal);
        return first == null ? null : ensureLabelBefore(mn, first);
    }

    private AbstractInsnNode firstRealAfterKeyInit(MethodNode mn, int keyLocal) {
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof VarInsnNode var
                    && var.getOpcode() == Opcodes.LSTORE
                    && var.var == keyLocal) {
                AbstractInsnNode next = nextReal(insn.getNext());
                return next == null ? firstReal(mn) : next;
            }
        }
        return firstReal(mn);
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

    private BlockPlan buildBlocks(MethodNode mn, LabelNode start, Set<LabelNode> extraLeaders,
            Set<LabelNode> zeroStackLabels, Set<LabelNode> linearLeaders,
            Frame<BasicValue>[] frames, Map<AbstractInsnNode, Integer> instructionIndex) {
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
                AbstractInsnNode next = nextReal(insn.getNext());
                boolean targetZero = zeroStackLabels.contains(jump.label);
                if (jump.getOpcode() == Opcodes.GOTO) {
                    if (targetZero) leaders.add(jump.label);
                } else if (targetZero && isZeroStack(next, frames, instructionIndex)) {
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
                if (isZeroStack(next, frames, instructionIndex)) leaders.add(ensureLabelBefore(mn, next));
            }
        }

        List<LabelNode> ordered = new ArrayList<>();
        for (AbstractInsnNode insn = start; insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label && leaders.contains(label)) {
                ordered.add(label);
            }
        }
        List<Block> blocks = new ArrayList<>();
        Map<LabelNode, LabelNode> aliases = new IdentityHashMap<>();
        Map<Integer, LabelNode> canonicalByIndex = new HashMap<>();
        for (int i = 0; i < ordered.size(); i++) {
            LabelNode label = ordered.get(i);
            AbstractInsnNode endExclusive = i + 1 < ordered.size() ? ordered.get(i + 1) : null;
            if (hasRealInstruction(label, endExclusive)) {
                blocks.add(new Block(label, endExclusive, handlerLabels.contains(label)));
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

    private boolean hasRealInstruction(LabelNode label, AbstractInsnNode endExclusive) {
        for (AbstractInsnNode insn = label; insn != null && insn != endExclusive; insn = insn.getNext()) {
            if (insn.getOpcode() >= 0) return true;
        }
        return false;
    }

    private Set<LabelNode> linearZeroStackLeaders(MethodNode mn, LabelNode start,
            Frame<BasicValue>[] frames, Map<AbstractInsnNode, Integer> instructionIndex) {
        Set<LabelNode> leaders = new HashSet<>();
        AbstractInsnNode[] insns = mn.instructions.toArray();
        boolean active = false;
        for (AbstractInsnNode insn : insns) {
            if (insn == start) active = true;
            if (!active || insn.getOpcode() < 0 || isControlTransfer(insn)) continue;
            AbstractInsnNode next = nextReal(insn.getNext());
            if (isZeroStack(next, frames, instructionIndex)) {
                leaders.add(ensureLabelBefore(mn, next));
            }
        }
        return leaders;
    }

    private Frame<BasicValue>[] analyzeFrames(String owner, MethodNode mn) {
        try {
            Analyzer<BasicValue> analyzer = new Analyzer<>(new BasicInterpreter());
            return analyzer.analyze(owner, mn);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot prove verifier-safe CFF split points for "
                + owner + "." + mn.name + mn.desc, e);
        }
    }

    private Map<AbstractInsnNode, Integer> instructionIndex(MethodNode mn) {
        AbstractInsnNode[] insns = mn.instructions.toArray();
        Map<AbstractInsnNode, Integer> index = new IdentityHashMap<>();
        for (int i = 0; i < insns.length; i++) {
            index.put(insns[i], i);
        }
        return index;
    }

    private Set<LabelNode> zeroStackLabels(MethodNode mn, Frame<BasicValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndex) {
        Set<LabelNode> labels = new HashSet<>();
        for (AbstractInsnNode insn = mn.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LabelNode label && isZeroStack(insn, frames, instructionIndex)) {
                labels.add(label);
            }
        }
        return labels;
    }

    private boolean isZeroStack(AbstractInsnNode insn, Frame<BasicValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndex) {
        if (insn == null) return false;
        Integer index = instructionIndex.get(insn);
        return index != null && frames[index] != null && frames[index].getStackSize() == 0;
    }

    private boolean allSwitchTargetsZero(LabelNode dflt, List<LabelNode> labels, Set<LabelNode> zeroStackLabels) {
        if (!zeroStackLabels.contains(dflt)) return false;
        for (LabelNode label : labels) {
            if (!zeroStackLabels.contains(label)) return false;
        }
        return true;
    }

    private void insertHandlerBridges(MethodNode mn, Map<LabelNode, LabelNode> handlerBodies,
            int exceptionLocal, int keyLocal, int stateLocal, int methodSalt,
            Map<LabelNode, Integer> stateByLabel, Map<LabelNode, LabelNode> dispatcherByLabel,
            long methodSeed, long salt) {
        if (handlerBodies.isEmpty()) return;
        for (Map.Entry<LabelNode, LabelNode> entry : handlerBodies.entrySet()) {
            LabelNode handler = entry.getKey();
            LabelNode body = entry.getValue();
            Integer bodyState = stateByLabel.get(body);
            LabelNode bodyDispatcher = dispatcherByLabel.get(body);
            long edgeSeed = edgeSeed(salt, handler, body, 0x45584348414E444CL);
            InsnList prefix = new InsnList();
            JvmKeyDispatchPass.emitKeyInit(prefix, keyLocal, methodSeed, 0x4346464B65794C31L);
            prefix.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
            prefix.add(transition(requireState(body, bodyState), requireDispatcher(body, bodyDispatcher),
                keyLocal, stateLocal, methodSalt, edgeSeed, true));
            mn.instructions.insert(handler, prefix);

            InsnList reload = new InsnList();
            reload.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
            mn.instructions.insert(body, reload);
        }
    }

    private void insertBlockDispatchers(MethodNode mn, List<Block> blocks,
            Map<LabelNode, LabelNode> dispatcherByLabel, int keyLocal, int stateLocal, long methodSeed,
            int methodSalt, int[] states, int exceptionLocal, long salt) {
        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            InsnList dispatcher = buildSingleBlockDispatcher(block.label(), dispatcherByLabel.get(block.label()),
                keyLocal, stateLocal, methodSalt, states[i], salt);
            if (i == 0) {
                InsnList entry = new InsnList();
                if (exceptionLocal >= 0) {
                    entry.add(new InsnNode(Opcodes.ACONST_NULL));
                    entry.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
                }
                emitStepKey(entry, keyLocal, edgeSeed(salt, block.label(), block.label(), 0x454E5452594B4559L));
                emitStoreEncodedState(entry, stateLocal, keyLocal, states[i], methodSalt);
                entry.add(new JumpInsnNode(Opcodes.GOTO, dispatcherByLabel.get(block.label())));
                entry.add(dispatcher);
                mn.instructions.insertBefore(block.label(), entry);
            } else {
                mn.instructions.insertBefore(block.label(), dispatcher);
            }
        }
    }

    private InsnList buildSingleBlockDispatcher(LabelNode blockLabel, LabelNode dispatcher,
            int keyLocal, int stateLocal, int methodSalt, int state, long salt) {
        InsnList insns = new InsnList();
        LabelNode dflt = new LabelNode();
        LabelNode target = new LabelNode();
        LabelNode fake = new LabelNode();
        int fakeState = fakeState(salt, state);
        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        cases.put(state, target);
        cases.put(fakeState, fake);
        insns.add(dispatcher);
        emitDecodeState(insns, stateLocal, keyLocal, methodSalt);
        insns.add(new LookupSwitchInsnNode(dflt,
            cases.keySet().stream().mapToInt(Integer::intValue).toArray(),
            cases.values().toArray(LabelNode[]::new)));
        insns.add(dflt);
        emitStepKey(insns, keyLocal, edgeSeed(salt, blockLabel, blockLabel, 0x504F49534F4E4B31L));
        insns.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
            "java/lang/IllegalStateException", "<init>", "()V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));
        insns.add(fake);
        emitStepKey(insns, keyLocal, edgeSeed(salt, blockLabel, blockLabel, 0x46414B4545444745L));
        emitStoreEncodedState(insns, stateLocal, keyLocal, state, methodSalt);
        insns.add(new JumpInsnNode(Opcodes.GOTO, dispatcher));
        insns.add(target);
        insns.add(new JumpInsnNode(Opcodes.GOTO, blockLabel));
        return insns;
    }

    private void rewriteBlockExit(MethodNode mn, Block block, LabelNode next,
            int keyLocal, int stateLocal, int methodSalt, Map<LabelNode, Integer> stateByLabel,
            Map<LabelNode, LabelNode> dispatcherByLabel, long salt) {
        AbstractInsnNode last = lastRealBefore(block.endExclusive());
        if (last == null || before(last, block.label())) return;
        int opcode = last.getOpcode();
        if (terminates(opcode)) return;

        if (last instanceof JumpInsnNode jump) {
            if (opcode == Opcodes.GOTO) {
                Integer targetState = stateByLabel.get(jump.label);
                LabelNode targetDispatcher = dispatcherByLabel.get(jump.label);
                mn.instructions.insertBefore(last, transition(requireState(jump.label, targetState),
                    requireDispatcher(jump.label, targetDispatcher), keyLocal, stateLocal, methodSalt,
                    edgeSeed(salt, block.label(), jump.label, opcode), true));
                mn.instructions.remove(last);
                return;
            }
            LabelNode taken = new LabelNode();
            Integer targetState = stateByLabel.get(jump.label);
            Integer fallthroughState = next == null ? null : stateByLabel.get(next);
            LabelNode targetDispatcher = dispatcherByLabel.get(jump.label);
            LabelNode fallthroughDispatcher = next == null ? null : dispatcherByLabel.get(next);
            if (next == null) {
                throw new IllegalStateException("CFF conditional block has no verifier-safe fallthrough target");
            }
            JumpInsnNode replacement = new JumpInsnNode(opcode, taken);
            mn.instructions.insertBefore(last, replacement);
            mn.instructions.insertBefore(last, transition(requireState(next, fallthroughState),
                requireDispatcher(next, fallthroughDispatcher), keyLocal, stateLocal, methodSalt,
                edgeSeed(salt, block.label(), next, opcode ^ 0x46534C53), true));
            mn.instructions.insertBefore(last, taken);
            mn.instructions.insertBefore(last, transition(requireState(jump.label, targetState),
                requireDispatcher(jump.label, targetDispatcher), keyLocal, stateLocal, methodSalt,
                edgeSeed(salt, block.label(), jump.label, opcode ^ 0x54525545), true));
            mn.instructions.remove(last);
            return;
        }
        if (last instanceof LookupSwitchInsnNode ls) {
            rewriteLookupSwitch(mn, ls, keyLocal, stateLocal, methodSalt, stateByLabel,
                dispatcherByLabel, block.label(), salt);
            return;
        }
        if (last instanceof TableSwitchInsnNode ts) {
            rewriteTableSwitch(mn, ts, keyLocal, stateLocal, methodSalt, stateByLabel,
                dispatcherByLabel, block.label(), salt);
            return;
        }
        if (next != null) {
            Integer nextState = stateByLabel.get(next);
            LabelNode nextDispatcher = dispatcherByLabel.get(next);
            mn.instructions.insert(last, transition(requireState(next, nextState),
                requireDispatcher(next, nextDispatcher), keyLocal, stateLocal, methodSalt,
                edgeSeed(salt, block.label(), next, 0x46414C4C), containsCall(block)));
        }
    }

    private void rewriteLookupSwitch(MethodNode mn, LookupSwitchInsnNode ls,
            int keyLocal, int stateLocal, int methodSalt, Map<LabelNode, Integer> stateByLabel,
            Map<LabelNode, LabelNode> dispatcherByLabel, LabelNode source, long salt) {
        LabelNode defaultSet = new LabelNode();
        List<LabelNode> setLabels = new ArrayList<>();
        for (int i = 0; i < ls.labels.size(); i++) setLabels.add(new LabelNode());
        List<LabelNode> originalTargets = new ArrayList<>(ls.labels);
        LabelNode originalDefault = ls.dflt;
        ls.labels.clear();
        ls.labels.addAll(setLabels);
        ls.dflt = defaultSet;
        InsnList tail = new InsnList();
        tail.add(defaultSet);
        tail.add(transition(requireState(originalDefault, stateByLabel.get(originalDefault)),
            requireDispatcher(originalDefault, dispatcherByLabel.get(originalDefault)),
            keyLocal, stateLocal, methodSalt, edgeSeed(salt, source, originalDefault, 0x53574446), true));
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(transition(requireState(originalTarget, stateByLabel.get(originalTarget)),
                requireDispatcher(originalTarget, dispatcherByLabel.get(originalTarget)),
                keyLocal, stateLocal, methodSalt, edgeSeed(salt, source, originalTarget,
                    ls.keys.get(i) ^ 0x53574C53), true));
        }
        mn.instructions.insert(ls, tail);
    }

    private void rewriteTableSwitch(MethodNode mn, TableSwitchInsnNode ts,
            int keyLocal, int stateLocal, int methodSalt, Map<LabelNode, Integer> stateByLabel,
            Map<LabelNode, LabelNode> dispatcherByLabel, LabelNode source, long salt) {
        LabelNode defaultSet = new LabelNode();
        List<LabelNode> setLabels = new ArrayList<>();
        for (int i = 0; i < ts.labels.size(); i++) setLabels.add(new LabelNode());
        List<LabelNode> originalTargets = new ArrayList<>(ts.labels);
        LabelNode originalDefault = ts.dflt;
        ts.labels.clear();
        ts.labels.addAll(setLabels);
        ts.dflt = defaultSet;
        InsnList tail = new InsnList();
        tail.add(defaultSet);
        tail.add(transition(requireState(originalDefault, stateByLabel.get(originalDefault)),
            requireDispatcher(originalDefault, dispatcherByLabel.get(originalDefault)),
            keyLocal, stateLocal, methodSalt, edgeSeed(salt, source, originalDefault, 0x54534446), true));
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(transition(requireState(originalTarget, stateByLabel.get(originalTarget)),
                requireDispatcher(originalTarget, dispatcherByLabel.get(originalTarget)),
                keyLocal, stateLocal, methodSalt, edgeSeed(salt, source, originalTarget,
                    (ts.min + i) ^ 0x54534C53), true));
        }
        mn.instructions.insert(ts, tail);
    }

    private int requireState(LabelNode target, Integer state) {
        if (state == null) {
            throw new IllegalStateException("CFF target has no state: " + target.getLabel());
        }
        return state;
    }

    private LabelNode requireDispatcher(LabelNode target, LabelNode dispatcher) {
        if (dispatcher == null) {
            throw new IllegalStateException("CFF target has no dispatcher: " + target.getLabel());
        }
        return dispatcher;
    }

    private InsnList transition(int state, LabelNode dispatcher, int keyLocal, int stateLocal,
            int methodSalt, long edgeSeed, boolean updateKey) {
        InsnList insns = new InsnList();
        if (updateKey) {
            emitStepKey(insns, keyLocal, edgeSeed);
        }
        emitStoreEncodedState(insns, stateLocal, keyLocal, state, methodSalt);
        insns.add(new JumpInsnNode(Opcodes.GOTO, dispatcher));
        return insns;
    }

    private void emitStepKey(InsnList insns, int keyLocal, long seed) {
        switch ((int) (seed >>> 61) & 3) {
            case 0 -> emitStepXorAddFold(insns, keyLocal, seed);
            case 1 -> emitStepAddRotateFold(insns, keyLocal, seed);
            case 2 -> emitStepSelfRotateAdd(insns, keyLocal, seed);
            default -> emitStepXorRotateAdd(insns, keyLocal, seed);
        }
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitStepXorAddFold(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, CFF_KEY_STEP);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 27);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushInt(insns, rotate(seed, 17));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Long", "rotateLeft", "(JI)J", false));
    }

    private void emitStepAddRotateFold(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed ^ 0xD1B54A32D192ED03L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 33);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushInt(insns, rotate(seed, 23));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Long", "rotateRight", "(JI)J", false));
    }

    private void emitStepSelfRotateAdd(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, rotate(seed, 11));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Long", "rotateLeft", "(JI)J", false));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, seed + 0xA0761D6478BD642FL);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 29);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
    }

    private void emitStepXorRotateAdd(InsnList insns, int keyLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, rotate(seed, 7));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Long", "rotateRight", "(JI)J", false));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, CFF_KEY_STEP ^ seed);
        insns.add(new InsnNode(Opcodes.LADD));
    }

    private void emitStoreEncodedState(InsnList insns, int stateLocal, int keyLocal, int state, int salt) {
        JvmPassBytecode.pushInt(insns, state);
        emitFoldKey(insns, keyLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, salt);
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, salt & 15);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Integer", "rotateLeft", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stateLocal));
    }

    private void emitDecodeState(InsnList insns, int stateLocal, int keyLocal, int salt) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        JvmPassBytecode.pushInt(insns, salt & 15);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "java/lang/Integer", "rotateRight", "(II)I", false));
        JvmPassBytecode.pushInt(insns, salt);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitFoldKey(insns, keyLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitFoldKey(InsnList insns, int keyLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 17);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 9);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
    }

    private boolean containsCall(Block block) {
        for (AbstractInsnNode insn = block.label(); insn != null && insn != block.endExclusive(); insn = insn.getNext()) {
            if (insn instanceof MethodInsnNode || insn instanceof InvokeDynamicInsnNode) return true;
        }
        return false;
    }

    private int rotate(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 62);
    }

    private long edgeSeed(long salt, LabelNode from, LabelNode to, long discriminator) {
        long seed = JvmPassBytecode.mix(salt ^ discriminator, System.identityHashCode(from));
        seed = JvmPassBytecode.mix(seed, System.identityHashCode(to));
        return seed == 0L ? discriminator ^ 0x5DEECE66DL : seed;
    }

    private int fakeState(long salt, int state) {
        int fake = (int) JvmPassBytecode.mix(salt ^ 0x46414B4543415345L, state);
        return fake == state ? fake ^ 0x13579BDF : fake;
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
    private record BlockPlan(List<Block> blocks, Map<LabelNode, LabelNode> aliases) {}
}
