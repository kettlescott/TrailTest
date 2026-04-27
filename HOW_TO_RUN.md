# How to Run

## Prerequisites

- **Java 25** (or later) with preview feature support
- **Apache Maven 3.8+**
- *(Optional, Linux only)* `linux-tools-perf` for hardware profiling

## Build

```bash
mvn clean package
```

## Run BenchmarkMain (YAML-Driven)

`BenchmarkMain` runs from a YAML file. The primary CLI is:

```bash
--config=benchmarks.yaml
```

### Quick Start (recommended)

Run with Maven (dependencies auto-included):

```bash
mvn -DskipTests exec:java -Dexec.args="--config=benchmarks.yaml"
```

Run with `java` + classpath (includes runtime dependencies like SnakeYAML):

```bash
CP=$(mvn -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile=/tmp/trail_cp.txt >/dev/null && cat /tmp/trail_cp.txt)
java -Xms1g -Xmx1g --enable-preview -cp "target/classes:$CP" com.scott.BenchmarkMain --config=benchmarks.yaml
```

Run with packaged JAR + dependencies:

```bash
CP=$(mvn -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile=/tmp/trail_cp.txt >/dev/null && cat /tmp/trail_cp.txt)
java -Xms1g -Xmx1g --enable-preview -cp "target/TrailSystem-1.0-SNAPSHOT.jar:$CP" com.scott.BenchmarkMain --config=benchmarks.yaml
```

Run with self-contained shaded JAR (built by `mvn clean package`):

```bash
java -Xms1g -Xmx1g --enable-preview -jar target/TrailSystem-1.0-SNAPSHOT-all.jar --config=benchmarks.yaml
```

## YAML Structure

Minimal shape:

```yaml
global:
  workerCount: 16        # total worker budget (used by shared/sharded modes)
  maxInflight: 32        # bounded in-flight submission window
  seed: 3735928559
  warmupSeconds: 3
  measurementSeconds: 10
  taskCount: 0           # 0 = time-based (run for measurementSeconds)

# ----------------------------------------------------------------------
# Workload schema
#
# Each workload is a list of entries. Every entry MUST specify:
#   kind         : CPU | MEMORY | IO
#   targetMillis : desired wall-clock execution time per task (calibrated
#                  for CPU/MEMORY at startup; LockSupport.parkNanos for IO)
#   ratio        : fraction of generated tasks; ratios sum to 1.0
#                  (or 100 — integer-percent inputs are auto-rescaled)
# Optional:
#   name         : human-readable label
#   memory       : (only when kind=MEMORY) accessPattern / bufferMB / writeBack
# ----------------------------------------------------------------------
workloads:
  cpu_1ms:
    - { kind: CPU, targetMillis: 1, ratio: 1.0 }

  io_2ms:
    - { kind: IO, targetMillis: 2, ratio: 1.0 }

  memory_random_64mb:
    - kind: MEMORY
      targetMillis: 5
      ratio: 1.0
      memory:
        accessPattern: RANDOM   # SEQUENTIAL | RANDOM
        bufferMB: 64            # working-set size; ladder: 8 / 64 / 256
        writeBack: false        # default false — read-only avoids false sharing

  mixed_realistic:
    - { name: fast_cpu, kind: CPU,    targetMillis: 1,  ratio: 0.40 }
    - { name: scan,    kind: MEMORY, targetMillis: 5,  ratio: 0.30 }
    - { name: io_call, kind: IO,     targetMillis: 20, ratio: 0.30 }

# ----------------------------------------------------------------------
# Hybrid dispatcher routing policy
#
# REQUIRED for any run with mode=hybrid. There are NO built-in defaults:
# every WorkloadKind (CPU, MEMORY, IO) must be mapped explicitly to
# either SHARED or SHARDED. This makes routing policy a first-class
# experimental variable. Per-run 'hybrid:' overrides the top-level one.
#
# Total hybrid workers = sharedWorkers + shardedWorkers. If this differs
# from global.workerCount, the run still proceeds but a warning is
# printed (apples-to-apples comparison with shared/sharded modes is
# only meaningful when totals match).
# ----------------------------------------------------------------------
hybrid:
  sharedWorkers: 8
  shardedWorkers: 8
  routing:
    CPU:    SHARDED
    MEMORY: SHARED
    IO:     SHARED

profiling:
  enabled: true
  control: api            # 'api' (in-process, recommended) | 'cli' (jcmd)
  settings: profile       # JFR preset: 'default' | 'profile' | path to a .jfc
  start: beforeMeasurement
  stop:  afterMeasurement
  filename: ${runName}.jfr
  startupQuietPeriodMs: 200
  shutdownFlushMs: 100
  startCommand: JFR.start name=${runName} settings=${settings} filename=${outputFile}
  stopCommand:  JFR.stop  name=${runName} filename=${outputFile}

  perf:
    enabled: false
    binary: perf
    frequency: 99
    clock: monotonic
    callGraph: none
    mmapPages: 512
    extraArgs: []
    filename: ${runName}.perf.data

  asyncProfiler:
    enabled: false
    binary: asprof
    event: wall
    interval: 1ms
    format: jfr
    filename: ${runName}.async.jfr
    extraArgs: []

runs:
  - name: shared_cpu_1ms
    mode: shared
    workload: cpu_1ms

  - name: sharded_cpu_1ms
    mode: sharded
    workload: cpu_1ms

  - name: hybrid_policyA_mixed   # CPU=SHARDED, MEMORY=SHARED, IO=SHARED
    mode: hybrid
    workload: mixed_realistic
    # Per-run override of the top-level hybrid block (optional).
    hybrid:
      sharedWorkers: 8
      shardedWorkers: 8
      routing:
        CPU:    SHARDED
        MEMORY: SHARED
        IO:     SHARED
```

### Workloads

A workload is a **list of entries**, each describing one task class:

| Field | Required | Notes |
|---|---|---|
| `kind` | yes | `CPU` \| `MEMORY` \| `IO` |
| `targetMillis` | yes | Wall-clock target; calibrated at startup for CPU/MEMORY |
| `ratio` | yes | Fraction; entries within a workload sum to ~1.0 (or 100) |
| `name` | no | Display label in console / `summary.txt` |
| `memory` | no | Only valid when `kind: MEMORY`; see below |

**MEMORY knobs:**

| Sub-field | Default | Notes |
|---|---|---|
| `accessPattern` | `SEQUENTIAL` | `SEQUENTIAL` (cache-friendly) or `RANDOM` (TLB / LLC pressure) |
| `bufferMB` | `8` | Working-set size; useful ladder: 8 / 64 / 256 |
| `writeBack` | `false` | Default read-only — avoids false sharing as a confounding variable |

> **Removed in this version:** the legacy `kind: single|mix, type: short|medium|long, distribution: {...}` schema. Old YAML files are auto-translated with a `[legacy-yaml]` warning, but new configs should use the explicit `kind: CPU|MEMORY|IO` form above. `global.targetTaskNanos` is also deprecated (per-entry `targetMillis` supersedes it); if present in YAML it is ignored with a warning.

### Runs

| Field | Required | Notes |
|---|---|---|
| `name` | yes | Output folder under `results/` |
| `mode` | yes | `shared` \| `sharded` \| `hybrid` |
| `workload` | yes | Key from the `workloads:` map |
| `hybrid` | no | Per-run override of the top-level `hybrid:` block |

`mode: hybrid` requires an effective `hybrid:` config (per-run override or top-level). Validation fails fast at load time if missing.

## Output Files

For each run in `runs:`:

- `results/<runName>/summary.txt`               — see breakdown below
- `results/<runName>/run.json`                  — reproducibility metadata (host, JVM, OS, config, outputs)
- `results/<runName>/<runName>.jfr`             — when `profiling.enabled: true`
- `results/<runName>/<runName>.perf.data`       — when `profiling.perf.enabled: true`
- `results/<runName>/<runName>.perf.log`        — `perf record` stderr / launch diagnostics
- `results/<runName>/<runName>.async.jfr`       — when `profiling.asyncProfiler.enabled: true`
- `results/<runName>/<runName>.async.log`       — `asprof start`/`stop` stderr

### `summary.txt` content

Generated after each run, machine-friendly key=value lines plus formatted percentile tables:

```
runName=hybrid_policyA_mixed
mode=hybrid
workload=mixed_realistic
workerBudget=16
hybridTotalWorkers=16
hybridSharedWorkers=8
hybridShardedWorkers=8
hybridRouting=CPU=SHARDED,MEMORY=SHARED,IO=SHARED

workloadEntries=3
entry[0]=name=fast_cpu, kind=CPU, targetMillis=1, ratio=0.4000
entry[1]=name=scan, kind=MEMORY, targetMillis=5, ratio=0.3000, accessPattern=SEQUENTIAL, bufferMB=8, writeBack=false
entry[2]=name=io_call, kind=IO, targetMillis=20, ratio=0.3000

calibrationEntries=3
calibration[0]=name=fast_cpu, kind=CPU, targetMillis=1, cpuIterations=4923
calibration[1]=name=scan, kind=MEMORY, targetMillis=5, memorySteps=78912, bufferMB=8, accessPattern=SEQUENTIAL, writeBack=false
calibration[2]=name=io_call, kind=IO, targetMillis=20  (no calibration; parkNanos)

submitted=600000
submitDurationSeconds=10.000
drainDurationSeconds=2.314
totalDurationSeconds=12.314
submittedPerSecond=60000.0
completedPerSecond=48725.3
backpressureEvents=42
backpressureWaitMillis=11.728
avgQueueDepth=14.21
maxQueueDepth=64
queueDepthSamples=1000

=== Latency: overall ===
Recorded tasks: 600000

Metric            p50        p90        p95        p99        max
------------------------------------------------------------------------
Submit overhead   0.001 ms   0.002 ms   0.003 ms   0.012 ms   3.117 ms
Queue wait        0.083 ms   0.412 ms   0.881 ms   2.140 ms  18.402 ms
Execution         1.024 ms   5.014 ms   5.301 ms  20.108 ms  21.880 ms
End-to-end        1.110 ms   5.502 ms   6.281 ms  22.443 ms  37.117 ms

=== Latency: CPU ===
... (same table, CPU-only samples)
=== Latency: MEMORY ===
... (same table, MEMORY-only samples)
=== Latency: IO ===
... (same table, IO-only samples)

perKind.CPU.count=240118
perKind.CPU.queueWaitMs.p50=0.041, p95=0.117, p99=0.502
perKind.CPU.executionMs.p50=1.012, p95=1.092, p99=1.221
perKind.CPU.endToEndMs.p50=1.057, p95=1.220, p99=1.733
... (MEMORY, IO blocks)
```

Key fields for cross-mode comparison:

| Field | Meaning |
|---|---|
| `workerBudget` | `global.workerCount` — the apples-to-apples budget across modes |
| `hybridTotalWorkers` | `hybrid.sharedWorkers + hybrid.shardedWorkers` — should equal `workerBudget` for fair comparison |
| `hybridRouting` | Compact policy string, e.g. `CPU=SHARDED,MEMORY=SHARED,IO=SHARED` |
| `submitDurationSeconds` | Wall time submitting the measurement task stream |
| `drainDurationSeconds` | Wall time waiting for in-flight tasks to finish *(excluded from profiling windows)* |
| `totalDurationSeconds` | `submit + drain` |
| `submittedPerSecond` | Throughput against the submit window (input rate) |
| `completedPerSecond` | Throughput against total wall time (sustained rate) |
| `backpressureWaitMillis` | Total time the submitter blocked on `permits.acquire()` |
| `avgQueueDepth` / `maxQueueDepth` | Sampled at 10 ms intervals during the **submit window only** |
| `calibration[i]` | Actual calibrated `cpuIterations` / `memorySteps` for reproducibility |
| `perKind.<KIND>.{queueWait,execution,endToEnd}Ms.{p50,p95,p99}` | Per-WorkloadKind percentiles (essential for hybrid policy comparison) |

---

## JFR

### Control modes

| `control:` | What happens | When to use |
|---|---|---|
| **`api`** *(default, recommended)* | In-process `jdk.jfr.Recording` API. No external binary. Works on JRE and slim containers. Lower start/stop latency; `startCommand` / `stopCommand` are ignored. | Default for this project. |
| `cli` | Shells out to `jcmd` (resolved from `$JAVA_HOME/bin/jcmd` first, then `PATH`). Requires a full JDK. | Only if you need `jcmd` semantics specifically, or are reproducing existing `jcmd`-based tooling. |

### Lifecycle (both modes)

- `start: beforeMeasurement` — recording opens immediately **after warmup**, before the first measurement-phase submit.
- `stop:  afterMeasurement`  — recording closes at **`submitEnd`** (the moment the last measurement task is handed to the dispatcher), **before** the post-submit drain. This window is the **submit window only**: the 10–30 s drain is never part of any profiler recording.

Implementation: `runPhase()` invokes a callback at `submitEnd` that emits the JFR `stop` anchor, stops the queue-depth sampler, and calls `ProfilingSession.stop()` — all before the drain begins. The outer `finally` block calls `stop()` again as a safety net; `ProfilingSession.stop()` is idempotent so the second call is a no-op on the happy path.

### Anchor events

At the instant the recording opens and closes, the session emits one JFR event of type `com.scott.MeasurementAnchor` carrying:

- `phase` — `"start"` or `"stop"`
- `runId` — matches the `name:` in `runs:`
- `benchmarkMode` — `SHARED` | `SHARDED` | `HYBRID`
- `workloadType` — workload key from YAML
- `workerCount`
- `note` — for HYBRID runs this carries the routing policy string (e.g. `CPU=SHARDED,MEMORY=SHARED,IO=SHARED`); empty otherwise
- `nanoTime` — `System.nanoTime()` at the boundary (same clock as `perf -k monotonic`)
- `epochMillis` — wall-clock milliseconds

These two events are the fiducials used to align JFR with `perf` timelines (see below). They are emitted twice per run — never on the hot path.

### Template variables (CLI mode only)

- `${runName}`, `${settings}`, `${outputFile}`

The default `startCommand` / `stopCommand` are dispatched as a pre-built argv list (no whitespace splitting), so run paths containing spaces are safe. If you override them with a custom template, the expanded string is split on whitespace; in that case, avoid spaces in run directory paths.

### Inspecting a JFR file

```bash
jfr print --events com.scott.MeasurementAnchor results/<run>/<run>.jfr
jfr summary results/<run>/<run>.jfr
# Or open in JDK Mission Control (JMC)
```

---

## Linux `perf` Integration (optional)

The harness can start a `perf record` subprocess that brackets the JFR measurement window, giving you time-aligned kernel / hardware samples alongside JFR events. The `perf` process is a child of the JVM, not a Java thread; steady-state sampling runs in the kernel and the external `perf` process.

### Enable

```yaml
profiling:
  perf:
    enabled: true
```

Defaults are intentionally low-overhead (`frequency: 99`, `callGraph: none`). For deeper analysis, override these — see the presets below.

### One-time prerequisites (root)

```bash
sudo sysctl -w kernel.perf_event_paranoid=1
sudo sysctl -w kernel.kptr_restrict=0
```

Persist in `/etc/sysctl.d/99-perf.conf` if you want them across reboots.

### JVM flags for usable Java stacks

Only needed when `callGraph: fp` (or any stack-based analysis):

```
-XX:+UnlockDiagnosticVMOptions
-XX:+PreserveFramePointer      # required for --call-graph fp
-XX:+DumpPerfMapAtExit         # writes /tmp/perf-<pid>.map at JVM shutdown
```

Invocation with flags:

```bash
java -Xms1g -Xmx1g --enable-preview \
     -XX:+UnlockDiagnosticVMOptions -XX:+PreserveFramePointer -XX:+DumpPerfMapAtExit \
     -jar target/TrailSystem-1.0-SNAPSHOT-all.jar --config=benchmarks.yaml
```

### Overhead presets

| Goal | YAML override |
|---|---|
| Minimal-overhead probe *(default)* | `frequency: 99`, `callGraph: none` |
| Deep CPU profile / flame graph | `frequency: 999`, `callGraph: fp` *(requires `-XX:+PreserveFramePointer`)* |
| Portable call graphs without JVM flags | `frequency: 999`, `callGraph: dwarf` |
| Hardware counters only | `callGraph: none`, `extraArgs: ["-e", "cycles,instructions,cache-misses,LLC-load-misses"]` |

### Recommended `extraArgs` recipes

`extraArgs` is passed verbatim to `perf record` *before* the harness-managed
`-p / -o` flags. Use it for anything the top-level YAML fields don't expose.

| Goal | `extraArgs:` |
|---|---|
| **Fast stop** (always safe; skips build-id cache rebuild) | `["--no-buildid", "--no-buildid-cache"]` |
| **HW counter overlay** (shared-vs-sharded headline table) | `["-e", "cycles,instructions,cache-misses,LLC-load-misses,branch-misses"]` |
| **Cache-coherence / false-sharing focus** (Intel SKX+/AMD Zen2+) | `["-e", "mem_load_retired.l3_miss,mem_load_l3_miss_retired.remote_hitm"]` |
| **Off-CPU / scheduler analysis** (explain tail latency) | `["-e", "sched:sched_switch", "-e", "sched:sched_wakeup", "-e", "sched:sched_stat_sleep"]` |
| **Compressed output** (long runs) | `["-z"]` |
| **User-space only** (minimise kernel-side perturbation) | `["--all-user"]` |
| **High-rate ring-buffer tuning** (if `LOST chunks` persists after raising `mmapPages`) | `["--aio=4"]` |

Combinable example — HW counters + fast stop, the most useful single recipe
for this benchmark:

```yaml
perf:
  enabled: true
  frequency: 999
  callGraph: fp
  extraArgs:
    - "--no-buildid"
    - "--no-buildid-cache"
    - "-e"
    - "cycles,instructions,cache-misses,LLC-load-misses,branch-misses"
```

**Do not put these in `extraArgs`** — they are managed by the harness and
overriding them breaks alignment or double-configures `perf`:

- `-p` / `--pid`          *(set from JVM PID)*
- `-o` / `--output`       *(set from `filename:`)*
- `-F`                    *(set from `frequency:`)*
- `-k` / `--clockid`      *(set from `clock:`; **overriding breaks JFR/perf time alignment**)*
- `-g` / `--call-graph`   *(set from `callGraph:`)*
- `-m` / `--mmap-pages`   *(set from `mmapPages:`)*

### Time alignment with JFR

JFR and `perf` both timestamp events with `CLOCK_MONOTONIC` (= `System.nanoTime()`). As long as `profiling.perf.clock` is left at `monotonic` (the default), the two timelines share the same clock and can be overlaid directly.

Verify alignment after a run:

```bash
# Read JFR anchor markers
jfr print --events com.scott.MeasurementAnchor results/<run>/<run>.jfr

# Read first/last perf sample timestamps
perf script -i results/<run>/<run>.perf.data --ns \
  | awk '{print $4}' | sed 's/://' | sort -n | sed -n '1p;$p'
```

Expectation: JFR `start` anchor `nanoTime` ≥ first `perf` timestamp, JFR `stop` anchor ≤ last `perf` timestamp. Residual skew is single-digit microseconds (sample-buffering / IRQ latency), not clock drift.

### Offline perf recipes

For common diagnostic commands (`perf record` flame graphs, `perf stat`, `perf c2c` cache-line contention, `perf sched` latency, `perf lock`), see [`docs/perf_cookbook.md`](docs/perf_cookbook.md).

### Platform support

- **Linux** — fully supported.
- **macOS / Windows** — `perf` does not exist. If you set `perf.enabled: true`, the harness throws `UnsupportedOperationException` at run start. Leave `perf.enabled: false` (the default) and use `xctrace` (macOS) externally if needed.

---

## async-profiler Integration (tail-latency analysis)

`perf` samples **on-CPU** threads. When the shared-queue executor stalls on
AQS `park`, on a futex inside `ReentrantLock`, or waits for a GC safepoint,
those threads are **off-CPU** and are therefore invisible to `perf -F 99`.
p99 / p99.9 latency spikes in this benchmark almost always originate in
that off-CPU region, which is exactly what async-profiler's **wall-clock**
event captures.

The harness drives async-profiler through the `asprof` CLI (the modern
`profiler.sh` replacement). `asprof start` attaches via the JVM attach API
and returns immediately; `asprof stop` dumps and detaches. Nothing runs
in-process on the benchmark thread during the measurement window.

### Prerequisites

1. Download async-profiler (≥ 3.0) and put `asprof` on `PATH`, or set
   `profiling.asyncProfiler.binary` to an absolute path.
2. Linux only: `sudo sysctl -w kernel.perf_event_paranoid=1` if you use
   `event: cpu` (wall/lock/alloc work without this flag; they are purely
   user-space mechanisms).
3. No JVM flags are strictly required — async-profiler's attach agent
   enables `-XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints`
   automatically. Keep `-XX:+PreserveFramePointer` if you are also running
   perf with `callGraph: fp`.

### Enable

```yaml
profiling:
  enabled: true
  control: api
  settings: profile
  filename: ${runName}.jfr

  asyncProfiler:
    enabled:  true
    event:    wall       # wall | cpu | lock | alloc | itimer
    interval: 1ms        # 100us, 1ms, 10ms, or raw ns integer
    format:   jfr        # jfr | html | collapsed | tree
    filename: ${runName}.async.jfr
    extraArgs: []
```

### Event choice — which one answers which question

| Question about tail latency | `event:` | Why |
|---|---|---|
| **"Where are my threads stuck?"** — p99 spike root cause | `wall` | On-CPU **and** off-CPU samples. Shows `LockSupport.park`, futex, I/O, safepoint, whatever is eating wall time. **This is the default and the right starting point.** |
| "Who owns the CPU?" | `cpu` | On-CPU only. Use when p99 is driven by CPU work, not waiting. |
| "Which lock is contended?" | `lock` | JVM lock events (`Object.wait`, monitor contention, `ReentrantLock.lock`). Directly answers "is the shared queue lock the bottleneck?". |
| "Is GC / allocation to blame?" | `alloc` | Samples allocation sites; useful if tail latency correlates with GC pauses. |

For the shared-vs-sharded comparison, run **`wall` first** on both modes —
the flame graph difference is the paper figure. Follow up with `lock` on
whichever mode has the wider tail.

### Recommended `extraArgs`

`extraArgs` is passed verbatim to `asprof start` before the target PID.

| Goal | `extraArgs:` |
|---|---|
| **Only the benchmark worker threads** (filter out JIT, GC, dispatcher) | `["-t"]` — split samples by thread; then filter in JMC / converter |
| **Include only specific thread name prefix** | `["-I", "shared-worker-*"]` or `["-I", "shard-*"]` |
| **Exclude sampling inside native code** | `["--cstack", "no"]` |
| **Higher-fidelity Java stacks** | `["--cstack", "fp"]` *(requires `-XX:+PreserveFramePointer`)* |
| **Memory-mapped JIT symbols persist across runs** | *(default)* — nothing extra needed; async-profiler reads the JVM in-process |

### Time alignment with JFR and perf

`asprof -o jfr` writes a standalone JFR file that uses `System.nanoTime()`
timestamps — the **same clock** as the main JFR recording and as `perf -k
monotonic`. The `com.scott.MeasurementAnchor` events emitted at the
measurement-window boundary therefore align all three timelines.

Verify:

```bash
# async-profiler file: first/last sample
jfr print --events jdk.ExecutionSample results/<run>/<run>.async.jfr \
  | awk '/startTime/ {print $2}' | sort -n | sed -n '1p;$p'

# main JFR anchors
jfr print --events com.scott.MeasurementAnchor results/<run>/<run>.jfr
```

Expectation: async-profiler's first sample ≥ JFR `start` anchor, last sample
≤ JFR `stop` anchor. The harness nests windows as `[perf ⊃ jfr ⊃ async]`
specifically so this invariant always holds.

### Analyzing the output

```bash
# Flame graph (HTML)
asprof convert -o flamegraph results/<run>/<run>.async.jfr \
       -f results/<run>/<run>.async.flame.html

# Or load the .jfr into JDK Mission Control (JMC)
jmc results/<run>/<run>.async.jfr

# Compare two runs side-by-side (differential flame graph)
asprof convert -o flamegraph --total \
       --diff results/shared_short/shared_short.async.jfr \
       results/sharded_short/sharded_short.async.jfr \
       -f diff.html
```

The differential flame graph is the single most useful artefact for the
shared-vs-sharded comparison: the red regions are where the shared queue
spends wall time that the sharded queue does not.

### Overhead

Wall-clock `event: wall, interval: 1ms` samples every live thread once per
ms. For 16 workers this is ~16k samples/s, ~0.1 % CPU. Lower the interval
(`100us`) only if you need sub-millisecond resolution — it multiplies
overhead by 10×.

### Platform support

- **Linux**, **macOS**, **Windows** — all supported by async-profiler ≥ 3.0.
  Unlike `perf`, async-profiler is cross-platform, so this is the
  recommended profiler on non-Linux benchmark hosts.

### Output files

When `asyncProfiler.enabled: true`:

- `results/<runName>/<runName>.async.jfr`  — samples (or `.html` / `.collapsed` / `.tree.html`, per `format:`)
- `results/<runName>/<runName>.async.log`  — `asprof start`/`stop` stderr
- `run.json` gains `asyncProfilerEnabled`, `asyncProfilerEvent`,
  `asyncProfilerInterval`, `asyncProfilerFormat`, `asyncProfilerOutput`.

---

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `Cannot run program "jcmd": No such file or directory` | `jcmd` not on `PATH`, or running on a JRE | Set `profiling.control: api` (recommended) or run on a full JDK. |
| `perf exited immediately` in the perf log | `kernel.perf_event_paranoid` too high, missing capability, or invalid clock name | `sudo sysctl -w kernel.perf_event_paranoid=1`; keep `clock: monotonic`. |
| All Java frames show as `[unknown]` in `perf report` | Missing `/tmp/perf-<pid>.map` | Add `-XX:+DumpPerfMapAtExit` (offline) or use `async-profiler` / `perf-map-agent` (online). |
| Java stacks 1 frame deep with `callGraph: fp` | Frame pointers not preserved | Add `-XX:+PreserveFramePointer`, or switch to `callGraph: dwarf`. |
| `LOST X chunks` in perf log | Ring buffer too small for sample rate × cores | Raise `profiling.perf.mmapPages` (must be a positive power of two). |
| `perf.data` appears truncated | `perf` killed with SIGTERM instead of SIGINT | Should not happen — the recorder sends SIGINT. If reproduced, file a bug with the log. |
| Recording seems to include dispatcher shutdown | Old code path | Rebuild; current `BenchmarkMain` closes profiling at `submitEnd`, before drain. |
| `*** WARNING: hybrid.sharedWorkers + hybrid.shardedWorkers (N) != global.workerCount (M)` | Hybrid pool size doesn't match the cross-mode budget | For apples-to-apples comparison with `shared`/`sharded` runs, set `sharedWorkers + shardedWorkers == global.workerCount`. The run still proceeds. |
| `mode=hybrid requires a hybrid config` (validation error at YAML load) | Missing `hybrid:` block | Add a top-level `hybrid:` section, or a per-run `hybrid:` override, mapping every `WorkloadKind` (CPU, MEMORY, IO) to `SHARED` or `SHARDED`. |
| `[yaml] global.targetTaskNanos is deprecated and ignored` | Old YAML still has `global.targetTaskNanos` | Remove it; task sizing is per-entry via `targetMillis`. |

---

## Profiling Architecture

All profiling is driven by a single `ProfilingSession` (package `com.scott.profiling`). The benchmark driver only calls lifecycle methods; no back-end-specific code leaks into `BenchmarkMain` or the hot path.

```
BenchmarkMain
    │
    ▼
ProfilingSession  ── wraps ──►  Profiler (interface)
                                  │
                                  ├─► JfrProfiler           (API | CLI)
                                  ├─► PerfProfiler          (Linux only)
                                  ├─► AsyncProfilerProfiler (cross-platform)
                                  └─► CompositeProfiler     (ordered list)
```

Lifecycle, in order:

1. `session.start()` — starts each profiler in insertion order; rolls back in reverse order on any failure.
2. `session.beforeMeasurement()` — sleeps `startupQuietPeriodMs` so perf kernel-event install and JFR first-chunk flush don't bleed into the measurement window.
3. `session.markMeasurementStart(ctx)` — emits one `com.scott.MeasurementAnchor` JFR event (phase=`"start"`) at `submitStart`.
4. Measurement loop runs. **No profiling code on the hot path.**
5. At `submitEnd` (last task submitted, drain not yet started), the `runPhase` `onSubmitEnd` callback fires and:
   - emits the matching phase=`"stop"` anchor via `session.markMeasurementStop(ctx)`,
   - stops the queue-depth sampler,
   - calls `session.stop()` — optional `shutdownFlushMs` sleep, then stops profilers in reverse order (perf last, so its timeline brackets JFR's).
6. Dispatcher drain proceeds. **No profiler / sampler observes it.** The outer `finally` block calls `session.stop()` again as a safety net; the call is idempotent and a no-op on the happy path.

Profilers stop **before** drain (and therefore before `dispatcher.shutdown()`), so 10–30 s of drain/cleanup is never attributed to the benchmark.

### `run.json`

Written once per run, outside the measurement window. Used for reproducibility:

```json
{
  "runId": "hybrid_policyA_mixed",
  "timestamp": "2026-04-21T12:34:56Z",
  "hostname": "...",
  "os.name": "Linux",
  "jvm.version": "25",
  "cpu.cores": 16,
  "mode": "HYBRID",
  "workload": "mixed_realistic",
  "workerBudget": 16,
  "workerCount": 16,
  "maxInflight": 32,
  "warmupSeconds": 3,
  "measurementSeconds": 10,
  "taskCount": 0,
  "submitted": 600000,
  "submitDurationSeconds": "10.000",
  "drainDurationSeconds":  "2.314",
  "totalDurationSeconds":  "12.314",
  "submittedPerSecond":    "60000.0",
  "completedPerSecond":    "48725.3",
  "avgQueueDepth":         "14.21",
  "maxQueueDepth":         64,
  "hybridTotalWorkers":    16,
  "hybridSharedWorkers":   8,
  "hybridShardedWorkers":  8,
  "hybridRouting":         "CPU=SHARDED,MEMORY=SHARED,IO=SHARED",
  "profilingEnabled":      true,
  "jfrSettings":           "profile",
  "jfrOutput":             "results/hybrid_policyA_mixed/hybrid_policyA_mixed.jfr",
  "perfEnabled":           false,
  "asyncProfilerEnabled":  false
}
```

---

## PinningExample (Optional)

Removed in the latest cleanup pass — `PinningExample.java` is no longer part of the source tree. CPU pinning itself is still available to `ShardedExecutor` via its `(workerCount, enablePinning, coreMap)` constructor; benchmark runs do not currently expose it via YAML. To experiment with pinning, instantiate `ShardedExecutor` directly from a small ad-hoc driver.
