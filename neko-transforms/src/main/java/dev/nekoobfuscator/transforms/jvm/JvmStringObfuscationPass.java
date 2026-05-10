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
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Flow-keyed string literal protection for CFF-protected application code.
 */
public final class JvmStringObfuscationPass implements TransformPass {
    public static final String ID = "stringObfuscation";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM String Obfuscation";
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
        if (TransformGuards.isRuntimeClass(clazz) || TransformGuards.isGeneratedMethod(method)) return;
        if (method.isAbstract() || method.isNative()) return;

        String methodKey = JvmKeyDispatchPass.coverageKey(clazz, method);
        ControlFlowFlatteningPass.CffMethodMetadata metadata =
            ControlFlowFlatteningPass.methodMetadata(pctx).get(methodKey);
        if (metadata == null) return;
        if (metadata.classKeyTable() == null) {
            throw new IllegalStateException(
                "stringObfuscation requires CFF class key table metadata for " + methodKey
            );
        }

        MethodNode mn = method.asmNode();
        int transformed = 0;
        int ordinal = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            if (!(insn instanceof LdcInsnNode ldc) || !(ldc.cst instanceof String value)) continue;
            ControlFlowFlatteningPass.CffInstructionState state =
                metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException(
                    "stringObfuscation cannot bind CFF state for " + methodKey
                );
            }

            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal);
            Algorithm algorithm = algorithmFor(ordinal, siteSeed, metadata, state);
            byte[] encrypted = encryptPayload(value, siteSeed, metadata, state, algorithm);
            InsnList replacement = new InsnList();
            emitDecodedString(replacement, encrypted, siteSeed, metadata, state, algorithm, mn);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            mn.instructions.remove(insn);
            transformed++;
            ordinal++;
        }

        if (transformed > 0) {
            mn.maxStack = Math.max(mn.maxStack, 32);
            clazz.markDirty();
            pctx.invalidate(method);
            JvmObfuscationCoverage.get(ctx).full(
                id(),
                clazz.name(),
                method.name(),
                method.descriptor(),
                "cff-keyed-string-sites-" + transformed
            );
        }
    }

    private Algorithm algorithmFor(
        int ordinal,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        if ((ordinal & 1) == 0) return Algorithm.AES;
        int root = liveStringWord(metadata, state, rootSeed(siteSeed));
        byte[] key = keyBytes(root, siteSeed, Algorithm.DES);
        try {
            return DESKeySpec.isWeak(key, 0) ? Algorithm.AES : Algorithm.DES;
        } catch (InvalidKeyException ex) {
            return Algorithm.AES;
        }
    }

    private byte[] encryptPayload(
        String value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        Algorithm algorithm
    ) {
        int root = liveStringWord(metadata, state, rootSeed(siteSeed));
        byte[] utf8 = value.getBytes(StandardCharsets.UTF_8);
        int rawLength = 4 + utf8.length;
        int paddedLength = ((rawLength + algorithm.blockSize - 1) / algorithm.blockSize) * algorithm.blockSize;
        byte[] payload = new byte[paddedLength];
        payload[0] = (byte) (utf8.length >>> 24);
        payload[1] = (byte) (utf8.length >>> 16);
        payload[2] = (byte) (utf8.length >>> 8);
        payload[3] = (byte) utf8.length;
        System.arraycopy(utf8, 0, payload, 4, utf8.length);
        long pad = JvmPassBytecode.mix(siteSeed, 0x5354525041444B31L);
        for (int i = rawLength; i < payload.length; i++) {
            pad = JvmPassBytecode.mix(pad, i);
            payload[i] = (byte) pad;
        }
        byte[] xor = xorBytes(root, siteSeed, payload.length);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ xor[i]);
        }
        try {
            Cipher cipher = Cipher.getInstance(algorithm.transformation);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(root, siteSeed, algorithm), algorithm.keyName));
            return cipher.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt string literal with " + algorithm.keyName, ex);
        }
    }

    private byte[] keyBytes(
        int root,
        long siteSeed,
        Algorithm algorithm
    ) {
        byte[] out = new byte[algorithm.keySize];
        for (int word = 0; word < out.length / 4; word++) {
            int value = streamWord(root, keySeed(siteSeed, word, algorithm));
            writeWord(out, word * 4, value);
        }
        return out;
    }

    private byte[] xorBytes(
        int root,
        long siteSeed,
        int length
    ) {
        byte[] out = new byte[length];
        for (int offset = 0, word = 0; offset < out.length; offset += 4, word++) {
            int value = streamWord(root, xorSeed(siteSeed, word));
            for (int b = 0; b < 4 && offset + b < out.length; b++) {
                out[offset + b] = (byte) (value >>> (24 - b * 8));
            }
        }
        return out;
    }

    private void writeWord(byte[] out, int offset, int value) {
        out[offset] = (byte) (value >>> 24);
        out[offset + 1] = (byte) (value >>> 16);
        out[offset + 2] = (byte) (value >>> 8);
        out[offset + 3] = (byte) value;
    }

    private void emitDecodedString(
        InsnList insns,
        byte[] encrypted,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        Algorithm algorithm,
        MethodNode mn
    ) {
        int encryptedLocal = mn.maxLocals;
        int keyLocal = encryptedLocal + 1;
        int cipherLocal = keyLocal + 1;
        int plainLocal = cipherLocal + 1;
        int wordLocal = plainLocal + 1;
        int lengthLocal = wordLocal + 1;
        int rootLocal = lengthLocal + 1;
        mn.maxLocals = Math.max(mn.maxLocals, rootLocal + 1);

        emitByteArray(insns, encrypted);
        insns.add(new VarInsnNode(Opcodes.ASTORE, encryptedLocal));
        emitLiveStringWord(insns, rootSeed(siteSeed), metadata, state);
        insns.add(new VarInsnNode(Opcodes.ISTORE, rootLocal));
        JvmPassBytecode.pushInt(insns, algorithm.keySize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, keyLocal));
        emitFillKey(insns, siteSeed, algorithm, keyLocal, wordLocal, rootLocal);
        emitCipherDecrypt(insns, encryptedLocal, keyLocal, cipherLocal, plainLocal, algorithm);
        emitXorPlaintext(insns, siteSeed, encrypted.length, plainLocal, wordLocal, rootLocal);
        emitStringLength(insns, plainLocal);
        insns.add(new VarInsnNode(Opcodes.ISTORE, lengthLocal));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, plainLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new VarInsnNode(Opcodes.ILOAD, lengthLocal));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            "java/nio/charset/StandardCharsets",
            "UTF_8",
            "Ljava/nio/charset/Charset;"
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "java/lang/String",
            "<init>",
            "([BIILjava/nio/charset/Charset;)V",
            false
        ));
    }

    private void emitByteArray(InsnList insns, byte[] data) {
        StringBuilder encoded = new StringBuilder(data.length);
        for (byte b : data) {
            encoded.append((char) (b & 0xFF));
        }
        insns.add(new LdcInsnNode(encoded.toString()));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            "java/nio/charset/StandardCharsets",
            "ISO_8859_1",
            "Ljava/nio/charset/Charset;"
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "getBytes",
            "(Ljava/nio/charset/Charset;)[B",
            false
        ));
    }

    private void emitFillKey(
        InsnList insns,
        long siteSeed,
        Algorithm algorithm,
        int keyLocal,
        int wordLocal,
        int rootLocal
    ) {
        for (int word = 0; word < algorithm.keySize / 4; word++) {
            emitStreamWord(insns, rootLocal, keySeed(siteSeed, word, algorithm));
            insns.add(new VarInsnNode(Opcodes.ISTORE, wordLocal));
            for (int b = 0; b < 4; b++) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, keyLocal));
                JvmPassBytecode.pushInt(insns, word * 4 + b);
                insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
                JvmPassBytecode.pushInt(insns, 24 - b * 8);
                insns.add(new InsnNode(Opcodes.IUSHR));
                insns.add(new InsnNode(Opcodes.BASTORE));
            }
        }
    }

    private void emitCipherDecrypt(
        InsnList insns,
        int encryptedLocal,
        int keyLocal,
        int cipherLocal,
        int plainLocal,
        Algorithm algorithm
    ) {
        insns.add(new LdcInsnNode(algorithm.transformation));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "javax/crypto/Cipher",
            "getInstance",
            "(Ljava/lang/String;)Ljavax/crypto/Cipher;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cipherLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cipherLocal));
        JvmPassBytecode.pushInt(insns, Cipher.DECRYPT_MODE);
        insns.add(new TypeInsnNode(Opcodes.NEW, "javax/crypto/spec/SecretKeySpec"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyLocal));
        insns.add(new LdcInsnNode(algorithm.keyName));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESPECIAL,
            "javax/crypto/spec/SecretKeySpec",
            "<init>",
            "([BLjava/lang/String;)V",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "javax/crypto/Cipher",
            "init",
            "(ILjava/security/Key;)V",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cipherLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, encryptedLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "javax/crypto/Cipher",
            "doFinal",
            "([B)[B",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, plainLocal));
    }

    private void emitXorPlaintext(
        InsnList insns,
        long siteSeed,
        int length,
        int plainLocal,
        int wordLocal,
        int rootLocal
    ) {
        for (int offset = 0, word = 0; offset < length; offset += 4, word++) {
            emitStreamWord(insns, rootLocal, xorSeed(siteSeed, word));
            insns.add(new VarInsnNode(Opcodes.ISTORE, wordLocal));
            for (int b = 0; b < 4 && offset + b < length; b++) {
                insns.add(new VarInsnNode(Opcodes.ALOAD, plainLocal));
                JvmPassBytecode.pushInt(insns, offset + b);
                insns.add(new VarInsnNode(Opcodes.ALOAD, plainLocal));
                JvmPassBytecode.pushInt(insns, offset + b);
                insns.add(new InsnNode(Opcodes.BALOAD));
                insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
                JvmPassBytecode.pushInt(insns, 24 - b * 8);
                insns.add(new InsnNode(Opcodes.IUSHR));
                insns.add(new InsnNode(Opcodes.IXOR));
                insns.add(new InsnNode(Opcodes.BASTORE));
            }
        }
    }

    private int streamWord(int root, long seed) {
        int x = root ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x535453545245414DL));
        x += x << shift(seed, 3);
        x ^= x >>> shift(seed, 13);
        x *= nonZeroInt(JvmPassBytecode.mix(seed, 0x53545354524D554CL)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x5354535446494E31L));
    }

    private void emitStreamWord(InsnList insns, int rootLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x535453545245414DL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 3));
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 13));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53545354524D554CL)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5354535446494E31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStringLength(InsnList insns, int plainLocal) {
        for (int i = 0; i < 4; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, plainLocal));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new InsnNode(Opcodes.BALOAD));
            JvmPassBytecode.pushInt(insns, 0xFF);
            insns.add(new InsnNode(Opcodes.IAND));
            if (i != 3) {
                JvmPassBytecode.pushInt(insns, 24 - i * 8);
                insns.add(new InsnNode(Opcodes.ISHL));
            }
            if (i != 0) {
                insns.add(new InsnNode(Opcodes.IOR));
            }
        }
    }

    private int liveStringWord(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long seed
    ) {
        int x =
            (state.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x5354475541524431L))) +
            state.pathKey();
        x ^= x >>> shift(seed, 5);
        x = (x + state.blockKey()) ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x5354424C4F434B31L));
        x *= nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D554C544B31L)) | 1;
        x ^= (int) metadata.methodSeed() ^ (int) (metadata.methodSeed() >>> 32);
        x += state.pcToken() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x53545043544F4B31L));
        int idx =
            (x ^ state.state() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x535454414249584CL))) &
            (metadata.classKeyTable().values().length - 1);
        x ^= metadata.classKeyTable().values()[idx] +
            nonZeroInt(JvmPassBytecode.mix(seed, 0x535454414256414CL));
        x += x >>> shift(seed, 23);
        return x ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x535446494E414C31L));
    }

    private void emitLiveStringWord(
        InsnList insns,
        long seed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5354475541524431L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5354424C4F434B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D554C544B31L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53545043544F4B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x535454414249584CL)));
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
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x535454414256414CL)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(seed, 23));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x535446494E414C31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private long keySeed(long siteSeed, int word, Algorithm algorithm) {
        long h = JvmPassBytecode.mix(siteSeed ^ 0x5354524B45594B31L, word);
        return JvmPassBytecode.mix(h, algorithm.keySize);
    }

    private long rootSeed(long siteSeed) {
        return JvmPassBytecode.mix(siteSeed, 0x535452524F4F5431L);
    }

    private long xorSeed(long siteSeed, int word) {
        return JvmPassBytecode.mix(siteSeed ^ 0x535452584F524B31L, word);
    }

    private long siteSeed(
        long masterSeed,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffInstructionState state,
        int ordinal
    ) {
        long h = JvmPassBytecode.mix(masterSeed ^ 0x5354524F42464B31L, clazz.name().hashCode());
        h = JvmPassBytecode.mix(h, method.name().hashCode());
        h = JvmPassBytecode.mix(h, method.descriptor().hashCode());
        h = JvmPassBytecode.mix(h, state.blockIndex());
        h = JvmPassBytecode.mix(h, state.state());
        return JvmPassBytecode.mix(h, ordinal);
    }

    private int shift(long seed, int base) {
        return 1 + (int) ((seed >>> base) & 30L);
    }

    private int nonZeroInt(long value) {
        int v = (int) value;
        return v == 0 ? 0x3D6B2A4F : v;
    }

    private enum Algorithm {
        AES("AES", "AES/ECB/NoPadding", 16, 16),
        DES("DES", "DES/ECB/NoPadding", 8, 8);

        final String keyName;
        final String transformation;
        final int keySize;
        final int blockSize;

        Algorithm(String keyName, String transformation, int keySize, int blockSize) {
            this.keyName = keyName;
            this.transformation = transformation;
            this.keySize = keySize;
            this.blockSize = blockSize;
        }
    }
}
