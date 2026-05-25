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

### [-] 1A. Budget Revision Intake Checkpoint

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

### [ ] 2. Baseline CFF Size/Topology Census

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

### [ ] 3. Budgeted Synthetic CFF Noise Density

Scope:

- Add a generic size-budget policy for synthetic CFF noise only. The policy may
  lower fake case count, fake bounce density, and fake-only alias hub count
  when estimated method/code pressure crosses recorded thresholds.
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

Required evidence:

- Current `fakeCaseCount(long)` emits one to three fake cases for every island
  regardless of method size pressure.
- Current `aliasHubCount(int)` emits one to three alias hubs based only on
  non-handler block count, not bytecode budget.
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

### [ ] 4. Direct Real-Case Island Dispatch Where Verifier-Compatible

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

### [ ] 5. CFF Fake Case Shared Router Encoding

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

### [ ] 6. Transition Delta-Key Update Encoding

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

### [ ] 7. String Call-Site Live-Word Thinning

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
