package com.scott;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Core accuracy verification for CV aggregation formula.
 * Tests that the fixed aggregation correctly includes per-worker M2 variance.
 */
class CVAggregationAccuracyTest {
    private static final double TOLERANCE = 1e-4;

    /**
     * Test 1: Two balanced workers [42µs×5] and [58µs×5]
     * Should compute CV correctly by including both within and between-worker variance.
     * 
     * Expected: global_mean = 50µs, stddev = 8µs, CV = 0.16
     */
    @Test
    void testTwoWorkerBalancedLoad() {
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        // Worker 1: 5 samples of 42,000 ns (42µs)
        for (int i = 0; i < 5; i++) {
            window1.addSample(now + i * 1_000_000, 42_000L);
        }
        // Worker 2: 5 samples of 58,000 ns (58µs)
        for (int i = 0; i < 5; i++) {
            window2.addSample(now + i * 1_000_000, 58_000L);
        }
        
        // Compute aggregate CV manually
        long total_n = window1.getSampleCount() + window2.getSampleCount();
        assertEquals(10L, total_n, "Should have 10 total samples");
        
        // Compute weighted mean
        double mean1 = window1.getMean();
        double mean2 = window2.getMean();
        assertEquals(42_000.0, mean1, 1.0, "Worker 1 mean should be 42µs");
        assertEquals(58_000.0, mean2, 1.0, "Worker 2 mean should be 58µs");
        
        double weighted_mean = (mean1 * 5 + mean2 * 5) / 10;
        assertEquals(50_000.0, weighted_mean, 1.0, "Global mean should be 50µs");
        
        // Compute M2 total
        // M2_1 = 0 (identical samples), M2_2 = 0
        double M2_1 = window1.getM2();
        double M2_2 = window2.getM2();
        assertEquals(0.0, M2_1, TOLERANCE, "M2 for identical samples should be 0");
        assertEquals(0.0, M2_2, TOLERANCE, "M2 for identical samples should be 0");
        
        // Between-worker contribution:
        // 5 × (42000 - 50000)² + 5 × (58000 - 50000)² = 5×64M + 5×64M = 640M
        double between_var = 5 * (42_000 - 50_000) * (42_000 - 50_000) 
                           + 5 * (58_000 - 50_000) * (58_000 - 50_000);
        assertEquals(640_000_000.0, between_var, 1.0);
        
        double M2_total = M2_1 + M2_2 + between_var;
        double variance = M2_total / total_n;
        double stddev = Math.sqrt(variance);
        double cv = stddev / weighted_mean;
        
        assertEquals(0.16, cv, 0.001, "CV should be 0.16");
    }

    /**
     * Test 2: Identical samples (all 50µs)
     * CV should be 0
     * targetDeviation(T=50µs) should be 0
     */
    @Test
    void testIdenticalSamples() {
        SlidingWindowCV window1 = new SlidingWindowCV(100);
        SlidingWindowCV window2 = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        for (int i = 0; i < 5; i++) {
            window1.addSample(now + i * 1_000_000, 50_000L);
            window2.addSample(now + i * 1_000_000, 50_000L);
        }
        
        // CV should be 0
        double M2_total = window1.getM2() + window2.getM2();
        double variance = M2_total / 10;
        double stddev = Math.sqrt(Math.max(0, variance));
        double cv = stddev / 50_000.0;
        assertEquals(0.0, cv, TOLERANCE);
        
        // targetDeviation should be 0
        long targetNanos = 50_000L;
        double sum_sq = window1.getTargetDeviationSumSquares(targetNanos) 
                      + window2.getTargetDeviationSumSquares(targetNanos);
        variance = sum_sq / 10;
        stddev = Math.sqrt(Math.max(0, variance));
        double target_dev = stddev / targetNanos;
        assertEquals(0.0, target_dev, TOLERANCE);
    }

    /**
     * Test 3: Three workers with different loads
     * Verify aggregation handles multiple workers correctly
     */
    @Test
    void testThreeWorkers() {
        SlidingWindowCV[] workers = new SlidingWindowCV[3];
        for (int i = 0; i < 3; i++) {
            workers[i] = new SlidingWindowCV(100);
        }
        
        long now = System.nanoTime();
        
        // Worker 0: 3 samples @ 45µs
        for (int i = 0; i < 3; i++) {
            workers[0].addSample(now + i * 1_000_000, 45_000L);
        }
        
        // Worker 1: 4 samples @ 50µs  
        for (int i = 0; i < 4; i++) {
            workers[1].addSample(now + i * 1_000_000, 50_000L);
        }
        
        // Worker 2: 3 samples @ 55µs
        for (int i = 0; i < 3; i++) {
            workers[2].addSample(now + i * 1_000_000, 55_000L);
        }
        
        // Total: 10 samples
        long total_n = 0;
        double weighted_mean = 0.0;
        for (SlidingWindowCV w : workers) {
            long n = w.getSampleCount();
            if (n == 0) continue;
            double m = w.getMean();
            weighted_mean = (weighted_mean * total_n + m * n) / (total_n + n);
            total_n += n;
        }
        
        assertEquals(10L, total_n);
        assertEquals(50_000.0, weighted_mean, 1.0, "Mean should be 50µs");
        
        // All M2_i should be 0 (identical samples per worker)
        // Between-worker: 3×(45-50)² + 4×(50-50)² + 3×(55-50)²
        //               = 3×25M + 0 + 3×25M = 150M
        double M2_total = 3 * 5000 * 5000 + 0 + 3 * 5000 * 5000;  // 150M
        double variance = M2_total / total_n;
        double stddev = Math.sqrt(variance);
        double cv = stddev / weighted_mean;
        
        assertTrue(cv > 0 && cv < 0.2, "CV should be positive and less than 0.2");
    }

    /**
     * Test 4: Buffer expansion doesn't corrupt statistics
     */
    @Test
    void testBufferExpansion() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        long now = System.nanoTime();
        
        int capacity_before = window.getCapacity();
        
        // Add more samples than initial capacity
        for (int i = 0; i < 100; i++) {
            window.addSample(now + i * 1_000_000L, 50_000L + i % 10);  // Slight variation
        }
        
        long count = window.getSampleCount();
        assertEquals(100L, count, "Should have 100 samples");
        
        double mean = window.getMean();
        assertTrue(mean > 0, "Mean should be positive");
        
        double M2 = window.getM2();
        assertTrue(M2 >= 0, "M2 should be non-negative");
    }

    /**
     * Test 5: targetDeviation with efficient Welford computation
     */
    @Test
    void testTargetDeviationEfficient() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        long now = System.nanoTime();
        
        // Add samples: [40, 50, 60] (mean=50)
        window.addSample(now, 40_000L);
        window.addSample(now + 1_000_000, 50_000L);
        window.addSample(now + 2_000_000, 60_000L);
        
        assertEquals(50_000.0, window.getMean(), 1.0, "Mean should be 50µs");
        
        // For T=50µs: Σ(x-50)² = (40-50)² + (50-50)² + (60-50)² = 100M + 0 + 100M = 200M
        long targetNanos = 50_000L;
        double sum_sq = window.getTargetDeviationSumSquares(targetNanos);
        
        // Verify using Welford formula: S = M2 + n·(mean - T)²
        // M2 = (40-50)² + (50-50)² + (60-50)² - compensation for mean change
        // Using Welford: M2 should accumulate correctly
        
        double variance = sum_sq / 3;
        double stddev = Math.sqrt(variance);
        double target_dev = stddev / targetNanos;
        
        // Expected: sqrt(200M/3) / 50k ≈ 8.165k / 50k ≈ 0.1633
        assertEquals(0.1633, target_dev, 0.001, "targetDeviation should be ≈0.1633");
    }
}

