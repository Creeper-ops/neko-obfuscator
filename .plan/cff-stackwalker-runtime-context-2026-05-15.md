# CFF StackWalker Runtime Context Optimization — 2026-05-15

## Scope

Optimize generated CFF/key-transfer runtime context mixing for stack-sensitive material helpers.

## Subtasks

### [x] 1. Implement StackWalker runtime source

- Scope: `CffIslandMaterial.java` generated runtime-source bytecode.
- Evidence required: generated helper bytecode contains JDK 9+ StackWalker path and Java 8 StackTrace fallback path.
- Evidence: `javap -classpath build/test-jvm-full-obf-perf/TEST-full-jvm-obf.jar -c -p a.a` shows `StackWalker.getInstance(RETAIN_CLASS_REFERENCE).walk(Function)` on JDK 9+ and a `Thread.getStackTrace()` legacy branch guarded by `java.specification.version`.
- Validation command: `./gradlew :neko-transforms:compileJava`.
- Completion criteria: runtime source generation compiles without new helper classes, JNI, skip, or original-bytecode fallback.

### [x] 2. Share key-transfer runtime context

- Scope: `installKeyTransferMaterialHelper(...)` and key-transfer cursor decoding.
- Evidence required: generated high/low material helper computes shared runtime context once when modes match.
- Evidence: key-transfer helper decodes high/low cursor modes up front; equal modes call runtime-source cursor emission once and derives the low cursor from the high cursor bucket delta.
- Validation command: full JVM obfuscation performance test.
- Completion criteria: high/low decode still returns correct material and avoids duplicate stack/thread context computation for same runtime mode.

### [x] 3. Update island context mixing

- Scope: CFF island runtime source and step material source emitters.
- Evidence required: stack frame depth is derived from live key/guard/path/block/cursor/mode, not fixed stack[2]/stack[3].
- Evidence: generated fallback branch derives frame indexes from the mixed source (`source ^ source >>> 16`, `source >>> 5`) and generated StackWalker target iterates to those dynamic depths; final javap output contains no `Arrays.copyOf`.
- Validation command: full JVM obfuscation performance test.
- Completion criteria: generated code uses dynamic depth and no Arrays.copyOf/filter array in helper path.

### [x] 4. Validate generated obfuscation outputs

- Scope: generated TEST full JVM obfuscation jar.
- Evidence required: compile/test pass plus generated-bytecode inspection.
- Evidence: `./gradlew :neko-transforms:compileJava` and `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks` both passed after the final source change. `javap` inspection confirms StackWalker, legacy StackTrace branch, dynamic stack depths, and no `Arrays.copyOf`.
- Validation command: `./gradlew :neko-transforms:compileJava && ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
- Completion criteria: test passes; generated helpers show StackWalker path, Java 8 StackTrace fallback, no split hidden-key ABI, no CFF fallback/original-bytecode marker.
