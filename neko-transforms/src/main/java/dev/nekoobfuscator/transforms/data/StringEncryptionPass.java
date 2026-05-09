package dev.nekoobfuscator.transforms.data;

import dev.nekoobfuscator.api.transform.*;
import dev.nekoobfuscator.core.ir.l1.*;
import dev.nekoobfuscator.core.pipeline.PipelineContext;
import dev.nekoobfuscator.transforms.key.DynamicKeyDerivationEngine;
import dev.nekoobfuscator.transforms.key.KeyDispatcherSupport;
import dev.nekoobfuscator.transforms.util.TransformGuards;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;

import java.nio.charset.StandardCharsets;
import java.util.*;

public final class StringEncryptionPass implements TransformPass {
    private static final String FLOW_KEY_VALUES_KEY = "controlFlowFlattening.flowKeys";
    private static final String FLOW_KEY_LOCAL_BY_METHOD_KEY = "controlFlowFlattening.flowKeyLocalByMethod";
    private static final String DIRECT_RUNTIME_OPTION = "directRuntime";
    private static final String USE_CONTROL_FLOW_KEY_OPTION = "useControlFlowKey";
    private static final String LOCAL_CODEC_PREFIX = "__neko_s";

    private static final String BOOTSTRAP_CLASS = "dev/nekoobfuscator/runtime/NekoBootstrap";
    private static final String BOOTSTRAP_METHOD = "bsmString";
    // BSM args: fieldIdx, methodNameHash, methodDescHash, insnSalt, flowMode, keyMode, keyComponent
    private static final String BOOTSTRAP_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;IIIIIII)Ljava/lang/invoke/CallSite;";
    private static final Handle BSM_HANDLE = new Handle(
        Opcodes.H_INVOKESTATIC, BOOTSTRAP_CLASS, BOOTSTRAP_METHOD, BOOTSTRAP_DESC, false);

    // Per-class state
    private int encFieldCounter;
    private DynamicKeyDerivationEngine keyEngine;
    private long classKey;
    private String codecMethodName;
    private Map<Integer, SiteKeyInfo> stringSiteKeyInfo;

    private static final int CLASS_METADATA_ACCESS =
        Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;
    private static final int INTERFACE_METADATA_ACCESS =
        Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC;

    @Override public String id() { return "stringEncryption"; }
    @Override public String name() { return "String Encryption"; }
    @Override public TransformPhase phase() { return TransformPhase.TRANSFORM; }
    @Override public IRLevel requiredLevel() { return IRLevel.L1; }
    @Override public Set<String> dependsOn() { return Set.of("controlFlowFlattening"); }

    @Override
    public void transformClass(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Class clazz = pctx.currentL1Class();

        // Initialize key engine if needed
        keyEngine = pctx.getPassData("keyEngine");
        if (keyEngine == null) {
            keyEngine = new DynamicKeyDerivationEngine(pctx.masterSeed());
            pctx.putPassData("keyEngine", keyEngine);
        }

        classKey = keyEngine.deriveClassKey(clazz);
        encFieldCounter = countExistingEncFields(clazz);
        codecMethodName = LOCAL_CODEC_PREFIX + Integer.toUnsignedString(clazz.name().hashCode(), 36);
        stringSiteKeyInfo = new HashMap<>();
        materializeConstantStringFields(clazz);
    }

    @Override
    public void transformMethod(TransformContext ctx) {
        PipelineContext pctx = (PipelineContext) ctx;
        L1Method method = pctx.currentL1Method();
        L1Class clazz = pctx.currentL1Class();
        IdentityHashMap<AbstractInsnNode, Long> flowKeyValues = pctx.getPassData(FLOW_KEY_VALUES_KEY);
        Map<String, Integer> flowKeyLocalByMethod = pctx.getPassData(FLOW_KEY_LOCAL_BY_METHOD_KEY);
        var config = pctx.config().transforms().get("stringEncryption");

        if (method.isAbstract() || method.isNative() || !method.hasCode()
                || TransformGuards.isRuntimeClass(clazz)
                || TransformGuards.isGeneratedMethod(method)) {
            JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                "guarded-runtime-generated-or-no-code");
            return;
        }
        InsnList insns = method.instructions();
        int methodNameHash = method.name().hashCode();
        int methodDescHash = method.descriptor().hashCode();
        // Full multi-layer: methodKey = mix(mix(classKey, nameHash), descHash)
        long methodKey = DynamicKeyDerivationEngine.mix(
            DynamicKeyDerivationEngine.mix(classKey, methodNameHash), methodDescHash);

        boolean materializedConcat = materializeStringConcatRecipes(method.asmNode(), insns);

        // Collect string LDC instructions
        List<AbstractInsnNode> stringLdcs = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof LdcInsnNode ldc && ldc.cst instanceof String) {
                stringLdcs.add(insn);
            }
        }

        if (stringLdcs.isEmpty()) {
            if (materializedConcat) {
                clazz.markDirty();
                JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(),
                    "concat-recipe-materialized");
            } else {
                JvmObfuscationCoverage.get(pctx).notApplicable(id(), clazz.name(), method.name(), method.descriptor(),
                    "no-string-literals");
            }
            return;
        }
        boolean reflectionShapeSensitive = TransformGuards.isReflectionShapeSensitive(pctx, clazz);
        KeyDispatcherSupport.Profile keyDispatcher = !reflectionShapeSensitive && KeyDispatcherSupport.enabled(config)
            ? KeyDispatcherSupport.getOrCreate(pctx, clazz, config, "string", methodNameHash)
            : null;

        String methodKeyId = clazz.name() + "." + method.name() + method.descriptor();
        Integer flowKeyLocalSlot = flowKeyLocalByMethod == null
            ? null : flowKeyLocalByMethod.get(methodKeyId);
        boolean directRuntime = booleanOption(config, DIRECT_RUNTIME_OPTION, true);

        // Process each string
        for (AbstractInsnNode insn : stringLdcs) {
            LdcInsnNode ldc = (LdcInsnNode) insn;
            String original = (String) ldc.cst;

            // Allocate field index first (needed for key derivation)
            int fieldIdx = encFieldCounter++;
            int insnSalt = pctx.random().nextInt();
            int keyComponent = fieldIdx ^ insnSalt;
            long siteInsnKey = keyEngine.deriveInsnKey(methodKey, fieldIdx, insnSalt);

            // Predicted contextKey at this site (only for directRuntime):
            //   - CFF method: per-block flowKey from CFF's edge-XOR chain prediction
            //     (runtime LLOAD will produce the same value via the actual XOR chain).
            //   - Non-CFF method: per-site random long pushed at callsite as a 2-LDC XOR
            //     split so the literal never appears as one LDC.
            // For indy mode we keep the legacy formula (no flowKey contribution at compile
            // time; bsm reads NekoContext.flowKey() if useFlowKey) since bsm semantics differ.
            long predictedContextKey = 0L;
            long callsiteMaskA = 0L;
            long callsiteMaskB = 0L;
            boolean directKeyMode = directRuntime || reflectionShapeSensitive;
            boolean cffSite = directKeyMode
                && flowKeyValues != null && flowKeyValues.containsKey(insn)
                && flowKeyLocalSlot != null;
            if (directKeyMode) {
                if (cffSite) {
                    predictedContextKey = flowKeyValues.get(insn);
                } else {
                    callsiteMaskA = pctx.random().nextLong() | 1L;
                    callsiteMaskB = pctx.random().nextLong();
                    predictedContextKey = callsiteMaskA ^ callsiteMaskB;
                }
            }

            boolean indyUseFlowKey = !directRuntime
                && booleanOption(config, USE_CONTROL_FLOW_KEY_OPTION, false)
                && flowKeyValues != null
                && flowKeyValues.containsKey(insn);
            long indyMixedKey = siteInsnKey;
            if (indyUseFlowKey) {
                indyMixedKey = DynamicKeyDerivationEngine.mix(indyMixedKey, flowKeyValues.get(insn));
            }

            long effectiveKey;
            if (directRuntime || reflectionShapeSensitive) {
                long mixedKey = DynamicKeyDerivationEngine.mix(siteInsnKey, predictedContextKey);
                effectiveKey = keyDispatcher != null
                    ? KeyDispatcherSupport.dispatch(keyDispatcher, mixedKey, keyComponent)
                    : mixedKey;
            } else {
                effectiveKey = keyDispatcher != null
                    ? KeyDispatcherSupport.dispatch(keyDispatcher, indyMixedKey, keyComponent)
                    : indyMixedKey;
            }
            stringSiteKeyInfo.put(fieldIdx,
                new SiteKeyInfo(siteInsnKey, keyDispatcher, keyComponent, indyUseFlowKey));

            // Encrypt the string
            byte[] plainBytes = original.getBytes(StandardCharsets.UTF_8);
            byte[] encrypted = DynamicKeyDerivationEngine.encrypt(plainBytes, effectiveKey);
            if (reflectionShapeSensitive) {
                InsnList replacement = inlineRuntimeDecryptReplacement(encrypted, effectiveKey, siteInsnKey, cffSite, flowKeyLocalSlot);
                NumberEncryptionPass.excludeGeneratedNumericInsns(pctx, replacement);
                insns.insertBefore(insn, replacement);
                insns.remove(insn);
                continue;
            }

            String fieldName = "__e" + fieldIdx;
            FieldNode fn = new FieldNode(
                metadataAccess(clazz.asmNode()),
                fieldName, "Ljava/lang/String;", null,
                new String(encrypted, StandardCharsets.ISO_8859_1));
            clazz.asmNode().fields.add(fn);

            if (directRuntime) {
                InsnList replacement = new InsnList();
                replacement.add(NumberEncryptionPass.generatedInt(fieldIdx));
                if (cffSite) {
                    replacement.add(new VarInsnNode(Opcodes.LLOAD, flowKeyLocalSlot));
                } else {
                    replacement.add(new LdcInsnNode(callsiteMaskA));
                    replacement.add(new LdcInsnNode(callsiteMaskB));
                    replacement.add(new InsnNode(Opcodes.LXOR));
                }
                replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    clazz.name(), codecMethodName, "(IJ)Ljava/lang/String;",
                    (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0));
                NumberEncryptionPass.excludeGeneratedNumericInsns(pctx, replacement);
                insns.insertBefore(insn, replacement);
                insns.remove(insn);
            } else {
                // Create invokedynamic instruction with full key components
                InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                    "decrypt",                    // name (arbitrary)
                    "()Ljava/lang/String;",       // descriptor: no args, returns String
                    BSM_HANDLE,                   // bootstrap method
                    fieldIdx,                     // bsm arg 0: encrypted field index
                    methodNameHash,               // bsm arg 1: method name hash
                    methodDescHash,               // bsm arg 2: method descriptor hash
                    insnSalt,                     // bsm arg 3: per-instruction salt
                    indyUseFlowKey ? 1 : 0,       // bsm arg 4: consume dynamic flow key
                    keyDispatcher != null ? 1 : 0,
                    keyComponent
                );

                // Replace LDC with invokedynamic
                insns.set(insn, indy);
            }
        }

        if (!reflectionShapeSensitive) {
            ensureLocalStringCodec(clazz);
        }
        clazz.markDirty();
        JvmObfuscationCoverage.get(pctx).full(id(), clazz.name(), method.name(), method.descriptor(),
            "encrypted-strings=" + stringLdcs.size());
    }

    private InsnList inlineRuntimeDecryptReplacement(byte[] encrypted, long effectiveKey,
            long siteInsnKey, boolean cffSite, Integer flowKeyLocalSlot) {
        InsnList replacement = new InsnList();
        replacement.add(pushInt(encrypted.length));
        replacement.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        for (int i = 0; i < encrypted.length; i++) {
            replacement.add(new InsnNode(Opcodes.DUP));
            replacement.add(pushInt(i));
            replacement.add(pushInt(encrypted[i]));
            replacement.add(new InsnNode(Opcodes.BASTORE));
        }
        if (cffSite && flowKeyLocalSlot != null) {
            replacement.add(new LdcInsnNode(siteInsnKey));
            replacement.add(new VarInsnNode(Opcodes.LLOAD, flowKeyLocalSlot));
            replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                "dev/nekoobfuscator/runtime/NekoKeyDerivation", "mix", "(JJ)J", false));
        } else {
            replacement.add(new LdcInsnNode(effectiveKey));
        }
        replacement.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
            "dev/nekoobfuscator/runtime/NekoStringDecryptor", "decrypt", "([BJ)Ljava/lang/String;", false));
        return replacement;
    }

    private void ensureLocalStringCodec(L1Class clazz) {
        ClassNode cn = clazz.asmNode();
        cn.methods.removeIf(method -> method.name.startsWith(LOCAL_CODEC_PREFIX)
            && "(IJ)Ljava/lang/String;".equals(method.desc));

        List<Integer> sites = encryptedStringSites(cn);
        if (sites.isEmpty()) return;

        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        if ((cn.access & Opcodes.ACC_INTERFACE) != 0) {
            access |= Opcodes.ACC_PUBLIC;
        } else {
            access |= Opcodes.ACC_PRIVATE;
        }

        MethodNode method = new MethodNode(access, codecMethodName, "(IJ)Ljava/lang/String;", null, null);
        InsnList insns = method.instructions;
        LabelNode defaultLabel = new LabelNode();
        LabelNode afterSwitch = new LabelNode();
        int[] keys = new int[sites.size()];
        LabelNode[] labels = new LabelNode[sites.size()];
        for (int i = 0; i < sites.size(); i++) {
            keys[i] = sites.get(i);
            labels[i] = new LabelNode();
        }

        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new LookupSwitchInsnNode(defaultLabel, keys, labels));
        for (int i = 0; i < sites.size(); i++) {
            insns.add(labels[i]);
            insns.add(new FieldInsnNode(Opcodes.GETSTATIC, cn.name, "__e" + sites.get(i), "Ljava/lang/String;"));
            insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
            SiteKeyInfo info = stringSiteKeyInfo.get(sites.get(i));
            // Bake the per-site insnKey inside the codec switch case so callsites
            // never carry a plain "long insnKey" LDC. Mix it with the contextKey
            // received from the caller (real CFF flowKey or per-site mask split).
            if (info != null) {
                insns.add(new LdcInsnNode(info.siteInsnKey()));
                insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
                insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC,
                    "dev/nekoobfuscator/runtime/NekoKeyDerivation", "mix", "(JJ)J", false));
                insns.add(new VarInsnNode(Opcodes.LSTORE, 1));
                if (info.keyDispatcher() != null) {
                    insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
                    insns.add(NumberEncryptionPass.generatedInt(info.keyComponent()));
                    insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, cn.name,
                        info.keyDispatcher().methodName(), "(JI)J",
                        (cn.access & Opcodes.ACC_INTERFACE) != 0));
                    insns.add(new VarInsnNode(Opcodes.LSTORE, 1));
                }
            }
            insns.add(new JumpInsnNode(Opcodes.GOTO, afterSwitch));
        }
        insns.add(defaultLabel);
        insns.add(new InsnNode(Opcodes.ACONST_NULL));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 3));
        insns.add(afterSwitch);

        LabelNode notNull = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new JumpInsnNode(Opcodes.IFNONNULL, notNull));
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "()V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));
        insns.add(notNull);

        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "length", "()I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 4));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_BYTE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, 5));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, 6));

        LabelNode loopCheck = new LabelNode();
        LabelNode loopBody = new LabelNode();
        insns.add(loopCheck);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLT, loopBody));
        LabelNode trimCheck = new LabelNode();
        insns.add(new JumpInsnNode(Opcodes.GOTO, trimCheck));
        insns.add(loopBody);
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "charAt", "(I)C", false));
        insns.add(new IntInsnNode(Opcodes.SIPUSH, 255));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 6));
        insns.add(new IntInsnNode(Opcodes.BIPUSH, 7));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new InsnNode(Opcodes.ICONST_3));
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2B));
        insns.add(new InsnNode(Opcodes.BASTORE));
        insns.add(new IincInsnNode(6, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loopCheck));

        LabelNode trimBody = new LabelNode();
        LabelNode construct = new LabelNode();
        insns.add(trimCheck);
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new JumpInsnNode(Opcodes.IFLE, construct));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new InsnNode(Opcodes.BALOAD));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, trimBody));
        insns.add(new JumpInsnNode(Opcodes.GOTO, construct));
        insns.add(trimBody);
        insns.add(new IincInsnNode(4, -1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, trimCheck));

        insns.add(construct);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, "java/nio/charset/StandardCharsets",
            "UTF_8", "Ljava/nio/charset/Charset;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>",
            "([BIILjava/nio/charset/Charset;)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));

        method.maxStack = 8;
        method.maxLocals = 7;
        cn.methods.add(method);
    }

    private List<Integer> encryptedStringSites(ClassNode cn) {
        List<Integer> sites = new ArrayList<>();
        for (FieldNode field : cn.fields) {
            if (!field.name.startsWith("__e") || !"Ljava/lang/String;".equals(field.desc)) {
                continue;
            }
            try {
                sites.add(Integer.parseInt(field.name.substring(3)));
            } catch (NumberFormatException ignored) {
            }
        }
        Collections.sort(sites);
        return sites;
    }

    private boolean materializeStringConcatRecipes(MethodNode method, InsnList insns) {
        boolean changed = false;
        List<InvokeDynamicInsnNode> concats = new ArrayList<>();
        for (AbstractInsnNode insn = insns.getFirst(); insn != null; insn = insn.getNext()) {
            if (insn instanceof InvokeDynamicInsnNode indy && isStringConcatWithConstants(indy)) {
                concats.add(indy);
            }
        }
        for (InvokeDynamicInsnNode indy : concats) {
            InsnList replacement = explicitStringConcat(method, indy);
            insns.insertBefore(indy, replacement);
            insns.remove(indy);
            changed = true;
        }
        return changed;
    }

    private boolean isStringConcatWithConstants(InvokeDynamicInsnNode indy) {
        return "makeConcatWithConstants".equals(indy.name)
            && indy.bsm != null
            && "java/lang/invoke/StringConcatFactory".equals(indy.bsm.getOwner())
            && indy.bsmArgs != null
            && indy.bsmArgs.length > 0
            && indy.bsmArgs[0] instanceof String;
    }

    private InsnList explicitStringConcat(MethodNode method, InvokeDynamicInsnNode indy) {
        Type[] argTypes = Type.getArgumentTypes(indy.desc);
        int nextLocal = method.maxLocals;
        int[] argLocals = new int[argTypes.length];
        InsnList out = new InsnList();

        for (int i = argTypes.length - 1; i >= 0; i--) {
            argLocals[i] = nextLocal;
            nextLocal += argTypes[i].getSize();
            out.add(new VarInsnNode(argTypes[i].getOpcode(Opcodes.ISTORE), argLocals[i]));
        }
        method.maxLocals = Math.max(method.maxLocals, nextLocal);

        out.add(new TypeInsnNode(Opcodes.NEW, "java/lang/StringBuilder"));
        out.add(new InsnNode(Opcodes.DUP));
        out.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/StringBuilder", "<init>", "()V", false));

        String recipe = (String) indy.bsmArgs[0];
        int argIndex = 0;
        int constIndex = 1;
        StringBuilder literal = new StringBuilder();
        for (int i = 0; i < recipe.length(); i++) {
            char ch = recipe.charAt(i);
            if (ch == '\u0001' || ch == '\u0002') {
                appendLiteral(out, literal);
                if (ch == '\u0001') {
                    Type type = argTypes[argIndex];
                    out.add(new VarInsnNode(type.getOpcode(Opcodes.ILOAD), argLocals[argIndex++]));
                    appendValue(out, type);
                } else {
                    Object constant = indy.bsmArgs[constIndex++];
                    pushConcatConstant(out, constant);
                    appendValue(out, typeOfConstant(constant));
                }
            } else {
                literal.append(ch);
            }
        }
        appendLiteral(out, literal);
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL,
            "java/lang/StringBuilder", "toString", "()Ljava/lang/String;", false));
        method.maxStack = Math.max(method.maxStack, 4);
        return out;
    }

    private void appendLiteral(InsnList out, StringBuilder literal) {
        if (literal.length() == 0) return;
        out.add(new LdcInsnNode(literal.toString()));
        appendValue(out, Type.getType(String.class));
        literal.setLength(0);
    }

    private void pushConcatConstant(InsnList out, Object constant) {
        if (constant == null) {
            out.add(new InsnNode(Opcodes.ACONST_NULL));
        } else if (constant instanceof Integer || constant instanceof Long
                || constant instanceof Float || constant instanceof Double
                || constant instanceof String) {
            out.add(new LdcInsnNode(constant));
        } else if (constant instanceof Type type) {
            out.add(new LdcInsnNode(type));
        } else if (constant instanceof Handle handle) {
            out.add(new LdcInsnNode(handle));
        } else {
            out.add(new LdcInsnNode(String.valueOf(constant)));
        }
    }

    private Type typeOfConstant(Object constant) {
        if (constant instanceof Integer) return Type.INT_TYPE;
        if (constant instanceof Long) return Type.LONG_TYPE;
        if (constant instanceof Float) return Type.FLOAT_TYPE;
        if (constant instanceof Double) return Type.DOUBLE_TYPE;
        if (constant instanceof String) return Type.getType(String.class);
        return Type.getType(Object.class);
    }

    private void appendValue(InsnList out, Type type) {
        String desc = switch (type.getSort()) {
            case Type.BOOLEAN -> "(Z)Ljava/lang/StringBuilder;";
            case Type.CHAR -> "(C)Ljava/lang/StringBuilder;";
            case Type.BYTE, Type.SHORT, Type.INT -> "(I)Ljava/lang/StringBuilder;";
            case Type.LONG -> "(J)Ljava/lang/StringBuilder;";
            case Type.FLOAT -> "(F)Ljava/lang/StringBuilder;";
            case Type.DOUBLE -> "(D)Ljava/lang/StringBuilder;";
            case Type.OBJECT -> "java/lang/String".equals(type.getInternalName())
                ? "(Ljava/lang/String;)Ljava/lang/StringBuilder;"
                : "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
            default -> "(Ljava/lang/Object;)Ljava/lang/StringBuilder;";
        };
        out.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/StringBuilder", "append", desc, false));
    }

    private AbstractInsnNode pushInt(int value) {
        if (value >= -1 && value <= 5) {
            return new InsnNode(Opcodes.ICONST_0 + value);
        } else if (value >= Byte.MIN_VALUE && value <= Byte.MAX_VALUE) {
            return new IntInsnNode(Opcodes.BIPUSH, value);
        } else if (value >= Short.MIN_VALUE && value <= Short.MAX_VALUE) {
            return new IntInsnNode(Opcodes.SIPUSH, value);
        }
        return new LdcInsnNode(value);
    }

    private boolean booleanOption(dev.nekoobfuscator.api.config.TransformConfig config, String key, boolean defaultValue) {
        if (config == null) return defaultValue;
        Object value = config.options().get(key);
        return value instanceof Boolean bool ? bool : defaultValue;
    }

    private int countExistingEncFields(L1Class clazz) {
        int count = 0;
        for (FieldNode fn : clazz.asmNode().fields) {
            if (fn.name.startsWith("__e")
                    && ("[B".equals(fn.desc) || "Ljava/lang/String;".equals(fn.desc))) {
                count++;
            }
        }
        return count;
    }

    private void materializeConstantStringFields(L1Class clazz) {
        InsnList init = new InsnList();
        boolean changed = false;
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.name.startsWith("__i") || field.name.startsWith("__e")) {
                continue;
            }
            if (!"Ljava/lang/String;".equals(field.desc) || !(field.value instanceof String value)) {
                continue;
            }
            field.value = null;
            init.add(new LdcInsnNode(value));
            init.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), field.name, field.desc));
            changed = true;
        }
        if (!changed) return;

        MethodNode clinit = ensureClassInit(clazz);
        insertBeforeReturn(clinit, init);
        clinit.maxStack = Math.max(clinit.maxStack, 1);
        clazz.markDirty();
    }

    private MethodNode ensureClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                return method;
            }
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions = new InsnList();
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private void insertBeforeReturn(MethodNode method, InsnList init) {
        AbstractInsnNode target = null;
        for (AbstractInsnNode insn = method.instructions.getLast(); insn != null; insn = insn.getPrevious()) {
            if (insn.getOpcode() == Opcodes.RETURN) {
                target = insn;
                break;
            }
        }
        if (target == null) {
            method.instructions.add(init);
            method.instructions.add(new InsnNode(Opcodes.RETURN));
        } else {
            method.instructions.insertBefore(target, init);
        }
    }

    private int metadataAccess(ClassNode classNode) {
        return (classNode.access & Opcodes.ACC_INTERFACE) != 0
            ? INTERFACE_METADATA_ACCESS
            : CLASS_METADATA_ACCESS;
    }

    private record SiteKeyInfo(long siteInsnKey, KeyDispatcherSupport.Profile keyDispatcher,
                               int keyComponent, boolean indyUseFlowKey) {}
}
