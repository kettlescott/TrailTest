package com.scott;

/**
 * Classification of benchmark tasks by expected execution duration.
 *
 * <p>Used by {@link TypeAwareDispatcher} to route tasks to the most
 * appropriate execution backend:
 * <ul>
 *   <li>{@link #SHORT}  — lightweight work, best on a sharded queue
 *       (low contention, fast dequeue)</li>
 *   <li>{@link #MEDIUM} — moderate work, routed to the shared queue
 *       (benefits from work-stealing load balancing)</li>
 *   <li>{@link #LONG}   — heavy work, routed to the shared queue
 *       (avoids head-of-line blocking on a single shard)</li>
 * </ul>
 *
 * <p>Each constant carries a human-readable label and a multiplier that
 * {@link BenchmarkMain} uses to scale the calibrated iteration count,
 * producing workloads of different durations from the same base calibration.
 */
public enum TaskType {

    /** ~1× base iterations — fast tasks. */
    SHORT("short", 1),

    /** ~10× base iterations — moderate tasks. */
    MEDIUM("medium", 10),

    /** ~100× base iterations — heavy tasks. */
    LONG("long", 100);

    private final String label;
    private final int iterationMultiplier;

    TaskType(String label, int iterationMultiplier) {
        this.label = label;
        this.iterationMultiplier = iterationMultiplier;
    }

    /** Human-readable name (e.g. "short"). */
    public String label() {
        return label;
    }

    /**
     * Factor applied to the base calibrated iteration count to produce
     * a workload of the corresponding duration.
     */
    public int iterationMultiplier() {
        return iterationMultiplier;
    }
}

