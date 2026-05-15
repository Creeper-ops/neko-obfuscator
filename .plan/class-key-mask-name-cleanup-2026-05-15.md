# Class-key mask helper name cleanup — 2026-05-15

## Scope

Remove the misleading `G18` token from the class-key word mask helper names in `CffMaterialTables` only. This is a naming cleanup; the ZKM/g18-inspired obfuscation implementation and generated behavior must not change.

## Evidence

- `emitG18ClassKeyWordMask(...)` emits the runtime int mask used to decode class-key table words; it does not emit or initialize g18 state.
- Its pure Java counterpart `g18ClassRootWord(...)` computes the same per-index class-key word mask for compile-time encoding.
- Both helpers are private to `CffMaterialTables` and are only used by `installClassKeyTableInit(...)`.

## Subtasks

### [x] 1. Record rename evidence

- Scope: class-key word mask helper declarations and callsites in `CffMaterialTables`.
- Required evidence: references are local and the change is name-only.
- Evidence: LSP references found the runtime emitter declaration and one callsite; source context shows the pure counterpart is used on the adjacent compile-time encoding line.
- Validation target: LSP/source audit.
- Completion criteria: plan records that g18-inspired obfuscation remains unchanged.

### [x] 2. Rename helpers without g18

- Scope: `g18ClassRootWord(...)`, `emitG18ClassKeyWordMask(...)`, and their callsites.
- Required evidence: no remaining references to the old mask helper names.
- Evidence: LSP rename updated both private helpers and their callsites; source search for `g18ClassRootWord`, `emitG18ClassKeyWordMask`, and `emitG18ClassRootWord` returned no matches.
- Validation target: source search.
- Completion criteria: helpers are named `classKeyWordMask` and `emitClassKeyWordMask`.

### [x] 3. Validate compile and commit

- Scope: renamed Java source and this plan.
- Required evidence: `./gradlew :neko-transforms:compileJava` passes.
- Evidence: `./gradlew :neko-transforms:compileJava` passed after removing `G18` from the class-key word mask helper names.
- Validation target: compile.
- Completion criteria: scoped changes are committed.
