# JVM CFF Helper Host Renamer Rule - 2026-05-25

## Evidence

- User observation: JVM obfuscation can inject helper classes named like
  `e$1hp2twm`, which does not follow the renamer's generated class naming rule.
- Source path: `ObfuscationPipeline.execute` runs `obfuscateGeneratedHelperApi`
  before `runJvmOutputFinalizers`.
- Source path: `ControlFlowFlatteningPass.finalizeOutput` calls
  `finalizeClassCodeIntegrity`, which can call `relocateLargeCffHelperSets`
  after generated helper API renaming has already run.
- Exact failing invariant: `CffClassSetup.uniqueCffHelperHostName` constructs
  relocated helper host class names as `owner + "$" + base36Hash`. When the owner
  class has already been renamed to a short renamer name such as `e`, this emits
  a new synthetic class named `e$...`, outside the renamer's simple-name stream.
- Scope boundary: this plan changes only the CFF relocated-helper host class
  naming path. It does not change CFF block construction, key flow, dispatch,
  helper relocation criteria, JNI/native paths, or runtime fallback behavior.

## Plan

- [x] Baseline/evidence capture
  - Scope: identify the exact post-renamer helper host generation path.
  - Required evidence: source chain from pipeline ordering to
    `uniqueCffHelperHostName`.
  - Validation target: source inspection.
  - Completion criteria: failing invariant is recorded above.

- [x] Plan/write review
  - Scope: record a narrow repair before implementation.
  - Required evidence: plan names the source path and excludes runtime/native
    behavior changes.
  - Validation target: diff review of this document.
  - Completion criteria: plan exists before code edits.
  - Review note: current harness subagent tools are policy-restricted to
    user-requested delegation, so this plan uses local review as the available
    substitute.

- [ ] Repair CFF relocated helper host naming
  - Scope: replace `owner + "$" + hash` host names with deterministic,
    same-package, renamer-style simple class names.
  - Required evidence: generated host names are selected from the same base-26
    simple-name stream used by the renamer and avoid occupied class names.
  - Validation target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest`.
  - Completion criteria: code no longer emits `$` helper host class names from
    the CFF relocation path and targeted tests pass.

- [ ] Add regression coverage
  - Scope: prove the helper host allocator returns same-package names without
    `$` and without colliding with existing classes.
  - Required evidence: focused test exercises the allocator behavior directly.
  - Validation target:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest`.
  - Completion criteria: test fails on the previous `owner + "$"` behavior and
    passes with the repair.

- [ ] Final review and commit
  - Scope: review diff for scope discipline and commit only this plan plus the
    implementation/test files.
  - Required evidence: `git diff --check`, targeted Gradle result, and scoped
    `git status`.
  - Validation target: local diff review and targeted test output.
  - Completion criteria: no unrelated dirty work is included.
