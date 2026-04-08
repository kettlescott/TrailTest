package com.scott;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * GC-aware, sustained benchmark that runs both {@link SharedExecutor}
 * and {@link ShardedExecutor} back-to-back with identical configuration,
 * then prints a side-by-side latency comparison.
 *
 * <h3>Execution model</h3>
 * <ol>
 *   <li><b>Calibrate</b> — determine iteration count for ~4 ms task service time,
 *       then verify by timing a single task execution.</li>
 *   <li><b>SharedExecutor run</b> — warmup + measurement with a single shared queue.</li>
 *   <li><b>ShardedExecutor run</b> — warmup + measurement with per-worker queues.</li>
 *   <li><b>Comparison</b> — side-by-side p50/p90/p95/p99 table.</li>
 * </ol>
 *
 * <h3>Controlled-load design</h3>
 * <p>Each batch submits {@code BATCH_SIZE} (= workerCount × 2) tasks, then
 * awaits their completion via a per-batch {@link CountDownLatch} before
 * starting the next batch.  This bounds the maximum in-flight task count to
 * {@code BATCH_SIZE}, preventing queue buildup while keeping workers busy.</p>
 *
 * <h3>Fairness</h3>
 * <p>Both executors receive the same worker count, batch size, workload
 * calibration, seed sequence, warmup duration, and measurement duration.
 * The <em>only</em> variable is the queue topology.  Each executor gets its
 * own warmup phase so JIT and GC state are settled independently.
 * For publication-grade results, run each executor in a separate JVM or
 * alternate the execution order across trials.</p>
 *
 * <h3>GC awareness</h3>
 * <ul>
 *   <li>{@link TaskTimingStore} and {@code Task[]} are pre-allocated once
 *       per executor run and reused across every batch.</li>
 *   <li>{@link LatencyRecorder} is pre-sized to avoid runtime growth.</li>
 *   <li>Per-task objects ({@link Task}, {@link CpuBoundWorkload}) are freshly
 *       created each batch — allocation rate is bounded by the controlled load.</li>
 * </ul>
 */
public class BenchmarkMain {

    /* ---- tunables ---- */

    private static final int  WORKER_COUNT         = Runtime.getRuntime().availableProcessors();
    private static final int  BATCH_SIZE           = WORKER_COUNT * 2;
    private static final long SEED                 = 0xDEADBEEFL;
    private static final long TARGET_TASK_NANOS    = 4_000_000L;        // ~4 ms per task
    private static final long WARMUP_SECONDS       = 10;
    private static final long MEASUREMENT_SECONDS  = 30;
    private static final int  SHARD_QUEUE_CAPACITY = 256;

    /** Minimum recorded tasks for meaningful percentile analysis. */
    private static final int  MIN_USEFUL_SAMPLES   = 100;

    public static void main(String[] args) throws Exception {

        // ---- 1. Calibrate workload (shared by both executors) ----
        System.out.println("=== Calibrating workload ===");
        int iterations = WorkloadCalibrator.calibrateIterations(TARGET_TASK_NANOS, SEED);
        System.out.printf("  Calibrated    : %,d iterations%n", iterations);

        // Verify calibration by timing a single task execution
        long verifyStart = System.nanoTime();
        long verifyResult = new CpuBoundWorkload(SEED, iterations).execute();
        long verifyNanos = System.nanoTime() - verifyStart;
        // consume result so JIT cannot eliminate the work
        if (verifyResult == Long.MIN_VALUE) System.out.print("");
        System.out.printf("  Verified      : %.3f ms  (target %.1f ms)%n",
                verifyNanos / 1_000_000.0, TARGET_TASK_NANOS / 1_000_000.0);

        if (verifyNanos > 10 * TARGET_TASK_NANOS) {
            System.err.printf("  *** WARNING: verified task is %.1fx slower than target!%n",
                    (double) verifyNanos / TARGET_TASK_NANOS);
            System.err.println("  *** Calibration may be inaccurate — expect very few batches.");
        }
        if (verifyNanos > 0 && verifyNanos < TARGET_TASK_NANOS / 10) {
            System.err.printf("  *** WARNING: verified task is %.1fx faster than target!%n",
                    (double) TARGET_TASK_NANOS / verifyNanos);
        }

        System.out.println();
        System.out.println("=== Benchmark Configuration ===");
        System.out.printf("  Workers        : %d%n", WORKER_COUNT);
        System.out.printf("  Batch size     : %d%n", BATCH_SIZE);
        System.out.printf("  Warmup         : %d s%n", WARMUP_SECONDS);
        System.out.printf("  Measurement    : %d s%n", MEASUREMENT_SECONDS);
        System.out.printf("  Queue capacity : %d (sharded)%n", SHARD_QUEUE_CAPACITY);

        double estBatchMs = (double) BATCH_SIZE / WORKER_COUNT * verifyNanos / 1_000_000.0;
        long estBatches   = (long) (MEASUREMENT_SECONDS * 1000.0 / estBatchMs);
        long estTasks     = estBatches * BATCH_SIZE;
        System.out.printf("  Est. batch time: %.1f ms  →  ~%,d batches  →  ~%,d tasks in %ds%n%n",
                estBatchMs, estBatches, estTasks, MEASUREMENT_SECONDS);

        // ---- 2. SharedExecutor run ----
        System.out.println("========================================");
        System.out.println("  SharedExecutor (single shared queue)");
        System.out.println("========================================");
        LatencyRecorder sharedRecorder;
        {
            SharedExecutor executor = new SharedExecutor(WORKER_COUNT);
            sharedRecorder = runBenchmark(executor, iterations, "SharedExecutor");
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        System.out.println();

        // ---- 3. ShardedExecutor run ----
        System.out.println("========================================");
        System.out.println("  ShardedExecutor (per-worker queue)");
        System.out.println("========================================");
        LatencyRecorder shardedRecorder;
        {
            ShardedExecutor executor = new ShardedExecutor(WORKER_COUNT, SHARD_QUEUE_CAPACITY);
            shardedRecorder = runBenchmark(executor, iterations, "ShardedExecutor");
            executor.shutdown();
            executor.awaitTermination(30, TimeUnit.SECONDS);
        }
        System.out.println();

        // ---- 4. Side-by-side comparison ----
        printComparison(sharedRecorder, shardedRecorder);
    }

    /* ================================================================
     *  Per-executor benchmark (warmup + measurement + summary)
     * ================================================================ */

    private static LatencyRecorder runBenchmark(BenchmarkExecutor executor,
                                                int iterations,
                                                String label) throws InterruptedException {

        TaskTimingStore batchStore = new TaskTimingStore(BATCH_SIZE);
        Task[] batchTasks = new Task[BATCH_SIZE];

        // -- warmup --
        System.out.printf("  Warmup (%d s)...%n", WARMUP_SECONDS);
        long[] warmupCounts = runPhase(executor, iterations, WARMUP_SECONDS,
                batchStore, batchTasks, null);
        System.out.printf("  Warmup done    : batches=%,d  submitted=%,d  completed=%,d%n",
                warmupCounts[0], warmupCounts[1], warmupCounts[1]);

        // -- measurement --
        int estimated = estimateTaskCount(MEASUREMENT_SECONDS);
        LatencyRecorder recorder = new LatencyRecorder(estimated);

        System.out.printf("  Measurement (%d s)...%n", MEASUREMENT_SECONDS);
        long[] measureCounts = runPhase(executor, iterations, MEASUREMENT_SECONDS,
                batchStore, batchTasks, recorder);
        int recorded = recorder.recordedTasks();
        System.out.printf("  Measurement done: batches=%,d  submitted=%,d  completed=%,d  recorded=%,d%n",
                measureCounts[0], measureCounts[1], measureCounts[1], recorded);

        // -- verification: recorded must equal submitted --
        if (recorded != measureCounts[1]) {
            System.err.printf("  *** ERROR: recorded (%d) != submitted (%d) — tasks were lost!%n",
                    recorded, measureCounts[1]);
        }

        // -- warn if too few for percentile analysis --
        if (recorded < MIN_USEFUL_SAMPLES) {
            System.err.printf("  *** WARNING: only %d tasks recorded — too few for percentile analysis.%n",
                    recorded);
            System.err.printf("               Each batch of %d tasks may be taking too long.%n", BATCH_SIZE);
            System.err.printf("               Try reducing iterations or increasing measurement duration.%n");
        }

        System.out.printf("  --- %s Latency Summary ---%n", label);
        System.out.println(recorder.summary());
        return recorder;
    }

    /* ================================================================
     *  Phase runner — drives repeated batches for a fixed duration
     * ================================================================ */

    /**
     * Submits and completes small batches for {@code durationSeconds}.
     *
     * <p>Each batch is fully completed (latch awaited) before the next one
     * starts, so in-flight task count never exceeds {@code BATCH_SIZE}.
     * The {@code batchStore} and {@code batchTasks} arrays are reused
     * across batches — contents are overwritten each batch and consumed
     * (recorded) before the next overwrite.
     *
     * @param recorder if non-null, completed tasks are recorded
     *                 (measurement); {@code null} discards results (warmup)
     * @return {@code long[2]}: [0] = batches completed, [1] = total tasks
     */
    private static long[] runPhase(BenchmarkExecutor executor,
                                   int iterations,
                                   long durationSeconds,
                                   TaskTimingStore batchStore,
                                   Task[] batchTasks,
                                   LatencyRecorder recorder) throws InterruptedException {

        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(durationSeconds);
        long totalTasks = 0;
        long batchCount = 0;

        while (System.nanoTime() < deadlineNanos) {
            runOneBatch(executor, iterations, batchStore, batchTasks,
                    totalTasks, recorder);
            totalTasks += BATCH_SIZE;
            batchCount++;
        }

        return new long[]{ batchCount, totalTasks };
    }

    /* ================================================================
     *  Single-batch execution
     * ================================================================ */

    /**
     * Builds, submits, and awaits one batch of {@code BATCH_SIZE} tasks.
     *
     * <p>The build loop creates each {@link Task} with its own immutable
     * {@link CpuBoundWorkload}.  The submit loop is timing-sensitive:
     * it records submit time immediately before enqueue.  After the
     * per-batch latch is released, latencies are recorded if a
     * {@link LatencyRecorder} is provided.
     *
     * <p>Recording happens on the main thread <em>after</em>
     * {@code latch.await()} returns.  The latch's happens-before
     * guarantees that all timing data written by worker threads is
     * visible.  Every task in the batch is always recorded — there is
     * no sampling or filtering.
     */
    private static void runOneBatch(BenchmarkExecutor executor,
                                    int iterations,
                                    TaskTimingStore batchStore,
                                    Task[] batchTasks,
                                    long baseTaskId,
                                    LatencyRecorder recorder) throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(BATCH_SIZE);

        // -- build tasks (each gets its own immutable CpuBoundWorkload) --
        for (int i = 0; i < BATCH_SIZE; i++) {
            Workload workload = new CpuBoundWorkload(SEED + baseTaskId + i, iterations);
            batchTasks[i] = new Task(baseTaskId + i, i, workload, batchStore, latch);
        }

        // -- submit (timing-sensitive — keep minimal) --
        for (int i = 0; i < BATCH_SIZE; i++) {
            batchStore.recordSubmit(i, System.nanoTime());
            executor.submit(batchTasks[i]);
        }

        // -- await batch completion --
        latch.await();

        // -- record latencies into the measurement recorder --
        // All BATCH_SIZE tasks are always recorded; no subset, no filtering.
        if (recorder != null) {
            for (int i = 0; i < BATCH_SIZE; i++) {
                recorder.record(batchTasks[i]);
            }
        }
    }

    /* ================================================================
     *  Capacity estimation for LatencyRecorder pre-sizing
     * ================================================================ */

    private static int estimateTaskCount(long phaseSeconds) {
        double waves = (double) BATCH_SIZE / WORKER_COUNT;
        double batchSeconds = waves * TARGET_TASK_NANOS / 1_000_000_000.0;
        if (batchSeconds <= 0) batchSeconds = 0.001;

        long batches = (long) (phaseSeconds / batchSeconds);
        long tasks   = batches * BATCH_SIZE;

        // 50 % headroom so LongBuffer never needs to grow
        return (int) Math.min(Math.max(tasks * 3 / 2, MIN_USEFUL_SAMPLES), Integer.MAX_VALUE);
    }

    /* ================================================================
     *  Side-by-side comparison output
     * ================================================================ */

    private static void printComparison(LatencyRecorder shared, LatencyRecorder sharded) {
        System.out.println("========================================");
        System.out.println("  Side-by-Side Comparison");
        System.out.println("========================================");
        System.out.printf("  Shared  recorded: %,d tasks%n", shared.recordedTasks());
        System.out.printf("  Sharded recorded: %,d tasks%n%n", sharded.recordedTasks());
        System.out.printf("%-20s %12s %12s%n", "Metric", "Shared", "Sharded");
        System.out.println("-".repeat(46));
        compareRow("p50 Queue wait",  shared.p50QueueWait(),  sharded.p50QueueWait());
        compareRow("p90 Queue wait",  shared.p90QueueWait(),  sharded.p90QueueWait());
        compareRow("p95 Queue wait",  shared.p95QueueWait(),  sharded.p95QueueWait());
        compareRow("p99 Queue wait",  shared.p99QueueWait(),  sharded.p99QueueWait());
        System.out.println("-".repeat(46));
        compareRow("p50 Execution",   shared.p50Execution(),  sharded.p50Execution());
        compareRow("p90 Execution",   shared.p90Execution(),  sharded.p90Execution());
        compareRow("p95 Execution",   shared.p95Execution(),  sharded.p95Execution());
        compareRow("p99 Execution",   shared.p99Execution(),  sharded.p99Execution());
        System.out.println("-".repeat(46));
        compareRow("p50 End-to-end",  shared.p50EndToEnd(),   sharded.p50EndToEnd());
        compareRow("p90 End-to-end",  shared.p90EndToEnd(),   sharded.p90EndToEnd());
        compareRow("p95 End-to-end",  shared.p95EndToEnd(),   sharded.p95EndToEnd());
        compareRow("p99 End-to-end",  shared.p99EndToEnd(),   sharded.p99EndToEnd());
    }

    private static void compareRow(String label, double sharedNs, double shardedNs) {
        System.out.printf("%-20s %10.3f ms %10.3f ms%n",
                label, sharedNs / 1_000_000.0, shardedNs / 1_000_000.0);
    }
}
