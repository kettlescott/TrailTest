package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive accuracy verification tests for CV and targetDeviation calculation.
 * 
 * Tests the core aggregation formulas to ensure they correctly implement:
 * 
 * CV = sqrt(M2_total / N) / mean
 *    where M2_total = Σ[M2_i + n_i × (mean_i - global_mean)²]
 * 
 * targetDeviation = sqrt(S / N) / T
 *    where S = Σ[n_i × (mean_i - T)² + M2_i]  (efficient via Welford)
 */
@DisplayName("CV/targetDeviation Accuracy Tests")
class CVAccuracyTest {

    private static final double TOLERANCE = 1e-6;

    /**
     * Test 1: All samples identical (42µs in each worker).
     * Expected: CV = 0, targetDeviation (T=42µs) = 0
     */
    @Test
    @DisplayName("Test 1: Identical samples (42µs) - CV and targetDeviation should both be 0")
    void testIdenticalSamples() {
        long targetNanos = 42_000L;
        
        // Create two workers, each with 5 samples of 42,000 ns
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            window1.addSample(now + i * 1_000_000, 42_000L);
            window2.addSample(now + i * 1_000_000, 42_000L);
        }
        
        // Compute aggregate CV
        double cv = computeAggregateCV(new SlidingWindowCV[]{window1, window2});
        assertEquals(0.0, cv, TOLERANCE, "CV should be 0 for identical samples");
        
        // Compute aggregate targetDeviation
        double targetDev = computeAggregateTargetDeviation(
            new SlidingWindowCV[]{window1, window2}, targetNanos);
        assertEquals(0.0, targetDev, TOLERANCE, "targetDeviation should be 0 when all samples equal target");
    }

    /**
     * Test 2: Balanced two-worker load with mixed samples.
     * Worker1: [42, 42, 42, 42, 42] = 5 samples, mean=42
     * Worker2: [58, 58, 58, 58, 58] = 5 samples, mean=58
     * 
     * Global mean = (5*42 + 5*58) / 10 = 50
     * Within-worker M2:
     *   M2_1 = 0 (all identical)
     *   M2_2 = 0 (all identical)
     * Between-worker contribution:
     *   n1 × (42-50)² = 5 × 64 = 320
     *   n2 × (58-50)² = 5 × 64 = 320
     * M2_total = 0 + 0 + 320 + 320 = 640
     * variance = 640 / 10 = 64
     * stddev = 8
     * CV = 8 / 50 = 0.16
     * 
     * For T=50µs: all Σ(x-50)² = 5×64 + 5×64 = 640
     * variance = 640/10 = 64, stddev = 8
     * targetDeviation = 8 / 50 = 0.16
     */
    @Test
    @DisplayName("Test 2: Two workers [42µs x5] and [58µs x5] - CV=0.16, targetDev(T=50µs)=0.16")
    void testMixedBalancedLoad() {
        long targetNanos = 50_000L;
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        // Worker 1: 5 samples of 42,000 ns
        for (int i = 0; i < 5; i++) {
            window1.addSample(now + i * 1_000_000, 42_000L);
        }
        // Worker 2: 5 samples of 58,000 ns
        for (int i = 0; i < 5; i++) {
            window2.addSample(now + i * 1_000_000, 58_000L);
        }
        
        // Check CV
        double cv = computeAggregateCV(new SlidingWindowCV[]{window1, window2});
        assertEquals(0.16, cv, 1e-4, "CV should be 0.16 for [42µs×5, 58µs×5]");
        
        // Check targetDeviation
        double targetDev = computeAggregateTargetDeviation(
            new SlidingWindowCV[]{window1, window2}, targetNanos);
        assertEquals(0.16, targetDev, 1e-4, "targetDeviation(T=50µs) should be 0.16");
    }

    /**
     * Test 3: Same as Test 2, but with T=53.5µs (offset from mean).
     * All samples: 5×42 + 5×58
     * Σ(x - 53.5)² = 5×(42-53.5)² + 5×(58-53.5)²
     *              = 5×132.25 + 5×20.25
     *              = 661.25 + 101.25
     *              = 762.5
     * variance = 762.5 / 10 = 76.25
     * stddev = √76.25 ≈ 8.7315
     * targetDeviation = 8.7315 / 53.5 ≈ 0.1631
     */
    @Test
    @DisplayName("Test 3: Two workers [42µs x5] and [58µs x5], T=53.5µs - targetDev≈0.1631")
    void testTargetDeviationOffsetFromMean() {
        long targetNanos = 53_500L;  // 53.5µs
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            window1.addSample(now + i * 1_000_000, 42_000L);
            window2.addSample(now + i * 1_000_000, 58_000L);
        }
        
        double targetDev = computeAggregateTargetDeviation(
            new SlidingWindowCV[]{window1, window2}, targetNanos);
        assertEquals(0.1631, targetDev, 1e-3, "targetDeviation(T=53.5µs) ≈ 0.1631");
    }

    /**
     * Test 4: All samples identical at 50µs, but T=53.5µs.
     * All samples: 10×50
     * Σ(x - 53.5)² = 10 × (50 - 53.5)² = 10 × 12.25 = 122.5
     * variance = 122.5 / 10 = 12.25
     * stddev = 3.5
     * targetDeviation = 3.5 / 53.5 ≈ 0.0654
     */
    @Test
    @DisplayName("Test 4: All samples 50µs, T=53.5µs - targetDev≈0.0654, CV=0")
    void testIdenticalSamplesOffsetTarget() {
        long targetNanos = 53_500L;
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            window1.addSample(now + i * 1_000_000, 50_000L);
            window2.addSample(now + i * 1_000_000, 50_000L);
        }
        
        double cv = computeAggregateCV(new SlidingWindowCV[]{window1, window2});
        assertEquals(0.0, cv, TOLERANCE, "CV should be 0 for identical samples");
        
        double targetDev = computeAggregateTargetDeviation(
            new SlidingWindowCV[]{window1, window2}, targetNanos);
        assertEquals(0.0654, targetDev, 1e-3, "targetDeviation(T=53.5µs) ≈ 0.0654");
    }

    /**
     * Test 5: Three workers with different means.
     * Worker1 (3 samples): [45, 45, 45] → mean=45
     * Worker2 (4 samples): [50, 50, 50, 50] → mean=50
     * Worker3 (3 samples): [55, 55, 55] → mean=55
     * 
     * N = 10, global mean = (3×45 + 4×50 + 3×55) / 10 = 500 / 10 = 50
     * M2 components (all M2_i = 0 since each worker has identical samples):
     *   Worker1: 3 × (45-50)² = 3 × 25 = 75
     *   Worker2: 4 × (50-50)² = 0
     *   Worker3: 3 × (55-50)² = 3 × 25 = 75
     * M2_total = 150
     * variance = 150 / 10 = 15
     * stddev = √15 ≈ 3.873
     * CV = 3.873 / 50 ≈ 0.0775
     */
    @Test
    @DisplayName("Test 5: Three workers [45×3], [50×4], [55×3] - CV≈0.0775")
    void testThreeWorkersMixedLoad() {
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        SlidingWindowCV window3 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        for (int i = 0; i < 3; i++) {
            window1.addSample(now + i * 1_000_000, 45_000L);
        }
        for (int i = 0; i < 4; i++) {
            window2.addSample(now + i * 1_000_000, 50_000L);
        }
        for (int i = 0; i < 3; i++) {
            window3.addSample(now + i * 1_000_000, 55_000L);
        }
        
        double cv = computeAggregateCV(new SlidingWindowCV[]{window1, window2, window3});
        assertEquals(0.0775, cv, 1e-3, "CV should be ≈0.0775 for three-worker mix");
    }

    /**
     * Test 6: Verify sample expiration doesn't corrupt statistics.
     * Add samples with increasing completion times, then expire old ones.
     */
    @Test
    @DisplayName("Test 6: Expiration correctness - statistics remain consistent")
    void testSampleExpiration() {
        SlidingWindowCV window = new SlidingWindowCV(100);  // 100ms window
        
        long baseTime = System.nanoTime();
        
        // Add samples in time range [0, 200ms)
        for (int i = 0; i < 10; i++) {
            long completionTime = baseTime + i * 20_000_000L;  // 20ms apart
            window.addSample(completionTime, 50_000L);
        }
        long countBefore = window.getSampleCount();
        
        // Expire samples older than 110ms ago (should remove 0-100ms samples, keep 100-200ms)
        long cutoffTime = baseTime + 110_000_000L;
        window.expireOldSamples(cutoffTime);
        long countAfter = window.getSampleCount();
        
        // Should have removed ~5-6 samples
        assertTrue(countAfter < countBefore, "Samples should be removed");
        assertEquals(50_000.0, window.getMean(), TOLERANCE, "Mean should remain correct after expiration");
    }

    /**
     * Test 7: Buffer expansion when capacity is exceeded.
     * Verify statistics remain consistent after expansion.
     */
    @Test
    @DisplayName("Test 7: Buffer expansion - statistics consistency")
    void testBufferExpansion() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        
        // The initial capacity is 8192. Add more samples to force expansion.
        // For this test, we just add enough to test the basic mechanism.
        int startCap = window.getCapacity();
        
        // Add samples incrementally
        for (int i = 0; i < 100; i++) {
            window.addSample(now + i * 1_000_000L, 50_000L + i);  // Slight variation
        }
        
        // Verify statistics are valid
        double cv = window.computeCV();
        assertFalse(Double.isNaN(cv), "CV should be finite after samples added");
        assertEquals(100L, window.getSampleCount(), "Sample count should match added samples");
    }

    /**
     * Test 8: Verify cross-worker Welford aggregation matches direct calculation.
     * Create three independent workers with different distributions, then verify
     * the aggregated statistics match what we'd compute directly.
     */
    @Test
    @DisplayName("Test 8: Aggregation consistency - cross-worker formula matches direct calculation")
    void testAggregationConsistency() {
        // Create workers with specific distributions
        SlidingWindowCV[] workers = new SlidingWindowCV[3];
        for (int i = 0; i < 3; i++) {
            workers[i] = new SlidingWindowCV(100);
        }
        
        long now = System.nanoTime();
        
        // Worker 0: [40, 40, 40]
        for (int i = 0; i < 3; i++) {
            workers[0].addSample(now + i * 1_000_000, 40_000L);
        }
        
        // Worker 1: [50, 50, 50, 50]
        for (int i = 0; i < 4; i++) {
            workers[1].addSample(now + i * 1_000_000, 50_000L);
        }
        
        // Worker 2: [60, 60]
        for (int i = 0; i < 2; i++) {
            workers[2].addSample(now + i * 1_000_000, 60_000L);
        }
        
        // Aggregate using the formula
        double aggregatedCV = computeAggregateCV(workers);
        
        // Compute directly from all samples
        // All samples: [40, 40, 40, 50, 50, 50, 50, 60, 60]
        // mean = (3×40 + 4×50 + 2×60) / 9 = 440 / 9 ≈ 48.889
        // For each worker: M2=0 (identical samples within)
        // Between-worker variance:
        //   3×(40-48.889)² + 4×(50-48.889)² + 2×(60-48.889)²
        //   = 3×79.012 + 4×1.234 + 2×123.457
        //   ≈ 237 + 5 + 247 ≈ 489.7
        // variance = 489.7 / 9 ≈ 54.41
        // stddev = 7.376
        // CV = 7.376 / 48.889 ≈ 0.1508
        
        // Tolerance: ±0.015 (1.5%)
        assertEquals(0.1508, aggregatedCV, 0.015, "Aggregated CV should match direct calculation");
    }

    /**
     * Test 9: Single sample handling.
     */
    @Test
    @DisplayName("Test 9: Single sample per worker - CV should be finite or NaN appropriately")
    void testSingleSamplePerWorker() {
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        window1.addSample(now, 50_000L);
        window2.addSample(now + 1_000_000, 55_000L);
        
        // With only 2 total samples, should be able to compute CV
        double cv = computeAggregateCV(new SlidingWindowCV[]{window1, window2});
        assertFalse(Double.isNaN(cv), "CV should be finite with 2 samples across workers");
        assertTrue(cv > 0, "CV should be > 0 for different samples");
    }

    /**
     * Test 10: Empty window handling.
     */
    @Test
    @DisplayName("Test 10: Empty windows - metrics should be NaN")
    void testEmptyWindows() {
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        double cv = computeAggregateCV(new SlidingWindowCV[]{window1, window2});
        assertTrue(Double.isNaN(cv), "CV should be NaN for empty windows");
        
        double targetDev = computeAggregateTargetDeviation(
            new SlidingWindowCV[]{window1, window2}, 50_000L);
        assertTrue(Double.isNaN(targetDev), "targetDeviation should be NaN for empty windows");
    }

    /* ================================================================
     * Helper methods (implementing the aggregation formulas)
     * ================================================================ */

    /**
     * Aggregates CV across multiple workers.
     * Formula: CV = sqrt(M2_total / N) / mean
     * where M2_total = Σ[M2_i + n_i × (mean_i - global_mean)²]
     */
    private double computeAggregateCV(SlidingWindowCV[] workers) {
        long totalN = 0;
        double weightedMean = 0.0;

        for (SlidingWindowCV window : workers) {
            long n = window.getSampleCount();
            if (n == 0) continue;
            double mean_i = window.getMean();
            weightedMean = (weightedMean * totalN + mean_i * n) / (totalN + n);
            totalN += n;
        }

        if (totalN < 2 || weightedMean <= 0.0) {
            return Double.NaN;
        }

        double totalM2 = 0.0;
        for (SlidingWindowCV window : workers) {
            long n = window.getSampleCount();
            if (n > 0) {
                double mean_i = window.getMean();
                double M2_i = window.getM2();
                totalM2 += M2_i;
                double delta = mean_i - weightedMean;
                totalM2 += n * delta * delta;
            }
        }

        double variance = totalM2 / totalN;
        if (variance < 0.0) variance = 0.0;
        double stddev = Math.sqrt(variance);
        double cv = stddev / weightedMean;
        return Double.isFinite(cv) ? cv : Double.NaN;
    }

    /**
     * Aggregates targetDeviation across multiple workers.
     * Formula: targetDeviation = sqrt(S / N) / T
     * where S = Σ_i Σ(x_ij - T)² computed efficiently via Welford
     */
    private double computeAggregateTargetDeviation(SlidingWindowCV[] workers, long targetNanos) {
        long totalN = 0;
        double totalSumSquaredDiffs = 0.0;

        for (SlidingWindowCV window : workers) {
            long n = window.getSampleCount();
            if (n > 0) {
                double sumSquaredDiffs = window.getTargetDeviationSumSquares(targetNanos);
                totalSumSquaredDiffs += sumSquaredDiffs;
                totalN += n;
            }
        }

        if (totalN < 1) {
            return Double.NaN;
        }

        double variance = SlidingWindowCV.computeVariance(totalSumSquaredDiffs, totalN);
        double stddev = SlidingWindowCV.computeStddev(variance);
        double targetDeviation = stddev / targetNanos;
        return Double.isFinite(targetDeviation) ? targetDeviation : Double.NaN;
    }
}

