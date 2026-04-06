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
 * thread and read by the main thread <em>after</em> {@code completionLatch.await()}
 * returns.  {@link CountDownLatch#countDown()} happens-before
 * {@link CountDownLatch#await()}, so all stores performed before
 * {@code countDown()} are visible to the awaiting thread.
 * Therefore no {@code volatile} is needed on any timing field or on
 * {@code workloadResult}.</p>
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

    /* ---- recorded during run() — no volatile needed (see class doc) ---- */

    private long workloadResult;

    /**
     * Full constructor.
     *
     * @param taskId          logical identifier (may differ from {@code taskIndex})
     * @param taskIndex       index into the {@code timingStore} arrays
     * @param workload        the synthetic workload to execute
     * @param timingStore     shared store where submit/start/finish times are recorded
     * @param completionLatch optional latch counted down when the task finishes
     *                        ({@code null} if not needed)
     */
    public Task(long taskId,
                int taskIndex,
                Workload workload,
                TaskTimingStore timingStore,
                CountDownLatch completionLatch) {
        this.taskId          = taskId;
        this.taskIndex       = taskIndex;
        this.workload        = workload;
        this.timingStore     = timingStore;
        this.completionLatch = completionLatch;
    }

    /**
     * Convenience constructor without a latch.
     */
    public Task(long taskId, int taskIndex, Workload workload, TaskTimingStore timingStore) {
        this(taskId, taskIndex, workload, timingStore, null);
    }

    /* ================================================================
     *  Execution
     * ================================================================ */

    /**
     * Records start time, executes the workload, records finish time.
     * The {@link CountDownLatch} (if present) is counted down in the
     * {@code finally} block so the latch is released even on failure,
     * and the happens-before edge is established for all preceding stores.
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

