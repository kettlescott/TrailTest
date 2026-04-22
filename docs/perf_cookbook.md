can y# Profiling a Java Application with `perf` — Practical Cookbook

This document collects the `perf` invocations that are actually useful when
analysing a JVM workload, and explains what each one tells you that JFR
does not.

> **Why use `perf` if I already have JFR?**
> JFR sees only what the JVM tells it. `perf` sees the kernel, the OS
> scheduler, hardware counters, and native code. Use JFR for *what the JVM
> did*; use `perf` for *what the CPU and kernel did to the JVM*.

---

## 0. One-time prerequisites

### Kernel knobs (root)
```bash
sudo sysctl -w kernel.perf_event_paranoid=1   # allow user-level sampling
sudo sysctl -w kernel.kptr_restrict=0         # resolve kernel symbols
# Persist:
echo 'kernel.perf_event_paranoid=1' | sudo tee -a /etc/sysctl.d/99-perf.conf
echo 'kernel.kptr_restrict=0'        | sudo tee -a /etc/sysctl.d/99-perf.conf
```

`perf_event_paranoid` values:
- `2` (distro default on many systems): user-mode sampling only — no kernel stacks.
- `1`: + kernel profiling allowed.
- `0`: + raw tracepoints.
- `-1`: no restrictions.

For this benchmark, **`1` is the minimum** so you get scheduler / lock-contention information from kernel space.

### JVM flags (so `perf` can see Java frames)
Add to your JVM command line:
```
-XX:+UnlockDiagnosticVMOptions
-XX:+PreserveFramePointer        # required for --call-graph fp
-XX:+DumpPerfMapAtExit           # writes /tmp/perf-<pid>.map at JVM shutdown
```

`PreserveFramePointer` costs ~1–3% throughput on x86 because it reserves
`%rbp`, but is the only way to get fast, accurate Java call graphs.
Without it use `--call-graph dwarf` instead (slower, larger `perf.data`,
but no JVM flag needed).

For *live* symbol resolution while the JVM is still running (rather than
at exit), use [async-profiler](https://github.com/async-profiler/async-profiler)
or `perf-map-agent` to write `/tmp/perf-<pid>.map` on demand.

### Sanity check
```bash
perf list | head        # event types are visible
perf bench sched messaging   # perf is functional
```

---

## 1. CPU profiling (the default question: "where is time spent?")

```bash
PID=$(pgrep -f TrailSystem)

# Sample on-CPU stacks at 999 Hz (prime, avoids beating with JFR's 100 Hz)
perf record -F 999 -p $PID --call-graph fp -k monotonic \
            -m 512 -o cpu.data -- sleep 30
```

Inspect:
```bash
perf report -i cpu.data --stdio --sort=overhead,dso,symbol | less
perf report -i cpu.data            # interactive TUI
```

Flame graph (Brendan Gregg's scripts):
```bash
perf script -i cpu.data \
  | ./stackcollapse-perf.pl \
  | ./flamegraph.pl > cpu.svg
```

**What this tells you that JFR doesn't:** native-library frames (libc,
malloc, kernel syscalls), JIT-compiler frames (`C2 CompilerThread`), and
GC threads at the same fidelity as your worker threads.

---

## 2. Hardware-counter accounting (the "what is the CPU actually doing?" question)

This is the single most useful command for explaining throughput regressions
between two executor implementations.

```bash
perf stat -p $PID -d -d -d -- sleep 10
```

Or with explicit events relevant to a queueing benchmark:

```bash
perf stat -p $PID \
  -e task-clock,cycles,instructions,branches,branch-misses \
  -e cache-references,cache-misses \
  -e LLC-loads,LLC-load-misses \
  -e L1-dcache-loads,L1-dcache-load-misses \
  -e dTLB-loads,dTLB-load-misses \
  -e context-switches,cpu-migrations,page-faults \
  -- sleep 10
```

Read these together:
- **IPC** (`instructions / cycles`): < 1.0 means the core is stalled — usually on memory.
- **`cache-misses` / `cache-references`** > a few %: data structure too big for L3 or false sharing.
- **`LLC-load-misses`**: confirms main-memory traffic; cross-socket if NUMA.
- **`branch-misses`**: high for code with data-dependent branches; for `CpuBoundWorkload` should be near zero (sanity check).
- **`context-switches` + `cpu-migrations`**: should be near the number of voluntary blocks. High `cpu-migrations` ⇒ workers not pinned, OR your pinning isn't working.
- **`dTLB-load-misses`**: high ⇒ working set spans many pages; consider `-XX:+UseTransparentHugePages`.

For the **shared vs sharded executor comparison**, run `perf stat` against
both runs and put the table in your paper. The difference in
`cache-misses`, `LLC-loads`, and `cpu-migrations` is usually the
mechanical explanation for any latency difference you observe.

---

## 3. Cache-line contention (`perf c2c`) — *the single most relevant tool for queue benchmarks*

`perf c2c` (Cache-to-Cache) identifies the *exact memory addresses*
that are being bounced between cores by the cache-coherence protocol.
For multi-producer / multi-consumer queues this is gold.

```bash
sudo perf c2c record -p $PID -- sleep 30
sudo perf c2c report --stats
sudo perf c2c report               # interactive: shows hot cache lines + producers/consumers
```

What you'll see:
- Each "HITM" (hit-modified) row is a cache line where one core wrote and
  another read — the fundamental cost of contention.
- Symbols column tells you which Java field / queue index it is (assuming
  the perf-map is in place).
- For your sharded executor, you should see *no* HITM events on the
  per-shard queues; for the shared executor, the head/tail pointers of
  the shared queue should dominate. This is the headline figure for the
  paper.

Requires Intel Haswell+ or AMD Zen+ for full PEBS-based memory event
support; older hardware reports a subset.

---

## 4. Scheduler latency (`perf sched`) — explain tail latency

When a worker thread is ready to run but the kernel doesn't schedule it
immediately, your task's queue-wait time grows even though no Java code
is at fault. `perf sched` quantifies this directly.

```bash
sudo perf sched record -p $PID -- sleep 10
sudo perf sched latency --sort max         # max wake-up→on-CPU latency per thread
sudo perf sched timehist                   # per-event timeline
```

Use this to answer: "Is my p99.9 tail caused by my queue or by the kernel?"
If `perf sched latency` shows worker threads with `Maximum delay` of, say,
2 ms, no executor algorithm in the world will give you a sub-2 ms p99.9.

---

## 5. Lock contention (`perf lock`) — find blocking primitives

```bash
sudo perf lock record -p $PID -- sleep 10
sudo perf lock report --sort=wait_total       # which kernel/futex locks blocked the most
sudo perf lock contention -p $PID -- sleep 5  # newer perf: per-callstack contention
```

For a JVM, the interesting locks usually appear as `futex` operations
backing `LockSupport.park` / `synchronized` / `ReentrantLock`. Combine
with `--call-graph` to see *which* Java code path took the lock.

---

## 6. System-wide vs single-process

Everything above with `-p $PID` records only your JVM. To see *everything
the kernel did during your benchmark window* (other processes, IRQ
handlers, kernel threads), use `-a`:

```bash
perf record -a -F 999 --call-graph fp -k monotonic -- sleep 10
```

Useful when investigating jitter: a noisy neighbour, a `kworker` thread
servicing IRQs on your pinned CPU, or `irqbalance` migrating interrupts.

---

## 7. Time-aligned overlay with JFR

Always pass:
```
perf record -k monotonic ...
```
JFR on Linux uses `CLOCK_MONOTONIC`, as does `System.nanoTime()`. With
`-k monotonic` (or any equivalent spelling, normalised by this project's
`PerfConfig`), every `perf` sample carries a timestamp in the **same
clock domain** as JFR events. You can then:

1. Dump anchor markers from JFR:
   ```bash
   jfr print --events com.scott.AnchorEvent results/<run>/<run>.jfr
   ```
2. Read first/last `perf` sample timestamps:
   ```bash
   perf script -i results/<run>/<run>.perf.data --ns \
     | awk '{print $4}' | sed 's/://' | sort -n | sed -n '1p;$p'
   ```
3. Confirm: JFR start anchor ≥ first perf timestamp, JFR stop anchor ≤ last
   perf timestamp. Residual offset (sample-buffering + IRQ latency) is
   single-digit microseconds.

---

## 8. Pitfalls

| Pitfall | Symptom | Fix |
|---|---|---|
| `kernel.perf_event_paranoid` too high | `perf record` emits no kernel stacks; `perf c2c` refuses to start | `sysctl -w kernel.perf_event_paranoid=1` |
| Missing `PreserveFramePointer` with `--call-graph fp` | Java stacks are 1 frame deep, all `<unknown>` | Add the JVM flag, or use `--call-graph dwarf` |
| No `/tmp/perf-<pid>.map` | All Java frames show as `[unknown]` or hex addresses | `-XX:+DumpPerfMapAtExit` (offline) or `perf-map-agent` (online) |
| `Process.destroy()` on `perf` | `perf.data` truncated, `perf script` complains | Send `SIGINT` (`kill -INT`), not `SIGTERM` |
| `clock: CLOCK_MONOTONIC` to perf -k | `perf record` exits immediately with `invalid clockid` | Use `monotonic` (or rely on `PerfConfig`'s normaliser) |
| `cpu-migrations` > `context-switches` | Workers not actually pinned | Verify with `taskset -pc <pid>`; check `CpuAffinity` succeeded |
| `LOST X chunks` in perf log | Ring buffer too small for sample rate × cores | Increase `-m` (we default to 512 pages) |
| Sampler beats with JFR | Periodic spikes at 100/1000 Hz harmonics | Use a *prime* `-F` value (`999`, `997`); we default to 999 |
| Recording during JIT warmup | Profile dominated by `C2 CompilerThread` | Start `perf` *after* warmup; this project starts it at `beforeMeasurement`, after the warmup loop |

---

## 9. Quick reference: minimal cheat sheet for this project

```bash
PID=$(pgrep -f TrailSystem)

# CPU flame graph
perf record -F 999 -k monotonic --call-graph fp -m 512 -p $PID -o cpu.data -- sleep 13
perf script -i cpu.data | stackcollapse-perf.pl | flamegraph.pl > cpu.svg

# HW counters (one-line summary)
perf stat -p $PID -d -d -d -- sleep 10

# Cache-line contention (sharded vs shared queue!)
sudo perf c2c record -p $PID -- sleep 13 && sudo perf c2c report

# Scheduler latency (explain tail)
sudo perf sched record -p $PID -- sleep 13 && sudo perf sched latency

# Lock contention
sudo perf lock record -p $PID -- sleep 13 && sudo perf lock report
```

Pair any of the above with the JFR recording opened in JDK Mission Control
or `jfr print` for a complete, time-aligned picture.

