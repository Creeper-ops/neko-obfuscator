# JVM Validation Sink Hardening - 2026-05-23

## Scope

Implement a generic JVM-only hardening pass for fixed validation sinks whose
accepted value is currently recoverable as a static reversible byte/string
constraint. The first implementation protects constant `String.equals`
validation sites by replacing the plaintext target comparison with a keyed tag
check driven by CFF live state and class key material.

## Constraints

- Do not special-case any sample, class, method, descriptor, passphrase, flag,
  benchmark, or generated name.
- Do not weaken CFF coverage, block boundaries, hidden `long` keys, packed
  `Object[]` carriers, reflection/MethodHandle/lambda/constructor rewriting,
  string obfuscation, constant obfuscation, or invokedynamic obfuscation.
- Do not introduce JNI, JVMTI, native fallback, Java runtime helper classes,
  original-bytecode fallback, skip-on-error, or direct debugger/tool-name aborts.
- Any dynamic analysis signal must feed key/tag material or poison; it must not
  become a default direct abort.
- Use repository `./gradlew` only after explicit permission.

## Todo

- [x] VS-1: Add the JVM validation sink hardening pass and register it in the
  standard JVM pipeline.
  - Scope: detect constant `String.equals` sites in application bytecode after
    CFF metadata exists, replace them with a generated same-class helper that
    computes a keyed 64-bit tag from the runtime candidate string, and compare it
    to an expected tag decoded from live CFF state plus the class key table.
  - Required evidence: source diff shows no plaintext accepted string is left at
    the transformed callsite; the expected tag is masked by live CFF locals and
    class key material; helper code is generated in the same class and remains
    eligible for generated-helper hardening, renaming, string obfuscation, and
    constant obfuscation.
  - Validation command/runtime target: targeted JVM validation covering
    `ControlFlowFlatteningAlgebraicAuditTest`,
    `CffStrongEntrySeedRegressionTest`,
    `JvmStringObfuscationIntegrationTest`,
    `JvmConstantObfuscationIntegrationTest`,
    `JvmInvokeDynamicObfuscationIntegrationTest`, and
    `JvmFullObfuscationPerfTest`, after permission to use `./gradlew`.
  - Completion criteria: fresh validation passes; generated artifact inspection
    shows no raw validation sink target strings at protected callsites; static
    marker scans find no fallback, static-key, descriptor-only recomputation,
    self-cancellation, verifier error, `MethodTooLarge`, or direct abort markers;
    full-JVM performance does not regress relative to the same-run baseline.
  - Validation finding: the first wider run exposed a generic generated-helper
    API gap: synthetic static `[Ljava/lang/Object;` carrier fields with raw `$`
    names were excluded from the post-transform helper API remapper, causing the
    renamer invariant to fail on generated CFF/class-key carrier fields. This is
    part of VS-1 completion because the new validation helper must remain inside
    the existing generated-helper hardening and API-obfuscation surface.
  - Completion evidence: `./gradlew -PbuildDir=build/validation-vsink
    :neko-test:test --rerun-tasks` with the recorded targeted test filters
    completed successfully on 2026-05-23. The validation-sink fixture's
    transformed `check(String,long)` method calls `__neko_vsink(... JJI)Z`
    after live CFF/class-key masked argument decoding and no longer calls
    `String.equals` at the protected sink. Full-JVM TEST output contains
    `Test 1.6: Pool PASS`; static log scans found no verifier, fallback,
    skip-on-error, `MethodTooLarge`, static-key, descriptor-only, or
    self-cancel markers. The generated-helper API remapper now renames
    synthetic static `Object[]` carrier fields, and the renamer fixtures expose
    no raw `$` helper fields.

- [x] VS-2: Add static inverse-regression and artifact audits.
  - Scope: extend JVM tests so a fixed validation string protected by VS-1 cannot
    be recovered by reading a plaintext `LDC` at the comparison site or by
    finding a standalone generated static byte/string/long target field.
  - Required evidence: the test fixture has a normal success path and a wrong
    input failure path; the obfuscated method no longer contains the target
    string adjacent to a `String.equals` invocation.
  - Validation command/runtime target: same targeted JVM validation set as VS-1.
  - Completion criteria: tests fail on the pre-hardening shape and pass on fresh
    obfuscated artifacts without adding sample-specific rules.
  - Implementation note: extend the focused validation-sink audit to inspect the
    generated helper body and class fields, proving the protected target is not
    left in the helper and that no standalone generated static byte/string/long
    target carrier is emitted.
  - Completion evidence: the same targeted Gradle validation set passed on
    2026-05-23 after the audit expansion. `ControlFlowFlatteningAlgebraicAuditTest`
    now asserts that the protected `check(String,long)` method has no plaintext
    target LDC and no `String.equals`, the generated `__neko_vsink` helper has no
    plaintext target and no equality shortcut, and the class has no synthetic
    static `[B`, `String`, `String[]`, or `long` target-carrier field. Fresh
    artifact inspection showed the helper uses `String.length`, `String.charAt`,
    and a tag compare only.

- [ ] VS-3: Add low-risk polymorphic formula variants.
  - Scope: after VS-1/VS-2 are accepted, vary constant/string/indy formula or
    payload ordering without changing CFF block construction, row layout, helper
    ABI, or native paths.
  - Required evidence: exact generated shape currently has fixed formula/layout
    repetition and the chosen variant removes that repetition without increasing
    helper/API or performance pressure.
  - Validation command/runtime target: same targeted JVM validation set plus
    generated helper/API and artifact-size comparison.
  - Completion criteria: fresh validation passes and helper/API, artifact size,
    and runtime medians do not regress.
