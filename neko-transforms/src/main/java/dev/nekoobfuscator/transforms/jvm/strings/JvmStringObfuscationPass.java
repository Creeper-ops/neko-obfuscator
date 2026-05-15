package dev.nekoobfuscator.transforms.jvm.strings;

import dev.nekoobfuscator.api.transform.IRLevel;
import dev.nekoobfuscator.api.transform.TransformContext;
import dev.nekoobfuscator.api.transform.TransformPass;
import dev.nekoobfuscator.api.transform.TransformPhase;
import dev.nekoobfuscator.core.ir.l1.L1Class;
import dev.nekoobfuscator.core.ir.l1.L1Method;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.util.JvmObfuscationCoverage;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import dev.nekoobfuscator.transforms.jvm.internal.JvmPassBytecode;
import dev.nekoobfuscator.transforms.jvm.cff.ControlFlowFlatteningPass;
import dev.nekoobfuscator.transforms.jvm.key.JvmKeyDispatchPass;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
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
    private static final String STRING_SITE_CACHES = "stringObfuscation.stringSiteCaches";
    private static final String STRING_KEY_TABLES = "stringObfuscation.stringKeyTables";
    private static final String STRING_DECODE_TAILS = "stringObfuscation.stringDecodeTails";
    private static final String STRING_CONCAT_HELPERS = "stringObfuscation.stringConcatHelpers";
    private static final String STRING_TAIL_DESC = "([Ljava/lang/Object;IJI)Ljava/lang/String;";
    private static final int STRING_PAYLOAD_TABLE_SLOT = 0;
    private static final int STRING_CACHE_TABLE_SLOT = 1;
    private static final int STRING_AES_CIPHER_SLOT = 2;
    private static final int STRING_DES_CIPHER_SLOT = 3;
    private static final int STRING_KEY_CELL_BASE_SLOT = 4;
    private static final int STRING_KEY_CELL_PAYLOAD_SLOT = 8;
    private static final int STRING_KEY_CELL_CACHE_SLOT = 9;
    private static final int STRING_KEY_CELL_LENGTH = 10;
    private static final long STRING_KEY_CELL_PAYLOAD_MASK = 0x5354525041594C44L;
    private static final long STRING_KEY_CELL_CACHE_MASK = 0x5354524341434845L;
    private static final long STRING_KEY_CELL_LENGTH_MASK = 0x5354524C454E3031L;
    private static final long STATIC_CHAR_STRIDE = 0x9E3779B97F4A7C15L;

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
        if (TransformGuards.isRuntimeClass(clazz)) return;
        if (
            TransformGuards.isGeneratedMethod(method) &&
            !Boolean.TRUE.equals(pctx.getPassData("stringObfuscation.hardenGeneratedHelpers"))
        ) return;
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
        boolean hasLoopStringCacheInit = false;
        boolean canUseCallerCarrierLocal = !"<clinit>".equals(method.name());
        int callerCarrierLocal = -1;
        InsnList callerCarrierInit = new InsnList();
        Set<AbstractInsnNode> loopInstructions = loopRegionInstructions(mn);
        InsnList loopStringCacheInit = new InsnList();
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
                StringKeyMaterial keyMaterial = stringKeyMaterial(pctx, clazz, siteSeed);
                Algorithm algorithm = algorithmFor(ordinal, siteSeed, metadata, state, keyMaterial);
                ByteLayer byteLayer = byteLayerFor(siteSeed, metadata, state, keyMaterial);
                byte[] encrypted = encryptPayload(value, siteSeed, metadata, state, algorithm, byteLayer, keyMaterial);
                StringSiteCache siteCache = ensureStringSiteCache(pctx, clazz, metadata, siteSeed, encrypted, keyMaterial, algorithm, byteLayer);
                if (canUseCallerCarrierLocal && callerCarrierLocal < 0) {
                    callerCarrierLocal = mn.maxLocals++;
                    callerCarrierInit.add(new FieldInsnNode(
                        Opcodes.GETSTATIC,
                        siteCache.owner(),
                        siteCache.keyTableFieldName(),
                        "[Ljava/lang/Object;"
                    ));
                    callerCarrierInit.add(new VarInsnNode(Opcodes.ASTORE, callerCarrierLocal));
                }
                InsnList replacement = new InsnList();
                if (loopInstructions.contains(insn)) {
                    int activeCarrierLocal = callerCarrierLocal;
                    int cacheLocal = mn.maxLocals++;
                    loopStringCacheInit.add(new InsnNode(Opcodes.ACONST_NULL));
                    loopStringCacheInit.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
                    hasLoopStringCacheInit = true;
                    emitLoopCachedString(replacement, cacheLocal, () -> emitDecodedStringCall(
                        pctx,
                        clazz,
                        replacement,
                        mn,
                        encrypted.length,
                        siteSeed,
                        metadata,
                        state,
                        algorithm,
                        byteLayer,
                        siteCache,
                        activeCarrierLocal
                    ));
                } else {
                    emitDecodedStringCall(
                        pctx,
                        clazz,
                        replacement,
                        mn,
                        encrypted.length,
                        siteSeed,
                        metadata,
                        state,
                        algorithm,
                        byteLayer,
                        siteCache,
                        callerCarrierLocal
                    );
                }
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
                    boolean loopSite = loopInstructions.contains(insn);
                    if (loopSite && canUseCallerCarrierLocal && callerCarrierLocal < 0) {
                        callerCarrierLocal = mn.maxLocals++;
                        callerCarrierInit.add(new FieldInsnNode(
                            Opcodes.GETSTATIC,
                            metadata.classKeyTable().owner(),
                            metadata.classKeyTable().objectFieldName(),
                            "[Ljava/lang/Object;"
                        ));
                        callerCarrierInit.add(new VarInsnNode(Opcodes.ASTORE, callerCarrierLocal));
                    }
                    ConcatRewriteResult result = rewriteStringConcat(
                        pctx,
                        clazz,
                        method,
                        metadata,
                        state,
                        mn,
                        indy,
                        concat,
                        ordinal,
                        loopSite,
                        loopSite && canUseCallerCarrierLocal ? callerCarrierLocal : -1,
                        loopStringCacheInit
                    );
                    JvmKeyDispatchPass.markGenerated(pctx, result.instructions());
                    mn.instructions.insertBefore(insn, result.instructions());
                    mn.instructions.remove(insn);
                    transformed += result.encryptedStrings();
                    ordinal += result.encryptedStrings();
                    hasLoopStringCacheInit |= result.usesLoopCache();
                }
            }
        }

        if (transformed > 0) {
            if (callerCarrierLocal >= 0) {
                JvmKeyDispatchPass.markGenerated(pctx, callerCarrierInit);
                mn.instructions.insert(callerCarrierInit);
            }
            if (hasLoopStringCacheInit) {
                JvmKeyDispatchPass.markGenerated(pctx, loopStringCacheInit);
                mn.instructions.insert(loopStringCacheInit);
            }
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
        int ordinal,
        boolean loopSite,
        int callerCarrierLocal,
        InsnList loopStringCacheInit
    ) {
        Type[] args = Type.getArgumentTypes(indy.desc);
        long helperSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal) ^ 0x5354524341543131L;
        List<String> externalStrings = concatStringConstants(concat);
        ConcatRewriteResult helper = installConcatHelper(
            pctx,
            clazz,
            method,
            metadata,
            state,
            indy,
            concat,
            ordinal,
            args,
            helperSeed,
            externalStrings
        );
        InsnList out = new InsnList();
        int externalized = 0;
        boolean usesLoopCache = false;
        for (String value : externalStrings) {
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal + externalized);
            StringKeyMaterial keyMaterial = stringKeyMaterial(pctx, clazz, siteSeed);
            Algorithm algorithm = algorithmFor(ordinal + externalized, siteSeed, metadata, state, keyMaterial);
            ByteLayer byteLayer = byteLayerFor(siteSeed, metadata, state, keyMaterial);
            byte[] payload = encryptPayload(value, siteSeed, metadata, state, algorithm, byteLayer, keyMaterial);
            StringSiteCache siteCache = ensureStringSiteCache(pctx, clazz, metadata, siteSeed, payload, keyMaterial, algorithm, byteLayer);
            if (loopSite) {
                int cacheLocal = mn.maxLocals++;
                loopStringCacheInit.add(new InsnNode(Opcodes.ACONST_NULL));
                loopStringCacheInit.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
                usesLoopCache = true;
                emitLoopCachedString(out, cacheLocal, () -> emitDecodedStringCall(
                    pctx,
                    clazz,
                    out,
                    mn,
                    payload.length,
                    siteSeed,
                    metadata,
                    state,
                    algorithm,
                    byteLayer,
                    siteCache,
                    callerCarrierLocal
                ));
            } else {
                emitDecodedStringCall(
                    pctx,
                    clazz,
                    out,
                    mn,
                    payload.length,
                    siteSeed,
                    metadata,
                    state,
                    algorithm,
                    byteLayer,
                    siteCache,
                    callerCarrierLocal
                );
            }
            externalized++;
        }
        out.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        out.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        out.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        out.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        out.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        out.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            clazz.name(),
            helper.helperName(),
            concatHelperDescriptor(args, externalStrings.size()),
            (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0
        ));
        return new ConcatRewriteResult(out, helper.encryptedStrings() + externalized, null, usesLoopCache);
    }

    private ConcatRewriteResult installConcatHelper(
        PipelineContext pctx,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        InvokeDynamicInsnNode indy,
        ConcatPlan concat,
        int ordinal,
        Type[] args,
        long helperSeed,
        List<String> externalStrings
    ) {
        int[] argLocals = new int[args.length];
        int nextLocal = 0;
        for (int i = 0; i < args.length; i++) {
            argLocals[i] = nextLocal;
            nextLocal += args[i].getSize();
        }
        Map<String, Integer> decodedStrings = new LinkedHashMap<>();
        for (String value : externalStrings) {
            decodedStrings.put(value, nextLocal++);
        }
        for (ConcatPiece piece : concat.pieces()) {
            if (piece instanceof LiteralPiece literal && !literal.value().isEmpty()) {
                decodedStrings.putIfAbsent(literal.value(), -1);
            } else if (piece instanceof ConstPiece constant
                && constant.value() instanceof String value
                && !value.isEmpty()) {
                decodedStrings.putIfAbsent(value, -1);
            }
        }
        boolean hasInternalDecodedStrings = decodedStrings.containsValue(-1);
        String helperDesc = concatHelperDescriptor(args, externalStrings.size());
        ConcatHelperCacheKey cacheKey = null;
        if (!hasInternalDecodedStrings) {
            cacheKey = new ConcatHelperCacheKey(
                clazz.name(),
                helperDesc,
                concatHelperCachePieces(concat, externalStrings),
                concatExternalStringPattern(externalStrings)
            );
            String cached = cachedConcatHelper(pctx, cacheKey);
            if (cached != null) {
                return new ConcatRewriteResult(null, 0, cached, false);
            }
        }
        int methodKeyLocal = nextLocal;
        nextLocal += 2;
        int guardLocal = nextLocal++;
        int pathKeyLocal = nextLocal++;
        int blockKeyLocal = nextLocal++;
        int pcLocal = nextLocal++;
        String name = uniqueMethodName(
            clazz,
            "__neko_strcat$" + Long.toUnsignedString(helperSeed, 36)
        );
        MethodNode helper = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name,
            helperDesc,
            null,
            null
        );
        ControlFlowFlatteningPass.CffMethodMetadata helperMetadata =
            new ControlFlowFlatteningPass.CffMethodMetadata(
                state.methodKey(),
                methodKeyLocal,
                guardLocal,
                pathKeyLocal,
                blockKeyLocal,
                pcLocal,
                -1,
                Set.of(),
                Map.of(),
                metadata.classKeyTable()
        );
        helper.maxLocals = nextLocal;
        helper.maxStack = 32;
        int helperCarrierLocal = helper.maxLocals++;
        int selectorArrayLocal = helper.maxLocals++;
        int classWordsLocal = helper.maxLocals++;
        int concatPredicateLocal = helper.maxLocals++;
        int concatResultLocal = helper.maxLocals++;
        InsnList helperCarrierInit = new InsnList();
        helperCarrierInit.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            metadata.classKeyTable().owner(),
            metadata.classKeyTable().objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        helperCarrierInit.add(new VarInsnNode(Opcodes.ASTORE, helperCarrierLocal));
        int encrypted = 0;
        for (String value : new ArrayList<>(decodedStrings.keySet())) {
            if (decodedStrings.get(value) != -1) continue;
            nextLocal = Math.max(nextLocal, helper.maxLocals);
            int local = nextLocal++;
            helper.maxLocals = nextLocal;
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal + encrypted);
            StringKeyMaterial keyMaterial = stringKeyMaterial(pctx, clazz, siteSeed);
            Algorithm algorithm = algorithmFor(ordinal + encrypted, siteSeed, metadata, state, keyMaterial);
            ByteLayer byteLayer = byteLayerFor(siteSeed, metadata, state, keyMaterial);
            byte[] payload = encryptPayload(value, siteSeed, metadata, state, algorithm, byteLayer, keyMaterial);
            StringSiteCache siteCache = ensureStringSiteCache(pctx, clazz, helperMetadata, siteSeed, payload, keyMaterial, algorithm, byteLayer);
            emitDecodedStringCall(
                pctx,
                clazz,
                helper.instructions,
                helper,
                payload.length,
                siteSeed,
                helperMetadata,
                state,
                algorithm,
                byteLayer,
                siteCache,
                helperCarrierLocal
            );
            nextLocal = Math.max(nextLocal, helper.maxLocals);
            helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, local));
            decodedStrings.put(value, local);
            encrypted++;
        }
        emitConcatCarrierDependency(
            helper.instructions,
            helperCarrierLocal,
            methodKeyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal,
            selectorArrayLocal,
            classWordsLocal,
            concatPredicateLocal
        );
        JvmKeyDispatchPass.markGenerated(pctx, helperCarrierInit);
        helper.instructions.insertBefore(helper.instructions.getFirst(), helperCarrierInit);

        List<Type> concatArgs = new ArrayList<>();
        int argIndex = 0;
        for (ConcatPiece piece : concat.pieces()) {
            if (piece instanceof ArgPiece) {
                Type type = args[argIndex];
                helper.instructions.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), argLocals[argIndex]));
                concatArgs.add(type);
                argIndex++;
            } else if (piece instanceof LiteralPiece literal) {
                if (emitDecodedStringLoad(helper.instructions, decodedStrings, literal.value())) {
                    concatArgs.add(Type.getType(String.class));
                }
            } else if (piece instanceof ConstPiece constant) {
                Type type = emitConcatConstant(helper.instructions, decodedStrings, constant.value());
                if (type != null) {
                    concatArgs.add(type);
                }
            }
        }
        helper.instructions.add(new InvokeDynamicInsnNode(
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
        helper.instructions.add(new VarInsnNode(Opcodes.ASTORE, concatResultLocal));
        LabelNode predicateZero = new LabelNode();
        LabelNode predicateDone = new LabelNode();
        helper.instructions.add(new VarInsnNode(Opcodes.ILOAD, concatPredicateLocal));
        helper.instructions.add(new JumpInsnNode(Opcodes.IFEQ, predicateZero));
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, concatResultLocal));
        helper.instructions.add(new JumpInsnNode(Opcodes.GOTO, predicateDone));
        helper.instructions.add(predicateZero);
        helper.instructions.add(new VarInsnNode(Opcodes.ALOAD, concatResultLocal));
        helper.instructions.add(predicateDone);
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        helper.maxLocals = Math.max(helper.maxLocals, nextLocal);
        helper.maxStack = 32;
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        clazz.markDirty();
        publishGeneratedStringHelperFlowKey(
            pctx,
            clazz.name(),
            name,
            helper.desc,
            methodKeyLocal
        );
        if (cacheKey != null) {
            cacheConcatHelper(pctx, cacheKey, name);
        }
        return new ConcatRewriteResult(null, encrypted, name, false);
    }

    private void emitConcatCarrierDependency(
        InsnList insns,
        int carrierLocal,
        int methodKeyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal,
        int selectorArrayLocal,
        int classWordsLocal,
        int predicateLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        JvmPassBytecode.pushInt(
            insns,
            ControlFlowFlatteningPass.CLASS_KEY_WORDS_SELECTOR_SLOT
        );
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, selectorArrayLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, selectorArrayLocal));
        emitConcatCarrierSelectorIndex(
            insns,
            methodKeyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal
        );
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, classWordsLocal));
        emitConcatCarrierSelectorIndex(
            insns,
            methodKeyLocal,
            guardLocal,
            pathKeyLocal,
            blockKeyLocal,
            pcLocal
        );
        insns.add(new VarInsnNode(Opcodes.ISTORE, predicateLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classWordsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, predicateLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classWordsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, predicateLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, predicateLocal));
    }

    private void emitConcatCarrierSelectorIndex(
        InsnList insns,
        int methodKeyLocal,
        int guardLocal,
        int pathKeyLocal,
        int blockKeyLocal,
        int pcLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, methodKeyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.LLOAD, methodKeyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, guardLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pathKeyLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, blockKeyLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, pcLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private List<ConcatCachePiece> concatHelperCachePieces(
        ConcatPlan concat,
        List<String> externalStrings
    ) {
        Map<String, Integer> externalSlotByValue = new LinkedHashMap<>();
        for (int i = 0; i < externalStrings.size(); i++) {
            externalSlotByValue.put(externalStrings.get(i), i);
        }
        List<ConcatCachePiece> pieces = new ArrayList<>();
        for (ConcatPiece piece : concat.pieces()) {
            if (piece instanceof ArgPiece) {
                pieces.add(new CacheArgPiece());
            } else if (piece instanceof LiteralPiece literal) {
                if (!literal.value().isEmpty()) {
                    pieces.add(new CacheStringPiece(externalSlotByValue.get(literal.value())));
                }
            } else if (piece instanceof ConstPiece constant) {
                Object value = constant.value();
                if (value instanceof String stringValue) {
                    if (!stringValue.isEmpty()) {
                        pieces.add(new CacheStringPiece(externalSlotByValue.get(stringValue)));
                    }
                } else {
                    pieces.add(new CacheConstPiece(value));
                }
            }
        }
        return List.copyOf(pieces);
    }

    private List<Integer> concatExternalStringPattern(List<String> externalStrings) {
        Map<String, Integer> patternByValue = new LinkedHashMap<>();
        List<Integer> pattern = new ArrayList<>(externalStrings.size());
        for (String value : externalStrings) {
            Integer id = patternByValue.get(value);
            if (id == null) {
                id = patternByValue.size();
                patternByValue.put(value, id);
            }
            pattern.add(id);
        }
        return List.copyOf(pattern);
    }

    private String concatHelperDescriptor(Type[] args) {
        return concatHelperDescriptor(args, 0);
    }

    @SuppressWarnings("unchecked")
    private String cachedConcatHelper(
        PipelineContext pctx,
        ConcatHelperCacheKey key
    ) {
        Map<ConcatHelperCacheKey, String> helpers =
            pctx.getPassData(STRING_CONCAT_HELPERS);
        if (helpers == null) return null;
        return helpers.get(key);
    }

    @SuppressWarnings("unchecked")
    private void cacheConcatHelper(
        PipelineContext pctx,
        ConcatHelperCacheKey key,
        String helperName
    ) {
        Map<ConcatHelperCacheKey, String> helpers =
            pctx.getPassData(STRING_CONCAT_HELPERS);
        if (helpers == null) {
            helpers = new LinkedHashMap<>();
            pctx.putPassData(STRING_CONCAT_HELPERS, helpers);
        }
        helpers.putIfAbsent(key, helperName);
    }

    private String concatHelperDescriptor(Type[] args, int externalStrings) {
        Type[] helperArgs = new Type[args.length + externalStrings + 5];
        System.arraycopy(args, 0, helperArgs, 0, args.length);
        for (int i = 0; i < externalStrings; i++) {
            helperArgs[args.length + i] = Type.getType(String.class);
        }
        int keyOffset = args.length + externalStrings;
        helperArgs[keyOffset] = Type.LONG_TYPE;
        helperArgs[keyOffset + 1] = Type.INT_TYPE;
        helperArgs[keyOffset + 2] = Type.INT_TYPE;
        helperArgs[keyOffset + 3] = Type.INT_TYPE;
        helperArgs[keyOffset + 4] = Type.INT_TYPE;
        return Type.getMethodDescriptor(Type.getType(String.class), helperArgs);
    }

    private List<String> concatStringConstants(ConcatPlan concat) {
        Map<String, Boolean> values = new LinkedHashMap<>();
        for (ConcatPiece piece : concat.pieces()) {
            if (piece instanceof LiteralPiece literal && !literal.value().isEmpty()) {
                values.putIfAbsent(literal.value(), Boolean.TRUE);
            } else if (piece instanceof ConstPiece constant
                && constant.value() instanceof String value
                && !value.isEmpty()) {
                values.putIfAbsent(value, Boolean.TRUE);
            }
        }
        return new ArrayList<>(values.keySet());
    }

    private void emitLoopCachedString(
        InsnList insns,
        int cacheLocal,
        Runnable decodeEmitter
    ) {
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, done));
        insns.add(new InsnNode(Opcodes.POP));
        decodeEmitter.run();
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
        insns.add(done);
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
            } else if (node instanceof org.objectweb.asm.tree.LookupSwitchInsnNode lookup) {
                markBackwardRegion(loop, nodes, indexByNode, i, lookup.dflt);
                for (LabelNode label : lookup.labels) {
                    markBackwardRegion(loop, nodes, indexByNode, i, label);
                }
            } else if (node instanceof org.objectweb.asm.tree.TableSwitchInsnNode table) {
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
        ControlFlowFlatteningPass.CffInstructionState state,
        StringKeyMaterial keyMaterial
    ) {
        if ((ordinal & 1) == 0) return Algorithm.AES;
        int root = stringRoot(metadata, state, siteSeed, keyMaterial);
        byte[] key = keyBytes(root, siteSeed, Algorithm.DES, stringStreamFlow(root));
        try {
            return DESKeySpec.isWeak(key, 0) ? Algorithm.AES : Algorithm.DES;
        } catch (InvalidKeyException ex) {
            return Algorithm.AES;
        }
    }

    private ByteLayer byteLayerFor(
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        StringKeyMaterial keyMaterial
    ) {
        int selector = stringKeyMixedWord(
            liveStringWord(metadata, state, byteLayerSeed(siteSeed)),
            siteSeed ^ 0x5354524C41595231L,
            keyMaterial
        ) ^
            (int) (siteSeed >>> 32);
        return switch (selector & 3) {
            case 0 -> ByteLayer.ADD;
            case 1 -> ByteLayer.SUBTRACT;
            case 2 -> ByteLayer.XOR;
            default -> ByteLayer.ADD;
        };
    }

    private byte[] encryptPayload(
        String value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        Algorithm algorithm,
        ByteLayer byteLayer,
        StringKeyMaterial keyMaterial
    ) {
        int root = stringRoot(metadata, state, siteSeed, keyMaterial);
        long streamFlow = stringStreamFlow(root);
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
        byte[] xor = xorBytes(root, siteSeed, payload.length, streamFlow);
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (payload[i] ^ xor[i]);
        }
        applyByteLayer(payload, root, siteSeed, byteLayer, streamFlow);
        try {
            Cipher cipher = Cipher.getInstance(algorithm.transformation);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes(root, siteSeed, algorithm, streamFlow), algorithm.keyName));
            return cipher.doFinal(payload);
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to encrypt string literal with " + algorithm.keyName, ex);
        }
    }

    private byte[] keyBytes(
        int root,
        long siteSeed,
        Algorithm algorithm,
        long streamFlow
    ) {
        byte[] out = new byte[algorithm.keySize];
        long seed = keyStreamSeed(siteSeed, algorithm);
        for (int word = 0; word < out.length / 4; word++) {
            int value = streamWord(root, seed, word, streamFlow);
            writeWord(out, word * 4, value);
        }
        return out;
    }

    private byte[] xorBytes(
        int root,
        long siteSeed,
        int length,
        long streamFlow
    ) {
        byte[] out = new byte[length];
        long seed = xorSeed(siteSeed);
        for (int offset = 0; offset < out.length; offset++) {
            int value = streamWord(root, seed, offset >>> 2, streamFlow);
            int shift = (3 - (offset & 3)) << 3;
            out[offset] = (byte) (value >>> shift);
        }
        return out;
    }

    private void applyByteLayer(
        byte[] payload,
        int root,
        long siteSeed,
        ByteLayer byteLayer,
        long streamFlow
    ) {
        long seed = byteLayerSeed(siteSeed);
        for (int offset = 0; offset < payload.length; offset++) {
            int value = streamWord(root, seed, offset >>> 2, streamFlow);
            int shift = (3 - (offset & 3)) << 3;
            int mask = (value >>> shift) & 0xFF;
            int b = payload[offset] & 0xFF;
            payload[offset] = switch (byteLayer) {
                case ADD -> (byte) (b + mask);
                case SUBTRACT -> (byte) (b - mask);
                case XOR -> (byte) (b ^ mask);
            };
        }
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
        ByteLayer byteLayer,
        StringSiteCache siteCache,
        StringDecodeTail tail,
        int keyTableLocal,
        MethodNode mn
    ) {
        if (!siteCache.hasKeyMaterial()) {
            throw new IllegalStateException("shared string decode tail requires key material table");
        }
        if (keyTableLocal >= 0) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyTableLocal));
        } else {
            insns.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                siteCache.owner(),
                siteCache.keyTableFieldName(),
                "[Ljava/lang/Object;"
            ));
        }
        emitLiveStringWord(insns, rootSeed(siteSeed), metadata, state);
        insns.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        JvmPassBytecode.pushInt(insns, siteCache.keySlot());
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            tail.owner(),
            tail.name(),
            STRING_TAIL_DESC,
            tail.interfaceOwner()
        ));
    }

    private void emitDecodedStringCall(
        PipelineContext pctx,
        L1Class clazz,
        InsnList caller,
        MethodNode mn,
        int encryptedLength,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        Algorithm algorithm,
        ByteLayer byteLayer,
        StringSiteCache siteCache
    ) {
        emitDecodedStringCall(
            pctx,
            clazz,
            caller,
            mn,
            encryptedLength,
            siteSeed,
            metadata,
            state,
            algorithm,
            byteLayer,
            siteCache,
            -1
        );
    }

    private void emitDecodedStringCall(
        PipelineContext pctx,
        L1Class clazz,
        InsnList caller,
        MethodNode mn,
        int encryptedLength,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        Algorithm algorithm,
        ByteLayer byteLayer,
        StringSiteCache siteCache,
        int carrierLocal
    ) {
        if (!siteCache.hasKeyMaterial()) {
            throw new IllegalStateException(
                "stringObfuscation requires dynamic key material table for decode helper in " + clazz.name()
            );
        }
        StringDecodeTail tail = ensureStringDecodeTail(
            pctx,
            clazz
        );
        emitDecodedString(
            caller,
            encryptedLength,
            siteSeed,
            metadata,
            state,
            algorithm,
            byteLayer,
            siteCache,
            tail,
            carrierLocal,
            mn
        );
    }

    @SuppressWarnings("unchecked")
    private StringDecodeTail ensureStringDecodeTail(
        PipelineContext pctx,
        L1Class clazz
    ) {
        Map<String, StringDecodeTail> tails = pctx.getPassData(STRING_DECODE_TAILS);
        if (tails == null) {
            tails = new LinkedHashMap<>();
            pctx.putPassData(STRING_DECODE_TAILS, tails);
        }
        String key = packageName(clazz.name());
        StringDecodeTail existing = tails.get(key);
        if (existing != null) return existing;

        boolean interfaceOwner = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
        String name = uniqueMethodName(
            clazz,
            "__neko_strtail$"
        );
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        access |= interfaceOwner ? Opcodes.ACC_PUBLIC : 0;
        MethodNode helper = new MethodNode(
            access,
            name,
            STRING_TAIL_DESC,
            null,
            null
        );
        helper.maxLocals = 12;
        helper.maxStack = 32;
        emitDecodedStringTail(
            helper.instructions,
            helper
        );
        helper.instructions.add(new InsnNode(Opcodes.ARETURN));
        JvmKeyDispatchPass.markGenerated(pctx, helper.instructions);
        clazz.asmNode().methods.add(helper);
        clazz.markDirty();
        publishGeneratedStringHelperFlowKey(pctx, clazz.name(), name, STRING_TAIL_DESC, 2);

        StringDecodeTail tail = new StringDecodeTail(clazz.name(), name, interfaceOwner);
        tails.put(key, tail);
        return tail;
    }

    private void emitDecodedStringTail(
        InsnList insns,
        MethodNode mn
    ) {
        int carrierLocal = 0;
        int rootLocal = 1;
        int flowLocal = 2;
        int keySlotLocal = 4;
        int payloadSlotLocal = 5;
        int cacheSlotLocal = 6;
        int encryptedLengthLocal = 7;
        int keySeedLocal = 8;
        int byteLayerSeedLocal = 10;
        int xorSeedLocal = 12;
        int siteSeedLocal = 14;
        int encryptedLocal = 16;
        int keyLocal = 17;
        int cipherLocal = 18;
        int plainLocal = 19;
        int wordLocal = 20;
        int lengthLocal = 21;
        int indexLocal = 22;
        int stringLocal = 23;
        int throwableLocal = 24;
        int keyCellLocal = 25;
        int oldEpochLocal = 26;
        int nextEpochLocal = 27;
        int fingerprintLocal = 28;
        int selectorLocal = 30;
        int streamFlowLocal = 31;
        int keyTableLocal = 33;
        int slotSelectorLocal = 34;
        mn.maxLocals = Math.max(mn.maxLocals, 35);

        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        emitRuntimeStringMaterialSlotSelection(insns, carrierLocal, rootLocal, flowLocal, keySlotLocal);
        insns.add(new VarInsnNode(Opcodes.ISTORE, slotSelectorLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotSelectorLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, keyTableLocal));

        emitRuntimeStringKeyCellLoad(insns, keyTableLocal, keySlotLocal);
        insns.add(new VarInsnNode(Opcodes.ASTORE, keyCellLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, oldEpochLocal));
        emitRuntimeStringSiteSeedLoad(insns, keyCellLocal, oldEpochLocal, siteSeedLocal);
        emitRuntimeStringTailSelectorLoad(insns, keyCellLocal, oldEpochLocal, selectorLocal);
        emitRuntimeStringSiteMetadataLoad(
            insns,
            keyCellLocal,
            oldEpochLocal,
            siteSeedLocal,
            STRING_KEY_CELL_PAYLOAD_SLOT,
            STRING_KEY_CELL_PAYLOAD_MASK,
            payloadSlotLocal
        );
        emitRuntimeStringSiteMetadataLoad(
            insns,
            keyCellLocal,
            oldEpochLocal,
            siteSeedLocal,
            STRING_KEY_CELL_CACHE_SLOT,
            STRING_KEY_CELL_CACHE_MASK,
            cacheSlotLocal
        );
        emitRuntimeStringSiteMetadataLoad(
            insns,
            keyCellLocal,
            oldEpochLocal,
            siteSeedLocal,
            STRING_KEY_CELL_LENGTH,
            STRING_KEY_CELL_LENGTH_MASK,
            encryptedLengthLocal
        );
        emitRuntimeStringKeyMixedWord(insns, rootLocal, keyCellLocal, oldEpochLocal, siteSeedLocal);
        insns.add(new VarInsnNode(Opcodes.ISTORE, rootLocal));
        emitRuntimeStringStreamFlow(insns, rootLocal);
        insns.add(new VarInsnNode(Opcodes.LSTORE, streamFlowLocal));
        emitRuntimeStringKeyCellUpdate(insns, keyCellLocal, rootLocal, nextEpochLocal, oldEpochLocal, siteSeedLocal, selectorLocal);
        emitRuntimeFingerprint(insns, rootLocal, streamFlowLocal, siteSeedLocal, keySeedLocal);
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, fingerprintLocal));
        emitRuntimeDerivedSeed(insns, siteSeedLocal, 0x5354524C41595231L, 0x425954454C415952L, byteLayerSeedLocal);
        emitRuntimeDerivedSeed(insns, siteSeedLocal, 0x535452584F524B31L, 0x425954455354524DL, xorSeedLocal);

        LabelNode cacheMiss = new LabelNode();
        LabelNode done = new LabelNode();
        emitRuntimeStringCacheTableLoad(insns, keyTableLocal);
        emitStringCacheIndex(insns, cacheSlotLocal, false);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/String"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stringLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, cacheMiss));
        emitRuntimeStringCacheTableLoad(insns, keyTableLocal);
        emitStringCacheIndex(insns, cacheSlotLocal, true);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "java/lang/Long"));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Long",
            "longValue",
            "()J",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.LLOAD, fingerprintLocal));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, cacheMiss));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));

        insns.add(cacheMiss);
        emitRuntimeStringPayloadTableLoad(insns, keyTableLocal);
        insns.add(new VarInsnNode(Opcodes.ILOAD, payloadSlotLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[B"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, encryptedLocal));
        emitAlgorithmCipherDecrypt(
            insns,
            keyTableLocal,
            selectorLocal,
            encryptedLocal,
            keyLocal,
            cipherLocal,
            plainLocal,
            throwableLocal,
            wordLocal,
            rootLocal,
            streamFlowLocal,
            indexLocal,
            siteSeedLocal,
            keySeedLocal,
            mn
        );
        emitByteLayerDecode(insns, selectorLocal, plainLocal, wordLocal, rootLocal, streamFlowLocal, indexLocal, encryptedLengthLocal, byteLayerSeedLocal);
        emitXorPlaintext(insns, plainLocal, wordLocal, rootLocal, streamFlowLocal, indexLocal, encryptedLengthLocal, xorSeedLocal);
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
        emitRuntimeStringCacheTableLoad(insns, keyTableLocal);
        emitStringCacheIndex(insns, cacheSlotLocal, true);
        insns.add(new VarInsnNode(Opcodes.LLOAD, fingerprintLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Long",
            "valueOf",
            "(J)Ljava/lang/Long;",
            false
        ));
        insns.add(new InsnNode(Opcodes.AASTORE));
        emitRuntimeStringCacheTableLoad(insns, keyTableLocal);
        emitStringCacheIndex(insns, cacheSlotLocal, false);
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(done);
    }

    private void emitStringCacheIndex(InsnList insns, int cacheSlotLocal, boolean fingerprint) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, cacheSlotLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IMUL));
        if (fingerprint) {
            JvmPassBytecode.pushInt(insns, 1);
            insns.add(new InsnNode(Opcodes.IADD));
        }
    }

    private void emitRuntimeStringMaterialSlotSelection(
        InsnList insns,
        int carrierLocal,
        int rootLocal,
        int flowLocal,
        int keySlotLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.STRING_MATERIAL_SELECTOR_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, keySlotLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
    }

    private void emitAlgorithmCipherDecrypt(
        InsnList insns,
        int keyTableLocal,
        int selectorLocal,
        int encryptedLocal,
        int keyLocal,
        int cipherLocal,
        int plainLocal,
        int throwableLocal,
        int wordLocal,
        int rootLocal,
        int flowLocal,
        int indexLocal,
        int siteSeedLocal,
        int keySeedLocal,
        MethodNode mn
    ) {
        LabelNode des = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, selectorLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, Algorithm.DES.ordinal());
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, des));
        emitRuntimeDerivedSeed(insns, siteSeedLocal, 0x5354524B45594C31L, Algorithm.AES.keySize, keySeedLocal);
        JvmPassBytecode.pushInt(insns, Algorithm.AES.keySize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, keyLocal));
        emitFillKey(insns, Algorithm.AES, selectorLocal, keyLocal, wordLocal, rootLocal, flowLocal, indexLocal, keySeedLocal);
        emitCipherDecryptTail(insns, Algorithm.AES, keyTableLocal, encryptedLocal, keyLocal, cipherLocal, plainLocal, throwableLocal, mn);
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(des);
        emitRuntimeDerivedSeed(insns, siteSeedLocal, 0x5354524B45594C31L, Algorithm.DES.keySize, keySeedLocal);
        JvmPassBytecode.pushInt(insns, Algorithm.DES.keySize);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, keyLocal));
        emitFillKey(insns, Algorithm.DES, selectorLocal, keyLocal, wordLocal, rootLocal, flowLocal, indexLocal, keySeedLocal);
        emitCipherDecryptTail(insns, Algorithm.DES, keyTableLocal, encryptedLocal, keyLocal, cipherLocal, plainLocal, throwableLocal, mn);
        insns.add(done);
    }

    private void emitFillKey(
        InsnList insns,
        Algorithm algorithm,
        int selectorLocal,
        int keyLocal,
        int wordLocal,
        int rootLocal,
        int flowLocal,
        int indexLocal,
        int keySeedLocal
    ) {
        LabelNode reverse = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, selectorLocal));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFNE, reverse));
        emitFillKeyLoop(insns, algorithm, false, keyLocal, wordLocal, rootLocal, flowLocal, indexLocal, keySeedLocal);
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(reverse);
        emitFillKeyLoop(insns, algorithm, true, keyLocal, wordLocal, rootLocal, flowLocal, indexLocal, keySeedLocal);
        insns.add(done);
    }

    private void emitFillKeyLoop(
        InsnList insns,
        Algorithm algorithm,
        boolean reverseBytes,
        int keyLocal,
        int wordLocal,
        int rootLocal,
        int flowLocal,
        int indexLocal,
        int keySeedLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, algorithm.keySize / 4);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        emitRuntimeStreamWord(insns, rootLocal, indexLocal, keySeedLocal, flowLocal);
        insns.add(new VarInsnNode(Opcodes.ISTORE, wordLocal));
        for (int step = 0; step < 4; step++) {
            int b = reverseBytes ? 3 - step : step;
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
            JvmPassBytecode.pushInt(insns, 4);
            insns.add(new InsnNode(Opcodes.IMUL));
            JvmPassBytecode.pushInt(insns, b);
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
            JvmPassBytecode.pushInt(insns, 24 - b * 8);
            insns.add(new InsnNode(Opcodes.IUSHR));
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(end);
    }

    private void emitCipherDecryptTail(
        InsnList insns,
        Algorithm algorithm,
        int keyTableLocal,
        int encryptedLocal,
        int keyLocal,
        int cipherLocal,
        int plainLocal,
        int throwableLocal,
        MethodNode mn
    ) {
        LabelNode protectedStart = new LabelNode();
        LabelNode protectedEnd = new LabelNode();
        LabelNode handler = new LabelNode();
        LabelNode done = new LabelNode();

        emitRuntimeStringCipherLoad(insns, keyTableLocal, algorithm);
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cipherLocal));
        insns.add(new InsnNode(Opcodes.MONITORENTER));
        insns.add(protectedStart);
        insns.add(new VarInsnNode(Opcodes.ALOAD, cipherLocal));
        JvmPassBytecode.pushInt(insns, Cipher.DECRYPT_MODE);
        insns.add(new TypeInsnNode(Opcodes.NEW, "javax/crypto/spec/SecretKeySpec"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyLocal));
        emitStaticString(
            insns,
            algorithm.keyName,
            JvmPassBytecode.mix(0x535452544B455931L, algorithm.ordinal())
        );
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

    private void emitByteLayerDecode(
        InsnList insns,
        int selectorLocal,
        int plainLocal,
        int wordLocal,
        int rootLocal,
        int flowLocal,
        int indexLocal,
        int encryptedLengthLocal,
        int byteLayerSeedLocal
    ) {
        LabelNode subtract = new LabelNode();
        LabelNode xor = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, selectorLocal));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IUSHR));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, ByteLayer.SUBTRACT.ordinal());
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, subtract));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, ByteLayer.XOR.ordinal());
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPEQ, xor));
        insns.add(new InsnNode(Opcodes.POP));
        emitByteLayerDecodeLoop(insns, ByteLayer.ADD, plainLocal, wordLocal, rootLocal, flowLocal, indexLocal, encryptedLengthLocal, byteLayerSeedLocal);
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(subtract);
        insns.add(new InsnNode(Opcodes.POP));
        emitByteLayerDecodeLoop(insns, ByteLayer.SUBTRACT, plainLocal, wordLocal, rootLocal, flowLocal, indexLocal, encryptedLengthLocal, byteLayerSeedLocal);
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(xor);
        insns.add(new InsnNode(Opcodes.POP));
        emitByteLayerDecodeLoop(insns, ByteLayer.XOR, plainLocal, wordLocal, rootLocal, flowLocal, indexLocal, encryptedLengthLocal, byteLayerSeedLocal);
        insns.add(done);
    }

    private void emitByteLayerDecodeLoop(
        InsnList insns,
        ByteLayer byteLayer,
        int plainLocal,
        int wordLocal,
        int rootLocal,
        int flowLocal,
        int indexLocal,
        int encryptedLengthLocal,
        int byteLayerSeedLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, encryptedLengthLocal));
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
        emitRuntimeStreamWord(insns, rootLocal, wordLocal, byteLayerSeedLocal, flowLocal);
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.ISUB));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IUSHR));
        switch (byteLayer) {
            case ADD -> insns.add(new InsnNode(Opcodes.ISUB));
            case SUBTRACT -> insns.add(new InsnNode(Opcodes.IADD));
            case XOR -> insns.add(new InsnNode(Opcodes.IXOR));
        }
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(end);
    }

    private void emitXorPlaintext(
        InsnList insns,
        int plainLocal,
        int wordLocal,
        int rootLocal,
        int flowLocal,
        int indexLocal,
        int encryptedLengthLocal,
        int xorSeedLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, encryptedLengthLocal));
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
        emitRuntimeStreamWord(insns, rootLocal, wordLocal, xorSeedLocal, flowLocal);
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

    private void emitRuntimeStringPayloadTableLoad(InsnList insns, int keyTableLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyTableLocal));
        JvmPassBytecode.pushInt(insns, STRING_PAYLOAD_TABLE_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
    }

    private void emitRuntimeStringCacheTableLoad(InsnList insns, int keyTableLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyTableLocal));
        JvmPassBytecode.pushInt(insns, STRING_CACHE_TABLE_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/Object;"));
    }

    private void emitRuntimeStringCipherLoad(InsnList insns, int keyTableLocal, Algorithm algorithm) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyTableLocal));
        JvmPassBytecode.pushInt(insns, stringCipherSlot(algorithm));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "javax/crypto/Cipher"));
    }

    private int stringCipherSlot(Algorithm algorithm) {
        return switch (algorithm) {
            case AES -> STRING_AES_CIPHER_SLOT;
            case DES -> STRING_DES_CIPHER_SLOT;
        };
    }

    private void emitRuntimeStringKeyCellLoad(InsnList insns, int keyTableLocal, int keySlotLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyTableLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, keySlotLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
    }

    private void emitRuntimeStringSiteSeedLoad(
        InsnList insns,
        int keyCellLocal,
        int epochLocal,
        int siteSeedLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 5);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitRuntimeStringSiteTokenCellMask(insns, epochLocal, 0);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 6);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitRuntimeStringSiteTokenCellMask(insns, epochLocal, 1);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteSeedLocal));
    }

    private void emitRuntimeStringTailSelectorLoad(
        InsnList insns,
        int keyCellLocal,
        int epochLocal,
        int selectorLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 7);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitRuntimeStringTailSelectorCellMask(insns, epochLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, selectorLocal));
    }

    private void emitRuntimeStringKeyMixedWord(
        InsnList insns,
        int liveWordLocal,
        int keyCellLocal,
        int epochLocal,
        int siteSeedLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, liveWordLocal));
        emitRuntimeDecodedStringKeyWord(insns, keyCellLocal, epochLocal, siteSeedLocal, 0);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, liveWordLocal));
        emitRuntimeShift(insns, siteSeedLocal, 7);
        insns.add(new InsnNode(Opcodes.ISHL));
        emitRuntimeDecodedStringKeyWord(insns, keyCellLocal, epochLocal, siteSeedLocal, 1);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, siteSeedLocal, 17);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeDecodedStringKeyWord(insns, keyCellLocal, epochLocal, siteSeedLocal, 2);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, liveWordLocal));
        emitRuntimeShift(insns, siteSeedLocal, 29);
        insns.add(new InsnNode(Opcodes.IUSHR));
        emitRuntimeDecodedStringKeyWord(insns, keyCellLocal, epochLocal, siteSeedLocal, 3);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, siteSeedLocal, 5);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeDecodedStringKeyWord(
        InsnList insns,
        int keyCellLocal,
        int epochLocal,
        int siteSeedLocal,
        int wordIndex
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, wordIndex);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitRuntimeStringKeyCellMask(insns, siteSeedLocal, wordIndex, epochLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeStringKeyCellUpdate(
        InsnList insns,
        int keyCellLocal,
        int rootLocal,
        int nextEpochLocal,
        int oldEpochLocal,
        int siteSeedLocal,
        int selectorLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, oldEpochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, 0x5354524B55504431L);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, siteSeedLocal, 19);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, 0x5354524B55504432L);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, 0x5354524B55504433L);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, nextEpochLocal));
        for (int i = 0; i < 4; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new InsnNode(Opcodes.IALOAD));
            emitRuntimeStringKeyCellMask(insns, siteSeedLocal, i, oldEpochLocal);
            insns.add(new InsnNode(Opcodes.IXOR));
            emitRuntimeStringKeyCellMask(insns, siteSeedLocal, i, nextEpochLocal);
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new InsnNode(Opcodes.IASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new VarInsnNode(Opcodes.ILOAD, nextEpochLocal));
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 5);
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteSeedLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        emitRuntimeStringSiteTokenCellMask(insns, nextEpochLocal, 0);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 6);
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteSeedLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        emitRuntimeStringSiteTokenCellMask(insns, nextEpochLocal, 1);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 7);
        insns.add(new VarInsnNode(Opcodes.ILOAD, selectorLocal));
        emitRuntimeStringTailSelectorCellMask(insns, nextEpochLocal);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
        emitRuntimeStringSiteMetadataCellUpdate(
            insns,
            keyCellLocal,
            siteSeedLocal,
            oldEpochLocal,
            nextEpochLocal,
            STRING_KEY_CELL_PAYLOAD_SLOT,
            STRING_KEY_CELL_PAYLOAD_MASK
        );
        emitRuntimeStringSiteMetadataCellUpdate(
            insns,
            keyCellLocal,
            siteSeedLocal,
            oldEpochLocal,
            nextEpochLocal,
            STRING_KEY_CELL_CACHE_SLOT,
            STRING_KEY_CELL_CACHE_MASK
        );
        emitRuntimeStringSiteMetadataCellUpdate(
            insns,
            keyCellLocal,
            siteSeedLocal,
            oldEpochLocal,
            nextEpochLocal,
            STRING_KEY_CELL_LENGTH,
            STRING_KEY_CELL_LENGTH_MASK
        );
    }

    private void emitRuntimeStringSiteMetadataLoad(
        InsnList insns,
        int keyCellLocal,
        int epochLocal,
        int siteSeedLocal,
        int cellIndex,
        long maskSeed,
        int targetLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, cellIndex);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitRuntimeStringSiteMetadataCellMask(insns, siteSeedLocal, epochLocal, maskSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, targetLocal));
    }

    private void emitRuntimeStringSiteMetadataCellUpdate(
        InsnList insns,
        int keyCellLocal,
        int siteSeedLocal,
        int oldEpochLocal,
        int nextEpochLocal,
        int cellIndex,
        long maskSeed
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, cellIndex);
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, cellIndex);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitRuntimeStringSiteMetadataCellMask(insns, siteSeedLocal, oldEpochLocal, maskSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeStringSiteMetadataCellMask(insns, siteSeedLocal, nextEpochLocal, maskSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    private void emitRuntimeStringSiteMetadataCellMask(
        InsnList insns,
        int siteSeedLocal,
        int epochLocal,
        long maskSeed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, epochLocal));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, maskSeed);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, siteSeedLocal, 11);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, maskSeed ^ 0x5354524D45544131L);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, maskSeed ^ 0x5354524D45544132L);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeStringKeyCellMask(
        InsnList insns,
        int siteSeedLocal,
        int wordIndex,
        int epochLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, epochLocal));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, 0x5354524B4D41534BL + wordIndex);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, siteSeedLocal, 9 + wordIndex);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, 0x5354524B4D554C31L + wordIndex);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        emitRuntimeMixToNonZeroInt(insns, siteSeedLocal, 0x5354524B4D46494EL + wordIndex);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeStringSiteTokenCellMask(InsnList insns, int epochLocal, int wordIndex) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, epochLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x5354525349544531L, wordIndex)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 7 + wordIndex);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x5354525349544532L, wordIndex)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x5354525349544533L, wordIndex)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeStringTailSelectorCellMask(InsnList insns, int epochLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, epochLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x53545253454C3131L, 0x5441494C53454CL)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 13);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x53545253454C3132L, 0x5441494C53454CL)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(0x53545253454C3133L, 0x5441494C53454CL)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeFingerprint(
        InsnList insns,
        int rootLocal,
        int flowLocal,
        int siteSeedLocal,
        int seedScratchLocal
    ) {
        emitRuntimeDerivedSeed(insns, siteSeedLocal, 0x5354524341434845L, 0, seedScratchLocal);
        emitRuntimeStreamWordConstant(insns, rootLocal, 0, seedScratchLocal, flowLocal);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        emitRuntimeDerivedSeed(insns, siteSeedLocal, 0x5354524341434845L, 1, seedScratchLocal);
        emitRuntimeStreamWordConstant(insns, rootLocal, 0, seedScratchLocal, flowLocal);
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private void emitRuntimeStreamWord(InsnList insns, int rootLocal, int wordLocal, int seedLocal, int flowLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        emitRuntimeStreamWordTail(insns, () -> insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal)), seedLocal, flowLocal);
    }

    private void emitRuntimeStreamWordConstant(InsnList insns, int rootLocal, int word, int seedLocal, int flowLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        emitRuntimeStreamWordTail(insns, () -> JvmPassBytecode.pushInt(insns, word), seedLocal, flowLocal);
    }

    private void emitRuntimeStreamWordTail(InsnList insns, Runnable wordEmitter, int seedLocal, int flowLocal) {
        emitRuntimeMixToNonZeroInt(insns, seedLocal, 0x535453545245414DL);
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeFlowMixToNonZeroInt(insns, flowLocal, seedLocal, 0x53545354464C4F57L);
        insns.add(new InsnNode(Opcodes.IXOR));
        wordEmitter.run();
        emitRuntimeMixToNonZeroInt(insns, seedLocal, 0x53545354494E4458L);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, seedLocal, 3);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        emitRuntimeShift(insns, seedLocal, 13);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitRuntimeMixToNonZeroInt(insns, seedLocal, 0x53545354524D554CL);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        emitRuntimeMixToNonZeroInt(insns, seedLocal, 0x5354535446494E31L);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitRuntimeDerivedSeed(InsnList insns, int siteSeedLocal, long xorConst, long value, int outLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteSeedLocal));
        JvmPassBytecode.pushLong(insns, xorConst);
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, value);
        emitRuntimeMixLong(insns);
        insns.add(new VarInsnNode(Opcodes.LSTORE, outLocal));
    }

    private void emitRuntimeMixLong(InsnList insns) {
        insns.add(new InsnNode(Opcodes.LADD));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 30);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0xBF58476D1CE4E5B9L);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 27);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x94D049BB133111EBL);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
    }

    private void emitRuntimeMixToNonZeroInt(InsnList insns, int seedLocal, long value) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        JvmPassBytecode.pushLong(insns, value);
        emitRuntimeMixLong(insns);
        insns.add(new InsnNode(Opcodes.L2I));
        LabelNode nonZero = new LabelNode();
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.POP));
        JvmPassBytecode.pushInt(insns, 0x3D6B2A4F);
        insns.add(nonZero);
    }

    private void emitRuntimeFlowMixToNonZeroInt(
        InsnList insns,
        int flowLocal,
        int seedLocal,
        long value
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, value);
        emitRuntimeMixLong(insns);
        insns.add(new InsnNode(Opcodes.L2I));
        LabelNode nonZero = new LabelNode();
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.POP));
        JvmPassBytecode.pushInt(insns, 0x5A17C3E9);
        insns.add(nonZero);
    }

    private long stringStreamFlow(int root) {
        int high = root ^ 0x53545346;
        int low = (root * 0x45D9F3B) ^ (root >>> 16) ^ 0x464C4F57;
        return (((long) high) << 32) | (Integer.toUnsignedLong(low));
    }

    private void emitRuntimeStringStreamFlow(InsnList insns, int rootLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        JvmPassBytecode.pushInt(insns, 0x53545346);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        JvmPassBytecode.pushInt(insns, 0x45D9F3B);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, 0x464C4F57);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private void emitRuntimeShift(InsnList insns, int seedLocal, int base) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, base);
        insns.add(new InsnNode(Opcodes.LUSHR));
        JvmPassBytecode.pushLong(insns, 30L);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.L2I));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IADD));
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String candidate = base;
        int suffix = 0;
        while (hasAsmMethodName(clazz, candidate)) {
            candidate = base + "$" + ++suffix;
        }
        return candidate;
    }

    private boolean hasAsmMethodName(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (name.equals(method.name)) {
                return true;
            }
        }
        return false;
    }

    private String packageName(String owner) {
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? "" : owner.substring(0, slash);
    }

    private void emitRecoverMethodSeedLocal(
        InsnList insns,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int methodKeyLocal
    ) {
        long seed = methodSeedArgumentSeed(siteSeed);
        JvmPassBytecode.pushInt(insns, ((int) state.methodKey()) ^ methodSeedTransferWord(metadata, state, seed));
        emitMethodSeedTransferWord(insns, seed, metadata, state);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, methodKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, methodKeyLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x5354525041434B31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, methodKeyLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, methodKeyLocal));
    }

    private void emitByteArray(InsnList insns, byte[] data) {
        JvmPassBytecode.pushInt(insns, data.length);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < data.length; i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, i);
            JvmPassBytecode.pushInt(insns, data[i]);
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
    }

    @SuppressWarnings("unchecked")
    private void publishGeneratedStringHelperFlowKey(
        PipelineContext pctx,
        String owner,
        String name,
        String desc,
        int keyLocal
    ) {
        Map<String, Integer> locals = pctx.getPassData(JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD);
        if (locals == null) {
            locals = new LinkedHashMap<>();
            pctx.putPassData(JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD, locals);
        }
        locals.put(owner + "." + name + desc, keyLocal);
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

    private StringKeyMaterial stringKeyMaterial(PipelineContext pctx, L1Class clazz, long siteSeed) {
        long seed = JvmPassBytecode.mix(pctx.masterSeed() ^ 0x5354524B4D415431L, clazz.name().hashCode());
        int[] words = new int[4];
        words[0] = nonZeroInt(JvmPassBytecode.mix(seed ^ siteSeed, 0x5354524B4D415430L));
        words[1] = nonZeroInt(JvmPassBytecode.mix(seed + siteSeed, 0x5354524B4D415431L));
        words[2] = nonZeroInt(JvmPassBytecode.mix(seed ^ Long.rotateLeft(siteSeed, 17), 0x5354524B4D415432L));
        words[3] = nonZeroInt(JvmPassBytecode.mix(seed + Long.rotateRight(siteSeed, 11), 0x5354524B4D415433L));
        int epoch = nonZeroInt(JvmPassBytecode.mix(seed ^ Long.rotateLeft(siteSeed, 29), 0x5354524B45504F43L));
        return new StringKeyMaterial(siteSeed, epoch, words);
    }

    private int[] encodedStringKeyCell(
        StringKeyMaterial keyMaterial,
        Algorithm algorithm,
        ByteLayer byteLayer,
        int payloadSlot,
        int cacheSlot,
        int encryptedLength
    ) {
        int[] cell = new int[11];
        for (int i = 0; i < keyMaterial.words().length; i++) {
            cell[i] = keyMaterial.words()[i] ^ stringKeyCellMask(keyMaterial.siteSeed(), i, keyMaterial.epoch());
        }
        cell[4] = keyMaterial.epoch();
        cell[5] = (int) (keyMaterial.siteSeed() >>> 32) ^ stringSiteTokenCellMask(keyMaterial.epoch(), 0);
        cell[6] = (int) keyMaterial.siteSeed() ^ stringSiteTokenCellMask(keyMaterial.epoch(), 1);
        int selector = (algorithm.ordinal() << 3)
            | (byteLayer.ordinal() << 1)
            | ((keyMaterial.siteSeed() & 2L) != 0L ? 1 : 0);
        cell[7] = selector ^ stringTailSelectorCellMask(keyMaterial.epoch());
        cell[STRING_KEY_CELL_PAYLOAD_SLOT] = payloadSlot ^ stringSiteMetadataCellMask(
            keyMaterial.siteSeed(),
            keyMaterial.epoch(),
            STRING_KEY_CELL_PAYLOAD_MASK
        );
        cell[STRING_KEY_CELL_CACHE_SLOT] = cacheSlot ^ stringSiteMetadataCellMask(
            keyMaterial.siteSeed(),
            keyMaterial.epoch(),
            STRING_KEY_CELL_CACHE_MASK
        );
        cell[STRING_KEY_CELL_LENGTH] = encryptedLength ^ stringSiteMetadataCellMask(
            keyMaterial.siteSeed(),
            keyMaterial.epoch(),
            STRING_KEY_CELL_LENGTH_MASK
        );
        return cell;
    }

    private int stringRoot(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed,
        StringKeyMaterial keyMaterial
    ) {
        return stringKeyMixedWord(liveStringWord(metadata, state, rootSeed(siteSeed)), siteSeed, keyMaterial);
    }

    private int stringKeyMixedWord(int liveWord, long siteSeed, StringKeyMaterial keyMaterial) {
        if (keyMaterial == null) {
            return liveWord;
        }
        int[] words = keyMaterial.words();
        int x = liveWord ^ words[0];
        x += (liveWord << shift(siteSeed, 7)) ^ words[1];
        x ^= x >>> shift(siteSeed, 17);
        x *= words[2] | 1;
        x += (liveWord >>> shift(siteSeed, 29)) ^ words[3];
        x ^= x >>> shift(siteSeed, 5);
        return x;
    }

    private void emitStringKeyCellLoad(InsnList insns, StringSiteCache siteCache, int keyTableLocal) {
        if (keyTableLocal >= 0) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyTableLocal));
        } else {
            insns.add(new FieldInsnNode(
                Opcodes.GETSTATIC,
                siteCache.owner(),
                siteCache.keyTableFieldName(),
                "[Ljava/lang/Object;"
            ));
        }
        JvmPassBytecode.pushInt(insns, siteCache.keySlot());
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
    }

    private void emitStringKeyMixedWord(
        InsnList insns,
        int liveWordLocal,
        int keyCellLocal,
        long siteSeed
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, liveWordLocal));
        emitDecodedStringKeyWord(insns, keyCellLocal, siteSeed, 0);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, liveWordLocal));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 7));
        insns.add(new InsnNode(Opcodes.ISHL));
        emitDecodedStringKeyWord(insns, keyCellLocal, siteSeed, 1);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 17));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitDecodedStringKeyWord(insns, keyCellLocal, siteSeed, 2);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, liveWordLocal));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 29));
        insns.add(new InsnNode(Opcodes.IUSHR));
        emitDecodedStringKeyWord(insns, keyCellLocal, siteSeed, 3);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 5));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitDecodedStringKeyWord(
        InsnList insns,
        int keyCellLocal,
        long siteSeed,
        int wordIndex
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, wordIndex);
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitStringKeyCellMask(insns, siteSeed, wordIndex);
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitStringKeyCellUpdate(
        InsnList insns,
        int keyCellLocal,
        int rootLocal,
        int nextEpochLocal,
        int oldEpochLocal,
        long siteSeed
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, oldEpochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, oldEpochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, rootLocal));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B55504431L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 19));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B55504432L)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B55504433L)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, nextEpochLocal));
        for (int i = 0; i < 4; i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new InsnNode(Opcodes.IALOAD));
            insns.add(new VarInsnNode(Opcodes.ILOAD, oldEpochLocal));
            emitStringKeyCellMask(insns, siteSeed, i);
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new VarInsnNode(Opcodes.ILOAD, nextEpochLocal));
            emitStringKeyCellMask(insns, siteSeed, i);
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new InsnNode(Opcodes.IASTORE));
        }
        insns.add(new VarInsnNode(Opcodes.ALOAD, keyCellLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new VarInsnNode(Opcodes.ILOAD, nextEpochLocal));
        insns.add(new InsnNode(Opcodes.IASTORE));
    }

    private int stringKeyCellMask(long siteSeed, int wordIndex, int epoch) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D41534BL + wordIndex));
        x ^= x >>> shift(siteSeed, 9 + wordIndex);
        x *= nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D554C31L + wordIndex)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D46494EL + wordIndex));
    }

    private int stringSiteTokenCellMask(int epoch, int wordIndex) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(0x5354525349544531L, wordIndex));
        x ^= x >>> (7 + wordIndex);
        x *= nonZeroInt(JvmPassBytecode.mix(0x5354525349544532L, wordIndex)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(0x5354525349544533L, wordIndex));
    }

    private int stringTailSelectorCellMask(int epoch) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(0x53545253454C3131L, 0x5441494C53454CL));
        x ^= x >>> 13;
        x *= nonZeroInt(JvmPassBytecode.mix(0x53545253454C3132L, 0x5441494C53454CL)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(0x53545253454C3133L, 0x5441494C53454CL));
    }

    private int stringSiteMetadataCellMask(long siteSeed, int epoch, long maskSeed) {
        int x = epoch ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, maskSeed));
        x ^= x >>> shift(siteSeed, 11);
        x *= nonZeroInt(JvmPassBytecode.mix(siteSeed, maskSeed ^ 0x5354524D45544131L)) | 1;
        return x ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, maskSeed ^ 0x5354524D45544132L));
    }

    private void emitStringKeyCellMask(InsnList insns, long siteSeed, int wordIndex) {
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D41534BL + wordIndex)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, shift(siteSeed, 9 + wordIndex));
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D554C31L + wordIndex)) | 1);
        insns.add(new InsnNode(Opcodes.IMUL));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x5354524B4D46494EL + wordIndex)));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private void emitFillKey(
        InsnList insns,
        long siteSeed,
        Algorithm algorithm,
        int keyLocal,
        int wordLocal,
        int rootLocal,
        int indexLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode end = new LabelNode();
        boolean reverseBytes = (siteSeed & 2L) != 0L;
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, algorithm.keySize / 4);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        emitStreamWord(insns, rootLocal, indexLocal, keyStreamSeed(siteSeed, algorithm));
        insns.add(new VarInsnNode(Opcodes.ISTORE, wordLocal));
        for (int step = 0; step < 4; step++) {
            int b = reverseBytes ? 3 - step : step;
            insns.add(new VarInsnNode(Opcodes.ALOAD, keyLocal));
            insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
            JvmPassBytecode.pushInt(insns, 4);
            insns.add(new InsnNode(Opcodes.IMUL));
            JvmPassBytecode.pushInt(insns, b);
            insns.add(new InsnNode(Opcodes.IADD));
            insns.add(new VarInsnNode(Opcodes.ILOAD, wordLocal));
            JvmPassBytecode.pushInt(insns, 24 - b * 8);
            insns.add(new InsnNode(Opcodes.IUSHR));
            insns.add(new InsnNode(Opcodes.BASTORE));
        }
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(end);
    }

    private void emitByteLayerDecode(
        InsnList insns,
        long siteSeed,
        int length,
        ByteLayer byteLayer,
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
        emitStreamWord(insns, rootLocal, wordLocal, byteLayerSeed(siteSeed));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.ISUB));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IUSHR));
        switch (byteLayer) {
            case ADD -> insns.add(new InsnNode(Opcodes.ISUB));
            case SUBTRACT -> insns.add(new InsnNode(Opcodes.IADD));
            case XOR -> insns.add(new InsnNode(Opcodes.IXOR));
        }
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(end);
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
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        long siteSeed,
        byte[] encrypted,
        StringKeyMaterial keyMaterial,
        Algorithm algorithm,
        ByteLayer byteLayer
    ) {
        Map<String, StringSiteCache> caches = pctx.getPassData(STRING_SITE_CACHES);
        if (caches == null) {
            caches = new LinkedHashMap<>();
            pctx.putPassData(STRING_SITE_CACHES, caches);
        }
        String key = clazz.name() + ":" + Long.toUnsignedString(siteSeed, 36);
        StringSiteCache existing = caches.get(key);
        if (existing != null) return existing;

        StringKeyTable keyTable = ensureStringKeyTable(pctx, clazz, metadata);
        int payloadSlot = registerStringPayload(pctx, keyTable, encrypted);
        int cacheSlot = registerStringCacheSlot(pctx, keyTable);
        int keySlot = registerStringKeyMaterial(
            pctx,
            keyTable,
            keyMaterial,
            algorithm,
            byteLayer,
            payloadSlot,
            cacheSlot,
            encrypted.length
        );

        StringSiteCache cache = new StringSiteCache(
            clazz.name(),
            keyTable.fieldName(),
            keySlot
        );
        caches.put(key, cache);
        clazz.markDirty();
        return cache;
    }

    @SuppressWarnings("unchecked")
    private StringKeyTable ensureStringKeyTable(
        PipelineContext pctx,
        L1Class clazz,
        ControlFlowFlatteningPass.CffMethodMetadata metadata
    ) {
        Map<String, StringKeyTable> tables = pctx.getPassData(STRING_KEY_TABLES);
        if (tables == null) {
            tables = new LinkedHashMap<>();
            pctx.putPassData(STRING_KEY_TABLES, tables);
        }
        StringKeyTable existing = tables.get(clazz.name());
        if (existing != null) return existing;

        String fieldName = metadata.classKeyTable().objectFieldName();
        boolean directClinit = true;
        MethodNode helper = findOrCreateClassInit(clazz);
        LabelNode initStart = new LabelNode();
        LabelNode initEnd = new LabelNode();
        insertClassInitSegmentAfter(helper, metadata.classKeyTable().initEnd(), initStart, initEnd);

        StringKeyTable table = new StringKeyTable(
            clazz.name(),
            fieldName,
            helper,
            new ArrayList<>(),
            new ArrayList<>(),
            new ArrayList<>(),
            directClinit,
            initStart,
            initEnd
        );
        tables.put(clazz.name(), table);
        clazz.markDirty();
        return table;
    }

    private int registerStringKeyMaterial(
        PipelineContext pctx,
        StringKeyTable table,
        StringKeyMaterial keyMaterial,
        Algorithm algorithm,
        ByteLayer byteLayer,
        int payloadSlot,
        int cacheSlot,
        int encryptedLength
    ) {
        if (keyMaterial == null) {
            throw new IllegalStateException("string key material table requires encoded key material");
        }
        int slot = table.cells().size() + STRING_KEY_CELL_BASE_SLOT;
        table.cells().add(encodedStringKeyCell(keyMaterial, algorithm, byteLayer, payloadSlot, cacheSlot, encryptedLength));
        rebuildStringKeyInit(pctx, table);
        return slot;
    }

    private int registerStringPayload(
        PipelineContext pctx,
        StringKeyTable table,
        byte[] encrypted
    ) {
        int slot = table.payloads().size();
        table.payloads().add(encrypted.clone());
        rebuildStringKeyInit(pctx, table);
        return slot;
    }

    private int registerStringCacheSlot(PipelineContext pctx, StringKeyTable table) {
        int slot = table.cacheSlots().size();
        table.cacheSlots().add(slot);
        rebuildStringKeyInit(pctx, table);
        return slot;
    }

    private void rebuildStringKeyInit(PipelineContext pctx, StringKeyTable table) {
        InsnList insns = new InsnList();
        int arrayLocal = table.directClinit() ? table.initHelper().maxLocals : 0;
        JvmPassBytecode.pushInt(insns, table.cells().size() + STRING_KEY_CELL_BASE_SLOT);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, arrayLocal));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.fieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.STRING_MATERIAL_SLOT);
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.fieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.STRING_MATERIAL_ALIAS_SLOT);
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            table.owner(),
            table.fieldName(),
            "[Ljava/lang/Object;"
        ));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.STRING_MATERIAL_SELECTOR_SLOT);
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.STRING_MATERIAL_SLOT);
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.STRING_MATERIAL_ALIAS_SLOT);
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(insns, STRING_PAYLOAD_TABLE_SLOT);
        JvmPassBytecode.pushInt(insns, table.payloads().size());
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        for (int i = 0; i < table.payloads().size(); i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, i);
            emitByteArray(insns, table.payloads().get(i));
            insns.add(new InsnNode(Opcodes.AASTORE));
        }
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
        JvmPassBytecode.pushInt(insns, STRING_CACHE_TABLE_SLOT);
        JvmPassBytecode.pushInt(insns, table.cacheSlots().size() * 2);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/Object"));
        insns.add(new InsnNode(Opcodes.AASTORE));
        emitStringCipherCacheStore(insns, table, arrayLocal, Algorithm.AES);
        emitStringCipherCacheStore(insns, table, arrayLocal, Algorithm.DES);
        for (int i = 0; i < table.cells().size(); i++) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, arrayLocal));
            JvmPassBytecode.pushInt(insns, i + STRING_KEY_CELL_BASE_SLOT);
            emitIntArray(insns, table.cells().get(i));
            insns.add(new InsnNode(Opcodes.AASTORE));
        }
        JvmKeyDispatchPass.markGenerated(pctx, insns);
        if (table.directClinit()) {
            replaceClassInitSegment(table.initHelper(), table.initStart(), table.initEnd(), insns);
        } else {
            table.initHelper().instructions.clear();
            table.initHelper().instructions.add(insns);
            table.initHelper().instructions.add(new InsnNode(Opcodes.RETURN));
            table.initHelper().maxLocals = 0;
        }
        table.initHelper().maxLocals = Math.max(table.initHelper().maxLocals, arrayLocal + 1);
        table.initHelper().maxStack = 16;
    }

    private void emitStringCipherCacheStore(InsnList insns, StringKeyTable table, int tableLocal, Algorithm algorithm) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        JvmPassBytecode.pushInt(insns, stringCipherSlot(algorithm));
        emitStaticString(
            insns,
            algorithm.transformation,
            JvmPassBytecode.mix(
                table.owner().hashCode() ^ table.fieldName().hashCode(),
                0x535452434950484CL ^ algorithm.ordinal()
            )
        );
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "javax/crypto/Cipher",
            "getInstance",
            "(Ljava/lang/String;)Ljavax/crypto/Cipher;",
            false
        ));
        insns.add(new InsnNode(Opcodes.AASTORE));
    }

    private void emitIntArray(InsnList insns, int[] values) {
        JvmPassBytecode.pushInt(insns, values.length);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        for (int i = 0; i < values.length; i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, i);
            JvmPassBytecode.pushInt(insns, values[i]);
            insns.add(new InsnNode(Opcodes.IASTORE));
        }
    }

    private int stringMaterialTableFieldAccess(L1Class clazz) {
        if ((clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0) {
            return Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
        }
        return Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
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

    private void emitStaticString(InsnList insns, String value, long seed) {
        JvmPassBytecode.pushInt(insns, value.length());
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_CHAR));
        for (int i = 0; i < value.length(); i++) {
            int mask = staticCharMask(seed, i);
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, i);
            JvmPassBytecode.pushInt(insns, value.charAt(i) ^ mask);
            JvmPassBytecode.pushInt(insns, mask);
            insns.add(new InsnNode(Opcodes.IXOR));
            insns.add(new InsnNode(Opcodes.I2C));
            insns.add(new InsnNode(Opcodes.CASTORE));
        }
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/String",
            "valueOf",
            "([C)Ljava/lang/String;",
            false
        ));
    }

    private int staticCharMask(long seed, int index) {
        long mixed = JvmPassBytecode.mix(seed ^ 0x5354415449435354L, index * STATIC_CHAR_STRIDE);
        return (int) (mixed ^ (mixed >>> 32)) & 0xFFFF;
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

    private void insertClassInitSegment(MethodNode clinit, LabelNode start, LabelNode end) {
        InsnList segment = new InsnList();
        segment.add(start);
        segment.add(end);
        AbstractInsnNode first = clinit.instructions.getFirst();
        if (first == null) {
            clinit.instructions.add(segment);
            clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            clinit.instructions.insertBefore(first, segment);
        }
    }

    private void insertClassInitSegmentAfter(
        MethodNode clinit,
        LabelNode anchor,
        LabelNode start,
        LabelNode end
    ) {
        if (anchor == null) {
            insertClassInitSegment(clinit, start, end);
            return;
        }
        InsnList segment = new InsnList();
        segment.add(start);
        segment.add(end);
        clinit.instructions.insert(anchor, segment);
    }

    private void replaceClassInitSegment(
        MethodNode clinit,
        LabelNode start,
        LabelNode end,
        InsnList replacement
    ) {
        AbstractInsnNode insn = start.getNext();
        while (insn != null && insn != end) {
            AbstractInsnNode next = insn.getNext();
            clinit.instructions.remove(insn);
            insn = next;
        }
        clinit.instructions.insert(start, replacement);
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
        LabelNode syntheticDone = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Field",
            "isSynthetic",
            "()Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, syntheticDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Field",
            "getModifiers",
            "()I",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/reflect/Modifier",
            "isStatic",
            "(I)Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        insns.add(syntheticDone);
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
        return streamWord(root, seed, 0, 0L);
    }

    private int streamWord(int root, long seed, int wordIndex, long streamFlow) {
        int x = root ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x535453545245414DL));
        x ^= nonZeroInt(JvmPassBytecode.mix(streamFlow ^ seed, 0x53545354464C4F57L));
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
            metadata.classKeyTable().objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        emitStringClassKeyWordsSlotSelectionFromCarrier(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
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

    private void emitStringClassKeyWordsSlotSelectionFromCarrier(
        InsnList insns,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.CLASS_KEY_WORDS_SELECTOR_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
    }

    private int methodSeedTransferWord(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long seed
    ) {
        int x =
            (state.guardKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54475531L))) +
            state.pathKey();
        x ^= x >>> 11;
        x += state.blockKey() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54424C31L));
        x ^= state.pcToken() + nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54504331L));
        int idx =
            (x ^ state.state() ^ nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54544231L))) &
            (metadata.classKeyTable().values().length - 1);
        x += metadata.classKeyTable().values()[idx] ^
            nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54545631L));
        x ^= x >>> 17;
        return x;
    }

    private void emitMethodSeedTransferWord(
        InsnList insns,
        long seed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54475531L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 11);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54424C31L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54504331L)));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54544231L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, metadata.classKeyTable().values().length - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new FieldInsnNode(
            Opcodes.GETSTATIC,
            metadata.classKeyTable().owner(),
            metadata.classKeyTable().objectFieldName(),
            "[Ljava/lang/Object;"
        ));
        emitStringClassKeyWordsSlotSelectionFromCarrier(insns, metadata, state);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new InsnNode(Opcodes.SWAP));
        insns.add(new InsnNode(Opcodes.IALOAD));
        JvmPassBytecode.pushInt(insns, nonZeroInt(JvmPassBytecode.mix(seed, 0x53544D54545631L)));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 17);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
    }

    private long byteLayerSeed(long siteSeed) {
        return JvmPassBytecode.mix(siteSeed ^ 0x5354524C41595231L, 0x425954454C415952L);
    }

    private long methodSeedArgumentSeed(long siteSeed) {
        return JvmPassBytecode.mix(siteSeed ^ 0x5354524D45544831L, 0x4152474B45594C31L);
    }

    private long keySeed(long siteSeed, int word, Algorithm algorithm) {
        long h = JvmPassBytecode.mix(siteSeed ^ 0x5354524B45594B31L, word);
        return JvmPassBytecode.mix(h, algorithm.keySize);
    }

    private long keyStreamSeed(long siteSeed, Algorithm algorithm) {
        return JvmPassBytecode.mix(siteSeed ^ 0x5354524B45594C31L, algorithm.keySize);
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

    private record ConcatRewriteResult(
        InsnList instructions,
        int encryptedStrings,
        String helperName,
        boolean usesLoopCache
    ) {}

    private record ConcatHelperCacheKey(
        String owner,
        String desc,
        List<ConcatCachePiece> pieces,
        List<Integer> externalStringPattern
    ) {}

    private sealed interface ConcatCachePiece permits CacheArgPiece, CacheStringPiece, CacheConstPiece {}

    private record CacheArgPiece() implements ConcatCachePiece {}

    private record CacheStringPiece(int externalSlot) implements ConcatCachePiece {}

    private record CacheConstPiece(Object value) implements ConcatCachePiece {}

    private record StringSiteCache(
        String owner,
        String keyTableFieldName,
        int keySlot
    ) {
        boolean hasKeyMaterial() {
            return keyTableFieldName != null && keySlot >= 0;
        }
    }

    private record StringKeyMaterial(long siteSeed, int epoch, int[] words) {}

    private record StringKeyTable(
        String owner,
        String fieldName,
        MethodNode initHelper,
        List<int[]> cells,
        List<byte[]> payloads,
        List<Integer> cacheSlots,
        boolean directClinit,
        LabelNode initStart,
        LabelNode initEnd
    ) {}

    private record StringDecodeTail(String owner, String name, boolean interfaceOwner) {}

    private enum ByteLayer {
        ADD,
        SUBTRACT,
        XOR
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
