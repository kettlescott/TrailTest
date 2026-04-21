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
  workerCount: 16
  maxInflight: 32
  seed: 3735928559
  targetTaskNanos: 100000
  warmupSeconds: 3
  measurementSeconds: 10
  taskCount: 0

workloads:
  short_only:
    kind: single
    type: short

  mixed_60_30_10:
    kind: mix
    distribution:
      short: 60
      medium: 30
      long: 10
    generation: shuffled

profiling:
  enabled: true
  control: api            # 'api' (in-process, recommended) | 'cli' (jcmd)
  settings: profile       # JFR preset: 'default' | 'profile' | path to a .jfc
  start: beforeMeasurement
  stop:  afterMeasurement
  filename: ${runName}.jfr
  # Quiet period between profiler start and measurement-window open.
  # Lets perf install kernel events and JFR flush its first chunk before
  # benchmark samples begin. Default: 200 ms.
  startupQuietPeriodMs: 200
  # Grace period between measurement-window close and profiler stop.
  # Lets perf's per-CPU ring buffers drain to disk before SIGINT. Default: 100 ms.
  shutdownFlushMs: 100
  # Only used when control: cli
  startCommand: JFR.start name=${runName} settings=${settings} filename=${outputFile}
  stopCommand:  JFR.stop  name=${runName} filename=${outputFile}

  # Optional Linux perf integration (disabled by default)
  perf:
    enabled: false
    binary: perf
    frequency: 99          # Hz; raise (e.g. 999) for deeper CPU profiles
    clock: monotonic       # aligns perf samples with JFR / System.nanoTime
    callGraph: none        # none | fp | dwarf | lbr
    mmapPages: 512
    extraArgs: []
    filename: ${runName}.perf.data

runs:
  - name: shared_short
    mode: shared
    workload: short_only

  - name: sharded_mix
    mode: sharded
    workload: mixed_60_30_10
```

### Workloads

- `single`: fixed type (`short|medium|long`)
- `mix`: percentage distribution that must sum to `100`
- `generation`: currently `shuffled`

### Runs

- `name`: output folder name
- `mode`: `shared` or `sharded`
- `workload`: key from `workloads`

## Output Files

For each run in `runs:`:

- `results/<runName>/summary.txt`
- `results/<runName>/run.json`                  — reproducibility metadata (host, JVM, OS, config, outputs)
- `results/<runName>/<runName>.jfr`             — when `profiling.enabled: true`
- `results/<runName>/<runName>.perf.data`       — when `profiling.perf.enabled: true`
- `results/<runName>/<runName>.perf.log`        — `perf record` stderr / launch diagnostics

---

## JFR

### Control modes

| `control:` | What happens | When to use |
|---|---|---|
| **`api`** *(default, recommended)* | In-process `jdk.jfr.Recording` API. No external binary. Works on JRE and slim containers. Lower start/stop latency; `startCommand` / `stopCommand` are ignored. | Default for this project. |
| `cli` | Shells out to `jcmd` (resolved from `$JAVA_HOME/bin/jcmd` first, then `PATH`). Requires a full JDK. | Only if you need `jcmd` semantics specifically, or are reproducing existing `jcmd`-based tooling. |

### Lifecycle (both modes)

- `start: beforeMeasurement` — recording opens immediately **after warmup**, before the measurement phase.
- `stop:  afterMeasurement`  — recording closes immediately **after** the measurement phase **and before dispatcher shutdown**, so the 10–30 s drain/cleanup is never part of the recording.

### Anchor events

At the instant the recording opens and closes, the session emits one JFR event of type `com.scott.MeasurementAnchor` carrying:

- `phase` — `"start"` or `"stop"`
- `runId` — matches the `name:` in `runs:`
- `benchmarkMode` — `SHARED` | `SHARDED`
- `workloadType` — workload key from YAML
- `workerCount`
- `note`
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
| Recording seems to include dispatcher shutdown | Old code path | Rebuild; current `BenchmarkMain` closes profiling before `dispatcher.shutdown()`. |

---

## Profiling Architecture

All profiling is driven by a single `ProfilingSession` (package `com.scott.profiling`). The benchmark driver only calls lifecycle methods; no back-end-specific code leaks into `BenchmarkMain` or the hot path.

```
BenchmarkMain
    │
    ▼
ProfilingSession  ── wraps ──►  Profiler (interface)
                                  │
                                  ├─► JfrProfiler       (API | CLI)
                                  ├─► PerfProfiler      (Linux only)
                                  └─► CompositeProfiler (ordered list)
```

Lifecycle, in order:

1. `session.start()` — starts each profiler in insertion order; rolls back in reverse order on any failure.
2. `session.beforeMeasurement()` — sleeps `startupQuietPeriodMs` so perf kernel-event install and JFR first-chunk flush don't bleed into the measurement window.
3. `session.markMeasurementStart(ctx)` — emits one `com.scott.MeasurementAnchor` JFR event (phase=`"start"`).
4. Measurement loop runs. **No profiling code on the hot path.**
5. `session.markMeasurementStop(ctx)` — emits the matching phase=`"stop"` anchor.
6. `session.stop()` — optional `shutdownFlushMs` sleep, then stops profilers in reverse order (perf last, so its timeline brackets JFR's).

Profilers stop **before** `dispatcher.shutdown()`, so 10–30 s of drain/cleanup is never attributed to the benchmark.

### `run.json`

Written once per run, outside the measurement window. Used for reproducibility:

```json
{
  "runId": "shared_short",
  "timestamp": "2026-04-21T12:34:56Z",
  "hostname": "...",
  "os.name": "Linux",
  "jvm.version": "25",
  "cpu.cores": 16,
  "mode": "SHARED",
  "workload": "short_only",
  "workerCount": 16,
  "throughputPerSecond": "881269.0",
  "perfEnabled": true,
  "perfFrequency": 99,
  "perfCallGraph": "dwarf",
  "perfClock": "monotonic",
  "jfrOutput": "results/shared_short/shared_short.jfr",
  "perfOutput": "results/shared_short/shared_short.perf.data"
}
```

---

## PinningExample (Optional)

CPU pinning demo remains separate from YAML benchmark runs:

```bash
mvn -DskipTests exec:java -Dexec.mainClass=com.scott.PinningExample
```

Direct `java` form:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED -cp target/classes com.scott.PinningExample
```
