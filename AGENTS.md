# Agent Instructions

#Neko Obfuscator Agent Instructions

## Scope discipline

- Do not implement or optimize for a special sample, benchmark, class, method,
  owner, descriptor, crash site, log string, or known test artifact.
- All modifications must follow JVM ABI, All JVM features must be supported.
- Every fix must be generic and architecture-level.
- Every code or configuration change must be backed by a complete, concrete
  evidence chain before implementation. The evidence must identify the exact
  failing invariant, runtime path, instruction/site, generated artifact, log, or
  test observation that proves the root cause and justifies the change. Do not
  modify code based on speculation, intuition, probability language, or phrases
  such as "possibly", "likely", "probably", "high probability", "I think", or
  "I suspect". If the evidence is incomplete, gather more evidence first.
- Do not jump to a later-stage workaround while earlier prerequisites are open.
  If order must change, update the todo first with a plan-level dependency
  reason.
- Do not mark stale work complete. A stale jar, stale generated C file,
  compile-only result, unit-only result, or previous run is not proof.
- After a verified completed substep, commit the implementation and the todo
  checkbox update before continuing.
- Do not revert or overwrite unrelated user work. Work with existing changes if
  they affect the task.
- Ask for permission to use the repository/system `./gradlew`; do not create, copy, or use a
  temporary `gradlew`.

## Task planning and clarification

- Requests that are non-trivial, explicitly require planning/subtasking, require
  investigation or runtime/code validation, or contain multiple dependent changes
  must be split into concrete subtasks before implementation starts. The
  subtasks must be written both in the active Codex todo list and in a matching
  `.plan/` todo document in the repository.
- Simple tasks are exempt from the Codex todo and `.plan/` subtask workflow when
  the user did not explicitly ask for it. The final change still requires a git
  commit.
- Each recorded subtask must include its scope, required evidence, validation
  command or runtime target, and completion criteria. Do not start
  implementation for a recorded subtask until those fields are recorded.
- When one recorded subtask is completed and freshly validated, immediately
  update both todo locations and create a git commit containing only that
  subtask's implementation plus its matching todo update. This commit is the
  required checkpoint and rollback point before starting the next subtask.
- If any implementation detail, required behavior, acceptance condition, task
  boundary, or user intent is unclear, do not infer or invent the missing
  details. Use the ask tool to ask the user for the missing information before
  editing code or configuration. If the ask tool is unavailable, ask a concise
  direct question in the conversation and wait for the answer.

## Forbidden fallbacks and forbidden mechanisms

- No JNI fallback, soft fallback, skip-on-error path, or original-bytecode
  fallback.
- No JVMTI.
- No new JVM runtime helper layer outside the planned native/bootstrap surface.
- No new Java-side bootstrap or new `<clinit>` behavior beyond the existing
  native-loader entry.
- `JNI_OnLoad` must stay minimal: `GetEnv`, capture anchors, initialize native
  metadata, then stop using the JNI function table.
- Outside that minimal `JNI_OnLoad` path, generated/runtime native code must not
  use `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, `Call*Method`, `Get*Field`,
  `FindClass`, `NewStringUTF`, `NewObject*`, `Throw*`, JNI monitor calls, JNI
  array calls, or equivalent JNI wrappers.
- Missing VMStruct, JVM symbol, class, method, field, string metadata, GC
  barrier, CodeHeap support, or call stub support must hard abort/error. It must
  not fall back to JNI, skip the method, or keep original bytecode.
- ZGC/Shenandoah compatibility must not be achieved by skipping classes or
  methods. Missing required barriers must abort.

## JVM obfuscation invariants

- JVM obfuscation fixes must preserve or increase obfuscation strength. Do not
  disable, bypass, downgrade, skip, or special-case any transform to make a jar
  pass.
- Control-flow flattening must use correct dynamic dispatch. State transitions
  must be computed from live control-flow state and must not collapse into
  static dispatch, self-canceling predicates, dead stores, or constant-only
  switch selectors.
- Control-flow flattening, invokedynamic obfuscation, method-parameter
  obfuscation, constant obfuscation, and string obfuscation must remain
  compatible when all are enabled together.
- Hidden method keys and control-flow keys must stay semantically linked.
  Key material passed between transformed methods must match the target method's
  actual ABI, descriptor, dispatch family, and runtime entry path.
- Full-obfuscation coverage is mandatory for original application classes:
  enabled transforms must cover every eligible original class, method, member,
  constant, string, reflective datum, and call path.
- True JVM ABI surfaces that must retain JVM-required shape are exempt from name
  or descriptor obfuscation only to the extent required by the ABI, including
  class initializers (`<clinit>`), `main([Ljava/lang/String;)V`, and inherited
  external API override slots. Constructors (`<init>`) may retain only the JVM-
  mandated name and return shape; original application constructors must still
  receive parameter rewriting, hidden-key injection, packed-argument carrier
  changes, and protected callsite rewriting wherever their descriptor is not an
  inherited or external ABI contract. This ABI exemption must not disable
  method-parameter obfuscation, control-flow parameter changes, hidden keys,
  packed `Object[]` carriers, or protected callsite rewriting for non-ABI
  original application paths.
- Hidden `long` key parameters and packed `Object[]` parameter carriers are
  mandatory obfuscation surfaces. Do not remove, bypass, downgrade, or exclude
  them for reflection, loaders, annotations, MethodHandle, LambdaMetafactory,
  virtual/interface dispatch, benchmarks, tests, or compatibility fixes.
  Reflection and dynamic invocation compatibility must be achieved by generic
  rewriting of lookup names, lookup descriptors, argument arrays, resource
  paths, metadata, and generated-member filters to the final obfuscated ABI.
  Do not preserve original application descriptors or names as a workaround,
  except for the true JVM ABI surfaces listed above.
- Reflection, MethodHandle, loader, annotation, and dynamic-invocation
  compatibility rewrites are not allowed to expose plaintext class/member
  names, descriptor strings, resource paths, parameter-type constants, seed
  material, or invoke adapters. Any inserted bytecode that represents
  user-visible reflective data must remain eligible for CFF, constant
  obfuscation, string obfuscation, and invokedynamic obfuscation, or must emit
  an equivalent protected form directly. Reflection lookup names, descriptor
  arrays, annotation element calls, resource paths, and rewritten reflective
  invocation arguments must not remain as plain strings or plain constants after
  compatibility rewriting. Generated markers may protect only transform
  bookkeeping that would be invalid to reprocess; they must not be used to skip
  obfuscation of reflective application data.
- Reflection loading, reflective invocation, declared-member lookup
  (`getDeclared*`/`get*`), MethodHandle lookup/invocation, LambdaMetafactory, and
  invokedynamic-created call paths are mandatory full-obfuscation surfaces for
  original application code. They must be made compatible by rewriting lookup
  names, descriptors, parameter type arrays, argument arrays, bootstrap
  arguments, handles, callsites, and injected hidden parameters to the final
  obfuscated ABI; they must not be left unobfuscated, skipped, or preserved as
  original descriptors/names for compatibility.
- Injected helper classes and helper members may be obfuscated as needed for
  correctness, security, and compatibility, but they do not require mandatory
  full-obfuscation coverage. Injected outliner code is not a helper exemption:
  outliners must receive full obfuscation coverage.
- Dynamic key propagation is mandatory. Do not replace dynamic key transfer with
  static `stateKey`, static seed constants, descriptor-only recomputation, or
  any equivalent uncorrelated key source.
- Do not expose raw static keys in generated bytecode. If a canonical target key
  must be captured for a delayed, escaping, reflective, or lambda call path, it
  must be dynamically encoded or decoded from live obfuscated state before use.
- LambdaMetafactory, MethodHandle, reflection, virtual/interface dispatch,
  abstract-method dispatch, packed `Object[]` parameters, constructor paths,
  exception paths, loops, recursion, and asynchronous execution must preserve
  key correctness without fallback.
- Original application constructor paths are mandatory full-obfuscation surfaces.
  Every object creation sequence, including `new`/`dup`/`invokespecial <init>`,
  reflective construction, MethodHandle construction, serialization-like
  construction paths, and invokedynamic/lambda construction paths, must rewrite
  the creation callsite and injected constructor parameters to match the final
  obfuscated constructor ABI. Constructors must not be excluded from parameter
  changes or hidden-key propagation merely because the JVM name is `<init>`.
- InvokeDynamic obfuscation must not introduce Java bridge methods, adapter
  methods, synthetic runtime dispatch layers, or helper classes to compensate
  for key-transfer bugs.
- New Java helpers are forbidden unless they are part of the existing planned
  transform surface. String decode helpers are allowed only for string
  obfuscation and must not be used as invokedynamic or CFF bridges.
- Do not add fallback behavior, original-call fallback, original-bytecode
  fallback, skip-on-error, try-original-on-failure, or reduced-obfuscation
  rescue paths for JVM transforms.
- Do not implement fixes for a specific jar, class, method, stack trace, log
  string, benchmark, or test artifact. JVM transform fixes must be generic and
  architecture-level.
- A generated jar is not considered fixed until the freshly generated artifact
  runs successfully with the same enabled transform set that exposed the bug.
- Never reduce, approximate, thin, segment, merge, coarsen, batch, delay, or
  otherwise weaken control-flow flattening coverage or block granularity to make
  a jar pass. Do not change CFF block construction, CFF block boundaries, CFF
  block count, or CFF block selection as a size or verifier workaround.
- Do not expose, persist, log, encode as plain constants, or otherwise leak any
  flow key, method key, state key, key-transfer material, or derived dispatch
  key in generated bytecode, helper APIs, metadata, maps, logs, resources, or
  synthetic members.
- Do not implement key self-canceling logic. Key updates and key transfer must
  not contain mathematically removable pairs, neutral operations, inverse pairs,
  static folds, constant-only recomputation, dead-key stores, or expressions
  that can be algebraically simplified into a static key or a descriptor-only
  value.
- All key propagation must be nonlinear and driven by live external key input.
  A method entry key must drive every CFF block's active state, every future key
  update, and every key transfer to downstream calls or delayed execution paths.
- Method entry keys must remain semantically live from method entry through all
  CFF dispatcher decisions, transition updates, hidden-method key propagation,
  invokedynamic sites, reflection paths, lambda/MethodHandle paths, exception
  paths, loops, recursion, and asynchronous execution. Recomputing equivalent
  values from static seeds, state constants, descriptors, owner/name strings, or
  block indexes is not an acceptable replacement for live key flow.
- Any architectural size fix must preserve full-strength CFF and dynamic key
  propagation. It may move equivalent protected logic only if the moved path is
  still keyed by live method-entry material and does not introduce helper-layer
  fallback, static-key exposure, reduced transform coverage, or changed CFF block
  semantics.

## Validation workflow for every subtask

Before editing code for a new subtask, record the runtime target row that will
prove the changed path. Then:

1. Make one coherent generic change.
2. Regenerate the affected native artifact with repository `./gradlew`.
3. Run the required runtime targets from the todo.
4. Inspect stdout, stderr, native logs, `hs_err` files, and generated C when
   claiming JNI removal.
5. If anything fails, fix and rerun. If it passes, commit only the implementation
   and the matching todo checkbox update.

A subtask remains `[ ]` or `[-]` until the changed path is exercised by a freshly
regenerated runtime artifact. Every source change after a pass invalidates the
previous proof and requires a new runtime run.

Runtime validation must reject:

- SIGSEGV, SIGABRT, verifier errors, or VM fatal errors.
- `translated=0`.
- `Native compilation produced no libraries`.
- skip-on-error behavior.
- missing-symbol fallback.
- JNI fallback.
- original bytecode fallback.

Resolver and bind-time subtasks must prove that the current bind/runtime path
uses the resolved pointer, offset, or entry. Hot-path opcode subtasks must prove
the runtime jar executes the opcode through the new native path and generated C
contains no forbidden JNI wrappers. Cleanup/removal subtasks must prove the
removed fallback is no longer needed and missing capability paths hard abort.

## Required runtime target groups

- `R-build`: cleanly regenerate native artifacts with the repo Gradle wrapper.
- `R-test`: run the generated TEST native jar.
- `R-obfusjack`: run the obfusjack Java fixture.
- `R-native-obfusjack`: run the generated obfusjack native jar.
- `R-native-test`: run the generated TEST native jar under the native path.
- `R-inspect`: inspect generated C/native output for forbidden JNI and fallback
  markers.
- `R-negative`: prove missing required symbols/capabilities fail closed instead
  of falling back.

## Performance and GC gates

- `R-build`.
- `R-test` repeated 5 times.
- `R-obfusjack` repeated 5 times.
- `R-native-test`.
- `R-inspect`.

Median thresholds:

- TEST Calc <= 20 ms, must be same or less than baseline(before edit) jar.
- obfusjack matrix-mul Seq <= 14 ms, must be same or less than baseline(before edit) jar.
- Platform <= 44 ms, must be same or less than baseline(before edit) jar.
- Virtual <= 35 ms.
- Parallel and VThreads must preserve the prior wins required by the todo.

If a threshold fails, make a new generic optimization commit and rerun the gate.
Do not special-case the benchmark.

GC strict compatibility requires TEST and obfusjack runs under:

- G1.
- Parallel.
- Serial.
- ZGC with `ZVerifyViews`.
- Shenandoah with verification enabled.

## Final acceptance

Final native-gentle-flamingo completion requires:

- Static grep showing only the allowed `GetEnv`.
- Dynamic validation showing no JNI calls on the translated path.
- Thread state stable as `_thread_in_java`, except controlled NJX transitions.
- TEST Calc <= 20 ms.
- obfusjack full green.
- GC compatibility without skip behavior.

The following are explicitly out of scope and must not be introduced:

- JNI fallback.
- JVMTI.
- JVM helper classes outside the existing planned transform surface.
- Calc-only shipping proof.
- ZGC/Shenandoah skip behavior.
- Never create any files/folders in /tmp folder.
