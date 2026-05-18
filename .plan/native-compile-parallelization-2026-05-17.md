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

### [ ] P9: Compress generated string-concat C without changing native semantics

- Scope: replace repeated per-callsite string-concat boilerplate in translated
  methods with a shared native support helper that normalizes null operands and
  calls the existing fast native `neko_require_fast_string_concat` path. Keep
  `zig cc`, `-O3`, `NEKO_FLATTEN`, native method coverage, and runtime helper
  policy unchanged.
- Required evidence: fresh generated C shows large methods call the shared
  helper instead of embedding repeated lhs/rhs/field-offset boilerplate; the
  long `test21` method compile timing improves; runtime perf gates do not
  regress for the affected native jars.
- Validation command or runtime target: focused generator/native C audit,
  fresh `test21.jar` native-only generation timing, and baseline-vs-native
  runtime comparison for any jar whose generated code is changed.
- Completion criteria: compile speed improves without lowering translated
  runtime behavior or native performance, no JNI/JVMTI/fallback markers are
  introduced, and freshly generated artifacts still report non-zero translated
  counts with no native compilation fallback.

### [-] P10: Split late support and record native compile long-tail evidence

- Scope: keep the same Zig compiler and optimization flags, but split generated
  late support into manifest/bootstrap, signature dispatchers, and trampoline
  translation units; record per-source compile/link elapsed time in the native
  build manifest.
- Required evidence: fresh manifests list `neko_native_manifest.c`,
  `neko_native_dispatchers.c`, and `neko_native_trampolines.c`; compile
  commands still use `zig cc -c -O3`; the manifest records
  `target.*.compile.N.elapsed.ms`.
- Validation command or runtime target: focused generated-C audit, then one
  fresh evaluator native generation to identify the remaining compile tail.
- Completion criteria: native libraries still link without fallback, generated-C
  audit remains green, and the next optimization target is selected from fresh
  per-source elapsed evidence rather than speculation.
- Current evidence: `NativeGeneratedCHotPathAuditTest` passed after splitting
  manifest/dispatcher/trampoline files. Fresh evaluator native generation
  produced 126 C sources and completed successfully with
  `translated=122 rejected=0`, but total time remained `9229ms`. The manifest
  showed `neko_native_support.c=2350ms`, `neko_native_manifest.c=405ms`,
  `neko_native_dispatchers.c=932ms`, `neko_native_trampolines.c=158ms`, and the
  remaining long tail in translated impl files:
  `neko_native_impl_71.c=7923ms`, `neko_native_impl_103.c=6707ms`,
  `neko_native_impl_72.c=6629ms`, and `neko_native_impl_104.c=4895ms`. All four
  long-tail impl files still used `NEKO_FLATTEN`.

### [-] P11: Lower recursive flattening threshold for medium generated bodies

- Scope: keep `zig cc`, `-O3`, `NEKO_HOT`, source splitting, and all native
  runtime paths unchanged, but stop applying recursive `NEKO_FLATTEN` to
  medium generated impl bodies that produce multi-second single-file optimizer
  tails.
- Required evidence: P10 manifest identifies the exact invariant: medium
  translated impl files still using `NEKO_FLATTEN` dominate wall-clock compile
  time after support/trampoline splitting.
- Validation command or runtime target: focused generator/native audit, fresh
  evaluator native generation timing, and baseline-vs-native runtime comparison
  for the regenerated artifacts.
- Completion criteria: the longest evaluator compile source is below the prior
  multi-second tail without changing Zig optimization flags; native runtime
  behavior remains equivalent under the accepted four-jar output contracts.
- Latest evidence: fresh evaluator native generation after lowering the
  threshold to 128 completed in 8886 ms with 126 C sources and `translated=122
  rejected=0`. `neko_native_impl_71.c` and `neko_native_impl_72.c` no longer
  contain `NEKO_FLATTEN`, but still compile in 7673 ms and 6462 ms. Inspecting
  the generated sources shows the exact remaining invariant: Blowfish block
  methods repeatedly inline primitive array-load null/bounds checks and
  exception-constructor dispatch expressions for each `IALOAD`.

### [-] P12: Externalize checked primitive array load bodies

- Scope: keep `zig cc`, `-O3`, `NEKO_HOT`, one-method implementation splitting,
  no-JNI/no-JVMTI runtime semantics, and primitive array direct loads unchanged,
  but move the repeated primitive `xALOAD` null/bounds check body into generic
  hidden native helpers compiled once in support code.
- Required evidence: P11 manifest and generated `neko_native_impl_71.c` prove
  the longest post-flatten compile tail is caused by repeated inline primitive
  array-load check bodies, not by support/trampoline code, link time, or Zig
  job scheduling.
- Validation command or runtime target: focused generator/unit tests, native C
  hot-path audit, one fresh evaluator native generation timing, generated C
  inspection for short checked-load call sites and no forbidden JNI wrappers,
  and baseline-vs-native runtime comparison for the regenerated artifacts.
- Completion criteria: generated primitive array loads call hidden checked-load
  helpers instead of expanding the full null/bounds/exception body at every
  opcode; evaluator native generation is faster than the 8886 ms P11 result
  without changing compiler optimization flags or translated runtime behavior.
- Checkpoint evidence: focused generator/unit tests passed
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest`; native C hot-path
  audit passed
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`.
  Fresh evaluator generation with checked primitive array-load helpers produced
  126 C sources, `translated=122 rejected=0`, and completed once in 4788 ms and
  once in 5053 ms. `neko_native_impl_71.c` and `neko_native_impl_72.c` dropped
  from the P11 7673/6462 ms tail to 462/561 ms in the latest manifest; the new
  longest evaluator impl files are `neko_native_impl_103.c=3408 ms` and
  `neko_native_impl_104.c=1904 ms`, while `neko_native_support.c=2437 ms`.
  Static grep over the changed generated impl/support path found no real
  `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, `Call*Method`, `Get*Field`,
  `NewStringUTF`, `NewObject*`, or `Throw*` calls. Manual four-jar generation
  time rows: `evaluator.jar` 5053 ms (`translated=122 rejected=0`, 126 C
  sources), `test21.jar` 3223 ms (`translated=93 rejected=0`, 97 C sources),
  `snake.jar` 1786 ms (`translated=18 rejected=0`, 22 C sources), and
  `test.jar` 2384 ms (`translated=49 rejected=0`, 53 C sources). Runtime
  behavior remains open: the freshly generated evaluator native jar still threw
  the existing LinkageError stub at
  `dev.sim0n.evaluator.manager.TestManager$NekoLambda$5.accept`, so this row is
  a compile-time checkpoint, not final behavior acceptance.

### [x] P13: Split support and generic callsite scaffolding further

- Scope: keep Zig `-O3`, `NEKO_HOT`, no-JNI/no-JVMTI runtime semantics, and
  translated runtime behavior unchanged, but reduce the remaining fixed support
  compile floor and large invoke-heavy implementation tails by moving repeated
  generic callsite scaffolding out of generated impl bodies and by splitting
  support into smaller architecture-level translation units where dependencies
  allow it.
- Required evidence: latest four-jar manifests prove the next compile-time
  bottlenecks are not primitive `xALOAD` checks anymore: evaluator is dominated
  by `neko_native_impl_103.c=3408 ms`, `neko_native_support.c=2437 ms`, and
  `neko_native_impl_104.c=1904 ms`; `test21.jar` is dominated by support at
  2328 ms plus `neko_native_impl_39.c=2356 ms`; `test.jar` is dominated by
  support at 1938 ms plus invoke-heavy impl files at 1688/1210 ms.
- Validation command or runtime target: focused generator/unit tests, native C
  hot-path audit, one fresh four-jar native generation timing pass, and static
  inspection that generated code still has no forbidden JNI/JVMTI or fallback
  calls.
- Completion criteria: all four native generation rows improve or remain within
  measurement noise versus the P12 checkpoint without reducing compiler
  optimization flags, without changing native coverage, and without adding any
  JNI/JVMTI/original-bytecode fallback.
- Checkpoint evidence: generic source-shape changes only. Inline-cache sites
  and metadata are now stored in contiguous generated C tables with macro
  aliases instead of one exported global per callsite; the exact address identity
  passed to `neko_icache_dispatch` remains per-site (`&neko_icache_*` expands to
  `&neko_icache_sites[index]`). Object `AALOAD`/`AASTORE` null/bounds paths now
  use `NEKO_HOT_INLINE` checked helpers that keep the fast load/store and GC
  barrier logic inline while removing repeated generated branch bodies from
  translated impl functions. Focused tests passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest`. Native C hot-path
  audit passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`.
  Fresh evaluator generation after the inline helper change completed in 4553 ms
  (`translated=122 rejected=0`, 126 C sources, library 14893368 bytes); the
  largest impl compile dropped to `neko_native_impl_103.c=2892 ms`, support was
  `2336 ms`, and the object-array-heavy `neko_native_impl_107.c` generated body
  dropped to 83 lines. Fresh four-jar generation rows:
  `evaluator.jar` 4539 ms (`translated=122 rejected=0`, 126 C sources),
  `test21.jar` 2980 ms (`translated=93 rejected=0`, 97 C sources),
  `snake.jar` 1774 ms (`translated=18 rejected=0`, 22 C sources), and
  `test.jar` 2360 ms (`translated=49 rejected=0`, 53 C sources). This row is a
  compile-time checkpoint; the broader baseline-vs-native runtime-output
  acceptance gate remains separate.

### [ ] P14: Reduce fixed support compile floor without de-inlining hot paths

- Scope: keep Zig `-O3`, `NEKO_HOT`, `NEKO_HOT_INLINE`, no-JNI/no-JVMTI
  semantics, and translated runtime behavior unchanged while reducing the
  remaining `neko_native_support.c` compile floor.
- Required evidence: P13 manifests show all four jars now finish generation
  under 5s, but support remains a common fixed floor (`evaluator` support
  2336 ms) even when impl tails improve. The next optimization must target
  support code organization, not compiler flags or weaker hot-path inlining.
- Validation command or runtime target: focused generator/unit tests, native C
  hot-path audit, one fresh four-jar generation timing pass, static no-forbidden
  JNI/JVMTI/fallback inspection, and runtime-output comparison if behavior-
  affecting support boundaries are changed.
- Completion criteria: support compile time is reduced or remains within noise
  while all four generation rows stay under 5s, no hot-path helper loses
  `NEKO_HOT_INLINE` where the current generated path depends on inlining, and
  native coverage remains `translated>0 rejected=0` for all four fixtures.

## Notes

- This plan must not change JVM obfuscation transforms, method selection,
  control-flow flattening, key propagation, JNI fallback policy, or runtime
  helper policy.
- The implementation must be architecture-level: no jar-, class-, method-, log-,
  benchmark-, or test-specific compile split.
- Each completed subtask requires a fresh validation run and a commit containing
  only that subtask's implementation plus this todo update.
