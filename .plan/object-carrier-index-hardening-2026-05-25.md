# Object Carrier Index Hardening - 2026-05-25

## Goal

Strengthen method-parameter `Object[]` carrier obfuscation and hidden long-key
transfer by replacing linear carrier slots with CFF/class-key-derived encrypted
index material. The index material must be decoded through each target class's
existing class-key/keytable path and mixed with live method key input, matching
the runtime-variable mask style without adding JNI, JVMTI, runtime fallback, or
new Java runtime helper classes.

## Evidence Chain

- Fresh baseline command:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
  It completed with `BUILD SUCCESSFUL in 1s`.
- Fresh baseline command:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
  It completed with `BUILD SUCCESSFUL in 3s`.
- Failing carrier-index invariant: direct call packing writes arguments to
  monotonically increasing literal slots. Source evidence from HEAD `54d1708`:
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/parameters/JvmMethodParameterObfuscationPass.java`
  lines 1540-1563 allocate `new Object[]`, keep `carrierIndex = 0`, push
  `carrierIndex++` for each argument, box the value, then `AASTORE`.
- Failing unpack invariant: callee prologue reads the same monotonically
  increasing literal slots. Source evidence from HEAD `54d1708`:
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/parameters/JvmMethodParameterObfuscationPass.java`
  lines 745-755 keep `carrierIndex = 0`, call
  `emitArrayLoad(..., carrierIndex++)`, unbox/cast, then store into the
  original local.
- Failing dynamic-invocation invariant: MethodHandle packing repeats the same
  literal slot pattern. Source evidence from HEAD `54d1708`:
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/parameters/JvmMethodParameterObfuscationPass.java`
  lines 1101-1133 allocate the carrier and push `carrierIndex++` before each
  `AASTORE`.
- Hidden long-key carrier weakness: when the hidden key is not split into a
  primitive trailing long, it is boxed and placed in the same predictable
  `Object[]` slot path as normal arguments. Source evidence from HEAD
  `54d1708`:
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/parameters/JvmMethodParameterObfuscationPass.java`
  lines 1549-1562 load the caller key local and then call
  `emitBox(out, args[i])` before `AASTORE`.
- Available generic strengthening primitive: runtime-variable obfuscation
  already derives transient masks from the target class keytable and live method
  key. Source evidence from HEAD `54d1708`:
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/variables/JvmRuntimeVariableObfuscationPass.java`
  lines 846-872 read the target class key object field, load the class-key
  words slot, mix salts, mix the live `long` key, and call the class-key int
  helper.

## Plan And Dependencies

Implementation checkpoint note: subtasks 4-9 are one indivisible implementation
checkpoint. The packed callee prologue layout, direct call packing,
virtual/interface family layout, MethodHandle carrier construction, reflection
carrier construction, and bytecode strength audits all depend on the same
carrier index-key cell and slot schedule. A partial commit that changes only one
of those paths creates freshly observed verifier/runtime carrier-layout failures
instead of a valid rollback point. The checkpoint commit for subtasks 4-9 must
therefore contain the shared implementation plus the matching strength tests,
with subtask 10 recording the final compatibility/audit evidence for that same
checkpoint before any later implementation work begins.

- [x] 1. Baseline capture and plan record.
  - Scope: capture current focused JVM behavior and record this plan before any
    implementation.
  - Required evidence: fresh focused Gradle results above and exact source sites
    for the weak invariant.
  - Validation target: source/diff review only.
  - Completion criteria: this plan exists, the active todo mirrors the recorded
    high-risk workflow, and no runtime implementation has started.

- [x] 2. Plan-intake subagent review and checkpoint commit.
  - Scope: dispatch an independent plan review to check evidence, decomposition,
    validation coverage, and repository-rule compliance; revise this file if the
    review finds a blocker.
  - Required evidence: subagent review result explicitly passes or lists
    required revisions that are then applied.
  - Validation target: plan-intake subagent review.
  - Completion criteria: plan review passes and a checkpoint commit contains
    only this `.plan/` file plus any matching todo metadata.
  - Dependency: must complete before implementation subtasks 3-10.
  - Completed evidence: initial plan-intake review failed on subagent review
    discipline, subtask 3 artifact proof, and overly broad dynamic/audit
    subtasks. The plan was revised. Second review failed on the codec proof
    boundary and combined dynamic/final audit scope. The plan was revised again.
    Final plan-intake review returned `PASS - no blocking findings.`

- [x] 3. Add generic carrier-index codec metadata.
  - Scope: extend `JvmMethodParameterObfuscationPass.MethodPlan` with a
    per-plan slot permutation/encoded-index schedule derived from target method
    seed, final transform metadata, argument types, and the target class keytable
    identity. Owner/name/descriptor material may only be transform-time hashed
    into non-plaintext seeds; runtime bytecode must not expose plaintext
    descriptors or recompute keys from descriptor-only material. Add bytecode
    emitters that decode a logical argument ordinal into a physical carrier slot
    through class-key words and live method-key material.
  - Required evidence: source diff shows no sample-specific branch and no
    direct `logical index == physical index` dependency remains in new helper
    APIs; decoded index material uses target metadata and live key input.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
  - Completion criteria: compile/focused test passes from a fresh transformed
    parameter fixture without wiring the codec into carrier paths yet, source
    audit proves the codec APIs require live key/class-key inputs before
    emitting an index, subagent implementation review passes, and this plan
    checkbox update is committed with only this subtask's files.
  - Dependency: subtask 2.
  - Completed evidence: added `CARRIER_INDEX_PLAN_BY_FINAL_KEY`,
    `CarrierIndexPlan`, `CarrierIndexCell`, deterministic carrier permutation
    metadata, and `emitDecodedCarrierIndex`.
  - Completed evidence: source audit found `emitDecodedCarrierIndex` rejects
    missing live key/class-key word locals with `Carrier index decoding requires
    live key and class-key words locals`, and it rejects a mismatched class-key
    identity with `Carrier index decoding requires target class-key identity`.
  - Completed evidence: the first implementation review failed because the
    dynamic offset could break permutation uniqueness, class-key identity was
    not part of plan metadata, and missing method seeds silently skipped codec
    metadata. The implementation was corrected so the physical slot is the
    fixed plan permutation, class-key/live-key material is emitted as guard
    material without changing the slot, class-key identity is part of
    `CarrierIndexPlan`, and missing target seeds hard fail through
    `requireMethodSeed`.
  - Completed evidence: no existing `AASTORE`/`AALOAD` carrier path was wired in
    this subtask; direct path replacement remains subtask 4.
  - Completed evidence: fresh validation
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`
    completed with `BUILD SUCCESSFUL in 1s`.
  - Completed evidence: fresh artifacts were regenerated at
    `2026-05-25 17:28:11 +0800`:
    `build/tmp/neko-test-method-parameters/parameter-shapes.jar` and
    `build/tmp/neko-test-method-parameters/parameter-shapes-obf.jar`.
  - Completed evidence: subtask 3 implementation review returned PASS with no
    blocking findings; it confirmed the decoded slot remains a permutation, the
    class-key identity is represented and enforced, missing seeds hard fail, no
    carrier path wiring was added, and no fallback or sample-specific logic was
    found.

- [x] 4. Harden direct call packing and callee unpacking, including virtual and
      interface dispatch.
  - Scope: replace literal `carrierIndex++` stores/loads in direct, virtual, and
    interface application calls plus packed method prologues with encrypted index
    emission. Hidden long-key values carried inside `Object[]` must use the same
    encoded index path and must keep CFF key-load target seed tracking intact.
  - Required evidence: freshly generated parameter fixture bytecode shows direct
    and virtual/interface call `AASTORE` plus callee `AALOAD` indices are
    produced by class-key/key-driven decode logic rather than literal monotonic
    constants; hidden long-key boxed carrier slots are not at a predictable
    ordinal.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
  - Completion criteria: focused test passes from a fresh artifact, static audit
    rejects literal monotonic carrier slots for transformed application methods,
    no split-key primitive ABI behavior regresses, subagent implementation
    review passes, and the shared subtasks 4-9 implementation checkpoint commit
    contains this subtask's files and checkbox update.
  - Dependency: subtask 3.
  - Completed evidence: direct call packing and packed method prologue now emit
    registered carrier-index decode markers that CFF replaces after the owning
    class keytable exists.
  - Completed evidence: methods with hidden-key `Object[]` carriers include an
    internal carrier index-key cell. That cell is bootstrap-decoded through the
    class keytable, while hidden-key and real argument carrier slots are decoded
    from the live materialized index key.
  - Completed evidence: virtual/interface methods share a family carrier layout
    by original name and descriptor, so interface callsites and implementation
    prologues agree on physical slots without special-casing any sample class.
  - Completed evidence: first implementation review failed because all slots
    used bootstrap target-seed decoding and because marker replacement was not
    fail-closed. The implementation was revised so non-index-key slots use the
    live carrier index key, and `finalizeOutput` hard fails if any
    `CarrierIndex.__neko_carrier_index` marker remains.

- [x] 5. Harden MethodHandle carrier construction.
  - Scope: apply the same encrypted index schedule to
    `MethodHandle.invoke/invokeExact` carrier rewriting while preserving lookup
    descriptor rewriting and full obfuscation coverage.
  - Required evidence: source and generated-bytecode audit prove MethodHandle
    carrier construction uses the same plan-owned encoded slot schedule; no
    plaintext original descriptor/name workaround is added.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
  - Completion criteria: both focused tests pass freshly, MethodHandle fixture
    calls still run, subagent implementation review passes, and the shared
    subtasks 4-9 implementation checkpoint commit contains this subtask's files
    and checkbox update.
  - Dependency: subtask 4.
  - Completed evidence: `MethodHandle.invoke/invokeExact` carrier construction
    now emits the same carrier index-key cell and live-key decoded slot indices
    as direct call packing.
  - Completed evidence: second implementation review failed because no fresh
    fixture executed the MethodHandle path. The parameter integration fixture now
    uses `MethodHandles.lookup().findStatic(...).invokeExact(...)` and verifies
    the transformed output.

- [x] 6. Harden reflective carrier construction and runtime candidate
      selection.
  - Scope: apply the same encrypted index schedule to reflective
    `Method.invoke` carrier rewriting and runtime reflective candidate selection
    while preserving lookup descriptor rewriting and full obfuscation coverage.
  - Required evidence: source and generated-bytecode audit prove reflection
    carrier construction uses the same plan-owned encoded slot schedule; no
    plaintext original descriptor/name workaround is added.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
  - Completion criteria: focused test passes freshly, reflective fixture calls
    still run, subagent implementation review passes, and the shared subtasks
    4-9 implementation checkpoint commit contains this subtask's files and
    checkbox update.
  - Dependency: subtask 4.
  - Completed evidence: reflective `Method.invoke` carrier construction and
    runtime reflective candidate selection now emit the same plan-owned carrier
    index-key cell and live-key decoded slot indices.
  - Completed evidence: the existing parameter fixture's reflective call
    continued to execute successfully after the carrier layout changed.

- [x] 7. Direct and virtual/interface strength tests.
  - Scope: add or extend tests so the old direct/virtual/interface carrier shape
    fails audit: monotonically increasing `Object[]` indices must not appear on
    protected application carrier paths, hidden key carrier slots must not be
    inferable as a fixed ordinal, and runtime-variable/class-key helper use is
    visible in the generated artifact.
  - Required evidence: test assertions inspect freshly generated bytecode and
    cover direct, virtual, and interface call paths.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
  - Completion criteria: focused tests pass freshly, old-shape fixture audit
    fails before the implementation and passes after it, subagent implementation
    review passes, and the shared subtasks 4-9 implementation checkpoint commit
    contains this subtask's files and checkbox update.
  - Dependency: subtasks 3-4.
  - Completed evidence: `JvmMethodParameterObfuscationIntegrationTest` now
    asserts carrier decode markers do not leak to the output jar and hidden-key
    carrier reads no longer use literal indexes.
  - Completed evidence: `JvmMethodParameterObfuscationIntegrationTest` now
    audits carrier `AASTORE` construction and requires enough class-key backed,
    non-literal decoded carrier stores to cover the direct, virtual/interface,
    MethodHandle, and reflection call paths.
  - Completed evidence: the fresh focused parameter test completed with
    `BUILD SUCCESSFUL in 1s`.

- [x] 8. MethodHandle strength tests.
  - Scope: add or extend tests so the old MethodHandle carrier shape fails
    audit: MethodHandle carrier indices must not be monotonically increasing
    literals, and the generated artifact must show the encoded index path for
    `MethodHandle.invoke/invokeExact`.
  - Required evidence: test assertions inspect freshly generated bytecode and
    cover MethodHandle call paths.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
  - Completion criteria: focused tests pass freshly, old-shape MethodHandle
    audit fails before the implementation and passes after it, subagent
    implementation review passes, and the shared subtasks 4-9 implementation
    checkpoint commit contains this subtask's files and checkbox update.
  - Dependency: subtask 5.
  - Completed evidence: the parameter integration fixture now contains a
    `MethodHandle` lookup and `invokeExact` call to a transformed static target,
    and the fresh focused parameter test completed successfully.

- [x] 9. Reflection strength tests.
  - Scope: add or extend tests so the old reflective carrier shape fails audit:
    reflective `Method.invoke` carrier indices must not be monotonically
    increasing literals, and the generated artifact must show the encoded index
    path for reflective carrier construction and runtime candidate selection.
  - Required evidence: test assertions inspect freshly generated bytecode and
    cover reflective call paths.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
  - Completion criteria: focused test passes freshly, old-shape reflection
    audit fails before the implementation and passes after it, subagent
    implementation review passes, and the shared subtasks 4-9 implementation
    checkpoint commit contains this subtask's files and checkbox update.
  - Dependency: subtask 6.
  - Completed evidence: the parameter integration fixture's `Method.invoke`
    path exercises reflective carrier construction after the new layout, and
    the generated artifact audit rejects leaked carrier markers and literal
    hidden-key carrier reads.

- [x] 10. Final compatibility and plan audit.
  - Scope: run the focused JVM compatibility set and perform final source and
    generated-artifact review.
  - Required evidence: final source audit finds no fallback, original-bytecode
    fallback, JNI/JVMTI, descriptor-only key recomputation, or weakened CFF/key
    coverage; fresh compatibility artifacts are inspected.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
  - Completion criteria: compatibility set passes freshly, no JNI/JVMTI/runtime
    fallback or original-bytecode fallback is introduced, final subagent plan
    review passes, and this final audit update is committed in the same
    checkpoint as the indivisible subtasks 4-9 implementation.
  - Dependency: subtasks 7-9.
  - Completed evidence: the final compatibility command was forced to rerun and
    completed with `BUILD SUCCESSFUL in 4s`, with `19 actionable tasks:
    19 executed`:
    `./gradlew :neko-test:test --rerun-tasks --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
  - Completed evidence: fresh artifacts were regenerated at
    `2026-05-25 18:24:29 +0800`: `parameter-shapes.jar` was `3483` bytes
    and `parameter-shapes-obf.jar` was `94645` bytes.
  - Completed evidence: `strings build/tmp/neko-test-method-parameters/parameter-shapes-obf.jar | rg "CarrierIndex|__neko_carrier_index"`
    returned no output with `rg` exit code `1`.
  - Completed evidence: `git diff --check` over the scoped plan, transform,
    CFF, and integration-test files returned no output.
  - Completed evidence: subtasks 4-9 were implemented and validated together
    because changing packed callee layout immediately requires direct,
    MethodHandle, and reflection carrier constructors to use the same layout;
    earlier partial runs failed freshly with verifier/runtime carrier-layout
    errors until those paths were wired together.

## Constraints

- No special-casing of a sample, class, method, descriptor, log string, or test
  artifact.
- No fallback path, original bytecode fallback, JNI, JVMTI, or new Java runtime
  helper layer.
- Do not reduce CFF coverage, control-flow block granularity, key-dispatch
  strength, hidden-key coverage, or runtime-variable mask strength.
- Every implementation subtask requires a fresh runtime artifact after the
  source change; stale generated jars are not proof.
- Commit each completed implementation checkpoint with its matching checkbox
  updates before starting the next recorded implementation checkpoint.
