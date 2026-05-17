# Native Compile Parallelization Plan - 2026-05-17

## Objective

Reduce native obfuscation compile latency by changing generated native output
from one monolithic C translation unit into multiple C translation units that
can be compiled in parallel, while preserving native runtime semantics and
obfuscated output behavior.

## Evidence Recorded Before Implementation

- `CCodeGenerator.generateSource(...)` currently emits all support code,
  translated native implementations, manifest tables, dispatchers, trampolines,
  and `JNI_OnLoad` into one generated source string.
- `NativeBuildEngine.build(...)` currently writes only `neko_native.c` plus
  `neko_native.h`, then invokes one `zig cc -shared ... neko_native.c`.
- The generated build manifest currently records only `generated.c.path` and
  one compiler command per target, so existing tests inspect a single source
  file.
- Existing focused native fixtures cover three jars through
  `NativeObfuscationHelper`: `test.jar`, `snake.jar`, and `test21.jar`.
- Existing `.plan/native-full-repair.md` identifies four manual native-only full
  jars: `evaluator.jar`, `test21.jar`, `snake.jar`, and `test.jar`.

## Runtime Target Rows

### [x] P1: Split generated source model

- Scope: introduce a generated-source artifact model that can represent one
  shared support translation unit plus method implementation translation units.
- Required evidence: code references showing all current source consumers are
  updated from one source string to a structured source set.
- Validation command or runtime target: compile-time tests covering
  `NativeTranslator.translate(...)` and `CCodeGenerator` source generation.
- Completion criteria: unit tests can still inspect generated source content,
  and no source consumer assumes a single generated C file.
- Fresh validation: `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest` passed after introducing
  `GeneratedSourceSet`; legacy `TranslationResult.source()` still returns the
  monolithic source for existing source-inspection tests.

### [x] P2: Parallel native compilation and link

- Scope: change `NativeBuildEngine` to write all generated source files, compile
  each `.c` file to an object file, and link objects into the target library.
- Required evidence: generated build manifest lists all source and object files;
  target command lines show per-source compile commands plus a link command.
- Validation command or runtime target: `R-build` using the repository Gradle
  wrapper, with generated manifest inspection.
- Completion criteria: native build produces a library from multiple object
  files and preserves hard failure on compile/link errors.
- Fresh validation: `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest` passed. The fresh
  TEST fixture manifest recorded 4 generated C files; the obfusjack manifest
  recorded 5 generated C files. Both builds linked object files into
  `libneko_linux_x64.so` without fallback.

### [x] P3: Generated-C audit compatibility

- Scope: update generated-C audit and performance capture tests to consume all
  generated C paths rather than a single `generated.c.path`.
- Required evidence: tests parse manifest source list and audit every generated
  C file.
- Validation command or runtime target: targeted native generated-C audit test.
- Completion criteria: audit still finds translated `neko_native_impl_*`
  regions and reports forbidden JNI/fallback markers across all generated
  source files.
- Fresh validation: `NativeGeneratedCHotPathAuditTest` parsed
  `generated.c.count` / `generated.c.N.path`, concatenated every generated C
  file for each artifact, found translated `neko_native_impl_*` regions, and
  completed successfully.

### [ ] P4: Runtime equivalence for four jars

- Scope: freshly regenerate native-obfuscated artifacts for
  `evaluator.jar`, `test21.jar`, `snake.jar`, and `test.jar`, then compare
  their runtime outputs with baseline jars using the same application args and
  documented non-interactive mode where required.
- Required evidence: fresh obfuscation logs, generated manifest inspection,
  stdout/stderr for baseline and native runs, and `hs_err` scan.
- Validation command or runtime target: manual four-jar baseline-vs-native run
  plus existing native integration/perf tests.
- Completion criteria: all four native outputs match the accepted baseline
  output contract, no fatal JVM error occurs, no `translated=0`, no
  `Native compilation produced no libraries`, and no fallback marker appears.
- Current checkpoint evidence: after commit `b8fc403`, fresh native-only
  generation produced split C source counts `test=8`, `test21=13`, `snake=4`,
  and `evaluator=17`; `test` and `snake` runtime runs matched their accepted
  exit contracts, while `test21-native` crashed with SIGSEGV and
  `evaluator-native` exited 1. This row remains open.

### [ ] P5: Remove duplicated impl prelude compilation

- Scope: keep the same Zig compiler and optimization flags, but move the common
  translated-implementation prelude out of each `neko_native_impl_*.c` body into
  a generated implementation header that is compiled once as a PCH and included
  by every impl compile.
- Required evidence: generated manifests show the implementation header/PCH
  path, impl C files contain only translated function chunks plus markers, and
  compile commands still use `zig cc`, `-O3`, and the existing target flags.
- Validation command or runtime target: `R-build` through
  `NativeGeneratedCHotPathAuditTest`, then fresh native-only generation for the
  four test jars with manifest source-size and elapsed-time inspection.
- Completion criteria: native libraries are built from split impl C files plus
  the precompiled impl prelude, no compile/link fallback occurs, generated C
  audit still covers every impl file, and the measured build path moves toward
  the requested 5s target without reducing compile optimization.
- Current evidence: rejected. Fresh four-jar generation showed the PCH step was
  a serial bottleneck, not a parallelization improvement: `test.jar` and
  `evaluator.jar` spent about 21.8s in the precompiled header step, and
  `test21.jar` spent about 61.8s before any impl compile could run. This row
  remains open and is not the current implementation path.

### [ ] P6: Remove generated-C warning-output overhead

- Scope: keep the same Zig compiler and optimization flags while suppressing
  warnings for machine-generated C units so native builds do not spend time
  formatting and storing repeated unused-function/unused-variable diagnostics.
- Required evidence: manifest compile commands still contain `zig cc`, `-O3`,
  `-march=x86_64_v3`, and existing optimization flags, while warning output is
  suppressed and compiler output no longer dominates manifest size.
- Validation command or runtime target: focused native generated-C audit test
  and fresh native-only generation timing for the four test jars.
- Completion criteria: generated libraries still build, source audit remains
  green, and measured build time improves without changing transform coverage,
  CFF granularity, Zig, or compiler optimization level.
- Current evidence: warning output suppression is retained in the active build
  path as `-w` while keeping `zig cc`, `-O3`, `-march=x86_64_v3`,
  `-fno-plt`, `-fno-semantic-interposition`, `-fmerge-all-constants`, and
  `-funroll-loops`. `NativeGeneratedCHotPathAuditTest` passed with this command
  shape.

### [ ] P7: Remove forced recursive flattening from translated C entry bodies

- Scope: keep `zig cc`, `-O3`, `-march=x86_64_v3`, and all existing compiler
  optimization flags, but stop marking every translated method entry with the
  recursive `NEKO_FLATTEN` attribute. Keep `NEKO_HOT` and keep helper-level
  `always_inline` attributes where the generated runtime requires them.
- Required evidence: a generated `test.jar` max impl experiment showed
  `neko_native_impl_0.o` shrinking from 3.5MB to 842KB and compiling in under
  one second when only `NEKO_FLATTEN` was removed, with Zig and `-O3` unchanged.
- Validation command or runtime target: focused generated-C audit, fresh
  native-only generation timing, and runtime comparison for the four test jars.
- Completion criteria: native generation reaches the requested speed target for
  representative jars or records the remaining long-tail evidence, and runtime
  behavior remains equivalent under the accepted output contracts.
- Current evidence: rejected for now. Removing `NEKO_FLATTEN` produced much
  faster C compiles, but fresh runtime comparison then regressed:
  `test-native` reported `Test 2.5: Loader FAIL` and `evaluator-native` threw
  `java.lang.LinkageError` in `TestManager$NekoLambda$5.accept`. The active
  code keeps `NEKO_FLATTEN`.

### [x] P8: Externalize impl prelude and split impl chunks to one method

- Scope: keep `zig cc`, `-O3`, and existing target optimization flags; compile
  common non-inline support helpers and per-site state once in
  `neko_native_support.c`; generate a lightweight
  `neko_native_impl_prelude.h` contract for impl units; split impl units to one
  translated method per `.c` file.
- Required evidence: generated manifests show the implementation header path,
  impl files include the generated contract header instead of duplicating the
  full prelude, support symbols are hidden extern definitions, no PCH command is
  emitted, and compile commands keep Zig and optimization flags unchanged.
- Validation command or runtime target: `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`.
- Completion criteria: generated C audit passes, native libraries link from the
  support source plus one-method impl sources, and no compile/link fallback or
  `translated=0` occurs in the audited fresh artifacts.
- Fresh validation: the validation command passed. Fresh audit manifests
  recorded `generated.c.count=50` for `test.jar` and `generated.c.count=94` for
  `test21.jar`; both manifests retained `zig cc -c -O3 ... -march=x86_64_v3
  -fno-plt -fno-semantic-interposition -fmerge-all-constants -funroll-loops`
  and recorded no PCH command. Manual per-source timing on the fresh
  `test21.jar` artifact showed `neko_native_support.c` compiles in about 2.0s,
  but the single translated method `neko_native_impl_39.c`
  (`org/example/Main.main([Ljava/lang/String;)V`) still takes about 32.5s with
  Zig `-O3` and `NEKO_FLATTEN`. This proves the remaining >5s bottleneck is now
  inside one translated C function and cannot be further parallelized by file
  splitting alone.

## Notes

- This plan must not change JVM obfuscation transforms, method selection,
  control-flow flattening, key propagation, JNI fallback policy, or runtime
  helper policy.
- The implementation must be architecture-level: no jar-, class-, method-, log-,
  benchmark-, or test-specific compile split.
- Each completed subtask requires a fresh validation run and a commit containing
  only that subtask's implementation plus this todo update.
