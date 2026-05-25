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

- [ ] 3. Add generic carrier-index codec metadata.
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

- [ ] 4. Harden direct call packing and callee unpacking, including virtual and
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
    review passes, and this subtask has its own commit.
  - Dependency: subtask 3.

- [ ] 5. Harden MethodHandle carrier construction.
  - Scope: apply the same encrypted index schedule to
    `MethodHandle.invoke/invokeExact` carrier rewriting while preserving lookup
    descriptor rewriting and full obfuscation coverage.
  - Required evidence: source and generated-bytecode audit prove MethodHandle
    carrier construction uses the same plan-owned encoded slot schedule; no
    plaintext original descriptor/name workaround is added.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest`.
  - Completion criteria: both focused tests pass freshly, MethodHandle fixture
    calls still run, subagent implementation review passes, and this subtask has
    its own commit.
  - Dependency: subtask 4.

- [ ] 6. Harden reflective carrier construction and runtime candidate
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
    still run, subagent implementation review passes, and this subtask has its
    own commit.
  - Dependency: subtask 4.

- [ ] 7. Direct and virtual/interface strength tests.
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
    review passes, and this subtask has its own commit.
  - Dependency: subtasks 3-4.

- [ ] 8. MethodHandle strength tests.
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
    implementation review passes, and this subtask has its own commit.
  - Dependency: subtask 5.

- [ ] 9. Reflection strength tests.
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
    implementation review passes, and this subtask has its own commit.
  - Dependency: subtask 6.

- [ ] 10. Final compatibility and plan audit.
  - Scope: run the focused JVM compatibility set and perform final source and
    generated-artifact review.
  - Required evidence: final source audit finds no fallback, original-bytecode
    fallback, JNI/JVMTI, descriptor-only key recomputation, or weakened CFF/key
    coverage; fresh compatibility artifacts are inspected.
  - Validation command:
    `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest`.
  - Completion criteria: compatibility set passes freshly, no JNI/JVMTI/runtime
    fallback or original-bytecode fallback is introduced, final subagent plan
    review passes, and this final audit update has its own commit if files
    changed.
  - Dependency: subtasks 7-9.

## Constraints

- No special-casing of a sample, class, method, descriptor, log string, or test
  artifact.
- No fallback path, original bytecode fallback, JNI, JVMTI, or new Java runtime
  helper layer.
- Do not reduce CFF coverage, control-flow block granularity, key-dispatch
  strength, hidden-key coverage, or runtime-variable mask strength.
- Every implementation subtask requires a fresh runtime artifact after the
  source change; stale generated jars are not proof.
- Commit each completed implementation subtask with its matching checkbox update
  before starting the next recorded subtask.
