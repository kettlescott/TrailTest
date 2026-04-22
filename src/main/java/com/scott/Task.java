package com.scott;

import java.util.concurrent.CountDownLatch;

/**
 * A self-contained benchmark task that pairs a {@link Workload} with its
 * identity and full timing lifecycle.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   submit  →  timingStore.recordSubmit(taskIndex, now)   (caller, before enqueue)
 *       ↓
 *   queue   →  queue wait  = startTimeNanos − submitTimeNanos
 *       ↓
 *   run()   →  timingStore.recordStart  / recordFinish    (worker thread)
 *       ↓
 *   done    →  end-to-end  = finishTimeNanos − submitTimeNanos
 * </pre>
 *
 * <h3>Memory-visibility contract</h3>
 * <p>Timing data is written to a shared {@link TaskTimingStore} by the worker
 * thread and read by the main thread <em>after</em> all in-flight tasks have
 * completed (either via {@code completionLatch.await()} or Semaphore drain).
 * The synchronization action (latch countDown or Semaphore release)
 * establishes a happens-before relationship, so no {@code volatile} is needed
 * on any timing field or on {@code workloadResult}.</p>
 *
 * <h3>Completion signaling</h3>
 * <p>Two optional completion mechanisms are supported:
 * <ul>
 *   <li>{@link CountDownLatch} — for per-batch closed-loop submission</li>
 *   <li>{@link Runnable} callback ({@code onComplete}) — for open-loop
 *       submission with Semaphore-gated backpressure</li>
 * </ul>
 * Both may be {@code null}; both are invoked in the {@code finally} block
 * of {@link #run()} so they fire even on workload failure.</p>
 *
 * <h3>Design rationale</h3>
 * <p>Timestamps are stored in pre-allocated {@code long[]} arrays inside
 * {@link TaskTimingStore} rather than in per-task object fields.  This
 * eliminates per-field {@code volatile} barriers on the hot path, avoids
 * extra object-header overhead, and improves cache locality when the
 * recorder iterates over results after the run.</p>
 */
public final class Task implements Runnable {

    private final long taskId;
    private final TaskType type;
    private final int iterations;
    private final long submitNanos;
    private final boolean measurement;
    private final Workload workload;
    private final long workloadSeed;
    private final CountDownLatch completionLatch;
    private final Runnable onComplete;

    private long startNanos;
    private long finishNanos;
    private long workloadResult;

    public Task(long taskId,
                TaskType type,
                int iterations,
                long submitNanos,
                boolean measurement,
                Workload workload,
                CountDownLatch completionLatch,
                Runnable onComplete) {
        this.taskId = taskId;
        this.type = type == null ? TaskType.SHORT : type;
        this.iterations = iterations;
        this.submitNanos = submitNanos;
        this.measurement = measurement;
        this.workload = workload;
        this.workloadSeed = 0L;
        this.completionLatch = completionLatch;
        this.onComplete = onComplete;
    }

    public Task(long taskId,
                TaskType type,
                int iterations,
                long submitNanos,
                boolean measurement,
                long workloadSeed,
                Runnable onComplete) {
        this.taskId = taskId;
        this.type = type == null ? TaskType.SHORT : type;
        this.iterations = iterations;
        this.submitNanos = submitNanos;
        this.measurement = measurement;
        this.workload = null;
        this.workloadSeed = workloadSeed;
        this.completionLatch = null;
        this.onComplete = onComplete;
    }

    public Task(long taskId,
                TaskType type,
                int iterations,
                long submitNanos,
                boolean measurement,
                Workload workload,
                Runnable onComplete) {
        this(taskId, type, iterations, submitNanos, measurement, workload, null, onComplete);
    }

    /* ================================================================
     *  Execution
     * ================================================================ */

    /**
     * Records start time, executes the workload, records finish time.
     *
     * <p>Both the {@link CountDownLatch} (if present) and the
     * {@code onComplete} callback (if present) are invoked in the
     * {@code finally} block so they fire even on failure and establish
     * the necessary happens-before edges for all preceding stores.
     */
    @Override
    public void run() {
        startNanos = System.nanoTime();
        try {
            // New YAML-driven path always supplies a concrete Workload.
            // The (workload==null) fallback is kept for legacy callers
            // that still build Tasks from a raw (seed, iterations) pair.
            workloadResult = workload != null
                    ? workload.execute()
                    : CpuBoundWorkload.execute(workloadSeed, iterations);
        } finally {
            finishNanos = System.nanoTime();
            if (completionLatch != null) {
                completionLatch.countDown();
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /* ================================================================
     *  Latency queries (all in nanoseconds)
     *
     *  Each method delegates to timingStore — no per-task fields involved.
     * ================================================================ */

    /** Time the task spent waiting in the queue before a worker picked it up. */
    public long queueWaitTimeNanos() {
        return startNanos - submitNanos;
    }

    /** Wall-clock time spent inside {@link Workload#execute()}. */
    public long executionTimeNanos() {
        return finishNanos - startNanos;
    }

    /** End-to-end latency from submission to completion. */
    public long endToEndLatencyNanos() {
        return finishNanos - submitNanos;
    }

    /* ================================================================
     *  Accessors
     * ================================================================ */

    public long taskId() { return taskId; }
    public TaskType type() { return type; }
    public TaskType taskType() { return type; }
    public int iterations() { return iterations; }
    public long submitNanos() { return submitNanos; }
    public long workloadResult() { return workloadResult; }
    public boolean isMeasurement() { return measurement; }
    public Workload workload() { return workload; }

    /* ================================================================
     *  Formatting
     * ================================================================ */

    private static double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }

    @Override
    public String toString() {
        return String.format(
                "[Task-%d]  queueWait=%.3f ms  execution=%.3f ms  endToEnd=%.3f ms",
                taskId,
                nsToMs(queueWaitTimeNanos()),
                nsToMs(executionTimeNanos()),
                nsToMs(endToEndLatencyNanos())
        );
    }
}
