# CFF Structural Size Optimization - 2026-05-25

## Objective

Reduce JVM full-obfuscation method-size growth caused by control-flow
flattening physical encoding, string call-site live-word expansion, and
invokedynamic runtime-word expansion. The current priority is bytecode size and
64k/JIT huge-method pressure reduction. Hot-loop runtime cost is a hard
non-regression constraint, not the first optimization target.

Budget-driven reductions may lower only synthetic CFF noise density, such as
fake cases, fake bounce rows, and fake-only alias routing, when a method is
under size pressure. Real CFF block coverage, real edge rewriting, real
dispatcher semantics, dynamic key propagation, hidden key parameters, packed
`Object[]` carriers, and poison/fail-closed behavior must not be reduced.

This plan is high-risk because it touches CFF dispatch/key material and
string/indy CFF integration. Every implementation subtask must be generic,
evidence-backed, freshly validated, reviewed by a subagent, and committed with
only its matching plan update before the next implementation subtask starts.

Continuation decision on 2026-05-26: the user explicitly requested finishing
the complete plan, including the optimization rows previously deferred after
the first size milestone. The prior milestone closure remains historical
evidence only. Tasks 4 through 8 are reopened as retained high-risk
implementation subtasks, and task 9 is reopened as the final review that must
run after those subtasks. Each reopened subtask must still preserve real CFF
coverage, dynamic key propagation, hidden key ABI behavior, string/indy live
dependencies, and fail-closed behavior.

## Baseline Evidence

- JVM pass order is fixed through `StandardJvmPasses.register()`: key dispatch
  and method-parameter packing run before CFF, while invokedynamic, constants,
  and strings add call-site material after CFF metadata exists.
- User design decision on 2026-05-25: bytecode-size reduction is the current
  priority. Size-budgeted reductions may reduce some fake case/fake block
  density for large methods, while small methods and methods with sufficient
  budget should keep stronger synthetic noise density.
- CFF currently splits protected methods into dispatch islands, adds
  `pc/guard/path/block/domain/keyTmp` locals, rewrites every block exit, and
  inserts island dispatchers.
- `fakeCaseCount(long)` creates one to three fake cases for every island. Each
  fake case currently expands to a fake dispatch token, a fake label, step-key
  updates, and fake bounce routing.
- `aliasHubCount(int)` creates one to three alias hubs for non-handler blocks.
  `aliasHub(...)` emits pure routing chains or opaque branches that end at the
  same group hub. These are synthetic routing noise and are eligible for
  budgeted density reduction only when verifier-safe and only when real
  dispatcher reachability is unchanged.
- Real island switch cases currently dispatch through stub labels that then
  `GOTO` the actual block label.
- Every inline transition calls `emitDecodeBlockKeys(...)`, which decodes and
  commits all three active CFF words (`guard`, `path`, and `block`) before
  writing the next `pc`, method key, and sometimes domain token.
- Existing evidence in `.plan/jvm-test21-vthread-huge-method-2026-05-23.md`
  recorded a VThread worker above HotSpot's 8000-byte huge-method JIT boundary
  before budgeted token-dispatch encoding.
- Existing evidence in `.plan/string-indy-material-table-reduction.md` recorded
  a full-obf `MethodTooLargeException` at an estimated 73,389 bytes when CFF
  sidecar update logic was inlined at many token sites.
- Existing evidence in `.plan/cff-zkm-key-dispatch-optimization.md` records
  retained and rejected CFF performance changes. Rejected rows prove that
  skipping semantically required domain refreshes or weakening token masks breaks
  validation.
- String obfuscation already uses a shared string tail, but every string use
  still expands `emitLiveStringWord(...)` at the application call site.
- InvokeDynamic obfuscation already uses shared helpers and loop-aware runtime
  word caching, but non-loop call sites still expand the helper argument load
  sequence and call the shared flow helper.

## Non-Degradation Constraints

- Do not reduce CFF block construction, block boundaries, block selection, real
  dispatch case coverage, poison/fail-closed behavior, or transform coverage.
- Synthetic fake case/fake bounce/fake-only alias density may be reduced only by
  a generic size-budget policy. It must not be reduced for samples,
  benchmarks, class names, method names, descriptors, log strings, or known
  test artifacts.
- For emitted fake cases, keep dynamic fake routing, wrong-key pollution, live
  CFF key consumption, and class-material dependency. If a budget tier emits no
  fake case for a large method, the real CFF path and poison path must remain
  fully protected.
- Do not replace dynamic key transfer with static seeds, descriptor-only
  recomputation, method names, owner names, or constant-only material.
- Do not expose raw flow keys, method keys, state keys, dispatch keys, string
  keys, or derived key material as plain constants or metadata.
- Do not remove hidden long key parameters or packed `Object[]` carriers.
- Do not add fallback behavior, original-bytecode fallback, skip-on-error,
  Java bridge/adaptor layers, JNI, JVMTI, or a new runtime helper layer.
- Do not specialize behavior for a sample, benchmark, class, method, owner,
  descriptor, crash site, log string, or test artifact.
- Do not create files or folders under `/tmp`.
- Repository Gradle validation must use `./gradlew` through the active harness.

## Runtime Target Rows

- `R-build`: regenerate affected JVM full-obfuscation artifacts with the
  repository Gradle wrapper.
- `R-cff`: targeted CFF/key tests:
  `ControlFlowFlatteningAlgebraicAuditTest`,
  `CffStrongEntrySeedRegressionTest`, and
  `JvmMethodParameterObfuscationIntegrationTest`.
- `R-string-indy`: targeted string and indy integration tests:
  `JvmStringObfuscationIntegrationTest` and
  `JvmInvokeDynamicObfuscationIntegrationTest`.
- `R-full-jvm`: `JvmFullObfuscationPerfTest`, including generated artifact
  structural audit, runtime execution, output-size report, helper count checks,
  and full-obf performance report.
- `R-inspect`: bytecode/source inspection for largest methods, CFF fake/real
  case topology, helper descriptor counts, forbidden fallback/static-key markers,
  and direct evidence that changed paths are present in fresh artifacts.

Validation rejection rules:

- Reject verifier errors, bootstrap errors, runtime failures, VM fatal errors,
  `MethodTooLargeException`, static-key/static-decrypt evidence, skip-on-error
  behavior, original-bytecode fallback, or transform coverage reduction.
- Reject stale jars, compile-only output for runtime-bearing subtasks, or
  inspection that does not cover the changed generated path.
- Reject performance claims without a fresh before/after artifact comparison in
  the same environment.

## Planned Subtasks

### [x] 1. Plan Intake Checkpoint

Scope:

- Record this plan and active todo entry before production code edits.
- Dispatch a subagent plan-intake review before implementation.
- Report the plan scope, order, benefits, and tradeoffs to the user.
- Commit only this plan/todo checkpoint before implementation starts.

Required evidence:

- Static source evidence listed in the baseline section identifies the generic
  CFF/string/indy growth paths.
- Plan-intake subagent review returns PASS or all blocking findings are fixed.

Validation target:

- Plan-intake subagent review.

Completion criteria:

- This plan exists, the plan-intake review passes, the user-facing plan summary
  is reported, and the plan checkpoint is committed without unrelated work.

Completion evidence:

- Initial plan-intake review returned FAIL with blocking findings about stale
  checkbox state, missing `R-build` targets, and overly broad helper/delta
  wording. This revision keeps the checkpoint in progress until review passes,
  the plan is reported, and the checkpoint commit is created.
- Second plan-intake review returned PASS after revisions. The review confirmed
  that the prior blocking findings were fixed, implementation subtasks include
  `R-build`, helper boundaries are constrained to existing transform surfaces,
  transition delta eligibility is bounded, and JVM obfuscation invariants are
  preserved.
- User-facing plan report was provided before this checkpoint commit, covering
  planned change scope, ordering, expected benefits, and tradeoffs.
- This checkpoint commit records only this plan file.

### [x] 1A. Budget Revision Intake Checkpoint

Scope:

- Revise this plan for the 2026-05-25 user decision to prioritize bytecode
  volume and allow size-budgeted reduction of synthetic fake case/fake block
  density.
- Record the new boundary between reducible synthetic noise and non-reducible
  real CFF semantics.
- Dispatch a plan-intake subagent review before implementation proceeds under
  the revised scope.
- Report the revised scope, order, benefits, and tradeoffs to the user.
- Commit only this plan/todo checkpoint before implementation resumes.

Required evidence:

- Source evidence identifies fake case count and alias hub count as synthetic
  physical encoding sources, while real block construction and edge rewriting
  remain separate code paths.
- Plan-intake subagent review returns PASS or all blocking findings are fixed.

Validation target:

- Plan-intake subagent review.

Completion criteria:

- This revised plan records the budgeted synthetic-noise policy, the review
  passes, the user-facing revised plan summary is reported, and the checkpoint
  commit contains only this plan file.

Completion evidence:

- Budget revision plan-intake review returned PASS. The review confirmed that
  the revised plan separates reducible synthetic fake/alias noise from
  non-reducible real CFF block/edge/key semantics, includes concrete validation
  targets and stale-validation rejection rules, and now prioritizes bytecode
  size through the budgeted synthetic-noise task before later thinning work.
- Non-blocking review risks are carried forward: task 3 must record concrete
  budget tiers and threshold evidence before code changes; any future shared
  fake helper must remain inside the existing CFF-generated helper surface; any
  string helper thinning must remain inside the string-decode surface and must
  not become an indy or CFF bridge.

### [x] 1B. Full Plan Continuation Intake Checkpoint

Scope:

- Reopen tasks 4 through 8 after the 2026-05-26 user request to complete the
  full plan rather than stopping at the first validated size milestone.
- Reopen task 9 so final compatibility and performance review happens after
  all retained implementation subtasks, not after task 3 only.
- Dispatch a plan-intake subagent review before starting any reopened
  implementation subtask.
- Report the continuation scope, ordering, benefits, and tradeoffs to the user.
- Commit only this continuation plan checkpoint before implementation resumes.

Required evidence:

- The existing plan already records scope, required evidence, validation
  targets, and completion criteria for tasks 4 through 8.
- The previous milestone completion evidence proves task 3 fixed the first
  failing size invariant, but does not complete the user-requested remaining
  optimization rows.
- Plan-intake subagent review returns PASS or all blocking findings are fixed.

Validation target:

- Plan-intake subagent review.

Completion criteria:

- Tasks 4 through 8 and task 9 are reopened in this plan.
- The continuation plan-intake review passes.
- The user-facing continuation plan summary is reported.
- The checkpoint commit contains only this plan file.

Completion evidence:

- Continuation plan-intake review returned PASS. The review confirmed that the
  objective section reopens tasks 4 through 8 and task 9, that task 1B records
  scope/evidence/validation/completion criteria, that task 9 treats the earlier
  task-3 validation as stale historical evidence, and that the reopened
  subtasks are bounded enough for one-at-a-time implementation, review, and
  commit.
- The review identified task 6 as the riskiest row and confirmed that its plan
  text correctly requires recording the exact eligible transition family and
  proof before replacing any transition.

### [x] 2. Baseline CFF Size/Topology Census

Scope:

- Add or extend test-side inspection only. Do not alter production transform
  behavior in this subtask.
- Capture fresh full-JVM CFF topology metrics: largest method byte estimates,
  real-case stub counts, fake case counts, alias hub counts, transition helper
  calls, direct island counts, string live-word call-site counts, and indy
  live-word call-site counts.

Required evidence:

- The current source has fixed fake/alias generation and real-case stubs, but
  the fresh per-artifact distribution is not recorded in one machine-readable
  report.
- A baseline census is required before claiming that a later optimization
  reduces structural growth.

Validation target:

- `R-build`.
- `R-full-jvm`.
- `R-inspect`.

Completion criteria:

- Fresh report records CFF/string/indy growth metrics for TEST, obfusjack,
  SnakeGame, and evaluator full-obf artifacts.
- No production behavior changes are included in this subtask.
- Subagent implementation review passes.

Progress evidence:

- Test-side topology reporting was added to
  `JvmFullObfuscationPerfTest`: generated output jars are inspected for largest
  estimated methods, total instruction estimates, helper descriptor counts,
  string tail calls, indy flow calls, and CFF transition/material helper calls.
  Obfuscation logs are parsed for CFF dry-run metrics such as fake cases, fake
  bounce rows, real rows, material rows, and helper pressure.
- Fresh `R-full-jvm` attempt with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`
  failed while obfuscating `test21.jar`, before a complete four-fixture report
  could be written. The failure is the size invariant this plan targets:
  `ControlFlowFlatteningPass` reported `ClassTooLargeException: Class too large:
  a/a` during class-code integrity finalization.
- Fresh failing evidence recorded by the same run: largest method estimates in
  class `a/a` were `zc([Ljava/lang/Object;)V` 64,523 bytes,
  `cr([Ljava/lang/Object;)V` 64,523 bytes,
  `cb([Ljava/lang/Object;)V` 64,389 bytes, `main([Ljava/lang/String;)V`
  63,981 bytes, `yc([Ljava/lang/Object;)V` 61,919 bytes, and
  `db([Ljava/lang/Object;)V` 61,733 bytes.
- Fresh CFF dry-run evidence for the same `test21.jar` run shows
  `a/a.main([Ljava/lang/String;)V` emitted 148 helpers, 217 real blocks,
  303 fake cases, 303 fake bounce rows, 148 poison rows, 15,995 material words,
  and 63,980 raw material bytes. This proves synthetic fake density is a
  concrete contributor to the size failure.
- Dependency reason for proceeding to task 3 before task 2 completion: task 2
  cannot meet its four-fixture report completion criteria until the generic
  size-pressure repair prevents the current `ClassTooLargeException`. Task 3 is
  therefore the next prerequisite repair, not a workaround.
- After the generic size-pressure repair and verifier/key-transfer repairs,
  fresh `R-full-jvm` completed and wrote the full four-fixture report at
  `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json`. The
  report includes TEST, obfusjack, SnakeGame, and evaluator output jars,
  topology largest-method rows, helper descriptor counts, string tail calls,
  indy flow calls, CFF material/helper call counts, and parsed CFF dry-run
  metrics.
- The fresh report confirms the previously blocked `test21.jar` fixture now
  writes `test21-obf.jar`, while `a/a.main([Ljava/lang/String;)V` retains 217
  real blocks and 148 poison rows with zero fake rows under `CRITICAL` pressure.
  The artifact run also completes obfusjack output through
  `=== All tests completed ===`.

### [x] 3. Budgeted Synthetic CFF Noise Density

Scope:

- Add a generic size-budget policy for synthetic CFF noise only. The policy may
  lower fake case count, fake bounce density, and fake-only alias hub count
  when estimated method/code pressure crosses recorded thresholds.
- Split relocated CFF helper host classes by a generic helper-count budget when
  the existing helper relocation path would otherwise concentrate a large helper
  set into one synthetic class.
- Add a generated-helper hardening size gate so already-generated keyed helpers
  keep their existing protected CFF/material form under large-method pressure
  instead of receiving additional string, indy, or numeric-context expansion.
- Route relocated CFF helper call sites through descriptor-level relay helpers
  when a large helper set would otherwise leave hundreds of unique Methodref
  entries in the original class constant pool.
- Relocate renamed generated helper methods, identified from generated-helper
  renamer map entries, out of the original owner when the same class is already
  under large helper relocation pressure.
- Keep stronger current fake density for small methods and methods with
  sufficient budget.
- Keep poison/fail-closed paths, real block dispatch, real edge rewriting,
  method-key transfer, domain refresh, string/indy live-word dependencies, and
  packed carrier behavior unchanged.
- The budget must be computed from generic structural metrics such as estimated
  method bytes, block count, edge count, handler count, generated helper
  pressure, and projected CFF material rows. It must not inspect sample names,
  benchmark names, class names, method names, descriptors, logs, or test
  artifacts.
- The plan update for this subtask must record the concrete budget tiers and
  threshold evidence before code changes.

Concrete budget tiers recorded before code changes:

- `NORMAL`: estimated CFF outliner pressure below 8,000 bytes. Keep current
  fake case density (`1..3` fake cases per island) and current alias hub density
  (`1..3` alias hubs by group size).
- `PRESSURE`: estimated CFF outliner pressure from 8,000 through 17,999 bytes.
  Keep real CFF unchanged, reduce synthetic fake density to `0..1` fake cases
  per island from seed material, and cap alias hubs at one per dispatch group.
- `CRITICAL`: estimated CFF outliner pressure at or above 18,000 bytes. Keep
  real CFF and poison/fail-closed paths unchanged, emit zero fake cases, and
  emit no alias hubs.

Threshold evidence:

- The failing `test21.jar` method `a/a.main([Ljava/lang/String;)V` has 217 real
  blocks and 303 fake cases, with CFF finalizer method estimates already near
  64KB and class serialization failing with `ClassTooLargeException`. This
  justifies the `CRITICAL` tier eliminating synthetic fake/alias noise while
  preserving real CFF.
- First implementation attempt proved the budget path was active but too
  conservative for class-level size accumulation: `test21` main dropped from
  303 fake cases to 0 and material words dropped from 15,995 to 6,905, but
  class `a/a` still failed `ClassTooLargeException`. Additional methods in the
  same class, such as `a/a.d([Ljava/lang/Object;)Ljava/lang/String;`, still
  retained 100 fake cases under the initial thresholds. This justifies lowering
  thresholds generically so medium and large protected methods in a large class
  shed synthetic noise before class serialization fails.
- Tightened threshold evidence shows the fake reduction path remains active:
  `test21` main retained 217 real rows and 148 poison rows while dropping fake
  rows to zero; medium methods such as `a/a.d` still retained budgeted fake
  rows. The run still failed `ClassTooLargeException` after logging `Relocated
  large CFF helper sets: hosts=1 methods=521`, proving a second structural
  amplification source: existing helper relocation prevents the original class
  from carrying all helpers, but moves the whole helper set into one synthetic
  host. Splitting that existing relocation host by helper count is therefore a
  generic prerequisite to let the fake-density budget reach class serialization.
- Helper-host splitting then changed the relocation log to `hosts=9
  methods=521`, proving that host concentration was repaired. The same fresh run
  still failed class preview with generated-helper hardening active on 18
  classes and `Obfuscated generated helper API: methods=605 fields=19`; the
  largest remaining methods in `a/a` are generated-helper shaped
  `([Ljava/lang/Object;)V` methods near 64KB. This proves post-CFF helper
  hardening is now the remaining structural size pressure, and justifies a
  generic generated-helper hardening budget.
- The current class preview failure reports `constantPoolCount=65888`, only 353
  entries above the JVM limit. The same class has 521 relocated CFF helper
  methods, and the original owner still contains one unique Methodref for each
  rewritten helper call. A descriptor-level relay keeps the same generated
  helpers and keyed execution path while replacing hundreds of original-class
  Methodrefs with one relay Methodref per helper descriptor.
- Descriptor-level relay reduced the failing class constant pool from 65,888 to
  65,800 but did not cross the JVM limit. The remaining largest methods in the
  original owner have generated-helper-shaped `([Ljava/lang/Object;)V`
  descriptors after `Obfuscated generated helper API: methods=605 fields=19`.
  Relocating only methods proven by the renamer map to have originated from
  `__neko_` generated helpers addresses that remaining class-local helper
  material without moving original application ABI methods.
- The threshold input is generic: existing `estimatedOutlinerCodePressure(...)`
  combines estimated method bytes, estimated edge count, and protected handler
  cost. It does not inspect fixture names, class names, method names,
  descriptors, log strings, or test artifacts.

Required evidence:

- Current `fakeCaseCount(long)` emits one to three fake cases for every island
  regardless of method size pressure.
- Current `aliasHubCount(int)` emits one to three alias hubs based only on
  non-handler block count, not bytecode budget.
- Current `relocateLargeCffHelperSets(...)` creates exactly one synthetic host
  for each large helper set, regardless of helper count. The failing fresh run
  produced one host for 521 helper methods, making the host class itself the
  remaining class-size pressure point.
- Generated helper hardening currently reruns string, indy, and numeric
  hardening on every generated helper method with a generated name. It does not
  apply a bytecode budget before adding another layer of keyed call-site
  context to helpers that are already generated protected material.
- Relocated CFF helper rewriting currently changes each call site directly to
  the final helper owner/name/descriptor. That relieves method/code ownership
  pressure but leaves all unique helper Methodrefs in the caller class constant
  pool.
- Generated helper API renaming records `METHOD owner.__neko_* desc -> name`
  lines. These provide a generic, non-sample-specific proof that a post-rename
  method is generated helper material and can be relocated without treating an
  original application method as helper code.
- Existing dry-run stats record fake case, fake bounce, alias/router, and
  material row counts, making budget impact measurable before and after the
  change.

Validation target:

- `R-build`.
- `R-cff`.
- `R-string-indy`.
- `R-full-jvm`.
- `R-inspect` proving small/budget-safe methods retain synthetic noise while
  pressure/critical methods reduce only fake/alias noise and keep real CFF
  semantics.

Completion criteria:

- Fresh reports show reduced CFF physical growth for methods under size
  pressure.
- Real CFF block coverage, real dispatch targets, real edge rewriting, dynamic
  key propagation, and poison/fail-closed behavior are unchanged.
- Emitted fake cases remain live-state-driven and class-material-dependent.
- Subagent implementation review passes.

Progress evidence:

- Implemented a generic `SyntheticNoiseBudget` with `NORMAL`, `PRESSURE`, and
  `CRITICAL` tiers. The budget is derived from estimated outliner code pressure,
  not from fixture, class, method, descriptor, or log identity. `PRESSURE`
  reduces fake cases to `0..1` and caps alias hubs at one; `CRITICAL` removes
  synthetic fake cases and alias hubs while preserving real CFF blocks, real
  dispatch cases, poison rows, and key transitions.
- Propagated the budget through CFF island dispatch, shared state, key-state
  emission, dispatch emission, and transition outliner code so the policy is
  applied generically at CFF construction time.
- Added descriptor-level CFF helper relays and chunked relocated helper hosts by
  `RELOCATED_CFF_HELPERS_PER_HOST = 64`, replacing single-host concentration
  with multiple synthetic helper hosts while preserving the relocated helper
  bodies and keyed call path.
- Added generated-helper hardening size pressure gates to constant, string, and
  invokedynamic helper hardening so already-generated helper material under
  large-method pressure does not receive another full layer of helper
  expansion.
- Added verifier repair for mixed `Object` locals by inferring required
  consumer casts. Fresh generated bytecode for the previously failing
  `RuntimeException.<init>(Throwable)` site now inserts `checkcast
  java/lang/Throwable` before the constructor call.
- Fresh `R-build` compile target passed:
  `./gradlew :neko-test:compileTestJava`.
- Earlier fresh `R-cff` and indy/parameter regression target passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --rerun-tasks`.
- Fresh `R-full-jvm` no longer fails with `ClassTooLargeException` and writes
  the affected artifacts. Current evidence includes `test21-obf.jar` written
  with `Wrote 30 classes and 1 resources` and relocation log `Relocated large
  CFF helper sets: hosts=9 methods=571`.
- The obfusjack runtime failure was diagnosed generically. Temporary runtime
  prints proved the invokedynamic resolver selected the correct packed static
  interface target, but the target entered with a key that did not mix back to
  its method seed, so the failure was a packed hidden-key transfer/context
  issue rather than an indy resolver or relocation failure. The temporary
  prints were removed.
- Enabled the existing split-hidden-key ABI for static/private packed methods,
  so reducible static/private call sites no longer box hidden method keys inside
  the `Object[]` carrier. This keeps the hidden long key mandatory and dynamic,
  but removes a redundant carrier slot/index-decode layer for methods whose ABI
  can be fully rewritten.
- Implementation review found a split-hidden-key reflection compatibility gap:
  runtime `Method.invoke` candidate selection skipped split-hidden-key plans
  when the exact reflective target was not statically resolved. Fixed the path
  generically by allowing split candidates, tracking runtime outer-argument
  arity, and emitting the split `Long` key slot only for matched split targets.
  Added a regression case that obtains a static target through
  `getDeclaredMethods()` before invoking it reflectively.
- Updated packed/generated CFF key-transfer replacement to build materialized
  key loads from the actual replaced key-load instruction's block state instead
  of cloning a later call-site replacement. This prevents packed carrier
  key-material helpers from decoding under the wrong CFF block context.
- Added default initialization for CFF non-empty-stack spill locals before the
  protected region. This fixes verifier paths where the flatten dispatcher can
  reach a spill-load label from verifier-visible edges that did not execute the
  real predecessor spill store.
- Fresh final `R-build` passed:
  `./gradlew :neko-test:compileTestJava`.
- Fresh focused split-reflection regression passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --rerun-tasks`.
- Fresh final targeted validation passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --rerun-tasks`.
- Fresh final `R-full-jvm` passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
  TEST, obfusjack, SnakeGame, and evaluator artifacts were regenerated after
  the final source change. The final report records obfusjack full-obf
  `exitCode=0` and evaluator full-obf `exitCode=0`; SnakeGame preserves the
  expected headless failure behavior with `HeadlessException`.
- One intervening `R-full-jvm` attempt failed in the original, unobfuscated
  evaluator run with `WeirdLoopTest` random-input `ArrayIndexOutOfBoundsException`.
  No source changed after that failure; rerunning the same target produced the
  fresh pass above.
- Final `R-inspect` evidence from fresh obfuscation logs: no
  `ClassTooLargeException` or `MethodTooLargeException` appears in the generated
  obfuscation logs; `test21` reports `Relocated large CFF helper sets: hosts=9
  methods=569` and writes `Wrote 30 classes and 1 resources`; evaluator reports
  `Relocated large CFF helper sets: hosts=11 methods=605` and writes `Wrote 42
  classes and 2 resources`.
- Investigated a possible cross-class carrier-index table mismatch by
  temporarily making carrier index decode use the target owner's CFF table. That
  moved TEST to `IllegalAccessError: class a.b tried to access private field
  a.u.b`, proving direct target-table loads would require illegal access to
  target private carrier fields. The experiment was reverted and is not part of
  the implementation.

### [x] 4. Direct Real-Case Island Dispatch Where Verifier-Compatible

Scope:

- Remove unnecessary real-case stub `GOTO` nodes only where the switch case can
  directly target the existing block label without changing verifier frame
  shape, handler structure, stack state, block construction, or fake/poison
  coverage.
- Preserve stubs for any case where direct label targeting is not proven
  verifier-safe.

Required evidence:

- Current inline island dispatch maps every real block to a generated stub label
  and then emits `GOTO` to the actual block. This is a physical encoding cost,
  not a required CFF semantic when the target block label is already a valid
  zero-stack dispatcher target.

Validation target:

- `R-build`.
- `R-cff`.
- `R-string-indy`.
- `R-full-jvm`.
- `R-inspect` proving direct-label cases exist and fake/poison case counts are
  unchanged.

Completion criteria:

- Generated bytecode has fewer real-case stub gotos in eligible methods.
- CFF audits still prove live key/table token decoding and wrong-key pollution.
- No fake/poison case is removed.
- Subagent implementation review passes.

Continuation evidence:

- Previously deferred after task 3 because the first size failure was already
  repaired. Reopened on 2026-05-26 by user request to complete the full plan.
  The implementation must record concrete verifier eligibility before changing
  any real-case target.

Concrete verifier eligibility recorded before code changes:

- A real switch case may target `block.label()` directly only when
  `CffFrameAnalysis.zeroStackLabels()` proves that label is a zero-stack
  verifier target after `normalizeNonZeroStackControlTargets(...)` has run.
- The direct target must be the same canonical block label that the existing
  generated stub would have reached by a zero-stack `GOTO`; state token,
  selector seed, dispatch case key, island/domain placement, fake rows, poison
  rows, and transition encoding are unchanged.
- If a block label is not proven zero-stack by the current frame analysis, keep
  the generated stub label and `GOTO block.label()` path.

Progress evidence:

- Implemented direct real-case targets only for `block.label()` values accepted
  by the same `isZeroStackLabel(...)` verifier analysis used during block
  construction. The fallback stub path remains for labels not proven zero-stack.
- Fresh `R-build` passed:
  `./gradlew :neko-test:compileTestJava`.
- Fresh focused `R-cff` and `R-string-indy` validation passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --rerun-tasks`.
- Fresh `R-full-jvm` passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
- Fresh `R-inspect` over generated `javap` output shows direct real-case switch
  targets now exist while fallback stub targets remain where needed:
  `a-a.javap directTargets=1335 stubTargets=84`,
  `evaluator-a-a.javap directTargets=175 stubTargets=2`, and
  `test21-a-a.javap directTargets=1274 stubTargets=74`.
- Fresh obfuscation logs still show fake and poison rows for budget tiers that
  retain them, for example `a/c.a([Ljava/lang/Object;)V realRows=17
  fakeRows=5 poisonRows=5` and `a/w.q([Ljava/lang/Object;)V realRows=25
  fakeRows=43 poisonRows=24`.
- Implementation subagent review returned PASS. The review found that direct
  real cases are gated by existing zero-stack verifier analysis, fallback stubs
  remain where needed, fake/poison emission is intact, and no static-key or
  fallback semantics were introduced.

### [x] 5. CFF Fake Case Shared Router Encoding

Scope:

- For fake cases retained by the budget policy, keep the retained fake token set
  and replace repeated per-fake physical blocks with a shared fake-router
  encoding when method size pressure justifies it.
- The shared encoding may be either an in-method shared label sequence or an
  existing CFF-generated synthetic helper method owned by the transformed class
  and published with live flow-key metadata. It must not introduce a new Java
  bridge/adaptor, external runtime helper layer, fallback path, or helper class.
- The router must consume live guard/path/block/pc/method-key state, the current
  fake selector, and per-class CFF material. It must still execute wrong-key
  pollution and bounce/poison behavior equivalent to the current fake path.

Required evidence:

- Every fake case currently contributes dispatch rows, step-key update rows,
  fake bounce rows, and sometimes bounce predicates. Dry-run stats already count
  these rows, proving they are a repeated physical encoding family.

Validation target:

- `R-build`.
- `R-cff`.
- `R-full-jvm`.
- `R-inspect` proving fake case count/token count is unchanged while repeated
  physical fake blocks shrink or move to shared helper material.

Completion criteria:

- Retained fake case cardinality and dispatch tokens are preserved for the
  selected budget tier.
- Fake-route runtime depends on live CFF state and class material.
- Largest-method and output-size reports show reduced physical growth.
- Subagent implementation review passes.

Continuation evidence:

- Previously deferred after task 3 because `CRITICAL` methods already shed fake
  rows. Reopened on 2026-05-26 by user request to complete the full plan. The
  implementation is limited to retained fake rows in budget tiers that still
  emit fake cases, and must preserve retained fake token/cardinality behavior.

Concrete shared-router eligibility recorded before code changes:

- The in-method shared fake router is eligible only when an inline island
  dispatcher retains more than one fake case. `CRITICAL` methods still emit zero
  fake cases by task 3 policy, and single-fake islands keep the current direct
  fake body because a shared router would not reduce repeated physical blocks.
- Each retained fake dispatch token still maps to a distinct fake label. The
  label stores a fake selector and jumps to the island-local shared fake body.
- The shared fake body must mix the fake selector into live guard/path/block and
  method-key state before executing fake-key pollution and bounce logic, so the
  route remains live-state-dependent and selector-dependent without exposing a
  static key or removing fake/poison rows.

Progress evidence:

- Implemented an island-local shared fake router for inline dispatchers with
  more than one retained fake case. Each fake token still targets a distinct
  fake label; the labels store a selector and jump to the shared fake body.
- The shared fake body mixes the selector with live guard/path/block and method
  key state before running fake step-key pollution and fake bounce logic.
  Single-fake islands still use the original direct fake body.
- Fresh `R-build` passed:
  `./gradlew :neko-test:compileTestJava`.
- Fresh focused `R-cff` and `R-string-indy` validation passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --rerun-tasks`.
- Fresh `R-full-jvm` passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
- Fresh `R-inspect` confirms retained fake cardinality remains present in
  budget tiers that emit fake rows, for example
  `a/c.a([Ljava/lang/Object;)V realRows=17 fakeRows=5 poisonRows=5`,
  `a/w.q([Ljava/lang/Object;)V realRows=25 fakeRows=45 poisonRows=24`, and
  `a/a.d([Ljava/lang/Object;J)Ljava/lang/String; realRows=49 fakeRows=22
  poisonRows=49`.
- Fresh obfuscation/run stderr inspection showed no `ClassTooLargeException`,
  `MethodTooLargeException`, bootstrap error, skip-on-error marker, or fallback
  marker in the generated full-JVM logs. SnakeGame retains the expected
  headless stderr behavior.
- Implementation subagent review returned PASS. The review confirmed retained
  fake tokens still enter dispatch cases individually, fake labels route through
  a bounded shared body, selector/live-state mixing happens before fake
  step/bounce logic, poison/default dispatch remains intact, and no fallback or
  static-key replacement was introduced.

### [x] 6. Transition Delta-Key Update Encoding

Scope:

- Replace only explicitly eligible full guard/path/block decode+commit sequences
  with a materialized delta update when source and target key states prove that a
  smaller live-state-dependent update is equivalent for that transition.
- Eligibility is limited to a transition family whose source state, target
  state, edge role, downstream domain read, method-key transfer, and dispatcher
  read set are all recorded in the plan update before implementation. If any
  downstream consumer may read a refreshed word, keep the existing full update.
- The delta formula must be nonlinear, depend on live method-entry material
  through the current guard/path/block/method-key state, and must not contain
  inverse pairs, neutral operations, descriptor-only recomputation, dead stores,
  constant-only recomputation, or static-key exposure.
- Inspection evidence must identify every changed transition family and prove
  that each changed family still consumes live CFF state and class material.
- Do not skip domain refresh, method-key transfer, pc token materialization, or
  class/table dependency where they are read downstream.

Required evidence:

- Current transition emission decodes all three active key words for every edge.
  Existing rejected rows prove that skipping semantically required refreshes
  breaks validation, so this subtask must prove equivalence before replacing a
  full update.

Validation target:

- `R-build`.
- `R-cff`.
- `R-string-indy`.
- `R-full-jvm`.
- `R-inspect` proving changed transitions use live-state delta material and no
  full-strength coverage is removed.

Completion criteria:

- Every changed transition still drives dispatcher selection, downstream hidden
  key transfer, string/indy live words, exception paths, loops, and recursion
  from live method-entry material.
- CFF audits and full-obf artifacts pass.
- Subagent implementation review passes.

Continuation evidence:

- Previously deferred after task 3 because rejected optimization rows show this
  path is sensitive. Reopened on 2026-05-26 by user request to complete the full
  plan. The implementation must first record the exact transition family and
  eligibility proof before replacing any full guard/path/block refresh.

Concrete transition family and eligibility proof recorded before code changes:

- The only eligible family for this subtask is inline, non-outlined
  `EdgeKind.DIRECT_ISLAND` transitions for non-handler roles. Outlined
  transition-material helper calls, handler-entry transitions, hub transitions,
  alias-hub transitions, poison paths, and any path that refreshes domain for a
  downstream dispatcher remain on the existing full decode+commit path.
- For the eligible family, the source guard/path/block locals are live at the
  transition site and match `sourceKeys`; the existing transition already
  computes a live `keyBaseLocal` from method key plus guard/path/block before
  decoding target words. The delta path reuses that same live base, decodes a
  protected per-word delta, and updates each live source word in place to the
  exact target word.
- `pc` token materialization, method-key materialization, direct-island jump
  target selection, downstream hidden-key transfer state, and class/table
  dependency are unchanged. Since the family is direct-island only, no domain
  refresh is removed from a path that would read it downstream.

Progress evidence:

- Implemented delta key updates only inside inline `EdgeKind.DIRECT_ISLAND`
  transitions for non-handler roles. All outlined material transitions,
  handler transitions, hub transitions, alias-hub transitions, and domain
  refresh paths still use the existing full decode+commit path.
- The delta path computes the same live `keyBaseLocal` from method key plus
  current guard/path/block state, decodes protected per-word deltas from that
  live base, and updates guard/path/block in place. The following `pc` token and
  method-key materialization still uses the existing transition-base logic.
- Fresh `R-build` passed:
  `./gradlew :neko-test:compileTestJava`.
- Fresh focused `R-cff` and `R-string-indy` validation passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --rerun-tasks`.
- Fresh `R-full-jvm` passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
- Fresh `R-inspect` of generated full-JVM logs showed no
  `ClassTooLargeException`, `MethodTooLargeException`, `VerifyError`,
  bootstrap error, skip-on-error marker, or fallback marker in obfuscation and
  runtime stderr logs. SnakeGame retains the expected headless stderr behavior.
- Implementation subagent review returned PASS. The review confirmed that
  outlined transitions bypass the delta path, non-eligible paths still use full
  decode, domain refresh remains on hub/alias paths, eligibility is limited to
  non-handler direct-island transitions, and the delta update consumes live
  method key plus guard/path/block through `keyBaseLocal`.

### [x] 7. String Call-Site Live-Word Thinning

Scope:

- Move repeated string live-word derivation out of application call sites and
  into the existing shared string tail/helper boundary, or introduce a thin
  package-shared helper only if it remains inside the existing string-decode
  helper surface.
- The helper must not be used as an invokedynamic bridge, CFF bridge, Java
  adaptor layer, fallback path, or generic runtime helper. It must only serve
  string decode material already owned by `JvmStringObfuscationPass`.
- Keep payload encryption, key-cell update, cache fingerprinting, class-owned
  `Object[]` material, protected string data/key material, and live
  CFF/method-key dependency.

Required evidence:

- `emitLiveStringWord(...)` currently expands at every string use even though the
  decrypt tail is shared.
- Existing string decryption already uses generated `__neko_strtail$...`
  methods as the shared decode-helper surface. This task may widen that tail ABI
  for string-owned live-word completion, but must not add a generic runtime
  helper, invokedynamic bridge, CFF bridge, or fallback path.
- The moved suffix must still be keyed by caller-supplied live CFF/method state:
  the call site must provide the live-word prefix, method key, state token, and
  class-word selector; the tail must complete class-key-table lookup and final
  mixing from those live inputs plus the protected class-owned carrier.

Validation target:

- `R-build`.
- `R-string-indy`.
- `R-cff`.
- `R-full-jvm`.
- `R-inspect` proving call sites are thinner and string decode still consumes
  live CFF state and key material.

Completion criteria:

- String call sites carry less inline bytecode without exposing plaintext or
  static key material.
- String integration tests still prove shared-tail decrypt ownership and dynamic
  key-cell update.
- Subagent implementation review passes.

Continuation evidence:

- Previously deferred after task 3 because generated-helper hardening and
  relocation repaired the first string-related size pressure. Reopened on
  2026-05-26 by user request to complete the full plan. The implementation must
  stay inside the existing string decode helper surface.

Progress evidence:

- Implemented string call-site live-word thinning by widening the existing
  generated string tail ABI from `([Ljava/lang/Object;IJI)Ljava/lang/String;`
  to `([Ljava/lang/Object;IJIII)Ljava/lang/String;`. Call sites now compute the
  live-word prefix, pass the live method key, state token, and class-key-word
  selector, and leave class-key-table lookup plus final live-word mixing inside
  the string-owned tail.
- The tail derives the runtime `rootSeed(siteSeed)` before the moved suffix, so
  the helper computes the same class-table index, sealed class-key word, and
  final mix constants as the original inline `emitLiveStringWord(...)` suffix.
- Payload selection, encrypted byte material, cache fingerprinting, cipher
  ownership, key-cell update, boxed fingerprint cache state, and class-owned
  `Object[]` material remain inside the existing generated string tail path.
  No invokedynamic bridge, CFF bridge, Java adapter layer, fallback path, or
  generic runtime helper was added.
- Updated `JvmStringObfuscationIntegrationTest` to assert the new thin string
  tail ABI while preserving the invariant that tail calls pass a live `long`
  flow key.
- A focused pre-fix run failed in
  `JvmStringObfuscationIntegrationTest.stringObfuscationUsesCffKeyedAesDesXorWithoutHelpers`
  because the structural assertion still matched the old
  `([Ljava/lang/Object;IJI)Ljava/lang/String;` tail descriptor. The generated
  jar executed successfully after the root-seed repair; the assertion was then
  updated to the new descriptor.
- Fresh `R-build` passed:
  `./gradlew :neko-test:compileTestJava`.
- Fresh focused `R-cff` and `R-string-indy` validation passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --rerun-tasks`.
- Fresh `R-full-jvm` passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
- Fresh `R-inspect` of full-JVM logs showed no
  `ClassTooLargeException`, `MethodTooLargeException`, `VerifyError`,
  bootstrap error, skip-on-error marker, or fallback marker in obfuscation and
  runtime stderr logs. SnakeGame retains the expected headless stderr behavior.
- Fresh `javap` inspection of
  `build/tmp/neko-test-strings/string-shapes-obf.jar` showed
  `StringShapes.__neko_strtail$:([Ljava/lang/Object;IJIII)Ljava/lang/String;`
  at both the generated tail definition and every string decode call site.
- Implementation subagent review returned PASS. The review confirmed the
  widened ABI call shape and local layout, verified that live dependency remains
  rooted in guard/path/block/pc and live method key before the tail combines it
  with state token and protected class-key material, and found no new bridge,
  adapter, fallback, JNI path, or generic runtime helper.

### [ ] 8. Indy Call-Site Flow-Word Budget Thinning

Scope:

- Keep loop-aware indy flow-word caching.
- Add a size-budgeted thin call-site path for non-loop indy sites only when
  projected method size justifies it.
- Preserve `MutableCallSite` behavior, callsite descriptors, resolver cache
  behavior, class-owned material tables, and live CFF flow-word dependency.

Required evidence:

- Current indy call sites already call a shared flow helper, but still expand
  repeated argument loads and non-loop runtime-word derivation at every site.

Validation target:

- `R-build`.
- `R-string-indy`.
- `R-cff`.
- `R-full-jvm`.
- `R-inspect` proving changed non-loop sites remain live-state-driven.

Completion criteria:

- Non-loop indy call-site bytecode shrinks only under generic size pressure.
- Existing loop caching behavior is preserved.
- Subagent implementation review passes.

Continuation evidence:

- Previously deferred after task 3 because split-hidden-key transfer and the
  existing loop-aware indy path passed full-JVM validation. Reopened on
  2026-05-26 by user request to complete the full plan. The implementation is
  limited to generic size-budgeted non-loop indy call sites.

### [ ] 9. Final Compatibility and Performance Review

Scope:

- Run final JVM full-obfuscation compatibility and performance evidence after
  all retained implementation subtasks.
- Dispatch a final subagent plan review.

Required evidence:

- Fresh artifacts generated after the final source change.
- No stale validation or compile-only evidence.

Validation target:

- `R-build`.
- `R-cff`.
- `R-string-indy`.
- `R-full-jvm`.
- `R-inspect`.

Completion criteria:

- Full-obf TEST and obfusjack complete.
- No `MethodTooLargeException`, verifier error, bootstrap error, fallback,
  static-key exposure, or skipped transform marker is present.
- Reports show reduced CFF/string/indy physical growth versus the baseline
  census where changed paths apply.
- Final subagent plan review passes.

Historical evidence from the earlier task-3 milestone:

- Fresh final `R-build`, focused method-parameter reflection regression,
  targeted CFF/parameter/indy/string validation, and `R-full-jvm` passed after
  the final source change.
- Fresh `R-full-jvm` report was captured at
  `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json` with
  TEST and obfusjack `exitCode=0`, evaluator `exitCode=0`, and SnakeGame
  preserving the expected headless `exitCode=1` behavior.
- Fresh obfuscation logs show no `ClassTooLargeException` or
  `MethodTooLargeException` in the generated obfuscation output. The previously
  failing `test21` artifact writes `Wrote 30 classes and 1 resources` with
  `Relocated large CFF helper sets: hosts=9 methods=569`.
- Implementation subagent review passed after the split-hidden-key reflection
  gap was fixed. The review found no sample-specific production logic, no
  temporary diagnostics, and confirmed the runtime reflective split-key path and
  regression coverage.

Continuation requirement:

- The historical evidence above is stale for the reopened full-plan objective.
  Task 9 remains incomplete until fresh validation and final plan review pass
  after tasks 4 through 8 are implemented and committed.
