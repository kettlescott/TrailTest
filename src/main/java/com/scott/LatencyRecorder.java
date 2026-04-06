package com.scott;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects latency data from completed {@link Task} objects and computes
 * percentile statistics (p50, p90, p95, p99) for queue wait time,
 * execution time, and end-to-end latency.
 *
 * <p>All data is kept in memory using standard Java collections.
 * Call {@link #record(Task)} for each completed task, then
 * {@link #summary()} to get a formatted report.
 */
public final class LatencyRecorder {

    private final List<Long> queueWaitNanos  = new ArrayList<>();
    private final List<Long> executionNanos  = new ArrayList<>();
    private final List<Long> endToEndNanos   = new ArrayList<>();

    /* ================================================================
     *  Recording
     * ================================================================ */

    /**
     * Records the latency data from a single completed task.
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
     * Computes the p-th percentile from an unsorted list of nanosecond values
     * using the nearest-rank method.
     *
     * @param data       raw nanosecond samples
     * @param percentile the desired percentile (0–100)
     * @return the percentile value in nanoseconds
     */
    private static double percentile(List<Long> data, int percentile) {
        if (data.isEmpty()) return 0.0;

        var sorted = new ArrayList<>(data);
        Collections.sort(sorted);

        // nearest-rank: index = ceil(percentile / 100 * N) - 1, clamped
        int index = (int) Math.ceil(percentile / 100.0 * sorted.size()) - 1;
        index = Math.clamp(index, 0, sorted.size() - 1);
        return sorted.get(index);
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

