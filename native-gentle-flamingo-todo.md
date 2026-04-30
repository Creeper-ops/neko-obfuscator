# native-gentle-flamingo TODO / Plan Status

Source plan: `~/.claude/plans/native-gentle-flamingo.md`

Status legend:
- `[ ]` not completed / not verified
- `[-]` actively in progress only when current work is underway

## Hard Constraints

- Do not mark any task complete unless the exact requested artifact is regenerated and run.
- After any TODO sub-step is completed and verified, commit that completed slice before continuing.
- No targeted implementations for a specific benchmark, class, method, owner/name pair, or crash site. Fixes must be general architectural changes that satisfy the plan.
- No bug-specific, sample-specific, benchmark-specific, class-specific, method-specific, owner/name-specific, descriptor-specific, or crash-site-specific repair is allowed. If a proposed change only explains one observed failure instead of a whole plan-level category, stop and redesign it as a generic mechanism.
- Every implementation subtask must start by rereading the full source plan `~/.claude/plans/native-gentle-flamingo.md` and this todo file, then explicitly checking the intended change against the plan's current stage and dependency order before editing.
- Do not implement a later-stage workaround to make a validation jar pass while an earlier-stage architectural prerequisite is still open. Follow the plan order unless the todo is first updated with a plan-level dependency reason.
- No JNI fallback, soft fallback, skip-on-error success, or "fast path unavailable -> slow JNI path".
- No direct or indirect JNI fallback is allowed: no `NEKO_JNI_FN_PTR`, no `(*env)->...`, no `env->...`, no `Call*Method`, no `Get*Field`, no `FindClass`, no `NewStringUTF`, no `NewObject*`, no `Throw*`, no monitor or array JNI calls outside the explicitly allowed minimal `JNI_OnLoad` `GetEnv` bootstrap.
- Failure to resolve a required VMStruct, JVM symbol, class, method, field, string, GC barrier, CodeHeap entry, or call stub is a hard abort/error, never a fallback path and never a reason to leave original bytecode/JVM execution in place.
- No JVMTI.
- No new JVM runtime helper layer outside the planned native/bootstrap mechanism.
- Keep `JNI_OnLoad` as the minimal bootstrap only: `GetEnv`, capture needed VM/JNI anchors, initialize native metadata, then stop using JNI function-table calls.
- Do not add `<clinit>` or Java-side bootstrap beyond the existing native-loader entry requirement.
- ZGC and Shenandoah must not be bypassed by skipping classes or methods. Missing required barrier support must abort, not fallback.
- `obfusjack-test21` is a required validation target; Calc-only success is not enough.
- Generated C inspection is mandatory before claiming a no-JNI subtask: grep for `NEKO_JNI_FN_PTR`, `(*env)->`, `env->`, and JNI wrapper names must match the subtask's allowed surface.
- Use the repository/system `./gradlew`; do not create or use temporary `gradlew`.
- `test-jars/oouput/` is pre-existing untracked output and must not be staged unless explicitly requested.
- Existing unrelated worktree changes, especially `X86_64WindowsTrampoline.java`, must not be reverted or staged accidentally.

## Per-Subtask Runtime Validation Gate

This gate applies to every T0-T4 subtask, including small scaffolding and cleanup subtasks.

- Before starting any new subtask or any new code change inside the current subtask, reread this todo and the source plan, then record which row in the runtime target matrix below will validate that exact change.
- Every implementation change must follow this loop before it is treated as resolved: edit one coherent change, regenerate the affected native artifact with repository `./gradlew`, run the required runtime target(s), inspect stdout/stderr/native logs/`hs_err_pid*.log`, inspect generated C when JNI removal is claimed, then either fix and rerun or commit only after the checks pass.
- No checkbox may be changed to `[x]` because a previous jar, stale generated C file, unit-only test, or compile-only check passed. The validation artifact must be regenerated after the latest source edit.
- If a subtask is split into multiple commits or multiple code changes, each split inherits the full runtime target row for that subtask unless the todo is updated first with a narrower runtime target and a plan-level reason.
- Before editing code for a new subtask, write down the runtime target(s) that will prove the changed path. If the current subtask cannot be proven by `TEST-native.jar` plus `obfusjack-test21`/`obfusjack-native.jar`, add the narrower or additional runtime target before implementation.
- A subtask must stay `[ ]` or `[-]` until its changed code path is exercised by a regenerated runtime artifact, not only by compile success, source inspection, or generated-C inspection.
- Every code change inside a subtask must be followed by runtime validation of a freshly regenerated artifact before that change can be treated as resolved. Compile-only, unit-only, stale-jar, or source-only validation is not enough.
- Before changing any subtask checkbox to `[x]`, rerun the exact Gradle/native generation path that rebuilds the jar or native artifact affected by that subtask.
- After regeneration, run the exact runtime target that exercises the new path. For this plan, the default minimum runtime targets are `TEST-native.jar` and `obfusjack-test21`/`obfusjack-native.jar`; if a subtask needs a narrower or additional runtime target, state it before marking `[x]`.
- Runtime validation must inspect stdout, stderr, generated native logs, and any `hs_err_pid*.log` from that run. Crashes, SIGSEGV/SIGABRT, verifier errors, `translated=0`, `Native compilation produced no libraries`, skip-on-error output, missing required symbol diagnostics, or fallback/original-bytecode execution keep the subtask incomplete.
- A resolver/bind-time subtask is not complete until the resolver is called by the current bind/runtime path and a runtime jar proves the resolved pointer/offset/entry is used correctly.
- A hot-path opcode subtask is not complete until a runtime jar executes that opcode through the new native path and the generated C inspection confirms no forbidden JNI wrapper remains for that path.
- A cleanup/removal subtask is not complete until a runtime jar proves the removed fallback is not required and the failure mode is a hard abort/error when the required native mechanism is unavailable.
- The commit for a completed subtask must include only the implementation plus its todo checkbox update after the runtime validation above has passed.

### Runtime Target Matrix

Use these target groups in every row below:

- `R-build`: regenerate with repository `./gradlew` through the native integration path that rebuilds `TEST-native.jar` and `obfusjack-native.jar`; compile-only is not enough.
- `R-test`: run the regenerated `TEST-native.jar` directly with `NEKO_PATCH_DEBUG=1 java -XX:+PerfDisableSharedMem -jar neko-test/build/test-native/TEST-native.jar`.
- `R-obfusjack`: run the regenerated `obfusjack-test21`/`obfusjack-native.jar` target directly with `NEKO_PATCH_DEBUG=1 java -XX:+PerfDisableSharedMem -jar neko-test/build/test-native/obfusjack-native.jar`.
- `R-native-obfusjack`: same direct regenerated obfusjack native artifact as `R-obfusjack`; when a task mentions random runtime failure, run it 10 consecutive times and treat any abort, hang, missing `=== All tests completed ===`, any `TLAB * allocation failed`, or new `hs_err_pid*.log` as incomplete.
- `R-native-test`: run the relevant `NativeObfuscationIntegrationTest` Gradle target after regeneration; use focused tests while iterating, then the full class before marking `[x]`.
- `R-inspect`: inspect generated C, native build logs, stdout, stderr, and newest `hs_err_pid*.log`; reject `translated=0`, `Native compilation produced no libraries`, skip-on-error success, forbidden JNI function-table calls, crashes, verifier errors, or fallback/original-bytecode execution.
- `R-negative`: where a subtask removes a fallback, force or inspect the missing-capability path so the failure mode is a hard abort/error, not JNI fallback or original bytecode.

Each subtask below requires the listed runtime proof after the latest edit:

- T0.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; prove native resolution readiness bits are required before Stage 2 resolver use.
- T0.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; prove missing GC barrier symbols abort and no GC mode is silently skipped.
- T1.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated manifest/export inspection must prove no `Java_*`/`RegisterNatives` entry path remains.
- T1.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated Windows trampoline inspection must prove Java ABI entry and no JNI state transition.
- T1.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated SysV/Aarch64 trampoline inspection must prove Java ABI entry and no JNI state transition.
- T1.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must prove patched compiled/interpreted entries execute the new stubs.
- T1.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; prove `NEKO_DISABLE_CODEBLOB` is gone and private CodeHeap failure aborts.
- T1.6: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must prove obfusjack-test21 completes and native execution stays in `_thread_in_java`.
- T2.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; `JNI_OnLoad` inspection must show only allowed `GetEnv` bootstrap behavior.
- T2.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove class resolver calls current bind path and missing class resolution aborts.
- T2.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove method resolver calls current bind path and missing method resolution aborts.
- T2.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove field resolver calls current bind path and missing field resolution aborts.
- T2.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove string literal binding uses `neko_intern_string` and generated C has no `NewStringUTF` on that path.
- T2.6: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove bind macros use native resolvers and Unsafe-reflection offset paths are removed.
- T2.7: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove HotSpot support derives compressed-oops/klass data natively and MXBean/Unsafe probes are gone.
- T2.8: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove `JNIHandles::resolve`/local handle support works and raw `*(void**)ref` fallback aborts.
- T2.8a: `R-build`, `R-native-test` x10, `R-native-obfusjack` x10, `R-inspect`, `R-negative`; runtime must prove obfusjack no longer randomly aborts with `TLAB * allocation failed for string literal` and no longer hangs after the platform-thread microbench output.
- T2.9: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated bind-support C must have zero forbidden `(*env)->`/`NEKO_JNI_FN_PTR` hits outside allowed `GetEnv`.
- T3.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute LDC String/Class through cached bind-time slots.
- T3.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute primitive field access through direct offsets with JNI fallback deleted.
- T3.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute object field/static loads through barrier-aware native load entry.
- T3.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute object field/static stores through barrier-aware native store entry.
- T3.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `ARRAYLENGTH` through direct array length offset.
- T3.6: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute primitive array load/store through direct array memory access.
- T3.7: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must execute `AALOAD`/`AASTORE` through barrier-aware array load/store and native store checks.
- T3.8: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `NEW` and `NEW+<init>` without JNI allocation.
- T3.9: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute primitive/object/multi array allocation without JNI allocation.
- T3.10: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `INSTANCEOF`/`CHECKCAST` through native subtype metadata.
- T3.11: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `getClass` through oop header klass and mirror.
- T3.12: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute monitor enter/exit through HotSpot synchronizer/stub entry.
- T3.13: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute `ATHROW` by writing `_pending_exception`.
- T3.14: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must construct implicit exceptions through native allocation/call-stub path.
- T3.15: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must dispatch exception handlers through `_pending_exception` read/clear.
- T3.16: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; runtime must execute boxing/unboxing through cached methods/direct fields without JNI wrappers.
- T3.17: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime plus safety checker must prove non-manifest invoke callees are rejected and JNI invoke wrappers are deleted.
- T3.18: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove supported invokedynamic paths run natively and unsupported paths reject before fallback.
- T3.19: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; runtime must prove StringConcat paths do not enter StringBuilder JNI fallback.
- T3.20: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; generated runtime support C must prove `NEKO_JNI_FN_PTR` and replaced wrappers are gone.
- T4.1: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; final grep must prove generated C has zero forbidden JNI function-table calls outside single `GetEnv`.
- T4.2: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; final inspection must prove `JNIEnv` is only a type/bootstrap handle and never a function-table caller after bootstrap.
- T4.3: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; final runtime must prove obfusjack-test21 and Calc pass, with Calc `<= 20 ms`.
- T4.4: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`, `R-negative`; final runtime or explicit VM-mode proof must cover G1, Parallel, Serial, ZGC, and Shenandoah strict behavior with no soft skip.
- T4.5: `R-build`, `R-test`, `R-obfusjack`, `R-native-test`, `R-inspect`; final replay/regression must prove the replacement for `replay_pid2968920.log` still covers the native path.

## Preparation

- [x] T0.1 VMStructs offsets for `SystemDictionary`, `InstanceKlass`, `ConstantPool`, `Symbol`, `StringTable`, `Universe`, `BarrierSet`, and `oopFactory`.
- [x] T0.2 GC barrier dlsym table for G1, ZGC, Shenandoah, Parallel, and Serial, with missing required symbols aborting.

## Stage 1: Native Entry

- [x] T1.1 Remove `RegisterNatives` / `Java_*` export generation; manifest must carry owner/method/sig plus native target pointer.
- [x] T1.2 Rewrite `X86_64WindowsTrampoline` to Java ABI entry with no JavaFrameAnchor/thread-state transition.
- [x] T1.3 Rewrite `X86_64SysVTrampoline` and `Aarch64SysVTrampoline` the same way.
- [x] T1.4 Patch `_from_compiled_entry` and `_from_interpreted_entry` to the new stubs / adapter path.
- [x] T1.5 Remove `NEKO_DISABLE_CODEBLOB` escape hatch; private CodeHeap init/register failure must fail native entry setup.
- [x] T1.6 Validate `obfusjack-test21` and thread state staying `_thread_in_java`.

## Stage 2: Bind-Time Native Resolution

- [x] T2.1 Reduce `JniOnLoadEmitter` to `GetEnv`, capture env/function table/thread register, run native layout init, and avoid exception-check/clear calls.
- [x] T2.2 Implement `neko_resolve_class(const char*)` via JVM symbol / SystemDictionary walk.
- [x] T2.3 Implement `neko_resolve_method(InstanceKlass*, name, sig)` by scanning `InstanceKlass::_methods`.
- [x] T2.4 Implement `neko_resolve_field(InstanceKlass*, name, sig, is_static)` by scanning field metadata.
- [x] T2.5 Implement `neko_intern_string(modutf, len)` without `NewStringUTF`.
- [x] T2.6 Rewrite `renderBindSupport()` macros and remove Unsafe-reflection field offset path.
- [x] T2.7 Rewrite `renderHotSpotSupport()` to remove MXBean and `Unsafe.addressSize()` probes.
- [x] T2.8 Remove `JniHandlesShimEmitter` raw `*(void**)ref` fallback; missing `JNIHandles::resolve` must abort.
- [x] T2.8a Fix random obfusjack runtime abort/hang and add 10-run native stability validation.
- [x] T2.9 Verify generated bind-support C has zero `(*env)->` / `NEKO_JNI_FN_PTR` hits outside allowed `GetEnv`.

## Stage 3: Hot-Path Opcode Removal

- [x] T3.1 LDC String / Class through bind-time cached slots.
- [x] T3.2 Primitive field access: direct offset only, no JNI fallback.
- [x] T3.3 Object field/static load through barrier-aware load entry.
- [x] T3.4 Object field/static store through barrier-aware store entry.
- [x] T3.5 `ARRAYLENGTH` direct length offset.
- [x] T3.6 Primitive array load/store direct memory path.
- [ ] T3.7 `AALOAD` / `AASTORE` barrier-aware path with bounds and store checks.
- [ ] T3.8 `NEW` / `NEW+<init>` without `AllocObject` / `NewObjectA`.
- [ ] T3.9 `NEWARRAY` / `ANEWARRAY` / `MULTIANEWARRAY` without JNI array allocation.
- [ ] T3.10 `INSTANCEOF` / `CHECKCAST` via subtype metadata walk.
- [ ] T3.11 `getClass` intrinsic via oop header klass and mirror.
- [ ] T3.12 `MONITORENTER` / `MONITOREXIT` via HotSpot synchronizer/stub entry.
- [ ] T3.13 `ATHROW` by writing `JavaThread::_pending_exception`.
- [ ] T3.14 Implicit exception construction without `ThrowNew`.
- [ ] T3.15 Exception dispatch via `_pending_exception` read/clear.
- [ ] T3.16 Boxing/unboxing through direct call_stub / field reads.
- [ ] T3.17 Reject non-manifest invoke callees and delete invoke JNI wrappers.
- [ ] T3.18 Desugar or direct-resolve `INVOKEDYNAMIC`; delete MethodHandle JNI fallback.
- [ ] T3.19 Remove StringBuilder JNI concat fallback.
- [ ] T3.20 Delete `NEKO_JNI_FN_PTR` macro and replaced runtime wrappers.

## Stage 4: Verification

- [ ] T4.1 Generated C grep for JNI function-table calls is zero outside the single `GetEnv`.
- [ ] T4.2 `JNIEnv` remains only as type / bootstrap handle, not function-table calls.
- [ ] T4.3 `obfusjack-test21` and Calc regression; Calc must be `<= 20 ms`.
- [ ] T4.4 G1 / Parallel / Serial / ZGC / Shenandoah strict compatibility with no soft skip.
- [ ] T4.5 Replay or replace `replay_pid2968920.log` regression baseline.
