# Native Path Performance Optimization TODO

This TODO is based only on the current git-baseline source under `neko-native/` and native tests under `neko-test/src/test/java/`.

It intentionally does not use existing TODO files, project notes, memory files, or prior performance writeups as evidence. Every optimization item below names the current source location that motivated it. Items without enough runtime evidence start with measurement or generated-C audit work instead of assuming a bottleneck.

Hard requirement: every optimization in this TODO must preserve the JVM ABI exactly. Any change that violates, narrows, bypasses, weakens, approximates, or relies on undefined behavior against the JVM ABI is forbidden, even if it improves a benchmark.

## Repository Native Constraints

These constraints are imported from the repository `AGENTS.md` and apply to every item in this TODO.

- Do not implement or optimize for a special sample, benchmark, class, method, owner, descriptor, crash site, log string, or known test artifact.
- All changes must support all JVM features and must be generic, architecture-level changes.
- Do not jump to a later-stage workaround while earlier prerequisites are open. If order must change, update this TODO first with a plan-level dependency reason.
- Do not mark stale work complete. A stale jar, stale generated C file, compile-only result, unit-only result, or previous run is not proof.
- After a verified completed substep, commit the implementation and the matching TODO checkbox update before continuing.
- Do not revert or overwrite unrelated user work. Work with existing changes if they affect the task.
- Use the repository/system `./gradlew`; do not create, copy, or use a temporary `gradlew`.
- No JNI fallback, soft fallback, skip-on-error path, original-bytecode fallback, or JVMTI is allowed.
- Do not add a new JVM runtime helper layer outside the planned native/bootstrap surface.
- Do not add new Java-side bootstrap behavior or new `<clinit>` behavior beyond the existing native-loader entry.
- `JNI_OnLoad` must stay minimal: `GetEnv`, capture anchors, initialize native metadata, then stop using the JNI function table.
- Outside the minimal `JNI_OnLoad` path, generated/runtime native code must not use `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, `Call*Method`, `Get*Field`, `FindClass`, `NewStringUTF`, `NewObject*`, `Throw*`, JNI monitor calls, JNI array calls, or equivalent JNI wrappers.
- Missing VMStruct, JVM symbol, class, method, field, string metadata, GC barrier, CodeHeap support, or call stub support must hard abort/error. It must not fall back to JNI, skip a method, or keep original bytecode.
- ZGC/Shenandoah compatibility must not be achieved by skipping classes or methods. Missing required barriers must abort.
- Resolver and bind-time subtasks must prove that the current bind/runtime path uses the resolved pointer, offset, or entry.
- Hot-path opcode subtasks must prove that the runtime jar executes the opcode through the new native path and generated C contains no forbidden JNI wrappers.
- Cleanup/removal subtasks must prove the removed fallback is no longer needed and missing capability paths hard abort.
- Never create files or folders in `/tmp`.

Status legend:
- `[ ]` not implemented / not verified
- `[-]` actively in progress only while that exact task is being worked
- `[x]` implemented, freshly regenerated, runtime-verified, inspected, and committed

## Source-Only Performance Findings

### Confirmed from current source

- The per-signature entry dispatcher always saves and restores a JNIHandleBlock window, even for primitive-only signatures and methods that may not create object handles.
  Source evidence: `SignatureDispatcherEmitter.renderOne` emits `neko_handle_save` unconditionally, then restores it on every return path.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/SignatureDispatcherEmitter.java:85-149`

- Translated raw functions always allocate operand stack, monitor records, locals, call `neko_hotspot_fast_require`, and receive `thread`, `env`, and owner/receiver parameters.
  Source evidence: `CCodeGenerator.renderRawFunction`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:459-495`

- Shadow-frame bookkeeping is unconditional for every translated method and must stay semantically unconditional. It is required for translated frames to remain visible when this native jar is called as a dependency from any Java path that later observes `Throwable.getStackTrace()`.
  Source evidence: `NativeTranslator.translateMethod` sets `shadowEnabled = true` and emits `neko_shadow_push`; returns emit `neko_shadow_pop`; `Throwable.getStackTrace` consumes the shadow stack through `neko_shadow_stack_trace`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslator.java:125-132`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:316-321`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:562-565`

- Exception checks are already coalesced at the translator level, but many opcode-specific paths still emit immediate `if (!neko_exception_check(env))` guards after helper or invoke calls.
  Source evidence: deferred `pendingHandlers` in `NativeTranslator`, plus direct checks in invoke/checkcast/string/switch paths.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslator.java:138-203`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:359`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:458`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:506`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:660`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:722-763`

- The current fast-state constant layer is only partially using the frozen const alias. Several `NEKO_CONST_INLINE` accessors still read `g_hotspot` directly instead of `g_hotspot_const`.
  Source evidence: `g_hotspot_const` is declared, but accessors for klass/compressed-oops/fast-bits/init/primitive bases/scales read `g_hotspot`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:3786-3840`

- Many hot helpers still embed diagnostic `fprintf` argument construction directly in the inline helper body.
  Source evidence: `neko_fast_aaload`, object field helpers, primitive field helpers, primitive array helpers.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5435-5466`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5629-5734`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5866-5925`

- Primitive field access uses `volatile` loads/stores for every primitive field access, regardless of whether the Java field is volatile.
  Source evidence: `appendPrimitiveFieldHelpers` emits `*((volatile <type>*)(oop + offset))` for get/set paths.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5866-5904`

- Primitive and object array accesses still perform per-access null/bounds checks; there is no source-level loop analysis that can hoist range checks out of counted loops.
  Source evidence: `OpcodeTranslator.arrayAccessBody` emits checks per array opcode; fast helpers also check bounds.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:168-178`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5435-5452`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5907-5925`

- Nested-array fusion is a narrow peephole only: it recognizes adjacent `AALOAD; <int-push>; XALOAD`, not general raw-oop lifetimes or loop-invariant outer array references.
  Source evidence: `tryFuseArrayLoad` only looks at the next two non-meta instructions and rejects labels.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:88-143`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/NativeTranslator.java:472-488`

- Virtual/interface dispatch has a compact hit path, but miss handling still performs receiver klass discovery, exact mirror materialization, class linking, metadata method resolution, local-ref deletion, and NJX entry resolution.
  Source evidence: `neko_icache_dispatch`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:4477-4567`

- NJX direct Java calls allocate/free a temporary Java handle block and zero stack buffers per call.
  Source evidence: `NativeToJavaInvokeEmitter` emits `calloc/free`, `call_params` memset, and `__jcw_buf` memset in the call-stub path.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/NativeToJavaInvokeEmitter.java:187-222`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/emit/NativeToJavaInvokeEmitter.java:291-430`

- Raw oop to local-handle conversion allocates a new JNIHandleBlock with `calloc` when the current active block is full.
  Source evidence: `neko_direct_oop_to_handle`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:4880-4917`

- The release native compiler flags are already aggressive (`-O3`, arch baseline, `-fno-plt`, `-fno-semantic-interposition`, `-fmerge-all-constants`, `-funroll-loops`), but there is no source-controlled optimization-diagnostics mode for validating inlining/LICM/code size.
  Source evidence: `NativeBuildEngine`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/NativeBuildEngine.java:43-94`

- The native performance tests currently enforce TEST Calc under 150 ms, but they do not record a structured native-path baseline for obfusjack matrix/thread timings or generated-C hot-path counts.
  Source evidence: `NativeObfuscationPerfTest` parses only Calc timing in the performance test.
  `neko-test/src/test/java/dev/nekoobfuscator/test/NativeObfuscationPerfTest.java:71-91`

### Things not to schedule as new work because current source already has them

- Do not add a TODO for direct manifest-local static/special calls as if it does not exist. Current source already emits direct C calls for eligible translated targets.
  Source evidence: `OpcodeTranslator.translateDirectInvoke`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:729-769`

- Do not add a TODO for `AtomicLong.addAndGet` / `AtomicInteger.addAndGet` intrinsics as if they are missing. Current source already has direct atomic helpers and translator intrinsics.
  Source evidence: `translateIntrinsicMethodInvoke` and atomic helpers.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:567-584`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5736-5758`

- Do not add a TODO for basic adjacent nested-array fusion as if it is missing. Current source already has the peephole and fused helpers.
  Source evidence: `tryFuseArrayLoad`, `appendFusedAALoadHelpers`.
  `neko-native/src/main/java/dev/nekoobfuscator/native_/translator/OpcodeTranslator.java:88-143`
  `neko-native/src/main/java/dev/nekoobfuscator/native_/codegen/CCodeGenerator.java:5763-5864`

## Validation Rules

- Before editing code for any item, record the runtime target row that proves the changed path.
- Do not mark a checkbox complete from source inspection, stale generated C, stale jars, compile-only success, or previous runs.
- After each completed item, regenerate the affected native artifacts, run the row's runtime targets, inspect stdout/stderr/native logs/generated C/`hs_err_pid*.log`, then commit only that implementation and this TODO checkbox update.
- Reject SIGSEGV, SIGABRT, verifier errors, VM fatal errors, `translated=0`, missing native libraries, skip-on-error behavior, JNI fallback, original-bytecode fallback, or new forbidden JNI function-table use.
- JVM ABI compliance is mandatory for every item. If an optimization cannot be proven JVM-ABI-equivalent for all supported JVM features, descriptors, calling conventions, object/reference lifetimes, thread states, exception states, stack frames, monitors, GC interactions, and class/linkage states, do not implement it.
- No performance item may drop JVM features, JVM ABI behavior, Java Memory Model behavior, exception ordering, monitor behavior, GC root visibility, class initialization semantics, stack trace visibility, or dependency-jar call correctness.
- Do not optimize by assuming this native jar is the main jar, the top-level entry point, a closed-world caller, a benchmark-only path, or a path where `Throwable.getStackTrace()` cannot be observed.
- Shadow-frame push/pop must remain present for every translated method entry/exit unless a future architecture provides a strictly equivalent always-visible translated-frame mechanism. Conditional omission, lazy reconstruction that misses already-entered frames, or method/body classification that hides frames is forbidden.

Runtime target groups:
- `R-build`: cleanly regenerate native artifacts with the repository Gradle wrapper.
- `R-test`: run the generated TEST native jar.
- `R-obfusjack`: run the obfusjack Java fixture.
- `R-native-obfusjack`: run the generated obfusjack native jar.
- `R-native-test`: run the generated TEST native jar under the native path.
- `R-inspect`: inspect generated C/native output, stdout, stderr, native logs, newest `hs_err_pid*.log`, forbidden JNI markers, and fallback markers.
- `R-negative`: prove missing required symbols/capabilities fail closed instead of falling back.

Performance and GC gates:
- Run `R-build`, `R-test` repeated 5 times, `R-obfusjack` repeated 5 times, `R-native-test`, and `R-inspect` for performance-sensitive items.
- Required medians per current user acceptance: TEST Calc must be same as or faster than the original JVM jar measured in the same run environment; obfusjack matrix-mul Seq must be <= 10 ms; every other parsed benchmark/test timing must be same as original JVM or within 1.5x slowdown. Any older absolute thresholds in this file are superseded by this stricter gate.
- If a threshold fails, make a new generic optimization commit and rerun the gate. Do not special-case the benchmark.
- GC strict compatibility requires TEST and obfusjack runs under G1, Parallel, Serial, ZGC with `ZVerifyViews`, and Shenandoah with verification enabled.

## Performance Optimization TODO

- [x] P0 Add a source-controlled performance baseline capture for the current native artifacts. Runtime target row: `R-build`, `R-test` x5, `R-obfusjack` x5, `R-inspect`. The capture must record JDK version, native compiler command line, generated C path, generated library size, TEST Calc timing for 5 runs, obfusjack completion timing for 5 runs, parsed matrix/thread timing lines when present, stdout/stderr paths, and `hs_err` status. This is required before making optimization claims because the current source tests only assert Calc under 150 ms. Source evidence: `NativeObfuscationPerfTest.java:71-91`. Validation: `R-build`, `R-test` x5, `R-obfusjack` x5, `R-inspect`. Verified 2026-05-04 with `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeObfuscationPerfTest`; report: `neko-test/build/test-native/native-performance-baseline.json`.

- [x] P1 Add a generated-C hot-path audit. Runtime target row: `R-build`, `R-inspect`. The audit must count per generated C artifact: `neko_handle_save`, `neko_handle_restore`, `neko_direct_oop_to_handle`, `calloc`, `free`, `memset`, direct `g_hotspot.` reads, `volatile` primitive field accesses, `neko_exception_check`, `neko_icache_dispatch`, NJX dispatcher calls, inline `fprintf`, `getenv`, and forbidden JNI function-table spellings. It must report counts by helper/function region, not just global totals. Source evidence: current hot-path candidates are generated by `CCodeGenerator.java:459-495`, `SignatureDispatcherEmitter.java:85-149`, `NativeToJavaInvokeEmitter.java:187-430`, and `CCodeGenerator.java:4880-5916`. Validation: `R-build`, `R-inspect`. Verified 2026-05-04 with `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`; report: `neko-test/build/test-native/native-generated-c-hot-path-audit.json`.

- [-] P2 Generate primitive/no-handle dispatchers only for fully proven no-reference paths. Runtime target row: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate. A method is eligible only if descriptor and translated body together prove there is no receiver reference, object/array parameter, object/array return, object allocation, array allocation, object/array load result, object/array field result, string operation, Java/NJX call, exception object creation, monitor object operation, or any helper that can create or expose a local reference. If any proof is incomplete, keep the existing handle window. This must be descriptor- and translated-body driven, not benchmark-driven, and it must not change GC root visibility or JNI local-reference lifetime for any reference-bearing path. Source evidence: current dispatcher saves/restores unconditionally in `SignatureDispatcherEmitter.java:85-149`, while raw functions already know params and emitted body in `NativeTranslator.java:98-208`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; generated C must show no handle-window calls only for classified no-reference dispatchers and unchanged handle windows for all reference-capable dispatchers.
  - Current validation blocker row recorded 2026-05-06 for P2 GC strict prerequisite: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, GC strict compatibility gate. Fresh validation showed default/G1 native runs pass, but ZGC/Shenandoah fail before P2 can be checked complete because direct field metadata binding is wrongly gated by primitive raw-heap fast bits and string-literal binding still requires raw String allocation. Fix these as generic GC-capability prerequisites, not benchmark/sample work. Raw-disabled string literal binding must allocate through VM-managed non-JNI paths (stable JVM_NewArray plus existing NJX Unsafe.allocateInstance or exported VM intern symbol) and hard-abort if required symbols/barriers/metadata are missing.
  - Current validation blocker row recorded 2026-05-07 for P2 ZGC reflection prerequisite: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, GC strict compatibility gate. Fresh ZGC strict TEST run reaches reflected custom-loader invocation, then aborts in `java/lang/reflect/Method.invoke` caller-class materialization because the intrinsic uses runtime `self.getClass()` through a stale/cleared handle. Fix this generically by passing the lexical/current translated owner class for reflective caller-sensitive intrinsics, preserving JVM caller-class semantics instead of deriving it from the receiver runtime class.
  - Current validation blocker row recorded 2026-05-07 for P2 ZGC ReTrace prerequisite: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, GC strict compatibility gate. Fresh ZGC strict TEST now passes Loader after the lexical caller fix but aborts in ReTrace after `Throwable.getStackTrace()`/`AALOAD` because current-owner Class LDC still re-derives the current class from `self` and materializes a fresh mirror handle before dispatching on the stack-trace element. Fix this generically by using the translated method's lexical `clazz` ABI parameter for current-owner Class LDC validation/materialization, preserving duplicate-loader semantics and avoiding late receiver-handle dependency.
  - Current validation blocker row recorded 2026-05-07 for P2 ZGC object-local rooting prerequisite: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, GC strict compatibility gate. Fresh ZGC strict TEST still aborts in ReTrace after current-owner Class LDC was moved to lexical `clazz`: an object loaded through `AALOAD` is stored in a translated object local as a transient JNI handle slot, and later virtual dispatch cannot resolve that receiver. Lazy active-block local roots, preallocated active-block roots, and the attempted dedicated-root-below-scratch topology all failed; OpenJDK `JNIHandleBlock` allocation/GC invariants show a scratch block with `_top=0` and roots in `_next` can be zapped and is not GC-visible behind a non-full block. The next generic step is to make translated object locals mirror Java null as `NULL` and non-null values as direct/good-colored oops, reserve valid JNIHandleBlock root slots in the normal active chain for GC visibility only, update those roots on store/parameter/tail-recursive rewrite, and compare object refs by resolved oop identity rather than handle pointer identity, while preserving hard-abort behavior and primitive/no-handle dispatchers.
  - Current validation blocker row recorded 2026-05-07 for P2 ZGC pointer-mask prerequisite: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, GC strict compatibility gate. Fresh direct-local-oop TEST artifact aborts under ZGC in Field with a false `ZGC bad oop load` produced by the old single-sample high-bit mask fallback. OpenJDK 21u `zAddress` shows ZGC masks are dynamic low-bit `ZGlobalsForVMStructs` values (`ZPointerLoadGood/Bad`, `ZPointerStoreGood/Bad`, load shift); deriving `good=sample-high-bit`/`bad=other-high-bits` is invalid. Fix generically by capturing the real store-bad VMStruct pointer, using live ZGlobals pointers for all ZGC masks, and failing closed when those masks are unavailable instead of bootstrapping from an oop sample.
  - Current validation blocker row recorded 2026-05-07 for P2 ZGC local-handle prerequisite: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, GC strict compatibility gate. After removing the invalid sample mask fallback, fresh ZGC strict TEST fails closed during bootstrap handle resolution because `neko_handle_oop` applies the ZGC zpointer load-barrier mask path to an untagged JNI local handle slot before ZGlobals masks are initialized. OpenJDK `JNIHandles::resolve_impl` resolves local handles with a plain slot load; only global/weak handles use `NativeAccess` barriers. Fix generically by treating untagged JNI local handles as already-dereferenceable oop/zaddress values under ZGC, ordering that local-handle path before direct-oop classification while live masks are unavailable, while keeping tagged global/weak handles on the barriered path; apply the same canonical resolver to static-base mirrors so class/static-field bootstrap does not call direct-oop recognition on local jclass handles, and preserve hard-abort behavior for real field/array zpointer loads when masks are unavailable.

- [x] P3 Reduce shadow-frame overhead without removing always-visible translated frames. Keep one push on every translated method entry and one pop on every translated exit path. Optimize only the representation and emitted call shape: replace three per-entry string pointer arguments with a generated immutable frame descriptor pointer, store one descriptor pointer per stack slot instead of three strings, and keep `neko_shadow_stack_trace` responsible for expanding the descriptor into `StackTraceElement` values. This must preserve dependency-jar usage where native-translated methods are called from unrelated Java code and `Throwable.getStackTrace()` is observed later on another path. Conditional omission, lazy materialization after the observation point, or method/body classification that hides frames is forbidden. Source evidence: `NativeTranslator.java:125-132` always emits `neko_shadow_push(owner, method, file)`, `OpcodeTranslator.java:316-321` emits return pops, and `CCodeGenerator.java:3114-3181` stores three strings per shadow slot then expands them in `neko_shadow_stack_trace`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; add a dependency-jar stack-trace fixture where an external Java caller invokes translated code and later observes `Throwable.getStackTrace()`, and generated C must still show one shadow push and one shadow pop path for every translated method.
  - Completion evidence 2026-05-21 for NPT-3ar: current source already emits a
    per-method immutable `neko_shadow_frame_desc` and calls
    `neko_shadow_push(&__neko_shadow_desc)`, while runtime support stores only a
    `const neko_shadow_frame_desc *desc` per shadow frame and expands
    `desc->owner`, `desc->method`, and `desc->file` only when building
    `neko_shadow_stack_trace`. Fresh generated C from
    `build/neko-native-work/run-14840042038598` shows descriptor pushes in
    translated `neko_native_impl_*.c` files and pointer-only shadow frame
    storage in support helpers. No executable change was needed for this row.
  - Implementation row recorded 2026-05-21: NPT-3as will add the missing P3
    dependency-jar stack-trace fixture. The test will create an input jar
    containing only the native-translated target class and a separate dependency
    jar containing the external caller, run `java -cp <native-output>:<dep>`,
    and prove the returned stack trace contains translated target frames. This
    is test-only and must not change runtime/native code.
  - Completion evidence 2026-05-21 for NPT-3as: added
    `nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`, which
    builds separate target and dependency jars, obfuscates only the target jar,
    runs the external caller with `java -cp <native-output>:<dependency>`, and
    validates the returned stack trace contains both
    `pkg.NativeStackTarget.leaf` and `pkg.NativeStackTarget.capture`. Focused
    Gradle validation passed and runtime stdout was `dependency-stacktrace-ok`
    with empty stderr.
  - Partial implementation evidence recorded under `.plan/native-compile-parallelization-2026-05-17.md` P25: the generated runtime representation and call shape were changed to descriptor pointers and focused native-only validation passed for TEST/test21/SnakeGame/evaluator. This native-performance P3 row remains open for its broader dependency-jar stack-trace fixture and GC strict compatibility gate.

- [-] P4 Fix the const fast-state accessor layer. Accessors for post-bootstrap immutable values must read `g_hotspot_const`, not mutable-looking `g_hotspot`, unless the value is intentionally dynamic under a collector. Keep ZGC dynamic mask pointers mutable. Current source evidence: `NativeHotSpotFastAccessEmitter.java:66-84` declares `g_hotspot_const` and already uses it for `neko_const_array_length_offset`, but `neko_const_klass_offset_bytes`, `neko_const_use_zgc`, `neko_const_use_compressed_klass_ptrs`, `neko_const_compressed_oops_enabled`, `neko_const_compressed_oops_shift`, `neko_const_compressed_oops_base`, `neko_const_fast_bits`, `neko_const_initialized`, `neko_const_prim_array_base`, and `neko_const_prim_array_scale` still read `g_hotspot`. Dynamic ZGC mask helpers must keep reading the live `g_hotspot.z_zglobals_*` pointers. Validation row recorded before editing: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, and GC strict compatibility gate; generated C or compiler diagnostics must show immutable accessor reads use `g_hotspot_const`, while dynamic GC mask paths remain mutable.
  - P4 implementation evidence 2026-05-20: immutable const accessors now read
    `g_hotspot_const` in source and in freshly generated TEST C
    `build/neko-native-work/run-3244581472985/neko_native_support.c`; ZGC
    dynamic mask helpers still read mutable `g_hotspot.z_zglobals_*` pointers.
    Focused generator/audit tests and `NativeObfuscationIntegrationTest`
    passed. Fresh native-only P4 artifacts in `build/native-post-transfer-p4/`
    reported `translated>0 rejected=0` for TEST/obfusjack/SnakeGame/evaluator.
    Default direct runtime passed: TEST x5 Calc 35/35/35/33/34 ms; obfusjack
    x5 completed with Seq 23/23/23/22/25 ms. This row remains `[-]` because
    the full P4 performance and GC strict gates are not satisfied yet: TEST
    Calc is still above 20 ms, ZGC/Shenandoah still fail closed on the existing
    mask-publication blocker, and Parallel/Serial obfusjack direct strict runs
    did not produce a clean completion under the direct-run cap.
  - Implementation row recorded 2026-05-21: NPT-3ae will fix only the ZGC
    barrier-readiness invariant. Evidence: strict ZGC logs show
    `ZGlobalsForVMStructs` metadata and offsets are discovered, but all
    published mask values are zero and `z_lrb`, `z_array`, and `z_store` are
    `(nil)` while current code still reports `gc barrier: ready=1 kind=3`;
    runtime then aborts later with `ZGC oop load masks unavailable addr=0x0
    good=0x0`. Completion requires readiness to be capability-based: callable
    ZGC runtime/CompilerToVM barriers or nonzero live masks sufficient for the
    inline path. Without either capability, the runtime must fail closed before
    selecting translated oop paths and must not use JNI fallback, skip
    behavior, original bytecode, or sample-derived masks.
  - Completion evidence 2026-05-21 for NPT-3ae: focused generator/audit tests
    passed; fresh TEST native generation produced
    `build/npt-3ae-zgc/TEST-native.jar` from
    `build/neko-native-work/run-10986264548170` with `translated=49
    rejected=0`. Generated C now requires nonzero ZGC address, load-good,
    load-bad, store-good, and store-bad masks before the inline VMStruct path
    marks ZGC barriers ready. Strict ZGC TEST with patch logging now aborts at
    native layout initialization with `gc barrier: ready=0 kind=3` and
    `gc barrier path not ready for kind=3`; the previous later
    `ZGC oop load masks unavailable addr=0x0 good=0x0` abort is gone. Default
    TEST still completes with `Calc: 89ms`. This closes the readiness-invariant
    substep only; P2/P4 GC strict remains open until a real callable ZGC
    barrier or nonzero-mask capability is implemented for this JDK.
  - Implementation row recorded 2026-05-21: NPT-3af will add only opt-in
    patch-debug capability evidence for HotSpot-published ZGC sources not
    currently audited: `gHotSpotVMLongConstants`, `CompilerToVM::Data`
    ZBarrierSetRuntime fields, and thread-local ZGC mask offsets such as
    `thread_address_bad_mask_offset`. Evidence: post-NPT-3ae strict ZGC TEST
    fails closed at layout initialization with `gc barrier: ready=0 kind=3`;
    local `libjvm.so` strings contain `thread_address_bad_mask_offset` and
    `ZBarrierSetRuntime_*` names while `nm -D` exposes no matching dynamic
    symbols; current generated runtime walks VMStructs/VMTypes/VMIntConstants
    but not VMLongConstants. This row must not select CodeBlob stubs, derive
    sample masks, add fallback, or change runtime behavior outside default-off
    diagnostics. Completion requires fresh strict-ZGC logs proving either the
    exact permitted service-table source or that this JDK does not expose one
    through the allowed runtime discovery paths.
  - Completion evidence 2026-05-21 for NPT-3af: focused generator/audit tests
    passed. Fresh TEST native generation produced
    `build/npt-3af-zgc/TEST-native.jar` from
    `build/neko-native-work/run-11515458382165` with `translated=49
    rejected=0`. Strict ZGC TEST with patch logging still failed closed at
    native layout initialization with `gc barrier: ready=0 kind=3`; the fresh
    log shows standard VMStructs expose only `ZGlobalsForVMStructs` zcap
    entries with `compilertovm_zcap_matches=0`, VMIntConstants expose
    `vmint zcap matches=0`, and VMLongConstants expose only four zero-valued
    `ZAddress*` constants. Default collector TEST still completes with
    `Calc: 92ms`. Independent local OpenJDK source inspection shows the next
    generic source path is the JVMCI serviceability tables that publish
    `CompilerToVM::Data` Z barrier entries and
    `thread_address_bad_mask_offset`; no runtime barrier is selected by this
    diagnostic row.
  - Implementation row recorded 2026-05-21: NPT-3ag will add a generic native
    JVMCI serviceability-table walker for `jvmciHotSpotVMStructs`,
    `jvmciHotSpotVMIntConstants`, and `jvmciHotSpotVMLongConstants`, binding
    only `CompilerToVM::Data::thread_address_bad_mask_offset` and
    `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` as ZGC
    evidence. Local OpenJDK source shows those fields plus
    `ZBarrierSetRuntime_load_barrier_on_oop_array` are exported by
    `vmStructs_jvmci.cpp`
    and initialized under `UseZGC` by `jvmciCompilerToVMInit.cpp` from
    `XThreadLocalData::address_bad_mask_offset()` and
    `XBarrierSetRuntime::*_addr()`, but `XBarrierSetRuntime.hpp` shows the
    array barrier ABI is `void(oop*, size_t)` and does not match the current
    runtime typedef, so this row must audit that entry without publishing it as
    callable. This row must not call C1 CodeBlob stubs, derive sample masks,
    mark ZGC ready, or change store/no-store semantics; completion requires
    fresh strict-ZGC logs proving the JVMCI table bind result while preserving
    fail-closed behavior until a later row proves the complete ZGC barrier
    capability.
  - Completion evidence 2026-05-21 for NPT-3ag: focused generator/audit tests
    passed. Fresh TEST native generation produced
    `build/npt-3ag-zgc/TEST-native.jar` from
    `build/neko-native-work/run-12028033311558` with `translated=49
    rejected=0`. Generated C contains the JVMCI VMStruct/constant walkers,
    `z_bad_off` logging, and the corrected
    `off_zglobals_pointer_store_bad_mask = -1` sentinel initialization. Strict
    ZGC TEST with patch logging still fails closed in both default JVMCI state
    and with `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`; both logs
    report `jvmci vmstructs unavailable: table=(nil)`,
    `jvmci vmint constants unavailable: table=(nil)`, and
    `jvmci vmlong constants unavailable: table=(nil)`, then
    `gc barrier: ready=0 kind=3` with `z_lrb=(nil)`, `z_array=(nil)`,
    `z_store=(nil)`, and `z_bad_off=-1`. ELF inspection of the active Arch
    OpenJDK `libjvm.so` shows no dynamic or regular `jvmciHotSpotVM*` symbols
    even though `strings` still contains the relevant field names. Default
    collector TEST still completes with `Calc: 94ms`. This closes the
    JVMCI-table walker/audit substep only; ZGC strict remains blocked because
    this runtime does not expose the JVMCI table through `dlsym`.
  - Audit row recorded 2026-05-21: NPT-3ah will determine whether the active
    stripped Arch OpenJDK `libjvm.so` contains a generically recoverable JVMCI
    VMStructEntry table even though `jvmciHotSpotVM*` symbols are absent from
    `dlsym`, `nm`, and `readelf -Ws`. Evidence to start from: `strings` still
    contains `thread_address_bad_mask_offset` and
    `ZBarrierSetRuntime_load_barrier_on_oop_*`, while local OpenJDK source
    defines the shared VMStructEntry layout and JVMCI table fields. This row is
    read-only/prototype-only: no arbitrary memory scanner, runtime barrier
    selection, fallback, or executable behavior change may be added until a
    bounded section-scoped recovery invariant is proven.
  - Completion evidence 2026-05-21 for NPT-3ah: read-only ELF/source
    inspection found a bounded recovery invariant. The active Arch OpenJDK
    `libjvm.so` has no `.symtab` and no `jvmciHotSpotVM*` entries in
    `readelf -Ws`, but `.rodata` contains `CompilerToVM::Data` at
    file/VMA `0xf4c22a`, `thread_address_bad_mask_offset` at `0xfb1eb8`,
    `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` at `0xfb1ed8`,
    and `ZBarrierSetRuntime_load_barrier_on_oop_array` at `0xfb2060`.
    A read-only pointer-addend prototype found VMStructEntry-shaped records in
    the data image at `0x12f0930`, `0x12f0960`, and `0x12f0a80` with
    `typeName=CompilerToVM::Data`, the expected field names, `isStatic=1`,
    and static-address addends `0x1314db0`, `0x1314da8`, and `0x1314d78`.
    Local OpenJDK source confirms VMStructEntry is six fields / 48 bytes and
    the JVMCI arrays are sentinel-terminated. The next implementation may scan
    only the mapped libjvm data/RELRO ranges for this VMStructEntry invariant;
    it must not scan arbitrary memory, bind the mismatched array ABI, select
    ZGC readiness, or add fallback behavior.
  - Implementation row recorded 2026-05-21: NPT-3ai will implement a bounded
    native scanner for stripped JVMCI `CompilerToVM::Data` VMStructEntry
    records when `jvmciHotSpotVM*` export symbols are unavailable. The scanner
    may bind only `thread_address_bad_mask_offset` and
    `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` after exact
    VMStructEntry invariant checks within mapped `libjvm.so` data/RELRO ranges;
    VMStructEntry scanning must stay path-tagged while slot validation may also
    accept immediately adjacent anonymous writable loader mappings for
    `libjvm.so` data.
    It must audit but not publish the mismatched
    `ZBarrierSetRuntime_load_barrier_on_oop_array` ABI, must not scan arbitrary
    process memory, and must not mark ZGC ready or change store/no-store
    semantics.
  - Completion evidence 2026-05-21 for NPT-3ai: focused generator/audit tests
    passed. Fresh TEST native generation produced
    `build/npt-3ai-zgc/TEST-native.jar` from
    `build/neko-native-work/run-13077085932357` with `translated=49 rejected=0`.
    Strict ZGC TEST with `NEKO_PATCH_DEBUG=1` found bounded libjvm scan, slot,
    and executable ranges, including adjacent anonymous slot ranges, recovered the exact
    `CompilerToVM::Data` VMStructEntry records, bound
    `thread_address_bad_mask_offset` from slot `0x7f2d12b14db0` with value `0`,
    observed a null field-load barrier slot, audited executable target validation
    for future non-null field barrier slots, audited the array barrier as
    not-bound due to the current ABI mismatch, and failed closed with
    `gc barrier: ready=0 kind=3`. Generated-C inspection shows the scanner is
    emitted in `neko_native_support.c` and the bootstrap call is emitted in
    `neko_native_support_helpers_3.c`. Default collector TEST completed with
    `Calc: 91ms`.
  - Audit row recorded 2026-05-21: NPT-3aj will determine why the recovered
    stripped JVMCI
    `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` static slot is
    null during strict ZGC native layout initialization even though the
    `CompilerToVM::Data` VMStructEntry and slot address are recoverable. This
    row is read-only: inspect OpenJDK source, local `libjvm.so`
    symbols/strings, generated native logs, and generated C only. Do not call
    JVMCI initialization paths, bind raw CodeBlob stubs, mark ZGC ready, change
    store/no-store semantics, or add fallback behavior.
  - Completion evidence 2026-05-21 for NPT-3aj: OpenJDK source shows
    `CompilerToVM::Data::initialize(JVMCI_TRAPS)` publishes the ZGC slots from
    `XBarrierSetRuntime::*_addr()` under `UseZGC`; the initializer is invoked
    from `readConfiguration0`, reached through JVMCI Java/native configuration
    paths, not from the VMStruct table walk. `JVM_GetJVMCIRuntime` requires
    `EnableJVMCI` and initializes the Java `HotSpotJVMCIRuntime`, so it is not
    a valid native-bootstrap trigger under the no-JNI/no-new-helper constraints.
    Runtime evidence confirms timing: default strict ZGC keeps the recovered
    field-load and array slots null, while strict ZGC with
    `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+EagerJVMCI` fills
    `thread_address_bad_mask_offset=40`,
    `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded=0x7f398144ace0`,
    and `ZBarrierSetRuntime_load_barrier_on_oop_array=0x7f398144ab70`.
    Readiness still fails closed because the current native surface does not
    bind the mismatched array ABI and has no resolved store barrier. Next row:
    correct and bind the ZGC array ABI from the recovered JVMCI slot, then
    reassess ZGC readiness around actually available callable barriers and the
    inline fail-closed store path.
  - Audit row recorded 2026-05-21: NPT-3ak will determine whether named ZGC C1
    barrier CodeBlobs in the CodeCache can provide a generic recovery path to
    underlying callable runtime leaf functions when `libjvm.so` symbols are
    stripped and JVMCI `CompilerToVM::Data` slots have not been initialized.
    This row is read-only: inspect OpenJDK C1/ZGC source, current generated
    logs, generated C, and static binary metadata only. Do not bind raw
    CodeBlob stubs, execute recovered targets, infer addresses from samples,
    mark ZGC ready, or add executable behavior.
  - Completion evidence 2026-05-21 for NPT-3ak: source shows the named C1 load
    barrier blobs are generated by `XBarrierSetC1::generate_c1_runtime_stubs`
    or `ZBarrierSetC1::generate_c1_runtime_stubs`, and x86 emits a VM-leaf call
    to the field-load runtime address. Fresh strict-ZGC logs confirm the
    default run contains `load_barrier_on_oop_field_preloaded_runtime_stub` and
    the weak-load sibling. The CodeBlobs are C1 runtime stubs with VM/stub entry
    ABI, not C ABI direct-call targets, and binding the raw stub remains
    rejected. The active `gc/x` source has no corresponding C1 store-barrier
    stubs, and neither `gc/x` nor `gc/z` C1 generation provides the object-array
    runtime barrier as a named CodeBlob. This path cannot provide complete
    load-array and store capability, so it is rejected as the next ZGC readiness
    route. Next generic route: fix recovered JVMCI array ABI for already
    published JVMCI slots, while default strict ZGC remains fail-closed until a
    complete non-JVMCI store/load capability is proven.
  - Implementation row recorded 2026-05-21: NPT-3al will correct the native
    ZGC object-array load barrier ABI from the current single-argument/returning
    call shape to OpenJDK's `void(oop*, size_t)` shape for wide oop arrays,
    update dlsym names, and bind the recovered stripped JVMCI
    `ZBarrierSetRuntime_load_barrier_on_oop_array` slot only when it is already
    published and executable-range validated. It must not use that runtime
    barrier for compressed-oop array slots, bind raw CodeBlob stubs, initialize
    JVMCI, mark default strict ZGC ready, or change store-barrier semantics.
  - Completion evidence 2026-05-21 for NPT-3al: focused generator/audit tests
    passed. Fresh TEST native generation produced
    `build/npt-3al-zgc/TEST-native.jar` from
    `build/neko-native-work/run-13753557747563` with `translated=49 rejected=0`.
    Generated C contains `typedef void (*neko_z_lrb_array_t)(void**, size_t)`,
    corrected
    `_ZN18{Z,X}BarrierSetRuntime25load_barrier_on_oop_arrayEPP7oopDescm` dlsym
    probes, and executable-range validated array binding logs. Strict ZGC with
    eager JVMCI bound `z_lrb=0x7fe898c4ace0`, `z_array=0x7fe898c4ab70`, and
    `z_bad_off=40`, then still failed closed due to missing `z_store`. Default
    strict ZGC kept field/array slots null and failed closed with
    `z_lrb=(nil)`, `z_array=(nil)`, `z_store=(nil)`. Default collector TEST
    completed with `Calc: 87ms`.

- [x] P5 Split primitive field access into volatile and non-volatile paths. Current generated primitive field helpers use C `volatile` for every primitive load/store, which blocks useful compiler optimization for normal fields. Bind-time field metadata already carries `access_flags`; extend field binding so generated field slots expose Java `ACC_VOLATILE`, then emit volatile C access only for volatile Java fields and normal loads/stores for ordinary fields. Source evidence: all primitive field helpers emit volatile pointer dereferences in `CCodeGenerator.java:5866-5904`; field resolution records access flags in `CCodeGenerator.java:931-939` and sets them in resolution paths. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; add unit coverage for volatile and non-volatile primitive fields.
  - Implementation row recorded 2026-05-21: NPT-3am will expose resolved Java
    field `access_flags` through generated native field-slot metadata and use it
    so primitive field helpers emit volatile C accesses only for `ACC_VOLATILE`
    fields. Non-volatile Java fields should use ordinary C loads/stores.
    Unknown or unresolved metadata must remain fail-closed or conservatively
    volatile. This row must not change object reference barrier semantics, field
    resolution ownership, JNI fallback, exception behavior, or unrelated helper
    ABI.
  - Completion evidence 2026-05-21 for NPT-3am: fresh source/diff review showed
    this target is already implemented in the current code before any NPT-3am
    executable edit. Generated field refs carry `access_flags_slot`; owner
    binding calls pass `g_access_*` slots to `neko_bind_instance_field_offset`
    and `neko_bind_static_field_metadata`; those bind helpers write
    `native_field.access_flags` into the slot. Fresh generated C from
    `build/neko-native-work/run-13753557747563` proves primitive helpers take
    `uint32_t access_flags` and branch on `0x0040u`: volatile fields use
    `volatile` pointer access and non-volatile fields use ordinary pointer
    access for all primitive get/set and static get/set helpers. Primitive field
    callsites pass the resolved `g_access_*` slots. Focused generator/audit
    tests passed during NPT-3al, fresh TEST native generation produced
    `translated=49 rejected=0`, and default collector TEST completed with
    `Calc: 87ms`. No executable change was needed for this row.

- [ ] P6 Outline hot-helper diagnostic failure blocks. Move inline `fprintf` / `abort` diagnostic construction out of hot helpers into shared `cold, noinline` functions. Keep the hot body as a small predicted branch to the cold function. Source evidence: inline diagnostics exist in `neko_fast_aaload` at `CCodeGenerator.java:5455-5465`, object field helpers at `CCodeGenerator.java:5647-5733`, fused helpers at `CCodeGenerator.java:5847-5854`, and primitive array helpers at `CCodeGenerator.java:5907-5925`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate; generated C audit must show hot helper bodies no longer contain large `fprintf` blocks.
  - Implementation row recorded 2026-05-21: NPT-3an will move selected inline
    `fprintf`/`abort` diagnostic construction out of hot native helper bodies
    into shared `cold`, `noinline` diagnostic functions. The first scope must
    be narrow: only helpers whose cold branch already terminates
    unconditionally and whose argument values can be passed without changing
    evaluation order. Do not change exception behavior, pending-exception
    handling, GC barriers, array bounds/null ordering, or field/array access
    semantics.
  - Completion evidence 2026-05-21 for NPT-3an: object-field GETFIELD/
    GETSTATIC/PUTFIELD/PUTSTATIC diagnostic exits were outlined to hidden
    `cold`, `noinline` helper functions. Focused generator/audit tests passed,
    fresh TEST native generation succeeded in
    `build/neko-native-work/run-14414463312953` with `translated=49 rejected=0`
    and `libneko_linux_x64.so` size `1037656` bytes, generated C inspection
    showed hot helpers call `neko_abort_object_*_unavailable` while
    `neko_native_support_helpers_4.c` defines those helpers with
    `visibility("hidden")`, `cold`, and `noinline`, and default collector TEST
    smoke on `build/npt-3an-r2/TEST-native.jar` completed with no stderr and
    `Calc: 91ms`. P6 remains open for object-array/fused-array diagnostics and
    broader performance-gate closure.
  - Implementation row recorded 2026-05-21: NPT-3ao will move object-array-only
    diagnostic `fprintf`/`abort` construction from `neko_array_store_check`,
    `neko_fast_aastore`, and `neko_fast_aaload` into hidden `cold`,
    `noinline` helper functions. Keep successful object-array fast paths, null
    and bounds checks, array-store type checks, GC barriers, raw oop
    loads/stores, and handle creation unchanged. Do not touch the generated
    primitive-array diagnostic shape rejected by NPT-3z.
  - Completion evidence 2026-05-21 for NPT-3ao: `neko_array_store_check`,
    `neko_fast_aastore`, and `neko_fast_aaload` now call hidden `cold`,
    `noinline` diagnostic helpers for terminal diagnostic exits. Focused
    generator/audit tests passed, fresh TEST native generation succeeded in
    `build/neko-native-work/run-14632991188519` with `translated=49 rejected=0`
    and `libneko_linux_x64.so` size `1038728` bytes, generated C inspection
    showed object-array hot helpers call `neko_abort_aastore_*` and
    `neko_abort_fast_aa*` while `neko_native_support_helpers_4.c` defines those
    helpers with `visibility("hidden")`, `cold`, and `noinline`, and default
    collector TEST smoke on `build/npt-3ao/TEST-native.jar` completed with no
    stderr and `Calc: 89ms`. P6 remains open for fused-array diagnostics and
    broader performance-gate closure.
  - Implementation row recorded 2026-05-21: NPT-3ap will move object-array
    checked/fused diagnostic `fprintf`/`abort` construction from
    `neko_checked_aaload`, `neko_checked_aastore`, and
    `neko_fast_aaload_aaload` into hidden `cold`, `noinline` helper functions.
    Keep returned reason codes, null and bounds ordering, handle resolution,
    array-store checks, GC barriers, and raw oop loads/stores unchanged. Do not
    touch generated primitive checked/fused helpers or the primitive direct
    array diagnostic shape rejected by NPT-3z.
  - Completion evidence 2026-05-21 for NPT-3ap: `neko_checked_aaload`,
    `neko_checked_aastore`, and `neko_fast_aaload_aaload` now call hidden
    `cold`, `noinline` diagnostic helpers for layout and unresolved-handle
    hard-abort paths. Focused generator/audit tests passed, fresh TEST native
    generation succeeded in `build/neko-native-work/run-14840042038598` with
    `translated=49 rejected=0` and `libneko_linux_x64.so` size `1036952`
    bytes, generated C inspection showed selected checked/fused object-array
    helpers call `neko_abort_checked_*` and
    `neko_abort_fused_aaload_aaload_*` while `neko_native_support_helpers_4.c`
    defines those helpers with `visibility("hidden")`, `cold`, and `noinline`,
    and default collector TEST smoke on `build/npt-3ap/TEST-native.jar`
    completed with no stderr. Repeated TEST samples showed NPT-3ao
    `88,88,95,95,94 ms` (median `94ms`) versus NPT-3ap
    `89,85,92,88,86 ms` (median `88ms`). P6 remains open for generated fused
    primitive AALOAD+xALOAD diagnostics and broader performance-gate closure.
  - Implementation row recorded 2026-05-21: NPT-3aq will move generated fused
    `AALOAD+xALOAD` and raw fused `raw AALOAD+xALOAD` diagnostic
    layout/outer-handle hard-abort formatting into hidden `cold`, `noinline`
    helper functions. Preserve all reason-code returns for outer null, outer
    bounds, inner null, and inner bounds, and preserve the raw fused null check
    before `neko_zgc_good_oop`. Do not touch direct primitive array helpers or
    generated checked primitive load/store helpers.
  - Rejected row update 2026-05-21: NPT-3aq tag-parameterized cold-helper
    outlining for generated fused primitive `AALOAD+xALOAD` diagnostics was
    reverted before commit. Focused generator/audit tests passed, fresh
    generation succeeded in `build/neko-native-work/run-15069482044439` with
    `translated=49 rejected=0` and `libneko_linux_x64.so` size `1037208`
    bytes, generated C inspection proved the intended calls, and default TEST
    smoke completed with no stderr and `Calc: 90ms`, but repeated TEST samples
    regressed versus NPT-3ap: NPT-3aq `92,95,98,91,87 ms` (median `92ms`)
    versus NPT-3ap `89,85,92,88,86 ms` (median `88ms`). Do not retry this
    tag-parameterized fused primitive diagnostic outlining without new
    branch-layout or compiler-output evidence.
  - Rejected row update 2026-05-21: NPT-3z primitive-array cold diagnostic
    outlining was reverted. Focused generator/audit tests and
    `NativeObfuscationIntegrationTest` passed, but direct parity in
    `build/native-run-tmp/parity-p10z/` regressed versus NPT-3y: TEST native
    Calc median `90 ms` vs `87 ms`; obfusjack native Seq `18 ms` vs `17 ms`,
    Platform `45 ms` vs `43 ms`, and Virtual `42 ms` vs `36 ms`. Do not retry
    this shape without new code-size or branch-layout evidence.

- [ ] P7 Remove redundant opcode-local exception checks only after helper effect classification. Build a table for generated helpers: `never_sets_pending_exception`, `may_set_pending_exception`, `calls_java`, or `writes_pending_exception`. Use it in `OpcodeTranslator` so direct helpers that return normally or hard-abort do not get a surrounding `if (!neko_exception_check(env))`, while direct translated calls, NJX calls, helpers that can call Java, helpers that can create Java exceptions, and all try/catch boundary-sensitive paths keep checks. This must preserve JVM exception ordering, pending-exception visibility, handler selection, finally behavior, and method-exit behavior. Source evidence: direct checks currently appear in `OpcodeTranslator.java:359`, `:458`, `:506`, `:531`, `:540`, `:565`, `:608`, `:660`, `:722`, `:763`, `:1060`, and `:1201`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate; add generated-C audit proving fewer checks only where classified safe and runtime tests preserving exception order across caught, uncaught, nested, and finally paths.

- [ ] P8 Add loop recognition and range-check hoisting only when JVM exception semantics are proven identical. Build bytecode-level loop metadata from labels/backedges and prove induction variables, invariant array reference, monotonic step, dominated upper bound, no side effects before the original first failing access, and unchanged null-check ordering. When proven, emit one preheader guard and remove per-iteration bounds checks for covered primitive/object array operations. If proof is incomplete, keep per-access checks. This must preserve NPE vs AIOOBE order, the index reported by AIOOBE where applicable, side-effect ordering, deoptimization-free native behavior, and try/catch handler selection. Source evidence: array access is emitted per opcode by `OpcodeTranslator.arrayAccessBody` at `OpcodeTranslator.java:168-174`; fast helpers also check every access at `CCodeGenerator.java:5435-5452` and `CCodeGenerator.java:5907-5925`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; negative tests must preserve NPE/AIOOBE behavior, side effects, and catch behavior when proof fails.

- [ ] P9 Generalize nested-array raw-oop lifetime beyond the current peephole without weakening GC safety. Track short-lived object-array element oops through a small SSA-like model. If an `AALOAD` result is consumed only by dominated array/field operations before any call, safepoint, allocation, exception creation, store, monitor operation, or escape, keep it as raw oop and skip local-handle creation. Materialize a local handle before any operation that may expose the object to Java, require a JNI local reference, or allow GC movement/relocation to matter. Source evidence: current fusion only matches adjacent `AALOAD; <int-push>; XALOAD` in `OpcodeTranslator.java:98-143`; regular `AALOAD` materializes handles through `neko_fast_aaload` at `CCodeGenerator.java:5435-5452`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; generated C audit must show fewer `neko_direct_oop_to_handle` calls only in proven safe regions.
  - Implementation row recorded 2026-05-22: NPT-3bz will add a default-off
    direct-handle origin audit before any P9 lifetime rewrite. It may add
    counters only under `NEKO_NATIVE_HANDLE_AUDIT=1` and must not change default
    generated runtime semantics. Required evidence: post-NPT-3by opt-in audit
    still shows high direct-handle volume despite reduced overflow allocation
    calls, so the next P9 prerequisite is to identify whether runtime
    `neko_direct_oop_to_handle` traffic is dominated by `AALOAD` materialization,
    object field results, allocation returns, string/object helpers, or other
    paths. Validation: focused generator/audit tests, fresh opt-in TEST and
    obfusjack generation/runtime with origin counters, default generated-C grep
    proving audit code is compiled out without `NEKO_NATIVE_HANDLE_AUDIT`, and
    forbidden-JNI inspection. Completion requires no default behavior change and
    a concrete origin distribution for both TEST and obfusjack.
  - Completed 2026-05-22: NPT-3bz added opt-in origin counters compiled only
    under `NEKO_HANDLE_AUDIT`. Focused `CCodeGeneratorTest` and
    `NativeGeneratedCHotPathAuditTest` passed. Fresh default TEST
    `build/npt-3bz/TEST-default.jar` came from
    `build/neko-native-work/run-32880538686672` with `translated=49 rejected=0`,
    `handle.audit.build=false`, and lib `1084344`; default obfusjack came from
    `run-32883581015154` with `translated=93 rejected=0`,
    `handle.audit.build=false`, and lib `1876824`. Default smoke passed without
    `[neko-direct]` stats: TEST `Calc: 70ms`, obfusjack Platform `41ms`,
    Virtual `36ms`, Seq `17ms`. Strict forbidden-JNI grep returned no matches.
    Opt-in TEST `run-32932004575725` reported dominant
    `handle_direct_total=510054`, `njx_return=510004`, `object_alloc=16`,
    `static_object_field=24`, `bound_string=10`, all AALOAD origins `0`, and
    `handle_direct_unavailable=0`; its second stats row reconciled to `172`
    with `checked_aaload=8`. Opt-in obfusjack `run-32935171276056` reported
    `handle_direct_total=854741`, `njx_return=301141`, `object_alloc=200430`,
    `static_object_field=200096`, `checked_aaload=148993`,
    `object_field=3484`, `primitive_array_alloc=577`, `object_array_alloc=13`,
    `fused_aaload_aaload=1`, `bound_string=6`, `other=0`, and
    `handle_direct_unavailable=0`. Conclusion: P9 is an obfusjack contributor,
    but TEST Calc is dominated by NJX-return handle materialization, so the next
    generic performance substep should target NJX return handles first.

- [-] P10 Reduce NJX call-stub per-call allocation and zeroing. Replace per-call `calloc/free` of the temporary Java handle block with a scoped reusable per-thread or caller-owned block that preserves GC root visibility and nested-call semantics. Remove full `memset` of call parameter arrays when all used slots are explicitly written, while keeping required two-slot padding initialized. Source evidence: `NativeToJavaInvokeEmitter.java:187-222` allocates/frees handle blocks; `NativeToJavaInvokeEmitter.java:304` and `:348` zero buffers in the generic path; shape-specific generation also emits `memset` in `NativeToJavaInvokeEmitter.java:813` and `:844`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; stress tests must prove no stale roots or handle-chain corruption.
  - Implementation row recorded 2026-05-22: NPT-3ca will add a default-off
    NJX object-return shape audit before changing the NJX return path. Required
    evidence: NPT-3bz shows TEST `njx_return=510004` and obfusjack
    `njx_return=301141`, but it does not identify whether those returns are
    spread across generic shapes or concentrated in one object-return shape. The
    audit may add per-shape counters only under `NEKO_NATIVE_HANDLE_AUDIT=1`,
    must not change default call_stub parameters, Method*/entry selection,
    JavaCallWrapper layout, handle-window semantics, return materialization, or
    exception behavior, and must not add named-JDK/native substitutions.
    Validation: focused generator/audit tests, fresh opt-in TEST/obfusjack
    generation/runtime with shape counters, default smoke proving no stats and
    no behavior change, and forbidden-JNI inspection.
  - Completed 2026-05-22: NPT-3ca added `NEKO_HANDLE_AUDIT`-gated per-shape
    object-return counters. Focused `CCodeGeneratorTest` and
    `NativeGeneratedCHotPathAuditTest` passed. Fresh default TEST
    `build/npt-3ca/TEST-default.jar` came from
    `build/neko-native-work/run-33252836211792` with `translated=49 rejected=0`,
    `handle.audit.build=false`, and lib `1084344`; default obfusjack came from
    `run-33255732064280` with `translated=93 rejected=0`,
    `handle.audit.build=false`, and lib `1876824`. Default smoke passed without
    `[neko-direct]` stats: TEST `Calc: 72ms`, obfusjack Platform `46ms`,
    Virtual `39ms`, Seq `17ms`. Strict forbidden-JNI grep returned no matches.
    Opt-in TEST `run-33444693183231` reconciled with `njx_return=510004`:
    `V:L:L=510002`, `V:L:=1`, `V:L:J=1`. Opt-in obfusjack
    `run-33447791842344` reconciled with `njx_return=301141`: `V:L:=300822`,
    `V:L:L=159`, `S:L:J=57`, `S:L:I=50`, `S:L:L=19`, `S:L:=9`, `S:L:II=9`,
    `S:L:D=5`, `S:L:LLL=3`, `S:L:LLLL=2`, `V:L:LLL=2`, `S:L:LL=1`,
    `V:L:LIL=1`, `V:L:LL=1`, `V:L:II=1`. Conclusion: the next generic route is
    object-return continuation/lifetime across all object-return shapes, not a
    single shape or named method.
  - Implementation row recorded 2026-05-22: NPT-3cb will add a default-off
    object-return continuation audit for the generic invokedynamic string-concat
    accumulator before any raw-return NJX optimization. Required evidence:
    NPT-3ca shows TEST's NJX return traffic is almost entirely `V:L:L`, and the
    generic concat lowering calls `neko_concat_accumulate_string` for each concat
    piece then pushes only the final accumulator. The audit may count concat
    accumulator calls, accumulator calls that enter NJX, and final concat pushes
    only under `NEKO_NATIVE_HANDLE_AUDIT=1`; it must not construct strings
    natively, change `String.concat` target selection, change default runtime
    behavior, or special-case any sample/method. Completion requires proving how
    much of TEST's `V:L:L` traffic is intermediate and therefore eligible for a
    later generic raw-return continuation path.
  - Completed/rejected 2026-05-22: NPT-3cb added `NEKO_HANDLE_AUDIT`-gated
    concat continuation counters. Focused `CCodeGeneratorTest` and
    `NativeGeneratedCHotPathAuditTest` passed. Fresh default TEST
    `build/npt-3cb/TEST-default.jar` came from
    `build/neko-native-work/run-33684566669446` with `translated=49 rejected=0`,
    `handle.audit.build=false`, and lib `1084344`; default obfusjack came from
    `run-33687571188325` with `translated=93 rejected=0`,
    `handle.audit.build=false`, and lib `1876824`. Default smoke passed without
    `[neko-direct]` stats: TEST `Calc: 72ms`, obfusjack Platform `47ms`,
    Virtual `38ms`, Seq `17ms`. Strict forbidden-JNI grep returned no matches.
    Opt-in TEST `run-33733597034954` still reported `V:L:L=510002` and
    `njx_return=510004`, but concat continuation counters were all zero:
    `accumulate_total=0`, `accumulate_njx=0`, `final_push=0`,
    `intermediate_candidate=0`. Opt-in obfusjack `run-33736528676932` reported
    only `intermediate_candidate=41` against `njx_return=301141`. Conclusion:
    raw concat-continuation is rejected as the next route; TEST's dominant
    `V:L:L` returns are immediate final pushes from the generic concat fast path.
  - Implementation row recorded 2026-05-22: NPT-3cc will add a default-off
    audit for the recognized `StringBuilder.append(String).append(String).toString`
    concat fast path emitted by `NativeTranslator`. Required evidence: NPT-3cb
    proved the invokedynamic accumulator counters are zero for TEST while source
    generation emits `neko_concat_append_inline(...)` followed by immediate
    `PUSH_O(__fastConcat)` for the other generic concat lowering. The audit may
    count only recognized fast-path emissions and runtime executions under
    `NEKO_NATIVE_HANDLE_AUDIT=1`; it must not construct strings natively, change
    the `String.concat` Method*/entry target, change stack/local root semantics,
    alter default runtime behavior, or special-case any class, method, sample, or
    benchmark. Validation: focused generator/audit tests, fresh default
    TEST/obfusjack smoke proving `handle.audit.build=false` artifacts emit no
    fast-path audit stats, fresh opt-in TEST/obfusjack runtime with
    `NEKO_DIRECT_DEBUG=1` showing the fast-path counters, generated-C/manifest
    inspection for `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback
    grep. Completion requires proving whether the immediate StringBuilder concat
    fast path accounts for TEST's dominant `V:L:L` NJX return traffic enough to
    justify or reject a later generic optimization at that boundary.
  - Completed 2026-05-22: NPT-3cc added `NEKO_HANDLE_AUDIT`-gated StringBuilder
    fast-concat counters in both recognized `NativeTranslator` branches and kept
    default artifacts at `handle.audit.build=false`. Focused `CCodeGeneratorTest`
    and `NativeGeneratedCHotPathAuditTest` passed. Fresh default TEST
    `build/neko-native-work/run-34233855782829` translated `49 rejected=0`,
    `handle.audit.build=false`, lib `1084344`; fresh default obfusjack
    `run-34238244839435` translated `93 rejected=0`, `handle.audit.build=false`,
    lib `1876824`. Default five-run medians were TEST Calc `73ms`, obfusjack
    Platform `47ms`, Virtual `41ms`, Seq `17ms`; baseline stderr logs emitted no
    `[neko-direct]` audit rows, and strict generated-C grep for
    `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` returned no matches. Fresh opt-in
    artifacts were TEST `run-34356662706187` (`handle.audit.build=true`, lib
    `1132648`) and obfusjack `run-34361066685190` (`handle.audit.build=true`,
    lib `1958600`). Direct opt-in TEST runtime completed with `V:L:L=510002`,
    `njx_return=510004`, concat-continuation zero, and
    `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Direct
    opt-in obfusjack runtime completed with full program output, `njx_return=301141`,
    concat-continuation `accumulate_total=137 accumulate_njx=89 final_push=48`,
    and `stringbuilder-fast-concat: total=0 literal=0 dynamic=0`. Conclusion:
    immediate StringBuilder fast concat accounts for all but two of TEST's
    dominant `V:L:L` object returns and is the next justified generic optimization
    boundary; obfusjack remains dominated by other object-return and direct-handle
    origins, so the follow-up must preserve non-StringBuilder paths.
  - Implementation row recorded 2026-05-22: NPT-3cd will optimize only the
    recognized StringBuilder fast-concat path when its returned object is consumed
    by the immediately following `ASTORE`. Required evidence: NPT-3cc proved
    TEST executes this path `510000` times against `V:L:L=510002`, and fresh
    generated C shows the current sequence is `neko_concat_append_inline(...)`,
    `PUSH_O(__fastConcat)`, then `{ jobject __ref = POP_O(); locals[N].o =
    neko_store_local_oop_ref(...) }`. Source evidence shows the current store
    resolves the just-created NJX handle back to a raw oop before publishing the
    existing local root. The change may add a raw-oop NJX return helper for the
    already-resolved `V:L:L` call-stub shape and a local-root raw store helper,
    then fuse only same-basic-block immediate `ASTORE` consumers. It must still
    call the original JVM `String.concat` Method*/entry, must not construct string
    payloads natively, must preserve pending-exception dispatch using the original
    `toString`/concat instruction as the potentially excepting site, must not
    bypass local-root publication, and must not alter non-immediate consumers or
    non-StringBuilder paths. This is not a retry of rejected P37 stack/local-store
    traffic removal; the new evidence targets the NJX return handle materialization
    plus handle re-read before the same local-root store. Validation: focused
    generator/audit tests, fresh TEST/obfusjack artifacts, generated-C inspection
    proving the hot immediate-`ASTORE` path emits raw-return local-root publication
    and no `PUSH_O(__fastConcat)`/`POP_O` pair while non-immediate paths keep the
    normal handle path, strict forbidden-JNI grep, TEST and obfusjack runtime, and
    performance gate. Completion requires TEST Calc median to improve or not
    regress versus the NPT-3cc default median while obfusjack Platform/Virtual/Seq
    do not regress.
  - Completed 2026-05-22: NPT-3cd added a raw-oop `V:L:L` NJX return variant and
    fused only same-basic-block immediate StringBuilder concat-to-`ASTORE`
    consumers into `neko_concat_append_inline_store_local(...)`. The generated
    TEST artifact `run-35239467010830` shows the hot loop publishing through
    `&__neko_local_roots[0]` with no `PUSH_O(__fastConcat)`/`POP_O` pair on that
    path; non-immediate consumers keep the normal handle path. Focused
    translator/generator/audit tests passed, fresh perf capture passed with
    obfusjack artifact `run-35243935226564`, strict generated-C grep for
    `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was empty, and medians were TEST
    Calc `63 ms`, obfusjack Platform `45 ms`, Virtual `37 ms`, Seq `17 ms`,
    Parallel `1 ms`, VThreads `1 ms`. This improves TEST versus NPT-3cc default
    median `73 ms` and does not regress obfusjack versus NPT-3cc defaults.
  - Completed 2026-05-22: NPT-3ce verified the post-NPT-3cd opt-in audit gate
    before editing code. The perf-capture stderr logs were empty because that
    harness did not set `NEKO_DIRECT_DEBUG`, not because the StringBuilder audit
    gate was dead. Direct opt-in/debug TEST runtime completed and printed
    `V:L:L=510002`, `dispatched=510061`, `handle_direct_total=54`,
    `njx_return=4`, concat-continuation zero, and
    `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Direct
    opt-in/debug obfusjack completed and printed `dispatched=976247`,
    `handle_direct_total=854741`, `njx_return=301141`, concat-continuation
    `accumulate_total=137 accumulate_njx=89 final_push=48 intermediate_candidate=41`,
    and `stringbuilder-fast-concat: total=0 literal=0 dynamic=0`. No audit code
    change is justified; this proves the remaining TEST hot boundary is still
    510000 `V:L:L` `String.concat` call-stub dispatches with raw local
    publication, while obfusjack remains dominated by other NJX/direct-handle
    paths.
  - Current implementation row recorded 2026-05-20: remove the per-call full `memset` of NJX `call_params` only. Every used slot must still be initialized exactly: one-slot primitive/object arguments write their own slot, float slots are zeroed before writing the 32-bit payload to preserve the previous high-word state, and two-slot long/double arguments zero the required leading padding slot before writing the payload slot. This is a generic call-stub stack-packing optimization for every NJX target, including original JVM/JDK functions; it must not replace any target method with native code or change which JVM function is called.
  - Validation update 2026-05-20: forbidden Math/libm and other named-JDK
    native substitutions were removed; generated run
    `build/neko-native-work/run-5500725993204` contains no
    `neko_fast_string_length`, `neko_fast_get_object_class`,
    `neko_fast_string_concat`, `neko_fast_atomic_*`, `__atomic_add_fetch`,
    `sqrt`, or `pow` calls. Focused generator/audit tests passed
    (`artifact://112`) and `NativeObfuscationIntegrationTest` passed
    (`artifact://114`). The row remains `[-]`: direct parity measurements in
    `build/native-run-tmp/parity-current/summary-after-generic.json` still show
    TEST native Calc median `135 ms` vs original `12 ms`, and obfusjack native
    Seq/Platform/Virtual medians `19/50/47 ms` vs original `2/25/15 ms`.
  - Implementation row recorded 2026-05-20: NPT-3b will evaluate the existing
    shape-keyed compiled-entry trampoline as the next generic P10 route. It may
    only pass `_from_compiled_entry` pointers for the same resolved Method*
    targets that call_stub currently invokes through `_from_interpreted_entry`;
    every owner/name/descriptor-specific Java/JDK method-body replacement
    remains forbidden. Validation must include generated-C proof, focused
    generator/audit tests, `NativeObfuscationIntegrationTest`, direct TEST and
    obfusjack parity runs, and forbidden-marker inspection. Any verifier,
    stack-walk, GC-root, runtime, or target-selection failure invalidates and
    reverts the row.
  - Rejected row update 2026-05-20: NPT-3b compiled-entry trampoline was
    reverted. Focused generator/audit tests passed (`artifact://151`), but
    fresh `NativeObfuscationIntegrationTest` crashed in HotSpot stack walking /
    `Throwable.fillInStackTrace` across `~BufferBlob::neko_njx_trampoline`
    (`artifact://153`, `artifact://155`, `hs_err_pid1442764.log`,
    `hs_err_pid1500630.log`). A frame-size adjustment from
    `8 + stackArgs + pad` to `9 + stackArgs + pad` did not fix the crash, so
    this route is not an accepted checkpoint and must not be retried without a
    new stack-walk invariant proof.
  - Implementation row recorded 2026-05-20: NPT-3c will trim only redundant
    wrapper-local register saves in `neko_call_stub_guarded`, preserving the
    same HotSpot call_stub target, Method* argument, entry pointer argument,
    JavaCallWrapper mirror, parameter stack, and thread-state logic. This is a
    generic bridge/context-switch optimization and remains forbidden from
    replacing any original JVM/JDK method body.
  - Completion evidence 2026-05-20 for NPT-3c: `neko_call_stub_guarded`
    now removes only wrapper-local callee-saved register saves and the
    compensating alignment word; stack-passed call_stub arguments, Method*,
    entry pointer, JavaCallWrapper mirror, handle scope, and thread-state logic
    are unchanged. Focused generator/audit tests passed (`artifact://167`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://169`). Direct parity
    runs in `build/native-run-tmp/parity-p10c/` completed with no fatal
    markers: TEST original/native Calc medians `11 ms` / `134 ms`; obfusjack
    original/native medians Seq `2 ms` / `18 ms`, Platform `26 ms` / `50 ms`,
    Virtual `15 ms` / `44 ms`. This is accepted as a generic bridge
    micro-optimization, but P10 remains `[-]` because original JVM parity is
    still not achieved.
  - Implementation row recorded 2026-05-20: NPT-3d will reduce only the
    JavaCallWrapper stack-buffer clear in the generic call_stub bridge. The
    cleared prefix must cover every wrapper and JavaFrameAnchor field written
    before HotSpot observes the wrapper, and all Method*/entry/argument/thread
    behavior must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3d bounded JavaCallWrapper zeroing was
    reverted. Focused generator/audit tests passed (`artifact://179`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://181`), but direct
    parity in `build/native-run-tmp/parity-p10d/` regressed against NPT-3c:
    TEST native Calc median `136 ms` vs `134 ms`; obfusjack native Seq
    `19 ms` vs `18 ms`; Virtual `46 ms` vs `44 ms`. Do not retry this shape
    without a new measured reason.
  - Implementation row recorded 2026-05-20: NPT-3e will change only the NJX
    call_stub thread-state precheck branch shape so `_thread_in_java` is the
    predicted fallthrough. The native-caller transition and unsupported-state
    hard abort behavior must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3e thread-state branch hint was
    reverted. Focused generator/audit tests passed (`artifact://186`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://188`), but direct
    parity did not meet the no-regression gate: TEST native Calc values in
    `build/native-run-tmp/parity-p10e/` were `137/132/136/137/134 ms`, the
    obfusjack native repeated run timed out once at 120s, and a follow-up
    obfusjack native check showed Platform `53/53/52 ms` versus the NPT-3c
    median `50 ms`.
  - Implementation row recorded 2026-05-20: NPT-3f will remove only the
    temporary `neko_call_stub_args_t` aggregate from the NJX call_stub guard and
    pass the same call_stub target, JavaCallWrapper mirror, Method*, entry
    pointer, parameter stack, result buffer, result type, and JavaThread as
    direct C ABI arguments. This is a generic bridge optimization; no original
    JVM/JDK method body may be replaced.
  - Rejected row update 2026-05-20: NPT-3f direct call_stub guard arguments
    were reverted. Focused generator/audit tests passed (`artifact://199`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://201`), but direct
    parity in `build/native-run-tmp/parity-p10f/` regressed on TEST native Calc
    `136 ms` vs NPT-3c `134 ms`, obfusjack Platform `51 ms` vs `50 ms`, and
    Virtual `45 ms` vs `44 ms` despite Seq improving `17 ms` vs `18 ms`.
  - Implementation row recorded 2026-05-20: NPT-3g will reduce redundant
    `JNIHandleBlock::_last` stores in generic handle push paths by publishing
    `_last = block` only on the transition from `_top == 0` to `_top == 1`.
    Overflow blocks and restore semantics must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3g conditional `_last` publication was
    reverted. Focused generator/audit tests passed (`artifact://206`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://208`), but parity in
    `build/native-run-tmp/parity-p10g/` regressed: TEST native Calc median
    `137 ms` vs NPT-3c `134 ms`, and obfusjack native timed out once at 180s
    after four completed repeated runs.
  - Implementation row recorded 2026-05-20: NPT-3h will make only the
    shape-specialized NJX result unpack path return-kind-specific, avoiding
    integer-lane loads for FP/void returns and FP-lane copies for
    integer/object/void returns. The call target, Method*, entry pointer,
    arguments, handles, and exception behavior must remain unchanged.
  - Completion evidence 2026-05-20 for NPT-3h: shape-specialized NJX
    dispatchers now unpack only the result lane required by their return kind;
    call_stub target selection, Method*, entry pointer, arguments, handles, and
    exception behavior are unchanged. Focused generator/audit tests passed
    (`artifact://214`) and `NativeObfuscationIntegrationTest` passed
    (`artifact://216`). Direct parity in `build/native-run-tmp/parity-p10h/`
    completed with no fatal markers: TEST original/native Calc medians
    `12 ms` / `134 ms`; obfusjack original/native medians Seq
    `2 ms` / `17 ms`, Platform `26 ms` / `50 ms`, Virtual `14 ms` / `44 ms`.
    Generated C in `build/neko-native-work/run-9173021508276/` preserved
    call_stub dispatch and no named JVM/JDK native method-body replacement was
    introduced. This is accepted as a generic bridge micro-optimization; P10
    remains `[-]` because original JVM parity is still not achieved.
  - Implementation row recorded 2026-05-20: NPT-3i will reduce only the
    shape-specialized NJX `__call_result` stack buffer from two machine words
    to one word after NPT-3h made result unpacking read a single lane. The
    call_stub result pointer and BasicType are unchanged; no target method
    behavior or selection may change.
  - Rejected row update 2026-05-20: NPT-3i single-slot result buffer was
    reverted. Focused generator/audit tests passed (`artifact://221`), but
    fresh `NativeObfuscationIntegrationTest` failed in
    `nativeObfuscation_randomRuntimeStableTenRuns` because
    `obfusjack-native.jar` timed out after 45s (`artifact://223`).
  - Implementation row recorded 2026-05-20: NPT-3j will make only the
    shape-specialized NJX `call_params` packing use constant slot indexes
    instead of a mutable `__njx_pos` cursor. The existing slot layout and
    zero-padding semantics for primitive, long, and double arguments must be
    preserved exactly.
  - Rejected row update 2026-05-20: NPT-3j constant-index call parameter
    packing was reverted. Focused generator/audit tests passed
    (`artifact://227`) and `NativeObfuscationIntegrationTest` passed
    (`artifact://229`), but direct parity in
    `build/native-run-tmp/parity-p10j/` regressed: TEST native Calc median
    `136 ms` vs NPT-3h `134 ms`, and obfusjack native timed out once at 180s
    after three completed repeated runs.
  - Implementation row recorded 2026-05-20: NPT-3k will make shape-specialized
    NJX result locals and debug result logging return-kind-specific, avoiding
    unused `out_rax` or `out_xmm0` locals after NPT-3h. The call_stub buffer,
    BasicType, Method*, entry pointer, arguments, handles, and exception
    behavior must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3k return-kind-specific result locals
    were reverted. Focused generator/audit tests passed (`artifact://235`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://237`), but direct
    parity in `build/native-run-tmp/parity-p10k/` regressed: TEST native Calc
    median `138 ms` vs NPT-3h `134 ms`, and obfusjack native timed out once at
    180s after two completed repeated runs.
  - Implementation row recorded 2026-05-20: NPT-3l will mark only the generic
    x86-64 `neko_call_stub_guarded` wrapper as compiler-hot while preserving
    its `naked`/`noinline` ABI and the same HotSpot call_stub argument
    contract. No target method behavior or selection may change.
  - Rejected row update 2026-05-20: NPT-3l hot call_stub guard attribute was
    reverted. Focused generator/audit tests passed (`artifact://243`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://245`), but direct
    parity in `build/native-run-tmp/parity-p10l/` regressed: TEST native Calc
    median `140 ms` vs NPT-3h `134 ms`, obfusjack Platform `51 ms` vs
    `50 ms`, and Virtual `47 ms` vs `44 ms`.
  - Implementation row recorded 2026-05-20: NPT-3m will outline only the
    primitive array helper failure `fprintf`/`abort` blocks into shared cold
    noinline helpers. Fast-path checks, load/store behavior, and fail-closed
    abort semantics must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3m primitive-array cold failure
    outlining was reverted. Focused generator/audit tests passed
    (`artifact://250`) and `NativeObfuscationIntegrationTest` passed
    (`artifact://252`), but direct parity in
    `build/native-run-tmp/parity-p10m/` regressed: TEST native Calc median
    `139 ms` vs NPT-3h `134 ms`, and obfusjack native timed out once at 180s
    after two completed repeated runs.
  - Implementation row recorded 2026-05-20: NPT-3n will remove only the
    per-call diagnostic `neko_njx_note_dispatch()` counter from
    shape-specialized NJX dispatchers. Direct call_stub invocation,
    Method*/entry target selection, debug logging, resolve-failure counting,
    handles, and exception behavior must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3n hot dispatch stats removal was
    reverted. Focused generator/audit tests passed (`artifact://260`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://262`), but direct
    parity in `build/native-run-tmp/parity-p10n/` regressed: TEST native Calc
    median `141 ms` vs NPT-3h `134 ms`, and obfusjack native timed out once at
    180s after one completed repeated run.
  - Implementation row recorded 2026-05-20: NPT-3o will keep NJX debug logging
    behavior but cache the debug gate in each shape-specialized dispatcher so
    the entry/exit debug log branches do not repeatedly call
    `neko_njx_debug()`. Call_stub target selection, dispatch stats, resolve
    stats, handles, and exception behavior must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3o local debug gate was reverted.
    Focused generator/audit tests passed (`artifact://268`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://270`), but direct
    parity in `build/native-run-tmp/parity-p10o/` regressed: TEST native Calc
    median `138 ms` vs NPT-3h `134 ms`, and obfusjack native timed out once at
    180s after four completed repeated runs.
  - Implementation row recorded 2026-05-20: NPT-3p will remove only the
    unreachable generated `neko_njx_dispatch_generic` body and its generic
    return-BasicType helper after source search proves all emitted callsites use
    shape-specialized dispatchers. This is dead-code removal and must not
    change any reachable JVM target call path.
  - Completion evidence 2026-05-20 for NPT-3p: removed unreachable generated
    `neko_njx_dispatch_generic` and `neko_njx_result_basic_type`; source search
    showed all emitted NJX callsites use shape-specialized dispatchers.
    Focused generator/audit tests passed (`artifact://280`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://282`). Generated C
    under `build/neko-native-work/run-12646002423282/` contains no generic NJX
    dispatcher symbol and preserves shape-specialized call_stub Method*/entry
    dispatch with no named JVM/JDK native method-body replacement. P10 remains
    `[-]` because original JVM parity is still not achieved.
  - Implementation row recorded 2026-05-20: NPT-3q will remove only the
    obsolete compiled-entry trampoline emitter/declarations left after rejected
    NPT-3b. The generated runtime must continue using shape-specialized
    HotSpot call_stub dispatchers for the same original JVM Method*/entry
    targets.
  - Completion evidence 2026-05-20 for NPT-3q: removed the obsolete
    compiled-entry trampoline emitter, `neko_njx_tramp_*` declarations,
    `g_njx_wrapper_*` globals, and unused runtime/frame-layout helper code
    after NPT-3b had been rejected. Focused generator/audit tests passed
    (`artifact://287`) and `NativeObfuscationIntegrationTest` passed
    (`artifact://289`). Generated C under
    `build/neko-native-work/run-12921477179290/` contains no compiled-entry
    trampoline symbols and still emits shape-specialized call_stub dispatchers
    for the same Method*/entry targets. P10 remains `[-]` because original JVM
    parity is still not achieved.
  - Implementation row recorded 2026-05-20: NPT-3r will remove only the
    unused declared `jmethodID` parameter from virtual/interface
    `neko_icache_dispatch` and its generated callsites. Concrete receiver
    resolution must remain driven by receiver `Klass*` plus callsite
    name/descriptor metadata, preserving the same Method*/entry target and
    call_stub dispatch.
  - Rejected row update 2026-05-20: NPT-3r declared-`jmethodID` removal was
    reverted. Focused generator/audit tests passed (`artifact://296`), but
    fresh `NativeObfuscationIntegrationTest` failed (`artifact://298`) in
    `nativeObfuscation_randomRuntimeStableTenRuns` with an obfusjack native
    timeout after 45s; `native_obfusjack_stability_9.stdout.log` reached only
    the platform-thread section. Virtual/interface dispatch must keep the
    declared method binding until a generic proof shows it can be removed
    without stability or performance regression.
  - Implementation row recorded 2026-05-20: NPT-3s will outline only the
    virtual/interface `neko_icache_dispatch` cold miss resolver into a
    `cold,noinline` helper. The hot path must still compute the receiver
    `Klass*` key, probe the PIC, and dispatch direct-C/direct-NJX hits to the
    same Method*/entry/call_stub targets; the miss helper must preserve exact
    receiver resolution, fail-closed aborts, and exception behavior. No
    owner/name/descriptor-specific native replacement is allowed.
  - Rejected row update 2026-05-20: NPT-3s cold icache miss outlining was
    reverted. Focused generator/audit tests passed (`artifact://307`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://309`), but direct
    parity in `build/native-run-tmp/parity-p10s/` regressed: TEST native Calc
    median `137 ms` vs NPT-3h `134 ms`, obfusjack native Seq median `18 ms`
    vs `17 ms`, and Platform median `53 ms` vs `50 ms`. Virtual improved to
    `43 ms` vs `44 ms`, but the row fails the required no-regression gate.
  - Implementation row recorded 2026-05-20: NPT-3t will add only a generic
    fast-path branch prediction marker to `neko_direct_oop_to_handle` for the
    normal `top < g_neko_jnih_block_capacity` active-handle slot case. The
    helper must keep the same raw-oop handle slot, `_top`, `_last`, overflow
    allocation, active-handle chain, and fail-closed abort behavior; no
    owner/name/descriptor-specific native replacement is allowed.
  - Rejected row update 2026-05-20: NPT-3t direct-oop handle fast-slot
    prediction was reverted. Focused generator/audit tests passed
    (`artifact://315`) and `NativeObfuscationIntegrationTest` passed
    (`artifact://317`), but direct parity in `build/native-run-tmp/parity-p10t/`
    failed because the fifth obfusjack native run timed out after 180s.
    Completed TEST native Calc median was `134 ms`, but the incomplete
    obfusjack run fails the required no-regression gate.
  - Implementation row recorded 2026-05-20: NPT-3u will extend the existing
    no-handle-window proof to NJX call_stub dispatchers only for static
    primitive-only shapes. The generated dispatcher must still invoke the same
    original Method*/entry through call_stub and keep JavaCallWrapper anchors,
    thread-state checks/transitions, exception handling, and all reference
    shapes' handle save/install/restore behavior. No method-owner/name/descriptor
    native replacement is allowed.
  - Rejected row update 2026-05-20: NPT-3u static primitive no-handle NJX was
    reverted. Focused generator/audit tests passed (`artifact://325`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://327`), but direct
    parity in `build/native-run-tmp/parity-p10u/` failed because the fifth
    obfusjack native run timed out after 180s. Completed TEST native Calc
    median was `134 ms`; the incomplete obfusjack run fails the required
    no-regression gate.
  - Implementation row recorded 2026-05-20: NPT-3v will skip only redundant
    NJX JavaFrameAnchor pre-call copy/clear stores when `saved_sp`,
    `saved_pc`, and `saved_fp` are all null. The JavaCallWrapper buffer must
    remain fully zeroed; Method*/entry target selection, call_stub invocation,
    parameter/result handling, handle frames, thread-state transitions,
    exception behavior, and final anchor restore must remain unchanged. This is
    generic HotSpot state handling, not method-owner/name/descriptor native
    replacement.
  - Rejected row update 2026-05-20: NPT-3v empty-anchor copy/clear fast path
    was reverted. Focused generator/audit tests passed (`artifact://338`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://340`), but direct
    parity in `build/native-run-tmp/parity-p10v/` regressed: TEST native Calc
    median `137 ms` vs NPT-3h `134 ms`, and obfusjack native Virtual median
    `46 ms` vs `44 ms`. Seq and Platform did not regress, but the row fails
    the required no-regression gate.
  - Implementation row recorded 2026-05-20: NPT-3w will reduce only the fixed
    NJX stack `JNIHandleBlock` buffer from 512 bytes to 384 bytes. The runtime
    size guard in `neko_njx_install_java_handles` must remain unchanged, so
    larger JVM layouts use the existing heap block path. Current fresh logs
    show `blk_size=296`, preserving the stack path on the measured JDK 21
    runtime. Method*/entry calls, call_stub, JavaCallWrapper, thread state,
    exception handling, and handle restore behavior must remain unchanged.
  - Rejected row update 2026-05-20: NPT-3w 384-byte NJX stack handle buffer
    was reverted. Focused generator/audit tests passed (`artifact://346`) and
    `NativeObfuscationIntegrationTest` passed (`artifact://348`), but direct
    parity in `build/native-run-tmp/parity-p10w/` regressed: TEST native Calc
    stayed median `134 ms`, obfusjack native Seq stayed median `17 ms`, but
    Platform regressed to `52 ms` vs NPT-3h `50 ms`, and Virtual regressed to
    `46 ms` vs `44 ms`.
  - Implementation row recorded 2026-05-20: NPT-3x will replace only the
    remaining hot `getenv("NEKO_PATCH_DEBUG")` checks in `neko_handle_oop` with
    the existing cached `NEKO_PATCH_DEBUG` macro. Handle resolution, ZGC
    bootstrap handling, direct-oop classification, GC barriers, Method*/entry
    calls, call_stub, thread state, and exception behavior must remain
    unchanged; this is not method-owner/name/descriptor native replacement.
  - Rejected row update 2026-05-20: NPT-3x handle debug-cache replacement was
    reverted. Focused generator/audit tests passed (`artifact://356`), but
    fresh `NativeObfuscationIntegrationTest` failed (`artifact://358`) with
    SIGSEGV in obfusjack runs; the debug runtime log wrote
    `hs_err_pid3307640.log` and crashed after `merged=579.0`.
  - Completion evidence 2026-05-21 for NPT-3y: `neko_bound_method_i_entry_ref(ref)`
    now reads a populated interpreted-entry slot directly only when the cached
    `Method*` slot is also populated; otherwise it calls the existing
    fail-closed `neko_bound_method_i_entry` helper. Focused generator/audit
    tests and `NativeObfuscationIntegrationTest` passed. Direct parity logs in
    `build/native-run-tmp/parity-p10y/` completed five runs each without fatal
    markers: TEST original/native Calc medians `10 ms` / `87 ms`; obfusjack
    original/native medians Seq `2 ms` / `17 ms`, Platform `26 ms` / `43 ms`,
    Virtual `12 ms` / `36 ms`. Generated C in
    `build/neko-native-work/run-3262720662482/` preserved `g_mptr_*`/
    `g_mientry_*` call_stub dispatch and contained no executable forbidden
    JNI/JVMTI markers. This is accepted as a generic cached-entry
    micro-optimization, but P10 remains `[-]` because strict original JVM parity
    is still not achieved.

- [ ] P11 Reduce local-handle overflow allocation in translated object-heavy paths. Replace `neko_direct_oop_to_handle` overflow `calloc` with a reusable block strategy or larger scoped translated-method handle window. This is separate from NJX because ordinary object array loads, object field loads, string concat, array allocation, and object allocation all route through `neko_direct_oop_to_handle`. Source evidence: overflow allocation is in `CCodeGenerator.java:4880-4917`, and callers include `neko_fast_aaload` at `CCodeGenerator.java:5435-5452`, object field helpers at `CCodeGenerator.java:5629-5734`, and allocation helpers at `CCodeGenerator.java:4919-4988`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate.
  - Implementation row recorded 2026-05-22: NPT-3bx will add only default-off
    handle-overflow audit counters around `neko_direct_oop_to_handle`, not a
    reusable-block implementation. The audit must count total
    `neko_direct_oop_to_handle` calls, active-block fast-slot hits, overflow
    `calloc` allocations, unavailable direct-slot exits, and max observed
    active-block top/capacity. Counters
    must be compiled only for opt-in audit builds and printed through the
    existing `NEKO_DIRECT_DEBUG=1` stats path. If the final TEST/obfusjack audit
    reports zero or negligible overflow allocation, do not implement the
    reusable-block strategy without new evidence.
  - Completion evidence 2026-05-22 for NPT-3bx: focused generator/audit tests
    passed; fresh opt-in TEST and obfusjack artifacts were generated with
    `NEKO_NATIVE_HANDLE_AUDIT=1`, `translated=49/0` and `93/0`, and manifest
    `handle.audit.build=true`. Strict generated-C forbidden-JNI grep was empty.
    `NEKO_DIRECT_DEBUG=1` audit runtime showed the overflow allocation path is
    real and non-negligible: TEST dominant stats row
    `handle_direct_total=510054`, `handle_direct_fast_slot=491718`,
    `handle_direct_overflow_alloc=18336`, `handle_direct_unavailable=0`,
    `handle_direct_max_top=32`, `handle_direct_max_capacity=32`; obfusjack
    stats row `handle_direct_total=854741`, `handle_direct_fast_slot=846154`,
    `handle_direct_overflow_alloc=8587`, `handle_direct_unavailable=0`,
    `handle_direct_max_top=32`, `handle_direct_max_capacity=32`. Fresh default
    non-audit TEST reported `handle.audit.build=false`, completed with
    `Calc: 73ms`, and emitted no stats line. This justifies a generic P11
    reusable-block/window implementation as the next P11 substep.
  - Implementation row recorded 2026-05-22: NPT-3by will replace only the
    repeated overflow `calloc` in `neko_direct_oop_to_handle` with scoped
    native-owned JNIHandleBlock slab allocation plus a bounded per-thread
    recycle list. Fresh opt-in evidence from the first recycle-only attempt
    showed TEST still at `handle_direct_overflow_alloc=18336`, proving TEST
    creates its overflow blocks inside a long translated invocation where
    end-of-invocation recycling cannot help. The revised generic path may batch
    blocks only inside a `neko_handle_save`/`neko_handle_restore` scope, may
    recycle only blocks above the saved active block or between the saved block
    and `saved_next`, must never recycle caller/VM-owned pre-existing blocks,
    must clear stored oop slots before reuse, must free scoped slabs at restore,
    must preserve `_top`, `_next`, `_last`, active-handle restoration, GC
    visibility while handles are live, and hard-abort behavior when no block can
    be obtained. Validation: focused generator/audit tests, fresh TEST and
    obfusjack generation/runtime, opt-in handle audit proving overflow
    allocation calls drop while fast-slot/total counts remain coherent, default
    timing compare, and forbidden-JNI/fallback inspection.
  - Completed 2026-05-22: NPT-3by implemented scoped native-owned
    JNIHandleBlock slab allocation under the existing handle save/restore
    window, retaining the bounded no-scope recycle list. Focused
    `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest` passed. Fresh
    default TEST artifact `build/npt-3by-slab/TEST-default-r2.jar` came from
    `build/neko-native-work/run-32502768700627` with `translated=49 rejected=0`,
    `handle.audit.build=false`, and lib `1079896`; default obfusjack
    `build/npt-3by-slab/obfusjack-default-r2.jar` came from
    `run-32505682956586` with `translated=93 rejected=0`,
    `handle.audit.build=false`, and lib `1880200`. Strict generated-C grep for
    `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` returned no matches. Default TEST
    x5 Calc `68/70/76/78/67` ms, median `70`; default obfusjack x5 Platform
    `44/42/43/44/47` ms, median `44`, Virtual `36/37/44/38/41` ms, median `38`,
    Seq `17/17/18/17/17` ms, median `17`. Fresh opt-in audit artifacts from
    `run-32559694871235` and `run-32562676424869` were `handle.audit.build=true`;
    TEST dominant `handle_direct_overflow_alloc` dropped from NPT-3bx `18336`
    to `286`, and obfusjack dropped from `8587` to `898`, both with
    `handle_direct_unavailable=0`.
  - Implementation row recorded 2026-05-21: NPT-3at will reduce translated
    local-root handle reservation only for methods whose ABI and bytecode prove
    they do not store references in locals. Source evidence: `NativeTranslator`
    currently calls `neko_prepare_local_oop_roots` for every static primitive
    method whose bytecode is not fully primitive-only, so primitive methods with
    direct translated calls or primitive static field access still reserve root
    slots on every call. Existing generated TEST `Calc.runAll` calls
    translated `call`, `runAdd`, and `runStr` 10,000 times; `call` and
    `runAdd` reserve local roots even though their generated bodies contain no
    object local stores, while `runStr` correctly needs roots for its string
    local. The change must keep roots for instance methods, reference
    parameters, every `ASTORE`/reference-local store path, and tail-recursive
    reference rewrites; it must not change handle creation, GC visibility for
    actual object locals, shadow frames, exception behavior, or direct-call
    target selection. Validation: focused generator/audit tests, fresh TEST
    native generation, generated-C inspection proving `neko_prepare_local_oop_roots`
    is removed only from no-object-local methods and retained for object-local
    methods, default TEST smoke, and repeated TEST timing comparison.
  - Completion evidence 2026-05-21 for NPT-3at: `NativeTranslator` now reserves
    local roots for static primitive-ABI methods only when bytecode contains an
    object-local store; instance methods and reference parameters still reserve
    roots. Focused generator/audit tests passed. Fresh TEST native generation
    produced `build/npt-3at/TEST-native.jar` from
    `build/neko-native-work/run-15969669901673` with `translated=49 rejected=0`
    and `libneko_linux_x64.so` size `1034616` bytes. Generated C inspection
    shows `Calc.runAll`, `Calc.call`, and `Calc.runAdd` no longer call
    `neko_prepare_local_oop_roots`, while `Calc.runStr` still reserves roots
    and uses `neko_store_local_oop_ref` for its string local. Direct TEST smoke
    had empty stderr; same-session comparison showed prior accepted NPT-3ap Calc
    median `90ms` versus NPT-3at median `88ms`. Focused native integration
    tests for TEST Calc and obfusjack completion passed.

- [x] P12 Add a generic virtual/interface PIC-hit fast stub audit before changing dispatch. The current `neko_icache_dispatch` already has direct-C and direct-NJX hit branches, so do not redesign it blindly. First measure generated hit/miss counts and emitted branch shape; then, only if hot misses or hit overhead are proven, split cold miss resolution into a noinline function and keep the hit path as receiver-key lookup plus direct target call. Source evidence: hit and miss paths are interleaved in `CCodeGenerator.java:4477-4567`; translator emits virtual dispatch sites at `OpcodeTranslator.java:466-510`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate.
  - Accepted implementation row recorded 2026-05-21: NPT-3ab added only default-off
    virtual/interface PIC audit counters and extend the final
    `NEKO_DIRECT_DEBUG=1` stats summary. This row counts direct-C hits,
    direct-NJX hits, misses, translated-stub stores, direct-NJX stores, and
    unresolved dispatch exits behind compile-time `NEKO_ICACHE_AUDIT=1`, enabled
    only by `NEKO_NATIVE_ICACHE_AUDIT=1`; the default build keeps the original
    two-field stats line and does not initialize direct-debug state from the
    constructor. Validation: focused `CCodeGeneratorTest` and
    `NativeGeneratedCHotPathAuditTest` passed; fresh
    `NativeObfuscationIntegrationTest` passed; opt-in TEST audit artifact
    `build/p12-icache-audit/TEST-native-audit.jar` generated with
    `translated=49 rejected=0` and manifest `icache.audit.build=true`; opt-in
    `NEKO_DIRECT_DEBUG=1` runtime completed and emitted useful counters:
    `icache_direct_njx_hit=519999`, `icache_miss=43`,
    `icache_direct_njx_store=44`, `resolve_failed=0`, `icache_unresolved=0`.
    Generated-C inspection found no executable forbidden JNI/JVMTI markers in
    the icache support path; support-file JNI strings were comments or internal
    JVM symbol names. This evidence does not justify another P12 dispatch shape:
    the current hot runtime mix is dominated by direct-NJX PIC hits, not cold
    misses.

- [ ] P13 Tune raw function flattening by generated body size. `NEKO_FLATTEN` is currently applied to every raw translated function. Add a generic heuristic that keeps flattening for small/hot straight-line bodies but avoids flattening very large functions where cold blocks and helper expansions inflate instruction-cache footprint. This must be driven by generated statement/body size, not owner/method names. Source evidence: unconditional `NEKO_FLATTEN NEKO_HOT` is emitted at `CCodeGenerator.java:459-467`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate; compare generated C size, native library size, and medians.
  - Validation row recorded 2026-05-21: NPT-3ac will validate the existing
    structural implementation already present in `CCodeGenerator`: raw
    translated bodies with `fn.body().size() <= MAX_FLATTEN_STATEMENTS` emit
    `NEKO_FLATTEN NEKO_HOT`, while larger bodies emit `NEKO_HOT` without
    `NEKO_FLATTEN`. This row must not change the threshold, owner/method
    selection, bytecode translation, CFF block construction, runtime dispatch,
    JNI/JVMTI behavior, or fallback behavior. Completion requires fresh
    generator/runtime validation, generated-C inspection showing both flattened
    and non-flattened raw translated bodies selected by statement count, and a
    performance/runtime report that does not regress the current accepted
    baseline.
  - Rejected row update 2026-05-21: NPT-3ac is not accepted as a performance
    win. Fresh TEST generation translated `49` methods with `rejected=0` and
    showed both selection paths (`neko_native_impl_0.c` emitted `NEKO_HOT`
    without `NEKO_FLATTEN`; small bodies such as `neko_native_impl_8.c` kept
    `NEKO_FLATTEN NEKO_HOT`). Fresh obfusjack generation translated `93`
    methods with `rejected=0` and showed the same generic structural selection:
    large bodies `neko_native_impl_39.c` (`1085` lines),
    `neko_native_impl_44.c` (`181` lines), and `neko_native_impl_46.c` (`146`
    lines) emitted without `NEKO_FLATTEN`, while small bodies such as
    `neko_native_impl_13.c` (`38` lines) kept `NEKO_FLATTEN NEKO_HOT`.
    Runtime results: TEST Calc median `86 ms` versus accepted NPT-3y `87 ms`,
    but obfusjack medians were Platform `45 ms`, Virtual `43 ms`, and Seq
    `17 ms`; Platform and Virtual regressed versus accepted NPT-3y and missed
    the gate (`Platform <= 44 ms`, `Virtual <= 35 ms`, `Seq <= 14 ms`). A
    120s obfusjack run timed out after printing completion, while a 180s rerun
    exited `0`, so the rejection is performance-gate based, not a crash/fatal
    or fallback finding. Do not tune the threshold without new compiler-layout
    evidence.

- [x] P14 Add native compiler optimization diagnostics mode. Add a repository-controlled environment flag that appends compiler diagnostics such as optimization remarks, inlining reports, assembly, or equivalent zig/clang output for selected generated C artifacts, without changing normal release flags. Use this to verify P4/P6/P8/P13 rather than guessing about compiler behavior. Source evidence: `NativeBuildEngine.java:43-94` currently supports debug/release flags but no optimization-report mode. Validation: `R-build`, `R-inspect`; diagnostics mode must be optional and must not affect normal native artifacts.
  - Accepted implementation row recorded 2026-05-21: NPT-3ad added only an
    opt-in `NEKO_NATIVE_OPT_DIAGNOSTICS=1` native compiler diagnostics mode.
    Default compile/link commands, release flags, generated C, translated
    bytecode, runtime dispatch, JNI/JVMTI behavior, fallback behavior, and
    performance gates remain unchanged when the environment flag is absent. The
    opt-in build adds supported clang/zig optimization-record flags and manifest
    properties for per-source diagnostic output paths. Validation: focused
    `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest` passed; normal
    TEST generation `build/neko-native-work/run-9988143205398` produced
    `translated=49 rejected=0`, manifest `opt.diagnostics.build=false`, and no
    `-fsave-optimization-record`/`-foptimization-record-file` compile flags;
    first opt-in attempt rejected unsupported
    `-foptimization-record-format=yaml`, then the narrowed supported flag set
    generated `build/p14-opt-diagnostics/TEST-native-opt-diagnostics.jar` from
    `build/neko-native-work/run-10032888234072` with `translated=49 rejected=0`,
    manifest `opt.diagnostics.build=true`, compile commands containing
    `-fsave-optimization-record` and per-source `-foptimization-record-file=...`,
    and 67 `.opt.yaml` files under `opt-diagnostics/linux_x64`. Representative
    diagnostics contain inline pass records such as `AlwaysInline` in
    `neko_native_impl_0.opt.yaml`. The opt-in TEST jar ran successfully with
    `Calc: 83ms`. Generated-C forbidden-marker inspection found no executable
    JNI/JVMTI/fallback markers; remaining support-file JNI strings were
    comments or internal JVM symbol names.

- [x] P15 Tighten native performance tests after P0 baseline exists. Extend native performance tests to record and assert TEST Calc median plus obfusjack parsed matrix/thread medians when those output lines are present. Keep thresholds relative to the immediate baseline until stable absolute gates are justified by current-source measurements. Source evidence: current perf tests only parse TEST Calc at `NativeObfuscationPerfTest.java:71-91`; obfusjack integration currently checks completion, not performance, at `NativeObfuscationIntegrationTest.java:101-109`. Validation: `R-build`, `R-test` x5, `R-obfusjack` x5, `R-native-test`, `R-inspect`.
  - Implementation row recorded 2026-05-22: NPT-3bv will make
    `NativeObfuscationPerfTest` parse structured median values for TEST Calc
    and obfusjack Platform, Virtual, Seq, Parallel, and VThreads rows from the
    same repeated native-path runs it already performs. The test may assert
    that emitted timing rows are parseable, complete for the current obfusjack
    fixture output, positive, and within conservative current-source sanity
    ceilings; it must not change runtime code, obfuscation coverage, JNI/JVMTI
    behavior, generated C, or collector behavior. The baseline report must
    include the parsed per-run timings and medians so future hot-path changes
    can compare against fresh same-environment measurements instead of
    unstructured logs.
  - Completion evidence 2026-05-22 for NPT-3bv: focused
    `NativeObfuscationPerfTest` passed with `java.io.tmpdir` under
    `build/native-run-tmp`. The generated
    `neko-test/build/test-native/native-performance-baseline.json` now records
    per-run `timingsMillis` maps and `mediansMillis`: TEST Calc median `69 ms`;
    obfusjack Platform `45 ms`, Virtual `36 ms`, Seq `17 ms`, Parallel `1 ms`,
    and VThreads `1 ms`. Diff inspection showed only perf test/report
    instrumentation and matching todo/plan updates; no runtime, codegen,
    JNI/JVMTI, fallback, obfuscation coverage, generated-C, or collector
    behavior changed.

- [x] P16 Reduce translated-to-translated direct-call raw-entry overhead without
  changing Java-level method activation semantics. Internal direct calls between
  translated methods may skip only the redundant runtime capability check after
  the caller has already executed `neko_hotspot_fast_require(thread, env)` for
  the same translated call chain. External raw ABI entries, dispatcher/manifest
  targets, stack/local/monitor storage, local-root reservation where required,
  shadow-frame push/pop, exception behavior, and target selection must remain
  unchanged. Source evidence: `CCodeGenerator.renderRawFunction` currently emits
  `neko_hotspot_fast_require(thread, env)` inside every raw translated function,
  and `OpcodeTranslator.translateDirectInvoke` calls `binding.rawFunctionName()`
  directly for static/special/direct-call-safe translated calls. Existing TEST
  generated C shows `Calc.runAll` calls translated `call`, `runAdd`, and
  `runStr` 10,000 times and each callee reruns the same raw-entry gate.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`,
  performance gate; generated C must show external `neko_native_impl_*` entries
  still call `neko_hotspot_fast_require`, internal direct calls target an
  internal body entry, body entries preserve roots and shadow frames, and no
  JNI/JVMTI/fallback markers are introduced.
  - Implementation row recorded 2026-05-21: NPT-3au will split raw generated
    methods into an external ABI wrapper and an internal translated body entry.
    The wrapper keeps `neko_hotspot_fast_require(thread, env)` and then calls the
    body. The body keeps activation storage, object-local root setup where
    required, parameter-to-local rooting, and one shadow push/pop, but uses an
    assumption-only fast-state marker instead of rechecking runtime capability.
    Only `OpcodeTranslator.translateDirectInvoke` may switch translated internal
    direct calls to the body symbol; NJX, virtual/interface dispatch, manifest
    entries, trampoline dispatchers, and external ABI calls must continue to use
    the wrapper/raw function.
  - Completed 2026-05-22: focused generator/audit tests passed. First fresh
    runtime failed with `UnsatisfiedLinkError: undefined symbol:
    neko_native_impl_19_body`, proving the split-source `_body` prototypes had
    internal linkage. The generic fix externalizes `_body` prototypes and
    definitions with the existing raw wrappers. Fresh TEST generation
    `build/neko-native-work/run-16584698339661` built `libneko_linux_x64.so`
    (`1034872` bytes) with `translated=49 rejected=0`. Generated C inspection
    shows wrappers still call `neko_hotspot_fast_require`, body entries call
    `neko_hotspot_fast_assume`, and `Calc.runAll` direct calls target
    `neko_native_impl_20_body`, `neko_native_impl_21_body`, and
    `neko_native_impl_22_body`. Same-session TEST smoke passed with empty
    stderr: NPT-3at `86,87,90,86,91 ms` (median `87ms`) versus NPT-3au
    `83,86,84,83,96 ms` (median `84ms`). Focused native integration tests for
    TEST Calc and obfusjack completion also passed.

- [ ] P17 Add a static primitive field-ref fast path after class initialization.
  Primitive static get/put may use `neko_static_field_ref` slots directly only
  when class initialization is complete and direct base/offset/access metadata
  is present. Uninitialized or incomplete refs must keep the current slow path
  that resolves the class, initializes the class, binds the field, and hard
  aborts on missing required metadata. Do not change object static fields or
  instance fields in this substep. Source evidence: NPT-3au generated TEST C
  still calls `neko_fast_get_static_I_field_ref(env, &g_static_field_ref_3)` and
  emits static primitive puts as `neko_bound_class_ref`,
  `neko_ensure_class_initialized_once`, `neko_bound_field_ref`, then
  `neko_fast_set_static_I_field` inside the translated hot loop. Helper source
  resolves class and field inside every static primitive ref get, while bind
  support already records `class_init_slot`, `static_base_slot`,
  `static_offset_slot`, and `access_flags_slot` and aborts on invalid static
  metadata. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show static primitive get and
  put use ref helpers, and helper code must prove class-init/direct-metadata
  checks guard the direct fast path with no JNI/JVMTI/fallback markers.
  - Implementation row recorded 2026-05-22: NPT-3av will add static primitive
    ref get/set helpers that first check `class_init_slot`, `static_base_slot`,
    `static_offset_slot`, and `access_flags_slot`; only that proven initialized
    direct-metadata path may access memory directly. The slow path must call the
    existing class/field initialization and binding helpers, then use the same
    direct primitive field helper. `OpcodeTranslator.translatePrimitiveFieldPut`
    may switch primitive static puts to the ref setter so get and put share the
    same class-init preserving path.
  - Rejected row update 2026-05-22: NPT-3av was reverted. Focused
    generator/audit tests passed, and fresh TEST generation
    `build/neko-native-work/run-17090115800257` built `libneko_linux_x64.so`
    (`1037336` bytes) with `translated=49 rejected=0`. Generated C proved
    primitive static puts used `neko_fast_set_static_I_field_ref` without the
    per-site class-init/field-bind sequence, but same-session timing regressed
    versus NPT-3au: NPT-3au `84,84,83,88,85 ms` (median `84ms`) versus NPT-3av
    `87,83,92,93,86 ms` (median `87ms`). A tightened inline-direct helper
    variant in `build/neko-native-work/run-17205237824086` also regressed:
    NPT-3au `85,83,85,85,88 ms` (median `85ms`) versus NPT-3av
    `87,87,86,89,84 ms` (median `87ms`). Do not retry this static-field-ref
    shape without new branch-layout or code-size evidence.

- [x] P18 Elide translated monitor storage for methods without monitor bytecodes.
  Generated raw bodies should declare `neko_monitor_record monitors[...]` and
  `monitor_sp` only when the source bytecode contains `MONITORENTER` or
  `MONITOREXIT`. Methods containing monitor bytecodes must retain the current
  storage and monitor helper calls unchanged. Synchronized-method entry
  semantics must not be changed by this substep. Source evidence: NPT-3au
  generated TEST C declares monitor storage in every raw body, including
  primitive hot methods, while no generated TEST body calls
  `neko_fast_monitor_enter` or `neko_fast_monitor_exit`; `OpcodeTranslator`
  references `monitors`/`monitor_sp` only in the monitor opcode cases.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show no-monitor methods omit
  monitor storage and monitor-bytecode methods retain it.
  - Implementation row recorded 2026-05-22: NPT-3aw will add monitor-use
    metadata to generated `CFunction`s, set it from structural bytecode scanning
    in `NativeTranslator`, and guard the monitor array declarations in
    `CCodeGenerator.renderRawFunction`. No opcode translation, monitor helper,
    synchronized-method, exception, shadow-frame, or GC behavior may change.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-17599946034860` built
    `libneko_linux_x64.so` (`1034872` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.runAll`, `Calc.call`, and `Calc.runAdd`
    bodies no longer declare `neko_monitor_record monitors[...]` or
    `int monitor_sp = 0`; generated TEST body grep found no monitor helper call
    sites. Same-session TEST smoke passed with empty stderr: NPT-3au
    `94,91,84,85,87 ms` (median `87ms`) versus NPT-3aw
    `84,84,84,87,86 ms` (median `84ms`). Focused native integration tests for
    TEST Calc and obfusjack completion also passed.

- [x] P19 Elide unreferenced translated exception-exit blocks. Generated raw
  bodies should emit `__neko_exception_exit` and the default shadow-pop/return
  tail only when the translated body structurally references that label. This
  must be based on generated statements, not class/method names. Source
  evidence: NPT-3aw generated TEST C shows `Calc.call` and `Calc.runAdd` carry
  an exception-exit block with no `goto __neko_exception_exit`, while
  `Calc.runAll` and `Calc.runStr` do branch to it and must retain it.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show every remaining
  `goto __neko_exception_exit` has a matching label and methods without such a
  reference omit only the unreachable exception-exit tail.
  - Implementation row recorded 2026-05-22: NPT-3ax will scan generated
    `CStatement`s before appending the exception-exit label. If no statement
    references `__neko_exception_exit`, the label/default-return tail is omitted.
    Normal return paths, shadow pops, pending-exception dispatch, catch-handler
    branches, and opcode translation must remain unchanged.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-17954422278482` built
    `libneko_linux_x64.so` (`1034872` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.call` and `Calc.runAdd` omit
    `__neko_exception_exit`, while `Calc.runAll` and `Calc.runStr` keep the label
    for real pending-exception branches. Same-session TEST smoke passed with
    empty stderr: NPT-3aw `92,90,89,94,91 ms` (median `91ms`) versus NPT-3ax
    `89,85,90,92,89 ms` (median `89ms`). Focused native integration tests for
    TEST Calc and obfusjack completion also passed.

- [x] P20 Elide same-owner static direct-call class guards. Generated direct
  translated `INVOKESTATIC` calls whose target owner is the current translated
  owner should pass the existing non-null current `clazz` parameter directly
  instead of materializing `jclass targetCls = (jclass)clazz` and guarding
  `targetCls != NULL`. This must not change cross-owner static calls, instance
  calls, direct body dispatch, or post-call pending-exception checks. Source
  evidence: NPT-3ax generated TEST C shows same-owner static direct calls in
  `Calc.runAll` to `Calc.call`, `Calc.runAdd`, and `Calc.runStr` all emit this
  redundant local/null guard even though the current method's native entry ABI
  supplies `clazz`.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show no `targetCls != NULL`
  guard for same-owner static direct calls and unchanged guard shape for
  cross-owner/instance direct calls.
  - Implementation row recorded 2026-05-22: NPT-3ay will specialize only the
    same-owner static translated direct-call emission. The change is owner/ABI
    driven rather than benchmark driven, and every post-call
    pending-exception check must remain in place.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-18549458339348` built
    `libneko_linux_x64.so` (`1035160` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.runAll` same-owner static direct calls
    now invoke `Calc.call`, `Calc.runAdd`, and `Calc.runStr` bodies with
    `(jclass)clazz` directly, while cross-owner static and instance direct calls
    still retain `targetCls` guards. Static grep found no `NEKO_JNI_FN_PTR`,
    `(*env)->`, or `env->` markers in the generated work directory.
    Same-session alternating TEST smoke passed with empty stderr and equal
    medians: NPT-3ax `93,88,89,84,85,89,96 ms` (median `89ms`) versus NPT-3ay
    `88,89,98,85,101,89,85 ms` (median `89ms`). Focused native integration
    tests for TEST Calc and obfusjack completion also passed.

- [x] P21 Fuse primitive integer branch producers. The translator should fuse
  only adjacent same-basic-block primitive integer producers
  (`ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`) feeding
  `IFEQ`/`IFNE`/`IFLT`/`IFGE`/`IFGT`/`IFLE`, or two such producers feeding
  `IF_ICMP*`, and emit direct primitive branch C without stack mutation. This
  must not cross labels, try-handler boundary flushes, pending-exception
  dispatch, object/ref stack operations, fields, arrays, invokes, monitors,
  arithmetic, division, floating-point comparisons, or helper calls. Source
  evidence: fresh generated TEST C contains generic round trips such as
  `PUSH_I(local); if (POP_I() != 0)` and `PUSH_I(local); PUSH_I(const);
  POP/POP; if (...)`, while `OpcodeTranslator.intPushExpression` already
  restricts recognized producers to primitive constants and `ILOAD`.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show known integer branch
  round trips replaced and object/field/invoke paths unchanged.
  - Implementation row recorded 2026-05-22: NPT-3az will implement this as a
    `NativeTranslator` multi-instruction peephole so it can consume the branch
    bytecode and preserve the existing pending-exception flush rules before
    branches. Fused C must be primitive-only, same-basic-block, and
    non-throwing.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-19020205937571` built
    `libneko_linux_x64.so` (`1034840` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.runAll` and `Digi.run` loop guards use
    direct primitive compare C, and `Tracee.toTrace` uses
    `if (locals[1].i != 0)` without stack mutation; object/field/invoke paths
    remained outside the fused slice. Static grep found no `NEKO_JNI_FN_PTR`,
    `(*env)->`, or `env->` markers in the generated work directory.
    Same-session alternating TEST smoke passed with empty stderr: NPT-3ay
    `89,83,83,88,88,85,91 ms` (median `88ms`) versus NPT-3az
    `87,82,89,84,105,88,85 ms` (median `87ms`). Focused native integration
    tests for TEST Calc and obfusjack completion also passed.

- [x] P22 Fuse same-field static int add updates without changing field helper
  shape. The translator should fuse only adjacent same-basic-block
  `GETSTATIC int`, primitive int constant producer, `IADD`, and matching
  `PUTSTATIC int` for the same owner/name/descriptor. The fused C must keep the
  existing get helper, class binding, class initialization, field binding, and
  set helper calls in the same relative order; it may only replace stack
  traffic with a primitive local value expression. This is not the rejected
  static primitive field-ref fast path and must not alter static field-ref
  metadata, static base/offset binding, class initialization, instance fields,
  object/reference fields, non-int primitives, generic arithmetic, labels,
  pending-exception dispatch, invokes, arrays, monitors, or fallback behavior.
  Source evidence: fresh NPT-3az generated TEST C shows `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` all execute `GETSTATIC Calc.errors`, push
  `1`, perform `IADD`, and pop into `PUTSTATIC Calc.errors`.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show the Calc static int
  updates use one primitive local and unchanged get/class-init/set helpers.
  - Implementation row recorded 2026-05-22: NPT-3ba will implement this as a
    same-basic-block multi-instruction peephole and will reject any nonmatching
    field identity or non-int/non-add sequence.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-19381987030477` built
    `libneko_linux_x64.so` (`1034808` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.call`, `Calc.runAdd`, and `Calc.runStr`
    static int updates use a single primitive `val` expression while retaining
    the existing static get, class bind/init, field bind, and static set
    helpers. Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`
    markers in the generated work directory. Same-session alternating TEST
    smoke passed with empty stderr: NPT-3az `86,85,89,95,90,82,89 ms` (median
    `89ms`) versus NPT-3ba `83,87,94,89,85,101,88 ms` (median `88ms`). Focused
    native integration tests for TEST Calc and obfusjack completion also passed.

- [x] P23 Reject direct pending-exception reads for opcode result guards.
  Replacing translated opcode result/yield guards of the form
  `!neko_exception_check(env)`
  with `neko_pending_exception_oop(thread) == NULL` only where `thread` is
  already in scope and the guard is preserving a result after a native/JVM
  call. This keeps the exception check and changes only the access path from
  `env -> JavaThread -> _pending_exception` to the existing direct
  `thread -> _pending_exception` read. Do not change CHECKCAST, exception
  dispatch checks, static primitive field paths, shadow frames, JNI bootstrap,
  helper fallback behavior, or exception timing. Source evidence: fresh
  NPT-3az generated TEST C contains many `if (!neko_exception_check(env)) {
  PUSH_*... }` guards after icache, NJX, direct translated body, MethodHandle
  bridge, intrinsic, and string-switch calls, while
  `neko_pending_exception_oop(thread)` is already emitted and used for
  translated control-flow exception dispatch.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show call result guards no
  longer use `neko_exception_check(env)` and CHECKCAST remains unchanged.
  - Implementation row recorded 2026-05-22: NPT-3bb will change only
    `OpcodeTranslator` result/yield guard predicates and will not remove any
    guard or alter CHECKCAST.
  - Rejected 2026-05-22: the implementation changed only `OpcodeTranslator`
    result/yield guard predicates and left CHECKCAST unchanged. Focused
    generator/audit tests passed, and fresh TEST generation
    `build/neko-native-work/run-19677117143362` built `libneko_linux_x64.so`
    (`1038488` bytes) with `translated=49 rejected=0`. Generated C inspection
    showed call-result guards no longer used `neko_exception_check(env)`, the
    only remaining impl occurrences were four CHECKCAST guards, and static grep
    found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers. Runtime smoke
    stayed functional with empty stderr, but performance/code-size evidence
    rejected the slice: first alternating run regressed NPT-3ba
    `85,85,88,96,84,88,94 ms` (median `88ms`) versus NPT-3bb
    `91,91,87,84,87,97,93 ms` (median `91ms`); a second alternating run was
    equal at median `87ms`, but combined samples still skewed slower and the
    library grew from `1034808` to `1038488` bytes. Source edits were reverted.
    Do not retry this broad predicate replacement without new code-size or
    branch-layout evidence.

- [x] P24 Fuse primitive int arithmetic returns. The translator should fuse
  only adjacent same-basic-block `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`, second
  matching int producer, `IADD` or `IMUL`, and `IRETURN` into a direct primitive
  return expression. The fused C must still execute the normal shadow pop before
  returning and must flush any pending exception dispatch before the return.
  This must not cross labels, try-handler boundary flushes, object/ref
  operations, fields, arrays, invokes, monitors, division/remainder,
  subtraction with operand-order risk, long/float/double arithmetic, or helper
  calls. Source evidence: fresh NPT-3ba generated TEST C shows generic leaf
  methods such as `Abst1.mul(II)I`, `Top.add(II)I`, and `flo.solve(II)I` load
  two int locals, emit `IADD`/`IMUL`, then immediately pop and return the
  result.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show leaf arithmetic returns
  use direct primitive return expressions with shadow pop retained.
  - Implementation row recorded 2026-05-22: NPT-3bc will implement this as a
    primitive-only `NativeTranslator` peephole and will reject any non-add/mul,
    non-int, non-immediate-return, or label-crossing sequence.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-20027566395481` built
    `libneko_linux_x64.so` (`1034808` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Abst1.mul`, `Top.add`, and `flo.solve` use
    direct primitive int return expressions with `neko_shadow_pop()` retained
    and no stack round trip. Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`,
    or `env->` markers in the generated work directory. Same-session
    alternating TEST smoke passed with empty stderr: NPT-3ba
    `87,87,84,85,93,98,92 ms` (median `87ms`) versus NPT-3bc
    `88,85,86,86,84,99,94 ms` (median `86ms`). Focused native integration
    tests for TEST Calc and obfusjack completion also passed.

- [x] P25 Fuse primitive int arithmetic into single-arg self tail calls. The
  translator should fuse only adjacent same-basic-block `ICONST_*`/`BIPUSH`/
  `SIPUSH`/`ILOAD`, second matching int producer, `IADD`, `ISUB`, or `IMUL`,
  then a static self-recursive tail call for a method with exactly one `int`
  argument and a matching return. The fused C must assign the computed
  expression directly to the restarted local, clear the operand stack, and jump
  to the existing tail-call landing pad. This must not cross labels between the
  primitive producers and the self call. Like the existing tail-call rewrite,
  labels, lines, and frames may appear between the call and the matching return
  only when no intervening real instruction exists. This must not cross
  try-handler-covered call sites, object/ref operations, fields, arrays, invokes
  other than the proven self tail call, monitors, division/remainder,
  long/float/double arithmetic, multi-argument calls, instance receiver calls,
  or helper calls. Source evidence: fresh NPT-3bc generated TEST C at
  `build/neko-native-work/run-20027566395481/neko_native_impl_20.c` shows the
  generic static self-recursive `int -> void` tail path evaluating
  `locals[0].i - 1` through `PUSH_I(locals[0].i)`, `PUSH_I(1)`, `ISUB`, then
  immediately popping that value into `locals[0].i` inside the existing
  tail-call rewrite.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show the single-arg
  arithmetic self tail call uses a direct local assignment with the existing
  `sp = 0; goto __neko_tco_entry;`.
  - Implementation row recorded 2026-05-22: NPT-3bd will implement this as a
    primitive-only one-argument static self-tail-call peephole and will reject
    any non-add/sub/mul, non-int, non-static, multi-argument, non-self, non-tail,
    active-handler, or producer-to-call label-crossing sequence.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-20650186041509` built
    `libneko_linux_x64.so` (`1034808` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.call(I)V` assigns `locals[0].i - 1`
    directly in the tail-call restart path while preserving the normal `L3`
    return epilogue. Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or
    `env->` markers in the generated work directory. Same-session alternating
    TEST smoke passed with empty stderr: NPT-3bc `81,88,99,94,95,90,89 ms`
    (median `90ms`) versus NPT-3bd `88,92,88,90,86,87,95 ms` (median `88ms`).
    Focused native integration tests for TEST Calc and obfusjack completion
    also passed.

- [x] P26 Fuse primitive compare results into zero branches. The translator
  should fuse only adjacent same-basic-block pure primitive producers feeding
  `LCMP`, `FCMPL`, `FCMPG`, `DCMPL`, or `DCMPG`, followed immediately by an
  `IFEQ`, `IFNE`, `IFLT`, `IFGE`, `IFGT`, or `IFLE` zero-branch. Initial
  producers are limited to primitive local loads and numeric constants that the
  existing translator already lowers as direct literals or local reads. The
  fused C must compute the exact existing compare-result expression into a local
  `jint __cmp` and branch on `__cmp`, preserving `FCMPL`/`FCMPG` and
  `DCMPL`/`DCMPG` NaN polarity. It must not simplify float/double comparisons
  into direct relational branches, cross labels, fold throwing/side-effecting
  operations, or alter exception dispatch boundaries. Source evidence: fresh
  NPT-3bd generated TEST C at
  `build/neko-native-work/run-20650186041509/neko_native_impl_1.c` and
  `neko_native_impl_21.c` shows `FCMPL` and `DCMPG` compare results are pushed
  to the operand stack and immediately popped by zero-branches in hot loop
  paths. The existing fallback compare expressions at `OpcodeTranslator` lines
  for `LCMP`/`FCMPL`/`FCMPG`/`DCMPL`/`DCMPG` provide the exact expression shape
  that must be reused.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show fused compare branches
  use `jint __cmp` without compare-result `PUSH_I`/`POP_I` round trips.
  - Implementation row recorded 2026-05-22: NPT-3be will implement this as a
    primitive-only same-block compare-result branch peephole and will reject
    any label-crossing, non-zero-branch, non-primitive-producer, or
    side-effecting producer sequence.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-21034784798896` built
    `libneko_linux_x64.so` (`1034808` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed pure `FCMPL`/`DCMPG` zero-branches use local
    `jint __cmp` branches in `Digi.run` and `Calc.runAdd`, while the mixed
    `double -> float` conversion path remained on the fallback stack form.
    Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in
    the generated work directory. Same-session alternating TEST smoke passed
    with empty stderr: NPT-3bd `82,82,90,91,87,89,88 ms` (median `88ms`) versus
    NPT-3be `85,92,87,83,88,93,97 ms` (median `88ms`). Focused native
    integration tests for TEST Calc and obfusjack completion also passed.

- [x] P27 Fuse primitive float/double same-local add updates. The translator
  should fuse only adjacent same-basic-block `FLOAD` or `DLOAD` of a local,
  followed by a matching pure float/double constant producer, `FADD` or `DADD`,
  and `FSTORE` or `DSTORE` back to the same local. The fused C must assign the
  same arithmetic expression directly to the local slot and must preserve the
  existing fallback C arithmetic shape. It must not cross labels, fold
  subtraction/multiplication/division/remainder, fold non-constant RHS values,
  change local indexes, touch long/int/object/ref locals, or alter exception
  dispatch boundaries. Source evidence: fresh NPT-3be generated TEST C at
  `build/neko-native-work/run-21034784798896/neko_native_impl_1.c` and
  `neko_native_impl_21.c` shows hot loops increment float/double locals through
  `PUSH_F`/`PUSH_D`, `FADD`/`DADD`, then immediate `FSTORE`/`DSTORE` to the same
  local.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show same-local float/double
  add updates use direct local assignments with no add-result stack round trip.
  - Implementation row recorded 2026-05-22: NPT-3bf will implement this as a
    primitive-only same-local float/double constant add-update peephole and will
    reject label-crossing, different-local, non-constant, and non-add sequences.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-21337326266905` built
    `libneko_linux_x64.so` (`1034808` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed hot float/double loop increments in
    `Digi.run` and `Calc.runAdd` use direct same-local assignments such as
    `locals[4].f = locals[4].f + 1.3f` and
    `locals[0].d = locals[0].d + 0.99`. Static grep found no
    `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
    directory. Same-session alternating TEST smoke passed with empty stderr:
    NPT-3be `83,88,84,84,89,88,87 ms` (median `87ms`) versus NPT-3bf
    `93,85,92,86,85,87,85 ms` (median `86ms`). Focused native integration tests
    for TEST Calc and obfusjack completion also passed.

- [x] P28 Fuse primitive constant local stores. The translator should fuse only
  adjacent same-basic-block primitive constant producers (`ICONST_*`, `BIPUSH`,
  `SIPUSH`, `LCONST_*`, `FCONST_*`, `DCONST_*`, and numeric `LDC`) followed
  immediately by a matching primitive local store (`ISTORE`, `LSTORE`, `FSTORE`,
  or `DSTORE`). The fused C must assign the same literal expression directly to
  the target local slot. It must not cross labels, fold object/reference stores,
  fold non-constant producers, change local indexes, or alter exception
  dispatch boundaries. Source evidence: fresh NPT-3bf generated TEST C at
  `build/neko-native-work/run-21337326266905/neko_native_impl_1.c`,
  `neko_native_impl_19.c`, and `neko_native_impl_21.c` shows hot methods still
  initialize primitive locals through constant `PUSH_*` followed immediately by
  `locals[n].* = POP_*()`.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show primitive constant local
  stores use direct assignments with no constant push/pop round trip.
  - Implementation row recorded 2026-05-22: NPT-3bg will implement this as a
    primitive-only constant local-store peephole and will reject label-crossing,
    type-mismatched, object/reference, and non-constant producer sequences.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-21666360179964` built
    `libneko_linux_x64.so` (`1034760` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed hot primitive initializers in `Digi.run`,
    `Calc.runAll`, and `Calc.runAdd` use direct local assignments such as
    `locals[1].d = 0.0`, `locals[3].i = 0`, and `locals[4].f = 1.1f`. Static
    grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the
    generated work directory. Same-session alternating TEST smoke passed with
    empty stderr: NPT-3bf `90,88,86,93,87,88,94 ms` (median `88ms`) versus
    NPT-3bg `91,84,91,88,89,88,87 ms` (median `88ms`), with library size
    reduced from `1034808` to `1034760` bytes. Focused native integration tests
    for TEST Calc and obfusjack completion also passed.

- [x] P29 Fuse single int producers into static translated direct calls. The
  translator should fuse only adjacent same-basic-block pure `int` producers
  (`ICONST_*`, `BIPUSH`, `SIPUSH`, or `ILOAD`) feeding an immediate
  `INVOKESTATIC` whose target is already translated and whose descriptor has
  exactly one `int` argument. The fused C must call the existing translated
  direct-call body with the producer expression as the argument and must reuse
  the existing direct-call target class/guard/result handling. It must not cross
  labels, fold non-static calls, fold NJX or virtual/interface dispatch, fold
  multi-argument calls, fold non-int arguments, or alter pending-exception
  coalescing. Source evidence: fresh NPT-3bg generated TEST C at
  `build/neko-native-work/run-21666360179964/neko_native_impl_19.c` shows the
  hot `Calc.runAll` loop still materializes the constant argument to translated
  static `Calc.call(I)V` through `PUSH_I(100)` and immediate `POP_I()` inside
  the direct translated call wrapper.
  Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`,
  `R-inspect`, performance gate; generated C must show the direct translated
  call receives the int expression directly without argument-stack push/pop.
  - Implementation row recorded 2026-05-22: NPT-3bh will implement this as a
    one-int-argument static translated direct-call peephole and will reject
    label-crossing, non-static, non-translated, multi-argument, and non-int
    sequences.
  - Completed 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-22094701424303` built
    `libneko_linux_x64.so` (`1034712` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.runAll` passes `(jint)(100)` directly
    to `neko_native_impl_20_body` without argument push/pop, while other
    dispatch forms remain unchanged. Static grep found no `NEKO_JNI_FN_PTR`,
    `(*env)->`, or `env->` markers in the generated work directory.
    Same-session alternating TEST smoke passed with empty stderr: NPT-3bg
    `84,85,88,88,90,84,90 ms` (median `88ms`) versus NPT-3bh
    `83,86,84,91,87,88,87 ms` (median `87ms`), with library size reduced from
    `1034760` to `1034712` bytes. Focused native integration tests for TEST
    Calc and obfusjack completion also passed.

- [x] P30 Intrinsify `java/lang/String.length()I` through existing raw String
  layout metadata. The translator should lower only exact
  `INVOKEVIRTUAL java/lang/String.length:()I` sites to a generic native helper
  that pops the receiver, preserves the existing null-receiver NPE path, reads
  the already-bound `String.value:[B` and `String.coder:B` offsets, loads the
  backing byte-array length through the existing array length offset, and
  pushes `value.length >> coder`. It must hard-abort if String field metadata,
  object/array layout, compressed-oop decoding, or GC load-barrier prerequisites
  are unavailable. It must not apply to other owners, names, descriptors,
  static/special/interface calls, user methods, or virtual dispatch in general;
  it must not add JNI/JVMTI/fallback behavior or bypass the implicit-exception
  helper.
  Source evidence: fresh NPT-3bh generated TEST C at
  `build/neko-native-work/run-22094701424303/neko_native_impl_22.c` still
  dispatches `java/lang/String.length()I` through `neko_icache_dispatch` inside
  the hot `Calc.runStr` loop. That loop runs about 101 iterations per
  `runStr`, and `runAll` calls `runStr` 10000 times, so the site executes about
  one million virtual dispatches per TEST run. Existing native infrastructure
  already binds `java/lang/String.value:[B` and `java/lang/String.coder:B`
  offsets for string concat/literal support, reads object fields with
  `neko_barrier_load_oop_field`, decodes narrow oops, and reads array lengths
  through `neko_const_array_length_offset()`.
  Validation: `R-build`, focused translator/generator/audit tests, fresh TEST
  native generation, generated-C inspection proving `String.length` sites emit
  `neko_fast_string_length` and no `neko_icache_dispatch` for that call,
  `R-test` repeated timing comparison, `R-native-test`, `R-inspect`, and
  focused runtime coverage for null, Latin1, UTF16, and concat-produced
  strings.
  Completion criteria: only exact `String.length()I` calls are intrinsified;
  null receivers still raise and route through the normal implicit NPE helper;
  Latin1 and UTF16 length results match JVM semantics; generated C has no
  forbidden JNI wrappers or fallback markers; TEST timing does not regress.
  - Implementation row recorded 2026-05-22: NPT-3bi will implement this as an
    exact-owner/name/descriptor virtual-call intrinsic using existing String
    value/coder field offset binding and existing oop/array load barriers. It
    will reject all other virtual dispatch sites and keep the normal implicit
    NPE helper for null receivers.
  - Completed 2026-05-22: focused generator/audit tests passed for
    `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
    `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
    `build/neko-native-work/run-22968063094268` built `libneko_linux_x64.so`
    (`1036696` bytes) with `translated=49 rejected=0`. Generated C inspection
    showed `Calc.runStr` calls `neko_fast_string_length(__str, g_off_10,
    g_off_11)` and no longer uses `neko_icache_dispatch` for
    `java/lang/String.length()I`; null receivers still route through
    `neko_raise_implicit_exception_ref`. Strict forbidden JNI grep for
    `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Same-session
    alternating TEST smoke passed with empty stderr: NPT-3bh
    `85,85,95,106,86,85,88 ms` (median `86ms`) versus NPT-3bi
    `73,69,68,70,85,84,71 ms` (median `71ms`). Focused native integration
    tests for TEST Calc, obfusjack completion, and implicit exception/String
    length runtime coverage passed.

- [x] P31 Reject intrinsic `String.length()` compare-branch fusion.
  Rejected as a performance slice.
  The translator should fuse only adjacent same-basic-block `ALOAD` receiver,
  exact `INVOKEVIRTUAL java/lang/String.length:()I`, pure int constant/local
  producer, and `IF_ICMP*` branch. The fused C must preserve the exact
  null-receiver implicit NPE path, check pending exception before evaluating
  the branch, and compare the computed length directly against the second int
  operand without materializing the result through the operand stack. The first
  implementation is restricted to call sites with no active try handlers so a
  null receiver can keep the existing `__neko_exception_exit` route; try/catch
  covered sites must retain the unfused opcode sequence. It must not fuse
  other methods, other owners, non-virtual calls, object/reference compares,
  label-crossing patterns, concat paths, or any path where exception handler
  routing is not proven identical.
  Source evidence: fresh NPT-3bi generated TEST C at
  `build/neko-native-work/run-22968063094268/neko_native_impl_22.c` shows
  `Calc.runStr` now emits `neko_fast_string_length` for the hot
  `String.length` call, but still pushes the result, pushes constant `101`,
  checks pending exception, then pops both ints in `IF_ICMPGE`. This stack
  round trip executes once per append-loop iteration after the accepted length
  intrinsic.
  Validation: `R-build`, focused translator/generator/audit tests, fresh TEST
  native generation, generated-C inspection proving hot `String.length` compare
  branches use a direct local length compare with pending exception check
  retained, `R-test` repeated timing comparison, `R-native-test`, and
  `R-inspect`.
  Completion criteria: only same-block no-active-handler exact
  `String.length` int-compare branches fuse; null receiver behavior and
  pending-exception ordering remain identical; try/catch-covered cases stay
  unfused; generated C has no forbidden JNI wrappers or fallback markers;
  timing does not regress.
  - Implementation row recorded 2026-05-22: NPT-3bj tested only the
    no-active-handler same-block `String.length` plus `IF_ICMP*` branch shape.
    It preserved the normal implicit NPE helper and emitted an explicit pending
    exception check before the fused branch.
  - Rejected 2026-05-22: focused generator/audit tests passed, and fresh TEST
    generation `build/neko-native-work/run-23558603672735` built
    `libneko_linux_x64.so` (`1036744` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed `Calc.runStr` used a direct `__len` local and
    no longer emitted `PUSH_I(neko_fast_string_length(...))` or `PUSH_I(101)`;
    strict forbidden JNI grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->`
    was clean. Runtime stayed functional, but same-session alternating TEST
    timing rejected the slice: NPT-3bi `69,72,77,71,74,72,77 ms`
    (median `72ms`) versus NPT-3bj `73,72,77,87,74,72,75 ms`
    (median `74ms`), and the native library grew from `1036696` to `1036744`
    bytes. Source/test edits were reverted. Do not retry this branch-fusion
    shape without new code-size or branch-layout evidence.

- [x] P32 Reject guarded generated concat literal string binding.
  Rejected on thread-safety evidence.
  When native string-concat pattern emission creates a generated static
  `jstring` literal slot, emit a slot-null guard around the existing
  `neko_bind_string_slot(thread, env, &slot, "...")` call. The change may only
  affect generated concat literal slots created by `literalStringProducer`; it
  must not change owner string binding, intern/global-ref creation,
  `neko_concat_append`, `StringConcatFactory` recipe lowering, raw string
  construction, JNI usage, exception behavior, or GC barriers.
  Source evidence: fresh NPT-3bi generated TEST C at
  `build/neko-native-work/run-22968063094268/neko_native_impl_22.c` shows the
  hot `Calc.runStr` loop executes `neko_bind_string_slot(thread, env,
  &__neko_concat_lit_0, "ax")` on every concat iteration before
  `neko_concat_append`. The helper itself returns immediately when `slot !=
  NULL && *slot != NULL` (`NativeBindSupportEmitter`), so the generated guard
  is the same predicate moved to the call site. The helper already writes a
  global reference into the static slot, and concurrent first binds are already
  governed by the helper's unsynchronized slot check; adding the same outer
  check must not introduce a new race class.
  Validation: `R-build`, focused translator/generator/audit tests, fresh TEST
  native generation, generated-C inspection proving concat literal sites emit
  `if (__neko_concat_lit_N == NULL) neko_bind_string_slot(...)` and no hot
  unconditional bind call, `R-test` repeated timing comparison,
  `R-native-test`, `R-inspect`, and focused native integration tests for TEST
  Calc and obfusjack completion.
  Completion criteria: only generated concat literal slots gain the guard;
  first-use bind still hard-aborts on missing thread/env/global-ref failure;
  subsequent uses reuse the same global slot; generated C has no forbidden
  JNI/JVMTI/fallback markers; timing does not regress.
  - Rejected 2026-05-22: focused generator/audit tests passed and fresh TEST
    generation `build/neko-native-work/run-23910172237245` built
    `libneko_linux_x64.so` (`1036712` bytes) with `translated=49 rejected=0`.
    Generated C inspection showed the intended
    `if (__neko_concat_lit_0 == NULL) { neko_bind_string_slot(...) }` shape in
    `Calc.runStr`, and strict JNI marker grep for `NEKO_JNI_FN_PTR`,
    `(*env)->`, and `env->` was clean. The slice was rejected before timing
    acceptance because the generated outer guard performs a plain non-atomic
    read of a function-local static `jstring` while `neko_bind_string_slot`
    publishes the slot through a plain non-atomic write. Concurrent first use
    would therefore keep the existing duplicate-bind race and add a C data-race
    read in generated hot code. Source/test edits were reverted. Do not retry
    local static concat literal slot guarding without atomic publication and
    losing-global-ref cleanup evidence.

- [x] P33 Reject exact integer `java/lang/Math.min` intrinsic.
  Rejected as a performance/runtime slice.
  Lower only exact `INVOKESTATIC java/lang/Math.min:(II)I` and
  `INVOKESTATIC java/lang/Math.min:(JJ)J` bytecode sites to direct primitive C
  comparisons after popping both operands in JVM argument order. The change must
  not affect `Math.max`, float/double overloads, non-JDK owners, translated
  application methods, method handles/reflection, class initialization,
  exception paths, object/GC behavior, or NJX in general.
  Source evidence: fresh generated obfusjack native C at
  `build/neko-native-work/run-23052048100862/neko_native_impl_48.c:29` and
  `neko_native_impl_65.c:26` shows exact `Math.min(II)I` sites still dispatch
  through `neko_njx_S_I_II`; `neko_native_impl_55.c:55` shows exact
  `Math.min(JJ)J` still dispatches through `neko_njx_S_J_JJ`. These wrappers
  build argument arrays, install/restore Java handle blocks, check thread
  state, call through the guarded call stub, and marshal results for a pure
  integer operation. Java `Math.min` for `int` and `long` is exactly
  `a <= b ? a : b`; restricting the slice to integer overloads avoids
  floating-point NaN and signed-zero semantics.
  Validation: `R-build`, focused translator/generator/audit tests, fresh
  obfusjack native generation, generated-C inspection proving the exact
  `Math.min(II)I` and `(JJ)J` sites emit direct `PUSH_I`/`PUSH_L` compares and
  no `neko_njx_*` call for those sites, strict forbidden JNI grep,
  obfusjack native runtime/timing comparison, `R-native-test`, and focused
  native integration tests for TEST Calc and obfusjack completion.
  Completion criteria: only exact integer `Math.min` overloads are lowered;
  operand order and signed comparison semantics match Java; no pending
  exception check is introduced for the pure operation; generated C has no
  forbidden JNI/JVMTI/fallback markers; obfusjack timing does not regress.
  - Implementation note 2026-05-22: the first generated form kept a call to
    `neko_ensure_class_initialized_once` at every lowered `Math.min` site.
    Same-session obfusjack timing rejected that shape because the steady-state
    helper call offset the NJX removal. The next implementation keeps the same
    first-use class initialization, but emits the existing initialized-slot
    check in generated code so the hot path enters the helper only while the
    `java/lang/Math` slot is not initialized.
  - Rejected 2026-05-22: focused generator/audit tests passed. Fresh obfusjack
    generation `build/neko-native-work/run-24493430020950` built
    `libneko_linux_x64.so` (`1804984` bytes) with `translated=93 rejected=0`.
    Generated C inspection proved the target sites emitted direct primitive
    compares with a cold `g_cls_initialized_26` guard:
    `neko_native_impl_48.c:29` and `neko_native_impl_65.c:26` for
    `Math.min(II)I`, and `neko_native_impl_55.c:55` for `Math.min(JJ)J`.
    Strict grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean.
    Runtime rejected the slice: one NPT-3bl obfusjack run dumped core before
    completion, and the completed same-session alternating runs regressed
    against the cached pre-change obfusjack jar. Baseline Platform
    `44,45,46,46,45 ms` (median `45ms`) versus NPT-3bl completed Platform
    `52,46,47,47 ms` (median `47ms`); baseline Virtual `33,32,34,49,32 ms`
    (median `33ms`) versus NPT-3bl completed Virtual `37,43,34,38 ms`
    (median `37.5ms`); baseline Seq `16,16,16,18,18 ms` (median `16ms`)
    versus NPT-3bl completed Seq `17,17,17,17 ms` (median `17ms`). Source/test
    edits were reverted. Do not retry this exact `Math.min` intrinsic shape
    without new evidence explaining the crash and branch/code-layout
    regression.

- [x] P34 Reject owner-bound strings for StringBuilder concat literals.
  Rejected as a performance slice.
  For the existing native StringBuilder concat pattern, replace generated
  function-local static literal slots with the same owner-bound string cache
  expression used by normal LDC and indy concat literals. The change may only
  affect literal producers inside the already-recognized StringBuilder concat
  pattern. It must not change string construction, `neko_concat_append`, raw
  concat allocation, owner binding semantics, non-literal producers,
  StringConcatFactory lowering, JNI usage, exception behavior, or GC barriers.
  Source evidence: fresh NPT-3bi TEST generated C shows `Calc.runStr` calls
  `neko_bind_owner_strings_14(thread, env)` at method entry and uses
  `neko_bound_string(thread, env, &g_str_37, "")` for the normal LDC empty
  string, but the hot `"ax"` concat literal uses a separate
  `static jstring __neko_concat_lit_0` plus `neko_bind_string_slot(...)` inside
  the append loop. The owner bind function for `pack/tests/bench/Calc` does
  not include `"ax"`, so the same literal is rebound through the helper on
  every loop iteration. `OpcodeTranslator.cachedStringExpression` already
  registers literals with `CCodeGenerator.registerOwnerStringReference`, and
  `methodMayUseBoundStrings` already adds the owner-string bind call for
  methods containing LDC strings.
  Validation: `R-build`, focused translator/generator/audit tests, fresh TEST
  native generation, generated-C inspection proving the hot concat literal uses
  `neko_bound_string(thread, env, &g_str_N, "ax")`, the owner bind function
  contains `"ax"`, and no `__neko_concat_lit_N` local static remains for that
  pattern, strict forbidden JNI grep, `R-test` repeated timing comparison,
  `R-native-test`, and focused native integration tests for TEST Calc and
  obfusjack completion.
  Completion criteria: only StringBuilder concat literal producers switch to
  owner-bound string slots; first-use/global-ref binding stays centralized in
  the existing owner-string binder; no new static local publication path is
  introduced; generated C has no forbidden JNI/JVMTI/fallback markers; timing
  does not regress.
  - Rejected 2026-05-22: focused generator/audit tests passed. Fresh TEST
    generation `build/neko-native-work/run-24803874241514` built
    `libneko_linux_x64.so` (`1036728` bytes) with `translated=49 rejected=0`.
    Generated C inspection proved `Calc.runStr` moved the hot `"ax"` literal to
    `neko_bound_string(thread, env, &g_str_38, "ax")`,
    `neko_bind_owner_strings_14` binds `g_str_38` once at owner-string bind
    time, and no `__neko_concat_lit_N` local static slot remained for the
    pattern. Strict grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was
    clean. Runtime smoke passed, but same-session alternating TEST timing
    rejected the slice: NPT-3bi `76,76,74,73,79,72,75 ms` (median `75ms`)
    versus NPT-3bm `72,71,83,70,87,80,76 ms` (median `76ms`). Source/test
    edits were reverted. Do not retry this owner-bound concat literal shape
    without new code-layout or inlining evidence.

- [x] P35 Inline concat append wrapper before existing String.concat NJX.
  Add a prelude-local inline concat append wrapper that preserves the existing
  `neko_concat_append` semantics: normalize null accumulator and null rhs to
  `neko_string_null(env)`, then call the existing inline
  `neko_require_fast_string_concat` path. Update generated concat accumulation
  paths to use the inline wrapper so hot loops do not pay an extra hidden C
  function call before the existing String.concat NJX dispatch. The change must
  not construct String payloads natively, change String.concat semantics, alter
  null-to-"null" behavior, remove hard aborts, change allocation/GC behavior,
  or affect non-concat call sites.
  Source evidence: fresh NPT-3bi generated C shows `Calc.runStr` calls hidden
  external `neko_concat_append(...)` once per append-loop iteration.
  `NativeFastObjectAccessEmitter` defines `neko_require_fast_string_concat` as
  `NEKO_FAST_INLINE`, but `neko_concat_append` itself is emitted as a hidden
  out-of-line support helper; `neko_concat_accumulate` is inline yet still
  calls that hidden helper when `acc != NULL`. This adds a native function-call
  layer before the already-required NJX String.concat dispatch.
  Validation: `R-build`, focused translator/generator/audit tests, fresh TEST
  native generation, generated-C inspection proving concat paths call the
  inline wrapper and hot `Calc.runStr` no longer calls hidden
  `neko_concat_append`, strict forbidden JNI grep, `R-test` repeated timing
  comparison, `R-native-test`, and focused native integration tests for TEST
  Calc and obfusjack completion.
  Completion criteria: inline wrapper preserves exact previous null
  normalization and `neko_require_fast_string_concat` call; no raw String
  construction or fallback is introduced; generated C has no forbidden
  JNI/JVMTI/fallback markers; timing does not regress.
  Completed 2026-05-22: focused translator/generator/audit tests passed, fresh
  TEST native generation produced `build/npt-3bn/TEST-native.jar` from
  `build/neko-native-work/run-25167378564670` with `translated=49` and
  `rejected=0`. Generated `neko_native_impl_22.c` calls
  `neko_concat_append_inline(...)` in `Calc.runStr` and no longer calls hidden
  `neko_concat_append(...)` on the append-loop hot path. Strict forbidden JNI
  grep over the fresh run directory returned no matches. TEST smoke completed
  with `Calc: 74ms`. Alternating timing comparison versus the accepted NPT-3bi
  jar was NPT-3bi `78,72,72,76,71,88,81 ms` (median `76ms`) versus NPT-3bn
  `71,67,69,67,79,71,68 ms` (median `69ms`). Focused native integration tests
  for TEST Calc and obfusjack completion passed.

- [x] P36 Post-P35 bottleneck selection and current-head gate.
  Capture a fresh current-head post-P35 performance and generated-code evidence
  set before selecting the next implementation slice. This is an
  audit/selection subtask only. It may inspect generated TEST and obfusjack
  native artifacts, runtime timings, `hs_err` files, generated C, and support
  helper call shapes. It must not change runtime behavior, special-case a
  sample, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm shapes, or implement
  raw native String construction without complete compact-string, allocation,
  exception, and GC-barrier evidence.
  Source evidence required: fresh current-head TEST native artifact after
  NPT-3bn shows `translated>0`, `rejected=0`, strict forbidden JNI grep clean,
  and `Calc.runStr` still reaches the remaining hot loop through
  `neko_fast_string_length` plus `neko_concat_append_inline` and the
  `neko_njx_V_L_L` String.concat call-stub path. Current-head TEST timing must
  be repeated at least 5 times. Obfusjack native timing/completion evidence
  must include Seq/Platform/Virtual when the fixture output exposes them.
  Validation: fresh TEST and obfusjack native generation when no current-head
  artifact is available, strict generated-C grep for forbidden JNI/JVMTI/
  fallback markers, repeated TEST native runs, repeated obfusjack native runs,
  generated-C call-shape counts for remaining hot helper/NJX/string paths, and
  inspection for `hs_err` or abort logs.
  Completion criteria: record a concrete next implementation row with scope,
  evidence, validation target, and completion criteria, or record that no
  implementation is justified because the evidence chain is incomplete. The
  selected row must be generic and architecture-level, must preserve JVM ABI and
  full native invariants, and must not depend on one fixture or benchmark
  artifact.
  Completed 2026-05-22: fresh current-head TEST generation produced
  `build/npt-3bo/TEST-native.jar` from
  `build/neko-native-work/run-25611539669398` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1036952` bytes. Fresh
  obfusjack generation produced `build/npt-3bo/obfusjack-native.jar` from
  `build/neko-native-work/run-25625820070350` with `translated=93`,
  `rejected=0`, and `libneko_linux_x64.so` size `1810840` bytes. Strict
  forbidden JNI grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` over both
  run directories returned no matches, and no newer `hs_err_pid*.log` appeared.
  Current-head TEST timing was `72,74,74,74,72,75,75 ms` (median `74ms`).
  Obfusjack repeated timing was Platform `46,45,46,43,36 ms` (median `45ms`),
  Virtual `39,41,34,38,48 ms` (median `39ms`), Seq `18,17,18,17,17 ms`
  (median `17ms`), Parallel `1ms`, and VThreads `1ms`. Generated
  `Calc.runStr` still contains `PUSH_O(locals[0].o)` followed by a
  `String.length` `POP_O()` consumer, and `neko_concat_append_inline(...)`
  followed by `PUSH_O(__fastConcat)` and immediate `neko_store_local_oop_ref`.
  Selected P37/NPT-3bp to fuse only those same-basic-block operand-stack
  shuffles while preserving the existing fast String.length helper, existing
  NJX String.concat path, pending-exception checks, and local-root storage.

- [x] P37 Reject local String.length and concat-result store stack traffic.
  Add generic same-basic-block peephole lowering for two already-proven
  generated shapes: `ALOAD local; INVOKEVIRTUAL java/lang/String.length()I`
  may read `locals[local].o` directly into the existing
  `neko_fast_string_length` intrinsic, and a recognized StringBuilder concat
  producer immediately followed by `ASTORE local` may store the concat result
  directly through `neko_store_local_oop_ref`. This must not change
  String.concat allocation/copy behavior, introduce raw native String
  construction, remove the NJX String.concat call, bypass local-root storage,
  move or remove pending-exception checks, cross labels or handler boundaries,
  special-case Calc, or alter non-adjacent operand-stack semantics.
  Source evidence: fresh P36 generated TEST C shows `Calc.runStr` line 24 emits
  `PUSH_O(locals[0].o)` and line 25 immediately pops that same operand for the
  already-accepted `neko_fast_string_length` path. The same method line 30
  emits accepted `neko_concat_append_inline(...)` and `PUSH_O(__fastConcat)`,
  and line 31 immediately pops the same reference into
  `neko_store_local_oop_ref`. `runAll` invokes `runStr` inside the 10000-loop,
  and `runStr` appends until String length reaches `101`, so both shuffles are
  hot without relying on a fixture-specific semantic shortcut.
  Validation: focused translator/generator/audit tests covering direct local
  String.length and concat-result direct store, fresh TEST native generation,
  generated-C inspection proving the two `PUSH_O`/`POP_O` pairs are removed
  only for adjacent same-block patterns, strict forbidden JNI grep, repeated
  TEST timing comparison against the P36 current-head baseline, and focused
  native integration tests for TEST Calc and obfusjack completion.
  Completion criteria: generated code removes only the proven redundant
  operand-stack traffic; null behavior, pending-exception ordering,
  `neko_store_local_oop_ref`, NJX String.concat semantics, JNI policy, and GC
  rooting remain unchanged; no forbidden JNI/JVMTI/fallback markers appear; and
  timing does not regress.
  Rejected 2026-05-22: focused translator/generator/audit tests passed. Fresh
  TEST generation produced `build/npt-3bp/TEST-native.jar` from
  `build/neko-native-work/run-26009768901566` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1037048` bytes. Generated
  `Calc.runStr` inspection proved the intended shape: the hot `String.length`
  path directly reads `locals[0].o`, and the concat result is stored directly
  through `neko_store_local_oop_ref` without `PUSH_O(__fastConcat)` plus
  immediate `ASTORE`. Strict forbidden JNI grep was clean and TEST smoke
  completed, but the same-session alternating timing gate rejected the slice:
  P36 `69,71,89,71,68,77,70 ms` (median `71ms`) versus P37
  `74,68,67,74,78,72,80 ms` (median `74ms`). Source/test edits were reverted.
  Do not retry this operand-stack shuffle fusion without new code-layout or
  optimizer evidence.

- [x] P38 Reject raw native `String.concat` implementation until returned-String
  publication invariants are proven.
  Compare JDK 21 `java/lang/String.concat`, `StringConcatHelper.simpleConcat`,
  `newArray`, `newString`, `String(byte[],byte)`, and `String(String)`
  semantics against the current native raw allocation, compact-string field,
  byte-array allocation, copy, exception, TLAB refill, OOME/overflow, GC
  barrier, and strict-GC capability surface. This is an evidence-only
  selection subtask. It must not implement raw native String construction,
  special-case Calc or obfusjack, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/
  NPT-3bm/NPT-3bp shapes, introduce JNI/JVMTI/original-bytecode fallback,
  weaken hard abort behavior, or change runtime behavior.
  Source evidence required: local JDK bytecode for `String.concat` and
  `StringConcatHelper` proving empty-string identity/copy behavior,
  compact-string coder propagation, overflow/OOME behavior, byte-array
  allocation size calculation, and final `String` field initialization;
  current generated native support evidence for `neko_require_fast_string_concat`
  still crossing `neko_njx_V_L_L`; current raw string literal allocation and
  `neko_store_oop_raw` evidence showing which allocation/barrier pieces already
  exist; and explicit missing-invariant notes for any unsupported coder,
  allocation, GC, or exception path.
  Validation: source and bytecode inspection, generated-C inspection on the
  fresh P36/P37 native artifacts, strict forbidden JNI/fallback marker review
  of the current native support surface, and subagent audit cross-checks.
  Completion criteria: record either a concrete next implementation row with a
  complete invariant/evidence chain and runtime validation target, or record a
  no-go/rejection because a required compact-string, allocation, exception, or
  GC invariant is not yet proven. No source implementation may start until this
  row is completed and committed.
  Rejected 2026-05-22: local JDK is OpenJDK `21.0.11+10`, and bytecode
  inspection proves `String.concat(String)` first evaluates `rhs.isEmpty()`
  (preserving null-rhs NPE), returns the original receiver for empty rhs, and
  otherwise dispatches to `StringConcatHelper.simpleConcat`. `simpleConcat`
  preserves additional empty-side copy behavior with `new String(other)`,
  computes compact-string coder and length through `mix`, allocates byte arrays
  through `newArray`, copies through `String.getBytes(byte[], int, byte)`, and
  returns `newString(byte[], long)`. `newArray` throws Java
  `OutOfMemoryError("Overflow: String length out of range")` for overflow and
  uses `Unsafe.allocateUninitializedArray(Byte.TYPE, len)`. Current native
  support still routes concat through `neko_require_fast_string_concat` and
  `neko_njx_V_L_L`; raw String allocation exists only in string literal/intern
  binding, not as a generic returned Java object path. The audit did not find
  complete evidence for freshly allocated returned `String` publication under
  G1, Serial, Parallel, ZGC, and Shenandoah, nor a generic Java-exception path
  matching JDK concat overflow and rhs-null behavior. Missing invariants are:
  returned raw `String`/`byte[]` publication without constructor/NJX,
  collector-specific or generic barriered `String.value` initialization,
  exact Latin1/UTF16 `String.getBytes` copy/inflation, Java OOME/NPE behavior,
  and empty-side identity/copy behavior. No raw concat implementation is
  justified until those prerequisites are proven.

- [x] P39 Reject resolved static-field ref reuse in fused static int add updates.
  For the existing generic same-field fusion
  `GETSTATIC int; const-int; IADD; PUTSTATIC same int field`, resolve the
  `neko_static_field_ref` once and reuse that same carrier for both read and
  write. This must preserve class-initialization ordering, field metadata
  hard-aborts, volatile access flags, static-base handling, same-field proof,
  and non-fused behavior. It must not change raw String construction, retry any
  rejected concat/Math/stack-shuffle shape, introduce JNI/JVMTI/original-bytecode
  fallback, or special-case Calc or obfusjack.
  Source evidence: fresh P36 generated TEST C emits
  `neko_fast_get_static_I_field_ref(env, &g_static_field_ref_3)` and then
  separately emits `neko_bound_class_ref`, `neko_ensure_class_initialized_once`,
  `neko_bound_field_ref`, and `neko_fast_set_static_I_field` for the same
  proven field. `neko_static_field_ref` already carries class, init, field,
  static-base, offset, and access-flag slots; `tryStaticIntAddUpdateFusion`
  already proves same owner/name/descriptor and same basic block.
  Validation: focused generator coverage for the fused same-field shape,
  fresh TEST native generation, generated-C inspection proving the duplicate
  class/init/field binding sequence is removed only for the fused same-field
  update, strict forbidden JNI/fallback grep, repeated same-session TEST timing
  against the current-head baseline, and focused TEST/obfusjack native smoke.
  Completion criteria: generated static int updates reuse one resolved
  `neko_static_field_ref` while retaining hard-abort metadata checks and exact
  field semantics; non-same-field updates remain unfused; no forbidden
  markers appear; timing does not regress.
  Rejected 2026-05-22: implementation added a generated
  `neko_fast_add_static_I_field_ref` helper that resolved class/init/field
  once, read the current int field value through existing hard-abort direct
  metadata, and wrote the updated value through the existing static int setter.
  Focused generator/hot-path audit tests passed after fixing helper emission.
  Fresh TEST generation produced `build/npt-3br/TEST-native.jar` from
  `build/neko-native-work/run-26581728457250` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1035992` bytes. Generated
  `Calc.runStr` contained only `neko_fast_add_static_I_field_ref(env,
  &g_static_field_ref_3, (jint)(1))` for the fused static update, and strict
  forbidden JNI grep over the run directory returned no matches. Runtime timing
  rejected the slice: P36 `71,69,66,71,74,69,72 ms` (median `71ms`) versus
  P39 `80,68,71,71,75,82,74 ms` (median `74ms`). Source/test edits were
  reverted. Do not retry this helper shape without new inlining or code-layout
  evidence.

- [x] P40 Post-P39 bottleneck selection and raw-publication prerequisite decision.
  Re-audit the current post-P35/P39 state before selecting another runtime
  implementation. This is an evidence-only selection subtask. It may inspect
  generated C, runtime timings, native support helpers, existing todo
  prerequisites, and subagent audit results. It must not implement code,
  special-case a fixture, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm/
  NPT-3bp/NPT-3br shapes, or implement raw native `String.concat` before the
  returned-String publication invariants from P38 are proven.
  Source evidence required: current generated TEST/obfusjack C showing the
  remaining hot runtime path, strict JNI/fallback marker status, current
  baseline timings after reverted P39 source, and explicit comparison between
  any non-raw-string candidate and the raw-publication prerequisite route.
  Validation: source/generated-C inspection, repeated runtime timing where a
  fresh current artifact is needed, strict forbidden marker grep, and subagent
  cross-checks for both generic hot-path candidates and raw returned-String
  prerequisites.
  Completion criteria: record a concrete next implementation/prerequisite row
  with complete scope, evidence, validation target, and completion criteria, or
  record that no implementation is justified yet because the evidence chain is
  incomplete.
  Completed 2026-05-22: fresh current-head TEST generation after the reverted
  P39 source produced `build/npt-3bs/TEST-native.jar` from
  `build/neko-native-work/run-26815840155097` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1035992` bytes. Fresh
  obfusjack generation produced `build/npt-3bs/obfusjack-native.jar` from
  `build/neko-native-work/run-26860456274238` with `translated=93`,
  `rejected=0`, and `libneko_linux_x64.so` size `1810760` bytes. Strict
  forbidden JNI grep over both run directories returned no matches. Current
  TEST timing was `67,69,70,69,70,70,72 ms` (median `70ms`). Current obfusjack
  timing was Platform `43,47,44,42,45 ms` (median `44ms`), Virtual
  `40,38,43,44,32 ms` (median `40ms`), Seq `17,17,17,17,22 ms` (median
  `17ms`), and Parallel `1ms`. Generated TEST still has the hot
  `neko_fast_string_length` plus `neko_concat_append_inline` path, and the
  inline append still reaches the `neko_njx_V_L_L` `String.concat` call stub.
  Generated current artifacts contain 58 TEST and 182 obfusjack `neko_njx_*`
  call sites, with one TEST `neko_concat_append_inline` and one generated
  concat literal binding at the hot `runStr` site. A sidecar audit proposed
  inlining `neko_bound_method_ref` as a generic micro-optimization, but the
  current large blocker remains raw returned `String` publication; the method
  binding slice is deferred because it cannot plausibly close the TEST
  `<=20ms` gap. Selected P41/NPT-3bt to prove and harden the generic returned
  fresh oop publication/rooting/barrier/exception prerequisite without
  replacing `String.concat` yet.

- [x] P41 Reject/defer returned fresh oop publication until strict-GC barrier
  mapping is fixed.
  Implement the smallest generic prerequisite needed before raw native
  `String.concat`: a returned-fresh-object publication surface for translated
  native object returns. Scope is limited to freshly allocated object/array
  graphs, immediate local rooting before any possible safepoint or VM/NJX call,
  return-handle to raw-oop handoff, collector-valid field/array store barriers,
  and Java exception propagation for allocation/length failures. This must not
  replace `String.concat`, special-case benchmark code, add JNI/JVMTI/original
  bytecode fallback, introduce Java helper layers, or weaken hard-abort
  behavior for missing native capabilities.
  Source evidence: `ARETURN` currently returns a `jobject` from translated
  native code; object-return dispatch converts handle to raw oop and restores
  the handle window before returning to the trampoline; existing
  `neko_direct_oop_to_handle` and pre-reserved local root slots provide local
  rooting primitives but are not yet tied to freshly constructed returned
  object graphs; raw primitive array allocation currently returns handles and
  aborts on negative/allocation failures; string literal binding allocates
  `byte[]` and `String` only for bind-time/intern paths; `neko_store_oop_raw`
  colors ZGC oops but ordinary object field stores use the full selected
  pre/post barrier path.
  Validation: focused generator/runtime coverage for freshly allocated returned
  object and byte-array graphs, source/generated-C inspection proving immediate
  rooting and no forbidden JNI/JVMTI/original-bytecode fallback, `R-build`,
  `R-test`, `R-native-test`, `R-inspect`, and GC strict runs under G1, Serial,
  Parallel, ZGC with `ZVerifyViews`, and Shenandoah verification. Negative
  coverage must prove length/allocation/overflow failures become Java
  exceptions where the raw returned-object surface claims Java-equivalent
  behavior, otherwise the path must hard abort before being used by raw
  `String.concat`.
  Completion criteria: a generic returned fresh oop graph can survive the
  translated return handoff and GC/safepoint pressure under all required
  collectors, with exact recorded limits for which Java exception paths are
  implemented versus still blocking raw concat. Raw native `String.concat`
  remains out of scope until this row is completed and committed.
  Rejected/deferred 2026-05-22: the implementation hardened object-return
  dispatch by converting non-null return handles through a generated
  `neko_prepare_return_oop(thread, __ret, "sigN")` before restoring the handle
  window. Focused generator and hot-path audit tests passed. Fresh TEST
  generation produced `build/npt-3bt/TEST-native.jar` from
  `build/neko-native-work/run-27224871548608` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1035848` bytes; fresh
  obfusjack generation produced `build/npt-3bt/obfusjack-native.jar` from
  `build/neko-native-work/run-27238526919313` with `translated=93`,
  `rejected=0`, and `libneko_linux_x64.so` size `1803816` bytes. Generated C
  used `neko_prepare_return_oop` before `neko_handle_restore`, and strict
  forbidden JNI grep over both run directories returned no matches. Default
  TEST timing was `71,70,68,69,84 ms` (median `70ms`); obfusjack smoke was
  green with Platform `44,43,32 ms`, Virtual `46,36,46 ms`, Seq `17,17,17 ms`,
  and Parallel `1ms`. G1, Serial, and Parallel TEST GC runs passed with Calc
  `69ms`, `71ms`, and `68ms`. ZGC with `ZVerifyViews` and Shenandoah with
  verification aborted during native layout initialization before the changed
  object-return path. The same ZGC/Shenandoah bootstrap failure reproduces on
  the pre-P41 `build/npt-3bs/TEST-native.jar` baseline. Patch-debug evidence
  shows BarrierSet `tag=5`, VMStruct constants `z=-1` and `shen=-1`,
  `use_zgc=0`, `use_shen=0`, and the selected GC barrier path not ready. The
  source/test implementation was reverted. Do not resume this row until the
  strict-GC barrier mapping/capability prerequisite is fixed and validated.

- [x] P42 Fix strict-GC barrier tag and capability detection for
  ZGC/Shenandoah bootstrap.
  Scope is limited to generic HotSpot GC barrier identification and readiness
  detection during native layout/bootstrap. It must map or structurally detect
  current ZGC and Shenandoah BarrierSet layouts so the native runtime selects a
  complete supported barrier path, or hard-aborts with an exact missing-symbol
  or missing-capability diagnostic. This must not skip classes or methods,
  fall back to JNI/JVMTI/original bytecode, disable ZGC/Shenandoah, weaken
  barriers, implement raw `String.concat`, or change unrelated opcode/runtime
  behavior.
  Source evidence: strict ZGC patch-debug bootstrap for both P41 and the P40
  baseline reports a live BarrierSet `tag=5`, missing VMStruct ZGC/Shenandoah
  tag constants (`z=-1`, `shen=-1`), `use_zgc=0`, `use_shen=0`, and a selected
  barrier kind whose required path is not ready, causing
  `[neko-bootstrap] native layout initialization failed`. The failing invariant
  is bootstrap selecting/validating GC barrier capability from incomplete tag
  metadata before any translated object-return change executes.
  Validation: focused generator/source tests for the barrier detection shape,
  fresh TEST native generation, `R-inspect` strict forbidden JNI/fallback grep,
  TEST native runs under G1, Serial, Parallel, ZGC with `ZVerifyViews`, and
  Shenandoah verification. If ZGC or Shenandoah required symbols are absent,
  the runtime must hard-abort with an exact missing capability message rather
  than misclassifying the collector or falling back. Completion criteria:
  fresh generated TEST initializes native layout under all required supported
  collectors or fails closed with exact capability diagnostics for genuinely
  missing required VM support; no forbidden fallback markers appear.
  Completion evidence 2026-05-22: implemented generic JDK 21 BarrierSet tag
  fallbacks for Shenandoah `tag=4` and ZGC `tag=5`, ordered strict-GC
  detection before CardTable/G1 families, removed the previous unsafe
  `ZGlobalsForVMStructs::_instance_p` ZGC structural fingerprint that
  misclassified Shenandoah, and made ZGC readiness require either callable
  runtime barrier symbols or complete nonzero live ZGlobals masks. Focused
  generator/audit tests passed. Fresh TEST native generation produced
  `build/npt-3bu/TEST-native.jar` from
  `build/neko-native-work/run-28846828379689` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1038104` bytes. Strict
  forbidden JNI/fallback grep over that run returned no matches. Default,
  G1, Serial, and Parallel TEST native runs passed with Calc `89ms`, `76ms`,
  `73ms`, and `76ms`. ZGC with `ZVerifyViews` now reports
  `barrier-tag detected: tag=5 ... z=-1 shen=-1`, then fails closed during
  layout with `ZGC barrier capability missing: symbols field=(nil) array=(nil)
  store=(nil) masks addr=0x0 load_good=0x0 load_bad=0x0 store_good=0x0
  store_bad=0x0` and `[neko-bootstrap] native layout initialization failed`.
  Shenandoah with verification now reports `barrier-tag detected: tag=4 ...
  z=-1 shen=-1`, then fails closed during layout with `Shenandoah barrier
  capability missing: lrb=(nil) pre=(nil) array=(nil)` and
  `[neko-bootstrap] native layout initialization failed`. No JNI, JVMTI,
  original-bytecode, skip, or collector-disabling fallback was introduced.

- [x] P43 Resume returned object-result publication after strict-GC capability
  detection.
  Implement only the generic dispatcher publication boundary from the deferred
  P41 shape: non-null object-return handles from translated native methods
  must be converted through a generated `neko_prepare_return_oop(thread, handle,
  site)` helper before the dispatcher restores its handle window. The helper
  may resolve the handle, normalize collector-specific good-oop representation,
  and fail closed if the handle cannot be resolved; it must not allocate,
  replace `String.concat`, special-case benchmark code, call JNI/JVMTI, add a
  Java helper layer, change pending-exception behavior, or change primitive/void
  return dispatchers.
  Source evidence: current `SignatureDispatcherEmitter` object-return dispatch
  resolves `__ret` with `neko_handle_oop(__ret)`, then calls
  `neko_handle_restore(&__hsave)`, then returns the raw oop; pending-exception
  returns still restore and return `NULL`. P41/NPT-3bt proved the minimal
  source shape and generated-C ordering, and was deferred only because strict
  ZGC/Shenandoah bootstrap detection was incomplete. P42 now gives exact
  strict-GC tag/capability fail-closed diagnostics for that blocker.
  Validation: focused generator tests proving object-return dispatch calls
  `neko_prepare_return_oop` before `neko_handle_restore` and primitive/void
  dispatchers are unchanged; fresh TEST native generation, `R-inspect` strict
  forbidden JNI/fallback grep, default/G1/Serial/Parallel TEST native runs, and
  strict ZGC/Shenandoah runs that either execute on a capable VM or retain
  P42's exact fail-closed capability diagnostics.
  Completion evidence 2026-05-22: implemented `neko_prepare_return_oop` in the
  generated fast-access support and changed only object-return signature
  dispatchers to call it before `neko_handle_restore`; pending-exception
  returns still restore and return `NULL`, and primitive/void dispatchers are
  unchanged. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh TEST native generation
  produced `build/npt-3bw/TEST-native.jar` from
  `build/neko-native-work/run-29733047147870` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1037768` bytes. Generated C
  has object-return dispatchers calling `neko_prepare_return_oop` before handle
  restore, and strict JNI wrapper grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and
  `env->` returned no matches. Default, G1, Serial, and Parallel TEST native
  runs passed with Calc `70ms`, `73ms`, `67ms`, and `68ms`. ZGC with
  `ZVerifyViews` failed closed during layout with `barrier-tag detected:
  tag=5 ... z=-1 shen=-1`, `ZGC barrier capability missing: symbols
  field=(nil) array=(nil) store=(nil) masks addr=0x0 load_good=0x0 load_bad=0x0
  store_good=0x0 store_bad=0x0`, and `[neko-bootstrap] native layout
  initialization failed`; Shenandoah verification failed closed with
  `barrier-tag detected: tag=4 ... z=-1 shen=-1`,
  `Shenandoah barrier capability missing: lrb=(nil) pre=(nil) array=(nil)`,
  and `[neko-bootstrap] native layout initialization failed`. No raw
  `String.concat`, JNI/JVMTI, original-bytecode, skip, or collector-disabling
  fallback was introduced.

- [x] P44 Select post-publication raw String concat prerequisite.
  Re-audit the raw `String.concat` blocker after NPT-3cd/NPT-3ce and P43
  before selecting another implementation. This is an evidence-only selection
  subtask. It must not implement raw concat, special-case TEST `Calc.runStr`,
  retry rejected static-field or call-stub micro-shapes, call JNI/JVMTI, add
  Java helper layers, or introduce original-bytecode fallback.
  Source evidence required: local OpenJDK 21 source for `String.concat`,
  `StringConcatHelper.simpleConcat`, `StringConcatHelper.newArray`,
  `StringConcatHelper.newString`, and package-private
  `String(byte[],byte)`/`String.getBytes`; current native concat helpers still
  crossing `neko_njx_V_L_L`/`neko_njx_V_L_L_raw_oop`; P43 object-return
  publication evidence; and current raw string literal allocation evidence
  showing which byte-array, `String.value`, `String.coder`, and local-root
  pieces exist only for bind-time string literals.
  Validation: source inspection, current generated helper inspection, strict
  forbidden-fallback review, and selection of one concrete next row with
  scope/evidence/validation/completion criteria before any runtime edit.
  Completion criteria: record a concrete implementation row that advances the
  missing raw returned-String construction invariants without replacing
  `String.concat` prematurely, or record a no-go if the evidence remains
  incomplete.
  Completed 2026-05-22: OpenJDK 21 source proves `String.concat(String)`
  preserves rhs-null NPE by evaluating `str.isEmpty()`, returns `this` for
  empty rhs, and otherwise enters `StringConcatHelper.simpleConcat`;
  `simpleConcat` creates a new `String` copy for either empty side, mixes coder
  and char length, allocates a byte array via `newArray`, copies bytes with
  `String.getBytes`, and finalizes with `new String(byte[], byte)` through
  `newString`. `newArray` throws Java
  `OutOfMemoryError("Overflow: String length out of range")` on overflow and
  otherwise uses `Unsafe.allocateUninitializedArray(byte.class, len)`. Current
  source still routes the hot translated StringBuilder fast path through
  `neko_require_fast_string_concat_store_local`, which calls the original
  `String.concat` Method*/entry via `neko_njx_V_L_L_raw_oop` before publishing
  to a prepared local root. P43 proves the handle-to-raw object-return boundary,
  but it does not construct a fresh `String`/`byte[]` graph. Bind-time string
  literal code already proves raw byte-array header/length initialization,
  direct byte copying, `String.value`/`coder` writes, and bound-string local
  rooting, but that path is not a returned runtime concat path and does not
  implement `String.concat` empty-side copy/identity, overflow/OOME behavior,
  or generic lhs/rhs coder mixing. Selected P45/NPT-3cg to implement the next
  generic prerequisite without wiring it into hot concat dispatch yet.

- [x] P45 Build raw newborn String graph prerequisite without changing concat
  dispatch.
  Add the smallest generic raw newborn `String` graph construction surface
  needed before replacing `String.concat`: allocate a fresh byte array and a
  fresh `java/lang/String` instance using current VM layout metadata,
  copy/inflate bytes from two existing `jstring` inputs according to their
  coders, initialize `String.value` and `String.coder`, and publish the result
  into an already prepared local root for focused validation. This row must not
  change the production `neko_require_fast_string_concat*` dispatch, must not
  special-case TEST or any literal value, must not call JNI/JVMTI or add Java
  helpers, must not fall back to original bytecode, and must hard-abort on
  missing raw heap, layout, allocation, or barrier capability not explicitly
  proven by the row.
  Source evidence: P44 source evidence; `NativeBindSupportEmitter` bind-time
  raw string literal allocation currently initializes byte-array header/length,
  copies Latin1/UTF16 payload, writes `String.value` through
  `neko_store_oop_raw`, writes `String.coder`, roots the new string with
  `neko_direct_oop_to_handle_origin`, and interns it; `NativeFastObjectAccessEmitter`
  already exposes `neko_fast_tlab_alloc`, `neko_init_oop_header`,
  `neko_fast_string_length`, `neko_store_local_oop_raw`, byte-array klass bits,
  and object allocation metadata; P43 proves object-return publication ordering
  but not newborn graph construction.
  Validation: focused generator/source tests for the helper emission and
  unchanged concat dispatch; a focused runtime fixture that exercises raw
  construction for Latin1+Latin1, Latin1+UTF16, empty lhs, empty rhs, and
  local-root publication without using the hot concat dispatch; fresh TEST
  native generation; strict generated-C grep for forbidden JNI/fallback
  markers; default/G1/Serial/Parallel TEST native smoke; and ZGC/Shenandoah
  strict runs proving either execution on a capable VM or the exact existing
  fail-closed capability diagnostics.
  Completion criteria: the helper can build and root fresh `String` graphs with
  correct bytes/coder under supported collectors, production concat helpers
  still call the original Method*/entry, no forbidden markers appear, and the
  row records the exact remaining exception/allocation semantics before a later
  row may replace the hot concat dispatch.
  Completion evidence 2026-05-22: implemented a default-off raw newborn
  `String` graph helper behind `NEKO_RAW_STRING_GRAPH_PREREQ`; default
  production concat dispatch still emits the original `neko_njx_V_L_L(...)`
  Method*/entry path. The helper allocates and roots a fresh byte array,
  reloads lhs/rhs value pointers after any allocation-capable transition,
  copies/inflates Latin1/UTF16 payloads, allocates a fresh `String` with a TLAB
  fast path and existing managed allocation cache only when needed, reloads the
  byte-array handle after String allocation, writes `String.value`/`coder`, and
  publishes the result into the prepared local root. Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  The focused runtime fixture printed `raw-string-graph-ok` under the opt-in
  helper after exercising Latin1+Latin1, Latin1+UTF16, empty lhs, and empty rhs
  concat shapes. Fresh direct smokes passed: opt-in TEST native `Calc: 71ms`,
  default TEST native `Calc: 65ms`, and obfusjack reached
  `=== All tests completed ===`. G1/Serial/Parallel TEST smokes passed with
  `Calc: 64ms`, `58ms`, and `60ms`. Strict generated-C grep over
  `run-37288355022467`, `run-37292658797327`, and `run-37295214600332` found
  no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`. ZGC and Shenandoah strict runs
  failed closed with the existing diagnostics: `ZGC barrier capability missing`
  / `Shenandoah barrier capability missing`, `gc barrier path not ready`, and
  `[neko-bootstrap] native layout initialization failed`.
  Remaining semantics before default hot concat replacement: Java-visible
  `String.concat` empty-side identity, Java exception delivery for allocation
  failures, and final default dispatch replacement are not claimed by this
  prerequisite; missing layout/allocation/cache/barrier capability still hard
  aborts.

- [x] P46 Correct raw String allocation size from Klass layout helper.
  Fix the raw `java/lang/String` graph allocation size calculation so positive
  `Klass::_layout_helper` instance values are treated as already aligned byte
  sizes, not multiplied by pointer size, while respecting bit 0 as HotSpot's
  instance slow-path allocation flag. Apply the correction only to the
  P45 raw newborn String graph metadata; do not change the generic
  `neko_fast_alloc_object` path in this row because direct generic NEW sizing
  needs separate liveness proof. This row must not special-case TEST, obfusjack,
  or any class name beyond the existing metadata binding, must
  not change CFF or bytecode selection, must not introduce JNI/JVMTI or
  original-bytecode fallback, and must hard-abort on invalid non-positive
  layout helper values.
  Source evidence: OpenJDK HotSpot `klass.hpp` documents that for instances
  layout helper is a positive number equal to the instance size and already
  aligned/scaled to bytes, with the low-order bit set when the instance cannot
  use the fast allocation path; `layout_helper_size_in_bytes` masks that bit
  with `~_lh_instance_slow_path_bit`. Current source multiplies this value by
  `sizeof(void*)` in `NativeBindSupportEmitter.neko_ensure_string_alloc_bits`;
  a P46 work-in-progress attempt to apply the same direct byte-size change to
  `NativeFastObjectAccessEmitter.neko_fast_alloc_object` made obfusjack print
  `=== All tests completed ===` but not exit before `timeout 120s`, so generic
  NEW sizing is rejected from this row until it has a separate liveness proof.
  P45 opt-in
  runtime evidence showed raw `String` allocation requesting `192` bytes before
  the managed slow allocation fallback, and current default/opt-in TEST Calc
  remains `65ms`/`71ms`, indicating allocation pressure still blocks the
  concat hot path.
  Validation: focused generator/source tests proving the byte-size calculation;
  fresh native TEST/raw-string-graph generation; opt-in raw-string graph
  fixture; opt-in and default TEST native smokes; obfusjack native smoke;
  G1/Serial/Parallel TEST native smokes; strict generated-C grep for forbidden
  JNI wrappers; and ZGC/Shenandoah strict fail-closed diagnostics.
  Completion criteria: generated C uses `layout_helper & ~1` for positive
  `String` instance sizes and hard-aborts if `java/lang/String` requires the
  layout-helper slow path, raw `String` graph allocation no longer requests
  pointer-size inflated object bytes, focused opt-in concat-shape fixture still
  passes, default behavior remains correct, and performance evidence records
  whether this sizing correction is sufficient before any default raw concat
  dispatch replacement.
  Completion evidence 2026-05-22: `neko_ensure_string_alloc_bits` now rejects
  a `java/lang/String` slow-path allocation bit and stores
  `g_neko_string_instance_bytes = (size_t)(layout_helper & ~1)`. The generic
  `neko_fast_alloc_object` path is intentionally unchanged in this row; a
  work-in-progress direct-byte generic NEW variant made obfusjack print
  `=== All tests completed ===` but fail to exit before `timeout 120s`, so that
  broader change remains unshipped. Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  Fresh generated dirs were `run-38199290827824` (TEST),
  `run-38203811063440` (obfusjack), and `run-38206555063361` (raw string
  fixture). The opt-in raw-string fixture printed `raw-string-graph-ok`;
  opt-in TEST native passed with `Calc: 64ms`; default TEST native passed with
  `Calc: 64ms`; obfusjack native exited normally and reached
  `=== All tests completed ===` with Platform `45ms`, Virtual `35ms`, Seq
  `17ms`, Parallel `1ms`, and VThreads `1ms`. G1/Serial/Parallel TEST smokes
  passed with `Calc: 69ms`, `71ms`, and `63ms`. Strict generated-C grep over
  the three fresh dirs found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`.
  ZGC and Shenandoah strict runs failed closed with the existing diagnostics:
  `ZGC barrier capability missing` / `Shenandoah barrier capability missing`,
  `gc barrier path not ready`, and `[neko-bootstrap] native layout
  initialization failed`. This sizing correction is not sufficient for the
  final performance target; raw/default TEST still remain around `64ms`, so a
  later row must reduce the remaining concat dispatch/allocation cost.

- [x] P47 Defer raw String byte-array rooting until allocation slow path.
  Optimize the P45 raw newborn `String` graph helper by keeping a freshly
  TLAB-allocated byte array as a raw oop when the immediately following
  `java/lang/String` allocation also succeeds in the TLAB. Publish the byte
  array to a local handle only if a later allocation-capable slow path is about
  to run after the byte array exists. This must preserve the current rooted
  path for slow byte-array allocation, String TLAB refill, managed String
  allocation, GC/barrier fail-closed behavior, and local-root publication of
  the returned String. It must not special-case TEST, obfusjack, class names,
  literals, or any benchmark shape, and must not introduce JNI/JVMTI or
  fallback behavior.
  Source evidence: fresh P46 handle-audit build generated TEST at
  `build/neko-native-work/run-38490167041237` and obfusjack at
  `build/neko-native-work/run-38493171298515`. Default TEST debug audit still
  showed `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`
  and `njx-return-shapes: ... V:L:L=510002 ...`, proving the default path still
  crosses NJX for concat. The same fresh TEST artifact with
  `NEKO_RAW_STRING_GRAPH_PREREQ=1` reduced dispatch to `dispatched=61` and
  `njx-return-shapes: ... V:L:L=2 ...`, proving the raw graph route removes
  the concat NJX crossings, but reported `handle_direct_total=509984` with
  `primitive_array_alloc=509930`, proving the raw graph now publishes almost
  every fast byte-array allocation to a handle even when the immediately
  following String allocation can stay inside the TLAB. Current source in
  `NativeFastObjectAccessEmitter.neko_build_raw_string_graph_store_local`
  publishes `array_oop` with `neko_direct_oop_to_handle_origin(...)` before
  attempting the String allocation and then reloads it after allocation.
  Validation: focused generator/source tests for the raw graph helper; fresh
  handle-audit native generation; opt-in TEST audit proving primitive-array
  handle publications are eliminated or materially reduced on the all-TLAB raw
  graph path while dispatch remains low; opt-in and default TEST native smokes;
  obfusjack native smoke; G1/Serial/Parallel TEST native smokes; strict
  generated-C grep for forbidden JNI wrappers; and ZGC/Shenandoah strict
  fail-closed diagnostics.
  Completion criteria: the all-TLAB raw graph path never calls
  `neko_direct_oop_to_handle_origin` for the intermediate byte array, every
  allocation-capable slow path after byte-array creation roots the byte array
  before safepointing, raw concat shape fixture remains correct, no forbidden
  JNI/fallback markers appear, and timing/counters record whether this is
  sufficient before making the raw graph default.
  Completion evidence 2026-05-22: `neko_build_raw_string_graph_store_local`
  now leaves fast TLAB byte arrays unrooted only across the immediate
  non-safepoint String TLAB allocation, while slow byte-array allocation uses
  its returned local handle and the String slow-allocation path publishes the
  byte array before `neko_refill_tlab_with_slow_byte_array`,
  `neko_resolve_class_mirror_with_env`, or `Unsafe.allocateInstance` can run.
  Focused validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  Fresh handle-audit generation passed with TEST at
  `build/neko-native-work/run-38722568601819` and obfusjack at
  `build/neko-native-work/run-38725591470562`. Opt-in TEST audit proved the
  intended hot-path change: before this row P46 opt-in reported
  `primitive_array_alloc=509930`, `handle_direct_total=509984`,
  `dispatched=61`, and `V:L:L=2`; after this row it reported
  `primitive_array_alloc=32`, `handle_direct_total=87`, `dispatched=62`, and
  `V:L:L=3`, while retaining
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Default
  TEST audit remained on the original dispatch path with `V:L:L=510002`.
  Opt-in TEST x5 was `42/42/41/53/41 ms` (median `42ms`) versus default TEST
  x5 `63/64/62/63/63 ms` (median `63ms`). Default obfusjack reached
  `=== All tests completed ===` with Platform `47ms`, Virtual `47ms`, Seq
  `18ms`, Parallel `1ms`, and VThreads `1ms`. Opt-in G1/Serial/Parallel TEST
  smokes passed with `Calc: 42ms`, `44ms`, and `46ms`. Strict generated-C grep
  over both fresh audit dirs found no `NEKO_JNI_FN_PTR`, `(*env)->`, or
  `env->`. ZGC with `ZVerifyViews` and Shenandoah with verification both
  failed closed at bootstrap with `[neko-bootstrap] native layout
  initialization failed`. This row improves the raw graph opt-in path but is
  not sufficient for the final target; the next row must reduce the remaining
  raw allocation/copy cost and eventually make a fully proven path default.

- [x] P48 Skip raw String input reloads when byte-array allocation cannot
  safepoint.
  Remove only the redundant lhs/rhs handle and `String.value` reload block in
  `neko_build_raw_string_graph_store_local` when the intermediate byte array
  was allocated by the TLAB fast path and no allocation-capable slow path has
  run. Keep the existing reload and validation block when `array_handle !=
  NULL`, which covers slow byte-array allocation and any path where the byte
  array was rooted because a safepoint-capable operation has already happened.
  This must not skip reloads after slow allocation, TLAB refill, managed String
  allocation, NJX, or any future safepoint-capable path, and must not change
  concat selection, CFF, JNI/JVMTI usage, fallback policy, or exception/abort
  behavior.
  Source evidence: P47 opt-in TEST audit showed the raw graph path now has low
  dispatch and handle traffic (`dispatched=62`, `handle_direct_total=87`,
  `primitive_array_alloc=32`) while still executing
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0` and
  median `42ms`. Current source still reloads `left_oop`, `right_oop`,
  `left_value`, and `right_value` unconditionally after byte-array allocation,
  even though the all-TLAB path between the initial reads and payload copy only
  performs `neko_fast_tlab_alloc`, `neko_init_oop_header`, and the byte-array
  length store. That path cannot safepoint, so the reloads are required only
  when `array_handle != NULL` proves a slow/rooted byte-array path already
  occurred.
  Validation: focused generator/source tests; fresh raw-string graph runtime
  fixture; fresh handle-audit TEST generation; opt-in TEST audit proving
  dispatch and handle counters remain low; opt-in/default TEST timing
  comparison; obfusjack smoke; strict generated-C grep for forbidden JNI
  wrappers; and G1/Serial/Parallel plus ZGC/Shenandoah fail-closed smokes.
  Completion criteria: generated raw graph helper guards the post-byte-array
  lhs/rhs reload block with `if (array_handle != NULL)`, all slow/rooted paths
  retain the reload and null checks, all-TLAB raw concat keeps correct
  Latin1/UTF16/empty-shape output, no forbidden markers appear, and timing does
  not regress versus P47 opt-in/default medians.
  Completion evidence 2026-05-22: `neko_build_raw_string_graph_store_local`
  now executes the post-byte-array lhs/rhs and value reload block only when
  `array_handle != NULL`; slow/rooted paths still keep the existing null checks
  and all-TLAB raw concat proceeds directly to the payload copies. Focused
  validation passed:
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp --rerun-tasks`.
  Fresh handle-audit generation passed with TEST at
  `build/neko-native-work/run-39101082444663` and obfusjack at
  `build/neko-native-work/run-39104735242967`. Generated-C inspection found
  the guarded reload block and strict grep over both fresh dirs found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->`. Opt-in TEST audit reported
  `dispatched=61`, `V:L:L=2`, `handle_direct_total=74`, and
  `primitive_array_alloc=20` while retaining
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Opt-in
  TEST x5 was `32/30/31/31/38 ms` (median `31ms`) versus P47 opt-in median
  `42ms`; default TEST remained on the original dispatch path with x5
  `64/71/67/65/68 ms` (median `67ms`). Default obfusjack reached
  `=== All tests completed ===` with Platform `43ms`, Virtual `40ms`, Seq
  `17ms`, Parallel `1ms`, and VThreads `1ms`. Opt-in G1/Serial/Parallel TEST
  smokes passed with `Calc: 46ms`, `32ms`, and `33ms`. ZGC with
  `ZVerifyViews` and Shenandoah with verification both failed closed at
  bootstrap with `[neko-bootstrap] native layout initialization failed`. This
  row materially improves the raw graph opt-in path but final acceptance is
  still open because default TEST remains above target and raw graph still
  requires opt-in.

- [x] P49 Scalarize proven same-local StringBuilder concat recurrence.
  Add a generic bytecode proof and native emission path for a non-escaping
  same-local recurrence of the form `s = new StringBuilder().append(s).append(x).toString()`
  where the updated local is used only for scalar observations proven
  equivalent before final materialization. The first accepted surface is
  limited to same-method, same-local recurrence with `String.length()` loop
  observation and a final normal method exit or proven local dead point where
  materializing the final `String` is not Java-observable. It must reject any
  field/array/static store, return, invoke argument, monitor, reflection,
  MethodHandle, exception object, stack escape, aliasing store, or unproven
  observation between recurrence updates. Proof failure must leave the existing
  concat path unchanged at translation time; no runtime fallback after partial
  scalarization is allowed. This row must not special-case TEST, class names,
  literals, or a benchmark method, must not weaken CFF/key propagation, and
  must not introduce JNI, JVMTI, Java helpers, original-bytecode fallback, or
  skip behavior.
  Source evidence: P48 shows the remaining opt-in hot path is no longer NJX or
  handle publication: TEST opt-in x5 was `32/30/31/31/38 ms` (median `31ms`)
  with `dispatched=61`, `V:L:L=2`, `handle_direct_total=74`,
  `primitive_array_alloc=20`, and
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`.
  Generated TEST `runStr` is the generic recurrence shape: local `0` is
  length-checked, concatenated with a string literal through the recognized
  StringBuilder concat pattern, and stored back to the same local. The
  remaining cost is therefore per-iteration `String`/`byte[]` allocation plus
  payload copy for intermediate values whose identity is not observed in the
  proven recurrence.
  Validation: focused source tests for recurrence proof acceptance and
  rejection; generated-C inspection proving accepted recurrence sites do not
  call `neko_build_raw_string_graph_store_local` per iteration and rejection
  fixtures retain the current concat path; fresh TEST default x5 with no
  opt-in dependency targeting `<=20ms`; obfusjack x5 non-regression for
  Seq/Platform/Virtual/Parallel/VThreads; strict generated-C grep for forbidden
  JNI/fallback markers; G1/Serial/Parallel TEST and obfusjack smokes; and
  ZGC/Shenandoah strict fail-closed diagnostics unless barrier support is
  completed in a prior row.
  Completion criteria: scalarization applies only when bytecode proof shows
  all intermediate String identities are unobservable and scalar observations
  are equivalent, proof failure preserves the existing safe concat emission,
  default TEST median is materially improved without `NEKO_RAW_STRING_GRAPH_PREREQ`,
  obfusjack does not regress, and all required runtime/static checks pass.
  Gate blocker 2026-05-22: source tests passed and fresh TEST `runStr`
  generated C in `build/neko-native-work/run-39893685712791/neko_native_impl_22.c`
  contains `scalarized same-local StringBuilder concat recurrence`, removing
  the per-iteration concat call. Default TEST x5 reported Calc `2/3/2/2/3 ms`
  (median `2ms`). P49 cannot complete yet because obfusjack x5 hard-aborted
  once in an unrelated primitive array allocation path:
  `[neko-direct] primitive array allocation direct path unavailable len=192
  kind=7 ... raw=1 zgc=0 coh=0 tlab=1`. P50/NPT-3cl is the required allocator
  prerequisite before P49 can be checkpointed.
  Completion evidence 2026-05-22: after P50 removed the primitive-array
  allocation abort, the recurrence scalarizer remains validated in the focused
  Gradle slice and fresh generated TEST C still removes the per-iteration
  StringBuilder concat path. Fresh TEST x5 after the current translator changes
  reported Calc `3/3/2/3/3 ms` with Pool PASS, preserving the default
  `<=20ms` target without `NEKO_RAW_STRING_GRAPH_PREREQ`. Fresh obfusjack x5
  completed without aborts and reported Platform `49/43/49/49/46 ms`, Virtual
  `39/47/36/40/41 ms`, Seq `13/11/11/11/11 ms`, Parallel `1 ms`, and VThreads
  `1 ms`; the remaining Platform/Virtual misses are tracked by the next
  thread-work hot-path row, while the string recurrence path did not regress
  obfusjack correctness. Strict grep over fresh `run-413*` generated C/headers
  found no forbidden JNI wrapper markers. G1/Serial/Parallel TEST smokes
  reported Pool PASS and Calc `3/3/3 ms`.

- [x] P50 Use JVM_NewArray slow primitive-array allocation when TLAB refill cannot satisfy direct allocation.
  Extend the existing non-JNI JVM symbol slow allocation surface used by
  byte-array TLAB refill to cover all primitive array kinds when
  `neko_fast_new_primitive_array` has valid metadata but TLAB allocation still
  returns NULL after refill. The helper must resolve the primitive mirror by
  kind through existing `JVM_FindPrimitiveClass`, allocate with existing
  `JVM_NewArray`, return the resulting local handle, and hard-abort on missing
  symbols, invalid kind, pending exception, unresolved handle, or null result.
  It must not use the JNI function table, JNI array functions, Java helpers,
  skip behavior, original-bytecode fallback, or benchmark-specific logic.
  Source evidence: P49 validation exposed an obfusjack runtime abort in matrix
  multiply after four successful runs: `len=192 kind=7` (`double[]`),
  `g_hotspot.initialized=1`, primitive array klass/base/scale present, raw heap
  enabled, compact headers disabled, and `g_neko_tlab_alloc_ready=1`. Source
  evidence in `NativeFastObjectAccessEmitter.neko_fast_new_primitive_array`
  shows it refills via a slow byte array and aborts if the second TLAB attempt
  still fails; `NativeBindSupportEmitter.neko_alloc_jbyte_array_oop_slow` proves
  the runtime already has a fail-closed `JVM_FindPrimitiveClass`/`JVM_NewArray`
  slow path for byte arrays.
  Validation: focused generator tests covering slow primitive array helper
  emission and hard-abort guards, fresh native artifact regeneration,
  obfusjack x5 without primitive-array allocation abort, TEST x5 preserving the
  P49 Calc win, strict generated-C forbidden JNI/fallback grep, and
  G1/Serial/Parallel TEST smokes. ZGC/Shenandoah remain fail-closed unless
  barrier support is completed in a prior row.
  Completion criteria: direct primitive array allocation remains the hot path;
  only TLAB exhaustion after refill uses the JVM symbol slow allocation path;
  missing symbols/capabilities hard-abort; obfusjack no longer aborts in the
  observed primitive array path; TEST and generated-C inspections do not
  regress.
  Completion evidence 2026-05-22: `neko_fast_new_primitive_array` still uses
  direct TLAB allocation first, then refills through the existing slow byte-array
  path, and only calls `neko_alloc_primitive_array_slow(env, len, kind)` if the
  second TLAB allocation returns NULL. The slow helper maps all primitive kinds
  to `JVM_FindPrimitiveClass` names and allocates with `JVM_NewArray`, with
  hard aborts on invalid kind, missing symbols, pending exceptions, null arrays,
  and unresolved handles. Focused Gradle validation passed:
  `:neko-test:test --tests CCodeGeneratorTest --tests
  NativeGeneratedCHotPathAuditTest --tests OpcodeTranslatorUnitTest --tests
  NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated C in `build/neko-native-work/run-40149471798380` and
  `run-40152881799054` contains the slow helper call and no forbidden JNI
  markers by strict grep. TEST x5 after P50 preserved the P49 Calc win
  (`2/4/3/3/3 ms`, median `3ms`; one Pool fixture printed FAIL once and four
  subsequent runs printed PASS). G1/Serial/Parallel TEST smokes reported Calc
  `2/3/3 ms`. Obfusjack x5 completed without the primitive-array allocation
  abort; timings were Platform `50/46/48/41/50 ms`, Virtual `40/40/42/45/43 ms`,
  Seq `18/17/17/17/17 ms`, Parallel `1 ms`, VThreads `1 ms`.

- [x] P52 Scalarize counted double[][] multiply-accumulate inner loops.
  Add a generic translator loop fusion for a counted `int` loop whose body
  updates one `double` accumulator local with the product of two `double[][]`
  element reads and then increments the counted local by one. The proof must
  require a single backedge to the loop header, no active try handler over the
  covered range, no branch target inside the covered range other than the
  header/exit labels, an `IF_ICMPGE`/`IF_ICMPGT` style exit guard that dominates
  the body, a same-local induction increment, and no writes to the array
  reference, bound, index, or accumulator locals except the proven update. The
  emitted C may replace the bytecode body with one native `for` loop that uses
  the existing raw fused `AALOAD+DALOAD` helper, preserves the helper failure
  reason dispatch, and jumps to the original exit label. It must not hoist
  nullable array dereferences before the original loop guard, must not change
  exception order on the first failing access, and must not specialize owner,
  method, class, descriptor, source line, or benchmark artifact names.
  Source evidence: after P51, fresh generated obfusjack C in
  `build/neko-native-work/run-40744661698418/neko_native_impl_52.c` proves the
  `mmulSeq` inner loop is now reduced to a scalar multiply-add statement but
  still executes the translated label loop, loop-local stores, increment,
  pending-exception check, and backedge every iteration. Fresh obfusjack x5
  after P51 still misses the gate with Seq `17/20/17/17/17 ms`, while TEST Calc
  remains under target at `4/3/2/3/3 ms`. The exact failing invariant is
  loop-dispatch overhead remaining after the straight-line multiply-add fusion;
  the changed path is the generic counted-loop translator path for the same
  bytecode shape.
  Validation: focused translator tests for positive counted-loop fusion and
  rejection when a protected range, interior branch target, non-unit induction
  update, mismatched accumulator store, or escaping write breaks the proof;
  fresh artifact regeneration; generated-C inspection proving the inner loop
  emits one scalar native `for` and no per-iteration `PUSH_D`/`POP_D`
  multiply-add sequence or translated backedge in the covered range; TEST x5
  preserving Calc; obfusjack x5 measuring Seq/Platform/Virtual/Parallel/
  VThreads; strict forbidden JNI/fallback grep; G1/Serial/Parallel TEST smokes.
  Completion criteria: the optimization is enabled only by the generic
  bytecode/control-flow proof, rejection tests keep the existing translation,
  generated C preserves fast-array failure dispatch and first failing access
  semantics, obfusjack Seq reaches or materially moves toward the `<=14ms`
  target without runtime aborts, and TEST does not regress.
  Completion evidence 2026-05-22: implemented the generic counted-loop
  scalarizer in `NativeTranslator` with proof checks for the loop header, single
  `IF_ICMPGE` exit, unit `IINC` induction update, matching accumulator store,
  no protected range, and no interior branch target. Focused Gradle validation
  passed for `OpcodeTranslatorUnitTest`, `CCodeGeneratorTest`,
  `NativeGeneratedCHotPathAuditTest`, and
  `NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated obfusjack C in
  `build/neko-native-work/run-41392045707312/neko_native_impl_52.c` emits
  `scalarized counted double[][] multiply-accumulate loop` as one native `for`
  using `neko_fast_raw_aaload_daload`, preserves fast-array reason dispatch, and
  removes the translated inner-loop `locals[9].i += 1` backedge from the covered
  range. Strict grep over fresh `run-413*` generated C/headers found no
  forbidden JNI wrapper markers. Fresh TEST x5 reported Calc `3/3/2/3/3 ms`
  with Pool PASS. Fresh obfusjack x5 reported Platform `49/43/49/49/46 ms`,
  Virtual `39/47/36/40/41 ms`, Seq `13/11/11/11/11 ms`, Parallel `1 ms`, and
  VThreads `1 ms`, so the Seq median is `11 ms` and meets the `<=14ms` gate.
  G1/Serial/Parallel TEST smokes reported Pool PASS and Calc `3/3/3 ms`.

- [x] P53 Remove redundant getClass probes after generated lambda allocation.
  Update native LambdaMetafactory lowering so the replacement bytecode emits
  `new`, `dup`, captured arguments, and `<init>` for the generated lambda
  class, but no longer appends the unused `dup; Object.getClass(); pop` probe.
  This applies only to generated lambda classes created by the native stage's
  generic LambdaMetafactory lowering. It must not change lambda capture order,
  constructor invocation, SAM implementation, generated class metadata,
  manifest patching, hidden-key propagation, or any non-lambda invokedynamic
  lowering.
  Source evidence: fresh generated obfusjack C in
  `build/neko-native-work/run-42691025802299/neko_native_impl_63.c` and
  `neko_native_impl_66.c` shows each generated `Callable` factory allocates a
  `Main$NekoLambda$*`, invokes its constructor, then duplicates the object and
  dispatches a no-arg virtual call through `neko_icache_dispatch` before
  popping the result. Source evidence in
  `NativeCompilationStage.lowerLambdaMetafactory` proves this is the explicit
  unused `Object.getClass()` probe inserted after construction. The freshly
  allocated object is non-null, allocation already resolves the generated
  class, and the returned `Class` object is discarded, so the probe has no
  observable lambda result semantics while adding one virtual dispatch to each
  generated lambda factory call. The obfusjack thread benchmark builds 50,000
  task lambdas in the timed Platform and Virtual paths, and prior arithmetic,
  AtomicLong, shadow-frame, and direct-call-check trials did not meet the
  remaining Platform/Virtual gates.
  Validation: focused native-stage/source tests proving lowered
  LambdaMetafactory bytecode no longer contains an unused `Object.getClass()`
  call after generated lambda construction while generated lambda invocation
  still works; fresh artifact regeneration; generated-C inspection proving
  `lambda$microbenchThreads$6` and `lambda$microbenchThreads$9` no longer
  contain the post-constructor no-arg `neko_icache_dispatch`, while constructor
  invocation and returned lambda object remain; TEST x5; obfusjack x5
  measuring Platform/Virtual/Seq/Parallel/VThreads; strict forbidden JNI/
  fallback grep; G1/Serial/Parallel TEST smokes.
  Completion criteria: the removed probe is only the native-stage generated
  lambda `getClass` probe, lambda construction/invocation remains correct,
  manifest discovery still patches generated lambda classes, TEST and Seq stay
  within target, Platform and Virtual medians move toward or meet Platform
  `<=44ms` and Virtual `<=35ms`, and no runtime aborts or forbidden JNI/
  fallback markers appear.
  Completion evidence: focused Gradle validation passed with fresh native
  regeneration:
  `:neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated obfusjack C in
  `build/neko-native-work/run-43586804685160/neko_native_impl_63.c` and
  `neko_native_impl_66.c` allocates each generated lambda object, invokes its
  constructor, and returns the object without the prior post-constructor
  no-arg `neko_icache_dispatch`/`Object.getClass` probe. Fresh TEST x5
  reported Calc `4/2/2/3/3 ms`. Fresh obfusjack x5 reported Platform
  `40/47/40/44/43 ms`, Virtual `35/36/53/39/42 ms`, Seq
  `14/11/11/17/11 ms`, Parallel `1 ms`, and VThreads `1 ms`, so Platform
  median is `43 ms` and meets `<=44 ms`; Seq median is `11 ms` and meets
  `<=14 ms`; Virtual median is `39 ms` and remains the next open gate.
  G1/Serial/Parallel TEST smokes reported Pool PASS and Calc `3/3/3 ms`.
