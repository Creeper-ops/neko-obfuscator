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
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Numeric constant protection that is tied to CFF's live key locals.
 */
public final class JvmConstantObfuscationPass implements TransformPass {
    public static final String ID = "constantObfuscation";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM Constant Obfuscation";
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
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        if (clazz == null || TransformGuards.isRuntimeClass(clazz)) return;

        int moved = moveNumericConstantValues(pctx, clazz);
        if (moved > 0) {
            clazz.markDirty();
        }
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        if (!(ctx instanceof PipelineContext pctx)) return;
        L1Class clazz = pctx.currentL1Class();
        L1Method method = pctx.currentL1Method();
        if (clazz == null || method == null || !method.hasCode()) return;
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return;
        if (method.isAbstract() || method.isNative()) return;

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        ControlFlowFlatteningPass.CffMethodMetadata metadata =
            ControlFlowFlatteningPass.methodMetadata(pctx).get(methodKey);
        if (metadata == null) return;
        if (metadata.classKeyTable() == null) {
            throw new IllegalStateException(
                "constantObfuscation requires CFF class key table metadata for " + methodKey
            );
        }

        MethodNode mn = method.asmNode();
        int transformed = 0;
        int ordinal = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            ControlFlowFlatteningPass.CffInstructionState state =
                metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException(
                    "constantObfuscation cannot bind CFF state for " + methodKey
                );
            }
            NumericKind kind = numericKind(insn);
            if (kind == null) continue;
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++);
            if (insn instanceof IincInsnNode iinc) {
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ILOAD, iinc.var));
                emitDecodedInt(replacement, iinc.incr, siteSeed, metadata, state, mn);
                replacement.add(new InsnNode(Opcodes.IADD));
                replacement.add(new VarInsnNode(Opcodes.ISTORE, iinc.var));
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                transformed++;
                continue;
            }

            InsnList replacement = new InsnList();
            emitDecodedConstant(replacement, insn, kind, siteSeed, metadata, state, mn);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            mn.instructions.remove(insn);
            transformed++;
        }

        if (transformed > 0) {
            mn.maxStack = Math.max(mn.maxStack, 12);
            clazz.markDirty();
            pctx.invalidate(method);
            JvmObfuscationCoverage.get(ctx).full(
                id(),
                clazz.name(),
                method.name(),
                method.descriptor(),
                "cff-keyed-numeric-sites-" + transformed
            );
        }
    }

    private int moveNumericConstantValues(PipelineContext pctx, L1Class clazz) {
        if (clazz.asmNode().fields == null) return 0;
        int moved = 0;
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.value == null || !isNumericConstantValue(field)) continue;
            Object value = field.value;
            field.value = null;
            MethodNode clinit = findOrCreateClassInit(clazz);
            InsnList assignment = new InsnList();
            long seed = JvmPassBytecode.mix(
                pctx.masterSeed() ^ 0x434F4E535456414CL,
                (clazz.name() + "." + field.name + field.desc).hashCode()
            );
            emitFieldValue(assignment, value, field.desc, seed);
            assignment.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), field.name, field.desc));
            JvmKeyDispatchPass.markGenerated(pctx, assignment);
            AbstractInsnNode returnInsn = firstReturn(clinit);
            if (returnInsn == null) {
                clinit.instructions.add(assignment);
                clinit.instructions.add(new InsnNode(Opcodes.RETURN));
            } else {
                clinit.instructions.insertBefore(returnInsn, assignment);
            }
            clinit.maxStack = Math.max(clinit.maxStack, 8);
            moved++;
        }
        return moved;
    }

    private boolean isNumericConstantValue(FieldNode field) {
        if ((field.access & Opcodes.ACC_STATIC) == 0) return false;
        return switch (field.desc) {
            case "B", "C", "S", "I", "J", "F", "D" -> field.value instanceof Number;
            default -> false;
        };
    }

    private void emitFieldValue(InsnList insns, Object value, String desc, long seed) {
        switch (desc) {
            case "J" -> emitStaticDecodedLong(insns, ((Number) value).longValue(), seed);
            case "F" -> {
                emitStaticDecodedInt(insns, Float.floatToRawIntBits(((Number) value).floatValue()), seed);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case "D" -> {
                emitStaticDecodedLong(insns, Double.doubleToRawLongBits(((Number) value).doubleValue()), seed);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
            default -> emitStaticDecodedInt(insns, ((Number) value).intValue(), seed);
        }
    }

    private void emitDecodedConstant(
        InsnList insns,
        AbstractInsnNode source,
        NumericKind kind,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        MethodNode mn
    ) {
        switch (kind) {
            case INT -> emitDecodedInt(insns, intConstant(source), siteSeed, metadata, state, mn);
            case LONG -> emitDecodedLong(insns, longConstant(source), siteSeed, metadata, state, mn);
            case FLOAT -> {
                emitDecodedInt(insns, Float.floatToRawIntBits(floatConstant(source)), siteSeed, metadata, state, mn);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case DOUBLE -> {
                emitDecodedLong(insns, Double.doubleToRawLongBits(doubleConstant(source)), siteSeed, metadata, state, mn);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
            case IINC -> throw new IllegalStateException("IINC is handled by caller");
        }
    }

    private void emitDecodedInt(
        InsnList insns,
        int value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        MethodNode mn
    ) {
        int expectedMask = liveConstantMask(metadata, state, siteSeed);
        int encrypted = value ^ expectedMask;
        JvmPassBytecode.pushInt(insns, encrypted);
        emitLiveConstantMask(insns, siteSeed, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitDecodedLong(
        InsnList insns,
        long value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        MethodNode mn
    ) {
        emitDecodedInt(insns, (int) (value >>> 32), siteSeed ^ 0x484947484B31L, metadata, state, mn);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitDecodedInt(insns, (int) value, siteSeed ^ 0x4C4F574B31L, metadata, state, mn);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private int liveConstantMask(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed
    ) {
        int x =
            (state.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x43474B31L))) +
            state.pathKey();
        x ^= x >>> shift(siteSeed, 7);
        x = (x + state.blockKey()) ^
            nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x43424B31L));
        x *= nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x434D554CL)) | 1;
        x ^= derivedMethodKeyFold(metadata, state);
        int idx =
            (x ^ state.pcToken() ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x435441424CL))) &
            (metadata.classKeyTable().values().length - 1);
        x ^= metadata.classKeyTable().values()[idx] +
            nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x435441494CL));
        x += x >>> shift(siteSeed, 19);
        return x;
    }

    private int derivedMethodKeyFold(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        int x = (state.guardKey() ^ state.pathKey()) + state.blockKey();
        x ^= (int) state.methodSalt();
        x ^= (int) metadata.methodSeed();
        return x;
    }

    private void emitLiveConstantMask(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x43474B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 7));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x43424B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x434D554CL)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        emitDerivedMethodKeyFold(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x435441424CL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, metadata.classKeyTable().values().length - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            metadata.classKeyTable().owner(),
            metadata.classKeyTable().fieldName(),
            "[I"
        ));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new InsnNode(Opcodes.IALOAD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x435441494CL)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 19));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
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

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30L);
    }

    private void emitStaticDecodedInt(InsnList insns, int value, long seed) {
        int mask = nonZeroInt(JvmPassBytecode.mix(seed, 0x5354415449434B31L));
        JvmPassBytecode.pushInt(insns, value ^ mask);
        JvmPassBytecode.pushInt(insns, mask);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStaticDecodedLong(InsnList insns, long value, long seed) {
        emitStaticDecodedInt(insns, (int) (value >>> 32), seed ^ 0x484947484B31L);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitStaticDecodedInt(insns, (int) value, seed ^ 0x4C4F574B31L);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private NumericKind numericKind(AbstractInsnNode insn) {
        if (insn instanceof IincInsnNode) return NumericKind.IINC;
        int opcode = insn.getOpcode();
        if ((opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5)
            || opcode == Opcodes.BIPUSH
            || opcode == Opcodes.SIPUSH) {
            return NumericKind.INT;
        }
        if (opcode == Opcodes.LCONST_0 || opcode == Opcodes.LCONST_1) return NumericKind.LONG;
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) return NumericKind.FLOAT;
        if (opcode == Opcodes.DCONST_0 || opcode == Opcodes.DCONST_1) return NumericKind.DOUBLE;
        if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof Number cst) {
            if (cst instanceof Integer) return NumericKind.INT;
            if (cst instanceof Long) return NumericKind.LONG;
            if (cst instanceof Float) return NumericKind.FLOAT;
            if (cst instanceof Double) return NumericKind.DOUBLE;
        }
        return null;
    }

    private int intConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.ICONST_M1 && opcode <= Opcodes.ICONST_5) {
            return opcode - Opcodes.ICONST_0;
        }
        if (opcode == Opcodes.BIPUSH || opcode == Opcodes.SIPUSH) {
            return ((IntInsnNode) insn).operand;
        }
        return (Integer) ((LdcInsnNode) insn).cst;
    }

    private long longConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.LCONST_0) return 0L;
        if (opcode == Opcodes.LCONST_1) return 1L;
        return (Long) ((LdcInsnNode) insn).cst;
    }

    private float floatConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode >= Opcodes.FCONST_0 && opcode <= Opcodes.FCONST_2) {
            return opcode - Opcodes.FCONST_0;
        }
        return (Float) ((LdcInsnNode) insn).cst;
    }

    private double doubleConstant(AbstractInsnNode insn) {
        int opcode = insn.getOpcode();
        if (opcode == Opcodes.DCONST_0) return 0.0d;
        if (opcode == Opcodes.DCONST_1) return 1.0d;
        return (Double) ((LdcInsnNode) insn).cst;
    }

    private long siteSeed(
        long masterSeed,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffInstructionState state,
        int ordinal
    ) {
        long h = JvmPassBytecode.mix(masterSeed ^ 0x434F4E53544F4246L, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, state.blockIndex());
        h = JvmPassBytecode.mix(h, state.state());
        return JvmPassBytecode.mix(h, ordinal);
    }

    private MethodNode findOrCreateClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name)) return method;
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private AbstractInsnNode firstReturn(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn.getOpcode() == Opcodes.RETURN) return insn;
        }
        return null;
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x5A17C9E3 : v;
    }

    private enum NumericKind {
        INT,
        LONG,
        FLOAT,
        DOUBLE,
        IINC
    }
}
