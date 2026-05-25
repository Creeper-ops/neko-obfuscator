# Runtime Variable No-Leak Hardening - 2026-05-25

## Evidence chain

- Fresh baseline command, run only after permission to use repository Gradle:
  `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
  The command completed with `BUILD SUCCESSFUL in 4s`.
- Fresh generated artifacts from that command:
  `build/tmp/neko-test-runtime-vars/RuntimeVariableShapes.java`,
  `build/tmp/neko-test-runtime-vars/runtime-variable-shapes.jar`, and
  `build/tmp/neko-test-runtime-vars/runtime-variable-shapes-obf.jar` all had
  timestamp `2026-05-25 12:41:31 +0800`.
- Failing primitive invariant: protected primitive values are stored as an
  encoded shadow plus a persistent local mask. Source sites:
  `JvmRuntimeVariableObfuscationPass.java` stores masks at primitive
  initialization (`emitInitialShadow`, current lines 472/478), loads masks at
  primitive stores/loads (`emitStoreShadowFromStack` and `emitLoadFromShadow`,
  current lines 557/562/574/586/610/615/620/632), and compares shadow/mask
  locals in encoded zero branches (`rewriteEncodedZeroBranches`, current lines
  399-402). A local dump that captures both slots can reconstruct the plaintext
  with `value = shadow ^ mask`.
- Fresh artifact observation proving the primitive path: targeted `javap`
  inspection of the freshly generated jar used:
  `javap -classpath build/tmp/neko-test-runtime-vars/runtime-variable-shapes-obf.jar -c -p RuntimeVariableShapes | rg -n "__neko_rv_ref_handle|__neko_rv_mask|ThreadLocal\\.get|ThreadLocal\\.set|anewarray     #4|AASTORE|AALOAD|aastore|aaload|IF_ICMPEQ|IF_ICMPNE|if_icmpeq|if_icmpne"`.
  Representative output from that fresh artifact included
  `630: 599: invokestatic #116 // Method __neko_rv_mask$frop64:(IJ)I`,
  `636: 610: invokestatic #116 // Method __neko_rv_mask$frop64:(IJ)I`,
  `642: 621: invokestatic #116 // Method __neko_rv_mask$frop64:(IJ)I`,
  and encoded branch lines such as
  `491: 304: if_icmpeq 327` / `499: 321: if_icmpne 575`.
- Failing reference invariant: protected references are moved into a per-call
  `ThreadLocal` `Object[]` frame. Source sites:
  `installReferenceFrame` allocates `new Object[]` and installs it into
  `ThreadLocal` (`JvmRuntimeVariableObfuscationPass.java` current lines
  681-705), `emitStoreShadowFromStack` stores references into that frame
  (`AASTORE`, current lines 590-595), `emitLoadFromShadow` reads from that frame
  (`AALOAD`, current lines 642-648), and `ensureReferenceHandleHelper` emits
  `__neko_rv_ref_handle...` (current lines 979-1027). The integer handle
  obscures the index but the frame remains a plaintext, enumerable object
  warehouse.
- Fresh artifact observation proving the reference path: targeted `javap`
  inspections of the freshly generated jar used the command above and:
  `javap -classpath build/tmp/neko-test-runtime-vars/runtime-variable-shapes-obf.jar -v -p RuntimeVariableShapes | rg -n "__neko_rv_ref_handle|__neko_rv_mask|ThreadLocal|get:\\(\\)Ljava/lang/Object|set:\\(Ljava/lang/Object;\\)V|\\[Ljava/lang/Object;|SourceFile"`.
  Representative output from that fresh artifact included
  `315: 12: anewarray #4 // class java/lang/Object`,
  `320: 22: invokevirtual #74 // Method java/lang/ThreadLocal.get:()Ljava/lang/Object;`,
  `324: 30: invokevirtual #78 // Method java/lang/ThreadLocal.set:(Ljava/lang/Object;)V`,
  `679: 686: invokestatic #128 // Method __neko_rv_ref_handle$j8p99d:(III)I`,
  `7847: 665: aastore`, `7851: 672: aaload`, and
  `30125: public static int __neko_rv_ref_handle$j8p99d(int, int, int);`.
- JVM-layer boundary: pure verifier- and GC-correct bytecode cannot make a live
  object reference non-oop. This plan removes the additional plaintext reference
  warehouse and does not claim opaque reference encryption without native
  storage.

## Subtasks

- [x] 1. Plan intake and recorded baseline.
  - Scope: record this high-risk plan, keep it isolated from unrelated native
    worktree changes, record the active todo counterpart, and dispatch a
    subagent plan-intake review before code edits.
  - Required evidence: fresh Gradle-produced baseline timestamps are recorded;
    plan reviewer confirms the decomposition is generic, evidence-backed, and
    compliant with repository constraints.
  - Validation target: plan-intake subagent review.
  - Completion criteria: plan review passes, this plan/todo record is committed
    with `git add -f` because `.plan/` is ignored, and no runtime implementation
    starts before that commit.
  - Completed evidence: plan-intake subagent review passed after evidence and
    dependency-order revisions; checkpoint committed as
    `0e2f534 Plan runtime variable no-leak hardening`.

- [x] 2. Primitive masks become transient stack material with matching tests.
  - Scope: remove persistent runtime-variable primitive mask locals from
    `JvmRuntimeVariableObfuscationPass`; encode/decode int, long, float, double,
    IINC, and encoded zero branches by recomputing the pad transiently from live
    method key/class-key material at each use site. Update the focused test in
    the same subtask so it rejects the old two-local shadow/mask branch pattern
    instead of requiring it.
  - Required evidence: static audit of freshly generated bytecode proves
    protected primitive locals no longer have paired stored mask locals and
    encoded branch rewrites do not load a stable mask slot.
  - Validation command: ask before using repository `./gradlew`, then run
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
  - Completion criteria: focused test passes from a fresh artifact; no plaintext
    primitive local landing or stable shadow/mask decode pair remains.
  - Completed evidence: final focused validation used
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`
    and completed with `BUILD SUCCESSFUL in 1s`.
  - Completed evidence: freshly regenerated
    `build/tmp/neko-test-runtime-vars/RuntimeVariableShapes.java`,
    `runtime-variable-shapes.jar`, and `runtime-variable-shapes-obf.jar` had
    timestamp `2026-05-25 13:10 +0800`.
  - Completed evidence: static grep of
    `JvmRuntimeVariableObfuscationPass.java` for
    `runtimeSeedLocal|emitRuntimeSeed|maskLocal|maskSize|reusedOriginalMask|hasMask`
    returned no matches.
  - Completed evidence: representative fresh `javap` output showed normal mask
    calls as `ldc seed; lload_1; invokestatic __neko_rv_mask`, stack-only
    pending-key rekey calls as `dup2; ldc seed; dup_x2; pop; invokestatic
    __neko_rv_mask; ... lload_1; invokestatic __neko_rv_mask; ixor; ixor;
    istore shadow; lstore_1`, and encoded zero branches using
    `invokestatic __neko_rv_mask; if_icmp*`.
  - Completed evidence: RVNL-2 subagent review returned PASS with no blocking
    findings.

- [x] 3. Remove plaintext reference frame warehouse with matching tests.
  - Scope: remove runtime-variable `ThreadLocal` frame install/restore,
    reference handle generation, and reference `Object[]` frame routing from
    `JvmRuntimeVariableObfuscationPass`. Reference locals are left on their
    original verifier-correct JVM path by this pass; this subtask intentionally
    removes the old null-store assertion because the pass no longer pretends to
    protect references with an encrypted handle. It does not add another
    reference storage surface.
  - Required evidence: static audit of freshly generated bytecode proves no
    runtime-variable `__neko_rv_ref_handle`, no runtime-variable
    `ThreadLocal.get/set` frame pattern, and no per-call reference `Object[]`
    frame emitted by this pass.
  - Validation command: ask before using repository `./gradlew`, then rerun
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`.
  - Completion criteria: focused test passes and reference values are not moved
    into a new enumerable runtime-variable frame.
  - Completed evidence: final focused validation used
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest`
    and completed with `BUILD SUCCESSFUL in 1s`.
  - Completed evidence: freshly regenerated
    `build/tmp/neko-test-runtime-vars/runtime-variable-shapes.jar` had
    timestamp `2026-05-25 13:19:28 +0800`, and
    `build/tmp/neko-test-runtime-vars/runtime-variable-shapes-obf.jar` had
    timestamp `2026-05-25 13:19:29 +0800`.
  - Completed evidence: static grep of
    `JvmRuntimeVariableObfuscationPass.java` for
    `ReferenceFrame|refHandle|REF_HANDLE|ThreadLocal|frameSlot|referenceHandle|containsReferenceShadow|allocateReferenceFrame|installReferenceFrame|emitReferenceFrame|emitCurrentReferenceFrame|emitRestoreReferenceFrame|emitReferenceThreadLocal|TryCatchBlockNode|RUNTIME_VARIABLE_FRAME_SLOT`
    returned no matches.
  - Completed evidence: static product inspection of the fresh obfuscated jar
    returned no `__neko_rv_ref_handle` matches, and scoped inspection of
    non-generated application methods returned no `__neko_rv_ref_handle`,
    `java/lang/ThreadLocal`, or `anewarray java/lang/Object` matches.
  - Completed evidence: RVNL-3 subagent review returned PASS with no blocking
    findings.

- [ ] 4. Documentation and compatibility validation.
  - Scope: update docs/tests to state the exact JVM-layer guarantee and run the
    focused JVM compatibility set without native output.
  - Required evidence: docs no longer claim reference encryption; tests include
    audits for no stable primitive mask pair and no runtime-variable reference
    frame.
  - Validation command: ask before using repository `./gradlew`, then run
    `env JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/tmp ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmRuntimeVariableObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest`.
  - Completion criteria: compatibility set passes freshly; final subagent plan
    review passes; no JNI, JVMTI, native fallback, original-bytecode fallback,
    skip-on-error behavior, sample-specific logic, or CFF/key weakening is
    introduced.

## Constraints

- No sample-, method-, descriptor-, or log-specific implementation.
- No native/JNI/JVMTI fallback and no helper runtime layer.
- Do not reduce CFF coverage, key-dispatch strength, or method-key liveness.
- Do not use older generated artifacts as proof; every claim requires fresh
  post-edit output.
