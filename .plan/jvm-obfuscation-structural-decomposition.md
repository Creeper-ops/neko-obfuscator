# JVM obfuscation structural decomposition plan

## Non-negotiable constraints

- Preserve every JVM obfuscation invariant from `AGENTS.md`: no fallback behavior, no reduced obfuscation coverage, no static key exposure, no descriptor-only key recomputation, no JNI/JVMTI/helper-layer escape hatch.
- This plan is structural unless a separate subtask records concrete runtime/artifact evidence for a behavioral change.
- Keep `StandardJvmPasses` as the central JVM pass registration boundary unless a later subtask records concrete evidence for moving it safely.
- Do not use repository `./gradlew` until the user grants permission.
- Do not touch unrelated current native-code/test changes shown by `git status --short`.

## Current evidence

- The JVM transform directory is flat and oversized: `ControlFlowFlatteningPass.java` is about 481.7KB, `JvmStringObfuscationPass.java` about 145.1KB, `JvmInvokeDynamicObfuscationPass.java` about 129.7KB, `JvmMethodParameterObfuscationPass.java` about 68.8KB, `JvmKeyDispatchPass.java` about 58.4KB, `JvmRenamerPass.java` about 47.3KB, and `JvmConstantObfuscationPass.java` about 30.4KB.
- `StandardJvmPasses.register()` is the public JVM registration boundary and instantiates the JVM passes in order.
- Static dependency mapping shows `JvmPassBytecode` is consumed by CFF, key-dispatch, parameter, constant, invokedynamic, and string passes. `JvmCodeSizeEstimator` is consumed only by the constant pass.
- Static dependency mapping shows direct subpackage moves of pass classes are blocked by package-private coupling: `JvmKeyDispatchPass` helper APIs, CFF metadata records/constants, and `JvmMethodParameterObfuscationPass.CFF_KEY_LOAD_TARGET_SEED` are package-private today.

## Target layout

- `dev.nekoobfuscator.transforms.jvm` keeps `StandardJvmPasses`.
- `dev.nekoobfuscator.transforms.jvm.internal` owns shared JVM bytecode utilities.
- Later subtasks may split functional passes into:
  - `dev.nekoobfuscator.transforms.jvm.renamer`
  - `dev.nekoobfuscator.transforms.jvm.key`
  - `dev.nekoobfuscator.transforms.jvm.parameters`
  - `dev.nekoobfuscator.transforms.jvm.cff`
  - `dev.nekoobfuscator.transforms.jvm.invoke`
  - `dev.nekoobfuscator.transforms.jvm.constants`
  - `dev.nekoobfuscator.transforms.jvm.strings`

## Subtasks

[x] JVMSPLIT-1: Move shared bytecode helpers

- Scope: Move `JvmPassBytecode` and `JvmCodeSizeEstimator` into `dev.nekoobfuscator.transforms.jvm.internal`, make only their existing helper methods/classes public, update existing callers, and repair the existing CFF shared island-runtime-source helper declaration mismatch that blocked compilation. Do not alter key material, CFF block construction, pass order, transform eligibility, or fallback behavior.
- Required evidence: Static reference map showed `JvmPassBytecode` callers in CFF, key-dispatch, parameter, constant, invokedynamic, and string passes; `JvmCodeSizeEstimator` callers in CFF and constant passes. Compile evidence exposed a CFF invariant mismatch: `ensureSharedClassHelpers` created and consumed island-runtime-source helper metadata and called `installCffIslandRuntimeSourceHelper`, but `CffSharedClassHelpers`/`CffClassKeyTable` did not carry those fields and the install method was absent.
- Validation command/runtime target: `env GRADLE_USER_HOME=.gradle-user-home ./gradlew :neko-transforms:compileJava --rerun-tasks`.
- Completion criteria: Compilation succeeds; shared helper references resolve through `dev.nekoobfuscator.transforms.jvm.internal`; the CFF island-runtime-source shared helper has matching metadata fields and generated-helper installation; unrelated native-code changes are not modified.
- Completion evidence: After user approval for `./gradlew`, the first compile failed at `ControlFlowFlatteningPass.java:843-845`, `:966`, and `:975` with missing island-runtime-source shared-helper accessors/install method/record constructor shape. The source now adds the missing CFF metadata fields and a generated helper with descriptor `(JIIIIII)I` driven by the existing live key/guard/path/block runtime-source cursor logic. Rerunning the same compile command succeeded with `BUILD SUCCESSFUL in 1s`, `5 actionable tasks: 5 executed`.

[ ] JVMSPLIT-2: Extract explicit shared JVM-obfuscation APIs

- Scope: Introduce narrow public/internal APIs for currently package-private coupling between key-dispatch, CFF metadata, parameter carrier metadata, constants, invokedynamic, and string passes. This subtask is API-only unless concrete evidence justifies implementation movement.
- Required evidence: Exact symbols and callsites that require cross-package access, including `JvmKeyDispatchPass` helper APIs, CFF metadata records/constants, and `CFF_KEY_LOAD_TARGET_SEED` consumers.
- Validation command/runtime target: After user approval for repository `./gradlew`, compile `neko-transforms` and run targeted JVM obfuscation tests covering key-dispatch/CFF/string/indy interactions if any behavior-bearing API changes are made.
- Completion criteria: API boundaries are explicit, minimal, documented by names/types, and do not expose raw keys, plaintext reflective material, fallback behavior, or transform skip paths.

[x] JVMSPLIT-3: Move functional pass families

- Scope: Move pass implementations into functional source folders while preserving `StandardJvmPasses.register()` order and public registry IDs. `JvmRenamerPass` is moved into a real `dev.nekoobfuscator.transforms.jvm.renamer` package because static references showed it is independent except for registry construction. The heavily coupled key/CFF/parameter/constant/invokedynamic/string passes are moved into functional folders while retaining the existing Java package until JVMSPLIT-2 introduces safe explicit APIs.
- Required evidence: LSP references for `JvmRenamerPass` showed only self/classloader usage and `StandardJvmPasses`; static coupling evidence showed the other pass families still depend on package-private CFF/key/parameter metadata and should not be forced through broad public APIs in the same subtask.
- Validation command/runtime target: `env GRADLE_USER_HOME=.gradle-user-home ./gradlew :neko-transforms:compileJava --rerun-tasks`.
- Completion criteria: Functional source files are no longer flat in the root `jvm` directory, all callers compile, pass behavior and ordering remain unchanged, and no fallback/static-key/coverage weakening is introduced.
- Completion evidence: Root `transforms/jvm` now keeps `StandardJvmPasses` plus focused folders `renamer`, `key`, `parameters`, `cff`, `invoke`, `constants`, `strings`, and `internal`. `StandardJvmPasses` still registers the same pass IDs in the same order. LSP diagnostics for all JVM transform Java files reported no issues, and the compile command completed with `BUILD SUCCESSFUL in 1s`, `5 actionable tasks: 5 executed`.

[x] JVMSPLIT-4: Decompose oversized pass internals

- Scope: Split large pass internals into cohesive implementation classes under their functional folders, starting with non-bytecode-emitting CFF dry-run statistics because they are isolated data accumulation/reporting state.
- Required evidence: Search found `CffIslandDryRunStats`, `CffIslandDryRunMethodStats`, `CffIslandMaterialOpDryRunStats`, and `CffIslandMaterialOpDryRunMethodStats` were referenced only inside `ControlFlowFlatteningPass`; they do not emit bytecode, mutate keys, alter CFF blocks, or participate in helper ABI construction.
- Validation command/runtime target: `env GRADLE_USER_HOME=.gradle-user-home ./gradlew :neko-transforms:compileJava --rerun-tasks`.
- Completion criteria: File sizes and responsibilities are reduced without changing generated semantics, key propagation, CFF coverage, helper ABI, or runtime artifacts except for source organization.
- Completion evidence: Extracted the CFF island dry-run statistics classes into `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffIslandDryRunStats.java`, leaving `ControlFlowFlatteningPass` with only the pass-owned accessors. LSP diagnostics for all JVM transform Java files reported no issues, and the compile command completed with `BUILD SUCCESSFUL in 1s`, `5 actionable tasks: 5 executed`.

[x] JVMSPLIT-5: Split JVM transform Java packages

- Scope: Change the Java package declarations to match the functional folder layout for JVM obfuscation passes. Keep `StandardJvmPasses` as the root registration boundary, promote only the cross-package APIs that current callers already use, and do not change transform order, key material, CFF block construction, fallback behavior, or native/JNI behavior. Runtime-test evidence exposed two package-cutover regressions in cross-pass metadata use: packed hidden-key transfer metadata was detached from CFF rewriting for Object-array carriers, and reflective lookup owner discovery could bind parameter-type literals instead of the receiver class literal.
- Required evidence: Static search shows the full cross-package surface: CFF depends on key-dispatch and string pass IDs; constants/invokedynamic/string depend on CFF metadata and key-dispatch generated-node/key APIs; key-dispatch and parameter packing share the generated CFF key-load seed map; `StandardJvmPasses` constructs all pass classes. Focused runtime failure showed `ParameterShapes$Impl.work` received the wrong packed hidden key through an interface/Object-array carrier, then after that fix showed reflective lookup for `reflectTarget` was rewritten to `Object[]` while the method ABI was `Object[], long`. The JVM-related Gradle tests are `ControlFlowFlatteningAlgebraicAuditTest`, `CffStrongEntrySeedRegressionTest`, `JvmConstantObfuscationIntegrationTest`, `JvmInvokeDynamicObfuscationIntegrationTest`, `JvmMethodParameterObfuscationIntegrationTest`, `JvmRenamerIntegrationTest`, `JvmStringObfuscationIntegrationTest`, `JvmFullObfuscationPerfTest`, and `ObfuscationIntegrationTest`.
- Validation command/runtime target: `env GRADLE_USER_HOME=.gradle-user-home ./gradlew :neko-test:test --rerun-tasks --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.ObfuscationIntegrationTest`.
- Completion criteria: All listed JVM-related Gradle tests pass; package declarations match folders; exported APIs are minimal and existing-behavior only; source scan shows no newly introduced forbidden fallback/JNI/static-decrypt markers; unrelated native-code/test changes remain untouched.
- Completion evidence: The package declarations now match the functional folders and `StandardJvmPasses` imports/registers all JVM passes in the original order. CFF now resolves packed-call target seeds recorded by parameter packing, rewrites detached generated Object-array hidden-key loads from live CFF state, and uses a shared long-return key-transfer material helper to keep generated callsites below class/method size limits without exposing static keys. Reflective lookup owner discovery now ignores parameter-type literals until the method-name literal has been found, preserving split-hidden-key decisions for the actual lookup owner. Focused `JvmMethodParameterObfuscationIntegrationTest` and `CffStrongEntrySeedRegressionTest` passed, then the full listed JVM test command passed with `BUILD SUCCESSFUL in 18s`, `19 actionable tasks: 19 executed`. A forbidden-marker source scan over JVM transforms/tests found no new JNI/JVMTI/fallback/original-bytecode path; hits were existing hard errors, reflection names, test `throws Exception`, and runtime-test assertions.
