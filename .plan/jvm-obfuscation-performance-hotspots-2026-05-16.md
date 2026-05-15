# JVM obfuscation performance hotspot analysis — 2026-05-16

## Context

User requested analysis of current JVM obfuscation performance hotspots, especially per-CFF-basic-block key updates that generate large amounts of computation/outlining, and the performance impact of the AtomicLong key table.

## Subtasks

- [x] Map performance paths
  - Scope: locate JVM CFF key-update generation and AtomicLong key-table usage paths.
  - Required evidence: exact source files/classes/methods and generated-code shape responsible for per-block key updates and key-table access.
  - Validation command or runtime target: static source inspection only; do not run repository `./gradlew` without user permission.
  - Completion criteria: concrete source/runtime paths and invariants identified without implementation changes.
  - Evidence found: transition insertion in `CffDispatchEmitter.transition` and `emitTransitionCore`; edge rewrites for goto/conditional/switch/fallthrough call it; simple per-edge step update is `CffKeyStateEmitter.emitStepKeys`; large-method materialized step helper is `emitMaterializedStepKeys` -> `CffMaterialTables.installStepMaterialHelper`; encrypted PC/domain token path uses `emitEncryptedToken` -> token material helper; class key material and AtomicLong object cells are installed in `CffMaterialTables.installClassKeyTableInit`; additional AtomicLong global/class helper code is in `CffClassSetup` and island material object mask code is in `CffIslandMaterial.emitTokenMaterialObjectMask`.

- [x] Assess hotspot costs
  - Scope: analyze cost drivers in per-CFF-block key updates and AtomicLong key table access.
  - Required evidence: call graph/source snippets/logical operation counts from source and the user-provided generated/decompiled example.
  - Validation command or runtime target: static analysis; optional dynamic benchmark only after user grants permission to use repository `./gradlew`.
  - Completion criteria: generic architecture-level recommendations preserving live key propagation and obfuscation strength.
  - Evidence found: user-provided decompiled block matches current source shape: one local tiny key mutation, two encrypted token helper calls for PC/domain (`emitEncryptedToken`), one method-key helper call (`emitStoreMethodKey`), and optional predicate work. Hot loops suffer because this sequence is inserted for each CFF edge/block exit. For outlined/materialized paths, helper indirection reduces method size but introduces repeated static calls, table loads, row decoding, `Thread.currentThread`/thread-name/hash inputs in `emitStepMaterialRuntimeSource`, result routing, and object-cell mutation. AtomicLong cost is concentrated in class key object cells: clinit creates 64 AtomicLong cells and token material/object helpers cast table entries to AtomicLong and execute `getPlain`/`setPlain`; other helpers use `get`/`set`. Even plain modes keep object indirection/type checks and prevent scalarization across helper boundaries. Recommended next work: benchmark a generic reduced per-edge transition plan that keeps live key propagation but eliminates redundant per-edge helper calls by fusing PC/domain/method-key decode into one transition helper/result carrier, and replace AtomicLong object cells with plain long[] or two int[] epoch/value tables where no cross-thread atomic ordering is required.

## Implementation request — inline transition and atomic key-table optimization

- [x] Research ZKM update
  - Scope: read repository docs about ZKM key/state update style and compare against current CFF transition generation.
  - Required evidence: exact doc passages/files and exact source methods that differ.
  - Validation command or runtime target: static documentation/source inspection only.
  - Completion criteria: identify which transition computations can be inlined without helper calls and which values must remain independent to avoid leaking/reducing attack surface.
  - Evidence found: `docs/WHITEPAPER.md` states CFF is direct over the original method body, uses live locals `keyLocal`, `pcLocal`, `guardLocal`, `pathKeyLocal`, `blockKeyLocal`, `domainLocal`, and encodes PC/domain from the guard/path/block triad. Its edge evolution is intentionally tiny (`dst + (source ^ c)`, `(dst ^ c) + source`, etc.) and may mutate method key. `docs/ZKM.pdf` section 16.3 describes a single-block update as emitting from old state then mutating state, and method/site derivation as simple seed recomposition, not generated helper calls. Current source differs on helperized class-table paths: `emitEncryptedToken` calls the generated token material helper when `activeKeyTable` exists; `emitMethodKeyFromDecodedState` calls the generated method-key helper; `emitClassTokenMask` calls the generated int helper; `emitClassObjectTokenMaskAndUpdate` calls the generated object helper; object material uses per-cell `AtomicLong` in `installClassKeyTableInit` and `emitTokenMaterialObjectMask`. Security-relevant independent masks are PC token, domain token, control token mask, class word mask, object token mask/update, and method-key word decode; only helper boundaries and object-cell representation are proven removable, not the mask equations themselves.

- [x] Plan inline transitions
  - Scope: specify a generic inline per-edge transition implementation that preserves live key propagation, does not reuse non-useless security-relevant computations, and leaves runtime-source/materialized-step behavior unchanged.
  - Required evidence: source-level dataflow for PC token, domain token, method key, decoded block keys, and helper paths.
  - Validation command or runtime target: static inspection plus later generated artifact validation after permission for `./gradlew`.
  - Completion criteria: concrete implementation plan with forbidden/fallback risks ruled out.
  - Plan/evidence: implement only proven boundary/representation removals. Do not merge PC/domain/method-key masks or reuse non-identical mask bases, because those are security-relevant independent derivations. Inline the generated method-key helper by always emitting the existing direct method-key bytecode. Inline the class int-table helper by emitting the same `IALOAD` plus digest arithmetic directly. Inline token object mask/update at call sites instead of generated token helper calls, but preserve the class object mask/update equation. Preserve item 4/runtime-source materialized-step behavior unchanged. For cross-thread consistency, replace per-cell `AtomicLong` object cells with one `AtomicLongArray` in the class material carrier and use `get`/`compareAndSet` retry loops for linearizable old-state emission plus mutation; this is stronger than the current `getPlain`/`setPlain` pair and removes per-cell object/cast overhead. Validation still requires user permission for repository `./gradlew`.

- [ ] Implement inline transitions
  - Scope: implement direct inline transition token/key update for eligible per-edge hot path, keeping block coverage and live key propagation unchanged and not introducing helper/fallback paths.
  - Required evidence: source diff showing helper calls removed only where computations are proven redundant/useless, with remaining independent key material preserved.
  - Validation command or runtime target: compile/runtime validation only after user grants permission to use repository `./gradlew`.
  - Completion criteria: code builds and generated artifact shows fewer per-edge helper calls without plaintext/static key leaks.

- [ ] Implement atomic scheme
  - Scope: replace AtomicLong key-table cells with a more performant atomic-order-preserving representation if evidence proves equivalent cross-thread consistency.
  - Required evidence: exact read/write sites and required Java Memory Model ordering; replacement must preserve atomic 64-bit value semantics and ordering.
  - Validation command or runtime target: compile/runtime validation only after user grants permission to use repository `./gradlew`.
  - Completion criteria: source no longer uses slow object-cell AtomicLong path for hot key table while preserving required atomic consistency and failing closed on unsupported paths.

- [ ] Validate performance changes
  - Scope: regenerate fresh artifacts and run required runtime/performance checks for changed CFF and key-table paths.
  - Required evidence: fresh R-build, R-test/R-native-test and inspection proving no forbidden JNI/fallback/static-key leakage; performance comparison if baseline can be gathered.
  - Validation command or runtime target: repository `./gradlew` only after explicit user permission, then the project-specific runtime targets.
  - Completion criteria: all required validations pass on fresh artifacts.
