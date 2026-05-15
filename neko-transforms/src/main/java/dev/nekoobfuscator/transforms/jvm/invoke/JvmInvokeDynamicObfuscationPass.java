package dev.nekoobfuscator.transforms.jvm.invoke;

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
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
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
    private static final int RESOLVER_FLOW_LOCAL = 12;

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
    private static final String BOOLEAN = "java/lang/Boolean";
    private static final String CONCURRENT_HASH_MAP = "java/util/concurrent/ConcurrentHashMap";

    private static final String BSM_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;ILjava/lang/invoke/MethodHandle;)Ljava/lang/invoke/CallSite;";
    private static final String BSM_CORE_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/String;Ljava/lang/invoke/MethodType;" +
            "Ljava/lang/String;I[Ljava/lang/Object;)Ljava/lang/invoke/CallSite;";
    private static final String RESOLVER_DESC =
        "(Ljava/lang/invoke/MethodHandles$Lookup;Ljava/lang/invoke/MutableCallSite;Ljava/lang/invoke/MethodType;" +
        "Ljava/lang/String;I[Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;";
    private static final String FLOW_DESC = "(IIII[Ljava/lang/Object;IJI)J";
    private static final String HELPERS_KEY = "invokeDynamic.classHelpers";
    private static final String SHARED_HELPERS_KEY = "invokeDynamic.sharedHelpers";
    private static final String FLOW_TABLES_KEY = "invokeDynamic.flowSaltTables";
    private static final int FLOW_HELPER_METHOD_KEY_LOCAL = 6;

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
        if (TransformGuards.isRuntimeClass(clazz)) return;
        if (
            TransformGuards.isGeneratedMethod(method) &&
            !Boolean.TRUE.equals(pctx.getPassData("invokeDynamic.hardenGeneratedHelpers"))
        ) return;
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
        boolean hasFlowCacheInit = false;
        int indyMaterialLocal = -1;
        IndyFlowTable activeFlowTable = null;
        Set<AbstractInsnNode> loopInstructions = loopRegionInstructions(mn);
        InsnList flowCacheInit = new InsnList();
        InsnList indyMaterialInit = new InsnList();
        for (AbstractInsnNode insn : mn.instructions.toArray()) {
            if (!metadata.applicationInstructions().contains(insn)) continue;
            ControlFlowFlatteningPass.CffInstructionState state = metadata.instructionStates().get(insn);
            if (state == null) {
                throw new IllegalStateException("invokeDynamic cannot bind CFF state for " + methodKey);
            }
            SiteSpec spec = siteSpec(pctx, clazz, insn);
            if (spec == null) continue;
            if (helpers == null) {
                helpers = ensureHelpers(pctx, clazz, metadata);
            }
            long siteSeed = siteSeed(pctx.masterSeed(), clazz, method, state, ordinal++);
            long salt = JvmPassBytecode.mix(siteSeed ^ 0x494E445953414C54L, spec.owner().hashCode());
            long flow = liveIndyWord(metadata, state, siteSeed);
            long token = indyTokenFromFlow(siteSeed, salt, flow);
            IndyFlowTable flowTable = ensureIndyFlowTable(pctx, clazz, metadata);
            activeFlowTable = flowTable;
            if (indyMaterialLocal < 0) {
                indyMaterialLocal = mn.maxLocals++;
                indyMaterialInit.add(new FieldInsnNode(Opcodes.GETSTATIC, clazz.name(), flowTable.fieldName(), OBJECT_ARRAY_DESC));
                indyMaterialInit.add(new VarInsnNode(Opcodes.ASTORE, indyMaterialLocal));
            }
            int flowSaltSlot = registerIndyFlowSalt(pctx, flowTable, state.methodSalt(), siteSeed);
            int resolverSeedSlot = registerIndyResolverSeed(pctx, flowTable, siteSeed, salt);

            boolean interfaceOwner = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
            InvokeDynamicInsnNode indy = new InvokeDynamicInsnNode(
                indyName(siteSeed, spec.kind()),
                appendLong(spec.indyDesc()),
                new Handle(
                    Opcodes.H_INVOKESTATIC,
                    helpers.bootstrapOwner(),
                    helpers.bootstrap(),
                    BSM_DESC,
                    helpers.bootstrapInterfaceOwner()
                ),
                encrypt(payload(spec), siteSeed ^ salt ^ OWNER_SEED, token, flow),
                resolverSeedSlot,
                new Handle(
                    Opcodes.H_GETSTATIC,
                    clazz.name(),
                    flowTable.fieldName(),
                    OBJECT_ARRAY_DESC,
                    interfaceOwner
                )
            );

            InsnList replacement = new InsnList();
            if (loopInstructions.contains(insn)) {
                int flowCacheLocal = mn.maxLocals;
                mn.maxLocals += 2;
                flowCacheInit.add(new InsnNode(Opcodes.LCONST_0));
                flowCacheInit.add(new VarInsnNode(Opcodes.LSTORE, flowCacheLocal));
                hasFlowCacheInit = true;
                emitCachedLiveIndyWord(
                    replacement,
                    clazz.name(),
                    helpers,
                    metadata,
                    state,
                    interfaceOwner,
                    flowCacheLocal,
                    indyMaterialLocal,
                    flowSaltSlot
                );
            } else {
                emitLiveIndyWord(
                    replacement,
                    clazz.name(),
                    helpers,
                    metadata,
                    state,
                    interfaceOwner,
                    indyMaterialLocal,
                    flowSaltSlot
                );
            }
            replacement.add(indy);
            JvmKeyDispatchPass.markGenerated(pctx, replacement);
            mn.instructions.insertBefore(insn, replacement);
            mn.instructions.remove(insn);
            transformed++;
        }

        if (transformed > 0) {
            if (hasFlowCacheInit) {
                JvmKeyDispatchPass.markGenerated(pctx, flowCacheInit);
                mn.instructions.insert(flowCacheInit);
            }
            JvmKeyDispatchPass.markGenerated(pctx, indyMaterialInit);
            if ("<clinit>".equals(method.name()) && "()V".equals(method.descriptor()) && activeFlowTable != null) {
                mn.instructions.insert(activeFlowTable.initEnd(), indyMaterialInit);
            } else {
                mn.instructions.insert(indyMaterialInit);
            }
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

    private String payload(SiteSpec spec) {
        return spec.kind() + "\n" + spec.owner() + "\n" + spec.name() + "\n" + spec.desc();
    }

    private SiteSpec siteSpec(PipelineContext pctx, L1Class caller, AbstractInsnNode insn) {
        if (insn instanceof MethodInsnNode call) {
            if ("<init>".equals(call.name) || "<clinit>".equals(call.name)) return null;
            if (TransformGuards.isRuntimeClass(call.owner) || TransformGuards.isGeneratedName(call.name)) return null;
            if (isAnnotationElementCall(pctx, call)) return null;
            int kind = switch (call.getOpcode()) {
                case Opcodes.INVOKEVIRTUAL -> KIND_INVOKEVIRTUAL;
                case Opcodes.INVOKESTATIC -> KIND_INVOKESTATIC;
                case Opcodes.INVOKEINTERFACE -> KIND_INVOKEINTERFACE;
                case Opcodes.INVOKESPECIAL -> KIND_INVOKESPECIAL;
                default -> 0;
            };
            if (kind == 0) return null;
            String receiverOwner = kind == KIND_INVOKESPECIAL ? caller.name() : call.owner;
            String indyDesc = methodIndyDescriptor(receiverOwner, call.desc, kind == KIND_INVOKESTATIC);
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

    private boolean isAnnotationElementCall(PipelineContext pctx, MethodInsnNode call) {
        if (call.getOpcode() != Opcodes.INVOKEINTERFACE) return false;
        L1Class owner = pctx.classMap().get(call.owner);
        if (owner == null || !owner.isAnnotation()) return false;
        if (Type.getArgumentTypes(call.desc).length != 0) return false;
        Type returnType = Type.getReturnType(call.desc);
        if (returnType == Type.VOID_TYPE) return false;
        for (L1Method method : owner.methods()) {
            if (method.name().equals(call.name) && method.descriptor().equals(call.desc)) {
                return true;
            }
        }
        return false;
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

    private String appendLong(String desc) {
        Type method = Type.getMethodType(desc);
        Type[] args = method.getArgumentTypes();
        Type[] out = new Type[args.length + 1];
        System.arraycopy(args, 0, out, 0, args.length);
        out[args.length] = Type.LONG_TYPE;
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
        IndyFlowTable materialTable = ensureIndyFlowTable(pctx, clazz, metadata);
        ensureIndyCacheCarrierSlot(pctx, clazz, materialTable.fieldName(), metadata.classKeyTable().initEnd());
        boolean interfaceOwner = (clazz.asmNode().access & Opcodes.ACC_INTERFACE) != 0;
        SharedHelperNames shared = ensureSharedHelpers(pctx, clazz, metadata, interfaceOwner);
        HelperNames created = new HelperNames(
            shared.bootstrapOwner(),
            shared.bootstrap(),
            shared.bootstrapInterfaceOwner(),
            shared.flowOwner(),
            shared.flow(),
            shared.flowInterfaceOwner(),
            shared.guardOwner(),
            shared.guard(),
            shared.guardInterfaceOwner()
        );
        helpersByClass.put(clazz.name(), created);
        return created;
    }

    @SuppressWarnings("unchecked")
    private SharedHelperNames ensureSharedHelpers(
        PipelineContext pctx,
        L1Class clazz,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        boolean interfaceOwner
    ) {
        Map<String, SharedHelperNames> helpersByPackage = pctx.getPassData(SHARED_HELPERS_KEY);
        if (helpersByPackage == null) {
            helpersByPackage = new HashMap<>();
            pctx.putPassData(SHARED_HELPERS_KEY, helpersByPackage);
        }
        int classKeyMask = metadata.classKeyTable().values().length - 1;
        String key = packageName(clazz.name()) + "#" + classKeyMask;
        SharedHelperNames existing = helpersByPackage.get(key);
        if (existing != null) {
            return existing;
        }
        String flow = uniqueMethodName(clazz, "__neko_indy_flow");
        String guard = uniqueMethodName(clazz, "__neko_indy_guard");
        String resolver = uniqueMethodName(clazz, "__neko_indy_resolve");
        String bootstrapCore = uniqueMethodName(clazz, "__neko_indy_core");
        String bootstrap = uniqueMethodName(clazz, "__neko_indy_bsm");
        int access = Opcodes.ACC_STATIC | Opcodes.ACC_SYNTHETIC;
        if (interfaceOwner) {
            access |= Opcodes.ACC_PUBLIC;
        }
        clazz.asmNode().methods.add(emitBootstrapTrampoline(
            bootstrap,
            access,
            clazz.name(),
            bootstrapCore,
            interfaceOwner
        ));
        clazz.asmNode().methods.add(emitBootstrapCore(
            bootstrapCore,
            clazz.name(),
            resolver,
            interfaceOwner,
            access
        ));
        clazz.asmNode().methods.add(emitResolver(
            resolver,
            clazz.name(),
            guard,
            interfaceOwner,
            access
        ));
        clazz.asmNode().methods.add(emitGuard(guard, access));
        clazz.asmNode().methods.add(emitFlow(flow, classKeyMask, access));
        publishGeneratedIndyHelperFlowKey(pctx, clazz.name(), resolver, RESOLVER_DESC, RESOLVER_FLOW_LOCAL);
        publishGeneratedIndyHelperFlowKey(pctx, clazz.name(), flow, FLOW_DESC, FLOW_HELPER_METHOD_KEY_LOCAL);
        installInjectedStackTraceFilter(
            pctx,
            clazz.name(),
            List.of(bootstrap, bootstrapCore, resolver, flow, guard)
        );
        SharedHelperNames created = new SharedHelperNames(
            clazz.name(),
            bootstrap,
            interfaceOwner,
            clazz.name(),
            bootstrapCore,
            interfaceOwner,
            clazz.name(),
            resolver,
            interfaceOwner,
            clazz.name(),
            flow,
            interfaceOwner,
            clazz.name(),
            guard,
            interfaceOwner
        );
        helpersByPackage.put(key, created);
        return created;
    }

    private void ensureIndyCacheCarrierSlot(
        PipelineContext pctx,
        L1Class clazz,
        String carrierFieldName,
        LabelNode anchor
    ) {
        MethodNode clinit = findOrCreateClassInit(clazz);
        InsnList init = new InsnList();
        init.add(new FieldInsnNode(Opcodes.GETSTATIC, clazz.name(), carrierFieldName, OBJECT_ARRAY_DESC));
        JvmPassBytecode.pushInt(init, ControlFlowFlatteningPass.INDY_CACHE_SLOT);
        init.add(new TypeInsnNode(Opcodes.NEW, CONCURRENT_HASH_MAP));
        init.add(new InsnNode(Opcodes.DUP));
        init.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, CONCURRENT_HASH_MAP, "<init>", "()V", false));
        init.add(new InsnNode(Opcodes.AASTORE));
        JvmKeyDispatchPass.markGenerated(pctx, init);
        clinit.instructions.insert(anchor, init);
        clinit.maxStack = Math.max(clinit.maxStack, 4);
        clazz.markDirty();
    }

    @SuppressWarnings("unchecked")
    private IndyFlowTable ensureIndyFlowTable(
        PipelineContext pctx,
        L1Class clazz,
        ControlFlowFlatteningPass.CffMethodMetadata metadata
    ) {
        Map<String, IndyFlowTable> tables = pctx.getPassData(FLOW_TABLES_KEY);
        if (tables == null) {
            tables = new HashMap<>();
            pctx.putPassData(FLOW_TABLES_KEY, tables);
        }
        IndyFlowTable existing = tables.get(clazz.name());
        if (existing != null) return existing;

        String fieldName = metadata.classKeyTable().objectFieldName();
        MethodNode clinit = findOrCreateClassInit(clazz);
        LabelNode initStart = new LabelNode();
        LabelNode initEnd = new LabelNode();
        InsnList segment = new InsnList();
        segment.add(initStart);
        segment.add(initEnd);
        clinit.instructions.insert(metadata.classKeyTable().initEnd(), segment);

        IndyFlowTable table = new IndyFlowTable(
            clazz.name(),
            fieldName,
            clinit,
            new java.util.ArrayList<>(),
            new java.util.ArrayList<>(),
            initStart,
            initEnd
        );
        tables.put(clazz.name(), table);
        clazz.markDirty();
        return table;
    }

    private int registerIndyFlowSalt(
        PipelineContext pctx,
        IndyFlowTable table,
        long methodSalt,
        long siteSeed
    ) {
        int slot = table.cells().size();
        table.cells().add(new long[] {methodSalt, siteSeed});
        rebuildIndyFlowTableInit(pctx, table);
        return slot;
    }

    private void rebuildIndyFlowTableInit(PipelineContext pctx, IndyFlowTable table) {
        InsnList insns = new InsnList();
        int tableLocal = table.initHelper().maxLocals;
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        insns.add(new VarInsnNode(Opcodes.ASTORE, tableLocal));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, table.owner(), table.fieldName(), OBJECT_ARRAY_DESC));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_SLOT);
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, table.owner(), table.fieldName(), OBJECT_ARRAY_DESC));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_ALIAS_SLOT);
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, table.owner(), table.fieldName(), OBJECT_ARRAY_DESC));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_SELECTOR_SLOT);
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_SLOT);
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_ALIAS_SLOT);
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        JvmPassBytecode.pushInt(insns, table.cells().size() * 3);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        for (int i = 0; i < table.cells().size(); i++) {
            long[] cell = table.cells().get(i);
            int base = i * 3;
            long epoch = indyFlowSaltInitialEpoch(table, i);
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, base);
            JvmPassBytecode.pushLong(insns, epoch);
            insns.add(new InsnNode(Opcodes.LASTORE));
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, base + 1);
            JvmPassBytecode.pushLong(insns, cell[0] ^ indyFlowSaltMask(i, epoch, 0x494E4459464D534CL));
            insns.add(new InsnNode(Opcodes.LASTORE));
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, base + 2);
            JvmPassBytecode.pushLong(insns, cell[1] ^ indyFlowSaltMask(i, epoch, 0x494E445946535445L));
            insns.add(new InsnNode(Opcodes.LASTORE));
        }
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        JvmPassBytecode.pushInt(insns, table.resolverCells().size() * 3);
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_LONG));
        for (int i = 0; i < table.resolverCells().size(); i++) {
            long[] cell = table.resolverCells().get(i);
            int base = i * 3;
            long epoch = indyResolverSeedInitialEpoch(table, i);
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, base);
            JvmPassBytecode.pushLong(insns, epoch);
            insns.add(new InsnNode(Opcodes.LASTORE));
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, base + 1);
            JvmPassBytecode.pushLong(insns, cell[0] ^ indyResolverSeedMask(i, epoch, 0x494E445952534545L));
            insns.add(new InsnNode(Opcodes.LASTORE));
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, base + 2);
            JvmPassBytecode.pushLong(insns, cell[1] ^ indyResolverSeedMask(i, epoch, 0x494E44595253414CL));
            insns.add(new InsnNode(Opcodes.LASTORE));
        }
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, tableLocal));
        JvmPassBytecode.pushInt(insns, 2);
        JvmPassBytecode.pushInt(insns, table.cells().size());
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        for (int i = 0; i < table.cells().size(); i++) {
            insns.add(new InsnNode(Opcodes.DUP));
            JvmPassBytecode.pushInt(insns, i);
            insns.add(new TypeInsnNode(Opcodes.NEW, OBJECT));
            insns.add(new InsnNode(Opcodes.DUP));
            insns.add(new MethodInsnNode(Opcodes.INVOKESPECIAL, OBJECT, "<init>", "()V", false));
            insns.add(new InsnNode(Opcodes.AASTORE));
        }
        insns.add(new InsnNode(Opcodes.AASTORE));
        JvmKeyDispatchPass.markGenerated(pctx, insns);
        replaceIndyFlowTableInit(table, insns);
        table.initHelper().maxLocals = Math.max(table.initHelper().maxLocals, tableLocal + 1);
        table.initHelper().maxStack = Math.max(table.initHelper().maxStack, 8);
    }

    private long indyFlowSaltInitialEpoch(IndyFlowTable table, int slot) {
        return JvmPassBytecode.mix(
            0x494E445946455043L ^ table.owner().hashCode(),
            table.fieldName().hashCode() ^ slot
        );
    }

    private long indyFlowSaltMask(int slot, long epoch, long tag) {
        return JvmPassBytecode.mix(
            epoch ^ (((long) slot) << 32) ^ tag,
            tag ^ 0x494E4459464D4153L ^ slot
        );
    }

    private int registerIndyResolverSeed(
        PipelineContext pctx,
        IndyFlowTable table,
        long siteSeed,
        long salt
    ) {
        int slot = table.resolverCells().size();
        table.resolverCells().add(new long[] {siteSeed, salt});
        rebuildIndyFlowTableInit(pctx, table);
        return slot;
    }

    private long indyResolverSeedInitialEpoch(IndyFlowTable table, int slot) {
        return JvmPassBytecode.mix(
            0x494E445952455043L ^ table.owner().hashCode(),
            table.fieldName().hashCode() ^ slot
        );
    }


    private long indyResolverSeedMask(int slot, long epoch, long tag) {
        return JvmPassBytecode.mix(
            epoch ^ (((long) slot) << 32) ^ tag,
            tag ^ 0x494E4459524D4153L ^ slot
        );
    }

    private void replaceIndyFlowTableInit(IndyFlowTable table, InsnList replacement) {
        InsnList instructions = table.initHelper().instructions;
        AbstractInsnNode cursor = table.initStart().getNext();
        while (cursor != null && cursor != table.initEnd()) {
            AbstractInsnNode next = cursor.getNext();
            instructions.remove(cursor);
            cursor = next;
        }
        instructions.insert(table.initStart(), replacement);
    }

    @SuppressWarnings("unchecked")
    private void publishGeneratedIndyHelperFlowKey(
        PipelineContext pctx,
        String owner,
        String name,
        String desc,
        int keyLocal
    ) {
        Map<String, Integer> locals = pctx.getPassData(JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD);
        if (locals == null) {
            locals = new HashMap<>();
            pctx.putPassData(JvmKeyDispatchPass.CFF_LOCAL_BY_METHOD, locals);
        }
        locals.put(owner + "." + name + desc, keyLocal);
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
        int methodNameLocal = mn.maxLocals++;
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
        emitInjectedFrameTest(insns, frameLocal, methodNameLocal, helperOwner, helperMethods, skip);
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
        int methodNameLocal,
        String helperOwner,
        List<String> helperMethods,
        LabelNode skip
    ) {
        if (helperMethods.isEmpty()) {
            return;
        }
        String ownerName = helperOwner.replace('/', '.');
        LabelNode done = new LabelNode();
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
        insns.add(new JumpInsnNode(Opcodes.IFEQ, done));
        insns.add(new VarInsnNode(Opcodes.ALOAD, frameLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "getMethodName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, methodNameLocal));
        for (String helperMethod : helperMethods) {
            insns.add(new VarInsnNode(Opcodes.ALOAD, methodNameLocal));
            insns.add(new LdcInsnNode(helperMethod));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKEVIRTUAL,
                "java/lang/String",
                "equals",
                "(Ljava/lang/Object;)Z",
                false
            ));
            insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        }
        insns.add(done);
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
        insns.add(new JumpInsnNode(Opcodes.IFNE, skip));
        insns.add(new JumpInsnNode(Opcodes.GOTO, syntheticDone));
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

    private String packageName(String owner) {
        int slash = owner.lastIndexOf('/');
        return slash < 0 ? "" : owner.substring(0, slash);
    }

    private boolean hasMethod(L1Class clazz, String name) {
        for (MethodNode method : clazz.asmNode().methods) {
            if (method.name.equals(name)) return true;
        }
        return false;
    }

    private MethodNode emitBootstrapTrampoline(
        String name,
        int access,
        String bootstrapCoreOwner,
        String bootstrapCoreName,
        boolean bootstrapCoreInterfaceOwner
    ) {
        MethodNode mn = new MethodNode(
            access,
            name,
            BSM_DESC,
            null,
            new String[] {"java/lang/Throwable"}
        );
        InsnList insns = mn.instructions;
        int carrierLocal = 6;
        insns.add(new VarInsnNode(Opcodes.ALOAD, 5));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            METHOD_HANDLE,
            "invoke",
            "()Ljava/lang/Object;",
            false
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, OBJECT_ARRAY_DESC));
        insns.add(new VarInsnNode(Opcodes.ASTORE, carrierLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 3));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            bootstrapCoreOwner,
            bootstrapCoreName,
            BSM_CORE_DESC,
            bootstrapCoreInterfaceOwner
        ));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 6;
        mn.maxLocals = 7;
        return mn;
    }

    private MethodNode emitBootstrapCore(
        String name,
        String resolverOwner,
        String resolverName,
        boolean resolverInterfaceOwner,
        int access
    ) {
        MethodNode mn = new MethodNode(
            access,
            name,
            BSM_CORE_DESC,
            null,
            new String[] {"java/lang/Throwable"}
        );
        InsnList insns = mn.instructions;
        int carrierLocal = 5;
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
            resolverOwner,
            resolverName,
            RESOLVER_DESC,
            resolverInterfaceOwner
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
            insns.add(new VarInsnNode(Opcodes.ILOAD, 4));
            insns.add(new MethodInsnNode(
                Opcodes.INVOKESTATIC,
                "java/lang/Integer",
                "valueOf",
                "(I)Ljava/lang/Integer;",
                false
            ));
        });
        arrayStore(insns, 5, () -> insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal)));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "insertArguments",
            "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "explicitCastArguments",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_CACHE_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, CONCURRENT_HASH_MAP));
        emitResolverFallbackCacheKey(insns, 4);
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "putIfAbsent",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));
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
        String name,
        String guardOwner,
        String guardName,
        boolean guardInterfaceOwner,
        int access
    ) {
        MethodNode mn = new MethodNode(
            access,
            name,
            RESOLVER_DESC,
            null,
            new String[] {"java/lang/Throwable"}
        );
        InsnList insns = mn.instructions;
        int resolverSlotLocal = 4;
        int carrierLocal = 5;
        int argsLocal = 6;
        int indexLocal = 9;
        int tokenLocal = 10;
        int flowLocal = RESOLVER_FLOW_LOCAL;
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
        int resultLocal = 31;
        int filterSourceLocal = 32;
        int filterTargetLocal = 33;
        int filterIndexLocal = 34;
        int filterWriteLocal = 35;
        int filterMemberLocal = 36;
        int resolverSlabLocal = 37;
        int resolverIndexLocal = 38;
        int resolverEpochLocal = 39;
        int resolverNextEpochLocal = 41;
        int seedLocal = 43;
        int saltLocal = 45;
        int cacheLocal = 47;
        int fallbackHandleLocal = 48;
        int testHandleLocal = 49;

        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        loadLongArrayArg(insns, argsLocal, indexLocal);
        insns.add(new VarInsnNode(Opcodes.LSTORE, flowLocal));
        emitResolverSeedPairLoad(
            insns,
            carrierLocal,
            resolverSlotLocal,
            resolverSlabLocal,
            resolverIndexLocal,
            resolverEpochLocal,
            seedLocal,
            saltLocal
        );
        emitIndyTokenFromFlow(insns, tokenLocal, seedLocal, saltLocal, flowLocal);
        emitIndyCacheLoad(insns, carrierLocal, cacheLocal);

        LabelNode resolve = new LabelNode();
        LabelNode afterResolve = new LabelNode();
        LabelNode invoke = new LabelNode();
        emitLiveCacheKey(insns, cacheKeyLocal, tokenLocal, flowLocal, seedLocal, saltLocal);
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheKeyLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, METHOD_HANDLE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new JumpInsnNode(Opcodes.IFNULL, resolve));
        insns.add(new JumpInsnNode(Opcodes.GOTO, invoke));
        insns.add(resolve);

        emitDecodeInline(
            insns,
            3,
            seedLocal,
            saltLocal,
            OWNER_SEED,
            tokenLocal,
            flowLocal,
            payloadLocal,
            28,
            29,
            30
        );
        emitParsePayload(insns, payloadLocal, ownerLocal, nameLocal, descLocal, kindLocal, sep1Local, sep2Local, sep3Local);

        emitResolveClassWithCache(insns, cacheLocal, ownerLocal, classLocal, classKeyLocal);
        emitResolveMethodTypeWithCache(insns, cacheLocal, descLocal, typeLocal, typeKeyLocal);

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
        emitAdaptTarget(insns, handleLocal);
        emitInstallGuardedTarget(
            insns,
            guardOwner,
            guardName,
            guardInterfaceOwner,
            handleLocal,
            fallbackHandleLocal,
            testHandleLocal,
            flowLocal,
            cacheLocal,
            resolverSlotLocal
        );
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new LdcInsnNode(Type.getType(OBJECT_ARRAY_DESC)));
        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "asSpreader",
            "(Ljava/lang/Class;I)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "putIfAbsent",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));

        insns.add(invoke);
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "invoke",
            "([Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
        emitInlineReflectionArrayResultFilter(
            insns,
            resultLocal,
            filterSourceLocal,
            filterTargetLocal,
            filterIndexLocal,
            filterWriteLocal,
            filterMemberLocal
        );
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        insns.add(new InsnNode(Opcodes.ARETURN));
        mn.maxStack = 16;
        mn.maxLocals = 50;
        return mn;
    }

    private void emitIndyCacheLoad(InsnList insns, int carrierLocal, int cacheLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_CACHE_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, CONCURRENT_HASH_MAP));
        insns.add(new VarInsnNode(Opcodes.ASTORE, cacheLocal));
    }

    private void emitInlineReflectionArrayResultFilter(
        InsnList insns,
        int resultLocal,
        int sourceLocal,
        int filteredLocal,
        int indexLocal,
        int writeLocal,
        int memberLocal
    ) {
        LabelNode fieldCheck = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        insns.add(new TypeInsnNode(Opcodes.INSTANCEOF, "[Ljava/lang/reflect/Method;"));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, fieldCheck));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/reflect/Method;"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, sourceLocal));
        emitInlineMemberFilterLoop(
            insns,
            "java/lang/reflect/Method",
            "([Ljava/lang/Object;I)[Ljava/lang/Object;",
            "[Ljava/lang/reflect/Method;",
            resultLocal,
            sourceLocal,
            filteredLocal,
            indexLocal,
            writeLocal,
            memberLocal
        );
        insns.add(new JumpInsnNode(Opcodes.GOTO, done));
        insns.add(fieldCheck);
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        insns.add(new TypeInsnNode(Opcodes.INSTANCEOF, "[Ljava/lang/reflect/Field;"));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, done));
        insns.add(new VarInsnNode(Opcodes.ALOAD, resultLocal));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[Ljava/lang/reflect/Field;"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, sourceLocal));
        emitInlineMemberFilterLoop(
            insns,
            "java/lang/reflect/Field",
            "([Ljava/lang/Object;I)[Ljava/lang/Object;",
            "[Ljava/lang/reflect/Field;",
            resultLocal,
            sourceLocal,
            filteredLocal,
            indexLocal,
            writeLocal,
            memberLocal
        );
        insns.add(done);
    }

    private void emitInlineMemberFilterLoop(
        InsnList insns,
        String memberType,
        String copyDesc,
        String arrayType,
        int resultLocal,
        int sourceLocal,
        int filteredLocal,
        int indexLocal,
        int writeLocal,
        int memberLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode keep = new LabelNode();
        LabelNode skip = new LabelNode();
        LabelNode end = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, memberType));
        insns.add(new VarInsnNode(Opcodes.ASTORE, filteredLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ISTORE, writeLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, end));
        insns.add(new VarInsnNode(Opcodes.ALOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new VarInsnNode(Opcodes.ASTORE, memberLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            memberType,
            "isSynthetic",
            "()Z",
            false
        ));
        insns.add(new JumpInsnNode(Opcodes.IFEQ, keep));
        insns.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            memberType,
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
        insns.add(keep);
        insns.add(new VarInsnNode(Opcodes.ALOAD, filteredLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, writeLocal));
        insns.add(new IincInsnNode(writeLocal, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, memberLocal));
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
            copyDesc,
            false
        ));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, arrayType));
        insns.add(new VarInsnNode(Opcodes.ASTORE, resultLocal));
    }

    private void emitAdaptTarget(InsnList insns, int handleLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_HANDLE, "type",
            "()Ljava/lang/invoke/MethodType;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "parameterCount", "()I", false));
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, CLASS));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, LONG, "TYPE", "Ljava/lang/Class;"));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "dropArguments",
            "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "explicitCastArguments",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, handleLocal));
    }

    private void emitInstallGuardedTarget(
        InsnList insns,
        String owner,
        String guardName,
        boolean interfaceOwner,
        int handleLocal,
        int fallbackHandleLocal,
        int testHandleLocal,
        int flowLocal,
        int cacheLocal,
        int resolverSlotLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        emitResolverFallbackCacheKey(insns, resolverSlotLocal);
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "get",
            "(Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, METHOD_HANDLE));
        insns.add(new VarInsnNode(Opcodes.ASTORE, fallbackHandleLocal));

        insns.add(new LdcInsnNode(new Handle(
            Opcodes.H_INVOKESTATIC,
            owner,
            guardName,
            "(JJ)Z",
            interfaceOwner
        )));
        JvmPassBytecode.pushInt(insns, 1);
        JvmPassBytecode.pushInt(insns, 1);
        insns.add(new TypeInsnNode(Opcodes.ANEWARRAY, OBJECT));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, LONG, "valueOf", "(J)Ljava/lang/Long;", false));
        insns.add(new InsnNode(Opcodes.AASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "insertArguments",
            "(Ljava/lang/invoke/MethodHandle;I[Ljava/lang/Object;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new FieldInsnNode(Opcodes.GETSTATIC, BOOLEAN, "TYPE", "Ljava/lang/Class;"));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "changeReturnType",
            "(Ljava/lang/Class;)Ljava/lang/invoke/MethodType;", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new IntInsnNode(Opcodes.NEWARRAY, Opcodes.T_INT));
        insns.add(new InsnNode(Opcodes.DUP));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new VarInsnNode(Opcodes.ALOAD, 2));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, METHOD_TYPE, "parameterCount", "()I", false));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new InsnNode(Opcodes.IASTORE));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "permuteArguments",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodType;[I)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, testHandleLocal));

        insns.add(new VarInsnNode(Opcodes.ALOAD, 1));
        insns.add(new VarInsnNode(Opcodes.ALOAD, testHandleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, handleLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, fallbackHandleLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKESTATIC, METHOD_HANDLES, "guardWithTest",
            "(Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;Ljava/lang/invoke/MethodHandle;)Ljava/lang/invoke/MethodHandle;", false));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, MUTABLE_CALL_SITE, "setTarget",
            "(Ljava/lang/invoke/MethodHandle;)V", false));
    }

    private void emitResolverFallbackCacheKey(InsnList insns, int resolverSlotLocal) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, resolverSlotLocal));
        JvmPassBytecode.pushInt(insns, 0x4E4B4642);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Integer",
            "valueOf",
            "(I)Ljava/lang/Integer;",
            false
        ));
    }

    private MethodNode emitGuard(String name, int access) {
        MethodNode mn = new MethodNode(
            access,
            name,
            "(JJ)Z",
            null,
            null
        );
        LabelNode miss = new LabelNode();
        InsnList insns = mn.instructions;
        insns.add(new VarInsnNode(Opcodes.LLOAD, 0));
        insns.add(new VarInsnNode(Opcodes.LLOAD, 2));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, miss));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IRETURN));
        insns.add(miss);
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.IRETURN));
        mn.maxStack = 4;
        mn.maxLocals = 4;
        return mn;
    }

    private void emitLiveCacheKey(
        InsnList insns,
        int cacheKeyLocal,
        int tokenLocal,
        int flowLocal,
        int seedLocal,
        int saltLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, saltLocal));
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
        int cacheLocal,
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
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
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
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classKeyLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, classLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, CONCURRENT_HASH_MAP, "putIfAbsent",
            "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false));
        insns.add(new InsnNode(Opcodes.POP));
        insns.add(done);
    }

    private void emitResolveMethodTypeWithCache(
        InsnList insns,
        int cacheLocal,
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
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
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
        insns.add(new VarInsnNode(Opcodes.ALOAD, cacheLocal));
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

    private void emitDecodeInline(
        InsnList insns,
        int stringLocal,
        int seedLocal,
        int saltLocal,
        long label,
        int tokenLocal,
        int flowLocal,
        int outputLocal,
        int charsLocal,
        int indexLocal,
        int maskLocal
    ) {
        LabelNode loop = new LabelNode();
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.ALOAD, stringLocal));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, "java/lang/String", "toCharArray", "()[C", false));
        insns.add(new VarInsnNode(Opcodes.ASTORE, charsLocal));
        JvmPassBytecode.pushInt(insns, 0);
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(loop);
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, charsLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPGE, done));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, saltLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, label);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, CHAR_STRIDE);
        insns.add(new InsnNode(Opcodes.LMUL));
        insns.add(new InsnNode(Opcodes.LADD));
        emitInlineMix(insns);
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
        insns.add(new VarInsnNode(Opcodes.ASTORE, outputLocal));
    }

    private void emitIndyTokenFromFlow(
        InsnList insns,
        int tokenLocal,
        int seedLocal,
        int saltLocal,
        int flowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, saltLocal));
        JvmPassBytecode.pushLong(insns, 0x494E4459544B4631L);
        insns.add(new InsnNode(Opcodes.LXOR));
        emitInlineMix(insns);
        insns.add(new VarInsnNode(Opcodes.LSTORE, tokenLocal));
    }

    private void emitResolverSeedPairLoad(
        InsnList insns,
        int carrierLocal,
        int slotLocal,
        int slabLocal,
        int indexLocal,
        int epochLocal,
        int seedLocal,
        int saltLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_SELECTOR_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISHL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, OBJECT_ARRAY_DESC));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, slabLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, indexLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, slabLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, epochLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, slabLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.LALOAD));
        emitResolverSeedMask(insns, slotLocal, epochLocal, 0x494E445952534545L);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, seedLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, slabLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.LALOAD));
        emitResolverSeedMask(insns, slotLocal, epochLocal, 0x494E44595253414CL);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, saltLocal));
    }

    private void emitResolverSeedPairUpdate(
        InsnList insns,
        int slotLocal,
        int slabLocal,
        int indexLocal,
        int epochLocal,
        int nextEpochLocal,
        int seedLocal,
        int saltLocal,
        int tokenLocal,
        int flowLocal
    ) {
        emitResolverSeedNextEpoch(insns, slotLocal, epochLocal, tokenLocal, flowLocal);
        insns.add(new VarInsnNode(Opcodes.LSTORE, nextEpochLocal));
        emitResolverSeedCellStore(
            insns,
            slabLocal,
            indexLocal,
            1,
            slotLocal,
            nextEpochLocal,
            seedLocal,
            0x494E445952534545L
        );
        emitResolverSeedCellStore(
            insns,
            slabLocal,
            indexLocal,
            2,
            slotLocal,
            nextEpochLocal,
            saltLocal,
            0x494E44595253414CL
        );
        insns.add(new VarInsnNode(Opcodes.ALOAD, slabLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, nextEpochLocal));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitResolverSeedMask(
        InsnList insns,
        int slotLocal,
        int epochLocal,
        long tag
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, epochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, tag);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, tag ^ 0x494E4459524D4153L);
        insns.add(new InsnNode(Opcodes.LXOR));
        emitInlineMix(insns);
    }

    private void emitResolverSeedNextEpoch(
        InsnList insns,
        int slotLocal,
        int epochLocal,
        int tokenLocal,
        int flowLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, epochLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, tokenLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x494E44595245504CL);
        emitInlineMix(insns);
    }

    private void emitResolverSeedCellStore(
        InsnList insns,
        int slabLocal,
        int indexLocal,
        int indexOffset,
        int slotLocal,
        int epochLocal,
        int valueLocal,
        long tag
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, slabLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        if (indexOffset != 0) {
            JvmPassBytecode.pushInt(insns, indexOffset);
            insns.add(new InsnNode(Opcodes.IADD));
        }
        insns.add(new VarInsnNode(Opcodes.LLOAD, valueLocal));
        emitResolverSeedMask(insns, slotLocal, epochLocal, tag);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void loadLongArrayArg(InsnList insns, int argsLocal, int indexLocal) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, argsLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, indexLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, LONG));
        insns.add(new MethodInsnNode(Opcodes.INVOKEVIRTUAL, LONG, "longValue", "()J", false));
    }

    private void emitInlineMix(InsnList insns) {
        insns.add(new InsnNode(Opcodes.LADD));
        JvmPassBytecode.pushLong(insns, 0x9E3779B97F4A7C15L);
        insns.add(new InsnNode(Opcodes.LADD));
        emitInlineMixRound(insns, 30, 0xBF58476D1CE4E5B9L);
        emitInlineMixRound(insns, 27, 0x94D049BB133111EBL);
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 31);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
    }

    private void emitInlineMixRound(InsnList insns, int shift, long mul) {
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, shift);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, mul);
        insns.add(new InsnNode(Opcodes.LMUL));
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
        int x = (state.guardKey() + seededIndyInt(siteSeed, 0x494E44474731L)) ^
            state.pathKey();
        x += x >>> shift(siteSeed, 9);
        x = (x ^ state.blockKey()) * seededIndyInt(siteSeed, 0x494E444D554CL);
        x ^= state.pcToken() + seededIndyInt(siteSeed, 0x494E44504331L);
        int idx = (x + state.state() + seededIndyInt(siteSeed, 0x494E445441424CL)) &
            (metadata.classKeyTable().values().length - 1);
        x += metadata.classKeyTable().values()[idx] ^ seededIndyInt(siteSeed, 0x494E445456414CL);
        x ^= x >>> shift(siteSeed, 21);
        return (((long) x) & 0xFFFFFFFFL) ^
            state.methodKey() ^
            JvmPassBytecode.mix(state.methodKey(), 0x494E44594D4B31L) ^
            siteSeed;
    }

    private long indyTokenFromFlow(long siteSeed, long salt, long flow) {
        return JvmPassBytecode.mix(flow ^ siteSeed, salt ^ 0x494E4459544B4631L);
    }

    private void emitDynamicIndyLong(
        InsnList insns,
        long value,
        long siteSeed,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        int dynamicTokenLocal,
        int dynamicFlowLocal
    ) {
        int mask = liveIndyMask(metadata, state, siteSeed);
        int lowPad = nonZeroInt(JvmPassBytecode.mix(siteSeed, 0x494E44594C504144L));

        insns.add(new VarInsnNode(Opcodes.LLOAD, dynamicFlowLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new VarInsnNode(Opcodes.ISTORE, dynamicTokenLocal));
        JvmPassBytecode.pushInt(insns, (int) (value >>> 32) ^ mask);
        insns.add(new VarInsnNode(Opcodes.ILOAD, dynamicTokenLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));

        JvmPassBytecode.pushInt(insns, ((int) value) ^ (mask ^ lowPad));
        insns.add(new VarInsnNode(Opcodes.ILOAD, dynamicTokenLocal));
        JvmPassBytecode.pushInt(insns, lowPad);
        insns.add(new InsnNode(Opcodes.IXOR));
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

    private void emitLiveIndyWord(
        InsnList insns,
        String owner,
        HelperNames helpers,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        boolean interfaceOwner,
        int indyMaterialLocal,
        int flowSaltSlot
    ) {
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.guardLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pathKeyLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.blockKeyLocal()));
        insns.add(new VarInsnNode(Opcodes.ILOAD, metadata.pcLocal()));
        insns.add(new VarInsnNode(Opcodes.ALOAD, indyMaterialLocal));
        JvmPassBytecode.pushInt(insns, flowSaltSlot);
        insns.add(new VarInsnNode(Opcodes.LLOAD, metadata.keyLocal()));
        JvmPassBytecode.pushInt(insns, state.state());
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            helpers.flowOwner(),
            helpers.flow(),
            FLOW_DESC,
            helpers.flowInterfaceOwner()
        ));
    }

    private void emitCachedLiveIndyWord(
        InsnList insns,
        String owner,
        HelperNames helpers,
        ControlFlowFlatteningPass.CffMethodMetadata metadata,
        ControlFlowFlatteningPass.CffInstructionState state,
        boolean interfaceOwner,
        int flowCacheLocal,
        int indyMaterialLocal,
        int flowSaltSlot
    ) {
        LabelNode done = new LabelNode();
        insns.add(new VarInsnNode(Opcodes.LLOAD, flowCacheLocal));
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new InsnNode(Opcodes.LCONST_0));
        insns.add(new InsnNode(Opcodes.LCMP));
        insns.add(new JumpInsnNode(Opcodes.IFNE, done));
        insns.add(new InsnNode(Opcodes.POP2));
        emitLiveIndyWord(
            insns,
            owner,
            helpers,
            metadata,
            state,
            interfaceOwner,
            indyMaterialLocal,
            flowSaltSlot
        );
        insns.add(new InsnNode(Opcodes.DUP2));
        insns.add(new VarInsnNode(Opcodes.LSTORE, flowCacheLocal));
        insns.add(done);
    }

    private MethodNode emitFlow(
        String name,
        int classKeyMask,
        int access
    ) {
        MethodNode mn = new MethodNode(
            access,
            name,
            FLOW_DESC,
            null,
            null
        );
        InsnList insns = mn.instructions;
        int carrierLocal = 4;
        int flowSlotLocal = 5;
        int stateLocal = 8;
        int xLocal = 9;
        int idxLocal = 10;
        int saltCellLocal = 11;
        int methodSaltLocal = 12;
        int siteSeedLocal = 14;
        int epochLocal = 16;
        int nextEpochLocal = 18;
        int flowTableLocal = 20;
        int selectorFoldLocal = 21;
        int saltLockLocal = 22;
        int sourceLocal = 23;
        int threadLocal = 24;
        int stackLocal = 25;
        int stackLenLocal = 26;

        emitRuntimeThreadStackSource(
            insns,
            sourceLocal,
            threadLocal,
            stackLocal,
            stackLenLocal,
            FLOW_HELPER_METHOD_KEY_LOCAL,
            stateLocal
        );
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowSlotLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, FLOW_HELPER_METHOD_KEY_LOCAL));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, FLOW_HELPER_METHOD_KEY_LOCAL));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, selectorFoldLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.INDY_MATERIAL_SELECTOR_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, selectorFoldLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, OBJECT_ARRAY_DESC));
        insns.add(new VarInsnNode(Opcodes.ASTORE, flowTableLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, flowTableLocal));
        insns.add(new InsnNode(Opcodes.ICONST_0));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[J"));
        insns.add(new VarInsnNode(Opcodes.ASTORE, saltCellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowSlotLocal));
        JvmPassBytecode.pushInt(insns, 3);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, idxLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, flowTableLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, OBJECT_ARRAY_DESC));
        insns.add(new VarInsnNode(Opcodes.ILOAD, flowSlotLocal));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new VarInsnNode(Opcodes.ASTORE, saltLockLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltLockLocal));
        insns.add(new InsnNode(Opcodes.MONITORENTER));
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltCellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        insns.add(new InsnNode(Opcodes.LALOAD));
        insns.add(new VarInsnNode(Opcodes.LSTORE, epochLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltCellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.LALOAD));
        emitIndyFlowSaltMask(insns, flowSlotLocal, epochLocal, 0x494E4459464D534CL);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, methodSaltLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltCellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.LALOAD));
        emitIndyFlowSaltMask(insns, flowSlotLocal, epochLocal, 0x494E445946535445L);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LSTORE, siteSeedLocal));
        emitIndyFlowSaltNextEpoch(
            insns,
            flowSlotLocal,
            epochLocal,
            FLOW_HELPER_METHOD_KEY_LOCAL,
            stateLocal,
            sourceLocal
        );
        insns.add(new VarInsnNode(Opcodes.LSTORE, nextEpochLocal));
        emitIndyFlowSaltCellStore(
            insns,
            saltCellLocal,
            idxLocal,
            1,
            flowSlotLocal,
            nextEpochLocal,
            methodSaltLocal,
            0x494E4459464D534CL
        );
        emitIndyFlowSaltCellStore(
            insns,
            saltCellLocal,
            idxLocal,
            2,
            flowSlotLocal,
            nextEpochLocal,
            siteSeedLocal,
            0x494E445946535445L
        );
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltCellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, nextEpochLocal));
        insns.add(new InsnNode(Opcodes.LASTORE));
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltLockLocal));
        insns.add(new InsnNode(Opcodes.MONITOREXIT));

        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        emitSeededInt(insns, siteSeedLocal, 0x494E44474731L);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        emitShift(insns, siteSeedLocal, 9);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IXOR));
        emitSeededInt(insns, siteSeedLocal, 0x494E444D554CL);
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        emitSeededInt(insns, siteSeedLocal, 0x494E44504331L);
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        insns.add(new InsnNode(Opcodes.IADD));
        emitSeededInt(insns, siteSeedLocal, 0x494E445441424CL);
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, classKeyMask);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new VarInsnNode(Opcodes.ISTORE, idxLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, carrierLocal));
        emitIndyClassKeyWordsSlotSelectionFromCarrier(insns, selectorFoldLocal);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        insns.add(new InsnNode(Opcodes.IALOAD));
        emitSeededInt(insns, siteSeedLocal, 0x494E445456414CL);
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ISTORE, xLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, xLocal));
        emitShift(insns, siteSeedLocal, 21);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, 0xFFFFFFFFL);
        insns.add(new InsnNode(Opcodes.LAND));
        emitRuntimeBlockMethodKey(insns, methodSaltLocal);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, FLOW_HELPER_METHOD_KEY_LOCAL));
        JvmPassBytecode.pushLong(insns, 0x494E44594D4B31L);
        emitInlineMix(insns);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, siteSeedLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LRETURN));
        mn.maxStack = 12;
        mn.maxLocals = 27;
        return mn;
    }

    private void emitRuntimeThreadStackSource(
        InsnList insns,
        int sourceLocal,
        int threadLocal,
        int stackLocal,
        int stackLenLocal,
        int methodKeyLocal,
        int stateLocal
    ) {
        LabelNode noStack = new LabelNode();
        LabelNode skipFrame = new LabelNode();
        LabelNode skipTail = new LabelNode();
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/Thread",
            "currentThread",
            "()Ljava/lang/Thread;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, threadLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKESTATIC,
            "java/lang/System",
            "identityHashCode",
            "(Ljava/lang/Object;)I",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getName",
            "()Ljava/lang/String;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/String",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, 0x45d9f3b);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            OBJECT,
            "getClass",
            "()Ljava/lang/Class;",
            false
        ));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            OBJECT,
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, methodKeyLocal));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.LLOAD, methodKeyLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));

        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        JvmPassBytecode.pushInt(insns, 15);
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new JumpInsnNode(Opcodes.IFNE, noStack));
        insns.add(new VarInsnNode(Opcodes.ALOAD, threadLocal));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/Thread",
            "getStackTrace",
            "()[Ljava/lang/StackTraceElement;",
            false
        ));
        insns.add(new VarInsnNode(Opcodes.ASTORE, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new InsnNode(Opcodes.ARRAYLENGTH));
        insns.add(new VarInsnNode(Opcodes.ISTORE, stackLenLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLenLocal));
        JvmPassBytecode.pushInt(insns, 0x7feb352d);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLenLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, skipFrame));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        JvmPassBytecode.pushInt(insns, 0x85ebca6b);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        JvmPassBytecode.pushInt(insns, 2);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(skipFrame);
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLenLocal));
        JvmPassBytecode.pushInt(insns, 4);
        insns.add(new JumpInsnNode(Opcodes.IF_ICMPLE, skipTail));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ALOAD, stackLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stackLenLocal));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.ISUB));
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new MethodInsnNode(
            Opcodes.INVOKEVIRTUAL,
            "java/lang/StackTraceElement",
            "hashCode",
            "()I",
            false
        ));
        insns.add(new InsnNode(Opcodes.IADD));
        JvmPassBytecode.pushInt(insns, 0xc2b2ae35);
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
        insns.add(skipTail);
        insns.add(noStack);
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ISTORE, sourceLocal));
    }

    private void emitIndyClassKeyWordsSlotSelectionFromCarrier(
        InsnList insns,
        int selectorFoldLocal
    ) {
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, ControlFlowFlatteningPass.CLASS_KEY_WORDS_SELECTOR_SLOT);
        insns.add(new InsnNode(Opcodes.AALOAD));
        insns.add(new TypeInsnNode(Opcodes.CHECKCAST, "[I"));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.IADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, selectorFoldLocal));
        insns.add(new InsnNode(Opcodes.IXOR));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IAND));
        insns.add(new InsnNode(Opcodes.IALOAD));
    }

    private void emitIndyFlowSaltMask(
        InsnList insns,
        int slotLocal,
        int epochLocal,
        long tag
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, epochLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, tag);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushLong(insns, tag ^ 0x494E4459464D4153L);
        insns.add(new InsnNode(Opcodes.LXOR));
        emitInlineMix(insns);
    }

    private void emitIndyFlowSaltNextEpoch(
        InsnList insns,
        int slotLocal,
        int epochLocal,
        int methodKeyLocal,
        int stateLocal,
        int sourceLocal
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, epochLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, methodKeyLocal));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, slotLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 0));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 1));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 2));
        insns.add(new InsnNode(Opcodes.I2L));
        JvmPassBytecode.pushInt(insns, 17);
        insns.add(new InsnNode(Opcodes.LSHL));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, 3));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new VarInsnNode(Opcodes.ILOAD, stateLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LADD));
        insns.add(new VarInsnNode(Opcodes.ILOAD, sourceLocal));
        insns.add(new InsnNode(Opcodes.I2L));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, 0x494E44594645504CL);
        emitInlineMix(insns);
    }

    private void emitIndyFlowSaltCellStore(
        InsnList insns,
        int saltCellLocal,
        int idxLocal,
        int indexOffset,
        int slotLocal,
        int epochLocal,
        int valueLocal,
        long tag
    ) {
        insns.add(new VarInsnNode(Opcodes.ALOAD, saltCellLocal));
        insns.add(new VarInsnNode(Opcodes.ILOAD, idxLocal));
        if (indexOffset != 0) {
            JvmPassBytecode.pushInt(insns, indexOffset);
            insns.add(new InsnNode(Opcodes.IADD));
        }
        insns.add(new VarInsnNode(Opcodes.LLOAD, valueLocal));
        emitIndyFlowSaltMask(insns, slotLocal, epochLocal, tag);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.LASTORE));
    }

    private void emitRuntimeBlockMethodKey(InsnList insns, int methodSaltLocal) {
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
        insns.add(new VarInsnNode(Opcodes.LLOAD, methodSaltLocal));
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
        int seedLocal,
        long salt
    ) {
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        insns.add(new VarInsnNode(Opcodes.LLOAD, seedLocal));
        JvmPassBytecode.pushInt(insns, 32);
        insns.add(new InsnNode(Opcodes.LUSHR));
        insns.add(new InsnNode(Opcodes.LXOR));
        JvmPassBytecode.pushLong(insns, salt);
        insns.add(new InsnNode(Opcodes.LXOR));
        insns.add(new InsnNode(Opcodes.L2I));
        insns.add(new InsnNode(Opcodes.DUP));
        JvmPassBytecode.pushInt(insns, 16);
        insns.add(new InsnNode(Opcodes.IUSHR));
        insns.add(new InsnNode(Opcodes.IXOR));
        JvmPassBytecode.pushInt(insns, nonZeroInt(salt >>> 32));
        insns.add(new InsnNode(Opcodes.IMUL));
        insns.add(new InsnNode(Opcodes.ICONST_1));
        insns.add(new InsnNode(Opcodes.IOR));
    }

    private int seededIndyInt(long seed, long salt) {
        int value = (int) (seed ^ (seed >>> 32) ^ salt);
        value ^= value >>> 16;
        return (value * nonZeroInt(salt >>> 32)) | 1;
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

    private record HelperNames(
        String bootstrapOwner,
        String bootstrap,
        boolean bootstrapInterfaceOwner,
        String flowOwner,
        String flow,
        boolean flowInterfaceOwner,
        String guardOwner,
        String guard,
        boolean guardInterfaceOwner
    ) {}

    private record SharedHelperNames(
        String bootstrapOwner,
        String bootstrap,
        boolean bootstrapInterfaceOwner,
        String bootstrapCoreOwner,
        String bootstrapCore,
        boolean bootstrapCoreInterfaceOwner,
        String resolverOwner,
        String resolver,
        boolean resolverInterfaceOwner,
        String flowOwner,
        String flow,
        boolean flowInterfaceOwner,
        String guardOwner,
        String guard,
        boolean guardInterfaceOwner
    ) {}

    private record IndyFlowTable(
        String owner,
        String fieldName,
        MethodNode initHelper,
        List<long[]> cells,
        List<long[]> resolverCells,
        LabelNode initStart,
        LabelNode initEnd
    ) {}


    private record SiteSpec(int kind, String owner, String name, String desc, String indyDesc) {}
}
