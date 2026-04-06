package com.scott;

/**
 * Immutable record capturing the three key timestamps for a submitted task
 * and derived latency measurements.
 *
 * <ul>
 *   <li><b>submitNanos</b>  – time the task was handed to the executor</li>
 *   <li><b>startNanos</b>   – time a worker thread began executing the task</li>
 *   <li><b>finishNanos</b>  – time the worker thread finished the task</li>
 * </ul>
 */
public final class TaskMetrics {

    private final String taskName;
    private final long submitNanos;
    private volatile long startNanos;
    private volatile long finishNanos;

    public TaskMetrics(String taskName, long submitNanos) {
        this.taskName = taskName;
        this.submitNanos = submitNanos;
    }

    /* ---- mutators (called by the executor internals) ---- */

    public void markStart(long nanos) {
        this.startNanos = nanos;
    }

    public void markFinish(long nanos) {
        this.finishNanos = nanos;
    }

    /* ---- accessors ---- */

    public String getTaskName()   { return taskName; }
    public long getSubmitNanos()  { return submitNanos; }
    public long getStartNanos()   { return startNanos; }
    public long getFinishNanos()  { return finishNanos; }

    /**
     * Time the task spent waiting in the queue (start − submit).
     */
    public long getQueueWaitNanos() {
        return startNanos - submitNanos;
    }

    /**
     * Wall-clock execution time (finish − start).
     */
    public long getExecutionNanos() {
        return finishNanos - startNanos;
    }

    /**
     * End-to-end completion time (finish − submit).
     */
    public long getCompletionNanos() {
        return finishNanos - submitNanos;
    }

    /* ---- convenience formatting (nanoseconds → milliseconds) ---- */

    private static double nsToMs(long ns) {
        return ns / 1_000_000.0;
    }

    @Override
    public String toString() {
        return String.format(
                "[%s]  queueWait=%.3f ms  execution=%.3f ms  completion=%.3f ms",
                taskName,
                nsToMs(getQueueWaitNanos()),
                nsToMs(getExecutionNanos()),
                nsToMs(getCompletionNanos())
        );
    }
}

