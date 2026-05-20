# Native Post-Transfer Performance and Compile-Time Execution Plan - 2026-05-19

## Scope

Continue the current native work after the native transfer/JNI-removal stages by
executing one evidence-backed optimization at a time across two tracks:

- runtime/native-transfer performance: `native-gentle-flamingo-todo.md` Stage 4
  and `native-performance-optimization-todo.md` P3+;
- native compile-time/code-shape performance: continuation after
  `.plan/native-compile-parallelization-2026-05-17.md` P41.

This document is the matching `.plan/` audit queue for the current work. It does
not replace the existing source plans; each implementation row below must update
the source plan that owns the changed path before it can be considered complete.

## Status Legend

- `[ ]` not implemented / not freshly verified
- `[-]` actively in progress only while that exact row is being worked
- `[x]` implemented, freshly regenerated, runtime-verified, inspected, and
  committed where repository policy permits
- `[rejected]` measured and reverted or not retained because acceptance failed

## Non-Negotiable Constraints

- Do not implement or optimize for a special jar, benchmark, class, method,
  owner, descriptor, crash site, or log string.
- Do not weaken JVM ABI behavior, obfuscation coverage, CFF/key-flow semantics,
  native coverage, GC compatibility, or JNI-removal goals.
- Do not add JNI fallback, JVMTI, skip-on-error success, original-bytecode
  fallback, Java helper layers, or new bootstrap behavior.
- Do not reuse stale jars, stale generated C, stale manifests, unit-only passes,
  or compile-only results as runtime proof.
- Use a repository-local temporary directory such as `build/native-run-tmp` for
  runtime commands; do not use `/tmp`.
- Repository `./gradlew` use requires explicit user permission before running.

## Existing Plan State Reconciled Before Implementation

- `native-gentle-flamingo-todo.md` Stage 4 remains open. T4.0 is first in the
  Stage 4 ordering because `neko_exception_check_resolve_env_offset` is recorded
  as a structural post-transfer performance prerequisite before the wider T4.14
  performance gate.
- `native-performance-optimization-todo.md` has P0/P1 complete. P2 is blocked by
  GC strict prerequisites. P3 is partially implemented by
  `.plan/native-compile-parallelization-2026-05-17.md` P25 but still needs the
  broader dependency-jar stack-trace fixture and GC strict compatibility gate.
  P4-P15 remain open.
- `.plan/native-compile-parallelization-2026-05-17.md` has accepted work through
  P41. Rejected rows P27/P29/P33/P34/P36/P37/P40 must not be retried in the same
  shape without new root-cause evidence explaining the rejection.
- `.plan/native-full-validation.md` V1-V3 remain open as a final validation
  sweep, and `.plan/native-full-repair.md` R2-R5 remain open for full native-only
  repair/acceptance.

## Execution Rows

### [x] NPT-0: Reconcile current native performance and compile-time plan state

- Scope: locate the active runtime performance, native transfer, compile-time,
  full-validation, and full-repair plans before changing code.
- Required evidence: exact file paths, open rows, validation targets, rejected
  shapes, and current generated-artifact locations.
- Validation command or runtime target: read-only repository exploration; no
  Gradle or runtime command.
- Completion criteria: this document records the current source-plan state and
  the next executable rows without inventing a bottleneck or changing runtime
  behavior.
- Evidence: completed read-only exploration of
  `native-performance-optimization-todo.md`, `native-gentle-flamingo-todo.md`,
  `.plan/native-compile-parallelization-2026-05-17.md`,
  `.plan/native-full-validation.md`, and `.plan/native-full-repair.md`.

### [x] NPT-1: Capture fresh post-P41 baseline before the next optimization

- Scope: regenerate and measure the current accepted source state after P41 so
  both runtime and compile-time work starts from fresh artifacts, not the stale
  P41 evidence embedded in the plan.
- Required evidence: git revision/status, focused native audit output, fresh
  native-only generation rows for TEST/test21/SnakeGame/evaluator, per-source
  compile/link elapsed rows, generated source counts, packaged library sizes,
  direct runtime stdout/stderr for TEST/test21/evaluator/SnakeGame smoke, parsed
  TEST Calc and test21 Platform/Virtual/Seq/Parallel/VThreads timings, newest
  `hs_err_pid*.log` scan, and generated-C forbidden-marker inspection.
- Validation command or runtime target: after explicit permission to use
  `./gradlew`, run the focused native audit/tests needed to regenerate native
  artifacts, then run native-only generation through `.plan/native-only-full.yml`
  and direct jar commands with `-Djava.io.tmpdir=build/native-run-tmp`.
- Completion criteria: regenerated artifacts report `translated>0 rejected=0`,
  no native compilation fallback, no skip-on-error success, no original-bytecode
  fallback, no executable forbidden JNI/JVMTI markers, no fatal JVM/runtime
  errors, and the next runtime plus compile-time rows are selected from the
  fresh measurements.
- Partial evidence: focused Gradle audit passed after explicit `./gradlew`
  permission with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Exit code `0`; Gradle reported
  `BUILD SUCCESSFUL in 8s` with 19 actionable tasks. This is only the focused
  audit checkpoint; NPT-1 remains open until fresh four-jar native-only
  generation, direct runtime runs, manifest/timing capture, `hs_err` scan, and
  forbidden-marker inspection are complete.
- Completion evidence 2026-05-20 at git `4f537f98685afee2debcea9b6e1eb0b2d0a9017a`
  with clean worktree before generation: focused Gradle audit reran after
  explicit permission and passed with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL in 2s`, 19
  up-to-date tasks). Fresh `.plan/native-only-full.yml` CLI generation wrote
  `build/native-post-transfer-baseline/{TEST,obfusjack,SnakeGame,evaluator}-native-only.jar`;
  generated counts / translated rows were TEST 67 C files, `translated=49
  rejected=0`; obfusjack 113 C files, `translated=93 rejected=0`; SnakeGame 35
  C files, `translated=18 rejected=0`; evaluator 143 C files, `translated=122
  rejected=0`. Direct runtime logs under `build/native-post-transfer-baseline/`
  had no fatal markers: TEST x5 Calc = 34/39/34/36/35 ms; obfusjack x5
  completed with Seq = 29/28/29/28/29 ms, Parallel = 3/3/3/3/3 ms, VThreads =
  5/7/5/6/6 ms, Platform = 813/771/767/894/865 ms, Virtual =
  793/812/814/892/932 ms; SnakeGame exited with expected headless behavior;
  evaluator exited 0. No `hs_err_pid*.log` was created under
  `build/native-run-tmp`, `build/native-post-transfer-baseline`, or
  `build/neko-native-work`; newest root `hs_err_pid1039252.log` predates this
  run. Generated-C executable-marker inspection in
  `build/native-post-transfer-baseline/native-post-transfer-baseline-summary.json`
  found zero `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, function-table indexing,
  JNI call wrappers, array JNI wrappers, or JVMTI markers. Compile totals/top
  rows are recorded in the same summary JSON.

### [ ] NPT-2: Stage 4 T4.0 eager exception-check env-offset publication

- Scope: implement the Stage 4.A structural performance prerequisite from
  `native-gentle-flamingo-todo.md` T4.0: ensure
  `neko_exception_check_resolve_env_offset` is not reachable from the hot
  `neko_exception_check` path after publication.
- Required evidence: current generated C and runtime logs proving the resolver
  can still be reached from hot exception-check sites, the exact publication
  invariant chosen (`JNI_OnLoad` bootstrap, first dispatcher entry, TLS cache, or
  outlined unlikely helper), and generated C proving the hot path is an
  acquire-load plus pointer arithmetic plus `_pending_exception` read.
- Validation command or runtime target: `R-build`, `R-test` x5,
  `R-obfusjack` x5, `R-native-obfusjack` x10, `R-inspect`, and `R-negative` as
  specified by T4.0, with fresh artifacts only.
- Completion criteria: perf/debug evidence shows at most one env-offset
  derivation per process, missing-offset paths hard abort, TEST Calc and
  obfusjack timing gates do not regress, and generated/runtime inspection shows
  no new JNI function-table use or fallback path.
- Dependency: NPT-1, unless a plan-level update records why a narrower fresh
  baseline is sufficient.

### [-] NPT-3: Runtime P4 const fast-state accessor layer

- Scope: implement `native-performance-optimization-todo.md` P4 after the T4.0
  hot exception-check path is stable: post-bootstrap immutable fast-state
  accessors should read `g_hotspot_const` instead of mutable-looking
  `g_hotspot`, while collector-dynamic values such as ZGC mask pointers remain
  mutable.
- Required evidence: generated C/source inspection proving which accessors are
  immutable after bootstrap, which collector fields must remain dynamic, and how
  the changed accessors are used by hot helpers.
- Validation command or runtime target: `R-build`, `R-test`, `R-obfusjack`,
  `R-native-test`, `R-inspect`, performance gate, and GC strict compatibility
  gate from `native-performance-optimization-todo.md` P4.
- Completion criteria: generated C or compiler diagnostics show the intended
  immutable reads use `g_hotspot_const`, dynamic GC mask paths remain mutable,
  TEST/obfusjack runtime and GC gates pass with fresh artifacts, and no ABI,
  GC, JNI-removal, or fallback invariant is weakened.
- Dependency update: NPT-1 fresh generated C shows T4.0's structural hot path
  is already present in current source: `neko_exception_check` reads
  `g_neko_off_thread_jni_environment_for_check` directly, and the cold
  resolver is driven from `neko_method_layout_init`, not from the hot path.
  The remaining measured gap is performance, so P4 is the next generic
  post-bootstrap invariant optimization before revisiting wider T4.0/T4.14
  gates.
- P4 implementation evidence 2026-05-20: changed only
  `NativeHotSpotFastAccessEmitter` const accessors so post-bootstrap immutable
  fields read `g_hotspot_const`; source and generated C keep ZGC live-mask
  helpers on mutable `g_hotspot.z_zglobals_*` pointers. Focused Gradle audit
  passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL in 6s`).
  Fresh `.plan/native-only-full.yml` artifacts in
  `build/native-post-transfer-p4/` report TEST `translated=49 rejected=0`,
  obfusjack `translated=93 rejected=0`, SnakeGame `translated=18 rejected=0`,
  evaluator `translated=122 rejected=0`; generated TEST C
  `build/neko-native-work/run-3244581472985/neko_native_support.c` shows all
  `neko_const_*` immutable accessors reading `g_hotspot_const`, while dynamic
  ZGC mask helpers still read mutable mask pointers. Runtime: TEST x5 Calc =
  35/35/35/33/34 ms; obfusjack x5 completed with Seq = 23/23/23/22/25 ms,
  Parallel = 3/3/3/3/3 ms, VThreads = 4/5/6/4/5 ms. SnakeGame preserved
  headless behavior; evaluator exited 0. `NativeObfuscationIntegrationTest`
  passed (`BUILD SUCCESSFUL in 40s`). P4 remains `[-]` rather than `[x]`
  because the full performance gate still misses TEST Calc <= 20 ms and GC
  strict remains blocked: ZGC/Shenandoah abort at existing mask publication
  diagnostics (`ZGC oop load masks unavailable addr=0x0 good=0x0`), and
  Parallel/Serial obfusjack direct runs did not provide a clean strict-gate
  completion under the 240s direct-run cap.
- User-updated acceptance recorded 2026-05-20: Calc must match or beat the
  original JVM jar in the same run environment; obfusjack Seq must be <= 10 ms;
  every other parsed benchmark/test timing must match original or stay within
  1.5x slowdown. Older absolute threshold rows are superseded for ongoing
  performance work.

### [-] NPT-3a: Runtime P10 generic NJX call-parameter packing

- Scope: continue runtime performance work by optimizing only the generic
  native-to-Java call-stub machinery used to jump to original JVM methods.
  This row must not add owner/name/descriptor-specific native implementations.
- Required evidence: audit of current translator/codegen special cases, rollback
  of the uncommitted `java/lang/Math` → C libm lowering, source/generated-C
  proof that Math calls still bind and jump through NJX to the original JVM
  methods, and generated-C proof that full `memset(call_params, ...)` is gone
  while every used call parameter slot and required two-slot padding word is
  explicitly initialized.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew` after permission, then the owning P10 runtime gate
  before marking complete.
- Completion criteria: no `translateMathIntrinsicInvoke`, libm call, or `-lm`
  link flag remains; generated C contains bound `java/lang/Math` method entries
  rather than native math calls; NJX stack packing preserves the previous zeroed
  high/padding words without full-array memset; no JNI/JVMTI/original-bytecode
  fallback or target-method control-flow replacement is introduced.
- Evidence update 2026-05-20: rolled back the forbidden `java/lang/Math` native
  intrinsic route and removed the unused method-specific native helper bodies
  for `String.length`, `Object.getClass`, `String.concat`, and
  `Atomic*.addAndGet`. Source audit over `neko-native/src/main/java` found no
  remaining `translateMathIntrinsicInvoke`, libm `sqrt`/`pow`, `-lm`, or those
  fast helper definitions; generated audit over
  `build/neko-native-work/run-5500725993204` found no emitted forbidden helper
  symbols or libm calls. The remaining `translateIntrinsicMethodInvoke` cases
  are caller/stack compatibility bridges, not performance replacements.
- Evidence update 2026-05-20: retained only generic native-to-Java machinery
  changes: explicit NJX `call_params` slot initialization, shape-specialized
  call-stub parameter packing, receiver inline-cache keys derived from the
  already-resolved `Klass*`, and fused nested-array helper cleanup that removes
  a duplicate outer-bounds check after the same check has already succeeded.
  These changes preserve the original JVM/JDK target call; no named target
  method body is implemented in native code.
- Validation update 2026-05-20: focused generator/audit tests passed with
  `./gradlew :neko-test:test --rerun-tasks --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.OpcodeTranslatorUnitTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL`, artifact
  `artifact://112`). Full native integration passed with
  `./gradlew :neko-test:test --rerun-tasks --tests
  dev.nekoobfuscator.test.NativeObfuscationIntegrationTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL`, artifact
  `artifact://114`).
- Open gate evidence 2026-05-20: parity is still not accepted. Fresh repeated
  direct runs written to
  `build/native-run-tmp/parity-current/summary-after-generic.json` recorded
  TEST original/native Calc medians `12 ms` / `135 ms`; obfusjack
  original/native medians Seq `2 ms` / `19 ms`, Platform `25 ms` / `50 ms`,
  Virtual `15 ms` / `47 ms`. This row remains `[-]`; no checkpoint commit is
  valid until the parity gate is met or the retained subset is split into an
  accepted, freshly validated prerequisite row.
  - Evidence update 2026-05-20: read-only forbidden-substitution audit
    `agent://8-ForbiddenNativeSubstitutionAudit` found no remaining
    owner/name/descriptor-specific performance replacements for Math/libm,
    String.length/concat, Object.getClass, Atomic addAndGet, or similar
    targets in the requested native translator/codegen files. Remaining named
    cases are compatibility bridges that call cached original JVM Method*
    targets through NJX.

### [rejected] NPT-3b: Runtime P10 compiled-entry NJX bridge prototype

- Scope: replace the shape-specialized NJX call-stub bridge with the existing
  generic compiled-entry trampoline path only if the trampoline calls each
  supplied Method* `_from_compiled_entry` target unchanged. This must not
  implement any owner/name/descriptor-specific Java/JDK method in native code
  and must not alter translated-target selection.
- Required evidence: source and generated-C proof that direct, virtual, helper,
  reflection-adapter, String concat/valueOf, and implicit-exception NJX sites
  pass compiled-entry pointers when the bridge expects compiled entries; proof
  that each generated shape is keyed only by ABI shape; runtime logs or
  generated C proving original JVM/JDK methods are still invoked by Method*
  entry pointers instead of native replacements.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew`, `NativeObfuscationIntegrationTest`, direct TEST and
  obfusjack parity runs under `-Djava.io.tmpdir=build/native-run-tmp`, and
  generated-C forbidden-marker inspection. If any runtime, verifier, GC-root,
  stack-walk, or parity gate fails, revert this row before trying another
  optimization.
- Completion criteria: fresh artifacts report `translated>0 rejected=0`; no
  fatal JVM/runtime errors, JNI/JVMTI wrappers, fallback markers, or native
  method-body substitutions are present; TEST Calc and obfusjack parsed
  timings move toward same-run original JVM parity without replacing original
  target calls.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://151`), but fresh `NativeObfuscationIntegrationTest` failed with
  repeated SIGSEGVs in HotSpot stack walking / `Throwable.fillInStackTrace`
  while frames crossed `~BufferBlob::neko_njx_trampoline` (`artifact://153`,
  `artifact://155`, `hs_err_pid1442764.log`, `hs_err_pid1500630.log`).
  Adjusting the synthetic BufferBlob frame size from `8 + stackArgs + pad` to
  `9 + stackArgs + pad` did not remove the crash. The compiled-entry bridge
  prototype was reverted before any checkpoint commit because it violates the
  row's stack-walk/runtime acceptance criteria.

### [x] NPT-3c: Runtime P10 call_stub guard register-save trim

- Scope: optimize only the generic HotSpot call_stub bridge used by
  shape-specialized NJX dispatchers. The bridge must keep calling the supplied
  original Method* entry and must not introduce any owner/name/descriptor
  native implementation.
- Required evidence: the current guarded bridge saves callee-saved registers
  around a C-ABI call to HotSpot `call_stub`; the proposed change may remove
  only wrapper-local redundant saves while preserving SysV stack alignment and
  the two stack-passed call_stub arguments. Generated C must still call
  `neko_call_stub_guarded` / `g_neko_call_stub_entry` with the same Method* and
  entry pointers.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct TEST and obfusjack parity runs, and
  generated-C forbidden-marker inspection under
  `-Djava.io.tmpdir=build/native-run-tmp`.
- Completion criteria: fresh artifacts run without SIGSEGV/SIGABRT/verifier
  errors, no forbidden JNI/JVMTI/fallback or method-body substitution appears,
  and same-run direct timings improve or do not regress versus the committed
  call_stub baseline.
- Completion evidence 2026-05-20: removed the wrapper-local callee-saved
  register pushes/pops and alignment-only `subq $8` from
  `neko_call_stub_guarded`; the bridge still passes the same call_stub, Method*,
  entry pointer, parameter stack, result buffer, and JavaThread arguments to
  HotSpot. Focused generator/audit tests passed (`artifact://167`) and
  `NativeObfuscationIntegrationTest` passed (`artifact://169`). Fresh direct
  parity runs under `build/native-run-tmp/parity-p10c/` completed with no
  fatal markers: TEST original/native Calc medians `11 ms` / `134 ms`;
  obfusjack original/native medians Seq `2 ms` / `18 ms`, Platform
  `26 ms` / `50 ms`, Virtual `15 ms` / `44 ms`. Generated C inspection in
  `build/neko-native-work/run-7465333913274/neko_native_support.c` preserved
  call_stub dispatch and did not reintroduce Math/libm or named JDK native
  substitutions. The row is an accepted generic call_stub bridge micro-
  optimization, but P10 remains open because same-run original JVM parity is
  still not achieved.

### [rejected] NPT-3d: Runtime P10 bounded JavaCallWrapper zeroing

- Scope: reduce per-NJX-call stack zeroing in the call_stub bridge by clearing
  only the generated JavaCallWrapper prefix that is actually populated and
  whose frame-anchor extent is already validated before every call. This must
  keep the same HotSpot call_stub target, Method*, entry pointer, arguments,
  result handling, handle scope, and thread-state transitions.
- Required evidence: source/generated-C proof that the cleared byte count covers
  the thread, JNIHandleBlock, Method*, receiver, and JavaFrameAnchor fp/pc/sp
  fields before call_stub observes the wrapper; runtime validation must reject
  any stack-walk, exception, GC-root, or verifier failure.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct TEST and obfusjack parity runs, and
  generated-C forbidden-marker inspection under
  `-Djava.io.tmpdir=build/native-run-tmp`.
- Completion criteria: no SIGSEGV/SIGABRT/verifier/fatal markers; no forbidden
  JNI/JVMTI/fallback or named method-body substitutions; same-run direct
  timings improve or do not regress relative to the NPT-3c checkpoint.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://179`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://181`), but fresh direct parity runs in
  `build/native-run-tmp/parity-p10d/` regressed against the NPT-3c checkpoint:
  TEST native Calc median `136 ms` vs `134 ms`; obfusjack native Seq
  `19 ms` vs `18 ms`; Virtual `46 ms` vs `44 ms` while Platform remained
  `50 ms`. The bounded-zeroing source change was reverted before any
  implementation checkpoint because the row's no-regression criterion was not
  met.

### [rejected] NPT-3e: Runtime P10 Java-thread-state hot branch

- Scope: optimize only the generic NJX call_stub thread-state precheck by making
  the already-required `_thread_in_java` path the predicted fallthrough. Native
  callers must still transition through `neko_transition_native_to_java`; any
  unsupported state must still hard abort.
- Required evidence: source/generated-C proof that the same call_stub Method*
  and entry pointers are used and that only branch shape changed.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3c.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://186`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://188`), but direct timing did not meet the no-regression gate.
  Partial five-run parity in `build/native-run-tmp/parity-p10e/` recorded TEST
  native Calc values `137/132/136/137/134 ms`; the obfusjack native series hit
  a 120s timeout during the third repeated run. A follow-up three-run obfusjack
  native check under `build/native-run-tmp/parity-p10e-extra/` completed but
  showed Platform `53/53/52 ms` versus the NPT-3c median `50 ms`. The branch
  hint source change was reverted before any implementation checkpoint.

### [rejected] NPT-3f: Runtime P10 direct call_stub guard arguments

- Scope: remove the per-NJX-call temporary `neko_call_stub_args_t` aggregate
  from the generic call_stub bridge and pass the same call_stub arguments
  directly through the guard function's C ABI. The guard must still invoke the
  supplied HotSpot `call_stub` with the same JavaCallWrapper mirror, Method*,
  entry pointer, parameter stack, result buffer, result type, and JavaThread.
- Required evidence: generated-C proof that the guard call is still shape-
  generic and not owner/name/descriptor-specific; source proof that the x86-64
  SysV stack-passed `size` and `thread` arguments keep correct alignment.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3c.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://199`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://201`), but direct parity in
  `build/native-run-tmp/parity-p10f/` regressed against NPT-3c on TEST Calc
  and obfusjack Platform/Virtual: TEST native Calc median `136 ms` vs `134 ms`;
  obfusjack native Seq improved `17 ms` vs `18 ms`, but Platform was
  `51 ms` vs `50 ms` and Virtual was `45 ms` vs `44 ms`. The direct-argument
  source/test changes were reverted before any implementation checkpoint.

### [rejected] NPT-3g: Runtime P11 conditional JNIHandleBlock `_last` publication

- Scope: optimize the generic local-handle push paths by writing
  `JNIHandleBlock::_last` only when a previously empty active block receives
  its first pushed handle. Overflow block creation must still initialize its
  own `_last`, and restore must still restore the saved `_last` value.
- Required evidence: source/generated-C proof that every active block with
  `_top > 0` still has `_last == block` before HotSpot can allocate into it,
  while repeated pushes into the same non-empty block avoid redundant `_last`
  stores. This must apply generically to signature-dispatch and raw-oop return
  handle paths, not to a benchmark-specific method.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/GC-root/forbidden-marker regressions
  and same-run timings improve or do not regress relative to NPT-3c.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://206`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://208`), but direct parity in
  `build/native-run-tmp/parity-p10g/` did not meet the no-regression gate.
  TEST native Calc values were `132/133/137/138/138 ms` (median `137 ms`,
  worse than NPT-3c `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after four completed runs. The conditional `_last` source
  changes were reverted before any implementation checkpoint.

### [x] NPT-3h: Runtime P10 return-kind-specific NJX result loads

- Scope: optimize only shape-specialized NJX call_stub result unpacking by
  loading `__call_result` according to the known return kind: integer/object
  returns read the integer lane, float/double returns read the FP lane, and
  void returns read neither. The HotSpot call_stub target, Method*, entry
  pointer, argument stack, exception behavior, handle scope, and target method
  selection must remain unchanged.
- Required evidence: generated-C proof that each shape still uses the same
  call_stub bridge and only omits dead result-lane loads for return kinds that
  cannot observe them.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3c.
- Completion evidence 2026-05-20: shape-specialized NJX dispatchers now load
  only the result lane required by their return kind: void shapes skip
  `__call_result` reads, integer/object shapes load `out_rax`, and FP shapes
  copy only `out_xmm0`. Focused generator/audit tests passed
  (`artifact://214`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://216`). Fresh direct parity in
  `build/native-run-tmp/parity-p10h/` completed with no fatal markers: TEST
  original/native Calc medians `12 ms` / `134 ms`; obfusjack original/native
  medians Seq `2 ms` / `17 ms`, Platform `26 ms` / `50 ms`, Virtual
  `14 ms` / `44 ms`. Generated-C inspection of
  `build/neko-native-work/run-9173021508276/neko_native_support.c` shows
  call_stub dispatch is still used and the old combined
  `out_rax = ...; memcpy(&out_xmm0, ...)` unpack is gone from shape bodies.
  The row is accepted as a generic bridge micro-optimization; full P10 parity
  remains open.

### [rejected] NPT-3i: Runtime P10 single-slot NJX result buffer

- Scope: reduce the shape-specialized NJX call_stub result buffer from two
  machine words to the single `intptr_t` word needed to hold any JVM primitive
  or object return lane on the supported 64-bit targets. The call_stub result
  pointer and BasicType must remain unchanged, and void/integer/object/FP
  returns must preserve their existing semantics.
- Required evidence: generated-C proof that the buffer remains at least
  `sizeof(intptr_t)` and all result unpacking reads only that word after
  NPT-3h. This must be shape-generic and not target a method owner/name.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://221`), but fresh `NativeObfuscationIntegrationTest` failed in
  `nativeObfuscation_randomRuntimeStableTenRuns` because
  `obfusjack-native.jar` timed out after 45s (`artifact://223`). The
  single-slot result-buffer source change was reverted before any
  implementation checkpoint because the runtime stability gate failed.

### [rejected] NPT-3j: Runtime P10 constant-index NJX call parameter packing

- Scope: optimize only shape-specialized NJX call parameter packing by emitting
  constant `call_params` slot indexes for each known ABI shape instead of the
  mutable `__njx_pos` cursor. The exact slot layout, two-slot long/double
  padding word, object handle unwrapping, Method*, entry pointer, and call_stub
  behavior must remain unchanged.
- Required evidence: source/generated-C proof that generated shapes use fixed
  slot indexes and still preserve the existing zero-padding semantics for
  one-slot primitives and two-slot long/double arguments.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://227`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://229`), but direct parity in
  `build/native-run-tmp/parity-p10j/` did not meet the no-regression gate.
  TEST native Calc values were `140/140/133/136/134 ms` (median `136 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after three completed runs. The constant-index packing source
  change was reverted before any implementation checkpoint.

### [rejected] NPT-3k: Runtime P10 return-kind-specific NJX result locals

- Scope: continue NPT-3h by emitting only the result local variables and debug
  log arguments required by each shape return kind. Integer/object shapes need
  only `out_rax`, FP shapes need only `out_xmm0`, and void shapes need neither.
  The call_stub result buffer, BasicType, Method*, entry pointer, arguments,
  handle scope, and exception behavior must remain unchanged.
- Required evidence: generated-C proof that result locals/logging are
  return-kind-specific and no target method body or control-flow replacement is
  introduced.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://235`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://237`), but direct parity in
  `build/native-run-tmp/parity-p10k/` did not meet the no-regression gate.
  TEST native Calc values were `138/146/139/133/138 ms` (median `138 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after two completed runs. The return-kind-local source change
  was reverted before any implementation checkpoint.

### [rejected] NPT-3l: Runtime P10 mark call_stub guard hot

- Scope: mark the generic x86-64 `neko_call_stub_guarded` wrapper as a hot
  function so the native compiler places/optimizes the mandatory bridge as hot
  code. The wrapper remains `naked`, `noinline`, and calls the same supplied
  HotSpot call_stub with the same arguments.
- Required evidence: source/generated-C proof that only the function attribute
  changed and no owner/name/descriptor-specific method body or target selection
  behavior changed.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://243`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://245`), but direct parity in
  `build/native-run-tmp/parity-p10l/` regressed against NPT-3h: TEST native
  Calc median `140 ms` vs `134 ms`, obfusjack native Platform `51 ms` vs
  `50 ms`, and Virtual `47 ms` vs `44 ms` while Seq remained `17 ms`. The
  `hot` attribute source change was reverted before any implementation
  checkpoint.

### [rejected] NPT-3m: Runtime P6 primitive-array cold failure outlining

- Scope: outline only the primitive array helper failure `fprintf`/`abort`
  blocks into cold noinline helpers. Fast-path null/bounds/metadata checks,
  array load/store semantics, exception/fail-closed behavior, and generated
  target method selection must remain unchanged.
- Required evidence: generated-C proof that `neko_fast_*aload` and
  `neko_fast_*astore` hot bodies call cold failure helpers instead of carrying
  inline `fprintf` blocks.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://250`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://252`), but direct parity in
  `build/native-run-tmp/parity-p10m/` did not meet the no-regression gate.
  TEST native Calc values were `136/141/135/143/139 ms` (median `139 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after two completed runs. The primitive-array cold failure
  helper source change was reverted before any implementation checkpoint.

### [rejected] NPT-3n: Runtime P10 remove hot NJX dispatch stats counter

- Scope: remove only the per-NJX-call diagnostic dispatch counter from the hot
  shape-specialized call_stub path. Direct invocation remains mandatory, debug
  logging still works through `NEKO_DIRECT_LOG`, resolve-failure counting
  remains available, and target Method*/entry selection is unchanged.
- Required evidence: source/generated-C proof that shape dispatchers no longer
  call `neko_njx_note_dispatch()` while call_stub, Method*, entry pointer,
  parameter stack, result unpacking, exception checks, and handle scopes remain
  unchanged.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://260`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://262`), but direct parity in
  `build/native-run-tmp/parity-p10n/` did not meet the no-regression gate.
  TEST native Calc values were `142/139/141/141/135 ms` (median `141 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after one completed run. The dispatch-stats source change was
  reverted before any implementation checkpoint.

### [rejected] NPT-3o: Runtime P10 localize NJX debug log gate

- Scope: keep NJX debug logging behavior but avoid repeated hot-path
  `neko_njx_debug()` loads in each shape-specialized call_stub dispatcher by
  caching the debug gate in a local and using it for the entry/exit debug log
  branches. Direct invocation, dispatch stats, resolve-failure stats, Method*,
  entry pointer, arguments, handle scopes, and exception behavior must remain
  unchanged.
- Required evidence: generated-C proof that shape dispatchers still emit the
  same debug log strings when enabled and still call the same HotSpot call_stub
  target; no owner/name/descriptor-specific method body replacement is allowed.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://268`) and `NativeObfuscationIntegrationTest` passed
  (`artifact://270`), but direct parity in
  `build/native-run-tmp/parity-p10o/` did not meet the no-regression gate.
  TEST native Calc values were `138/138/141/142/136 ms` (median `138 ms`,
  worse than NPT-3h `134 ms`), and the obfusjack native repeated run timed out
  once at 180s after four completed runs. The local debug-gate source change
  was reverted before any implementation checkpoint.

### [x] NPT-3p: Runtime P10 remove unused generic NJX dispatcher body

- Scope: delete only the generated static generic NJX dispatcher and its
  generic return-BasicType helper after source search proves registered call
  sites emit shape-specialized dispatchers. This removes dead generated C and
  must not change any Method*/entry target, call_stub bridge, argument packing,
  handle scope, exception behavior, or debug behavior on reachable paths.
- Required evidence: source search showing `neko_njx_dispatch_generic` is not
  referenced by any emitted callsite; generated C still contains the
  shape-specialized `neko_njx_*` dispatchers and no generic dispatcher body.
- Validation command or runtime target: focused generator/audit tests and
  `NativeObfuscationIntegrationTest`. Direct parity is recorded if runtime
  paths change; this row is intended to remove unreachable code only.
- Completion criteria: fresh integration passes, generated C has no
  `neko_njx_dispatch_generic`, no forbidden JNI/JVMTI/fallback or named
  method-body substitution appears, and worktree is checkpointed.
- Completion evidence 2026-05-20: source search found no emitter callsite for
  `neko_njx_dispatch_generic`; all reachable callsites use shape-specialized
  dispatchers registered through `renderShapeCallStub`. Removed the unused
  generated generic dispatcher body and its private `neko_njx_result_basic_type`
  helper. Focused generator/audit tests passed (`artifact://280`) and fresh
  `NativeObfuscationIntegrationTest` passed (`artifact://282`). Generated C in
  `build/neko-native-work/run-12646002423282/` contains shape-specialized
  `neko_njx_*` dispatchers and no `neko_njx_dispatch_generic` or
  `neko_njx_result_basic_type` symbols; target Method*/entry call paths remain
  shape-specialized call_stub dispatches with no named JVM/JDK native method
  replacement.

### [x] NPT-3q: Runtime P10 remove obsolete compiled-entry trampoline generator

- Scope: delete the unused Java emitter code and generated declarations for the
  rejected compiled-entry NJX trampoline path. The retained generated runtime
  must continue to use HotSpot call_stub shape dispatchers for original JVM
  Method*/entry targets; no call target, argument packing, handle scope,
  exception behavior, or thread-state transition may change.
- Required evidence: source search proves `renderBodies()` emits
  `renderShapeCallStub` and no code references the compiled-entry generator,
  wrapper globals, or trampoline begin/end symbols after cleanup.
- Validation command or runtime target: focused generator/audit tests and
  `NativeObfuscationIntegrationTest`.
- Completion criteria: fresh integration passes, generated C has no unused
  `g_njx_wrapper_*` or `neko_njx_tramp_*` declarations, and shape-specialized
  call_stub dispatchers remain present.
- Completion evidence 2026-05-20: removed the unused compiled-entry trampoline
  emitter, per-shape `neko_njx_tramp_*` declarations, `g_njx_wrapper_*`
  globals, and unused runtime/frame-layout helper code from
  `NativeToJavaInvokeEmitter`. Focused generator/audit tests passed
  (`artifact://287`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://289`). Generated C under
  `build/neko-native-work/run-12921477179290/` contains no
  `g_njx_wrapper*`, `neko_njx_tramp_*`, or `neko_njx_runtime_t` symbols while
  retaining shape-specialized `neko_njx_*` call_stub dispatchers for the same
  Method*/entry targets.

### [rejected] NPT-3r: Runtime P12 remove unused virtual icache declared-mid argument

- Scope: remove the virtual/interface `neko_icache_dispatch` declared-method
  `jmethodID` parameter because the current generic dispatch path resolves the
  concrete target from receiver `Klass*` plus callsite name/descriptor metadata
  and never dereferences the declared id. This must not change receiver-null
  handling, exact-method resolution, Method*/entry target selection, PIC
  storage, or call_stub dispatch.
- Required evidence: source/generator proof that the parameter is used only as
  a null precondition and diagnostic value, and generated C proves callsites no
  longer bind/load a declared `jmethodID` solely for `neko_icache_dispatch`.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://296`), but fresh `NativeObfuscationIntegrationTest` failed
  (`artifact://298`) in `nativeObfuscation_randomRuntimeStableTenRuns` with
  an obfusjack native timeout after 45s. The timed-out run reached only the
  platform-thread section in
  `neko-test/build/test-native/native_obfusjack_stability_9.stdout.log`, so
  the declared-`jmethodID` removal was reverted before any implementation
  checkpoint.


### [ ] NPT-4: Compile-time post-P41 bottleneck selection

- Scope: choose the next native compile-time optimization only from the NPT-1
  fresh manifests and generated-source inspection.
- Required evidence: top compile-sum/max/wall-time rows for TEST/test21/
  SnakeGame/evaluator, generated source snippets for the dominant repeated
  generic code shape, rejected-shape comparison against P27/P29/P33/P34/P36/P37/
  P40, and a proof that the proposed change is source-shape or build-architecture
  generic rather than fixture-specific.
- Validation command or runtime target: read-only inspection of NPT-1 artifacts;
  no implementation in this row.
- Completion criteria: one concrete compile-time implementation row is added to
  `.plan/native-compile-parallelization-2026-05-17.md` with scope, required
  evidence, validation commands, and completion criteria before code changes.
- Dependency: NPT-1.

### [ ] NPT-5: Implement the first post-P41 compile-time optimization row

- Scope: implement only the concrete row selected by NPT-4, preserving Zig,
  `-O3`, current target flags, native coverage, no-JNI/no-JVMTI policy, and
  runtime semantics unless the selected row explicitly proves an equivalent
  behavior-preserving code-shape change.
- Required evidence: the selected row in
  `.plan/native-compile-parallelization-2026-05-17.md`, focused tests covering
  the generated source contract, fresh manifests, direct runtime output, and
  forbidden-marker inspection.
- Validation command or runtime target: focused generator/unit tests,
  generated-C hot-path audit, fresh native-only TEST/test21/SnakeGame/evaluator
  generation, direct runtime smoke, timing comparison against NPT-1, newest
  `hs_err` scan, and forbidden-marker scan.
- Completion criteria: at least one build-size/top-tail/wall-time metric improves
  without runtime regression, all fresh artifacts report `translated>0
  rejected=0`, and no rejected prior shape or fallback mechanism is reintroduced.
- Dependency: NPT-4.

### [ ] NPT-6: Sync final validation plans after the first accepted optimization

- Scope: update `.plan/native-full-validation.md`, `.plan/native-full-repair.md`,
  and the owning runtime/compile-time source plan with the fresh evidence from
  the first accepted implementation row.
- Required evidence: commands, exit codes, output paths, generated C/native
  inspection, runtime outputs, timing rows, and rejection criteria status.
- Validation command or runtime target: no new runtime command beyond the owning
  row; this is an audit-sync row.
- Completion criteria: no stale checkbox is marked complete; every updated
  checkbox has a matching fresh artifact and evidence path.
