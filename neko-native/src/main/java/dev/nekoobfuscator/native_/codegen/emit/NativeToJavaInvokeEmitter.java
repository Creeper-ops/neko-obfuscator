package dev.nekoobfuscator.native_.codegen.emit;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Emits per-shape native→Java direct-call dispatchers. JDK 21+ doesn't
 * publish {@code StubRoutines::_call_stub_entry} via VMStructs and we don't
 * want to dlsym mangled C++ symbols from libjvm — so we maintain HotSpot's
 * own Java calling convention ourselves and jump straight to
 * {@code Method::_from_compiled_entry}.
 *
 * <h2>HotSpot's compiled-Java calling convention (x86-64 SysV)</h2>
 *
 * Reference: hotspot/cpu/x86/sharedRuntime_x86_64.cpp
 * (SharedRuntime::java_calling_convention) and the j_rarg* register file
 * in hotspot/cpu/x86/register_x86.hpp.
 *
 * <ul>
 *   <li>{@code %rbx} = {@code Method*} (HotSpot's "method receiver" register)</li>
 *   <li>GP arg slots in order: {@code j_rarg0..j_rarg5} =
 *       {@code rsi, rdx, rcx, r8, r9, rdi}.
 *       Note j_rarg5 is {@code rdi} (swapped vs the C ABI's c_rarg0=rdi).
 *       For instance methods the receiver is {@code j_rarg0} = rsi.</li>
 *   <li>FP arg slots: {@code xmm0..xmm7} (same as SysV C).</li>
 *   <li>Args beyond the register set spill to the Java stack at
 *       {@code [rsp + i*8]} (caller pre-call rsp; the call instruction
 *       pushes its own return PC).</li>
 *   <li>{@code %r15} = {@code JavaThread*} (preserved across all Java
 *       calling-convention crossings).</li>
 *   <li>Return regs: {@code %rax} for int/long/object/ref, {@code %xmm0}
 *       for float/double.</li>
 * </ul>
 *
 * <h2>State-transition contract</h2>
 *
 * On entry the JVM thread is in {@code _thread_in_native} (we are inside a
 * JNI native body). The naked trampoline:
 *
 * <ol>
 *   <li>Publishes the JavaFrameAnchor ({@code _last_Java_sp/fp/pc}) so GC
 *       can walk the caller's frame.</li>
 *   <li>Transitions {@code _thread_in_native} → {@code _thread_in_native_trans}
 *       (mfence) → polling-word check → {@code _thread_in_Java}.</li>
 *   <li>Loads args into the Java calling convention registers + Java stack.</li>
 *   <li>{@code call *<entry>} (the Method's {@code _from_compiled_entry}).</li>
 *   <li>On return transitions back: {@code _thread_in_Java} →
 *       {@code _thread_in_native_trans} → poll → {@code _thread_in_native},
 *       clears the anchor, returns the result via rax/xmm0 packed into
 *       {@code out_rax} / {@code out_xmm0}.</li>
 * </ol>
 *
 * <h2>Object-arg / return marshalling</h2>
 *
 * HotSpot compiled entries take and return raw oops, not JNI handles. The
 * C dispatcher recovers raw oops from {@code jobject} args via the standard
 * tag-mask + deref. Returned raw oops go through
 * {@code neko_direct_oop_to_handle} into the active JNIHandleBlock so the
 * caller sees a normal {@code jobject}.
 *
 * <h2>Architecture coverage</h2>
 *
 * x86-64 SysV (Linux + macOS) implemented. x86-64 Windows + AArch64 SysV
 * emit per-shape stubs that abort with a clear diagnostic until their
 * backends land. The runtime guard {@code g_neko_direct_invoke_ready}
 * ensures call sites only enter the dispatcher when the layout walker has
 * resolved every required offset.
 */
public final class NativeToJavaInvokeEmitter {

    private final LinkedHashMap<String, SignaturePlan.Shape> shapes = new LinkedHashMap<>();

    public NativeToJavaInvokeEmitter() {}

    public static String shapeKey(SignaturePlan.Shape shape) {
        StringBuilder sb = new StringBuilder();
        sb.append(shape.isStatic() ? 'S' : 'V').append(':');
        sb.append(shape.returnKind()).append(':');
        for (char a : shape.argKinds()) sb.append(a);
        return sb.toString();
    }

    public String register(SignaturePlan.Shape shape) {
        String key = shapeKey(shape);
        shapes.putIfAbsent(key, shape);
        return dispatcherSymbol(key);
    }

    public boolean isEmpty() { return shapes.isEmpty(); }
    public Set<String> shapeKeys() { return new LinkedHashSet<>(shapes.keySet()); }
    public List<SignaturePlan.Shape> registeredShapes() { return List.copyOf(shapes.values()); }
    public boolean hasShape(SignaturePlan.Shape shape) { return shapes.containsKey(shapeKey(shape)); }

    public static String dispatcherSymbol(String key) {
        return "neko_njx_" + key.replace(':', '_');
    }
    public static String trampolineSymbol(String key) {
        return "neko_njx_tramp_" + key.replace(':', '_');
    }

    public String renderPrelude() {
        if (shapes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Native→Java direct invoke (forward decls) === */\n");
        sb.append("typedef jvalue (*neko_njx_dispatcher_t)(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args);\n");
        /* Forward declare the priv-heap allocator from MethodPatcherEmitter. */
        sb.append("static void *neko_priv_alloc_njx_wrapper(void *real_tramp);\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            sb.append("static jvalue ").append(dispatcherSymbol(e.getKey()))
              .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args);\n");
            /* Per-shape wrapper PC, populated at OnLoad time. NULL until
             * priv heap is set up; falls back to libneko trampoline directly
             * when NULL (which is fine for first calls before init). */
            sb.append("static void *").append(wrapperGlobalName(e.getKey())).append(" = NULL;\n");
        }
        sb.append('\n');
        return sb.toString();
    }

    public static String wrapperGlobalName(String key) {
        return "g_njx_wrapper_" + key.replace(':', '_');
    }

    /** Render a function that the JNI_OnLoad bootstrap invokes once the
     * priv heap is registered. It allocates priv-heap wrappers for every
     * registered shape so subsequent dispatches go through CodeBlob-
     * recognized frames. */
    public String renderInitFunction() {
        if (shapes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("static void neko_njx_init_wrappers(void) {\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            String key = e.getKey();
            sb.append("    ").append(wrapperGlobalName(key))
              .append(" = neko_priv_alloc_njx_wrapper((void*)&").append(trampolineSymbol(key)).append(");\n");
        }
        sb.append("    NEKO_DIRECT_LOG(\"njx wrappers initialized: ").append(shapes.size()).append(" shapes\");\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    public String renderBodies() {
        if (shapes.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        sb.append("/* === Native→Java direct invoke implementations === */\n");
        sb.append(renderHelpers());
        /* Per-shape naked trampolines + C dispatchers; per-arch sections. */
        sb.append("#if defined(__x86_64__) && (defined(__linux__) || defined(__APPLE__))\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            sb.append(renderShapeX86_64SysV(e.getKey(), e.getValue()));
        }
        sb.append("#else\n");
        for (Map.Entry<String, SignaturePlan.Shape> e : shapes.entrySet()) {
            sb.append(renderShapeUnsupported(e.getKey(), e.getValue()));
        }
        sb.append("#endif\n\n");
        return sb.toString();
    }

    private String renderHelpers() {
        return """
/* Convert a jobject handle to its raw oop. JNI handles carry low-bit tags
 * (1=weak, 2=strong); local-frame handles are untagged. Untag, deref. */
NEKO_FAST_INLINE void *neko_njx_handle_to_oop(jobject handle) {
    if (handle == NULL) return NULL;
    uintptr_t raw = (uintptr_t)handle;
    return *(void**)(raw & ~(uintptr_t)0x3u);
}

/* Wrap a raw oop returned from compiled Java back into a jobject via the
 * active JNIHandleBlock. Reuses the AALOAD fast path's helper. */
NEKO_FAST_INLINE jobject neko_njx_oop_to_handle(void *thread, void *oop) {
    if (oop == NULL) return NULL;
    return neko_direct_oop_to_handle(thread, oop);
}

/* Resolve Method* + _from_compiled_entry from a runtime jmethodID. The
 * Method* is *(Method**)mid (HotSpot stores jmethodIDs as indirection
 * cells); the entry pointer is at the published VMStructs offset. */
static int neko_njx_resolve_entry(jmethodID mid, void **out_method, void **out_entry) {
    if (mid == NULL || out_method == NULL || out_entry == NULL) return 0;
    if (!g_neko_method_layout.initialized || !g_neko_method_layout.usable) {
        NEKO_DIRECT_LOG("resolve_entry: layout not ready (init=%d usable=%d)",
            (int)g_neko_method_layout.initialized, (int)g_neko_method_layout.usable);
        return 0;
    }
    if (g_neko_method_layout.off_method_from_compiled_entry <= 0) {
        NEKO_DIRECT_LOG("resolve_entry: compiled-entry offset unresolved");
        return 0;
    }
    void *m = *(void**)mid;
    if (m == NULL) {
        NEKO_DIRECT_LOG("resolve_entry: jmethodID %p deref to NULL Method*", mid);
        return 0;
    }
    void *e = *(void**)((char*)m + g_neko_method_layout.off_method_from_compiled_entry);
    if (e == NULL) {
        NEKO_DIRECT_LOG("resolve_entry: Method* %p has NULL _from_compiled_entry", m);
        return 0;
    }
    *out_method = m;
    *out_entry  = e;
    return 1;
}

""";
    }

    /* ----------------------------------------------------------------
     * x86-64 SysV: per-shape dispatcher + naked trampoline.
     *
     * The naked trampoline takes a C-ABI signature with primitives passed
     * as int64_t/double, plus arrays for stack-bound args:
     *
     *   void neko_njx_tramp_<S>(
     *       void *thread,           // JavaThread* (rdi)
     *       void *method_ptr,       // Method* — goes to %rbx (rsi)
     *       void *entry_point,      // _from_compiled_entry — call target (rdx)
     *       const int64_t *gp_args, // values for j_rarg0..N (rcx)
     *       const double  *fp_args, // values for xmm0..N (r8)
     *       const int64_t *stack_args, int n_stack_args, // (r9, [rbp+16])
     *       int64_t *out_rax,       // [rbp+24]
     *       double  *out_xmm0       // [rbp+32]
     *   );
     *
     * The C dispatcher composes those arrays from the jvalue[] args and
     * calls the trampoline. Per-shape because the ARG COUNT into the
     * Java calling convention is fixed at compile time — but the ORDER
     * of register loads is shape-independent (j_rarg0 = gp_args[0], etc).
     * ---------------------------------------------------------------- */
    private String renderShapeX86_64SysV(String key, SignaturePlan.Shape shape) {
        char ret = shape.returnKind();
        char[] args = shape.argKinds();
        boolean isStatic = shape.isStatic();
        String fn = dispatcherSymbol(key);
        String tramp = trampolineSymbol(key);

        /* Java calling convention split:
         *   - receiver (if instance) goes to j_rarg0 first
         *   - then args in order: int/object/long → next j_rarg or stack;
         *     float/double → next xmm or stack
         *   - long/double take 1 slot in regs but 2 slots on stack
         *     (we compress to 1 slot here since both ABI lanes pass them
         *     in 8-byte slots)
         */
        int gpCount = isStatic ? 0 : 1;          // total GP args (receiver counts)
        int xmmCount = 0;
        int stackArgs = 0;
        int[] argLoc = new int[args.length];      // 0=GP, 1=XMM, 2=stack
        int[] argRegIdx = new int[args.length];
        int[] argStackIdx = new int[args.length];
        for (int i = 0; i < args.length; i++) {
            char a = args[i];
            if (a == 'F' || a == 'D') {
                if (xmmCount < 8) { argLoc[i] = 1; argRegIdx[i] = xmmCount++; }
                else              { argLoc[i] = 2; argStackIdx[i] = stackArgs++; }
            } else {
                if (gpCount < 6)  { argLoc[i] = 0; argRegIdx[i] = gpCount++; }
                else              { argLoc[i] = 2; argStackIdx[i] = stackArgs++; }
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("/* shape ").append(key).append(" — ");
        sb.append(isStatic ? "static" : "instance").append(" args=\"");
        for (char a : args) sb.append(a);
        sb.append("\" ret=").append(ret).append(" gp=").append(gpCount).append(" xmm=").append(xmmCount)
          .append(" stk=").append(stackArgs).append(" (x86_64 SysV) */\n");

        /* ---- Naked trampoline ---- */
        sb.append("__attribute__((naked, used, visibility(\"hidden\")))\n");
        sb.append("static void ").append(tramp)
          .append("(void *thread, void *method_ptr, void *entry_point,\n")
          .append("             const int64_t *gp_args, const double *fp_args,\n")
          .append("             const int64_t *stack_args, int n_stack_args,\n")
          .append("             int64_t *out_rax, double *out_xmm0) {\n");
        sb.append("    __asm__ volatile (\n");
        /* SysV C ABI on entry to this naked function:
         *   rdi=thread, rsi=method_ptr, rdx=entry_point,
         *   rcx=gp_args, r8=fp_args, r9=stack_args,
         *   [rbp+16]=n_stack_args (after pushq rbp; mov rsp,rbp),
         *   [rbp+24]=out_rax, [rbp+32]=out_xmm0
         *
         * CRITICAL: HotSpot uses %r12 as r12_heapbase when compressed oops
         * are enabled. Compiled Java code does narrow-oop decode as
         * `oop = r12 + (narrow << shift)`. Clobbering %r12 with anything
         * other than the heap base will cause every narrow-oop deref to
         * land at a bogus address (crash). HotSpot also uses %r15 as the
         * thread register (j_thread_reg).
         *
         * We MUST preserve %r12 = heap_base across the call (HotSpot set it
         * up before our JNI native body was entered; we just don't disturb
         * it). We use %r15 for both thread-pointer storage AND the "thread
         * register" HotSpot expects. */
        sb.append("        \"pushq %%rbp\\n\"\n");
        sb.append("        \"movq  %%rsp, %%rbp\\n\"\n");
        /* Save callee-saved regs we'll clobber: rbx, r12-r15. We DO save
         * r12 because we'll set it to the heap base before calling Java
         * (HotSpot's r12_heapbase convention). The pop in the epilogue
         * restores the original r12 our C caller had. */
        sb.append("        \"pushq %%rbx\\n\"\n");
        sb.append("        \"pushq %%r12\\n\"\n");
        sb.append("        \"pushq %%r13\\n\"\n");
        sb.append("        \"pushq %%r14\\n\"\n");
        sb.append("        \"pushq %%r15\\n\"\n");
        /* Stash incoming args into callee-saved regs so they survive the
         * call:
         *   r15 = thread (HotSpot's thread register; also our scratch)
         *   r13 = entry_point
         *   r14 = out_rax
         *   rbx = method_ptr (HotSpot's method receiver register)
         * out_xmm0 we leave on stack — read it post-call.
         *
         * NOTE: We do NOT touch %r12. It already holds HotSpot's heap base
         * from when we entered our JNI native body. Compiled Java code we
         * call back into requires this. */
        sb.append("        \"movq  %%rdi, %%r15\\n\"     /* thread */\n");
        sb.append("        \"movq  %%rsi, %%rbx\\n\"     /* method_ptr -> rbx */\n");
        sb.append("        \"movq  %%rdx, %%r13\\n\"     /* entry */\n");
        sb.append("        \"movq  24(%%rbp), %%r14\\n\" /* out_rax */\n");
        /* Buffer the gp_args/fp_args/stack_args ptrs into temp regs that
         * we'll consume in arg load. We use r10 (gp), r11 (fp), rax (stack
         * source ptr). All are caller-save. */
        sb.append("        \"movq  %%rcx, %%r10\\n\"     /* gp_args ptr */\n");
        sb.append("        \"movq  %%r8,  %%r11\\n\"     /* fp_args ptr */\n");

        /* Push stack args FIRST (before we set up the call frame for
         * compiled entry). HotSpot's compiled callee reads stack args at
         * [rsp+8 + i*8] when its own prologue runs — i.e. after our
         * `call *r13` pushes the return PC. So at the moment of `call`,
         * rsp must point AT stk[0] (with the return PC about to be
         * pushed below it). Layout:
         *
         *   high addr
         *   ...
         *   [rbp+16]   n_stack_args (input arg slot)
         *   [rbp+8]    return PC of this naked
         *   [rbp+0]    saved rbp <- rbp
         *   [rbp-8]    saved rbx
         *   [rbp-16]   saved r12
         *   [rbp-24]   saved r13
         *   [rbp-32]   saved r14
         *   [rbp-40]   saved r15
         *   ... pad to 16 if odd # of stack args ...
         *   stk[N-1]
         *   ...
         *   stk[0]     <- rsp at the moment of `call *r13`
         *   <pushed return PC after call>
         *
         * So we pre-pad if stack arg count is odd, then push args in
         * reverse so stk[0] ends at the lowest rsp.
         */
        if (stackArgs > 0) {
            sb.append("        /* Pre-align: 5 callee-save pushes (rbx,r12-r15) + saved rbp = -40\n");
            sb.append("           from rbp. -40 mod 16 = -8 → 8-misaligned, so even-stackArgs\n");
            sb.append("           after pushes ends 8-misaligned (bad), odd ends 16-aligned. */\n");
            if ((stackArgs & 1) == 0) {
                sb.append("        \"subq  $8, %%rsp\\n\"\n");
            }
            /* Push stack args in reverse: stk[N-1] first, stk[0] last. */
            for (int i = stackArgs - 1; i >= 0; i--) {
                sb.append("        \"movq  ").append(i * 8).append("(%%r9), %%rax\\n\"\n");
                sb.append("        \"pushq %%rax\\n\"\n");
            }
        } else {
            /* No stack args. rsp = rbp - 40 ≡ -8 mod 16. Pad by 8 so rsp
             * is 16-aligned at the call site (compiled entry expectation). */
            sb.append("        \"subq  $8, %%rsp\\n\"\n");
        }

        /* Load FP args from fp_args[] into xmm0..xmm(xmmCount-1). */
        for (int i = 0; i < xmmCount; i++) {
            sb.append("        \"movsd ").append(i * 8).append("(%%r11), %%xmm").append(i).append("\\n\"\n");
        }

        /* Load GP args from gp_args[] into the JAVA arg regs.
         * j_rarg0..j_rarg5 = rsi, rdx, rcx, r8, r9, rdi.
         * NOTE: rsi is also a SysV C-arg reg (the original method_ptr lived
         * there but we already moved it to rbx). We must load rdi LAST
         * because rdi is also the gp_args source (no — we moved gp_args to
         * r10 via rcx, so rdi is free). Order is irrelevant for correctness
         * because all sources come from r10[*] which is preserved. */
        String[] javaGpRegs = { "rsi", "rdx", "rcx", "r8", "r9", "rdi" };
        for (int i = 0; i < gpCount; i++) {
            sb.append("        \"movq  ").append(i * 8).append("(%%r10), %%").append(javaGpRegs[i]).append("\\n\"\n");
        }

        /* Publish JavaFrameAnchor pointing at OUR trampoline frame. HotSpot's
         * stack walker, when traversing from compiled-callee back through our
         * trampoline (which is registered as a BufferBlob in the priv heap),
         * uses the anchor to find the boundary at which to fall back to
         * walking the C call chain. Anchor.last_Java_pc points at the
         * instruction right after our `call *r13` so the walker associates
         * us with that label.
         *
         * Caller-managed restoration: the C dispatcher around us SAVES the
         * prior anchor (set by HotSpot's outer JNI native_wrapper for the
         * translated body's caller) before invoking the trampoline and
         * RESTORES it after. */
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq %%rbp, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"leaq 4f(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%rcx\\n\"\n");
        sb.append("        \"movq %%rax, (%%r15, %%rcx)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq %%rsp, (%%r15, %%rax)\\n\"\n");

        /* State transition: _thread_in_native -> _thread_in_native_trans
         * (mfence) -> polling check -> _thread_in_Java. */
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%rax\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native_trans(%%rip), %%ecx\\n\"\n");
        sb.append("        \"movl %%ecx, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_polling_word(%%rip), %%rax\\n\"\n");
        sb.append("        \"testq %%rax, %%rax\\n\"\n");
        sb.append("        \"je   5f\\n\"\n");
        sb.append("        \"movq (%%r15, %%rax), %%rcx\\n\"\n");
        sb.append("        \"testq %%rcx, %%rcx\\n\"\n");
        sb.append("        \"je   5f\\n\"\n");
        /* skipping neko_handle_safepoint_poll for now — to be revisited */
        sb.append("        \"5:\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%rax\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_java(%%rip), %%ecx\\n\"\n");
        sb.append("        \"movl %%ecx, (%%r15, %%rax)\\n\"\n");

        /* Set %r12 to the heap base. HotSpot compiled code uses %r12 as
         * r12_heapbase for narrow-oop decode (oop = r12 + (narrow << shift)).
         * Zero-based compressed oops has heap_base = NULL → r12 = 0.
         *
         * We MUST do this even though %r12 is callee-save: HotSpot's
         * convention requires it for compiled methods to function. Our
         * caller restores their %r12 from our stack push when we return. */
        sb.append("        \"movq g_neko_heap_base(%%rip), %%r12\\n\"\n");

        /* Call compiled entry. Method* in rbx, args in their slots. */
        sb.append("        \"call *%%r13\\n\"\n");
        sb.append("        \"4:\\n\"\n");

        /* Save result. r14 still holds out_rax pointer. */
        sb.append("        \"movq %%rax, (%%r14)\\n\"\n");
        sb.append("        \"movq 32(%%rbp), %%r14\\n\"\n");
        sb.append("        \"movsd %%xmm0, (%%r14)\\n\"\n");

        /* Reverse transition: _thread_in_Java -> _thread_in_native_trans
         * (mfence) -> polling check -> _thread_in_native. */
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%rax\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native_trans(%%rip), %%ecx\\n\"\n");
        sb.append("        \"movl %%ecx, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"mfence\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_polling_word(%%rip), %%rax\\n\"\n");
        sb.append("        \"testq %%rax, %%rax\\n\"\n");
        sb.append("        \"je   6f\\n\"\n");
        sb.append("        \"movq (%%r15, %%rax), %%rcx\\n\"\n");
        sb.append("        \"testq %%rcx, %%rcx\\n\"\n");
        sb.append("        \"je   6f\\n\"\n");
        /* skipping neko_handle_safepoint_poll for now — to be revisited */
        sb.append("        \"6:\\n\"\n");
        sb.append("        \"movq g_neko_off_thread_state(%%rip), %%rax\\n\"\n");
        sb.append("        \"movl g_neko_thread_state_in_native(%%rip), %%ecx\\n\"\n");
        sb.append("        \"movl %%ecx, (%%r15, %%rax)\\n\"\n");

        /* Clear the anchor we set up before the call. The C dispatcher
         * around us will subsequently restore the OUTER anchor (the one
         * HotSpot's native_wrapper had set on entry to the translated
         * body) — we just clear ours so it doesn't bleed past return. */
        sb.append("        \"movq g_neko_off_last_Java_sp(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_fp(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%rax)\\n\"\n");
        sb.append("        \"movq g_neko_off_last_Java_pc(%%rip), %%rax\\n\"\n");
        sb.append("        \"movq $0, (%%r15, %%rax)\\n\"\n");

        /* Epilogue: lea rsp back to right after the 5 callee-save pushes
         * (rbx, r12, r13, r14, r15 = 40 bytes), pop them, return. The leaq
         * folds back over any stack args + pad. */
        sb.append("        \"leaq -40(%%rbp), %%rsp\\n\"\n");
        sb.append("        \"popq %%r15\\n\"\n");
        sb.append("        \"popq %%r14\\n\"\n");
        sb.append("        \"popq %%r13\\n\"\n");
        sb.append("        \"popq %%r12\\n\"\n");
        sb.append("        \"popq %%rbx\\n\"\n");
        sb.append("        \"popq %%rbp\\n\"\n");
        sb.append("        \"ret\\n\"\n");
        sb.append("        :\n");
        sb.append("        :\n");
        sb.append("        : \"memory\"\n");
        sb.append("    );\n");
        sb.append("}\n\n");

        /* ---- C dispatcher: marshals jvalue[] -> trampoline arrays ---- */
        sb.append("static jvalue ").append(fn)
          .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args) {\n");
        sb.append("    jvalue result; result.j = 0;\n");
        sb.append("    (void)env;\n");
        sb.append("    if (!g_neko_direct_invoke_ready || method_ptr == NULL || entry_point == NULL || thread == NULL) {\n");
        sb.append("        fprintf(stderr, \"[neko-direct] precondition failed shape=").append(key)
          .append(" ready=%d m=%p e=%p t=%p\\n\", (int)g_neko_direct_invoke_ready, method_ptr, entry_point, thread); abort();\n");
        sb.append("    }\n");
        sb.append("    NEKO_DIRECT_LOG(\"dispatch shape=").append(key).append(" m=%p e=%p recv=%p\", method_ptr, entry_point, (void*)receiver);\n");
        sb.append("    int64_t gp_args[").append(Math.max(gpCount, 1)).append("];\n");
        sb.append("    double  fp_args[").append(Math.max(xmmCount, 1)).append("];\n");
        if (stackArgs > 0) sb.append("    int64_t stack_args[").append(stackArgs).append("];\n");
        else sb.append("    int64_t stack_args[1] = {0};\n");

        /* Receiver as raw oop -> gp[0] */
        if (!isStatic) {
            sb.append("    gp_args[0] = (int64_t)(uintptr_t)neko_njx_handle_to_oop(receiver);\n");
        }
        for (int i = 0; i < args.length; i++) {
            String dst = (argLoc[i] == 0) ? ("gp_args[" + argRegIdx[i] + "]")
                       : (argLoc[i] == 1) ? ("fp_args[" + argRegIdx[i] + "]")
                                          : ("stack_args[" + argStackIdx[i] + "]");
            switch (args[i]) {
                case 'L' -> sb.append("    ").append(dst).append(" = (int64_t)(uintptr_t)neko_njx_handle_to_oop(args[").append(i).append("].l);\n");
                case 'J' -> sb.append("    ").append(dst).append(" = (int64_t)args[").append(i).append("].j;\n");
                case 'F' -> {
                    if (argLoc[i] == 1) sb.append("    fp_args[").append(argRegIdx[i]).append("] = (double)args[").append(i).append("].f;\n");
                    else sb.append("    { int32_t __t; memcpy(&__t, &args[").append(i).append("].f, sizeof(__t)); stack_args[").append(argStackIdx[i]).append("] = (int64_t)__t; }\n");
                }
                case 'D' -> {
                    if (argLoc[i] == 1) sb.append("    fp_args[").append(argRegIdx[i]).append("] = args[").append(i).append("].d;\n");
                    else sb.append("    { int64_t __t; memcpy(&__t, &args[").append(i).append("].d, sizeof(__t)); stack_args[").append(argStackIdx[i]).append("] = __t; }\n");
                }
                default -> sb.append("    ").append(dst).append(" = (int64_t)(int32_t)args[").append(i).append("].i;\n");
            }
        }

        sb.append("    int64_t out_rax = 0;\n");
        sb.append("    double  out_xmm0 = 0.0;\n");
        /* Save handle-block top so any handles created by Java code during
         * the nested call are bounded to this call's scope. */
        sb.append("    neko_handle_save_t __njx_hsave;\n");
        sb.append("    neko_handle_save(thread, &__njx_hsave);\n");
        /* Save the OUTER JavaFrameAnchor (set by HotSpot's native_wrapper
         * for our translated body's caller). The trampoline overwrites the
         * anchor with its own values — we restore the outer one after.
         * Mirrors what JavaCallWrapper::~JavaCallWrapper() does. */
        sb.append("    void *saved_sp = (g_neko_off_last_Java_sp > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_sp) : NULL;\n");
        sb.append("    void *saved_pc = (g_neko_off_last_Java_pc > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_pc) : NULL;\n");
        sb.append("    void *saved_fp = (g_neko_off_last_Java_fp > 0) ? *(void**)((char*)thread + g_neko_off_last_Java_fp) : NULL;\n");
        sb.append("    NEKO_DIRECT_LOG(\"  -> tramp shape=").append(key).append(" gp=").append(gpCount)
          .append(" xmm=").append(xmmCount).append(" stk=").append(stackArgs).append(" saved_sp=%p\", saved_sp);\n");
        /* Route through priv-heap wrapper when available — that wrapper is a
         * registered BufferBlob, giving HotSpot's stack walker a frame it
         * recognizes immediately above the compiled callee. Falls through
         * to direct libneko call when the wrapper isn't yet allocated
         * (e.g. before priv heap init runs). */
        sb.append("    typedef void (*").append(tramp).append("_t)(void*, void*, void*, const int64_t*, const double*, const int64_t*, int, int64_t*, double*);\n");
        sb.append("    ").append(tramp).append("_t __tramp_pc = (").append(tramp).append("_t)(")
          .append(wrapperGlobalName(key)).append(" != NULL ? ").append(wrapperGlobalName(key))
          .append(" : (void*)&").append(tramp).append(");\n");
        sb.append("    __tramp_pc(thread, method_ptr, entry_point, gp_args, fp_args, stack_args, ")
          .append(stackArgs).append(", &out_rax, &out_xmm0);\n");
        sb.append("    NEKO_DIRECT_LOG(\"  <- tramp shape=").append(key).append(" rax=0x%llx xmm0=%g\", (unsigned long long)out_rax, out_xmm0);\n");
        /* Restore outer anchor */
        sb.append("    if (g_neko_off_last_Java_sp > 0) *(void**)((char*)thread + g_neko_off_last_Java_sp) = saved_sp;\n");
        sb.append("    if (g_neko_off_last_Java_pc > 0) *(void**)((char*)thread + g_neko_off_last_Java_pc) = saved_pc;\n");
        sb.append("    if (g_neko_off_last_Java_fp > 0) *(void**)((char*)thread + g_neko_off_last_Java_fp) = saved_fp;\n");
        sb.append("    neko_handle_restore(&__njx_hsave);\n");
        switch (ret) {
            case 'V' -> { /* nothing */ }
            case 'I' -> sb.append("    result.i = (jint)(int32_t)out_rax;\n");
            case 'J' -> sb.append("    result.j = (jlong)out_rax;\n");
            case 'F' -> sb.append("    { float __f = (float)out_xmm0; result.f = __f; }\n");
            case 'D' -> sb.append("    result.d = (jdouble)out_xmm0;\n");
            case 'L' -> sb.append("    result.l = neko_njx_oop_to_handle(thread, (void*)(uintptr_t)out_rax);\n");
        }
        sb.append("    return result;\n");
        sb.append("}\n\n");
        return sb.toString();
    }

    private String renderShapeUnsupported(String key, SignaturePlan.Shape shape) {
        String fn = dispatcherSymbol(key);
        StringBuilder sb = new StringBuilder();
        sb.append("static jvalue ").append(fn)
          .append("(void *thread, JNIEnv *env, void *method_ptr, void *entry_point, jobject receiver, const jvalue *args) {\n");
        sb.append("    (void)thread; (void)env; (void)method_ptr; (void)entry_point; (void)receiver; (void)args;\n");
        sb.append("    fprintf(stderr, \"[neko-direct-invoke] arch backend missing for shape=").append(key).append("\\n\"); abort();\n");
        sb.append("    jvalue r; r.j = 0; return r;\n");
        sb.append("}\n\n");
        return sb.toString();
    }
}
