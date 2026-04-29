# Hybrid (Type-Aware) Task Routing Workflow

```
┌─────────────────────────────────────┐
│          BenchmarkMain              │
│  Generate mixed workload (SHORT,   │
│  MEDIUM, LONG) with reproducible   │
│  seed + calibrated iterations      │
└──────────────┬──────────────────────┘
               │
               │  task.submit()
               ▼
┌─────────────────────────────────────┐
│       TypeAwareDispatcher           │
│                                     │
│   switch (task.taskType())          │
│                                     │
│   ┌─────────┬───────────┬────────┐  │
│   │ SHORT   │ MEDIUM    │ LONG   │  │
│   └────┬────┴─────┬─────┴───┬────┘  │
│        │          │         │        │
│        ▼          └────┬────┘        │
│   Sharded              │             │
│   Executor          Shared           │
│                     Executor         │
└────┬───────────────────┬─────────────┘
     │                   │
     ▼                   ▼
┌──────────────┐  ┌──────────────────┐
│   Sharded    │  │  Shared Queue    │
│   Executor   │  │  (Single         │
│              │  │   BlockingQueue) │
│ taskId %     │  │                  │
│ shardCount   │  │  N worker        │
│  ┌────────┐  │  │  threads pull    │
│  │Shard 0 │  │  │  from same queue │
│  │Queue+  │  │  │                  │
│  │Worker  │  │  └────────┬─────────┘
│  ├────────┤  │           │
│  │Shard 1 │  │           ▼
│  │Queue+  │  │  ┌──────────────────┐
│  │Worker  │  │  │  Worker Thread   │
│  ├────────┤  │  │  picks MEDIUM /  │
│  │  ...   │  │  │  LONG task       │
│  ├────────┤  │  │                  │
│  │Shard N │  │  │  Avoids HOL      │
│  │Queue+  │  │  │  blocking of     │
│  │Worker  │  │  │  SHORT tasks     │
│  └───┬────┘  │  └────────┬─────────┘
│      │       │           │
└──────┼───────┘           │
       │                   │
       ▼                   ▼
┌─────────────────────────────────────┐
│           Task.run()                │
│                                     │
│  1. startNanos = System.nanoTime()  │
│  2. CpuBoundWorkload.execute(      │
│       seed, iterations)            │
│  3. finishNanos = System.nanoTime() │
│  4. latch.countDown()              │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│       TaskTimingStore               │
│                                     │
│  Record per-task timestamps:        │
│  • submitNanos  (before enqueue)    │
│  • startNanos   (begin execution)   │
│  • finishNanos  (end execution)     │
└──────────────┬──────────────────────┘
               │
               ▼
┌─────────────────────────────────────┐
│       LatencyRecorder               │
│                                     │
│  Compute offline:                   │
│  • Queue wait  = start - submit     │
│  • Exec time   = finish - start     │
│  • End-to-end  = finish - submit    │
└─────────────────────────────────────┘
```

## Routing Rules Summary

| TaskType | Target Executor | Reason |
|----------|----------------|--------|
| `SHORT`  | **Sharded** (dedicated queue per shard) | Low contention, fast dequeue, no HOL blocking |
| `MEDIUM` | **Shared** (single queue, N workers) | Work-stealing load balancing |
| `LONG`   | **Shared** (single queue, N workers) | Avoids blocking SHORT tasks on shard queues |

## Why This Matters

In a **sharded-only** design, a `LONG` task (~100 ms) on shard _k_ blocks
all `SHORT` tasks (~1 ms) queued behind it on the same shard — causing
**head-of-line (HOL) blocking** and inflating p99/p99.9 latency.

The **type-aware hybrid** avoids this by routing `SHORT` tasks to dedicated
shards (low contention) while sending `LONG`/`MEDIUM` tasks to the shared
queue where any idle worker can pick them up.

