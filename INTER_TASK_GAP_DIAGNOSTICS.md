# Inter-Task Gap Diagnostics - Implementation Guide

## Overview

Lightweight diagnostics instrumentation added to explain why `QueueWait` remains high even when task execution time is stable. The system tracks time spent between finishing one task and starting the next, with minimal overhead.

## What Gets Measured

### 1. **Inter-Task Gap** (time between tasks)
- **Gap start**: When previous task finishes
- **Gap end**: When current task starts
- **Includes**: All time spent outside of task execution (polling, waiting, scheduling)

### 2. **Poll/Dequeue Latency** 
- Time from initiating queue poll until task is obtained
- Distinguishes between:
  - **Empty queue wait**: Time blocking when queue was empty
  - **Non-empty dequeue**: Time retrieving from populated queue

### 3. **Queue State Analysis**
- Queue depth samples during measurement window
- Non-empty ratio (fraction of time queue had pending tasks)
- Per-queue enqueue/dequeue counts

### 4. **Per-Worker Metrics**
```
- tasksProcessed:        measurement tasks only
- executionTime:         avg/p50/p95/p99/max
- interTaskGapTime:      avg/p50/p95/p99/max
- pollTime:              avg/p50/p95/p99/max
- emptyQueueTime:        avg and ratio
- nonEmptyQueueTime:     avg and ratio
```

## Architecture

### Core Classes

#### `InterTaskGapDiagnostics.java` (Package-Private)
Collects per-worker gap metrics. Owned by each worker (ShardedWorker) or shared worker pool (SharedExecutor).

```java
// Always collected
long tasksProcessed;              // measurement tasks
long interTaskGapTimeSumNs;       // sum of gaps
long pollTimeSumNs;               // sum of dequeue times
long emptyQueueTimeSumNs;         // sum of empty waits
long nonEmptyQueueTimeSumNs;      // sum of non-empty dequeues

// Optional histograms (when perWorkerLatency enabled)
long[] interTaskGapHistogram;     // raw gap values
long[] pollTimeHistogram;         // raw poll times
```

#### `QueueStatistics.java` (Package-Private)
Per-queue statistics (currently stub; extends in future for shared queue sampling).

```java
long enqueuedCount;               // total enqueued tasks
long dequeuedCount;               // total dequeued tasks
long avgDepth;                    // average queue depth
long maxDepth;                    // maximum queue depth
double nonEmptyRatio;             // % of time queue non-empty
```

#### `InterTaskGapReporter.java`
Formats and prints per-worker and aggregate diagnostics.

### Instrumentation Points

#### **ShardedWorker.java** (Lines ~305-345)
Instruments the main worker loop:

```java
long previousTaskFinishNs = System.nanoTime();
while (true) {
    long pollStartNs = System.nanoTime();
    long interTaskGapNs = pollStartNs - previousTaskFinishNs;
    
    // ---- dequeue ----
    task = effectiveQueue.take();
    long pollFinishNs = System.nanoTime();
    long pollNs = pollFinishNs - pollStartNs;
    
    // ---- heuristic: estimate empty vs non-empty time ----
    long emptyQueueNs = (pollNs > 100) ? (long)(pollNs * 0.7) : 0;
    long nonEmptyQueueNs = pollNs - emptyQueueNs;
    
    // ---- execute task ----
    long taskExecStartNs = System.nanoTime();
    task.run();
    long taskExecFinishNs = System.nanoTime();
    long executionNs = taskExecFinishNs - taskExecStartNs;
    
    // ---- record if measurement task ----
    if (stats != null && task.isMeasurement()) {
        stats.recordInterTaskGap(interTaskGapNs, pollNs, 
                                emptyQueueNs, nonEmptyQueueNs, 
                                executionNs);
    }
    
    previousTaskFinishNs = taskExecFinishNs;
}
```

**Overhead per task**: ~4 `System.nanoTime()` calls + simple arithmetic (~50 ns on modern x86)

#### **SharedExecutor.java** (Lines ~140-190)
Instruments ThreadPoolExecutor hooks:

```java
beforeExecute(Thread t, Runnable r) {
    long pollStartNs = System.nanoTime();
    if (r instanceof Task task && task.isMeasurement()) {
        Long prevFinishNs = PREVIOUS_TASK_FINISH_NS.get();
        if (prevFinishNs != null) {
            long gapNs = pollStartNs - prevFinishNs;
            // Store metadata for afterExecute
            task._gapStartNs = pollStartNs;
            task._prevFinishNs = prevFinishNs;
            // Heuristic: large gaps suggest empty queue wait
            task._emptyQueueNsEstimate = (gapNs > 1000) 
                ? (long)(gapNs * 0.5) : 0;
            task._nonEmptyQueueNsEstimate = gapNs 
                - task._emptyQueueNsEstimate;
        }
    }
}

afterExecute(Runnable r, Throwable th) {
    long finishNs = System.nanoTime();
    if (r instanceof Task task && task._prevFinishNs > 0) {
        statsF.recordInterTaskGap(
            task._gapStartNs - task._prevFinishNs,
            task._gapStartNs - task._prevFinishNs,  // approximation
            task._emptyQueueNsEstimate,
            task._nonEmptyQueueNsEstimate,
            task.executionTimeNanos());
    }
    PREVIOUS_TASK_FINISH_NS.set(finishNs);
}
```

**Overhead per task**: ~2 `System.nanoTime()` calls + simple assignment (~30 ns)

### Data Flow

```
ShardedWorker / SharedExecutor
    ↓
    record gap metrics via WorkerStats.recordInterTaskGap()
    ↓
WorkerStats._gapDiags (InterTaskGapDiagnostics)
    ├─ aggregate sum/max counters (always)
    └─ histogram (when perWorkerLatency=true)
    ↓
BenchmarkMain.executeRun() [after measurement completes]
    ↓
    InterTaskGapReporter.printDiagnostics()
    ↓
    console output
```

## Output Format

```
╔════════════════════════════════════════════════════════════════════════════════╗
║             INTER-TASK GAP DIAGNOSTICS (Lightweight Analysis)                ║
╚════════════════════════════════════════════════════════════════════════════════╝

─── Per-Worker Gap Metrics ───────────────────────────────────────────────────────

Worker-0 (measurement tasks: 1,234,567):
  Execution Time:     avg=   50.12 μs  max=    2.34 ms
  Inter-Task Gap:     avg=   12.34 μs  p50=    8.56 μs  p95=   25.67 μs  p99=   45.23 μs  max=  123.45 ms
  Poll/Dequeue Time:  avg=    2.34 μs  p50=    1.23 μs  p95=    5.67 μs  p99=   12.34 μs  max=   45.67 ms
  Queue State:        empty=45.2%  non-empty=54.8%  (avg empty wait: 1.05 μs)

Worker-1 (measurement tasks: 1,234,567):
  ...

╔ Aggregate (all 32 workers) ──────────────────────────────────────────────────────╗
║  Total measurement tasks: 39,506,144
║  Avg execution time:      50.12 μs  (80.3% of wall time)
║  Avg inter-task gap:      12.34 μs  (19.7% of wall time)
║  Avg poll time:            2.34 μs  (3.7% of wall time)
║  Avg empty queue wait:     1.05 μs  (44.9% of poll time)
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

## Interpretation Guide

### Comparing CPU100 Q8 vs MEM100 Q8

**Expected differences**:

| Metric | CPU Workload | Memory Workload |
|--------|-------------|-----------------|
| **Execution Time** | ~50-100 μs (shorter, cache-friendly) | ~500-2000 μs (longer, DRAM access) |
| **Poll Time** | ~1-3 μs | ~1-3 μs (same queue) |
| **Empty Queue %** | Low (rapid task submission) | High (slower execution → queue starves) |
| **Gap/Exec Ratio** | ~10-20% | ~2-5% (execution dominates) |

**Interpretation**:
- **High inter-task gap in CPU workload** → queue contention or insufficient task density
- **High empty queue % in memory workload** → throttled by memory latency, tasks complete slowly
- **Consistent poll time** → queue implementation is stable

### Diagnosing QueueWait Issues

**If QueueWait is high but execution time is normal**:

1. **Check empty queue ratio**
   - If `empty% > 50%`: queue is starving, investigate task submission rate
   - If `empty% < 20%`: task waiting on contended locks, check for lock contention

2. **Check poll time vs total gap**
   - If `poll% > 50% of gap`: dequeue itself is slow (lock contention in shared queue)
   - If `poll% < 10% of gap`: other scheduling/OS delays

3. **Compare Q1 vs Q32**
   - Q1: single queue, all contention measured in one place
   - Q32: distributed queues, gaps should be much smaller if load is balanced
   - Large difference → workload imbalance or core affinity issues

## Configuration

Diagnostics are enabled when `DiagnosticsConfig.perWorkerLatency()` is true in YAML:

```yaml
diagnostics:
  enabled: true
  perWorkerLatency: true          # enables gap diagnostics + histograms
  perWorkerSlowThresholdMillis: 10
```

**Memory overhead** (when perWorkerLatency=true):
- Per worker: ~16 KB (2 × 1024-entry histograms + metadata)
- For 32 workers: ~512 KB total
- Negligible compared to benchmark measurement

**Timing overhead**:
- ShardedWorker path: ~50 ns per task
- SharedExecutor path: ~30 ns per task
- Acceptable for 100+ μs workloads

## Limitations & Heuristics

1. **Empty queue estimation (ShardedWorker)**
   - Heuristic: if `pollNs > 100`, assume 70% was empty queue wait
   - This is an approximation; actual ratio depends on task arrival pattern
   - Real measurements require kernel-level tracing

2. **SharedExecutor empty queue**
   - Cannot directly measure (ThreadPoolExecutor internal)
   - Estimated from gap size
   - Less accurate than ShardedWorker

3. **No per-task logging**
   - Aggregate statistics only
   - Histograms support percentile queries post-run
   - JFR/perf not enabled (as per spec)

4. **Single-writer assumption**
   - ShardedWorker: owned by one thread
   - SharedExecutor aggregate: assumes single-threaded submission
   - Safe due to benchmark architecture

## Future Extensions

1. **Per-queue depth sampling** (QueueStatistics)
   - Integrate with QueueDepthSampler
   - Track average/max depth over time windows

2. **CPU migration tracking**
   - Correlate gaps with thread migrations
   - Detect NUMA node hops

3. **Lock contention analysis**
   - Sample JVM thread dumps during high gaps
   - Detect lock holder waits

4. **Export to CSV**
   - Per-worker gap statistics
   - Histogram raw values for external analysis

