package dev.nekoobfuscator.transforms.jvm.validation;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Replaces fixed String.equals validation sinks with CFF-live keyed tag checks.
 */
public final class JvmValidationSinkHardeningPass implements TransformPass {
    public static final String ID = "validationSinkHardening";
    private static final String HELPERS = "validationSinkHardening.helpers";
    private static final long TAG_MUL = 0x100000001B3L;
    private static final long TAG_ADD = 0x9E3779B97F4A7C15L;
    private static final String HELPER_DESC = "(Ljava/lang/String;JJI)Z";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Validation Sink Hardening";
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
        return Set.of(ControlFlowFlatteningPass.ID);
    }

    @Override
    public void transformClass(TransformContext ctx) {
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;
        if (TransformGuards.isRuntimeClass(clazz)) return;
        if (TransformGuards.isGeneratedMethod(method)) return;

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        ControlFlowFlatteningPass.CffMethodMetadata metadata =
            ControlFlowFlatteningPass.methodMetadata(pctx).get(methodKey);
        if (metadata == null || metadata.classKeyTable() == null) return;

        MethodNode mn = method.asmNode();
        int transformed = 0;
        int ordinal = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!isStringEquals(insn)) continue;
            AbstractInsnNode previous = previousReal(insn.getPrevious());
            if (!(previous instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String target)) continue;
            if (!metadata.applicationInstructions().contains(insn) ||
                !metadata.applicationInstructions().contains(previous)) {
                continue;
            }
            ControlFlowFlatteningPass.CffInstructionState state =
                metadata.instructionStates().get(insn);
            if (state == null) {
                state = metadata.instructionStates().get(previous);
            }
            if (state == null) {
                throw new IllegalStateException(
                    "validationSinkHardening cannot bind CFF state for " + methodKey
                );
            }
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++);
            String helperName = ensureHelper(pctx, clazz);
            InsnList replacement = new InsnList();
            emitDecodedLong(replacement, metadata, state, siteSeed, 0x5653485345454431L,
                tagSeed(target, siteSeed));
            emitDecodedLong(replacement, metadata, state, siteSeed, 0x5653485441473031L,
                tag(target, tagSeed(target, siteSeed)));
            emitDecodedInt(replacement, metadata, state, siteSeed, 0x5653484C454E3031L,
                target.length());
            replacement.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                clazz.name(),
                helperName,
                HELPER_DESC,
                clazz.isInterface()
            ));
            mn.instructions.insertBefore(previous, replacement);
            mn.instructions.remove(previous);
            mn.instructions.remove(insn);
            transformed++;
        }

        if (transformed > 0) {
            mn.maxStack = Math.max(mn.maxStack, 16);
            clazz.markDirty();
            pctx.invalidate(method);
            JvmObfuscationCoverage.get(ctx).full(
                id(),
                clazz.name(),
                method.name(),
                method.descriptor(),
                "cff-keyed-string-validation-sinks-" + transformed
            );
        }
    }

    private boolean isStringEquals(AbstractInsnNode insn) {
        return insn instanceof MethodInsnNode call &&
            call.getOpcode() == Opcodes.INVOKEVIRTUAL &&
            "java/lang/String".equals(call.owner) &&
            "equals".equals(call.name) &&
            "(Ljava/lang/Object;)Z".equals(call.desc);
    }

    private AbstractInsnNode previousReal(AbstractInsnNode insn) {
        for (AbstractInsnNode cursor = insn; cursor != null; cursor = cursor.getPrevious()) {
            if (cursor.getOpcode() >= 0) return cursor;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private String ensureHelper(PipelineContext pctx, L1Class clazz) {
        Map<String, String> helpers = pctx.getPassData(HELPERS);
        if (helpers == null) {
            helpers = new LinkedHashMap<>();
            pctx.putPassData(HELPERS, helpers);
        }
        String existing = helpers.get(clazz.name());
        if (existing != null) return existing;

        String name = uniqueMethodName(clazz, "__neko_vsink$");
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        access |= clazz.isInterface() ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode helper = new MethodNode(access, name, HELPER_DESC, null, null);
        emitHelperBody(helper.instructions);
        helper.maxLocals = 10;
        helper.maxStack = 8;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        clazz.markDirty();
        helpers.put(clazz.name(), name);
        return name;
    }

    private void emitHelperBody(InsnList insns) {
        int stringLocal = 0;
        int seedLocal = 1;
        int expectedLocal = 3;
        int lengthLocal = 5;
        int indexLocal = 6;
        int hashLocal = 7;
        int charLocal = 9;
        LabelNode lengthOk = new LabelNode();
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        LabelNode match = new LabelNode();

        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "length",
            "()I",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lengthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, lengthOk));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));

        insns.add(lengthOk);
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lengthLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, TAG_ADD);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, hashLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));

        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, lengthLocal));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "charAt",
            "(I)C",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ISTORE, charLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, hashLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, charLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, TAG_MUL);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new VarInsnNode(Opcodes.LSTORE, hashLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, hashLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, hashLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, TAG_ADD);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, hashLocal));
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));

        insns.add(done);
        insns.add(new VarInsnNode(Opcodes.LLOAD, hashLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, expectedLocal));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, match));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(match);
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IRETURN));
    }

    private void emitDecodedLong(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        long domain,
        long value
    ) {
        long mask = liveMask(metadata, state, siteSeed, domain);
        emitLiveMask(insns, metadata, state, siteSeed, domain);
        JvmPassBytecode.pushLong(insns, value ^ mask);
        insns.add(new InsnNode(Opcodes.LXOR));
    }

    private void emitDecodedInt(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        long domain,
        int value
    ) {
        int mask = (int) liveMask(metadata, state, siteSeed, domain);
        emitLiveMask(insns, metadata, state, siteSeed, domain);
        insns.add(new InsnNode(Opcodes.L2I));
        JvmPassBytecode.pushInt(insns, value ^ mask);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private long liveMask(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        long domain
    ) {
        int wordA = liveWord(metadata, state, siteSeed, domain);
        int wordB = liveWord(metadata, state, siteSeed, domain ^ 0x5653484D41534B32L);
        return ((long) wordA << 32) ^ (wordB & 0xFFFFFFFFL);
    }

    private int liveWord(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        long domain
    ) {
        long seed = JvmPassBytecode.mix(siteSeed ^ domain, state.selectorSeed());
        int x = (state.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x5653484755415244L))) +
            state.pathKey();
        x ^= x >>> shift(seed, 5);
        x = (x + state.blockKey()) ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x565348424C4F434BL));
        x *= nonZeroInt(JvmPassBytecode.mix(seed, 0x5653484D554C544BL)) | 1;
        x ^= derivedMethodKeyFold(state);
        x += state.pcToken() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x5653485043544F4BL));
        int idx = (x ^ state.state() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x5653485441424958L))) &
            (metadata.classKeyTable().values().length - 1);
        x ^= metadata.classKeyTable().values()[idx] +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x565348544142564CL));
        x += x >>> shift(seed, 19);
        return x ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x56534846494E414CL));
    }

    private void emitLiveMask(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        long domain
    ) {
        emitLiveWord(insns, metadata, state, siteSeed, domain);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitLiveWord(insns, metadata, state, siteSeed, domain ^ 0x5653484D41534B32L);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
    }

    private void emitLiveWord(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        long domain
    ) {
        long seed = JvmPassBytecode.mix(siteSeed ^ domain, state.selectorSeed());
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5653484755415244L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x565348424C4F434BL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5653484D554C544BL)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        emitDerivedMethodKeyFold(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5653485043544F4BL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5653485441424958L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, metadata.classKeyTable().values().length - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            metadata.classKeyTable().owner(),
            metadata.classKeyTable().objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.CLASS_KEY_WORDS_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new InsnNode(Opcodes.SWAP));
        ControlFlowFlatteningPass.emitDecodedSealedClassKeyWord(
            insns,
            ControlFlowFlatteningPass.CLASS_KEY_WORD_SEAL
        );
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x565348544142564CL)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 19));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x56534846494E414CL)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private int derivedMethodKeyFold(ControlFlowFlatteningPass.CffInstructionState state) {
        int x = (state.guardKey() ^ state.pathKey()) + state.blockKey();
        x ^= (int) state.methodSalt();
        x ^= (int) state.methodKey();
        return x;
    }

    private void emitDerivedMethodKeyFold(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, (int) state.methodSalt());
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private long tagSeed(String target, long siteSeed) {
        long seed = JvmPassBytecode.mix(siteSeed ^ 0x5653485441475344L, target.length());
        return nonZeroLong(JvmPassBytecode.mix(seed, 0x5653485345454432L));
    }

    private long tag(String value, long seed) {
        long h = (seed ^ value.length()) ^ TAG_ADD;
        for (int i = 0; i < value.length(); i++) {
            h ^= value.charAt(i);
            h *= TAG_MUL;
            h = (h ^ (h >>> 32)) + TAG_ADD;
        }
        return nonZeroLong(h);
    }

    private long siteSeed(
        long masterSeed,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffInstructionState state,
        int ordinal
    ) {
        long h = JvmPassBytecode.mix(masterSeed ^ 0x5653484F42465331L, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, state.blockIndex());
        h = JvmPassBytecode.mix(h, state.state());
        return JvmPassBytecode.mix(h, ordinal);
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String candidate = base + Integer.toUnsignedString(clazz.asmNode().methods.size(), 36);
        int suffix = 0;
        while (hasMethod(clazz, candidate)) {
            candidate = base + Integer.toUnsignedString(clazz.asmNode().methods.size() + ++suffix, 36);
        }
        return candidate;
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30L);
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x56534831 : v;
    }

    private long nonZeroLong(long value) {
        return value == 0L ? 0x5653484C4F4E4731L : value;
    }
}
