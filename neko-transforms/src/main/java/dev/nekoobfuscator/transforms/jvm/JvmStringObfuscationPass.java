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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.DESKeySpec;
import javax.crypto.spec.SecretKeySpec;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TryCatchBlockNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Flow-keyed string literal protection for CFF-protected application code.
 */
public final class JvmStringObfuscationPass implements TransformPass {
    public static final String ID = "stringObfuscation";
    private static final String CIPHER_CACHES = "stringObfuscation.cipherCaches";
    private static final String STRING_SITE_CACHES = "stringObfuscation.stringSiteCaches";

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
            ControlFlowFlatteningPass.CffInstructionState state =
                metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException(
                    "stringObfuscation cannot bind CFF state for " + methodKey
                );
            }

            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String value) {
                long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal);
                Algorithm algorithm = algorithmFor(ordinal, siteSeed, metadata, state);
                byte[] encrypted = encryptPayload(value, siteSeed, metadata, state, algorithm);
                CipherCache cipherCache = ensureCipherCache(pctx, clazz, algorithm);
                StringSiteCache siteCache = ensureStringSiteCache(pctx, clazz, siteSeed, encrypted);
                InsnList replacement = new InsnList();
                emitDecodedString(replacement, encrypted.length, siteSeed, metadata, state, algorithm, cipherCache, siteCache, mn);
                JvmKeyDispatchPass.markGenerated(pctx, replacement);
                mn.instructions.insertBefore(insn, replacement);
                mn.instructions.remove(insn);
                transformed++;
                ordinal++;
                continue;
            }

            if (insn instanceof InvokeDynamicInsnNode indy && isStringConcatWithConstants(indy)) {
                ConcatPlan concat = concatPlan(indy);
                if (concat.hasStringConstants()) {
                    ConcatRewriteResult result = rewriteStringConcat(
                        pctx,
                        clazz,
                        method,
                        metadata,
                        state,
                        mn,
                        indy,
                        concat,
                        ordinal
                    );
                    JvmKeyDispatchPass.markGenerated(pctx, result.instructions());
                    mn.instructions.insertBefore(insn, result.instructions());
                    mn.instructions.remove(insn);
                    transformed += result.encryptedStrings();
                    ordinal += result.encryptedStrings();
                }
            }
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

    private ConcatRewriteResult rewriteStringConcat(
        PipelineContext pctx,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        MethodNode mn,
        InvokeDynamicInsnNode indy,
        ConcatPlan concat,
        int ordinal
    ) {
        Type[] args = Type.getArgumentTypes(indy.desc);
        int[] argLocals = new int[args.length];
        int nextLocal = mn.maxLocals;
        for (int i = 0; i < args.length; i++) {
            argLocals[i] = nextLocal;
            nextLocal += args[i].getSize();
        }
        mn.maxLocals = Math.max(mn.maxLocals, nextLocal);

        InsnList out = new InsnList();
        for (int i = args.length - 1; i >= 0; i--) {
            out.add(new VarInsnNode(args[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }

        Map<String, Integer> decodedStrings = new LinkedHashMap<>();
        for (ConcatPiece piece : concat.pieces()) {
            if (piece instanceof LiteralPiece literal && !literal.value().isEmpty()) {
                decodedStrings.putIfAbsent(literal.value(), -1);
            } else if (piece instanceof ConstPiece constant
                && constant.value() instanceof String value
                && !value.isEmpty()) {
                decodedStrings.putIfAbsent(value, -1);
            }
        }

        int encrypted = 0;
        for (String value : new ArrayList<>(decodedStrings.keySet())) {
            int local = mn.maxLocals++;
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal + encrypted);
            Algorithm algorithm = algorithmFor(ordinal + encrypted, siteSeed, metadata, state);
            byte[] payload = encryptPayload(value, siteSeed, metadata, state, algorithm);
            CipherCache cipherCache = ensureCipherCache(pctx, clazz, algorithm);
            StringSiteCache siteCache = ensureStringSiteCache(pctx, clazz, siteSeed, payload);
            emitDecodedString(out, payload.length, siteSeed, metadata, state, algorithm, cipherCache, siteCache, mn);
            out.add(new VarInsnNode(Opcodes.ASTORE, local));
            decodedStrings.put(value, local);
            encrypted++;
        }

        List<Type> concatArgs = new ArrayList<>();
        int argIndex = 0;
        for (ConcatPiece piece : concat.pieces()) {
            if (piece instanceof ArgPiece) {
                Type type = args[argIndex];
                out.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), argLocals[argIndex]));
                concatArgs.add(type);
                argIndex++;
            } else if (piece instanceof LiteralPiece literal) {
                if (emitDecodedStringLoad(out, decodedStrings, literal.value())) {
                    concatArgs.add(Type.getType(String.class));
                }
            } else if (piece instanceof ConstPiece constant) {
                Type type = emitConcatConstant(out, decodedStrings, constant.value());
                if (type != null) {
                    concatArgs.add(type);
                }
            }
        }
        out.add(new InvokeDynamicInsnNode(
            "makeConcat",
            Type.getMethodDescriptor(
                Type.getType(String.class),
                concatArgs.toArray(Type[]::new)
            ),
            new Handle(
                Opcodes.H_INVOKESTATIC,
                "java/lang/invoke/StringConcatFactory",
                "makeConcat",
                "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/CallSite;",
                false
            )
        ));
        return new ConcatRewriteResult(out, encrypted);
    }

    private boolean emitDecodedStringLoad(
        InsnList insns,
        Map<String, Integer> decodedStrings,
        String value
    ) {
        if (value.isEmpty()) return false;
        Integer local = decodedStrings.get(value);
        if (local == null || local < 0) {
            throw new IllegalStateException("Missing decoded concat string local");
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, local));
        return true;
    }

    private Type emitConcatConstant(
        InsnList insns,
        Map<String, Integer> decodedStrings,
        Object value
    ) {
        if (value instanceof String string) {
            return emitDecodedStringLoad(insns, decodedStrings, string)
                ? Type.getType(String.class)
                : null;
        }
        emitConstant(insns, value);
        return valueType(value);
    }

    private void emitConstant(InsnList insns, Object value) {
        if (value instanceof Integer v) {
            JvmPassBytecode.pushInt(insns, v);
        } else if (value instanceof Long v) {
            JvmPassBytecode.pushLong(insns, v);
        } else if (value instanceof Float || value instanceof Double || value instanceof Type || value instanceof Handle) {
            insns.add(new LdcInsnNode(value));
        } else {
            throw new IllegalStateException(
                "Unsupported StringConcatFactory constant type: " +
                    (value == null ? "null" : value.getClass().getName())
            );
        }
    }

    private Type valueType(Object value) {
        if (value instanceof Integer) return Type.INT_TYPE;
        if (value instanceof Long) return Type.LONG_TYPE;
        if (value instanceof Float) return Type.FLOAT_TYPE;
        if (value instanceof Double) return Type.DOUBLE_TYPE;
        return Type.getType(Object.class);
    }

    private boolean isStringConcatWithConstants(InvokeDynamicInsnNode indy) {
        return indy.bsm != null
            && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
            && "makeConcatWithConstants".equals(indy.bsm.getName())
            && indy.bsmArgs.length > 0
            && indy.bsmArgs[0] instanceof String
            && Type.getReturnType(indy.desc).equals(Type.getType(String.class));
    }

    private ConcatPlan concatPlan(InvokeDynamicInsnNode indy) {
        String recipe = (String) indy.bsmArgs[0];
        Object[] constants = new Object[Math.max(0, indy.bsmArgs.length - 1)];
        System.arraycopy(indy.bsmArgs, 1, constants, 0, constants.length);
        List<ConcatPiece> pieces = new ArrayList<>();
        StringBuilder literal = new StringBuilder();
        int argCount = 0;
        int constCount = 0;
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001' || ch == '\u0002') {
                if (!literal.isEmpty()) {
                    pieces.add(new LiteralPiece(literal.toString()));
                    literal.setLength(0);
                }
                if (ch == '\u0001') {
                    pieces.add(new ArgPiece());
                    argCount++;
                } else {
                    if (constCount >= constants.length) {
                        throw new IllegalStateException("Malformed StringConcatFactory recipe constant count");
                    }
                    pieces.add(new ConstPiece(constants[constCount++]));
                }
            } else {
                literal.append(ch);
            }
        }
        if (!literal.isEmpty()) {
            pieces.add(new LiteralPiece(literal.toString()));
        }
        int descriptorArgs = Type.getArgumentTypes(indy.desc).length;
        if (argCount != descriptorArgs || constCount != constants.length) {
            throw new IllegalStateException("Malformed StringConcatFactory recipe argument count");
        }
        return new ConcatPlan(pieces);
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
        long seed = xorSeed(siteSeed);
        for (int offset = 0; offset < out.length; offset++) {
            int value = streamWord(root, seed, offset >>> 2);
            int shift = (3 - (offset & 3)) << 3;
            out[offset] = (byte) (value >>> shift);
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
        int encryptedLength,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        Algorithm algorithm,
        CipherCache cipherCache,
        StringSiteCache siteCache,
        MethodNode mn
    ) {
        int encryptedLocal = mn.maxLocals;
        int keyLocal = encryptedLocal + 1;
        int cipherLocal = keyLocal + 1;
        int plainLocal = cipherLocal + 1;
        int wordLocal = plainLocal + 1;
        int lengthLocal = wordLocal + 1;
        int rootLocal = lengthLocal + 1;
        int indexLocal = rootLocal + 1;
        int stringLocal = indexLocal + 1;
        int throwableLocal = stringLocal + 1;
        int fingerprintLocal = throwableLocal + 1;
        mn.maxLocals = Math.max(mn.maxLocals, fingerprintLocal + 2);

        emitLiveStringWord(insns, rootSeed(siteSeed), metadata, state);
        insns.add(new VarInsnNode(Opcodes.ISTORE, rootLocal));
        emitFingerprint(insns, siteSeed, rootLocal);
        insns.add(new VarInsnNode(Opcodes.LSTORE, fingerprintLocal));
        LabelNode cacheMiss = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            siteCache.owner(),
            siteCache.stringFieldName(),
            "Ljava/lang/String;"
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stringLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, cacheMiss));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            siteCache.owner(),
            siteCache.fingerprintFieldName(),
            "J"
        ));
        insns.add(new VarInsnNode(Opcodes.LLOAD, fingerprintLocal));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, cacheMiss));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));

        insns.add(cacheMiss);
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            siteCache.owner(),
            siteCache.payloadFieldName(),
            "[B"
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, encryptedLocal));
        JvmPassBytecode.pushInt(insns, algorithm.keySize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, keyLocal));
        emitFillKey(insns, siteSeed, algorithm, keyLocal, wordLocal, rootLocal);
        emitCipherDecrypt(insns, encryptedLocal, keyLocal, cipherLocal, plainLocal, throwableLocal, cipherCache, algorithm, mn);
        emitXorPlaintext(insns, siteSeed, encryptedLength, plainLocal, wordLocal, rootLocal, indexLocal);
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
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stringLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, fingerprintLocal));
        insns.add(new FieldInsnNode(
            Opcodes.PUTSTATIC,
            siteCache.owner(),
            siteCache.fingerprintFieldName(),
            "J"
        ));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new FieldInsnNode(
            Opcodes.PUTSTATIC,
            siteCache.owner(),
            siteCache.stringFieldName(),
            "Ljava/lang/String;"
        ));
        insns.add(done);
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

    private void emitFingerprint(InsnList insns, long siteSeed, int rootLocal) {
        emitStreamWord(insns, rootLocal, fingerprintSeed(siteSeed, 0));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitStreamWord(insns, rootLocal, fingerprintSeed(siteSeed, 1));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
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
        int throwableLocal,
        CipherCache cipherCache,
        Algorithm algorithm,
        MethodNode mn
    ) {
        LabelNode protectedStart = new LabelNode();
        LabelNode protectedEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();

        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            cipherCache.owner(),
            cipherCache.fieldName(),
            "Ljavax/crypto/Cipher;"
        ));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cipherLocal));
        insns.add(new InsnNode(Opcodes.MONITORENTER));
        insns.add(protectedStart);
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
        insns.add(protectedEnd);
        insns.add(new VarInsnNode(Opcodes.ALOAD, cipherLocal));
        insns.add(new InsnNode(Opcodes.MONITOREXIT));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(handler);
        insns.add(new VarInsnNode(Opcodes.ASTORE, throwableLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cipherLocal));
        insns.add(new InsnNode(Opcodes.MONITOREXIT));
        insns.add(new VarInsnNode(Opcodes.ALOAD, throwableLocal));
        insns.add(new InsnNode(Opcodes.ATHROW));
        insns.add(done);
        mn.tryCatchBlocks.add(new TryCatchBlockNode(protectedStart, protectedEnd, handler, null));
    }

    private void emitXorPlaintext(
        InsnList insns,
        long siteSeed,
        int length,
        int plainLocal,
        int wordLocal,
        int rootLocal,
        int indexLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, length);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, wordLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, plainLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, plainLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.BALOAD));
        emitStreamWord(insns, rootLocal, wordLocal, xorSeed(siteSeed));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.ISUB));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(end);
    }

    @SuppressWarnings("unchecked")
    private StringSiteCache ensureStringSiteCache(
        PipelineContext pctx,
        L1Class clazz,
        long siteSeed,
        byte[] encrypted
    ) {
        Map<String, StringSiteCache> caches = pctx.getPassData(STRING_SITE_CACHES);
        if (caches == null) {
            caches = new LinkedHashMap<>();
            pctx.putPassData(STRING_SITE_CACHES, caches);
        }
        String key = clazz.name() + ":" + Long.toUnsignedString(siteSeed, 36);
        StringSiteCache existing = caches.get(key);
        if (existing != null) return existing;

        long seed = JvmPassBytecode.mix(siteSeed ^ 0x5354525349544531L, clazz.name().hashCode());
        String base = "$" + Integer.toUnsignedString((int) seed, 36);
        String payloadField = uniqueFieldName(clazz, base + "p");
        String fingerprintField = uniqueFieldName(clazz, base + "f");
        String stringField = uniqueFieldName(clazz, base + "s");

        clazz.asmNode().fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            payloadField,
            "[B",
            null,
            null
        ));
        clazz.asmNode().fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
            fingerprintField,
            "J",
            null,
            null
        ));
        clazz.asmNode().fields.add(new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_VOLATILE | Opcodes.ACC_SYNTHETIC,
            stringField,
            "Ljava/lang/String;",
            null,
            null
        ));

        StringSiteCache cache = new StringSiteCache(clazz.name(), payloadField, fingerprintField, stringField);
        installPayloadInit(pctx, clazz, cache, encrypted);
        installInjectedFieldReflectionFilter(pctx, clazz, payloadField);
        installInjectedFieldReflectionFilter(pctx, clazz, fingerprintField);
        installInjectedFieldReflectionFilter(pctx, clazz, stringField);
        caches.put(key, cache);
        clazz.markDirty();
        return cache;
    }

    private void installPayloadInit(
        PipelineContext pctx,
        L1Class clazz,
        StringSiteCache cache,
        byte[] encrypted
    ) {
        MethodNode clinit = findOrCreateClassInit(clazz);
        InsnList init = new InsnList();
        emitByteArray(init, encrypted);
        init.add(new FieldInsnNode(
            Opcodes.PUTSTATIC,
            cache.owner(),
            cache.payloadFieldName(),
            "[B"
        ));
        JvmKeyDispatchPass.markGenerated(pctx, init);
        AbstractInsnNode first = firstReal(clinit);
        if (first == null) {
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            clinit.instructions.insertBefore(first, init);
        }
        clinit.maxStack = Math.max(clinit.maxStack, 2);
    }

    @SuppressWarnings("unchecked")
    private CipherCache ensureCipherCache(
        PipelineContext pctx,
        L1Class clazz,
        Algorithm algorithm
    ) {
        Map<String, Map<Algorithm, CipherCache>> caches = pctx.getPassData(CIPHER_CACHES);
        if (caches == null) {
            caches = new LinkedHashMap<>();
            pctx.putPassData(CIPHER_CACHES, caches);
        }
        Map<Algorithm, CipherCache> classCaches =
            caches.computeIfAbsent(clazz.name(), ignored -> new LinkedHashMap<>());
        CipherCache existing = classCaches.get(algorithm);
        if (existing != null) return existing;

        long seed = JvmPassBytecode.mix(
            pctx.masterSeed() ^ 0x535452434950484CL,
            clazz.name().hashCode() ^ algorithm.ordinal()
        );
        String fieldName = uniqueFieldName(clazz, "$" + Integer.toUnsignedString((int) seed, 36));
        FieldNode field = new FieldNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC,
            fieldName,
            "Ljavax/crypto/Cipher;",
            null,
            null
        );
        clazz.asmNode().fields.add(field);

        CipherCache cache = new CipherCache(clazz.name(), fieldName, algorithm);
        installCipherCacheInit(pctx, clazz, cache);
        installInjectedFieldReflectionFilter(pctx, clazz, fieldName);
        classCaches.put(algorithm, cache);
        clazz.markDirty();
        return cache;
    }

    private String uniqueFieldName(L1Class clazz, String base) {
        String candidate = base;
        int suffix = 0;
        while (hasAsmFieldName(clazz, candidate)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private boolean hasAsmFieldName(L1Class clazz, String name) {
        for (FieldNode field : clazz.asmNode().fields) {
            if (name.equals(field.name)) {
                return true;
            }
        }
        return false;
    }

    private void installCipherCacheInit(
        PipelineContext pctx,
        L1Class clazz,
        CipherCache cache
    ) {
        MethodNode clinit = findOrCreateClassInit(clazz);
        InsnList init = new InsnList();
        init.add(new LdcInsnNode(cache.algorithm().transformation));
        init.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "javax/crypto/Cipher",
            "getInstance",
            "(Ljava/lang/String;)Ljavax/crypto/Cipher;",
            false
        ));
        init.add(new FieldInsnNode(
            Opcodes.PUTSTATIC,
            cache.owner(),
            cache.fieldName(),
            "Ljavax/crypto/Cipher;"
        ));
        JvmKeyDispatchPass.markGenerated(pctx, init);
        AbstractInsnNode first = firstReal(clinit);
        if (first == null) {
            clinit.instructions.add(init);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            clinit.instructions.insertBefore(first, init);
        }
        clinit.maxStack = Math.max(clinit.maxStack, 2);
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

    private AbstractInsnNode firstReal(MethodNode method) {
        for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
            int type = insn.getType();
            if (type != AbstractInsnNode.LABEL && type != AbstractInsnNode.LINE && type != AbstractInsnNode.FRAME) {
                return insn;
            }
        }
        return null;
    }

    private void installInjectedFieldReflectionFilter(
        PipelineContext pctx,
        L1Class clazz,
        String fieldName
    ) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.instructions == null) continue;
            for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                if (!(insn instanceof MethodInsnNode call) || !isFieldReflectionCall(call)) continue;
                InsnList filter = injectedFieldFilter(method, clazz, fieldName);
                JvmKeyDispatchPass.markGenerated(pctx, filter);
                method.instructions.insert(call, filter);
            }
        }
    }

    private boolean isFieldReflectionCall(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/Class".equals(call.owner)
            && ("getFields".equals(call.name) || "getDeclaredFields".equals(call.name))
            && "()[Ljava/lang/reflect/Field;".equals(call.desc);
    }

    private InsnList injectedFieldFilter(
        MethodNode mn,
        L1Class clazz,
        String fieldName
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
        emitInjectedFieldTest(insns, fieldLocal, clazz, fieldName, skip);
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
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/util/Arrays",
            "copyOf",
            "([Ljava/lang/Object;I)[Ljava/lang/Object;",
            false
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/reflect/Field;"));
        mn.maxStack = Math.max(mn.maxStack, 6);
        return insns;
    }

    private void emitInjectedFieldTest(
        InsnList insns,
        int fieldLocal,
        L1Class clazz,
        String fieldName,
        LabelNode skip
    ) {
        LabelNode next = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Field",
            "getName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new LdcInsnNode(fieldName));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "equals",
            "(Ljava/lang/Object;)Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, next));
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Field",
            "getDeclaringClass",
            "()Ljava/lang/Class;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Class",
            "getName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new LdcInsnNode(clazz.name().replace('/', '.')));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "equals",
            "(Ljava/lang/Object;)Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        insns.add(next);
    }

    private int streamWord(int root, long seed) {
        return streamWord(root, seed, 0);
    }

    private int streamWord(int root, long seed, int wordIndex) {
        int x = root ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x535453545245414DL));
        x += wordIndex * nonZeroInt(JvmPassBytecode.mix(seed, 0x53545354494E4458L));
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

    private void emitStreamWord(InsnList insns, int rootLocal, int wordLocal, long seed) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x535453545245414DL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53545354494E4458L)));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
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
        x ^= derivedMethodKeyFold(metadata, state);
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
        emitDerivedMethodKeyFold(insns, metadata, state);
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

    private int derivedMethodKeyFold(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        int x = (state.guardKey() ^ state.pathKey()) + state.blockKey();
        x ^= (int) state.methodSalt();
        x ^= (int) metadata.methodSeed();
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

    private long keySeed(long siteSeed, int word, Algorithm algorithm) {
        long h = JvmPassBytecode.mix(siteSeed ^ 0x5354524B45594B31L, word);
        return JvmPassBytecode.mix(h, algorithm.keySize);
    }

    private long rootSeed(long siteSeed) {
        return JvmPassBytecode.mix(siteSeed, 0x535452524F4F5431L);
    }

    private long xorSeed(long siteSeed) {
        return JvmPassBytecode.mix(siteSeed ^ 0x535452584F524B31L, 0x425954455354524DL);
    }

    private long fingerprintSeed(long siteSeed, int word) {
        return JvmPassBytecode.mix(siteSeed ^ 0x5354524341434845L, word);
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

    private sealed interface ConcatPiece permits ArgPiece, LiteralPiece, ConstPiece {}

    private record ArgPiece() implements ConcatPiece {}

    private record LiteralPiece(String value) implements ConcatPiece {}

    private record ConstPiece(Object value) implements ConcatPiece {}

    private record ConcatPlan(List<ConcatPiece> pieces) {
        boolean hasStringConstants() {
            for (ConcatPiece piece : pieces) {
                if (piece instanceof LiteralPiece literal && !literal.value().isEmpty()) {
                    return true;
                }
                if (piece instanceof ConstPiece constant
                    && constant.value() instanceof String value
                    && !value.isEmpty()) {
                    return true;
                }
            }
            return false;
        }
    }

    private record ConcatRewriteResult(InsnList instructions, int encryptedStrings) {}

    private record CipherCache(String owner, String fieldName, Algorithm algorithm) {}

    private record StringSiteCache(
        String owner,
        String payloadFieldName,
        String fingerprintFieldName,
        String stringFieldName
    ) {}

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
