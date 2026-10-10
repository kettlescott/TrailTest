package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Timeout;

import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Stress and boundary condition tests for {@link WorkloadCalibrator}.
 * Tests extreme values, rapid-fire calibrations, and edge cases.
 */
@DisplayName("WorkloadCalibrator Stress and Boundary Tests")
class WorkloadCalibratorStressTest {

    /**
     * Test rapid sequential calibrations to verify no state issues.
     */
    @Test
    @DisplayName("Rapid sequential CPU calibrations should all succeed")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void rapidSequentialCpuCalibrations() {
        for (int i = 0; i < 10; i++) {
            int iter = WorkloadCalibrator.calibrateIterations(10_000_000L + i * 1_000, 12345L);
            assertTrue(iter > 0, "Iteration " + i + " failed");
        }
    }

    /**
     * Test rapid sequential memory calibrations.
     */
    @Test
    @DisplayName("Rapid sequential memory calibrations should all succeed")
    @Timeout(value = 10, unit = TimeUnit.SECONDS)
    void rapidSequentialMemoryCalibrations() {
        long[] buffer = new long[16_384];
        for (int i = 0; i < 10; i++) {
            int steps = WorkloadCalibrator.calibrateMemorySteps(
                10_000_000L + i * 1_000, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
            assertTrue(steps > 0, "Memory calibration " + i + " failed");
        }
    }

    /**
     * Test with extremely small buffer (minimal memory).
     */
    @Test
    @DisplayName("Should handle minimum size buffer (8 elements)")
    void minimalBuffer() {
        long[] buffer = new long[8];
        int steps = WorkloadCalibrator.calibrateMemorySteps(
            5_000_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0);
    }

    /**
     * Test with very large buffer (realistic limit).
     */
    @Test
    @DisplayName("Should handle large buffer (100MB)")
    void largeBuffer() {
        long[] buffer = new long[12_500_000];  // ~100MB
        int steps = WorkloadCalibrator.calibrateMemorySteps(
            10_000_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0);
    }

    /**
     * Test with extreme target values.
     */
    @Test
    @DisplayName("CPU calibration should handle 1 nanosecond target")
    void extremelySmallCpuTarget() {
        int iter = WorkloadCalibrator.calibrateIterations(1L, 12345L);
        assertTrue(iter >= 1);
    }

    /**
     * Test memory calibration with extreme small target.
     */
    @Test
    @DisplayName("Memory calibration should handle 1 nanosecond target")
    void extremelySmallMemoryTarget() {
        long[] buffer = new long[1024];
        int steps = WorkloadCalibrator.calibrateMemorySteps(
            1L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps >= 1);
    }

    /**
     * Test with extreme large target (just below Long.MAX_VALUE).
     */
    @Test
    @DisplayName("CPU calibration should handle near-maximum target")
    void extremelyLargeCpuTarget() {
        int iter = WorkloadCalibrator.calibrateIterations(Long.MAX_VALUE - 1, 12345L);
        assertTrue(iter > 0);
    }

    /**
     * Test memory with extreme large target.
     */
    @Test
    @DisplayName("Memory calibration should handle near-maximum target")
    void extremelyLargeMemoryTarget() {
        long[] buffer = new long[16_384];
        int steps = WorkloadCalibrator.calibrateMemorySteps(
            Long.MAX_VALUE - 1, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
        assertTrue(steps > 0);
    }

    /**
     * Test all combinations of access patterns and write-back flags.
     */
    @Test
    @DisplayName("All pattern and write-back combinations should work")
    void allPatternWriteBackCombinations() {
        long[] buffer = new long[16_384];
        boolean[] writeBackFlags = {false, true};
        
        for (MemoryBoundWorkload.AccessPattern pattern : MemoryBoundWorkload.AccessPattern.values()) {
            for (boolean writeBack : writeBackFlags) {
                int steps = WorkloadCalibrator.calibrateMemorySteps(
                    5_000_000L, buffer, pattern, writeBack, 12345L);
                assertTrue(steps > 0,
                    "Failed for pattern=" + pattern + " writeBack=" + writeBack);
            }
        }
    }

    /**
     * Test with various seed values (including boundary seeds).
     */
    @Test
    @DisplayName("Calibration should work with various seed values")
    void variousSeedValues() {
        long[] seeds = {
            0L,
            1L,
            -1L,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            0xDEADBEEFL,
            0x12345678ABCDEF01L
        };
        
        for (long seed : seeds) {
            int iter = WorkloadCalibrator.calibrateIterations(5_000_000L, seed);
            assertTrue(iter > 0, "Failed with seed=" + seed);
        }
    }

    /**
     * Test memory calibration with various seeds.
     */
    @Test
    @DisplayName("Memory calibration should work with various seeds")
    void memoryVariousSeedValues() {
        long[] buffer = new long[16_384];
        long[] seeds = {
            0L,
            1L,
            -1L,
            Long.MAX_VALUE,
            Long.MIN_VALUE,
            0xDEADBEEFL,
            0x12345678ABCDEF01L
        };
        
        for (long seed : seeds) {
            int steps = WorkloadCalibrator.calibrateMemorySteps(
                5_000_000L, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, seed);
            assertTrue(steps > 0, "Failed with seed=" + seed);
        }
    }

    /**
     * Test that output is always within reasonable bounds.
     */
    @Test
    @DisplayName("CPU calibration output should never be negative or zero")
    void cpuOutputAlwaysPositive() {
        long[] targets = {1L, 100L, 1_000L, 1_000_000L, Long.MAX_VALUE};
        long[] seeds = {0L, 1L, Long.MAX_VALUE, 12345L};
        
        for (long target : targets) {
            for (long seed : seeds) {
                int iter = WorkloadCalibrator.calibrateIterations(target, seed);
                assertTrue(iter > 0,
                    "Target=" + target + " seed=" + seed + " produced " + iter);
            }
        }
    }

    /**
     * Test memory calibration output bounds.
     */
    @Test
    @DisplayName("Memory calibration output should never be negative or zero")
    void memoryOutputAlwaysPositive() {
        long[] buffer = new long[16_384];
        long[] targets = {1L, 100L, 1_000L, 1_000_000L, Long.MAX_VALUE};
        long[] seeds = {0L, 1L, Long.MAX_VALUE, 12345L};
        
        for (long target : targets) {
            for (long seed : seeds) {
                int steps = WorkloadCalibrator.calibrateMemorySteps(
                    target, buffer,
                    MemoryBoundWorkload.AccessPattern.RANDOM, false, seed);
                assertTrue(steps > 0,
                    "Target=" + target + " seed=" + seed + " produced " + steps);
            }
        }
    }

    /**
     * Test power-of-two targets (common alignment).
     */
    @Test
    @DisplayName("CPU calibration should work with power-of-two targets")
    void cpuPowerOfTwoTargets() {
        for (int i = 0; i < 30; i++) {
            long target = 1L << i;
            if (target > 0) {  // Skip overflow
                int iter = WorkloadCalibrator.calibrateIterations(target, 12345L);
                assertTrue(iter > 0, "Failed for 2^" + i + " = " + target);
            }
        }
    }

    /**
     * Test memory calibration with power-of-two buffer sizes.
     */
    @Test
    @DisplayName("Memory calibration should work with power-of-two buffer sizes")
    void memoryPowerOfTwoBufferSizes() {
        for (int i = 0; i < 20; i++) {
            int bufferSize = 1 << i;
            long[] buffer = new long[bufferSize];
            int steps = WorkloadCalibrator.calibrateMemorySteps(
                5_000_000L, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
            assertTrue(steps > 0, "Failed for 2^" + i + " = " + bufferSize);
        }
    }

    /**
     * Test non-power-of-two buffer sizes.
     */
    @Test
    @DisplayName("Memory calibration should work with non-power-of-two buffer sizes")
    void memoryNonPowerOfTwoBufferSizes() {
        int[] bufferSizes = {100, 333, 1000, 4567, 12345, 99999};
        for (int bufferSize : bufferSizes) {
            long[] buffer = new long[bufferSize];
            int steps = WorkloadCalibrator.calibrateMemorySteps(
                5_000_000L, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, 12345L);
            assertTrue(steps > 0, "Failed for buffer size " + bufferSize);
        }
    }

    /**
     * Verify that multiple calibrations with same parameters are identical.
     */
    @Test
    @DisplayName("CPU calibration should be reasonably reproducible")
    void cpuReproducibility() {
        int[] results = new int[5];
        for (int i = 0; i < 5; i++) {
            results[i] = WorkloadCalibrator.calibrateIterations(10_000_000L, 54321L);
        }
        
        // Check that all results are within ±20% of the first
        for (int i = 1; i < results.length; i++) {
            double ratio = (double) results[i] / results[0];
            assertTrue(ratio >= 0.80 && ratio <= 1.20,
                "Invocation " + i + " ratio should be within ±20%, got " + ratio);
        }
    }

    /**
     * Verify memory calibration reproducibility.
     */
    @Test
    @DisplayName("Memory calibration should be reasonably reproducible")
    void memoryReproducibility() {
        long[] buffer = new long[16_384];
        int[] results = new int[5];
        for (int i = 0; i < 5; i++) {
            results[i] = WorkloadCalibrator.calibrateMemorySteps(
                10_000_000L, buffer,
                MemoryBoundWorkload.AccessPattern.SEQUENTIAL, true, 54321L);
        }
        
        // Check that all results are within ±20% of the first
        for (int i = 1; i < results.length; i++) {
            double ratio = (double) results[i] / results[0];
            assertTrue(ratio >= 0.80 && ratio <= 1.20,
                "Invocation " + i + " ratio should be within ±20%, got " + ratio);
        }
    }

}
