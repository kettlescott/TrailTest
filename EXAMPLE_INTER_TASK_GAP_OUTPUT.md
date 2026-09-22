# Inter-Task Gap Diagnostics - Example Output

This document shows what the diagnostics output looks like for typical scenarios.

## Example 1: CPU-Bound Workload (100 iterations, Q8 Shared Queue)

### YAML Configuration
```yaml
diagnostics:
  enabled: true
  perWorkerLatency: true
  perWorkerSlowThresholdMillis: 10

workloads:
  - name: cpu_100
    entries:
      - name: cpu
        kind: cpu
        cpuIterations: 100
        ratio: 1.0

runs:
  - name: cpu_100_q8
    mode: shared
    workload: cpu_100
    taskCount: 39500000
    workerCount: 32
    queue_sweep:
      shared_queue_count: 8
```

### Output

```
╔════════════════════════════════════════════════════════════════════════════════╗
║             INTER-TASK GAP DIAGNOSTICS (Lightweight Analysis)                ║
╚════════════════════════════════════════════════════════════════════════════════╝

─── Per-Worker Gap Metrics ───────────────────────────────────────────────────────

Worker-0 (measurement tasks: 1,234,375):
  Execution Time:     avg=    65.23 μs  max=     2.34 ms
  Inter-Task Gap:     avg=     8.45 μs  p50=     5.12 μs  p95=    15.67 μs  p99=    32.45 μs  max=   234.56 ms
  Poll/Dequeue Time:  avg=     2.34 μs  p50=     1.12 μs  p95=     4.56 μs  p99=     8.23 μs  max=    45.67 ms
  Queue State:        empty=23.4%  non-empty=76.6%  (avg empty wait: 0.55 μs)

Worker-1 (measurement tasks: 1,234,375):
  Execution Time:     avg=    64.87 μs  max=     2.12 ms
  Inter-Task Gap:     avg=     8.67 μs  p50=     5.34 μs  p95=    16.12 μs  p99=    33.21 μs  max=   198.76 ms
  Poll/Dequeue Time:  avg=     2.41 μs  p50=     1.15 μs  p95=     4.78 μs  p99=     8.56 μs  max=    42.34 mm
  Queue State:        empty=22.1%  non-empty=77.9%  (avg empty wait: 0.53 μs)

Worker-2 (measurement tasks: 1,234,375):
  Execution Time:     avg=    65.45 μs  max=     2.56 ms
  Inter-Task Gap:     avg=     8.23 μs  p50=     4.98 μs  p95=    15.34 μs  p99=    31.67 μs  max=   267.89 ms
  Poll/Dequeue Time:  avg=     2.29 μs  p50=     1.09 μs  p95=     4.34 μs  p99=     7.89 μs  max=    38.92 ms
  Queue State:        empty=24.7%  non-empty=75.3%  (avg empty wait: 0.57 μs)

[... workers 3-31 omitted ...]

╔ Aggregate (all 32 workers) ──────────────────────────────────────────────────────╗
║  Total measurement tasks: 39,500,000
║  Avg execution time:      65.23 μs  (88.5% of wall time)
║  Avg inter-task gap:       8.45 μs  (11.5% of wall time)
║  Avg poll time:            2.34 μs  (2.8% of wall time)
║  Avg empty queue wait:     0.55 μs  (23.5% of poll time)
╚═══════════════════════════════════════════════════════════════════════════════════╝

─── Key Insights ─────────────────────────────────────────────────────────────────

  Inter-task gap = time between finishing one task and starting the next
  └─ Poll Time:      time acquiring task from queue
     ├─ Empty Queue: waiting for tasks to arrive
     └─ Non-Empty:   dequeuing when queue already had tasks

  High inter-task gap indicates:
    • Contention on shared queue (compare Q1 vs Q32)
    • Unbalanced workload distribution (memory vs cpu)
    • Empty queue waits (insufficient task submission rate)
```

### Analysis
- **Execution dominates**: 65 μs execution vs 8 μs gap → CPU time is primary
- **Low empty queue %**: 23% → sustained task submission rate is good
- **Short poll time**: 2.3 μs → queue operations efficient
- **Consistent across workers**: All workers show similar patterns
- **Low p99 gaps**: 32 μs at p99 → few scheduling anomalies
- **Conclusion**: Queue is **not a bottleneck** for CPU workload

---

## Example 2: Memory-Bound Workload (100 iterations, Q8 Shared Queue)

### YAML Configuration
```yaml
diagnostics:
  enabled: true
  perWorkerLatency: true

workloads:
  - name: mem_100
    entries:
      - name: memory
        kind: memory
        memorySteps: 100000
        bufferMB: 256
        accessPattern: random
        ratio: 1.0

runs:
  - name: mem_100_q8
    mode: shared
    workload: mem_100
    taskCount: 39500000
    workerCount: 32
    queue_sweep:
      shared_queue_count: 8
```

### Output

```
╔════════════════════════════════════════════════════════════════════════════════╗
║             INTER-TASK GAP DIAGNOSTICS (Lightweight Analysis)                ║
╚════════════════════════════════════════════════════════════════════════════════╝

─── Per-Worker Gap Metrics ───────────────────────────────────────────────────────

Worker-0 (measurement tasks: 1,234,375):
  Execution Time:     avg=  1234.56 μs  max=    12.34 ms
  Inter-Task Gap:     avg=    45.67 μs  p50=    23.45 μs  p95=   125.67 μs  p99=   234.56 μs  max=  1234.56 ms
  Poll/Dequeue Time:  avg=     2.12 μs  p50=     1.05 μs  p95=     3.45 μs  p99=     6.78 μs  max=    28.90 ms
  Queue State:        empty=67.8%  non-empty=32.2%  (avg empty wait: 30.98 μs)

Worker-1 (measurement tasks: 1,234,375):
  Execution Time:     avg=  1256.78 μs  max=    11.67 mm
  Inter-Task Gap:     avg=    41.23 μs  p50=    21.12 μs  p95=   112.34 μs  p99=   218.90 μs  max=  1156.78 mm
  Poll/Dequeue Time:  avg=     2.08 μs  p50=     1.02 μs  p95=     3.34 μs  p99=     6.45 μs  max=    26.78 mm
  Queue State:        empty=71.2%  non-empty=28.8%  (avg empty wait: 29.34 μs)

Worker-2 (measurement tasks: 1,234,375):
  Execution Time:     avg=  1245.34 μs  max=    12.89 mm
  Inter-Task Gap:     avg=    48.90 μs  p50=    25.67 μs  p95=   134.56 μs  p99=   245.67 μs  max=  1289.34 mm
  Poll/Dequeue Time:  avg=     2.15 μs  p50=     1.07 μs  p95=     3.56 μs  p99=     6.89 μs  max=    31.23 mm
  Queue State:        empty=64.5%  non-empty=35.5%  (avg empty wait: 31.56 μs)

[... workers 3-31 omitted ...]

╔ Aggregate (all 32 workers) ──────────────────────────────────────────────────────╗
║  Total measurement tasks: 39,500,000
║  Avg execution time:    1245.34 μs  (96.4% of wall time)
║  Avg inter-task gap:      45.67 μs  (3.6% of wall time)
║  Avg poll time:            2.12 μs  (0.2% of wall time)
║  Avg empty queue wait:    30.98 μs  (68.2% of poll time)
╚═══════════════════════════════════════════════════════════════════════════════════╝

─── Key Insights ─────────────────────────────────────────────────────────────────

  Inter-task gap = time between finishing one task and starting the next
  ...
```

### Analysis
- **Execution dominates**: 1245 μs execution vs 46 μs gap → memory time is primary
- **High empty queue %**: 68% → queue frequently empty while waiting for task completion
  - This is **normal for memory workloads** (DRAM latency is the limiter)
  - **Not a queue bottleneck** - the queue is fast, DRAM is slow
- **Short poll time**: 2.1 μs → queue operations still efficient
- **Consistent across workers**: Load is well-balanced
- **Higher p99 gaps**: 235 μs → occasional scheduling delays (but still tiny vs execution)
- **Conclusion**: Queue is **not a bottleneck**; DRAM bandwidth is the limiter

---

## Example 3: Comparison - Q1 vs Q32 (Memory Workload)

### Memory 100 with Q1 (Single Shared Queue)

```
Worker-0 (measurement tasks: 39,500,000):  [all workers feed single queue]
  Execution Time:     avg=  1245.34 μs  max=    12.34 mm
  Inter-Task Gap:     avg=   234.56 μs  p50=    123.45 μs  p95=   567.89 μs  p99=  1234.56 μs  max=  5678.90 mm
  Poll/Dequeue Time:  avg=    12.34 μs  p50=     6.78 μs  p95=    28.90 μs  p99=    56.78 μs  max=   234.56 mm
  Queue State:        empty=45.2%  non-empty=54.8%  (avg empty wait: 5.56 μs)

╔ Aggregate (all 32 workers) ──────────────────────────────────────────────────────╗
║  Avg execution time:    1245.34 μs  (84.1% of wall time)
║  Avg inter-task gap:     234.56 μs  (15.9% of wall time)
║  Avg poll time:           12.34 μs  (0.8% of wall time)
║  Avg empty queue wait:     5.56 μs  (45.0% of poll time)
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

### Memory 100 with Q32 (32 Distributed Queues)

```
Worker-0 (measurement tasks: 1,234,375):  [workers distributed across 32 queues]
  Execution Time:     avg=  1245.34 μs  max=    12.34 mm
  Inter-Task Gap:     avg=    45.67 μs  p50=    23.45 μs  p95=   125.67 μs  p99=   234.56 μs  max=  1234.56 mm
  Poll/Dequeue Time:  avg=     2.12 μs  p50=     1.05 μs  p95=     3.45 μs  p99=     6.78 μs  max=    28.90 mm
  Queue State:        empty=67.8%  non-empty=32.2%  (avg empty wait: 30.98 μs)

╔ Aggregate (all 32 workers) ──────────────────────────────────────────────────────╗
║  Avg execution time:    1245.34 μs  (96.4% of wall time)
║  Avg inter-task gap:      45.67 μs  (3.6% of wall time)
║  Avg poll time:            2.12 μs  (0.2% of wall time)
║  Avg empty queue wait:    30.98 μs  (68.2% of poll time)
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

### Comparison Table

| Metric | Q1 (Single Queue) | Q32 (Distributed) | Delta | Analysis |
|--------|-------------------|-------------------|-------|----------|
| **Poll Time (avg)** | 12.34 μs | 2.12 μs | **-83%** | Lock contention in Q1 |
| **Gap (avg)** | 234.56 μs | 45.67 μs | **-80%** | Q1 gap inflated by lock |
| **Poll p99** | 56.78 μs | 6.78 μs | **-88%** | Q32 much more predictable |
| **Empty %** | 45% | 68% | +23% | Q32 has more available capacity |
| **Execution %** | 84% | 96% | +12% | Q32 loses less time to queueing |

### Analysis
- **Lock contention in Q1**: Poll time 12 μs vs 2 μs = **10 μs per-task overhead** from lock contention
- **Gap improvement**: 180 μs reduction per task × 39.5M tasks = **~7 billion μs = 7 seconds saved** by going from Q1 to Q32
- **Consistency**: Q32 has 8-10x lower poll time variance (p99 56 vs 7 μs)
- **Conclusion**: Distributed queues dramatically reduce lock contention cost

---

## Example 4: Diagnosing Queue Starvation

### Scenario: Insufficient Task Submission Rate

```
Worker-0 (measurement tasks: 123,456):  [task submission lagging]
  Execution Time:     avg=    50.12 μs  max=     2.34 ms
  Inter-Task Gap:     avg=   567.89 μs  p50=   234.56 μs  p95=  1234.56 μs  p99= 5678.90 μs  max= 45678.90 mm
  Poll/Dequeue Time:  avg=   432.10 μs  p50=   210.56 μs  p95=  1045.67 μs  p99= 4567.89 μs  max= 34567.89 mm
  Queue State:        empty=94.2%  non-empty=5.8%  (avg empty wait: 407.53 μs)

╔ Aggregate (all 32 workers) ──────────────────────────────────────────────────────╗
║  Avg execution time:      50.12 μs  (8.1% of wall time)
║  Avg inter-task gap:     567.89 μs  (91.9% of wall time)
║  Avg poll time:          432.10 μs  (76.0% of wall time)
║  Avg empty queue wait:   407.53 μs  (94.3% of poll time)
╚═══════════════════════════════════════════════════════════════════════════════════╝
```

### Red Flags
- ❌ **Gap > Execution**: 568 vs 50 μs → workers spending 10x more time waiting than working
- ❌ **Empty % > 90%**: Queue is starving, tasks trickling in
- ❌ **Poll p99 > 4ms**: Waiting can be very long (indicates task stalls)

### Action Items
1. Increase task submission parallelism
2. Check for bottleneck in task generation
3. Increase batch submission size
4. Monitor network latency (if distributed)

---

## Key Takeaways

1. **Gap size ≠ Queue bottleneck**
   - High gap in memory workload (68% empty queue) is **normal** - DRAM is the bottleneck
   - High gap in Q1 (lock contention) is **fixable** - use Q32

2. **Look at components separately**
   - Execution time: workload characteristic
   - Poll time: queue implementation efficiency
   - Empty %: task submission rate

3. **Use Q1 vs Q32 comparison**
   - Delta reveals lock contention cost
   - Expect 80-90% reduction in poll time with Q32

4. **Red flags that need action**
   - Empty % > 85% → task submission starving
   - Poll time > 10 μs (Q1) or > 5 μs (Q32) → investigate lock contention
   - Poll p99 > 1ms → something is very wrong

