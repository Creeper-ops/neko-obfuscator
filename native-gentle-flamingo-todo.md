# native-gentle-flamingo TODO / Plan Status

Source plan: `~/.claude/plans/native-gentle-flamingo.md`

Status legend:
- `[ ]` not started
- `[-]` partial / blocked by dependent work
- `[x]` completed in this worktree

Current notes:
- Must-do: after any TODO sub-step is completed and verified, immediately commit that completed slice before continuing.
- Existing worktree already had edits in `MethodPatcherEmitter.java`, `X86_64WindowsTrampoline.java`, and `NativeCompilationStage.java` before this pass.
- `test-jars/oouput/` is untracked and was left untouched.
- Generated C must not be considered native-success proof unless the exact native jars are regenerated and run.
- Existing excluded-dependency skipping is now counted as native rejection and becomes a hard failure when `skipOnError=false`.
- Native config default and checked-in native validation configs now use `skipOnError=false`; native build failure should not silently return original JVM classes.

Verification run in this pass:
- `env GRADLE_USER_HOME=/tmp/gradle-home-native-coverage bash ./gradlew :neko-native:compileJava` PASS.
- `env GRADLE_USER_HOME=/tmp/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest` PASS.
- `env GRADLE_USER_HOME=/tmp/gradle-home-native-coverage bash ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest` PASS.
- `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.CCodeGeneratorTest --tests dev.nekoobfuscator.test.OpcodeTranslatorUnitTest` PASS with system Gradle home after deleting `/tmp/gradle-home-native-coverage`.
- First strict native generation without Zig cache override failed as expected instead of falling back: `Native compilation produced no libraries` due read-only `/home/fadouse/.cache/zig`.
- `env XDG_CACHE_HOME=/tmp/neko-zig-cache neko-cli ... configs/native-test.yml ... /tmp/native-gentle-flamingo-TEST-native.jar` PASS: `translated=46 rejected=0`, wrote `neko/native/libneko_linux_x64.so`.
- `timeout 120s java -jar /tmp/native-gentle-flamingo-TEST-native.jar` PASS at process level: TEST output reached finish, `Calc: 12ms`; observed `Test 2.8: Sec ERROR`, so this is not recorded as all-green.
- After removing the MXBean probe path, regenerated `/tmp/native-gentle-flamingo-TEST-native.jar` and reran it: process exit 0, all expected native integration pass lines reached, `Calc: 13ms`; `Test 2.8: Sec ERROR` remains the repository's known TEST baseline as asserted by `NativeObfuscationIntegrationTest.nativeObfuscation_TEST_allTestsExceptSecurityPass`.
- `nm -D /tmp/native-gentle-flamingo-libneko_linux_x64.so | rg " Java_|neko_impl_|neko_entry_|JNI_OnLoad"` showed only exported `JNI_OnLoad`; no `Java_*` export symbols.
- Static generated-C check on `/tmp/neko_native_5410848013843882579/neko_native.c` still finds `NEKO_JNI_FN_PTR`, so T2.9/T4.1 remain open.
- `neko_exception_check` no longer falls back to JNI table index 228; early bootstrap without offsets returns `JNI_FALSE` until T2 moves all probes behind VMStructs.
- Follow-up regression: after removing the exception-check JNI fallback, `timeout 120s java -jar /tmp/native-gentle-flamingo-TEST-native.jar` crashed during JNI_OnLoad because the old `HotSpotDiagnosticMXBean` probe raised `NoSuchMethodError` before pending-exception offsets were available. Fix direction: remove MXBean probing, not restore JNI fallback.

## Preparation

- [ ] T0.1 VMStructs offsets for `SystemDictionary`, `InstanceKlass`, `ConstantPool`, `Symbol`, `StringTable`, `Universe`, `BarrierSet`, and `oopFactory`.
- [ ] T0.2 GC barrier dlsym table for G1, ZGC, Shenandoah, Parallel, and Serial, with missing required symbols aborting.

## Stage 1: Native Entry

- [x] T1.1 Remove `RegisterNatives` / `Java_*` export generation; manifest now carries owner/method/sig plus `neko_impl_*` target pointer, and the built native library exports no `Java_*` symbols.
- [ ] T1.2 Rewrite `X86_64WindowsTrampoline` to Java ABI entry with no JavaFrameAnchor/thread-state transition.
- [ ] T1.3 Rewrite `X86_64SysVTrampoline` and `Aarch64SysVTrampoline` the same way.
- [ ] T1.4 Patch `_from_compiled_entry` and `_from_interpreted_entry` to the new stubs / adapter path.
- [x] T1.5 Remove `NEKO_DISABLE_CODEBLOB` escape hatch; private CodeHeap init/register failure now fails native entry setup.
- [ ] T1.6 Validate `obfusjack-test21` and thread state staying `_thread_in_java`.

## Stage 2: Bind-Time Native Resolution

- [-] T2.1 `JNI_OnLoad` entry cleanup started: layout init failure now returns `JNI_ERR`, and `JniOnLoadEmitter` no longer emits exception check/clear calls. Full completion still requires replacing `neko_hotspot_init` / manifest discovery JNI paths.
- [ ] T2.2 Implement `neko_resolve_class(const char*)` via JVM symbol / SystemDictionary walk.
- [ ] T2.3 Implement `neko_resolve_method(InstanceKlass*, name, sig)` by scanning `InstanceKlass::_methods`.
- [ ] T2.4 Implement `neko_resolve_field(InstanceKlass*, name, sig, is_static)` by scanning field metadata.
- [ ] T2.5 Implement `neko_intern_string(modutf, len)` without `NewStringUTF`.
- [ ] T2.6 Rewrite `renderBindSupport()` macros and remove Unsafe-reflection field offset path.
- [-] T2.7 Rewrite `renderHotSpotSupport()` to remove MXBean and `Unsafe.addressSize()` probes. MXBean option probing is removed from active init; `Unsafe.addressSize()` still remains.
- [x] T2.8 Remove `JniHandlesShimEmitter` raw `*(void**)ref` fallback; missing `JNIHandles::resolve` now aborts.
- [ ] T2.9 Verify generated bind-support C has zero `(*env)->` / `NEKO_JNI_FN_PTR` hits outside allowed `GetEnv`. Current generated C still fails this check.

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
- [-] T3.20 Delete `NEKO_JNI_FN_PTR` macro and replaced runtime wrappers. `neko_exception_check` JNI fallback removed; many wrappers remain.

## Stage 4: Verification

- [ ] T4.1 Generated C grep for JNI function-table calls is zero outside the single `GetEnv`. Current generated C still contains `NEKO_JNI_FN_PTR`.
- [ ] T4.2 `JNIEnv` remains only as type / bootstrap handle, not function-table calls.
- [-] T4.3 `obfusjack-test21` and Calc regression; Calc <= 20 ms. TEST jar reached `Calc: 12ms`; obfusjack-test21 still not rerun in this pass.
- [ ] T4.4 G1 / Parallel / Serial / ZGC / Shenandoah strict compatibility with no soft skip.
- [ ] T4.5 Replay or replace `replay_pid2968920.log` regression baseline.
