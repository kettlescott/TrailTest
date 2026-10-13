package com.scott;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test to verify CV accuracy with known input distributions.
 * 
 * <p>Test case: Each worker inputs [42µs, 58µs] samples.
 * Expected CV should be 0.16 (16%).
 * 
 * <p>Verification:
 * <ul>
 *   <li>Manually create samples with exact [42µs, 58µs] distribution</li>
 *   <li>Calculate expected CV offline</li>
 *   <li>Compare with CVMonitorThread aggregation result</li>
 * </ul>
 */
public class CVAccuracyOfflineTest {

    /**
     * Verify CV calculation with known distribution: [42µs, 58µs].
     * This is a uniform 2-point distribution.
     * 
     * <p>Mathematical verification:
     * <pre>
     * x = [42, 58] (in microseconds)
     * n = 2
     * mean = (42 + 58) / 2 = 50
     * variance = [(42-50)² + (58-50)²] / 2 = [64 + 64] / 2 = 64
     * stddev = sqrt(64) = 8
     * CV = 8 / 50 = 0.16
     * </pre>
     */
    @Test
    public void testCVWithUniformDistribution() {
        // Create windows for multiple workers
        int numWorkers = 4;
        SlidingWindowCV[] windows = new SlidingWindowCV[numWorkers];
        for (int i = 0; i < numWorkers; i++) {
            windows[i] = new SlidingWindowCV(100);  // 100ms window
        }
        
        // Each worker contributes samples with [42µs, 58µs] distribution
        long base42us = 42_000L;  // 42 microseconds in nanos
        long base58us = 58_000L;  // 58 microseconds in nanos
        
        for (int workerIdx = 0; workerIdx < numWorkers; workerIdx++) {
            long completionBase = System.nanoTime();
            // Add 10 pairs of samples to each worker
            for (int pair = 0; pair < 10; pair++) {
                windows[workerIdx].addSample(completionBase + pair * 1_000_000_000L, base42us);
                windows[workerIdx].addSample(completionBase + pair * 1_000_000_000L + 1_000L, base58us);
            }
        }
        
        // Calculate aggregate CV using Welford's parallel algorithm (as implemented in CVMonitorThread)
        long totalN = 0;
        double weightedMean = 0.0;
        
        for (SlidingWindowCV window : windows) {
            long n = window.getSampleCount();
            if (n == 0) continue;
            double mean_i = window.getMean();
            weightedMean = (weightedMean * totalN + mean_i * n) / (totalN + n);
            totalN += n;
        }
        
        assertTrue(totalN > 0, "Should have samples");
        
        // Second pass: aggregate M2
        double totalM2 = 0.0;
        for (SlidingWindowCV window : windows) {
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
        double stddev = Math.sqrt(variance);
        double cv = stddev / weightedMean;
        
        // Expected: CV = 0.16
        double expectedCv = 0.16;
        double tolerance = 0.001;  // Allow ±0.001 tolerance due to floating point
        
        assertEquals(expectedCv, cv, tolerance,
                String.format("CV should be %.2f, got %.6f (n=%d, mean=%.1f, stddev=%.1f)",
                        expectedCv, cv, totalN, weightedMean, stddev));
    }

    /**
     * Verify that recomputing from a static window gives same result as incremental aggregation.
     */
    @Test
    public void testCVConsistency() {
        // Create window
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        // Add exact samples [42, 58, 42, 58, ...]
        long base42us = 42_000L;
        long base58us = 58_000L;
        
        for (int i = 0; i < 100; i++) {
            if (i % 2 == 0) {
                window.addSample(System.nanoTime() + i * 1_000_000L, base42us);
            } else {
                window.addSample(System.nanoTime() + i * 1_000_000L, base58us);
            }
        }
        
        // Incremental CV
        double incrementalCV = window.computeCV();
        
        // Manual verification
        long n = window.getSampleCount();
        double mean = window.getMean();
        double M2 = window.getM2();
        double variance = M2 / n;
        double stddev = Math.sqrt(variance);
        double manualCV = stddev / mean;
        
        assertEquals(incrementalCV, manualCV, 1e-10,
                "Incremental CV should match manual calculation");
        
        // For [42, 58] uniform distribution:
        // mean = 50, stddev = 8, CV = 0.16
        assertEquals(50.0, mean, 0.01, "Mean should be ~50");
        assertEquals(0.16, incrementalCV, 0.001, "CV should be ~0.16");
    }

    /**
     * Verify targetDeviation calculation with known target.
     */
    @Test
    public void testTargetDeviationAccuracy() {
        // Create window with 50µs target
        SlidingWindowCV window = new SlidingWindowCV(100);
        long targetNanos = 50_000L;  // 50 microseconds
        
        // Add samples: all exactly at target (should give targetDeviation = 0)
        for (int i = 0; i < 20; i++) {
            window.addSample(System.nanoTime() + i * 1_000_000L, targetNanos);
        }
        
        double targetDevSum = window.getTargetDeviationSumSquares(targetNanos);
        long n = window.getSampleCount();
        double variance = targetDevSum / n;
        double stddev = Math.sqrt(variance);
        double targetDev = stddev / targetNanos;
        
        // All samples at target: targetDeviation = 0
        assertEquals(0.0, targetDev, 1e-10,
                "targetDeviation should be 0 when all samples equal target");
        
        // Now add samples off-target: [42µs, 58µs]
        long[] offTargets = {42_000L, 58_000L};
        for (int i = 0; i < 100; i++) {
            window.addSample(System.nanoTime() + 1_000_000_000L + i * 1_000_000L, 
                           offTargets[i % 2]);
        }
        
        // Recompute
        targetDevSum = window.getTargetDeviationSumSquares(targetNanos);
        n = window.getSampleCount();
        variance = targetDevSum / n;
        stddev = Math.sqrt(variance);
        targetDev = stddev / targetNanos;
        
        // Should now be non-zero
        assertTrue(targetDev > 0, "targetDeviation should be non-zero with off-target samples");
        
        // Verify calculation: Σ(x - T)² with mixed samples
        // First 20: (50 - 50)² = 0
        // Next 50: (42 - 50)² = 64 each
        // Next 50: (58 - 50)² = 64 each
        // Sum = 50 * 64 + 50 * 64 = 6400
        // variance = 6400 / 120 = 53.33...
        // stddev = sqrt(53.33) = 7.31
        // targetDev = 7.31 / 50 = 0.146
        double expectedTargetDev = 0.146;
        assertEquals(expectedTargetDev, targetDev, 0.01,
                "targetDeviation with [42,58] should be ~0.146");
    }

    /**
     * Test window expiration: old samples are properly removed.
     */
    @Test
    public void testWindowExpiration() {
        SlidingWindowCV window = new SlidingWindowCV(100);  // 100ms window
        
        long now = System.nanoTime();
        long windowNanos = 100_000_000L;  // 100ms in nanos
        
        // Add old samples (older than window)
        long oldTime = now - windowNanos - 1_000_000_000L;  // Way outside window
        for (int i = 0; i < 50; i++) {
            window.addSample(oldTime + i * 1_000_000L, 100_000L);
        }
        
        long oldCount = window.getSampleCount();
        assertTrue(oldCount > 0, "Should have added samples");
        
        // Expire samples
        long cutoffNanos = now - windowNanos;
        window.expireOldSamples(cutoffNanos);
        
        long newCount = window.getSampleCount();
        assertEquals(0L, newCount, "All old samples should be expired");
        
        // Add recent samples (within window)
        for (int i = 0; i < 30; i++) {
            window.addSample(now - 1_000_000_000L + i * 1_000_000L, 100_000L);
        }
        
        newCount = window.getSampleCount();
        assertEquals(30L, newCount, "Recent samples should be retained");
    }

    /**
     * Test capacity expansion: buffer grows when exceeding initial size.
     */
    @Test
    public void testCapacityExpansion() {
        SlidingWindowCV window = new SlidingWindowCV(100);  // 100ms, initial capacity ~8192
        
        // Add more samples than initial capacity
        long now = System.nanoTime();
        for (int i = 0; i < 20000; i++) {
            window.addSample(now + (i % 100) * 1_000_000_000L + (i / 100) * 1_000_000L, 50_000L + (i % 100));
        }
        
        long count = window.getSampleCount();
        assertTrue(count > 0, "Should have retained samples");
        
        int capacity = window.getCapacity();
        assertTrue(capacity > 8192, "Capacity should have expanded beyond initial 8192");
        assertTrue(capacity <= 1_000_000, "Capacity should not exceed hard limit");
        
        // Verify dropped count
        long dropped = window.getDroppedDueToCapacityMax();
        assertTrue(dropped == 0 || dropped < 50, "Dropped count should be minimal after expansion");
    }
}

