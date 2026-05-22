# CFF Poison Fake Diversion - 2026-05-22

## Scope

Replace generated control-flow-flattening poison/default runtime sinks that directly
construct and throw `IllegalStateException` with verifier-valid fake control-flow
diversion. The change is generic to CFF dispatcher generation and must not special
case any jar, class, method, benchmark, or observed stack trace.

## Evidence

- Generated inline CFF island dispatchers still materialize a shared poison label
  in `CffDispatchEmitter.insertIslandDispatchers`. That runtime path emits
  `NEW java/lang/IllegalStateException`, invokes its constructor, and executes
  `ATHROW` after poison key stepping.
- Generated outlined island dispatch helpers still materialize a helper-local
  poison label in `CffTransitionOutliner.createIslandDispatchHelper`. That runtime
  path emits the same `IllegalStateException` allocation and `ATHROW` after
  materialized poison key stepping.
- These paths are generated runtime bytecode. They make mismatch locations obvious
  to a reverser and violate the requested behavior that mismatches should corrupt
  live CFF/key state and continue into misleading control flow instead of directly
  failing at the poison block.

## Todo

- [x] Scope: Replace inline dispatcher poison throws with live-state fake diversion.
  Required evidence: source diff shows the generated `IllegalStateException` and
  `ATHROW` sequence removed from `CffDispatchEmitter` runtime poison emission.
  Validation: focused CFF audit test and generated artifact inspection.
  Completion criteria: inline poison path performs poison key stepping and exits
  through a bounded key-derived fake return for normal methods, while constructor
  paths keep verifier-safe fake block diversion. No direct runtime
  `IllegalStateException` throw is generated. Fresh validation passed with
  `./gradlew -PbuildDir=build/validation-cff-poison :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --rerun-tasks`.

- [x] Scope: Replace outlined helper poison throws with fake diversion.
  Required evidence: source diff shows the generated `IllegalStateException` and
  `ATHROW` sequence removed from `CffTransitionOutliner` runtime poison emission.
  Validation: focused CFF audit test and generated artifact inspection.
  Completion criteria: outlined poison path performs materialized poison key
  stepping and returns an invalid router token through the existing out-local
  protocol so the caller-side bounded poison exit handles the mismatch. Fresh
  validation passed with the focused CFF audit command above.

- [x] Scope: Add structural regression coverage.
  Required evidence: audit test inspects freshly generated CFF output for generated
  poison exception sinks.
  Validation: `ControlFlowFlatteningAlgebraicAuditTest`.
  Completion criteria: the test rejects generated `IllegalStateException`/`ATHROW`
  poison sinks while the fresh obfuscated audit jar still preserves normal output
  and tampered jars still poison protected flow. Fresh validation passed with the
  focused CFF audit command above.

- [x] Scope: Regenerate and smoke the five requested test jars after the focused
  validation passes.
  Required evidence: fresh artifacts in `/mnt/d/Code/Reverse/NekoOBF/` and runtime
  smoke output for the same full-JVM obfuscation profile.
  Validation: run the existing obfuscation command path and smoke the generated
  jars.
  Completion criteria: no new G18/keytable `IllegalStateException` initializer
  failure is introduced; GUI-only jars may fail only for the local display reason.
  Completion evidence: freshly regenerated `ctf-obf.jar`, `evaluator-obf.jar`,
  `snake-obf.jar`, `test-obf.jar`, and `test21-obf.jar` into
  `/mnt/d/Code/Reverse/NekoOBF/` with `test-jars/full-jvm-obf.yml` after commit
  `4c60217`. Smoke runs: `evaluator-obf.jar` completed, `test-obf.jar` completed
  with the existing `Sec ERROR` and `Calc: 176ms`, `test21-obf.jar` completed with
  `=== All tests completed ===`, `snake-obf.jar` stayed alive until the 15s GUI
  timeout with no initializer exception, and `ctf-obf.jar` exited without a
  G18/keytable initializer exception.
