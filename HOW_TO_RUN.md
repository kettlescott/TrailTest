# How to Run

## Prerequisites

- **Java 25** (or later) with preview feature support
- **Apache Maven 3.8+**

## Build

```bash
mvn clean package
```

## Run BenchmarkMain (YAML-Driven)

`BenchmarkMain` now runs from a YAML file. The primary CLI is:

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
  control: cli
  settings: profile
  start: beforeMeasurement
  stop: afterMeasurement
  filename: ${runName}.jfr
  startCommand: JFR.start name=${runName} settings=${settings} filename=${outputFile}
  stopCommand: JFR.stop name=${runName} filename=${outputFile}

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
- `results/<runName>/<runName>.jfr` (when `profiling.enabled: true`)

## JFR Notes

JFR is controlled by YAML (`profiling` section) using command-line `jcmd` commands (not JFR Java API).

Supported lifecycle values:

- `start: beforeMeasurement`
- `stop: afterMeasurement`

Command templates (optional, YAML configurable):

- `startCommand`
- `stopCommand`

Supported template variables:

- `${runName}`
- `${settings}`
- `${outputFile}`

Ensure `jcmd` is available on your `PATH` (comes with JDK).

## PinningExample (Optional)

CPU pinning demo remains separate from YAML benchmark runs:

```bash
mvn -DskipTests exec:java -Dexec.mainClass=com.scott.PinningExample
```

Direct `java` form:

```bash
java --enable-preview --enable-native-access=ALL-UNNAMED -cp target/classes com.scott.PinningExample
```
