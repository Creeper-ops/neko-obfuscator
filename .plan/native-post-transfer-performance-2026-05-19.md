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

### [x] NPT-2: Stage 4 T4.0 eager exception-check env-offset publication

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
- Reopened evidence 2026-05-22: fresh TEST artifact
  `build/npt-3bw/TEST-native.jar` and obfusjack artifact
  `build/npt-3bw/obfusjack-native.jar` have the collapsed hot
  `neko_exception_check` body in generated C and no forbidden JNI grep matches,
  but an obfusjack `NEKO_PATCH_DEBUG=1` runtime printed repeated
  `eager env-offset publication via memory walk: off=960` lines in one JVM
  process. The exact failing invariant is process-wide publication: every
  generated shared library owns a private
  `g_neko_off_thread_jni_environment_for_check`, so each library repeats the
  cold memory walk even though the hot path remains resolver-free. The next
  implementation substep is a generic generated-native bootstrap cache: publish
  the validated JNIEnv-to-JavaThread offset into a process-wide cache after the
  first successful cold derivation, have later libraries validate that cached
  offset against the current `JNIEnv`, JNI functions table, pending-exception
  slot, vtable window, and thread-state invariant before accepting it, and keep
  the hot `neko_exception_check` path as a plain global load plus pointer
  arithmetic plus `_pending_exception` read. Validation remains the row's
  existing fresh `R-build`, repeated TEST/obfusjack runtime, `R-inspect`, and
  negative/fail-closed evidence.
- Completion evidence 2026-05-22: implemented a generic generated-native
  process cache for `JNIEnv* -> JavaThread*` distance publication. The first
  generated library validates and publishes `NEKO_NATIVE_JNI_ENV_OFFSET` after
  the cold memory walk; later generated libraries parse and revalidate that
  cache against the current `JNIEnv`, JNI functions table, vtable window,
  pending-exception slot, and thread-state invariant before accepting it.
  Invalid cache values are ignored after diagnostic proof and do not corrupt
  the hot path. The cache helpers are emitted as cold/noinline bootstrap-only
  code, and `neko_exception_check` remains a plain load of
  `g_neko_off_thread_jni_environment_for_check`, pointer arithmetic, and direct
  `_pending_exception` read with no resolver call. Focused `R-build` passed:
  `./gradlew --no-daemon :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Fresh artifacts:
  `build/npt-2-cache/TEST-native.jar` from
  `build/neko-native-work/run-31103118865777` (`translated=49 rejected=0`,
  lib `1043048` bytes) and `build/npt-2-cache/obfusjack-native.jar` from
  `build/neko-native-work/run-31110445445738`
  (`translated=93 rejected=0`, lib `1811816` bytes). `R-inspect` over those
  run dirs found `0` `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` generated-C
  matches and showed the cache helpers plus cold/noinline declarations.
  `NEKO_PATCH_DEBUG=1` obfusjack showed exactly one
  `derived JNIEnv->JavaThread distance via memory walk` and one
  `eager env-offset publication via memory walk`, followed by repeated
  `eager env-offset publication via process cache` lines; no later library
  repeated the memory walk. Invalid-cache negative runtime with
  `NEKO_NATIVE_JNI_ENV_OFFSET=8 NEKO_PATCH_DEBUG=1` exited cleanly, logged
  `ignored invalid process JNIEnv->JavaThread offset cache`, then performed one
  validated memory walk and completed obfusjack. Runtime gates on the final
  artifacts passed: TEST x5 retained `Tests r Finished` and Calc
  `71/70/71/68/69` ms (median `70`); original obfusjack x5 completed with
  Seq `2/2/2/3/2` ms; native obfusjack x10 completed with Platform
  `44/44/46/48/47/46/43/43/43/45` ms, Virtual
  `37/40/39/43/36/37/39/36/38/37` ms, Seq
  `17/17/17/20/17/17/17/17/17/17` ms, and Parallel/VThreads `1` ms. Same-
  session old-artifact comparison was similarly noisy and did not show a
  distinct regression: prior obfusjack samples included Platform
  `47/46/48/46`, Virtual `40/37/42/36`, and Seq `23/18/17/17` ms.
- Validation addendum 2026-05-22: closed the remaining T4.0 negative-proof gap
  by adding the generic diagnostic gate
  `NEKO_NATIVE_DIAG_FAIL_ENV_OFFSET_PUBLICATION` in `neko_method_layout_init`.
  The gate forces the same missing-publication hard-error path before any
  translated dispatch; it does not skip classes, enter JNI fallback, or preserve
  original bytecode. Focused generator validation passed with
  `CCodeGeneratorTest`; fresh `NativeObfuscationPerfTest` regenerated TEST at
  `build/neko-native-work/run-44896788247250` and obfusjack at
  `build/neko-native-work/run-44901712281896` and passed with TEST x5 Calc
  `3/2/3/3/3` ms and obfusjack x5 medians Platform `37`, Virtual `31`, Seq
  `10`, Parallel/VThreads `1`. Post-change `NEKO_PATCH_DEBUG=1` TEST and
  obfusjack runs each logged exactly one memory-walk derivation, then process
  cache reuse for later generated libraries, with no `memory-walk derivation
  failed` or `hot-path env-offset unpublished` hit. Generated-C inspection
  found resolver use only in the support helper definition and bootstrap
  layout-init call, while the hot `neko_exception_check` body remained a direct
  offset load plus pointer arithmetic and `_pending_exception` read. Native
  obfusjack x10 completed green with Seq
  `11/11/11/10/10/10/11/11/10/11` ms. Negative TEST-native and
  obfusjack-native runs with the diagnostic gate set aborted at startup with
  exit `134` and the log `eager env-offset publication forced missing by
  diagnostic gate; refusing to enter translated native path`.

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

### [x] NPT-4: Stage 4 T4.1 primitive Class mirror slots

- Scope: complete `native-gentle-flamingo-todo.md` T4.1 for primitive
  `Class` constants by routing both `neko_class_for_descriptor` and
  LDC primitive class slot binding through the generated descriptor → mirror
  table.
- Required evidence: current generated C proving `neko_class_for_descriptor`
  already uses `neko_primitive_mirror_for_char`, plus generated/source evidence
  that `neko_bind_primitive_class_slot` still uses `JVM_FindPrimitiveClass` for
  LDC primitive class descriptors.
- Validation command or runtime target: `R-build`, `R-test`,
  `R-obfusjack`, `R-native-test`, `R-inspect`, and `R-negative`; fresh
  generated C must show no `JVM_FindPrimitiveClass unavailable for LDC Class
  descriptor` path and no `FindClass` / `GetStaticFieldID` /
  `GetStaticObjectField` primitive-Class fallback.
- Completion criteria: primitive `Z/B/C/S/I/J/F/D/V` class slots bind through
  the mirror table populated at OnLoad, primitive-array kind mapping remains
  limited to legal array element primitive types, missing wrapper/TYPE/mirror
  metadata aborts, TEST and obfusjack complete with fresh artifacts, and no JNI
  or original-bytecode fallback is introduced.
- Negative-proof addendum recorded before editing: add a generic diagnostic gate
  that forces a selected primitive mirror-table wrapper lookup to take the same
  hard-abort path as missing wrapper metadata. The gate is table/tag driven only;
  it must not skip classes, preserve original bytecode, or add JNI fallback.
- Completion evidence 2026-05-22: `neko_bind_primitive_class_slot` now routes
  primitive class slots through `neko_primitive_mirror_for_char(env, desc[0])`.
  The mirror table covers `Z/B/C/S/I/J/F/D/V` with `java/lang/Void.TYPE`, while
  primitive-array kind mapping remains limited to the eight legal primitive
  array element types. `CCodeGeneratorTest` passed. Fresh full perf validation
  passed with
  `env GRADLE_USER_HOME=/mnt/d/Code/Security/NekoObfuscator/build/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeObfuscationPerfTest --rerun-tasks --no-parallel -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/native-run-tmp`;
  generated artifacts were TEST `build/neko-native-work/run-46729377030886` and
  obfusjack `build/neko-native-work/run-46734567148804`. Perf medians were TEST
  Calc `3` ms and obfusjack Platform `40` ms, Virtual `36` ms, Seq `11` ms,
  Parallel/VThreads `1` ms. Generated-C inspection found
  `localClass = neko_primitive_mirror_for_char(env, desc[0]);`, `case 'V'`,
  `java/lang/Void`, and `NEKO_NATIVE_DIAG_FAIL_PRIMITIVE_MIRROR_TAG`; grep found
  zero `JVM_FindPrimitiveClass unavailable for LDC Class descriptor` and zero
  primitive-Class fallback table-index hits for `6/144/145`. `R-negative` with
  `NEKO_NATIVE_DIAG_FAIL_PRIMITIVE_MIRROR_TAG=I` aborted both TEST-native and
  obfusjack-native with exit `134` and log `T4.1 missing wrapper-class mirror
  for java/lang/Integer (kind=4 tag=I)`, proving missing metadata fails closed.

### [x] NPT-4a: Stage 4 T4.2a class lookup wrapper call sites

- Scope: complete `native-gentle-flamingo-todo.md` T4.2a by proving generated
  helper and manifest class-lookup call sites no longer call `neko_find_class`
  and route through `neko_resolve_class_with_env` /
  `neko_resolve_class_mirror_with_env` instead.
- Required evidence: source-emitter and generated-C grep for live
  `neko_find_class(...)` callsites, plus generated-C evidence that enumerated
  class-lookup paths use the resolver path.
- Validation command or runtime target: reuse the fresh T4.1 `R-build`,
  `R-test`, `R-obfusjack`, `R-native-test`, and `R-inspect` artifacts because no
  runtime source changed after T4.1; missing class resolution inherits T2.2
  `R-negative`.
- Completion criteria: live-callsite grep reports zero `neko_find_class(...)`
  callsites outside comments, generated C shows resolver calls in the enumerated
  helper/manifest paths, and no wrapper deletion is attempted before T4.11.
- Completion evidence 2026-05-22: no runtime code change was required. Fresh
  T4.1 artifacts TEST `build/neko-native-work/run-46729377030886` and obfusjack
  `build/neko-native-work/run-46734567148804` were produced by the passing
  `NativeObfuscationPerfTest` gate. Live-callsite grep
  `rg -n "neko_find_class[[:space:]]*\\(" ... | rg -v "^[^:]+:[0-9]+:[[:space:]]*(/\\*|\\*|//)"`
  returned zero for both generated artifacts and source emitters; raw hits were
  comments only. Generated C shows the class-lookup paths using
  `neko_resolve_class_with_env` / `neko_resolve_class_mirror_with_env`, and the
  wrapper remains undeleted for the T4.11 sweep gate.
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

### [rejected] NPT-3s: Runtime P12 outline virtual icache miss resolver

- Scope: split only the cold virtual/interface `neko_icache_dispatch` miss
  resolution block into a `cold,noinline` helper. The hot dispatch function
  must keep receiver null/meta checks, receiver `Klass*` key computation, PIC
  lookup, direct-C hits, direct-NJX hits, and the same Method*/entry/call_stub
  target path. No owner/name/descriptor-specific native implementation may be
  introduced.
- Required evidence: generated-C proof that `neko_icache_dispatch` contains
  the PIC hit path and calls `neko_icache_dispatch_miss` only after a miss,
  while the miss helper still performs the existing exact receiver method
  resolution and call_stub dispatch.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://307`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://309`), but direct parity in
  `build/native-run-tmp/parity-p10s/` failed the no-regression gate. TEST
  native Calc was `137/134/138/136/139 ms` (median `137 ms`, worse than
  NPT-3h `134 ms`), and obfusjack native was Seq `18/18/19/18/18 ms`
  (median `18 ms`, worse than `17 ms`) and Platform `53/55/55/42/52 ms`
  (median `53 ms`, worse than `50 ms`). The cold-miss outline source change
  was reverted before any implementation checkpoint.

### [rejected] NPT-3t: Runtime P11 direct-oop handle fast-slot prediction

- Scope: add generic branch prediction only to the `neko_direct_oop_to_handle`
  active JNIHandleBlock slot availability check. The helper must still push
  the same raw oop into the same active JNIHandleBlock slot, update `_top` and
  `_last` exactly as before, allocate an overflow block only on the same
  overflow condition, and hard-abort on the same unavailable paths.
- Required evidence: source/generated-C proof that only the generic
  `top < g_neko_jnih_block_capacity` predicate is marked as the expected fast
  path and no owner/name/descriptor-specific method replacement or handle
  semantics change is introduced.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://315`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://317`), but direct parity in
  `build/native-run-tmp/parity-p10t/` did not complete: the fifth obfusjack
  native run timed out after 180s. Completed runs did not justify retaining
  the change: TEST native Calc median was `134 ms`, but obfusjack native Seq
  completed values were `17/18/18/17 ms` and the run timed out before the
  required five-run median. The branch-prediction source change was reverted
  before any implementation checkpoint.

### [rejected] NPT-3u: Runtime P10 no-handle NJX for static primitive shapes

- Scope: extend the existing descriptor-proven no-handle-window policy to
  native-to-Java call_stub dispatchers only for static shapes with primitive
  arguments and primitive/void returns. The dispatcher must still call the
  original Method*/entry through HotSpot call_stub, preserve JavaCallWrapper
  anchor fields, thread-state handling, exception handling, and all reference
  shapes' handle save/install/restore behavior.
- Required evidence: generated-C proof that only `S:*` shapes with no `L`
  argument and no `L` return omit `neko_handle_save`,
  `neko_njx_install_java_handles`, `neko_njx_restore_java_handles`, and
  `neko_handle_restore`; virtual, object-arg, and object-return NJX shapes must
  retain the handle frame.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://325`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://327`), but direct parity in
  `build/native-run-tmp/parity-p10u/` did not complete: the fifth obfusjack
  native run timed out after 180s. Completed TEST native Calc median was
  `134 ms`, but obfusjack native completed values were Seq `17/18/18/17 ms`,
  Platform `52/51/51/51 ms`, and Virtual `44/42/47/45 ms`; the incomplete
  obfusjack run fails the required no-regression gate. The no-handle NJX
  source change was reverted before any implementation checkpoint.

### [rejected] NPT-3v: Runtime P10 skip empty JavaFrameAnchor copy

- Scope: optimize only generic NJX call_stub JavaFrameAnchor bookkeeping.
  When all saved JavaThread anchor slots are already null (`saved_sp`,
  `saved_pc`, and `saved_fp`), skip copying those nulls into the already
  zeroed JavaCallWrapper anchor and skip clearing the already-empty thread
  anchor. Keep the existing full JavaCallWrapper `memset`, Method*/entry
  target, call_stub invocation, parameters, result buffer, handle frame,
  thread-state transitions, exception handling, and final anchor restore.
- Required evidence: OpenJDK 21 JavaCallWrapper/JavaFrameAnchor source shows
  empty anchors are represented by null anchor slots and `clear()` writes nulls;
  generated C proves only the pre-call anchor copy/clear block is conditional
  on null slots, with no owner/name/descriptor-specific native replacement.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://338`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://340`), but direct parity in
  `build/native-run-tmp/parity-p10v/` failed the no-regression gate. TEST
  native Calc was `136/137/136/137/140 ms` (median `137 ms`, worse than
  NPT-3h `134 ms`), and obfusjack native Virtual was `47/46/48/46/40 ms`
  (median `46 ms`, worse than `44 ms`) while Seq stayed median `17 ms` and
  Platform stayed median `50 ms`. The anchor fast-path source change was
  reverted before any implementation checkpoint.

### [rejected] NPT-3w: Runtime P10 shrink NJX stack handle block buffer

- Scope: reduce only the fixed stack reserve used for NJX call_stub's
  temporary JNIHandleBlock from 512 bytes to 384 bytes. Runtime still checks
  `stack_handles_size >= sizeof_JNIHandleBlock` before using the stack block
  and falls back to the existing heap block path when a JVM layout is larger.
  Current validation logs show the JDK 21 `JNIHandleBlock` layout is 296 bytes,
  so 384 preserves the stack path for the measured runtime while reducing each
  NJX dispatcher frame footprint. Method*/entry target selection, call_stub
  invocation, JavaCallWrapper, thread state, exception handling, and handle
  restore semantics must remain unchanged.
- Required evidence: source/generated-C proof that only
  `__njx_handle_buf` size changed, layout logs show `blk_size=296`, and no
  owner/name/descriptor-specific native replacement is introduced.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://346`) and fresh `NativeObfuscationIntegrationTest` passed
  (`artifact://348`), but direct parity in
  `build/native-run-tmp/parity-p10w/` failed the no-regression gate. TEST
  native Calc stayed median `134 ms`, and obfusjack native Seq stayed median
  `17 ms`, but Platform regressed to median `52 ms` vs NPT-3h `50 ms`, and
  Virtual regressed to median `46 ms` vs `44 ms`. The 384-byte NJX stack
  handle buffer source change was reverted before any implementation
  checkpoint.

### [rejected] NPT-3x: Runtime P10 use cached patch debug flag in handle resolver

- Scope: replace the remaining hot `getenv("NEKO_PATCH_DEBUG")` checks inside
  `neko_handle_oop` with the existing cached `NEKO_PATCH_DEBUG` macro. This
  must only change debug gating; direct-oop classification, ZGC bootstrap
  handling, local/global handle resolution, GC barriers, Method*/entry target
  selection, call_stub invocation, and exception behavior must remain
  unchanged.
- Required evidence: generated-C proof that `neko_handle_oop` no longer calls
  `getenv` on the hot handle path and still emits the same diagnostic blocks
  behind `NEKO_PATCH_DEBUG`, with no owner/name/descriptor-specific native
  replacement.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Rejection evidence 2026-05-20: focused generator/audit tests passed
  (`artifact://356`), but fresh `NativeObfuscationIntegrationTest` failed
  (`artifact://358`) with SIGSEGV in obfusjack runs; the debug runtime log
  wrote `hs_err_pid3307640.log` and crashed after `merged=579.0`. The handle
  debug-cache source change was reverted before any implementation checkpoint.

### [x] NPT-3y: Runtime P10 inline cached interpreted-entry lookup

- Scope: optimize only the generated `neko_bound_method_i_entry_ref` path used
  by static/direct and helper NJX callsites before entering HotSpot call_stub.
  After bind-time has populated the interpreted-entry slot, generated code
  should read the cached slot directly and call `neko_bound_method_i_entry`
  only on the existing null/missing-entry path. This preserves the same
  original JVM Method*/entry target and does not replace any method body.
- Required evidence: source/generated-C proof that callsites still pass the
  same `g_mptr_*` and `g_mientry_*` slots to call_stub dispatchers, and the
  helper remains the fail-closed path for null entry slots.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions and
  same-run timings improve or do not regress relative to NPT-3h.
- Completion evidence 2026-05-21: `neko_bound_method_i_entry_ref(ref)` now
  reads the cached interpreted-entry slot only when both cached `Method*` and
  interpreted-entry slots are populated; otherwise it calls the existing
  fail-closed `neko_bound_method_i_entry` helper. Focused generator/audit tests
  passed with `./gradlew :neko-test:test --rerun-tasks --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp` (`BUILD SUCCESSFUL in 7s`). Fresh
  `NativeObfuscationIntegrationTest` passed (`BUILD SUCCESSFUL in 41s`). Direct
  parity logs in `build/native-run-tmp/parity-p10y/` completed five runs each
  without fatal markers: TEST original/native Calc medians `10 ms` / `87 ms`;
  obfusjack original/native medians Seq `2 ms` / `17 ms`, Platform `26 ms` /
  `43 ms`, Virtual `12 ms` / `36 ms`. Generated C in
  `build/neko-native-work/run-3262720662482/` contains the cached-entry macro in
  `neko_native_support.c` and `neko_native_impl_prelude.h`; callsites still use
  `neko_bound_method_i_entry_ref(&g_method_entry_ref_...)`; the fallback helper
  remains referenced for null slots. Executable forbidden-marker inspection
  found no `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, JNI call wrappers, array JNI
  wrappers, or JVMTI markers; remaining `FindClass`/`NewStringUTF`/`NewObject`
  strings are comments or `JVM_FindClass*` symbol names. No new `hs_err` files
  were created during this validation; newest root `hs_err_pid*.log` still
  predates the run.


### [rejected] NPT-3z: Runtime P6 primitive-array cold diagnostic outlining

- Scope: optimize only generated primitive array fast helpers by moving the
  unreachable-success-path diagnostic `fprintf`/`abort` blocks for primitive
  `xALOAD`/`xASTORE` direct-path failures into one cold, noinline, noreturn
  helper. The hot helper success checks, null/bounds semantics, primitive array
  layout reads, GC handling, and hard-abort behavior must remain unchanged.
- Required evidence: source/generated-C proof that primitive array hot helper
  bodies call the cold helper on failure instead of embedding `fprintf`, and
  generated-C proof that no JNI/JVMTI/fallback/original-bytecode path is added.
- Validation command or runtime target: focused generator/audit tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: no runtime/fatal/forbidden-marker regressions; same-run
  timings improve or do not regress relative to NPT-3y; P6 remains open for
  other hot-helper diagnostic blocks not covered by this narrow row.
- Rejection evidence 2026-05-21: source changed only primitive array direct-path
  failure diagnostics into a shared cold, noinline, noreturn helper. Focused
  generator/audit tests passed and `NativeObfuscationIntegrationTest` passed,
  but direct parity in `build/native-run-tmp/parity-p10z/` regressed relative
  to NPT-3y: TEST native Calc median `90 ms` vs `87 ms`; obfusjack native Seq
  `18 ms` vs `17 ms`, Platform `45 ms` vs `43 ms`, and Virtual `42 ms` vs
  `36 ms`. The source change was reverted before checkpoint.


### [rejected] NPT-3aa: Runtime P7 remove CHECKCAST success-path exception check

- Scope: optimize only translated `CHECKCAST` success paths. `neko_fast_is_instance_of`
  is classified as `never_sets_pending_exception` on normal return and hard-abort
  on missing metadata; the cast-failure branch calls the existing implicit
  exception raiser and immediately jumps to `__neko_exception_exit`. Therefore
  the trailing `if (!neko_exception_check(env)) { PUSH_O(obj); }` after a
  successful cast can become `PUSH_O(obj)` without changing JVM exception
  ordering, handler selection, pending-exception visibility, or ABI behavior.
- Required evidence: source/generated-C proof that only `CHECKCAST` success
  path checks are removed; failure paths still raise `ClassCastException` and
  jump to exception exit; no NJX, Java-calling, reflection, MethodHandle,
  direct translated call, or try/catch boundary checks are removed.
- Validation command or runtime target: focused translator/generator tests,
  `NativeObfuscationIntegrationTest`, direct parity runs, and generated-C
  forbidden-marker inspection.
- Completion criteria: generated C shows `CHECKCAST` success pushes directly,
  runtime and parity runs have no fatal/forbidden-marker regressions, and same-run
  timings improve or do not regress relative to NPT-3y.
- Rejection evidence 2026-05-21: source changed only the `CHECKCAST` success
  continuation to push directly after `neko_fast_is_instance_of`; the cast
  failure branch still raised `ClassCastException` and jumped to
  `__neko_exception_exit`. Focused translator/generator tests passed and
  `NativeObfuscationIntegrationTest` passed, but direct parity in
  `build/native-run-tmp/parity-p10aa/summary-p10aa.json` failed the no-regression
  gate relative to NPT-3y: TEST native Calc median `86 ms` vs `87 ms` improved,
  but obfusjack native Platform regressed to `45 ms` vs `43 ms` and Virtual
  regressed to `42 ms` vs `36 ms`; Seq stayed `17 ms`. The source change was
  reverted before checkpoint.

### [x] NPT-4: Compile-time post-P41 bottleneck selection

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
- Completion evidence 2026-05-21: selected `.plan/native-compile-parallelization-2026-05-17.md`
  P42 from the fresh NPT-1 post-P41 baseline. The baseline summary shows TEST
  compile sum/max `25439ms/1216ms`, obfusjack `41138ms/2053ms`, SnakeGame
  `9816ms/770ms`, and evaluator `45750ms/1629ms`. Source inspection of the
  top generated impl files found exact `if (!neko_exception_check(env)) {`
  continuation guards still widespread after P41: TEST `77`, obfusjack `301`
  with `82` in `neko_native_impl_39.c`, SnakeGame `61`, and evaluator `239`.
  Larger top-file repeated shapes were rejected by prior rows and are not
  selected: P33 string literal descriptors, P40 current-owner class macro,
  P36 method-pointer macro, P29 static setter refs, P37 fieldID elision, P27
  nested primitive-store fusion, and P34 helper shard rebalancing. P42 records
  a source-shape-only macro compaction target that preserves every exception
  check and does not implement the rejected P7/NPT-3aa check-removal route.

### [rejected] NPT-5: Implement the first post-P41 compile-time optimization row

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
- Rejection evidence 2026-05-21: selected compile-time row P42 was implemented
  and fully validated but rejected. Focused Gradle tests passed, fresh
  native-only generation under `build/p42-native-validation/` reported
  TEST/obfusjack/SnakeGame/evaluator translated/rejected rows `49/0`, `93/0`,
  `18/0`, and `122/0`, direct runtime was clean with no fresh `hs_err`, and
  generated-C inspection found `NEKO_IF_NO_EXCEPTION(env)` at rewritten sites
  with old exact continuation guards at `0`. The row failed completion because
  compile metrics were mixed and obfusjack no-debug runtime repeats regressed
  versus NPT-3y. P42 source/test changes were reverted before checkpoint; a new
  compile-time selection row is required before another implementation attempt.

### [x] NPT-5a: Select the next post-P42 compile-time optimization row

- Scope: choose the next native compile-time optimization from fresh post-P41/
  post-P42 evidence without retrying the rejected P42 macro-only guard shape or
  any previously rejected body/runtime shape.
- Required evidence: generated prelude/header sizes, implementation include
  counts, per-source compile tails from NPT-1/P42 artifacts, read-only
  generated-source inspection identifying the repeated generic declaration
  surface, and explicit rejection-boundary comparison against P27/P29/P33/P34/
  P36/P37/P40/P42 and NPT-3aa.
- Validation command or runtime target: read-only artifact/source inspection; no
  Gradle or runtime command for the selection row.
- Completion criteria: the owning compile plan records one concrete P43 row with
  scope, required evidence, validation commands, completion criteria, and a
  dependency/order reason before implementation starts.
- Dependency reason: NPT-2 and runtime rows remain open, but this selected row
  changes only generated source packaging/declaration inclusion for compile-time
  work. It must not alter runtime helper behavior or translated statement
  bodies, and the implementation row remains blocked on fresh runtime smoke
  validation before acceptance.
- Selection evidence 2026-05-21: selected
  `.plan/native-compile-parallelization-2026-05-17.md` P43. Read-only
  inspection found the canonical generated implementation prelude is included by
  almost every implementation file and is large in the NPT-1 artifacts: TEST
  about `353094` bytes/`6768` lines, obfusjack `410699` bytes/`7970` lines,
  SnakeGame `293653` bytes/`5569` lines, and evaluator `388879` bytes/`7251`
  lines. Obfusjack's hot `neko_native_impl_39.c` includes the full prelude but
  uses only subsets of numbered method-entry refs, method-id refs, field refs,
  class refs, inline-cache site macros, and method-pointer globals. P43 targets
  per-implementation sliced contract headers and explicitly does not change
  translated bodies, exception checks, strings, current-owner binding,
  method-pointer operands, static setters, field binding semantics, nested
  stores, or helper shard size.

### [x] NPT-5b: Implement P43 sliced implementation contract headers

- Scope: implement only P43's source-packaging change: generate per-impl sliced
  prelude headers for translated implementation sources while retaining the
  canonical full `neko_native_impl_prelude.h` for support/late-support shards.
- Required evidence: focused source-set tests, generated manifest/header-size
  data, fresh native-only TEST/obfusjack/SnakeGame/evaluator generation,
  generated-C inspection proving implementation body text is unchanged except
  include filenames and sufficient referenced declarations are present, direct
  runtime output, forbidden-marker scan, and newest `hs_err` scan.
- Validation command or runtime target: after explicit permission for
  repository `./gradlew`, run focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, then fresh native-only generation with
  `.plan/native-only-full.yml`, direct TEST/obfusjack/evaluator/SnakeGame smoke
  with `-Djava.io.tmpdir=build/native-run-tmp`, manifest/header comparison, and
  forbidden-marker inspection.
- Completion criteria: refreshed artifacts report `translated>0 rejected=0`;
  compile/link succeeds without fallback; per-impl header parse volume and at
  least one build tail/compile-sum/wall-time metric improves or remains within
  noise with no runtime regression; support shards still use the canonical full
  contract; no runtime semantics, compiler flags, native coverage, JNI/JVMTI
  fallback, skip-on-error behavior, original-bytecode fallback, or stale proof
  is introduced.
- Dependency: NPT-5a and P43.
- Completion evidence 2026-05-21: P43 is implemented and validated. The code
  now emits `neko_native_impl_N_prelude.h` for implementation sources and keeps
  `neko_native_impl_prelude.h` for support shards. A dependency-closure fix was
  added after focused audit exposed a generic sliced-macro dependency failure:
  retained `neko_concat_accumulate_string` needed `g_off_*` externs even though
  the implementation body referenced only the macro. Focused Gradle validation
  passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest -Djava.io.tmpdir=build/native-run-tmp`.
  Fresh native-only generation under `build/p43-native-validation/` produced
  TEST/obfusjack/SnakeGame/evaluator translated/rejected rows `49/0`, `93/0`,
  `18/0`, and `122/0`. Fresh run dirs
  `build/neko-native-work/run-8052435246015`,
  `run-8067151202699`, `run-8077119367912`, and
  `run-8091067486053` recorded generated C counts `67`, `113`, `34`, and
  `143`, and implementation header counts/sliced counts `50/49`, `94/93`,
  `19/18`, and `123/122`. Representative sliced headers are materially smaller
  than the full contract: TEST impl0 `299509` vs `356323` bytes, obfusjack
  impl39 `333519` vs `416657`, SnakeGame impl0 `275269` vs `295686`, evaluator
  impl104 `298320` vs `393898`. Direct runtime smoke with
  `-XX:+PerfDisableSharedMem -Djava.io.tmpdir=build/native-run-tmp` passed for
  TEST, obfusjack, and evaluator; SnakeGame preserved the expected headless GUI
  exception. Forbidden-marker inspection found no executable JNI/JVMTI/fallback
  markers in the fresh generated C/header/property set, and no fresh
  `hs_err_pid*.log` appeared in the validation build/runtime directories.

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

### [x] NPT-3ab: Runtime P12 virtual/interface PIC-hit audit

- Scope: add only default-off diagnostic counters for the existing
  `neko_icache_dispatch` virtual/interface PIC path. The audit may count
  direct-C hits, direct-NJX hits, misses, translated-stub stores, direct-NJX
  stores, and unresolved exits in the existing `NEKO_DIRECT_DEBUG=1` stats
  summary.
- Required evidence: current source shows direct-C and direct-NJX hit branches,
  interleaved miss resolution, and only a per-site `miss_count`; previous P12
  declared-mid removal and cold-miss outlining were rejected. The new audit must
  prove the current hit/miss/target-kind mix before any further dispatch shape
  change.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew` after permission, fresh
  `NativeObfuscationIntegrationTest`, default-off TEST/obfusjack parity runs,
  one opt-in `NEKO_DIRECT_DEBUG=1` diagnostic runtime, and generated-C
  inspection.
- Completion criteria: generated C shows only audit counters/logging added
  around existing branches; default-off parity has no accepted regression; the
  opt-in diagnostic run emits useful PIC hit/miss/target-kind counts; no
  receiver-key computation, PIC size, target selection, miss resolution,
  direct-C dispatch, direct-NJX call-stub dispatch, handle scope, exception
  behavior, JNI/JVMTI fallback, skip-on-error behavior, or original-bytecode
  fallback changes.
- Completion evidence: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed; fresh
  `NativeObfuscationIntegrationTest` passed; opt-in TEST audit artifact
  `build/p12-icache-audit/TEST-native-audit.jar` generated with
  `translated=49 rejected=0` from
  `build/neko-native-work/run-9527959662306`; its build manifest recorded
  `icache.audit.build=true` and compile commands containing
  `-DNEKO_ICACHE_AUDIT=1`. The opt-in `NEKO_DIRECT_DEBUG=1` runtime completed
  and emitted counters including `icache_direct_njx_hit=519999`,
  `icache_miss=43`, `icache_direct_njx_store=44`, `resolve_failed=0`, and
  `icache_unresolved=0`; a second loaded native segment reported
  `icache_direct_njx_hit=20`, `icache_miss=76`, `icache_direct_njx_store=77`,
  `resolve_failed=0`, `icache_unresolved=0`. Generated-C inspection found no
  executable forbidden JNI/JVMTI markers in the icache support path; remaining
  support-file JNI strings were comments or internal JVM symbol names.
- Outcome: the audit is accepted as measurement infrastructure. The recorded
  runtime mix is dominated by direct-NJX PIC hits, so no further P12 cold-miss
  outlining or dispatch-shape change is justified without new evidence.
- Dependency update: P2/P4 GC strict acceptance remains blocked by the fresh
  ZGC mask-publication abort (`ZGC oop load masks unavailable addr=0x0
  good=0x0`). This P12 row is measurement-only runtime hot-path debugging to
  guide the next performance change; it does not mark any GC blocker complete.

### [-] NPT-3ac: Runtime P13 raw-function flattening validation

- Scope: validate the existing structural raw-function flattening heuristic in
  `CCodeGenerator` without changing runtime code. The current source emits
  `NEKO_FLATTEN NEKO_HOT` only for translated raw bodies whose statement count
  is at or below `MAX_FLATTEN_STATEMENTS`, and emits `NEKO_HOT` without
  `NEKO_FLATTEN` for larger translated bodies.
- Required evidence: source line evidence for `MAX_FLATTEN_STATEMENTS`,
  `isLargeImplementation`, and conditional raw-function emission; fresh
  generated-C evidence showing both flattened and non-flattened translated
  bodies selected by statement count; runtime/performance evidence that the
  current source does not regress the accepted baseline.
- Validation command or runtime target: focused generator/audit tests with
  repository `./gradlew` after permission, fresh TEST/obfusjack native
  generation and runtime, generated-C inspection, and performance capture.
- Completion criteria: no owner/name/descriptor/sample-specific selection,
  no CFF block change, no bytecode translation change, no JNI/JVMTI fallback,
  no skip/original-bytecode fallback, and no accepted runtime/performance
  regression.
- Rejected validation 2026-05-21: the structural heuristic is generic and was
  present in source, but this row is not accepted as a performance win. Source
  evidence: `CCodeGenerator.MAX_FLATTEN_STATEMENTS = 128`,
  `fn.body().size() > MAX_FLATTEN_STATEMENTS`, and conditional raw emission at
  `CCodeGenerator.java:2150-2157`. Fresh TEST native generation translated
  `49` methods with `rejected=0`; generated C showed large
  `neko_native_impl_0.c` emitted as `NEKO_HOT` without `NEKO_FLATTEN` while
  small bodies such as `neko_native_impl_8.c` kept
  `NEKO_FLATTEN NEKO_HOT`. Fresh obfusjack native generation translated `93`
  methods with `rejected=0`; generated C showed large bodies
  `neko_native_impl_39.c` (`1085` lines), `neko_native_impl_44.c` (`181`
  lines), and `neko_native_impl_46.c` (`146` lines) emitted as `NEKO_HOT`
  without `NEKO_FLATTEN`, while small bodies such as `neko_native_impl_13.c`
  (`38` lines) and `neko_native_impl_0.c` (`30` lines) kept
  `NEKO_FLATTEN NEKO_HOT`. Runtime evidence: TEST native completed five fresh
  runs with Calc `86/90/85/90/83 ms`, median `86 ms`, which is below the
  accepted NPT-3y TEST median `87 ms`. Obfusjack native completed fresh runs
  with Platform `45/48/50/45/45 ms`, Virtual `47/37/39/48/43 ms`, and Seq
  `17/19/17/17/17 ms`; medians were Platform `45 ms`, Virtual `43 ms`, and
  Seq `17 ms`. One 120s run timed out after printing `=== All tests completed
  ===`; a 180s rerun exited `0` with Platform `44 ms`, Virtual `42 ms`, and
  Seq `18 ms`, so no crash/fatal/JNI fallback was observed. The row remains
  rejected because obfusjack Platform and Virtual regressed versus the accepted
  baseline and missed the performance gate (`Platform <= 44 ms`,
  `Virtual <= 35 ms`, `Seq <= 14 ms`). Do not change the flattening threshold
  or selection shape without new generated-code and compiler-layout evidence.

### [x] NPT-3ad: Runtime P14 opt-in native compiler diagnostics

- Scope: add only an optional native compiler diagnostics mode controlled by
  `NEKO_NATIVE_OPT_DIAGNOSTICS=1`. The mode may add clang/zig optimization
  record flags and manifest entries for per-source diagnostic files.
- Required evidence: `NativeBuildEngine` currently records debug and icache
  audit build flags and assembles common compile flags, but has no
  optimization-diagnostics flag or per-source diagnostics manifest paths.
- Validation command or runtime target: focused Gradle tests with repository
  `./gradlew` after permission, one normal native generation proving default
  compile commands are unchanged and diagnostics disabled, one opt-in native
  generation proving diagnostics enabled in the manifest/compile commands and
  producing inspectable compiler diagnostics or optimization-record files, plus
  generated-C forbidden-marker inspection.
- Completion criteria: default builds do not include diagnostics flags and do
  not change generated C, translated bytecode, runtime dispatch, JNI/JVMTI
  behavior, fallback behavior, or release compile/link flags; opt-in builds
  expose enough compiler output to guide P4/P6/P8/P13 without making
  diagnostics mandatory for normal artifacts.
- Completion evidence: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Normal TEST generation
  `build/neko-native-work/run-9988143205398` produced `translated=49
  rejected=0`, manifest `opt.diagnostics.build=false`, and no
  `-fsave-optimization-record` or `-foptimization-record-file` compile flags.
  The first opt-in attempt rejected unsupported
  `-foptimization-record-format=yaml`; the accepted narrowed implementation
  generated `build/p14-opt-diagnostics/TEST-native-opt-diagnostics.jar` from
  `build/neko-native-work/run-10032888234072` with `translated=49 rejected=0`,
  manifest `opt.diagnostics.build=true`, compile commands containing
  `-fsave-optimization-record` and per-source `-foptimization-record-file=...`,
  and 67 `.opt.yaml` files under `opt-diagnostics/linux_x64`. Representative
  `neko_native_impl_0.opt.yaml` records inline pass diagnostics such as
  `AlwaysInline`. The opt-in TEST jar ran successfully with `Calc: 83ms`.
  Generated-C inspection found no executable forbidden JNI/JVMTI/fallback
  markers in the checked support/icache paths; remaining support-file JNI
  strings were comments or internal JVM symbol names.
- Outcome: P14 is accepted as opt-in compiler evidence infrastructure. It is
  not itself a runtime optimization, but it gives the next P13/P4 work a
  concrete inlining/optimization record without perturbing normal artifacts.

### [x] NPT-3ae: Runtime ZGC barrier readiness capability invariant

- Scope: fix only the ZGC GC-barrier readiness predicate so readiness means
  translated oop load/store paths have an executable ZGC barrier capability.
  The change must not add JNI fallback, skip behavior, original-bytecode
  fallback, sample-derived ZGC masks, owner/name/descriptor selection, or any
  new Java helper.
- Required evidence: fresh strict ZGC logs show `ZGlobalsForVMStructs`
  metadata is discovered, but the published values are all zero and `z_lrb`,
  `z_array`, and `z_store` are all `(nil)` while the current runtime still
  reports `gc barrier: ready=1 kind=3`. Runtime then aborts later with
  `ZGC oop load masks unavailable addr=0x0 good=0x0`. Source evidence:
  `neko_gc_barrier_layout_ready` treats ZGC instance pointer plus offsets as
  ready even when no callable runtime barrier and no nonzero live masks are
  available.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with patch logging, and generated-C/log
  inspection.
- Completion criteria: ZGC readiness is capability-based: either callable ZGC
  runtime/CompilerToVM barriers are present, or the inline path has nonzero
  live masks sufficient for translated oop loads. If neither capability is
  present, the runtime fails closed before selecting translated oop paths and
  does not reach a later missing-mask abort, JNI fallback, skip, or original
  bytecode fallback.
- Completion evidence 2026-05-21: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh TEST native generation
  produced `build/npt-3ae-zgc/TEST-native.jar` from
  `build/neko-native-work/run-10986264548170` with `translated=49 rejected=0`
  and `libneko_linux_x64.so` size `1088536` bytes. Generated C contains the
  new capability predicate in `neko_native_support_helpers_2.c`: it reads
  live/frozen ZGC address, load-good, load-bad, store-good, and store-bad masks
  and requires all five to be nonzero before accepting the inline VMStruct
  path. Strict ZGC TEST with `NEKO_PATCH_DEBUG=1 -XX:+UseZGC
  -XX:+UnlockDiagnosticVMOptions -XX:+ZVerifyViews` aborted at layout
  initialization with `gc barrier: ready=0 kind=3` and
  `gc barrier path not ready for kind=3`; the previous later
  `ZGC oop load masks unavailable addr=0x0 good=0x0` abort did not appear.
  The same generated TEST native jar completed under the default collector with
  `Calc: 89ms`. The row is accepted as a fail-closed capability-invariant fix;
  P2/P4 GC strict completion still requires a real callable ZGC barrier or
  nonzero-mask capability on this JDK.

### [x] NPT-3af: Runtime ZGC CompilerToVM and long-constant capability audit

- Scope: add only generic, default-off patch-debug evidence collection for
  HotSpot-published ZGC capability sources that are not currently audited:
  `gHotSpotVMLongConstants`, `CompilerToVM::Data` Z barrier fields, and
  thread-local ZGC mask offsets such as `thread_address_bad_mask_offset`.
  This row must not call CodeBlob runtime stubs, select a new ZGC barrier path,
  derive sample masks, add JNI/JVMTI/helper fallback, or alter translated
  runtime behavior outside opt-in diagnostics.
- Required evidence: post-NPT-3ae strict ZGC TEST fails closed at native layout
  initialization with `gc barrier: ready=0 kind=3`; local `libjvm.so` strings
  contain `thread_address_bad_mask_offset` and `ZBarrierSetRuntime_*` names,
  while `nm -D` does not expose matching dynamic symbols. Current source walks
  `gHotSpotVMStructs`, `gHotSpotVMTypes`, and `gHotSpotVMIntConstants`, but
  has no `gHotSpotVMLongConstants` walker and current patch-debug logs do not
  prove whether CompilerToVM or long-constant entries are reachable.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with `NEKO_PATCH_DEBUG=1`, and generated-C/log
  inspection.
- Completion criteria: fresh logs either prove a generic, HotSpot-published,
  ABI-safe source for ZGC masks/barriers exists and identify its exact service
  table path, or prove that this JDK does not expose such a source through the
  runtime's permitted VMStructs/VMTypes/VMIntConstants/VMLongConstants/dlsym
  paths. The runtime must still fail closed under ZGC until a later row records
  complete ABI evidence and implements an executable barrier capability.
- Completion evidence 2026-05-21: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed after adding the diagnostics.
  Fresh TEST native generation produced `build/npt-3af-zgc/TEST-native.jar`
  from `build/neko-native-work/run-11515458382165` with `translated=49
  rejected=0` and `libneko_linux_x64.so` size `1090200` bytes. Generated C
  contains the new `neko_walk_vm_long_constants` audit and VMStruct/VMInt
  zcap summaries. Strict ZGC TEST with `NEKO_PATCH_DEBUG=1 -XX:+UseZGC
  -XX:+UnlockDiagnosticVMOptions -XX:+ZVerifyViews` still failed closed at
  native layout initialization with `gc barrier: ready=0 kind=3`. The fresh
  stderr log shows standard VMStructs expose only 11 `ZGlobalsForVMStructs`
  zcap entries and `compilertovm_zcap_matches=0`; VMIntConstants expose
  `vmint zcap matches=0`; VMLongConstants expose only four zero-valued
  `ZAddress*` constants. The same jar completed under the default collector
  with `Calc: 92ms`. Independent local OpenJDK source inspection found the
  missing `CompilerToVM::Data` Z barrier entries and
  `thread_address_bad_mask_offset` in JVMCI VMStruct tables
  (`jvmciHotSpotVMStructs` / `jvmciHotSpotVMIntConstants` /
  `jvmciHotSpotVMLongConstants`), so the next implementation row must add a
  generic JVMCI serviceability-table walker before selecting any ZGC barrier
  capability.

### [x] NPT-3ag: Runtime JVMCI serviceability-table ZGC binding

- Scope: add a generic native walker for the JVMCI HotSpot serviceability
  tables that are already exported by libjvm on JVMCI-capable builds:
  `jvmciHotSpotVMStructs`, `jvmciHotSpotVMIntConstants`, and
  `jvmciHotSpotVMLongConstants`. Bind only the ZGC-relevant
  `CompilerToVM::Data` entries that the local OpenJDK source publishes and
  whose ABI already matches the runtime call surface:
  `thread_address_bad_mask_offset` and
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded`. Audit, but do not
  publish as callable, `ZBarrierSetRuntime_load_barrier_on_oop_array` because
  local OpenJDK source shows its ABI is `void(oop*, size_t)` while the current
  runtime typedef is still `void*(void*)`. This row must not treat the
  C1 CodeBlob runtime stubs as callable C ABI functions, must not derive
  sample masks, and must not mark ZGC ready until store/no-store semantics are
  recorded and proven by a later row.
- Required evidence: NPT-3af fresh logs prove the standard
  `gHotSpotVMStructs`/VMInt/VMLong tables do not expose CompilerToVM ZGC
  capability entries on this JDK. Local OpenJDK source shows JVMCI exports
  `jvmciHotSpotVMStructs`, `jvmciHotSpotVMIntConstants`, and
  `jvmciHotSpotVMLongConstants`; `vmStructs_jvmci.cpp` lists
  `CompilerToVM::Data::thread_address_bad_mask_offset`,
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded`, and
  `ZBarrierSetRuntime_load_barrier_on_oop_array`; `XBarrierSetRuntime.hpp`
  proves the field-load ABI matches the current two-argument runtime typedef,
  while the array ABI does not match yet; and
  `jvmciCompilerToVMInit.cpp` initializes those fields under `UseZGC` from
  `XThreadLocalData::address_bad_mask_offset()` and
  `XBarrierSetRuntime::*_addr()`.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with `NEKO_PATCH_DEBUG=1`, generated-C/log
  inspection, and default collector TEST smoke.
- Completion criteria: generated C contains a JVMCI-table walker that uses the
  same VMStruct entry layout offsets as the standard table, fresh strict-ZGC
  logs show whether the JVMCI table is present and whether the expected
  CompilerToVM entries bind or are deliberately left unbound for ABI reasons,
  and the runtime still fails closed unless a complete ZGC barrier capability
  is proven. No JNI fallback,
  JVMTI, original bytecode, skip behavior, or benchmark/sample-specific logic
  may be introduced.
- Completion evidence 2026-05-21: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed after adding the JVMCI walkers.
  Fresh TEST native generation produced `build/npt-3ag-zgc/TEST-native.jar`
  from `build/neko-native-work/run-12028033311558` with `translated=49
  rejected=0` and `libneko_linux_x64.so` size `1093336` bytes. Generated C
  contains `neko_walk_jvmci_vm_structs`, `neko_walk_jvmci_vm_constants`, the
  `z_bad_off` GC-barrier diagnostic, and the corrected
  `off_zglobals_pointer_store_bad_mask = -1` initialization. Strict ZGC TEST
  with patch logging failed closed at layout initialization in both default
  JVMCI state and with `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI`;
  both fresh logs report `jvmci vmstructs unavailable: table=(nil)`,
  `jvmci vmint constants unavailable: table=(nil)`, and
  `jvmci vmlong constants unavailable: table=(nil)`, then
  `gc barrier: ready=0 kind=3 ... z_lrb=(nil) z_array=(nil) z_store=(nil)
  z_bad_off=-1`. ELF inspection of the active
  `/usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so` shows no dynamic or
  regular symbols for `jvmciHotSpotVMStructs`, while `strings` still contains
  the names `thread_address_bad_mask_offset` and
  `ZBarrierSetRuntime_load_barrier_on_oop_*`. The same jar completed under the
  default collector with `Calc: 94ms`. The row is accepted as a generic
  dlsym-reachable JVMCI-table audit/binder; this runtime does not expose the
  table, so ZGC strict remains fail-closed.

### [x] NPT-3ah: Runtime stripped JVMCI serviceability-table recovery audit

- Scope: determine whether the active product `libjvm.so` contains a
  recoverable JVMCI VMStructEntry table despite stripping the exported
  `jvmciHotSpotVM*` symbols. This is a read-only audit/prototype row only:
  inspect ELF sections, local OpenJDK layout, and runtime logs; do not add a
  runtime scanner, do not scan arbitrary heap/code memory, do not select a ZGC
  barrier path, and do not change executable behavior until a complete generic
  invariant is recorded.
- Required evidence: NPT-3ag shows `dlsym` cannot resolve
  `jvmciHotSpotVMStructs`, `jvmciHotSpotVMIntConstants`, or
  `jvmciHotSpotVMLongConstants` even with `-XX:+EnableJVMCI`; `nm` and
  `readelf -Ws` show no matching symbols in the active Arch OpenJDK
  `libjvm.so`; `strings` still contains
  `thread_address_bad_mask_offset` and `ZBarrierSetRuntime_load_barrier_on_oop_*`;
  local OpenJDK source defines VMStructEntry layout and the JVMCI table fields.
- Validation command or runtime target: read-only local source and ELF
  inspection, plus optional repository-local prototype output under `build/`
  if needed. No Gradle or runtime artifact is required unless the audit
  becomes an implementation row.
- Completion criteria: either identify a generic, bounded, section-scoped way
  to recover JVMCI VMStructEntry records from the mapped `libjvm.so` image with
  enough evidence for a later implementation row, or record that the product
  JDK does not expose a safe recoverable JVMCI table path and choose the next
  non-ZGC performance/GC blocker.
- Completion evidence 2026-05-21: read-only ELF/source inspection identified a
  bounded recovery path. `readelf -S` shows the active
  `/usr/lib/jvm/java-21-openjdk/lib/server/libjvm.so` contains `.rodata`,
  `.data.rel.ro`, and `.data` sections; `.symtab` is absent and `readelf -Ws`
  shows no `jvmciHotSpotVM*` symbols. `grep -aob` found
  `CompilerToVM::Data` at file/VMA `0xf4c22a`,
  `thread_address_bad_mask_offset` at `0xfb1eb8`,
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` at `0xfb1ed8`,
  and `ZBarrierSetRuntime_load_barrier_on_oop_array` at `0xfb2060`. A
  read-only prototype search for little-endian pointer addends found
  VMStructEntry-shaped records in the data image: entry `0x12f0930` points to
  `CompilerToVM::Data`, `thread_address_bad_mask_offset`, type string `int`,
  `isStatic=1`, and static-address addend `0x1314db0`; entry `0x12f0960`
  points to `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded`, type
  string `address`, and static-address addend `0x1314da8`; entry `0x12f0a80`
  points to `ZBarrierSetRuntime_load_barrier_on_oop_array`, type string
  `address`, and static-address addend `0x1314d78`. Local OpenJDK source
  confirms the VMStructEntry layout is six fields / 48 bytes and the JVMCI
  table emits sentinel-terminated arrays. The next implementation row may add
  a generic libjvm image-section scanner limited to the mapped libjvm
  data/RELRO ranges and these VMStructEntry invariants; it must still keep the
  array barrier unbound until its ABI is corrected.

### [x] NPT-3ai: Runtime stripped JVMCI VMStructEntry scanner

- Scope: implement a generic, bounded native scanner that recovers only
  stripped JVMCI `CompilerToVM::Data` VMStructEntry records from mapped
  `libjvm.so` data/RELRO ranges when the `jvmciHotSpotVM*` export symbols are
  unavailable. VMStructEntry scanning must stay limited to path-tagged
  `libjvm.so` non-executable mappings; VMStruct static slots may additionally
  validate against immediately adjacent anonymous writable data mappings
  created by the loader for `libjvm.so` data. The scanner may bind
  `thread_address_bad_mask_offset` and
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` if their entries
  satisfy the exact VMStructEntry invariant. It must audit but not publish
  `ZBarrierSetRuntime_load_barrier_on_oop_array` until the array ABI row fixes
  the runtime call surface. It must not scan arbitrary process memory, treat
  raw CodeBlob stubs as C ABI calls, derive sample masks, mark ZGC ready, or
  change store/no-store semantics.
- Required evidence: NPT-3ah proves the active stripped `libjvm.so` contains
  VMStructEntry-shaped records in the data image pointing to the exact
  `CompilerToVM::Data` and ZGC field strings, and local OpenJDK source proves
  the entry layout and field ABI for the field-load barrier. NPT-3ag proves
  dlsym-exported JVMCI tables are unavailable at runtime.
- Validation command or runtime target: focused generator/audit tests with the
  repository `./gradlew` after permission, fresh TEST native generation,
  strict ZGC TEST runtime probe with `NEKO_PATCH_DEBUG=1`, generated-C/log
  inspection, and default collector TEST smoke.
- Completion criteria: generated C contains a scanner bounded to mapped
  `libjvm.so` non-executable data/RELRO ranges, adjacent loader-created
  anonymous libjvm data slots, and exact VMStructEntry pointer invariants; fresh
  strict-ZGC logs show the scanner either binds
  `thread_address_bad_mask_offset` and the field-load barrier or proves the
  entries were not recoverable; the array barrier remains unbound with an ABI
  diagnostic; ZGC readiness remains fail-closed until a later complete barrier
  row.
- Completion evidence 2026-05-21: focused generator/audit tests passed. Fresh
  TEST native generation produced
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

### [x] NPT-3aj: JVMCI ZGC barrier slot initialization audit

- Scope: determine why the recovered stripped JVMCI
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded` static slot is null
  during strict ZGC native layout initialization even though the
  `CompilerToVM::Data` VMStructEntry record and slot address are recoverable.
  This is a read-only evidence row. It may inspect OpenJDK source, local
  `libjvm.so` symbols/strings, generated native logs, and existing generated C,
  but must not call JVMCI initialization paths, bind raw CodeBlob stubs, mark
  ZGC ready, change store/no-store semantics, or add fallback behavior.
- Required evidence: NPT-3ai proves the VMStructEntry scanner recovers the
  bad-mask static slot and the field-load barrier static slot, but fresh strict
  ZGC logs show the field-load barrier slot value is null while the GC barrier
  path remains fail-closed.
- Validation command or runtime target: source/symbol/log audit only; if a
  candidate runtime path is found, record a later implementation row with its
  exact invariant and runtime validation target before editing executable code.
- Completion criteria: identify the exact initializer or runtime publication
  path responsible for filling the JVMCI ZGC barrier slots, prove whether it is
  callable or observable from the current native bootstrap surface without JNI
  fallback/JVMTI/new helper layers, and record the next generic implementation
  or rejection decision.
- Completion evidence 2026-05-21: OpenJDK source shows
  `CompilerToVM::Data::initialize(JVMCI_TRAPS)` publishes the ZGC slots from
  `XBarrierSetRuntime::*_addr()` under `UseZGC`; the initializer is invoked
  from `readConfiguration0`, which is reached through JVMCI Java/native
  configuration paths, not from the VMStruct table walk itself. The native
  JVMCI runtime entry `JVM_GetJVMCIRuntime` rejects calls unless `EnableJVMCI`
  is set and initializes the Java `HotSpotJVMCIRuntime`; this path requires the
  JVMCI/JNI Java runtime surface and is not a valid native-bootstrap trigger
  under the no-JNI/no-new-helper constraints. Runtime evidence confirms the
  timing: default strict ZGC keeps the recovered field-load and array slots
  null, while strict ZGC with
  `-XX:+UnlockExperimentalVMOptions -XX:+EnableJVMCI -XX:+EagerJVMCI` fills
  `thread_address_bad_mask_offset=40`,
  `ZBarrierSetRuntime_load_barrier_on_oop_field_preloaded=0x7f398144ace0`,
  and `ZBarrierSetRuntime_load_barrier_on_oop_array=0x7f398144ab70`.
  Readiness still fails closed because the current native surface intentionally
  does not bind the mismatched array ABI and has no resolved store barrier.
  Next implementation must be a separate row: correct and bind the ZGC array
  ABI from the recovered JVMCI slot, then reassess ZGC readiness around the
  actually available callable barriers and inline fail-closed store path.

### [x] NPT-3ak: ZGC C1 barrier stub target recovery audit

- Scope: determine whether the named ZGC C1 barrier CodeBlobs present in the
  CodeCache can provide a generic, architecture-level recovery path to the
  underlying callable runtime leaf functions when `libjvm.so` symbols are
  stripped and JVMCI `CompilerToVM::Data` slots have not been initialized. This
  is a read-only evidence row. It may inspect OpenJDK C1/ZGC source, current
  generated logs, generated C, and static binary metadata. It must not bind raw
  CodeBlob stubs, execute recovered targets, infer addresses from sample
  benchmarks, mark ZGC ready, or add executable behavior.
- Required evidence: NPT-3aj proves default strict ZGC does not publish the
  JVMCI barrier slot values during early native layout initialization, while
  fresh logs show named `load_barrier_on_oop_field_preloaded_runtime_stub`
  CodeBlobs exist even in the default strict-ZGC run.
- Validation command or runtime target: source/log/static audit only. If a
  target-recovery invariant is proven, record a later implementation row with
  the exact CodeBlob name, instruction/site invariant, ABI, compressed-oops
  applicability, and runtime validation target before editing executable code.
- Completion criteria: either prove a generic and safe way to recover callable
  ZGC runtime leaf targets from named stubs without binding the stubs as call
  targets, or reject this path with the exact missing invariant and choose the
  next barrier-capability route.
- Completion evidence 2026-05-21: source shows the named C1 load barrier
  blobs are generated by `XBarrierSetC1::generate_c1_runtime_stubs` or
  `ZBarrierSetC1::generate_c1_runtime_stubs`, and the x86 generator emits a
  VM-leaf call to `XBarrierSetRuntime::load_barrier_on_oop_field_preloaded_addr`
  or `ZBarrierSetRuntime::load_barrier_on_oop_field_preloaded_addr`. Fresh
  strict-ZGC logs confirm the default run contains
  `load_barrier_on_oop_field_preloaded_runtime_stub` and weak-load sibling
  CodeBlobs. However, those CodeBlobs are C1 runtime stubs with VM/stub entry
  ABI, not C ABI functions for direct native calls, and binding the raw stub is
  rejected. More importantly, the active `gc/x` source has no corresponding C1
  store-barrier stubs, and neither `gc/x` nor `gc/z` C1 generation provides the
  object-array runtime barrier as a named CodeBlob. This path can at most
  recover a field-load leaf target after an architecture-specific instruction
  invariant is separately proven; it cannot provide the complete load-array and
  store capability required to mark ZGC ready. Reject C1-stub target recovery as
  the next readiness path. The next generic route is to fix the recovered JVMCI
  array ABI for already-published JVMCI slots and separately keep default
  strict-ZGC fail-closed until a complete non-JVMCI store/load capability is
  proven.

### [x] NPT-3al: Correct recovered ZGC object-array barrier ABI

- Scope: correct the native ZGC object-array load barrier ABI from the current
  single-argument/returning call shape to the OpenJDK `void(oop*, size_t)` shape
  for wide oop arrays, update dlsym names accordingly, and bind the recovered
  stripped JVMCI `ZBarrierSetRuntime_load_barrier_on_oop_array` slot only when
  its target is already published and validated inside executable `libjvm.so`.
  The implementation must not use the runtime array barrier for compressed-oop
  array slots, must not bind raw CodeBlob stubs, must not initialize JVMCI, must
  not mark default strict ZGC ready, and must not change store-barrier
  semantics.
- Required evidence: NPT-3aj proves the JVMCI array slot is published under
  eager JVMCI and null in default early layout; local OpenJDK source proves the
  array runtime ABI is `void(oop*, size_t)` while the current native typedef is
  `void *(*)(void*)`; NPT-3ak rejects C1 stubs as a complete readiness path.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, strict ZGC with eager JVMCI to prove the array slot is
  bound and logged, strict default ZGC to prove it remains fail-closed, and
  generated-C inspection for the corrected ABI and absence of JNI fallback.
- Completion criteria: generated C uses the corrected array typedef/call shape,
  dlsym probes use the corrected mangled two-argument symbol names, recovered
  JVMCI array targets are executable-range validated before binding, compressed
  oop arrays do not call the wide array runtime, and ZGC readiness remains
  blocked by missing complete store/default capability.
- Completion evidence 2026-05-21: focused generator/audit tests passed. Fresh
  TEST native generation produced
  `build/npt-3al-zgc/TEST-native.jar` from
  `build/neko-native-work/run-13753557747563` with `translated=49 rejected=0`.
  Generated C contains `typedef void (*neko_z_lrb_array_t)(void**, size_t)`,
  the corrected
  `_ZN18{Z,X}BarrierSetRuntime25load_barrier_on_oop_arrayEPP7oopDescm` dlsym
  probes, and executable-range validated array binding logs. Strict ZGC with
  eager JVMCI bound `z_lrb=0x7fe898c4ace0`,
  `z_array=0x7fe898c4ab70`, and `z_bad_off=40`, then still failed closed due
  to missing `z_store`. Default strict ZGC kept field/array slots null and
  failed closed with `z_lrb=(nil)`, `z_array=(nil)`, `z_store=(nil)`.
  Default collector TEST completed with `Calc: 87ms`.

### [x] NPT-3am: Split primitive field volatile access

- Scope: expose resolved Java field `access_flags` through generated native
  field-slot metadata and use it so primitive field helpers emit volatile C
  accesses only for fields with `ACC_VOLATILE`. Non-volatile Java fields should
  use ordinary C loads/stores. Unknown or unresolved metadata must remain
  fail-closed or conservatively volatile; this row must not change object
  reference barrier semantics, field resolution ownership, JNI fallback,
  exception behavior, or ABI layout for unrelated helpers.
- Required evidence: the performance todo identifies current primitive field
  helpers as using volatile pointer dereferences for every primitive load/store,
  while field resolution already records HotSpot field access flags. This row
  must confirm the exact generated slot layout and access sites before editing.
- Validation command or runtime target: focused generator/unit tests with
  repository `./gradlew`, generated-C inspection proving volatile appears only
  on volatile primitive field paths or conservative unknown paths, fresh TEST
  native generation, default collector TEST smoke, and performance samples for
  TEST Calc/Platform if the row passes functional validation.
- Completion criteria: generated metadata carries field volatility generically,
  primitive field helpers preserve volatile semantics for volatile fields,
  normal fields no longer force volatile C memory access, unknown metadata stays
  conservative, and runtime validation shows no regression or fallback.
- Completion evidence 2026-05-21: fresh source/diff review showed this target
  is already implemented in the current code before any NPT-3am executable
  edit: generated static and instance field refs carry `access_flags_slot`,
  owner binding calls pass `g_access_*` slots to
  `neko_bind_instance_field_offset` and `neko_bind_static_field_metadata`, and
  those bind helpers write `native_field.access_flags` into the slot. Fresh
  generated C from `build/neko-native-work/run-13753557747563` proves primitive
  helpers take `uint32_t access_flags` and branch on `0x0040u`: volatile fields
  use `volatile` pointer access and non-volatile fields use ordinary pointer
  access for all primitive get/set and static get/set helpers. Primitive field
  callsites pass the resolved `g_access_*` slots. Focused generator/audit tests
  passed during NPT-3al, fresh TEST native generation produced `translated=49
  rejected=0`, and default collector TEST completed with `Calc: 87ms`. No
  executable change was needed for this row.

### [x] NPT-3an: Outline hot-helper diagnostics

- Scope: move selected inline `fprintf`/`abort` diagnostic construction out of
  hot native helper bodies into shared `cold`, `noinline` diagnostic functions.
  The first implementation must be narrowly scoped to helpers where the cold
  branch already terminates unconditionally and where argument values can be
  passed without changing evaluation order. It must not change exception
  behavior, pending-exception handling, GC barriers, array bounds/null ordering,
  or field/array access semantics.
- Required evidence: current generated helper source contains inline diagnostic
  blocks in hot array and field helpers; NPT-3am/P5 did not require executable
  edits, leaving P6 as the next performance row.
- Validation command or runtime target: focused generator/audit tests, generated
  C inspection proving hot helper bodies call cold diagnostics instead of
  containing large inline diagnostic formatting, fresh TEST native generation,
  and default collector TEST smoke. Broader performance gate remains a later
  row unless this change produces a runtime regression.
- Completion criteria: selected hot helper error paths are outlined to cold
  noinline functions, normal fast-path branches and semantic checks are
  unchanged, generated C compiles, and runtime smoke shows no new failure.
- Completion evidence 2026-05-21: object-field GETFIELD/GETSTATIC/PUTFIELD/
  PUTSTATIC diagnostic exits were outlined to `cold`, `noinline` hidden helper
  functions. Focused generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh TEST native
  generation succeeded in `build/neko-native-work/run-14414463312953` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1037656` bytes.
  Generated C inspection showed hot helpers in `neko_native_support.c` call
  `neko_abort_object_*_unavailable`, while
  `neko_native_support_helpers_4.c` defines those functions as
  `__attribute__((visibility("hidden"))) void __attribute__((cold, noinline))`.
  Default collector TEST smoke on `build/npt-3an-r2/TEST-native.jar` completed
  with no stderr and `Calc: 91ms`.

### [x] NPT-3ao: Outline object-array diagnostics

- Scope: move object-array-only diagnostic `fprintf`/`abort` construction from
  `neko_array_store_check`, `neko_fast_aastore`, and `neko_fast_aaload` into
  `cold`, `noinline` hidden helper functions. Keep successful object-array
  fast paths, null and bounds checks, array-store type checks, GC barriers, raw
  oop loads/stores, and handle creation unchanged. Do not touch the primitive
  array diagnostic shape rejected by NPT-3z.
- Required evidence: current source keeps unconditional diagnostic abort blocks
  in object-array helper failure paths. The NPT-3z regression applies to the
  generated primitive-array helpers, while the object-array blocks remain an
  untested portion of P6.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving object-array hot
  helper bodies call cold diagnostics instead of containing large inline
  formatting blocks, and default collector TEST smoke.
- Completion criteria: selected object-array diagnostic exits are outlined to
  cold noinline helpers, all normal branches and semantic checks are unchanged,
  generated C compiles with `translated>0 rejected=0`, and runtime smoke shows
  no new failure.
- Completion evidence 2026-05-21: `neko_array_store_check`,
  `neko_fast_aastore`, and `neko_fast_aaload` now call hidden `cold`,
  `noinline` diagnostic helpers for their terminal diagnostic exits. Focused
  generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh TEST native
  generation succeeded in `build/neko-native-work/run-14632991188519` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1038728` bytes.
  Generated C inspection showed object-array hot helpers in
  `neko_native_support.c` call `neko_abort_aastore_*` and
  `neko_abort_fast_aa*`, while `neko_native_support_helpers_4.c` defines those
  helpers with `visibility("hidden")`, `cold`, and `noinline`. Default
  collector TEST smoke on `build/npt-3ao/TEST-native.jar` completed with no
  stderr and `Calc: 89ms`.

### [x] NPT-3ap: Outline object fused-array diagnostics

- Scope: move object-array checked/fused diagnostic `fprintf`/`abort`
  construction from `neko_checked_aaload`, `neko_checked_aastore`, and
  `neko_fast_aaload_aaload` into hidden `cold`, `noinline` helper functions.
  Keep returned reason codes, null and bounds ordering, handle resolution,
  array-store checks, GC barriers, and raw oop loads/stores unchanged. Do not
  touch generated primitive checked/fused helpers or the primitive direct array
  diagnostic shape rejected by NPT-3z.
- Required evidence: current source still emits unconditional diagnostic aborts
  in object-array checked/fused failure paths after NPT-3ao. These paths are
  separate from the rejected primitive-array direct diagnostic row and can be
  moved without changing success-path semantics.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving the selected
  object-array checked/fused helpers call cold diagnostics instead of containing
  inline formatting blocks, and default collector TEST smoke.
- Completion criteria: selected object-array checked/fused diagnostic exits are
  outlined to cold noinline helpers, all normal checks and reason-code returns
  are unchanged, generated C compiles with `translated>0 rejected=0`, and
  runtime smoke shows no new failure.
- Completion evidence 2026-05-21: `neko_checked_aaload`,
  `neko_checked_aastore`, and `neko_fast_aaload_aaload` now call hidden `cold`,
  `noinline` diagnostic helpers for layout and unresolved-handle hard-abort
  paths. Focused generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh TEST native
  generation succeeded in `build/neko-native-work/run-14840042038598` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1036952` bytes.
  Generated C inspection showed selected checked/fused object-array helpers in
  `neko_native_support.c` call `neko_abort_checked_*` and
  `neko_abort_fused_aaload_aaload_*`, while `neko_native_support_helpers_4.c`
  defines those helpers with `visibility("hidden")`, `cold`, and `noinline`.
  Default collector TEST smoke on `build/npt-3ap/TEST-native.jar` completed
  with no stderr and `Calc: 97ms`; repeated TEST samples showed NPT-3ao
  `88,88,95,95,94 ms` (median `94ms`) versus NPT-3ap
  `89,85,92,88,86 ms` (median `88ms`).

### [rejected] NPT-3aq: Outline fused primitive AALOAD diagnostics

- Scope: move generated fused `AALOAD+xALOAD` and raw fused
  `raw AALOAD+xALOAD` diagnostic layout/outer-handle hard-abort formatting into
  hidden `cold`, `noinline` helper functions. Preserve all reason-code returns
  for outer null, outer bounds, inner null, and inner bounds; preserve the raw
  fused null check before `neko_zgc_good_oop`. Do not touch direct primitive
  array helpers or generated checked primitive load/store helpers.
- Required evidence: current source templates for
  `neko_fast_aaload_{b,c,s,i,l,f,d}aload` and
  `neko_fast_raw_aaload_{b,c,s,i,l,f,d}aload` still emit inline diagnostic
  formatting in hard-abort-only paths. The subagent audit identified these as
  distinct from the NPT-3z rejected direct primitive-array diagnostic shape.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving fused generated helper
  bodies call cold diagnostics instead of containing inline formatting blocks,
  default collector TEST smoke, and repeated TEST sample comparison against
  NPT-3ap if a smoke timing regresses.
- Completion criteria: generated fused primitive AALOAD diagnostic exits are
  outlined to cold noinline helpers, reason-code behavior is unchanged,
  generated C compiles with `translated>0 rejected=0`, and runtime validation
  shows no new failure or measured regression.
- Rejection evidence 2026-05-21: the attempted generic cold-helper shape moved
  `neko_fast_aaload_{b,c,s,i,l,f,d}aload` layout/outer-handle diagnostics and
  `neko_fast_raw_aaload_{b,c,s,i,l,f,d}aload` layout diagnostics to shared
  tag-parameterized cold helpers. Focused generator/audit tests passed, fresh
  generation succeeded in `build/neko-native-work/run-15069482044439` with
  `translated=49 rejected=0` and `libneko_linux_x64.so` size `1037208` bytes,
  generated C inspection proved the intended calls, and default TEST smoke
  completed with no stderr and `Calc: 90ms`. Repeated TEST samples regressed
  versus the NPT-3ap median: NPT-3aq `92,95,98,91,87 ms` (median `92ms`)
  versus NPT-3ap `89,85,92,88,86 ms` (median `88ms`). The source change was
  reverted before commit. Do not retry this tag-parameterized fused primitive
  diagnostic outlining without new branch-layout or compiler-output evidence.

### [x] NPT-3ar: Close descriptor shadow-frame and primitive-volatility rows

- Scope: close stale performance todo rows whose generic implementation is
  already present in the current source and validated by fresh generated
  artifacts. This is an evidence-only audit update; it must not change
  executable code.
- Required evidence: P3 requires one shadow-stack push/pop per translated
  method while replacing three per-slot strings with one descriptor pointer.
  P5 requires primitive field access to branch on resolved Java `ACC_VOLATILE`
  metadata so non-volatile fields use ordinary C loads/stores.
- Validation command or runtime target: source inspection plus generated-C
  inspection from the fresh NPT-3ap TEST artifact, with the same focused tests,
  generation, and smoke evidence already recorded for NPT-3ap/NPT-3am.
- Completion criteria: P3 and P5 todo rows are marked complete only if current
  generated C proves descriptor-pointer shadow frames and access-flag-driven
  primitive field volatility, with no executable diff in this row.
- Completion evidence 2026-05-21: source emits
  `static const neko_shadow_frame_desc __neko_shadow_desc` and
  `neko_shadow_push(&__neko_shadow_desc)` at translated method entry, and
  runtime support stores only `const neko_shadow_frame_desc *desc` per shadow
  frame before expanding owner/method/file in `neko_shadow_stack_trace`.
  Generated C from `build/neko-native-work/run-14840042038598` shows per-method
  descriptor pushes in `neko_native_impl_*.c`, `neko_shadow_frame` containing
  only `desc`, and trace expansion through `desc->owner`, `desc->method`, and
  `desc->file`. The same generated artifact proves primitive field helpers
  accept `uint32_t access_flags`, branch on `0x0040u`, use `volatile` only for
  volatile paths, and use ordinary pointer loads/stores otherwise. No
  executable source changed in this row.

### [x] NPT-3as: Add dependency shadow-stack fixture

- Scope: add the missing P3 acceptance fixture proving an external, unmodified
  Java classpath caller can invoke translated native code and later observe the
  translated frames produced by the descriptor-backed shadow stack. This is a
  test-only change; it must not change runtime/native code.
- Required evidence: NPT-3ar proved descriptor-pointer shadow frames in source
  and generated C, but the original P3 row also required a dependency-jar
  stack-trace fixture.
- Validation command or runtime target: focused
  `NativeObfuscationIntegrationTest` test using repository `./gradlew`.
- Completion criteria: the test creates an input jar containing only the
  native-translated target class, creates a separate dependency jar containing
  the external caller, runs `java -cp <native-output>:<dependency>`, and proves
  the returned stack trace contains translated target frames.
- Completion evidence 2026-05-21: added
  `nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`, which builds
  `dependency-shadow-target.jar` with `pkg.NativeStackTarget`, builds a separate
  `dependency-shadow-caller.jar` with `dep.ExternalStackCaller`, obfuscates only
  the target jar with `native-test.yml`, and runs `java -cp
  <native-output>:<dependency> dep.ExternalStackCaller`. The caller validates
  the returned stack trace string contains both
  `pkg.NativeStackTarget.leaf` and `pkg.NativeStackTarget.capture`. Focused
  validation passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests
  dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_dependencyCallerObservesTranslatedStackTrace`.
  Runtime stdout was `dependency-stacktrace-ok` and stderr was empty.

### [x] NPT-3at: Tighten translated local-root reservation

- Scope: reduce translated method-entry local-handle reservation only when the
  method ABI and bytecode prove that no reference value is stored in a JVM local
  slot. This is a generic P11 substep that avoids unnecessary
  `neko_prepare_local_oop_roots` calls without reusing HotSpot-owned
  `JNIHandleBlock::_next` blocks.
- Required evidence: current source emits local-root reservation for every
  static primitive-ABI method that is not fully primitive-only; generated TEST
  `Calc.runAll` calls translated `call`, `runAdd`, and `runStr` 10,000 times,
  and `call`/`runAdd` reserve roots even though they have no object-local
  stores while `runStr` needs roots for its string local. P11 ownership audit
  proves reusing existing `_next` blocks is unsafe because restore treats
  `saved_next` as pre-existing caller/VM state and restores `_top` only for the
  saved active block.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection for removed/retained
  `neko_prepare_local_oop_roots`, default TEST smoke, and repeated TEST timing
  comparison.
- Completion criteria: instance methods, reference parameters, `ASTORE` paths,
  and reference tail-recursive rewrites still reserve local roots; no-object-local
  static primitive methods do not reserve local roots; generated artifacts report
  `translated>0 rejected=0`; runtime smoke has no fatal/error output; timing does
  not regress.
- Completion evidence 2026-05-21: `NativeTranslator.methodMayUseObjectLocalRoots`
  now reserves local roots for static primitive-ABI methods only when bytecode
  contains an object-local store; instance methods and reference parameters
  still reserve roots. Focused generator/audit tests passed:
  `env GRADLE_USER_HOME=build/gradle-home-native-coverage bash ./gradlew
  :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Fresh TEST native generation produced
  `build/npt-3at/TEST-native.jar` from
  `build/neko-native-work/run-15969669901673` with `translated=49 rejected=0`
  and `libneko_linux_x64.so` size `1034616` bytes. Generated C inspection shows
  `Calc.runAll`, `Calc.call`, and `Calc.runAdd` no longer call
  `neko_prepare_local_oop_roots`, while `Calc.runStr` still reserves roots and
  uses `neko_store_local_oop_ref` for its string local. Direct TEST smoke ran
  cleanly with empty stderr; same-session comparison showed prior accepted
  `npt-3ap` Calc samples `89,90,92,90,90 ms` (median `90ms`) versus NPT-3at
  `88,85,83,101,94 ms` (median `88ms`). Focused native integration tests for
  TEST Calc and obfusjack completion also passed.

### [x] NPT-3bx: P11 default-off direct-oop handle overflow audit

- Scope: add measurement only for `native-performance-optimization-todo.md`
  P11 before changing local-handle overflow allocation. The audit must instrument
  the generic `neko_direct_oop_to_handle` path, not a benchmark-specific caller,
  and must not alter default runtime behavior or handle ownership.
- Required evidence: current source shows overflow `calloc` in
  `neko_direct_oop_to_handle`; generated C/source inspection identifies all
  counters as opt-in only; opt-in TEST and obfusjack runtimes report total
  direct-oop handle calls, active-block fast-slot hits, overflow block
  allocations, unavailable direct-slot exits, and max observed active-block
  top/capacity. The evidence must decide whether a reusable-block/window
  implementation is justified; zero or negligible overflow means P11 should
  remain an audit finding rather than an allocation rewrite.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh opt-in TEST/obfusjack generation
  using repository-local build/temp/cache directories, `NEKO_DIRECT_DEBUG=1`
  runtime for both artifacts, default non-audit smoke for at least TEST, and
  generated-C inspection showing audit counters are absent or inert in default
  builds and no forbidden JNI/JVMTI/fallback markers are introduced.
- Completion criteria: opt-in stats print useful nonzero total-call data;
  default builds retain the existing stats surface and hot path; runtime
  artifacts report `translated>0 rejected=0`; TEST and obfusjack complete
  without fatal/error output; the row records whether P11 implementation should
  proceed or be deferred.
- Completion evidence 2026-05-22: added default-off `NEKO_HANDLE_AUDIT`
  counters to the generic `neko_direct_oop_to_handle` path and exposed the
  build through `NEKO_NATIVE_HANDLE_AUDIT=1` / manifest
  `handle.audit.build=true`. Default builds keep `NEKO_HANDLE_AUDIT=0` and do
  not print the handle stats fields unless explicitly compiled with the audit
  flag and run with `NEKO_DIRECT_DEBUG=1`. Focused validation passed:
  `./gradlew --no-daemon :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest
  -Djava.io.tmpdir=build/native-run-tmp`. Fresh opt-in artifacts:
  `build/npt-3bx/TEST-handle-audit.jar` from
  `build/neko-native-work/run-31631741784087` (`translated=49 rejected=0`,
  manifest `handle.audit.build=true`, lib `1059560` bytes) and
  `build/npt-3bx/obfusjack-handle-audit.jar` from
  `build/neko-native-work/run-31640797565320`
  (`translated=93 rejected=0`, manifest `handle.audit.build=true`, lib
  `1838584` bytes). Strict forbidden-JNI grep over those generated run dirs
  found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` matches. Opt-in TEST
  runtime with `NEKO_DIRECT_DEBUG=1` completed with `Calc: 387ms` under audit
  overhead and printed two stats rows; the dominant row showed
  `handle_direct_total=510054`, `handle_direct_fast_slot=491718`,
  `handle_direct_overflow_alloc=18336`, `handle_direct_unavailable=0`,
  `handle_direct_max_top=32`, and `handle_direct_max_capacity=32`. Opt-in
  obfusjack runtime completed and printed `handle_direct_total=854741`,
  `handle_direct_fast_slot=846154`, `handle_direct_overflow_alloc=8587`,
  `handle_direct_unavailable=0`, `handle_direct_max_top=32`, and
  `handle_direct_max_capacity=32`. Fresh default non-audit TEST artifact
  `build/npt-3bx/TEST-default.jar` from
  `build/neko-native-work/run-31689142844822` reported
  `handle.audit.build=false`, completed with `Calc: 73ms`, and emitted no
  `[neko-direct] stats` line. Conclusion: P11 overflow allocation is hot enough
  to justify a generic reusable-block/window implementation; do not defer P11
  on zero-overflow grounds.

### [x] NPT-3by: P11 batch native-owned JNIHandleBlock overflow blocks

- Scope: reduce the measured P11 overflow allocation cost by batching and
  recycling only JNIHandleBlock memory allocated by generated native code. This
  is a generic scoped block allocator below the existing native/bootstrap
  surface, not a JNI fallback and not a benchmark-specific capacity tweak.
- Required evidence: NPT-3bx opt-in audit proves overflow allocation is hot:
  TEST `handle_direct_overflow_alloc=18336` and obfusjack
  `handle_direct_overflow_alloc=8587`, both with `handle_direct_max_top=32` and
  `handle_direct_max_capacity=32`. Source evidence must show restore knows the
  saved active block and `saved_next`, so blocks above that boundary are
  native-owned by the current translated scope and may be cleared and recycled
  only after they are removed from active JNI handle visibility. Fresh
  recycle-only audit then reported TEST still at
  `handle_direct_overflow_alloc=18336`, proving long single-invocation handle
  bursts need scoped batching before restore-time recycling can help.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh TEST and obfusjack native
  generation, default TEST/obfusjack runtime, opt-in `NEKO_NATIVE_HANDLE_AUDIT=1`
  TEST/obfusjack generation and `NEKO_DIRECT_DEBUG=1` runtime proving overflow
  allocation count drops and unavailable count remains zero, generated-C
  inspection for bounded recycle helpers and forbidden JNI/JVMTI/fallback
  markers, and repeated timing comparison against the before-edit default
  artifacts.
- Completion criteria: live JNI handles remain visible until restore; restore
  returns the active block, `_top`, `_next`, and `_last` to the saved state;
  scoped slabs are freed only after their blocks leave the active handle chain;
  recycled blocks are zeroed before reuse/pooling; the recycle list is bounded
  and per-thread; missing allocation still hard aborts; runtime artifacts report
  `translated>0 rejected=0`; TEST and obfusjack complete without fatal/error
  output; performance does not regress.
- Completed 2026-05-22: implemented scoped native-owned JNIHandleBlock slab
  allocation under the existing handle save/restore window, with the no-scope
  per-thread recycle list retained as a bounded fallback. Focused validation
  passed with `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest`.
  Fresh default artifacts: TEST `build/npt-3by-slab/TEST-default-r2.jar` from
  `build/neko-native-work/run-32502768700627` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1079896`), obfusjack
  `build/npt-3by-slab/obfusjack-default-r2.jar` from
  `build/neko-native-work/run-32505682956586` (`translated=93 rejected=0`,
  `handle.audit.build=false`, lib `1880200`). Strict generated-C grep for
  `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` returned no matches. Default TEST
  x5 Calc `68/70/76/78/67` ms, median `70`; default obfusjack x5 Platform
  `44/42/43/44/47` ms, median `44`, Virtual `36/37/44/38/41` ms, median `38`,
  Seq `17/17/18/17/17` ms, median `17`, Parallel/VThreads `1/1`. Fresh opt-in
  audit artifacts: TEST `build/npt-3by-slab/TEST-handle-audit-r2.jar` from
  `run-32559694871235`, obfusjack
  `build/npt-3by-slab/obfusjack-handle-audit-r2.jar` from
  `run-32562676424869`, both `handle.audit.build=true`. Opt-in TEST dominant
  stats dropped `handle_direct_overflow_alloc` from NPT-3bx `18336` to `286`
  with `handle_direct_unavailable=0`; opt-in obfusjack dropped from `8587` to
  `898` with `handle_direct_unavailable=0`.

### [x] NPT-3bz: P9 default-off direct-handle origin audit

- Scope: add a default-off origin audit for calls into
  `neko_direct_oop_to_handle` so P9 can choose a generic raw-oop lifetime
  target from runtime evidence instead of static callsite counts. The audit is
  permitted only under `NEKO_NATIVE_HANDLE_AUDIT=1`; default native artifacts
  must keep the same semantics and must not emit or execute audit-specific
  origin accounting.
- Required evidence: after NPT-3by, opt-in TEST still reports
  `handle_direct_total=510054` and obfusjack reports
  `handle_direct_total=854741`, while allocation-call counts fell sharply. This
  proves the remaining P9 question is not overflow allocation but which generic
  producer is causing direct-handle materialization. Source evidence: the
  existing fusion only handles adjacent `AALOAD; <int-push>; XALOAD` in
  `OpcodeTranslator`, while regular `neko_fast_aaload` still materializes a
  `jobject` through `neko_direct_oop_to_handle`.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh opt-in TEST and obfusjack
  generation/runtime with `NEKO_DIRECT_DEBUG=1`, generated-C inspection proving
  origin counters exist only in `NEKO_NATIVE_HANDLE_AUDIT` builds, default
  generated-C/manifest inspection proving `handle.audit.build=false` artifacts
  compile out the origin counters, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: default runtime behavior and generated helper call
  shapes are unchanged; opt-in TEST and obfusjack complete without fatal/error
  output; origin counter totals reconcile with `handle_direct_total`; no
  `handle_direct_unavailable` path appears; the recorded evidence identifies
  whether P9 should target `AALOAD` raw-oop lifetime or a different generic
  producer first.
- Completed 2026-05-22: added opt-in origin counters compiled under
  `NEKO_HANDLE_AUDIT` only. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh default artifacts:
  `build/npt-3bz/TEST-default.jar` from
  `build/neko-native-work/run-32880538686672` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1084344`) and
  `build/npt-3bz/obfusjack-default.jar` from `run-32883581015154`
  (`translated=93 rejected=0`, `handle.audit.build=false`, lib `1876824`).
  Default smoke passed with TEST `Calc: 70ms` and obfusjack Platform `41ms`,
  Virtual `36ms`, Seq `17ms`; neither emitted `[neko-direct]` stats. Strict
  generated-C forbidden grep returned no matches. Fresh opt-in artifacts:
  TEST `build/npt-3bz/TEST-handle-origin-audit.jar` from `run-32932004575725`
  and obfusjack `build/npt-3bz/obfusjack-handle-origin-audit.jar` from
  `run-32935171276056`, both `handle.audit.build=true`; strict forbidden grep
  returned no matches. TEST dominant origin row reconciled exactly to
  `handle_direct_total=510054`: `njx_return=510004`, `object_alloc=16`,
  `static_object_field=24`, `bound_string=10`, all AALOAD origins `0`, and
  `handle_direct_unavailable=0`. The second TEST stats row reconciled to
  `172`, with only `checked_aaload=8`. Obfusjack origins reconciled to
  `handle_direct_total=854741`: `njx_return=301141`, `object_alloc=200430`,
  `static_object_field=200096`, `checked_aaload=148993`, `object_field=3484`,
  `primitive_array_alloc=577`, `object_array_alloc=13`, `fused_aaload_aaload=1`,
  `bound_string=6`, `other=0`, and `handle_direct_unavailable=0`. Conclusion:
  P9 AALOAD lifetime is relevant for obfusjack but not the dominant TEST Calc
  lever; the next generic performance substep should prioritize NJX return
  handle materialization before a P9 lifetime rewrite.

### [x] NPT-3ca: P10 default-off NJX object-return shape audit

- Scope: add default-off per-shape counters for object-return NJX call_stub
  dispatches. This is an evidence-only P10 substep to identify whether the
  `njx_return` handle-materialization hot path is concentrated in one generic
  shape or spread across several shapes. It must not alter default call_stub
  arguments, Method*/entry selection, JavaCallWrapper layout, handle-window
  semantics, returned object materialization, exception behavior, or introduce
  any named-JDK/native substitution.
- Required evidence: NPT-3bz showed TEST `njx_return=510004` and obfusjack
  `njx_return=301141`, the largest single direct-handle origin in both targets.
  That proves NJX object returns are hot but does not yet identify the generic
  shape distribution needed to choose between a shape-level, all-object-return,
  or caller-consumption optimization.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh default TEST/obfusjack smoke proving
  `handle.audit.build=false` artifacts emit no shape stats and complete
  normally, fresh opt-in TEST/obfusjack runtime with `NEKO_DIRECT_DEBUG=1`
  showing per-shape object-return counters, generated-C/manifest inspection for
  `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: opt-in shape totals reconcile with the NPT-3bz
  `njx_return` origin totals for non-null object returns; default artifacts have
  no behavior change; TEST and obfusjack complete without fatal/error output;
  the recorded evidence identifies the next generic NJX-return optimization
  boundary.
- Completed 2026-05-22: added `NEKO_HANDLE_AUDIT`-gated per-object-return-shape
  counters and a shape summary. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh default artifacts:
  `build/npt-3ca/TEST-default.jar` from
  `build/neko-native-work/run-33252836211792` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1084344`) and
  `build/npt-3ca/obfusjack-default.jar` from `run-33255732064280`
  (`translated=93 rejected=0`, `handle.audit.build=false`, lib `1876824`).
  Default smoke passed: TEST `Calc: 72ms`; obfusjack rerun completed with
  Platform `46ms`, Virtual `39ms`, Seq `17ms`; neither default run emitted
  `[neko-direct]` stats. Strict forbidden-JNI grep returned no matches. Fresh
  opt-in artifacts: TEST `build/npt-3ca/TEST-shape-audit.jar` from
  `run-33444693183231` and obfusjack
  `build/npt-3ca/obfusjack-shape-audit.jar` from `run-33447791842344`, both
  `handle.audit.build=true`, with strict forbidden-JNI grep clean. TEST
  dominant shape row reconciled with `njx_return=510004`: `V:L:L=510002`,
  `V:L:=1`, `V:L:J=1`. Obfusjack reconciled with `njx_return=301141`:
  `V:L:=300822`, `V:L:L=159`, `S:L:J=57`, `S:L:I=50`, `S:L:L=19`, `S:L:=9`,
  `S:L:II=9`, `S:L:D=5`, `S:L:LLL=3`, `S:L:LLLL=2`, `V:L:LLL=2`,
  `S:L:LL=1`, `V:L:LIL=1`, `V:L:LL=1`, `V:L:II=1`. Conclusion: TEST and
  obfusjack have different dominant object-return shapes, so the next generic
  optimization boundary must be object-return continuation/lifetime, not a
  single shape or named JDK method.

### [x] NPT-3cb: P10 default-off NJX object-return continuation audit

- Scope: add default-off continuation counters for the existing generic
  invokedynamic string-concat accumulator. This is an evidence-only P10 substep
  to determine whether the dominant TEST `V:L:L` NJX object returns are
  intermediate concat accumulator values or final stack values. It must not
  construct strings natively, change the `String.concat` Method*/entry target,
  change default runtime behavior, or special-case a sample, class, method, or
  benchmark.
- Required evidence: NPT-3ca showed TEST `njx_return=510004` with
  `V:L:L=510002`. Source evidence shows the generic concat lowering calls
  `neko_concat_accumulate_string` for each concat piece and emits one final
  `PUSH_O(__acc)`. A raw-return optimization is justified only if runtime
  counters prove a material number of NJX object returns are intermediate
  accumulator values that are consumed by the next concat step before any
  safepoint-capable operation.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh default TEST/obfusjack smoke proving
  `handle.audit.build=false` artifacts emit no continuation stats, fresh opt-in
  TEST/obfusjack runtime with `NEKO_DIRECT_DEBUG=1` showing concat accumulator
  and final-push counters, generated-C/manifest inspection for
  `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: opt-in counters reconcile with the NPT-3ca `V:L:L`
  traffic enough to identify intermediate versus final concat object returns;
  default artifacts have no behavior change; TEST and obfusjack complete without
  fatal/error output; the recorded evidence either justifies or rejects a
  generic raw-return concat-continuation optimization.
- Completed 2026-05-22: added `NEKO_HANDLE_AUDIT`-gated concat continuation
  counters. Focused `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest`
  passed. Fresh default artifacts: `build/npt-3cb/TEST-default.jar` from
  `build/neko-native-work/run-33684566669446` (`translated=49 rejected=0`,
  `handle.audit.build=false`, lib `1084344`) and
  `build/npt-3cb/obfusjack-default.jar` from `run-33687571188325`
  (`translated=93 rejected=0`, `handle.audit.build=false`, lib `1876824`).
  Default smoke passed without `[neko-direct]` stats: TEST `Calc: 72ms`,
  obfusjack Platform `47ms`, Virtual `38ms`, Seq `17ms`. Strict forbidden-JNI
  grep returned no matches. Fresh opt-in artifacts: TEST
  `build/npt-3cb/TEST-continuation-audit.jar` from `run-33733597034954` and
  obfusjack `build/npt-3cb/obfusjack-continuation-audit.jar` from
  `run-33736528676932`, both `handle.audit.build=true`, with strict
  forbidden-JNI grep clean. TEST still reported `V:L:L=510002` and
  `njx_return=510004`, but concat continuation counters were all zero:
  `accumulate_total=0`, `accumulate_njx=0`, `final_push=0`,
  `intermediate_candidate=0`. Obfusjack reported `accumulate_total=137`,
  `accumulate_njx=89`, `final_push=48`, `intermediate_candidate=41`, far below
  its `njx_return=301141`. Conclusion: generic concat-continuation raw returns
  are rejected as the next performance route; TEST's dominant `V:L:L` returns
  come from the other generic concat fast path that immediately pushes the
  returned object.

### [x] NPT-3cc: P10 default-off StringBuilder concat fast-path audit

- Scope: add default-off audit counters for the recognized
  `StringBuilder.append(String).append(String).toString` concat fast path emitted
  by `NativeTranslator`. This is an evidence-only P10 substep to determine
  whether the dominant TEST `V:L:L` NJX object returns come from the immediate
  `neko_concat_append_inline(...)` plus `PUSH_O(__fastConcat)` path. It must not
  construct strings natively, change the `String.concat` Method*/entry target,
  change stack/local root semantics, change default runtime behavior, or
  special-case a sample, class, method, or benchmark.
- Required evidence: NPT-3cb proved the invokedynamic accumulator counters are
  zero for TEST while source generation shows the other generic concat lowering
  emits `neko_concat_append_inline(...)` followed by immediate `PUSH_O` in both
  literal and non-literal second-argument branches.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh default TEST/obfusjack smoke proving
  `handle.audit.build=false` artifacts emit no fast-path audit stats, fresh
  opt-in TEST/obfusjack runtime with `NEKO_DIRECT_DEBUG=1` showing the
  StringBuilder fast-path counters, generated-C/manifest inspection for
  `NEKO_HANDLE_AUDIT` gating, and forbidden-JNI/JVMTI/fallback grep.
- Completion criteria: opt-in counters reconcile with the NPT-3ca/NPT-3cb
  `V:L:L` traffic enough to identify whether the immediate StringBuilder concat
  fast path is the next generic optimization boundary; default artifacts have no
  behavior change; TEST and obfusjack complete without fatal/error output; the
  recorded evidence either justifies or rejects optimizing that boundary.
- Completed 2026-05-22: added `NEKO_HANDLE_AUDIT`-gated StringBuilder
  fast-concat counters in both recognized `NativeTranslator` branches. Focused
  `CCodeGeneratorTest` and `NativeGeneratedCHotPathAuditTest` passed. Fresh
  default artifacts: TEST `build/neko-native-work/run-34233855782829`
  (`translated=49 rejected=0`, `handle.audit.build=false`, lib `1084344`) and
  obfusjack `run-34238244839435` (`translated=93 rejected=0`,
  `handle.audit.build=false`, lib `1876824`). Default five-run medians were TEST
  Calc `73ms`, obfusjack Platform `47ms`, Virtual `41ms`, Seq `17ms`; default
  benchmark stderr logs emitted no `[neko-direct]` audit rows, and strict
  generated-C grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` returned no
  matches. Fresh opt-in artifacts: TEST `run-34356662706187`
  (`handle.audit.build=true`, lib `1132648`) and obfusjack
  `run-34361066685190` (`handle.audit.build=true`, lib `1958600`). Direct
  opt-in TEST runtime completed with `V:L:L=510002`, `njx_return=510004`,
  concat-continuation zero, and `stringbuilder-fast-concat: total=510000
  literal=510000 dynamic=0`. Direct opt-in obfusjack runtime completed with
  full program output, `njx_return=301141`, concat-continuation
  `accumulate_total=137 accumulate_njx=89 final_push=48`, and
  `stringbuilder-fast-concat: total=0 literal=0 dynamic=0`. Conclusion:
  immediate StringBuilder fast concat accounts for all but two of TEST's
  dominant `V:L:L` object returns and is the next justified generic optimization
  boundary; obfusjack remains dominated by other object-return and direct-handle
  origins.

### [x] NPT-3cd: P10 raw-return local-root publication for immediate StringBuilder concat stores

- Scope: optimize only the recognized StringBuilder fast-concat path when its
  returned object is consumed by the immediately following `ASTORE`. The change
  may add a raw-oop NJX return helper for the already-resolved `V:L:L` call-stub
  shape and a local-root raw store helper, then fuse same-basic-block immediate
  `ASTORE` consumers. It must still call the original JVM `String.concat`
  Method*/entry, must not construct string payloads natively, must not bypass
  local-root publication, must not change non-immediate consumers, and must not
  alter non-StringBuilder paths.
- Required evidence: NPT-3cc proved TEST executes the immediate StringBuilder
  fast-concat path `510000` times against `V:L:L=510002`. Fresh generated C for
  the current artifact shows the exact sequence `neko_concat_append_inline(...)`,
  `PUSH_O(__fastConcat)`, then `{ jobject __ref = POP_O(); locals[N].o =
  neko_store_local_oop_ref(...) }`; source evidence shows
  `neko_store_local_oop_ref` resolves the just-created NJX handle back to a raw
  oop before publishing the existing local root.
- Validation command or runtime target: focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest`, fresh TEST/obfusjack generation, generated-C
  inspection proving the hot immediate-`ASTORE` path emits raw-return local-root
  publication and no `PUSH_O(__fastConcat)`/`POP_O` pair while non-immediate paths
  keep the normal handle path, strict generated-C grep for forbidden JNI wrappers,
  TEST and obfusjack runtime, and performance gate.
- Completion criteria: pending-exception dispatch is still driven by the original
  concat/toString potentially-excepting site; local-root stores still publish to
  `__neko_local_roots[N]`; TEST and obfusjack complete without fatal/error output;
  TEST Calc median improves or does not regress versus the NPT-3cc default median
  while obfusjack Platform/Virtual/Seq do not regress.
- Completed 2026-05-22: added a raw-oop variant for the registered `V:L:L` NJX
  return shape and fused only same-basic-block immediate StringBuilder
  concat-to-`ASTORE` consumers into `neko_concat_append_inline_store_local(...)`.
  The helper still calls the resolved `String.concat` Method*/entry and publishes
  through `__neko_local_roots[N]`; non-immediate consumers keep the normal
  handle-return path.
- Fresh validation: focused `OpcodeTranslatorUnitTest`
  (`stringBuilderConcatImmediateAstoreUsesRawLocalPublication` and existing
  StringBuilder concat test), `CCodeGeneratorTest`, and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh default perf capture passed
  with TEST artifact `run-35239467010830` and obfusjack artifact
  `run-35243935226564`; generated C shows the hot loop now emits
  `neko_concat_append_inline_store_local(thread, env, &__neko_local_roots[0], ...)`
  with no `PUSH_O(__fastConcat)`/`POP_O` pair on that path. Strict generated-C
  grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was empty. Median timings:
  TEST Calc `63 ms`; obfusjack Platform `45 ms`, Virtual `37 ms`, Seq `17 ms`,
  Parallel `1 ms`, VThreads `1 ms`. This improves TEST versus the NPT-3cc default
  median `73 ms` and does not regress obfusjack versus NPT-3cc defaults
  (`47/41/17 ms` for Platform/Virtual/Seq).

### [x] NPT-3ce: P10 post-NPT-3cd audit gate correction

- Scope: verify whether the post-NPT-3cd opt-in audit silence is an audit-gate
  defect before editing runtime code. Default builds must remain unchanged.
- Required evidence: fresh post-NPT-3cd opt-in perf capture generated TEST
  artifact `run-35475737821910` with `handle.audit.build=true`, and generated C
  still emits `NEKO_HANDLE_AUDIT_HIT(g_neko_stringbuilder_concat_fast_total_count)`
  in the hot immediate-`ASTORE` StringBuilder concat loop. The perf-capture
  stderr logs were empty because that harness did not set `NEKO_DIRECT_DEBUG`,
  not because the StringBuilder audit gate was dead.
- Completed 2026-05-22 with no executable change: direct opt-in/debug TEST
  runtime completed and printed `V:L:L=510002`, `dispatched=510061`,
  `handle_direct_total=54`, `njx_return=4`, concat-continuation zero, and
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Direct
  opt-in/debug obfusjack completed and printed `dispatched=976247`,
  `handle_direct_total=854741`, `njx_return=301141`, concat-continuation
  `accumulate_total=137 accumulate_njx=89 final_push=48 intermediate_candidate=41`,
  and `stringbuilder-fast-concat: total=0 literal=0 dynamic=0`. No audit code
  change is justified; this proves the remaining TEST hot boundary is still
  510000 `V:L:L` `String.concat` call-stub dispatches with raw local publication,
  while obfusjack remains dominated by other NJX/direct-handle paths.

### [x] NPT-3au: Split translated direct-call body entry

- Scope: reduce translated-to-translated direct-call raw-entry overhead by
  splitting generated raw methods into an external ABI wrapper and an internal
  body entry. The body entry may skip only the runtime capability checks already
  proven by the caller wrapper/dispatcher; it must preserve all Java-level method
  activation behavior.
- Required evidence: direct-call audit shows `OpcodeTranslator.translateDirectInvoke`
  calls `binding.rawFunctionName()` directly, while every generated raw function
  starts with `neko_hotspot_fast_require(thread, env)`. Fresh NPT-3at TEST C
  still shows `Calc.runAll` calling `Calc.call`, `Calc.runAdd`, and `Calc.runStr`
  in the 10,000-iteration loop, with each callee rerunning the same fast gate.
  `neko_hotspot_fast_require` checks only post-bootstrap fast-state invariants
  (`neko_const_initialized`, non-null `thread`, and supported GC barrier kind)
  and then emits compiler assumptions; callee roots and shadow frames remain
  separate required semantics.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving external wrappers still
  call `neko_hotspot_fast_require` and internal direct calls target body symbols,
  default TEST smoke, same-session timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: manifest/dispatcher ABI continues to target
  `neko_native_impl_*` wrappers; translated internal direct calls target
  `neko_native_impl_*_body`; body entries keep activation arrays, object-local
  roots where required, parameter-to-local rooting, and shadow push/pop; no
  JNI/JVMTI/fallback markers or fatal runtime errors appear; timing does not
  regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. First generated TEST runtime failed with
  `UnsatisfiedLinkError: undefined symbol: neko_native_impl_19_body`, proving the
  exact split-source invariant violation: `_body` declarations were static in
  implementation preludes while callers were in different translation units. The
  generic fix externalizes `_body` prototypes/definitions alongside raw wrappers.
  Fresh generation `build/neko-native-work/run-16584698339661` built
  `libneko_linux_x64.so` at `1034872` bytes with `translated=49 rejected=0`.
  Generated C shows external wrappers call `neko_hotspot_fast_require`, bodies
  call `neko_hotspot_fast_assume`, and `Calc.runAll` calls
  `neko_native_impl_20_body`, `neko_native_impl_21_body`, and
  `neko_native_impl_22_body`. Same-session smoke comparison passed with stderr
  empty: NPT-3at `86,87,90,86,91 ms` (median `87ms`) versus NPT-3au
  `83,86,84,83,96 ms` (median `84ms`). Focused native integration tests for
  TEST Calc and obfusjack completion also passed.

### [rejected] NPT-3av: Fast static primitive field refs after class init

- Scope: reduce repeated static primitive field get/put metadata work by using
  the existing `neko_static_field_ref` slots directly after class initialization
  and direct field metadata are already proven available. This must remain a
  generic helper/translator change for primitive static fields only, not a
  benchmark or field-name special case.
- Required evidence: fresh NPT-3au generated C shows the translated TEST Calc
  loop calls `neko_fast_get_static_I_field_ref(env, &g_static_field_ref_3)` and
  emits static puts as `neko_bound_class_ref`, `neko_ensure_class_initialized_once`,
  `neko_bound_field_ref`, then `neko_fast_set_static_I_field` on each looped
  translated call. Current helper source resolves the class and field inside
  every `neko_fast_get_static_*_field_ref`, even though bind support records
  `class_init_slot`, `static_base_slot`, `static_offset_slot`, and
  `access_flags_slot`; `neko_bind_static_field_metadata` sets base/offset/access
  from the resolved field metadata and hard-aborts on invalid static metadata.
- Validation command or runtime target: focused translator/generator/audit tests,
  fresh TEST native generation, generated-C inspection proving static primitive
  gets and puts use ref helpers and the helper fast path checks initialized
  class plus direct metadata before direct memory access, same-session TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: static field class initialization semantics remain
  preserved because uninitialized or incomplete refs still route through the
  existing class-init/bind slow path; missing direct metadata still aborts, not
  falls back; object static field helpers are unchanged; no JNI/JVMTI/fallback
  markers are introduced; timing does not regress.
- Rejected 2026-05-22. Focused generator/audit tests passed, and fresh TEST
  generation `build/neko-native-work/run-17090115800257` built
  `libneko_linux_x64.so` at `1037336` bytes with `translated=49 rejected=0`.
  Generated C showed `Calc.call`, `Calc.runAdd`, and `Calc.runStr` using
  `neko_fast_set_static_I_field_ref` and no per-site
  `neko_ensure_class_initialized_once`/`neko_bound_field_ref` sequence for the
  static primitive put. Runtime smoke was functionally green, but same-session
  timing regressed versus NPT-3au: NPT-3au `84,84,83,88,85 ms` (median `84ms`)
  versus NPT-3av `87,83,92,93,86 ms` (median `87ms`). A tightened inline-direct
  variant in `build/neko-native-work/run-17205237824086` also passed
  generation (`translated=49 rejected=0`) but regressed: NPT-3au
  `85,83,85,85,88 ms` (median `85ms`) versus NPT-3av
  `87,87,86,89,84 ms` (median `87ms`). Source/test edits were reverted; do not
  retry this static-field-ref shape without new code-size or branch-layout
  evidence.

### [x] NPT-3aw: Elide translated monitor storage for no-monitor methods

- Scope: avoid allocating translated monitor state in generated raw bodies whose
  bytecode contains no `MONITORENTER` or `MONITOREXIT`. This is a structural
  bytecode invariant, not an owner/method/benchmark special case.
- Required evidence: fresh NPT-3au generated TEST C emits
  `neko_monitor_record monitors[...]` and `int monitor_sp = 0` in every raw body,
  including `Calc.call`, `Calc.runAdd`, and `Calc.runStr`, while generated TEST
  contains no `neko_fast_monitor_enter` or `neko_fast_monitor_exit` use outside
  helper definitions. `OpcodeTranslator` is the only translated opcode path that
  references `monitors`/`monitor_sp`, and it does so only for monitor bytecodes.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving no-monitor methods omit
  `monitors`/`monitor_sp` while monitor-bytecode methods retain them, default
  TEST smoke/timing comparison, and focused native integration tests for TEST
  Calc and obfusjack completion.
- Completion criteria: no monitor-bytecode method loses monitor storage; no
  monitor helper call refers to undeclared storage; synchronized method JVM
  entry semantics are unchanged because this substep only controls translated
  explicit monitor opcode storage; no JNI/JVMTI/fallback markers are introduced;
  timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-17599946034860` built `libneko_linux_x64.so` at
  `1034872` bytes with `translated=49 rejected=0`. Generated C inspection showed
  `Calc.runAll`, `Calc.call`, and `Calc.runAdd` bodies no longer declare
  `neko_monitor_record monitors[...]` or `int monitor_sp = 0`, and `rg` found no
  generated TEST body monitor helper call sites. Same-session smoke comparison
  passed with stderr empty: NPT-3au `94,91,84,85,87 ms` (median `87ms`) versus
  NPT-3aw `84,84,84,87,86 ms` (median `84ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bg: Fuse primitive constant local stores

- Scope: fuse only adjacent same-basic-block primitive constant producers
  (`ICONST_*`, `BIPUSH`, `SIPUSH`, `LCONST_*`, `FCONST_*`, `DCONST_*`, and
  numeric `LDC`) followed immediately by a matching primitive local store
  (`ISTORE`, `LSTORE`, `FSTORE`, or `DSTORE`). The fused C must assign the same
  literal expression directly to the target local slot. It must not cross
  labels, fold object/reference stores, fold non-constant producers, change
  local indexes, or alter exception dispatch boundaries.
- Required evidence: fresh NPT-3bf generated TEST C at
  `build/neko-native-work/run-21337326266905/neko_native_impl_1.c`,
  `neko_native_impl_19.c`, and `neko_native_impl_21.c` shows hot methods still
  initialize primitive locals through constant `PUSH_*` followed immediately by
  `locals[n].* = POP_*()`. These are primitive non-throwing stack round trips.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving primitive
  constant local stores use direct assignments with no constant push/pop round
  trip, default TEST smoke/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: only primitive constant local stores fuse; label-blocked,
  type-mismatched, and non-constant cases retain stack traffic; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-21666360179964` built `libneko_linux_x64.so` at
  `1034760` bytes with `translated=49 rejected=0`. Generated C inspection
  showed hot primitive initializers in `Digi.run`, `Calc.runAll`, and
  `Calc.runAdd` use direct local assignments such as `locals[1].d = 0.0`,
  `locals[3].i = 0`, and `locals[4].f = 1.1f`. Static grep found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating TEST smoke comparison passed with empty
  stderr: NPT-3bf `90,88,86,93,87,88,94 ms` (median `88ms`) versus NPT-3bg
  `91,84,91,88,89,88,87 ms` (median `88ms`), with library size reduced from
  `1034808` to `1034760` bytes. Focused native integration tests for TEST Calc
  and obfusjack completion also passed.

### [x] NPT-3bh: Fuse single int producers into static translated direct calls

- Scope: fuse only adjacent same-basic-block pure `int` producers
  (`ICONST_*`, `BIPUSH`, `SIPUSH`, or `ILOAD`) feeding an immediate
  `INVOKESTATIC` whose target is already translated and whose descriptor has
  exactly one `int` argument. The fused C must call the existing translated
  direct-call body with the producer expression as the argument and must reuse
  the existing direct-call target class/guard/result handling. It must not cross
  labels, fold non-static calls, fold NJX or virtual/interface dispatch, fold
  multi-argument calls, fold non-int arguments, or alter pending-exception
  coalescing.
- Required evidence: fresh NPT-3bg generated TEST C at
  `build/neko-native-work/run-21666360179964/neko_native_impl_19.c` shows the
  hot `Calc.runAll` loop still materializes the constant argument to translated
  static `Calc.call(I)V` through `PUSH_I(100)` and immediate `POP_I()` inside
  the direct translated call wrapper.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the
  direct translated call receives the int expression directly without
  argument-stack push/pop, default TEST smoke/timing comparison, and focused
  native integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only one-int-argument static translated direct calls
  fuse; label-blocked, non-static, non-translated, multi-argument, and non-int
  cases retain stack traffic; no JNI/JVMTI/fallback markers are introduced;
  timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-22094701424303` built `libneko_linux_x64.so` at
  `1034712` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.runAll` passes `(jint)(100)` directly to
  `neko_native_impl_20_body` without argument push/pop, while other dispatch
  forms remain unchanged. Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`,
  or `env->` markers in the generated work directory. Same-session alternating
  TEST smoke comparison passed with empty stderr: NPT-3bg
  `84,85,88,88,90,84,90 ms` (median `88ms`) versus NPT-3bh
  `83,86,84,91,87,88,87 ms` (median `87ms`), with library size reduced from
  `1034760` to `1034712` bytes. Focused native integration tests for TEST Calc
  and obfusjack completion also passed.

### [x] NPT-3bf: Fuse primitive float/double same-local add updates

- Scope: fuse only adjacent same-basic-block `FLOAD` or `DLOAD` of a local,
  followed by a matching pure float/double constant producer, `FADD` or `DADD`,
  and `FSTORE` or `DSTORE` back to the same local. The fused C must assign the
  same arithmetic expression directly to the local slot and must preserve the
  existing fallback C arithmetic shape. It must not cross labels, fold
  subtraction/multiplication/division/remainder, fold non-constant RHS values,
  change local indexes, touch long/int/object/ref locals, or alter exception
  dispatch boundaries.
- Required evidence: fresh NPT-3be generated TEST C at
  `build/neko-native-work/run-21034784798896/neko_native_impl_1.c` and
  `neko_native_impl_21.c` shows hot loops increment float/double locals through
  `PUSH_F`/`PUSH_D`, `FADD`/`DADD`, then immediate `FSTORE`/`DSTORE` to the
  same local. These instructions are primitive, non-throwing stack round trips.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving same-local
  float/double add updates use direct local assignments with no add-result
  stack round trip, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-local float/double constant add updates fuse;
  label-blocked and different-local cases retain stack traffic; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-21337326266905` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed hot float/double loop increments in `Digi.run` and `Calc.runAdd` use
  direct same-local assignments such as `locals[4].f = locals[4].f + 1.3f` and
  `locals[0].d = locals[0].d + 0.99`. Static grep found no `NEKO_JNI_FN_PTR`,
  `(*env)->`, or `env->` markers in the generated work directory. Same-session
  alternating TEST smoke comparison passed with empty stderr: NPT-3be
  `83,88,84,84,89,88,87 ms` (median `87ms`) versus NPT-3bf
  `93,85,92,86,85,87,85 ms` (median `86ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3ax: Elide unreferenced exception-exit blocks

- Scope: omit the generated `__neko_exception_exit` label and default return
  tail only for translated methods whose generated body contains no structural
  branch/reference to that label. This is a generic generated-body invariant,
  not an owner/name/benchmark special case.
- Required evidence: fresh NPT-3aw generated TEST C shows `Calc.call` and
  `Calc.runAdd` still emit `__neko_exception_exit` plus shadow-pop/default
  return despite having no `goto __neko_exception_exit`. Methods such as
  `Calc.runAll` and `Calc.runStr` do contain pending-exception branches to the
  label and must retain the block.
- Validation command or runtime target: focused generator/audit tests, fresh
  TEST native generation, generated-C inspection proving no-reference methods
  omit the exception-exit block and referenced methods retain it, default TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: every generated `goto __neko_exception_exit` still has a
  matching label; methods without a generated reference omit only unreachable
  cleanup; normal return paths and shadow pops remain unchanged; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-17954422278482` built `libneko_linux_x64.so` at
  `1034872` bytes with `translated=49 rejected=0`. Generated C inspection showed
  `Calc.call` and `Calc.runAdd` omit `__neko_exception_exit`, while `Calc.runAll`
  and `Calc.runStr` keep the label for real pending-exception branches.
  Same-session smoke comparison passed with stderr empty: NPT-3aw
  `92,90,89,94,91 ms` (median `91ms`) versus NPT-3ax
  `89,85,90,92,89 ms` (median `89ms`). Focused native integration tests for
  TEST Calc and obfusjack completion also passed.

### [x] NPT-3ay: Elide same-owner static direct-call class guards

- Scope: remove the generated `targetCls` local and null guard only for
  translated direct `INVOKESTATIC` calls whose callee owner is the current
  translated owner. Keep cross-owner static calls, instance calls, pending
  exception checks, direct body dispatch, and class binding behavior unchanged.
- Required evidence: fresh NPT-3ax generated TEST C shows same-owner static
  direct calls in `Calc.runAll` materialize `jclass targetCls = (jclass)clazz`
  and then guard `if (targetCls != NULL)` before calling `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` bodies. The JVM native entry ABI supplies a
  non-null current `clazz`, and same-owner direct body calls already propagate
  that current class.
- Validation command or runtime target: focused translator/generator tests,
  fresh TEST native generation, generated-C inspection proving same-owner static
  direct calls pass `(jclass)clazz` directly with no `targetCls != NULL` guard,
  generated-C inspection proving cross-owner/instance direct call guard shape is
  unchanged, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: the optimization is owner/ABI driven rather than
  benchmark driven; no null guard is removed from cross-owner or instance
  direct calls; every post-call pending-exception check remains in place; no
  JNI/JVMTI/fallback markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-18549458339348` built `libneko_linux_x64.so` at
  `1035160` bytes with `translated=49 rejected=0`. Generated C inspection showed
  `Calc.runAll` same-owner static direct calls now invoke `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` bodies with `(jclass)clazz` directly, while
  cross-owner static and instance direct calls still retain `targetCls` guards.
  Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the
  generated work directory. Same-session alternating smoke comparison passed
  with empty stderr and equal medians: NPT-3ax
  `93,88,89,84,85,89,96 ms` (median `89ms`) versus NPT-3ay
  `88,89,98,85,101,89,85 ms` (median `89ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3az: Fuse primitive integer branch producers

- Scope: fuse only adjacent same-basic-block primitive integer branch producer
  sequences in `NativeTranslator`: `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`
  followed by `IFEQ`/`IFNE`/`IFLT`/`IFGE`/`IFGT`/`IFLE`, and two such
  producers followed by `IF_ICMP*`. The fused C branch must read the primitive
  expression directly and avoid stack mutation. It must not cross labels,
  branches, try-handler boundary flushes, pending-exception dispatch, fields,
  arrays, invokes, monitors, object/ref stack operations, arithmetic, division,
  floating-point comparisons, or helper calls.
- Required evidence: fresh NPT-3ax/NPT-3ay generated TEST C contains generic
  straight-line integer branch stack round trips such as `PUSH_I(local);
  if (POP_I() != 0)` and `PUSH_I(local); PUSH_I(const); POP/POP; if (...)`.
  Source inspection shows `OpcodeTranslator.intPushExpression` already
  recognizes only primitive constants and `ILOAD`, while the central
  `NativeTranslator` bytecode walk is where multi-instruction peepholes can
  consume the branch bytecode and preserve pending-exception flush ordering.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the known
  integer branch round trips are replaced without touching object/field/invoke
  paths, default TEST smoke/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: branch targets and comparison semantics match JVM
  bytecode; fused producers are primitive-only and non-throwing; no label or
  handler boundary is crossed; pending-exception checks still flush before the
  branch when required; no JNI/JVMTI/fallback markers are introduced; timing
  does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-19020205937571` built `libneko_linux_x64.so` at
  `1034840` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.runAll` and `Digi.run` loop guards use direct primitive compare
  C, and `Tracee.toTrace` uses `if (locals[1].i != 0)` without stack mutation;
  object/field/invoke paths remained outside the fused slice. Static grep found
  no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating smoke comparison passed with empty stderr:
  NPT-3ay `89,83,83,88,88,85,91 ms` (median `88ms`) versus NPT-3az
  `87,82,89,84,105,88,85 ms` (median `87ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3ba: Fuse same-field static int add updates

- Scope: fuse only adjacent same-basic-block `GETSTATIC int`, primitive int
  constant producer, `IADD`, and matching `PUTSTATIC int` for the same owner,
  field name, and descriptor. The fused C must still call the existing
  `neko_fast_get_static_I_field_ref`, `neko_bound_class_ref`,
  `neko_ensure_class_initialized_once`, `neko_bound_field_ref`, and
  `neko_fast_set_static_I_field` helpers in the same relative order as the
  unfused sequence; it may only replace stack traffic with a primitive local
  value expression. It must not change static field-ref helper layout, static
  base/offset binding, class initialization, object/reference fields, instance
  fields, non-int primitives, subtraction/division/arithmetic in general,
  labels, try-handler boundary flushes, pending-exception dispatch, invokes,
  arrays, monitors, or helper fallback behavior.
- Required evidence: fresh NPT-3az generated TEST C shows `Calc.call`,
  `Calc.runAdd`, and `Calc.runStr` all execute `GETSTATIC Calc.errors`, push
  `1`, perform `IADD`, pop into `PUTSTATIC Calc.errors`, and then emit the
  existing static set helper sequence. This is a generic same-field update
  stack round trip and does not require changing the previously rejected
  static primitive field-ref helper shape.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the Calc
  static int updates use one primitive local and the same get/class-init/set
  helpers, default TEST smoke/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-field static int add updates fuse; all helper
  calls and class-init ordering are retained; no object/reference/instance
  field path changes; no JNI/JVMTI/fallback markers are introduced; timing does
  not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-19381987030477` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.call`, `Calc.runAdd`, and `Calc.runStr` static int updates use a
  single primitive `val` expression while retaining the existing static get,
  class bind/init, field bind, and static set helpers. Static grep found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating smoke comparison passed with empty stderr:
  NPT-3az `86,85,89,95,90,82,89 ms` (median `89ms`) versus NPT-3ba
  `83,87,94,89,85,101,88 ms` (median `88ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bb: Reject direct pending-exception reads for opcode result guards

- Scope: replace translated opcode result/yield guards of the form
  `!neko_exception_check(env)` with `neko_pending_exception_oop(thread) == NULL`
  only where `thread` is already in scope and the guard is preserving a result
  after a native/JVM call. This keeps the exception check and changes only the
  access path from `env -> JavaThread -> _pending_exception` to the existing
  direct `thread -> _pending_exception` read. Do not change the CHECKCAST guard,
  exception dispatch checks, static primitive field paths, shadow frames, JNI
  bootstrap, helper fallback behavior, or exception timing.
- Required evidence: fresh NPT-3az generated TEST C contains many
  `if (!neko_exception_check(env)) { PUSH_*... }` guards after icache, NJX,
  direct translated body, MethodHandle bridge, intrinsic, and string-switch
  calls, while the direct hard-aborting helper `neko_pending_exception_oop` is
  already emitted and used throughout translated methods for control-flow
  exception dispatch.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving call
  result guards no longer use `neko_exception_check(env)` and CHECKCAST remains
  unchanged, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: every changed guard still reads pending exception before
  consuming the result; CHECKCAST and exception-dispatch semantics remain
  unchanged; no JNI/JVMTI/fallback markers are introduced; timing does not
  regress.
- Rejected 2026-05-22. The implementation changed only `OpcodeTranslator`
  result/yield guard predicates and left CHECKCAST unchanged. Focused
  generator/audit tests passed, and fresh TEST generation
  `build/neko-native-work/run-19677117143362` built `libneko_linux_x64.so` at
  `1038488` bytes with `translated=49 rejected=0`. Generated C inspection
  showed call-result guards no longer used `neko_exception_check(env)`, the only
  remaining impl occurrences were four CHECKCAST guards, and static grep found
  no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers. Runtime smoke stayed
  functional with empty stderr, but performance/code-size evidence rejected the
  slice: first alternating run regressed NPT-3ba
  `85,85,88,96,84,88,94 ms` (median `88ms`) versus NPT-3bb
  `91,91,87,84,87,97,93 ms` (median `91ms`); a second alternating run was equal
  at median `87ms`, but combined samples still skewed slower and the library
  grew from `1034808` to `1038488` bytes. Source edits were reverted. Do not
  retry this broad predicate replacement without new code-size or branch-layout
  evidence.

### [x] NPT-3bc: Fuse primitive int arithmetic returns

- Scope: fuse only adjacent same-basic-block `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`,
  second matching int producer, `IADD` or `IMUL`, and `IRETURN` into a direct
  primitive return expression. The fused C must still execute the normal shadow
  pop before returning and must flush any pending exception dispatch before the
  return. It must not cross labels, try-handler boundary flushes, object/ref
  operations, fields, arrays, invokes, monitors, division/remainder, subtraction
  with operand-order risk, long/float/double arithmetic, or helper calls.
- Required evidence: fresh NPT-3ba generated TEST C shows generic leaf methods
  such as `Abst1.mul(II)I`, `Top.add(II)I`, and `flo.solve(II)I` load two int
  locals, emit `IADD`/`IMUL`, then immediately pop and return the result. This
  is a stack-only round trip in primitive non-throwing arithmetic.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the leaf
  arithmetic returns use direct primitive return expressions, default TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: only primitive int add/mul immediate returns fuse;
  operand order and shadow-pop behavior match the original generated sequence;
  pending-exception flush before return is preserved; no JNI/JVMTI/fallback
  markers are introduced; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-20027566395481` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Abst1.mul`, `Top.add`, and `flo.solve` use direct primitive int
  return expressions with `neko_shadow_pop()` retained and no stack round trip.
  Static grep found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the
  generated work directory. Same-session alternating smoke comparison passed
  with empty stderr: NPT-3ba `87,87,84,85,93,98,92 ms` (median `87ms`) versus
  NPT-3bc `88,85,86,86,84,99,94 ms` (median `86ms`). Focused native integration
  tests for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bd: Fuse primitive int arithmetic into single-arg self tail calls

- Scope: fuse only adjacent same-basic-block `ICONST_*`/`BIPUSH`/`SIPUSH`/`ILOAD`,
  second matching int producer, `IADD`, `ISUB`, or `IMUL`, then a static
  self-recursive tail call for a method with exactly one `int` argument and a
  matching return. The fused C must assign the computed expression directly to
  the restarted local, clear the operand stack, and jump to the existing
  tail-call landing pad. It must not cross labels between the primitive
  producers and the self call. Like the existing tail-call rewrite, labels,
  lines, and frames may appear between the call and the matching return only
  when no intervening real instruction exists. It must not cross
  try-handler-covered call sites, object/ref operations, fields, arrays, invokes
  other than the proven self tail call, monitors, division/remainder,
  long/float/double arithmetic, multi-argument calls, instance receiver calls,
  or helper calls.
- Required evidence: fresh NPT-3bc generated TEST C at
  `build/neko-native-work/run-20027566395481/neko_native_impl_20.c` shows the
  generic static self-recursive `int -> void` tail path evaluating
  `locals[0].i - 1` through `PUSH_I(locals[0].i)`, `PUSH_I(1)`, `ISUB`, then
  immediately popping that value into `locals[0].i` inside the existing
  tail-call rewrite. This is stack-only primitive arithmetic immediately
  consumed by the generic TCO path.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the
  single-arg arithmetic self tail call uses a direct local assignment with the
  existing `sp = 0; goto __neko_tco_entry;`, default TEST smoke/timing
  comparison, and focused native integration tests for TEST Calc and obfusjack
  completion.
- Completion criteria: only one-argument static `int` self tail calls with
  same-block primitive int add/sub/mul argument arithmetic fuse; operand order
  for subtraction matches JVM stack order; call-site active handlers remain
  rejected; no JNI/JVMTI/fallback markers are introduced; timing does not
  regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-20650186041509` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.call(I)V` assigns `locals[0].i - 1` directly in the tail-call
  restart path while preserving the normal `L3` return epilogue. Static grep
  found no `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated
  work directory. Same-session alternating TEST smoke comparison passed with
  empty stderr: NPT-3bc `81,88,99,94,95,90,89 ms` (median `90ms`) versus
  NPT-3bd `88,92,88,90,86,87,95 ms` (median `88ms`). Focused native
  integration tests for TEST Calc and obfusjack completion also passed.

### [x] NPT-3be: Fuse primitive compare results into zero branches

- Scope: fuse only adjacent same-basic-block pure primitive producers feeding
  `LCMP`, `FCMPL`, `FCMPG`, `DCMPL`, or `DCMPG`, followed immediately by an
  `IFEQ`, `IFNE`, `IFLT`, `IFGE`, `IFGT`, or `IFLE` zero-branch. Initial
  producers are limited to primitive local loads and numeric constants that the
  existing translator already lowers as direct literals or local reads. The
  fused C must compute the exact existing compare-result expression into a
  local `jint __cmp` and branch on `__cmp`, preserving `FCMPL`/`FCMPG` and
  `DCMPL`/`DCMPG` NaN polarity. It must not simplify float/double comparisons
  into direct relational branches, cross labels, fold throwing/side-effecting
  operations, or alter exception dispatch boundaries.
- Required evidence: fresh NPT-3bd generated TEST C at
  `build/neko-native-work/run-20650186041509/neko_native_impl_1.c` and
  `neko_native_impl_21.c` shows `FCMPL` and `DCMPG` compare results are pushed
  to the operand stack and immediately popped by zero-branches in hot loop
  paths. The existing fallback compare expressions at `OpcodeTranslator` lines
  for `LCMP`/`FCMPL`/`FCMPG`/`DCMPL`/`DCMPG` provide the exact expression shape
  that must be reused.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving fused
  compare branches use `jint __cmp` without compare-result `PUSH_I`/`POP_I`
  round trips, default TEST smoke/timing comparison, and focused native
  integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-block primitive compare-result zero-branches
  fuse; NaN polarity matches the existing fallback expression; label-blocked
  cases retain stack traffic; no JNI/JVMTI/fallback markers are introduced;
  timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed:
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-21034784798896` built `libneko_linux_x64.so` at
  `1034808` bytes with `translated=49 rejected=0`. Generated C inspection
  showed pure `FCMPL`/`DCMPG` zero-branches use local `jint __cmp` branches in
  `Digi.run` and `Calc.runAdd`, while the mixed `double -> float` conversion
  path remained on the fallback stack form. Static grep found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` markers in the generated work
  directory. Same-session alternating TEST smoke comparison passed with empty
  stderr: NPT-3bd `82,82,90,91,87,89,88 ms` (median `88ms`) versus NPT-3be
  `85,92,87,83,88,93,97 ms` (median `88ms`). Focused native integration tests
  for TEST Calc and obfusjack completion also passed.

### [x] NPT-3bi: Intrinsify `java/lang/String.length()I`

- Scope: lower only exact `INVOKEVIRTUAL java/lang/String.length:()I` sites to
  a generic native helper that pops the receiver, preserves the existing
  null-receiver NPE path, reads the already-bound `String.value:[B` and
  `String.coder:B` offsets, loads the backing byte-array length through the
  existing array length offset, and pushes `value.length >> coder`. The helper
  must hard-abort if String field metadata, object/array layout,
  compressed-oop decoding, or GC load-barrier prerequisites are unavailable. It
  must not apply to other owners, names, descriptors, static/special/interface
  calls, user methods, or virtual dispatch in general; it must not add
  JNI/JVMTI/fallback behavior or bypass the implicit-exception helper.
- Required evidence: fresh NPT-3bh generated TEST C at
  `build/neko-native-work/run-22094701424303/neko_native_impl_22.c` still
  dispatches `java/lang/String.length()I` through `neko_icache_dispatch` inside
  the hot `Calc.runStr` loop. That loop runs about 101 iterations per
  `runStr`, and `runAll` calls `runStr` 10000 times, so the site executes about
  one million virtual dispatches per TEST run. Existing native infrastructure
  already binds `java/lang/String.value:[B` and `java/lang/String.coder:B`
  offsets for string concat/literal support, reads object fields with
  `neko_barrier_load_oop_field`, decodes narrow oops, and reads array lengths
  through `neko_const_array_length_offset()`.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving
  `String.length` sites emit `neko_fast_string_length` and no
  `neko_icache_dispatch` for that call, default TEST smoke/timing comparison,
  focused native integration tests for TEST Calc and obfusjack completion, and
  focused runtime coverage for null, Latin1, UTF16, and concat-produced
  strings.
- Completion criteria: only exact `String.length()I` calls are intrinsified;
  null receivers still raise and route through the normal implicit NPE helper;
  Latin1 and UTF16 length results match JVM semantics; generated C has no
  forbidden JNI wrappers or fallback markers; timing does not regress.
- Completed 2026-05-22. Focused generator/audit tests passed for
  `CCodeGeneratorTest`, `OpcodeTranslatorUnitTest`, and
  `NativeGeneratedCHotPathAuditTest`. Fresh TEST generation
  `build/neko-native-work/run-22968063094268` built `libneko_linux_x64.so` at
  `1036696` bytes with `translated=49 rejected=0`. Generated C inspection
  showed `Calc.runStr` calls `neko_fast_string_length(__str, g_off_10,
  g_off_11)` and no longer uses `neko_icache_dispatch` for
  `java/lang/String.length()I`; null receivers still route through
  `neko_raise_implicit_exception_ref`. Strict forbidden JNI grep for
  `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Same-session
  alternating TEST smoke comparison passed with empty stderr: NPT-3bh
  `85,85,95,106,86,85,88 ms` (median `86ms`) versus NPT-3bi
  `73,69,68,70,85,84,71 ms` (median `71ms`). Focused native integration tests
  for TEST Calc, obfusjack completion, and implicit exception/String length
  runtime coverage passed.

### [x] NPT-3bj: Reject intrinsic `String.length()` compare-branch fusion

- Scope: fuse only adjacent same-basic-block `ALOAD` receiver, exact
  `INVOKEVIRTUAL java/lang/String.length:()I`, pure int constant/local
  producer, and `IF_ICMP*` branch. The fused C must preserve the exact
  null-receiver implicit NPE path, check pending exception before evaluating
  the branch, and compare the computed length directly against the second int
  operand without materializing the result through the operand stack. The first
  implementation is restricted to call sites with no active try handlers so a
  null receiver can keep the existing `__neko_exception_exit` route; try/catch
  covered sites must retain the unfused opcode sequence. It must not fuse other
  methods, other owners, non-virtual calls, object/reference compares,
  label-crossing patterns, concat paths, or any path where exception handler
  routing is not proven identical.
- Required evidence: fresh NPT-3bi generated TEST C at
  `build/neko-native-work/run-22968063094268/neko_native_impl_22.c` shows
  `Calc.runStr` now emits `neko_fast_string_length` for the hot
  `String.length` call, but still pushes the result, pushes constant `101`,
  checks pending exception, then pops both ints in `IF_ICMPGE`. This stack
  round trip executes once per append-loop iteration after the accepted length
  intrinsic.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving hot
  `String.length` compare branches use a direct local length compare with
  pending exception check retained, default TEST smoke/timing comparison, and
  focused native integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only same-block no-active-handler exact `String.length`
  int-compare branches fuse; null receiver behavior and pending-exception
  ordering remain identical; try/catch-covered cases stay unfused; generated C
  has no forbidden JNI wrappers or fallback markers; timing does not regress.
- Rejected 2026-05-22. The implementation tested only the no-active-handler
  same-block `String.length` plus `IF_ICMP*` branch shape. It preserved the
  normal implicit NPE helper and emitted an explicit pending exception check
  before the fused branch. Focused generator/audit tests passed, and fresh TEST
  generation `build/neko-native-work/run-23558603672735` built
  `libneko_linux_x64.so` at `1036744` bytes with `translated=49 rejected=0`.
  Generated C inspection showed `Calc.runStr` used a direct `__len` local and
  no longer emitted `PUSH_I(neko_fast_string_length(...))` or `PUSH_I(101)`;
  strict forbidden JNI grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was
  clean. Runtime stayed functional, but same-session alternating TEST timing
  rejected the slice: NPT-3bi `69,72,77,71,74,72,77 ms` (median `72ms`) versus
  NPT-3bj `73,72,77,87,74,72,75 ms` (median `74ms`), and the native library
  grew from `1036696` to `1036744` bytes. Source/test edits were reverted. Do
  not retry this branch-fusion shape without new code-size or branch-layout
  evidence.

### [x] NPT-3bk: Reject guarded generated concat literal string binding

- Scope: when native string-concat pattern emission creates a generated static
  `jstring` literal slot, emit a slot-null guard around the existing
  `neko_bind_string_slot(thread, env, &slot, "...")` call. The change may only
  affect generated concat literal slots created by `literalStringProducer`; it
  must not change owner string binding, intern/global-ref creation,
  `neko_concat_append`, `StringConcatFactory` recipe lowering, raw string
  construction, JNI usage, exception behavior, or GC barriers.
- Required evidence: fresh NPT-3bi generated TEST C at
  `build/neko-native-work/run-22968063094268/neko_native_impl_22.c` shows the
  hot `Calc.runStr` loop executes `neko_bind_string_slot(thread, env,
  &__neko_concat_lit_0, "ax")` on every concat iteration before
  `neko_concat_append`. The helper itself returns immediately when `slot !=
  NULL && *slot != NULL` (`NativeBindSupportEmitter`), so the generated guard
  is the same predicate moved to the call site. The helper already writes a
  global reference into the static slot, and concurrent first binds are already
  governed by the helper's unsynchronized slot check; adding the same outer
  check must not introduce a new race class.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving concat
  literal sites emit `if (__neko_concat_lit_N == NULL)
  neko_bind_string_slot(...)` and no hot unconditional bind call, strict
  forbidden JNI/fallback grep, default TEST smoke/timing comparison, and
  focused native integration tests for TEST Calc and obfusjack completion.
- Completion criteria: only generated concat literal slots gain the guard;
  first-use bind still hard-aborts on missing thread/env/global-ref failure;
  subsequent uses reuse the same global slot; generated C has no forbidden
  JNI/JVMTI/fallback markers; timing does not regress.
- Rejected 2026-05-22. Focused generator/audit tests passed and fresh TEST
  generation `build/neko-native-work/run-23910172237245` built
  `libneko_linux_x64.so` at `1036712` bytes with `translated=49 rejected=0`.
  Generated C inspection showed the intended
  `if (__neko_concat_lit_0 == NULL) { neko_bind_string_slot(...) }` shape in
  `Calc.runStr`, and strict JNI marker grep for `NEKO_JNI_FN_PTR`, `(*env)->`,
  and `env->` was clean. The slice was rejected before timing acceptance
  because the generated outer guard performs a plain non-atomic read of a
  function-local static `jstring` while `neko_bind_string_slot` publishes the
  slot through a plain non-atomic write. Concurrent first use would therefore
  keep the existing duplicate-bind race and add a C data-race read in generated
  hot code. Source/test edits were reverted. Do not retry local static concat
  literal slot guarding without atomic publication and losing-global-ref
  cleanup evidence.

### [x] NPT-3bl: Reject exact integer `java/lang/Math.min` intrinsic

- Scope: lower only exact `INVOKESTATIC java/lang/Math.min:(II)I` and
  `INVOKESTATIC java/lang/Math.min:(JJ)J` bytecode sites to direct primitive C
  comparisons after popping both operands in JVM argument order. The change must
  not affect `Math.max`, float/double overloads, non-JDK owners, translated
  application methods, method handles/reflection, class initialization,
  exception paths, object/GC behavior, or NJX in general.
- Required evidence: fresh generated obfusjack native C at
  `build/neko-native-work/run-23052048100862/neko_native_impl_48.c:29` and
  `neko_native_impl_65.c:26` shows exact `Math.min(II)I` sites still dispatch
  through `neko_njx_S_I_II`; `neko_native_impl_55.c:55` shows exact
  `Math.min(JJ)J` still dispatches through `neko_njx_S_J_JJ`. These wrappers
  build argument arrays, install/restore Java handle blocks, check thread
  state, call through the guarded call stub, and marshal results for a pure
  integer operation. Java `Math.min` for `int` and `long` is exactly
  `a <= b ? a : b`; restricting the slice to integer overloads avoids
  floating-point NaN and signed-zero semantics.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh obfusjack native generation, generated-C inspection proving the
  exact `Math.min(II)I` and `(JJ)J` sites emit direct `PUSH_I`/`PUSH_L`
  compares and no `neko_njx_*` call for those sites, strict forbidden JNI grep,
  obfusjack native runtime/timing comparison, and focused native integration
  tests for TEST Calc and obfusjack completion.
- Completion criteria: only exact integer `Math.min` overloads are lowered;
  operand order and signed comparison semantics match Java; no pending
  exception check is introduced for the pure operation; generated C has no
  forbidden JNI/JVMTI/fallback markers; obfusjack timing does not regress.
- Implementation note 2026-05-22: the first generated form kept a call to
  `neko_ensure_class_initialized_once` at every lowered `Math.min` site.
  Same-session obfusjack timing rejected that shape because the steady-state
  helper call offset the NJX removal. The next implementation keeps the same
  first-use class initialization, but emits the existing initialized-slot check
  in generated code so the hot path enters the helper only while the
  `java/lang/Math` slot is not initialized.
- Rejected 2026-05-22. Focused generator/audit tests passed. Fresh obfusjack
  generation `build/neko-native-work/run-24493430020950` built
  `libneko_linux_x64.so` at `1804984` bytes with `translated=93 rejected=0`.
  Generated C inspection proved the target sites emitted direct primitive
  compares with a cold `g_cls_initialized_26` guard:
  `neko_native_impl_48.c:29` and `neko_native_impl_65.c:26` for
  `Math.min(II)I`, and `neko_native_impl_55.c:55` for `Math.min(JJ)J`. Strict
  grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Runtime
  rejected the slice: one NPT-3bl obfusjack run dumped core before completion,
  and the completed same-session alternating runs regressed against the cached
  pre-change obfusjack jar. Baseline Platform `44,45,46,46,45 ms` (median
  `45ms`) versus NPT-3bl completed Platform `52,46,47,47 ms` (median
  `47ms`); baseline Virtual `33,32,34,49,32 ms` (median `33ms`) versus NPT-3bl
  completed Virtual `37,43,34,38 ms` (median `37.5ms`); baseline Seq
  `16,16,16,18,18 ms` (median `16ms`) versus NPT-3bl completed Seq
  `17,17,17,17 ms` (median `17ms`). Source/test edits were reverted. Do not
  retry this exact `Math.min` intrinsic shape without new evidence explaining
  the crash and branch/code-layout regression.

### [x] NPT-3bm: Reject owner-bound strings for StringBuilder concat literals

- Scope: for the existing native StringBuilder concat pattern, replace
  generated function-local static literal slots with the same owner-bound
  string cache expression used by normal LDC and indy concat literals. The
  change may only affect literal producers inside the already-recognized
  StringBuilder concat pattern. It must not change string construction,
  `neko_concat_append`, raw concat allocation, owner binding semantics,
  non-literal producers, StringConcatFactory lowering, JNI usage, exception
  behavior, or GC barriers.
- Required evidence: fresh NPT-3bi TEST generated C shows `Calc.runStr` calls
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
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving the hot
  concat literal uses `neko_bound_string(thread, env, &g_str_N, "ax")`, the
  owner bind function contains `"ax"`, and no `__neko_concat_lit_N` local
  static remains for that pattern, strict forbidden JNI grep, default TEST
  smoke/timing comparison, and focused native integration tests for TEST Calc
  and obfusjack completion.
- Completion criteria: only StringBuilder concat literal producers switch to
  owner-bound string slots; first-use/global-ref binding stays centralized in
  the existing owner-string binder; no new static local publication path is
  introduced; generated C has no forbidden JNI/JVMTI/fallback markers; timing
  does not regress.
- Rejected 2026-05-22. Focused generator/audit tests passed. Fresh TEST
  generation `build/neko-native-work/run-24803874241514` built
  `libneko_linux_x64.so` at `1036728` bytes with `translated=49 rejected=0`.
  Generated C inspection proved `Calc.runStr` moved the hot `"ax"` literal to
  `neko_bound_string(thread, env, &g_str_38, "ax")`, `neko_bind_owner_strings_14`
  binds `g_str_38` once at owner-string bind time, and no
  `__neko_concat_lit_N` local static slot remained for the pattern. Strict grep
  for `NEKO_JNI_FN_PTR`, `(*env)->`, and `env->` was clean. Runtime smoke
  passed, but same-session alternating TEST timing rejected the slice:
  NPT-3bi `76,76,74,73,79,72,75 ms` (median `75ms`) versus NPT-3bm
  `72,71,83,70,87,80,76 ms` (median `76ms`). Source/test edits were reverted.
  Do not retry this owner-bound concat literal shape without new code-layout or
  inlining evidence.

### [x] NPT-3bn: Inline concat append wrapper before existing String.concat NJX

- Scope: add a prelude-local inline concat append wrapper that preserves the
  existing `neko_concat_append` semantics: normalize null accumulator and null
  rhs to `neko_string_null(env)`, then call the existing inline
  `neko_require_fast_string_concat` path. Update generated concat accumulation
  paths to use the inline wrapper so hot loops do not pay an extra hidden C
  function call before the existing String.concat NJX dispatch. The change must
  not construct String payloads natively, change String.concat semantics,
  alter null-to-"null" behavior, remove hard aborts, change allocation/GC
  behavior, or affect non-concat call sites.
- Required evidence: fresh NPT-3bi generated C shows `Calc.runStr` calls
  hidden external `neko_concat_append(...)` once per append-loop iteration.
  `NativeFastObjectAccessEmitter` defines `neko_require_fast_string_concat` as
  `NEKO_FAST_INLINE`, but `neko_concat_append` itself is emitted as a hidden
  out-of-line support helper; `neko_concat_accumulate` is inline yet still
  calls that hidden helper when `acc != NULL`. This adds a native function-call
  layer before the already-required NJX String.concat dispatch.
- Validation command or runtime target: focused translator/generator/audit
  tests, fresh TEST native generation, generated-C inspection proving concat
  paths call the inline wrapper and hot `Calc.runStr` no longer calls hidden
  `neko_concat_append`, strict forbidden JNI grep, default TEST smoke/timing
  comparison, and focused native integration tests for TEST Calc and obfusjack
  completion.
- Completion criteria: inline wrapper preserves exact previous null
  normalization and `neko_require_fast_string_concat` call; no raw String
  construction or fallback is introduced; generated C has no forbidden
  JNI/JVMTI/fallback markers; timing does not regress.
- Completed 2026-05-22: focused translator/generator/audit tests passed, fresh
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

### [x] NPT-3bo: Post-P35 bottleneck selection and current-head gate

- Scope: capture a fresh current-head post-P35 performance and generated-code
  evidence set before selecting the next implementation slice. This is an
  audit/selection subtask only. It may inspect generated TEST and obfusjack
  native artifacts, runtime timings, `hs_err` files, generated C, and support
  helper call shapes. It must not change runtime behavior, special-case a
  sample, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm shapes, or implement
  raw native String construction without complete compact-string, allocation,
  exception, and GC-barrier evidence.
- Required evidence: fresh current-head TEST native artifact after NPT-3bn
  shows `translated>0`, `rejected=0`, strict forbidden JNI grep clean, and
  `Calc.runStr` still reaches the remaining hot loop through
  `neko_fast_string_length` plus `neko_concat_append_inline` and the
  `neko_njx_V_L_L` String.concat call-stub path. Current-head TEST timing must
  be repeated at least 5 times. Obfusjack native timing/completion evidence
  must include Seq/Platform/Virtual when the fixture output exposes them.
- Validation command or runtime target: fresh TEST and obfusjack native
  generation when no current-head artifact is available, strict generated-C
  grep for forbidden JNI/JVMTI/fallback markers, repeated TEST native runs,
  repeated obfusjack native runs, generated-C call-shape counts for remaining
  hot helper/NJX/string paths, and inspection for `hs_err` or abort logs.
- Completion criteria: record a concrete next implementation row with scope,
  evidence, validation target, and completion criteria, or record that no
  implementation is justified because the evidence chain is incomplete. The
  selected row must be generic and architecture-level, must preserve JVM ABI and
  full native invariants, and must not depend on one fixture or benchmark
  artifact.
- Completed 2026-05-22: fresh current-head TEST generation produced
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
  Selected NPT-3bp/P37 to fuse only those same-basic-block operand-stack
  shuffles while preserving the existing fast String.length helper, existing
  NJX String.concat path, pending-exception checks, and local-root storage.

### [x] NPT-3bp: Reject local String.length and concat-result store stack traffic

- Scope: add generic same-basic-block peephole lowering for two already-proven
  generated shapes: `ALOAD local; INVOKEVIRTUAL java/lang/String.length()I`
  may read `locals[local].o` directly into the existing
  `neko_fast_string_length` intrinsic, and a recognized StringBuilder concat
  producer immediately followed by `ASTORE local` may store the concat result
  directly through `neko_store_local_oop_ref`. This must not change
  String.concat allocation/copy behavior, introduce raw native String
  construction, remove the NJX String.concat call, bypass local-root storage,
  move or remove pending-exception checks, cross labels or handler boundaries,
  special-case Calc, or alter non-adjacent operand-stack semantics.
- Required evidence: fresh P36 generated TEST C shows `Calc.runStr` line 24
  emits `PUSH_O(locals[0].o)` and line 25 immediately pops that same operand
  for the already-accepted `neko_fast_string_length` path. The same method line
  30 emits accepted `neko_concat_append_inline(...)` and `PUSH_O(__fastConcat)`,
  and line 31 immediately pops the same reference into
  `neko_store_local_oop_ref`. `runAll` invokes `runStr` inside the 10000-loop,
  and `runStr` appends until String length reaches `101`, so both shuffles are
  hot without relying on a fixture-specific semantic shortcut.
- Validation command or runtime target: focused translator/generator/audit
  tests covering direct local String.length and concat-result direct store,
  fresh TEST native generation, generated-C inspection proving the two
  `PUSH_O`/`POP_O` pairs are removed only for adjacent same-block patterns,
  strict forbidden JNI grep, repeated TEST timing comparison against the P36
  current-head baseline, and focused native integration tests for TEST Calc and
  obfusjack completion.
- Completion criteria: generated code removes only the proven redundant
  operand-stack traffic; null behavior, pending-exception ordering,
  `neko_store_local_oop_ref`, NJX String.concat semantics, JNI policy, and GC
  rooting remain unchanged; no forbidden JNI/JVMTI/fallback markers appear; and
  timing does not regress.
- Rejected 2026-05-22: focused translator/generator/audit tests passed. Fresh
  TEST generation produced `build/npt-3bp/TEST-native.jar` from
  `build/neko-native-work/run-26009768901566` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1037048` bytes. Generated
  `Calc.runStr` inspection proved the intended shape: the hot
  `String.length` path directly reads `locals[0].o`, and the concat result is
  stored directly through `neko_store_local_oop_ref` without
  `PUSH_O(__fastConcat)` plus immediate `ASTORE`. Strict forbidden JNI grep
  was clean and TEST smoke completed, but the same-session alternating timing
  gate rejected the slice: P36 `69,71,89,71,68,77,70 ms` (median `71ms`)
  versus P37 `74,68,67,74,78,72,80 ms` (median `74ms`). Source/test edits were
  reverted. Do not retry this operand-stack shuffle fusion without new
  code-layout or optimizer evidence.

### [x] NPT-3bq: Reject raw native String.concat until returned-String publication invariants are proven

- Scope: compare JDK 21 `java/lang/String.concat`,
  `StringConcatHelper.simpleConcat`, `newArray`, `newString`,
  `String(byte[],byte)`, and `String(String)` semantics against the current
  native raw allocation, compact-string field, byte-array allocation, copy,
  exception, TLAB refill, OOME/overflow, GC barrier, and strict-GC capability
  surface. This is an evidence-only selection subtask. It must not implement
  raw native String construction, special-case Calc or obfusjack, retry
  rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm/NPT-3bp shapes, introduce
  JNI/JVMTI/original-bytecode fallback, weaken hard abort behavior, or change
  runtime behavior.
- Required evidence: local JDK bytecode for `String.concat` and
  `StringConcatHelper` proving empty-string identity/copy behavior,
  compact-string coder propagation, overflow/OOME behavior, byte-array
  allocation size calculation, and final `String` field initialization; current
  generated native support evidence for `neko_require_fast_string_concat` still
  crossing `neko_njx_V_L_L`; current raw string literal allocation and
  `neko_store_oop_raw` evidence showing which allocation/barrier pieces already
  exist; and explicit missing-invariant notes for any unsupported coder,
  allocation, GC, or exception path.
- Validation command or runtime target: source and bytecode inspection,
  generated-C inspection on the fresh P36/P37 native artifacts, strict
  forbidden JNI/fallback marker review of the current native support surface,
  and subagent audit cross-checks.
- Completion criteria: record either a concrete next implementation row with a
  complete invariant/evidence chain and runtime validation target, or record a
  no-go/rejection because a required compact-string, allocation, exception, or
  GC invariant is not yet proven. No source implementation may start until this
  row is completed and committed.
- Completion evidence: rejected/no-go 2026-05-22. Local JDK is OpenJDK
  `21.0.11+10`, and bytecode inspection proves `String.concat(String)` first
  evaluates `rhs.isEmpty()` (preserving null-rhs NPE), returns the original
  receiver for empty rhs, and otherwise dispatches to
  `StringConcatHelper.simpleConcat`. `simpleConcat` preserves additional
  empty-side copy behavior with `new String(other)`, computes compact-string
  coder and length through `mix`, allocates byte arrays through `newArray`,
  copies through `String.getBytes(byte[], int, byte)`, and returns
  `newString(byte[], long)`. `newArray` throws Java
  `OutOfMemoryError("Overflow: String length out of range")` for overflow and
  uses `Unsafe.allocateUninitializedArray(Byte.TYPE, len)`. Current native
  support still routes concat through `neko_require_fast_string_concat` and
  `neko_njx_V_L_L`; raw String allocation exists only in string literal/intern
  binding, not as a generic returned Java object path. The audit did not find
  complete evidence for freshly allocated returned `String` publication under
  G1, Serial, Parallel, ZGC, and Shenandoah, nor a generic Java-exception path
  matching JDK concat overflow and rhs-null behavior. Missing invariants are
  returned raw `String`/`byte[]` publication without constructor/NJX,
  collector-specific or generic barriered `String.value` initialization, exact
  Latin1/UTF16 `String.getBytes` copy/inflation, Java OOME/NPE behavior, and
  empty-side identity/copy behavior. No raw concat implementation is justified
  until those prerequisites are proven. The next selected implementation slice
  is NPT-3br/P39, which reuses resolved static-field refs in the already-proven
  same-field static int add/update fusion without touching raw String
  construction.

### [x] NPT-3br: Reject resolved static-field ref reuse in fused static int add updates

- Scope: for the existing generic same-field fusion
  `GETSTATIC int; const-int; IADD; PUTSTATIC same int field`, resolve the
  `neko_static_field_ref` once and reuse that same carrier for both read and
  write. This must preserve class-initialization ordering, field metadata
  hard-aborts, volatile access flags, static-base handling, same-field proof,
  and non-fused behavior. It must not change raw String construction, retry any
  rejected concat/Math/stack-shuffle shape, introduce JNI/JVMTI/original-
  bytecode fallback, or special-case Calc or obfusjack.
- Required evidence: fresh P36 generated TEST C emits
  `neko_fast_get_static_I_field_ref(env, &g_static_field_ref_3)` and then
  separately emits `neko_bound_class_ref`, `neko_ensure_class_initialized_once`,
  `neko_bound_field_ref`, and `neko_fast_set_static_I_field` for the same
  proven field. `neko_static_field_ref` already carries class, init, field,
  static-base, offset, and access-flag slots; `tryStaticIntAddUpdateFusion`
  already proves same owner/name/descriptor and same basic block.
- Validation command or runtime target: focused generator coverage for the
  fused same-field shape, fresh TEST native generation, generated-C inspection
  proving the duplicate class/init/field binding sequence is removed only for
  the fused same-field update, strict forbidden JNI/fallback grep, repeated
  same-session TEST timing against the current-head baseline, and focused
  TEST/obfusjack native smoke.
- Completion criteria: generated static int updates reuse one resolved
  `neko_static_field_ref` while retaining hard-abort metadata checks and exact
  field semantics; non-same-field updates remain unfused; no forbidden markers
  appear; timing does not regress.
- Completion evidence: rejected 2026-05-22. Implementation added a generated
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

### [x] NPT-3bs: Post-P39 bottleneck selection and raw-publication prerequisite decision

- Scope: re-audit the current post-P35/P39 state before selecting another
  runtime implementation. This is an evidence-only selection subtask. It may
  inspect generated C, runtime timings, native support helpers, existing todo
  prerequisites, and subagent audit results. It must not implement code,
  special-case a fixture, retry rejected NPT-3bj/NPT-3bk/NPT-3bl/NPT-3bm/
  NPT-3bp/NPT-3br shapes, or implement raw native `String.concat` before the
  returned-String publication invariants from NPT-3bq are proven.
- Required evidence: current generated TEST/obfusjack C showing the remaining
  hot runtime path, strict JNI/fallback marker status, current baseline timings
  after reverted P39 source, and explicit comparison between any non-raw-string
  candidate and the raw-publication prerequisite route.
- Validation command or runtime target: source/generated-C inspection, repeated
  runtime timing where a fresh current artifact is needed, strict forbidden
  marker grep, and subagent cross-checks for both generic hot-path candidates
  and raw returned-String prerequisites.
- Completion criteria: record a concrete next implementation/prerequisite row
  with complete scope, evidence, validation target, and completion criteria, or
  record that no implementation is justified yet because the evidence chain is
  incomplete.
- Completion evidence: completed 2026-05-22. Fresh current-head TEST
  generation after the reverted P39 source produced
  `build/npt-3bs/TEST-native.jar` from
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
  `<=20ms` gap. Selected NPT-3bt/P41 to prove and harden the generic returned
  fresh oop publication/rooting/barrier/exception prerequisite without
  replacing `String.concat` yet.

### [x] NPT-3bt: Reject/defer returned fresh oop publication until strict-GC barrier mapping is fixed

- Scope: implement the smallest generic prerequisite needed before raw native
  `String.concat`: a returned-fresh-object publication surface for translated
  native object returns. Scope is limited to freshly allocated object/array
  graphs, immediate local rooting before any possible safepoint or VM/NJX call,
  return-handle to raw-oop handoff, collector-valid field/array store barriers,
  and Java exception propagation for allocation/length failures. This must not
  replace `String.concat`, special-case benchmark code, add JNI/JVMTI/original
  bytecode fallback, introduce Java helper layers, or weaken hard-abort
  behavior for missing native capabilities.
- Required evidence: `ARETURN` currently returns a `jobject` from translated
  native code; object-return dispatch converts handle to raw oop and restores
  the handle window before returning to the trampoline; existing
  `neko_direct_oop_to_handle` and pre-reserved local root slots provide local
  rooting primitives but are not yet tied to freshly constructed returned
  object graphs; raw primitive array allocation currently returns handles and
  aborts on negative/allocation failures; string literal binding allocates
  `byte[]` and `String` only for bind-time/intern paths; `neko_store_oop_raw`
  colors ZGC oops but ordinary object field stores use the full selected
  pre/post barrier path.
- Validation command or runtime target: focused generator/runtime coverage for
  freshly allocated returned object and byte-array graphs, source/generated-C
  inspection proving immediate rooting and no forbidden JNI/JVMTI/original-
  bytecode fallback, `R-build`, `R-test`, `R-native-test`, `R-inspect`, and GC
  strict runs under G1, Serial, Parallel, ZGC with `ZVerifyViews`, and
  Shenandoah verification. Negative coverage must prove length/allocation/
  overflow failures become Java exceptions where the raw returned-object
  surface claims Java-equivalent behavior, otherwise the path must hard abort
  before being used by raw `String.concat`.
- Completion criteria: a generic returned fresh oop graph can survive the
  translated return handoff and GC/safepoint pressure under all required
  collectors, with exact recorded limits for which Java exception paths are
  implemented versus still blocking raw concat. Raw native `String.concat`
  remains out of scope until this row is completed and committed.
- Completion evidence: rejected/deferred 2026-05-22. The implementation
  hardened object-return dispatch by converting non-null return handles through
  a generated `neko_prepare_return_oop(thread, __ret, "sigN")` before
  restoring the handle window. Focused generator and hot-path audit tests
  passed. Fresh TEST generation produced `build/npt-3bt/TEST-native.jar` from
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

### [x] NPT-3bu: Fix strict-GC barrier tag and capability detection for ZGC/Shenandoah bootstrap

- Scope: limit the implementation to generic HotSpot GC barrier identification
  and readiness detection during native layout/bootstrap. It must map or
  structurally detect current ZGC and Shenandoah BarrierSet layouts so the
  native runtime selects a complete supported barrier path, or hard-aborts with
  an exact missing-symbol or missing-capability diagnostic. This must not skip
  classes or methods, fall back to JNI/JVMTI/original bytecode, disable
  ZGC/Shenandoah, weaken barriers, implement raw `String.concat`, or change
  unrelated opcode/runtime behavior.
- Required evidence: strict ZGC patch-debug bootstrap for both NPT-3bt and the
  NPT-3bs baseline reports a live BarrierSet `tag=5`, missing VMStruct
  ZGC/Shenandoah tag constants (`z=-1`, `shen=-1`), `use_zgc=0`,
  `use_shen=0`, and a selected barrier kind whose required path is not ready,
  causing `[neko-bootstrap] native layout initialization failed`. The failing
  invariant is bootstrap selecting/validating GC barrier capability from
  incomplete tag metadata before any translated object-return change executes.
- Validation command or runtime target: focused generator/source tests for the
  barrier detection shape, fresh TEST native generation, `R-inspect` strict
  forbidden JNI/fallback grep, TEST native runs under G1, Serial, Parallel,
  ZGC with `ZVerifyViews`, and Shenandoah verification. If ZGC or Shenandoah
  required symbols are absent, the runtime must hard-abort with an exact
  missing capability message rather than misclassifying the collector or
  falling back.
- Completion criteria: fresh generated TEST initializes native layout under all
  required supported collectors or fails closed with exact capability
  diagnostics for genuinely missing required VM support; no forbidden fallback
  markers appear.
- Completion evidence 2026-05-22: implemented generic JDK 21 BarrierSet tag
  fallbacks for Shenandoah `tag=4` and ZGC `tag=5`, ordered strict-GC
  detection before CardTable/G1 families, removed the unsafe
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

### [x] NPT-3bv: Tighten structured native performance median gates

- Scope: limit the implementation to native performance test instrumentation
  and assertions in `NativeObfuscationPerfTest`. Parse TEST Calc timing and
  obfusjack Platform, Virtual, Seq, Parallel, and VThreads timing rows from the
  repeated native-path runs already performed by the test. Record per-run
  timing maps and medians in the baseline JSON report, and assert that current
  fixture timing rows are parseable, complete when present, positive, and within
  conservative current-source sanity ceilings. This must not change generated
  runtime code, native code generation, JNI/JVMTI behavior, fallback behavior,
  obfuscation coverage, or collector behavior.
- Required evidence: current source records `calcMillis` for TEST and stores
  raw obfusjack timing lines as strings, but it does not compute or assert
  obfusjack matrix/thread medians. The observed current obfusjack output emits
  `Platform threads`, `Virtual threads`, `Seq`, `Parallel`, and `VThreads`
  rows, so the hot-path gate can become structured without runtime changes.
- Validation command or runtime target: focused
  `NativeObfuscationPerfTest` execution with `java.io.tmpdir` under
  `build/native-run-tmp`, plus diff inspection proving only test/report
  instrumentation changed.
- Completion criteria: fresh focused perf test passes, baseline JSON contains
  parsed per-run timing maps and median timing maps, and no runtime/codegen
  source files are changed.
- Completion evidence 2026-05-22: focused `NativeObfuscationPerfTest` passed
  using repository `./gradlew` with `java.io.tmpdir` under
  `build/native-run-tmp`. The generated
  `neko-test/build/test-native/native-performance-baseline.json` includes
  `timingsMillis` for every repeated TEST and obfusjack run plus
  `mediansMillis`: TEST Calc `69 ms`; obfusjack Platform `45 ms`, Virtual
  `36 ms`, Seq `17 ms`, Parallel `1 ms`, and VThreads `1 ms`. Diff inspection
  shows this checkpoint changed only perf test/report instrumentation and the
  matching todo/plan rows; no runtime/codegen source, JNI/JVMTI path, fallback
  behavior, obfuscation coverage, generated C, or collector behavior changed.

### [x] NPT-3bw: Resume returned object-result publication after strict-GC capability detection

- Scope: restore only the generic returned-object publication boundary from the
  deferred NPT-3bt/P41 implementation. Non-null object-return handles produced
  by translated native methods must pass through a generated
  `neko_prepare_return_oop(thread, handle, site)` helper before the signature
  dispatcher restores its handle window. The helper may resolve the handle,
  normalize collector-specific good-oop representation, and fail closed on an
  unresolved handle. This must not allocate, replace `String.concat`,
  special-case benchmark code, call JNI/JVMTI, add Java helper layers, change
  pending-exception behavior, or change primitive/void return dispatchers.
- Required evidence: current object-return dispatch in
  `SignatureDispatcherEmitter` resolves `__ret` through `neko_handle_oop`,
  restores the saved JNIHandleBlock window, then returns the raw oop. The
  pending-exception branch restores the window and returns `NULL`. NPT-3bt
  already proved the minimal generated-C ordering and runtime smoke shape, but
  was deferred because strict ZGC/Shenandoah bootstrap capability detection was
  incomplete. NPT-3bu/P42 now provides exact collector tag fallback and
  fail-closed missing-capability diagnostics for that prerequisite.
- Validation command or runtime target: focused generator tests for
  object-return dispatcher ordering and unchanged primitive/void dispatchers;
  fresh TEST native generation; `R-inspect` strict forbidden JNI/fallback grep;
  TEST native default, G1, Serial, and Parallel runs; strict ZGC/Shenandoah
  runs proving either execution on a capable VM or P42's exact fail-closed
  capability diagnostics.
- Completion criteria: generated object-return dispatchers call
  `neko_prepare_return_oop` before handle-window restore, pending-exception
  return behavior is unchanged, primitive/void return dispatchers are
  unchanged, fresh TEST native runtime passes under supported collectors, and
  no JNI/JVMTI/original-bytecode/skip fallback or raw `String.concat` path is
  introduced.
- Completion evidence 2026-05-22: implemented `neko_prepare_return_oop` in the
  generated fast-access support and changed only object-return signature
  dispatchers to call it before `neko_handle_restore`. The pending-exception
  branch still restores and returns `NULL`; primitive/void dispatchers are
  unchanged. Focused `CCodeGeneratorTest` and
  `NativeGeneratedCHotPathAuditTest` passed. Fresh TEST native generation
  produced `build/npt-3bw/TEST-native.jar` from
  `build/neko-native-work/run-29733047147870` with `translated=49`,
  `rejected=0`, and `libneko_linux_x64.so` size `1037768` bytes. Generated C
  shows object-return dispatchers calling `neko_prepare_return_oop` before
  handle-window restore, and strict grep for `NEKO_JNI_FN_PTR`, `(*env)->`, and
  `env->` returned no matches. Default, G1, Serial, and Parallel TEST native
  runs passed with Calc `70ms`, `73ms`, `67ms`, and `68ms`. ZGC with
  `ZVerifyViews` retained P42's fail-closed bootstrap diagnostic:
  `barrier-tag detected: tag=5 ... z=-1 shen=-1`,
  `ZGC barrier capability missing: symbols field=(nil) array=(nil) store=(nil)
  masks addr=0x0 load_good=0x0 load_bad=0x0 store_good=0x0 store_bad=0x0`, and
  `[neko-bootstrap] native layout initialization failed`. Shenandoah with
  verification retained P42's fail-closed diagnostic:
  `barrier-tag detected: tag=4 ... z=-1 shen=-1`,
  `Shenandoah barrier capability missing: lrb=(nil) pre=(nil) array=(nil)`, and
  `[neko-bootstrap] native layout initialization failed`. No raw
  `String.concat`, JNI/JVMTI, original-bytecode, skip, or collector-disabling
  fallback was introduced.

### [x] NPT-3cf: Select post-publication raw String concat prerequisite

- Scope: re-audit the raw `String.concat` blocker after NPT-3cd/NPT-3ce and
  NPT-3bw/P43 before selecting another implementation. This is an evidence-only
  selection subtask. It must not implement raw concat, special-case TEST
  `Calc.runStr`, retry rejected static-field or call-stub micro-shapes, call
  JNI/JVMTI, add Java helper layers, or introduce original-bytecode fallback.
- Required evidence: local OpenJDK 21 source for `String.concat`,
  `StringConcatHelper.simpleConcat`, `StringConcatHelper.newArray`,
  `StringConcatHelper.newString`, and package-private
  `String(byte[],byte)`/`String.getBytes`; current native concat helpers still
  crossing `neko_njx_V_L_L`/`neko_njx_V_L_L_raw_oop`; P43 object-return
  publication evidence; and current raw string literal allocation evidence
  showing which byte-array, `String.value`, `String.coder`, and local-root
  pieces exist only for bind-time string literals.
- Validation command or runtime target: source inspection, current generated
  helper inspection, strict forbidden-fallback review, and selection of one
  concrete next row with scope/evidence/validation/completion criteria before
  any runtime edit.
- Completion criteria: record a concrete implementation row that advances the
  missing raw returned-String construction invariants without replacing
  `String.concat` prematurely, or record a no-go if the evidence remains
  incomplete.
- Completion evidence 2026-05-22: OpenJDK 21 source proves
  `String.concat(String)` preserves rhs-null NPE by evaluating
  `str.isEmpty()`, returns `this` for empty rhs, and otherwise enters
  `StringConcatHelper.simpleConcat`; `simpleConcat` creates a new `String` copy
  for either empty side, mixes coder and char length, allocates a byte array via
  `newArray`, copies bytes with `String.getBytes`, and finalizes with
  `new String(byte[], byte)` through `newString`. `newArray` throws Java
  `OutOfMemoryError("Overflow: String length out of range")` on overflow and
  otherwise uses `Unsafe.allocateUninitializedArray(byte.class, len)`.
  Current source still routes the hot translated StringBuilder fast path through
  `neko_require_fast_string_concat_store_local`, which calls the original
  `String.concat` Method*/entry via `neko_njx_V_L_L_raw_oop` before publishing
  to a prepared local root. P43 proves the handle-to-raw object-return boundary,
  but it does not construct a fresh `String`/`byte[]` graph. Bind-time string
  literal code already proves raw byte-array header/length initialization,
  direct byte copying, `String.value`/`coder` writes, and bound-string local
  rooting, but that path is not a returned runtime concat path and does not
  implement `String.concat` empty-side copy/identity, overflow/OOME behavior,
  or generic lhs/rhs coder mixing. Selected NPT-3cg/P44 to implement the next
  generic prerequisite: a source-level raw newborn `String` graph constructor
  audit helper with full `String` instance sizing, byte-array sizing/copy,
  coder mixing, local-root publication, and focused runtime coverage, still
  not wired into the hot `String.concat` path until its exception and
  unsupported-allocation limits are proven.

### [x] NPT-3cg: Build raw newborn String graph prerequisite without changing concat dispatch

- Scope: add the smallest generic raw newborn `String` graph construction
  surface needed before replacing `String.concat`: allocate a fresh byte array
  and a fresh `java/lang/String` instance using current VM layout metadata,
  copy/inflate bytes from two existing `jstring` inputs according to their
  coders, initialize `String.value` and `String.coder`, and publish the result
  into an already prepared local root for focused validation. This row must not
  change the production `neko_require_fast_string_concat*` dispatch, must not
  special-case TEST or any literal value, must not call JNI/JVMTI or add Java
  helpers, must not fall back to original bytecode, and must hard-abort on
  missing raw heap, layout, allocation, or barrier capability not explicitly
  proven by the row.
- Required evidence: NPT-3cf source evidence; `NativeBindSupportEmitter`
  bind-time raw string literal allocation currently initializes byte-array
  header/length, copies Latin1/UTF16 payload, writes `String.value` through
  `neko_store_oop_raw`, writes `String.coder`, roots the new string with
  `neko_direct_oop_to_handle_origin`, and interns it; `NativeFastObjectAccessEmitter`
  already exposes `neko_fast_tlab_alloc`, `neko_init_oop_header`,
  `neko_fast_string_length`, `neko_store_local_oop_raw`, byte-array klass bits,
  and object allocation metadata; P43 proves object-return publication ordering
  but not newborn graph construction.
- Validation command or runtime target: focused generator/source tests for the
  helper emission and unchanged concat dispatch; a focused runtime fixture that
  exercises raw construction for Latin1+Latin1, Latin1+UTF16, empty lhs, empty
  rhs, and local-root publication without using the hot concat dispatch; fresh
  TEST native generation; strict generated-C grep for forbidden JNI/fallback
  markers; default/G1/Serial/Parallel TEST native smoke; and ZGC/Shenandoah
  strict runs proving either execution on a capable VM or the exact existing
  fail-closed capability diagnostics.
- Completion criteria: the helper can build and root fresh `String` graphs with
  correct bytes/coder under supported collectors, production concat helpers
  still call the original Method*/entry, no forbidden markers appear, and the
  row records the exact remaining exception/allocation semantics before a later
  row may replace the hot concat dispatch.
- Completion evidence 2026-05-22: implemented a default-off raw newborn
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

### [x] NPT-3ch: Correct raw String allocation size from Klass layout helper

- Scope: fix the raw `java/lang/String` graph allocation size calculation so positive
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
- Required evidence: OpenJDK HotSpot `klass.hpp` documents that for instances
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
- Validation command or runtime target: focused generator/source tests proving
  the byte-size calculation; fresh native TEST/raw-string-graph generation;
  opt-in raw-string graph fixture; opt-in and default TEST native smokes;
  obfusjack native smoke; G1/Serial/Parallel TEST native smokes; strict
  generated-C grep for forbidden JNI wrappers; and ZGC/Shenandoah strict
  fail-closed diagnostics.
- Completion criteria: generated C uses `layout_helper & ~1` for positive
  `String` instance sizes and hard-aborts if `java/lang/String` requires the
  layout-helper slow path, raw `String` graph allocation no longer requests
  pointer-size inflated object bytes, focused opt-in concat-shape fixture still passes,
  default behavior remains correct, and performance evidence records whether
  this sizing correction is sufficient before any default raw concat dispatch
  replacement.
- Completion evidence 2026-05-22: `neko_ensure_string_alloc_bits` now rejects
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

### [x] NPT-3ci: Defer raw String byte-array rooting until allocation slow path

- Scope: optimize the P45 raw newborn `String` graph helper by keeping a freshly
  TLAB-allocated byte array as a raw oop when the immediately following
  `java/lang/String` allocation also succeeds in the TLAB. Publish the byte
  array to a local handle only if a later allocation-capable slow path is about
  to run after the byte array exists. This must preserve the current rooted
  path for slow byte-array allocation, String TLAB refill, managed String
  allocation, GC/barrier fail-closed behavior, and local-root publication of
  the returned String. It must not special-case TEST, obfusjack, class names,
  literals, or any benchmark shape, and must not introduce JNI/JVMTI or
  fallback behavior.
- Required evidence: fresh P46 handle-audit build generated TEST at
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
- Validation command or runtime target: focused generator/source tests for the
  raw graph helper; fresh handle-audit native generation; opt-in TEST audit
  proving primitive-array handle publications are eliminated or materially
  reduced on the all-TLAB raw graph path while dispatch remains low; opt-in and
  default TEST native smokes; obfusjack native smoke; G1/Serial/Parallel TEST
  native smokes; strict generated-C grep for forbidden JNI wrappers; and
  ZGC/Shenandoah strict fail-closed diagnostics.
- Completion criteria: the all-TLAB raw graph path never calls
  `neko_direct_oop_to_handle_origin` for the intermediate byte array, every
  allocation-capable slow path after byte-array creation roots the byte array
  before safepointing, raw concat shape fixture remains correct, no forbidden
  JNI/fallback markers appear, and timing/counters record whether this is
  sufficient before making the raw graph default.
- Completion evidence 2026-05-22: `neko_build_raw_string_graph_store_local`
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

### [x] NPT-3cj: Skip raw String input reloads when byte-array allocation cannot safepoint

- Scope: remove only the redundant lhs/rhs handle and `String.value` reload
  block in `neko_build_raw_string_graph_store_local` when the intermediate
  byte array was allocated by the TLAB fast path and no allocation-capable slow
  path has run. Keep the existing reload and validation block when
  `array_handle != NULL`, which covers slow byte-array allocation and any path
  where the byte array was rooted because a safepoint-capable operation has
  already happened. This must not skip reloads after slow allocation, TLAB
  refill, managed String allocation, NJX, or any future safepoint-capable path,
  and must not change concat selection, CFF, JNI/JVMTI usage, fallback policy,
  or exception/abort behavior.
- Required evidence: P47 opt-in TEST audit showed the raw graph path now has
  low dispatch and handle traffic (`dispatched=62`, `handle_direct_total=87`,
  `primitive_array_alloc=32`) while still executing
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0` and
  median `42ms`. Current source still reloads `left_oop`, `right_oop`,
  `left_value`, and `right_value` unconditionally after byte-array allocation,
  even though the all-TLAB path between the initial reads and payload copy only
  performs `neko_fast_tlab_alloc`, `neko_init_oop_header`, and the byte-array
  length store. That path cannot safepoint, so the reloads are required only
  when `array_handle != NULL` proves a slow/rooted byte-array path already
  occurred.
- Validation command or runtime target: focused generator/source tests; fresh
  raw-string graph runtime fixture; fresh handle-audit TEST generation; opt-in
  TEST audit proving dispatch and handle counters remain low; opt-in/default
  TEST timing comparison; obfusjack smoke; strict generated-C grep for
  forbidden JNI wrappers; and G1/Serial/Parallel plus ZGC/Shenandoah
  fail-closed smokes.
- Completion criteria: generated raw graph helper guards the post-byte-array
  lhs/rhs reload block with `if (array_handle != NULL)`, all slow/rooted paths
  retain the reload and null checks, all-TLAB raw concat keeps correct
  Latin1/UTF16/empty-shape output, no forbidden markers appear, and timing does
  not regress versus P47 opt-in/default medians.
- Completion evidence 2026-05-22: `neko_build_raw_string_graph_store_local`
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

### [x] NPT-3ck: Scalarize proven same-local StringBuilder concat recurrence

- Scope: add a generic bytecode proof and native emission path for a
  non-escaping same-local recurrence of the form `s = new
  StringBuilder().append(s).append(x).toString()` where the updated local is
  used only for scalar observations proven equivalent before final
  materialization. The first accepted surface is limited to same-method,
  same-local recurrence with `String.length()` loop observation and a final
  normal method exit or proven local dead point where materializing the final
  `String` is not Java-observable. It must reject any field/array/static store,
  return, invoke argument, monitor, reflection, MethodHandle, exception object,
  stack escape, aliasing store, or unproven observation between recurrence
  updates. Proof failure must leave the existing concat path unchanged at
  translation time; no runtime fallback after partial scalarization is allowed.
  This row must not special-case TEST, class names, literals, or a benchmark
  method, must not weaken CFF/key propagation, and must not introduce JNI,
  JVMTI, Java helpers, original-bytecode fallback, or skip behavior.
- Required evidence: P48 shows the remaining opt-in hot path is no longer NJX
  or handle publication: TEST opt-in x5 was `32/30/31/31/38 ms` (median
  `31ms`) with `dispatched=61`, `V:L:L=2`, `handle_direct_total=74`,
  `primitive_array_alloc=20`, and
  `stringbuilder-fast-concat: total=510000 literal=510000 dynamic=0`. Generated
  TEST `runStr` is the generic recurrence shape: local `0` is length-checked,
  concatenated with a string literal through the recognized StringBuilder
  concat pattern, and stored back to the same local. The remaining cost is
  therefore per-iteration `String`/`byte[]` allocation plus payload copy for
  intermediate values whose identity is not observed in the proven recurrence.
- Validation command or runtime target: focused source tests for recurrence
  proof acceptance and rejection; generated-C inspection proving accepted
  recurrence sites do not call `neko_build_raw_string_graph_store_local` per
  iteration and rejection fixtures retain the current concat path; fresh TEST
  default x5 with no opt-in dependency targeting `<=20ms`; obfusjack x5
  non-regression for Seq/Platform/Virtual/Parallel/VThreads; strict
  generated-C grep for forbidden JNI/fallback markers; G1/Serial/Parallel TEST
  and obfusjack smokes; and ZGC/Shenandoah strict fail-closed diagnostics unless
  barrier support is completed in a prior row.
- Completion criteria: scalarization applies only when bytecode proof shows all
  intermediate String identities are unobservable and scalar observations are
  equivalent, proof failure preserves the existing safe concat emission,
  default TEST median is materially improved without `NEKO_RAW_STRING_GRAPH_PREREQ`,
  obfusjack does not regress, and all required runtime/static checks pass.
- Gate blocker 2026-05-22: source tests passed and fresh TEST `runStr`
  generated C in `build/neko-native-work/run-39893685712791/neko_native_impl_22.c`
  contains `scalarized same-local StringBuilder concat recurrence`, removing the
  per-iteration concat call. Default TEST x5 reported Calc `2/3/2/2/3 ms`
  (median `2ms`). P49 cannot complete yet because obfusjack x5 hard-aborted
  once in an unrelated primitive array allocation path:
  `[neko-direct] primitive array allocation direct path unavailable len=192
  kind=7 ... raw=1 zgc=0 coh=0 tlab=1`. NPT-3cl is recorded as the required
  allocator prerequisite before P49 can be checkpointed.
- Completion evidence 2026-05-22: after NPT-3cl removed the primitive-array
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

### [x] NPT-3cl: Use JVM_NewArray slow primitive-array allocation when TLAB refill cannot satisfy direct allocation

- Scope: extend the existing non-JNI JVM symbol slow allocation surface used by
  byte-array TLAB refill to cover all primitive array kinds when
  `neko_fast_new_primitive_array` has valid metadata but TLAB allocation still
  returns NULL after refill. The helper must resolve the primitive mirror by
  kind through existing `JVM_FindPrimitiveClass`, allocate with existing
  `JVM_NewArray`, return the resulting local handle, and hard-abort on missing
  symbols, invalid kind, pending exception, unresolved handle, or null result.
  It must not use the JNI function table, JNI array functions, Java helpers,
  skip behavior, original-bytecode fallback, or benchmark-specific logic.
- Required evidence: P49 validation exposed an obfusjack runtime abort in
  matrix multiply after four successful runs: `len=192 kind=7` (`double[]`),
  `g_hotspot.initialized=1`, primitive array klass/base/scale present, raw heap
  enabled, compact headers disabled, and `g_neko_tlab_alloc_ready=1`. Source
  evidence in `NativeFastObjectAccessEmitter.neko_fast_new_primitive_array`
  shows it refills via a slow byte array and aborts if the second TLAB attempt
  still fails; `NativeBindSupportEmitter.neko_alloc_jbyte_array_oop_slow` proves
  the runtime already has a fail-closed `JVM_FindPrimitiveClass`/`JVM_NewArray`
  slow path for byte arrays.
- Validation command or runtime target: focused generator tests covering slow
  primitive array helper emission and hard-abort guards, fresh native artifact
  regeneration, obfusjack x5 without primitive-array allocation abort, TEST x5
  preserving the P49 Calc win, strict generated-C forbidden JNI/fallback grep,
  and G1/Serial/Parallel TEST smokes. ZGC/Shenandoah remain fail-closed unless
  barrier support is completed in a prior row.
- Completion criteria: direct primitive array allocation remains the hot path;
  only TLAB exhaustion after refill uses the JVM symbol slow allocation path;
  missing symbols/capabilities hard-abort; obfusjack no longer aborts in the
  observed primitive array path; TEST and generated-C inspections do not
  regress.
- Completion evidence 2026-05-22: `neko_fast_new_primitive_array` still uses
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

### [x] NPT-3cn: Scalarize counted double[][] multiply-accumulate inner loops

- Scope: add a generic translator loop fusion for a counted `int` loop whose
  body updates one `double` accumulator local with the product of two
  `double[][]` element reads and then increments the counted local by one. The
  proof must require a single backedge to the loop header, no active try
  handler over the covered range, no branch target inside the covered range
  other than the header/exit labels, an `IF_ICMPGE`/`IF_ICMPGT` style exit guard
  that dominates the body, a same-local induction increment, and no writes to
  the array reference, bound, index, or accumulator locals except the proven
  update. The emitted C may replace the bytecode body with one native `for`
  loop that uses the existing raw fused `AALOAD+DALOAD` helper, preserves the
  helper failure reason dispatch, and jumps to the original exit label. It must
  not hoist nullable array dereferences before the original loop guard, must not
  change exception order on the first failing access, and must not specialize
  owner, method, class, descriptor, source line, or benchmark artifact names.
- Required evidence: after P51, fresh generated obfusjack C in
  `build/neko-native-work/run-40744661698418/neko_native_impl_52.c` proves the
  `mmulSeq` inner loop is now reduced to a scalar multiply-add statement but
  still executes the translated label loop, loop-local stores, increment,
  pending-exception check, and backedge every iteration. Fresh obfusjack x5
  after P51 still misses the gate with Seq `17/20/17/17/17 ms`, while TEST Calc
  remains under target at `4/3/2/3/3 ms`. The exact failing invariant is
  loop-dispatch overhead remaining after the straight-line multiply-add fusion;
  the changed path is the generic counted-loop translator path for the same
  bytecode shape.
- Validation command or runtime target: focused translator tests for positive
  counted-loop fusion and rejection when a protected range, interior branch
  target, non-unit induction update, mismatched accumulator store, or escaping
  write breaks the proof; fresh artifact regeneration; generated-C inspection
  proving the inner loop emits one scalar native `for` and no per-iteration
  `PUSH_D`/`POP_D` multiply-add sequence or translated backedge in the covered
  range; TEST x5 preserving Calc; obfusjack x5 measuring Seq/Platform/Virtual/
  Parallel/VThreads; strict forbidden JNI/fallback grep; G1/Serial/Parallel
  TEST smokes.
- Completion criteria: the optimization is enabled only by the generic
  bytecode/control-flow proof, rejection tests keep the existing translation,
  generated C preserves fast-array failure dispatch and first failing access
  semantics, obfusjack Seq reaches or materially moves toward the `<=14ms`
  target without runtime aborts, and TEST does not regress.
- Completion evidence 2026-05-22: implemented the generic counted-loop
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

### [x] NPT-3co: Remove redundant getClass probes after generated lambda allocation

- Scope: update native LambdaMetafactory lowering so the replacement bytecode
  emits `new`, `dup`, captured arguments, and `<init>` for the generated lambda
  class, but no longer appends the unused `dup; Object.getClass(); pop` probe.
  This applies only to generated lambda classes created by the native stage's
  generic LambdaMetafactory lowering. It must not change lambda capture order,
  constructor invocation, SAM implementation, generated class metadata,
  manifest patching, hidden-key propagation, or any non-lambda invokedynamic
  lowering.
- Required evidence: fresh generated obfusjack C in
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
- Validation command or runtime target: focused native-stage/source tests
  proving lowered LambdaMetafactory bytecode no longer contains an unused
  `Object.getClass()` call after generated lambda construction while generated
  lambda invocation still works; fresh artifact regeneration; generated-C
  inspection proving `lambda$microbenchThreads$6` and
  `lambda$microbenchThreads$9` no longer contain the post-constructor no-arg
  `neko_icache_dispatch`, while constructor invocation and returned lambda
  object remain; TEST x5; obfusjack x5 measuring Platform/Virtual/Seq/Parallel/
  VThreads; strict forbidden JNI/fallback grep; G1/Serial/Parallel TEST smokes.
- Completion criteria: the removed probe is only the native-stage generated
  lambda `getClass` probe, lambda construction/invocation remains correct,
  manifest discovery still patches generated lambda classes, TEST and Seq stay
  within target, Platform and Virtual medians move toward or meet Platform
  `<=44ms` and Virtual `<=35ms`, and no runtime aborts or forbidden JNI/
  fallback markers appear.
- Completion evidence: focused Gradle validation passed with fresh native
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

### [x] NPT-3cp: Omit LambdaMetafactory Object return adapter casts

- Scope: update native-stage LambdaMetafactory adapter generation so object or
  array values adapted to `java/lang/Object` do not receive a redundant
  `CHECKCAST java/lang/Object`. Keep all other casts intact, including casts to
  specific object types, interface types, arrays, unboxing, boxing, void
  adaptation, and all non-lambda bytecode. This is a generic assignability
  cleanup in the generated lambda adapter, not a runtime fallback and not a
  benchmark-specific transform.
- Required evidence: fresh generated obfusjack C in
  `build/neko-native-work/run-43586804685160/neko_native_impl_89.c` and
  `neko_native_impl_91.c` shows generated `Callable.call()` adapters for
  `Main$NekoLambda$17` and `Main$NekoLambda$19` call the translated task body,
  then execute a checkcast block that binds `java/lang/Object` and calls
  `neko_fast_is_instance_of` before returning. The corresponding generated
  class bytecode shows the LambdaMetafactory adapter is returning `Object` from
  a more specific reference return, and `NativeCompilationStage.adaptStackValue`
  currently emits `CHECKCAST` for every object-to-object mismatch. A cast to
  `java/lang/Object` cannot fail for any non-null reference and preserves null,
  so it adds work without changing lambda return semantics. The obfusjack
  Platform/Virtual task paths execute these generated `Callable.call()` methods
  50,000 times per timed path.
- Validation command or runtime target: focused Gradle validation with fresh
  native regeneration; generated-C inspection proving `NekoLambda$17.call` and
  `NekoLambda$19.call` no longer contain the post-task `java/lang/Object`
  checkcast block while casts to narrower types remain emitted elsewhere; TEST
  x5; obfusjack x5 measuring Platform/Virtual/Seq/Parallel/VThreads; strict
  forbidden JNI/fallback grep; G1/Serial/Parallel TEST smokes.
- Completion criteria: LambdaMetafactory adapters still preserve JVM
  assignability for all non-Object targets, lambda construction and invocation
  remain correct, TEST and Seq stay within target, Virtual median moves toward
  or meets `<=35 ms` without regressing Platform beyond `<=44 ms`, and no
  runtime aborts or forbidden JNI/fallback markers appear.
- Completion evidence: focused Gradle validation passed with fresh native
  regeneration:
  `:neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest --tests dev.nekoobfuscator.test.NativeObfuscationIntegrationTest.nativeObfuscation_rawStringGraphOptInRunsConcatShapes`.
  Fresh generated obfusjack C in
  `build/neko-native-work/run-43946550536660/neko_native_impl_89.c` and
  `neko_native_impl_91.c` returns directly after the translated task body and
  no longer emits the prior post-task `java/lang/Object`
  `neko_fast_is_instance_of` checkcast block. Targeted forbidden JNI/fallback
  grep over those changed generated methods returned no matches. Fresh TEST x5
  reported Calc `3/3/2/3/2 ms`. Fresh obfusjack x5 reported Platform
  `24/35/30/38/31 ms`, Virtual `28/35/44/36/29 ms`, Seq
  `11/11/11/11/12 ms`, Parallel `1 ms`, and VThreads `1 ms`; medians are
  Platform `31 ms`, Virtual `35 ms`, and Seq `11 ms`, meeting the recorded
  thresholds. G1/Serial/Parallel TEST smokes reported Pool PASS and Calc
  `3/3/3 ms`.
