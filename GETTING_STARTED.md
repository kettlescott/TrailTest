# Getting Started: Duration-Controlled MEMORY Benchmarks

## Overview

The duration-controlled MEMORY workload feature enables stable task execution across different queue depths. This guide walks you through running one of the 18 pre-configured benchmark scenarios.

---

## Prerequisites

- Java 11+
- Maven 3.6+
- 32 CPU cores recommended (tests use core pinning)

---

## Quick Start (5 minutes)

### 1. Build the Project
```bash
cd /Users/wangs100/dev/multiqueue/TrailTest

# Clean build
mvn clean -q package -DskipTests

# Expected output
# [INFO] BUILD SUCCESS
```

### 2. Run a Single Benchmark
```bash
# Run the 50µs benchmark with 1 shared queue
java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
  com.scott.BenchmarkMain \
  benchmarks_shared_queue_sweep/memory/50us/benchmarks_shared_mem_50us_q1.yaml
```

### 3. Inspect Results
```bash
# Results stored in: results/shared_mem_50us_q1/
cat results/shared_mem_50us_q1/summary.txt

# Expected key metrics
# calibration[0]=name=mem_50us, kind=MEMORY, targetMicros=50, mode=DURATION_CONTROLLED
# ...
# executionMs.avg=0.050          ← Should match 50µs = 0.050ms
# executionMs.p50=0.050
# executionMs.p95=0.055
```

---

## Running Different Configurations

### By Target Duration (Microseconds)

**50 Microsecond Tasks**
```bash
# Q1 (1 queue)
java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
  com.scott.BenchmarkMain \
  benchmarks_shared_queue_sweep/memory/50us/benchmarks_shared_mem_50us_q1.yaml

# Q32 (32 queues)
java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
  com.scott.BenchmarkMain \
  benchmarks_shared_queue_sweep/memory/50us/benchmarks_shared_mem_50us_q32.yaml
```

**100 Microsecond Tasks**
```bash
java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
  com.scott.BenchmarkMain \
  benchmarks_shared_queue_sweep/memory/100us/benchmarks_shared_mem_100us_q1.yaml
```

**300 Microsecond Tasks**
```bash
java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
  com.scott.BenchmarkMain \
  benchmarks_shared_queue_sweep/memory/300us/benchmarks_shared_mem_300us_q1.yaml
```

### By Queue Count

All combinations are available:
- Q1, Q2, Q4, Q8, Q16, Q32 for each target duration
- Total: **18 pre-configured scenarios**

Example: Compare queue-count impact on 50µs:
```bash
for q in q1 q2 q4 q8 q16 q32; do
  java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
    com.scott.BenchmarkMain \
    benchmarks_shared_queue_sweep/memory/50us/benchmarks_shared_mem_50us_${q}.yaml
done
```

---

## Understanding the Results

### Key Metrics in `summary.txt`

```
calibration[0]=name=mem_50us, kind=MEMORY, targetMicros=50, mode=DURATION_CONTROLLED
↑ Confirms duration-controlled mode is active

executionMs.avg=0.0502
↑ Average execution time (should ≈ targetMicros ÷ 1000 = 0.050)

executionMs.p50=0.0501
executionMs.p90=0.0530
executionMs.p95=0.0550
executionMs.p99=0.0620
↑ Percentile latencies (should be tightly clustered for duration-controlled)

queue.wait.avg=0.0012
↑ Average queue wait time

end2end.avg=0.0514
↑ Total end-to-end latency = queue wait + execution
```

### Interpreting Timing

For a **50µs target**:
- **Good**: `executionMs.avg ≈ 0.050 ± 0.005`
- **Excellent**: P95 within 20% of target (≤ 0.060)

For **300µs target**:
- **Good**: `executionMs.avg ≈ 0.300 ± 0.030`
- **Excellent**: P95 within 10% of target (≤ 0.330)

---

## Queue-Count Independence

One key benefit of duration-controlled mode is **stable execution time across queue counts**.

To verify this:

```bash
# Extract execution times
echo "Queue Count | Avg Execution Time (ms)"
for q in q1 q2 q4 q8 q16 q32; do
  file="results/shared_mem_50us_${q}/summary.txt"
  avg=$(grep "executionMs.avg" "$file" | awk '{print $NF}')
  echo "$q | $avg"
done
```

**Expected output**: All values should be approximately **0.050** (±0.005)

```
Queue Count | Avg Execution Time (ms)
q1          | 0.0501
q2          | 0.0499
q4          | 0.0502
q8          | 0.0501
q16         | 0.0500
q32         | 0.0502
```

If values vary significantly, the queue depth is affecting execution time (which should not happen with duration-controlled mode).

---

## Batch Configuration

All 18 configs use identical memory parameters:

```yaml
memory:
  accessPattern: random      # Random access defeats prefetcher
  bufferMB: 512              # 512 MiB buffer (DRAM-bound)
  writeBack: false           # Read-only (no store overhead)
```

### Adjusting for Different Workloads

To create a custom config with different parameters:

```yaml
global:
  workerCount: 32
  sharedQueueCount: 4        # Change: Q4
  maxInflight: 32
  warmupSeconds: 10
  measurementSeconds: 60

workloads:
  my_mem_50us:
    - kind: MEMORY
      targetMicros: 50       # ← Control duration
      ratio: 1.0
      memory:
        accessPattern: random  # or sequential
        bufferMB: 256          # or 64, 512, 1024, etc.
        writeBack: false       # or true for read-modify-write

runs:
  - name: my_custom_run
    mode: shared
    workload: my_mem_50us
```

---

## Running Tests

### Unit Tests Only
```bash
mvn test -Dtest=DurationControlledMemoryTest
```

### All Validation Tests (18 configs)
```bash
mvn test -Dtest=DurationControlledMemoryValidationTest
```

### Accuracy Tests
```bash
mvn test -Dtest=MemoryDurationAccuracyTest
```

---

## Output Directory Structure

Results are organized by run name:

```
results/
├── shared_mem_50us_q1/
│   ├── summary.txt                    ← Key metrics
│   ├── shared_mem_50us_q1.jfr         ← Flight Recording (profiling)
│   ├── shared_mem_50us_q1.txt         ← Raw latency samples
│   └── shared_mem_50us_q1.perf        ← perf output (if enabled)
├── shared_mem_50us_q2/
├── shared_mem_50us_q4/
...
```

### Viewing Results
```bash
# Summary
head -30 results/shared_mem_50us_q1/summary.txt

# Raw latency samples (microseconds)
head -100 results/shared_mem_50us_q1/shared_mem_50us_q1.txt

# Profiling (if JFR enabled)
jfr dump results/shared_mem_50us_q1/shared_mem_50us_q1.jfr
```

---

## Batch Runs (1-2 hours)

To run all 18 scenarios sequentially:

```bash
# Create a run script
cat > run_all_18.sh << 'EOF'
#!/bin/bash
set -e

PROJECT="/Users/wangs100/dev/multiqueue/TrailTest"
cd "$PROJECT"

echo "Running all 18 duration-controlled memory benchmarks..."
echo "Estimated time: 1-2 hours"
echo ""

for size in 50us 100us 300us; do
  for q in q1 q2 q4 q8 q16 q32; do
    config="benchmarks_shared_queue_sweep/memory/$size/benchmarks_shared_mem_${size}_${q}.yaml"
    echo "Running: $config"
    java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
      com.scott.BenchmarkMain "$config"
    echo "✅ Completed: $size / $q"
    echo ""
  done
done

echo "✅ All 18 benchmarks completed!"
EOF

chmod +x run_all_18.sh
./run_all_18.sh
```

---

## Comparing Results

### Compare Q1 vs Q32
```bash
echo "=== Q1 (1 queue) ==="
grep "executionMs" results/shared_mem_50us_q1/summary.txt

echo ""
echo "=== Q32 (32 queues) ==="
grep "executionMs" results/shared_mem_50us_q32/summary.txt
```

Both should show similar execution times (queue-independent).

### Compare Different Durations
```bash
echo "50µs:  $(grep executionMs.avg results/shared_mem_50us_q1/summary.txt)"
echo "100µs: $(grep executionMs.avg results/shared_mem_100us_q1/summary.txt)"
echo "300µs: $(grep executionMs.avg results/shared_mem_300us_q1/summary.txt)"
```

Should scale linearly (100µs ≈ 2× 50µs, 300µs ≈ 6× 50µs).

---

## Troubleshooting

### Issue: "Config file not found"
```bash
# Ensure you're running from project root
cd /Users/wangs100/dev/multiqueue/TrailTest

# Verify config exists
ls -la benchmarks_shared_queue_sweep/memory/50us/
```

### Issue: "Mode detection failed" or mode shows as FIXED_STEP
Check that `targetMicros` is set in the config:
```bash
grep targetMicros benchmarks_shared_queue_sweep/memory/50us/benchmarks_shared_mem_50us_q1.yaml
# Should output: targetMicros: 50
```

### Issue: Execution time doesn't match target
- Check `executionMs.avg` in results
- Variance up to ±10% is normal
- Larger variance may indicate:
  - CPU contention
  - Insufficient warmup
  - JIT compilation still in progress

### Issue: Build fails
```bash
# Clean and rebuild
mvn clean -q
mvn -q compile

# Check for errors
mvn compile 2>&1 | grep ERROR
```

---

## Advanced: Custom Configuration

To create your own duration-controlled config:

1. Copy a template:
```bash
cp benchmarks_shared_queue_sweep/memory/50us/benchmarks_shared_mem_50us_q1.yaml \
   my_custom_config.yaml
```

2. Edit parameters:
```yaml
global:
  workerCount: 32
  sharedQueueCount: 8        # Change queue count
  
workloads:
  mem_custom:
    - kind: MEMORY
      targetMicros: 75       # Change target duration
      memory:
        accessPattern: sequential  # Try different pattern
        bufferMB: 256              # Different buffer size
        writeBack: true            # Enable write-back
```

3. Run:
```bash
java -cp target/TrailSystem-1.0-SNAPSHOT-all.jar \
  com.scott.BenchmarkMain my_custom_config.yaml
```

---

## References

- **Full Documentation**: `DURATION_CONTROLLED_MEMORY.md`
- **Implementation Details**: `IMPLEMENTATION_NOTES.md`
- **Code Changes**: `CODE_CHANGES.md`
- **Architecture**: `ARCHITECTURE.md`

