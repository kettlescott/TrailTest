package com.scott;

import java.util.concurrent.CountDownLatch;

/**
 * Benchmark task: pairs a Workload with identity and the full 4-stage
 * timing lifecycle (created / enqueued / start / finish).
 *
 * <pre>
 *   createdNanos   : set by TaskGenerator, before dispatch/routing
 *   enqueuedNanos  : set by Dispatcher, just before the backing
 *                    executor enqueues the task (post-routing)
 *   startNanos     : set by the worker thread, right before run()
 *   finishNanos    : set by the worker thread, right after run()
 *
 *   submit/build overhead = enqueuedNanos - createdNanos
 *   queue wait            = startNanos    - enqueuedNanos
 *   execution             = finishNanos   - startNanos
 *   end-to-end            = finishNanos   - createdNanos
 * </pre>
 *
 * <p>If a caller path does not call {@link #markEnqueued()} (e.g. the
 * convenience {@code submitNew} entry points), the enqueued timestamp
 * falls back to {@code createdNanos}, so {@code queueWait} gracefully
 * degrades to {@code startNanos - createdNanos}.
 *
 * <p>Memory visibility: plain {@code long} fields. The created/enqueued
 * stamps are written by the submitting thread; start/finish/result by a
 * single worker. Readers only touch these after the completion signal
 * ({@link CountDownLatch#countDown()} or the {@code onComplete}
 * callback's happens-before via a {@link java.util.concurrent.Semaphore}
 * release), so no {@code volatile} is required.
 */
public final class Task implements Runnable {

    private final long taskId;
    private final TaskType type;
    private final int iterations;
    private final long createdNanos;
    private final boolean measurement;
    private final Workload workload;
    private final long workloadSeed;
    private final CountDownLatch completionLatch;
    private final Runnable onComplete;

    private long enqueuedNanos;
    private long startNanos;
    private long finishNanos;
    private long workloadResult;

    public Task(long taskId,
                TaskType type,
                int iterations,
                long createdNanos,
                boolean measurement,
                Workload workload,
                CountDownLatch completionLatch,
                Runnable onComplete) {
        this.taskId = taskId;
        this.type = type == null ? TaskType.SHORT : type;
        this.iterations = iterations;
        this.createdNanos = createdNanos;
        this.measurement = measurement;
        this.workload = workload;
        this.workloadSeed = 0L;
        this.completionLatch = completionLatch;
        this.onComplete = onComplete;
    }

    public Task(long taskId,
                TaskType type,
                int iterations,
                long createdNanos,
                boolean measurement,
                long workloadSeed,
                Runnable onComplete) {
        this.taskId = taskId;
        this.type = type == null ? TaskType.SHORT : type;
        this.iterations = iterations;
        this.createdNanos = createdNanos;
        this.measurement = measurement;
        this.workload = null;
        this.workloadSeed = workloadSeed;
        this.completionLatch = null;
        this.onComplete = onComplete;
    }

    public Task(long taskId,
                TaskType type,
                int iterations,
                long createdNanos,
                boolean measurement,
                Workload workload,
                Runnable onComplete) {
        this(taskId, type, iterations, createdNanos, measurement, workload, null, onComplete);
    }

    /**
     * Records the moment the task is about to be handed to the backing
     * queue. Called on the submitting thread: one {@code System.nanoTime()}
     * plus a plain store, no allocation.
     */
    public void markEnqueued() {
        this.enqueuedNanos = System.nanoTime();
    }

    @Override
    public void run() {
        startNanos = System.nanoTime();
        try {
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

    /** {@code enqueued - created}: dispatcher/routing cost. */
    public long submitOverheadNanos() {
        long e = enqueuedNanos == 0L ? createdNanos : enqueuedNanos;
        return e - createdNanos;
    }

    /** {@code start - enqueued} (falls back to {@code start - created}). */
    public long queueWaitTimeNanos() {
        long base = enqueuedNanos == 0L ? createdNanos : enqueuedNanos;
        return startNanos - base;
    }

    /** {@code finish - start}: time inside {@link Workload#execute()}. */
    public long executionTimeNanos() {
        return finishNanos - startNanos;
    }

    /** {@code finish - created}: end-to-end latency. */
    public long endToEndLatencyNanos() {
        return finishNanos - createdNanos;
    }

    public long taskId()           { return taskId; }
    public TaskType type()         { return type; }
    public TaskType taskType()     { return type; }
    public int iterations()        { return iterations; }
    public long createdNanos()     { return createdNanos; }
    /** @deprecated use {@link #createdNanos()}. */
    @Deprecated public long submitNanos() { return createdNanos; }
    public long enqueuedNanos()    { return enqueuedNanos; }
    public long startNanos()       { return startNanos; }
    public long finishNanos()      { return finishNanos; }
    public long workloadResult()   { return workloadResult; }
    public boolean isMeasurement() { return measurement; }
    public Workload workload()     { return workload; }

    private static double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }

    @Override
    public String toString() {
        return String.format(
                "[Task-%d]  submitOverhead=%.3f ms  queueWait=%.3f ms  execution=%.3f ms  endToEnd=%.3f ms",
                taskId,
                nsToMs(submitOverheadNanos()),
                nsToMs(queueWaitTimeNanos()),
                nsToMs(executionTimeNanos()),
                nsToMs(endToEndLatencyNanos())
        );
    }
}

