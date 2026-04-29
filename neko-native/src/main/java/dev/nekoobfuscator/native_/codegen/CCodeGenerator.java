package dev.nekoobfuscator.native_.codegen;

import dev.nekoobfuscator.core.ir.l3.CFunction;
import dev.nekoobfuscator.core.ir.l3.CStatement;
import dev.nekoobfuscator.core.ir.l3.CVariable;
import dev.nekoobfuscator.native_.codegen.emit.JniHandlesShimEmitter;
import dev.nekoobfuscator.native_.codegen.emit.JniOnLoadEmitter;
import dev.nekoobfuscator.native_.codegen.emit.ManifestEmitter;
import dev.nekoobfuscator.native_.codegen.emit.MethodPatcherEmitter;
import dev.nekoobfuscator.native_.codegen.emit.NativeToJavaInvokeEmitter;
import dev.nekoobfuscator.native_.codegen.emit.SignatureDispatcherEmitter;
import dev.nekoobfuscator.native_.codegen.emit.SignaturePlan;
import dev.nekoobfuscator.native_.codegen.emit.TrampolineEmitter;
import dev.nekoobfuscator.native_.translator.NativeTranslator.NativeMethodBinding;
import org.objectweb.asm.Type;

import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CCodeGenerator {
    @SuppressWarnings("unused")
    private final SymbolTableGenerator symbols;
    private final ManifestEmitter manifestEmitter = new ManifestEmitter();
    private final SignatureDispatcherEmitter signatureDispatcherEmitter = new SignatureDispatcherEmitter();
    private final TrampolineEmitter trampolineEmitter = new TrampolineEmitter();
    private final MethodPatcherEmitter methodPatcherEmitter = new MethodPatcherEmitter();
    private final JniHandlesShimEmitter jniHandlesShimEmitter = new JniHandlesShimEmitter();
    private final JniOnLoadEmitter jniOnLoadEmitter = new JniOnLoadEmitter();
    private final NativeToJavaInvokeEmitter nativeToJavaInvokeEmitter = new NativeToJavaInvokeEmitter();
    private final LinkedHashMap<String, Integer> classSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> methodSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> fieldSlotIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> ownerBindIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, OwnerResolution> ownerResolutions = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> icacheMethodIndex = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheSiteRef> icacheSites = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheDirectStubRef> icacheDirectStubs = new LinkedHashMap<>();
    private final LinkedHashMap<String, IcacheMetaRef> icacheMetas = new LinkedHashMap<>();
    private int stringCacheCount;

    public CCodeGenerator(long masterSeed) {
        this.symbols = new SymbolTableGenerator(masterSeed);
    }

    public void configureStringCacheCount(int stringCacheCount) {
        this.stringCacheCount = stringCacheCount;
    }

    public int internClass(String internalName) {
        return classSlotIndex.computeIfAbsent(internalName, ignored -> classSlotIndex.size());
    }

    public int internMethod(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "V");
        return methodSlotIndex.computeIfAbsent(key, ignored -> methodSlotIndex.size());
    }

    public int internField(String owner, String name, String desc, boolean isStatic) {
        String key = owner + "." + name + desc + "/" + (isStatic ? "S" : "I");
        return fieldSlotIndex.computeIfAbsent(key, ignored -> fieldSlotIndex.size());
    }

    public String classSlotName(String internalName) {
        return "g_cls_" + internClass(internalName);
    }

    public String objectArrayKlassBitsSlotName(String internalName) {
        return "g_obj_array_klass_" + internClass(internalName);
    }

    public String methodSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mid_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodPtrSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mptr_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodCEntrySlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mcentry_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodIEntrySlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mientry_" + internMethod(owner, name, desc, isStatic);
    }

    public String methodHolderSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_mholder_" + internMethod(owner, name, desc, isStatic);
    }

    public String fieldSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_fid_" + internField(owner, name, desc, isStatic);
    }

    public String fieldOffsetSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_off_" + internField(owner, name, desc, isStatic);
    }

    public String staticFieldOffsetSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_static_off_" + internField(owner, name, desc, isStatic);
    }

    public String staticFieldBaseSlotName(String owner, String name, String desc, boolean isStatic) {
        return "g_static_base_" + internField(owner, name, desc, isStatic);
    }

    public void registerBindingOwner(String ownerInternalName) {
        ownerBindIndex.computeIfAbsent(ownerInternalName, ignored -> ownerBindIndex.size());
        OwnerResolution resolution = ownerResolutions.computeIfAbsent(ownerInternalName, ignored -> new OwnerResolution());
        resolution.classes.add(ownerInternalName);
        internClass(ownerInternalName);
    }

    public void registerOwnerClassReference(String bindingOwner, String classOwner) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).classes.add(classOwner);
        internClass(classOwner);
    }

    public void registerOwnerMethodReference(String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        registerOwnerClassReference(bindingOwner, owner);
        ownerResolutions.get(bindingOwner).methods.add(new MethodRef(owner, name, desc, isStatic));
        internMethod(owner, name, desc, isStatic);
    }

    public void registerOwnerFieldReference(String bindingOwner, String owner, String name, String desc, boolean isStatic) {
        registerOwnerClassReference(bindingOwner, owner);
        ownerResolutions.get(bindingOwner).fields.add(new FieldRef(owner, name, desc, isStatic));
        internField(owner, name, desc, isStatic);
    }

    public void registerOwnerStringReference(String bindingOwner, String value, String cacheVar) {
        registerBindingOwner(bindingOwner);
        ownerResolutions.get(bindingOwner).strings.add(new StringRef(cacheVar, value));
    }

    public String reserveInvokeCacheSite(String bindingOwner, String methodKey, int siteIndex) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheSites.computeIfAbsent(siteKey, ignored -> new IcacheSiteRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            bindingOwner,
            methodKey
        )).symbol();
    }

    public String reserveInvokeCacheDirectStub(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        NativeMethodBinding binding,
        Type[] args,
        Type returnType
    ) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheDirectStubs.computeIfAbsent(siteKey, ignored -> new IcacheDirectStubRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            binding,
            args.clone(),
            returnType
        )).symbol();
    }

    /** Register an invoke-callee shape with the native→Java dispatcher emitter.
     * Returns the C dispatcher symbol that call sites can use as a function
     * pointer. Idempotent across duplicate shapes. */
    public String registerInvokeShape(SignaturePlan.Shape shape) {
        return nativeToJavaInvokeEmitter.register(shape);
    }

    /** Returns the dispatcher symbol for the given (isStatic, ret, args)
     * tuple, registering it if not already present. Convenience for callers
     * that don't already have a SignaturePlan.Shape on hand. */
    public String registerInvokeShape(boolean isStatic, char returnKind, char[] argKinds) {
        return nativeToJavaInvokeEmitter.register(SignaturePlan.Shape.of(returnKind, argKinds, isStatic));
    }

    public String reserveInvokeCacheMeta(
        String bindingOwner,
        String methodKey,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol,
        String directDispatcherSymbol
    ) {
        String cacheMethodKey = bindingOwner + '#' + methodKey;
        String siteKey = cacheMethodKey + '#' + siteIndex;
        registerBindingOwner(bindingOwner);
        return icacheMetas.computeIfAbsent(siteKey, ignored -> new IcacheMetaRef(
            ownerBindIndex.get(bindingOwner),
            icacheMethodIndex.computeIfAbsent(cacheMethodKey, key -> icacheMethodIndex.size()),
            siteIndex,
            name,
            desc,
            isInterface,
            translatedClassSlot,
            translatedStubSymbol,
            directDispatcherSymbol
        )).symbol();
    }

    public String generateHeader(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("#ifndef NEKO_NATIVE_H\n");
        sb.append("#define NEKO_NATIVE_H\n\n");
        sb.append("#include <jni.h>\n\n");
        sb.append("\n#endif\n");
        return sb.toString();
    }

    public String generateSource(List<CFunction> functions, List<NativeMethodBinding> bindings) {
        StringBuilder body = new StringBuilder();
        for (CFunction function : functions) {
            body.append(renderRawFunction(function)).append("\n");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("#include \"neko_native.h\"\n");
        sb.append("#include <stddef.h>\n");
        sb.append("#include <stdint.h>\n");
        sb.append("#include <stdio.h>\n");
        sb.append("#include <stdlib.h>\n");
        sb.append("#include <string.h>\n");
        sb.append("#include <math.h>\n\n");
        SignaturePlan signaturePlan = SignaturePlan.build(bindings);
        /* Forward decls + manifest struct first; everything below references them. */
        sb.append(manifestEmitter.renderStructAndForwardDecls());
        sb.append("/* Forward decls for trampoline ↔ patcher coupling. */\n");
        sb.append("struct neko_sig_entry { void *i2i; void *c2i; };\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern const struct neko_sig_entry g_neko_sig_table[];\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern const uint32_t g_neko_sig_table_count;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern const uint32_t g_neko_sig_extraspace_words[];\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void * const g_neko_sig_i2i_path2[];\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_state;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_thread_state_in_java;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_thread_state_in_native;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_thread_state_in_native_trans;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_polling_word;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_thread_state_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_last_Java_sp;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_last_Java_fp;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_last_Java_pc;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_frame_anchor_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_handle_push_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_native_resolution_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern jboolean  g_neko_gc_barrier_ready;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_gc_barrier_kind;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_load_oop_field_preloaded;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_load_oop_array;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_store_oop_field;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_write_ref_array_pre;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_write_ref_field_pre;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_barrier_write_ref_field_post;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern void     *g_neko_card_table_byte_map_base;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_card_table_dirty_card;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_card_table_clean_card;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_g1_young_card;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern int32_t   g_neko_card_table_shift;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) extern ptrdiff_t g_neko_off_thread_pending_exception;\n");
        sb.append("__attribute__((visibility(\"hidden\"))) void neko_handle_safepoint_poll(void);\n");
        /* The save/restore/push helpers and neko_thread_jni_env / neko_jni_env_to_thread
         * are defined as `static inline __attribute__((always_inline))` further
         * down (inside the methodPatcherEmitter region). No extern declarations
         * here — extern would shadow the inline definitions and force the
         * dispatcher to make a real function call. */
        sb.append("typedef struct { void *thread; void *block; void *saved_next; void *saved_last; int32_t saved_top; } neko_handle_save_t;\n");
        sb.append("static jboolean neko_resolve_jnihandles(void *jvm);\n");
        sb.append("static void *neko_dlsym(void *h, const char *name);\n");
        sb.append("static void neko_njx_init_wrappers(void);\n\n");
        sb.append("static void neko_fast_string_runtime_init(JNIEnv *env);\n\n");
        sb.append(renderResolutionCaches());
        sb.append(renderRawFunctionPrototypes(bindings));
        sb.append(nativeToJavaInvokeEmitter.renderPrelude());
        sb.append(renderRuntimeSupport());
        sb.append(renderHotSpotSupport());
        sb.append(jniHandlesShimEmitter.render());
        sb.append(methodPatcherEmitter.render());
        /* Fast AALOAD helper: piggy-backs on methodPatcherEmitter's inline
         * neko_handle_push, so it has to come right after. Available to every
         * impl_fn / export wrapper / dispatcher emitted below. */
        sb.append(renderObjectArrayFastHelpers());
        /* Native→Java direct invoke dispatcher bodies — must come AFTER
         * methodPatcherEmitter (uses g_neko_call_stub_entry / g_neko_off_*
         * globals it publishes) and AFTER renderObjectArrayFastHelpers
         * (uses neko_direct_oop_to_handle for object-return marshalling). */
        sb.append(nativeToJavaInvokeEmitter.renderBodies());
        sb.append(nativeToJavaInvokeEmitter.renderInitFunction());
        sb.append(renderBindSupport());
        sb.append(jniOnLoadEmitter.renderRegistrationTable());
        sb.append(renderBindOwnerFunctions());
        sb.append(renderIcacheDirectStubs());
        sb.append(renderIcacheMetas());
        sb.append(body);
        sb.append(manifestEmitter.renderTables(bindings, signaturePlan));
        sb.append(signatureDispatcherEmitter.render(signaturePlan));
        sb.append(trampolineEmitter.render(signaturePlan));
        sb.append(manifestEmitter.renderDiscoveryDriver(bindings, ownerBindIndex));
        sb.append(jniOnLoadEmitter.renderJniOnLoadAndBootstrap());
        return sb.toString();
    }


    private String renderRawFunction(CFunction fn) {
        StringBuilder sb = new StringBuilder();
        sb.append("static ").append(fn.returnType().jniName()).append(' ').append(fn.name()).append('(');
        for (int i = 0; i < fn.params().size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderParam(fn.params().get(i)));
        }
        sb.append(") {\n");
        sb.append("    neko_slot stack[").append(fn.maxStack() + 16).append("];\n");
        sb.append("    int sp = 0;\n");
        /* Locals are uninitialized at function entry (Java spec requires
         * every local be assigned before read). Skipping the memset saves
         * 26+ qword-stores per call on every translated method. The stack
         * slots are also uninitialized — POP only ever returns previously
         * PUSH'd values per JVM verifier guarantees. */
        sb.append("    neko_slot locals[").append(fn.maxLocals() + 8).append("];\n");
        for (CStatement statement : fn.body()) {
            sb.append(renderStatement(statement));
        }
        sb.append("}\n");
        return sb.toString();
    }

    private String renderRawFunctionPrototypes(List<NativeMethodBinding> bindings) {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Raw translated-body prototypes ===\n");
        for (NativeMethodBinding binding : bindings) {
            sb.append("static ").append(jniType(Type.getReturnType(binding.descriptor()))).append(' ')
                .append(binding.rawFunctionName()).append("(void *thread, JNIEnv *env, ")
                .append(binding.isStatic() ? "jclass clazz" : "jobject self");
            Type[] args = Type.getArgumentTypes(binding.descriptor());
            for (int i = 0; i < args.length; i++) {
                sb.append(", ").append(jniType(args[i])).append(" p").append(i);
            }
            sb.append(");\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderStatement(CStatement statement) {
        if (statement instanceof CStatement.RawC raw) {
            return "    " + raw.code() + "\n";
        }
        if (statement instanceof CStatement.Label label) {
            return label.name() + ": ;\n";
        }
        if (statement instanceof CStatement.Goto go) {
            return "    goto " + go.label() + ";\n";
        }
        if (statement instanceof CStatement.ReturnVoid) {
            return "    return;\n";
        }
        if (statement instanceof CStatement.Return ret) {
            return "    return " + ret.value() + ";\n";
        }
        if (statement instanceof CStatement.Comment comment) {
            return "    /* " + comment.text() + " */\n";
        }
        throw new IllegalStateException("Unsupported C statement in generator: " + statement.getClass().getSimpleName());
    }

    private String renderResolutionCaches() {
        StringBuilder sb = new StringBuilder();
        sb.append("// === Global resolution caches ===\n");
        sb.append("#define NEKO_ICACHE_PIC_SIZE 4u\n");
        sb.append("typedef struct neko_icache_site {\n");
        sb.append("    uintptr_t receiver_key[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    void* target[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    void* target2[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    jclass cached_class[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    uint8_t target_kind[NEKO_ICACHE_PIC_SIZE];\n");
        sb.append("    uint8_t next_slot;\n");
        sb.append("    uint16_t miss_count;\n");
        sb.append("    /* legacy icache field names: receiver_key; target; target_kind; cached_class; */\n");
        /* For NEKO_ICACHE_DIRECT_NJX: target = HotSpot Method* (resolved
         * from the receiver-class-specific jmethodID), target2 = the
         * cached _from_compiled_entry pointer. The dispatcher itself
         * comes from neko_icache_meta::direct_dispatcher (shape-specific,
         * known at codegen time). */
        sb.append("} neko_icache_site;\n\n");
        sb.append("#define NEKO_ICACHE_EMPTY 0u\n");
        sb.append("#define NEKO_ICACHE_DIRECT_C 1u\n");
        sb.append("#define NEKO_ICACHE_MEGA 3u\n");
        sb.append("#define NEKO_ICACHE_DIRECT_NJX 4u\n");
        sb.append("#define NEKO_ICACHE_MEGA_THRESHOLD 16u\n\n");
        for (Map.Entry<String, Integer> entry : classSlotIndex.entrySet()) {
            sb.append("static jclass g_cls_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
            sb.append("static uintptr_t g_obj_array_klass_").append(entry.getValue()).append(" = 0;\n");
        }
        for (Map.Entry<String, Integer> entry : methodSlotIndex.entrySet()) {
            sb.append("static jmethodID g_mid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
            /* HotSpot Method* + cached entry pointers — populated at JNI_OnLoad
             * by neko_bind_method_slot once the layout walker has resolved the
             * Method::_from_compiled_entry / _from_interpreted_entry offsets.
             * Reads on the hot path are direct memory loads; writes are
             * single-pointer stores so torn reads are impossible on x86-64
             * and AArch64 (8-byte aligned, naturally atomic). */
            sb.append("static void* g_mptr_").append(entry.getValue()).append(" = NULL;\n");
            sb.append("static void* g_mcentry_").append(entry.getValue()).append(" = NULL;\n");
            sb.append("static void* g_mientry_").append(entry.getValue()).append(" = NULL;\n");
            sb.append("static void* g_mholder_").append(entry.getValue()).append(" = NULL;\n");
        }
        for (Map.Entry<String, Integer> entry : fieldSlotIndex.entrySet()) {
            sb.append("static jfieldID g_fid_").append(entry.getValue()).append(" = NULL;   // ").append(entry.getKey()).append("\n");
            sb.append("static jlong g_off_").append(entry.getValue()).append(" = -1;\n");
            sb.append("static jlong g_static_off_").append(entry.getValue()).append(" = -1;\n");
            sb.append("static jobject g_static_base_").append(entry.getValue()).append(" = NULL;\n");
        }
        for (int i = 0; i < stringCacheCount; i++) {
            sb.append("static jstring g_str_").append(i).append(" = NULL;\n");
        }
        for (Map.Entry<String, Integer> entry : ownerBindIndex.entrySet()) {
            sb.append("static jboolean g_owner_bound_").append(entry.getValue()).append(" = JNI_FALSE;   // ").append(entry.getKey()).append("\n");
        }
        for (IcacheSiteRef site : icacheSites.values()) {
            sb.append("static neko_icache_site ").append(site.symbol()).append(" = {0};   // ")
                .append(site.bindingOwner()).append(" :: ").append(site.methodKey()).append(" [site ")
                .append(site.siteIndex()).append("]\n");
        }
        sb.append("\n");
        sb.append("#define NEKO_ENSURE_CLASS(slot, env, name) ((slot) != NULL ? (slot) : ((slot) = (jclass)neko_new_global_ref((env), neko_find_class((env), (name)))))\n");
        sb.append("#define NEKO_ENSURE_STRING(slot, env, utf) ((slot) != NULL ? (slot) : ((slot) = (jstring)neko_new_global_ref((env), neko_new_string_utf((env), (utf)))))\n");
        sb.append("#define NEKO_ENSURE_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_method_id((env), (cls), (name), (desc))))\n");
        sb.append("#define NEKO_ENSURE_STATIC_METHOD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_method_id((env), (cls), (name), (desc))))\n");
        sb.append("#define NEKO_ENSURE_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_field_id((env), (cls), (name), (desc))))\n");
        sb.append("#define NEKO_ENSURE_STATIC_FIELD_ID(slot, env, cls, name, desc) ((slot) != NULL ? (slot) : ((slot) = neko_get_static_field_id((env), (cls), (name), (desc))))\n\n");
        return sb.toString();
    }

    private String renderBindSupport() {
        return """
typedef jclass (*neko_jvm_find_class_boot_t)(JNIEnv*, const char*);
typedef jclass (*neko_jvm_find_class_from_class_t)(JNIEnv*, const char*, jboolean, jclass);

static void *neko_class_mirror_to_klass(jclass mirror) {
    void *mirror_oop;
    void *klass;
    if (mirror == NULL) return NULL;
    if (g_neko_method_layout.off_java_lang_class_klass < 0) {
        fprintf(stderr, "[neko-bind] java.lang.Class::_klass offset unavailable\\n");
        abort();
    }
    mirror_oop = neko_handle_oop((jobject)mirror);
    if (mirror_oop == NULL) return NULL;
    klass = *(void**)((char*)mirror_oop + g_neko_method_layout.off_java_lang_class_klass);
    return klass;
}

static jobject neko_klass_java_mirror_handle(void *thread, void *klass) {
    void *mirror_oop;
    if (klass == NULL) return NULL;
    if (g_neko_method_layout.off_klass_java_mirror < 0) {
        fprintf(stderr, "[neko-bind] Klass::_java_mirror offset unavailable\\n");
        abort();
    }
    mirror_oop = *(void**)((char*)klass + g_neko_method_layout.off_klass_java_mirror);
    return mirror_oop != NULL ? neko_handle_push(thread, mirror_oop) : NULL;
}

static void *neko_resolve_class_with_mirror(const char *utf8, jclass from_class) {
    void *thread;
    JNIEnv *env;
    jclass resolved = NULL;
    void *klass;
    if (utf8 == NULL || utf8[0] == '\\0') {
        fprintf(stderr, "[neko-bind] class resolution requested with empty name\\n");
        abort();
    }
    if (g_neko_method_layout.off_java_lang_class_klass < 0) {
        fprintf(stderr, "[neko-bind] native class resolution missing java.lang.Class::_klass: %s\\n", utf8);
        abort();
    }
    thread = neko_current_thread_register();
    env = neko_thread_jni_env(thread);
    if (from_class != NULL && g_neko_method_layout.sym_jvm_find_class_from_class != NULL) {
        resolved = ((neko_jvm_find_class_from_class_t)g_neko_method_layout.sym_jvm_find_class_from_class)(
            env, utf8, JNI_FALSE, from_class);
    }
    if (resolved == NULL && g_neko_method_layout.sym_jvm_find_class_from_boot_loader != NULL) {
        resolved = ((neko_jvm_find_class_boot_t)g_neko_method_layout.sym_jvm_find_class_from_boot_loader)(env, utf8);
    }
    klass = neko_class_mirror_to_klass(resolved);
    if (klass == NULL) {
        fprintf(stderr, "[neko-bind] native class resolution failed: %s\\n", utf8);
        abort();
    }
    return klass;
}

static void *neko_resolve_class(const char *utf8) {
    return neko_resolve_class_with_mirror(utf8, NULL);
}

static jboolean neko_symbol_equals_utf8(void *symbol, const char *utf8) {
    uint16_t len;
    const char *body;
    if (symbol == NULL || utf8 == NULL) return JNI_FALSE;
    if (g_neko_method_layout.off_symbol_length < 0 || g_neko_method_layout.off_symbol_body < 0) {
        fprintf(stderr, "[neko-bind] Symbol layout unavailable\\n");
        abort();
    }
    len = *(uint16_t*)((char*)symbol + g_neko_method_layout.off_symbol_length);
    body = (const char*)symbol + g_neko_method_layout.off_symbol_body;
    return strlen(utf8) == (size_t)len && memcmp(body, utf8, len) == 0 ? JNI_TRUE : JNI_FALSE;
}

static ptrdiff_t neko_constantpool_base_offset(void) {
    if (g_neko_method_layout.off_constantpool_base >= 0) {
        return g_neko_method_layout.off_constantpool_base;
    }
    if (g_neko_method_layout.sizeof_ConstantPool > 0) {
        return (ptrdiff_t)g_neko_method_layout.sizeof_ConstantPool;
    }
    if (g_neko_method_layout.off_constantpool_length < 0) {
        fprintf(stderr, "[neko-bind] ConstantPool base layout unavailable\\n");
        abort();
    }
    fprintf(stderr, "[neko-bind] ConstantPool base layout unavailable\\n");
    abort();
}

static void *neko_constantpool_symbol_at(void *constant_pool, uint16_t index) {
    int length;
    void **base;
    if (constant_pool == NULL) return NULL;
    if (g_neko_method_layout.off_constantpool_length < 0) {
        fprintf(stderr, "[neko-bind] ConstantPool symbol layout unavailable\\n");
        abort();
    }
    length = *(int*)((char*)constant_pool + g_neko_method_layout.off_constantpool_length);
    if (index == 0 || index >= (uint16_t)length) return NULL;
    base = (void**)((char*)constant_pool + neko_constantpool_base_offset());
    return base[index];
}

static void *neko_method_constmethod(void *method) {
    if (method == NULL) return NULL;
    if (g_neko_method_layout.off_method_constMethod < 0) {
        fprintf(stderr, "[neko-bind] Method::_constMethod layout unavailable\\n");
        abort();
    }
    return *(void**)((char*)method + g_neko_method_layout.off_method_constMethod);
}

typedef struct {
    jboolean found;
    jboolean is_static;
    uint32_t offset;
    uint32_t access_flags;
    void *holder_klass;
} neko_field_resolution_t;

typedef struct {
    const uint8_t *data;
    int length;
    int position;
} neko_u5_reader_t;

#define NEKO_JVM_ACC_STATIC 0x0008u
#define NEKO_FIELD_FLAG_INITIALIZED (1u << 0)
#define NEKO_FIELD_FLAG_INJECTED    (1u << 1)
#define NEKO_FIELD_FLAG_GENERIC     (1u << 2)
#define NEKO_FIELD_FLAG_CONTENDED   (1u << 4)
#define NEKO_FIELDINFO_LEGACY_SLOTS 6
#define NEKO_FIELDINFO_TAG_SIZE     2
#define NEKO_FIELDINFO_TAG_OFFSET   1u
#define NEKO_FIELDINFO_TAG_MASK     3u

static uint32_t neko_u5_next(neko_u5_reader_t *reader, const char *context) {
    uint32_t b0;
    uint32_t sum;
    int shift;
    if (reader == NULL || reader->data == NULL || reader->position < 0 || reader->position >= reader->length) {
        fprintf(stderr, "[neko-bind] truncated fieldinfo stream while reading %s\\n", context == NULL ? "<unknown>" : context);
        abort();
    }
    b0 = reader->data[reader->position];
    if (b0 < 1u) {
        fprintf(stderr, "[neko-bind] invalid unsigned5 zero byte while reading %s at %d\\n",
            context == NULL ? "<unknown>" : context, reader->position);
        abort();
    }
    sum = b0 - 1u;
    if (sum < 191u) {
        reader->position++;
        return sum;
    }
    shift = 6;
    for (int i = 1; i < 5; i++) {
        uint32_t bi;
        if (reader->position + i >= reader->length) {
            fprintf(stderr, "[neko-bind] truncated unsigned5 value while reading %s at %d\\n",
                context == NULL ? "<unknown>" : context, reader->position);
            abort();
        }
        bi = reader->data[reader->position + i];
        if (bi < 1u) {
            fprintf(stderr, "[neko-bind] invalid unsigned5 zero byte while reading %s at %d\\n",
                context == NULL ? "<unknown>" : context, reader->position + i);
            abort();
        }
        sum += (uint32_t)((bi - 1u) << shift);
        if (bi < 192u || i == 4) {
            reader->position += i + 1;
            return sum;
        }
        shift += 6;
    }
    fprintf(stderr, "[neko-bind] unsigned5 decode fell through while reading %s\\n", context == NULL ? "<unknown>" : context);
    abort();
}

static jboolean neko_match_field_symbols(void *constant_pool, uint32_t name_index, uint32_t sig_index, uint32_t field_flags, const char *name_utf8, const char *sig_utf8) {
    void *name_symbol;
    void *sig_symbol;
    if ((field_flags & NEKO_FIELD_FLAG_INJECTED) != 0) {
        return JNI_FALSE;
    }
    if (name_index > 0xffffu || sig_index > 0xffffu) {
        return JNI_FALSE;
    }
    name_symbol = neko_constantpool_symbol_at(constant_pool, (uint16_t)name_index);
    sig_symbol = neko_constantpool_symbol_at(constant_pool, (uint16_t)sig_index);
    return (neko_symbol_equals_utf8(name_symbol, name_utf8)
            && neko_symbol_equals_utf8(sig_symbol, sig_utf8))
        ? JNI_TRUE : JNI_FALSE;
}

static jboolean neko_resolve_declared_field_stream(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    void *stream_array;
    void *constant_pool;
    int stream_length;
    uint32_t java_fields;
    uint32_t injected_fields;
    uint32_t total_fields;
    neko_u5_reader_t reader;
    if (instance_klass == NULL || out == NULL || g_neko_method_layout.off_instanceklass_fieldinfo_stream < 0) {
        return JNI_FALSE;
    }
    if (g_neko_method_layout.off_instanceklass_constants < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_u1_data < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass fieldinfo stream layout unavailable\\n");
        abort();
    }
    stream_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_fieldinfo_stream);
    constant_pool = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_constants);
    if (stream_array == NULL || constant_pool == NULL) {
        return JNI_FALSE;
    }
    stream_length = *(int*)((char*)stream_array + g_neko_method_layout.off_array_length);
    if (stream_length <= 0) {
        return JNI_FALSE;
    }
    reader.data = (const uint8_t*)stream_array + g_neko_method_layout.off_array_u1_data;
    reader.length = stream_length;
    reader.position = 0;
    java_fields = neko_u5_next(&reader, "java field count");
    injected_fields = neko_u5_next(&reader, "injected field count");
    total_fields = java_fields + injected_fields;
    if (total_fields > 65535u) {
        fprintf(stderr, "[neko-bind] unreasonable field count in fieldinfo stream: %u\\n", total_fields);
        abort();
    }
    for (uint32_t i = 0; i < total_fields; i++) {
        uint32_t name_index = neko_u5_next(&reader, "field name index");
        uint32_t sig_index = neko_u5_next(&reader, "field signature index");
        uint32_t offset = neko_u5_next(&reader, "field offset");
        uint32_t access_flags = neko_u5_next(&reader, "field access flags");
        uint32_t field_flags = neko_u5_next(&reader, "field flags");
        if ((field_flags & NEKO_FIELD_FLAG_INITIALIZED) != 0) (void)neko_u5_next(&reader, "field initializer index");
        if ((field_flags & NEKO_FIELD_FLAG_GENERIC) != 0) (void)neko_u5_next(&reader, "field generic signature index");
        if ((field_flags & NEKO_FIELD_FLAG_CONTENDED) != 0) (void)neko_u5_next(&reader, "field contention group");
        if (((access_flags & NEKO_JVM_ACC_STATIC) != 0) != (is_static == JNI_TRUE)) {
            continue;
        }
        if (neko_match_field_symbols(constant_pool, name_index, sig_index, field_flags, name_utf8, sig_utf8)) {
            out->found = JNI_TRUE;
            out->is_static = (access_flags & NEKO_JVM_ACC_STATIC) != 0 ? JNI_TRUE : JNI_FALSE;
            out->offset = offset;
            out->access_flags = access_flags;
            out->holder_klass = instance_klass;
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static jboolean neko_resolve_declared_field_legacy(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    void *fields_array;
    void *constant_pool;
    int shorts_length;
    uint16_t *fields_data;
    if (instance_klass == NULL || out == NULL || g_neko_method_layout.off_instanceklass_fields < 0) {
        return JNI_FALSE;
    }
    if (g_neko_method_layout.off_instanceklass_constants < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_u2_data < 0) {
        fprintf(stderr, "[neko-bind] legacy InstanceKlass field array layout unavailable\\n");
        abort();
    }
    fields_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_fields);
    constant_pool = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_constants);
    if (fields_array == NULL || constant_pool == NULL) {
        return JNI_FALSE;
    }
    shorts_length = *(int*)((char*)fields_array + g_neko_method_layout.off_array_length);
    fields_data = (uint16_t*)((char*)fields_array + g_neko_method_layout.off_array_u2_data);
    if (shorts_length <= 0 || shorts_length % NEKO_FIELDINFO_LEGACY_SLOTS != 0) {
        return JNI_FALSE;
    }
    for (int base = 0; base + (NEKO_FIELDINFO_LEGACY_SLOTS - 1) < shorts_length; base += NEKO_FIELDINFO_LEGACY_SLOTS) {
        uint32_t access_flags = fields_data[base + 0];
        uint32_t name_index = fields_data[base + 1];
        uint32_t sig_index = fields_data[base + 2];
        uint32_t packed = ((uint32_t)fields_data[base + 5] << 16) | fields_data[base + 4];
        uint32_t offset;
        if (((access_flags & NEKO_JVM_ACC_STATIC) != 0) != (is_static == JNI_TRUE)) {
            continue;
        }
        if ((packed & NEKO_FIELDINFO_TAG_MASK) != NEKO_FIELDINFO_TAG_OFFSET) {
            continue;
        }
        if (!neko_match_field_symbols(constant_pool, name_index, sig_index, 0, name_utf8, sig_utf8)) {
            continue;
        }
        offset = packed >> NEKO_FIELDINFO_TAG_SIZE;
        out->found = JNI_TRUE;
        out->is_static = (access_flags & NEKO_JVM_ACC_STATIC) != 0 ? JNI_TRUE : JNI_FALSE;
        out->offset = offset;
        out->access_flags = access_flags;
        out->holder_klass = instance_klass;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean neko_resolve_declared_field(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    if (neko_resolve_declared_field_stream(instance_klass, name_utf8, sig_utf8, is_static, out)) {
        return JNI_TRUE;
    }
    return neko_resolve_declared_field_legacy(instance_klass, name_utf8, sig_utf8, is_static, out);
}

static jboolean neko_resolve_interface_field(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static, neko_field_resolution_t *out) {
    void *interfaces_array;
    int interface_count;
    void **interface_data;
    if (instance_klass == NULL) return JNI_FALSE;
    if (g_neko_method_layout.off_instanceklass_transitive_interfaces < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass::_transitive_interfaces layout unavailable for field resolution\\n");
        abort();
    }
    interfaces_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_transitive_interfaces);
    if (interfaces_array == NULL) return JNI_FALSE;
    interface_count = *(int*)((char*)interfaces_array + g_neko_method_layout.off_array_length);
    interface_data = (void**)((char*)interfaces_array + g_neko_method_layout.off_array_data);
    for (int i = 0; i < interface_count; i++) {
        if (neko_resolve_declared_field(interface_data[i], name_utf8, sig_utf8, is_static, out)) {
            return JNI_TRUE;
        }
    }
    return JNI_FALSE;
}

static neko_field_resolution_t neko_resolve_field(void *instance_klass, const char *name_utf8, const char *sig_utf8, jboolean is_static) {
    neko_field_resolution_t out;
    void *klass;
    memset(&out, 0, sizeof(out));
    if (instance_klass == NULL || name_utf8 == NULL || sig_utf8 == NULL) {
        fprintf(stderr, "[neko-bind] field resolution requested with null input\\n");
        abort();
    }
    if (g_neko_method_layout.off_klass_super < 0) {
        fprintf(stderr, "[neko-bind] Klass::_super layout unavailable for field resolution\\n");
        abort();
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        if (neko_resolve_declared_field(klass, name_utf8, sig_utf8, is_static, &out)) {
            return out;
        }
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        if (neko_resolve_interface_field(klass, name_utf8, sig_utf8, is_static, &out)) {
            return out;
        }
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    fprintf(stderr, "[neko-bind] native field resolution failed: %s:%s static=%d\\n",
        name_utf8, sig_utf8, (int)is_static);
    abort();
}

static void *neko_resolve_declared_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *methods_array;
    int method_count;
    void **method_data;
    if (g_neko_method_layout.off_instanceklass_methods < 0
        || g_neko_method_layout.off_array_length < 0
        || g_neko_method_layout.off_array_data < 0
        || g_neko_method_layout.off_constmethod_constants < 0
        || g_neko_method_layout.off_constmethod_name_index < 0
        || g_neko_method_layout.off_constmethod_signature_index < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass method layout unavailable\\n");
        abort();
    }
    methods_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_methods);
    if (methods_array == NULL) {
        return NULL;
    }
    method_count = *(int*)((char*)methods_array + g_neko_method_layout.off_array_length);
    method_data = (void**)((char*)methods_array + g_neko_method_layout.off_array_data);
    for (int i = 0; i < method_count; i++) {
        void *method = method_data[i];
        void *const_method = neko_method_constmethod(method);
        void *constant_pool;
        uint16_t name_index;
        uint16_t sig_index;
        void *name_symbol;
        void *sig_symbol;
        if (const_method == NULL) continue;
        constant_pool = *(void**)((char*)const_method + g_neko_method_layout.off_constmethod_constants);
        name_index = *(uint16_t*)((char*)const_method + g_neko_method_layout.off_constmethod_name_index);
        sig_index = *(uint16_t*)((char*)const_method + g_neko_method_layout.off_constmethod_signature_index);
        name_symbol = neko_constantpool_symbol_at(constant_pool, name_index);
        sig_symbol = neko_constantpool_symbol_at(constant_pool, sig_index);
        if (neko_symbol_equals_utf8(name_symbol, name_utf8)
            && neko_symbol_equals_utf8(sig_symbol, sig_utf8)) {
            return method;
        }
    }
    return NULL;
}

static void *neko_resolve_interface_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *interfaces_array;
    int interface_count;
    void **interface_data;
    if (instance_klass == NULL) return NULL;
    if (g_neko_method_layout.off_instanceklass_transitive_interfaces < 0) {
        fprintf(stderr, "[neko-bind] InstanceKlass::_transitive_interfaces layout unavailable\\n");
        abort();
    }
    interfaces_array = *(void**)((char*)instance_klass + g_neko_method_layout.off_instanceklass_transitive_interfaces);
    if (interfaces_array == NULL) return NULL;
    interface_count = *(int*)((char*)interfaces_array + g_neko_method_layout.off_array_length);
    interface_data = (void**)((char*)interfaces_array + g_neko_method_layout.off_array_data);
    for (int i = 0; i < interface_count; i++) {
        void *method = neko_resolve_declared_method(interface_data[i], name_utf8, sig_utf8);
        if (method != NULL) return method;
    }
    return NULL;
}

static void *neko_resolve_method(void *instance_klass, const char *name_utf8, const char *sig_utf8) {
    void *klass;
    if (instance_klass == NULL || name_utf8 == NULL || sig_utf8 == NULL) {
        fprintf(stderr, "[neko-bind] method resolution requested with null input\\n");
        abort();
    }
    if (g_neko_method_layout.off_klass_super < 0) {
        fprintf(stderr, "[neko-bind] Klass::_super layout unavailable\\n");
        abort();
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        void *method = neko_resolve_declared_method(klass, name_utf8, sig_utf8);
        if (method != NULL) return method;
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    klass = instance_klass;
    for (int depth = 0; klass != NULL && depth < 256; depth++) {
        void *method = neko_resolve_interface_method(klass, name_utf8, sig_utf8);
        if (method != NULL) return method;
        klass = *(void**)((char*)klass + g_neko_method_layout.off_klass_super);
    }
    fprintf(stderr, "[neko-bind] native method resolution failed: %s%s\\n", name_utf8, sig_utf8);
    abort();
}

static void neko_raise_bound_resolution_error(JNIEnv *env, const char *errorClass, const char *message) {
    if (env == NULL || errorClass == NULL || message == NULL) return;
    if (neko_exception_check(env)) neko_exception_clear(env);
    jclass error = neko_find_class(env, errorClass);
    if (error == NULL) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        return;
    }
    neko_throw_new(env, error, message);
}

static void neko_bind_log_failure(JNIEnv *env, const char *errorClass, const char *message) {
    neko_raise_bound_resolution_error(env, errorClass, message);
    if (env != NULL && neko_exception_check(env)) neko_exception_clear(env);
}

static void neko_bind_owner_class_slot(JNIEnv *env, jclass *slot, jclass self_class, const char *owner) {
    jobject globalRef;
    char message[256];
    if (env == NULL || slot == NULL || *slot != NULL) return;
    if (self_class == NULL) {
        snprintf(message, sizeof(message), "Bind-time owner class missing: %s", owner == NULL ? "<null>" : owner);
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", message);
        return;
    }
    globalRef = neko_new_global_ref(env, self_class);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time owner class global-ref failed: %s", owner == NULL ? "<null>" : owner);
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", message);
        return;
    }
    *slot = (jclass)globalRef;
}

static void neko_bind_class_slot(JNIEnv *env, jclass *slot, const char *owner) {
    jclass localClass;
    jobject globalRef;
    char message[256];
    if (env == NULL || slot == NULL || *slot != NULL || owner == NULL) return;
    localClass = neko_find_class(env, owner);
    if (localClass == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time class resolution failed: %s", owner);
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", message);
        if (localClass != NULL) neko_delete_local_ref(env, localClass);
        return;
    }
    globalRef = neko_new_global_ref(env, localClass);
    neko_delete_local_ref(env, localClass);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time class global-ref failed: %s", owner);
        neko_bind_log_failure(env, "java/lang/NoClassDefFoundError", message);
        return;
    }
    *slot = (jclass)globalRef;
}

static void neko_bind_method_slot(JNIEnv *env, jmethodID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    *slot = isStatic ? neko_get_static_method_id(env, cls, name, desc) : neko_get_method_id(env, cls, name, desc);
    if (*slot == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time %s method resolution failed: %s.%s%s", isStatic ? "static" : "instance", owner, name, desc);
        neko_bind_log_failure(env, "java/lang/NoSuchMethodError", message);
        *slot = NULL;
    }
}

/* Resolve the underlying HotSpot Method* and harvest cached entry pointers
 * from a JNI-resolved jmethodID. The Method::_from_compiled_entry and
 * _from_interpreted_entry fields contain the stable PC that JIT-compiled
 * callers and the interpreter (respectively) would jump to. We snapshot
 * them once at bind time so the hot dispatch path can issue a direct call
 * without any further JNI traffic.
 *
 * Caller must have already populated *midSlot via neko_bind_method_slot.
 * Holder slot caches the Method::method_holder() (an InstanceKlass*) so
 * callers can keep the holder reachable; on JDK 21+ Methods are kept alive
 * by their holder Klass so this is the right anchor for stability.
 *
 * No JNI on the resolution path here either (only memory reads off the
 * Method* whose layout offsets came from VMStructs at OnLoad time). */
static void neko_bind_method_entry_slots(jmethodID midSlot, jclass cls, const char *owner, const char *name, const char *desc, void **methodPtr, void **compiledEntry, void **interpretedEntry, void **holder) {
    void *klass;
    void *scanned;
    if (midSlot == NULL || cls == NULL || name == NULL || desc == NULL) {
        fprintf(stderr, "[neko-bind] method entry bind missing input: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner,
            name == NULL ? "<null>" : name,
            desc == NULL ? "<null>" : desc);
        abort();
    }
    if (!g_neko_method_layout.initialized || !g_neko_method_layout.usable) {
        fprintf(stderr, "[neko-bind] method layout not usable while binding: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner, name, desc);
        abort();
    }
    void *m = neko_jmethodid_to_method_star(midSlot);
    if (m == NULL) {
        fprintf(stderr, "[neko-bind] jmethodID did not decode to Method*: %s.%s%s\\n",
            owner == NULL ? "<null>" : owner, name, desc);
        abort();
    }
    klass = neko_class_mirror_to_klass(cls);
    scanned = neko_resolve_method(klass, name, desc);
    if (scanned != m) {
        fprintf(stderr, "[neko-bind] native method resolver mismatch: %s.%s%s jmethodID=%p scanned=%p\\n",
            owner == NULL ? "<null>" : owner, name, desc, m, scanned);
        abort();
    }
    if (methodPtr != NULL && *methodPtr == NULL) {
        *methodPtr = m;
    }
    if (compiledEntry != NULL && *compiledEntry == NULL
        && g_neko_method_layout.off_method_from_compiled_entry > 0) {
        *compiledEntry = *(void**)((char*)m + g_neko_method_layout.off_method_from_compiled_entry);
    }
    if (interpretedEntry != NULL && *interpretedEntry == NULL
        && g_neko_method_layout.off_method_from_interpreted_entry > 0) {
        *interpretedEntry = *(void**)((char*)m + g_neko_method_layout.off_method_from_interpreted_entry);
    }
    if (holder != NULL && *holder == NULL) {
        *holder = klass;
    }
}

static void neko_bind_field_slot(JNIEnv *env, jfieldID *slot, jclass cls, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    void *klass;
    neko_field_resolution_t native_field;
    if (env == NULL || slot == NULL || *slot != NULL || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    klass = neko_class_mirror_to_klass(cls);
    native_field = neko_resolve_field(klass, name, desc, isStatic);
    if (!native_field.found || native_field.offset == 0) {
        fprintf(stderr, "[neko-bind] native field resolver returned invalid metadata: %s.%s:%s static=%d offset=%u\\n",
            owner, name, desc, (int)isStatic, native_field.offset);
        abort();
    }
    *slot = isStatic ? neko_get_static_field_id(env, cls, name, desc) : neko_get_field_id(env, cls, name, desc);
    if (*slot == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time %s field resolution failed: %s.%s:%s", isStatic ? "static" : "instance", owner, name, desc);
        neko_bind_log_failure(env, "java/lang/NoSuchFieldError", message);
        *slot = NULL;
    }
}

static void neko_bind_string_slot(JNIEnv *env, jstring *slot, const char *utf) {
    jstring localString;
    jobject globalRef;
    char message[256];
    if (env == NULL || slot == NULL || *slot != NULL || utf == NULL) return;
    localString = neko_new_string_utf(env, utf);
    if (localString == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time string resolution failed: %s", utf);
        neko_bind_log_failure(env, "java/lang/IllegalStateException", message);
        if (localString != NULL) neko_delete_local_ref(env, localString);
        return;
    }
    globalRef = neko_new_global_ref(env, localString);
    neko_delete_local_ref(env, localString);
    if (globalRef == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        snprintf(message, sizeof(message), "Bind-time string global-ref failed: %s", utf);
        neko_bind_log_failure(env, "java/lang/IllegalStateException", message);
        return;
    }
    *slot = (jstring)globalRef;
}

static void neko_bind_object_array_klass_bits(JNIEnv *env, uintptr_t *slot, jclass elemClass) {
    jobjectArray array;
    char *array_oop;
    if (env == NULL || slot == NULL || *slot != 0 || elemClass == NULL) return;
    if (!g_hotspot.initialized
        || ((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0 && !g_hotspot.use_zgc)
        || g_hotspot.use_compact_object_headers
        || g_hotspot.klass_offset_bytes <= 0) {
        return;
    }
    array = neko_new_object_array(env, 0, elemClass, NULL);
    if (array == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        return;
    }
    array_oop = (char*)neko_handle_oop((jobject)array);
    if (array_oop != NULL) {
        if (g_hotspot.use_compressed_klass_ptrs) {
            *slot = (uintptr_t)(*(uint32_t*)(array_oop + g_hotspot.klass_offset_bytes));
        } else {
            *slot = *(uintptr_t*)(array_oop + g_hotspot.klass_offset_bytes);
        }
    }
    neko_delete_local_ref(env, array);
}

static jclass neko_bound_class(JNIEnv *env, jclass slot, const char *owner) {
    char message[256];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound class: %s", owner == NULL ? "<null>" : owner);
    neko_raise_bound_resolution_error(env, "java/lang/NoClassDefFoundError", message);
    return NULL;
}

static jmethodID neko_bound_method(JNIEnv *env, jmethodID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound %s method: %s.%s%s", isStatic ? "static" : "instance", owner == NULL ? "<null>" : owner, name == NULL ? "<null>" : name, desc == NULL ? "<null>" : desc);
    neko_raise_bound_resolution_error(env, "java/lang/NoSuchMethodError", message);
    return NULL;
}

static jfieldID neko_bound_field(JNIEnv *env, jfieldID slot, const char *owner, const char *name, const char *desc, jboolean isStatic) {
    char message[320];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound %s field: %s.%s:%s", isStatic ? "static" : "instance", owner == NULL ? "<null>" : owner, name == NULL ? "<null>" : name, desc == NULL ? "<null>" : desc);
    neko_raise_bound_resolution_error(env, "java/lang/NoSuchFieldError", message);
    return NULL;
}

static jstring neko_bound_string(JNIEnv *env, jstring slot, const char *utf) {
    char message[256];
    if (slot != NULL) return slot;
    snprintf(message, sizeof(message), "Unresolved bound string: %s", utf == NULL ? "<null>" : utf);
    neko_raise_bound_resolution_error(env, "java/lang/IllegalStateException", message);
    return NULL;
}

static jboolean neko_bind_primitive_field_metadata_enabled(void) {
    return g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0;
}

static void neko_disable_primitive_field_fast_path(JNIEnv *env) {
    (void)env;
    g_hotspot.fast_bits &= ~NEKO_FAST_PRIM_FIELD;
}

static jobject neko_native_unsafe_singleton_for(JNIEnv *env, const char *className, const char *desc) {
    jclass unsafeClass;
    jfieldID theUnsafe;
    if (env == NULL || className == NULL || desc == NULL) return NULL;
    unsafeClass = neko_find_class(env, className);
    if (unsafeClass == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        return NULL;
    }
    theUnsafe = neko_get_static_field_id(env, unsafeClass, "theUnsafe", desc);
    if (theUnsafe == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        neko_delete_local_ref(env, unsafeClass);
        return NULL;
    }
    return neko_get_static_object_field(env, unsafeClass, theUnsafe);
}

static jobject neko_native_unsafe_singleton(JNIEnv *env) {
    jobject unsafe = neko_native_unsafe_singleton_for(env, "sun/misc/Unsafe", "Lsun/misc/Unsafe;");
    if (unsafe != NULL && !neko_exception_check(env)) return unsafe;
    if (neko_exception_check(env)) neko_exception_clear(env);
    return neko_native_unsafe_singleton_for(env, "jdk/internal/misc/Unsafe", "Ljdk/internal/misc/Unsafe;");
}

static jobject neko_native_declared_field(JNIEnv *env, jclass cls, const char *name) {
    static jclass g_class_cls = NULL;
    static jmethodID g_get_declared_field = NULL;
    jclass classCls;
    jmethodID getDeclaredField;
    jstring fieldName;
    jobject field;
    jvalue args[1];
    if (env == NULL || cls == NULL || name == NULL) return NULL;
    classCls = NEKO_ENSURE_CLASS(g_class_cls, env, "java/lang/Class");
    getDeclaredField = NEKO_ENSURE_METHOD_ID(g_get_declared_field, env, classCls, "getDeclaredField", "(Ljava/lang/String;)Ljava/lang/reflect/Field;");
    fieldName = neko_new_string_utf(env, name);
    if (fieldName == NULL || neko_exception_check(env)) return NULL;
    args[0].l = fieldName;
    field = neko_call_object_method_a(env, cls, getDeclaredField, args);
    neko_delete_local_ref(env, fieldName);
    if (field == NULL || neko_exception_check(env)) return NULL;
    return field;
}

static jlong neko_native_instance_field_offset(JNIEnv *env, jclass cls, const char *name) {
    jobject unsafe;
    jclass unsafeClass;
    jmethodID objectFieldOffset;
    jobject field;
    jvalue args[1];
    jlong out;
    unsafe = neko_native_unsafe_singleton(env);
    if (unsafe == NULL || neko_exception_check(env)) {
        fprintf(stderr, "[neko-direct-bind] Unsafe unavailable for instance field %s\\n", name == NULL ? "<null>" : name);
        return -1;
    }
    unsafeClass = neko_get_object_class(env, unsafe);
    objectFieldOffset = neko_get_method_id(env, unsafeClass, "objectFieldOffset", "(Ljava/lang/reflect/Field;)J");
    field = neko_native_declared_field(env, cls, name);
    if (objectFieldOffset == NULL || field == NULL || neko_exception_check(env)) {
        fprintf(stderr, "[neko-direct-bind] objectFieldOffset/Field unavailable for instance field %s\\n", name == NULL ? "<null>" : name);
        return -1;
    }
    args[0].l = field;
    out = neko_call_long_method_a(env, unsafe, objectFieldOffset, args);
    if (neko_exception_check(env)) {
        return -1;
    }
    return out;
}

static jlong neko_native_static_field_offset(JNIEnv *env, jclass cls, const char *name) {
    jobject unsafe;
    jclass unsafeClass;
    jmethodID staticFieldOffset;
    jobject field;
    jvalue args[1];
    jlong out;
    unsafe = neko_native_unsafe_singleton(env);
    if (unsafe == NULL || neko_exception_check(env)) return -1;
    unsafeClass = neko_get_object_class(env, unsafe);
    staticFieldOffset = neko_get_method_id(env, unsafeClass, "staticFieldOffset", "(Ljava/lang/reflect/Field;)J");
    field = neko_native_declared_field(env, cls, name);
    if (staticFieldOffset == NULL || field == NULL || neko_exception_check(env)) return -1;
    args[0].l = field;
    out = neko_call_long_method_a(env, unsafe, staticFieldOffset, args);
    if (neko_exception_check(env)) return -1;
    return out;
}

static jobject neko_native_static_field_base(JNIEnv *env, jclass cls, const char *name) {
    jobject unsafe;
    jclass unsafeClass;
    jmethodID staticFieldBase;
    jobject field;
    jvalue args[1];
    unsafe = neko_native_unsafe_singleton(env);
    if (unsafe == NULL || neko_exception_check(env)) return NULL;
    unsafeClass = neko_get_object_class(env, unsafe);
    staticFieldBase = neko_get_method_id(env, unsafeClass, "staticFieldBase", "(Ljava/lang/reflect/Field;)Ljava/lang/Object;");
    field = neko_native_declared_field(env, cls, name);
    if (staticFieldBase == NULL || field == NULL || neko_exception_check(env)) return NULL;
    args[0].l = field;
    return neko_call_object_method_a(env, unsafe, staticFieldBase, args);
}

static void neko_bind_instance_field_offset(JNIEnv *env, jlong *slot, jclass cls, jfieldID fid, const char *owner, const char *name, const char *desc, jboolean requireDirectOffset) {
    void *klass;
    neko_field_resolution_t native_field;
    (void)fid;
    (void)requireDirectOffset;
    if (!neko_bind_primitive_field_metadata_enabled() || env == NULL || slot == NULL || *slot > 0 || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    klass = neko_class_mirror_to_klass(cls);
    native_field = neko_resolve_field(klass, name, desc, JNI_FALSE);
    if (!native_field.found || native_field.is_static || native_field.offset == 0) {
        fprintf(stderr, "[neko-bind] native instance field metadata invalid: %s.%s:%s offset=%u static=%d\\n",
            owner, name, desc, native_field.offset, (int)native_field.is_static);
        abort();
    }
    *slot = (jlong)native_field.offset;
}

static void neko_bind_static_field_metadata(JNIEnv *env, jobject *baseSlot, jlong *offsetSlot, jclass cls, const char *owner, const char *name, const char *desc) {
    jobject baseLocal;
    jobject baseGlobal;
    void *klass;
    neko_field_resolution_t native_field;
    if (!neko_bind_primitive_field_metadata_enabled() || env == NULL || baseSlot == NULL || offsetSlot == NULL
        || (*baseSlot != NULL && *offsetSlot > 0) || cls == NULL || owner == NULL || name == NULL || desc == NULL) return;
    klass = neko_class_mirror_to_klass(cls);
    native_field = neko_resolve_field(klass, name, desc, JNI_TRUE);
    if (!native_field.found || !native_field.is_static || native_field.offset == 0) {
        fprintf(stderr, "[neko-bind] native static field metadata invalid: %s.%s:%s offset=%u static=%d\\n",
            owner, name, desc, native_field.offset, (int)native_field.is_static);
        abort();
    }
    *offsetSlot = (jlong)native_field.offset;
    baseLocal = neko_native_static_field_base(env, cls, name);
    if (neko_exception_check(env) || baseLocal == NULL) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] static field base resolution failed after native offset resolution: %s.%s:%s\\n",
            owner, name, desc);
        abort();
    }
    baseGlobal = neko_new_global_ref(env, baseLocal);
    neko_delete_local_ref(env, baseLocal);
    if (baseGlobal == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        fprintf(stderr, "[neko-bind] static field base global-ref failed after native offset resolution: %s.%s:%s\\n",
            owner, name, desc);
        abort();
    }
    *baseSlot = baseGlobal;
}

""";
    }

    private String renderBindOwnerFunctions() {
        StringBuilder sb = new StringBuilder();
        if (ownerBindIndex.isEmpty()) {
            return "";
        }
        sb.append("// === Bind-time owner resolution ===\n");
        for (Map.Entry<String, Integer> entry : ownerBindIndex.entrySet()) {
            String owner = entry.getKey();
            int ownerId = entry.getValue();
            OwnerResolution resolution = ownerResolutions.get(owner);
            sb.append("static void neko_bind_owner_").append(ownerId).append("(JNIEnv *env, jclass self_class) {\n");
            sb.append("    if (env == NULL || g_owner_bound_").append(ownerId).append(") return;\n");
            sb.append("    g_owner_bound_").append(ownerId).append(" = JNI_TRUE;\n");
            sb.append("    neko_bind_owner_class_slot(env, &").append(classSlotName(owner)).append(", self_class, \"")
                .append(c(owner)).append("\");\n");
            sb.append("    neko_bind_object_array_klass_bits(env, &").append(objectArrayKlassBitsSlotName(owner))
                .append(", ").append(classSlotName(owner)).append(");\n");
            for (String classOwner : resolution.classes) {
                if (owner.equals(classOwner)) {
                    continue;
                }
                sb.append("    neko_bind_class_slot(env, &").append(classSlotName(classOwner)).append(", \"")
                    .append(c(classOwner)).append("\");\n");
                sb.append("    neko_bind_object_array_klass_bits(env, &").append(objectArrayKlassBitsSlotName(classOwner))
                    .append(", ").append(classSlotName(classOwner)).append(");\n");
            }
            for (MethodRef methodRef : resolution.methods) {
                String midSlot = methodSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mptrSlot = methodPtrSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mcentrySlot = methodCEntrySlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mientrySlot = methodIEntrySlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                String mholderSlot = methodHolderSlotName(methodRef.owner(), methodRef.name(), methodRef.desc(), methodRef.isStatic());
                sb.append("    neko_bind_method_slot(env, &").append(midSlot)
                    .append(", ").append(classSlotName(methodRef.owner())).append(", \"")
                    .append(c(methodRef.owner())).append("\", \"")
                    .append(c(methodRef.name())).append("\", \"")
                    .append(c(methodRef.desc())).append("\", ")
                    .append(methodRef.isStatic() ? "JNI_TRUE" : "JNI_FALSE").append(");\n");
                /* Snapshot Method* and HotSpot entry pointers for the direct
                 * native→Java dispatcher. Reads happen straight off the Method*
                 * using VMStructs-published offsets — no JNI here. */
                sb.append("    neko_bind_method_entry_slots(").append(midSlot)
                    .append(", ").append(classSlotName(methodRef.owner()))
                    .append(", \"").append(c(methodRef.owner())).append("\"")
                    .append(", \"").append(c(methodRef.name())).append("\"")
                    .append(", \"").append(c(methodRef.desc())).append("\"")
                    .append(", &").append(mptrSlot)
                    .append(", &").append(mcentrySlot)
                    .append(", &").append(mientrySlot)
                    .append(", &").append(mholderSlot).append(");\n");
            }
            for (FieldRef fieldRef : resolution.fields) {
                sb.append("    neko_bind_field_slot(env, &").append(fieldSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), fieldRef.isStatic()))
                    .append(", ").append(classSlotName(fieldRef.owner())).append(", \"")
                    .append(c(fieldRef.owner())).append("\", \"")
                    .append(c(fieldRef.name())).append("\", \"")
                    .append(c(fieldRef.desc())).append("\", ")
                    .append(fieldRef.isStatic() ? "JNI_TRUE" : "JNI_FALSE").append(");\n");
                /* Resolve byte-offset metadata for both primitive AND object
                 * fields through the native field metadata scanner. T2.6 will
                 * remove the remaining Unsafe static-base handle path. */
                if (fieldRef.isStatic()) {
                    sb.append("    neko_bind_static_field_metadata(env, &")
                        .append(staticFieldBaseSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), true))
                        .append(", &")
                        .append(staticFieldOffsetSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), true))
                        .append(", ")
                        .append(classSlotName(fieldRef.owner()))
                        .append(", \"")
                        .append(c(fieldRef.owner()))
                        .append("\", \"")
                        .append(c(fieldRef.name()))
                        .append("\", \"")
                        .append(c(fieldRef.desc()))
                        .append("\");\n");
                } else {
                    sb.append("    neko_bind_instance_field_offset(env, &")
                        .append(fieldOffsetSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), false))
                        .append(", ")
                        .append(classSlotName(fieldRef.owner()))
                        .append(", ")
                        .append(fieldSlotName(fieldRef.owner(), fieldRef.name(), fieldRef.desc(), false))
                        .append(", \"")
                        .append(c(fieldRef.owner()))
                        .append("\", \"")
                        .append(c(fieldRef.name()))
                        .append("\", \"")
                        .append(c(fieldRef.desc()))
                        .append("\", ")
                        .append("JNI_TRUE")
                        .append(");\n");
                }
            }
            for (StringRef stringRef : resolution.strings) {
                sb.append("    neko_bind_string_slot(env, &").append(stringRef.cacheVar()).append(", \"")
                    .append(c(stringRef.value())).append("\");\n");
            }
            sb.append("}\n\n");
        }
        return sb.toString();
    }

    private boolean requiresLocalCapacity(CFunction fn) {
        for (CStatement statement : fn.body()) {
            if (statement instanceof CStatement.RawC raw) {
                String code = raw.code();
                if (code.contains("neko_new_") || code.contains("neko_call_") || code.contains("neko_get_object_array_element")
                    || code.contains("neko_set_object_array_element") || code.contains("neko_get_object_class")
                    || code.contains("NEKO_ENSURE_STRING") || code.contains("neko_string_concat")
                    || code.contains("neko_class_for_descriptor") || code.contains("neko_resolve_indy")
                    || code.contains("neko_resolve_constant_dynamic")) {
                    return true;
                }
            }
        }
        return false;
    }

    private String renderIcacheDirectStubs() {
        if (icacheDirectStubs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Inline-cache direct-call stubs ===\n");
        for (IcacheDirectStubRef stub : icacheDirectStubs.values()) {
            sb.append(renderIcacheDirectStub(stub));
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderIcacheMetas() {
        if (icacheMetas.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("// === Inline-cache metadata ===\n");
        for (IcacheMetaRef meta : icacheMetas.values()) {
            sb.append("static const neko_icache_meta ").append(meta.symbol()).append(" = {\"")
                .append(c(meta.name())).append("\", \"").append(c(meta.desc())).append("\", ")
                .append(meta.translatedClassSlot() == null ? "NULL" : "&" + meta.translatedClassSlot()).append(", ")
                .append(meta.translatedStubSymbol() == null ? "NULL" : meta.translatedStubSymbol()).append(", ")
                .append(meta.isInterface() ? "JNI_TRUE" : "JNI_FALSE").append(", ")
                .append(meta.directDispatcherSymbol() == null ? "NULL" : meta.directDispatcherSymbol())
                .append("};\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    private String renderIcacheDirectStub(IcacheDirectStubRef stub) {
        StringBuilder sb = new StringBuilder();
        sb.append("static jvalue ").append(stub.symbol()).append("(void *thread, JNIEnv *env, jobject receiver, const jvalue *args) {\n");
        sb.append("    jvalue result = {0};\n");
        if (stub.returnType().getSort() != Type.VOID) {
            sb.append("    result").append(jvalueAccessor(stub.returnType())).append(" = ");
        } else {
            sb.append("    ");
        }
        sb.append(stub.binding().rawFunctionName()).append("(thread, env, receiver");
        for (int i = 0; i < stub.args().length; i++) {
            sb.append(", ");
            if (stub.args()[i].getSort() == Type.ARRAY) {
                sb.append("(jarray)");
            } else if (stub.args()[i].getSort() == Type.OBJECT) {
                sb.append("(jobject)");
            }
            sb.append("args[").append(i).append("]").append(jvalueAccessor(stub.args()[i]));
        }
        sb.append(");\n");
        sb.append("    return result;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private boolean isPrimitiveFieldDescriptor(String desc) {
        return desc != null && desc.length() == 1 && "ZBCSIJFD".indexOf(desc.charAt(0)) >= 0;
    }

    private String renderRuntimeSupport() {
        return """
typedef union {
    jint i;
    jlong j;
    jfloat f;
    jdouble d;
    jobject o;
} neko_slot;

#define PUSH_I(v) do { jint __tmp = (jint)(v); stack[sp++].i = __tmp; } while (0)
#define PUSH_L(v) do { jlong __tmp = (jlong)(v); stack[sp].j = __tmp; stack[sp + 1].j = __tmp; sp += 2; } while (0)
#define PUSH_F(v) do { jfloat __tmp = (jfloat)(v); stack[sp++].f = __tmp; } while (0)
#define PUSH_D(v) do { jdouble __tmp = (jdouble)(v); stack[sp].d = __tmp; stack[sp + 1].d = __tmp; sp += 2; } while (0)
#define PUSH_O(v) do { jobject __tmp = (jobject)(v); stack[sp++].o = __tmp; } while (0)
#define POP_I() (stack[--sp].i)
#define POP_L() (sp -= 2, stack[sp].j)
#define POP_F() (stack[--sp].f)
#define POP_D() (sp -= 2, stack[sp].d)
#define POP_O() (stack[--sp].o)

#define NEKO_JNI_FN_PTR(env, idx, ret, ...) ((ret (*)(JNIEnv*, ##__VA_ARGS__))(*((void***)(env)))[idx])

static inline jclass neko_find_class(JNIEnv *env, const char *name) { return NEKO_JNI_FN_PTR(env, 6, jclass, const char*)(env, name); }
static inline jclass neko_get_object_class(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 31, jclass, jobject)(env, obj); }
static inline jboolean neko_is_instance_of(JNIEnv *env, jobject obj, jclass clazz) { return NEKO_JNI_FN_PTR(env, 32, jboolean, jobject, jclass)(env, obj, clazz); }
static inline jmethodID neko_get_method_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 33, jmethodID, jclass, const char*, const char*)(env, c, n, s); }
static inline jmethodID neko_get_static_method_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 113, jmethodID, jclass, const char*, const char*)(env, c, n, s); }
static inline jfieldID neko_get_field_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 94, jfieldID, jclass, const char*, const char*)(env, c, n, s); }
static inline jfieldID neko_get_static_field_id(JNIEnv *env, jclass c, const char *n, const char *s) { return NEKO_JNI_FN_PTR(env, 144, jfieldID, jclass, const char*, const char*)(env, c, n, s); }
static inline jint neko_throw(JNIEnv *env, jthrowable exc) { return NEKO_JNI_FN_PTR(env, 13, jint, jthrowable)(env, exc); }
static inline jint neko_throw_new(JNIEnv *env, jclass cls, const char *msg) { return NEKO_JNI_FN_PTR(env, 14, jint, jclass, const char*)(env, cls, msg); }
static inline jthrowable neko_exception_occurred(JNIEnv *env) { return NEKO_JNI_FN_PTR(env, 15, jthrowable)(env); }
static inline void neko_exception_clear(JNIEnv *env) { NEKO_JNI_FN_PTR(env, 17, void)(env); }
static inline jint neko_ensure_local_capacity(JNIEnv *env, jint capacity) { return NEKO_JNI_FN_PTR(env, 26, jint, jint)(env, capacity); }
static inline void neko_delete_global_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 22, void, jobject)(env, obj); }
static inline jobject neko_new_global_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 21, jobject, jobject)(env, obj); }
static inline void neko_delete_local_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 23, void, jobject)(env, obj); }
static inline jboolean neko_is_same_object(JNIEnv *env, jobject a, jobject b) { return NEKO_JNI_FN_PTR(env, 24, jboolean, jobject, jobject)(env, a, b); }
static inline jobject neko_new_weak_global_ref(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 226, jobject, jobject)(env, obj); }
static inline void neko_delete_weak_global_ref(JNIEnv *env, jobject obj) { NEKO_JNI_FN_PTR(env, 227, void, jobject)(env, obj); }
static inline jobject neko_alloc_object(JNIEnv *env, jclass cls) { return NEKO_JNI_FN_PTR(env, 27, jobject, jclass)(env, cls); }
static inline jobject neko_new_object_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 30, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_call_object_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 36, jobject, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jboolean neko_call_boolean_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 39, jboolean, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jbyte neko_call_byte_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 42, jbyte, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jchar neko_call_char_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 45, jchar, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jshort neko_call_short_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 48, jshort, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jint neko_call_int_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 51, jint, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jlong neko_call_long_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 54, jlong, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jfloat neko_call_float_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 57, jfloat, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jdouble neko_call_double_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 60, jdouble, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline void neko_call_void_method_a(JNIEnv *env, jobject obj, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 63, void, jobject, jmethodID, const jvalue*)(env, obj, mid, args); }
static inline jobject neko_call_nonvirtual_object_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 66, jobject, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jboolean neko_call_nonvirtual_boolean_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 69, jboolean, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jbyte neko_call_nonvirtual_byte_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 72, jbyte, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jchar neko_call_nonvirtual_char_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 75, jchar, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jshort neko_call_nonvirtual_short_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 78, jshort, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jint neko_call_nonvirtual_int_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 81, jint, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jlong neko_call_nonvirtual_long_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 84, jlong, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jfloat neko_call_nonvirtual_float_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 87, jfloat, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jdouble neko_call_nonvirtual_double_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 90, jdouble, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline void neko_call_nonvirtual_void_method_a(JNIEnv *env, jobject obj, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 93, void, jobject, jclass, jmethodID, const jvalue*)(env, obj, cls, mid, args); }
static inline jobject neko_get_object_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 95, jobject, jobject, jfieldID)(env, obj, fid); }
static inline jboolean neko_get_boolean_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 96, jboolean, jobject, jfieldID)(env, obj, fid); }
static inline jbyte neko_get_byte_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 97, jbyte, jobject, jfieldID)(env, obj, fid); }
static inline jchar neko_get_char_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 98, jchar, jobject, jfieldID)(env, obj, fid); }
static inline jshort neko_get_short_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 99, jshort, jobject, jfieldID)(env, obj, fid); }
static inline jint neko_get_int_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 100, jint, jobject, jfieldID)(env, obj, fid); }
static inline jlong neko_get_long_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 101, jlong, jobject, jfieldID)(env, obj, fid); }
static inline jfloat neko_get_float_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 102, jfloat, jobject, jfieldID)(env, obj, fid); }
static inline jdouble neko_get_double_field(JNIEnv *env, jobject obj, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 103, jdouble, jobject, jfieldID)(env, obj, fid); }
static inline void neko_set_object_field(JNIEnv *env, jobject obj, jfieldID fid, jobject val) { NEKO_JNI_FN_PTR(env, 104, void, jobject, jfieldID, jobject)(env, obj, fid, val); }
static inline void neko_set_boolean_field(JNIEnv *env, jobject obj, jfieldID fid, jboolean val) { NEKO_JNI_FN_PTR(env, 105, void, jobject, jfieldID, jboolean)(env, obj, fid, val); }
static inline void neko_set_byte_field(JNIEnv *env, jobject obj, jfieldID fid, jbyte val) { NEKO_JNI_FN_PTR(env, 106, void, jobject, jfieldID, jbyte)(env, obj, fid, val); }
static inline void neko_set_char_field(JNIEnv *env, jobject obj, jfieldID fid, jchar val) { NEKO_JNI_FN_PTR(env, 107, void, jobject, jfieldID, jchar)(env, obj, fid, val); }
static inline void neko_set_short_field(JNIEnv *env, jobject obj, jfieldID fid, jshort val) { NEKO_JNI_FN_PTR(env, 108, void, jobject, jfieldID, jshort)(env, obj, fid, val); }
static inline void neko_set_int_field(JNIEnv *env, jobject obj, jfieldID fid, jint val) { NEKO_JNI_FN_PTR(env, 109, void, jobject, jfieldID, jint)(env, obj, fid, val); }
static inline void neko_set_long_field(JNIEnv *env, jobject obj, jfieldID fid, jlong val) { NEKO_JNI_FN_PTR(env, 110, void, jobject, jfieldID, jlong)(env, obj, fid, val); }
static inline void neko_set_float_field(JNIEnv *env, jobject obj, jfieldID fid, jfloat val) { NEKO_JNI_FN_PTR(env, 111, void, jobject, jfieldID, jfloat)(env, obj, fid, val); }
static inline void neko_set_double_field(JNIEnv *env, jobject obj, jfieldID fid, jdouble val) { NEKO_JNI_FN_PTR(env, 112, void, jobject, jfieldID, jdouble)(env, obj, fid, val); }
static inline jobject neko_call_static_object_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 116, jobject, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jboolean neko_call_static_boolean_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 119, jboolean, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jbyte neko_call_static_byte_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 122, jbyte, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jchar neko_call_static_char_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 125, jchar, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jshort neko_call_static_short_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 128, jshort, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jint neko_call_static_int_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 131, jint, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jlong neko_call_static_long_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 134, jlong, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jfloat neko_call_static_float_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 137, jfloat, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jdouble neko_call_static_double_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { return NEKO_JNI_FN_PTR(env, 140, jdouble, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline void neko_call_static_void_method_a(JNIEnv *env, jclass cls, jmethodID mid, const jvalue *args) { NEKO_JNI_FN_PTR(env, 143, void, jclass, jmethodID, const jvalue*)(env, cls, mid, args); }
static inline jobject neko_get_static_object_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 145, jobject, jclass, jfieldID)(env, cls, fid); }
static inline jboolean neko_get_static_boolean_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 146, jboolean, jclass, jfieldID)(env, cls, fid); }
static inline jbyte neko_get_static_byte_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 147, jbyte, jclass, jfieldID)(env, cls, fid); }
static inline jchar neko_get_static_char_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 148, jchar, jclass, jfieldID)(env, cls, fid); }
static inline jshort neko_get_static_short_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 149, jshort, jclass, jfieldID)(env, cls, fid); }
static inline jint neko_get_static_int_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 150, jint, jclass, jfieldID)(env, cls, fid); }
static inline jlong neko_get_static_long_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 151, jlong, jclass, jfieldID)(env, cls, fid); }
static inline jfloat neko_get_static_float_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 152, jfloat, jclass, jfieldID)(env, cls, fid); }
static inline jdouble neko_get_static_double_field(JNIEnv *env, jclass cls, jfieldID fid) { return NEKO_JNI_FN_PTR(env, 153, jdouble, jclass, jfieldID)(env, cls, fid); }
static inline void neko_set_static_object_field(JNIEnv *env, jclass cls, jfieldID fid, jobject val) { NEKO_JNI_FN_PTR(env, 154, void, jclass, jfieldID, jobject)(env, cls, fid, val); }
static inline void neko_set_static_boolean_field(JNIEnv *env, jclass cls, jfieldID fid, jboolean val) { NEKO_JNI_FN_PTR(env, 155, void, jclass, jfieldID, jboolean)(env, cls, fid, val); }
static inline void neko_set_static_byte_field(JNIEnv *env, jclass cls, jfieldID fid, jbyte val) { NEKO_JNI_FN_PTR(env, 156, void, jclass, jfieldID, jbyte)(env, cls, fid, val); }
static inline void neko_set_static_char_field(JNIEnv *env, jclass cls, jfieldID fid, jchar val) { NEKO_JNI_FN_PTR(env, 157, void, jclass, jfieldID, jchar)(env, cls, fid, val); }
static inline void neko_set_static_short_field(JNIEnv *env, jclass cls, jfieldID fid, jshort val) { NEKO_JNI_FN_PTR(env, 158, void, jclass, jfieldID, jshort)(env, cls, fid, val); }
static inline void neko_set_static_int_field(JNIEnv *env, jclass cls, jfieldID fid, jint val) { NEKO_JNI_FN_PTR(env, 159, void, jclass, jfieldID, jint)(env, cls, fid, val); }
static inline void neko_set_static_long_field(JNIEnv *env, jclass cls, jfieldID fid, jlong val) { NEKO_JNI_FN_PTR(env, 160, void, jclass, jfieldID, jlong)(env, cls, fid, val); }
static inline void neko_set_static_float_field(JNIEnv *env, jclass cls, jfieldID fid, jfloat val) { NEKO_JNI_FN_PTR(env, 161, void, jclass, jfieldID, jfloat)(env, cls, fid, val); }
static inline void neko_set_static_double_field(JNIEnv *env, jclass cls, jfieldID fid, jdouble val) { NEKO_JNI_FN_PTR(env, 162, void, jclass, jfieldID, jdouble)(env, cls, fid, val); }
static inline jsize neko_get_string_length(JNIEnv *env, jstring str) { return NEKO_JNI_FN_PTR(env, 164, jsize, jstring)(env, str); }
static inline jstring neko_new_string_utf(JNIEnv *env, const char *utf) { return NEKO_JNI_FN_PTR(env, 167, jstring, const char*)(env, utf); }
static inline const char* neko_get_string_utf_chars(JNIEnv *env, jstring str) { return NEKO_JNI_FN_PTR(env, 169, const char*, jstring, jboolean*)(env, str, NULL); }
static inline void neko_release_string_utf_chars(JNIEnv *env, jstring str, const char *chars) { NEKO_JNI_FN_PTR(env, 170, void, jstring, const char*)(env, str, chars); }
static inline jsize neko_get_array_length(JNIEnv *env, jarray arr) { return NEKO_JNI_FN_PTR(env, 171, jsize, jarray)(env, arr); }
static inline jobjectArray neko_new_object_array(JNIEnv *env, jsize len, jclass cls, jobject init) { return NEKO_JNI_FN_PTR(env, 172, jobjectArray, jsize, jclass, jobject)(env, len, cls, init); }
static inline jobject neko_get_object_array_element(JNIEnv *env, jobjectArray arr, jsize index) { return NEKO_JNI_FN_PTR(env, 173, jobject, jobjectArray, jsize)(env, arr, index); }
static inline void neko_set_object_array_element(JNIEnv *env, jobjectArray arr, jsize index, jobject val) { NEKO_JNI_FN_PTR(env, 174, void, jobjectArray, jsize, jobject)(env, arr, index, val); }
static inline jbooleanArray neko_new_boolean_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 175, jbooleanArray, jsize)(env, len); }
static inline jbyteArray neko_new_byte_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 176, jbyteArray, jsize)(env, len); }
static inline jcharArray neko_new_char_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 177, jcharArray, jsize)(env, len); }
static inline jshortArray neko_new_short_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 178, jshortArray, jsize)(env, len); }
static inline jintArray neko_new_int_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 179, jintArray, jsize)(env, len); }
static inline jlongArray neko_new_long_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 180, jlongArray, jsize)(env, len); }
static inline jfloatArray neko_new_float_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 181, jfloatArray, jsize)(env, len); }
static inline jdoubleArray neko_new_double_array(JNIEnv *env, jsize len) { return NEKO_JNI_FN_PTR(env, 182, jdoubleArray, jsize)(env, len); }
static inline void neko_get_boolean_array_region(JNIEnv *env, jbooleanArray arr, jsize start, jsize len, jboolean *buf) { NEKO_JNI_FN_PTR(env, 199, void, jbooleanArray, jsize, jsize, jboolean*)(env, arr, start, len, buf); }
static inline void neko_get_byte_array_region(JNIEnv *env, jbyteArray arr, jsize start, jsize len, jbyte *buf) { NEKO_JNI_FN_PTR(env, 200, void, jbyteArray, jsize, jsize, jbyte*)(env, arr, start, len, buf); }
static inline void neko_get_char_array_region(JNIEnv *env, jcharArray arr, jsize start, jsize len, jchar *buf) { NEKO_JNI_FN_PTR(env, 201, void, jcharArray, jsize, jsize, jchar*)(env, arr, start, len, buf); }
static inline void neko_get_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, jshort *buf) { NEKO_JNI_FN_PTR(env, 202, void, jshortArray, jsize, jsize, jshort*)(env, arr, start, len, buf); }
static inline void neko_get_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, jint *buf) { NEKO_JNI_FN_PTR(env, 203, void, jintArray, jsize, jsize, jint*)(env, arr, start, len, buf); }
static inline void neko_get_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, jlong *buf) { NEKO_JNI_FN_PTR(env, 204, void, jlongArray, jsize, jsize, jlong*)(env, arr, start, len, buf); }
static inline void neko_get_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, jfloat *buf) { NEKO_JNI_FN_PTR(env, 205, void, jfloatArray, jsize, jsize, jfloat*)(env, arr, start, len, buf); }
static inline void neko_get_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, jdouble *buf) { NEKO_JNI_FN_PTR(env, 206, void, jdoubleArray, jsize, jsize, jdouble*)(env, arr, start, len, buf); }
static inline void neko_set_boolean_array_region(JNIEnv *env, jbooleanArray arr, jsize start, jsize len, const jboolean *buf) { NEKO_JNI_FN_PTR(env, 207, void, jbooleanArray, jsize, jsize, const jboolean*)(env, arr, start, len, buf); }
static inline void neko_set_byte_array_region(JNIEnv *env, jbyteArray arr, jsize start, jsize len, const jbyte *buf) { NEKO_JNI_FN_PTR(env, 208, void, jbyteArray, jsize, jsize, const jbyte*)(env, arr, start, len, buf); }
static inline void neko_set_char_array_region(JNIEnv *env, jcharArray arr, jsize start, jsize len, const jchar *buf) { NEKO_JNI_FN_PTR(env, 209, void, jcharArray, jsize, jsize, const jchar*)(env, arr, start, len, buf); }
static inline void neko_set_short_array_region(JNIEnv *env, jshortArray arr, jsize start, jsize len, const jshort *buf) { NEKO_JNI_FN_PTR(env, 210, void, jshortArray, jsize, jsize, const jshort*)(env, arr, start, len, buf); }
static inline void neko_set_int_array_region(JNIEnv *env, jintArray arr, jsize start, jsize len, const jint *buf) { NEKO_JNI_FN_PTR(env, 211, void, jintArray, jsize, jsize, const jint*)(env, arr, start, len, buf); }
static inline void neko_set_long_array_region(JNIEnv *env, jlongArray arr, jsize start, jsize len, const jlong *buf) { NEKO_JNI_FN_PTR(env, 212, void, jlongArray, jsize, jsize, const jlong*)(env, arr, start, len, buf); }
static inline void neko_set_float_array_region(JNIEnv *env, jfloatArray arr, jsize start, jsize len, const jfloat *buf) { NEKO_JNI_FN_PTR(env, 213, void, jfloatArray, jsize, jsize, const jfloat*)(env, arr, start, len, buf); }
static inline void neko_set_double_array_region(JNIEnv *env, jdoubleArray arr, jsize start, jsize len, const jdouble *buf) { NEKO_JNI_FN_PTR(env, 214, void, jdoubleArray, jsize, jsize, const jdouble*)(env, arr, start, len, buf); }
static inline jint neko_monitor_enter(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 217, jint, jobject)(env, obj); }
static inline jint neko_monitor_exit(JNIEnv *env, jobject obj) { return NEKO_JNI_FN_PTR(env, 218, jint, jobject)(env, obj); }
/* Some HotSpot helper blocks use `neko_handle_oop` before the fast-access
 * section is emitted in the final C file. Declare it here so C99 does not
 * infer an implicit int-returning prototype on first use. */
static inline void* neko_handle_oop(jobject handle);
/* Forward decl + helper: read JavaThread::_pending_exception directly. The
 * field offset is recovered by VMStructs (g_neko_off_thread_pending_exception)
 * and the JNIEnv->JavaThread distance is recovered by the patcher init
 * (g_neko_off_thread_jni_environment_for_check). When both are known,
 * neko_exception_check becomes a load + compare instead of a JNI call. The
 * translator emits one neko_exception_check after every JNI call inside an
 * impl_fn, so saving the JNI dispatch (~50 cycles) per check buys a lot in
 * tight loops (matrix multiply: ~35M checks per microbench iteration). */
extern ptrdiff_t g_neko_off_thread_pending_exception;
__attribute__((visibility("hidden"))) extern ptrdiff_t g_neko_off_thread_jni_environment_for_check;
static inline jboolean neko_exception_check(JNIEnv *env) {
    if (env != NULL
        && g_neko_off_thread_pending_exception > 0
        && g_neko_off_thread_jni_environment_for_check > 0) {
        void *thread = (void*)((char*)env - g_neko_off_thread_jni_environment_for_check);
        return *(void**)((char*)thread + g_neko_off_thread_pending_exception) != NULL
            ? JNI_TRUE : JNI_FALSE;
    }
    return NEKO_JNI_FN_PTR(env, 228, jboolean)(env);
}

typedef struct {
    const char *owner;
    const char *method;
    const char *file;
} neko_shadow_frame;

#define NEKO_SHADOW_STACK_MAX 256
static __thread neko_shadow_frame g_neko_shadow_stack[NEKO_SHADOW_STACK_MAX];
static __thread uint32_t g_neko_shadow_depth = 0u;

static void neko_shadow_push(const char *owner, const char *method, const char *file) {
    if (owner == NULL || method == NULL || file == NULL) return;
    if (g_neko_shadow_depth < NEKO_SHADOW_STACK_MAX) {
        g_neko_shadow_stack[g_neko_shadow_depth].owner = owner;
        g_neko_shadow_stack[g_neko_shadow_depth].method = method;
        g_neko_shadow_stack[g_neko_shadow_depth].file = file;
        g_neko_shadow_depth++;
    }
}

static void neko_shadow_pop(void) {
    if (g_neko_shadow_depth > 0u) g_neko_shadow_depth--;
}

static jstring neko_shadow_dotted_string(JNIEnv *env, const char *internal_name) {
    char buf[512];
    size_t i;
    if (internal_name == NULL) return NULL;
    for (i = 0u; i + 1u < sizeof(buf) && internal_name[i] != '\\0'; i++) {
        buf[i] = internal_name[i] == '/' ? '.' : internal_name[i];
    }
    buf[i] = '\\0';
    return neko_new_string_utf(env, buf);
}

static jobjectArray neko_shadow_stack_trace(JNIEnv *env) {
    jclass ste_cls = neko_find_class(env, "java/lang/StackTraceElement");
    jmethodID ste_ctor;
    jobjectArray trace;
    uint32_t depth = g_neko_shadow_depth;
    uint32_t count;
    uint32_t i;
    if (ste_cls == NULL || neko_exception_check(env)) return NULL;
    ste_ctor = neko_get_method_id(env, ste_cls, "<init>", "(Ljava/lang/String;Ljava/lang/String;Ljava/lang/String;I)V");
    if (ste_ctor == NULL || neko_exception_check(env)) return NULL;
    count = depth == 0u ? 0u : depth;
    trace = neko_new_object_array(env, (jsize)count, ste_cls, NULL);
    if (trace == NULL || neko_exception_check(env)) return trace;
    for (i = 0u; i < count; i++) {
        neko_shadow_frame *frame = &g_neko_shadow_stack[depth - 1u - i];
        jvalue args[4];
        jobject element;
        args[0].l = neko_shadow_dotted_string(env, frame->owner);
        args[1].l = neko_new_string_utf(env, frame->method);
        args[2].l = neko_new_string_utf(env, frame->file);
        args[3].i = -1;
        if (neko_exception_check(env)) return trace;
        element = neko_new_object_a(env, ste_cls, ste_ctor, args);
        if (neko_exception_check(env) || element == NULL) return trace;
        neko_set_object_array_element(env, trace, (jsize)i, element);
        if (neko_exception_check(env)) return trace;
    }
    return trace;
}

static char* neko_dotted_class_name(const char *internalName) {
    size_t len = strlen(internalName);
    char *out = (char*)malloc(len + 1u);
    if (out == NULL) return NULL;
    for (size_t i = 0; i < len; i++) out[i] = internalName[i] == '/' ? '.' : internalName[i];
    out[len] = '\\0';
    return out;
}

static jclass neko_load_class_noinit(JNIEnv *env, const char *internalName) {
    char *dotted = neko_dotted_class_name(internalName);
    if (dotted == NULL) return NULL;
    jclass clClass = neko_find_class(env, "java/lang/ClassLoader");
    jmethodID getSystem = neko_get_static_method_id(env, clClass, "getSystemClassLoader", "()Ljava/lang/ClassLoader;");
    jobject loader = neko_call_static_object_method_a(env, clClass, getSystem, NULL);
    jclass classClass = neko_find_class(env, "java/lang/Class");
    jmethodID forName = neko_get_static_method_id(env, classClass, "forName", "(Ljava/lang/String;ZLjava/lang/ClassLoader;)Ljava/lang/Class;");
    jvalue args[3];
    args[0].l = neko_new_string_utf(env, dotted);
    args[1].z = JNI_FALSE;
    args[2].l = loader;
    free(dotted);
    return (jclass)neko_call_static_object_method_a(env, classClass, forName, args);
}

static jobject neko_box_boolean(JNIEnv *env, jboolean v) {
    static jclass g_box_boolean_cls = NULL;
    static jmethodID g_box_boolean_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_boolean_cls, env, "java/lang/Boolean");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_boolean_mid, env, cls, "valueOf", "(Z)Ljava/lang/Boolean;");
    jvalue args[1]; args[0].z = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_byte(JNIEnv *env, jbyte v) {
    static jclass g_box_byte_cls = NULL;
    static jmethodID g_box_byte_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_byte_cls, env, "java/lang/Byte");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_byte_mid, env, cls, "valueOf", "(B)Ljava/lang/Byte;");
    jvalue args[1]; args[0].b = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_char(JNIEnv *env, jchar v) {
    static jclass g_box_char_cls = NULL;
    static jmethodID g_box_char_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_char_cls, env, "java/lang/Character");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_char_mid, env, cls, "valueOf", "(C)Ljava/lang/Character;");
    jvalue args[1]; args[0].c = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_short(JNIEnv *env, jshort v) {
    static jclass g_box_short_cls = NULL;
    static jmethodID g_box_short_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_short_cls, env, "java/lang/Short");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_short_mid, env, cls, "valueOf", "(S)Ljava/lang/Short;");
    jvalue args[1]; args[0].s = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_int(JNIEnv *env, jint v) {
    static jclass g_box_int_cls = NULL;
    static jmethodID g_box_int_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_int_cls, env, "java/lang/Integer");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_int_mid, env, cls, "valueOf", "(I)Ljava/lang/Integer;");
    jvalue args[1]; args[0].i = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_long(JNIEnv *env, jlong v) {
    static jclass g_box_long_cls = NULL;
    static jmethodID g_box_long_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_long_cls, env, "java/lang/Long");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_long_mid, env, cls, "valueOf", "(J)Ljava/lang/Long;");
    jvalue args[1]; args[0].j = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_float(JNIEnv *env, jfloat v) {
    static jclass g_box_float_cls = NULL;
    static jmethodID g_box_float_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_float_cls, env, "java/lang/Float");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_float_mid, env, cls, "valueOf", "(F)Ljava/lang/Float;");
    jvalue args[1]; args[0].f = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jobject neko_box_double(JNIEnv *env, jdouble v) {
    static jclass g_box_double_cls = NULL;
    static jmethodID g_box_double_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_box_double_cls, env, "java/lang/Double");
    jmethodID mid = NEKO_ENSURE_STATIC_METHOD_ID(g_box_double_mid, env, cls, "valueOf", "(D)Ljava/lang/Double;");
    jvalue args[1]; args[0].d = v;
    return neko_call_static_object_method_a(env, cls, mid, args);
}
static jboolean neko_unbox_boolean(JNIEnv *env, jobject obj) {
    static jclass g_unbox_boolean_cls = NULL;
    static jmethodID g_unbox_boolean_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_boolean_cls, env, "java/lang/Boolean");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_boolean_mid, env, cls, "booleanValue", "()Z");
    return neko_call_boolean_method_a(env, obj, mid, NULL);
}
static jbyte neko_unbox_byte(JNIEnv *env, jobject obj) {
    static jclass g_unbox_byte_cls = NULL;
    static jmethodID g_unbox_byte_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_byte_cls, env, "java/lang/Byte");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_byte_mid, env, cls, "byteValue", "()B");
    return neko_call_byte_method_a(env, obj, mid, NULL);
}
static jchar neko_unbox_char(JNIEnv *env, jobject obj) {
    static jclass g_unbox_char_cls = NULL;
    static jmethodID g_unbox_char_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_char_cls, env, "java/lang/Character");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_char_mid, env, cls, "charValue", "()C");
    return neko_call_char_method_a(env, obj, mid, NULL);
}
static jshort neko_unbox_short(JNIEnv *env, jobject obj) {
    static jclass g_unbox_short_cls = NULL;
    static jmethodID g_unbox_short_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_short_cls, env, "java/lang/Short");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_short_mid, env, cls, "shortValue", "()S");
    return neko_call_short_method_a(env, obj, mid, NULL);
}
static jint neko_unbox_int(JNIEnv *env, jobject obj) {
    static jclass g_unbox_int_cls = NULL;
    static jmethodID g_unbox_int_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_int_cls, env, "java/lang/Integer");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_int_mid, env, cls, "intValue", "()I");
    return neko_call_int_method_a(env, obj, mid, NULL);
}
static jlong neko_unbox_long(JNIEnv *env, jobject obj) {
    static jclass g_unbox_long_cls = NULL;
    static jmethodID g_unbox_long_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_long_cls, env, "java/lang/Long");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_long_mid, env, cls, "longValue", "()J");
    return neko_call_long_method_a(env, obj, mid, NULL);
}
static jfloat neko_unbox_float(JNIEnv *env, jobject obj) {
    static jclass g_unbox_float_cls = NULL;
    static jmethodID g_unbox_float_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_float_cls, env, "java/lang/Float");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_float_mid, env, cls, "floatValue", "()F");
    return neko_call_float_method_a(env, obj, mid, NULL);
}
static jdouble neko_unbox_double(JNIEnv *env, jobject obj) {
    static jclass g_unbox_double_cls = NULL;
    static jmethodID g_unbox_double_mid = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_unbox_double_cls, env, "java/lang/Double");
    jmethodID mid = NEKO_ENSURE_METHOD_ID(g_unbox_double_mid, env, cls, "doubleValue", "()D");
    return neko_call_double_method_a(env, obj, mid, NULL);
}

static jclass neko_class_for_descriptor(JNIEnv *env, const char *desc) {
    switch (desc[0]) {
        case 'Z': { jclass c = neko_find_class(env, "java/lang/Boolean"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'B': { jclass c = neko_find_class(env, "java/lang/Byte"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'C': { jclass c = neko_find_class(env, "java/lang/Character"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'S': { jclass c = neko_find_class(env, "java/lang/Short"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'I': { jclass c = neko_find_class(env, "java/lang/Integer"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'J': { jclass c = neko_find_class(env, "java/lang/Long"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'F': { jclass c = neko_find_class(env, "java/lang/Float"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'D': { jclass c = neko_find_class(env, "java/lang/Double"); jfieldID f = neko_get_static_field_id(env, c, "TYPE", "Ljava/lang/Class;"); return (jclass)neko_get_static_object_field(env, c, f); }
        case 'L': {
            const char *start = desc + 1;
            const char *semi = strchr(start, ';');
            size_t len = (size_t)(semi - start);
            char *buf = (char*)malloc(len + 1u);
            memcpy(buf, start, len); buf[len] = '\\0';
            jclass out = neko_find_class(env, buf);
            free(buf);
            return out;
        }
        case '[':
            return neko_find_class(env, desc);
        default:
            return NULL;
    }
}

typedef struct {
    jlong id;
    jobject mh;
} neko_indy_entry;

static neko_indy_entry g_indy_table[4096];
static jint g_indy_count = 0;

static jobject neko_get_indy_mh(jlong site_id) {
    for (jint i = 0; i < g_indy_count; i++) {
        if (g_indy_table[i].id == site_id) return g_indy_table[i].mh;
    }
    return NULL;
}

static jobject neko_put_indy_mh(JNIEnv *env, jlong site_id, jobject mh) {
    jobject gref = mh == NULL ? NULL : neko_new_global_ref(env, mh);
    for (jint i = 0; i < g_indy_count; i++) {
        if (g_indy_table[i].id == site_id) {
            g_indy_table[i].mh = gref;
            return gref;
        }
    }
    if (g_indy_count < (jint)(sizeof(g_indy_table) / sizeof(g_indy_table[0]))) {
        g_indy_table[g_indy_count].id = site_id;
        g_indy_table[g_indy_count].mh = gref;
        g_indy_count++;
    }
    return gref;
}

static jobject neko_public_lookup(JNIEnv *env) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandles");
    jmethodID mid = neko_get_static_method_id(env, mhClass, "publicLookup", "()Ljava/lang/invoke/MethodHandles$Lookup;");
    return neko_call_static_object_method_a(env, mhClass, mid, NULL);
}

static jobject neko_impl_lookup(JNIEnv *env) {
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jfieldID fid = neko_get_static_field_id(env, lookupClass, "IMPL_LOOKUP", "Ljava/lang/invoke/MethodHandles$Lookup;");
    return neko_get_static_object_field(env, lookupClass, fid);
}

static jobject neko_lookup_for_jclass(JNIEnv *env, jclass ownerClass);

static jobject neko_lookup_for_class(JNIEnv *env, const char *owner) {
    jclass ownerClass = neko_find_class(env, owner);
    return neko_lookup_for_jclass(env, ownerClass);
}

static jobject neko_lookup_for_jclass(JNIEnv *env, jclass ownerClass) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandles");
    jmethodID mid = neko_get_static_method_id(env, mhClass, "privateLookupIn", "(Ljava/lang/Class;Ljava/lang/invoke/MethodHandles$Lookup;)Ljava/lang/invoke/MethodHandles$Lookup;");
    jvalue args[2];
    args[0].l = ownerClass;
    args[1].l = neko_impl_lookup(env);
    return neko_call_static_object_method_a(env, mhClass, mid, args);
}

static jobject neko_method_type_from_descriptor(JNIEnv *env, const char *desc) {
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_static_method_id(env, mtClass, "fromMethodDescriptorString", "(Ljava/lang/String;Ljava/lang/ClassLoader;)Ljava/lang/invoke/MethodType;");
    jvalue args[2];
    args[0].l = neko_new_string_utf(env, desc);
    args[1].l = NULL;
    return neko_call_static_object_method_a(env, mtClass, mid, args);
}

static jobjectArray neko_bootstrap_parameter_array(JNIEnv *env, const char *bsm_desc) {
    jobject mt = neko_method_type_from_descriptor(env, bsm_desc);
    jclass mtClass = neko_find_class(env, "java/lang/invoke/MethodType");
    jmethodID mid = neko_get_method_id(env, mtClass, "parameterArray", "()[Ljava/lang/Class;");
    return (jobjectArray)neko_call_object_method_a(env, mt, mid, NULL);
}

static jobject neko_invoke_bootstrap(JNIEnv *env, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray invoke_args) {
    jclass bsmClass = neko_find_class(env, bsm_owner);
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jclass classClass = neko_find_class(env, "java/lang/Class");
    jmethodID getDeclaredMethod = neko_get_method_id(env, classClass, "getDeclaredMethod", "(Ljava/lang/String;[Ljava/lang/Class;)Ljava/lang/reflect/Method;");
    jvalue getArgs[2];
    getArgs[0].l = neko_new_string_utf(env, bsm_name);
    getArgs[1].l = paramTypes;
    jobject method = neko_call_object_method_a(env, bsmClass, getDeclaredMethod, getArgs);

    jclass accessibleClass = neko_find_class(env, "java/lang/reflect/AccessibleObject");
    jmethodID setAccessible = neko_get_method_id(env, accessibleClass, "setAccessible", "(Z)V");
    jvalue accessibleArgs[1];
    accessibleArgs[0].z = JNI_TRUE;
    neko_call_void_method_a(env, method, setAccessible, accessibleArgs);

    jclass methodClass = neko_find_class(env, "java/lang/reflect/Method");
    jmethodID invoke = neko_get_method_id(env, methodClass, "invoke", "(Ljava/lang/Object;[Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue invokeArgs[2];
    invokeArgs[0].l = NULL;
    invokeArgs[1].l = invoke_args;
    return neko_call_object_method_a(env, method, invoke, invokeArgs);
}

static jobject neko_method_handle_from_parts(JNIEnv *env, jint tag, const char *owner, const char *name, const char *desc, jboolean isInterface) {
    (void)isInterface;
    jobject lookup = neko_lookup_for_class(env, owner);
    jclass lookupClass = neko_find_class(env, "java/lang/invoke/MethodHandles$Lookup");
    jclass ownerClass = neko_find_class(env, owner);
    jstring nameString = neko_new_string_utf(env, name);

    switch (tag) {
        case 1: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 2: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStaticGetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 3: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 4: {
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStaticSetter", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = neko_class_for_descriptor(env, desc);
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 5: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 6: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findStatic", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 7: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findSpecial", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;Ljava/lang/Class;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[4]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt; args[3].l = ownerClass;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 8: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findConstructor", "(Ljava/lang/Class;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[2]; args[0].l = ownerClass; args[1].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        case 9: {
            jobject mt = neko_method_type_from_descriptor(env, desc);
            jmethodID mid = neko_get_method_id(env, lookupClass, "findVirtual", "(Ljava/lang/Class;Ljava/lang/String;Ljava/lang/invoke/MethodType;)Ljava/lang/invoke/MethodHandle;");
            jvalue args[3]; args[0].l = ownerClass; args[1].l = nameString; args[2].l = mt;
            return neko_call_object_method_a(env, lookup, mid, args);
        }
        default:
            return NULL;
    }
}

static jobject neko_call_mh(JNIEnv *env, jobject mh, jobjectArray args) {
    jclass mhClass = neko_find_class(env, "java/lang/invoke/MethodHandle");
    jmethodID mid = neko_get_method_id(env, mhClass, "invokeWithArguments", "([Ljava/lang/Object;)Ljava/lang/Object;");
    jvalue callArgs[1];
    callArgs[0].l = args;
    return neko_call_object_method_a(env, mh, mid, callArgs);
}

static jstring neko_string_null(JNIEnv *env) {
    static jstring g_str_null = NULL;
    return NEKO_ENSURE_STRING(g_str_null, env, "null");
}

static jstring neko_string_concat2(JNIEnv *env, jobject left, jobject right) {
    static jclass g_str_cls = NULL;
    static jmethodID g_str_value_of = NULL;
    static jmethodID g_str_concat = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_str_cls, env, "java/lang/String");
    jmethodID valueOf = NEKO_ENSURE_STATIC_METHOD_ID(g_str_value_of, env, cls, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jmethodID concat = NEKO_ENSURE_METHOD_ID(g_str_concat, env, cls, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
    jvalue valueOfArgs[1];
    valueOfArgs[0].l = left;
    jstring lhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    valueOfArgs[0].l = right;
    jstring rhs = (jstring)neko_call_static_object_method_a(env, cls, valueOf, valueOfArgs);
    jvalue concatArgs[1];
    concatArgs[0].l = rhs;
    return (jstring)neko_call_object_method_a(env, lhs, concat, concatArgs);
}

static jstring neko_string_concat_string(JNIEnv *env, jobject left, jstring right) {
    static jclass g_str_cls2 = NULL;
    static jmethodID g_str_value_of2 = NULL;
    static jmethodID g_str_concat2 = NULL;
    jclass cls = NEKO_ENSURE_CLASS(g_str_cls2, env, "java/lang/String");
    jmethodID valueOf = NEKO_ENSURE_STATIC_METHOD_ID(g_str_value_of2, env, cls, "valueOf", "(Ljava/lang/Object;)Ljava/lang/String;");
    jmethodID concat = NEKO_ENSURE_METHOD_ID(g_str_concat2, env, cls, "concat", "(Ljava/lang/String;)Ljava/lang/String;");
    jstring lhs;
    if (left == NULL) {
        lhs = neko_string_null(env);
    } else {
        lhs = (jstring)left;
    }
    jvalue concatArgs[1];
    concatArgs[0].l = right == NULL ? neko_string_null(env) : right;
    return (jstring)neko_call_object_method_a(env, lhs, concat, concatArgs);
}

static jobject neko_resolve_indy(JNIEnv *env, jlong site_id, const char *caller_owner, const char *indy_name, const char *indy_desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobject cached = neko_get_indy_mh(site_id);
    if (cached != NULL) return cached;

    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, indy_name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_method_type_from_descriptor(env, indy_desc));
    for (jsize i = 0; i < neko_get_array_length(env, (jarray)static_args); i++) {
        neko_set_object_array_element(env, invokeArgs, i + 3, neko_get_object_array_element(env, static_args, i));
    }

    jobject bootstrapResult = neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
    jclass callSiteClass = neko_find_class(env, "java/lang/invoke/CallSite");
    jobject mh = bootstrapResult;
    if (bootstrapResult != NULL && neko_is_instance_of(env, bootstrapResult, callSiteClass)) {
        jmethodID dynamicInvoker = neko_get_method_id(env, callSiteClass, "dynamicInvoker", "()Ljava/lang/invoke/MethodHandle;");
        mh = neko_call_object_method_a(env, bootstrapResult, dynamicInvoker, NULL);
    }
    return neko_put_indy_mh(env, site_id, mh);
}

static jobject neko_resolve_constant_dynamic(JNIEnv *env, const char *caller_owner, const char *name, const char *desc, const char *bsm_owner, const char *bsm_name, const char *bsm_desc, jobjectArray static_args) {
    jobjectArray paramTypes = neko_bootstrap_parameter_array(env, bsm_desc);
    jsize paramCount = neko_get_array_length(env, (jarray)paramTypes);
    jclass objClass = neko_find_class(env, "java/lang/Object");
    jobjectArray invokeArgs = neko_new_object_array(env, paramCount, objClass, NULL);
    neko_set_object_array_element(env, invokeArgs, 0, neko_lookup_for_class(env, caller_owner));
    neko_set_object_array_element(env, invokeArgs, 1, neko_new_string_utf(env, name));
    neko_set_object_array_element(env, invokeArgs, 2, neko_class_for_descriptor(env, desc));
    for (jsize i = 0; i < neko_get_array_length(env, (jarray)static_args); i++) {
        neko_set_object_array_element(env, invokeArgs, i + 3, neko_get_object_array_element(env, static_args, i));
    }
    return neko_invoke_bootstrap(env, bsm_owner, bsm_name, bsm_desc, invokeArgs);
}

static jobject neko_multi_new_array(JNIEnv *env, jint num_dims, jint *dims, const char *desc) {
    if (num_dims <= 0) return NULL;
    if (num_dims == 1) {
        char leaf = desc[1];
        switch (leaf) {
            case 'Z': return (jobject)neko_new_boolean_array(env, dims[0]);
            case 'B': return (jobject)neko_new_byte_array(env, dims[0]);
            case 'C': return (jobject)neko_new_char_array(env, dims[0]);
            case 'S': return (jobject)neko_new_short_array(env, dims[0]);
            case 'I': return (jobject)neko_new_int_array(env, dims[0]);
            case 'J': return (jobject)neko_new_long_array(env, dims[0]);
            case 'F': return (jobject)neko_new_float_array(env, dims[0]);
            case 'D': return (jobject)neko_new_double_array(env, dims[0]);
            case 'L':
            case '[': {
                jclass elemClass = neko_class_for_descriptor(env, desc + 1);
                return (jobject)neko_new_object_array(env, dims[0], elemClass, NULL);
            }
            default:
                return NULL;
        }
    }
    jclass topElemClass = neko_class_for_descriptor(env, desc + 1);
    jobjectArray arr = (jobjectArray)neko_new_object_array(env, dims[0], topElemClass, NULL);
    for (jint i = 0; i < dims[0]; i++) {
        jobject sub = neko_multi_new_array(env, num_dims - 1, dims + 1, desc + 1);
        neko_set_object_array_element(env, arr, i, sub);
    }
    return (jobject)arr;
}

""";
    }

    private String renderHotSpotSupport() {
        return """
typedef enum {
    NEKO_COOP_MODE_UNKNOWN = 0,
    NEKO_COOP_MODE_DISABLED = 1,
    NEKO_COOP_MODE_ZERO_BASED = 2,
    NEKO_COOP_MODE_HEAP_BASED = 3
} neko_coop_encoded_mode;

enum {
    NEKO_PRIM_Z = 0,
    NEKO_PRIM_B = 1,
    NEKO_PRIM_C = 2,
    NEKO_PRIM_S = 3,
    NEKO_PRIM_I = 4,
    NEKO_PRIM_J = 5,
    NEKO_PRIM_F = 6,
    NEKO_PRIM_D = 7,
    NEKO_PRIM_COUNT = 8
};

enum {
    NEKO_HOTSPOT_FAST_RAW_HEAP = 1ll << 16,
    NEKO_HOTSPOT_FAST_ARRAY_LAYOUT = 1ll << 18,
    NEKO_HOTSPOT_FAST_HANDLE_TAGS = 1ll << 19,
    NEKO_HOTSPOT_FAST_FIELD_HELPERS = 1ll << 20,
    NEKO_FAST_RECEIVER_KEY = 0x10ll,
    NEKO_FAST_PRIM_FIELD = 0x4ll,
    NEKO_FAST_PRIM_ARRAY = 0x8ll
};

typedef struct {
    jboolean initialized;
    jint address_size;
    jboolean compressed_oops_enabled;
    jint compressed_oops_shift;
    jlong compressed_oops_base;
    jboolean compressed_klass_ptrs;
    jboolean use_compact_object_headers;
    jint coop_encoded_mode;
    jint primitive_array_base_offsets[NEKO_PRIM_COUNT];
    jint primitive_array_index_scales[NEKO_PRIM_COUNT];
    uintptr_t primitive_array_klass_bits[NEKO_PRIM_COUNT];
    jlong fast_bits;
    jboolean is_hotspot;
    jboolean use_zgc;
    jboolean use_shenandoah_gc;
    uintptr_t z_address_offset_mask;
    uintptr_t z_pointer_load_good_mask;
    uintptr_t z_pointer_load_bad_mask;
    uintptr_t z_pointer_store_good_mask;
    size_t z_pointer_load_shift;
    jint object_alignment_in_bytes;
    /*
     * Receiver-key scaffold state is appended so existing field offsets stay
     * unchanged for T1-T4 fast paths. klass_offset_bytes is derived from the
     * object-header mark word width (4 bytes on 32-bit, 8 bytes on 64-bit).
     */
    jboolean use_compressed_klass_ptrs;
    jint klass_offset_bytes;
} neko_hotspot_state;

static neko_hotspot_state g_hotspot;
static uintptr_t g_neko_handle_sample_oop = 0;

static jlong neko_native_instance_field_offset(JNIEnv *env, jclass cls, const char *name);
static jlong neko_native_static_field_offset(JNIEnv *env, jclass cls, const char *name);
static jobject neko_native_static_field_base(JNIEnv *env, jclass cls, const char *name);

static const char* neko_hotspot_primitive_name(int kind) {
    switch (kind) {
        case NEKO_PRIM_Z: return "boolean";
        case NEKO_PRIM_B: return "byte";
        case NEKO_PRIM_C: return "char";
        case NEKO_PRIM_S: return "short";
        case NEKO_PRIM_I: return "int";
        case NEKO_PRIM_J: return "long";
        case NEKO_PRIM_F: return "float";
        case NEKO_PRIM_D: return "double";
        default: return NULL;
    }
}

static jboolean neko_hotspot_clear_exception(JNIEnv *env) {
    if (!neko_exception_check(env)) return JNI_FALSE;
    neko_exception_clear(env);
    return JNI_TRUE;
}

static jboolean neko_parse_bool_option(const char *value, jboolean *out) {
    if (value == NULL || out == NULL) return JNI_FALSE;
    if (strcmp(value, "true") == 0) {
        *out = JNI_TRUE;
        return JNI_TRUE;
    }
    if (strcmp(value, "false") == 0) {
        *out = JNI_FALSE;
        return JNI_TRUE;
    }
    return JNI_FALSE;
}

static jboolean neko_parse_int_option(const char *value, jint *out) {
    char *end = NULL;
    long parsed;
    if (value == NULL || out == NULL) return JNI_FALSE;
    parsed = strtol(value, &end, 10);
    if (end == value || (end != NULL && *end != '\\0')) return JNI_FALSE;
    *out = (jint)parsed;
    return JNI_TRUE;
}

static jint neko_hotspot_shift_from_alignment(jint alignment) {
    jint shift = 0;
    if (alignment <= 0) return 0;
    while ((alignment & 1) == 0) {
        alignment >>= 1;
        shift++;
    }
    return alignment == 1 ? shift : 0;
}

static jstring neko_system_property(JNIEnv *env, const char *key) {
    static jclass g_system_cls = NULL;
    static jmethodID g_system_get_property = NULL;
    jclass systemClass = NEKO_ENSURE_CLASS(g_system_cls, env, "java/lang/System");
    jmethodID getProperty = NEKO_ENSURE_STATIC_METHOD_ID(g_system_get_property, env, systemClass, "getProperty", "(Ljava/lang/String;)Ljava/lang/String;");
    jvalue args[1];
    args[0].l = neko_new_string_utf(env, key);
    if (args[0].l == NULL) return NULL;
    jstring value = (jstring)neko_call_static_object_method_a(env, systemClass, getProperty, args);
    neko_delete_local_ref(env, args[0].l);
    return value;
}

static jboolean neko_detect_hotspot(JNIEnv *env) {
    jstring vmName = neko_system_property(env, "java.vm.name");
    const char *chars;
    jboolean isHotspot = JNI_FALSE;
    if (vmName == NULL || neko_hotspot_clear_exception(env)) return JNI_FALSE;
    chars = neko_get_string_utf_chars(env, vmName);
    if (chars != NULL) {
        if (strstr(chars, "HotSpot") != NULL || strstr(chars, "OpenJDK") != NULL) {
            isHotspot = JNI_TRUE;
        }
        neko_release_string_utf_chars(env, vmName, chars);
    }
    neko_delete_local_ref(env, vmName);
    return isHotspot;
}

static jboolean neko_hotspot_option_string(JNIEnv *env, const char *name, char *buffer, size_t bufferSize) {
    jclass managementFactoryClass;
    jclass hotspotMxBeanClass;
    jmethodID getPlatformMxBean;
    jmethodID getVmOption;
    jmethodID getValue;
    jobject mxBean;
    jobject vmOption;
    jstring optionName;
    jstring optionValue;
    const char *chars;
    jvalue args[1];
    if (buffer == NULL || bufferSize == 0) return JNI_FALSE;
    managementFactoryClass = neko_find_class(env, "java/lang/management/ManagementFactory");
    hotspotMxBeanClass = neko_find_class(env, "com/sun/management/HotSpotDiagnosticMXBean");
    if (managementFactoryClass == NULL || hotspotMxBeanClass == NULL || neko_hotspot_clear_exception(env)) return JNI_FALSE;
    getPlatformMxBean = neko_get_static_method_id(env, managementFactoryClass, "getPlatformMXBean", "(Ljava/lang/Class;)Ljava/lang/Object;");
    if (getPlatformMxBean == NULL || neko_hotspot_clear_exception(env)) return JNI_FALSE;
    args[0].l = hotspotMxBeanClass;
    mxBean = neko_call_static_object_method_a(env, managementFactoryClass, getPlatformMxBean, args);
    if (mxBean == NULL || neko_hotspot_clear_exception(env)) return JNI_FALSE;
    getVmOption = neko_get_method_id(env, hotspotMxBeanClass, "getVMOption", "(Ljava/lang/String;)Lcom/sun/management/VMOption;");
    if (getVmOption == NULL || neko_hotspot_clear_exception(env)) return JNI_FALSE;
    optionName = neko_new_string_utf(env, name);
    if (optionName == NULL) return JNI_FALSE;
    args[0].l = optionName;
    vmOption = neko_call_object_method_a(env, mxBean, getVmOption, args);
    if (neko_hotspot_clear_exception(env)) {
        neko_delete_local_ref(env, optionName);
        return JNI_FALSE;
    }
    neko_delete_local_ref(env, optionName);
    if (vmOption == NULL) return JNI_FALSE;
    getValue = neko_get_method_id(env, neko_get_object_class(env, vmOption), "getValue", "()Ljava/lang/String;");
    if (getValue == NULL || neko_hotspot_clear_exception(env)) return JNI_FALSE;
    optionValue = (jstring)neko_call_object_method_a(env, vmOption, getValue, NULL);
    if (neko_hotspot_clear_exception(env)) return JNI_FALSE;
    if (optionValue == NULL) return JNI_FALSE;
    chars = neko_get_string_utf_chars(env, optionValue);
    if (chars == NULL) {
        neko_delete_local_ref(env, optionValue);
        return JNI_FALSE;
    }
    strncpy(buffer, chars, bufferSize - 1u);
    buffer[bufferSize - 1u] = '\\0';
    neko_release_string_utf_chars(env, optionValue, chars);
    neko_delete_local_ref(env, optionValue);
    return JNI_TRUE;
}

static jobject neko_hotspot_unsafe_singleton_for(JNIEnv *env, const char *className) {
    jclass unsafeClass = neko_find_class(env, className);
    jfieldID theUnsafe;
    jobject unsafe;
    if (unsafeClass == NULL || neko_hotspot_clear_exception(env)) return NULL;
    theUnsafe = neko_get_static_field_id(env, unsafeClass, "theUnsafe", "Lsun/misc/Unsafe;");
    if (theUnsafe == NULL || neko_hotspot_clear_exception(env)) {
        theUnsafe = neko_get_static_field_id(env, unsafeClass, "theUnsafe", "Ljdk/internal/misc/Unsafe;");
        if (theUnsafe == NULL || neko_hotspot_clear_exception(env)) return NULL;
    }
    unsafe = neko_get_static_object_field(env, unsafeClass, theUnsafe);
    if (neko_hotspot_clear_exception(env)) return NULL;
    return unsafe;
}

static jobject neko_hotspot_unsafe_singleton(JNIEnv *env) {
    jobject unsafe = neko_hotspot_unsafe_singleton_for(env, "sun/misc/Unsafe");
    if (unsafe != NULL) return unsafe;
    return neko_hotspot_unsafe_singleton_for(env, "jdk/internal/misc/Unsafe");
}

static jint neko_hotspot_address_size(JNIEnv *env) {
    jobject unsafe = neko_hotspot_unsafe_singleton(env);
    jmethodID mid;
    jint value;
    if (unsafe == NULL) return 0;
    mid = neko_get_method_id(env, neko_get_object_class(env, unsafe), "addressSize", "()I");
    if (mid == NULL || neko_hotspot_clear_exception(env)) return 0;
    value = neko_call_int_method_a(env, unsafe, mid, NULL);
    if (neko_hotspot_clear_exception(env)) return 0;
    return value;
}

static jclass neko_hotspot_primitive_array_class(JNIEnv *env, const char *primitiveName) {
    if (primitiveName == NULL) return NULL;
    if (strcmp(primitiveName, "boolean") == 0) return neko_find_class(env, "[Z");
    if (strcmp(primitiveName, "byte") == 0) return neko_find_class(env, "[B");
    if (strcmp(primitiveName, "char") == 0) return neko_find_class(env, "[C");
    if (strcmp(primitiveName, "short") == 0) return neko_find_class(env, "[S");
    if (strcmp(primitiveName, "int") == 0) return neko_find_class(env, "[I");
    if (strcmp(primitiveName, "long") == 0) return neko_find_class(env, "[J");
    if (strcmp(primitiveName, "float") == 0) return neko_find_class(env, "[F");
    if (strcmp(primitiveName, "double") == 0) return neko_find_class(env, "[D");
    return NULL;
}

static jarray neko_hotspot_new_primitive_array(JNIEnv *env, int kind, jsize len) {
    switch (kind) {
        case NEKO_PRIM_Z: return (jarray)neko_new_boolean_array(env, len);
        case NEKO_PRIM_B: return (jarray)neko_new_byte_array(env, len);
        case NEKO_PRIM_C: return (jarray)neko_new_char_array(env, len);
        case NEKO_PRIM_S: return (jarray)neko_new_short_array(env, len);
        case NEKO_PRIM_I: return (jarray)neko_new_int_array(env, len);
        case NEKO_PRIM_J: return (jarray)neko_new_long_array(env, len);
        case NEKO_PRIM_F: return (jarray)neko_new_float_array(env, len);
        case NEKO_PRIM_D: return (jarray)neko_new_double_array(env, len);
        default: return NULL;
    }
}

static jint neko_hotspot_array_base_offset(JNIEnv *env, const char *primitiveName) {
    jobject unsafe = neko_hotspot_unsafe_singleton(env);
    jclass arrayClass = neko_hotspot_primitive_array_class(env, primitiveName);
    jmethodID mid;
    jvalue args[1];
    jint value;
    if (unsafe == NULL || arrayClass == NULL || neko_hotspot_clear_exception(env)) return -1;
    mid = neko_get_method_id(env, neko_get_object_class(env, unsafe), "arrayBaseOffset", "(Ljava/lang/Class;)I");
    if (mid == NULL || neko_hotspot_clear_exception(env)) return -1;
    args[0].l = arrayClass;
    value = neko_call_int_method_a(env, unsafe, mid, args);
    if (neko_hotspot_clear_exception(env)) return -1;
    return value;
}

static jint neko_hotspot_array_index_scale(JNIEnv *env, const char *primitiveName) {
    jobject unsafe = neko_hotspot_unsafe_singleton(env);
    jclass arrayClass = neko_hotspot_primitive_array_class(env, primitiveName);
    jmethodID mid;
    jvalue args[1];
    jint value;
    if (unsafe == NULL || arrayClass == NULL || neko_hotspot_clear_exception(env)) return 0;
    mid = neko_get_method_id(env, neko_get_object_class(env, unsafe), "arrayIndexScale", "(Ljava/lang/Class;)I");
    if (mid == NULL || neko_hotspot_clear_exception(env)) return 0;
    args[0].l = arrayClass;
    value = neko_call_int_method_a(env, unsafe, mid, args);
    if (neko_hotspot_clear_exception(env)) return 0;
    return value;
}

static void neko_hotspot_init(JNIEnv *env) {
    neko_hotspot_state state;
    char optionValue[64];
    jlong fastBits = 0;
    jboolean arraysOk = JNI_TRUE;
    jboolean fieldHelpersOk = JNI_FALSE;
    if (g_hotspot.initialized) return;
    memset(&state, 0, sizeof(state));
    if (env == NULL) goto fail;
    if (!neko_detect_hotspot(env)) goto fail;

    state.is_hotspot = JNI_TRUE;
    state.address_size = neko_hotspot_address_size(env);
    if (neko_hotspot_clear_exception(env) || state.address_size <= 0) goto fail;

    memset(optionValue, 0, sizeof(optionValue));
    if (neko_hotspot_option_string(env, "UseCompressedOops", optionValue, sizeof(optionValue))) {
        (void)neko_parse_bool_option(optionValue, &state.compressed_oops_enabled);
    }
    /* If the MXBean probe failed (it consistently does inside JNI_OnLoad
     * because the platform MXBean registry hasn't been fully wired up yet),
     * neko_method_layout_init will overwrite compressed_oops_enabled /
     * _shift / _base from the authoritative CompressedOops::_narrow_oop
     * VMStructs entries. */
    memset(optionValue, 0, sizeof(optionValue));
    if (neko_hotspot_option_string(env, "UseCompressedClassPointers", optionValue, sizeof(optionValue))) {
        (void)neko_parse_bool_option(optionValue, &state.compressed_klass_ptrs);
    }
    memset(optionValue, 0, sizeof(optionValue));
    if (neko_hotspot_option_string(env, "UseCompactObjectHeaders", optionValue, sizeof(optionValue))) {
        (void)neko_parse_bool_option(optionValue, &state.use_compact_object_headers);
    }
    memset(optionValue, 0, sizeof(optionValue));
    if (neko_hotspot_option_string(env, "UseZGC", optionValue, sizeof(optionValue))) {
        (void)neko_parse_bool_option(optionValue, &state.use_zgc);
    }
    memset(optionValue, 0, sizeof(optionValue));
    if (neko_hotspot_option_string(env, "UseShenandoahGC", optionValue, sizeof(optionValue))) {
        (void)neko_parse_bool_option(optionValue, &state.use_shenandoah_gc);
    }
    memset(optionValue, 0, sizeof(optionValue));
    if (neko_hotspot_option_string(env, "ObjectAlignmentInBytes", optionValue, sizeof(optionValue))) {
        (void)neko_parse_int_option(optionValue, &state.object_alignment_in_bytes);
    }

    state.use_compressed_klass_ptrs = state.compressed_klass_ptrs;
    if (state.address_size == 4 || state.address_size == 8) {
        state.klass_offset_bytes = state.address_size;
    }

    if (state.compressed_oops_enabled) {
        state.compressed_oops_shift = neko_hotspot_shift_from_alignment(state.object_alignment_in_bytes);
        state.compressed_oops_base = 0;
        state.coop_encoded_mode = NEKO_COOP_MODE_UNKNOWN;
    } else {
        state.coop_encoded_mode = NEKO_COOP_MODE_DISABLED;
    }

    for (int i = 0; i < NEKO_PRIM_COUNT; i++) {
        const char *primitiveName = neko_hotspot_primitive_name(i);
        jint baseOffset;
        jint indexScale;
        if (primitiveName == NULL) goto fail;
        baseOffset = neko_hotspot_array_base_offset(env, primitiveName);
        if (neko_hotspot_clear_exception(env)) {
            goto fail;
        }
        indexScale = neko_hotspot_array_index_scale(env, primitiveName);
        if (neko_hotspot_clear_exception(env)) {
            goto fail;
        }
        state.primitive_array_base_offsets[i] = baseOffset;
        state.primitive_array_index_scales[i] = indexScale;
        if (baseOffset < 0 || indexScale <= 0) arraysOk = JNI_FALSE;
    }

    {
        jclass integerClass = neko_find_class(env, "java/lang/Integer");
        jlong instanceOffset;
        jlong staticOffset;
        jobject staticBase;
        if (integerClass == NULL || neko_hotspot_clear_exception(env)) goto fail;
        instanceOffset = neko_native_instance_field_offset(env, integerClass, "value");
        if (neko_hotspot_clear_exception(env)) {
            neko_delete_local_ref(env, integerClass);
            goto fail;
        }
        staticOffset = neko_native_static_field_offset(env, integerClass, "TYPE");
        if (neko_hotspot_clear_exception(env)) {
            neko_delete_local_ref(env, integerClass);
            goto fail;
        }
        staticBase = neko_native_static_field_base(env, integerClass, "TYPE");
        if (neko_hotspot_clear_exception(env)) {
            neko_delete_local_ref(env, integerClass);
            goto fail;
        }
        fieldHelpersOk = (instanceOffset >= 0 && staticOffset >= 0 && staticBase != NULL) ? JNI_TRUE : JNI_FALSE;
        if (state.address_size == 8) {
            if (instanceOffset == 12) {
                state.use_compressed_klass_ptrs = JNI_TRUE;
            } else if (instanceOffset >= 16) {
                state.use_compressed_klass_ptrs = JNI_FALSE;
            }
        }
        if (staticBase != NULL) neko_delete_local_ref(env, staticBase);
        neko_delete_local_ref(env, integerClass);
    }

    if (state.address_size == 8) {
        jint byteArrayBase = state.primitive_array_base_offsets[NEKO_PRIM_B];
        if (byteArrayBase == 16) {
            state.use_compressed_klass_ptrs = JNI_TRUE;
        } else if (byteArrayBase >= 24) {
            state.use_compressed_klass_ptrs = JNI_FALSE;
        }
    }

    for (int i = 0; i < NEKO_PRIM_COUNT; i++) {
        jarray emptyArray = neko_hotspot_new_primitive_array(env, i, 0);
        char *arrayOop;
        if (emptyArray == NULL || neko_hotspot_clear_exception(env)) {
            arraysOk = JNI_FALSE;
            continue;
        }
        arrayOop = (char*)neko_handle_oop((jobject)emptyArray);
        if (arrayOop != NULL && state.klass_offset_bytes > 0) {
            if (state.use_compressed_klass_ptrs) {
                state.primitive_array_klass_bits[i] = (uintptr_t)(*(uint32_t*)(arrayOop + state.klass_offset_bytes));
            } else {
                state.primitive_array_klass_bits[i] = *(uintptr_t*)(arrayOop + state.klass_offset_bytes);
            }
        }
        neko_delete_local_ref(env, emptyArray);
    }

    {
        jclass objectClass = neko_find_class(env, "java/lang/Object");
        jobject globalRef;
        jobject weakRef;
        if (objectClass == NULL || neko_hotspot_clear_exception(env)) goto fail;
        globalRef = neko_new_global_ref(env, objectClass);
        weakRef = neko_new_weak_global_ref(env, objectClass);
        if (getenv("NEKO_PATCH_DEBUG") != NULL) {
            uintptr_t localSlot = ((uintptr_t)objectClass) & ~(uintptr_t)0x3u;
            uintptr_t globalSlot = ((uintptr_t)globalRef) & ~(uintptr_t)0x3u;
            void *localOop = localSlot != 0 ? *(void**)localSlot : NULL;
            void *globalOop = globalSlot != 0 ? *(void**)globalSlot : NULL;
            g_neko_handle_sample_oop = (uintptr_t)(localOop != NULL ? localOop : globalOop);
            fprintf(stderr, "[neko-patch] handle sample: local_ref=%p local_oop=%p global_ref=%p global_oop=%p coop_shift=%d use_zgc=%d\\n",
                (void*)objectClass, localOop, (void*)globalRef, globalOop,
                state.compressed_oops_shift, (int)state.use_zgc);
        } else {
            uintptr_t localSlot = ((uintptr_t)objectClass) & ~(uintptr_t)0x3u;
            if (localSlot != 0) g_neko_handle_sample_oop = (uintptr_t)(*(void**)localSlot);
        }
        if (globalRef != NULL && weakRef != NULL
            && ((((uintptr_t)objectClass) & 0x3u) == 0u)
            && ((((uintptr_t)globalRef) & 0x3u) == 0x2u)
            && ((((uintptr_t)weakRef) & 0x3u) == 0x1u)) {
            fastBits |= NEKO_HOTSPOT_FAST_HANDLE_TAGS;
        }
        if (globalRef != NULL) neko_delete_global_ref(env, globalRef);
        if (weakRef != NULL) neko_delete_weak_global_ref(env, weakRef);
        neko_delete_local_ref(env, objectClass);
    }

    if (arraysOk) fastBits |= NEKO_HOTSPOT_FAST_ARRAY_LAYOUT;
    if (fieldHelpersOk) fastBits |= NEKO_HOTSPOT_FAST_FIELD_HELPERS;
    if ((fastBits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0u && arraysOk && fieldHelpersOk
        && !state.use_zgc && !state.use_shenandoah_gc) {
        fastBits |= NEKO_HOTSPOT_FAST_RAW_HEAP;
    }

    if ((fastBits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0 && !state.use_compact_object_headers) {
        if (fieldHelpersOk) fastBits |= NEKO_FAST_PRIM_FIELD;
        if (arraysOk) fastBits |= NEKO_FAST_PRIM_ARRAY;
        if (state.klass_offset_bytes > 0) fastBits |= NEKO_FAST_RECEIVER_KEY;
    }

    /*
     * Compact object headers change where klass metadata lives, so any future
     * receiver-key extraction or raw heap layout shortcut must stay disabled.
     * Zeroing fast_bits here keeps later tiers on the conservative JNI path.
     */
    if (state.use_compact_object_headers) fastBits = 0;

    state.fast_bits = fastBits;
    state.initialized = JNI_TRUE;
    g_hotspot = state;
    return;

fail:
    memset(&g_hotspot, 0, sizeof(g_hotspot));
    g_hotspot.initialized = JNI_TRUE;
    g_hotspot.fast_bits = 0;
}

""" + renderHotSpotFastAccessHelpers();
    }

    private String renderHotSpotFastAccessHelpers() {
        StringBuilder sb = new StringBuilder();
        sb.append("""

#if defined(__STDC_VERSION__) && __STDC_VERSION__ >= 199901L
#define NEKO_FAST_INLINE static inline
#else
#define NEKO_FAST_INLINE static
#endif

NEKO_FAST_INLINE jboolean neko_ref_is_direct_oop(jobject ref) {
    uintptr_t raw;
    if (ref == NULL) return JNI_FALSE;
    raw = (uintptr_t)ref;
    if (g_hotspot.use_zgc) {
        uintptr_t good_mask = g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_store_good_mask;
        uintptr_t metadata_mask = good_mask | g_hotspot.z_pointer_load_bad_mask;
        uintptr_t valid_mask = g_hotspot.z_address_offset_mask | metadata_mask;
        return (good_mask != 0
            && g_hotspot.z_address_offset_mask != 0
            && (raw & metadata_mask) == good_mask
            && (raw & ~valid_mask) == 0) ? JNI_TRUE : JNI_FALSE;
    }
#if UINTPTR_MAX > 0xffffffffu
    if ((raw & (uintptr_t)0x7u) == 0 && raw < (uintptr_t)0x0000100000000000ULL) {
        return JNI_TRUE;
    }
#endif
    return JNI_FALSE;
}

NEKO_FAST_INLINE void *neko_zgc_uncolor_oop(void *oop) {
    uintptr_t raw = (uintptr_t)oop;
    if (raw == 0 || !g_hotspot.use_zgc) return oop;
    if (g_hotspot.z_pointer_load_shift != 0
        && (raw & (g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_load_bad_mask)) != 0) {
        return (void*)(raw >> g_hotspot.z_pointer_load_shift);
    }
    if (g_hotspot.z_address_offset_mask != 0
        && (raw & (g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_load_bad_mask)) != 0) {
        return (void*)(raw & g_hotspot.z_address_offset_mask);
    }
    return oop;
}

NEKO_FAST_INLINE void *neko_zgc_good_oop(void *oop) {
    uintptr_t raw = (uintptr_t)oop;
    uintptr_t good_mask;
    if (raw == 0 || !g_hotspot.use_zgc) return oop;
    if ((raw & (g_hotspot.z_pointer_load_good_mask | g_hotspot.z_pointer_load_bad_mask | g_hotspot.z_pointer_store_good_mask)) != 0) {
        return oop;
    }
    good_mask = g_hotspot.z_pointer_store_good_mask != 0 ? g_hotspot.z_pointer_store_good_mask : g_hotspot.z_pointer_load_good_mask;
    if (good_mask != 0 && g_hotspot.z_address_offset_mask != 0 && (raw & ~g_hotspot.z_address_offset_mask) == 0) {
        return (void*)(raw | good_mask);
    }
    return oop;
}

NEKO_FAST_INLINE void *neko_barrier_oop_load(void *raw_oop) {
    uintptr_t raw = (uintptr_t)raw_oop;
    if (raw == 0) return NULL;
    if (g_hotspot.use_zgc) {
        if (g_hotspot.z_pointer_load_bad_mask != 0 && (raw & g_hotspot.z_pointer_load_bad_mask) != 0) {
            fprintf(stderr, "[neko-direct] ZGC bad oop load needs runtime barrier raw=%p bad_mask=0x%llx\\n",
                raw_oop, (unsigned long long)g_hotspot.z_pointer_load_bad_mask);
            abort();
        }
        return neko_zgc_good_oop(raw_oop);
    }
    return raw_oop;
}

NEKO_FAST_INLINE void* neko_handle_oop(jobject handle) {
    uintptr_t raw;
    uintptr_t slot;
    if (handle == NULL) return NULL;
    raw = (uintptr_t)handle;
    /* Direct call_stub entry can re-enter translated native code with raw
     * HotSpot oops in Java heap registers/stack slots. JNI handle slots live
     * in native stack or JNIHandleBlock memory, which is far above the zero-
     * based compressed-oops heap used by the supported JDK 21 test targets.
     * Treat low aligned references as already-unwrapped oops so nested direct
     * calls do not dereference the object mark word as a handle slot. */
    if (neko_ref_is_direct_oop(handle)) {
        return neko_zgc_good_oop((void*)raw);
    }
    slot = (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_HANDLE_TAGS) != 0 ? (raw & ~(uintptr_t)0x3u) : raw;
    return neko_barrier_oop_load(*(void**)slot);
}

NEKO_FAST_INLINE void* neko_static_base_oop(jobject staticBase) {
    uintptr_t raw;
    uintptr_t untagged;
    if (staticBase == NULL) return NULL;
    if (neko_ref_is_direct_oop(staticBase)) {
        return neko_zgc_good_oop((void*)staticBase);
    }
    raw = (uintptr_t)staticBase;
    untagged = raw & ~(uintptr_t)0x3u;
    return neko_barrier_oop_load(*(void**)untagged);
}

NEKO_FAST_INLINE jint neko_fast_array_length(JNIEnv *env, jarray arr) {
    (void)env;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && arr != NULL) {
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL) {
            return *(jint*)(oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
        }
    }
    fprintf(stderr, "[neko-direct] ARRAYLENGTH direct path unavailable arr=%p\\n", (void*)arr);
    abort();
}

NEKO_FAST_INLINE jboolean neko_receiver_key_supported(void) {
    return g_hotspot.initialized
        && g_hotspot.use_compact_object_headers == JNI_FALSE
        && ((g_hotspot.fast_bits & NEKO_FAST_RECEIVER_KEY) != 0
            || (g_hotspot.use_zgc && g_hotspot.klass_offset_bytes > 0));
}

NEKO_FAST_INLINE uintptr_t neko_receiver_key(jobject obj) {
    char *oop;
    char *klassAddr;
    if (obj == NULL || !neko_receiver_key_supported()) return (uintptr_t)0;
    oop = (char*)neko_handle_oop(obj);
    if (oop == NULL || g_hotspot.klass_offset_bytes <= 0) return (uintptr_t)0;
    klassAddr = oop + g_hotspot.klass_offset_bytes;
    if (g_hotspot.use_compressed_klass_ptrs) {
        return (uintptr_t)(*(uint32_t*)klassAddr);
    }
    return *(uintptr_t*)klassAddr;
}

typedef jvalue (*neko_icache_direct_stub)(void *thread, JNIEnv *env, jobject receiver, const jvalue *args);

/* neko_njx_dispatcher_t was already typedef'd in the
 * NativeToJavaInvokeEmitter prelude (rendered before renderHotSpotSupport).
 * The per-shape dispatcher functions are forward-declared there too. */

typedef struct {
    const char *name;
    const char *desc;
    const jclass *translated_class_slot;
    neko_icache_direct_stub translated_stub;
    jboolean is_interface;
    /* Per-shape direct dispatcher. NULL means this site isn't wired for
     * direct invoke (the shape wasn't registered by the codegen). When
     * non-NULL, neko_icache_dispatch will route through it once we have a
     * resolved Method* + compiled-entry pair for the receiver class. */
    neko_njx_dispatcher_t direct_dispatcher;
} neko_icache_meta;

NEKO_FAST_INLINE char neko_icache_return_kind(const char *desc) {
    const char *ret = desc == NULL ? NULL : strrchr(desc, ')');
    return (ret != NULL && ret[1] != '\\0') ? ret[1] : 'V';
}

NEKO_FAST_INLINE int neko_icache_find_slot(neko_icache_site *site, uintptr_t receiverKey) {
    uint32_t i;
    if (site == NULL || receiverKey == 0) return -1;
    for (i = 0u; i < NEKO_ICACHE_PIC_SIZE; i++) {
        if (site->target_kind[i] != NEKO_ICACHE_EMPTY && site->receiver_key[i] == receiverKey) {
            return (int)i;
        }
    }
    return -1;
}

NEKO_FAST_INLINE uint32_t neko_icache_claim_slot(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey) {
    uint32_t i;
    int existing;
    if (site == NULL) return 0u;
    existing = neko_icache_find_slot(site, receiverKey);
    if (existing >= 0) return (uint32_t)existing;
    for (i = 0u; i < NEKO_ICACHE_PIC_SIZE; i++) {
        if (site->target_kind[i] == NEKO_ICACHE_EMPTY) return i;
    }
    i = (uint32_t)(site->next_slot++ & (NEKO_ICACHE_PIC_SIZE - 1u));
    if (site->cached_class[i] != NULL) neko_delete_global_ref(env, site->cached_class[i]);
    site->receiver_key[i] = 0;
    site->target[i] = NULL;
    site->target2[i] = NULL;
    site->cached_class[i] = NULL;
    site->target_kind[i] = NEKO_ICACHE_EMPTY;
    return i;
}

/* Forward decls: defined later in the methodPatcherEmitter region. */
extern jboolean g_neko_direct_invoke_ready;

/* Runtime gate for direct-NJX diagnostics. Direct invoke is mandatory;
 * NEKO_DIRECT_DEBUG=1 enables the final one-line stats report. */
static int g_neko_njx_enabled_cached = 1;
static int g_neko_njx_enabled_initialized = 0;
static int g_neko_njx_debug_cached = 0;
static volatile uint64_t g_neko_njx_dispatch_count = 0;
static volatile uint64_t g_neko_njx_resolve_fail_count = 0;
static volatile int g_neko_njx_stats_printed = 0;
NEKO_FAST_INLINE int neko_njx_debug(void) { return g_neko_njx_debug_cached; }
NEKO_FAST_INLINE int neko_njx_enabled(void) {
    if (__builtin_expect(!g_neko_njx_enabled_initialized, 0)) {
        g_neko_njx_enabled_cached = 1;
        const char *d = getenv("NEKO_DIRECT_DEBUG");
        g_neko_njx_debug_cached = (d != NULL && d[0] != '\\0' && d[0] != '0') ? 1 : 0;
        g_neko_njx_enabled_initialized = 1;
    }
    return g_neko_njx_enabled_cached;
}

NEKO_FAST_INLINE void neko_njx_note_dispatch(void) {
    if (__builtin_expect(neko_njx_debug(), 0)) {
        __atomic_fetch_add(&g_neko_njx_dispatch_count, 1, __ATOMIC_RELAXED);
    }
}

NEKO_FAST_INLINE void neko_njx_note_resolve_fail(void) {
    if (__builtin_expect(neko_njx_debug(), 0)) {
        __atomic_fetch_add(&g_neko_njx_resolve_fail_count, 1, __ATOMIC_RELAXED);
    }
}

#define NEKO_DIRECT_LOG(fmt, ...) do { } while (0)

NEKO_FAST_INLINE void neko_icache_store_direct(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, void *target) {
    uint32_t slot;
    if (site == NULL) return;
    slot = neko_icache_claim_slot(env, site, receiverKey);
    if (site->cached_class[slot] != NULL && site->cached_class[slot] != cachedClass) neko_delete_global_ref(env, site->cached_class[slot]);
    site->cached_class[slot] = cachedClass;
    site->receiver_key[slot] = receiverKey;
    site->target[slot] = target;
    site->target2[slot] = NULL;
    site->target_kind[slot] = NEKO_ICACHE_DIRECT_C;
}

/* Cache a resolved (Method*, _from_compiled_entry) pair for the receiver
 * class. Subsequent dispatches to the same class skip the JNI GetMethodID
 * and invoke the per-shape dispatcher (from meta) directly with the cached
 * entry pointer. */
NEKO_FAST_INLINE void neko_icache_store_direct_njx(JNIEnv *env, neko_icache_site *site, uintptr_t receiverKey, jclass cachedClass, void *method_ptr, void *compiled_entry) {
    uint32_t slot;
    if (site == NULL) return;
    slot = neko_icache_claim_slot(env, site, receiverKey);
    if (site->cached_class[slot] != NULL && site->cached_class[slot] != cachedClass) neko_delete_global_ref(env, site->cached_class[slot]);
    site->cached_class[slot] = cachedClass;
    site->receiver_key[slot] = receiverKey;
    site->target[slot] = method_ptr;
    site->target2[slot] = compiled_entry;
    site->target_kind[slot] = NEKO_ICACHE_DIRECT_NJX;
}

NEKO_FAST_INLINE jboolean neko_icache_note_miss(JNIEnv *env, neko_icache_site *site) {
    if (site == NULL) return JNI_FALSE;
    if (site->miss_count < (uint16_t)0xFFFFu) site->miss_count++;
    (void)env;
    return JNI_FALSE;
}

/* Forward decls for direct-NJX helpers — actual definitions live in the
 * NativeToJavaInvokeEmitter.renderBodies() region which runs *after*
 * methodPatcherEmitter (where g_neko_method_layout is defined). */
static int neko_njx_resolve_entry(jmethodID mid, void **out_method, void **out_entry);

static jvalue neko_icache_dispatch(
    void *thread,
    JNIEnv *env,
    neko_icache_site *site,
    const neko_icache_meta *meta,
    jobject receiver,
    jmethodID declared_mid,
    const jvalue *args
) {
    jvalue result = {0};
    uintptr_t receiverKey;
    void *__receiver_jni_slot = NULL;
    jobject receiver_jni;
    if (env == NULL || receiver == NULL || declared_mid == NULL) return result;
    receiver_jni = receiver;
    if (neko_ref_is_direct_oop(receiver)) {
        __receiver_jni_slot = (void*)receiver;
        receiver_jni = (jobject)&__receiver_jni_slot;
    }
    if (site != NULL && neko_receiver_key_supported()) {
        int cacheSlot;
        receiverKey = neko_receiver_key(receiver);
        if (receiverKey != 0) {
            cacheSlot = neko_icache_find_slot(site, receiverKey);
            if (cacheSlot >= 0) {
                if (site->target_kind[cacheSlot] == NEKO_ICACHE_DIRECT_C && site->target[cacheSlot] != NULL) {
                    return ((neko_icache_direct_stub)site->target[cacheSlot])(thread, env, receiver_jni, args);
                }
                if (site->target_kind[cacheSlot] == NEKO_ICACHE_DIRECT_NJX && site->target[cacheSlot] != NULL && site->target2[cacheSlot] != NULL
                    && meta != NULL && meta->direct_dispatcher != NULL && neko_njx_enabled()) {
                    return meta->direct_dispatcher(thread, env, site->target[cacheSlot], site->target2[cacheSlot], receiver, args);
                }
            }
            if (!neko_icache_note_miss(env, site)) {
                jclass exactClass = neko_get_object_class(env, receiver_jni);
                if (exactClass != NULL && !neko_exception_check(env)) {
                    jclass translatedClass = (meta != NULL && meta->translated_class_slot != NULL) ? *meta->translated_class_slot : NULL;
                    if (translatedClass != NULL && meta != NULL && meta->translated_stub != NULL && neko_is_same_object(env, exactClass, translatedClass)) {
                        jclass cachedExactClass = (jclass)neko_new_global_ref(env, exactClass);
                        if (neko_exception_check(env)) {
                            neko_exception_clear(env);
                            cachedExactClass = NULL;
                        }
                        neko_icache_store_direct(env, site, receiverKey, cachedExactClass, (void*)meta->translated_stub);
                        neko_delete_local_ref(env, exactClass);
                        return meta->translated_stub(thread, env, receiver_jni, args);
                    }
                    jmethodID exactMid = neko_get_method_id(env, exactClass, meta != NULL ? meta->name : NULL, meta != NULL ? meta->desc : NULL);
                    if (exactMid != NULL && !neko_exception_check(env)) {
                        jclass cachedExactClass = (jclass)neko_new_global_ref(env, exactClass);
                        if (neko_exception_check(env)) {
                            neko_exception_clear(env);
                            cachedExactClass = NULL;
                        }
                        /* Direct-NJX is mandatory once the virtual target is
                         * resolved. A missing dispatcher or unresolved Method*
                         * is a hard runtime failure, not a JNI call fallback. */
                        if (neko_njx_enabled() && g_neko_direct_invoke_ready
                            && meta != NULL && meta->direct_dispatcher != NULL) {
                            void *m_ptr = NULL, *m_entry = NULL;
                            if (neko_njx_resolve_entry(exactMid, &m_ptr, &m_entry)) {
                                neko_icache_store_direct_njx(env, site, receiverKey, cachedExactClass, m_ptr, m_entry);
                                result = meta->direct_dispatcher(thread, env, m_ptr, m_entry, receiver, args);
                                neko_delete_local_ref(env, exactClass);
                                return result;
                            } else {
                                neko_njx_note_resolve_fail();
                                fprintf(stderr, "[neko-direct] resolve-failed %s%s mid=%p\\n",
                                    meta != NULL ? meta->name : "?", meta != NULL ? meta->desc : "?", exactMid);
                                abort();
                            }
                        }
                        fprintf(stderr, "[neko-direct] missing direct dispatcher for %s%s\\n",
                            meta != NULL ? meta->name : "?", meta != NULL ? meta->desc : "?");
                        abort();
                    }
                    if (neko_exception_check(env)) neko_exception_clear(env);
                    neko_delete_local_ref(env, exactClass);
                } else if (neko_exception_check(env)) {
                    neko_exception_clear(env);
                }
            }
        }
    }
    neko_njx_note_resolve_fail();
    fprintf(stderr, "[neko-direct] unresolved virtual dispatch %s%s declared_mid=%p receiver=%p site=%p\\n",
        meta != NULL ? meta->name : "?", meta != NULL ? meta->desc : "?", declared_mid, receiver, (void*)site);
    abort();
    return result;
}

__attribute__((used)) static void neko_njx_dump_stats_at_exit(void) {
    uint64_t hits;
    uint64_t fails;
    if (!neko_njx_debug()) return;
    hits = __atomic_load_n(&g_neko_njx_dispatch_count, __ATOMIC_RELAXED);
    fails = __atomic_load_n(&g_neko_njx_resolve_fail_count, __ATOMIC_RELAXED);
    if (hits == 0 && fails == 0) return;
    if (!__sync_bool_compare_and_swap(&g_neko_njx_stats_printed, 0, 1)) return;
    fprintf(stderr, "[neko-direct] stats: dispatched=%llu resolve_failed=%llu\\n",
        (unsigned long long)hits, (unsigned long long)fails);
}
__attribute__((constructor)) static void neko_njx_register_atexit(void) {
    atexit(neko_njx_dump_stats_at_exit);
}

""");
        appendPrimitiveFieldHelpers(sb, 'Z', "jboolean", "boolean");
        appendPrimitiveFieldHelpers(sb, 'B', "jbyte", "byte");
        appendPrimitiveFieldHelpers(sb, 'C', "jchar", "char");
        appendPrimitiveFieldHelpers(sb, 'S', "jshort", "short");
        appendPrimitiveFieldHelpers(sb, 'I', "jint", "int");
        appendPrimitiveFieldHelpers(sb, 'J', "jlong", "long");
        appendPrimitiveFieldHelpers(sb, 'F', "jfloat", "float");
        appendPrimitiveFieldHelpers(sb, 'D', "jdouble", "double");
        appendPrimitiveArrayHelpers(sb, "z", "jboolean", "boolean", "NEKO_PRIM_Z");
        appendPrimitiveArrayHelpers(sb, "b", "jbyte", "byte", "NEKO_PRIM_B");
        appendPrimitiveArrayHelpers(sb, "c", "jchar", "char", "NEKO_PRIM_C");
        appendPrimitiveArrayHelpers(sb, "s", "jshort", "short", "NEKO_PRIM_S");
        appendPrimitiveArrayHelpers(sb, "i", "jint", "int", "NEKO_PRIM_I");
        appendPrimitiveArrayHelpers(sb, "l", "jlong", "long", "NEKO_PRIM_J");
        appendPrimitiveArrayHelpers(sb, "f", "jfloat", "float", "NEKO_PRIM_F");
        appendPrimitiveArrayHelpers(sb, "d", "jdouble", "double", "NEKO_PRIM_D");
        return sb.toString();
    }

    /**
     * Emit fast paths for AALOAD / AASTORE that avoid the per-element JNI
     * GetObjectArrayElement / SetObjectArrayElement round-trip. The slow JNI
     * path allocates a fresh local-ref handle for every load — for a tight
     * matrix-multiply inner loop this is the single biggest cost (millions of
     * handle allocations chained into JNIHandleBlock _next blocks).
     *
     * Fast path: read the element oop directly from the array's narrow-oop /
     * wide-oop slot and push it into the active JNIHandleBlock via the same
     * inlined neko_handle_push the dispatcher uses for ref args. Falls back
     * to the JNI version if VMStructs didn't recover all the bits we need
     * (compressed-oops shift, primitive-array layout — used as a stand-in for
     * object-array base since both are 16 bytes on 64-bit hotspot with
     * compressed klass pointers, the only configuration that ships today).
     *
     * Emitted AFTER methodPatcherEmitter so the inline neko_handle_push is
     * already in scope.
     */
    public String renderObjectArrayFastHelpers() {
        StringBuilder sb = new StringBuilder();
        appendObjectArrayHelpers(sb);
        return sb.toString();
    }

    private void appendObjectArrayHelpers(StringBuilder sb) {
        sb.append("""
static uintptr_t g_neko_string_klass_bits = 0;
static uintptr_t g_neko_byte_array_klass_bits = 0;
static jboolean g_neko_fast_string_alloc_ready = JNI_FALSE;

NEKO_FAST_INLINE jobject neko_direct_oop_to_handle(void *thread, void *raw_oop);

static void neko_fast_string_runtime_init(JNIEnv *env) {
    jstring empty;
    jbyteArray bytes;
    char *empty_oop;
    char *bytes_oop;
    if (g_neko_fast_string_alloc_ready || env == NULL) return;
    if (!g_hotspot.initialized
        || (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0
        || g_hotspot.use_compact_object_headers
        || g_hotspot.klass_offset_bytes <= 0
        || !g_neko_tlab_alloc_ready) {
        return;
    }
    empty = neko_new_string_utf(env, "");
    bytes = neko_new_byte_array(env, 0);
    if (empty == NULL || bytes == NULL || neko_exception_check(env)) {
        if (neko_exception_check(env)) neko_exception_clear(env);
        return;
    }
    empty_oop = (char*)neko_handle_oop((jobject)empty);
    bytes_oop = (char*)neko_handle_oop((jobject)bytes);
    if (empty_oop != NULL && bytes_oop != NULL) {
        if (g_hotspot.use_compressed_klass_ptrs) {
            g_neko_string_klass_bits = (uintptr_t)(*(uint32_t*)(empty_oop + g_hotspot.klass_offset_bytes));
            g_neko_byte_array_klass_bits = (uintptr_t)(*(uint32_t*)(bytes_oop + g_hotspot.klass_offset_bytes));
        } else {
            g_neko_string_klass_bits = *(uintptr_t*)(empty_oop + g_hotspot.klass_offset_bytes);
            g_neko_byte_array_klass_bits = *(uintptr_t*)(bytes_oop + g_hotspot.klass_offset_bytes);
        }
        g_neko_fast_string_alloc_ready =
            (g_neko_string_klass_bits != 0 && g_neko_byte_array_klass_bits != 0) ? JNI_TRUE : JNI_FALSE;
    }
    neko_delete_local_ref(env, empty);
    neko_delete_local_ref(env, bytes);
}

NEKO_FAST_INLINE size_t neko_align_object_bytes(size_t bytes) {
    size_t alignment = (size_t)(g_hotspot.object_alignment_in_bytes > 0 ? g_hotspot.object_alignment_in_bytes : 8);
    return (bytes + alignment - 1u) & ~(alignment - 1u);
}

NEKO_FAST_INLINE void *neko_fast_tlab_alloc(void *thread, size_t bytes) {
    char *tlab;
    char *top;
    char *end;
    char *new_top;
    size_t aligned;
    if (!g_neko_tlab_alloc_ready || thread == NULL || bytes == 0) return NULL;
    aligned = neko_align_object_bytes(bytes);
    tlab = (char*)thread + g_neko_off_thread_tlab;
    top = *(char**)(tlab + g_neko_off_tlab_top);
    end = *(char**)(tlab + g_neko_off_tlab_end);
    if (top == NULL || end == NULL || top > end || aligned > (size_t)(end - top)) return NULL;
    new_top = top + aligned;
    *(char**)(tlab + g_neko_off_tlab_top) = new_top;
    memset(top, 0, aligned);
    return top;
}

NEKO_FAST_INLINE void neko_init_oop_header(char *oop, uintptr_t klass_bits) {
    *(uintptr_t*)oop = (uintptr_t)1u;
    if (g_hotspot.use_compressed_klass_ptrs) {
        *(uint32_t*)(oop + g_hotspot.klass_offset_bytes) = (uint32_t)klass_bits;
    } else {
        *(uintptr_t*)(oop + g_hotspot.klass_offset_bytes) = klass_bits;
    }
}

NEKO_FAST_INLINE void* neko_decode_narrow_oop(uint32_t narrow) {
    if (narrow == 0) return NULL;
    return neko_barrier_oop_load((void*)((uintptr_t)((uintptr_t)narrow << g_hotspot.compressed_oops_shift)
                   + (uintptr_t)g_hotspot.compressed_oops_base));
}

NEKO_FAST_INLINE uint32_t neko_encode_narrow_oop(void *oop) {
    if (oop == NULL) return 0u;
    return (uint32_t)(((uintptr_t)oop - (uintptr_t)g_hotspot.compressed_oops_base) >> g_hotspot.compressed_oops_shift);
}

NEKO_FAST_INLINE void neko_store_oop_raw(char *oop, jlong offset, void *value) {
    value = neko_zgc_good_oop(value);
    if (g_hotspot.compressed_oops_enabled) {
        *(uint32_t*)(oop + offset) = neko_encode_narrow_oop(value);
    } else {
        *(void**)(oop + offset) = value;
    }
}

NEKO_FAST_INLINE char *neko_string_value_oop(char *str_oop, jlong valueOffset) {
    if (str_oop == NULL || valueOffset <= 0) return NULL;
    if (g_hotspot.compressed_oops_enabled) {
        return (char*)neko_decode_narrow_oop(*(uint32_t*)(str_oop + valueOffset));
    }
    return (char*)neko_barrier_oop_load(*(void**)(str_oop + valueOffset));
}

NEKO_FAST_INLINE jobject neko_fast_string_concat(
    void *thread,
    JNIEnv *env,
    jstring left,
    jstring right,
    jlong valueOffset,
    jlong coderOffset
) {
    (void)env;
    if (!g_neko_fast_string_alloc_ready
        || (g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) == 0
        || valueOffset <= 0
        || coderOffset <= 0
        || left == NULL
        || right == NULL) {
        return NULL;
    }
    char *left_oop = (char*)neko_handle_oop((jobject)left);
    char *right_oop = (char*)neko_handle_oop((jobject)right);
    char *left_value;
    char *right_value;
    jint left_len;
    jint right_len;
    jint total_len;
    char *array_oop;
    char *string_oop;
    size_t array_bytes;
    size_t string_bytes;
    size_t ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
    if (left_oop == NULL || right_oop == NULL) return NULL;
    if (*(jbyte*)(left_oop + coderOffset) != 0 || *(jbyte*)(right_oop + coderOffset) != 0) return NULL;
    left_value = neko_string_value_oop(left_oop, valueOffset);
    right_value = neko_string_value_oop(right_oop, valueOffset);
    if (left_value == NULL || right_value == NULL) return NULL;
    left_len = *(jint*)(left_value + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] - 4);
    right_len = *(jint*)(right_value + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] - 4);
    if (left_len < 0 || right_len < 0 || left_len > INT32_MAX - right_len) return NULL;
    total_len = left_len + right_len;
    array_bytes = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] + (size_t)total_len;
    array_oop = (char*)neko_fast_tlab_alloc(thread, array_bytes);
    if (array_oop == NULL) return NULL;
    neko_init_oop_header(array_oop, g_neko_byte_array_klass_bits);
    *(jint*)(array_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] - 4) = total_len;
    if (left_len > 0) {
        memcpy(array_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B],
               left_value + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B],
               (size_t)left_len);
    }
    if (right_len > 0) {
        memcpy(array_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] + left_len,
               right_value + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B],
               (size_t)right_len);
    }

    string_bytes = (size_t)valueOffset + ref_size;
    if ((size_t)coderOffset + 1u > string_bytes) string_bytes = (size_t)coderOffset + 1u;
    string_oop = (char*)neko_fast_tlab_alloc(thread, string_bytes);
    if (string_oop == NULL) return NULL;
    neko_init_oop_header(string_oop, g_neko_string_klass_bits);
    neko_store_oop_raw(string_oop, valueOffset, array_oop);
    *(jbyte*)(string_oop + coderOffset) = 0;
    return neko_direct_oop_to_handle(thread, string_oop);
}

NEKO_FAST_INLINE jobject neko_direct_oop_to_handle(void *thread, void *raw_oop) {
    if (raw_oop == NULL) return NULL;
    raw_oop = neko_zgc_good_oop(raw_oop);
    if (g_neko_handle_push_ready && thread != NULL) {
        void *block = *(void**)((char*)thread + g_neko_off_thread_active_handles);
        if (block != NULL) {
            int32_t *top_ptr = (int32_t*)((char*)block + g_neko_off_jnih_block_top);
            int32_t top = *top_ptr;
            if (top < g_neko_jnih_block_capacity) {
                void **handles = (void**)((char*)block + g_neko_off_jnih_block_handles);
                handles[top] = raw_oop;
                *top_ptr = top + 1;
                if (g_neko_method_layout.off_jnih_block_next > 0) {
                    ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
                    *(void**)((char*)block + off_last) = block;
                }
                return (jobject)&handles[top];
            }
            if (g_neko_method_layout.sizeof_JNIHandleBlock > 0 && g_neko_method_layout.off_jnih_block_next > 0) {
                void *new_block = calloc(1, g_neko_method_layout.sizeof_JNIHandleBlock);
                if (new_block != NULL) {
                    void **new_handles = (void**)((char*)new_block + g_neko_off_jnih_block_handles);
                    new_handles[0] = raw_oop;
                    *(int32_t*)((char*)new_block + g_neko_off_jnih_block_top) = 1;
                    *(void**)((char*)new_block + g_neko_method_layout.off_jnih_block_next) = block;
                    {
                        ptrdiff_t off_last = g_neko_method_layout.off_jnih_block_next + 8;
                        *(void**)((char*)new_block + off_last) = new_block;
                    }
                    *(void**)((char*)thread + g_neko_off_thread_active_handles) = new_block;
                    return (jobject)&new_handles[0];
                }
            }
        }
    }
    fprintf(stderr, "[neko-direct] JNIHandleBlock direct slot unavailable thread=%p raw=%p\\n", thread, raw_oop);
    abort();
}

NEKO_FAST_INLINE jobjectArray neko_fast_new_object_array(void *thread, JNIEnv *env, jint len, uintptr_t klass_bits, jobject init) {
    (void)env;
    if (len < 0) {
        fprintf(stderr, "[neko-direct] negative object array length %d\\n", (int)len);
        abort();
    }
    if (klass_bits != 0
        && g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && !g_hotspot.use_compact_object_headers
        && g_neko_tlab_alloc_ready) {
        size_t ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
        size_t base = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I];
        size_t bytes = base + ((size_t)len * ref_size);
        char *array_oop = (char*)neko_fast_tlab_alloc(thread, bytes);
        if (array_oop != NULL) {
            neko_init_oop_header(array_oop, klass_bits);
            *(jint*)(array_oop + base - 4u) = len;
            if (init != NULL) {
                void *init_oop = neko_handle_oop(init);
                for (jint i = 0; i < len; i++) {
                    neko_store_oop_raw(array_oop, (jlong)(base + ((size_t)i * ref_size)), init_oop);
                }
            }
            return (jobjectArray)neko_direct_oop_to_handle(thread, array_oop);
        }
    }
    fprintf(stderr, "[neko-direct] object array allocation direct path unavailable len=%d klass=0x%llx thread=%p\\n",
        (int)len, (unsigned long long)klass_bits, thread);
    abort();
}

NEKO_FAST_INLINE jarray neko_fast_new_primitive_array(void *thread, JNIEnv *env, jint len, int kind) {
    (void)env;
    if (len < 0) {
        fprintf(stderr, "[neko-direct] negative primitive array length %d kind=%d\\n", (int)len, kind);
        abort();
    }
    if (kind >= 0 && kind < NEKO_PRIM_COUNT
        && g_hotspot.primitive_array_klass_bits[kind] != 0
        && g_hotspot.primitive_array_base_offsets[kind] >= 0
        && g_hotspot.primitive_array_index_scales[kind] > 0
        && g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_HOTSPOT_FAST_RAW_HEAP) != 0 || g_hotspot.use_zgc)
        && !g_hotspot.use_compact_object_headers
        && g_neko_tlab_alloc_ready) {
        size_t base = (size_t)g_hotspot.primitive_array_base_offsets[kind];
        size_t scale = (size_t)g_hotspot.primitive_array_index_scales[kind];
        size_t bytes = base + ((size_t)len * scale);
        char *array_oop = (char*)neko_fast_tlab_alloc(thread, bytes);
        if (array_oop != NULL) {
            neko_init_oop_header(array_oop, g_hotspot.primitive_array_klass_bits[kind]);
            *(jint*)(array_oop + base - 4u) = len;
            return (jarray)neko_direct_oop_to_handle(thread, array_oop);
        }
    }
    fprintf(stderr, "[neko-direct] primitive array allocation direct path unavailable len=%d kind=%d thread=%p\\n",
        (int)len, kind, thread);
    abort();
}

NEKO_FAST_INLINE void neko_fast_aastore(void *thread, JNIEnv *env, jobjectArray arr, jint idx, jobject val) {
    (void)thread; (void)env;
    char *__debug_oop = NULL;
    jint __debug_len = -1;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && arr != NULL) {
        char *oop = (char*)neko_handle_oop((jobject)arr);
        __debug_oop = oop;
        if (oop != NULL) {
            size_t ref_size = g_hotspot.compressed_oops_enabled ? 4u : sizeof(void*);
            size_t base = (size_t)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I];
            jint arrayLen = *(jint*)(oop + base - 4u);
            __debug_len = arrayLen;
            if (idx >= 0 && idx < arrayLen) {
                void *value_oop = val == NULL ? NULL : neko_handle_oop(val);
                neko_store_oop_raw(oop, (jlong)(base + ((size_t)idx * ref_size)), value_oop);
                return;
            }
        }
    }
    fprintf(stderr, "[neko-direct] AASTORE direct path unavailable arr=%p idx=%d oop=%p len=%d baseI=%d use_zgc=%d fast=0x%llx\\n",
        (void*)arr, (int)idx, __debug_oop, (int)__debug_len,
        (int)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I],
        (int)g_hotspot.use_zgc,
        (unsigned long long)g_hotspot.fast_bits);
    abort();
}

NEKO_FAST_INLINE char *neko_inner_oop_from_outer(char *outer_oop, jint idx1, jint outer_len) {
    if (idx1 < 0 || idx1 >= outer_len) return NULL;
    if (g_hotspot.compressed_oops_enabled) {
        char *addr = outer_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx1 * 4);
        return (char*)neko_decode_narrow_oop(*(uint32_t*)addr);
    }
    char *addr = outer_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx1 * 8);
    return (char*)neko_barrier_oop_load(*(void**)addr);
}

NEKO_FAST_INLINE jint neko_fast_string_length(JNIEnv *env, jstring str, jlong valueOffset, jlong coderOffset) {
    (void)env;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] >= 0
        && valueOffset > 0
        && coderOffset > 0
        && str != NULL) {
        char *str_oop = (char*)neko_handle_oop((jobject)str);
        if (str_oop != NULL) {
            char *value_oop;
            if (g_hotspot.compressed_oops_enabled) {
                value_oop = (char*)neko_decode_narrow_oop(*(uint32_t*)(str_oop + valueOffset));
            } else {
                value_oop = (char*)neko_barrier_oop_load(*(void**)(str_oop + valueOffset));
            }
            if (value_oop != NULL) {
                jint value_len = *(jint*)(value_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_B] - 4);
                jbyte coder = *(jbyte*)(str_oop + coderOffset);
                return value_len >> (coder & 1);
            }
        }
    }
    abort();
}

NEKO_FAST_INLINE jobject neko_fast_aaload(void *thread, JNIEnv *env, jobjectArray arr, jint idx) {
    (void)env;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && arr != NULL) {
        char *oop = (char*)neko_handle_oop((jobject)arr);
        if (oop != NULL) {
            jint arrayLen = *(jint*)(oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
            if (idx >= 0 && idx < arrayLen) {
                void *element_oop;
                if (g_hotspot.compressed_oops_enabled) {
                    char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx * 4);
                    element_oop = neko_decode_narrow_oop(*(uint32_t*)addr);
                } else {
                    char *addr = oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx * 8);
                    element_oop = neko_barrier_oop_load(*(void**)addr);
                }
                return neko_direct_oop_to_handle(thread, element_oop);
            }
        }
    }
    {
        void *dbg_block = thread != NULL && g_neko_off_thread_active_handles > 0 ? *(void**)((char*)thread + g_neko_off_thread_active_handles) : NULL;
        int32_t dbg_top = dbg_block != NULL ? *(int32_t*)((char*)dbg_block + g_neko_off_jnih_block_top) : -1;
        void *dbg_oop = arr != NULL ? neko_handle_oop((jobject)arr) : NULL;
        jint dbg_len = dbg_oop != NULL ? *(jint*)((char*)dbg_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4) : -1;
        fprintf(stderr, "[neko-direct] AALOAD direct path unavailable arr=%p idx=%d thread=%p init=%d bits=0x%x base=%d push=%d block=%p top=%d oop=%p len=%d\\n",
            (void*)arr, (int)idx, thread, (int)g_hotspot.initialized, (unsigned)g_hotspot.fast_bits,
            (int)g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I], (int)g_neko_handle_push_ready,
            dbg_block, (int)dbg_top, dbg_oop, (int)dbg_len);
    }
    abort();
}

""");
        appendFusedAALoadHelpers(sb);
        appendObjectFieldFastHelpers(sb);
    }

    /**
     * Fast paths for object field reads (GETFIELD / GETSTATIC of L-types).
     * Skips the per-call JNI GetObjectField / GetStaticObjectField round-trip
     * by reading the field's narrow / wide oop slot directly off the receiver
     * (or static-base mirror) and inline-pushing the raw oop into the active
     * JNIHandleBlock — same trick the AALOAD fast path uses.
     *
     * Read-only: object writes still go through the JNI setter so HotSpot's
     * G1 / ZGC card-mark / load-barrier code paths fire correctly.
     */
    private void appendObjectFieldFastHelpers(StringBuilder sb) {
        sb.append("""
NEKO_FAST_INLINE jobject neko_fast_get_object_field(void *thread, JNIEnv *env, jobject obj, jfieldID fid, jlong offset) {
    (void)env; (void)fid;
    if (g_hotspot.initialized
        && offset > 0
        && obj != NULL) {
        char *receiver_oop = (char*)neko_handle_oop(obj);
        if (receiver_oop != NULL) {
            void *element_oop;
            if (g_hotspot.compressed_oops_enabled) {
                element_oop = neko_decode_narrow_oop(*(uint32_t*)(receiver_oop + offset));
            } else {
                element_oop = neko_barrier_oop_load(*(void**)(receiver_oop + offset));
            }
            return neko_direct_oop_to_handle(thread, element_oop);
        }
    }
    fprintf(stderr, "[neko-direct] object GETFIELD direct path unavailable obj=%p offset=%lld thread=%p\\n", (void*)obj, (long long)offset, thread);
    abort();
}

NEKO_FAST_INLINE jobject neko_fast_get_static_object_field(void *thread, JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {
    (void)env; (void)cls; (void)fid;
    if (g_hotspot.initialized
        && offset > 0
        && staticBase != NULL) {
        char *base_oop = (char*)neko_static_base_oop((jobject)cls);
        if (base_oop == NULL) base_oop = (char*)neko_static_base_oop(staticBase);
        if (base_oop != NULL) {
            void *element_oop;
            if (g_hotspot.compressed_oops_enabled) {
                element_oop = neko_decode_narrow_oop(*(uint32_t*)(base_oop + offset));
            } else {
                element_oop = neko_barrier_oop_load(*(void**)(base_oop + offset));
            }
            return neko_direct_oop_to_handle(thread, element_oop);
        }
    }
    {
        void *dbg_block = thread != NULL ? *(void**)((char*)thread + g_neko_off_thread_active_handles) : NULL;
        int32_t dbg_top = dbg_block != NULL ? *(int32_t*)((char*)dbg_block + g_neko_off_jnih_block_top) : -1;
        char *dbg_cls_oop = (char*)neko_static_base_oop((jobject)cls);
        char *dbg_base_oop = (char*)neko_static_base_oop(staticBase);
        uint32_t dbg_narrow = dbg_cls_oop != NULL && offset > 0 ? *(uint32_t*)(dbg_cls_oop + offset) : 0;
        void *dbg_wide = dbg_cls_oop != NULL && offset > 0 ? *(void**)(dbg_cls_oop + offset) : NULL;
        fprintf(stderr, "[neko-direct] object GETSTATIC direct path unavailable cls=%p base=%p offset=%lld thread=%p init=%d push=%d block=%p top=%d cap=%d cls_oop=%p base_oop=%p narrow=0x%x wide=%p compressed=%d\\n",
            (void*)cls, (void*)staticBase, (long long)offset, thread, (int)g_hotspot.initialized, (int)g_neko_handle_push_ready,
            dbg_block, (int)dbg_top, (int)g_neko_jnih_block_capacity, (void*)dbg_cls_oop, (void*)dbg_base_oop,
            (unsigned)dbg_narrow, dbg_wide, (int)g_hotspot.compressed_oops_enabled);
    }
    abort();
}

NEKO_FAST_INLINE jlong neko_fast_atomic_long_add_and_get(JNIEnv *env, jobject obj, jlong delta, jlong offset) {
    (void)env;
    if (g_hotspot.initialized && offset > 0 && obj != NULL) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) {
            return __atomic_add_fetch((jlong*)(oop + offset), delta, __ATOMIC_SEQ_CST);
        }
    }
    fprintf(stderr, "[neko-direct] AtomicLong.addAndGet direct path unavailable obj=%p offset=%lld\\n", (void*)obj, (long long)offset);
    abort();
}

NEKO_FAST_INLINE jint neko_fast_atomic_int_add_and_get(JNIEnv *env, jobject obj, jint delta, jlong offset) {
    (void)env;
    if (g_hotspot.initialized && offset > 0 && obj != NULL) {
        char *oop = (char*)neko_handle_oop(obj);
        if (oop != NULL) {
            return __atomic_add_fetch((jint*)(oop + offset), delta, __ATOMIC_SEQ_CST);
        }
    }
    fprintf(stderr, "[neko-direct] AtomicInteger.addAndGet direct path unavailable obj=%p offset=%lld\\n", (void*)obj, (long long)offset);
    abort();
}

""");
    }

    /**
     * Fused AALOAD + primitive *ALOAD helper: skips the JNIHandle allocation
     * for the intermediate inner-array reference, since the inner oop is only
     * used immediately within this single inline function.
     *
     * Hot path for matrix-style nested arrays (a[i][k]) — eliminates 100% of
     * per-iteration handle allocs in the inner loop.
     */
    private void appendFusedAALoadHelpers(StringBuilder sb) {
        appendFusedAALoadPrim(sb, "b", "jbyte",  "NEKO_PRIM_B", "byte",   "jbyteArray");
        appendFusedAALoadPrim(sb, "c", "jchar",  "NEKO_PRIM_C", "char",   "jcharArray");
        appendFusedAALoadPrim(sb, "s", "jshort", "NEKO_PRIM_S", "short",  "jshortArray");
        appendFusedAALoadPrim(sb, "i", "jint",   "NEKO_PRIM_I", "int",    "jintArray");
        appendFusedAALoadPrim(sb, "l", "jlong",  "NEKO_PRIM_J", "long",   "jlongArray");
        appendFusedAALoadPrim(sb, "f", "jfloat", "NEKO_PRIM_F", "float",  "jfloatArray");
        appendFusedAALoadPrim(sb, "d", "jdouble","NEKO_PRIM_D", "double", "jdoubleArray");
        sb.append("""
NEKO_FAST_INLINE jobject neko_fast_aaload_aaload(void *thread, JNIEnv *env, jobjectArray outer, jint idx1, jint idx2) {
    (void)env;
    if (g_hotspot.initialized
        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)
        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0
        && thread != NULL && outer != NULL) {
        char *outer_oop = (char*)neko_handle_oop((jobject)outer);
        if (outer_oop != NULL) {
            jint outer_len = *(jint*)(outer_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
            char *inner_oop = neko_inner_oop_from_outer(outer_oop, idx1, outer_len);
            if (inner_oop != NULL) {
                jint inner_len = *(jint*)(inner_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);
                if (idx2 >= 0 && idx2 < inner_len) {
                    void *element_oop;
                    if (g_hotspot.compressed_oops_enabled) {
                        char *addr = inner_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx2 * 4);
                        element_oop = neko_decode_narrow_oop(*(uint32_t*)addr);
                    } else {
                        char *addr = inner_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] + ((jlong)idx2 * 8);
                        element_oop = neko_barrier_oop_load(*(void**)addr);
                    }
                    return neko_direct_oop_to_handle(thread, element_oop);
                }
            }
        }
    }
    fprintf(stderr, "[neko-direct] AALOAD+AALOAD direct path unavailable outer=%p idx1=%d idx2=%d thread=%p\\n", (void*)outer, (int)idx1, (int)idx2, thread);
    abort();
}

""");
    }

    private void appendFusedAALoadPrim(
        StringBuilder sb, String prefix, String cType, String elemKind, String wrapperStem, String jArrayType
    ) {
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_aaload_").append(prefix)
            .append("aload(void *thread, JNIEnv *env, jobjectArray outer, jint idx1, jint idx2) {\n")
            .append("    (void)thread;\n")
            .append("    (void)env;\n")
            .append("    if (g_hotspot.initialized\n")
            .append("        && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc)\n")
            .append("        && g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] >= 0\n")
            .append("        && outer != NULL) {\n")
            .append("        char *outer_oop = (char*)neko_handle_oop((jobject)outer);\n")
            .append("        if (outer_oop != NULL) {\n")
            .append("            jint outer_len = *(jint*)(outer_oop + g_hotspot.primitive_array_base_offsets[NEKO_PRIM_I] - 4);\n")
            .append("            char *inner_oop = neko_inner_oop_from_outer(outer_oop, idx1, outer_len);\n")
            .append("            if (inner_oop != NULL) {\n")
            .append("                jint inner_len = *(jint*)(inner_oop + g_hotspot.primitive_array_base_offsets[").append(elemKind).append("] - 4);\n")
            .append("                if (idx2 >= 0 && idx2 < inner_len) {\n")
            .append("                    char *addr = inner_oop + g_hotspot.primitive_array_base_offsets[").append(elemKind).append("] + ((jlong)idx2 * g_hotspot.primitive_array_index_scales[").append(elemKind).append("]);\n")
            .append("                    return *(").append(cType).append("*)addr;\n")
            .append("                }\n")
            .append("            }\n")
            .append("        }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] AALOAD+").append(prefix).append("ALOAD direct path unavailable outer=%p idx1=%d idx2=%d\\n\", (void*)outer, (int)idx1, (int)idx2); abort();\n")
            .append("}\n\n");
    }

    private void appendPrimitiveFieldHelpers(StringBuilder sb, char desc, String cType, String wrapperStem) {
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_get_").append(desc)
            .append("_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, const char *owner, const char *name) {\n")
            .append("    (void)env; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(obj);\n")
            .append("        if (oop != NULL) return *(").append(cType).append("*)(oop + offset);\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive instance field direct metadata %s.%s kind=").append(desc).append(" offset=%lld obj=%p\\n\", owner, name, (long long)offset, (void*)obj); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_set_").append(desc)
            .append("_field(JNIEnv *env, jobject obj, jfieldID fid, jlong offset, ").append(cType).append(" value, const char *owner, const char *name) {\n")
            .append("    (void)env; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_handle_oop(obj);\n")
            .append("        if (oop != NULL) { *(").append(cType).append("*)(oop + offset) = value; return; }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive instance field direct metadata %s.%s kind=").append(desc).append(" offset=%lld obj=%p\\n\", owner, name, (long long)offset, (void*)obj); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_get_static_").append(desc)
            .append("_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset) {\n")
            .append("    (void)env; (void)cls; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_static_base_oop((jobject)cls);\n")
            .append("        if (oop == NULL) oop = (char*)neko_static_base_oop(staticBase);\n")
            .append("        if (oop != NULL) return *(").append(cType).append("*)(oop + offset);\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive static field direct metadata kind=").append(desc).append(" offset=%lld base=%p\\n\", (long long)offset, (void*)staticBase); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_set_static_").append(desc)
            .append("_field(JNIEnv *env, jclass cls, jfieldID fid, jobject staticBase, jlong offset, ").append(cType).append(" value) {\n")
            .append("    (void)env; (void)cls; (void)fid;\n")
            .append("    if (g_hotspot.initialized && (g_hotspot.fast_bits & NEKO_FAST_PRIM_FIELD) != 0 && offset > 0) {\n")
            .append("        char *oop = (char*)neko_static_base_oop((jobject)cls);\n")
            .append("        if (oop == NULL) oop = (char*)neko_static_base_oop(staticBase);\n")
            .append("        if (oop != NULL) { *(").append(cType).append("*)(oop + offset) = value; return; }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] missing primitive static field direct metadata kind=").append(desc).append(" offset=%lld base=%p\\n\", (long long)offset, (void*)staticBase); abort();\n")
            .append("}\n\n");
    }

    private void appendPrimitiveArrayHelpers(StringBuilder sb, String prefix, String cType, String wrapperStem, String kindConstant) {
        /* Read the length DIRECTLY from the array oop's length field rather
         * than calling JNI's GetArrayLength. Length lives at oop + base - 4
         * (where base = elements offset, e.g. 16 for header(12)+length(4) on
         * compressed-klass JDK 21). The JNI route was the dominant cost in
         * tight inner loops (matrix multiply: ~14M GetArrayLength calls). */
        sb.append("NEKO_FAST_INLINE ").append(cType).append(" neko_fast_").append(prefix)
            .append("aload(JNIEnv *env, jarray arr, jint idx) {\n")
            .append("    (void)env;\n")
            .append("    if (g_hotspot.initialized && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc) && arr != NULL) {\n")
            .append("        char *oop = (char*)neko_handle_oop((jobject)arr);\n")
            .append("        if (oop != NULL) {\n")
            .append("            jint arrayLen = *(jint*)(oop + g_hotspot.primitive_array_base_offsets[").append(kindConstant).append("] - 4);\n")
            .append("            if (idx >= 0 && idx < arrayLen) {\n")
            .append("                char *addr = oop + g_hotspot.primitive_array_base_offsets[").append(kindConstant).append("] + ((jlong)idx * g_hotspot.primitive_array_index_scales[").append(kindConstant).append("]);\n")
            .append("                return *(").append(cType).append("*)addr;\n")
            .append("            }\n")
            .append("        }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] ").append(prefix).append("ALOAD direct path unavailable arr=%p idx=%d\\n\", (void*)arr, (int)idx); abort();\n")
            .append("}\n\n")
            .append("NEKO_FAST_INLINE void neko_fast_").append(prefix)
            .append("astore(JNIEnv *env, jarray arr, jint idx, ").append(cType).append(" value) {\n")
            .append("    (void)env;\n")
            .append("    if (g_hotspot.initialized && ((g_hotspot.fast_bits & NEKO_FAST_PRIM_ARRAY) != 0 || g_hotspot.use_zgc) && arr != NULL) {\n")
            .append("        char *oop = (char*)neko_handle_oop((jobject)arr);\n")
            .append("        if (oop != NULL) {\n")
            .append("            jint arrayLen = *(jint*)(oop + g_hotspot.primitive_array_base_offsets[").append(kindConstant).append("] - 4);\n")
            .append("            if (idx >= 0 && idx < arrayLen) {\n")
            .append("                char *addr = oop + g_hotspot.primitive_array_base_offsets[").append(kindConstant).append("] + ((jlong)idx * g_hotspot.primitive_array_index_scales[").append(kindConstant).append("]);\n")
            .append("                *(").append(cType).append("*)addr = value;\n")
            .append("                return;\n")
            .append("            }\n")
            .append("        }\n")
            .append("    }\n")
            .append("    fprintf(stderr, \"[neko-direct] ").append(prefix).append("ASTORE direct path unavailable arr=%p idx=%d\\n\", (void*)arr, (int)idx); abort();\n")
            .append("}\n\n");
    }

    private String cTypeForArray(String prefix) {
        return switch (prefix) {
            case "z" -> "jbooleanArray";
            case "b" -> "jbyteArray";
            case "c" -> "jcharArray";
            case "s" -> "jshortArray";
            case "i" -> "jintArray";
            case "l" -> "jlongArray";
            case "f" -> "jfloatArray";
            case "d" -> "jdoubleArray";
            default -> "jarray";
        };
    }

    private String jniType(Type type) {
        return switch (type.getSort()) {
            case Type.VOID -> "void";
            case Type.BOOLEAN -> "jboolean";
            case Type.CHAR -> "jchar";
            case Type.BYTE -> "jbyte";
            case Type.SHORT -> "jshort";
            case Type.INT -> "jint";
            case Type.FLOAT -> "jfloat";
            case Type.LONG -> "jlong";
            case Type.DOUBLE -> "jdouble";
            case Type.ARRAY -> "jarray";
            default -> "jobject";
        };
    }

    private String c(String s) {
        return s.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
            .replace("\b", "\\b")
            .replace("\f", "\\f");
    }

    private String renderParam(CVariable variable) {
        if ("env".equals(variable.name())) {
            return "JNIEnv *env";
        }
        if ("thread".equals(variable.name())) {
            return "void *thread";
        }
        return variable.declaration();
    }

    private record MethodRef(String owner, String name, String desc, boolean isStatic) {}

    private record FieldRef(String owner, String name, String desc, boolean isStatic) {}

    private record StringRef(String cacheVar, String value) {}

        private String jvalueAccessor(Type type) {
        return switch (type.getSort()) {
            case Type.BOOLEAN -> ".z";
            case Type.BYTE -> ".b";
            case Type.CHAR -> ".c";
            case Type.SHORT -> ".s";
            case Type.INT -> ".i";
            case Type.FLOAT -> ".f";
            case Type.LONG -> ".j";
            case Type.DOUBLE -> ".d";
            default -> ".l";
        };
    }

    private record IcacheSiteRef(int ownerId, int methodId, int siteIndex, String bindingOwner, String methodKey) {
        private String symbol() {
            return "neko_icache_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private record IcacheDirectStubRef(
        int ownerId,
        int methodId,
        int siteIndex,
        NativeMethodBinding binding,
        Type[] args,
        Type returnType
    ) {
        private String symbol() {
            return "neko_icache_stub_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private record IcacheMetaRef(
        int ownerId,
        int methodId,
        int siteIndex,
        String name,
        String desc,
        boolean isInterface,
        String translatedClassSlot,
        String translatedStubSymbol,
        String directDispatcherSymbol
    ) {
        private String symbol() {
            return "neko_icache_meta_" + ownerId + '_' + methodId + '_' + siteIndex;
        }
    }

    private static final class OwnerResolution {
        private final Set<String> classes = new LinkedHashSet<>();
        private final Set<MethodRef> methods = new LinkedHashSet<>();
        private final Set<FieldRef> fields = new LinkedHashSet<>();
        private final Set<StringRef> strings = new LinkedHashSet<>();
    }
}
