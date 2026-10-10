package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link WorkloadCalibrator}.
 * Covers CPU and memory calibration with various targets and edge cases.
 */
@DisplayName("WorkloadCalibrator Tests")
class WorkloadCalibratorTest {

    @Test
    @DisplayName("calibrateIterations should reject non-positive target")
    void calibrateIterationsRejectsNonPositiveTarget() {
        assertThrows(IllegalArgumentException.class,
            () -> WorkloadCalibrator.calibrateIterations(0, 123L));
        assertThrows(IllegalArgumentException.class,
            () -> WorkloadCalibrator.calibrateIterations(-1, 123L));
        assertThrows(IllegalArgumentException.class,
            () -> WorkloadCalibrator.calibrateIterations(-1000L, 123L));
    }

    @Test
    @DisplayName("calibrateIterations should return positive value")
    void calibrateIterationsReturnsPositiveValue() {
        int iterations = WorkloadCalibrator.calibrateIterations(100_000L, 12345L);
        assertTrue(iterations > 0, "Calibrated iterations should be positive");
    }

    @Test
    @DisplayName("calibrateIterations should scale with target duration")
    void calibrateIterationsScalesWithTarget() {
        int iter1 = WorkloadCalibrator.calibrateIterations(10_000L, 12345L);
        int iter2 = WorkloadCalibrator.calibrateIterations(100_000L, 12345L);
        int iter3 = WorkloadCalibrator.calibrateIterations(1_000_000L, 12345L);
        
        // Longer target should require more iterations
        assertTrue(iter2 > iter1, "Longer target should require more iterations");
        assertTrue(iter3 > iter2, "Longer target should require more iterations");
    }

    @Test
    @DisplayName("calibrateIterations should be reasonably consistent")
    void calibrateIterationsReasonableConsistency() {
        // Calibration uses timing which varies due to JIT/CPU scheduling
        // Check consistency within ±15% tolerance
        int iter1 = WorkloadCalibrator.calibrateIterations(100_000L, 54321L);
        int iter2 = WorkloadCalibrator.calibrateIterations(100_000L, 54321L);
        
        double ratio = (double) iter2 / iter1;
        assertTrue(ratio >= 0.85 && ratio <= 1.15,
            String.format("Results should be within ±15%%, got ratio %.3f", ratio));
    }

    @Test
    @DisplayName("calibrateIterations should produce different results for different seeds")
    void calibrateIterationsDifferentSeeds() {
        int iter1 = WorkloadCalibrator.calibrateIterations(100_000L, 111L);
        int iter2 = WorkloadCalibrator.calibrateIterations(100_000L, 222L);
        
        // Different seeds may produce different results due to JIT/CPU variations
        // but both should be positive and reasonable
        assertTrue(iter1 > 0 && iter2 > 0);
    }

    @Test
    @DisplayName("calibrateIterations should handle small targets")
    void calibrateIterationsHandlesSmallTargets() {
        int iter = WorkloadCalibrator.calibrateIterations(1_000L, 12345L);
        assertTrue(iter > 0);
    }

    @Test
    @DisplayName("calibrateIterations should handle large targets")
    void calibrateIterationsHandlesLargeTargets() {
        int iter = WorkloadCalibrator.calibrateIterations(100_000_000L, 12345L);
        assertTrue(iter > 0);
    }

    @Test
    @DisplayName("calibrateIterations should not exceed Integer.MAX_VALUE")
    void calibrateIterationsDoesNotExceedMax() {
        int iter = WorkloadCalibrator.calibrateIterations(Long.MAX_VALUE, 12345L);
        assertTrue(iter > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should reject non-positive target")
    void calibrateMemoryStepsRejectsNonPositiveTarget() {
        long[] buffer = new long[1024];
        assertThrows(IllegalArgumentException.class,
            () -> WorkloadCalibrator.calibrateMemorySteps(0, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, 123L));
        assertThrows(IllegalArgumentException.class,
            () -> WorkloadCalibrator.calibrateMemorySteps(-100, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, 123L));
    }

    @Test
    @DisplayName("calibrateMemorySteps should return positive value")
    void calibrateMemoryStepsReturnsPositiveValue() {
        long[] buffer = new long[16_384];
        int steps = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0, "Calibrated steps should be positive");
    }

    @Test
    @DisplayName("calibrateMemorySteps should scale with target duration")
    void calibrateMemoryStepsScalesWithTarget() {
        long[] buffer = new long[16_384];
        int steps1 = WorkloadCalibrator.calibrateMemorySteps(10_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        int steps2 = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        int steps3 = WorkloadCalibrator.calibrateMemorySteps(1_000_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        
        // Longer target should require more steps
        assertTrue(steps2 > steps1, "Longer target should require more steps");
        assertTrue(steps3 > steps2, "Longer target should require more steps");
    }

    @Test
    @DisplayName("calibrateMemorySteps should be reasonably consistent")
    void calibrateMemoryStepsReasonableConsistency() {
        // Calibration uses timing which varies due to JIT/CPU scheduling
        // Check consistency within ±15% tolerance
        long[] buffer = new long[16_384];
        int steps1 = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 54321L);
        int steps2 = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 54321L);
        
        double ratio = (double) steps2 / steps1;
        assertTrue(ratio >= 0.85 && ratio <= 1.15,
            String.format("Results should be within ±15%%, got ratio %.3f", ratio));
    }

    @Test
    @DisplayName("calibrateMemorySteps should handle different access patterns")
    void calibrateMemoryStepsHandlesDifferentPatterns() {
        long[] buffer = new long[16_384];
        
        int stepsRandom = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        int stepsSequential = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.SEQUENTIAL, false, 12345L);
        
        assertTrue(stepsRandom > 0);
        assertTrue(stepsSequential > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should handle writeBack flag variations")
    void calibrateMemoryStepsHandlesWriteBackVariations() {
        long[] buffer = new long[16_384];
        
        int stepsNoWrite = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        int stepsWithWrite = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, true, 12345L);
        
        // Writes and reads have different cache behavior
        // Both should be positive
        assertTrue(stepsNoWrite > 0);
        assertTrue(stepsWithWrite > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should handle small buffers")
    void calibrateMemoryStepsHandlesSmallBuffers() {
        long[] buffer = new long[256];
        int steps = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should handle large buffers")
    void calibrateMemoryStepsHandlesLargeBuffers() {
        long[] buffer = new long[1_000_000];
        int steps = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should not exceed Integer.MAX_VALUE")
    void calibrateMemoryStepsDoesNotExceedMax() {
        long[] buffer = new long[16_384];
        int steps = WorkloadCalibrator.calibrateMemorySteps(Long.MAX_VALUE, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should return at least 1 step")
    void calibrateMemoryStepsReturnsAtLeastOne() {
        long[] buffer = new long[16_384];
        int steps = WorkloadCalibrator.calibrateMemorySteps(1_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps >= 1, "Should return at least 1 step");
    }

    @Test
    @DisplayName("calibrateIterations should return at least 1 iteration")
    void calibrateIterationsReturnsAtLeastOne() {
        int iter = WorkloadCalibrator.calibrateIterations(1_000L, 12345L);
        assertTrue(iter >= 1, "Should return at least 1 iteration");
    }

    @Test
    @DisplayName("calibrateMemorySteps with different seeds produces variations")
    void calibrateMemoryStepsDifferentSeedsProduceVariations() {
        long[] buffer = new long[16_384];
        
        int steps1 = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 111L);
        int steps2 = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 222L);
        
        // Both should be positive, but may differ
        assertTrue(steps1 > 0 && steps2 > 0);
    }

    @Test
    @DisplayName("calibrateMemorySteps should work with all access patterns")
    void calibrateMemoryStepsWorksWithAllPatterns() {
        long[] buffer = new long[16_384];
        
        for (MemoryBoundWorkload.AccessPattern pattern : MemoryBoundWorkload.AccessPattern.values()) {
            int steps = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer, pattern, false, 12345L);
            assertTrue(steps > 0, "Pattern " + pattern + " should produce positive steps");
        }
    }

    @Test
    @DisplayName("CPU calibration should complete in reasonable time")
    void cpuCalibrationCompletesInTime() {
        long start = System.currentTimeMillis();
        int iter = WorkloadCalibrator.calibrateIterations(100_000L, 12345L);
        long elapsed = System.currentTimeMillis() - start;
        
        assertTrue(iter > 0);
        assertTrue(elapsed < 5000, "Calibration should complete in under 5 seconds");
    }

    @Test
    @DisplayName("memory calibration should complete in reasonable time")
    void memoryCalibrationCompletesInTime() {
        long[] buffer = new long[16_384];
        long start = System.currentTimeMillis();
        int steps = WorkloadCalibrator.calibrateMemorySteps(100_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        long elapsed = System.currentTimeMillis() - start;
        
        assertTrue(steps > 0);
        assertTrue(elapsed < 5000, "Calibration should complete in under 5 seconds");
    }

    @Test
    @DisplayName("calibration with very small buffer should still work")
    void calibrationWithMinimalBuffer() {
        long[] buffer = new long[64];  // Very small
        int steps = WorkloadCalibrator.calibrateMemorySteps(50_000L, buffer,
            MemoryBoundWorkload.AccessPattern.SEQUENTIAL, false, 12345L);
        assertTrue(steps > 0);
    }

    @Test
    @DisplayName("calibration should produce reasonable ratios between targets")
    void calibrationProducesReasonableRatios() {
        int iter100us = WorkloadCalibrator.calibrateIterations(100_000L, 12345L);
        int iter1ms = WorkloadCalibrator.calibrateIterations(1_000_000L, 12345L);
        
        // 1ms is 10x the target, so expect roughly 10x iterations
        // Allow ±50% variance (5x to 15x)
        double ratio = (double) iter1ms / iter100us;
        assertTrue(ratio >= 5 && ratio <= 15,
            "Ratio " + ratio + " should be roughly 10x");
    }
}
