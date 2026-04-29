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

## Preparation

- [x] T0.1 VMStructs offsets for `SystemDictionary`, `InstanceKlass`, `ConstantPool`, `Symbol`, `StringTable`, `Universe`, `BarrierSet`, and `oopFactory`.
- [x] T0.2 GC barrier dlsym table for G1, ZGC, Shenandoah, Parallel, and Serial, with missing required symbols aborting.

## Stage 1: Native Entry

- [x] T1.1 Remove `RegisterNatives` / `Java_*` export generation; manifest must carry owner/method/sig plus native target pointer.
- [x] T1.2 Rewrite `X86_64WindowsTrampoline` to Java ABI entry with no JavaFrameAnchor/thread-state transition.
- [x] T1.3 Rewrite `X86_64SysVTrampoline` and `Aarch64SysVTrampoline` the same way.
- [ ] T1.4 Patch `_from_compiled_entry` and `_from_interpreted_entry` to the new stubs / adapter path.
- [ ] T1.5 Remove `NEKO_DISABLE_CODEBLOB` escape hatch; private CodeHeap init/register failure must fail native entry setup.
- [ ] T1.6 Validate `obfusjack-test21` and thread state staying `_thread_in_java`.

## Stage 2: Bind-Time Native Resolution

- [ ] T2.1 Reduce `JniOnLoadEmitter` to `GetEnv`, capture env/function table/thread register, run native layout init, and avoid exception-check/clear calls.
- [ ] T2.2 Implement `neko_resolve_class(const char*)` via JVM symbol / SystemDictionary walk.
- [ ] T2.3 Implement `neko_resolve_method(InstanceKlass*, name, sig)` by scanning `InstanceKlass::_methods`.
- [ ] T2.4 Implement `neko_resolve_field(InstanceKlass*, name, sig, is_static)` by scanning field metadata.
- [ ] T2.5 Implement `neko_intern_string(modutf, len)` without `NewStringUTF`.
- [ ] T2.6 Rewrite `renderBindSupport()` macros and remove Unsafe-reflection field offset path.
- [ ] T2.7 Rewrite `renderHotSpotSupport()` to remove MXBean and `Unsafe.addressSize()` probes.
- [ ] T2.8 Remove `JniHandlesShimEmitter` raw `*(void**)ref` fallback; missing `JNIHandles::resolve` must abort.
- [ ] T2.9 Verify generated bind-support C has zero `(*env)->` / `NEKO_JNI_FN_PTR` hits outside allowed `GetEnv`.

## Stage 3: Hot-Path Opcode Removal

- [ ] T3.1 LDC String / Class through bind-time cached slots.
- [ ] T3.2 Primitive field access: direct offset only, no JNI fallback.
- [ ] T3.3 Object field/static load through barrier-aware load entry.
- [ ] T3.4 Object field/static store through barrier-aware store entry.
- [ ] T3.5 `ARRAYLENGTH` direct length offset.
- [ ] T3.6 Primitive array load/store direct memory path.
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
