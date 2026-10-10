package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for {@link WorkloadCalibrator} verifying calibrated
 * values produce workloads that execute for approximately the target duration.
 */
@DisplayName("WorkloadCalibrator Integration Tests")
class WorkloadCalibratorIntegrationTest {

    /**
     * Tolerance for execution time verification.
     * We allow ±25% variance due to JIT compilation, OS scheduling, etc.
     */
    private static final double TIME_TOLERANCE_FACTOR = 0.25;

    @SuppressWarnings("unused")
    private static volatile long blackhole;

    private static void consume(long value) {
        blackhole = value;
    }

    /**
     * Verify that calibrated CPU iterations produce approximately target duration.
     */
    @Test
    @DisplayName("calibrated CPU iterations should execute in approximately target time")
    void cpuIterationsProduceTargetDuration() {
        long targetNanos = 10_000_000L;  // 10 milliseconds
        int iterations = WorkloadCalibrator.calibrateIterations(targetNanos, 12345L);
        
        // Execute the calibrated workload
        var workload = new CpuBoundWorkload(12345L, iterations);
        long start = System.nanoTime();
        consume(workload.execute());
        long elapsed = System.nanoTime() - start;
        
        // Verify timing is within tolerance
        long lowerBound = (long) (targetNanos * (1 - TIME_TOLERANCE_FACTOR));
        long upperBound = (long) (targetNanos * (1 + TIME_TOLERANCE_FACTOR));
        
        assertTrue(elapsed >= lowerBound && elapsed <= upperBound,
            String.format("Elapsed %d ns should be within [%d, %d] ns (target: %d)",
                elapsed, lowerBound, upperBound, targetNanos));
    }

    /**
     * Verify that calibrated memory steps produce approximately target duration.
     */
    @Test
    @DisplayName("calibrated memory steps should execute in approximately target time")
    void memoryStepsProduceTargetDuration() {
        long targetNanos = 10_000_000L;  // 10 milliseconds
        long[] buffer = new long[16_384];
        
        int steps = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM,
            false,
            12345L);
        
        // Execute the calibrated workload
        var workload = new MemoryBoundWorkload(buffer, steps,
            MemoryBoundWorkload.AccessPattern.RANDOM, 12345L, false);
        long start = System.nanoTime();
        consume(workload.execute());
        long elapsed = System.nanoTime() - start;
        
        // Verify timing is within tolerance
        long lowerBound = (long) (targetNanos * (1 - TIME_TOLERANCE_FACTOR));
        long upperBound = (long) (targetNanos * (1 + TIME_TOLERANCE_FACTOR));
        
        assertTrue(elapsed >= lowerBound && elapsed <= upperBound,
            String.format("Elapsed %d ns should be within [%d, %d] ns (target: %d)",
                elapsed, lowerBound, upperBound, targetNanos));
    }

    /**
     * Verify CPU calibration consistency across multiple runs.
     */
    @Test
    @DisplayName("CPU calibration should be reasonably consistent")
    void cpuCalibrationConsistency() {
        long targetNanos = 5_000_000L;  // 5 milliseconds
        
        int iter1 = WorkloadCalibrator.calibrateIterations(targetNanos, 111L);
        int iter2 = WorkloadCalibrator.calibrateIterations(targetNanos, 111L);
        int iter3 = WorkloadCalibrator.calibrateIterations(targetNanos, 111L);
        
        // Check consistency within ±15% due to timing variations
        double ratio12 = (double) iter2 / iter1;
        double ratio23 = (double) iter3 / iter2;
        
        assertTrue(ratio12 >= 0.85 && ratio12 <= 1.15, 
            "Calibrations should be within ±15%, got ratio " + ratio12);
        assertTrue(ratio23 >= 0.85 && ratio23 <= 1.15, 
            "Calibrations should be within ±15%, got ratio " + ratio23);
    }

    /**
     * Verify memory calibration consistency across multiple runs.
     */
    @Test
    @DisplayName("Memory calibration should be reasonably consistent")
    void memoryCalibrationConsistency() {
        long targetNanos = 5_000_000L;  // 5 milliseconds
        long[] buffer = new long[16_384];
        
        int steps1 = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.SEQUENTIAL,
            false,
            222L);
        int steps2 = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.SEQUENTIAL,
            false,
            222L);
        int steps3 = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.SEQUENTIAL,
            false,
            222L);
        
        // Check consistency within ±15% due to timing variations
        double ratio12 = (double) steps2 / steps1;
        double ratio23 = (double) steps3 / steps2;
        
        assertTrue(ratio12 >= 0.85 && ratio12 <= 1.15,
            "Calibrations should be within ±15%, got ratio " + ratio12);
        assertTrue(ratio23 >= 0.85 && ratio23 <= 1.15,
            "Calibrations should be within ±15%, got ratio " + ratio23);
    }

    /**
     * Verify CPU calibration scales linearly with target duration.
     */
    @Test
    @DisplayName("CPU calibration should scale linearly with target duration")
    void cpuLinearScaling() {
        int iter5ms = WorkloadCalibrator.calibrateIterations(5_000_000L, 333L);
        int iter10ms = WorkloadCalibrator.calibrateIterations(10_000_000L, 333L);
        int iter20ms = WorkloadCalibrator.calibrateIterations(20_000_000L, 333L);
        
        // Should roughly double when target doubles
        double ratio1 = (double) iter10ms / iter5ms;
        double ratio2 = (double) iter20ms / iter10ms;
        
        // Allow ±30% variance from 2x scaling
        assertTrue(ratio1 >= 1.7 && ratio1 <= 2.3,
            "10ms/5ms ratio should be ~2.0, got " + ratio1);
        assertTrue(ratio2 >= 1.7 && ratio2 <= 2.3,
            "20ms/10ms ratio should be ~2.0, got " + ratio2);
    }

    /**
     * Verify memory calibration scales linearly with target duration.
     */
    @Test
    @DisplayName("Memory calibration should scale linearly with target duration")
    void memoryLinearScaling() {
        long[] buffer = new long[16_384];
        
        int steps5ms = WorkloadCalibrator.calibrateMemorySteps(
            5_000_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 444L);
        int steps10ms = WorkloadCalibrator.calibrateMemorySteps(
            10_000_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 444L);
        int steps20ms = WorkloadCalibrator.calibrateMemorySteps(
            20_000_000L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 444L);
        
        // Should roughly double when target doubles
        double ratio1 = (double) steps10ms / steps5ms;
        double ratio2 = (double) steps20ms / steps10ms;
        
        // Allow ±30% variance from 2x scaling
        assertTrue(ratio1 >= 1.7 && ratio1 <= 2.3,
            "10ms/5ms ratio should be ~2.0, got " + ratio1);
        assertTrue(ratio2 >= 1.7 && ratio2 <= 2.3,
            "20ms/10ms ratio should be ~2.0, got " + ratio2);
    }

    /**
     * Verify that different access patterns produce different calibrations.
     */
    @Test
    @DisplayName("Different access patterns should produce different calibrations")
    void differentPatternsProduceDifferentValues() {
        long[] buffer = new long[16_384];
        long targetNanos = 10_000_000L;
        
        int stepsRandom = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 555L);
        int stepsSequential = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.SEQUENTIAL, false, 555L);
        
        // Different patterns have different memory access characteristics
        // Sequential is typically faster, so should need more steps for same duration
        assertTrue(stepsRandom > 0 && stepsSequential > 0);
        // They should be different (sequential access is typically faster)
        assertNotEquals(stepsRandom, stepsSequential,
            "Different access patterns should produce different step counts");
    }

    /**
     * Verify that write-back flag affects calibration.
     */
    @Test
    @DisplayName("WriteBack flag should affect calibration values")
    void writeBackAffectsCalibration() {
        long[] buffer = new long[16_384];
        long targetNanos = 10_000_000L;
        
        int stepsNoWrite = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 666L);
        int stepsWithWrite = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, true, 666L);
        
        // Both should be positive
        assertTrue(stepsNoWrite > 0 && stepsWithWrite > 0);
        // Write-back typically has different cache behavior than pure reads
        // They should generally be different
        assertNotEquals(stepsNoWrite, stepsWithWrite,
            "WriteBack flag should affect calibration");
    }

    /**
     * Verify buffer size affects memory calibration.
     */
    @Test
    @DisplayName("Buffer size should affect memory calibration")
    void bufferSizeAffectsCalibration() {
        long[] smallBuffer = new long[256];
        long[] largeBuffer = new long[1_000_000];
        long targetNanos = 10_000_000L;
        
        int stepsSmall = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, smallBuffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 777L);
        int stepsLarge = WorkloadCalibrator.calibrateMemorySteps(
            targetNanos, largeBuffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 777L);
        
        // Both should be positive
        assertTrue(stepsSmall > 0 && stepsLarge > 0);
        // Larger buffer affects cache behavior and should produce different values
        assertNotEquals(stepsSmall, stepsLarge,
            "Different buffer sizes should produce different calibrations");
    }

    /**
     * Verify very small target durations are handled correctly.
     */
    @Test
    @DisplayName("Very small target durations should produce positive calibrations")
    void verySmallTargets() {
        int iter = WorkloadCalibrator.calibrateIterations(100L, 888L);  // 100 ns
        assertTrue(iter >= 1, "Should produce at least 1 iteration");
    }

    /**
     * Verify that calibration respects the maximum value constraint.
     */
    @Test
    @DisplayName("Calibration should handle extremely large targets")
    void extremeLargeTargets() {
        int iter = WorkloadCalibrator.calibrateIterations(Long.MAX_VALUE, 3333L);
        assertTrue(iter > 0, "Should produce valid positive value");
    }

    /**
     * Verify calibration works across a range of realistic targets.
     */
    @Test
    @DisplayName("Calibration should work across realistic target durations")
    void realisticTargetRange() {
        long[] targets = {
            1_000L,           // 1 microsecond
            10_000L,          // 10 microseconds
            100_000L,         // 100 microseconds
            1_000_000L,       // 1 millisecond
            10_000_000L,      // 10 milliseconds
            100_000_000L,     // 100 milliseconds
        };
        
        for (long target : targets) {
            int iter = WorkloadCalibrator.calibrateIterations(target, 1111L);
            assertTrue(iter > 0, "Target " + target + " should produce positive iterations");
        }
    }

    /**
     * Verify memory calibration works across realistic targets with various buffers.
     */
    @Test
    @DisplayName("Memory calibration should work across realistic targets")
    void memoryRealisticTargetRange() {
        long[] buffer = new long[16_384];
        long[] targets = {
            1_000L,           // 1 microsecond
            10_000L,          // 10 microseconds
            100_000L,         // 100 microseconds
            1_000_000L,       // 1 millisecond
            10_000_000L,      // 10 milliseconds
        };
        
        for (long target : targets) {
            int steps = WorkloadCalibrator.calibrateMemorySteps(
                target, buffer,
                MemoryBoundWorkload.AccessPattern.RANDOM, false, 2222L);
            assertTrue(steps > 0, "Target " + target + " should produce positive steps");
        }
    }

    /**
     * Verify that calibration respects the minimum unit constraint.
     */
    @Test
    @DisplayName("Calibration should always return at least 1 unit")
    void minimumUnitGuarantee() {
        // Test very small targets for both CPU and memory
        int iter = WorkloadCalibrator.calibrateIterations(1L, 3333L);
        assertTrue(iter >= 1, "CPU calibration should return at least 1");
        
        long[] buffer = new long[16_384];
        int steps = WorkloadCalibrator.calibrateMemorySteps(1L, buffer,
            MemoryBoundWorkload.AccessPattern.RANDOM, false, 4444L);
        assertTrue(steps >= 1, "Memory calibration should return at least 1");
    }
}









