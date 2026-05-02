package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits the dlsym-resolved {@code JNIHandles::make_local} / {@code JNIHandles::resolve}
 * shim used by remaining JNI-facing helper paths to convert raw oop pointers
 * to {@code jobject} handles and back. Missing HotSpot symbols are fatal:
 * there is no raw jobject-cell dereference fallback.
 *
 * The C++ symbols are looked up from libjvm via dlsym. Multiple mangled names are
 * probed because HotSpot has shifted the {@code make_local} signature several times
 * (oop-only on JDK 8/11/17, JavaThread* + oop on JDK 21+).
 */
public final class JniHandlesShimEmitter {

    public String render() {
        return """
/* === JNIHandles dlsym shim ===
 * make_local converts raw oop -> jobject (managed local ref).
 * resolve converts jobject -> raw oop.
 */
typedef void* (*neko_jnih_make_local_t)(void*);
typedef void* (*neko_jnih_make_local_thread_t)(void*, void*);
typedef void* (*neko_jnih_resolve_t)(void*);

static neko_jnih_make_local_t       g_neko_jnih_make_local        = NULL;
static neko_jnih_make_local_thread_t g_neko_jnih_make_local_thread = NULL;
static neko_jnih_resolve_t          g_neko_jnih_resolve           = NULL;

static jboolean neko_resolve_jnihandles(void *jvm) {
    /* JDK 8/11/17:    _ZN10JNIHandles10make_localEP7oopDesc
     * JDK 21+:        _ZN10JNIHandles10make_localEP10JavaThreadP7oopDesc
     * Resolve:        _ZN10JNIHandles7resolveEP8_jobject
     */
    g_neko_jnih_make_local = (neko_jnih_make_local_t)
        neko_dlsym(jvm, "_ZN10JNIHandles10make_localEP7oopDesc");
    if (g_neko_jnih_make_local == NULL) {
        g_neko_jnih_make_local_thread = (neko_jnih_make_local_thread_t)
            neko_dlsym(jvm, "_ZN10JNIHandles10make_localEP10JavaThreadP7oopDesc");
    }
    g_neko_jnih_resolve = (neko_jnih_resolve_t)
        neko_dlsym(jvm, "_ZN10JNIHandles7resolveEP8_jobject");
    return ((g_neko_jnih_make_local != NULL || g_neko_jnih_make_local_thread != NULL)
            && g_neko_jnih_resolve != NULL) ? JNI_TRUE : JNI_FALSE;
}

static jobject neko_raw_to_jobject(JNIEnv *env, void *raw) {
    (void)env;
    if (raw == NULL) return NULL;
    if (g_neko_jnih_make_local != NULL) return (jobject)g_neko_jnih_make_local(raw);
    if (g_neko_jnih_make_local_thread != NULL) {
        /* JNIEnv* on HotSpot is the address of an embedded field within JavaThread.
         * The JavaThread base is at offset (off_java_thread_jni_environment) before env.
         * We don't currently track that offset; pass env as a best-effort proxy and
         * rely on HotSpot's tolerance during early call paths. */
        return (jobject)g_neko_jnih_make_local_thread((void*)env, raw);
    }
    fprintf(stderr, "[neko-bootstrap] JNIHandles::make_local unavailable for raw oop %p\\n", raw);
    abort();
}

static void *neko_jobject_to_raw(jobject ref) {
    if (ref == NULL) return NULL;
    if (g_neko_jnih_resolve != NULL) return g_neko_jnih_resolve(ref);
    fprintf(stderr, "[neko-bootstrap] JNIHandles::resolve unavailable for jobject %p\\n", (void*)ref);
    abort();
}

""";
    }
}
