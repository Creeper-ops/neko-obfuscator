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
