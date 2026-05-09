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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.Collections;

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

        int pcLocal = mn.maxLocals;
        int guardLocal = pcLocal + 1;
        int domainLocal = pcLocal + 2;
        int exceptionLocal = handlerBodies.isEmpty() ? -1 : pcLocal + 3;
        mn.maxLocals = pcLocal + 3 + (handlerBodies.isEmpty() ? 0 : 1);

        long salt = JvmPassBytecode.mix(pctx.masterSeed(), methodKey.hashCode());
        int[] states = uniqueStates((int) (salt >>> 32), blocks.size());
        Map<LabelNode, Integer> stateByLabel = new IdentityHashMap<>();
        for (int i = 0; i < blocks.size(); i++) {
            stateByLabel.put(blocks.get(i).label(), states[i]);
        }
        // Dispatcher hubs are grouped by verifier frame shape. A transition may
        // jump to a hub only when every block behind that hub has compatible
        // locals and an empty stack.
        DispatchPlan dispatchPlan = buildDispatchPlan(blocks, frames, instructionIndex, salt);
        for (Map.Entry<LabelNode, LabelNode> alias : blockPlan.aliases().entrySet()) {
            LabelNode canonical = alias.getValue();
            stateByLabel.put(alias.getKey(), stateByLabel.get(canonical));
            dispatchPlan.targets().put(alias.getKey(), dispatchPlan.targets().get(canonical));
        }

        for (int i = 0; i < blocks.size(); i++) {
            Block block = blocks.get(i);
            if (block.handler()) continue;
            LabelNode next = i + 1 < blocks.size() && !blocks.get(i + 1).handler()
                ? blocks.get(i + 1).label()
                : null;
            rewriteBlockExit(mn, block, next, guardLocal, pcLocal, domainLocal,
                keyLocal, stateByLabel, dispatchPlan.targets(), salt);
        }
        insertHandlerBridges(mn, handlerBodies, exceptionLocal, keyLocal, guardLocal, pcLocal, domainLocal,
            stateByLabel, dispatchPlan.targets(), methodSeed, salt);
        insertIslandDispatchers(mn, blocks, keyLocal, guardLocal, pcLocal, domainLocal,
            stateByLabel, dispatchPlan, exceptionLocal, salt);

        mn.localVariables = null;
        mn.visibleLocalVariableAnnotations = null;
        mn.invisibleLocalVariableAnnotations = null;
        mn.maxStack = Math.max(mn.maxStack + 10, 12);
        clazz.markDirty();
        pctx.invalidate(method);
        JvmObfuscationCoverage.get(ctx).full(id(), clazz.name(), method.name(),
            method.descriptor(), "direct-keyed-island-dispatchers-" + dispatchPlan.groups().size());
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
            int exceptionLocal, int keyLocal, int guardLocal, int pcLocal, int domainLocal,
            Map<LabelNode, Integer> stateByLabel, Map<LabelNode, DispatchTarget> dispatchByLabel,
            long methodSeed, long salt) {
        if (handlerBodies.isEmpty()) return;
        for (Map.Entry<LabelNode, LabelNode> entry : handlerBodies.entrySet()) {
            LabelNode handler = entry.getKey();
            LabelNode body = entry.getValue();
            Integer bodyState = stateByLabel.get(body);
            DispatchTarget bodyTarget = dispatchByLabel.get(body);
            long edgeSeed = edgeSeed(salt, handler, body, 0x45584348414E444CL);
            InsnList prefix = new InsnList();
            JvmKeyDispatchPass.emitKeyInit(prefix, keyLocal, methodSeed, 0x4346464B65794C31L);
            emitInitGuard(prefix, guardLocal, keyLocal, methodSeed ^ salt);
            prefix.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
            prefix.add(transition(requireState(body, bodyState), requireTarget(body, bodyTarget),
                keyLocal, guardLocal, pcLocal, domainLocal, edgeSeed, true, EdgeRole.HANDLER));
            mn.instructions.insert(handler, prefix);

            InsnList reload = new InsnList();
            reload.add(new VarInsnNode(Opcodes.ALOAD, exceptionLocal));
            mn.instructions.insert(body, reload);
        }
    }

    private void insertIslandDispatchers(MethodNode mn, List<Block> blocks,
            int keyLocal, int guardLocal, int pcLocal, int domainLocal,
            Map<LabelNode, Integer> stateByLabel, DispatchPlan dispatchPlan, int exceptionLocal, long salt) {
        for (IslandGroup group : dispatchPlan.groups()) {
            Block entryBlock = group.blocks().get(0);
            InsnList insns = new InsnList();
            LabelNode poison = new LabelNode();

            if (entryBlock == firstNonHandler(blocks)) {
                if (exceptionLocal >= 0) {
                    insns.add(new InsnNode(Opcodes.ACONST_NULL));
                    insns.add(new VarInsnNode(Opcodes.ASTORE, exceptionLocal));
                }
                DispatchTarget entryTarget = requireTarget(entryBlock.label(), dispatchPlan.targets().get(entryBlock.label()));
                emitInitGuard(insns, guardLocal, keyLocal, group.salt());
                emitStorePc(insns, pcLocal, guardLocal,
                    requireState(entryBlock.label(), stateByLabel.get(entryBlock.label())), group.salt());
                emitStoreDomain(insns, domainLocal, entryTarget.island(), group.salt());
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }

            for (int alias = 0; alias < group.aliasHubs().length; alias++) {
                insns.add(aliasHub(group, alias));
            }
            insns.add(group.hub());
            emitDomainDispatch(insns, domainLocal, group, poison);
            for (int island = 0; island < group.islandLabels().length; island++) {
                insns.add(buildIslandDispatcher(group, stateByLabel, keyLocal, guardLocal, pcLocal,
                    domainLocal, poison, island, salt));
            }
            insns.add(poison);
            long poisonSeed = edgeSeed(salt, entryBlock.label(), entryBlock.label(), 0x504F49534F4E4B31L);
            emitStepGuard(insns, guardLocal, poisonSeed);
            emitStepKey(insns, keyLocal, guardLocal, poisonSeed);
            insns.add(new org.objectweb.asm.tree.TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL,
                "java/lang/IllegalStateException", "<init>", "()V", false));
            insns.add(new InsnNode(Opcodes.ATHROW));
            mn.instructions.insertBefore(entryBlock.label(), insns);
        }
    }

    private void rewriteBlockExit(MethodNode mn, Block block, LabelNode next,
            int guardLocal, int pcLocal, int domainLocal,
            int keyLocal, Map<LabelNode, Integer> stateByLabel,
            Map<LabelNode, DispatchTarget> dispatchByLabel, long salt) {
        AbstractInsnNode last = lastRealBefore(block.endExclusive());
        if (last == null || before(last, block.label())) return;
        int opcode = last.getOpcode();
        if (terminates(opcode)) return;

        if (last instanceof JumpInsnNode jump) {
            if (opcode == Opcodes.GOTO) {
                Integer targetState = stateByLabel.get(jump.label);
                DispatchTarget target = dispatchByLabel.get(jump.label);
                mn.instructions.insertBefore(last, transition(requireState(jump.label, targetState),
                    requireTarget(jump.label, target), keyLocal, guardLocal, pcLocal, domainLocal,
                    edgeSeed(salt, block.label(), jump.label, opcode),
                    true, EdgeRole.GOTO));
                mn.instructions.remove(last);
                return;
            }
            Integer targetState = stateByLabel.get(jump.label);
            Integer fallthroughState = next == null ? null : stateByLabel.get(next);
            DispatchTarget target = dispatchByLabel.get(jump.label);
            DispatchTarget fallthrough = next == null ? null : dispatchByLabel.get(next);
            if (next == null) {
                throw new IllegalStateException("CFF conditional block has no verifier-safe fallthrough target");
            }
            long trueSeed = edgeSeed(salt, block.label(), jump.label, opcode ^ 0x54525545);
            long falseSeed = edgeSeed(salt, block.label(), next, opcode ^ 0x46534C53);
            if ((trueSeed & 1L) == 0L) {
                LabelNode taken = new LabelNode();
                mn.instructions.insertBefore(last, new JumpInsnNode(opcode, taken));
                mn.instructions.insertBefore(last, transition(requireState(next, fallthroughState),
                    requireTarget(next, fallthrough), keyLocal, guardLocal, pcLocal, domainLocal,
                    falseSeed, true, EdgeRole.CONDITIONAL_FALSE));
                mn.instructions.insertBefore(last, taken);
                mn.instructions.insertBefore(last, transition(requireState(jump.label, targetState),
                    requireTarget(jump.label, target), keyLocal, guardLocal, pcLocal, domainLocal,
                    trueSeed, true, EdgeRole.CONDITIONAL_TRUE));
            } else {
                LabelNode fallthroughLabel = new LabelNode();
                mn.instructions.insertBefore(last, new JumpInsnNode(invertJumpOpcode(opcode), fallthroughLabel));
                mn.instructions.insertBefore(last, transition(requireState(jump.label, targetState),
                    requireTarget(jump.label, target), keyLocal, guardLocal, pcLocal, domainLocal,
                    trueSeed, true, EdgeRole.CONDITIONAL_TRUE));
                mn.instructions.insertBefore(last, fallthroughLabel);
                mn.instructions.insertBefore(last, transition(requireState(next, fallthroughState),
                    requireTarget(next, fallthrough), keyLocal, guardLocal, pcLocal, domainLocal,
                    falseSeed, true, EdgeRole.CONDITIONAL_FALSE));
            }
            mn.instructions.remove(last);
            return;
        }
        if (last instanceof LookupSwitchInsnNode ls) {
            rewriteLookupSwitch(mn, ls, keyLocal, guardLocal, pcLocal, domainLocal,
                stateByLabel, dispatchByLabel, block.label(), salt);
            return;
        }
        if (last instanceof TableSwitchInsnNode ts) {
            rewriteTableSwitch(mn, ts, keyLocal, guardLocal, pcLocal, domainLocal,
                stateByLabel, dispatchByLabel, block.label(), salt);
            return;
        }
        if (next != null) {
            Integer nextState = stateByLabel.get(next);
            DispatchTarget nextTarget = dispatchByLabel.get(next);
            mn.instructions.insert(last, transition(requireState(next, nextState),
                requireTarget(next, nextTarget), keyLocal, guardLocal, pcLocal, domainLocal,
                edgeSeed(salt, block.label(), next, 0x46414C4C),
                true, EdgeRole.FALLTHROUGH));
        }
    }

    private void rewriteLookupSwitch(MethodNode mn, LookupSwitchInsnNode ls,
            int keyLocal, int guardLocal, int pcLocal, int domainLocal,
            Map<LabelNode, Integer> stateByLabel, Map<LabelNode, DispatchTarget> dispatchByLabel,
            LabelNode source, long salt) {
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
            requireTarget(originalDefault, dispatchByLabel.get(originalDefault)),
            keyLocal, guardLocal, pcLocal, domainLocal,
            edgeSeed(salt, source, originalDefault, 0x53574446), true, EdgeRole.SWITCH_DEFAULT));
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(transition(requireState(originalTarget, stateByLabel.get(originalTarget)),
                requireTarget(originalTarget, dispatchByLabel.get(originalTarget)),
                keyLocal, guardLocal, pcLocal, domainLocal, edgeSeed(salt, source, originalTarget,
                    ls.keys.get(i) ^ 0x53574C53), true, EdgeRole.SWITCH_CASE));
        }
        mn.instructions.insert(ls, tail);
    }

    private void rewriteTableSwitch(MethodNode mn, TableSwitchInsnNode ts,
            int keyLocal, int guardLocal, int pcLocal, int domainLocal,
            Map<LabelNode, Integer> stateByLabel, Map<LabelNode, DispatchTarget> dispatchByLabel,
            LabelNode source, long salt) {
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
            requireTarget(originalDefault, dispatchByLabel.get(originalDefault)),
            keyLocal, guardLocal, pcLocal, domainLocal,
            edgeSeed(salt, source, originalDefault, 0x54534446), true, EdgeRole.SWITCH_DEFAULT));
        for (int i = 0; i < setLabels.size(); i++) {
            LabelNode originalTarget = originalTargets.get(i);
            tail.add(setLabels.get(i));
            tail.add(transition(requireState(originalTarget, stateByLabel.get(originalTarget)),
                requireTarget(originalTarget, dispatchByLabel.get(originalTarget)),
                keyLocal, guardLocal, pcLocal, domainLocal, edgeSeed(salt, source, originalTarget,
                    (ts.min + i) ^ 0x54534C53), true, EdgeRole.SWITCH_CASE));
        }
        mn.instructions.insert(ts, tail);
    }

    private int requireState(LabelNode target, Integer state) {
        if (state == null) {
            throw new IllegalStateException("CFF target has no state: " + target.getLabel());
        }
        return state;
    }

    private DispatchTarget requireTarget(LabelNode label, DispatchTarget target) {
        if (target == null) {
            throw new IllegalStateException("CFF target has no dispatch target: " + label.getLabel());
        }
        return target;
    }

    private InsnList transition(int state, DispatchTarget target,
            int keyLocal, int guardLocal, int pcLocal, int domainLocal, long edgeSeed,
            boolean updateGuard, EdgeRole role) {
        InsnList insns = new InsnList();
        if (updateGuard) {
            emitStepGuard(insns, guardLocal, edgeSeed);
            emitStepKey(insns, keyLocal, guardLocal, edgeSeed);
        }
        switch (chooseEdgeKind(edgeSeed, role, target)) {
            case DIRECT_ISLAND -> {
                emitStorePc(insns, pcLocal, guardLocal, state, edgeSeed);
                insns.add(new JumpInsnNode(Opcodes.GOTO, target.islandLabels()[target.island()]));
            }
            case ALIAS_HUB -> {
                emitStorePc(insns, pcLocal, guardLocal, state, edgeSeed);
                emitStoreDomain(insns, domainLocal, target.island(), edgeSeed);
                insns.add(new JumpInsnNode(Opcodes.GOTO, selectAliasHub(target, edgeSeed)));
            }
            case HUB -> {
                emitStorePc(insns, pcLocal, guardLocal, state, edgeSeed);
                emitStoreDomain(insns, domainLocal, target.island(), edgeSeed);
                insns.add(new JumpInsnNode(Opcodes.GOTO, target.hub()));
            }
        }
        return insns;
    }

    private EdgeKind chooseEdgeKind(long seed, EdgeRole role, DispatchTarget target) {
        if (role == EdgeRole.HANDLER) {
            return hasAliasHub(target) && ((seed >>> 9) & 1L) == 0L ? EdgeKind.ALIAS_HUB : EdgeKind.HUB;
        }
        int choice = (int) ((seed >>> 56) & 7L);
        return switch (choice) {
            case 0, 1, 5 -> EdgeKind.DIRECT_ISLAND;
            case 2, 7 -> hasAliasHub(target) ? EdgeKind.ALIAS_HUB : EdgeKind.HUB;
            default -> EdgeKind.HUB;
        };
    }

    private boolean hasAliasHub(DispatchTarget target) {
        return target.aliasHubs().length > 0;
    }

    private LabelNode selectAliasHub(DispatchTarget target, long seed) {
        LabelNode[] aliases = target.aliasHubs();
        if (aliases.length == 0) return target.hub();
        return aliases[(int) Long.remainderUnsigned(seed, aliases.length)];
    }

    private InsnList buildIslandDispatcher(IslandGroup group, Map<LabelNode, Integer> stateByLabel,
            int keyLocal, int guardLocal, int pcLocal, int domainLocal, LabelNode poison, int island, long salt) {
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
                int state = requireState(block.label(), stateByLabel.get(block.label()));
                cases.put(state, stub);
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
            int fakeState = fakeState(salt, firstState ^ island ^ (fakeIndex * 0x45D9F3B));
            while (cases.containsKey(fakeState)) {
                fakeState = fakeState(salt ^ 0x9E3779B97F4A7C15L, fakeState + fakeIndex + 1);
            }
            cases.put(fakeState, fake);
        }
        insns.add(group.islandLabels()[island]);
        emitSelector(insns, pcLocal, guardLocal, group.salt() ^ island);
        emitStateDispatch(insns, cases, poison, group.salt() ^ island);
        for (Map.Entry<LabelNode, LabelNode> stub : stubs.entrySet()) {
            insns.add(stub.getKey());
            insns.add(new JumpInsnNode(Opcodes.GOTO, stub.getValue()));
        }
        for (int fakeIndex = 0; fakeIndex < fakes.size(); fakeIndex++) {
            insns.add(fakes.get(fakeIndex));
            long fakeSeed = edgeSeed(salt, group.hub(), group.islandLabels()[island],
                0x46414B4549534C45L ^ island ^ fakeIndex);
            emitStepGuard(insns, guardLocal, fakeSeed);
            emitStepKey(insns, keyLocal, guardLocal, fakeSeed);
            emitFakeCaseBounce(insns, group, keyLocal, guardLocal, pcLocal, domainLocal, firstState, island, fakeSeed);
        }
        return insns;
    }

    private void emitFakeCaseBounce(InsnList insns, IslandGroup group, int keyLocal, int guardLocal,
            int pcLocal, int domainLocal, int state, int island, long seed) {
        LabelNode hop = new LabelNode();
        LabelNode pass = new LabelNode();
        switch ((int) ((seed >>> 37) & 3L)) {
            case 0 -> {
                emitStorePc(insns, pcLocal, guardLocal, state, seed);
                emitStoreDomain(insns, domainLocal, island, seed);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 1 -> {
                emitStorePc(insns, pcLocal, guardLocal, state, seed);
                insns.add(new JumpInsnNode(Opcodes.GOTO, hop));
                insns.add(hop);
                emitStoreDomain(insns, domainLocal, island, seed);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 2 -> {
                emitStoreDomain(insns, domainLocal, island, seed);
                emitStorePc(insns, pcLocal, guardLocal, state, seed);
                JvmPassBytecode.pushInt(insns, (int) seed);
                JvmPassBytecode.pushInt(insns, (int) seed);
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            default -> {
                emitStorePc(insns, pcLocal, guardLocal, state, seed);
                emitStoreDomain(insns, domainLocal, island, seed);
                JvmPassBytecode.pushInt(insns, (int) seed);
                insns.add(new InsnNode(Opcodes.DUP));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
        }
    }

    private InsnList aliasHub(IslandGroup group, int alias) {
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
            case 2 -> {
                LabelNode pass = new LabelNode();
                insns.add(new InsnNode(Opcodes.ICONST_0));
                insns.add(new JumpInsnNode(Opcodes.IFEQ, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 4 -> {
                LabelNode fail = new LabelNode();
                JvmPassBytecode.pushInt(insns, (int) seed);
                insns.add(new InsnNode(Opcodes.DUP));
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, fail));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(fail);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            case 5 -> {
                LabelNode hopA = new LabelNode();
                LabelNode hopB = new LabelNode();
                insns.add(new JumpInsnNode(Opcodes.GOTO, hopA));
                insns.add(hopB);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(hopA);
                insns.add(new JumpInsnNode(Opcodes.GOTO, hopB));
            }
            case 6 -> {
                LabelNode pass = new LabelNode();
                JvmPassBytecode.pushInt(insns, (int) (seed >>> 32));
                JvmPassBytecode.pushInt(insns, (int) (seed >>> 32) ^ 1);
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
            default -> {
                LabelNode pass = new LabelNode();
                JvmPassBytecode.pushInt(insns, (int) seed);
                JvmPassBytecode.pushInt(insns, (int) seed);
                insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, pass));
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
                insns.add(pass);
                insns.add(new JumpInsnNode(Opcodes.GOTO, group.hub()));
            }
        }
        return insns;
    }

    private void emitDomainDispatch(InsnList insns, int domainLocal, IslandGroup group, LabelNode poison) {
        LabelNode[] islandLabels = group.islandLabels();
        if (((group.salt() >>> 5) & 1L) == 0L && islandLabels.length > 1) {
            emitDomainIfChain(insns, domainLocal, islandLabels, poison, group.salt());
            return;
        }
        TreeMap<Integer, LabelNode> cases = new TreeMap<>();
        for (int i = 0; i < islandLabels.length; i++) {
            cases.put(i, islandLabels[i]);
        }
        insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
        if (((group.salt() >>> 9) & 1L) == 0L) {
            insns.add(new org.objectweb.asm.tree.TableSwitchInsnNode(0, islandLabels.length - 1,
                poison, islandLabels));
        } else {
            insns.add(new LookupSwitchInsnNode(poison,
                cases.keySet().stream().mapToInt(Integer::intValue).toArray(),
                cases.values().toArray(LabelNode[]::new)));
        }
    }

    private void emitDomainIfChain(InsnList insns, int domainLocal, LabelNode[] islandLabels, LabelNode poison, long seed) {
        boolean reverse = ((seed >>> 11) & 1L) != 0L;
        for (int n = 0; n < islandLabels.length; n++) {
            int i = reverse ? islandLabels.length - 1 - n : n;
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ILOAD, domainLocal));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
            insns.add(new JumpInsnNode(Opcodes.GOTO, islandLabels[i]));
            insns.add(next);
        }
        insns.add(new JumpInsnNode(Opcodes.GOTO, poison));
    }

    private void emitStateDispatch(InsnList insns, TreeMap<Integer, LabelNode> cases, LabelNode poison, long seed) {
        if (cases.size() <= 3 && ((seed >>> 13) & 1L) == 0L) {
            emitStateIfChain(insns, cases, poison, seed);
            return;
        }
        insns.add(new LookupSwitchInsnNode(poison,
            cases.keySet().stream().mapToInt(Integer::intValue).toArray(),
            cases.values().toArray(LabelNode[]::new)));
    }

    private void emitStateIfChain(InsnList insns, TreeMap<Integer, LabelNode> cases, LabelNode poison, long seed) {
        List<Map.Entry<Integer, LabelNode>> entries = new ArrayList<>(cases.entrySet());
        if (((seed >>> 17) & 1L) != 0L) {
            Collections.reverse(entries);
        }
        for (Map.Entry<Integer, LabelNode> entry : entries) {
            LabelNode next = new LabelNode();
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, entry.getKey());
            insns.add(new JumpInsnNode(Opcodes.IF_ICMPNE, next));
            insns.add(new InsnNode(Opcodes.POP));
            insns.add(new JumpInsnNode(Opcodes.GOTO, entry.getValue()));
            insns.add(next);
        }
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(new JumpInsnNode(Opcodes.GOTO, poison));
    }

    private void emitSelector(InsnList insns, int pcLocal, int guardLocal, long seed) {
        // Hot selector decode stays a single xor; operand order varies per island.
        if (((seed >>> 19) & 1L) == 0L) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        } else {
            insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        }
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitInitGuard(InsnList insns, int guardLocal, int keyLocal, long seed) {
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

    private void emitInitGuardSeededXor(InsnList insns, int keyLocal, long seed) {
        emitInitGuardHighLow(insns, keyLocal);
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        foldTopInt16(insns);
    }

    private void emitInitGuardSeededAdd(InsnList insns, int keyLocal, long seed) {
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

    private void emitStorePc(InsnList insns, int pcLocal, int guardLocal, int state, long seed) {
        switch ((int) ((seed >>> 47) & 3L)) {
            case 0 -> {
                JvmPassBytecode.pushInt(insns, state);
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            case 1 -> {
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                JvmPassBytecode.pushInt(insns, state);
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            case 2 -> {
                int mask = (int) seed;
                JvmPassBytecode.pushInt(insns, state ^ mask);
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
                JvmPassBytecode.pushInt(insns, mask);
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            default -> {
                int delta = (int) (seed >>> 32);
                JvmPassBytecode.pushInt(insns, state + delta);
                JvmPassBytecode.pushInt(insns, delta);
                insns.add(new InsnNode(Opcodes.ISUB));
                insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
                insns.add(new InsnNode(Opcodes.IXOR));
            }
        }
        insns.add(new VarInsnNode(Opcodes.ISTORE, pcLocal));
    }

    private void emitStoreDomain(InsnList insns, int domainLocal, int island, long seed) {
        switch ((int) ((seed >>> 43) & 3L)) {
            case 0 -> JvmPassBytecode.pushInt(insns, island);
            case 1 -> {
                int mask = (int) seed;
                JvmPassBytecode.pushInt(insns, island ^ mask);
                JvmPassBytecode.pushInt(insns, mask);
                insns.add(new InsnNode(Opcodes.IXOR));
            }
            case 2 -> {
                int delta = (int) (seed >>> 32);
                JvmPassBytecode.pushInt(insns, island + delta);
                JvmPassBytecode.pushInt(insns, delta);
                insns.add(new InsnNode(Opcodes.ISUB));
            }
            default -> {
                JvmPassBytecode.pushInt(insns, island);
                insns.add(new InsnNode(Opcodes.DUP));
                insns.add(new InsnNode(Opcodes.IXOR));
                JvmPassBytecode.pushInt(insns, island);
                insns.add(new InsnNode(Opcodes.IADD));
            }
        }
        insns.add(new VarInsnNode(Opcodes.ISTORE, domainLocal));
    }

    private void emitStepGuard(InsnList insns, int guardLocal, long seed) {
        // Per-edge polymorphism is kept in the guard update, not in selector decode.
        switch ((int) (seed >>> 61) & 3) {
            case 0 -> emitStepGuardXor(insns, guardLocal, seed);
            case 1 -> emitStepGuardAddFold(insns, guardLocal, seed);
            case 2 -> emitStepGuardXorFoldAdd(insns, guardLocal, seed);
            default -> emitStepGuardAddShiftXor(insns, guardLocal, seed);
        }
        insns.add(new VarInsnNode(Opcodes.ISTORE, guardLocal));
    }

    private void emitStepKey(InsnList insns, int keyLocal, int guardLocal, long seed) {
        // Feed the evolved CFF guard back into the long method key. Keyed
        // callsites load this local, so child methods inherit the current path.
        switch ((int) ((seed >>> 57) & 3L)) {
            case 0 -> emitStepKeyShiftHighLow(insns, keyLocal, guardLocal, seed);
            case 1 -> emitStepKeyLowHigh(insns, keyLocal, guardLocal, seed);
            case 2 -> emitStepKeyAddGuard(insns, keyLocal, guardLocal, seed);
            default -> emitStepKeySelfFold(insns, keyLocal, guardLocal, seed);
        }
    }

    private void emitStepKeyShiftHighLow(InsnList insns, int keyLocal, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitStepKeyLowHigh(InsnList insns, int keyLocal, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, seed ^ 0xD1B54A32D192ED03L);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitStepKeyAddGuard(InsnList insns, int keyLocal, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        JvmPassBytecode.pushLong(insns, seed);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LADD));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitStepKeySelfFold(InsnList insns, int keyLocal, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, keyLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, seed + 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, keyLocal));
    }

    private void emitStepGuardXor(InsnList insns, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStepGuardAddFold(InsnList insns, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        JvmPassBytecode.pushInt(insns, (int) (seed >>> 32));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 11));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStepGuardXorFoldAdd(InsnList insns, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        JvmPassBytecode.pushInt(insns, (int) seed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (int) (seed ^ 0x9E3779B9));
        insns.add(new InsnNode(Opcodes.IADD));
    }

    private void emitStepGuardAddShiftXor(InsnList insns, int guardLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        JvmPassBytecode.pushInt(insns, (int) (seed + 0x7F4A7C15));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 23));
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private DispatchPlan buildDispatchPlan(List<Block> blocks, Frame<BasicValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndex, long salt) {
        // Split dispatchers must not merge blocks that require different local
        // initialization states. This preserves verifier compatibility without
        // falling back to unflattened bytecode.
        Map<String, List<Block>> byFrame = new LinkedHashMap<>();
        for (Block block : blocks) {
            if (block.handler()) continue;
            String signature = frameSignature(block.label(), frames, instructionIndex);
            byFrame.computeIfAbsent(signature, ignored -> new ArrayList<>()).add(block);
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
            LabelNode[] aliasHubs = new LabelNode[aliasHubCount(groupBlocks.size())];
            for (int i = 0; i < aliasHubs.length; i++) {
                aliasHubs[i] = new LabelNode();
            }
            Map<LabelNode, Integer> islands = new IdentityHashMap<>();
            for (int i = 0; i < groupBlocks.size(); i++) {
                Block block = groupBlocks.get(i);
                int island = islandFor(i, groupBlocks.size(), islandCount);
                islands.put(block.label(), island);
                targets.put(block.label(), new DispatchTarget(hub, islandLabels, aliasHubs, island));
            }
            long groupSalt = JvmPassBytecode.mix(salt ^ entry.getKey().hashCode(), groupIndex++ ^ groupBlocks.size());
            groups.add(new IslandGroup(hub, islandLabels, aliasHubs, groupBlocks, islands, groupSalt));
        }
        return new DispatchPlan(groups, targets);
    }

    private String frameSignature(LabelNode label, Frame<BasicValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndex) {
        Integer index = frameIndex(label, frames, instructionIndex);
        if (index == null) {
            throw new IllegalStateException("CFF island target has no frame: " + label.getLabel());
        }
        Frame<BasicValue> frame = frames[index];
        StringBuilder sb = new StringBuilder(frame.getLocals() * 3);
        for (int i = 0; i < frame.getLocals(); i++) {
            BasicValue value = frame.getLocal(i);
            if (value == null || value == BasicValue.UNINITIALIZED_VALUE) {
                sb.append('.');
            } else if (value.getType() == null) {
                sb.append(value);
            } else {
                sb.append(value.getType().getDescriptor());
            }
            sb.append(';');
        }
        return sb.toString();
    }

    private Integer frameIndex(LabelNode label, Frame<BasicValue>[] frames,
            Map<AbstractInsnNode, Integer> instructionIndex) {
        Integer index = instructionIndex.get(label);
        if (index != null && frames[index] != null) {
            return index;
        }
        AbstractInsnNode real = nextReal(label.getNext());
        index = real == null ? null : instructionIndex.get(real);
        if (index != null && frames[index] != null) {
            return index;
        }
        return null;
    }

    private int islandCount(int nonHandlerCount) {
        if (nonHandlerCount <= 1) return 1;
        return Math.min(4, Math.max(2, (nonHandlerCount + 3) / 4));
    }

    private int islandFor(int nonHandlerIndex, int nonHandlerCount, int islandCount) {
        return (nonHandlerIndex * islandCount) / Math.max(1, nonHandlerCount);
    }

    private int aliasHubCount(int nonHandlerCount) {
        if (nonHandlerCount <= 2) return 1;
        return Math.min(3, 1 + nonHandlerCount / 6);
    }

    private int fakeCaseCount(long seed) {
        return 1 + (int) Long.remainderUnsigned(seed >>> 29, 3L);
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
            default -> throw new IllegalStateException("Unsupported conditional opcode for inversion: " + opcode);
        };
    }

    private record Block(LabelNode label, AbstractInsnNode endExclusive, boolean handler) {}
    private record BlockPlan(List<Block> blocks, Map<LabelNode, LabelNode> aliases) {}
    private enum EdgeKind { HUB, DIRECT_ISLAND, ALIAS_HUB }
    private enum EdgeRole {
        FALLTHROUGH,
        GOTO,
        CONDITIONAL_TRUE,
        CONDITIONAL_FALSE,
        SWITCH_CASE,
        SWITCH_DEFAULT,
        HANDLER
    }
    private record DispatchTarget(LabelNode hub, LabelNode[] islandLabels, LabelNode[] aliasHubs, int island) {}
    private record IslandGroup(LabelNode hub, LabelNode[] islandLabels, LabelNode[] aliasHubs, List<Block> blocks,
            Map<LabelNode, Integer> islands, long salt) {}
    private record DispatchPlan(List<IslandGroup> groups, Map<LabelNode, DispatchTarget> targets) {}
}
