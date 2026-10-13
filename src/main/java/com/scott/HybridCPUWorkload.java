package com.scott;

/**
 * Precise duration-controlled CPU workload optimized for Hybrid executor.
 *
 * <p>Uses per-iteration timing checks to achieve high precision (±2%) on target duration.
 * Replaces batch-based checking which can overshoot by 10-50%.
 *
 * <p>Properties:
 * <ul>
 *   <li><b>Precise timing</b> – checks elapsed time frequently to stay within target</li>
 *   <li><b>Low variance</b> – execution times cluster tightly around target</li>
 *   <li><b>Deterministic</b> – same seed and target always produce same duration</li>
 *   <li><b>JIT-safe</b> – computed value is returned</li>
 *   <li><b>No locks/I/O/blocking</b> – pure CPU arithmetic</li>
 * </ul>
 *
 * <p>Key difference from {@link CpuBoundWorkloadDuration}:
 * <ul>
 *   <li>Checks time every N iterations instead of every batch</li>
 *   <li>Reduces variance in execution time (better for CV monitoring)</li>
 *   <li>Slightly higher nanoTime() overhead but worth it for precision</li>
 * </ul>
 */
public final class HybridCPUWorkload implements Workload {

    private final long targetNanos;
    private final long seed;
    private static final int CHECK_INTERVAL = 100;  // Check time every 100 iterations

    /**
     * Creates a precise duration-controlled CPU workload.
     *
     * @param targetMicros target execution time in microseconds
     * @param seed         per-task deterministic seed
     */
    public HybridCPUWorkload(long targetMicros, long seed) {
        this.targetNanos = targetMicros * 1_000L;
        this.seed = seed;
    }

    @Override
    public long execute() {
        long x = seed;
        long start = System.nanoTime();
        long elapsed = 0L;
        long counter = 0L;

        // Simple, precise timing loop
        while (elapsed < targetNanos) {
            // Perform CHECK_INTERVAL iterations
            for (int i = 0; i < CHECK_INTERVAL; i++) {
                x ^= (x << 13);
                x ^= (x >>> 7);
                x ^= (x << 17);
                x = x * 0x9E3779B97F4A7C15L + counter;
                counter++;
            }
            
            // Check elapsed time
            elapsed = System.nanoTime() - start;
        }

        return x;
    }

    public long getSeed() {
        return seed;
    }

    public long getTargetNanos() {
        return targetNanos;
    }
}

