package com.scott;

import java.time.Duration;

/**
 * Calibrates {@link CpuBoundWorkload} iteration counts so that
 * {@code execute()} takes approximately a desired wall-clock duration.
 *
 * <h3>Algorithm</h3>
 * <ol>
 *   <li>Run a small <em>pilot</em> workload to measure the cost-per-iteration
 *       on the current hardware.</li>
 *   <li>Linearly extrapolate the iteration count for the requested target time.</li>
 *   <li>Execute a <em>verification</em> pass and apply a single correction factor
 *       to compensate for JIT warm-up or frequency scaling.</li>
 * </ol>
 *
 * <p>The whole process is deterministic (no randomness, no I/O, no locks) and
 * uses only {@link System#nanoTime()} for measurement.
 */
public final class WorkloadCalibrator {

    /** Iterations used for the initial pilot measurement. */
    private static final int PILOT_ITERATIONS = 50_000;

    /** Minimum iteration count returned by calibration. */
    private static final int MIN_ITERATIONS = 1;

    /* ---- pre-defined target durations ---- */

    private static final long SHORT_NANOS  = Duration.ofMillis(1).toNanos();    //  1 ms
    private static final long MEDIUM_NANOS = Duration.ofMillis(10).toNanos();   // 10 ms
    private static final long LONG_NANOS   = Duration.ofMillis(100).toNanos();  // 100 ms

    // Utility class – no instantiation
    private WorkloadCalibrator() { }

    /* ================================================================
     *  Core calibration
     * ================================================================ */

    /**
     * Estimates the number of iterations required so that
     * {@code new CpuBoundWorkload(seed, n).execute()} takes approximately
     * {@code targetNanos} nanoseconds on the current hardware.
     *
     * @param targetNanos desired execution time in nanoseconds (&gt; 0)
     * @param seed        seed forwarded to {@link CpuBoundWorkload}
     * @return estimated iteration count (always &ge; 1)
     */
    public static int calibrateIterations(long targetNanos, long seed) {
        if (targetNanos <= 0) {
            throw new IllegalArgumentException("targetNanos must be positive, got " + targetNanos);
        }

        // Step 1 – pilot: measure how long PILOT_ITERATIONS takes
        long pilotNanos = timeWorkload(seed, PILOT_ITERATIONS);

        // Guard against a degenerate zero measurement
        if (pilotNanos <= 0) {
            pilotNanos = 1;
        }

        // Step 2 – linear extrapolation
        long estimated = (long) ((double) targetNanos / pilotNanos * PILOT_ITERATIONS);
        int firstGuess = clampIterations(estimated);

        // Step 3 – verification pass + single correction
        long verifyNanos = timeWorkload(seed, firstGuess);

        if (verifyNanos <= 0) {
            verifyNanos = 1;
        }

        long corrected = (long) ((double) targetNanos / verifyNanos * firstGuess);
        return clampIterations(corrected);
    }

    /* ================================================================
     *  Convenience helpers – predefined durations
     * ================================================================ */

    /**
     * Calibrates a <em>short</em> workload (~1 ms).
     *
     * @param seed seed for the workload
     * @return calibrated iteration count
     */
    public static int shortWorkload(long seed) {
        return calibrateIterations(SHORT_NANOS, seed);
    }

    /**
     * Calibrates a <em>medium</em> workload (~10 ms).
     *
     * @param seed seed for the workload
     * @return calibrated iteration count
     */
    public static int mediumWorkload(long seed) {
        return calibrateIterations(MEDIUM_NANOS, seed);
    }

    /**
     * Calibrates a <em>long</em> workload (~100 ms).
     *
     * @param seed seed for the workload
     * @return calibrated iteration count
     */
    public static int longWorkload(long seed) {
        return calibrateIterations(LONG_NANOS, seed);
    }

    /* ================================================================
     *  Internal helpers
     * ================================================================ */

    /**
     * Executes a {@link CpuBoundWorkload} and returns its wall-clock time
     * in nanoseconds.  The computed result is consumed (passed to a
     * black-hole sink) so the JIT cannot eliminate the work.
     */
    private static long timeWorkload(long seed, int iterations) {
        var workload = new CpuBoundWorkload(seed, iterations);
        long start  = System.nanoTime();
        long result = workload.execute();
        long end    = System.nanoTime();
        blackhole(result);
        return end - start;
    }

    /**
     * Consumes a value to prevent JIT dead-code elimination.
     * The {@code volatile} write is the lightest reliable sink.
     */
    @SuppressWarnings("unused")
    private static volatile long sink;

    private static void blackhole(long value) {
        sink = value;
    }

    /** Clamps a long estimate into a safe {@code int} range (&ge; 1). */
    private static int clampIterations(long value) {
        if (value < MIN_ITERATIONS) return MIN_ITERATIONS;
        if (value > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) value;
    }
}

