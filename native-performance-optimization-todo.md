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

- [-] P10 Reduce NJX call-stub per-call allocation and zeroing. Replace per-call `calloc/free` of the temporary Java handle block with a scoped reusable per-thread or caller-owned block that preserves GC root visibility and nested-call semantics. Remove full `memset` of call parameter arrays when all used slots are explicitly written, while keeping required two-slot padding initialized. Source evidence: `NativeToJavaInvokeEmitter.java:187-222` allocates/frees handle blocks; `NativeToJavaInvokeEmitter.java:304` and `:348` zero buffers in the generic path; shape-specific generation also emits `memset` in `NativeToJavaInvokeEmitter.java:813` and `:844`. Validation: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, performance gate, GC strict compatibility gate; stress tests must prove no stale roots or handle-chain corruption.
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

- [ ] P15 Tighten native performance tests after P0 baseline exists. Extend native performance tests to record and assert TEST Calc median plus obfusjack parsed matrix/thread medians when those output lines are present. Keep thresholds relative to the immediate baseline until stable absolute gates are justified by current-source measurements. Source evidence: current perf tests only parse TEST Calc at `NativeObfuscationPerfTest.java:71-91`; obfusjack integration currently checks completion, not performance, at `NativeObfuscationIntegrationTest.java:101-109`. Validation: `R-build`, `R-test` x5, `R-obfusjack` x5, `R-native-test`, `R-inspect`.
