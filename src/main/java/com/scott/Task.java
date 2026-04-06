package com.scott;

import java.util.concurrent.CountDownLatch;

/**
 * A self-contained benchmark task that pairs a {@link Workload} with its
 * identity and full timing lifecycle.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   construct (submitTimeNanos captured)
 *       ↓
 *   sits in queue  →  queue wait  = startTimeNanos − submitTimeNanos
 *       ↓
 *   run()          →  execution   = finishTimeNanos − startTimeNanos
 *       ↓
 *   done           →  end-to-end  = finishTimeNanos − submitTimeNanos
 * </pre>
 *
 * <p>The {@link #run()} method records start/finish timestamps around
 * {@link Workload#execute()} and stores the returned result so the JIT
 * cannot eliminate the computation as dead code.
 *
 * <p>An optional {@link CountDownLatch} is counted down in a {@code finally}
 * block so callers can reliably await completion of a batch of tasks.
 */
public final class Task implements Runnable {

    /* ---- identity (set at construction) ---- */

    private final long             taskId;
    private final long             submitTimeNanos;
    private final Workload         workload;
    private final CountDownLatch   completionLatch;   // nullable

    /* ---- recorded during run() ---- */

    private volatile long startTimeNanos;
    private volatile long finishTimeNanos;
    private volatile long workloadResult;

    /**
     * Full constructor.
     *
     * @param taskId          unique identifier for this task
     * @param submitTimeNanos timestamp (via {@link System#nanoTime()}) when the
     *                        task was submitted to the executor
     * @param workload        the synthetic workload to execute
     * @param completionLatch optional latch counted down when the task finishes
     *                        ({@code null} if not needed)
     */
    public Task(long taskId, long submitTimeNanos, Workload workload, CountDownLatch completionLatch) {
        this.taskId          = taskId;
        this.submitTimeNanos = submitTimeNanos;
        this.workload        = workload;
        this.completionLatch = completionLatch;
    }

    /**
     * Convenience constructor without a latch.
     */
    public Task(long taskId, long submitTimeNanos, Workload workload) {
        this(taskId, submitTimeNanos, workload, null);
    }

    /* ================================================================
     *  Execution
     * ================================================================ */

    /**
     * Executes the workload, recording start time, finish time, and the
     * computed result.  If a {@link CountDownLatch} was provided it is
     * counted down in the {@code finally} block — even on failure.
     */
    @Override
    public void run() {
        try {
            startTimeNanos  = System.nanoTime();
            workloadResult  = workload.execute();
            finishTimeNanos = System.nanoTime();
        } finally {
            if (completionLatch != null) {
                completionLatch.countDown();
            }
        }
    }

    /* ================================================================
     *  Latency queries (all in nanoseconds)
     * ================================================================ */

    /**
     * Time the task spent waiting in the queue before a worker picked it up.
     * <p>{@code startTimeNanos − submitTimeNanos}
     */
    public long queueWaitTimeNanos() {
        return startTimeNanos - submitTimeNanos;
    }

    /**
     * Wall-clock time spent inside {@link Workload#execute()}.
     * <p>{@code finishTimeNanos − startTimeNanos}
     */
    public long executionTimeNanos() {
        return finishTimeNanos - startTimeNanos;
    }

    /**
     * End-to-end latency from submission to completion.
     * <p>{@code finishTimeNanos − submitTimeNanos}
     */
    public long endToEndLatencyNanos() {
        return finishTimeNanos - submitTimeNanos;
    }

    /* ================================================================
     *  Accessors
     * ================================================================ */

    public long             getTaskId()          { return taskId; }
    public long             getSubmitTimeNanos() { return submitTimeNanos; }
    public long             getStartTimeNanos()  { return startTimeNanos; }
    public long             getFinishTimeNanos() { return finishTimeNanos; }
    public long             getWorkloadResult()  { return workloadResult; }
    public Workload         getWorkload()        { return workload; }
    public CountDownLatch   getCompletionLatch() { return completionLatch; }

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

