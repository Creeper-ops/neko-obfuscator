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

- If P1.2.1 does not reach P1 acceptance, replace remaining per-island helper
  execution with a generic shared class-level island execution helper for rows
  satisfying the dry-run readiness constraints.
- Preserve every row and token category listed in the parent P1.2 scope.

Required evidence before editing:

- Post-P1.2.1 JFR hot-method report and generated topology identifying the
  remaining dominant island/material helper path.
- A new invariant mapping for the shared island helper inputs, table rows,
  return `out` words, fake bounce behavior, and poison behavior.

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
