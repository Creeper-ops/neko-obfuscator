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

### [x] P14: Reduce fixed support compile floor without de-inlining hot paths

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
- Checkpoint evidence: implementation changed only compile-job submission order
  in `NativeBuildEngine`; generated C, Zig optimization flags, link order,
  `NEKO_HOT`, and `NEKO_HOT_INLINE` annotations are unchanged. Jobs are now
  submitted largest source first so long impl/support tasks start immediately
  instead of after many small sources, while manifest/result records remain
  sorted by original source index. Focused tests passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest`. Native C hot-path
  audit passed:
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`.
  One fresh evaluator run completed in 4213 ms (`translated=122 rejected=0`,
  126 C sources, same 14893368-byte library as P13). A full fresh four-jar
  generation pass stayed under 5s for every fixture and reported no fallback
  markers: `evaluator.jar` 4741 ms (`translated=122 rejected=0`, 126 C
  sources), `test21.jar` 3071 ms (`translated=93 rejected=0`, 97 C sources),
  `snake.jar` 1806 ms (`translated=18 rejected=0`, 22 C sources), and
  `test.jar` 2429 ms (`translated=49 rejected=0`, 53 C sources).

### [x] P15: Compress primitive array-store generated C without de-inlining

- Scope: keep Zig `-O3`, existing target optimization flags, `NEKO_HOT`,
  `NEKO_HOT_INLINE`, one-method implementation splitting, no-JNI/no-JVMTI
  semantics, and translated runtime behavior unchanged, but replace repeated
  primitive `xASTORE` null/bounds/exception generated bodies with generic
  checked inline helpers.
- Required evidence: P14 fresh manifests show the remaining evaluator long-tail
  implementation `neko_native_impl_103.c=4065 ms`; inspecting that generated C
  shows 50 repeated primitive stores, 50 repeated `neko_fast_array_length`
  checks, 115 repeated `neko_raise_implicit_exception` expressions, and 130
  repeated `neko_bound_method_i_entry` expressions. `xALOAD` and object
  `AASTORE` already use checked helpers, but primitive `xASTORE` still expands
  the full check body at every opcode site.
- Validation command or runtime target: focused generator/unit tests, native C
  hot-path audit, one fresh four-jar native generation timing pass after the
  code-shape optimization, and static generated-C inspection for short
  `neko_checked_*astore` call sites plus no forbidden JNI/JVMTI/fallback calls.
- Completion criteria: generated primitive array stores call
  `NEKO_HOT_INLINE` checked-store helpers instead of expanding the full
  null/bounds/exception body at every opcode; all four native generation rows
  remain under 5s with `translated>0 rejected=0`; compiler flags and hot helper
  inlining are unchanged; no JNI/JVMTI/original-bytecode fallback is introduced.
- Checkpoint evidence: focused generator/unit validation passed with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest`; generated-C
  hot-path validation passed with
  `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. A fresh
  native-only four-jar timing pass with `.plan/native-only-full.yml`
  (`skipOnError: false`) completed under the 5s gate:
  `evaluator.jar` 3211 ms (`translated=122 rejected=0`, 126 C sources),
  `test21.jar` 3215 ms (`translated=93 rejected=0`, 97 C sources),
  `snake.jar` 1929 ms (`translated=18 rejected=0`, 22 C sources), and
  `test.jar` 2716 ms (`translated=49 rejected=0`, 53 C sources). Fresh
  generated artifacts under `build/neko-native-work/run-15008940410461`,
  `run-15025150148753`, `run-15036312096800`, and `run-15049998311927`
  contain `NEKO_HOT_INLINE jboolean neko_checked_{b,c,s,i,l,f,d}astore`
  definitions plus short `neko_checked_*astore(thread, env, ...)` call sites.
  Static inspection of fresh `neko_native_impl_*.c` found no expanded
  `neko_fast_*astore(__a...)` primitive store bodies and no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` JNI function-table use in impl
  sources. Fresh build manifests still use Zig `-O3`, `-march=x86_64_v3`,
  and `-funroll-loops`.

### [x] P16: Split generated support owner-binding and inline-cache shards

- Scope: keep Zig `-O3`, target optimization flags, `NEKO_HOT`,
  `NEKO_HOT_INLINE`, one-method implementation splitting, no-JNI/no-JVMTI
  semantics, and translated runtime behavior unchanged, but split the already
  marker-delimited `neko_native_support.c` owner-binding and inline-cache
  support regions into separate generated C translation units that include the
  existing implementation prelude.
- Required evidence: P15 fresh evaluator manifest
  `build/neko-native-work/run-15008940410461/neko_native_build_manifest.properties`
  shows `neko_native_support.c` still takes 2555 ms, tied with
  `neko_native_impl_103.c` at 2551 ms. The same fresh support source is
  884283 bytes and contains explicit generic boundaries:
  `// === Bind-time owner resolution ===`,
  `// === Inline-cache direct-call stubs ===`, and
  `// === Inline-cache metadata ===`. These regions are generated
  architecture-wide from owner/callsite metadata and are referenced through
  declarations already present in `neko_native_impl_prelude.h`.
- Validation command or runtime target: focused `CCodeGeneratorTest`,
  generated-C hot-path audit, one fresh four-jar native-only timing pass with
  `.plan/native-only-full.yml`, and static generated-source inspection proving
  the new support shards compile with the same Zig flags and without forbidden
  JNI/JVMTI/fallback calls.
- Completion criteria: fresh generated source sets contain
  `neko_native_owner_bindings.c` and `neko_native_icache_support.c` when their
  corresponding regions exist; `neko_native_support.c` no longer carries those
  marker-delimited bodies; all four native generation rows stay under 5s with
  `translated>0 rejected=0`; compiler flags and translated hot-path inlining
  are unchanged; no JNI/JVMTI/original-bytecode fallback is introduced.
- Checkpoint evidence: focused source-set validation passed with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest`,
  and generated-C hot-path validation passed with
  `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh generated
  evaluator artifacts under `build/neko-native-work/run-15384558175071`
  contain `generated.c.count=128`, including
  `neko_native_owner_bindings.c` and `neko_native_icache_support.c`. The
  owner/icache shards include `neko_native_impl_prelude.h`; the remaining
  `neko_native_support.c` no longer contains the owner-binding or
  inline-cache metadata markers. The same evaluator manifest shows
  `neko_native_support.c` reduced from the P15 884283-byte/2555 ms source to
  715353 bytes/2120 ms, while the current dominant long tail remains
  `neko_native_impl_103.c` at 2575 ms. A fresh native-only four-jar timing
  pass with `.plan/native-only-full.yml` (`skipOnError: false`) stayed under
  the 5s gate: `evaluator.jar` 3247 ms (`translated=122 rejected=0`, 128 C
  sources), `test21.jar` 3272 ms (`translated=93 rejected=0`, 99 C sources),
  `snake.jar` 1881 ms (`translated=18 rejected=0`, 24 C sources), and
  `test.jar` 2476 ms (`translated=49 rejected=0`, 55 C sources). Static
  inspection of fresh impl sources found no `NEKO_JNI_FN_PTR`, `(*env)->`, or
  `env->` JNI function-table use. Fresh build manifests still use Zig `-O3`,
  `-march=x86_64_v3`, and `-funroll-loops`.

### [x] P17: Shorten generated fast-array exception call sites

- Scope: keep Zig `-O3`, target optimization flags, `NEKO_HOT`,
  `NEKO_HOT_INLINE`, checked array helper inlining, no-JNI/no-JVMTI
  semantics, and translated runtime behavior unchanged, but replace repeated
  generated arguments to `neko_raise_fast_array_reason` with one generic
  cached-support wrapper for fast-array failure reasons.
- Required evidence: P16 fresh evaluator manifest
  `build/neko-native-work/run-15384558175071/neko_native_build_manifest.properties`
  shows the dominant long tail is now `neko_native_impl_103.c` at 2575 ms.
  The same fresh `neko_native_impl_103.c` is 73013 bytes, and inspection shows
  repeated array-store failure branches that inline identical
  `neko_bound_class(env, g_cls_26, "java/lang/NullPointerException")`,
  `neko_bound_method_i_entry(... "java/lang/NullPointerException" ...)`,
  `neko_bound_class(env, g_cls_27,
  "java/lang/ArrayIndexOutOfBoundsException")`, and
  `neko_bound_method_i_entry(... "java/lang/ArrayIndexOutOfBoundsException"
  ...)` arguments at every checked array access call site. The success path
  already uses checked direct helpers; only the cold failure dispatch text is
  duplicated.
- Validation command or runtime target: focused translator/source-set tests,
  generated-C hot-path audit, one fresh native-only timing pass after the
  code-shape optimization, and static generated-C inspection proving array
  failure call sites use the cached wrapper while successful checked array
  helpers and Zig optimization flags remain unchanged.
- Completion criteria: generated array fast-path failure branches call a short
  `neko_raise_cached_fast_array_reason(thread, env, reason)` wrapper; the
  wrapper resolves the same NPE/AIOOBE metadata through existing native
  metadata slots and still hard-aborts through existing helper semantics for
  unexpected reasons; all four native generation rows remain under 5s with
  `translated>0 rejected=0`; compiler flags and successful hot-path inlining
  are unchanged; no JNI/JVMTI/original-bytecode fallback is introduced.
- Checkpoint evidence: focused translator/source-set validation passed with
  `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest
  --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest`; generated-C
  hot-path validation passed with
  `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. A fresh
  native-only four-jar timing pass with `.plan/native-only-full.yml`
  (`skipOnError: false`) stayed under the 5s gate:
  `evaluator.jar` 3255 ms (`translated=122 rejected=0`, 128 C sources),
  `test21.jar` 2904 ms (`translated=93 rejected=0`, 99 C sources),
  `snake.jar` 1898 ms (`translated=18 rejected=0`, 24 C sources), and
  `test.jar` 2471 ms (`translated=49 rejected=0`, 55 C sources). Fresh
  generated artifacts under `build/neko-native-work/run-16328715642299`,
  `run-16351057563811`, `run-16364104214757`, and `run-16375783174853`
  contain short `neko_raise_cached_fast_array_reason(thread, env, reason)`
  impl call sites, and static inspection found no remaining long
  `neko_raise_fast_array_reason(thread, env, __reason, ...)` generated
  branches. Static inspection of the same fresh impl sources found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, or `env->` JNI function-table use. Fresh build
  manifests still use Zig `-O3`, `-march=x86_64_v3`, and `-funroll-loops`.

### [x] P18: Capture post-P17 runtime and compile bottlenecks for the next generic optimization

- Scope: keep the current native code generation and runtime semantics
  unchanged while collecting fresh evidence for the next architecture-level
  optimization. The capture must cover `test-native.jar` Calc timing,
  `test21-native.jar` Platform/Virtual thread timing, Matrix Seq/Parallel/
  VThreads timing, native-vs-original JVM comparison, and post-P17 native build
  manifest long tails. Runtime jar execution must set `java.io.tmpdir` to a
  repository-local directory, not `/tmp`.
- Required evidence: fresh stdout/stderr from original and native TEST/test21
  runs, parsed timing rows for Calc/Platform/Virtual/Seq/Parallel/VThreads,
  fresh manifest source-count and per-source compile elapsed rows for the same
  native artifacts, generated C snippets or counters identifying the exact
  repeated/native runtime path behind the slowest runtime or compile row, and
  static inspection for forbidden JNI/JVMTI/fallback markers on the inspected
  generated path.
- Validation command or runtime target: ran the freshly generated post-P17
  native artifacts under
  `java -XX:+PerfDisableSharedMem -Djava.io.tmpdir=build/native-run-tmp -jar ...`
  and compared against original `test-jars/test.jar` and
  `test-jars/test21.jar`; inspected
  `build/neko-native-work/run-16375783174853` and
  `build/neko-native-work/run-16351057563811` manifests/sources for compile
  bottlenecks.
- Completion criteria: complete. TEST Calc measured original/native at
  `12ms`/`43ms`; test21 measured original/native Platform `22ms`/`45ms`,
  Virtual `14ms`/`45ms`, Matrix Seq `2ms`/`28ms`, Parallel `0ms`/`2ms`,
  and VThreads `1ms`/`3ms`. TEST compile long tails were
  `neko_native_impl_15.c=1981ms`, `neko_native_support.c=1829ms`,
  `neko_native_impl_32.c=1535ms`, and
  `neko_native_dispatchers.c=498ms`. test21 compile long tails were
  `neko_native_impl_39.c=2274ms`, `neko_native_support.c=2098ms`,
  `neko_native_impl_54.c=1863ms`, `neko_native_impl_50.c=1672ms`,
  and `neko_native_dispatchers.c=1053ms`. Generated C inspection identified
  two generic invariants for following subtasks: direct Java call translation
  currently emits cross-translation-unit C calls that prevent Zig from inlining
  hot direct callees such as TEST `Calc.runAll` -> `call/runAdd/runStr`, and
  nested primitive array translation currently calls
  `neko_fast_aaload_daload` inside the innermost matrix loop, rechecking row
  lookup, null, layout, handle, and bounds state for every element. The
  immediate build-speed candidate is splitting the per-signature dispatcher
  source by generated dispatcher groups; this preserves Zig optimization flags,
  `NEKO_HOT`/`NEKO_HOT_INLINE` use, no-JNI/no-JVMTI semantics, native coverage,
  and translated runtime behavior while reducing the measured dispatcher
  compile long tail.

### [x] P19: Split per-signature dispatcher source by generic symbol groups

- Scope: change only generated native source packaging for the per-signature
  direct-C dispatcher section. Keep dispatcher function bodies, trampoline
  bodies, manifest tables, native coverage, Zig flags, and runtime semantics
  unchanged. The split boundary is the generic `neko_sig_<id>_dispatch`
  symbol group emitted for every signature shape, not any jar, class, method,
  benchmark, or known test artifact.
- Required evidence: P18 fresh manifests measured
  `neko_native_dispatchers.c=498ms` for TEST and
  `neko_native_dispatchers.c=1053ms` for test21. Generated dispatcher C is a
  repeated sequence of independent `typedef neko_sig_<id>_impl_t` plus
  `neko_sig_<id>_dispatch(...)` groups, and trampolines refer to the global
  hidden dispatcher symbols by name, so those groups can live in separate
  translation units without changing call edges or generated body text.
- Validation command or runtime target: ran focused generator/unit validation
  with repository `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`; regenerate the
  native-only four jar set once after implementation and inspect fresh
  manifests for dispatcher shard files plus generated impl sources for
  forbidden JNI/JVMTI/fallback markers.
- Completion criteria: complete. Fresh generated source sets contain multiple
  `neko_native_dispatchers_<n>.c` shards when the dispatcher section is large
  enough: evaluator `run-17404432706426` emitted 5 dispatcher shards, test21
  `run-17410884179848` emitted 4, TEST `run-17407254506360` emitted 2, and
  small SnakeGame `run-17405960176478` retained one dispatcher source. Shards
  compile as independent whole dispatcher symbol groups with the normal native
  implementation prelude. The largest fresh dispatcher shard compile rows were
  evaluator `510ms`, test21 `412ms`, TEST `452ms`, and SnakeGame unsplit
  `458ms`, reducing the P18 test21 dispatcher long tail from `1053ms` without
  changing Zig flags or generated dispatcher body semantics. Fresh CLI rows
  stayed nonzero with `rejected=0`: evaluator `translated=122`, test21
  `translated=93`, SnakeGame `translated=18`, and TEST `translated=49`.
  Static inspection of fresh generated impl sources found no
  `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, direct `Call*Method`, `Get*Field`,
  `FindClass`, `NewStringUTF`, `NewObject*`, or `Throw*` JNI function-table
  usage.

### [x] P20: Eagerly patch generated lambda owners through the existing manifest path

- Scope: keep the existing native-stage LambdaMetafactory lowering and native
  translation coverage, but repair the bind-time ordering for generated
  `$NekoLambda$` owner classes. Do not add a Java helper, JNI fallback,
  JVMTI, skip-on-error path, original-bytecode fallback, or special handling
  for any fixture/class/method beyond the generic generated-lambda owner
  category that the native stage already emits.
- Required evidence: fresh P19 evaluator native run failed with
  `java.lang.LinkageError: please check your native library load correctly` at
  `dev.sim0n.evaluator.manager.TestManager$NekoLambda$5.accept`; the P17
  control artifact failed at the same generated SAM placeholder. Fresh
  generated `neko_native_manifest.c` contains manifest rows and
  `neko_manifest_patch_defined_class` cases for `$NekoLambda$` owners, but
  `ManifestEmitter` skips `$NekoLambda$` owners during the eager classloader
  anchored resolution pass. The only remaining patch trigger is
  `neko_bind_class_slot_from`, which runs too late for a generated lambda SAM
  method whose first execution is the placeholder body itself.
- Validation command or runtime target: reran focused generator tests, rebuilt
  the affected native-only artifacts, run evaluator and the other four-jar
  native behavior targets with repository-local `java.io.tmpdir`, and inspect
  generated impl sources for forbidden JNI/JVMTI/fallback markers.
- Completion criteria: complete. Generated lambda owners are resolved and
  patched by the
  existing manifest resolver after a non-lambda owner supplies the classloader
  anchor; fresh evaluator native artifact reached `Loaded 4 tests`,
  annotation, AES/Blowfish, large-string comparison, and final decrypt success
  markers instead of the previous generated SAM `LinkageError`. Fresh TEST
  native artifact exited 0 with the accepted rows through
  `-------------Tests r Finished-------------` and `Calc: 42ms`; fresh test21
  native artifact exited 0 with `=== All tests completed ===`; fresh SnakeGame
  native artifact preserved the accepted headless `java.awt.HeadlessException`
  behavior. Translated counts remained nonzero with `rejected=0`, and no new
  JNI function-table use, JVMTI use, helper class, skip-on-error path, or
  original-bytecode fallback was introduced.

### [x] P21: Split support global definitions into a data shard

- Scope: change only generated native source packaging for top-level hidden
  support global definitions whose symbols are already referenced through
  generated declarations. Keep every initializer value, symbol name, method
  selection, native coverage, Zig flags, hot inline helper body, no-JNI/
  no-JVMTI policy, and runtime behavior unchanged. The split boundary is the
  generic global-definition form emitted by codegen, not any jar, class,
  method, benchmark, or known test artifact.
- Required evidence: fresh P20 build manifests show `neko_native_support.c`
  remains the fixed compile long tail across all four native-only artifacts:
  evaluator `2327ms`, test21 `2178ms`, TEST `1912ms`, and SnakeGame
  `2298ms`. Fresh support C inspection shows the first large support body
  after `// === Global resolution caches ===` contains repeated hidden global
  slot definitions such as `g_cls_*`, `g_obj_array_klass_*`,
  `g_cls_initialized_*`, `g_mid_*`, `g_mptr_*`, and related cache globals.
  These definitions are data storage, while other translation units and support
  functions already consume equivalent `extern` declarations through the
  generated support contract.
- Validation command or runtime target: run focused generator/unit validation
  with repository `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`; regenerate the
  native-only four jar set once after implementation, run TEST/test21/
  evaluator/SnakeGame behavior targets with repository-local `java.io.tmpdir`,
  inspect fresh manifests for `neko_native_globals.c`, and inspect generated
  impl sources for forbidden JNI/JVMTI/fallback markers.
- Completion criteria: fresh generated source sets contain a
  `neko_native_globals.c` support shard when top-level hidden support globals
  are present; `neko_native_support.c` keeps extern declarations for those
  globals and no duplicate definitions; runtime jars still execute with the
  accepted behavior; `translated` remains nonzero with `rejected=0`; and static
  inspection still finds no forbidden JNI function-table usage in generated
  impl sources.
- Fresh validation: focused repository Gradle validation passed with
  `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`. Fresh native-only
  generation emitted `neko_native_globals.c` and linked it for all four
  artifacts: evaluator `run-17869695517907` with 133 C sources and
  `translated=122 rejected=0`; test21 `run-17860661488192` with 103 C sources
  and `translated=93 rejected=0`; SnakeGame `run-17863062732487` with 25 C
  sources and `translated=18 rejected=0`; TEST `run-17863274671999` with 57 C
  sources and `translated=49 rejected=0`. Runtime validation preserved the
  accepted behavior: TEST exited 0 through `-------------Tests r
  Finished-------------` with `Calc: 44ms`; test21 exited 0 through
  `=== All tests completed ===` with Platform `44ms`, Virtual `48ms`, Seq
  `29ms`, Parallel `2ms`, and VThreads `2ms`; evaluator exited 0 through the
  final decrypt success markers; SnakeGame preserved the accepted headless
  `java.awt.HeadlessException` exit. Static inspection over the fresh
  `neko_native_impl_*.c` files found no forbidden JNI function-table usage.
  Manifest evidence shows the data shard compiles separately as
  `neko_native_globals.c` in `111ms` to `173ms`, while `neko_native_support.c`
  remains the next fixed long tail at `1897ms` to `2104ms`; the next compile
  optimization must therefore target another generic support-code structure.

### [ ] P22: Remove unused moved-global externs from the support main unit

- Scope: change only generated native source packaging after P21 by omitting
  moved global-slot `extern` declarations from `neko_native_support.c` when the
  same support main translation unit does not reference that symbol. Keep every
  global definition in `neko_native_globals.c`, keep the implementation prelude
  contract declarations for other generated translation units, keep all
  initializer values, symbol names, method selection, native coverage, Zig
  flags, inline helper bodies, no-JNI/no-JVMTI policy, and runtime behavior
  unchanged. The criterion is generic same-translation-unit identifier use, not
  any jar, class, method, benchmark, or known test artifact.
- Required evidence: fresh P21 manifests show `neko_native_support.c` remains a
  fixed compile long tail across the native-only artifacts at TEST `1897ms`,
  evaluator `2096ms`, SnakeGame `1917ms`, and test21 `2104ms`. Fresh P21
  test21 support inspection shows 1597 hidden `extern g_*` declarations in
  `neko_native_support.c`; a same-file identifier-use scan shows only 98 are
  referenced by the support main unit and 1463 are unused there. The same
  artifact has `12918` support lines, `1561` global-shard definition lines,
  and a separate `7519`-line implementation prelude that already carries the
  cross-translation-unit extern contract.
- Validation command or runtime target: run focused generator/unit validation
  with repository `./gradlew :neko-test:test --tests
  dev.nekoobfuscator.test.CCodeGeneratorTest --tests
  dev.nekoobfuscator.test.NativeGeneratedCHotPathAuditTest`; regenerate the
  native-only four jar set once after implementation, run TEST/test21/
  evaluator/SnakeGame behavior targets with repository-local `java.io.tmpdir`,
  inspect fresh manifests for reduced support-main extern declarations and
  `neko_native_globals.c`, and inspect generated impl sources for forbidden
  JNI/JVMTI/fallback markers.
- Completion criteria: fresh generated source sets keep global definitions in
  `neko_native_globals.c`; `neko_native_impl_prelude.h` keeps the extern
  declarations needed by implementation and late-support shards; the support
  main source omits only moved global-slot extern declarations that are unused
  by that support main source; runtime jars still execute with the accepted
  behavior; `translated` remains nonzero with `rejected=0`; and static
  inspection still finds no forbidden JNI function-table usage in generated
  impl sources.
- Current evidence: rejected for the active implementation path. The experiment
  passed focused generator/native audit tests and the four fresh native jars
  preserved accepted behavior with `translated>0 rejected=0` and no forbidden
  JNI markers in generated impl sources. It reduced hidden `extern g_*`
  declarations in fresh support main files to about 127-133, but the fresh
  manifests did not prove a compile-speed improvement: `neko_native_support.c`
  still dominated at TEST `2137ms`, test21 `3374ms`, evaluator `3086ms`, and
  SnakeGame `2954ms`. No code from this experiment is retained for the next
  implementation path.

## Notes

- This plan must not change JVM obfuscation transforms, method selection,
  control-flow flattening, key propagation, JNI fallback policy, or runtime
  helper policy.
- The implementation must be architecture-level: no jar-, class-, method-, log-,
  benchmark-, or test-specific compile split.
- Each completed subtask requires a fresh validation run and a commit containing
  only that subtask's implementation plus this todo update.
