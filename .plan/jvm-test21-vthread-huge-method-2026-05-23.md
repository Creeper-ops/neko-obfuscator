# JVM test21 VThread Huge-Method Performance

## Evidence

- Runtime target: `/mnt/d/Code/Reverse/NekoOBF/test21-obf.jar`, generated from `test-jars/test21.jar` with `test-jars/full-jvm-obf.yml` and `native.enabled=false`.
- Reproduction command:
  `java -XX:-UsePerfData -Djava.io.tmpdir=build/native-run-tmp -jar /mnt/d/Code/Reverse/NekoOBF/test21-obf.jar`
- Observed matrix timings before edit:
  - `Seq: 349 ms`
  - `Parallel: 9 ms`
  - `VThreads: 798 ms`
- Scheduler checks did not remove the regression:
  - `-Djdk.virtualThreadScheduler.parallelism=32`: `Seq: 340 ms`, `Parallel: 37 ms`, `VThreads: 1124 ms`
  - `-Djdk.virtualThreadScheduler.parallelism=4`: `Seq: 622 ms`, `Parallel: 43 ms`, `VThreads: 1278 ms`
- Bytecode evidence from `javap -c -p`:
  - `METHOD org/example/Main.lambda$mmulVirtualThreads$15([[DII[[D[[D)Ljava/lang/Void; -> y`
  - Obfuscated `a.a::y` ends at bytecode offset `8077`.
  - Obfuscated parallel row worker `a.a::x` ends at bytecode offset `7674`.
  - Obfuscated sequential matrix method `a.a::fa` ends at bytecode offset `4617`.
- JIT evidence from `-XX:+PrintCompilation`:
  - `a.a::fa` and `a.a::x` compile at tier 3/4.
  - No compilation entry appears for `a.a::y` while the VThreads matrix phase runs.
  - HotSpot default `HugeMethodLimit` is 8000 bytes, so the 8077-byte Callable row worker is rejected for normal JIT compilation and executes interpreted inside virtual-thread tasks.

## Non-degradation constraint

- User requirement added on 2026-05-23: do not set any CFF degradation.
- The implementation must not reduce CFF block construction, protected coverage,
  fake/poison case cardinality, dynamic token masking, hidden-key propagation,
  transition semantics, or enabled transform coverage.
- Allowed change for this task: choose an equivalent bytecode encoding for the
  same token-dispatch case set when the generated method would otherwise cross
  HotSpot's huge-method JIT boundary.

## Subtasks

- [x] Add CFF JIT-budget token-dispatch encoding selector.
  - Scope: generic CFF sizing policy only; do not special-case test21, virtual threads, `Callable`, method names, or benchmark strings.
  - Required evidence: generated test21 obfuscated bytecode shows the virtual-thread row worker below the 8000-byte huge-method boundary while preserving CFF block coverage, hidden-key entry parameters, dynamic token masking, and all dispatch case targets.
  - Validation command: regenerate test21 with the repo Gradle-built CLI, run `javap -c -p`, and run `-XX:+PrintCompilation`.
  - Completion criteria: `a.a::y` or its remapped equivalent has code length below 8000 and appears in JIT compilation output; `a.a::x` and `a.a::fa` remain transformed.
  - Completed evidence:
    - Regenerated artifact: `build/analysis/test21-obf-budget-dispatch.jar`.
    - `javap -c -p`: `a.a::y` ended at bytecode offset `6970`; `a.a::x` at `6979`; `a.a::fa` at `4703`.
    - `-XX:+PrintCompilation`: `a.a::y (6971 bytes)`, `a.a::x (6980 bytes)`, and `a.a::fa (4704 bytes)` all reached tier 3/4 compilation.

- [x] Validate runtime and performance.
  - Scope: freshly regenerated `test21-obf.jar` only, with the same full JVM transform set and no native transform.
  - Required evidence: successful program completion, no verifier errors or VM fatal errors, and VThreads matrix timing no longer shows interpreted huge-method behavior.
  - Validation command: run original `test-jars/test21.jar` once and regenerated obfuscated test21 at least three times with `-XX:-UsePerfData -Djava.io.tmpdir=build/native-run-tmp`.
  - Completion criteria: all runs print `=== All tests completed ===`; VThreads matrix time is less than or equal to obfuscated Seq median and does not regress Parallel median versus the pre-edit obfuscated run.
  - Completed evidence:
    - Original `test-jars/test21.jar`: `Seq: 2 ms`, `Parallel: 0 ms`, `VThreads: 0 ms`.
    - Five regenerated obfuscated runs completed successfully:
      - `Seq: 334 ms`, `Parallel: 7 ms`, `VThreads: 8 ms`
      - `Seq: 360 ms`, `Parallel: 7 ms`, `VThreads: 8 ms`
      - `Seq: 328 ms`, `Parallel: 8 ms`, `VThreads: 9 ms`
      - `Seq: 326 ms`, `Parallel: 7 ms`, `VThreads: 9 ms`
      - `Seq: 329 ms`, `Parallel: 8 ms`, `VThreads: 9 ms`
