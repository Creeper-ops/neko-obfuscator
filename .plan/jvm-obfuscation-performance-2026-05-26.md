# JVM Obfuscation Performance Plan - 2026-05-26

## Scope

This plan covers JVM-only obfuscated artifact runtime performance. Native
translation, generated C, JNI removal, GC barriers, native runtime performance,
and obfuscation wall-clock/build-time performance are out of scope for this
plan.

The target transform set is `test-jars/full-jvm-obf.yml`:

- `renamer`
- `keyDispatch`
- `methodParameterObfuscation`
- `controlFlowFlattening`
- `validationSinkHardening`
- `invokeDynamic`
- `constantObfuscation`
- `stringObfuscation`
- `native.enabled: false`

All optimization work must preserve JVM ABI compatibility, full transform
coverage for eligible application code, live dynamic key propagation, packed
`Object[]` carriers, hidden method keys, reflection/MethodHandle compatibility,
and full-strength CFF block coverage. No benchmark-, method-, owner-,
descriptor-, fixture-, or crash-site-specific optimization is allowed.

## Fresh Evidence

### Baseline command

Fresh baseline command:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Result: `BUILD SUCCESSFUL in 23s`, 19 tasks executed.

Fresh runtime/topology report:

```text
build/test-jvm-full-obf-perf/jvm-full-obf-performance-baseline.json
```

JDK in report:

```text
OpenJDK 21.0.11, Arch Linux, OpenJDK 64-Bit Server VM
```

### Full-obfuscation runtime evidence

Single-run fresh report:

| Runtime row | Original | Full JVM obf |
| --- | ---: | ---: |
| TEST Calc | 9 ms | 179 ms |
| obfusjack Platform | 26 ms | 73 ms |
| obfusjack Virtual | 15 ms | 119 ms |
| obfusjack Seq matrix | 3 ms | 2500 ms |
| obfusjack Parallel | 0 ms | 8 ms |
| obfusjack VThreads | 0 ms | 9 ms |

Fresh 5-run repeat medians from generated jars:

| Fixture / row | Original median | CFF-only-stack median | Full JVM obf median |
| --- | ---: | ---: | ---: |
| TEST Calc | 11 ms | 94 ms | 198 ms |
| obfusjack Platform | 25 ms | 74 ms | 85 ms |
| obfusjack Virtual | 16 ms | 96 ms | 122 ms |
| obfusjack Seq matrix | 2 ms | 2459 ms | 2490 ms |
| obfusjack Parallel | 0 ms | 7 ms | 8 ms |
| obfusjack VThreads | 0 ms | 8 ms | 10 ms |

The repeated CFF-only-stack jars used `renamer`, `keyDispatch`,
`methodParameterObfuscation`, `controlFlowFlattening`, and
`validationSinkHardening`, with `invokeDynamic`, `constantObfuscation`, and
`stringObfuscation` disabled.

### Ablation evidence

Temporary measurement configs were written under `build/jvm-perf-ablation/`.
They are runtime measurement artifacts only, not product configs.

Valid single-run ablations:

| Variant | TEST Calc | obfusjack Seq | obfusjack Platform | obfusjack Virtual | Notes |
| --- | ---: | ---: | ---: | ---: | --- |
| `cff` | 100 ms | 2437 ms | 72 ms | 93 ms | CFF stack alone reproduces the matrix bottleneck. |
| `full-no-indy` | 126 ms | 2458 ms | 77 ms | 99 ms | Removing indy does not move obfusjack Seq. |
| `full-no-const-string` | 175 ms | 2428 ms | 86 ms | 101 ms | Keeping indy but removing constant/string does not move obfusjack Seq. |

Invalid ablation:

- `base` (`renamer` + `keyDispatch`) runs TEST at `Calc: 10ms`, but
  obfusjack fails with `NoSuchMethodException` /
  `NoSuchMethodError` for a MethodHandle path. This is useful only as a
  dependency finding: the obfusjack JDK21 fixture needs the fuller JVM
  compatibility surface, so this cannot be used as a performance baseline.

### JFR runtime hot-path evidence

Fresh CFF-only-stack JFR command:

```bash
mkdir -p build/jvm-runtime-perf/jfr build/jvm-runtime-perf/java-tmp
java -XX:-UsePerfData \
  -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
  -XX:StartFlightRecording=filename=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.jfr,settings=profile,delay=1s,dumponexit=true \
  -jar build/jvm-perf-ablation/cff-obfusjack.jar \
  > build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.stdout.log \
  2> build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.stderr.log
jfr view hot-methods build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.jfr
jfr print --events ExecutionSample --stack-depth 20 build/jvm-runtime-perf/jfr/cff-obfusjack-matrix.jfr
```

Runtime output completed with `Seq: 2435 ms`, `Parallel: 19 ms`,
`VThreads: 8 ms`, and `=== All tests completed ===`.

Top delayed-JFR samples, with recording delayed to target the matrix stage:

| Method | Samples | Percent |
| --- | ---: | ---: |
| `a.v.__neko_cff_relay$0(long, int, int, int, int, int, long[], int)` | 184 | 42.59% |
| `a.a.y(double[][], int, int, double[][], double[][], long)` | 113 | 26.16% |
| `a.a.x(double[][], int, double[][], double[][], long, int)` | 84 | 19.44% |
| `a.da.ua(long, int, int, int, Object[], int, int, long[])` | 18 | 4.17% |
| `a.v.__neko_cff_relay$4(long, int, int, int, Object[], int, int, long[], int)` | 12 | 2.78% |

Map evidence for `build/jvm-perf-ablation/cff-obfusjack.jar.map`:

- `a/a` is `org/example/Main`.
- `org/example/Main.mmulSeq([[D[[D[[D)V` maps to `a.a.fa`.
- Execution-sample stacks place `__neko_cff_relay$0` and
  `__neko_cff_relay$1` under `a.a.fa(Object[], long)`, then
  `a.a.o(double[][], double[][], double[][], long)`,
  `a.a.ja(Object[], long)`, `a.a.ba(Object[], long)`, and `a.a.main`.

Source evidence for the relay path:

- `CffClassSetup.relocateLargeCffHelperSets` moves CFF helper methods to
  synthetic public host classes.
- `installRelocatedCffHelperRelays` creates `__neko_cff_relay$*` methods by
  descriptor.
- `createRelocatedCffHelperRelay` emits `ILOAD selector` followed by
  `LOOKUPSWITCH`, then invokes the selected helper.
- `rewriteRelocatedCffHelperCalls` inserts the selector at every callsite and
  rewrites the call to the relay.

This proves an exact runtime path for the first CFF repair: a generic
compile-time-known selector dispatch layer is executing in the hot obfusjack
matrix `Seq` run under the obfuscated `mmulSeq` stack.

Fresh full JVM-obf JFR command:

```bash
java -XX:-UsePerfData \
  -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
  -XX:StartFlightRecording=filename=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/jfr/full-obfusjack-matrix.jfr,settings=profile,delay=1s,dumponexit=true \
  -jar build/test-jvm-full-obf-perf/test21-obf.jar \
  > build/jvm-runtime-perf/jfr/full-obfusjack-matrix.stdout.log \
  2> build/jvm-runtime-perf/jfr/full-obfusjack-matrix.stderr.log
jfr view hot-methods build/jvm-runtime-perf/jfr/full-obfusjack-matrix.jfr
jfr print --events ExecutionSample --stack-depth 20 build/jvm-runtime-perf/jfr/full-obfusjack-matrix.jfr
```

Runtime output completed with `Seq: 2512 ms`, `Parallel: 7 ms`,
`VThreads: 9 ms`, and `=== All tests completed ===`.

Top delayed-JFR full-obf samples:

| Method | Samples | Percent |
| --- | ---: | ---: |
| `a.v.__neko_cff_relay$0(long, int, int, int, int, int, long[], int)` | 148 | 42.53% |
| `a.a.x(double[][], int, double[][], double[][], long, int)` | 87 | 25.00% |
| `a.a.y(double[][], int, int, double[][], double[][], long)` | 71 | 20.40% |
| `a.da.sa(int[], int, int, int, int, int, int)` | 12 | 3.45% |
| `a.da.ua(long, int, int, int, Object[], int, int, long[])` | 9 | 2.59% |

Full-obf execution-sample stacks place `__neko_cff_relay$0` and
`__neko_cff_relay$1` under `a.a.fa(Object[], long)`, then the full-obf indy
resolver `a.b.d(...)`, then `a.a.o(...)`, `a.a.ja(...)`,
`a.a.ba(...)`, another full-obf indy resolver frame, and `a.a.main`. This
proves the same relay dispatch layer is present on the actual full-obf target
runtime path for P1.

### Topology evidence

Fresh full-obf `TEST` topology:

- output grew from `29184` bytes to `1123454` bytes.
- `totalEstimatedMethodBytes=1098163`.
- `totalInstructions=579053`.
- `totalInvokeDynamicInstructions=475`.
- `totalCffOutlinedDispatchCalls=622`.
- `totalCffSharedGroupDispatchCalls=9`.
- `totalCffTransitionMaterialCalls=621`.
- `totalCffStepMaterialCalls=520`.
- `totalCffIslandMaterialCalls=493`.
- CFF dry-run sums include `helpers=666`, `materialWords=42286`,
  `readyHelpers=333`, `currentHelpers=333`, `projectedSharedHelpers=9`,
  and `projectedHelperReduction=324`.

Fresh full-obf `obfusjack` topology:

- output grew from `28291` bytes to `929479` bytes.
- `totalEstimatedMethodBytes=896769`.
- `totalInstructions=460741`.
- `totalInvokeDynamicInstructions=525`.
- `totalCffOutlinedDispatchCalls=514`.
- `totalCffSharedGroupDispatchCalls=6`.
- `totalCffTransitionMaterialCalls=1`.
- `totalCffStepMaterialCalls=1`.
- `totalCffIslandMaterialCalls=1`.
- CFF dry-run sums include `helpers=550`, `materialWords=33110`,
  `readyHelpers=275`, `currentHelpers=275`, `projectedSharedHelpers=6`,
  and `projectedHelperReduction=269`.

### Source evidence

- `JvmFullObfuscationPerfTest` records full JVM obf runtime, topology,
  helper descriptor counts, largest methods, and CFF call counts.
  `neko-test/src/test/java/dev/nekoobfuscator/test/JvmFullObfuscationPerfTest.java`
- `CffClassSetup.logIslandDryRunMethodStats` emits the dry-run rows proving
  current helper count, material rows/words, live dispatch token rows, and
  projected shared-helper reduction.
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffClassSetup.java`
- `CffClassSetup` already has shared-helper metadata in `CffClassKeyTable`
  and relocates large helper sets through `__neko_cff_relay$*` relays.
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/cff/CffClassSetup.java`
- `invokeDynamic`, `constantObfuscation`, and `stringObfuscation` bind to CFF
  metadata and fail closed when CFF class key table or instruction state is
  missing. This is why "full minus CFF" is not a valid ablation.
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/invoke/JvmInvokeDynamicObfuscationPass.java`
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/constants/JvmConstantObfuscationPass.java`
  `neko-transforms/src/main/java/dev/nekoobfuscator/transforms/jvm/strings/JvmStringObfuscationPass.java`

## Findings

### Primary runtime bottleneck

The main current runtime bottleneck is the CFF execution surface:
per-method/per-helper dispatch, material-row access, fake/poison row handling,
and relocated helper relay calls. This is proven by the ablation where the
CFF-only-stack median reproduces the full-obf obfusjack matrix slowdown:
`2459ms` vs full `2490ms`, while original is `2ms`.

This is not an `invokeDynamic`, string, or constant-only bottleneck for the
obfusjack matrix path. Removing those transforms leaves the matrix `Seq` row at
roughly the same 2.4s range.

### Secondary runtime bottleneck

For TEST Calc, full JVM obf is slower than the CFF-only stack:
`94ms` CFF-only median vs `198ms` full median. The single-run ablations show
`full-no-indy` at `126ms` and `full-no-const-string` at `175ms`, so invokedynamic
materialization and constant/string materialization both add measurable runtime
work on this fixture. This is secondary because the largest observed regression
is still the CFF-driven obfusjack matrix path.

## Plan

## Runtime Acceptance

Final runtime acceptance for this plan:

- P1 acceptance: full JVM obf obfusjack matrix `Seq` five-run median must be
  `<= 200 ms`.
- P2 acceptance: full JVM obf TEST `Calc` five-run median must be `<= 60 ms`.
- Non-target runtime rows must not regress by more than 10% relative to the
  fresh full-obf medians in this plan:
  - obfusjack Platform `85 ms`.
  - obfusjack Virtual `122 ms`.
  - obfusjack Parallel `8 ms`.
  - obfusjack VThreads `10 ms`.
- Functional validation must stay green for TEST, obfusjack, SnakeGame
  headless behavior, and evaluator.
- Any verifier, bootstrap, reflection, MethodHandle, LambdaMetafactory,
  plaintext marker, fallback, skipped transform, reduced CFF block coverage, or
  static/non-live key propagation regression fails the plan.

### Common runtime validation commands

Before accepting any runtime-performance subtask, regenerate fresh full JVM
obf artifacts with:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Then run exact five-run runtime repeats:

```bash
mkdir -p build/jvm-runtime-perf/repeats build/jvm-runtime-perf/java-tmp
for variant in original full; do
  case "$variant" in
    original)
      testjar=test-jars/test.jar
      obfjar=test-jars/test21.jar
      ;;
    full)
      testjar=build/test-jvm-full-obf-perf/test-obf.jar
      obfjar=build/test-jvm-full-obf-perf/test21-obf.jar
      ;;
  esac
  for i in 1 2 3 4 5; do
    java -XX:-UsePerfData \
      -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
      -jar "$testjar" \
      > "build/jvm-runtime-perf/repeats/${variant}-TEST-${i}.stdout.log" \
      2> "build/jvm-runtime-perf/repeats/${variant}-TEST-${i}.stderr.log"
    java -XX:-UsePerfData \
      -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
      -jar "$obfjar" \
      > "build/jvm-runtime-perf/repeats/${variant}-obfusjack-${i}.stdout.log" \
      2> "build/jvm-runtime-perf/repeats/${variant}-obfusjack-${i}.stderr.log"
  done
done
```

Median report command:

```bash
mkdir -p build/jvm-runtime-perf/repeats
{
  printf 'variant\tfixture\tmetric\tvalues_sorted_ms\tmedian_ms\n'
  metric_value() {
    metric="$1"
    file="$2"
    case "$metric" in
      Calc) rg -o 'Calc:[[:space:]]+[0-9]+ms' "$file" | rg -o '[0-9]+' ;;
      Platform) rg -o 'Platform threads:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      Virtual) rg -o 'Virtual threads:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      Seq) rg -o 'Seq:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      Parallel) rg -o 'Parallel:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
      VThreads) rg -o 'VThreads:[[:space:]]+[0-9]+' "$file" | rg -o '[0-9]+' ;;
    esac
  }
  for variant in original full; do
    for fixture in TEST obfusjack; do
      if [ "$fixture" = TEST ]; then
        metrics='Calc'
      else
        metrics='Platform Virtual Seq Parallel VThreads'
      fi
      for metric in $metrics; do
        values=$(
          for i in 1 2 3 4 5; do
            metric_value "$metric" "build/jvm-runtime-perf/repeats/${variant}-${fixture}-${i}.stdout.log"
          done | sort -n
        )
        count=$(printf '%s\n' "$values" | sed '/^$/d' | wc -l)
        test "$count" -eq 5 || exit 1
        median=$(printf '%s\n' "$values" | sed -n '3p')
        joined=$(printf '%s\n' "$values" | paste -sd, -)
        printf '%s\t%s\t%s\t%s\t%s\n' "$variant" "$fixture" "$metric" "$joined" "$median"
      done
    done
  done
} | tee build/jvm-runtime-perf/repeats/runtime-medians.tsv
```

Optional human-readable timing dump:

```bash
for variant in original full; do
  for fixture in TEST obfusjack; do
    printf '\n%s %s\n' "$variant" "$fixture"
    for i in 1 2 3 4 5; do
      f="build/jvm-runtime-perf/repeats/${variant}-${fixture}-${i}.stdout.log"
      printf '%s ' "$(basename "$f")"
      rg 'Calc:|Platform threads|Virtual threads|Seq:|Parallel:|VThreads:' "$f" |
        sed -E 's/^[[:space:]]+//; s/[[:space:]]+/ /g' |
        tr '\n' ' '
      printf '\n'
    done
  done
done
```

Required runtime log inspection:

```bash
rg -n "Exception|Error|VerifyError|BootstrapMethodError|NoSuchMethod|NoSuchField|WrongMethodType|ClassCastException|ArrayIndexOutOfBoundsException|fallback|skip|static-decrypt|__neko_cff_didx|__neko_indy_bsm_shared|__neko_indy_register" \
  build/jvm-runtime-perf/repeats \
  build/test-jvm-full-obf-perf/*.run.stdout.log \
  build/test-jvm-full-obf-perf/*.run.stderr.log \
  build/test-jvm-full-obf-perf/*obfuscate.stdout.log \
  build/test-jvm-full-obf-perf/*obfuscate.stderr.log
```

Expected allowed runtime text remains TEST `Test 2.8: Sec ERROR` and
SnakeGame headless `java.awt.HeadlessException`. The obfusjack fixture's
expected exception-path output includes `Caught MyException: boom`, and its
obfuscation log may include class-load debug text for
`org/example/Main$MyException`. Any new unexpected hit must be resolved before
acceptance.

### P0 Baseline and plan intake

Scope:

- Keep this plan JVM-only.
- Preserve the fresh test reports and log paths as the evidence baseline.
- Do not implement runtime changes in this subtask.
- Exclude obfuscation wall-clock/build-time optimization from the plan.

Required evidence:

- Fresh `JvmFullObfuscationPerfTest` pass.
- Fresh 5-run original/CFF/full medians for TEST and obfusjack.
- Fresh ablation evidence showing CFF-only-stack reproduces the primary
  regression.
- Fresh JFR runtime hot-method evidence mapping the first CFF repair surface to
  the matrix runtime path.
- Plan-intake review result before implementation.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Completion criteria:

- Plan file exists under `.plan/`.
- Active todo system reflects planning progress.
- No runtime code changes are included in the planning commit.

Status: `[x]` plan-intake review passed after runtime-target revisions.

### P1 Bring obfusjack matrix runtime median to <= 200 ms

Scope:

- Optimize only generic CFF runtime execution overhead proven on the full
  JVM-obfuscated artifact runtime path.
- Preserve full CFF block coverage, block boundaries, fake rows, poison rows,
  material rows, hard-fail rows, dynamic live key propagation, class key tables,
  hidden method keys, packed carriers, and all JVM ABI behavior.

#### P1.1 Eliminate compile-time-constant relocated CFF relay dispatch

Scope:

- Replace `push selector; invokestatic __neko_cff_relay$N(...)` callsites with
  direct calls to the relocated helper target when the selector was introduced
  by `rewriteRelocatedCffHelperCalls` and the relocated helper is public static
  or nestmate-accessible.
- Keep relay generation only where a later source audit proves a callsite
  cannot legally call the target helper directly.
- Do not change helper bodies, CFF block partitioning, CFF material rows, fake
  rows, poison rows, or key formulas.

Required evidence:

- Existing JFR shows `a.v.__neko_cff_relay$0` consumes 42.59% of samples in a
  delayed matrix-stage `Seq: 2435 ms` CFF-only obfusjack runtime and 42.53% of
  samples in a delayed matrix-stage `Seq: 2512 ms` full-obf obfusjack runtime.
- Source evidence shows the selector is compile-time-known at every rewritten
  callsite and the relay uses `LOOKUPSWITCH`.
- Generated bytecode inspection after the change must show hot CFF helper
  callsites no longer invoke `__neko_cff_relay$0` for direct-callable targets.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.ControlFlowFlatteningAlgebraicAuditTest --tests dev.nekoobfuscator.test.CffStrongEntrySeedRegressionTest --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --tests dev.nekoobfuscator.test.JvmInvokeDynamicObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmConstantObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmStringObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmMethodParameterObfuscationIntegrationTest --tests dev.nekoobfuscator.test.JvmRenamerIntegrationTest --tests dev.nekoobfuscator.test.ObfuscationIntegrationTest --rerun-tasks
```

Runtime target:

- Run the common runtime validation commands.
- Run a fresh JFR on full obfusjack if the full-obf `Seq` median remains above
  `200 ms`.

Completion criteria:

- Functional tests pass.
- Full-obf obfusjack `Seq` five-run median improves from `2490 ms`; if it is
  still above `200 ms`, continue to P1.2 rather than claiming P1 complete.
- No non-target row regresses by more than 10% from the fresh full-obf medians.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

Status: `[x]` implemented, validated, and implementation review passed.
Evidence:

- Targeted JVM validation command passed after the source change.
- Corrected five-run median report:
  - full TEST `Calc=195 ms`.
  - full obfusjack `Platform=88 ms`, `Virtual=90 ms`, `Seq=409 ms`,
    `Parallel=8 ms`, `VThreads=8 ms`.
- Actual `test-obf.jar` and `test21-obf.jar` bytecode inspection found no
  `__neko_cff_relay$*` methods or calls.
- Post-P1.1 full-obfusjack JFR completed with `Seq: 462 ms`; hot samples moved
  from `__neko_cff_relay$0` to `a.a.fa` and CFF helper host methods under the
  `mmulSeq` stack, so P1.2 remains required.

#### P1.2 Complete shared per-class CFF dispatch/material execution

Scope:

- For dry-run rows with `readyHelpers == currentHelpers`,
  `staticDispatchTokenRows == 0`, and all missing-row counters equal to zero,
  replace remaining per-helper CFF dispatch/material execution with a generic
  per-class shared execution route.
- The shared route must consume the same live method-entry key material,
  dispatch token rows, material words, fake rows, poison rows, and hard-fail
  rows as the current route.
- The shared route may move equivalent protected logic but must not reduce
  block coverage, merge blocks, remove fake/poison semantics, expose keys, or
  replace live key flow with static seeds.

Implementation order:

1. P1.2.1 collapses the remaining compile-time-known shared-group dispatch
   layer that P1.1 exposed. This is first because the post-P1.1 full-obfusjack
   artifact still executes `SHARED_GROUP_DESC` on the obfuscated `mmulSeq`
   (`a.a.fa`) runtime path before entering per-island material helpers.
2. P1.2.2 completes the shared per-class island execution route if P1.2.1 does
   not bring full-obf obfusjack `Seq` to `<= 200 ms`.

Required evidence:

- Post-P1.1 JFR or bytecode topology must identify the remaining dominant CFF
  runtime method(s), helper descriptor(s), and source emission path(s).
- A written invariant mapping must be added to this plan before code edit,
  comparing old and new dispatch/material inputs and outputs.
- Generated bytecode topology must show reduced per-helper dispatch/material
  call surface without reducing `realRows`, `fakeRows`, `poisonRows`, or
  `liveDispatchTokenRows` in CFF dry-run logs.

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- Run JFR on full obfusjack and confirm relay/material helpers are no longer
  the dominant samples, or record the exact remaining runtime path for the next
  subtask.

Completion criteria:

- Full-obf obfusjack `Seq` five-run median is `<= 200 ms`.
- Full-obf TEST `Calc` does not regress from the post-P1.1 median and remains
  no worse than the fresh full-obf median `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

#### P1.2.1 Collapse compile-time-known shared-group dispatch

Scope:

- Replace group-hub `push groupIndex; goto sharedGroupDispatch` with a direct
  call to that group's prepared dispatch helper.
- Remove generation/use of the `SHARED_GROUP_DESC` helper where the source
  group is compile-time-known at the hub.
- Preserve the group dispatch helper, domain router, island dispatch helpers,
  result router, fake rows, poison rows, material rows, and all live key locals.

Required evidence:

- Post-P1.1 full-obfusjack JFR shows the relay layer is gone and the
  `Seq: 462 ms` run records `81` samples with `a.a.fa(Object[], long)` on the
  stack. Of those, `41` include
  `a.ba.us(long, int, int, int, int, int, int, long[])`, and `22` include
  `a.da.ua(long, int, int, int, Object[], int, int, long[])`.
- Fresh map evidence identifies `a.a.fa([Ljava/lang/Object;J)V` as
  `org/example/Main.mmulSeq([[D[[D[[D)V`.
- Fresh generated bytecode inspection of `build/test-jvm-full-obf-perf/test21-obf.jar`
  shows `a.a.fa([Ljava/lang/Object;J)V` calls
  `a.ba.us:(JIIIIII[J)J` at bytecode offset `613`. This descriptor is
  `CffTransitionOutliner.SHARED_GROUP_DESC`, proving the shared-group dispatch
  route is on the hot `mmulSeq` runtime path.
- Source evidence shows `CffDispatchEmitter` writes a constant `groupIndex` at
  every outlined group hub before jumping to one shared dispatch label, and
  `CffTransitionOutliner.createSharedGroupDispatchHelper` immediately switches
  on that constant to call the already prepared group helper.

Invariant mapping:

| Old path | New path | Preserved invariant |
| --- | --- | --- |
| group hub stores compile-time `groupIndex` to `sharedGroupLocal` | group hub does not store `groupIndex` | group identity is still the same compile-time `IslandGroup` selected by the hub |
| group hub jumps to `sharedGroupDispatch` | group hub directly invokes `prepareGroupDispatchHelper(group, ...)` output through `emitGroupDispatchCall` | call inputs remain `key`, `guard`, `path`, `block`, `pc`, `domain`, and `out` |
| shared helper switches on `groupIndex` | no shared switch | no application block, island, fake row, poison row, or material row is removed |
| shared helper invokes the selected group helper | group hub invokes the same selected group helper | group helper body, domain router, island helpers, result router, and `out` encoding stay unchanged |
| shared helper returns the group helper's `long` key result | direct group call stores the same `long` key result | caller reloads guard/path/block/pc/domain from the same `out` array layout |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- Generated bytecode topology must show `totalCffSharedGroupDispatchCalls=0`
  for TEST and obfusjack full-obf artifacts.
- If full-obf obfusjack `Seq` remains above `200 ms`, run fresh full-obfusjack
  JFR and record the remaining runtime path before P1.2.2 code edit.

Completion criteria:

- Full-obf obfusjack `Seq` five-run median improves from the P1.1 `409 ms`
  median, or P1.2.1 is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the P1.1 `195 ms` median and the
  original plan baseline `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

Status: `[x]` implemented, validated, and implementation review passed.
Evidence:

- Targeted JVM validation command passed after the source change.
- Generated topology now reports `totalCffSharedGroupDispatchCalls=0` for TEST
  and obfusjack full-obf artifacts.
- Fresh bytecode inspection of `test-obf.jar` and `test21-obf.jar` found no
  `__neko_cff_relay$*` methods/calls and no `(JIIIIII[J)J` shared-group helper
  calls.
- Corrected five-run median report:
  - full TEST `Calc=186 ms`.
  - full obfusjack `Platform=77 ms`, `Virtual=83 ms`, `Seq=327 ms`,
    `Parallel=7 ms`, `VThreads=8 ms`.
- Post-P1.2.1 full-obfusjack JFR completed with `Seq: 331 ms`. Hot samples no
  longer include `SHARED_GROUP_DESC`; the remaining `a.a.fa` stack contains
  per-island helpers such as `a.ba.*:(JIIIII[J)J` and material helper
  `a.da.ua:(JIII[Ljava/lang/Object;II[J)J`, so P1.2.2 remains required.

#### P1.2.2 Complete shared island execution route

Scope:

- If P1.2.1 does not reach P1 acceptance, continue reducing the remaining
  outlined island dispatch execution in dependency order.
- P1.2.2a first removes the proven runtime wrapper layer where a group helper
  switches on `domain` only to invoke an already prepared island helper. This
  keeps the existing per-island helper semantics unchanged and removes one hot
  helper frame before attempting a wider semantic interpreter switch.
- If P1.2.2a does not bring full-obf obfusjack `Seq` to `<= 200 ms`, P1.2.2b
  attempts the semantic shared island interpreter for rows satisfying the
  dry-run readiness constraints.
- Preserve every row and token category listed in the parent P1.2 scope.

Required evidence before editing:

- Post-P1.2.1 JFR hot-method report and generated topology identifying the
  remaining dominant island/material helper path.
- A new invariant mapping for the shared island helper inputs, table rows,
  return `out` words, fake bounce behavior, and poison behavior.

Post-P1.2.1 evidence:

- Corrected five-run median after P1.2.1 still reports full-obf obfusjack
  `Seq=327 ms`, above the P1 `<= 200 ms` target.
- Fresh full-obfusjack JFR completed with `Seq: 331 ms`.
- Filtering execution samples containing `a.a.fa(Object[], long)` reports
  `fa_samples=66`, `ba_any_under_fa=39`, `da_any_under_fa=18`, and
  `indy_under_fa=0`.
- Sample stacks under the obfuscated `mmulSeq` path include
  `a.ba.bs(long, int, int, int, int, int, long[])` above
  `a.ba.cs(long, int, int, int, int, int, long[])` above
  `a.a.fa(Object[], long)`. This proves the remaining hot CFF path still pays
  a group-helper frame that immediately enters an island helper.
- Generated topology for obfusjack after P1.2.1 reports
  `totalCffOutlinedDispatchCalls=514`, `totalCffSharedGroupDispatchCalls=0`,
  `totalCffTransitionMaterialCalls=391`, `totalCffStepMaterialCalls=463`,
  and `totalCffIslandMaterialCalls=347`.
- The same topology reports obfusjack readiness sums
  `readyHelpers=275`, `currentHelpers=275`, `projectedSharedHelpers=6`,
  `projectedHelperReduction=269`, `liveDispatchTokenRows=347`,
  `staticDispatchTokenRows=0`, `realRows=347`, `fakeRows=188`,
  `poisonRows=550`, `missingFakeStepRows=0`, `missingPoisonStepRows=0`,
  `missingBounceRows=0`, `missingFakeSourceKeyProofRows=0`, and
  `semanticSwitchBlockedFakeRows=0`.
- The hot obfuscated `mmulSeq` method itself reports
  `method=a/a.fa([Ljava/lang/Object;J)V`, `currentHelpers=19`,
  `projectedSharedHelpers=1`, `projectedHelperReduction=18`,
  `liveDispatchTokenRows=20`, `staticDispatchTokenRows=0`, `realRows=20`,
  `fakeRows=41`, `poisonRows=19`, and no missing fake/poison/bounce rows.

P1.2.2a invariant mapping:

| Old path | New path | Preserved invariant |
| --- | --- | --- |
| group hub calls a generated group helper with `key`, `guard`, `path`, `block`, `pc`, `domain`, and `out` | group hub emits the same `domain` switch at the hub callsite | dispatch inputs and live key locals are unchanged |
| group helper switches on `domainToken(group.salt(), island)` and optional direct-island domain tokens | inline hub switch uses the same domain token set | selected island identity is unchanged |
| selected group-helper case invokes that island's existing helper | selected inline case invokes the same prepared island helper | real dispatch rows, fake rows, poison rows, material rows, and helper body semantics are unchanged |
| group helper poison case writes the group poison result token to `out` and returns the live key | inline poison case writes the same `out` words and routes to the same result router | poison routing, dense/sparse result masking, and `out[0..2]` layout stay unchanged |
| caller reloads `guard`, `path`, `block`, `pc`, `domain`, and result token from `out` after the group helper returns | each inline case reloads from the same `out` layout before jumping to the same router | result router inputs and downstream block labels are unchanged |

P1.2.2a implementation dependency:

- The existing island helpers must still be created through
  `createIslandDispatchHelper`, and each generated helper must still publish
  its flow-key metadata through the existing helper flow-key path.
- The inline group-domain switch may move only the wrapper dispatch layer. It
  must not rewrite, skip, merge, or reinterpret island helper bodies, fake
  cases, poison cases, material-row decode, or result-router token assignment.
- The direct-island entry domain-token aliases recorded by
  `needsGroupedIslandEntry` must be preserved in the same domain switch.

P1.2.2a validation target:

- Same targeted JVM command as P1.1.
- Fresh generated topology must record medians and dry-run row counts after the
  change.
- Bytecode/JFR inspection must prove the changed path no longer executes a
  `group helper -> island helper` wrapper chain on the full-obfusjack
  `mmulSeq` stack. If `Seq` remains above `200 ms`, record the exact remaining
  CFF runtime path before P1.2.2b.

P1.2.2a completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Generated topology/bytecode inspection shows the group-domain wrapper call
  path is removed or no longer on the hot full-obfusjack `mmulSeq` runtime
  stack.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.1 artifact.
- Full-obf obfusjack `Seq` improves from the P1.2.1 `327 ms` median or the
  source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.2.2a status: `[x]` measured, rejected, and reverted before P1.2.2b.
No implementation commit was made for this branch.

P1.2.2a rejection evidence:

- Inline group-domain switch without a method-size gate failed the fresh
  targeted JVM validation during full-obfusjack generation with
  `MethodTooLargeException: Method too large: a/a.main ([Ljava/lang/String;)V`.
  The fresh finalizer log estimated `a/a.main` at `79101` bytes and
  `<clinit>` at `61104` bytes, proving the generic inline wrapper removal can
  exceed the JVM method-size limit on current full-obfusjack topology.
- A generic size-gated version preserved current group-helper routing for
  materialized direct-island paths and passed the same targeted JVM validation.
  Its generated topology still preserved coverage: obfusjack reported
  `totalCffSharedGroupDispatchCalls=0`, `totalCffTransitionMaterialCalls=391`,
  `totalCffStepMaterialCalls=451`, `totalCffIslandMaterialCalls=347`,
  `readyHelpers=275`, `currentHelpers=275`, `projectedHelperReduction=269`,
  `liveDispatchTokenRows=347`, `staticDispatchTokenRows=0`, `realRows=347`,
  `fakeRows=176`, `poisonRows=550`, and no missing-row blockers.
- The size-gated version failed P1.2.2a runtime acceptance. Fresh five-run
  medians were full TEST `Calc=224 ms`, full obfusjack `Platform=87 ms`,
  `Virtual=85 ms`, `Seq=326 ms`, `Parallel=8 ms`, and `VThreads=8 ms`.
  This violates the P1.2.2a criteria because TEST Calc regressed beyond the
  `198 ms` baseline and obfusjack `Seq` did not improve from the P1.2.1
  `327 ms` median.
- The source change was fully reverted to the P1.2.1 committed shape before
  continuing. P1.2.2b must therefore attack the remaining island execution
  cost through the semantic shared-interpreter route, not through the rejected
  group-wrapper inline route.

P1.2.2b execution split:

1. P1.2.2b.1 first removes the remaining hot real-hit material decode helper
   frame by caching the caller class-owned island material arrays inside each
   existing island helper and inlining the exact live material-word decode
   formula for the real-result word. This is a narrower semantic-preserving
   prerequisite because the post-P1.2.1 JFR records `a.da.ua` island material
   helper samples on the obfuscated `mmulSeq` stack, while the active runtime
   path currently decodes only one live real-result word from the compressed
   island material row.
2. P1.2.2b.2 attempts the full semantic shared island interpreter only if
   P1.2.2b.1 does not reach P1. The full interpreter must first consume the
   remaining real/fake/poison/router material without exposing static material
   seeds or weakening fake/poison coverage.

P1.2.2b.1 scope:

- Change only the generated island helper body emitted by
  `createIslandDispatchHelper`.
- Preserve the existing group helper, island helper count, dispatch token
  switch, fake source router, fake bounce, poison path, compressed material
  storage, runtime-source bucket selection, result router, hidden key ABI, and
  `out[0..2]` layout.
- Replace `emitCompressedIslandMaterialWordDecode -> __neko_cff_imat$` only
  for the existing live-decoded real-result word with an inline copy of the
  same live decode formula, using the same live `key`, `guard`, `path`,
  `block`, source-adjusted cursor, class-owned `Object[]` carrier, island
  material `int[]`, and class key words.
- Do not decode static-mask material fields, add static seed constants, rewrite
  fake/poison rows, change CFF block construction, or remove material rows.

P1.2.2b.1 required evidence:

- Post-P1.2.1 JFR already places `a.da.ua` under `a.a.fa(Object[], long)`,
  proving the material decode helper is on the full-obfusjack `mmulSeq`
  runtime path.
- Source audit shows the current island helper calls
  `emitCompressedIslandMaterialWordDecode` only for `realResultWordIndexes`
  before `finishOutlinedDispatchReturnFromLocal`; fake and poison paths use
  separate `emitMaterializedStepKeys` and bounce/poison emitters.
- Source audit shows `installCompressedIslandMaterialHelper` decodes a word
  from the class-owned island material entry with live `key`, `guard`, `path`,
  `block`, source-adjusted cursor, word index, and `CLASS_KEY_WORDS_SLOT`.

P1.2.2b.1 validation target:

- Same targeted JVM command as P1.1.
- Generated topology must show CFF row counts and helper coverage are unchanged
  except for instruction counts expected from inlining and the expected
  decrease in island-material helper call sites on the real-result path.
- Bytecode/JFR inspection must show the real-result path no longer invokes the
  island material helper from the hot full-obfusjack `mmulSeq` stack. If `Seq`
  remains above `200 ms`, record the remaining hot CFF path before P1.2.2b.2.

P1.2.2b.1 completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the P1.2.1 `327 ms` median or the
  source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.1 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.2.2b.1 status: `[x]` measured, rejected, and reverted before P1.2.2b.2.
No implementation commit was made for this branch.

P1.2.2b.1 rejection evidence:

- The inline real-result material decode implementation passed the fresh
  targeted JVM validation command for the CFF algebraic audit, strong-entry
  seed regression, full JVM obfuscation performance test, invokedynamic,
  constant, string, method-parameter, renamer, and obfuscation integration
  tests. Gradle reported `BUILD SUCCESSFUL` with `19 executed` tasks.
- The same implementation failed runtime acceptance. Fresh five-run medians
  were original TEST `Calc=10 ms`, original obfusjack `Seq=2 ms`, full TEST
  `Calc=210 ms`, full obfusjack `Platform=81 ms`, `Virtual=84 ms`,
  `Seq=327 ms`, `Parallel=7 ms`, and `VThreads=8 ms`.
- This violates the P1.2.2b.1 criteria because full TEST `Calc` regressed
  beyond the original plan baseline `198 ms`, and full obfusjack `Seq` did not
  improve from the P1.2.1 `327 ms` median.
- The source change was fully reverted to the P1.2.2b.1 plan-checkpoint shape.
  The next accepted implementation attempt must therefore target a different
  remaining CFF runtime path; inlining only the island-material helper real
  result decode is not sufficient.

P1.2.2b.2 revised scope:

- Before attempting the full shared interpreter, remove the repeated
  `Thread.currentThread()` / `System.identityHashCode(Thread)` work now proven
  at the top of the hot island helper path.
- Expand the existing generated transition scratch `long[] out` from 3 to 4
  slots and store a cached current-thread identity word in `out[3]` when the
  CFF method initializes the scratch array.
- Change island helper runtime-source emission to consume `out[3]` instead of
  calling `Thread.currentThread()` and `System.identityHashCode()` at every
  island helper entry. The runtime-source value must still be mixed with live
  method key, guard, path, block, pc, and domain state at each helper entry.
- Preserve the helper ABI `(JIIIII[J)J`, CFF block construction, group/island
  helper topology, fake/poison behavior, material rows, hidden method keys, and
  `out[0..2]` transition result layout.
- Do not cache guard/path/block/pc/domain, decoded material rows, dispatch
  tokens, static seeds, or descriptor-derived values. Do not use ThreadLocal
  rescue state, fallback, skip behavior, or original-bytecode paths.

P1.2.2b.2 required evidence:

- Fresh post-revert JFR on full-obfusjack completed with `Seq: 335 ms`.
  Filtering execution samples to the obfuscated `mmulSeq` method
  `a.a.fa(Object[], long)` reports `fa=37`, `ba_under_fa=23`, and
  `da_under_fa=8`, proving the remaining Seq hot path is still CFF helper
  execution.
- Fresh bytecode inspection of a sampled helper, for example
  `a.ba.bs:(JIIIII[J)J`, shows the helper begins by executing
  `Thread.currentThread()` and `System.identityHashCode(Object)` before mixing
  live key/control/pc/domain state to select the island material runtime-source
  bucket.
- Source inspection shows `emitInitTransitionOut` allocates only the generated
  transition scratch array and existing readers/writers use `out[0]`,
  `out[1]`, and `out[2]` for guard/path, block/domain, and result token. Adding
  `out[3]` is internal to generated CFF helper ABI and does not alter external
  JVM ABI.

P1.2.2b.2 validation target:

- Same targeted JVM command as P1.1.
- Generated bytecode inspection must show hot island helpers no longer contain
  `Thread.currentThread()` / `System.identityHashCode()` at helper entry, while
  the method entry scratch initialization still computes and stores current
  thread identity into `out[3]`. Inspection must also show the helper still
  mixes `out[3]` with live method key, guard, path, block, pc, and domain state.
- Generated topology must preserve CFF helper coverage and dry-run row counts.

P1.2.2b.2 completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the P1.2.1 `327 ms` median or the
  source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.1 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.2.2b.2 status: `[x]` implemented, validated, and implementation review
passed. This is accepted only as a generic incremental P1 optimization. It
does not complete P1 because full-obf obfusjack `Seq` remains above the
`<= 200 ms` target.

P1.2.2b.2 evidence:

- The fresh targeted JVM validation command for the CFF algebraic audit,
  strong-entry seed regression, full JVM obfuscation performance test,
  invokedynamic, constant, string, method-parameter, renamer, and obfuscation
  integration tests passed with `BUILD SUCCESSFUL` and `19 executed` tasks.
- Fresh five-run medians after regenerating the full JVM-obf artifacts were
  original TEST `Calc=10 ms`, original obfusjack `Seq=2 ms`, full TEST
  `Calc=180 ms`, full obfusjack `Platform=89 ms`, `Virtual=84 ms`,
  `Seq=312 ms`, `Parallel=7 ms`, and `VThreads=8 ms`.
- This improves full-obf obfusjack `Seq` from the P1.2.1 `327 ms` median and
  keeps full TEST `Calc` under the original plan baseline `198 ms`; P1 remains
  open because `312 ms` is still above the P1 `<= 200 ms` target.
- Fresh generated topology for obfusjack preserved the P1.2.1 CFF surface:
  `totalCffOutlinedDispatchCalls=514`, `totalCffSharedGroupDispatchCalls=0`,
  `totalCffTransitionMaterialCalls=391`, `totalCffStepMaterialCalls=451`,
  `totalCffIslandMaterialCalls=347`, `readyHelpers=275`,
  `currentHelpers=275`, `projectedHelperReduction=269`,
  `liveDispatchTokenRows=347`, `staticDispatchTokenRows=0`,
  `realRows=347`, `fakeRows=176`, `poisonRows=550`, and all missing-row
  counters remain `0`.
- Fresh generated bytecode inspection of obfuscated `mmulSeq`
  `a/a.fa([Ljava/lang/Object;J)V` shows the method initializes `long[4]`,
  stores `Thread.currentThread()` / `System.identityHashCode(Object)` into
  `out[3]`, and then passes that same scratch array to CFF island helpers.
- Fresh generated bytecode inspection of sampled hot island helper
  `a/ba.bs:(JIIIII[J)J` shows no `Thread.currentThread()` or
  `System.identityHashCode(Object)` call at helper entry. The helper loads
  `out[3]` with `LALOAD`, converts it with `L2I`, and still mixes it with live
  `key`, `guard`, `path`, `block`, `pc`, and `domain` locals.
- The required runtime/log scan found only expected text: SnakeGame headless
  `HeadlessException`, obfusjack `Caught MyException: boom`, and class-load
  debug for `org/example/Main$MyException`.
- Because full-obf obfusjack `Seq` remains above `200 ms`, a fresh post-change
  full-obfusjack JFR was captured at
  `build/jvm-runtime-perf/jfr/post-p1-2-2b-2-full-obfusjack-matrix.jfr`. The
  run completed with `Seq: 334 ms`, `Parallel: 9 ms`, `VThreads: 9 ms`, and
  `=== All tests completed ===`.
- The post-change JFR still records the remaining `mmulSeq` stack through
  `a.a.fa(Object[], long)`. Filtered execution samples report `fa=45`,
  `ba_under_fa=19`, `da_ua_under_fa=17`, `da_za_under_fa=18`, and
  `indy_under_fa=45`, proving the next P1 work must address the remaining
  generic CFF material/helper execution and full-obf invokedynamic entry cost
  on the same runtime path rather than the removed thread-identity acquisition.
- Subagent implementation review passed with no blockers. The review confirmed
  the diff is generic, preserves helper ABI and `out[0..2]`, keeps `out[3]`
  internal, preserves live `key/guard/path/block/pc/domain` mixing, introduces
  no fallback/skip/static-seed behavior, and supports retaining the subtask
  without claiming P1 completion.

P1.2.2b shared-interpreter invariant mapping, if P1.2.2a is insufficient:

| Current per-island helper surface | Shared interpreter surface | Preserved invariant |
| --- | --- | --- |
| helper ABI is `(JIIIII[J)J` with `key`, `guard`, `path`, `block`, `pc`, `domain`, and `out` | interpreter consumes the same live locals plus the protected island material cursor | method-entry key and CFF state remain live inputs |
| real cases use `emitTokenDispatch` with live mask and static generated case labels | real rows consume live-decoded dispatch token, raw pc token, dispatch-mask words, result token, result mask, and key-state words from class-owned island material | real block identity, dispatch token uniqueness, and result-router token are unchanged |
| real case stores result token through `finishOutlinedDispatchReturnFromLocal` | interpreter writes the same result token or masked result word into `out[2]` | dense and sparse result routers observe the same token space |
| miss path runs `emitDynamicFakeSourceRouter` and then fake key-step/bounce logic | fake rows must be selected from the same live source router proof and consume the stored fake step, bounce token, bounce domain token, result mask, bounce key state, and method/domain seed material | fake rows are not skipped and do not require static fake-token dispatch |
| poison path materializes poison step keys and writes poison result | poison rows consume the stored poison step material and write the same poison result behavior | hard-fail/poison rows remain present and fail closed |
| helper returns the updated `long` key and encodes `out[0]`, `out[1]`, `out[2]` | interpreter returns the updated `long` key and encodes the same `out` words | caller/router ABI is unchanged |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- Run fresh full-obfusjack JFR if `Seq` remains above `200 ms`.

Completion criteria:

- Full-obf obfusjack `Seq` five-run median is `<= 200 ms`.
- Full-obf TEST `Calc` remains no worse than the original plan baseline
  `198 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

#### P1.3 candidate rejected: retain multiple live-flow invokedynamic targets

Status: `[x]` measured, plan-intake rejected, and not implemented.

Rejected scope:

- The candidate was to change only
  `JvmInvokeDynamicObfuscationPass.emitInstallGuardedTarget` so a resolved
  invokedynamic live-flow target would install
  `guardWithTest(flow, resolvedTarget, previousCallsiteTarget)` instead of
  `guardWithTest(flow, resolvedTarget, initialResolverMissHandle)`.

Rejection evidence:

- Subagent plan-intake review failed before implementation because the evidence
  did not prove that the same hot callsite repeatedly observes a finite set of
  multiple live flows. The recorded evidence only proved that the resolver
  frame is on the post-P1.2.2b.2 `mmulSeq` stack and that the current source
  uses the initial resolver miss handle as the guard false target.
- A fresh `IndyCacheProbe` against
  `build/test-jvm-full-obf-perf/test21-obf.jar` completed the full obfusjack
  runtime with `Seq: 311 ms`, `Parallel: 7 ms`, `VThreads: 8 ms`, and
  `=== All tests completed ===`. It reported `INDY_CACHE size=911`,
  `liveLongKeys=379`, `resolverMissKeys=379`, and live value counts including
  `(long)AtomicLong=5` and `(Object,long,long)long=7`. This proves live cache
  population exists, but it does not prove repeated multi-flow reuse at one hot
  callsite; the descriptor counts can be explained by multiple callsites with
  those descriptors.
- Fresh bytecode inspection of obfuscated `mmulSeq` shows hot invokedynamic
  sites such as `#340` with descriptor `(J)AtomicLong` and `#341` with
  descriptor `(Object,JJ)J`, but the inspected cache data still does not
  distinguish per-callsite repeated flow reuse from multiple distinct
  callsites.
- A fresh same-JVM repeat probe invoked obfuscated `a.a.main(String[])` twice
  through reflection. Run 1 reported `Seq: 309 ms`, `Parallel: 7 ms`,
  `VThreads: 9 ms`, and elapsed `1856 ms`. Run 2 reported `Seq: 329 ms`,
  `Parallel: 7 ms`, `VThreads: 7 ms`, and elapsed `1018 ms`. The lower whole
  run elapsed is attributable to warm class/JIT/setup state, while the target
  `Seq` row did not improve.

Conclusion:

- The guard-chain candidate remains a plausible future optimization only if a
  later probe proves repeated multi-flow reuse at the same hot callsite.
  Current evidence does not justify changing the invokedynamic resolver.
- No runtime source changes were made for this candidate. P1 continues through
  the remaining CFF material/helper execution surface proven by the
  post-P1.2.2b.2 JFR (`ba_under_fa=19`, `da_ua_under_fa=17`,
  `da_za_under_fa=18`).

#### P1.4 Add a no-domain direct-island transition material helper

Scope:

- Optimize only the generic CFF transition-material helper path for
  `EdgeKind.DIRECT_ISLAND` transitions.
- Add a direct-island transition-material helper variant that decodes and
  publishes only the values the direct-island caller consumes: guard, path,
  block, pc, and method key. It must not decode or write the target domain
  word.
- Route only direct-island materialized transition callsites to the new helper.
  All non-direct transitions keep the existing transition-material helper and
  descriptor.
- Preserve transition material row registration, row encryption/masking,
  method-key update, CFF block construction, island helper topology, hidden
  method keys, packed carriers, fake/poison coverage, and `out[0]` /
  `out[1]` layout.
- Do not remove domain material from the stored row, change domain-token
  semantics, special-case a method/class/fixture, expose row words, or add any
  fallback/skip/original-bytecode path.

Dependency/order reason:

- P1.3 was rejected before implementation because invokedynamic guard-chain
  reuse was not proven at the hot callsite. The latest amplified JFR instead
  proves that CFF material helpers still dominate the target `mmulSeq` stack.
- This subtask is smaller than the full shared island interpreter and removes
  a source-proven unused decode from the currently hot transition helper
  without changing island dispatch semantics.

Required evidence:

- A fresh same-JVM 8-run full-obfusjack JFR completed all runs. The target
  `Seq` rows were `307`, `313`, `315`, `323`, `309`, `310`, `323`, and
  `308 ms`, proving P1 remains open in the current artifact.
- Filtering the amplified execution samples to stacks containing obfuscated
  `mmulSeq`, `a.a.fa(Object[], long)`, reports `fa_total=484`,
  `da_ua_under_fa=165`, `da_za_under_fa=207`, and `ba_under_fa=227`. The top
  frames under `fa` include `a.da.ua(...)` with `165` samples and
  `a.da.za(...)` with `207` samples.
- Fresh bytecode inspection of `a.a.fa(Object[], long)` reports `22`
  transition-material helper calls to `a.da.ua:(JIII[Ljava/lang/Object;II[J)J`.
  Only `2` of those calls reload `out[2]` after the helper; `20` do not reload
  the domain word.
- Source inspection shows `CffTransitionOutliner.emitCall` already calls
  `emitTransitionOutLoads(..., edgeKind != EdgeKind.DIRECT_ISLAND)`, so a
  direct-island caller intentionally does not consume the helper's decoded
  domain value.
- Source inspection shows `installTransitionMaterialHelper` always decodes
  `TRANSITION_MATERIAL_DOMAIN_WORD` and calls
  `emitTransitionOutStores(..., true)`, so the current direct-island path pays
  for a decoded domain value that the caller does not read.

Invariant mapping:

| Current direct-island transition path | New direct-island transition path | Preserved invariant |
| --- | --- | --- |
| callsite passes live key, guard, path, block, object material, row, domain, and out | callsite passes live key, guard, path, block, object material, row, and out | live key/control state and encrypted row identity remain the inputs |
| helper decodes guard, path, block, pc, method key high/low, and domain | helper decodes guard, path, block, pc, and method key high/low only | every value consumed by the direct-island caller is unchanged |
| helper writes `out[0]`, `out[1]`, and `out[2]` high domain | helper writes `out[0]` and `out[1]` only | caller reads the same guard/path/block/pc words and still does not read domain |
| non-direct transition helper handles domain-bearing paths | unchanged | hub/router/handler domain transfer semantics stay on the existing path |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- If full-obf obfusjack `Seq` remains above `200 ms`, run a fresh amplified
  full-obfusjack JFR and record whether `a.da.ua(...)` samples under
  `a.a.fa(Object[], long)` decrease.

Completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the P1.2.2b.2 `312 ms` median, or
  the source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the current P1.2.2b.2
  before-edit median `180 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- Generated bytecode inspection shows direct-island transition material
  callsites use the no-domain helper and do not reload `out[2]`, while
  non-direct transition material callsites still use the original helper.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.2b.2 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.4 status: `[x]` measured, rejected, and reverted. No implementation commit
was made for this branch.

P1.4 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  obfuscated `mmulSeq`, `a.a.fa(Object[], long)`, kept `2` calls to the
  original domain-bearing transition-material helper
  `(JIII[Ljava/lang/Object;II[J)J` and used the no-domain helper
  `(JIII[Ljava/lang/Object;[JI)J` for the other `20` direct-island calls.
  The no-domain helper contained only two `LASTORE` writes and did not write
  `out[2]`.
- The first fresh five-run runtime gate rejected the implementation. Full
  TEST `Calc` sorted values were `202,203,203,203,210` with median `203 ms`.
  Full obfusjack `Seq` sorted values were `290,295,297,305,314` with median
  `297 ms`.
- Because TEST timing is noisy, a fresh user-requested ten-run gate was run on
  the same artifact. Full TEST `Calc` sorted values were
  `201,201,202,206,206,207,207,210,215,216`, with lower/upper medians
  `206/207 ms`. Full obfusjack `Seq` sorted values were
  `290,291,295,297,299,301,303,304,304,305`, with lower/upper medians
  `299/301 ms`.
- The optimization improved full-obf obfusjack `Seq` from the P1.2.2b.2
  `312 ms` median, but it consistently regressed full TEST `Calc` beyond the
  current P1.2.2b.2 before-edit median `180 ms`. This violates P1.4 completion
  criteria, so the source change was fully reverted before continuing.
- The next P1 attempt must not reuse the no-domain direct-island transition
  helper unless it first explains and removes the Calc regression generically.

#### P1.5 Mark direct-island transition rows on the existing helper ABI

Scope:

- Optimize the same generic CFF direct-island transition-material path proven
  by P1.4, but keep the existing transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J` and helper count.
- For direct-island materialized transition callsites only, set a private
  high-bit flag on the row argument before invoking the existing helper.
- In the existing transition-material helper, read and clear that row high-bit
  flag before row-index scaling. When the flag is set, decode guard, path,
  block, pc, and method key exactly as before, but skip
  `TRANSITION_MATERIAL_DOMAIN_WORD` decode and write only `out[0]` / `out[1]`.
  When the flag is clear, execute the current domain-bearing path unchanged.
- Preserve transition material row registration, row encryption/masking,
  method-key update, CFF block construction, island helper topology, hidden
  method keys, packed carriers, fake/poison coverage, and non-direct domain
  semantics.
- Do not add a new helper method or descriptor, remove domain material from the
  stored row, change domain-token semantics, special-case a method/class/
  fixture, expose row words, or add fallback/skip/original-bytecode behavior.

Dependency/order reason:

- P1.4 proved the direct-island domain decode is generated and unconsumed on
  the hot `mmulSeq` path, and that removing it can reduce full-obfusjack
  `Seq`, but P1.4 also introduced a second transition-material helper and
  regressed TEST `Calc`.
- P1.5 keeps the original helper ABI and helper count while removing only the
  unconsumed direct-island domain decode. It therefore tests the same proven
  runtime invariant without the rejected P1.4 helper-surface change.

Required evidence:

- The freshly regenerated post-P1.4-revert full-obfusjack artifact completed a
  same-JVM 8-run JFR probe with target `Seq` rows `310`, `308`, `306`, `305`,
  `319`, `308`, `306`, and `305 ms`.
- Filtering that fresh JFR to stacks containing obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, reports `fa_total=474`, `da_za_under_fa=240`,
  `da_ua_under_fa=149`, and `ba_under_fa=260`, proving the transition helper
  remains on the accepted post-revert hot path.
- P1.4 bytecode inspection before revert proved the current obfuscated
  `mmulSeq` direct-island shape: `22` transition-material helper callsites,
  with only `2` reloading `out[2]` afterward. The other `20` direct-island
  callsites consumed only `out[0]` / `out[1]`.
- Source inspection shows `emitTransitionOutLoads(..., edgeKind !=
  EdgeKind.DIRECT_ISLAND)` still intentionally avoids loading the domain word
  for direct-island transitions, while `installTransitionMaterialHelper`
  currently decodes `TRANSITION_MATERIAL_DOMAIN_WORD` and stores `out[2]`
  unconditionally.
- Source inspection shows `TRANSITION_MATERIAL_TABLE_SIZE` is `16_384` in
  `CffSharedState`, and `registerTransitionMaterialRow` increments
  `transitionMaterialCounter` then hard-fails when
  `index >= TRANSITION_MATERIAL_TABLE_SIZE`. Therefore every real transition
  material row index is below `0x4000`, and a private `0x80000000` callsite
  flag can be stripped before scaling without colliding with any real row
  identity.
- P1.4 rejection evidence requires avoiding the extra no-domain helper
  descriptor unless the Calc regression is explained and removed. P1.5
  therefore forbids adding that helper descriptor and changes only the existing
  helper's internal row-flag path.

Invariant mapping:

| Current transition path | P1.5 transition path | Preserved invariant |
| --- | --- | --- |
| direct and non-direct callsites invoke the same helper descriptor | unchanged | helper ABI and generated helper count do not change |
| row argument is a table index below `TRANSITION_MATERIAL_TABLE_SIZE` | direct-island callsites pass the same row index with a private high-bit flag; helper clears the flag before scaling | material row identity and row table lookup are unchanged |
| helper always decodes guard/path/block/pc/method/domain | helper decodes domain only when the high-bit flag is clear | every caller-consumed value is unchanged; non-direct domain semantics remain unchanged |
| direct-island caller reads `out[0]` and `out[1]`, not `out[2]` | unchanged | direct-island routing observes the same guard/path/block/pc and method key |

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.
- If full-obf obfusjack `Seq` remains above `200 ms`, run a fresh full
  obfusjack JFR and record the remaining `a.da.ua(...)` samples under
  `a.a.fa(Object[], long)`.

Completion criteria:

- Targeted JVM validation passes.
- Common runtime validation commands complete and medians are recorded.
- Full-obf obfusjack `Seq` improves from the current post-revert `312 ms`
  median, or the source change is reverted before proceeding.
- Full-obf TEST `Calc` remains no worse than the current P1.2.2b.2
  before-edit median `180 ms`.
- Generated bytecode inspection shows no new transition-material helper
  descriptor was added, direct-island callsites still invoke the original
  helper descriptor with flagged row constants, and the helper clears the flag
  before indexing the row.
- Non-direct transition callsites still pass unflagged row constants and still
  decode/store domain.
- CFF dry-run row counts for `realRows`, `fakeRows`, `poisonRows`,
  `liveDispatchTokenRows`, material rows, and missing-row counters do not show
  reduced coverage or new blockers compared with the P1.2.2b.2 artifact.
- No forbidden runtime/log/marker scan hit is introduced.
- Subagent implementation review passes before commit.

P1.5 status: `[x]` measured, rejected, and reverted. No implementation commit
was made for this branch.

P1.5 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated
  without adding the P1.4 rejected helper descriptor: obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, still invoked the original transition-material
  helper descriptor `(JIII[Ljava/lang/Object;II[J)J`; `20` direct-island
  call windows used flagged row constants; and the helper bytecode cleared the
  high-bit flag with `Integer.MAX_VALUE` before row scaling.
- A fresh user-requested ten-run runtime gate rejected the implementation.
  Full TEST `Calc` sorted values were
  `177,178,178,180,181,182,183,184,185,193`, with lower/upper medians
  `181/182 ms`. Full obfusjack `Seq` sorted values were
  `362,362,364,364,365,373,374,374,375,378`, with lower/upper medians
  `365/373 ms`.
- The optimization regressed full-obf obfusjack `Seq` from the current
  accepted post-P1.2.2b.2 median `312 ms` and did not keep TEST `Calc` at or
  below the current before-edit median `180 ms`. This violates P1.5 completion
  criteria, so the source change was fully reverted before continuing.
- Runtime/log scanning reported only the expected SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- The next P1 attempt must not reuse row-flagged direct-island transition
  domain skipping unless it first explains why the existing-helper branch
  shape regresses `Seq` and `Calc` generically.

#### P1.6 Pre-scale transition-material row offsets at the call boundary

Status: `[x]` measured, rejected, and reverted. No implementation commit was
made for this branch.

Scope:

- Optimize only the generic CFF transition-material helper row-index boundary.
- Keep the existing transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J` and helper count.
- Change the helper's `row` argument contract from a transition-material row
  number to the already-scaled material-word offset.
- For every generated transition-material helper callsite, pass
  `registeredRow * TRANSITION_MATERIAL_ROW_WORDS`.
- Remove the helper-entry `row * TRANSITION_MATERIAL_ROW_WORDS` multiplication
  and continue using the row local as the material array word offset.
- Preserve transition material row registration, row encryption/masking,
  method-key update, CFF block construction, island helper topology, hidden
  method keys, packed carriers, fake/poison coverage, direct/non-direct
  domain semantics, and helper ABI.
- Do not special-case a method/class/fixture, remove domain decode, add a new
  helper descriptor, expose row words, or add fallback/skip/original-bytecode
  behavior.

Subtasks:

1. `[x]` Record and review the row-offset contract change.
   Evidence requirement: current accepted 10-run runtime baseline, fresh
   full-obfusjack JFR attribution, bytecode evidence for the helper-entry
   multiplication, and source evidence that all current transition-material
   helper callsites are generated from registered rows.
   Validation target: plan-intake review only.
   Completion criteria: subagent plan-intake review passed; the plan
   checkpoint must be committed before source edits.
2. `[x]` Implement, validate, measure, reject, and revert the row-offset
   boundary change.
   Evidence requirement: source diff shows only the helper-entry scaling and
   generated callsite row constants changed; bytecode inspection shows
   `a.da.ua` no longer contains the entry `bipush 37; imul`, while obfuscated
   `mmulSeq` still calls the original helper descriptor.
   Validation target: same targeted JVM command as P1.1, plus the common
   runtime validation commands with a 10-run gate.
   Completion criteria: targeted JVM validation passes; full-obf obfusjack
   `Seq` improves from the current accepted 10-run lower/upper median
   `318/320 ms`, or the source change is reverted before proceeding; full-obf
   TEST `Calc` remains no worse than the current accepted 10-run lower/upper
   median `182/183 ms`; no CFF dry-run row count or missing-row blocker
   regresses; no forbidden runtime/log/marker scan hit is introduced; subagent
   implementation review passes before commit.

Dependency/order reason:

- P1.4 and P1.5 both targeted unconsumed direct-island domain decode and were
  rejected, so the next P1 attempt must not reuse that path.
- The fresh accepted-source 10-run gate after the P1.5 revert still leaves P1
  open: full TEST `Calc` sorted values were
  `179,180,181,181,182,183,183,184,185,185` with lower/upper medians
  `182/183 ms`, and full obfusjack `Seq` sorted values were
  `306,310,314,318,318,320,321,322,326,334` with lower/upper medians
  `318/320 ms`.
- A fresh same-JVM 8-run full-obfusjack JFR on the accepted-source artifact
  completed with `Seq` rows `313`, `311`, `326`, `305`, `321`, `337`,
  `319`, and `323 ms`.
- Filtering that JFR to stacks containing obfuscated `mmulSeq`,
  `a.a.fa(Object[], long)`, reports `fa_total=493`,
  `a.da.ua(...)=208`, `a.da.za(...)=193`, and `a.b.d(...)=154`, proving the
  transition-material helper remains one of the current hot P1 runtime paths.
- Fresh bytecode inspection of `a.da.ua` shows every invocation enters with:
  `iload row; bipush 37; imul; istore row`, before the helper reads any
  transition-material words.
- Fresh bytecode inspection of obfuscated `mmulSeq` shows `22` calls to the
  original transition-material helper descriptor
  `(JIII[Ljava/lang/Object;II[J)J`.
- Source inspection shows the current material transition path in
  `CffTransitionOutliner.emitCall` always registers a transition-material row
  through `registerTransitionMaterialRow` before invoking the shared helper,
  and the helper-wrapper path in `createHelper` does the same. Source
  inspection also shows `registerTransitionMaterialRow` stores each row at
  `index * TRANSITION_MATERIAL_ROW_WORDS`, while the helper currently repeats
  the same multiplication at runtime.

Invariant mapping:

| Current transition-material path | P1.6 path | Preserved invariant |
| --- | --- | --- |
| callsite passes registered row index | callsite passes `registered row index * TRANSITION_MATERIAL_ROW_WORDS` | selected material row is unchanged |
| helper multiplies row index by `TRANSITION_MATERIAL_ROW_WORDS` before material loads | helper uses the already-scaled word offset directly | every material word load addresses the same array element |
| helper descriptor and argument order are `(JIII,Object[],int,int,long[])` | unchanged | JVM ABI surface and generated helper count stay unchanged |
| transition row storage uses `index * TRANSITION_MATERIAL_ROW_WORDS` | unchanged | row encryption/masking and table layout stay unchanged |

P1.6 rejection evidence:

- The implementation passed the fresh targeted JVM validation command for the
  CFF algebraic audit, strong-entry seed regression, full JVM obfuscation
  performance test, invokedynamic, constant, string, method-parameter,
  renamer, and obfuscation integration tests. Gradle reported
  `BUILD SUCCESSFUL` with `19 executed` tasks.
- Generated bytecode inspection proved the intended route was generated:
  obfuscated `a.da.ua:(JIII[Ljava/lang/Object;II[J)J` no longer contained the
  entry `iload row; bipush 37; imul; istore row` sequence, obfuscated
  `mmulSeq`, `a.a.fa(Object[], long)`, still invoked the original
  transition-material helper descriptor `22` times, and inspected helper
  callsites passed constants such as `12062`, `12099`, and `12136`, proving
  row constants were pre-scaled by `TRANSITION_MATERIAL_ROW_WORDS`.
- A fresh ten-run runtime gate rejected the implementation. Full TEST `Calc`
  sorted values were `199,206,207,208,210,211,215,218,221,225`, with
  lower/upper medians `210/211 ms`. Full obfusjack `Seq` sorted values were
  `308,309,309,309,310,315,316,318,318,349`, with lower/upper medians
  `310/315 ms`.
- The optimization improved full-obf obfusjack `Seq` slightly from the
  current accepted 10-run lower/upper median `318/320 ms`, but regressed full
  TEST `Calc` from the current accepted 10-run lower/upper median `182/183 ms`
  to `210/211 ms`. This violates P1.6 completion criteria, so the source
  change was fully reverted before continuing.
- Runtime/log scanning reported only the expected SnakeGame
  `HeadlessException`, obfusjack `Caught MyException: boom`, and the existing
  class-load debug line for `org/example/Main$MyException`; it did not report
  verifier errors, linkage errors, fallback, skip, or forbidden marker hits.
- The next P1 attempt must not reuse row-boundary pre-scaling unless it first
  explains and removes the TEST `Calc` regression generically.

### P2 Bring TEST Calc runtime median to <= 60 ms

Scope:

- Optimize only full JVM-obfuscated runtime overhead that remains after P1.
- Address the secondary TEST Calc delta between CFF-only median `94 ms` and
  full JVM obf median `198 ms`.
- Do not expose plaintext constants, descriptor strings, names, handles, keys,
  or static seed material, and do not add Java helper classes or adapter layers.

#### P2.1 Attribute remaining TEST Calc runtime samples

Scope:

- Run JFR on the post-P1 full TEST artifact.
- Map hot obfuscated methods to original methods through the fresh map file.
- Identify whether remaining samples are CFF execution, invokedynamic
  materialization, string decode/materialization, constant materialization, or
  packed carrier/key-transfer overhead.

Required evidence:

- JFR hot-method report and runtime stdout proving TEST completed and reporting
  `Calc`.
- Bytecode/topology evidence for the top sampled obfuscated methods.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Runtime target:

```bash
mkdir -p build/jvm-runtime-perf/jfr build/jvm-runtime-perf/java-tmp
java -XX:-UsePerfData \
  -Djava.io.tmpdir=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/java-tmp \
  -XX:StartFlightRecording=filename=/mnt/d/Code/Security/NekoObfuscator/build/jvm-runtime-perf/jfr/post-p1-test-calc.jfr,settings=profile,dumponexit=true \
  -jar build/test-jvm-full-obf-perf/test-obf.jar \
  > build/jvm-runtime-perf/jfr/post-p1-test-calc.stdout.log \
  2> build/jvm-runtime-perf/jfr/post-p1-test-calc.stderr.log
jfr view hot-methods build/jvm-runtime-perf/jfr/post-p1-test-calc.jfr
jfr print --events ExecutionSample --stack-depth 20 build/jvm-runtime-perf/jfr/post-p1-test-calc.jfr
```

Required post-run mapping and log inspection:

```bash
rg -n "Exception|Error|VerifyError|BootstrapMethodError|NoSuchMethod|NoSuchField|WrongMethodType|ClassCastException|ArrayIndexOutOfBoundsException|fallback|skip|static-decrypt|__neko_cff_didx|__neko_indy_bsm_shared|__neko_indy_register" \
  build/jvm-runtime-perf/jfr/post-p1-test-calc.stdout.log \
  build/jvm-runtime-perf/jfr/post-p1-test-calc.stderr.log \
  build/test-jvm-full-obf-perf/TEST.full-obf.run.stdout.log \
  build/test-jvm-full-obf-perf/TEST.full-obf.run.stderr.log \
  build/test-jvm-full-obf-perf/test-obf.obfuscate.stdout.log \
  build/test-jvm-full-obf-perf/test-obf.obfuscate.stderr.log
```

Top sampled obfuscated methods must be mapped through the fresh
`build/test-jvm-full-obf-perf/test-obf.jar.map` file and, where needed,
through `javap` output under `build/test-jvm-full-obf-perf/`.

Completion criteria:

- Plan is updated with an exact post-P1 TEST Calc hot runtime path before P2.2
  code edit begins.
- No runtime code changes in this subtask.

#### P2.2 Optimize repeated loop-site materialization on the proven Calc path

Scope:

- Optimize the single generic category proven by P2.1, using live CFF state and
  existing protected carrier/table surfaces.
- If P2.1 shows multiple categories, choose one bounded category and record a
  new P2 subtask before editing the next category.
- Do not cache or compact values from static descriptors, owner/name strings,
  static seeds, plaintext constants, or unkeyed helper state.

Required evidence:

- P2.1 runtime attribution.
- Source invariant showing cached/compacted material is still keyed by live
  method-entry/CFF state and remains eligible for the enabled JVM obfuscation
  surfaces or emits an equivalent protected form directly.

Validation target:

Same targeted JVM command as P1.1.

Runtime target:

- Run the common runtime validation commands.

Completion criteria:

- Full-obf TEST `Calc` five-run median is `<= 60 ms`.
- Full-obf obfusjack `Seq` remains `<= 200 ms`.
- Non-target obfusjack rows stay within the 10% regression budget.
- No generated helper count, descriptor count, plaintext marker, or fallback
  regression.
- Subagent implementation review passes before commit.

### P3 Add source-controlled JVM runtime ablation reporting

Scope:

- Convert the manual ablation matrix into a source-controlled test/report so
  future runtime optimization claims do not depend on ad hoc shell runs.
- Keep it test-only and JVM-only.
- Record per-run runtime timing rows, medians, output jar sizes, CFF topology,
  helper descriptor counts, largest methods, and invalid ablation failures.
- Do not add obfuscation wall-clock or pass-time acceptance gates in this row.

Required evidence before editing:

- Existing manual ablation logs from `build/jvm-perf-ablation`.
- Existing `JvmFullObfuscationPerfTest` reporting fields to reuse.

Validation target:

```bash
./gradlew :neko-test:test --tests dev.nekoobfuscator.test.JvmFullObfuscationPerfTest --rerun-tasks
```

Completion criteria:

- Report includes original, CFF-only-stack, full-no-indy,
  full-no-const-string, and full JVM obf rows.
- The report records invalid ablations as invalid instead of treating failures
  as performance data.
- No runtime or transform behavior changes are included.
- Subagent review passes before commit.

## Review Status

First plan-intake review failed because the plan lacked numeric acceptance
targets, had coarse P1/P2 tasks, did not define exact runtime validation
commands, and did not tie P1 to an exact hot runtime path. This revision records
the user-approved P1/P2 numeric targets, exact runtime commands, finer
subtasks, and JFR hot-path evidence for the first P1 repair surface.

Second plan-intake review failed because P2.1 lacked an explicit runtime target
with concrete TEST Calc JFR commands, output paths, map path, and log
inspection. This revision adds that P2.1 runtime target before implementation.

Third plan-intake review passed. No implementation blockers remained under the
JVM-runtime-only scope and the user-approved P1/P2 thresholds.
