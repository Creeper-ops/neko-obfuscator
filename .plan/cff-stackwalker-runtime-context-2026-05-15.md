# CFF StackWalker Runtime Context Optimization — 2026-05-15

## Scope

Optimize generated CFF/key-transfer runtime context mixing for stack-sensitive material helpers.

## Subtasks

### [ ] 1. Implement StackWalker runtime source

- Scope: `CffIslandMaterial.java` generated runtime-source bytecode.
- Evidence required: generated helper bytecode contains JDK 9+ StackWalker path and Java 8 StackTrace fallback path.
- Validation command: `./gradlew :neko-transforms:compileJava`.
- Completion criteria: runtime source generation compiles without new helper classes, JNI, skip, or original-bytecode fallback.

### [ ] 2. Share key-transfer runtime context

- Scope: `installKeyTransferMaterialHelper(...)` and key-transfer cursor decoding.
- Evidence required: generated high/low material helper computes shared runtime context once when modes match.
- Validation command: full JVM obfuscation performance test.
- Completion criteria: high/low decode still returns correct material and avoids duplicate stack/thread context computation for same runtime mode.

### [ ] 3. Update island context mixing

- Scope: CFF island runtime source and step material source emitters.
- Evidence required: stack frame depth is derived from live key/guard/path/block/cursor/mode, not fixed stack[2]/stack[3].
- Validation command: full JVM obfuscation performance test.
- Completion criteria: generated code uses dynamic depth and no Arrays.copyOf/filter array in helper path.

### [ ] 4. Validate generated obfuscation outputs

- Scope: generated TEST full JVM obfuscation jar.
- Evidence required: compile/test pass plus generated-bytecode inspection.
- Validation command: `./gradlew :neko-transforms:compileJava && ./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`.
- Completion criteria: test passes; generated helpers show StackWalker path, Java 8 StackTrace fallback, no split hidden-key ABI, no CFF fallback/original-bytecode marker.
