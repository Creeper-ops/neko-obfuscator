package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits {@code JNI_OnLoad}. The Java runtime loader only calls
 * {@code System.load}; all HotSpot probing and manifest discovery happen from
 * native initialization and no Java bootstrap method is registered or exported.
 */
public final class JniOnLoadEmitter {

    public String renderRegistrationTable() {
        StringBuilder sb = new StringBuilder();
        sb.append("/* === No Java native bridge: NekoNativeLoader only calls System.load === */\n\n");
        return sb.toString();
    }

    public String renderJniOnLoadAndBootstrap() {
        return """
__attribute__((visibility("hidden"))) extern void *g_neko_jni_onload_thread_reg;
__attribute__((visibility("hidden"))) extern void *g_neko_jni_functions_table;

JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM *vm, void *reserved) {
    /* Capture HotSpot's thread register IMMEDIATELY, before any compiler-
     * generated prologue / register reuse. r15 (x86_64) / x28 (AArch64)
     * holds the current JavaThread* across the JNI call into us. We use
     * this snapshot at neko_method_layout_init time to derive the
     * _jni_environment offset (which VMStructs does not expose unless
     * JVMCI is on). */
#if defined(__x86_64__)
    __asm__ volatile ("movq %%r15, %0" : "=m"(g_neko_jni_onload_thread_reg));
#elif defined(__aarch64__)
    __asm__ volatile ("str x28, %0" : "=m"(g_neko_jni_onload_thread_reg));
#endif
    JNIEnv *env = NULL;
    (void)reserved;
    g_neko_java_vm = vm;
    if ((*vm)->GetEnv(vm, (void**)&env, JNI_VERSION_1_6) != JNI_OK) return JNI_ERR;
    if (env == NULL) return JNI_ERR;
    g_neko_jni_functions_table = *(void**)env;
    if (g_neko_jni_functions_table == NULL) return JNI_ERR;
    if (!neko_method_layout_init(env)) {
        fprintf(stderr, "[neko-bootstrap] native layout initialization failed\\n");
        abort();
    }
    neko_hotspot_init(env);
    neko_refresh_hotspot_vmstruct_state();
    /* T4.1: populate the descriptor → primitive-mirror table. Must run AFTER
     * neko_hotspot_init (which publishes compressed-oops shift/base into
     * g_hotspot) and AFTER neko_method_layout_init (called above; publishes
     * Klass::_java_mirror offset and the native-resolution capability bit
     * neko_resolve_class / neko_resolve_field both rely on). Failure to
     * resolve any of the eight wrapper InstanceKlasses or their TYPE static
     * field aborts inside the helper — there is no fallback path back into
     * neko_find_class / neko_get_static_field_id. */
    neko_primitive_mirror_table_init(env);
    neko_boxing_cache_init(env);
    neko_bootstrap_owner_discovery(env);
    return JNI_VERSION_1_6;
}

static void neko_bootstrap_owner_discovery(JNIEnv *env) {
    if (env == NULL) return;
    if (!neko_manifest_discover_and_patch(env)) abort();
}

""";
    }
}
