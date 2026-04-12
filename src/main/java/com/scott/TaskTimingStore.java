package com.scott;

/**
 * Centralized timing store for benchmark tasks.
 *
 * <p>Designed for latency experiments where measurement overhead matters.
 * All timestamps are kept in three pre-allocated {@code long[]} arrays
 * (submit, start, finish), indexed by a task's ordinal position.
 *
 * <h3>Why primitive arrays?</h3>
 * <ul>
 *   <li>No boxing — avoids {@code Long} object allocation on the hot path.</li>
 *   <li>No per-field {@code volatile} — worker threads write by index;
 *       visibility is guaranteed by the {@link java.util.concurrent.CountDownLatch}
 *       happens-before that the caller uses before reading.</li>
 *   <li>Contiguous memory layout — improves cache locality when the
 *       recorder iterates over all entries after the benchmark run.</li>
 * </ul>
 */
public final class TaskTimingStore {

    private final long[] submitTimesNanos;
    private final long[] startTimesNanos;
    private final long[] finishTimesNanos;

    /**
     * Creates a timing store pre-sized for {@code taskCount} tasks.
     *
     * @param taskCount total number of tasks that will be recorded
     */
    public TaskTimingStore(int taskCount) {
        if (taskCount <= 0) {
            throw new IllegalArgumentException("taskCount must be positive, got " + taskCount);
        }
        this.submitTimesNanos = new long[taskCount];
        this.startTimesNanos  = new long[taskCount];
        this.finishTimesNanos = new long[taskCount];
    }

    /* ---- writers (called on the hot path) ---- */

    /**
     * Records submit timestamp.  Called by the main (submitting) thread,
     * not by workers, so the bounds check is always performed.
     */
    public void recordSubmit(int taskIndex, long submitTimeNanos) {
        checkIndex(taskIndex);
        submitTimesNanos[taskIndex] = submitTimeNanos;
    }

    /**
     * Records start timestamp.  Called by worker threads on the hot path.
     * Bounds check is only performed when {@link BenchmarkFlags#DEBUG}
     * is {@code true}; otherwise this is a single array write.
     */
    public void recordStart(int taskIndex, long startTimeNanos) {
        if (BenchmarkFlags.DEBUG) checkIndex(taskIndex);
        startTimesNanos[taskIndex] = startTimeNanos;
    }

    /**
     * Records finish timestamp.  Called by worker threads on the hot path.
     * Same DEBUG gating as {@link #recordStart}.
     */
    public void recordFinish(int taskIndex, long finishTimeNanos) {
        if (BenchmarkFlags.DEBUG) checkIndex(taskIndex);
        finishTimesNanos[taskIndex] = finishTimeNanos;
    }

    /* ---- readers (called after all tasks complete) ---- */

    public long submitTimeNanos(int taskIndex) {
        checkIndex(taskIndex);
        return submitTimesNanos[taskIndex];
    }

    public long startTimeNanos(int taskIndex) {
        checkIndex(taskIndex);
        return startTimesNanos[taskIndex];
    }

    public long finishTimeNanos(int taskIndex) {
        checkIndex(taskIndex);
        return finishTimesNanos[taskIndex];
    }

    /** Returns the number of task slots in this store. */
    public int size() {
        return submitTimesNanos.length;
    }

    /* ---- bounds check ---- */

    private void checkIndex(int taskIndex) {
        if (taskIndex < 0 || taskIndex >= submitTimesNanos.length) {
            throw new IndexOutOfBoundsException(
                    "taskIndex " + taskIndex + " out of range [0, " + submitTimesNanos.length + ")");
        }
    }
}

