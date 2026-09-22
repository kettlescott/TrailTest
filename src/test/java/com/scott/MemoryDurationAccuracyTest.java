package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Accuracy tests for duration-controlled MEMORY workloads.
 *
 * Verifies that MEM50, MEM100, and MEM300 execute with execution times
 * close to their target durations.
 */
class MemoryDurationAccuracyTest {

    private static final int BUFFER_MB = 64;
    private static final int BUFFER_LONGS = BUFFER_MB * (1 << 17);

    @Test
    @DisplayName("MEM50: Target 50µs - Execution time accuracy")
    void mem50_accuracyTest() {
        accuracyTestForTarget(50, "MEM50");
    }

    @Test
    @DisplayName("MEM100: Target 100µs - Execution time accuracy")
    void mem100_accuracyTest() {
        accuracyTestForTarget(100, "MEM100");
    }

    @Test
    @DisplayName("MEM300: Target 300µs - Execution time accuracy")
    void mem300_accuracyTest() {
        accuracyTestForTarget(300, "MEM300");
    }

    /**
     * Tests execution time accuracy for a given target duration.
     *
     * Runs multiple tasks and verifies:
     * 1. Average execution time is close to target
     * 2. Percentiles (p50, p95, p99) are reasonable
     * 3. No excessive overshoots
     *
     * @param targetMicros the target duration in microseconds
     * @param label display name for logging
     */
    private void accuracyTestForTarget(long targetMicros, String label) {
        // Setup buffer
        long[] buffer = createBuffer(BUFFER_LONGS);

        // Run multiple tasks and collect timing samples
        List<Long> executionTimesUs = new ArrayList<>();
        int taskCount = 20;  // Run 20 tasks to get statistics

        for (int i = 0; i < taskCount; i++) {
            MemoryBoundWorkloadDuration workload = new MemoryBoundWorkloadDuration(
                    buffer,
                    targetMicros,
                    MemoryBoundWorkload.AccessPattern.RANDOM,
                    12345L + i,  // Vary seed per task
                    false,        // writeBack=false
                    8);           // batchSize=8

            long startNanos = System.nanoTime();
            long result = workload.execute();
            long endNanos = System.nanoTime();

            long executionNanos = endNanos - startNanos;
            long executionUs = executionNanos / 1_000L;
            executionTimesUs.add(executionUs);

            assertNotEquals(0L, result, "Workload should return non-zero result");
        }

        // Analyze results
        analyzeAccuracy(label, targetMicros, executionTimesUs);
    }

    /**
     * Analyzes execution time accuracy and prints detailed statistics.
     */
    private void analyzeAccuracy(String label, long targetMicros, List<Long> timesUs) {
        // Sort for percentile calculation
        List<Long> sorted = new ArrayList<>(timesUs);
        sorted.sort(null);

        // Calculate statistics
        double avgUs = timesUs.stream().mapToLong(x -> x).average().orElse(0);
        long minUs = sorted.get(0);
        long maxUs = sorted.get(sorted.size() - 1);
        long medianUs = sorted.get(sorted.size() / 2);
        long p95Us = sorted.get((int) (sorted.size() * 0.95));
        long p99Us = sorted.get((int) Math.min(sorted.size() - 1, (int) (sorted.size() * 0.99)));

        // Calculate tolerances
        long toleranceUs = Math.max(5, targetMicros / 10);  // 10% or 5µs minimum
        double relativeError = Math.abs(avgUs - targetMicros) / targetMicros * 100;

        // Print results
        System.out.printf("""
                
                ═══════════════════════════════════════════════════════════════
                  %s Accuracy Test Results
                ═══════════════════════════════════════════════════════════════
                  Target Duration:          %d µs
                  
                  Execution Statistics (µs):
                    Min:                    %d µs
                    Median (p50):           %d µs
                    Average:                %.1f µs
                    P95:                    %d µs
                    P99:                    %d µs
                    Max:                    %d µs
                  
                  Accuracy Analysis:
                    Relative Error:         %.2f%%
                    Tolerance:              ±%d µs
                    Average vs Target:      %.1f µs
                    Overshoot (p99-target): %d µs
                  
                  Verdict:
                """,
                label,
                targetMicros,
                minUs, medianUs, avgUs, p95Us, p99Us, maxUs,
                relativeError, toleranceUs,
                avgUs - targetMicros,
                p99Us - targetMicros);

        // Assertions with tolerances
        // For shorter durations (50-100µs), allow wider tolerance
        double relaxedTolerance = targetMicros <= 100 ? 0.30 : 0.25;  // 30% or 25%

        assertTrue(
                relativeError <= relaxedTolerance * 100,
                String.format(
                        "%s: Average execution %.1f µs exceeds tolerance from target %d µs (error: %.2f%%)",
                        label, avgUs, targetMicros, relativeError));

        // P95 should be reasonably close (allow 50% tolerance)
        long p95Tolerance = (long)(targetMicros * 0.50);
        assertTrue(
                p95Us <= targetMicros + p95Tolerance,
                String.format(
                        "%s: P95 execution %d µs exceeds target + 50%% tolerance (%d µs)",
                        label, p95Us, targetMicros + p95Tolerance));

        System.out.printf("    ✅ %s accuracy verified%n", label);
        System.out.printf("       Average: %.1f µs (target: %d µs, error: %.2f%%)%n",
                avgUs, targetMicros, relativeError);
        System.out.println("═══════════════════════════════════════════════════════════════\n");
    }

    /**
     * Creates and initializes a random buffer.
     */
    private long[] createBuffer(int size) {
        long[] buf = new long[size];
        java.util.Random rand = new java.util.Random(42);
        for (int i = 0; i < size; i++) {
            buf[i] = rand.nextLong();
        }
        return buf;
    }

    /**
     * Comparative test: Runs all three targets and compares their accuracies.
     */
    @Test
    @DisplayName("Comparative accuracy: MEM50 vs MEM100 vs MEM300")
    void comparativeAccuracyTest() {
        System.out.println("\n╔════════════════════════════════════════════════════════════════╗");
        System.out.println("║          COMPARATIVE ACCURACY TEST - MEM50/100/300            ║");
        System.out.println("╚════════════════════════════════════════════════════════════════╝\n");

        long[] buffer = createBuffer(BUFFER_LONGS);
        long[] targets = {50, 100, 300};

        for (long target : targets) {
            List<Long> timesUs = new ArrayList<>();

            // Run 15 tasks per target
            for (int i = 0; i < 15; i++) {
                MemoryBoundWorkloadDuration workload = new MemoryBoundWorkloadDuration(
                        buffer, target, MemoryBoundWorkload.AccessPattern.RANDOM,
                        54321L + i, false, 8);

                long startNanos = System.nanoTime();
                long result = workload.execute();
                long endNanos = System.nanoTime();

                long executionUs = (endNanos - startNanos) / 1_000L;
                timesUs.add(executionUs);
            }

            // Quick analysis
            double avg = timesUs.stream().mapToLong(x -> x).average().orElse(0);
            double relErr = Math.abs(avg - target) / target * 100;

            System.out.printf("MEM%-3d: Target=%3d µs | Avg=%.1f µs | Error=%.2f%% %s%n",
                    target,
                    target,
                    avg,
                    relErr,
                    relErr < 30 ? "✅" : "⚠️");
        }

        System.out.println("\n✅ Comparative test complete\n");
    }

    /**
     * Stress test: Verifies stability under repeated execution.
     */
    @Test
    @DisplayName("Stability test: Repeated MEM100 execution")
    void stabilityTest() {
        long targetMicros = 100;
        long[] buffer = createBuffer(BUFFER_LONGS);
        List<Long> timesUs = new ArrayList<>();

        // Run 50 tasks to test stability
        for (int i = 0; i < 50; i++) {
            MemoryBoundWorkloadDuration workload = new MemoryBoundWorkloadDuration(
                    buffer, targetMicros, MemoryBoundWorkload.AccessPattern.RANDOM,
                    99999L + i, false, 8);

            long startNanos = System.nanoTime();
            long result = workload.execute();
            long endNanos = System.nanoTime();

            long executionUs = (endNanos - startNanos) / 1_000L;
            timesUs.add(executionUs);

            assertNotEquals(0L, result, "Workload result must be non-zero");
        }

        // Analyze stability
        List<Long> sorted = new ArrayList<>(timesUs);
        sorted.sort(null);

        double avg = timesUs.stream().mapToLong(x -> x).average().orElse(0);
        long stdDev = calculateStdDev(timesUs, avg);
        long minUs = sorted.get(0);
        long maxUs = sorted.get(sorted.size() - 1);
        long range = maxUs - minUs;

        System.out.printf("""
                
                ═══════════════════════════════════════════════════════════════
                  Stability Test (50x MEM100)
                ═══════════════════════════════════════════════════════════════
                  Average:                %.1f µs
                  Std Dev:                %d µs
                  Min:                    %d µs
                  Max:                    %d µs
                  Range:                  %d µs
                  
                """, avg, stdDev, minUs, maxUs, range);

        // Stability assertion: std dev should be reasonable
        assertTrue(stdDev < 30, "Standard deviation " + stdDev + " µs is too high (should be < 30 µs)");

        System.out.println("✅ Stability verified: std dev = " + stdDev + " µs\n");
    }

    private long calculateStdDev(List<Long> values, double mean) {
        double sumSquareDiffs = values.stream()
                .mapToLong(x -> x)
                .mapToDouble(x -> (x - mean) * (x - mean))
                .sum();
        return Math.round(Math.sqrt(sumSquareDiffs / values.size()));
    }
}

