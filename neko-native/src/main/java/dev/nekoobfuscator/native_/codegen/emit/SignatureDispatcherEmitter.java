package dev.nekoobfuscator.native_.codegen.emit;

/**
 * Emits one C dispatcher per unique {@link SignaturePlan.Shape}.
 *
 * The dispatcher is the direct-C bridge between the per-arch trampoline
 * (which delivers raw interpreter args) and the generated raw translated
 * body. The hot path is structured so the only JNI calls per invocation
 * are {@code PushLocalFrame} / {@code PopLocalFrame} — both required for
 * GC-safe handle-block bookkeeping when the translated body internally
 * uses JNI to do its work. Everything else is direct VMStruct memory ops:
 *
 * <ul>
 *   <li>{@code JNIEnv*} comes from {@code JavaThread::_jni_environment}
 *       (direct field read, no {@code GetEnv}/{@code AttachCurrentThread}).</li>
 *   <li>Per-call ref tracking uses {@code neko_handle_save / neko_handle_push /
 *       neko_handle_restore}, which write directly into
 *       {@code JavaThread::_active_handles->_top}/{@code _handles}.</li>
 *   <li>Pending exception detection reads
 *       {@code JavaThread::_pending_exception} directly when VMStructs has
 *       published the offset, replacing {@code (*env)->ExceptionCheck}.</li>
 *   <li>For static methods, {@code jclass owner} is taken from the
 *       per-binding {@code owner_class_global_ref} cached at
 *       {@code JNI_OnLoad} time — no {@code FindClass} per call (the
 *       biggest single perf win, since {@code jni_FindClass} traverses
 *       the loader hierarchy and acquires class-loading locks).</li>
 *   <li>For reference returns, the raw oop is recovered via a tag-aware
 *       deref — JNI globals carry low-bit tags (0x1 weak, 0x2 strong)
 *       that must be masked before dereferencing the slot, mirroring
 *       what {@code JNIHandles::resolve} would do internally.</li>
 * </ul>
 *
 * The PushLocalFrame / PopLocalFrame pair could not be simply replaced
 * by raw {@code _top} save/restore: the impl_fn's own JNI calls may
 * extend the handle-block chain ({@code _next}-linked) when filling
 * past 32 slots, and only PopLocalFrame walks the chain back. Resetting
 * just the saved block's {@code _top} leaks roots into chained blocks
 * and triggers GC stalls / SEGVs on long test runs (validated on
 * obfusjack inheritance tests).
 *
 * Constraints documented in {@code todo.md} / {@code problems.md}: no
 * JVMTI; the trampoline-to-impl_fn boundary keeps JNI usage to the two
 * frame-management calls plus what the translated body itself emits.
 */
public final class SignatureDispatcherEmitter {

    public String render(SignaturePlan plan) {
        if (plan.shapes().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Per-signature direct-C dispatchers === */\n");
        for (int sigId = 0; sigId < plan.shapes().size(); sigId++) {
            sb.append(renderOne(sigId, plan.shapes().get(sigId)));
        }
        return sb.toString();
    }

    private String renderOne(int sigId, SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        String retC = SignaturePlan.cAbiType(ret);
        String retJ = SignaturePlan.jniArgType(ret);
        boolean isStatic = shape.isStatic();

        // typedef for impl_fn signature (matches the generated raw body)
        sb.append("typedef ").append(retJ)
            .append(" (*neko_sig_").append(sigId).append("_impl_t)(void*, JNIEnv*, ")
            .append(isStatic ? "jclass" : "jobject");
        for (char a : args) sb.append(", ").append(SignaturePlan.jniArgType(a));
        sb.append(");\n");

        // dispatcher — hidden visibility (not static) so the naked-asm trampoline can call it.
        // The trampoline passes the JavaThread as the second arg (after entry)
        // so we can push ref args to the active handle block (GC tracking).
        sb.append("__attribute__((visibility(\"hidden\"))) ").append(retC)
            .append(" neko_sig_").append(sigId).append("_dispatch(NekoManifestMethod *entry, void *thread");
        if (!isStatic) sb.append(", void *raw_recv_slot");
        for (int i = 0; i < args.length; i++) {
            sb.append(", ").append(SignaturePlan.cAbiType(args[i])).append(" a").append(i);
        }
        sb.append(") {\n");
        // JNIEnv* via direct field read on JavaThread (falls back to GetEnv
        // only if VMStructs did not expose the offset — see neko_thread_jni_env).
        sb.append("    JNIEnv *env = neko_thread_jni_env(thread);\n");
        sb.append("    if (env == NULL) ").append(returnZero(ret)).append(";\n");
        sb.append("    NEKO_PATCH_LOG(\"sig").append(sigId).append(" enter %s.%s%s\", entry->owner_internal, entry->method_name, entry->method_desc);\n");
        sb.append("    if ((*env)->PushLocalFrame(env, 16) != 0) ").append(returnZero(ret)).append(";\n");

        // Save the active handle block top for receiver/ref args we push
        // ourselves (translates raw oops the trampoline gave us into
        // jobject slots). PushLocalFrame above bounds the impl_fn's own
        // local-ref allocations; this save/restore is the bookend for the
        // refs we push BEFORE PushLocalFrame's frame is unwound.
        sb.append("    neko_handle_save_t __hsave;\n");
        sb.append("    neko_handle_save(thread, &__hsave);\n");

        if (isStatic) {
            // Cached global ref captured at JNI_OnLoad (NewGlobalRef on
            // FindClass). No per-call FindClass.
            sb.append("    jclass owner_cls = (jclass)entry->owner_class_global_ref;\n");
        } else {
            sb.append("    void *__recv_oop = raw_recv_slot != NULL ? *(void**)raw_recv_slot : NULL;\n");
            sb.append("    jobject self = (jobject)neko_handle_push(thread, __recv_oop);\n");
        }
        for (int i = 0; i < args.length; i++) {
            if (args[i] == 'L') {
                sb.append("    void *__a").append(i).append("_oop = a").append(i)
                    .append(" != NULL ? *(void**)a").append(i).append(" : NULL;\n");
                sb.append("    jobject ja").append(i).append(" = (jobject)neko_handle_push(thread, __a")
                    .append(i).append("_oop);\n");
            }
        }

        // call
        if (ret == 'V') {
            sb.append("    ");
        } else if (ret == 'L') {
            sb.append("    jobject __ret = ");
        } else {
            sb.append("    ").append(retJ).append(" __ret = ");
        }
        sb.append("((neko_sig_").append(sigId).append("_impl_t)entry->impl_fn)(thread, env, ")
            .append(isStatic ? "owner_cls" : "self");
        for (int i = 0; i < args.length; i++) {
            sb.append(", ");
            switch (args[i]) {
                case 'L' -> sb.append("ja").append(i);
                case 'F' -> sb.append("(jfloat)a").append(i);
                case 'D' -> sb.append("(jdouble)a").append(i);
                case 'J' -> sb.append("(jlong)a").append(i);
                default -> sb.append("(jint)a").append(i);
            }
        }
        sb.append(");\n");

        // Direct read of JavaThread::_pending_exception when VMStructs
        // published the offset; otherwise fall through to JNI ExceptionCheck.
        // Either way, on a pending exception we PopLocalFrame(NULL) and
        // restore the saved _top before returning zero.
        sb.append("    int __pending = (g_neko_off_thread_pending_exception > 0)\n");
        sb.append("        ? (*(void**)((char*)thread + g_neko_off_thread_pending_exception) != NULL)\n");
        sb.append("        : ((*env)->ExceptionCheck(env) ? 1 : 0);\n");
        sb.append("    if (__pending) {\n");
        sb.append("        (void)(*env)->PopLocalFrame(env, NULL);\n");
        sb.append("        neko_handle_restore(&__hsave);\n");
        if (ret == 'V') sb.append("        return;\n");
        else if (ret == 'L') sb.append("        return NULL;\n");
        else sb.append("        return (").append(retC).append(")0;\n");
        sb.append("    }\n");

        // return + restore
        if (ret == 'V') {
            sb.append("    (void)(*env)->PopLocalFrame(env, NULL);\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    return;\n");
        } else if (ret == 'L') {
            // PopLocalFrame relocates __ret into the parent frame and
            // returns the new jobject. JNI globals (e.g. cached bound
            // strings, owner_class_global_ref escaping out via reflection)
            // would carry tag bits; mask before deref. For local refs
            // the bits are zero so the mask is a no-op.
            sb.append("    jobject __surviving = (*env)->PopLocalFrame(env, __ret);\n");
            sb.append("    void *__raw_ret = NULL;\n");
            sb.append("    if (__surviving != NULL) {\n");
            sb.append("        uintptr_t __slot = (uintptr_t)__surviving & ~(uintptr_t)0x3;\n");
            sb.append("        __raw_ret = *(void**)__slot;\n");
            sb.append("    }\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    return __raw_ret;\n");
        } else {
            sb.append("    (void)(*env)->PopLocalFrame(env, NULL);\n");
            sb.append("    neko_handle_restore(&__hsave);\n");
            sb.append("    return (").append(retC).append(")__ret;\n");
        }
        sb.append("}\n\n");
        return sb.toString();
    }

    private String returnZero(char ret) {
        return ret == 'V' ? "{ return; }" : "{ return (" + SignaturePlan.cAbiType(ret) + ")0; }";
    }
}
