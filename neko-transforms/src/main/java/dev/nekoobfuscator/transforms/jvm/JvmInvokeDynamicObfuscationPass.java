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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.objectweb.asm.Handle;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FieldNode;
import org.objectweb.asm.tree.IincInsnNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.IntInsnNode;
import org.objectweb.asm.tree.InvokeDynamicInsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.LdcInsnNode;
import org.objectweb.asm.tree.LookupSwitchInsnNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.TypeInsnNode;
import org.objectweb.asm.tree.VarInsnNode;

/**
 * Flow-keyed invokedynamic reference obfuscation for CFF-protected code.
 */
public final class JvmInvokeDynamicObfuscationPass implements TransformPass {
    public static final String ID = "invokeDynamic";

    private static final int KIND_GETFIELD = 1;
    private static final int KIND_PUTFIELD = 2;
    private static final int KIND_GETSTATIC = 3;
    private static final int KIND_PUTSTATIC = 4;
    private static final int KIND_INVOKEVIRTUAL = 5;
    private static final int KIND_INVOKESTATIC = 6;
    private static final int KIND_INVOKEINTERFACE = 7;
    private static final int KIND_INVOKESPECIAL = 8;

    private static final long OWNER_SEED = 0x494E44594F574E31L;
    private static final long NAME_SEED = 0x494E44594E414D31L;
    private static final long DESC_SEED = 0x494E445944455331L;
    private static final long CHAR_STRIDE = 0xD1342543DE82EF95L;

    private static final String LOOKUP = "java/lang/invoke/MethodHandles$Lookup";
    private static final String METHOD_HANDLES = "java/lang/invoke/MethodHandles";
    private static final String METHOD_HANDLE = "java/lang/invoke/MethodHandle";
    private static final String METHOD_TYPE = "java/lang/invoke/MethodType";
    private static final String CALL_SITE = "java/lang/invoke/CallSite";
    private static final String MUTABLE_CALL_SITE = "java/lang/invoke/MutableCallSite";
    private static final String OBJECT = "java/lang/Object";
    private static final String OBJECT_ARRAY_DESC = "[Ljava/lang/Object;";
    private static final String CLASS = "java/lang/Class";
    private static final String LONG = "java/lang/Long";
    private static final String CONCURRENT_HASH_MAP = "java/util/concurrent/ConcurrentHashMap";

    private static final String BSM_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/String;JJ)Ljava/lang/invoke/CallSite;";
    private static final String RESOLVER_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/String;JJ[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String DECODE_DESC = "(Ljava/lang/String;JJJ)Ljava/lang/String;";
    private static final String MIX_DESC = "(JJ)J";
    private static final String FLOW_DESC = "(IIIIJJI)J";
    private static final String HELPERS_KEY = "invokeDynamic.classHelpers";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String name() {
        return "JVM InvokeDynamic Reference Obfuscation";
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
        if (metadata == null) {
            return;
        }
        if (metadata.classKeyTable() == null) {
            throw new IllegalStateException("invokeDynamic requires CFF class key table metadata for " + methodKey);
        }

        MethodNode mn = method.asmNode();
        HelperNames helpers = null;
        int transformed = 0;
        int ordinal = 0;
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            ControlFlowFlatteningPass.CffInstructionState state = metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException("invokeDynamic cannot bind CFF state for " + methodKey);
            }
            SiteSpec spec = siteSpec(pctx, insn);
            if (spec == null) continue;
            if (helpers == null) {
                helpers = ensureHelpers(pctx, clazz, metadata);
            }
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++);
            long salt = JvmPassBytecode.mix(siteSeed ^ 0x494E445953414C54L, spec.owner().hashCode());
            long token = JvmPassBytecode.mix(siteSeed ^ 0x494E4459544F4B31L, salt);
            long flow = liveIndyWord(metadata, state, siteSeed);

            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                indyName(siteSeed, spec.kind()),
                appendLongLong(spec.indyDesc()),
                new Handle(
                    Opcodes.H_INVOKESTATIC,
                    clazz.name(),
                    helpers.bootstrap(),
                    BSM_DESC,
                    (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0
                ),
                encrypt(payload(spec), siteSeed ^ salt ^ OWNER_SEED, token, flow),
                siteSeed,
                salt
            );

            boolean interfaceOwner = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
            InsnList replacement = new InsnList();
            emitDynamicIndyLong(
                replacement,
                clazz.name(),
                helpers,
                token,
                siteSeed ^ 0x494E4459544F4B44L,
                metadata,
                state,
                interfaceOwner
            );
            emitLiveIndyWord(
                replacement,
                clazz.name(),
                helpers,
                siteSeed,
                metadata,
                state,
                interfaceOwner
            );
            replacement.add(indy);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            mn.instructions.remove(insn);
            transformed++;
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
                "cff-keyed-indy-reference-sites-" + transformed
            );
        }
    }

    private String payload(SiteSpec spec) {
        return spec.kind() + "\n" + spec.owner() + "\n" + spec.name() + "\n" + spec.desc();
    }

    private SiteSpec siteSpec(PipelineContext pctx, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            if ("<init>".equals(call.name) || "<clinit>".equals(call.name)) return null;
            if (TransformGuards.isRuntimeClass(call.owner) || TransformGuards.isGeneratedName(call.name)) return null;
            int kind = switch (call.getOpcode()) {
                case Opcodes.INVOKEVIRTUAL -> KIND_INVOKEVIRTUAL;
                case Opcodes.INVOKESTATIC -> KIND_INVOKESTATIC;
                case Opcodes.INVOKEINTERFACE -> KIND_INVOKEINTERFACE;
                case Opcodes.INVOKESPECIAL -> KIND_INVOKESPECIAL;
                default -> 0;
            };
            if (kind == 0) return null;
            String indyDesc = methodIndyDescriptor(call.owner, call.desc, kind == KIND_INVOKESTATIC);
            return new SiteSpec(kind, call.owner, call.name, call.desc, indyDesc);
        }
        if (insn instanceof FieldInsnNode field) {
            if (TransformGuards.isRuntimeClass(field.owner) || TransformGuards.isGeneratedName(field.name)) return null;
            int kind = switch (field.getOpcode()) {
                case Opcodes.GETFIELD -> KIND_GETFIELD;
                case Opcodes.PUTFIELD -> KIND_PUTFIELD;
                case Opcodes.GETSTATIC -> KIND_GETSTATIC;
                case Opcodes.PUTSTATIC -> KIND_PUTSTATIC;
                default -> 0;
            };
            if (kind == 0) return null;
            makeWritableFinalStoreTarget(pctx, field, kind);
            return new SiteSpec(kind, field.owner, field.name, "()" + field.desc, fieldIndyDescriptor(field, kind));
        }
        return null;
    }

    private void makeWritableFinalStoreTarget(PipelineContext pctx, FieldInsnNode field, int kind) {
        if (kind != KIND_PUTFIELD && kind != KIND_PUTSTATIC) return;
        L1Class owner = pctx.classMap().get(field.owner);
        if (owner == null) return;
        for (FieldNode declared : owner.asmNode().fields) {
            if (declared.name.equals(field.name) && declared.desc.equals(field.desc)) {
                if ((declared.access & Opcodes.ACC_FINAL) != 0) {
                    declared.access &= ~Opcodes.ACC_FINAL;
                    declared.value = null;
                    owner.markDirty();
                }
                return;
            }
        }
    }

    private String methodIndyDescriptor(String owner, String desc, boolean isStatic) {
        Type method = Type.getMethodType(desc);
        Type[] args = method.getArgumentTypes();
        Type[] indyArgs;
        if (isStatic) {
            indyArgs = args;
        } else {
            indyArgs = new Type[args.length + 1];
            indyArgs[0] = ownerType(owner);
            System.arraycopy(args, 0, indyArgs, 1, args.length);
        }
        return Type.getMethodDescriptor(method.getReturnType(), indyArgs);
    }

    private String fieldIndyDescriptor(FieldInsnNode field, int kind) {
        Type owner = ownerType(field.owner);
        Type value = Type.getType(field.desc);
        return switch (kind) {
            case KIND_GETFIELD -> Type.getMethodDescriptor(value, owner);
            case KIND_PUTFIELD -> Type.getMethodDescriptor(Type.VOID_TYPE, owner, value);
            case KIND_GETSTATIC -> Type.getMethodDescriptor(value);
            case KIND_PUTSTATIC -> Type.getMethodDescriptor(Type.VOID_TYPE, value);
            default -> throw new IllegalArgumentException("Unknown field kind " + kind);
        };
    }

    private Type ownerType(String owner) {
        return owner.startsWith("[") ? Type.getType(owner) : Type.getObjectType(owner);
    }

    private String appendLongLong(String desc) {
        Type method = Type.getMethodType(desc);
        Type[] args = method.getArgumentTypes();
        Type[] out = new Type[args.length + 2];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = Type.LONG_TYPE;
        out[args.length + 1] = Type.LONG_TYPE;
        return Type.getMethodDescriptor(method.getReturnType(), out);
    }

    @SuppressWarnings("unchecked")
    private HelperNames ensureHelpers(
        PipelineContext pctx,
        L1Class clazz,
        ControlFlowFlatteningPass.CffMethodMetadata metadata
    ) {
        Map<String, HelperNames> helpersByClass = pctx.getPassData(HELPERS_KEY);
        if (helpersByClass == null) {
            helpersByClass = new HashMap<>();
            pctx.putPassData(HELPERS_KEY, helpersByClass);
        }
        HelperNames existing = helpersByClass.get(clazz.name());
        if (existing != null) {
            return existing;
        }
        String bootstrap = uniqueMethodName(clazz, "__neko_indy_bsm");
        String resolver = uniqueMethodName(clazz, "__neko_indy_resolve");
        String decode = uniqueMethodName(clazz, "__neko_indy_decode");
        String mix = uniqueMethodName(clazz, "__neko_indy_mix");
        String flow = uniqueMethodName(clazz, "__neko_indy_flow");
        String cache = ensureCacheField(clazz);
        boolean interfaceOwner = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
        clazz.asmNode().methods.add(emitBootstrap(clazz.name(), bootstrap, resolver, interfaceOwner));
        clazz.asmNode().methods.add(emitResolver(clazz.name(), resolver, decode, cache, interfaceOwner));
        clazz.asmNode().methods.add(emitDecode(clazz.name(), decode, mix, interfaceOwner));
        clazz.asmNode().methods.add(emitMix(mix));
        clazz.asmNode().methods.add(emitFlow(clazz.name(), flow, mix, metadata.classKeyTable(), interfaceOwner));
        installInjectedMethodReflectionFilter(
            pctx,
            clazz.name(),
            List.of(bootstrap, resolver, decode, mix, flow)
        );
        installInjectedFieldReflectionFilter(
            pctx,
            clazz.name(),
            List.of(cache)
        );
        installInjectedStackTraceFilter(
            pctx,
            clazz.name(),
            List.of(bootstrap, resolver, decode, mix, flow)
        );
        HelperNames created = new HelperNames(bootstrap, resolver, decode, mix, flow, cache);
        helpersByClass.put(clazz.name(), created);
        return created;
    }

    private String ensureCacheField(L1Class clazz) {
        String name = uniqueFieldName(clazz, "__neko_n_indy_cache");
        int access = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0
            ? Opcodes.ACC_PUBLIC | Opcodes.ACC_STATIC | Opcodes.ACC_FINAL | Opcodes.ACC_SYNTHETIC
            : Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        clazz.asmNode().fields.add(new FieldNode(
            access,
            name,
            "Ljava/util/concurrent/ConcurrentHashMap;",
            null,
            null
        ));
        MethodNode clinit = findOrCreateClassInit(clazz);
        InsnList init = new InsnList();
        init.add(new TypeInsnNode(Opcodes.NEW, CONCURRENT_HASH_MAP));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CONCURRENT_HASH_MAP, "<init>", "()V", false));
        init.add(new FieldInsnNode(Opcodes.PUTSTATIC, clazz.name(), name, "Ljava/util/concurrent/ConcurrentHashMap;"));
        clinit.instructions.insert(init);
        clinit.maxStack = Math.max(clinit.maxStack, 2);
        clazz.markDirty();
        return name;
    }

    private MethodNode findOrCreateClassInit(L1Class clazz) {
        for (MethodNode method : clazz.asmNode().methods) {
            if ("<clinit>".equals(method.name) && "()V".equals(method.desc)) {
                return method;
            }
        }
        MethodNode clinit = new MethodNode(Opcodes.ACC_STATIC, "<clinit>", "()V", null, null);
        clinit.instructions.add(new InsnNode(Opcodes.RETURN));
        clinit.maxStack = 0;
        clinit.maxLocals = 0;
        clazz.asmNode().methods.add(clinit);
        return clinit;
    }

    private String uniqueFieldName(L1Class clazz, String base) {
        String name = base;
        int i = 0;
        while (hasField(clazz, name)) {
            name = base + "$" + (++i);
        }
        return name;
    }

    private boolean hasField(L1Class clazz, String name) {
        for (FieldNode field : clazz.asmNode().fields) {
            if (field.name.equals(name)) return true;
        }
        return false;
    }

    private void installInjectedStackTraceFilter(
        PipelineContext pctx,
        String helperOwner,
        List<String> helperMethods
    ) {
        for (L1Class target : pctx.classMap().values()) {
            for (MethodNode method : target.asmNode().methods) {
                if (method.instructions == null) continue;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (!(insn instanceof MethodInsnNode call) || !isStackTraceCall(call)) continue;
                    InsnList filter = injectedStackTraceFilter(method, helperOwner, helperMethods);
                    JvmKeyDispatchPass.markGenerated(pctx, filter);
                    method.instructions.insert(call, filter);
                    target.markDirty();
                }
            }
        }
    }

    private boolean isStackTraceCall(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "getStackTrace".equals(call.name)
            && "()[Ljava/lang/StackTraceElement;".equals(call.desc)
            && ("java/lang/Throwable".equals(call.owner) || "java/lang/Thread".equals(call.owner));
    }

    private InsnList injectedStackTraceFilter(MethodNode mn, String helperOwner, List<String> helperMethods) {
        int sourceLocal = mn.maxLocals++;
        int filteredLocal = mn.maxLocals++;
        int indexLocal = mn.maxLocals++;
        int writeLocal = mn.maxLocals++;
        int frameLocal = mn.maxLocals++;
        LabelNode loop = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode end = new LabelNode();
        InsnList insns = new InsnList();

        insns.add(new VarInsnNode(Opcodes.ASTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/StackTraceElement"));
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
        insns.add(new VarInsnNode(Opcodes.ASTORE, frameLocal));
        emitInjectedFrameTest(insns, frameLocal, helperOwner, helperMethods, skip);
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(new IincInsnNode(writeLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
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
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/StackTraceElement;"));
        mn.maxStack = Math.max(mn.maxStack, 6);
        return insns;
    }

    private void emitInjectedFrameTest(
        InsnList insns,
        int frameLocal,
        String helperOwner,
        List<String> helperMethods,
        LabelNode skip
    ) {
        String ownerName = helperOwner.replace('/', '.');
        for (String helperMethod : helperMethods) {
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StackTraceElement",
                "getMethodName",
                "()Ljava/lang/String;",
                false
            ));
            insns.add(new LdcInsnNode(helperMethod));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false
            ));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, next));
            insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/StackTraceElement",
                "getClassName",
                "()Ljava/lang/String;",
                false
            ));
            insns.add(new LdcInsnNode(ownerName));
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
    }

    private void installInjectedMethodReflectionFilter(
        PipelineContext pctx,
        String helperOwner,
        List<String> helperMethods
    ) {
        for (L1Class target : pctx.classMap().values()) {
            for (MethodNode method : target.asmNode().methods) {
                if (method.instructions == null) continue;
                String activeOwner = null;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Type type
                        && type.getSort() == Type.OBJECT) {
                        activeOwner = type.getInternalName();
                        continue;
                    }
                    if (!isMethodReflectionResult(insn) || !helperOwner.equals(activeOwner)) continue;
                    InsnList filter = injectedMethodFilter(method, helperOwner, helperMethods);
                    JvmKeyDispatchPass.markGenerated(pctx, filter);
                    method.instructions.insert(insn, filter);
                    target.markDirty();
                    activeOwner = null;
                }
            }
        }
    }

    private boolean isMethodReflectionResult(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            return isMethodReflectionCall(call);
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            return Type.getMethodType(indy.desc).getReturnType().getDescriptor().equals("[Ljava/lang/reflect/Method;");
        }
        return false;
    }

    private boolean isMethodReflectionCall(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/Class".equals(call.owner)
            && ("getMethods".equals(call.name) || "getDeclaredMethods".equals(call.name))
            && "()[Ljava/lang/reflect/Method;".equals(call.desc);
    }

    private void installInjectedFieldReflectionFilter(
        PipelineContext pctx,
        String helperOwner,
        List<String> helperFields
    ) {
        for (L1Class target : pctx.classMap().values()) {
            for (MethodNode method : target.asmNode().methods) {
                if (method.instructions == null) continue;
                String activeOwner = null;
                for (AbstractInsnNode insn = method.instructions.getFirst(); insn != null; insn = insn.getNext()) {
                    if (insn instanceof LdcInsnNode ldc
                        && ldc.cst instanceof Type type
                        && type.getSort() == Type.OBJECT) {
                        activeOwner = type.getInternalName();
                        continue;
                    }
                    if (!isFieldReflectionResult(insn) || !helperOwner.equals(activeOwner)) continue;
                    InsnList filter = injectedFieldFilter(method, helperOwner, helperFields);
                    JvmKeyDispatchPass.markGenerated(pctx, filter);
                    method.instructions.insert(insn, filter);
                    target.markDirty();
                    activeOwner = null;
                }
            }
        }
    }

    private boolean isFieldReflectionResult(AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            return isFieldReflectionCall(call);
        }
        if (insn instanceof InvokeDynamicInsnNode indy) {
            return Type.getMethodType(indy.desc).getReturnType().getDescriptor().equals("[Ljava/lang/reflect/Field;");
        }
        return false;
    }

    private boolean isFieldReflectionCall(MethodInsnNode call) {
        return call.getOpcode() == Opcodes.INVOKEVIRTUAL
            && "java/lang/Class".equals(call.owner)
            && ("getFields".equals(call.name) || "getDeclaredFields".equals(call.name))
            && "()[Ljava/lang/reflect/Field;".equals(call.desc);
    }

    private InsnList injectedFieldFilter(MethodNode mn, String helperOwner, List<String> helperFields) {
        int sourceLocal = mn.maxLocals++;
        int filteredLocal = mn.maxLocals++;
        int indexLocal = mn.maxLocals++;
        int writeLocal = mn.maxLocals++;
        int fieldLocal = mn.maxLocals++;
        LabelNode loop = new LabelNode();
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
        emitInjectedFieldTest(insns, fieldLocal, helperOwner, helperFields, skip);
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
        String helperOwner,
        List<String> helperFields,
        LabelNode skip
    ) {
        String ownerName = helperOwner.replace('/', '.');
        for (String helperField : helperFields) {
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, fieldLocal));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Field",
                "getName",
                "()Ljava/lang/String;",
                false
            ));
            insns.add(new LdcInsnNode(helperField));
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
            insns.add(new LdcInsnNode(ownerName));
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
    }

    private InsnList injectedMethodFilter(MethodNode mn, String helperOwner, List<String> helperMethods) {
        int sourceLocal = mn.maxLocals++;
        int filteredLocal = mn.maxLocals++;
        int indexLocal = mn.maxLocals++;
        int writeLocal = mn.maxLocals++;
        int methodLocal = mn.maxLocals++;
        LabelNode loop = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode end = new LabelNode();
        InsnList insns = new InsnList();

        insns.add(new VarInsnNode(Opcodes.ASTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, "java/lang/reflect/Method"));
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
        insns.add(new VarInsnNode(Opcodes.ASTORE, methodLocal));
        LabelNode syntheticDone = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Method",
            "isSynthetic",
            "()Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, syntheticDone));
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/reflect/Method",
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
        emitInjectedMethodTest(insns, methodLocal, helperOwner, helperMethods, skip);
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(new IincInsnNode(writeLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
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
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/reflect/Method;"));
        mn.maxStack = Math.max(mn.maxStack, 6);
        return insns;
    }

    private void emitInjectedMethodTest(
        InsnList insns,
        int methodLocal,
        String helperOwner,
        List<String> helperMethods,
        LabelNode skip
    ) {
        String ownerName = helperOwner.replace('/', '.');
        for (String helperMethod : helperMethods) {
            LabelNode next = new LabelNode();
            insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
                "getName",
                "()Ljava/lang/String;",
                false
            ));
            insns.add(new LdcInsnNode(helperMethod));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false
            ));
            insns.add(new JumpInsnNode(Opcodes.IFEQ, next));
            insns.add(new VarInsnNode(Opcodes.ALOAD, methodLocal));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/reflect/Method",
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
            insns.add(new LdcInsnNode(ownerName));
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
    }

    private String uniqueMethodName(L1Class clazz, String base) {
        String name = base;
        int i = 0;
        while (hasMethod(clazz, name)) {
            name = base + "$" + (++i);
        }
        return name;
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private MethodNode emitBootstrap(String owner, String name, String resolverName, boolean interfaceOwner) {
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name,
            BSM_DESC,
            null,
            new String[] {"java/lang/Throwable"}
        );
        InsnList insns = mn.instructions;
        int callSiteLocal = 8;
        int handleLocal = 9;

        insns.add(new TypeInsnNode(Opcodes.NEW, MUTABLE_CALL_SITE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, MUTABLE_CALL_SITE, "<init>",
            "(Ljava/lang/invoke/MethodType;)V", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, callSiteLocal));

        insns.add(new LdcInsnNode(new Handle(
            Opcodes.H_INVOKESTATIC,
            owner,
            resolverName,
            RESOLVER_DESC,
            interfaceOwner
        )));
        insns.add(new LdcInsnNode(Type.getType(OBJECT_ARRAY_DESC)));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "parameterCount", "()I", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asCollector",
            "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;", false));
        JvmPassBytecode.pushInt(insns, 0);
        JvmPassBytecode.pushInt(insns, 6);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        arrayStore(insns, 0, () -> insns.add(new VarInsnNode(Opcodes.ALOAD, 0)));
        arrayStore(insns, 1, () -> insns.add(new VarInsnNode(Opcodes.ALOAD, callSiteLocal)));
        arrayStore(insns, 2, () -> insns.add(new VarInsnNode(Opcodes.ALOAD, 2)));
        arrayStore(insns, 3, () -> insns.add(new VarInsnNode(Opcodes.ALOAD, 3)));
        arrayStore(insns, 4, () -> {
            insns.add(new VarInsnNode(Opcodes.LLOAD, 4));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LONG, "valueOf", "(J)Ljava/lang/Long;", false));
        });
        arrayStore(insns, 5, () -> {
            insns.add(new VarInsnNode(Opcodes.LLOAD, 6));
            insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LONG, "valueOf", "(J)Ljava/lang/Long;", false));
        });
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "insertArguments",
            "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "explicitCastArguments",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, callSiteLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_CALL_SITE, "setTarget",
            "(Ljava/lang/invoke/MethodHandle;)V", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, callSiteLocal));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 10;
        mn.maxLocals = 10;
        return mn;
    }

    private MethodNode emitResolver(
        String owner,
        String name,
        String decodeName,
        String cacheName,
        boolean interfaceOwner
    ) {
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name,
            RESOLVER_DESC,
            null,
            new String[] {"java/lang/Throwable"}
        );
        InsnList insns = mn.instructions;
        int argsLocal = 8;
        int indexLocal = 9;
        int tokenLocal = 10;
        int flowLocal = 12;
        int payloadLocal = 14;
        int ownerLocal = 15;
        int nameLocal = 16;
        int descLocal = 17;
        int classLocal = 18;
        int typeLocal = 19;
        int handleLocal = 20;
        int cacheKeyLocal = 21;
        int sep1Local = 22;
        int sep2Local = 23;
        int sep3Local = 24;
        int kindLocal = 25;
        int classKeyLocal = 26;
        int typeKeyLocal = 27;

        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        loadLongArrayArg(insns, argsLocal, indexLocal);
        insns.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
        insns.add(new IincInsnNode(indexLocal, 1));
        loadLongArrayArg(insns, argsLocal, indexLocal);
        insns.add(new VarInsnNode(Opcodes.LSTORE, flowLocal));

        LabelNode resolve = new LabelNode();
        LabelNode afterResolve = new LabelNode();
        LabelNode adapt = new LabelNode();
        emitLiveCacheKey(insns, cacheKeyLocal, tokenLocal, flowLocal);
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, cacheName, "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheKeyLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, METHOD_HANDLE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, resolve));
        insns.add(new JumpInsnNode(Opcodes.GOTO, adapt));
        insns.add(resolve);

        emitDecodeCall(insns, owner, decodeName, 3, 4, 6, OWNER_SEED, tokenLocal, flowLocal, interfaceOwner);
        insns.add(new VarInsnNode(Opcodes.ASTORE, payloadLocal));
        emitParsePayload(insns, payloadLocal, ownerLocal, nameLocal, descLocal, kindLocal, sep1Local, sep2Local, sep3Local);

        emitResolveClassWithCache(insns, owner, cacheName, ownerLocal, classLocal, classKeyLocal);
        emitResolveMethodTypeWithCache(insns, owner, cacheName, descLocal, typeLocal, typeKeyLocal);

        LabelNode getField = new LabelNode();
        LabelNode putField = new LabelNode();
        LabelNode getStatic = new LabelNode();
        LabelNode putStatic = new LabelNode();
        LabelNode virtual = new LabelNode();
        LabelNode statik = new LabelNode();
        LabelNode iface = new LabelNode();
        LabelNode special = new LabelNode();
        LabelNode badKind = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, kindLocal));
        insns.add(new LookupSwitchInsnNode(
            badKind,
            new int[] {
                KIND_GETFIELD, KIND_PUTFIELD, KIND_GETSTATIC, KIND_PUTSTATIC,
                KIND_INVOKEVIRTUAL, KIND_INVOKESTATIC, KIND_INVOKEINTERFACE, KIND_INVOKESPECIAL
            },
            new LabelNode[] {getField, putField, getStatic, putStatic, virtual, statik, iface, special}
        ));

        insns.add(getField);
        emitFindField(insns, "findGetter", classLocal, nameLocal, typeLocal);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(putField);
        emitFindField(insns, "findSetter", classLocal, nameLocal, typeLocal);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(getStatic);
        emitFindField(insns, "findStaticGetter", classLocal, nameLocal, typeLocal);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(putStatic);
        emitFindField(insns, "findStaticSetter", classLocal, nameLocal, typeLocal);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(virtual);
        emitFindMethod(insns, "findVirtual", classLocal, nameLocal, typeLocal, false);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(statik);
        emitFindMethod(insns, "findStatic", classLocal, nameLocal, typeLocal, false);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(iface);
        emitFindMethod(insns, "findVirtual", classLocal, nameLocal, typeLocal, false);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(special);
        emitFindMethod(insns, "findSpecial", classLocal, nameLocal, typeLocal, true);
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.GOTO, afterResolve));

        insns.add(badKind);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/IllegalStateException"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new LdcInsnNode("Bad invokedynamic reference kind"));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/IllegalStateException", "<init>",
            "(Ljava/lang/String;)V", false));
        insns.add(new InsnNode(Opcodes.ATHROW));

        insns.add(afterResolve);
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, cacheName, "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "putIfAbsent",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(adapt);
        emitAdaptTarget(insns, handleLocal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new LdcInsnNode(Type.getType(OBJECT_ARRAY_DESC)));
        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asSpreader",
            "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "invoke",
            "([Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 16;
        mn.maxLocals = 28;
        return mn;
    }

    private void emitAdaptTarget(InsnList insns, int handleLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "type",
            "()Ljava/lang/invoke/MethodType;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "parameterCount", "()I", false));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, CLASS));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, LONG, "TYPE", "Ljava/lang/Class;"));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, LONG, "TYPE", "Ljava/lang/Class;"));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "dropArguments",
            "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "explicitCastArguments",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_CALL_SITE, "setTarget",
            "(Ljava/lang/invoke/MethodHandle;)V", false));
    }

    private void emitLiveCacheKey(InsnList insns, int cacheKeyLocal, int tokenLocal, int flowLocal) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 4));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 6));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "hashCode", "()I", false));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LONG, "valueOf", "(J)Ljava/lang/Long;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cacheKeyLocal));
    }

    private void emitParsePayload(
        InsnList insns,
        int payloadLocal,
        int ownerLocal,
        int nameLocal,
        int descLocal,
        int kindLocal,
        int sep1Local,
        int sep2Local,
        int sep3Local
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, payloadLocal));
        JvmPassBytecode.pushInt(insns, '\n');
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(I)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sep1Local));

        insns.add(new VarInsnNode(Opcodes.ALOAD, payloadLocal));
        JvmPassBytecode.pushInt(insns, '\n');
        insns.add(new VarInsnNode(Opcodes.ILOAD, sep1Local));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sep2Local));

        insns.add(new VarInsnNode(Opcodes.ALOAD, payloadLocal));
        JvmPassBytecode.pushInt(insns, '\n');
        insns.add(new VarInsnNode(Opcodes.ILOAD, sep2Local));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "indexOf", "(II)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sep3Local));

        insns.add(new VarInsnNode(Opcodes.ALOAD, payloadLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ILOAD, sep1Local));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
            "(II)Ljava/lang/String;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Integer", "parseInt",
            "(Ljava/lang/String;)I", false));
        insns.add(new VarInsnNode(Opcodes.ISTORE, kindLocal));

        emitSubstring(insns, payloadLocal, sep1Local, sep2Local, ownerLocal);
        emitSubstring(insns, payloadLocal, sep2Local, sep3Local, nameLocal);

        insns.add(new VarInsnNode(Opcodes.ALOAD, payloadLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sep3Local));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
            "(I)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, descLocal));
    }

    private void emitSubstring(InsnList insns, int payloadLocal, int startSepLocal, int endSepLocal, int outLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, payloadLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, startSepLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, endSepLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "substring",
            "(II)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, outLocal));
    }

    private void emitResolveClassWithCache(
        InsnList insns,
        String owner,
        String cacheName,
        int ownerLocal,
        int classLocal,
        int classKeyLocal
    ) {
        LabelNode miss = new LabelNode();
        LabelNode done = new LabelNode();

        insns.add(new LdcInsnNode("\u0001"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
            "(Ljava/lang/String;)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, classKeyLocal));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, cacheName, "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classKeyLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, CLASS));
        insns.add(new VarInsnNode(Opcodes.ASTORE, classLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));

        insns.add(miss);
        insns.add(new VarInsnNode(Opcodes.ALOAD, ownerLocal));
        JvmPassBytecode.pushInt(insns, '/');
        JvmPassBytecode.pushInt(insns, '.');
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "replace",
            "(CC)Ljava/lang/String;", false));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        emitLookupClassLoader(insns);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, CLASS, "forName",
            "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, classLocal));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, cacheName, "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "putIfAbsent",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(done);
    }

    private void emitResolveMethodTypeWithCache(
        InsnList insns,
        String owner,
        String cacheName,
        int descLocal,
        int typeLocal,
        int typeKeyLocal
    ) {
        LabelNode miss = new LabelNode();
        LabelNode done = new LabelNode();

        insns.add(new LdcInsnNode("\u0002"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, descLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "concat",
            "(Ljava/lang/String;)Ljava/lang/String;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, typeKeyLocal));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, cacheName, "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, typeKeyLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, METHOD_TYPE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, typeLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, typeLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, miss));
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));

        insns.add(miss);
        insns.add(new VarInsnNode(Opcodes.ALOAD, descLocal));
        emitLookupClassLoader(insns);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_TYPE, "fromMethodDescriptorString",
            "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, typeLocal));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, owner, cacheName, "Ljava/util/concurrent/ConcurrentHashMap;"));
        insns.add(new VarInsnNode(Opcodes.ALOAD, typeKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, typeLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "putIfAbsent",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(done);
    }

    private void emitFindField(InsnList insns, String method, int classLocal, int nameLocal, int typeLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, typeLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "returnType", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, method,
            "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
    }

    private void emitFindMethod(InsnList insns, String method, int classLocal, int nameLocal, int typeLocal, boolean special) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, nameLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, typeLocal));
        if (special) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, method,
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        } else {
            insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, method,
                "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        }
    }

    private void emitLookupClassLoader(InsnList insns) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LOOKUP, "lookupClass", "()Ljava/lang/Class;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CLASS, "getClassLoader", "()Ljava/lang/ClassLoader;", false));
    }

    private void emitDecodeCall(
        InsnList insns,
        String owner,
        String decodeName,
        int stringLocal,
        int seedLocal,
        int saltLocal,
        long label,
        int tokenLocal,
        int flowLocal,
        boolean interfaceOwner
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, saltLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, label);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, decodeName, DECODE_DESC, interfaceOwner));
    }

    private void loadLongArrayArg(InsnList insns, int argsLocal, int indexLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, LONG));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LONG, "longValue", "()J", false));
    }

    private MethodNode emitDecode(String owner, String name, String mixName, boolean interfaceOwner) {
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name,
            DECODE_DESC,
            null,
            null
        );
        InsnList insns = mn.instructions;
        int charsLocal = 7;
        int indexLocal = 8;
        int maskLocal = 9;
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();

        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, charsLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, charsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 5));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 1));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, CHAR_STRIDE);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, mixName, MIX_DESC, interfaceOwner));
        insns.add(new VarInsnNode(Opcodes.LSTORE, maskLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, charsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.CALOAD));
        insns.add(new VarInsnNode(Opcodes.LLOAD, maskLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, maskLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2C));
        insns.add(new InsnNode(Opcodes.CASTORE));
        insns.add(new IincInsnNode(indexLocal, 1));
        insns.add(new JumpInsnNode(Opcodes.GOTO, loop));
        insns.add(done);
        insns.add(new TypeInsnNode(Opcodes.NEW, "java/lang/String"));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new VarInsnNode(Opcodes.ALOAD, charsLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, "java/lang/String", "<init>", "([C)V", false));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 10;
        mn.maxLocals = 11;
        return mn;
    }

    private MethodNode emitMix(String name) {
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name,
            MIX_DESC,
            null,
            null
        );
        InsnList insns = mn.instructions;
        int z = 4;
        insns.add(new VarInsnNode(Opcodes.LLOAD, 0));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 2));
        insns.add(new InsnNode(Opcodes.LADD));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, z));
        mixRound(insns, z, 30, 0xBF58476D1CE4E5B9L);
        mixRound(insns, z, 27, 0x94D049BB133111EBL);
        insns.add(new VarInsnNode(Opcodes.LLOAD, z));
        insns.add(new VarInsnNode(Opcodes.LLOAD, z));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LRETURN));
        mn.maxStack = 6;
        mn.maxLocals = 6;
        return mn;
    }

    private void mixRound(InsnList insns, int z, int shift, long mul) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, z));
        insns.add(new VarInsnNode(Opcodes.LLOAD, z));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, mul);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new VarInsnNode(Opcodes.LSTORE, z));
    }

    private void arrayStore(InsnList insns, int index, Runnable valueEmitter) {
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, index);
        valueEmitter.run();
        insns.add(new InsnNode(Opcodes.AASTORE));
    }

    private long liveIndyWord(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long siteSeed
    ) {
        int x = (state.guardKey() + nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x494E44474731L))) ^
            state.pathKey();
        x += x >>> shift(siteSeed, 9);
        x = (x ^ state.blockKey()) * (nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x494E444D554CL)) | 1);
        x ^= state.pcToken() + nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x494E44504331L));
        int idx = (x + state.state() + nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x494E445441424CL))) &
            (metadata.classKeyTable().values().length - 1);
        x += metadata.classKeyTable().values()[idx] ^ nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x494E445456414CL));
        x ^= x >>> shift(siteSeed, 21);
        return (((long) x) & 0xFFFFFFFFL) ^ state.methodKey() ^ siteSeed;
    }

    private void emitDynamicIndyLong(
        InsnList insns,
        String owner,
        HelperNames helpers,
        long value,
        long seed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        boolean interfaceOwner
    ) {
        long highSeed = JvmPassBytecode.mix(seed, 0x494E445948494748L);
        long lowSeed = JvmPassBytecode.mix(seed, 0x494E44594C4F5731L);
        int highMask = liveIndyMask(metadata, state, highSeed);
        int lowMask = liveIndyMask(metadata, state, lowSeed);

        JvmPassBytecode.pushInt(insns, (int) (value >>> 32) ^ highMask);
        emitFoldedLiveIndyWord(
            insns,
            owner,
            helpers,
            highSeed,
            metadata,
            state,
            interfaceOwner
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));

        JvmPassBytecode.pushInt(insns, ((int) value) ^ lowMask);
        emitFoldedLiveIndyWord(
            insns,
            owner,
            helpers,
            lowSeed,
            metadata,
            state,
            interfaceOwner
        );
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LOR));
    }

    private int liveIndyMask(
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        long seed
    ) {
        long word = liveIndyWord(metadata, state, seed);
        return (int) (word ^ (word >>> 32));
    }

    private void emitFoldedLiveIndyWord(
        InsnList insns,
        String owner,
        HelperNames helpers,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        boolean interfaceOwner
    ) {
        emitLiveIndyWord(insns, owner, helpers, siteSeed, metadata, state, interfaceOwner);
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
    }

    private void emitLiveIndyWord(
        InsnList insns,
        String owner,
        HelperNames helpers,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        boolean interfaceOwner
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        JvmPassBytecode.pushLong(insns, state.methodSalt());
        JvmPassBytecode.pushLong(insns, siteSeed);
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, helpers.flow(), FLOW_DESC, interfaceOwner));
    }

    private MethodNode emitFlow(
        String owner,
        String name,
        String mixName,
        ControlFlowFlatteningPass.CffClassKeyTable table,
        boolean interfaceOwner
    ) {
        MethodNode mn = new MethodNode(
            Opcodes.ACC_PRIVATE | Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC,
            name,
            FLOW_DESC,
            null,
            null
        );
        InsnList insns = mn.instructions;
        int xLocal = 9;
        int idxLocal = 10;

        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        emitSeededInt(insns, owner, mixName, 6, 0x494E44474731L, interfaceOwner);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        emitShift(insns, 6, 9);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitSeededInt(insns, owner, mixName, 6, 0x494E444D554CL, interfaceOwner);
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        emitSeededInt(insns, owner, mixName, 6, 0x494E44504331L, interfaceOwner);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 8));
        insns.add(new InsnNode(Opcodes.IADD));
        emitSeededInt(insns, owner, mixName, 6, 0x494E445441424CL, interfaceOwner);
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, table.values().length - 1);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, idxLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, table.owner(), table.fieldName(), "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitSeededInt(insns, owner, mixName, 6, 0x494E445456414CL, interfaceOwner);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        emitShift(insns, 6, 21);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        emitRuntimeBlockMethodKey(insns);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 6));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LRETURN));
        mn.maxStack = 8;
        mn.maxLocals = 11;
        return mn;
    }

    private void emitRuntimeBlockMethodKey(InsnList insns) {
        LabelNode nonZero = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 4));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, nonZero));
        insns.add(new InsnNode(Opcodes.POP2));
        JvmPassBytecode.pushLong(insns, 0xD1B54A32D192ED03L);
        insns.add(nonZero);
    }

    private void emitSeededInt(
        InsnList insns,
        String owner,
        String mixName,
        int seedLocal,
        long salt,
        boolean interfaceOwner
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        JvmPassBytecode.pushLong(insns, salt);
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, owner, mixName, MIX_DESC, interfaceOwner));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
    }

    private void emitShift(InsnList insns, int seedLocal, int base) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, base);
        insns.add(new InsnNode(Opcodes.LUSHR));
        JvmPassBytecode.pushLong(insns, 30L);
        insns.add(new InsnNode(Opcodes.LAND));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
    }

    private String encrypt(String value, long seed, long token, long flow) {
        char[] chars = value.toCharArray();
        for (int i = 0; i < chars.length; i++) {
            chars[i] = (char) (chars[i] ^ charMask(seed, token, flow, i));
        }
        return new String(chars);
    }

    private int charMask(long seed, long token, long flow, int index) {
        long mixed = JvmPassBytecode.mix(flow ^ seed, token + ((long) index * CHAR_STRIDE));
        return (int) (mixed ^ (mixed >>> 32));
    }

    private String indyName(long siteSeed, int kind) {
        int c = 0x80 + (int) ((siteSeed ^ (kind * 131L)) & 0x3F);
        return Character.toString((char) c);
    }

    private long siteSeed(
        long masterSeed,
        L1Class clazz,
        L1Method method,
        ControlFlowFlatteningPass.CffInstructionState state,
        int ordinal
    ) {
        long h = JvmPassBytecode.mix(masterSeed ^ 0x494E44594F424631L, clazz.name().hashCode());
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
        return ((int) value) | 1;
    }

    private record HelperNames(String bootstrap, String resolver, String decode, String mix, String flow, String cache) {}

    private record SiteSpec(int kind, String owner, String name, String desc, String indyDesc) {}
}
