# Native full repair todo

## Scope

Make all native-obfuscation-related tests and the four manual native-only full test-jars pass, then reduce native C compilation latency by changing generation/build architecture so large native output is not compiled as one monolithic C translation unit.

## Invariants

- No JNI fallback, original bytecode fallback, skip-on-error path, JVMTI, or reduced obfuscation coverage.
- Missing VM metadata or unsupported native capability must fail closed.
- Runtime proof must use freshly generated artifacts after each source change.
- Keep `JNI_OnLoad` minimal and do not add Java helper layers outside the existing native loader surface.

## Evidence from previous validation

- `./gradlew test` failed with 6 failures:
  - `Jdk21CompatibilityGradleTest.fullJvmJdk21Compatibility`: verifier error in transformed JVM path.
  - `Jdk21CompatibilityGradleTest.nativeJdk21Compatibility`: native runtime diagnostics include unreasonable derived JNI environment offset.
  - `NativeGeneratedCHotPathAuditTest`, `NativeObfuscationIntegrationTest`, and `NativeObfuscationPerfTest`: fixture path expects `test-jars/TEST.jar`, but repository currently contains `test-jars/test.jar`.
  - `OpcodeTranslatorUnitTest.opcodeTranslator_storesPopIntoLocals`: unit expectation is stale relative to current object-local root storage output.
- Manual native-only full obfuscation with `skipOnError=false` succeeded for four jars: evaluator, test21, snake, test.
- Manual runtime smoke failures:
  - `test21-native-full.jar`: JVM fatal SIGSEGV in monitorenter path through `neko_native_impl_39` and trampoline dispatch.
  - `test-native-full.jar`: self-check reports Loader FAIL and Sec ERROR; Calc 46ms.
  - `evaluator-native-full.jar`: LinkageError in generated lambda path.
  - `snake-native-full.jar`: timed out with no output, likely GUI/event loop; still must be validated appropriately.

## Subtasks

### [x] R1: Restore fixture and stale unit-test correctness

- Scope: update test fixture resolution and object-local store expectation without changing runtime behavior.
- Required evidence: affected tests and current generated C snippet were inspected; fixture names now resolve through one canonical map (`TEST` -> `test.jar`, `SnakeGame` -> `snake.jar`, `obfusjack` -> `test21.jar`).
- Validation command: `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest` and `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`.
- Completion criteria: stale expectation and fixture casing failures are eliminated, with no production transform downgrade.

### [ ] R2: Diagnose and fix native monitor/runtime crash generically

- Scope: fix the `test21` native-only full SIGSEGV through live evidence from generated C, hs_err, manifest mapping, and runtime path.
- Required evidence: identify exact bytecode/native method corresponding to `neko_native_impl_39`, the opcode/runtime helper causing monitorenter corruption, and the violated JVM/native invariant.
- Validation command: regenerate and run native-only full `test21.jar`, then targeted native tests covering monitor paths.
- Completion criteria: no SIGSEGV/SIGABRT/verifier failure and no fallback markers.

### [ ] R3: Diagnose and fix lambda/linkage and loader/security native failures

- Scope: fix evaluator lambda LinkageError and test.jar Loader/Sec failures through generic reflection/loader/lambda native compatibility.
- Required evidence: exact failing class/method/call path and generated artifact inspection.
- Validation command: regenerate/run evaluator and test native-only full jars plus relevant Gradle native tests.
- Completion criteria: evaluator exits successfully, test.jar self-check has no FAIL/ERROR, and native tests pass.

### [x] R4: Split generated native C into functional translation units

- Scope: change native generation/build to emit a common C source plus parallel translated-implementation C chunks instead of compiling every translated body through one monolithic C frontend invocation.
- Required evidence: `NativeBuildEngine` now writes `neko_native_common.c` and `neko_native_impl_N.c` files, compiles them with parallel `zig cc -c` processes using the existing optimization flags, and links the resulting objects with `zig cc -shared`.
- Validation command: `./gradlew :neko-native:compileJava :neko-cli:installDist`; `NEKO_NATIVE_SPLIT_UNITS=4 ... snake.jar ...`; `NEKO_NATIVE_SPLIT_UNITS=8 ... test21.jar ...`.
- Completion criteria: generated native build uses multiple C source files, generated output compiles with `translated>0`, and the manifest records `generated.source.count` plus every structured source path.

### [ ] R5: Final native acceptance sweep

- Scope: run all native-obfuscation-related Gradle tests and manual native-only full four-jar validation.
- Required evidence: command outputs, runtime outputs, generated C/native inspection.
- Validation command: native-related Gradle test set plus manual jar commands.
- Completion criteria: all native-related tests pass, all four manual native artifacts run or have a documented correct validation mode, and no stale artifact is used.
