package dev.nekoobfuscator.transforms.jvm.constants;

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
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TableSwitchInsnNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Numeric constant protection that is tied to CFF's live key locals.
 */
public final class JvmConstantObfuscationPass implements TransformPass {
    public static final String ID = "constantObfuscation";
    private static final String NUMERIC_HELPERS = "constantObfuscation.numericHelpers";
    private static final int COMPACT_SITE_THRESHOLD = 32;
    private static final int COMPACT_SIZE_PRESSURE = 18_000;
    private static final int GENERATED_HELPER_HARDENING_SIZE_PRESSURE = 12_000;
    private static final int SITE_MIX_A = 0x4F1BBCDC;
    private static final int SITE_MIX_B = 0x2C9277B5;
    private static final int SITE_MIX_C = 0x7FEB352D;
    private static final int SITE_MIX_D = 0x846CA68B;

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
        if (TransformGuards.isRuntimeClass(clazz)) return;
        boolean generatedHelper = TransformGuards.isGeneratedMethod(method);
        boolean hardenGeneratedHelper =
            Boolean.TRUE.equals(pctx.getPassData("constantObfuscation.hardenGeneratedHelpers"));
        if (generatedHelper && !hardenGeneratedHelper) return;
        if (method.isAbstract() || method.isNative()) return;
        if (generatedHelper && hardenGeneratedHelper && generatedHelperUnderSizePressure(method.asmNode())) {
            return;
        }

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
        List<NumericSite> sites = new ArrayList<>();
        List<ArrayConstantSite> arraySites = new ArrayList<>();
        Set<AbstractInsnNode> arrayConsumed = Collections.newSetFromMap(new IdentityHashMap<>());
        Set<AbstractInsnNode> loopInstructions = loopRegionInstructions(mn);
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
            if (!arrayConsumed.contains(insn)) {
                ArrayConstantSite arraySite = primitiveArrayConstantSite(pctx, metadata, insn, state);
                if (arraySite != null) {
                    arraySite = arraySite.withSeed(siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++));
                    arraySites.add(arraySite);
                    arrayConsumed.addAll(arraySite.consumed());
                    continue;
                }
            }
        }
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (arrayConsumed.contains(insn)) continue;
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
            sites.add(new NumericSite(insn, kind, state, siteSeed));
        }

        if (sites.isEmpty() && arraySites.isEmpty()) return;

        int baseLocal = mn.maxLocals++;
        Map<Integer, FlowSite> firstSiteByBlock = new LinkedHashMap<>();
        Map<AbstractInsnNode, FlowSite> flowSitesByInsn = new IdentityHashMap<>();
        for (NumericSite site : sites) {
            flowSitesByInsn.put(site.insn(), new FlowSite(site.insn(), site.state()));
        }
        for (ArrayConstantSite site : arraySites) {
            flowSitesByInsn.put(site.lengthInsn(), new FlowSite(site.lengthInsn(), site.state()));
        }
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            FlowSite site = flowSitesByInsn.get(insn);
            if (site != null) {
                firstSiteByBlock.putIfAbsent(site.state().blockIndex(), site);
            }
        }
        for (FlowSite site : firstSiteByBlock.values()) {
            InsnList base = new InsnList();
            emitLiveConstantBase(base, metadata, site.state());
            base.add(new VarInsnNode(Opcodes.ISTORE, baseLocal));
            JvmKeyDispatchPass.markGenerated(pctx, base);
            mn.instructions.insertBefore(site.insn(), base);
        }

        boolean compact = useCompactNumericDecode(mn, sites);
        String compactHelper = compact ? ensureIntDecodeHelper(pctx, clazz) : null;
        int transformed = 0;
        int transformedArrays = 0;
        for (ArrayConstantSite site : arraySites) {
            InsnList replacement = new InsnList();
            emitDecodedPrimitiveArray(replacement, site, metadata, baseLocal, compactHelper, clazz);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(site.lengthInsn(), replacement);
            for (AbstractInsnNode consumed : site.consumed()) {
                mn.instructions.remove(consumed);
            }
            transformedArrays++;
        }
        for (NumericSite site : sites) {
            AbstractInsnNode insn = site.insn();
            if (insn instanceof IincInsnNode iinc) {
                InsnList replacement = new InsnList();
                replacement.add(new VarInsnNode(Opcodes.ILOAD, iinc.var));
                emitDecodedInt(replacement, iinc.incr, site.siteSeed(), metadata, site.state(), baseLocal, compactHelper, clazz);
                replacement.add(new InsnNode(Opcodes.IADD));
                replacement.add(new VarInsnNode(Opcodes.ISTORE, iinc.var));
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                transformed++;
                continue;
            }
            InsnList replacement = new InsnList();
            emitDecodedConstant(replacement, insn, site.kind(), site.siteSeed(), metadata, site.state(), baseLocal, compactHelper, clazz);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            mn.instructions.remove(insn);
            transformed++;
        }

        if (transformed > 0 || transformedArrays > 0) {
            mn.maxStack = Math.max(mn.maxStack, 20);
            clazz.markDirty();
            pctx.invalidate(method);
            JvmObfuscationCoverage.get(ctx).full(
                id(),
                clazz.name(),
                method.name(),
                method.descriptor(),
                (compact ? "compact-" : "") +
                    "cff-keyed-numeric-sites-" + transformed +
                    "-primitive-arrays-" + transformedArrays
            );
        }
    }

    private boolean useCompactNumericDecode(MethodNode mn, List<NumericSite> sites) {
        if (sites.size() >= COMPACT_SITE_THRESHOLD) return true;
        int estimatedGrowth = 0;
        for (NumericSite site : sites) {
            estimatedGrowth += site.kind() == NumericKind.LONG || site.kind() == NumericKind.DOUBLE ? 38 : 18;
        }
        return JvmCodeSizeEstimator.estimateMethodBytes(mn) + estimatedGrowth >= COMPACT_SIZE_PRESSURE;
    }

    private boolean generatedHelperUnderSizePressure(MethodNode mn) {
        return JvmCodeSizeEstimator.estimateMethodBytes(mn) >= GENERATED_HELPER_HARDENING_SIZE_PRESSURE;
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
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        switch (kind) {
            case INT -> emitDecodedInt(insns, intConstant(source), siteSeed, metadata, state, baseLocal, compactHelper, clazz);
            case LONG -> emitDecodedLong(insns, longConstant(source), siteSeed, metadata, state, baseLocal, compactHelper, clazz);
            case FLOAT -> {
                emitDecodedInt(insns, Float.floatToRawIntBits(floatConstant(source)), siteSeed, metadata, state, baseLocal, compactHelper, clazz);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case DOUBLE -> {
                emitDecodedLong(insns, Double.doubleToRawLongBits(doubleConstant(source)), siteSeed, metadata, state, baseLocal, compactHelper, clazz);
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
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedIntUncached(insns, value, siteSeed, metadata, state, baseLocal, compactHelper, clazz);
    }

    private void emitDecodedIntUncached(
        InsnList insns,
        int value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        int expectedMask = constantMaskFromBase(liveConstantBase(metadata, state), (int) siteSeed);
        int encrypted = value ^ expectedMask;
        if (compactHelper != null) {
            insns.add(new VarInsnNode(Opcodes.ILOAD, baseLocal));
            JvmPassBytecode.pushInt(insns, encrypted);
            JvmPassBytecode.pushInt(insns, (int) siteSeed);
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                clazz.name(),
                compactHelper,
                "(III)I",
                clazz.isInterface()
            ));
        } else {
            JvmPassBytecode.pushInt(insns, encrypted);
            emitSiteMaskFromBase(insns, baseLocal, (int) siteSeed);
            insns.add(new InsnNode(Opcodes.IXOR));
        }
    }

    private void emitDecodedLong(
        InsnList insns,
        long value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedLongUncached(insns, value, siteSeed, metadata, state, baseLocal, compactHelper, clazz);
    }

    private void emitDecodedLongUncached(
        InsnList insns,
        long value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedInt(insns, (int) (value >>> 32), siteSeed ^ 0x484947484B31L, metadata, state, baseLocal, compactHelper, clazz);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitDecodedInt(insns, (int) value, siteSeed ^ 0x4C4F574B31L, metadata, state, baseLocal, compactHelper, clazz);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private int liveConstantBase(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        int x =
            (state.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424731L, 0x43474B31L))) +
            state.pathKey();
        x ^= x >>> 7;
        x = (x + state.blockKey()) ^
            nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424231L, 0x43424B31L));
        x *= nonZeroInt(JvmPassBytecode.mix(0x434F4E53544D554CL, 0x434D554CL)) | 1;
        x ^= derivedMethodKeyFold(metadata, state);
        x += state.pcToken() ^ nonZeroInt(JvmPassBytecode.mix(0x434F4E5354504331L, 0x43504331L));
        int idx =
            (x ^ state.state() ^ nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544142L, 0x435441424CL))) &
            (metadata.classKeyTable().values().length - 1);
        x ^= metadata.classKeyTable().values()[idx] +
            nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544149L, 0x435441494CL));
        x += x >>> 19;
        x *= nonZeroInt(JvmPassBytecode.mix(0x473138434F4E5354L, state.methodSalt())) | 1;
        x ^= x >>> 11;
        return x;
    }

    private int constantMaskFromBase(int base, int seed) {
        int x = base + (seed ^ SITE_MIX_A);
        x ^= x >>> 16;
        x *= (seed ^ SITE_MIX_B) | 1;
        x ^= seed * SITE_MIX_C + SITE_MIX_D;
        return x;
    }

    private int derivedMethodKeyFold(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        int x = (state.guardKey() ^ state.pathKey()) + state.blockKey();
        x ^= (int) state.methodSalt();
        x ^= (int) state.methodKey();
        return x;
    }

    private void emitLiveConstantBase(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424731L, 0x43474B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 7);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354424231L, 0x43424B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E53544D554CL, 0x434D554CL)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        emitDerivedMethodKeyFold(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354504331L, 0x43504331L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544142L, 0x435441424CL)));
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
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x434F4E5354544149L, 0x435441494CL)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 19);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x473138434F4E5354L, state.methodSalt())) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 11);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitSiteMaskFromBase(InsnList insns, int baseLocal, int seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, baseLocal));
        JvmPassBytecode.pushInt(insns, seed ^ SITE_MIX_A);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, (seed ^ SITE_MIX_B) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, seed * SITE_MIX_C + SITE_MIX_D);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    @SuppressWarnings("unchecked")
    private String ensureIntDecodeHelper(PipelineContext pctx, L1Class clazz) {
        Map<String, String> helpers = pctx.getPassData(NUMERIC_HELPERS);
        if (helpers == null) {
            helpers = new LinkedHashMap<>();
            pctx.putPassData(NUMERIC_HELPERS, helpers);
        }
        String key = clazz.name();
        String existing = helpers.get(key);
        if (existing != null) return existing;

        String name = uniqueMethodName(clazz, "__neko_num_i");
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        access |= (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0 ? Opcodes.ACC_PUBLIC : Opcodes.ACC_PRIVATE;
        MethodNode helper = new MethodNode(access, name, "(III)I", null, null);
        emitHelperSiteMask(helper.instructions);
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, 1));
        helper.instructions.add(new InsnNode(Opcodes.IXOR));
        helper.instructions.add(new InsnNode(Opcodes.IRETURN));
        helper.maxLocals = 4;
        helper.maxStack = 5;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        clazz.markDirty();
        helpers.put(key, name);
        return name;
    }

    private void emitHelperSiteMask(InsnList insns) {
        int xLocal = 3;
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        JvmPassBytecode.pushInt(insns, SITE_MIX_A);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        JvmPassBytecode.pushInt(insns, SITE_MIX_B);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        JvmPassBytecode.pushInt(insns, SITE_MIX_C);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, SITE_MIX_D);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String candidate = base;
        int suffix = 0;
        while (hasMethod(clazz, candidate)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (name.equals(method.name)) return true;
        }
        return false;
    }

    @SuppressWarnings("unchecked")
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

    private ArrayConstantSite primitiveArrayConstantSite(
        PipelineContext pctx,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        AbstractInsnNode lengthInsn,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        if (numericKind(lengthInsn) != NumericKind.INT) return null;
        int length = intConstant(lengthInsn);
        if (length < 0) return null;
        AbstractInsnNode arrayInsn = nextReal(lengthInsn.getNext());
        if (!(arrayInsn instanceof IntInsnNode newArray) || newArray.getOpcode() != Opcodes.NEWARRAY) {
            return null;
        }
        PrimitiveArrayKind kind = PrimitiveArrayKind.fromNewArrayOperand(newArray.operand);
        if (kind == null || !isApplicationPatternNode(pctx, metadata, arrayInsn)) return null;

        List<AbstractInsnNode> consumed = new ArrayList<>();
        consumed.add(lengthInsn);
        consumed.add(arrayInsn);
        List<ArrayElement> elements = new ArrayList<>();
        boolean[] seen = new boolean[length];
        AbstractInsnNode cursor = nextReal(arrayInsn.getNext());
        while (cursor != null && cursor.getOpcode() == Opcodes.DUP) {
            if (!isApplicationPatternNode(pctx, metadata, cursor) || elements.size() >= length) return null;
            AbstractInsnNode indexInsn = nextReal(cursor.getNext());
            if (!isApplicationPatternNode(pctx, metadata, indexInsn) || numericKind(indexInsn) != NumericKind.INT) {
                return null;
            }
            int index = intConstant(indexInsn);
            if (index < 0 || index >= length || seen[index]) return null;
            AbstractInsnNode valueInsn = nextReal(indexInsn.getNext());
            if (!isApplicationPatternNode(pctx, metadata, valueInsn)) return null;
            Long valueBits = arrayElementBits(kind, valueInsn);
            if (valueBits == null) return null;
            AbstractInsnNode storeInsn = nextReal(valueInsn.getNext());
            if (
                !isApplicationPatternNode(pctx, metadata, storeInsn) ||
                storeInsn.getOpcode() != kind.storeOpcode()
            ) {
                return null;
            }
            seen[index] = true;
            elements.add(new ArrayElement(index, valueBits));
            consumed.add(cursor);
            consumed.add(indexInsn);
            consumed.add(valueInsn);
            consumed.add(storeInsn);
            cursor = nextReal(storeInsn.getNext());
        }
        if (elements.size() != length) return null;
        return new ArrayConstantSite(lengthInsn, kind, length, elements, state, 0L, consumed);
    }

    private void emitDecodedPrimitiveArray(
        InsnList insns,
        ArrayConstantSite site,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        emitDecodedInt(
            insns,
            site.length(),
            JvmPassBytecode.mix(site.siteSeed(), 0x4152524C454E4754L),
            metadata,
            site.state(),
            baseLocal,
            compactHelper,
            clazz
        );
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, site.kind().newArrayOperand()));
        for (int ordinal = 0; ordinal < site.elements().size(); ordinal++) {
            ArrayElement element = site.elements().get(ordinal);
            long elementSeed = JvmPassBytecode.mix(
                site.siteSeed() ^ 0x415252454C454D31L,
                ((long) element.index() << 32) ^ ordinal ^ site.kind().newArrayOperand()
            );
            insns.add(new InsnNode(Opcodes.DUP));
            emitDecodedInt(
                insns,
                element.index(),
                JvmPassBytecode.mix(elementSeed, 0x4152524944583031L),
                metadata,
                site.state(),
                baseLocal,
                compactHelper,
                clazz
            );
            emitDecodedArrayElement(
                insns,
                site.kind(),
                element.valueBits(),
                JvmPassBytecode.mix(elementSeed, 0x41525256414C3031L),
                metadata,
                site.state(),
                baseLocal,
                compactHelper,
                clazz
            );
            insns.add(new InsnNode(site.kind().storeOpcode()));
        }
    }

    private void emitDecodedArrayElement(
        InsnList insns,
        PrimitiveArrayKind kind,
        long valueBits,
        long seed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int baseLocal,
        String compactHelper,
        L1Class clazz
    ) {
        switch (kind.valueKind()) {
            case INT, IINC -> emitDecodedInt(insns, (int) valueBits, seed, metadata, state, baseLocal, compactHelper, clazz);
            case LONG -> emitDecodedLong(insns, valueBits, seed, metadata, state, baseLocal, compactHelper, clazz);
            case FLOAT -> {
                emitDecodedInt(insns, (int) valueBits, seed, metadata, state, baseLocal, compactHelper, clazz);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Float",
                    "intBitsToFloat",
                    "(I)F",
                    false
                ));
            }
            case DOUBLE -> {
                emitDecodedLong(insns, valueBits, seed, metadata, state, baseLocal, compactHelper, clazz);
                insns.add(new MethodInsnNode(
                    Opcodes.INVOKESTATIC,
                    "java/lang/Double",
                    "longBitsToDouble",
                    "(J)D",
                    false
                ));
            }
        }
    }

    private Long arrayElementBits(PrimitiveArrayKind kind, AbstractInsnNode valueInsn) {
        return switch (kind.valueKind()) {
            case INT -> numericKind(valueInsn) == NumericKind.INT ? (long) intConstant(valueInsn) : null;
            case LONG -> numericKind(valueInsn) == NumericKind.LONG ? longConstant(valueInsn) : null;
            case FLOAT -> numericKind(valueInsn) == NumericKind.FLOAT
                ? (long) Float.floatToRawIntBits(floatConstant(valueInsn))
                : null;
            case DOUBLE -> numericKind(valueInsn) == NumericKind.DOUBLE
                ? Double.doubleToRawLongBits(doubleConstant(valueInsn))
                : null;
            case IINC -> null;
        };
    }

    private boolean isApplicationPatternNode(
        PipelineContext pctx,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        AbstractInsnNode insn
    ) {
        return insn != null &&
            insn.getOpcode() >= 0 &&
            metadata.applicationInstructions().contains(insn) &&
            !JvmKeyDispatchPass.isGeneratedNode(pctx, insn);
    }

    private AbstractInsnNode nextReal(AbstractInsnNode insn) {
        AbstractInsnNode cursor = insn;
        while (cursor != null && cursor.getOpcode() < 0) {
            cursor = cursor.getNext();
        }
        return cursor;
    }

    private Set<AbstractInsnNode> loopRegionInstructions(MethodNode mn) {
        AbstractInsnNode[] nodes = mn.instructions.toArray();
        Map<AbstractInsnNode, Integer> indexByNode = new IdentityHashMap<>();
        for (int i = 0; i < nodes.length; i++) {
            indexByNode.put(nodes[i], i);
        }
        Set<AbstractInsnNode> loop = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < nodes.length; i++) {
            AbstractInsnNode node = nodes[i];
            if (node instanceof JumpInsnNode jump) {
                markBackwardRegion(loop, nodes, indexByNode, i, jump.label);
            } else if (node instanceof LookupSwitchInsnNode lookup) {
                markBackwardRegion(loop, nodes, indexByNode, i, lookup.dflt);
                for (LabelNode label : lookup.labels) {
                    markBackwardRegion(loop, nodes, indexByNode, i, label);
                }
            } else if (node instanceof TableSwitchInsnNode table) {
                markBackwardRegion(loop, nodes, indexByNode, i, table.dflt);
                for (LabelNode label : table.labels) {
                    markBackwardRegion(loop, nodes, indexByNode, i, label);
                }
            }
        }
        return loop;
    }

    private void markBackwardRegion(
        Set<AbstractInsnNode> loop,
        AbstractInsnNode[] nodes,
        Map<AbstractInsnNode, Integer> indexByNode,
        int sourceIndex,
        LabelNode target
    ) {
        Integer targetIndex = indexByNode.get(target);
        if (targetIndex == null || targetIndex > sourceIndex) {
            return;
        }
        for (int i = targetIndex; i <= sourceIndex; i++) {
            loop.add(nodes[i]);
        }
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
        INT(false),
        LONG(true),
        FLOAT(false),
        DOUBLE(true),
        IINC(false);

        private final boolean wideCache;

        NumericKind(boolean wideCache) {
            this.wideCache = wideCache;
        }

        boolean wideCache() {
            return wideCache;
        }
    }

    private record NumericSite(
        AbstractInsnNode insn,
        NumericKind kind,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed
    ) {}

    private record FlowSite(
        AbstractInsnNode insn,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {}

    private record ArrayConstantSite(
        AbstractInsnNode lengthInsn,
        PrimitiveArrayKind kind,
        int length,
        List<ArrayElement> elements,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        List<AbstractInsnNode> consumed
    ) {
        ArrayConstantSite withSeed(long seed) {
            return new ArrayConstantSite(lengthInsn, kind, length, elements, state, seed, consumed);
        }
    }

    private record ArrayElement(int index, long valueBits) {}

    private enum PrimitiveArrayKind {
        BOOLEAN(Opcodes.T_BOOLEAN, Opcodes.BASTORE, NumericKind.INT),
        BYTE(Opcodes.T_BYTE, Opcodes.BASTORE, NumericKind.INT),
        CHAR(Opcodes.T_CHAR, Opcodes.CASTORE, NumericKind.INT),
        SHORT(Opcodes.T_SHORT, Opcodes.SASTORE, NumericKind.INT),
        INT(Opcodes.T_INT, Opcodes.IASTORE, NumericKind.INT),
        LONG(Opcodes.T_LONG, Opcodes.LASTORE, NumericKind.LONG),
        FLOAT(Opcodes.T_FLOAT, Opcodes.FASTORE, NumericKind.FLOAT),
        DOUBLE(Opcodes.T_DOUBLE, Opcodes.DASTORE, NumericKind.DOUBLE);

        private final int newArrayOperand;
        private final int storeOpcode;
        private final NumericKind valueKind;

        PrimitiveArrayKind(int newArrayOperand, int storeOpcode, NumericKind valueKind) {
            this.newArrayOperand = newArrayOperand;
            this.storeOpcode = storeOpcode;
            this.valueKind = valueKind;
        }

        int newArrayOperand() {
            return newArrayOperand;
        }

        int storeOpcode() {
            return storeOpcode;
        }

        NumericKind valueKind() {
            return valueKind;
        }

        static PrimitiveArrayKind fromNewArrayOperand(int operand) {
            for (PrimitiveArrayKind kind : values()) {
                if (kind.newArrayOperand == operand) return kind;
            }
            return null;
        }
    }

}
