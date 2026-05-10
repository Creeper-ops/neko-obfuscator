# NekoObfuscator Architecture

[Chinese version](./zh-CN/ARCHITECTURE.md)

## Scope

This document describes the current implementation, not the older planned architecture. The current CLI registers the rebuilt JVM passes in `neko-transforms/jvm` and optionally runs the native compilation stage from `neko-native`.

## Module Topology

```text
neko-cli
  -> neko-config
  -> neko-core
       -> neko-transforms
       -> neko-native     (loaded reflectively when native.enabled=true)
       -> neko-runtime    (classes injected into output JARs)
```

| Module | Role |
|---|---|
| `neko-api` | Configuration model, annotations, transform contracts. |
| `neko-config` | YAML parsing and validation. |
| `neko-core` | JAR input/output, class hierarchy, pass scheduling, cleanup, runtime injection, mapping output. |
| `neko-transforms` | Current JVM obfuscation passes: `renamer`, `keyDispatch`, `methodParameterObfuscation`, `controlFlowFlattening`, `constantObfuscation`, and `stringObfuscation`. |
| `neko-native` | Method selection, safety checking, invokedynamic lowering, JVM-to-C translation, C emission, native build, HotSpot method patching. |
| `neko-runtime` | Minimal runtime classes injected into output JARs. Currently `NekoNativeLoader`. |
| `neko-test` | JUnit integration and native tests. |

## IR Layers

| Layer | Current use |
|---|---|
| L1 | ASM `ClassNode` / `MethodNode` wrappers. All current JVM passes mutate L1 directly. |
| L2 | CFG/SSA infrastructure retained in `neko-core`; not the main path for the rebuilt CFF pass. |
| L3 | C-level model used by native translation and C generation. |

The rebuilt JVM CFF no longer depends on a generic L2 lowering path. It analyzes verifier frames directly from ASM, splits safe block leaders, and rewrites the original method body in place.

## Main Pipeline

`ObfuscationPipeline.execute(inputJar, outputJar)` performs:

1. Read classes and resources from the input JAR.
2. Build the class hierarchy and optional classpath hierarchy.
3. Create `PipelineContext`, including the build master seed.
4. Filter registered passes by `transforms.<id>.enabled`.
5. Schedule passes by phase and dependency.
6. Run enabled passes over L1 classes and methods.
7. Clean dirty bytecode: strip frames, remove unreachable code, sanitize try/catch ranges, recompute locals.
8. Harden generated helper methods when relevant.
9. If `renamer` is enabled, normalize generated helper API names added by later JVM passes and rewrite generated-name string constants globally.
10. Inject runtime classes needed by enabled features.
11. Run runtime-control-flow hardening when relevant.
12. Remap manifest entries and text/resource names affected by `renamer`.
13. Run optional native compilation.
14. Validate JVM coverage when `strictCoverage` is enabled.
15. Obfuscate injected runtime API names when `renamer.renameRuntime` is enabled.
16. Write the output JAR and optional mapping file.

## JVM Passes

The default CLI registration order is:

```text
renamer
keyDispatch
methodParameterObfuscation
controlFlowFlattening
constantObfuscation
stringObfuscation
```

The scheduler still honors phase and dependency metadata. `methodParameterObfuscation` depends on `keyDispatch`; `controlFlowFlattening` depends on `keyDispatch`; `constantObfuscation` and `stringObfuscation` depend on `controlFlowFlattening`.

### `renamer`

`JvmRenamerPass` runs in `PRE_TRANSFORM`, before generated CFF/string helper fields are created:

- Application classes are renamed into `transforms.renamer.packagePrefix`, default `a/`.
- Fields and methods receive short names. Override/interface groups are kept name-compatible, JVM entry points such as `main([Ljava/lang/String;)V` are preserved, and methods overriding external library methods are not renamed.
- Common class-name strings, `.class` resource strings, package resource paths, and direct `Class.getMethod` / `Class.getField` name literals are rewritten when the owner can be resolved or the mapping is globally unique.
- Source files, line numbers, and local debug tables are stripped.
- Mapping lines are written next to the output JAR as `<output>.map`.

Because CFF and string obfuscation add synthetic helper fields after this pass, `ObfuscationPipeline` performs a second renamer step near the end of the pipeline. That step renames generated helper fields/methods such as `$...`, `__e...`, and `__neko_n...` to the same short-name style, updates generated reflection-filter strings globally, and records those entries in the mapping file. This is why generated fields do not remain as `$1e76bobp`-style names in final output.

### `keyDispatch`

`JvmKeyDispatchPass` runs before CFF. It prepares hidden method key material:

- Eligible non-constructor, non-`main`, non-reflection-sensitive application methods have their descriptor rewritten from `(...args)R` to `(...args, long)R`.
- Call sites to rewritten application methods push the caller key before invocation.
- Boundary methods keep their JVM-mandated signature and seed a local key directly.
- Constructors and shape-sensitive methods are left with valid JVM signatures.

The pass publishes the local key slot under `controlFlowFlattening.flowKeyLocalByMethod`, so CFF and call-chain key dispatch share the same local.

### `controlFlowFlattening`

`ControlFlowFlatteningPass` depends on `keyDispatch` and rewrites each eligible method in place:

- Constructor code before the mandatory `this(...)` or `super(...)` call is preserved.
- Basic blocks are built only at labels with compatible verifier state.
- Dispatch groups are split by frame signature so a dispatcher never merges incompatible local states.
- Each group is split into islands with optional alias hubs.
- The active program counter (`pcLocal`) and island domain (`domainLocal`) are encoded using `guardLocal`, `pathKeyLocal`, and `blockKeyLocal`.
- Exception handlers receive bridges into the same keyed dispatcher model.

CFF also installs one synthetic class key table field per class that has protected application code. Reflection calls to `Class.getFields()` and `Class.getDeclaredFields()` are filtered so generated table fields are hidden, including cross-class reflection sites that inspect a different protected class.

### `methodParameterObfuscation`

`JvmMethodParameterObfuscationPass` runs after `keyDispatch`.

- Eligible methods are rewritten from their final post-key-dispatch descriptor to a descriptor with a single `Object[]` parameter.
- The prologue unpacks each original argument from the array, unboxes primitive values, casts references, and stores them back into the original local layout.
- Calls are rewritten to allocate and fill the carrier array before invocation.
- Collisions caused by packing overloads are resolved with a deterministic `$nkop$<hash>` suffix before later renaming.
- Constructors, class initializers, `main`, native methods, external overrides, annotations, generated methods, and invokedynamic handle targets are excluded.

### `constantObfuscation`

`JvmConstantObfuscationPass` runs after CFF and only rewrites original application instructions that CFF marked as protected:

- integer push opcodes, long/float/double numeric `LDC`, and `IINC`;
- numeric static `ConstantValue` fields for byte/char/short/int/long/float/double.

For instruction sites, the runtime decode depends on live CFF metadata: `guardLocal`, `pathKeyLocal`, `blockKeyLocal`, `pcLocal`, `domainLocal`, `keyLocal`, and a class-local key table word. If an instruction cannot be tied to CFF state metadata, the pass fails for that site instead of falling back to a method-only key. Numeric field constants have their `ConstantValue` attribute cleared and are assigned in `<clinit>` through generated decode bytecode. Float and double use raw bit decoding via `Float.intBitsToFloat` and `Double.longBitsToDouble`.

### `stringObfuscation`

`JvmStringObfuscationPass` also runs after CFF and rewrites original protected string sites:

- direct string `LDC`;
- string static final `ConstantValue` fields lowered into `<clinit>` before CFF/string processing;
- literal and constant string pieces inside `StringConcatFactory` recipes.

Each encrypted payload is length-prefixed UTF-8, padded to AES or DES block size, XOR-mixed with a stream derived from live CFF state, then encrypted with `AES/ECB/NoPadding` or `DES/ECB/NoPadding`. Runtime decode recomputes the key from live CFF state and the class key table. The pass caches per-class `Cipher` instances and per-site encrypted payload/fingerprint/string fields in the owner class to reduce repeated allocation without adding runtime/helper classes.

Generated string helper fields are synthetic and are hidden from reflective field enumeration. If `renamer` is enabled, those helper names are normalized by the post-pass generated-helper API renamer.

## Native Stage

When `native.enabled: true`, `ObfuscationPipeline` reflectively loads `NativeCompilationStage`.

The native stage:

1. Parses all classes.
2. Lowers supported `invokedynamic` forms before selection:
   - `LambdaMetafactory.metafactory`
   - `ObjectMethods.bootstrap`
3. Selects methods by annotation and glob patterns.
4. Closes over application callees so translated methods do not call untranslated application methods through an unsupported edge.
5. Iteratively rejects unsafe methods until the native manifest is closed and safe.
6. Translates selected bytecode to C functions.
7. Emits C source, headers, manifest, signature dispatchers, trampolines, and HotSpot patching support.
8. Builds platform libraries with Zig.
9. Rewrites translated Java methods into large `LinkageError` stubs that are not intended to execute.
10. Injects `NekoNativeLoader.load()` into owner class `<clinit>`.
11. Packages native libraries under `/neko/native/`.

The actual native entry path is HotSpot method-entry patching, not Java `native` stubs. `JNI_OnLoad` captures the current `JavaThread`, initializes HotSpot metadata from VMStructs and symbols, discovers manifest entries, and patches Method entry points to generated trampolines.

## Runtime Injection

The current runtime surface is intentionally small. `NekoNativeLoader` is injected only when native mode is enabled. It extracts the platform library to a temporary file and calls `System.load`.

JVM string and constant obfuscation do not inject runtime/helper classes. Their decode logic is emitted directly into transformed methods and class initializers.

## Failure Model

For strict native work, configure `native.skipOnError: false`. With `skipOnError: true`, the current code can return the original class/resource set if translation or compilation fails; that is useful for development but is not acceptable validation for native coverage.

Generated native code should fail closed when mandatory HotSpot metadata, GC barriers, CodeHeap support, or call-stub support is missing.
