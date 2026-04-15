package com.scott;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Open-loop benchmark harness for {@link SharedExecutor} and {@link ShardedExecutor}.
 *
 * <h3>Submission model</h3>
 * <p>Uses a <em>continuous open-loop producer</em> with backpressure:
 * <ul>
 *   <li>A single producer thread submits tasks for a fixed duration.</li>
 *   <li>For {@link SharedExecutor}: a global {@link Semaphore} with
 *       {@code maxInflight} permits bounds the in-flight count.  Any worker
 *       completion frees a permit, and the next task goes into the single
 *       shared queue.</li>
 *   <li>For {@link ShardedExecutor}: a <b>per-shard ready-channel</b>
 *       replaces the global Semaphore.  When a shard completes a task it
 *       returns its shard ID to the channel; the producer takes the next
 *       ready shard ID and routes the next task there.  This eliminates the
 *       <em>routing–completion mismatch</em> that would otherwise cause
 *       artificial head-of-line blocking and inflated tail latency (see
 *       {@link #runOpenLoopPhaseShardAware}).</li>
 * </ul>
 *
 * <h3>Execution modes</h3>
 * <p>Controlled via the {@code --mode} command-line argument:
 * <ul>
 *   <li>{@code --mode=prepare} — calibrate once, print a fixed
 *       {@link BenchmarkConfig} block, then exit (no executor started)</li>
 *   <li>{@code --mode=shared}  — run only {@link SharedExecutor}</li>
 *   <li>{@code --mode=sharded} — run only {@link ShardedExecutor}</li>
 *   <li>{@code --mode=compare} — run both sequentially and print a
 *       side-by-side comparison (default, preserves legacy behaviour)</li>
 * </ul>
 *
 * <h3>Reproducible cross-JVM workloads</h3>
 * <p>Running each executor in a <em>separate JVM process</em> produces the
 * cleanest JFR recordings.  To guarantee that both processes use the
 * <em>exact same</em> workload:
 * <ol>
 *   <li>Run {@code --mode=prepare} to calibrate and print a fixed config.</li>
 *   <li>Pass the printed values as CLI arguments to subsequent
 *       {@code --mode=shared} and {@code --mode=sharded} runs.</li>
 * </ol>
 *
 * <h3>Example</h3>
 * <pre>
 *   # Step 1 — calibrate once:
 *   java --enable-preview -cp target/classes com.scott.BenchmarkMain --mode=prepare
 *
 *   # Step 2 — run each executor with the SAME fixed config:
 *   java --enable-preview -cp target/classes com.scott.BenchmarkMain \
 *        --mode=shared --iterations=180472 --warmupSeconds=10 \
 *        --measurementSeconds=30 --seed=3735928559
 *
 *   java --enable-preview -cp target/classes com.scott.BenchmarkMain \
 *        --mode=sharded --iterations=180472 --warmupSeconds=10 \
 *        --measurementSeconds=30 --seed=3735928559
 * </pre>
 */
public class BenchmarkMain {

    /* ---- defaults (used when no CLI overrides are provided) ---- */

    private static final long DEFAULT_SEED              = 0xDEADBEEFL;
    private static final long DEFAULT_TARGET_TASK_NANOS = 100_000L;     // ~100 μs per task
    private static final int  DEFAULT_WARMUP_SECONDS    = 3;
    private static final int  DEFAULT_MEASURE_SECONDS   = 10;

    /** Minimum recorded tasks for meaningful percentile analysis. */
    private static final int  MIN_USEFUL_SAMPLES = 100;

    /**
     * Seed offset applied to measurement-phase tasks so warmup and
     * measurement use non-overlapping seed ranges.
     */
    private static final long MEASUREMENT_SEED_OFFSET = 1_000_000_000L;

    /* ---- active config (set once at the start of main) ---- */

    private static BenchmarkConfig config;

    /* ---- phase result record ---- */

    /**
     * Captures the outcome of a single benchmark phase (warmup or measurement).
     */
    private record PhaseResult(Task[] tasks, int submitted, TaskTimingStore store,
                               long elapsedNanos, int backpressureEvents) {}

    public static void main(String[] args) throws Exception {

        // ---- 0. Parse mode ----
        BenchmarkMode mode = BenchmarkMode.fromArgs(args);

        // ---- 1. Resolve config (fixed from CLI or calibrated dynamically) ----
        String configSource = resolveConfig(args);

        // ---- 2. Print configuration ----
        System.out.println();
        System.out.println("=== Benchmark Configuration ===");
        System.out.printf("  Config source     : %s%n", configSource);
        System.out.printf("  Mode              : %s%n", mode);
        System.out.printf("  Workers           : %d%n", config.workerCount());
        System.out.printf("  Max in-flight     : %d%n", config.maxInflight());
        System.out.printf("  Seed              : %d%n", config.seed());
        System.out.printf("  Iterations        : %,d%n", config.iterations());
        System.out.printf("  Warmup            : %d s%n", config.warmupSeconds());
        System.out.printf("  Measurement       : %d s%n", config.measurementSeconds());
        System.out.printf("  Task count        : %s%n",
                config.taskCount() > 0 ? String.format("%,d (fixed)", config.taskCount()) : "unlimited (time-based)");
        System.out.printf("  Task target       : %.1f ms%n", config.targetTaskNanos() / 1_000_000.0);
        System.out.printf("  Submission model  : open-loop (Semaphore-gated, %d permits)%n",
                config.maxInflight());
        System.out.printf("  Debug mode        : %s%n",
                BenchmarkFlags.DEBUG ? "ON (-Dbenchmark.debug=true)" : "OFF (hot-path minimal)");
        System.out.println();

        // ---- 3. Handle PREPARE mode (print fixed config and exit) ----
        if (mode == BenchmarkMode.PREPARE) {
            System.out.println("=== Fixed Config (machine-readable) ===");
            System.out.println(config.toFixedConfigBlock());
            System.out.println();
            System.out.println("=== Paste into shared/sharded commands ===");
            System.out.println("  java --enable-preview -cp target/classes com.scott.BenchmarkMain \\");
            System.out.printf("       --mode=shared  %s%n", config.toCliArgs());
            System.out.println();
            System.out.println("  java --enable-preview -cp target/classes com.scott.BenchmarkMain \\");
            System.out.printf("       --mode=sharded %s%n", config.toCliArgs());
            return;
        }

        // ---- 4. Run selected executor(s) ----
        switch (mode) {
            case SHARED -> {
                runSharedBenchmark();
            }
            case SHARDED -> {
                runShardedBenchmark();
            }
            case COMPARE -> {
                LatencyRecorder sharedRecorder  = runSharedBenchmark();
                System.out.println();
                LatencyRecorder shardedRecorder = runShardedBenchmark();
                System.out.println();
                printComparison(sharedRecorder, shardedRecorder);
            }
            default -> { /* PREPARE already handled above */ }
        }
    }

    /* ================================================================
     *  Config resolution — fixed CLI args or dynamic calibration
     * ================================================================ */

    /**
     * Populates the static {@link #config} field and returns a label
     * describing the config source ({@code "FIXED"} or {@code "CALIBRATED"}).
     */
    private static String resolveConfig(String[] args) {

        // -- attempt fixed config from CLI --
        BenchmarkConfig fixed = BenchmarkConfig.fromArgs(args, DEFAULT_SEED, DEFAULT_TARGET_TASK_NANOS);
        if (fixed != null) {
            config = fixed;
            return "FIXED (from command-line arguments)";
        }

        // -- dynamic calibration --
        System.out.println("=== Calibrating workload ===");
        int iterations = WorkloadCalibrator.calibrateIterations(DEFAULT_TARGET_TASK_NANOS, DEFAULT_SEED);
        System.out.printf("  Calibrated    : %,d iterations%n", iterations);

        // Verify calibration by timing a single task execution
        long verifyStart  = System.nanoTime();
        long verifyResult = new CpuBoundWorkload(DEFAULT_SEED, iterations).execute();
        long verifyNanos  = System.nanoTime() - verifyStart;
        // consume result so JIT cannot eliminate the work
        if (verifyResult == Long.MIN_VALUE) System.out.print("");
        System.out.printf("  Verified      : %.3f ms  (target %.1f ms)%n",
                verifyNanos / 1_000_000.0, DEFAULT_TARGET_TASK_NANOS / 1_000_000.0);

        if (verifyNanos > 10 * DEFAULT_TARGET_TASK_NANOS) {
            System.err.printf("  *** WARNING: verified task is %.1fx slower than target!%n",
                    (double) verifyNanos / DEFAULT_TARGET_TASK_NANOS);
            System.err.println("  *** Calibration may be inaccurate.");
        }
        if (verifyNanos > 0 && verifyNanos < DEFAULT_TARGET_TASK_NANOS / 10) {
            System.err.printf("  *** WARNING: verified task is %.1fx faster than target!%n",
                    (double) DEFAULT_TARGET_TASK_NANOS / verifyNanos);
        }

        // Parse optional worker/maxInflight overrides
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
     *  Standalone executor runs
     * ================================================================ */

    /**
     * Runs the full benchmark (warmup + measurement + reporting) using
     * only {@link SharedExecutor}.  Suitable for standalone JFR recording.
     */
    private static LatencyRecorder runSharedBenchmark() throws InterruptedException {

        System.out.println("========================================");
        System.out.println("  SharedExecutor (single shared queue)");
        System.out.println("========================================");

        SharedExecutor executor = new SharedExecutor(config.workerCount());
        LatencyRecorder recorder = runOpenLoopBenchmark(executor, "SharedExecutor");

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        executor.printQueueDistribution();

        printExecutorConsistency("SharedExecutor",
                executor.getMeasurementSubmitCount(),
                recorder.recordedTasks());

        return recorder;
    }

    /**
     * Runs the full benchmark (warmup + measurement + reporting) using
     * only {@link ShardedExecutor}.  Suitable for standalone JFR recording.
     */
    private static LatencyRecorder runShardedBenchmark() throws InterruptedException {

        System.out.println("========================================");
        System.out.println("  ShardedExecutor (per-worker queue)");
        System.out.println("========================================");

        ShardedExecutor executor = new ShardedExecutor(config.workerCount());
        LatencyRecorder recorder = runOpenLoopBenchmark(executor, "ShardedExecutor");

        executor.shutdown();
        executor.awaitTermination(30, TimeUnit.SECONDS);
        executor.printQueueDistribution();

        long[] measCounts = executor.getMeasurementProcessedCounts();
        long perQueueTotal = 0;
        for (long c : measCounts) perQueueTotal += c;
        printExecutorConsistency("ShardedExecutor",
                perQueueTotal,
                recorder.recordedTasks());

        return recorder;
    }

    /* ================================================================
     *  Open-loop benchmark — warmup + measurement + reporting
     * ================================================================ */

    /**
     * Runs the complete open-loop benchmark: warmup phase, then measurement
     * phase, then records and reports latencies.
     *
     * <p>Both phases submit tasks continuously with backpressure.
     * For {@link SharedExecutor} a global {@link Semaphore} is used;
     * for {@link ShardedExecutor} a per-shard ready-channel is used
     * to avoid routing–completion mismatch.
     */
    private static LatencyRecorder runOpenLoopBenchmark(
            BenchmarkExecutor executor, String label) throws InterruptedException {

        // ---- measure actual task time for accurate capacity estimation ----
        long actualTaskNanos = measureActualTaskNanos();
        System.out.printf("  Actual task time : %.3f ms  (used for capacity estimation)%n",
                actualTaskNanos / 1_000_000.0);

        boolean shardAware = executor instanceof ShardedExecutor;
        if (shardAware) {
            System.out.printf("  Backpressure     : per-shard ready-channel (1 slot/shard × %d shards = %d total)%n",
                    config.workerCount(), config.workerCount());
        } else {
            System.out.printf("  Backpressure     : global Semaphore (%d permits)%n", config.maxInflight());
        }

        // ---- warmup phase ----
        long warmupNanos = config.warmupSeconds() * 1_000_000_000L;
        System.out.printf("  Warmup (%d s, open-loop)...%n", config.warmupSeconds());

        PhaseResult warmup = runOpenLoopPhase(executor, warmupNanos, false, 0L, actualTaskNanos);

        System.out.printf("  Warmup done    : %,d tasks in %.3f s%n",
                warmup.submitted, warmup.elapsedNanos / 1_000_000_000.0);
        if (warmup.backpressureEvents > 0) {
            System.out.printf("  Warmup backpressure events: %,d%n", warmup.backpressureEvents);
        }

        // Release warmup arrays (Task[], TaskTimingStore) before measurement
        // allocation — with sub-ms tasks the warmup phase can produce millions
        // of objects; holding them while measurement allocates its own arrays
        // doubles peak heap usage and can cause OOM.
        //noinspection UnusedAssignment
        warmup = null;

        // ---- measurement phase ----
        long measureNanos = config.measurementSeconds() * 1_000_000_000L;
        System.out.printf("  Measurement (%d s, open-loop)...%n", config.measurementSeconds());

        PhaseResult measurement = runOpenLoopPhase(executor, measureNanos, true, MEASUREMENT_SEED_OFFSET, actualTaskNanos);

        double measureSecs = measurement.elapsedNanos / 1_000_000_000.0;
        double throughput  = measurement.submitted / measureSecs;

        System.out.printf("  Measurement done: %,d tasks in %.3f s%n",
                measurement.submitted, measureSecs);
        if (measurement.backpressureEvents > 0) {
            System.out.printf("  Backpressure events: %,d%n", measurement.backpressureEvents);
        }
        System.out.printf("  Throughput       : %,.1f tasks/s%n", throughput);

        // ---- record latencies (offline — after all tasks completed) ----
        LatencyRecorder recorder = new LatencyRecorder(measurement.submitted);
        for (int i = 0; i < measurement.submitted; i++) {
            recorder.record(measurement.tasks[i]);
        }

        // ---- warn if too few samples ----
        if (recorder.recordedTasks() < MIN_USEFUL_SAMPLES) {
            System.err.printf("  *** WARNING: only %d tasks recorded — too few for percentile analysis.%n",
                    recorder.recordedTasks());
        }

        // ---- print latency summary ----
        System.out.printf("  --- %s Latency Summary ---%n", label);
        System.out.println(recorder.summary());

        // ---- measurement consistency check (harness-level) ----
        System.out.printf("  === Measurement Consistency Check (%s) ===%n", label);
        System.out.printf("    submitted during measurement : %,d%n", measurement.submitted);
        System.out.printf("    latency records              : %,d%n", recorder.recordedTasks());
        if (recorder.recordedTasks() != measurement.submitted) {
            System.err.printf("    *** WARNING: latency records (%d) != submitted (%d) — mismatch!%n",
                    recorder.recordedTasks(), measurement.submitted);
        } else {
            System.out.println("    OK — all counts reconcile.");
        }

        return recorder;
    }

    /* ================================================================
     *  Phase dispatcher — selects global or shard-aware strategy
     * ================================================================ */

    /**
     * Dispatches to the appropriate open-loop phase strategy based on the
     * executor type.
     *
     * <ul>
     *   <li>{@link SharedExecutor} → {@link #runOpenLoopPhaseGlobal}
     *       (global Semaphore)</li>
     *   <li>{@link ShardedExecutor} → {@link #runOpenLoopPhaseShardAware}
     *       (per-shard ready-channel)</li>
     * </ul>
     */
    private static PhaseResult runOpenLoopPhase(
            BenchmarkExecutor executor,
            long phaseNanos,
            boolean isMeasurement,
            long seedOffset,
            long actualTaskNanos) throws InterruptedException {

        if (executor instanceof ShardedExecutor) {
            return runOpenLoopPhaseShardAware(
                    executor, phaseNanos, isMeasurement, seedOffset, actualTaskNanos);
        }
        return runOpenLoopPhaseGlobal(
                executor, phaseNanos, isMeasurement, seedOffset, actualTaskNanos);
    }

    /* ================================================================
     *  Global Semaphore strategy (for SharedExecutor)
     * ================================================================ */

    /**
     * Submits tasks continuously using a single global {@link Semaphore}.
     *
     * <p>This is the natural backpressure model for a single shared queue:
     * any worker completion frees a permit, and the next task enters the
     * one shared queue where any idle worker can pick it up.
     */
    private static PhaseResult runOpenLoopPhaseGlobal(
            BenchmarkExecutor executor,
            long phaseNanos,
            boolean isMeasurement,
            long seedOffset,
            long actualTaskNanos) throws InterruptedException {

        int maxInflight   = config.maxInflight();
        int estimatedMax  = estimateMaxTasks(phaseNanos, actualTaskNanos);

        TaskTimingStore store = new TaskTimingStore(estimatedMax);
        Task[]          tasks = new Task[estimatedMax];

        Semaphore permits       = new Semaphore(maxInflight);
        Runnable  releasePermit = permits::release;

        int  submitted         = 0;
        int  backpressureCount = 0;
        long phaseStart        = System.nanoTime();
        long deadline          = phaseStart + phaseNanos;

        int taskLimit = config.taskCount() > 0 ? config.taskCount() : estimatedMax;

        while (System.nanoTime() < deadline && submitted < taskLimit) {
            if (!permits.tryAcquire()) {
                backpressureCount++;
                permits.acquire();
            }

            int  idx      = submitted;
            long taskSeed = config.seed() + seedOffset + idx;

            Workload w    = new CpuBoundWorkload(taskSeed, config.iterations());
            Task     task = new Task(idx, idx, w, store, releasePermit, isMeasurement);

            store.recordSubmit(idx, System.nanoTime());
            executor.submit(task);

            tasks[idx] = task;
            submitted++;
        }

        if (config.taskCount() == 0 && submitted >= estimatedMax) {
            System.err.printf("  *** WARNING: estimated capacity (%,d) reached — phase may be truncated.%n",
                    estimatedMax);
        }

        permits.acquire(maxInflight);
        permits.release(maxInflight);

        long phaseEnd = System.nanoTime();
        return new PhaseResult(tasks, submitted, store, phaseEnd - phaseStart, backpressureCount);
    }

    /* ================================================================
     *  Per-shard ready-channel strategy (for ShardedExecutor)
     * ================================================================ */

    /**
     * Submits tasks continuously using a <b>per-shard ready-channel</b>
     * instead of a global Semaphore.
     *
     * <h3>Why a different strategy for sharded?</h3>
     * <p>With a global Semaphore and round-robin task IDs, the producer
     * submits to shard {@code (submitted % workerCount)}.  But the permit
     * that was just released came from whichever shard happened to finish
     * first — typically a <em>different</em> shard.  This creates a
     * <b>routing–completion mismatch</b>:</p>
     * <ol>
     *   <li>Shard 5 finishes → global permit released</li>
     *   <li>Producer submits next task → routes to shard {@code (N % 16)},
     *       e.g.&nbsp;shard 3</li>
     *   <li>Shard 3 might still be busy → task queues behind it</li>
     *   <li>Shard 5 is now idle — but no task goes there</li>
     * </ol>
     * <p>Over millions of tasks the <em>aggregate</em> distribution is
     * perfectly uniform (the per-queue counts confirm this), but the
     * <em>instantaneous</em> queue depth is unbalanced.  Some shards
     * accumulate 2–3+ tasks while others sit idle.  This inflates tail
     * latency (p95/p99 queue wait) by 10–15× compared to SharedExecutor,
     * which is <b>not</b> a property of the sharded design — it is a
     * benchmarking artifact caused by the harness.</p>
     *
     * <h3>Fix: completion-aware routing</h3>
     * <p>A {@link ArrayBlockingQueue} of shard IDs acts as a ready-channel.
     * Initially it contains <b>one</b> copy of each shard ID (total =
     * {@code workerCount}).  When a task completes it returns its shard ID
     * to the channel.  The producer takes the next ready shard ID and crafts
     * a {@code taskId} that will route to <em>that</em> shard.</p>
     *
     * <h3>Why perShard = 1?</h3>
     * <p>With {@code perShard > 1} (e.g.&nbsp;2), each shard pipelines
     * multiple tasks: one executing + one pre-queued in the per-worker
     * {@code LinkedBlockingQueue}.  If the executing task takes even
     * slightly longer than average (GC pause, OS scheduling, cache miss),
     * the pre-queued task's queue wait is inflated — and no other worker
     * can steal it.  This is <b>head-of-line blocking</b> within a shard.
     * With {@code perShard = 1} each shard has at most one task at a time;
     * the ready-channel token is returned only when the worker is truly
     * idle.  This eliminates per-shard HOL blocking and produces a fair
     * comparison against SharedExecutor's work-stealing shared queue.</p>
     *
     * <h3>GC note</h3>
     * <p>Shard IDs are 0–{@code workerCount-1}, well within the
     * {@link Integer} cache (−128 to 127), so {@code offer()} / {@code take()}
     * cause zero boxing allocation.  Per-shard callbacks are pre-created
     * (one {@link Runnable} per shard, not per task).</p>
     */
    private static PhaseResult runOpenLoopPhaseShardAware(
            BenchmarkExecutor executor,
            long phaseNanos,
            boolean isMeasurement,
            long seedOffset,
            long actualTaskNanos) throws InterruptedException {

        int workerCount   = config.workerCount();
        // ---- perShard = 1: no pre-queuing ----
        // With perShard > 1 (e.g. 2), each shard pipelines multiple tasks:
        // one executing + one pre-queued.  This causes head-of-line blocking
        // within each shard: if the executing task is even slightly slower
        // than average (GC jitter, OS scheduling), the pre-queued task's
        // queue wait is inflated — and no other worker can steal it.
        // The SharedExecutor doesn't suffer from this because any idle worker
        // grabs the next task from the single shared queue.
        //
        // With perShard = 1 each shard has at most one task at a time.
        // The ready-channel token is only returned when the worker is truly
        // idle, so the producer submits directly to an idle shard — zero
        // head-of-line blocking and a fair comparison against SharedExecutor.
        int perShard      = 1;
        int totalSlots    = perShard * workerCount;
        int estimatedMax  = estimateMaxTasks(phaseNanos, actualTaskNanos);

        TaskTimingStore store = new TaskTimingStore(estimatedMax);
        Task[]          tasks = new Task[estimatedMax];

        // Ready-shard channel: when a worker finishes a task it returns
        // its shard ID here; the producer takes the next available shard.
        ArrayBlockingQueue<Integer> readyShards = new ArrayBlockingQueue<>(totalSlots);
        for (int s = 0; s < workerCount; s++) {
            for (int j = 0; j < perShard; j++) {
                readyShards.add(s);
            }
        }

        // Pre-create one callback per shard (no per-task lambda allocation).
        // Integer.valueOf(s) is cached for 0..127, so offer() is allocation-free.
        Runnable[] shardCallbacks = new Runnable[workerCount];
        for (int i = 0; i < workerCount; i++) {
            final int s = i;
            shardCallbacks[i] = () -> readyShards.offer(s);
        }

        // Per-shard sequence counter — used to compute a taskId that
        // routes to the target shard via ShardedExecutor's hash formula:
        //   shard = Math.floorMod(Long.hashCode(taskId), workerCount)
        // For taskId < Integer.MAX_VALUE: Long.hashCode(x) == (int)x,
        // so taskId % workerCount == targetShard when
        //   taskId = targetShard + seqForShard * workerCount.
        long[] shardSeq = new long[workerCount];

        int  submitted         = 0;
        int  backpressureCount = 0;
        long phaseStart        = System.nanoTime();
        long deadline          = phaseStart + phaseNanos;

        int taskLimit = config.taskCount() > 0 ? config.taskCount() : estimatedMax;

        while (System.nanoTime() < deadline && submitted < taskLimit) {
            // Take the next ready shard (blocks if all shards are at capacity).
            Integer targetShard = readyShards.poll();
            if (targetShard == null) {
                backpressureCount++;
                targetShard = readyShards.take();
            }

            int  idx      = submitted;
            // taskId crafted so ShardedExecutor routes to targetShard
            long taskId   = targetShard + shardSeq[targetShard]++ * workerCount;
            long taskSeed = config.seed() + seedOffset + idx;

            Workload w    = new CpuBoundWorkload(taskSeed, config.iterations());
            Task     task = new Task(taskId, idx, w, store,
                                     shardCallbacks[targetShard], isMeasurement);

            store.recordSubmit(idx, System.nanoTime());
            executor.submit(task);

            tasks[idx] = task;
            submitted++;
        }

        if (config.taskCount() == 0 && submitted >= estimatedMax) {
            System.err.printf("  *** WARNING: estimated capacity (%,d) reached — phase may be truncated.%n",
                    estimatedMax);
        }

        // Drain: collect all totalSlots tokens.  Each token is either an
        // initial slot that was never consumed, or a completion callback's
        // returned shard ID.  Collecting all of them guarantees every
        // in-flight task has finished.
        for (int i = 0; i < totalSlots; i++) {
            readyShards.take();
        }

        long phaseEnd = System.nanoTime();
        return new PhaseResult(tasks, submitted, store, phaseEnd - phaseStart, backpressureCount);
    }

    /* ================================================================
     *  Capacity estimation
     * ================================================================ */

    /**
     * Estimates the maximum number of tasks that could be processed in
     * {@code phaseNanos} nanoseconds, with 2× headroom.  This determines
     * the pre-allocation size for {@link TaskTimingStore} and the task array.
     *
     * <p>Uses the actual measured task time (not the configured target) so
     * the estimate is accurate even when {@code --iterations} overrides the
     * default calibration.
     *
     * <p>The estimate is: {@code workerCount / taskSeconds * phaseSeconds * 2}.
     * A minimum of 4096 is enforced to handle edge cases.
     */
    private static int estimateMaxTasks(long phaseNanos, long actualTaskNanos) {
        // If a fixed task count is configured, use it directly (+ small headroom)
        if (config.taskCount() > 0) {
            return config.taskCount() + 64;
        }
        double phaseSeconds = phaseNanos / 1_000_000_000.0;
        double taskSeconds  = actualTaskNanos / 1_000_000_000.0;
        if (taskSeconds <= 0) taskSeconds = 0.000_001;
        double maxThroughput = config.workerCount() / taskSeconds;
        int estimated = (int) (maxThroughput * phaseSeconds);
        // 2× headroom + minimum floor
        return Math.max(estimated * 2, 4096);
    }

    /**
     * Measures the wall-clock time for a single {@link CpuBoundWorkload}
     * execution using the current config's iterations.
     *
     * <p>Runs a small pilot (200 invocations) to trigger JIT compilation
     * (C1 / OSR) before measuring, so the returned time reflects steady-
     * state performance, not interpreter overhead.  This is essential for
     * accurate capacity estimation: the cold-start time can be 10–25×
     * slower than the JIT-compiled time.
     */
    private static long measureActualTaskNanos() {
        long sink = 0;

        // Pilot: trigger JIT compilation of CpuBoundWorkload.execute()
        for (int i = 0; i < 200; i++) {
            sink += new CpuBoundWorkload(config.seed() + i, config.iterations()).execute();
        }

        // Measure post-compilation (average of 20 runs for stability)
        long start = System.nanoTime();
        int runs = 20;
        for (int i = 0; i < runs; i++) {
            sink += new CpuBoundWorkload(config.seed() + 1000 + i, config.iterations()).execute();
        }
        long elapsed = System.nanoTime() - start;

        // Consume sink to prevent JIT dead-code elimination
        if (sink == Long.MIN_VALUE) System.out.print("");

        return Math.max(elapsed / runs, 1);
    }

    /* ================================================================
     *  Executor-level measurement consistency
     * ================================================================ */

    /**
     * Prints a reconciliation summary comparing executor-reported measurement
     * count and latency records.  Warns on any mismatch.
     */
    private static void printExecutorConsistency(String label,
                                                 long executorMeasurementCount,
                                                 int latencyRecords) {
        System.out.println();
        System.out.printf("  === Executor Consistency Check (%s) ===%n", label);
        System.out.printf("    executor measurement processed : %,d%n", executorMeasurementCount);
        System.out.printf("    latency records                : %,d%n", latencyRecords);

        if (executorMeasurementCount != latencyRecords) {
            System.err.printf("    *** WARNING: executor count (%d) != latency records (%d)%n",
                    executorMeasurementCount, latencyRecords);
        } else {
            System.out.println("    OK — executor and latency counts reconcile.");
        }
    }

    /* ================================================================
     *  Side-by-side comparison output (compare mode only)
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
