# Keytable Main Merge - 2026-05-22

## Scope

Merge the unique JVM keytable protections from `worktree/keytable-updated` into
current `main` while preserving the newer G18 global-root class-byte/Unsafe
binding already present on `main`. The merge must be generic, must not special
case any test jar or sample class, must keep code concise, and must not touch
unrelated native worktree changes.

## Evidence

- `main` already contains the newer G18 class-byte/Unsafe root path from
  `9073f0b`, `153b1c6`, and `8dc1e14`: the G18 helper reads class bytes,
  computes a class-code hash, mixes an Unsafe layout fingerprint, and poisons
  key material instead of using a standalone verifier.
- `keytable-updated` still contains protections missing from `main`: sealed CFF
  class-key-word decoding, primitive array constant protection keyed by CFF flow
  state, and G18 ticket issue/consume/observe/defer modes bound into method
  entry and key-transfer paths.
- A whole-branch merge is not acceptable because `main` has later CFF poison
  fake-exit behavior and unrelated native changes, while `keytable-updated`
  diverges in the same CFF files.

## Todo

- [x] Scope: Port sealed class-key-word support needed by the constant/string/
  invokedynamic/CFF material paths.
  Required evidence: source diff shows class-key words are stored/decoded through
  a shared seal helper rather than direct plaintext array reads.
  Validation: focused CFF and constant-obfuscation tests.
  Completion criteria: generated class-key word material remains readable by all
  existing consumers without reintroducing plaintext direct `IALOAD` material.
  Completion evidence: class words now store the G18-root-masked word with an
  additional `CLASS_KEY_WORD_SEAL`, and direct/helper class-word consumers decode
  that seal before use. Fresh validation passed with
  `./gradlew -PbuildDir=build/validation-keytable-main-merge :neko-transforms:compileJava :neko-test:test --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --rerun-tasks`.

- [x] Scope: Port primitive array constant protection.
  Required evidence: source diff shows primitive array literals are detected and
  rewritten through CFF flow-key decoded length/index/value material.
  Validation: `JvmConstantObfuscationIntegrationTest`.
  Completion criteria: boolean, byte, short, char, int, long, float, and double
  array literals keep behavior while fixture plaintext payload stores disappear.
  Completion evidence: primitive array literal detection and flow-key decoded
  length/index/value emission are installed in the constant pass, and the focused
  integration test now exercises all primitive array kinds while rejecting
  plaintext fixture payload stores. Fresh validation passed with the focused
  command above.

- [x] Scope: Port G18 ticket binding.
  Required evidence: source diff shows G18 ticket issue/consume/observe/defer
  modes are available from the global helper and used by CFF method entry and
  key-transfer paths.
  Validation: focused CFF algebraic audit and strong-entry-seed regression tests.
  Completion criteria: ticket mismatches poison live key material instead of
  directly throwing, and normal transformed entry/key-transfer paths still run.
  Completion evidence: G18 negative-index ticket modes now issue, defer, observe,
  and consume tickets through the global helper carrier; key-transfer material
  helpers issue/defer tickets, and actual keyed entries consume or observe them.
  The generated G18 load-bit path no longer calls `Integer.rotateLeft`. Fresh
  validation passed with
  `./gradlew -PbuildDir=build/validation-keytable-main-merge :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --rerun-tasks`.

- [x] Scope: Reconcile class-byte/Unsafe binding.
  Required evidence: source diff shows `main` keeps a single G18 global helper
  authority for runtime class-byte hash and Unsafe layout fingerprint material,
  while any useful seed/mix behavior from `keytable-updated` is folded into that
  path instead of duplicating scanners.
  Validation: focused CFF audit and generated-artifact structural inspection.
  Completion criteria: class bytes and Unsafe fingerprint affect G18 root/global/
  node/keytable material, and no standalone verifier or manual mismatch throw is
  generated.
  Completion evidence: retained `main`'s single G18 global helper authority
  rather than duplicating `keytable-updated`'s older owner-delta scanner. Source
  inspection shows runtime class bytes are read in `emitG18ClassCodeHash`, Unsafe
  layout material is produced by `emitG18UnsafeBaseLayoutFingerprint`, and both
  feed the G18 root/global/node update path. Fresh focused CFF validation passed
  with the ticket checkpoint command above.

- [ ] Scope: Final focused validation and five-jar smoke.
  Required evidence: fresh focused Gradle runs and fresh full-JVM obfuscated jars
  under `/mnt/d/Code/Reverse/NekoOBF/`.
  Validation: compile, focused JVM tests, and runtime smoke for the five test
  jars.
  Completion criteria: `test21` passes, no new G18/keytable initializer failure
  appears, and existing unrelated `test` sec/perf issues are not treated as this
  merge's success criteria.
