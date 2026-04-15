package com.scott;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Open-loop benchmark harness that compares routing policies under a
 * reproducible mixed workload.
 *
 * <h3>Supported policies</h3>
 * <ul>
 *   <li>{@link SharedOnlyDispatcher}  — all tasks → single shared queue</li>
 *   <li>{@link ShardedOnlyDispatcher} — all tasks → per-worker queues</li>
 *   <li>{@link TypeAwareDispatcher}   — SHORT→sharded, MEDIUM/LONG→shared</li>
 * </ul>
 *
 * <h3>Submission model</h3>
 * <p>Uses a continuous open-loop producer with global {@link Semaphore}
 * backpressure.  The same workload sequence (seed + task-type pattern)
 * is replayed identically for every policy so results are directly
 * comparable.</p>
 *
 * <h3>Execution modes</h3>
 * <ul>
 *   <li>{@code --mode=prepare}    — calibrate, print fixed config, exit</li>
 *   <li>{@code --mode=shared}     — SharedOnlyDispatcher only</li>
 *   <li>{@code --mode=sharded}    — ShardedOnlyDispatcher only</li>
 *   <li>{@code --mode=type_aware} — TypeAwareDispatcher only</li>
 *   <li>{@code --mode=compare}    — all three sequentially, side-by-side</li>
 * </ul>
 */
public class BenchmarkMain {

    /* ---- defaults ---- */

    private static final long DEFAULT_SEED              = 0xDEADBEEFL;
    private static final long DEFAULT_TARGET_TASK_NANOS = 100_000L;     // ~100 μs per task
    private static final int  DEFAULT_WARMUP_SECONDS    = 3;
    private static final int  DEFAULT_MEASURE_SECONDS   = 10;
    private static final int  MIN_USEFUL_SAMPLES        = 100;
    private static final long MEASUREMENT_SEED_OFFSET   = 1_000_000_000L;

    /**
     * Repeating task-type pattern for mixed workload generation.
     * The pattern is cycled deterministically so every policy sees
     * the same type sequence.  Ratio: 6 SHORT : 3 MEDIUM : 1 LONG.
     */
    private static final TaskType[] TYPE_PATTERN = {
            TaskType.SHORT, TaskType.SHORT, TaskType.SHORT,
            TaskType.SHORT, TaskType.SHORT, TaskType.SHORT,
            TaskType.MEDIUM, TaskType.MEDIUM, TaskType.MEDIUM,
            TaskType.LONG
    };

    /* ---- active config ---- */

    private static BenchmarkConfig config;

    /* ---- phase result record ---- */

    private record PhaseResult(Task[] tasks, int submitted, TaskTimingStore store,
                               long elapsedNanos, int backpressureEvents) {}

    /* ================================================================
     *  Entry point
     * ================================================================ */

    public static void main(String[] args) throws Exception {

        BenchmarkMode mode = BenchmarkMode.fromArgs(args);
        String configSource = resolveConfig(args);

        System.out.println();
        System.out.println("=== Benchmark Configuration ===");
        System.out.printf("  Config source     : %s%n", configSource);
        System.out.printf("  Mode              : %s%n", mode);
        System.out.printf("  Workers           : %d%n", config.workerCount());
        System.out.printf("  Max in-flight     : %d%n", config.maxInflight());
        System.out.printf("  Seed              : %d%n", config.seed());
        System.out.printf("  Iterations (base) : %,d%n", config.iterations());
        System.out.printf("  Warmup            : %d s%n", config.warmupSeconds());
        System.out.printf("  Measurement       : %d s%n", config.measurementSeconds());
        System.out.printf("  Task count        : %s%n",
                config.taskCount() > 0 ? String.format("%,d (fixed)", config.taskCount()) : "unlimited (time-based)");
        System.out.printf("  Task target       : %.1f ms%n", config.targetTaskNanos() / 1_000_000.0);
        System.out.printf("  Type pattern      : 6 SHORT : 3 MEDIUM : 1 LONG%n");
        System.out.printf("  Submission model  : open-loop (Semaphore-gated, %d permits)%n",
                config.maxInflight());
        System.out.printf("  Debug mode        : %s%n",
                BenchmarkFlags.DEBUG ? "ON" : "OFF");
        System.out.println();

        if (mode == BenchmarkMode.PREPARE) {
            System.out.println("=== Fixed Config (machine-readable) ===");
            System.out.println(config.toFixedConfigBlock());
            System.out.println();
            System.out.println("=== Paste into subsequent commands ===");
            for (String m : new String[]{"shared", "sharded", "type_aware"}) {
                System.out.println("  java --enable-preview -cp target/classes com.scott.BenchmarkMain \\");
                System.out.printf("       --mode=%s %s%n%n", m, config.toCliArgs());
            }
            return;
        }

        switch (mode) {
            case SHARED -> {
                runWithDispatcher(new SharedOnlyDispatcher(config.workerCount()));
            }
            case SHARDED -> {
                runWithDispatcher(new ShardedOnlyDispatcher(config.workerCount()));
            }
            case TYPE_AWARE -> {
                runWithDispatcher(new TypeAwareDispatcher(config.workerCount()));
            }
            case COMPARE -> {
                LatencyRecorder r1 = runWithDispatcher(new SharedOnlyDispatcher(config.workerCount()));
                System.out.println();
                LatencyRecorder r2 = runWithDispatcher(new ShardedOnlyDispatcher(config.workerCount()));
                System.out.println();
                LatencyRecorder r3 = runWithDispatcher(new TypeAwareDispatcher(config.workerCount()));
                System.out.println();
                printComparison(r1, r2, r3);
            }
            default -> { /* PREPARE handled above */ }
        }
    }

    /* ================================================================
     *  Config resolution
     * ================================================================ */

    private static String resolveConfig(String[] args) {

        BenchmarkConfig fixed = BenchmarkConfig.fromArgs(args, DEFAULT_SEED, DEFAULT_TARGET_TASK_NANOS);
        if (fixed != null) {
            config = fixed;
            return "FIXED (from command-line arguments)";
        }

        System.out.println("=== Calibrating workload ===");
        int iterations = WorkloadCalibrator.calibrateIterations(DEFAULT_TARGET_TASK_NANOS, DEFAULT_SEED);
        System.out.printf("  Calibrated    : %,d iterations%n", iterations);

        long verifyStart  = System.nanoTime();
        long verifyResult = new CpuBoundWorkload(DEFAULT_SEED, iterations).execute();
        long verifyNanos  = System.nanoTime() - verifyStart;
        if (verifyResult == Long.MIN_VALUE) System.out.print("");
        System.out.printf("  Verified      : %.3f ms  (target %.1f ms)%n",
                verifyNanos / 1_000_000.0, DEFAULT_TARGET_TASK_NANOS / 1_000_000.0);

        if (verifyNanos > 10 * DEFAULT_TARGET_TASK_NANOS) {
            System.err.printf("  *** WARNING: verified task is %.1fx slower than target!%n",
                    (double) verifyNanos / DEFAULT_TARGET_TASK_NANOS);
        }
        if (verifyNanos > 0 && verifyNanos < DEFAULT_TARGET_TASK_NANOS / 10) {
            System.err.printf("  *** WARNING: verified task is %.1fx faster than target!%n",
                    (double) DEFAULT_TARGET_TASK_NANOS / verifyNanos);
        }

        Integer wcArg = BenchmarkConfig.parseIntArg(args, "--workerCount");
        int workerCount = wcArg != null ? wcArg : Runtime.getRuntime().availableProcessors();

        Integer miArg = BenchmarkConfig.parseIntArg(args, "--maxInflight");
        int maxInflight = miArg != null ? miArg : workerCount * 2;

        Integer tcArg = BenchmarkConfig.parseIntArg(args, "--taskCount");
        int taskCount = tcArg != null ? tcArg : 0;

        config = new BenchmarkConfig(workerCount, maxInflight, DEFAULT_SEED,
                iterations, DEFAULT_WARMUP_SECONDS, DEFAULT_MEASURE_SECONDS,
                DEFAULT_TARGET_TASK_NANOS, taskCount);

        return "CALIBRATED (dynamic)";
    }

    /* ================================================================
     *  Unified dispatcher benchmark
     * ================================================================ */

    /**
     * Runs the full benchmark (warmup + measurement + reporting) through
     * a {@link Dispatcher}.  The same open-loop Semaphore-gated submission
     * model is used regardless of the dispatcher implementation.
     */
    private static LatencyRecorder runWithDispatcher(Dispatcher dispatcher) throws InterruptedException {

        String label = dispatcher.label();

        System.out.println("========================================");
        System.out.printf("  %s%n", label);
        System.out.println("========================================");

        long actualTaskNanos = measureActualTaskNanos();
        System.out.printf("  Actual task time : %.3f ms  (base SHORT, used for capacity estimation)%n",
                actualTaskNanos / 1_000_000.0);
        System.out.printf("  Backpressure     : global Semaphore (%d permits)%n", config.maxInflight());

        // ---- warmup ----
        long warmupNanos = config.warmupSeconds() * 1_000_000_000L;
        System.out.printf("  Warmup (%d s, open-loop)...%n", config.warmupSeconds());

        PhaseResult warmup = runOpenLoopPhase(dispatcher, warmupNanos, false, 0L, actualTaskNanos);

        System.out.printf("  Warmup done    : %,d tasks in %.3f s%n",
                warmup.submitted, warmup.elapsedNanos / 1_000_000_000.0);
        if (warmup.backpressureEvents > 0) {
            System.out.printf("  Warmup backpressure events: %,d%n", warmup.backpressureEvents);
        }

        //noinspection UnusedAssignment
        warmup = null;   // release warmup arrays before measurement allocation

        // ---- measurement ----
        long measureNanos = config.measurementSeconds() * 1_000_000_000L;
        System.out.printf("  Measurement (%d s, open-loop)...%n", config.measurementSeconds());

        PhaseResult measurement = runOpenLoopPhase(dispatcher, measureNanos, true, MEASUREMENT_SEED_OFFSET, actualTaskNanos);

        double measureSecs = measurement.elapsedNanos / 1_000_000_000.0;
        double throughput  = measurement.submitted / measureSecs;

        System.out.printf("  Measurement done: %,d tasks in %.3f s%n",
                measurement.submitted, measureSecs);
        if (measurement.backpressureEvents > 0) {
            System.out.printf("  Backpressure events: %,d%n", measurement.backpressureEvents);
        }
        System.out.printf("  Throughput       : %,.1f tasks/s%n", throughput);

        // ---- record latencies offline ----
        LatencyRecorder recorder = new LatencyRecorder(measurement.submitted);
        for (int i = 0; i < measurement.submitted; i++) {
            recorder.record(measurement.tasks[i]);
        }

        if (recorder.recordedTasks() < MIN_USEFUL_SAMPLES) {
            System.err.printf("  *** WARNING: only %d tasks recorded — too few for percentile analysis.%n",
                    recorder.recordedTasks());
        }

        System.out.printf("  --- %s Latency Summary ---%n", label);
        System.out.println(recorder.summary());

        System.out.printf("  === Measurement Consistency Check (%s) ===%n", label);
        System.out.printf("    submitted during measurement : %,d%n", measurement.submitted);
        System.out.printf("    latency records              : %,d%n", recorder.recordedTasks());
        if (recorder.recordedTasks() != measurement.submitted) {
            System.err.printf("    *** WARNING: latency records (%d) != submitted (%d) — mismatch!%n",
                    recorder.recordedTasks(), measurement.submitted);
        } else {
            System.out.println("    OK — all counts reconcile.");
        }

        // ---- shutdown ----
        dispatcher.shutdown();
        dispatcher.awaitTermination(30, TimeUnit.SECONDS);

        return recorder;
    }

    /* ================================================================
     *  Open-loop phase — Semaphore-gated, mixed workload
     * ================================================================ */

    /**
     * Submits tasks continuously using a global {@link Semaphore} with
     * mixed {@link TaskType} workloads.  The type pattern is cycled
     * deterministically so every dispatcher sees the same sequence.
     */
    private static PhaseResult runOpenLoopPhase(
            Dispatcher dispatcher,
            long phaseNanos,
            boolean isMeasurement,
            long seedOffset,
            long actualTaskNanos) throws InterruptedException {

        int maxInflight  = config.maxInflight();
        int estimatedMax = estimateMaxTasks(phaseNanos, actualTaskNanos);

        TaskTimingStore store = new TaskTimingStore(estimatedMax);
        Task[]          tasks = new Task[estimatedMax];

        Semaphore permits       = new Semaphore(maxInflight);
        Runnable  releasePermit = permits::release;

        int  submitted         = 0;
        int  backpressureCount = 0;
        long phaseStart        = System.nanoTime();
        long deadline          = phaseStart + phaseNanos;

        int taskLimit = config.taskCount() > 0 ? config.taskCount() : estimatedMax;
        int patternLen = TYPE_PATTERN.length;

        while (System.nanoTime() < deadline && submitted < taskLimit) {
            if (!permits.tryAcquire()) {
                backpressureCount++;
                permits.acquire();
            }

            int      idx      = submitted;
            long     taskSeed = config.seed() + seedOffset + idx;
            TaskType type     = TYPE_PATTERN[idx % patternLen];

            int      iters = config.iterations() * type.iterationMultiplier();
            Workload w     = new CpuBoundWorkload(taskSeed, iters);
            Task     task  = new Task(idx, idx, w, store, null, releasePermit, isMeasurement, type);

            store.recordSubmit(idx, System.nanoTime());
            dispatcher.submit(task);

            tasks[idx] = task;
            submitted++;
        }

        if (config.taskCount() == 0 && submitted >= estimatedMax) {
            System.err.printf("  *** WARNING: estimated capacity (%,d) reached — phase may be truncated.%n",
                    estimatedMax);
        }

        // Drain: wait for all in-flight tasks to complete.
        permits.acquire(maxInflight);
        permits.release(maxInflight);

        long phaseEnd = System.nanoTime();
        return new PhaseResult(tasks, submitted, store, phaseEnd - phaseStart, backpressureCount);
    }

    /* ================================================================
     *  Capacity estimation
     * ================================================================ */

    private static int estimateMaxTasks(long phaseNanos, long actualTaskNanos) {
        if (config.taskCount() > 0) {
            return config.taskCount() + 64;
        }
        double phaseSeconds = phaseNanos / 1_000_000_000.0;
        double taskSeconds  = actualTaskNanos / 1_000_000_000.0;
        if (taskSeconds <= 0) taskSeconds = 0.000_001;
        double maxThroughput = config.workerCount() / taskSeconds;
        int estimated = (int) (maxThroughput * phaseSeconds);
        return Math.max(estimated * 2, 4096);
    }

    /**
     * Measures steady-state task time for the base (SHORT) workload.
     */
    private static long measureActualTaskNanos() {
        long sink = 0;
        for (int i = 0; i < 200; i++) {
            sink += new CpuBoundWorkload(config.seed() + i, config.iterations()).execute();
        }
        long start = System.nanoTime();
        int runs = 20;
        for (int i = 0; i < runs; i++) {
            sink += new CpuBoundWorkload(config.seed() + 1000 + i, config.iterations()).execute();
        }
        long elapsed = System.nanoTime() - start;
        if (sink == Long.MIN_VALUE) System.out.print("");
        return Math.max(elapsed / runs, 1);
    }

    /* ================================================================
     *  Side-by-side comparison (compare mode)
     * ================================================================ */

    private static void printComparison(LatencyRecorder shared,
                                        LatencyRecorder sharded,
                                        LatencyRecorder typeAware) {
        System.out.println("========================================");
        System.out.println("  Side-by-Side Comparison");
        System.out.println("========================================");
        System.out.printf("  SharedOnly  recorded: %,d tasks%n", shared.recordedTasks());
        System.out.printf("  ShardedOnly recorded: %,d tasks%n", sharded.recordedTasks());
        System.out.printf("  TypeAware   recorded: %,d tasks%n%n", typeAware.recordedTasks());

        System.out.printf("%-20s %12s %12s %12s%n", "Metric", "SharedOnly", "ShardedOnly", "TypeAware");
        System.out.println("-".repeat(58));
        compareRow("p50 Queue wait",  shared.p50QueueWait(),  sharded.p50QueueWait(),  typeAware.p50QueueWait());
        compareRow("p90 Queue wait",  shared.p90QueueWait(),  sharded.p90QueueWait(),  typeAware.p90QueueWait());
        compareRow("p95 Queue wait",  shared.p95QueueWait(),  sharded.p95QueueWait(),  typeAware.p95QueueWait());
        compareRow("p99 Queue wait",  shared.p99QueueWait(),  sharded.p99QueueWait(),  typeAware.p99QueueWait());
        System.out.println("-".repeat(58));
        compareRow("p50 Execution",   shared.p50Execution(),  sharded.p50Execution(),  typeAware.p50Execution());
        compareRow("p90 Execution",   shared.p90Execution(),  sharded.p90Execution(),  typeAware.p90Execution());
        compareRow("p95 Execution",   shared.p95Execution(),  sharded.p95Execution(),  typeAware.p95Execution());
        compareRow("p99 Execution",   shared.p99Execution(),  sharded.p99Execution(),  typeAware.p99Execution());
        System.out.println("-".repeat(58));
        compareRow("p50 End-to-end",  shared.p50EndToEnd(),   sharded.p50EndToEnd(),   typeAware.p50EndToEnd());
        compareRow("p90 End-to-end",  shared.p90EndToEnd(),   sharded.p90EndToEnd(),   typeAware.p90EndToEnd());
        compareRow("p95 End-to-end",  shared.p95EndToEnd(),   sharded.p95EndToEnd(),   typeAware.p95EndToEnd());
        compareRow("p99 End-to-end",  shared.p99EndToEnd(),   sharded.p99EndToEnd(),   typeAware.p99EndToEnd());
    }

    private static void compareRow(String label, double sharedNs, double shardedNs, double typeAwareNs) {
        System.out.printf("%-20s %10.3f ms %10.3f ms %10.3f ms%n",
                label,
                sharedNs / 1_000_000.0,
                shardedNs / 1_000_000.0,
                typeAwareNs / 1_000_000.0);
    }
}
