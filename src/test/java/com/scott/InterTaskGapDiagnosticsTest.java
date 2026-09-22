package com.scott;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for inter-task gap diagnostics functionality.
 */
@DisplayName("Inter-Task Gap Diagnostics Tests")
class InterTaskGapDiagnosticsTest {

    private InterTaskGapDiagnostics diagnostics;

    @BeforeEach
    void setUp() {
        diagnostics = new InterTaskGapDiagnostics(1024);
    }

    @Test
    @DisplayName("Should initialize with zero metrics")
    void testInitialState() {
        assertEquals(0, diagnostics.tasksProcessed());
        assertEquals(0, diagnostics.interTaskGapAvgNs());
        assertEquals(0, diagnostics.pollTimeAvgNs());
        assertEquals(0.0, diagnostics.emptyQueueRatio());
    }

    @Test
    @DisplayName("Should record single inter-task gap")
    void testRecordSingleGap() {
        long gapNs = 10_000;      // 10 μs
        long pollNs = 2_000;      // 2 μs
        long emptyQueueNs = 1_400;
        long nonEmptyQueueNs = 600;
        long executionNs = 50_000; // 50 μs

        diagnostics.recordInterTaskGap(gapNs, pollNs, emptyQueueNs, nonEmptyQueueNs, executionNs);

        assertEquals(1, diagnostics.tasksProcessed());
        assertEquals(gapNs, diagnostics.interTaskGapAvgNs());
        assertEquals(pollNs, diagnostics.pollTimeAvgNs());
        assertEquals(executionNs, diagnostics.executionTimeAvgNs());
        assertEquals(gapNs, diagnostics.interTaskGapMaxNs());
        assertEquals(pollNs, diagnostics.pollTimeMaxNs());
    }

    @Test
    @DisplayName("Should calculate average correctly")
    void testCalculateAverage() {
        // Record 3 gaps: 10μs, 20μs, 30μs → average 20μs
        diagnostics.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        diagnostics.recordInterTaskGap(20_000, 3_000, 2_100, 900, 50_000);
        diagnostics.recordInterTaskGap(30_000, 4_000, 2_800, 1_200, 50_000);

        assertEquals(3, diagnostics.tasksProcessed());
        assertEquals(20_000, diagnostics.interTaskGapAvgNs());
        assertEquals(3_000, diagnostics.pollTimeAvgNs());
        assertEquals(30_000, diagnostics.interTaskGapMaxNs()); // max should be 30μs
    }

    @Test
    @DisplayName("Should track empty queue ratio")
    void testEmptyQueueRatio() {
        // First gap: 70% empty, 30% non-empty
        diagnostics.recordInterTaskGap(10_000, 7_000, 7_000, 3_000, 50_000);
        // Second gap: 50% empty, 50% non-empty
        diagnostics.recordInterTaskGap(10_000, 10_000, 5_000, 5_000, 50_000);

        // Average ratio should be (7000+5000) / (7000+3000+5000+5000) = 12000/20000 = 0.6
        double expectedRatio = 12_000.0 / 20_000.0;
        assertEquals(expectedRatio, diagnostics.emptyQueueRatio(), 0.001);
    }

    @Test
    @DisplayName("Should collect histogram values")
    void testHistogramCollection() {
        // Record multiple gaps
        for (int i = 1; i <= 5; i++) {
            long gapNs = i * 10_000; // 10μs, 20μs, 30μs, 40μs, 50μs
            diagnostics.recordInterTaskGap(gapNs, 2_000, 1_400, 600, 50_000);
        }

        assertEquals(5, diagnostics.tasksProcessed());
        
        // With histograms, we should be able to query percentiles
        long p50 = diagnostics.interTaskGapPercentile(50);
        assertTrue(p50 > 0, "p50 should be positive with histogram");
        
        long p99 = diagnostics.interTaskGapPercentile(99);
        assertTrue(p99 > 0, "p99 should be positive");
        
        // p99 should be >= p50
        assertTrue(p99 >= p50, "p99 should be >= p50");
    }

    @Test
    @DisplayName("Should handle zero poll time")
    void testZeroPollTime() {
        diagnostics.recordInterTaskGap(5_000, 0, 0, 0, 50_000);
        
        assertEquals(1, diagnostics.tasksProcessed());
        assertEquals(5_000, diagnostics.interTaskGapAvgNs());
        assertEquals(0, diagnostics.pollTimeAvgNs());
        assertEquals(0.0, diagnostics.emptyQueueRatio(), 0.001);
    }

    @Test
    @DisplayName("Should handle large numbers")
    void testLargeNumbers() {
        long largeGap = 1_000_000_000; // 1 second
        long largePoll = 100_000_000;  // 100 ms
        long largeExecution = 500_000_000; // 500 ms

        diagnostics.recordInterTaskGap(largeGap, largePoll, 70_000_000, 30_000_000, largeExecution);

        assertEquals(1, diagnostics.tasksProcessed());
        assertEquals(largeGap, diagnostics.interTaskGapMaxNs());
        assertEquals(largePoll, diagnostics.pollTimeMaxNs());
    }

    @Test
    @DisplayName("Without histogram, percentiles should return -1")
    void testPercentileWithoutHistogram() {
        InterTaskGapDiagnostics noHistogram = new InterTaskGapDiagnostics(0);
        noHistogram.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);

        assertEquals(-1, noHistogram.interTaskGapPercentile(50));
        assertEquals(-1, noHistogram.pollTimePercentile(95));
    }
}

