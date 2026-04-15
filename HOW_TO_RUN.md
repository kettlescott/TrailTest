# How to Run

## Prerequisites

- **Java 25** (or later) with preview features support
- **Apache Maven 3.8+**

## Build

```bash
mvn clean compile
```

To package into a JAR:

```bash
mvn clean package
```

JAR equivalents below use:

`target/TrailSystem-1.0-SNAPSHOT.jar`

## Running the Benchmark (`BenchmarkMain`)

The main benchmark compares a **SharedExecutor** (single shared queue) against a **ShardedExecutor** (per-worker queues) using an **open-loop submission model** with Semaphore-gated backpressure.

### Submission Model

The benchmark uses a continuous open-loop producer instead of closed-loop batches:

- Tasks are submitted continuously for a fixed duration (warmup + measurement).
- A `Semaphore` with `maxInflight` permits provides backpressure — the producer blocks when too many tasks are in flight.
- Each completed task releases a permit, allowing the next submission.
- No idle gaps between batches, no artificial `Thread.park()` events.

### Modes

| Mode       | Description                                          |
|------------|------------------------------------------------------|
| `prepare`  | Calibrate once, print fixed config, then exit        |
| `shared`   | Run only the SharedExecutor                          |
| `sharded`  | Run only the ShardedExecutor                         |
| `compare`  | Run both sequentially with a side-by-side comparison |

> If no `--mode` is specified, `compare` is used by default.

### Quick Start

```bash
# Compare mode (default — both executors, side-by-side)
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --taskCount=<n>

# Shared only
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain --mode=shared --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=shared --taskCount=<n>

# Sharded only
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain --mode=sharded --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=sharded --taskCount=<n>
```

> Replace `<n>` with the number of tasks you want (e.g. `--taskCount=10000`).  
> Omit `--taskCount` entirely to use the default time-based mode (run until `--measurementSeconds` expires).

### Configuration Parameters

| Parameter              | Default               | Description                                  |
|------------------------|-----------------------|----------------------------------------------|
| `--mode=<mode>`        | `compare`             | Benchmark mode (prepare/shared/sharded/compare) |
| `--iterations=<n>`     | auto-calibrated       | CpuBoundWorkload iteration count             |
| `--warmupSeconds=<n>`  | `3`                   | Warmup phase duration in seconds             |
| `--measurementSeconds=<n>` | `10`              | Measurement phase duration in seconds        |
| `--workerCount=<n>`    | available processors  | Number of worker threads                     |
| `--maxInflight=<n>`    | `workerCount * 2`     | Max tasks in flight (Semaphore permits)       |
| `--seed=<n>`           | `3735928559`          | Base seed for deterministic workload          |
| `--taskCount=<n>`      | `0` (unlimited)       | Fixed number of tasks to submit per measurement phase; `0` = run until time deadline (default) |

### Fixed Task Count

Use `--taskCount` to control exactly how many tasks are submitted during the measurement phase,
instead of letting the benchmark run for a fixed duration.

```bash
# Run exactly 10000 tasks
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain --mode=shared --taskCount=10000
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=shared --taskCount=10000

# Run exactly 50000 tasks, both executors compared
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain --mode=compare --taskCount=50000
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=compare --taskCount=50000
```

### Heap Sizing

With short tasks (~100 μs), the benchmark produces millions of task objects per
phase.  **Always pass `-Xms1g -Xmx1g`** (or larger) to avoid OOM.  Setting
`-Xms` equal to `-Xmx` also prevents heap resizing during measurement, which
would add GC pauses that pollute latency results.

| Task target | 10 s measurement × 16 workers | Recommended heap |
|-------------|-------------------------------|------------------|
| ~4 ms       | ~40 K tasks                   | default (256 MB) |
| ~100 μs     | ~1.6 M tasks                  | `-Xmx1g`         |
| ~10 μs      | ~16 M tasks                   | `-Xmx4g`         |

### Reproducible Cross-JVM Workloads

For clean JFR recordings, run each executor in a **separate JVM process**.
To guarantee identical workloads across runs:

```bash
# Step 1 — calibrate once:
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain --mode=prepare
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=prepare

# Step 2 — copy the printed values into separate runs:
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain \
     --mode=shared --iterations=450582 --warmupSeconds=3 \
     --measurementSeconds=10 --seed=3735928559 --workerCount=16 --maxInflight=32 --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain \
     --mode=shared --iterations=450582 --warmupSeconds=3 \
     --measurementSeconds=10 --seed=3735928559 --workerCount=16 --maxInflight=32 --taskCount=<n>

java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain \
     --mode=sharded --iterations=450582 --warmupSeconds=3 \
     --measurementSeconds=10 --seed=3735928559 --workerCount=16 --maxInflight=32 --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain \
     --mode=sharded --iterations=450582 --warmupSeconds=3 \
     --measurementSeconds=10 --seed=3735928559 --workerCount=16 --maxInflight=32 --taskCount=<n>
```

On **Windows / PowerShell** (use backtick for line continuation):

```powershell
java -Xms1g -Xmx1g --enable-preview -cp target/classes com.scott.BenchmarkMain `
     --mode=shared --iterations=450582 --warmupSeconds=3 `
     --measurementSeconds=10 --seed=3735928559 --workerCount=16 --maxInflight=32 --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain `
     --mode=shared --iterations=450582 --warmupSeconds=3 `
     --measurementSeconds=10 --seed=3735928559 --workerCount=16 --maxInflight=32 --taskCount=<n>
```

### Debug Mode

Enable extra consistency checks, bounds verification, and diagnostic logging by setting the `benchmark.debug` system property:

```bash
java -Xms1g -Xmx1g -Dbenchmark.debug=true --enable-preview -cp target/classes com.scott.BenchmarkMain --taskCount=<n>
java -Xms1g -Xmx1g -Dbenchmark.debug=true --enable-preview -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --taskCount=<n>
```

When debug mode is **off** (default), all debug code paths are dead-code-eliminated by the JIT compiler, producing zero overhead on the hot path.

---

## JFR Recording

Use JVM-level JFR flags to record the benchmark. The open-loop model is designed so the benchmark runs long enough for JFR to produce meaningful data without artificial batch gaps.

### Quick Start

```bash
# Record shared-only run
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=shared.jfr,duration=60s \
     -cp target/classes com.scott.BenchmarkMain --mode=shared --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=shared.jfr,duration=60s \
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=shared --taskCount=<n>

# Record sharded-only run
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=sharded.jfr,duration=60s \
     -cp target/classes com.scott.BenchmarkMain --mode=sharded --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=sharded.jfr,duration=60s \
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=sharded --taskCount=<n>
```

On **Windows / PowerShell**:

```powershell
java -Xms1g -Xmx1g --enable-preview `
     -XX:StartFlightRecording=filename=shared.jfr,duration=60s `
     -cp target/classes com.scott.BenchmarkMain --mode=shared --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview `
     -XX:StartFlightRecording=filename=shared.jfr,duration=60s `
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=shared --taskCount=<n>

java -Xms1g -Xmx1g --enable-preview `
     -XX:StartFlightRecording=filename=sharded.jfr,duration=60s `
     -cp target/classes com.scott.BenchmarkMain --mode=sharded --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview `
     -XX:StartFlightRecording=filename=sharded.jfr,duration=60s `
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=sharded --taskCount=<n>
```

### Common JFR Options

| Option                  | Description                                                      |
|-------------------------|------------------------------------------------------------------|
| `filename=<path>`       | Output file path (e.g. `shared.jfr`)                             |
| `duration=<time>`       | Recording duration (e.g. `60s`, `5m`)                            |
| `settings=default`      | Low-overhead recording (good for production-like profiling)      |
| `settings=profile`      | Higher detail — method profiling, allocation sampling, etc.      |
| `maxsize=<size>`        | Maximum recording file size (e.g. `200m`)                        |
| `dumponexit=true`       | Dump recording when the JVM exits (useful if duration is unknown)|

### High-Detail Profiling

```bash
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=shared-profile.jfr,settings=profile,duration=60s \
     -cp target/classes com.scott.BenchmarkMain --mode=shared --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=shared-profile.jfr,settings=profile,duration=60s \
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=shared --taskCount=<n>
```

### Recording the Full Run (no fixed duration)

If you don't know how long the benchmark will take, use `dumponexit=true` so
JFR writes the file when the JVM shuts down:

```bash
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=shared.jfr,dumponexit=true \
     -cp target/classes com.scott.BenchmarkMain --mode=shared --taskCount=<n>
java -Xms1g -Xmx1g --enable-preview \
     -XX:StartFlightRecording=filename=shared.jfr,dumponexit=true \
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.BenchmarkMain --mode=shared --taskCount=<n>
```

### Viewing Results

Open the `.jfr` file in **JDK Mission Control (JMC)**:

```bash
jmc -open shared.jfr
```

Or use the CLI tools bundled with the JDK:

```bash
jfr summary shared.jfr
jfr print shared.jfr | head -100
jfr print --events jdk.CPULoad shared.jfr
```

> **Tip:** Run each executor in a **separate JVM process** (`--mode=shared` /
> `--mode=sharded`) for the cleanest JFR recordings — no cross-contamination
> of JIT compilation history, GC state, or thread pools.

---

## Running the Pinning Example (`PinningExample`)

This demonstrates CPU core pinning using the Foreign Function & Memory (FFM) API. It runs an A/B comparison:

- **Mode A** — ShardedExecutor without pinning (default OS scheduling)
- **Mode B** — ShardedExecutor with per-worker core pinning

### Required JVM Flags

| Flag                                  | Purpose                                                                                 |
|---------------------------------------|-----------------------------------------------------------------------------------------|
| `--enable-preview`                    | Unlocks preview FFM classes (`java.lang.foreign.*`)                                     |
| `--enable-native-access=ALL-UNNAMED`  | Permits calling native functions via `Linker.downcallHandle` (e.g., `sched_setaffinity`) |

### Command

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -cp target/classes com.scott.PinningExample
java --enable-preview --enable-native-access=ALL-UNNAMED \
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.PinningExample
```

On **Windows / PowerShell**:

```powershell
java --enable-preview --enable-native-access=ALL-UNNAMED `
     -cp target/classes com.scott.PinningExample
java --enable-preview --enable-native-access=ALL-UNNAMED `
     -cp target/TrailSystem-1.0-SNAPSHOT.jar com.scott.PinningExample
```

### Platform Notes

- **Linux** — Full pinning support. Verify pinning while the benchmark runs:
  ```bash
  ps -T -p $(pgrep -f PinningExample) | grep ShardedWorker
  taskset -p <tid>
  ```
- **Windows / macOS** — The non-pinned mode works normally. The pinned mode will print a warning and fall back to unpinned scheduling.

---

## Running via Maven

You can also run the `PinningExample` (configured as the default main class) with:

```bash
mvn compile exec:java
```

To run `BenchmarkMain` instead:

```bash
MAVEN_OPTS="-Xms1g -Xmx1g" mvn compile exec:java -Dexec.mainClass="com.scott.BenchmarkMain" -Dexec.args="--mode=compare --taskCount=<n>"
```
