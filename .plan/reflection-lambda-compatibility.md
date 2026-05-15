# Reflection and lambda compatibility plan

## Non-negotiable constraints

- Preserve all JVM obfuscation invariants from `AGENTS.md`: no fallback behavior, no reduced transform coverage, no reflection/lambda special-case deobfuscation, no plaintext reflective application data, no static key exposure, and no removal of reflective or lambda obfuscation surfaces.
- Build a fresh baseline before using any existing `build/test-jvm-full-obf-perf/` artifacts as evidence.
- Do not touch unrelated current native-code/test work shown by `git status --short`.
- Do not use repository `./gradlew` until the user grants permission.
- Do not create files or folders under `/tmp`.

## Current evidence

- User reports current compatibility problems around reflection calls and lambda calls under thick/full JVM obfuscation.
- The repository already has `JvmFullObfuscationPerfTest`, which creates `build/test-jvm-full-obf-perf/`, obfuscates TEST/obfusjack/SnakeGame/evaluator with `test-jars/full-jvm-obf.yml`, runs runnable original and full-obfuscated fixtures, and writes `jvm-full-obf-performance-baseline.json`.
- Existing `build/test-jvm-full-obf-perf/` outputs are stale for this task and must not be used as proof until regenerated.
- `git status --short` shows unrelated modified native-code/test files and untracked cache/import-looking files; this task must avoid changing or reverting them.

## Subtasks

[x] RLC-1: Regenerate fresh full-obf baseline

- Scope: Remove only stale baseline outputs under `build/test-jvm-full-obf-perf/` if needed, then run the existing full JVM obfuscation baseline test to regenerate artifacts from current sources. Do not modify transform code in this subtask.
- Required evidence: Fresh command output from the baseline run, fresh timestamps/artifacts in `build/test-jvm-full-obf-perf/`, and captured original/full-obfuscated stdout/stderr for runnable fixtures.
- Validation command/runtime target: After user approval for repository `./gradlew`, run `env GRADLE_USER_HOME=.gradle-user-home ./gradlew :neko-test:test --rerun-tasks --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest`.
- Completion criteria: Fresh TEST and obfusjack original/full-obf run logs exist; pass/fail output identifies the current compatibility failure without relying on stale artifacts; no source files are edited.
- Completion evidence: After explicit user permission for the repository Gradle wrapper, stale `build/test-jvm-full-obf-perf/` outputs were removed and the validation command completed with `BUILD SUCCESSFUL in 13s`, `19 actionable tasks: 19 executed`. The fresh report at `build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json` was captured at `2026-05-15T04:42:17.936535299Z`. Fresh TEST logs show the original jar reports `Test 2.1: Counter PASS`, `Test 2.5: Loader PASS`, `Test 2.6: ReTrace PASS`, and `Test 2.7: Annotation PASS`, while the full-obfuscated jar reports `ERROR` for those four reflection-heavy checks. Fresh obfusjack original and full-obfuscated logs both complete with `=== All tests completed ===`; randomized PRNG and timing lines differ as expected for that fixture.

[x] RLC-2: Identify exact reflective/lambda failing invariant

- Scope: From the fresh baseline failure and generated artifacts, identify the exact runtime path, bytecode site, transformed descriptor/name/key metadata, and output divergence for reflection or lambda calls. Use generated jars/logs only from RLC-1.
- Required evidence: Exact failing fixture, class/method/site, original vs obfuscated output difference or exception, and the transform metadata/instruction sequence proving the root cause.
- Validation command/runtime target: Re-run only the failing generated jar or focused fixture command needed to prove the path after RLC-1, without changing code.
- Completion criteria: A concrete evidence chain exists before implementation; no speculation-based code change is made.
- Completion evidence:
  - Re-ran the fresh full-obfuscated TEST artifact with `java -Xint -Xlog:exceptions=info -XX:-UsePerfData -jar build/test-jvm-full-obf-perf/TEST-full-jvm-obf.jar` and captured `build/inspect/test-full-obf-xint-exceptions.txt`. The fresh run still reports `Test 2.1: Counter ERROR`, `Test 2.5: Loader ERROR`, `Test 2.6: ReTrace ERROR`, `Test 2.7: Annotation ERROR`, and `Test 2.8: Sec ERROR`.
  - Loader root cause: `a/da` (`pack/tests/reflects/loader/LTest`) is loaded by custom loader `a/ea`, and its constructor directly invokes package-shared CFF helper `a/a.b([Ljava/lang/Object;IIII)I` at bci 148. `a/a.b` is generated from `pack/Clazz` as package-private `static int b(Object[], int, int, int, int)`. JVM runtime packages include the defining class loader, so `a/da` in loader `a/ea` is not in the same runtime package as app-loader `a/a`; the VM throws `IllegalAccessError`. Source owner: `CffClassSetup.ensureSharedClassHelpers` installs package-shared helpers with only `ACC_STATIC | ACC_SYNTHETIC` unless the helper owner is an interface.
  - Method-array reflection root cause: `CffReflectionMemberFilters.injectedMethodFilter` emits `IFNE skip` followed by unconditional `GOTO syntheticDone`, then an unreachable `Method.getModifiers`/`Modifier.isStatic` check. This malformed injected method-array filter is inserted before CFF and becomes flattened. The failing Counter path reaches this generated method-array filtering path after `Class.getDeclaredMethods`/indy result materialization and then traps in CFF (`a/y.ia` via `a/y.a` at bci 6468). The source invariant is that generated reflection filters must be structurally valid control flow and hide generated synthetic static members without introducing unreachable control-flow islands.
  - Reflective invoke/key-transfer root cause: ReTrace and Annotation reflectively invoke methods whose final ABI is the packed `Object[]` carrier with hidden key material. `JvmMethodParameterObfuscationPass.rewriteReflectiveInvoke` currently wraps the caller-supplied `Method.invoke` argument array as the inner carrier, and `emitRuntimeReflectiveKeySelection` writes the transferred key into the last existing argument slot only when the array is non-empty. It does not allocate the final carrier shape, does not preserve all original user arguments, and does not handle zero-argument reflective calls. This explains `a/ga.x([Ljava/lang/Object;)V` and `a/x.a([Ljava/lang/Object;)V` entering with wrong or missing hidden key material and trapping in CFF. Source owner: `JvmMethodParameterObfuscationPass.rewriteReflectiveInvoke`, `emitRuntimeReflectiveKeySelection`, and candidate matching around reflective `Method.invoke`.

[ ] RLC-3: Patch generic compatibility rewriting

- Scope: Modify the JVM transform architecture at the source of the proven invariant violation. Preserve reflection/lambda obfuscation, Object[] carriers, hidden long keys, dynamic key propagation, CFF, invokedynamic, string, constant, and renamer compatibility. No fallback, skip, helper-layer bypass, or plaintext exposure.
- Required evidence: Source-level mapping from the failing transformed site to the generic rewrite bug and all affected callsite families.
- Validation command/runtime target: Run the smallest focused Gradle test covering the changed path, then regenerate the full-obf baseline.
- Completion criteria: The failing reflective/lambda path is corrected generically; affected tests pass; no forbidden fallback or obfuscation weakening is introduced.

[ ] RLC-4: Final full-obf parity and hardening audit

- Scope: Regenerate `build/test-jvm-full-obf-perf/` after the fix, run runnable original and full-obfuscated jars, compare outputs, and inspect transformed artifacts for forbidden weakening markers.
- Required evidence: Fresh TEST/obfusjack original and full-obf logs after the fix; output parity evidence; static inspection showing no runtime helper class injection or forbidden fallback markers introduced by the fix.
- Validation command/runtime target: `env GRADLE_USER_HOME=.gradle-user-home ./gradlew :neko-test:test --rerun-tasks --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest` plus targeted artifact inspection commands using repository tools.
- Completion criteria: Fresh full-obf artifacts run with outputs consistent with original jars for runnable fixtures; reflection/lambda call points remain obfuscated; no stale artifact is used as proof.
