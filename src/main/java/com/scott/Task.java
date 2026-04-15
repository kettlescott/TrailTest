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

    /* ---- identity (set at construction) ---- */

    private final long             taskId;
    private final int              taskIndex;
    private final Workload         workload;
    private final TaskTimingStore  timingStore;
    private final CountDownLatch   completionLatch;   // nullable
    private final Runnable         onComplete;        // nullable — open-loop callback

    /**
     * Benchmark phase flag.  {@code true} if this task belongs to the
     * measurement window; {@code false} for warmup.  Set once at
     * construction and never changed.  Workers use this to maintain
     * separate measurement-only counters so that per-queue distribution
     * and latency recording are consistent.
     */
    private final boolean measurement;

    /**
     * Classification of this task for type-aware routing.
     * Never {@code null} — defaults to {@link TaskType#SHORT}.
     */
    private final TaskType taskType;

    /* ---- recorded during run() — no volatile needed (see class doc) ---- */

    private long workloadResult;

    /**
     * Master constructor — accepts both latch, callback, and task type.
     */
    public Task(long taskId,
                int taskIndex,
                Workload workload,
                TaskTimingStore timingStore,
                CountDownLatch completionLatch,
                Runnable onComplete,
                boolean measurement,
                TaskType taskType) {
        this.taskId          = taskId;
        this.taskIndex       = taskIndex;
        this.workload        = workload;
        this.timingStore     = timingStore;
        this.completionLatch = completionLatch;
        this.onComplete      = onComplete;
        this.measurement     = measurement;
        this.taskType        = taskType != null ? taskType : TaskType.SHORT;
    }

    /**
     * Backward-compatible 7-arg constructor — defaults to {@link TaskType#SHORT}.
     */
    public Task(long taskId,
                int taskIndex,
                Workload workload,
                TaskTimingStore timingStore,
                CountDownLatch completionLatch,
                Runnable onComplete,
                boolean measurement) {
        this(taskId, taskIndex, workload, timingStore, completionLatch, onComplete, measurement, TaskType.SHORT);
    }

    /**
     * Latch-based constructor (backward compatible — closed-loop batches).
     */
    public Task(long taskId,
                int taskIndex,
                Workload workload,
                TaskTimingStore timingStore,
                CountDownLatch completionLatch,
                boolean measurement) {
        this(taskId, taskIndex, workload, timingStore, completionLatch, null, measurement, TaskType.SHORT);
    }

    /**
     * Callback-based constructor (open-loop submission).
     */
    public Task(long taskId,
                int taskIndex,
                Workload workload,
                TaskTimingStore timingStore,
                Runnable onComplete,
                boolean measurement) {
        this(taskId, taskIndex, workload, timingStore, null, onComplete, measurement, TaskType.SHORT);
    }

    /**
     * Convenience constructor without a latch or callback (defaults to non-measurement).
     */
    public Task(long taskId, int taskIndex, Workload workload, TaskTimingStore timingStore) {
        this(taskId, taskIndex, workload, timingStore, null, null, false, TaskType.SHORT);
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
        timingStore.recordStart(taskIndex, System.nanoTime());
        try {
            workloadResult = workload.execute();
        } finally {
            timingStore.recordFinish(taskIndex, System.nanoTime());
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
        return startTimeNanos() - submitTimeNanos();
    }

    /** Wall-clock time spent inside {@link Workload#execute()}. */
    public long executionTimeNanos() {
        return finishTimeNanos() - startTimeNanos();
    }

    /** End-to-end latency from submission to completion. */
    public long endToEndLatencyNanos() {
        return finishTimeNanos() - submitTimeNanos();
    }

    /* ================================================================
     *  Accessors
     * ================================================================ */

    public long             taskId()           { return taskId; }
    public int              taskIndex()        { return taskIndex; }
    public long             workloadResult()   { return workloadResult; }
    public boolean          isMeasurement()    { return measurement; }
    public TaskType         taskType()         { return taskType; }
    public Workload         getWorkload()      { return workload; }
    public CountDownLatch   getCompletionLatch() { return completionLatch; }

    /** Reads submit timestamp from the backing {@link TaskTimingStore}. */
    public long submitTimeNanos() {
        return timingStore.submitTimeNanos(taskIndex);
    }

    /** Reads start timestamp from the backing {@link TaskTimingStore}. */
    public long startTimeNanos() {
        return timingStore.startTimeNanos(taskIndex);
    }

    /** Reads finish timestamp from the backing {@link TaskTimingStore}. */
    public long finishTimeNanos() {
        return timingStore.finishTimeNanos(taskIndex);
    }

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
