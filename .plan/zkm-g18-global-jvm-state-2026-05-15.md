# ZKM g18 Global JVM State Obfuscation Plan - 2026-05-15

## Scope

Implement the ZKM PDF g18-style JVM key-dispatch architecture as a generic, architecture-level strengthening of the JVM obfuscation pipeline. The required target is not a reduced approximation: class key initialization must become stateful and globally JVM-state-dependent, method keys must remain live-key linked through hidden `long` and `Object[]` carriers, and string/constant/invokedynamic/CFF call-site keys must consume the live method key chain.

Interpretation: the requested `cinti` class-key initialization is treated as JVM `<clinit>` class-key initialization, because the PDF and current repository use class initializer material tables for class roots.

## Non-negotiable invariants

- No fallback, skip-on-error, original-bytecode rescue, JNI/JVMTI helper path, transform exclusion, downgraded CFF coverage, or descriptor-only/static recomputation.
- No self-canceling key equations: no neutral XOR/add/sub pairs, inverse pairs, dead stores, or expressions that fold to `seed`, `descriptorHash`, `stateKey`, or static table material without live entry-key input.
- No hidden-key surface removal: hidden `long` and packed `Object[]` carriers remain mandatory and must be preserved through direct calls, constructors, reflection rewrites, MethodHandle/LambdaMetafactory paths, virtual/interface dispatch, callback/field-delayed paths, and exception/loop paths.
- No plaintext reflective metadata or key leakage: generated compatibility rewrites must not leave plaintext owner/name/descriptor/resource data or raw class/method/call-site keys in bytecode, maps, logs, helper APIs, or material tables.
- Missing required key/capability paths fail closed; they do not silently run unprotected code.
- The four generated full-JVM-obfuscation jars under `build/test-jvm-full-obf-perf/` must preserve the baseline behavior contract after the change.

## Evidence gathered before implementation

- `docs/ZKM.pdf` describes a layered chain: `<clinit>` class roots from `g18.a(k0,k1,lookupClass()).a(k2)`, method seed from `Object[]`/field/callback carrier XOR class seed, per-site key from literal XOR method seed, d11/invokedynamic key pair resolution, and callback/constructor/virtual seed propagation.
- g18 required behavior from the PDF: entry asserts seed validity, creates two mutable nodes, composes through a registry-aware node combiner, records `lookupClass`/owner in global state, emits a projected old state, then mutates node/global state and propagates the update to children. Global registry selection can reuse prior nodes and clone shift tables, so class key recovery is not a pure static function of literals.
- Current pass order in `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/StandardJvmPasses.java:18-26`: `renamer -> keyDispatch -> methodParameterObfuscation -> controlFlowFlattening -> invokeDynamic -> constantObfuscation -> stringObfuscation`.
- Current key initialization in `JvmKeyDispatchPass.java:218-222` is `push(seed ^ mask); push(mask); LXOR`, which is statically reversible. Incoming key mix in `JvmKeyDispatchPass.java:1167-1173` still combines a live local with static seed/mask material, but the class root is not g18/global-state-bound.
- Current method seed in `JvmKeyDispatchPass.java:229-249` is deterministic from master seed, owner/name/descriptor/instruction metadata or virtual-family owner; it is not a mutable global-state emission.
- Current descriptor/key propagation in `JvmKeyDispatchPass.java:425-448` and `634-688` appends/propagates hidden long keys and rewrites direct, indy, lookup, and reflective invoke paths.
- Current method-parameter pass packs `Object[]` carriers and may split the hidden key as an explicit primitive long (`JvmMethodParameterObfuscationPass.java:180-207`, `725-770`, `1527-1580`). Split hidden key is compatible behaviorally but weaker than mandatory carrier-linked ZKM-style propagation and must be folded into the full design.
- Current CFF uses live locals for guard/path/block/method key (`CffKeyStateEmitter.java:996-1015`, `1101-1114`) and rewrites generated key loads via dynamic material (`CffKeyTransferRewriter.java:158-210`, `730-765`), making it the correct choke point for per-block key update linkage.
- Current class material table is one synthetic static `Object[]` per class (`CffClassSetup.java:316-435`, `CffMaterialTables.java:59-115`, `CffSharedState.java:74-96`, `290-348`). This is the existing transform surface to extend; no standalone runtime helper class should be added.
- Current indy/string/constant consumers already take live CFF state: indy appends a live long (`JvmInvokeDynamicObfuscationPass.java:170-225`, `400-407`, `2435-2458`), constants fold guard/path/block/method material (`JvmConstantObfuscationPass.java:375-430`), and strings fold live words with string material (`JvmStringObfuscationPass.java:929-940`, `2334-2390`). These must be rekeyed to the g18-derived method/call-site chain.
- `neko-test/src/test/java/dev/nekoobfuscator/test/JvmFullObfuscationPerfTest.java:46-76` is the current four-jar full-obf behavior/perf gate for TEST, obfusjack, SnakeGame, and evaluator. At planning time, `build/test-jvm-full-obf-perf/` read as empty, so approved execution must first regenerate the baseline jars/report before changing behavior.

## Target architecture

1. **Generated g18-equivalent state model inside existing transform surface**
   - Extend the CFF/class-key material model with a generated application-owned global state host and per-class class-key state cells. Use existing synthetic member/material-table conventions; do not add a new external runtime helper class.
   - Represent g18 nodes as mutable state records/cells containing at least: primary long state, secondary delta, shift-table snapshot/index, mask-table snapshot/index, and optional child-link/index. The bytecode path must emit a projection of old state before mutation and child propagation.
   - Bind every class-root emission to `MethodHandles.lookup().lookupClass()` or an equivalent generated bytecode owner-binding that records the runtime class object in the global state side channel.
   - Use registry/memoization behavior: class `<clinit>` must mutate globally shared state and allow later class keys to depend on prior class-load state, not only per-class literals.

2. **Class key initialization in `<clinit>`**
   - Each transformed class gets one or more synthetic class root words initialized in `<clinit>` by the generated g18 state path: `classRoot = emitOldProjectedState(global/compositeNode, k2); mutate(node/global/child, k2, ownerBinding)`.
   - Existing class material tables are initialized from the class root, not directly from static build-time seed/mask material.
   - Interface/class initializer ordering must stay verifier-correct and deterministic for normal JVM execution; missing state host/capability hard-aborts rather than using an unprotected static seed.

3. **Method seed and carrier propagation**
   - Replace deterministic `methodSeed(...)` as the runtime effective key source with `methodSeedRuntime = carrierExpr ^ classRoot ^ liveEntryFold`, where `carrierExpr` is supplied through hidden `long`/`Object[]`/field/callback/virtual propagation and `liveEntryFold` depends on CFF entry state when present.
   - Preserve build-time seed metadata only as encryption material used to generate/verifiably decode runtime keys; do not expose it as the runtime key.
   - Remove or eliminate split-hidden-key weakening where it disconnects the hidden key from the `Object[]` carrier. If a primitive long ABI is JVM-required for a path, it must be dynamically linked to the carrier/classRoot and not become a bypass.
   - Vary carrier slots per method/caller, including reflection/MethodHandle/Lambda paths, instead of relying on a fixed element index.

4. **Per-block and call-site keying**
   - CFF block transitions must update live key state nonlinearly from method-entry key material, block state, guard/path/block locals, and class-root/global material.
   - Generated key transfers must decode target keys from current block key state plus g18-derived method seed. `incomingRawForCanonical` and generated key-load replacement must no longer produce values that can be derived without live entry state.
   - String/constant/invokedynamic sites must use `Ksite = siteLiteral/maskedSiteMaterial xor liveMethodSeed` plus CFF block-local material; the local literal alone or descriptor-only metadata must not recover the target.

5. **d11-style invokedynamic strengthening**
   - Keep invokedynamic targets hidden behind a key-pair resolver with last hidden long argument(s) and MutableCallSite first-use rewrite semantics.
   - Bind resolver lookup to `(key1 literal/material, live key2 method seed, lookup owner, descriptor/type)` and table index/mask schedule; no public callable oracle that accepts just two longs and returns Method/Field/Class.
   - Existing bootstrap/helper generated methods must be owner-bound and descriptor-bound; wrong key or wrong owner must fail closed, not cache a wrong plaintext target.

6. **Compatibility paths**
   - Reflection lookup descriptors, invocation argument arrays, MethodHandle adapters, LambdaMetafactory targets, constructors, virtual/interface overrides, callbacks, exception paths, recursion, loops, and async entry points must all carry the same linked key chain.
   - True JVM/external ABI entry points remain valid (`<init>`, `<clinit>`, `main([Ljava/lang/String;)V`, inherited external overrides), but their internal first protected call must enter the g18/classRoot carrier chain.

## Subtasks and required evidence

### [x] Subtask 1: Regenerate and lock the four-jar baseline

- Scope: regenerate `build/test-jvm-full-obf-perf/` using the current code before implementation and record TEST, obfusjack, SnakeGame, and evaluator behavior rows.
- Required evidence: fresh jar paths, report path, original-vs-full-obf exit codes/output markers, and any performance numbers already enforced by the test.
- Validation command/target: repository `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks` after user grants Gradle permission.
- Completion criteria: four fresh full-obf jars and `jvm-full-obf-performance-baseline.json` exist; behavior baseline is preserved before source changes.
- Validation result: passed `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`. Fresh outputs exist under `build/test-jvm-full-obf-perf/`: `TEST-full-jvm-obf.jar`, `obfusjack-full-jvm-obf.jar`, `SnakeGame-full-jvm-obf.jar`, `evaluator-full-jvm-obf.jar`, and `jvm-full-obf-performance-baseline.json`.

### [x] Subtask 2: Implement generated g18 global-state host and `<clinit>` class roots

- Scope: extend `CffSharedState`, `CffClassSetup`, and `CffMaterialTables` so class-key tables derive from g18-style mutable global state initialized through class `<clinit>`.
- Required evidence: generated bytecode contains owner-bound class-root emission, old-state projection before mutation, registry/global-state update, and no static `seed ^ mask ^ mask` root path for protected classes.
- Validation command/target: targeted unit/bytecode tests for generated class initialization plus R-inspect on fresh full-obf jars.
- Completion criteria: every protected class has class-root material dependent on global mutable g18 state; missing state fails closed.
- Validation result: passed `./gradlew :neko-transforms:compileJava` and `./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks`. Bytecode inspection with `javap -classpath build/test-jvm-full-obf-perf/TEST-full-jvm-obf.jar -c -p a.a` shows `<clinit>` creates synthetic `AtomicLong` state at the class carrier g18 slot, reads old state, projects a 48-bit root before mutation, mutates the cell with `AtomicLong.set(J)`, and decodes class key words from that live root.

### [ ] Subtask 3: Rewire method key dispatch to class-root carriers

- Scope: update `JvmKeyDispatchPass` so method key locals and callsite transfers derive from class-root-linked runtime carriers rather than deterministic method seed constants.
- Required evidence: `emitKeyInit`, `emitIncomingKeyMix`, `incomingRawForCanonical`, direct calls, invokedynamic rewrites, reflective lookup/invoke rewrites, and virtual-family seed handling all consume live class-root/method-entry material.
- Validation command/target: key-dispatch regression tests plus bytecode inspection for direct, virtual/interface, reflection, MethodHandle, lambda, and constructor paths.
- Completion criteria: no protected method can recover its effective key from descriptor/static constants alone; call boundaries preserve linked keys.

### [ ] Subtask 4: Make Object[] carrier propagation ZKM-equivalent

- Scope: update `JvmMethodParameterObfuscationPass` planning, `MethodPlan`, unpack prologues, `packCallArguments`, reflective carriers, MethodHandle carriers, and split-hidden-key handling.
- Required evidence: carrier slot varies generically; hidden keys remain inside or dynamically bound to `Object[]`; split primitive paths are not bypasses; reflection/lambda/virtual dispatch argument arrays carry the same key chain.
- Validation command/target: method-parameter, reflection, lambda, evaluator, and obfusjack full-obf behavior tests.
- Completion criteria: Object[] carrier and hidden long key remain mandatory obfuscation surfaces with no plaintext descriptor/name preservation workaround.

### [ ] Subtask 5: Rebind CFF per-block key updates and generated key transfers

- Scope: update `CffKeyStateEmitter`, `CffKeyTransferRewriter`, `ControlFlowFlatteningPass`, and related material registration so block transitions and decoded target keys depend on g18 method-entry material.
- Required evidence: generated CFF dispatch decisions and key transfers consume live key locals plus guard/path/block/pc state; no self-canceling algebra remains; block count/selection/granularity is unchanged.
- Validation command/target: CFF algebraic audit, strong-entry-seed regression, generated bytecode scan, and four-jar behavior gate.
- Completion criteria: every protected block remains keyed by live method-entry material from entry through dispatch, transitions, handlers, loops, and downstream calls.

### [ ] Subtask 6: Rebind string, constant, and invokedynamic call-site keys

- Scope: update `JvmStringObfuscationPass`, `JvmConstantObfuscationPass`, and `JvmInvokeDynamicObfuscationPass` so local string/integer/long/reference sites consume `site material xor live method seed` with class-root/global state.
- Required evidence: generated sites cannot decode with site literals and static tables only; indy resolver is owner/descriptor/live-key bound and has no public two-long oracle; MutableCallSite caching remains first-key-correct.
- Validation command/target: string, constants, indy-reference tests plus R-inspect on fresh full-obf jars.
- Completion criteria: strings/constants/references remain obfuscated and behavior-equivalent with wrong-key paths failing closed.

### [ ] Subtask 7: Remove weakening remnants and add structural audits

- Scope: remove obsolete static-key paths, split-key bypasses, plaintext compatibility remnants, old helper ABIs, and any generated public oracle surface not required by JVM linkage.
- Required evidence: repository/static bytecode scans for `seed ^ mask ^ mask` root patterns, direct descriptor-only recomputation, raw static keys, plaintext reflective metadata, fallback markers, and old helper descriptors.
- Validation command/target: existing structural audits plus new targeted tests for g18 class roots, carrier slots, and fail-closed wrong-key behavior.
- Completion criteria: scans pass and generated artifacts expose no reduced-strength compatibility data.

### [ ] Subtask 8: Full runtime validation and checkpoint commit

- Scope: run the complete validation suite needed for this change, update this plan with fresh evidence, and commit only the validated implementation plus matching plan update.
- Required evidence: fresh Gradle output, four-jar behavior report, generated bytecode inspection notes, no verifier/runtime/fatal errors, no fallback/skip behavior, and performance not regressed against baseline.
- Validation command/target: at minimum `JvmFullObfuscationPerfTest`, key/CFF/string/constant/indy/reflection/lambda targeted tests; any failing branch gets fixed and rerun after the final source change.
- Completion criteria: all four full-obf jars preserve baseline behavior, obfuscation-strength audits pass, and a clean checkpoint commit is created.

## Approval gate

Implementation is not started in this plan. User approval is required before source edits and before using repository `./gradlew` for baseline/validation. After approval, the first action is Subtask 1; no later subtask can be marked complete without fresh runtime evidence.
