package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive diagnostics test for SlidingWindowCV.
 * 
 * Verifies:
 * 1. Sample collection correctness (endExecution - startExecution)
 * 2. Time unit consistency (nanos vs micros)
 * 3. Variance accumulation and sample expiry
 * 4. Welford algorithm accuracy vs raw calculation
 * 5. Edge case: all samples equal to target (CV = 0)
 */
@DisplayName("SlidingWindowCV Diagnostics")
public class SlidingWindowCVDiagnosticsTest {

    /**
     * Test 1: Verify that samples are executionTimeNanos (endExecution - startExecution),
     * not including queue time or end-to-end latency.
     * 
     * Scenario: Add samples representing pure execution times
     */
    @Test
    @DisplayName("Sample must be executionTimeNanos only")
    void testSampleIntegrity() {
        SlidingWindowCV window = new SlidingWindowCV(100); // 100ms window
        
        // Add 3 samples with known execution times
        long now = System.nanoTime();
        window.addSample(now + 1_000_000, 50_000);      // 50µs execution
        window.addSample(now + 2_000_000, 75_000);      // 75µs execution
        window.addSample(now + 3_000_000, 25_000);      // 25µs execution
        
        assertEquals(3, window.getSampleCount(), "Should have 3 samples");
        
        // Get raw stats
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long max = stats[2];
        
        // Mean should be (50000 + 75000 + 25000) / 3 = 50000 nanos = 50µs
        assertEquals(50_000, mean, "Mean should be 50µs = 50000ns");
        assertEquals(75_000, max, "Max should be 75000ns");
    }

    /**
     * Test 2: Time unit consistency - ensure all operations use nanos internally,
     * but output can be in micros without precision loss.
     */
    @Test
    @DisplayName("Time units: nanos internally, micros for output")
    void testTimeUnitConsistency() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        // All samples are 50 micros = 50000 nanos
        long targetMicros = 50;
        long targetNanos = targetMicros * 1000;
        
        long now = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            window.addSample(now + i * 1_000_000, targetNanos);
        }
        
        // Verify: if all samples are identical, stddev = 0, CV = 0
        long[] stats = window.computeRawStatsNanos();
        long stddevNanos = stats[1];
        
        assertEquals(0, stddevNanos, "Stddev should be 0 when all samples are identical");
    }

    /**
     * Test 3: Variance accumulation - add samples progressively and verify
     * that M2 (sum of squared differences) accumulates correctly.
     */
    @Test
    @DisplayName("Variance accumulation via Welford's algorithm")
    void testVarianceAccumulation() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        // Add samples: 10µs, 20µs, 30µs (nanos: 10000, 20000, 30000)
        long now = System.nanoTime();
        window.addSample(now, 10_000);
        window.addSample(now + 1_000_000, 20_000);
        window.addSample(now + 2_000_000, 30_000);
        
        // Manual calculation:
        // mean = 20000, M2 = (10-20)² + (20-20)² + (30-20)² = 100 + 0 + 100 = 200 * 10^8
        // variance = M2/3 = 200/3 ≈ 66.67
        // stddev = sqrt(66.67) ≈ 8.165 (in nanos)
        
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long stddev = stats[1];
        
        assertEquals(20_000, mean, "Mean should be 20000");
        // stddev should be around 8165ns (in the ballpark of sqrt(66.67*10^4))
        assertTrue(stddev >= 8000 && stddev <= 8200, 
            "Stddev should be around 8165ns, got " + stddev);
    }

    /**
     * Test 4: Sample expiry - add samples beyond window boundary and verify
     * that old samples are removed and stats recalculated correctly.
     */
    @Test
    @DisplayName("Sample expiry removes old samples correctly")
    void testSampleExpiry() {
        SlidingWindowCV window = new SlidingWindowCV(10); // 10ms window
        
        long now = System.nanoTime();
        long windowNanos = 10_000_000;  // 10ms in nanos
        
        // Add sample at time 0
        window.addSample(now, 50_000);  // 50µs
        assertEquals(1, window.getSampleCount());
        
        // Add samples beyond window boundary (>10ms later)
        long cutoff = now + windowNanos;
        window.addSample(now + windowNanos + 1_000, 75_000);  // Just beyond cutoff
        assertEquals(2, window.getSampleCount());
        
        // Expire old samples
        window.expireOldSamples(cutoff);
        
        // Should have only 1 sample left (the one beyond cutoff)
        assertEquals(1, window.getSampleCount());
        long[] stats = window.computeRawStatsNanos();
        assertEquals(75_000, stats[0], "Remaining sample should be 75000ns");
    }

    /**
     * Test 5: Welford validation - compare Welford's online stats with
     * independent raw calculation.
     */
    @Test
    @DisplayName("Welford's algorithm matches raw calculation")
    void testWelfordValidation() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        // Add random samples
        long now = System.nanoTime();
        long[] samples = { 10_000, 25_000, 15_000, 50_000, 30_000 };
        for (int i = 0; i < samples.length; i++) {
            window.addSample(now + i * 100_000, samples[i]);
        }
        
        // Get Welford stats
        double welfMean = window.getMean();
        double welfM2 = window.getM2();
        long welfN = window.getSampleCount();
        
        // Get raw stats
        long[] rawStats = window.computeRawStatsNanos();
        long rawMean = rawStats[0];
        
        // Validate: discrepancy should be near zero
        double[] discrepancies = window.validateWelfordStats();
        assertTrue(Math.abs(discrepancies[0]) < 2, 
            "Mean discrepancy should be < 2ns, got " + discrepancies[0]);
        assertTrue(Math.abs(discrepancies[1]) < 2,
            "Stddev discrepancy should be < 2ns, got " + discrepancies[1]);
    }

    /**
     * Test 6: CRITICAL - All samples equal to target = CV should be 0.
     * This is the fundamental verification that conversion factor is correct.
     */
    @Test
    @DisplayName("Constant samples: CV = 0 (fundamental verification)")
    void testConstantSamplesCV() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        // durationFile value = 50 micros for all tasks
        long targetMicros = 50;
        long targetNanos = targetMicros * 1_000;  // 50000 nanos
        
        long now = System.nanoTime();
        // Add 1000 samples, all exactly 50 micros
        for (int i = 0; i < 1000; i++) {
            window.addSample(now + i * 100_000, targetNanos);
        }
        
        // Raw stats should show mean=50000ns, stddev=0
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long stddev = stats[1];
        
        assertEquals(50_000, mean, "Mean should be exactly 50000ns");
        assertEquals(0, stddev, "Stddev should be exactly 0");
        
        // CV = stddev / mean = 0 / 50000 = 0
        double cv = stddev / (double) mean;
        assertEquals(0.0, cv, "CV with constant samples should be 0.0");
    }

    /**
     * Test 7: targetDeviation calculation for constant samples.
     * If all samples = target, then targetDeviation = 0 / target = 0.
     */
    @Test
    @DisplayName("Target deviation = 0 when all samples match target")
    void testTargetDeviationConstant() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long targetMicros = 50;
        long targetNanos = targetMicros * 1_000;
        
        long now = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            window.addSample(now + i * 100_000, targetNanos);
        }
        
        // Calculate sum of squared differences from target
        double sumSquaredDiffs = window.getTargetDeviationSumSquares(targetNanos);
        assertEquals(0.0, sumSquaredDiffs, 1e-10,
            "Sum of squared differences should be 0 when all samples equal target");
        
        // targetDeviation = sqrt(0 / N) / target = 0
        double targetDeviation = Math.sqrt(sumSquaredDiffs / window.getSampleCount()) / targetNanos;
        assertEquals(0.0, targetDeviation, "Target deviation should be 0");
    }

    /**
     * Test 8: Top N samples - verify sorting is correct.
     */
    @Test
    @DisplayName("Top N largest samples are sorted correctly")
    void testTopNLargestSamples() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        window.addSample(now, 10_000);
        window.addSample(now + 100_000, 50_000);
        window.addSample(now + 200_000, 30_000);
        window.addSample(now + 300_000, 100_000);
        window.addSample(now + 400_000, 5_000);
        
        long[][] topSamples = window.getTopNLargestSamples(3);
        
        assertEquals(3, topSamples.length, "Should return top 3 samples");
        assertEquals(100_000, topSamples[0][0], "First should be 100000");
        assertEquals(50_000, topSamples[1][0], "Second should be 50000");
        assertEquals(30_000, topSamples[2][0], "Third should be 30000");
    }

    /**
     * Test 9: Verify that SampleBuffer correctly records executionNanos.
     * This is where the samples originate before reaching SlidingWindowCV.
     */
    @Test
    @DisplayName("SampleBuffer records execution time correctly")
    void testSampleBufferRecording() {
        SampleBuffer buffer = new SampleBuffer(100);
        
        long now = System.nanoTime();
        long completionTime = now + 1_000_000; // arbitrary completion time
        long executionTime = 50_000;            // 50µs execution
        
        buffer.offerSample(completionTime, executionTime);
        
        SampleBuffer.Sample sample = buffer.pollSample();
        assertNotNull(sample);
        assertEquals(completionTime, sample.completionNanos());
        assertEquals(executionTime, sample.executionNanos());
    }

    /**
     * Test 10: End-to-end integration - create a window, add many samples
     * with various durations, and verify all diagnostics.
     */
    @Test
    @DisplayName("End-to-end integration with realistic workload")
    void testEndToEndIntegration() {
        SlidingWindowCV window = new SlidingWindowCV(100);
        
        long now = System.nanoTime();
        long targetMicros = 50;
        long targetNanos = targetMicros * 1_000;
        
        // Create a mix of samples: mostly 50µs, some outliers
        long[] sampleValues = new long[1000];
        for (int i = 0; i < 900; i++) {
            sampleValues[i] = targetNanos;  // 50µs (nominal)
        }
        for (int i = 900; i < 950; i++) {
            sampleValues[i] = targetNanos * 2;  // 100µs (2x target)
        }
        for (int i = 950; i < 1000; i++) {
            sampleValues[i] = targetNanos / 2;  // 25µs (0.5x target)
        }
        
        for (int i = 0; i < sampleValues.length; i++) {
            window.addSample(now + i * 100_000, sampleValues[i]);
        }
        
        // Verify basic statistics
        long[] stats = window.computeRawStatsNanos();
        long mean = stats[0];
        long stddev = stats[1];
        long max = stats[2];
        long min = stats[3];
        
        assertTrue(mean > 40_000 && mean < 60_000, 
            "Mean should be around 50000ns, got " + mean);
        assertTrue(stddev > 5_000,
            "Stddev should be significant, got " + stddev);
        assertEquals(targetNanos * 2, max, "Max should be 100µs");
        assertEquals(targetNanos / 2, min, "Min should be 25µs");
        
        // Verify CV calculation
        double cv = stddev / (double) mean;
        System.out.printf("Integration test: mean=%.1fµs, stddev=%.1fµs, max=%.1fµs, CV=%.4f%n",
            mean / 1000.0, stddev / 1000.0, max / 1000.0, cv);
        assertTrue(cv > 0 && cv < 1.0, "CV should be between 0 and 1");
        
        // Verify Welford validation
        double[] discrepancies = window.validateWelfordStats();
        assertTrue(Math.abs(discrepancies[0]) < 10, "Mean discrepancy should be small");
        assertTrue(Math.abs(discrepancies[1]) < 100, "Stddev discrepancy should be small");
    }
}

