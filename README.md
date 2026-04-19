# TrailTest

YAML-driven Java benchmark comparing shared vs sharded queue executors.

## Build

```bash
mvn clean package
```

## Run

```bash
mvn -DskipTests exec:java -Dexec.mainClass=com.scott.BenchmarkMain -Dexec.args="--config=benchmarks.yaml"
```

Direct `java` classpath form:

```bash
CP=$(mvn -q -DincludeScope=runtime dependency:build-classpath -Dmdep.outputFile=/tmp/trail_cp.txt >/dev/null && cat /tmp/trail_cp.txt)
java -Xms1g -Xmx1g --enable-preview -cp "target/classes:$CP" com.scott.BenchmarkMain --config=benchmarks.yaml
```

## Config

- Main config file: `benchmarks.yaml`
- Run list: `runs[]`
- Workloads: `single` or `mix` (`generation: shuffled`)
- Profiling: `jcmd` command-line control via `profiling` section

## Output

- `results/<runName>/summary.txt`
- `results/<runName>/<runName>.jfr` (when profiling is enabled)
