package com.scott;

import java.util.Arrays;
import java.util.List;

/**
 * Collects latency data from completed {@link Task} objects and computes
 * percentile statistics (p50, p90, p95, p99) for queue wait time,
 * execution time, and end-to-end latency.
 *
 * <p>Latency samples are stored in primitive {@code long} buffers
 * ({@link LongBuffer}) to avoid {@link Long} boxing and excessive
 * temporary object allocation.  This reduces young-generation GC
 * pressure and minimises measurement interference during benchmark
 * experiments.
 *
 * <p>Percentile computation is performed <em>after</em> all tasks have
 * completed — it is an offline analysis step, not part of the critical
 * path.  Call {@link #record(Task)} (or {@link #recordAll(List)}) for
 * each completed task, then {@link #summary()} to get a formatted report.
 */
public final class LatencyRecorder {

    private final LongBuffer queueWaitNanos;
    private final LongBuffer executionNanos;
    private final LongBuffer endToEndNanos;

    /**
     * Creates a recorder with a default initial capacity (1024).
     * Suitable for ad-hoc runs; for formal benchmarks prefer
     * {@link #LatencyRecorder(int)} with the exact task count.
     */
    public LatencyRecorder() {
        this(1024);
    }

    /**
     * Creates a recorder pre-sized for {@code expectedTasks} samples.
     * When the exact task count is known up front this avoids any
     * runtime buffer growth, eliminating allocation on the recording path.
     *
     * @param expectedTasks anticipated number of tasks to record
     */
    public LatencyRecorder(int expectedTasks) {
        this.queueWaitNanos = new LongBuffer(expectedTasks);
        this.executionNanos = new LongBuffer(expectedTasks);
        this.endToEndNanos  = new LongBuffer(expectedTasks);
    }

    /* ================================================================
     *  Recording
     * ================================================================ */

    /**
     * Records the latency data from a single completed task.
     * Only primitive {@code long} values are stored — no boxing occurs.
     *
     * @param task a task whose {@link Task#run()} has already finished
     */
    public void record(Task task) {
        queueWaitNanos.add(task.queueWaitTimeNanos());
        executionNanos.add(task.executionTimeNanos());
        endToEndNanos.add(task.endToEndLatencyNanos());
    }

    /**
     * Records latency data from every task in the list.
     */
    public void recordAll(List<Task> tasks) {
        for (Task t : tasks) {
            record(t);
        }
    }

    /* ================================================================
     *  Percentile computation
     * ================================================================ */

    /** Returns the p50 (median) of the given metric. */
    public double p50QueueWait()  { return percentile(queueWaitNanos, 50); }
    public double p90QueueWait()  { return percentile(queueWaitNanos, 90); }
    public double p95QueueWait()  { return percentile(queueWaitNanos, 95); }
    public double p99QueueWait()  { return percentile(queueWaitNanos, 99); }

    public double p50Execution()  { return percentile(executionNanos, 50); }
    public double p90Execution()  { return percentile(executionNanos, 90); }
    public double p95Execution()  { return percentile(executionNanos, 95); }
    public double p99Execution()  { return percentile(executionNanos, 99); }

    public double p50EndToEnd()   { return percentile(endToEndNanos, 50); }
    public double p90EndToEnd()   { return percentile(endToEndNanos, 90); }
    public double p95EndToEnd()   { return percentile(endToEndNanos, 95); }
    public double p99EndToEnd()   { return percentile(endToEndNanos, 99); }

    /* ================================================================
     *  Summary
     * ================================================================ */

    /**
     * Returns a formatted multi-line summary of all recorded latencies
     * with p50 / p90 / p95 / p99 percentiles in milliseconds.
     */
    public String summary() {
        if (queueWaitNanos.isEmpty()) {
            return "LatencyRecorder: no data recorded.";
        }

        var sb = new StringBuilder();
        sb.append(String.format("Recorded tasks: %d%n%n", queueWaitNanos.size()));

        sb.append(String.format("%-16s %10s %10s %10s %10s%n",
                "Metric", "p50", "p90", "p95", "p99"));
        sb.append("-".repeat(60)).append('\n');

        sb.append(formatRow("Queue wait",
                p50QueueWait(), p90QueueWait(), p95QueueWait(), p99QueueWait()));
        sb.append(formatRow("Execution",
                p50Execution(), p90Execution(), p95Execution(), p99Execution()));
        sb.append(formatRow("End-to-end",
                p50EndToEnd(), p90EndToEnd(), p95EndToEnd(), p99EndToEnd()));

        return sb.toString();
    }

    /* ================================================================
     *  Internals
     * ================================================================ */

    /**
     * Computes the p-th percentile from a {@link LongBuffer} of nanosecond
     * samples using the nearest-rank method.  Operates entirely on a
     * primitive {@code long[]} copy — no boxing involved.
     *
     * @param data       raw nanosecond samples
     * @param percentile the desired percentile (0–100)
     * @return the percentile value in nanoseconds
     */
    private static double percentile(LongBuffer data, int percentile) {
        if (data.isEmpty()) return 0.0;

        long[] sorted = data.toArray();
        Arrays.sort(sorted);

        // nearest-rank: index = ceil(percentile / 100 * N) - 1, clamped
        int index = (int) Math.ceil(percentile / 100.0 * sorted.length) - 1;
        index = Math.max(0, Math.min(index, sorted.length - 1));
        return sorted[index];
    }

    private static String formatRow(String label, double p50, double p90, double p95, double p99) {
        return String.format("%-16s %9.3f ms %9.3f ms %9.3f ms %9.3f ms%n",
                label,
                p50 / 1_000_000.0,
                p90 / 1_000_000.0,
                p95 / 1_000_000.0,
                p99 / 1_000_000.0);
    }
}

