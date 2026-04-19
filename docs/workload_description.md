# Synthetic CPU-Bound Workload: Short and Long Tasks

This section describes the synthetic workload used in our executor-latency
benchmarks. The design goal is to obtain a task whose wall-clock cost is
**deterministic, reproducible, and insensitive to memory, I/O, or scheduling
artefacts**, so that any observed latency variation can be attributed to the
executor under test rather than to the task itself.

## 1. Workload Primitive: `CpuBoundWorkload`

Every task in the benchmark executes a single primitive workload,
`CpuBoundWorkload`, which runs a tight arithmetic loop with no system calls,
no allocations, no locking, no randomness, and no memory traffic beyond a
handful of registers. Given a 64-bit `seed` and a positive integer
`iterations`, it computes:

```java
long x = seed;
for (int i = 0; i < iterations; i++) {
    x ^= (x << 13);
    x ^= (x >>> 7);
    x ^= (x << 17);
    x = x * 0x9E3779B97F4A7C15L + i;
}
return x;
```

The inner body is a composition of two well-known bit-mixing primitives:

1. **Marsaglia xorshift (13, 7, 17).** The three XOR-shift steps form a
   full-period linear transformation over GF(2)^64 that diffuses every input
   bit into every output bit within a single iteration. It was selected
   because it is the canonical, branch-free, constant-time non-cryptographic
   mixer used in the literature on synthetic micro-benchmarks.
2. **Multiplicative hash with the golden-ratio constant
   `φ⁻¹ · 2⁶⁴ = 0x9E3779B97F4A7C15`.** Multiplying by an odd 64-bit constant
   is a bijection on `uint64`, and the golden-ratio constant is Knuth's
   recommended multiplier for maximising low-bit avalanche in Fibonacci
   hashing. Adding the loop index `i` prevents the sequence from entering a
   short cycle and guarantees that the compiler cannot hoist the computation
   out of the loop.

Properties relevant to benchmarking:

| Property | Justification |
|---|---|
| **Deterministic** | Identical `(seed, iterations)` pairs always yield bit-identical output, so runs are reproducible. |
| **Pure CPU** | The loop touches only a single `long` register; it does not read or write heap memory, does not branch on data, and does not invoke any syscall. Measured time therefore reflects only ALU throughput on the core executing it. |
| **Branch-predictor-neutral** | The loop body contains no data-dependent branches; only the loop counter comparison, which the predictor resolves perfectly. |
| **JIT-safe** | The final mixed value is returned and consumed by the caller (via `Task.workloadResult` or the `blackhole` sink in the calibrator). HotSpot therefore cannot eliminate the loop as dead code. |
| **Allocation-free** | A static overload `CpuBoundWorkload.execute(seed, iterations)` is provided and is used on the hot path inside `Task.run()`, so the per-task cost contains no object allocation and no GC pressure. |
| **Linearly scalable cost** | Because every iteration performs the same fixed, data-independent work, total wall-clock cost is (to first order) linear in `iterations`: `T(n) = n · t_iter + O(1)`. This is the property we exploit to define task classes of different durations. |

## 2. Task-Duration Classes

Tasks are classified by their target execution time. The enum `TaskType`
defines three classes, each associated with a fixed iteration multiplier
applied to a hardware-calibrated base count `N₀`:

| Class    | Label      | Multiplier `k` | Target wall-clock time |
|----------|------------|----------------|------------------------|
| `SHORT`  | `"short"`  | 1              | ≈ 1 ms   |
| `MEDIUM` | `"medium"` | 10             | ≈ 10 ms  |
| `LONG`   | `"long"`   | 100            | ≈ 100 ms |

A task of class `C` runs `CpuBoundWorkload` with `iterations = k(C) · N₀`.
The multipliers 1 / 10 / 100 give one order of magnitude separation between
consecutive classes, which is sufficient to expose head-of-line blocking
effects in sharded executors while keeping the longest task short enough to
sustain a statistically meaningful sample count within a bounded benchmark
run. **In the experiments reported in this paper we use only the two
extremes, `SHORT` and `LONG`, giving a 100× duration ratio** — the regime in
which scheduling policy has the largest observable impact on tail latency.

### 2.1 Short task (`SHORT`)
A `SHORT` task executes `N₀` iterations of `CpuBoundWorkload` and is
calibrated to ≈ 1 ms on the test machine. Short tasks dominate queue arrival
rates in mixed workloads and represent latency-sensitive, interactive units
of work. Because `T_short` is of the same order as typical context-switch
and queue-handoff costs, short tasks are the most sensitive indicator of
executor overhead: any excess queue-wait time is directly visible in their
end-to-end latency distribution.

### 2.2 Long task (`LONG`)
A `LONG` task executes `100 · N₀` iterations and is calibrated to ≈ 100 ms.
Long tasks represent background, throughput-oriented units of work. Their
role in the benchmark is to induce **head-of-line blocking**: when a long
task is placed on a shard queue, every short task subsequently enqueued
behind it waits up to ≈ 100 ms before being picked up, amplifying the p99
and p99.9 latency of short tasks. This is precisely the pathology that the
type-aware dispatcher under evaluation is designed to mitigate, and the
reason the benchmark's mixed-workload distributions deliberately include a
small percentage of `LONG` tasks among a majority of `SHORT` ones.

## 3. Calibration Algorithm (`WorkloadCalibrator`)

Because `t_iter` — the per-iteration cost of the xorshift/multiply body —
depends on the target CPU's IPC, clock frequency, and the final JIT-compiled
code shape, the base iteration count `N₀` is not hard-coded. It is obtained
at benchmark start-up by a two-stage online calibration procedure that maps
a **desired wall-clock duration `T*`** (1 ms, 10 ms, or 100 ms) to an
**iteration count `N`** such that `CpuBoundWorkload(seed, N).execute()`
takes approximately `T*` on the current hardware.

**Algorithm (pseudocode):**

```
Input:  target duration T* (ns), seed s
Output: iteration count N such that E[time(execute(s, N))] ≈ T*

1.  // Pilot measurement
    N_p ← 50_000                              // PILOT_ITERATIONS
    t_p ← time(  execute(s, N_p)  )           // via System.nanoTime()
    if t_p ≤ 0 then t_p ← 1                   // guard against clock granularity

2.  // Linear extrapolation (assumes cost is linear in N)
    N_1 ← clamp( round( T*/t_p · N_p ) , 1, INT_MAX )

3.  // Verification pass + single-shot correction
    t_1 ← time(  execute(s, N_1)  )
    if t_1 ≤ 0 then t_1 ← 1
    N_2 ← clamp( round( T*/t_1 · N_1 ) , 1, INT_MAX )

4.  return N_2
```

Rationale for each step:

- **Step 1 (pilot).** A fixed, small workload of 50 000 iterations gives a
  cheap but statistically usable estimate of `t_iter`. Using a fixed pilot
  size rather than a fixed pilot duration keeps calibration startup time
  bounded and independent of the target duration.
- **Step 2 (linear extrapolation).** Since the loop body performs the same
  work every iteration, wall-clock cost is linear in `N` to within measurement
  noise, so a single division suffices to reach the target. The result is
  clamped to `[1, INT_MAX]` to avoid pathological outputs when `t_p` is
  artificially small (clock-tick quantisation) or `T*` is unreasonably large.
- **Step 3 (verification + correction).** The pilot is executed before the
  JIT has fully optimised `execute()`; the verification pass is executed
  *after* the JIT has compiled the method at its final tier. A second,
  JIT-stable measurement `t_1` is therefore a more faithful estimate of
  `t_iter`, and a single multiplicative correction `N_2 = (T*/t_1) · N_1`
  removes the residual bias. We found empirically that a single correction
  is sufficient: further iterations converge within measurement noise and
  only lengthen startup without improving accuracy.
- **Dead-code-elimination guard.** The workload result is consumed by a
  `volatile` "black-hole" sink after each timed run, forcing the JIT to keep
  the entire loop live. Without this, HotSpot's escape analysis would be
  free to remove the loop and the measured `t_p` would collapse to zero.
- **Determinism.** The procedure uses only `System.nanoTime()` and the
  deterministic `CpuBoundWorkload` itself; it performs no I/O, no
  synchronisation, and no randomisation, so two calibrations on the same
  machine in the same JVM converge to the same `N₀` up to nanosecond-clock
  jitter.

The three public helpers `shortWorkload(seed)`, `mediumWorkload(seed)`, and
`longWorkload(seed)` invoke `calibrateIterations(T*, seed)` with
`T* ∈ {1 ms, 10 ms, 100 ms}` respectively, producing the base iteration
counts used throughout the benchmark.

## 4. Per-Task Construction and Execution

A benchmark task is represented by the immutable `Task` class, which pairs
a `TaskType` with an iteration count (`k(type) · N₀`) and a per-task
`workloadSeed = baseSeed + taskId`. The per-task seed guarantees that every
task executes a distinct trajectory through the xorshift/multiply state
space, preventing CPU caches or branch predictors from recognising a
repeated pattern across tasks while still keeping the whole benchmark
bit-for-bit reproducible from the global `baseSeed`.

At execution time, `Task.run()` performs:

1. `startNanos ← System.nanoTime()`
2. `workloadResult ← CpuBoundWorkload.execute(workloadSeed, iterations)`
3. `finishNanos ← System.nanoTime()`
4. Signal completion (CountDownLatch and/or callback).

From these three timestamps plus the submit timestamp recorded by the
producer before enqueue, three latencies are derived per task:

- **Queue-wait time** = `startNanos − submitNanos`
- **Execution time**  = `finishNanos − startNanos`
- **End-to-end latency** = `finishNanos − submitNanos`

Because the execution-time component is calibrated and bounded, deviations
observed in queue-wait and end-to-end latency can be attributed to the
executor's scheduling and dispatching behaviour — which is the object of
study in this paper.

## 5. Workload Mixes and Dispatching

A `WorkloadConfig` specifies either a **single**-class workload (all tasks
of one `TaskType`) or a **mix** whose `distribution` field gives integer
percentages for `short`, `medium`, and `long` that sum to 100. For each
task id, `TaskGenerator.taskTypeFor(taskId)` draws a pseudo-random percentile
from a splitmix-style hash of `(baseSeed, taskId)` and compares it against
the cumulative distribution; this yields a reproducible per-task class
assignment without any runtime randomness source.

The `TypeAwareDispatcher` used in the experiments routes tasks by class:

- `SHORT` tasks → sharded executor (low contention, fast dequeue),
- `MEDIUM` and `LONG` tasks → shared executor (avoids head-of-line
  blocking of short tasks behind long ones on a shard queue).

This routing rule is the policy whose latency impact is measured using the
workload primitive and calibration procedure described above.

