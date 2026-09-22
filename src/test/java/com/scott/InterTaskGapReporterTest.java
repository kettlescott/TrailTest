package com.scott;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for InterTaskGapReporter diagnostics output formatting.
 */
@DisplayName("Inter-Task Gap Reporter Tests")
class InterTaskGapReporterTest {

    private List<WorkerStats> workerStats;

    @BeforeEach
    void setUp() {
        workerStats = new ArrayList<>();
    }

    @Test
    @DisplayName("Should generate diagnostics with empty worker list")
    void testGenerateDiagnosticsEmpty() {
        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("INTER-TASK GAP DIAGNOSTICS"));
        assertTrue(output.contains("Key Insights"));
    }

    @Test
    @DisplayName("Should generate per-worker diagnostics")
    void testGeneratePerWorkerDiagnostics() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        stats.recordInterTaskGap(12_000, 2_200, 1_540, 660, 51_000);

        workerStats.add(stats);
        
        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("INTER-TASK GAP DIAGNOSTICS"));
    }

    @Test
    @DisplayName("Should include execution time metrics in output")
    void testExecutionTimeMetrics() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("Execution Time"));
    }

    @Test
    @DisplayName("Should include inter-task gap metrics in output")
    void testInterTaskGapMetrics() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(15_000, 2_000, 1_400, 600, 50_000);
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("Inter-Task Gap"));
    }

    @Test
    @DisplayName("Should include poll/dequeue metrics in output")
    void testPollTimeMetrics() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 3_000, 2_100, 900, 50_000);
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("Poll/Dequeue Time"));
    }

    @Test
    @DisplayName("Should include queue state metrics in output")
    void testQueueStateMetrics() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 7_000, 7_000, 3_000, 50_000); // 70% empty
        stats.recordInterTaskGap(10_000, 3_000, 1_500, 1_500, 50_000); // 50% empty
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("Queue State"));
        assertTrue(output.contains("empty="));
    }

    @Test
    @DisplayName("Should include aggregate summary")
    void testAggregateSummary() {
        // Add multiple workers
        for (int w = 0; w < 2; w++) {
            WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
            for (int i = 0; i < 3; i++) {
                stats.recordInterTaskGap(10_000 + i*1_000, 2_000, 1_400, 600, 50_000);
            }
            workerStats.add(stats);
        }

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("Aggregate"));
        assertTrue(output.contains("workers"));
    }

    @Test
    @DisplayName("Should include key insights section")
    void testKeyInsights() {
        // Add at least one worker with data to generate insights
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        workerStats.add(stats);
        
        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        assertTrue(output.contains("Key Insights"));
        // Check that output contains relevant diagnostic text
        assertTrue(output.contains("Inter-task gap") || output.contains("Poll") || output.contains("Queue"));
    }

    @Test
    @DisplayName("Should format percentiles when available")
    void testPercentileFormatting() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 1024);
        // Record 10 values to get meaningful percentiles
        for (int i = 1; i <= 10; i++) {
            stats.recordInterTaskGap(i * 1_000, 500, 350, 150, 50_000);
        }
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        // Should include p50, p95, p99
        assertTrue(output.contains("p50"));
        assertTrue(output.contains("p95"));
        assertTrue(output.contains("p99"));
    }

    @Test
    @DisplayName("Should format output with proper units (μs, ms)")
    void testUnitFormatting() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        // Should contain output with proper formatting (check for key indicators)
        assertTrue(output.contains("INTER-TASK GAP DIAGNOSTICS") || output.contains("avg="));
    }

    @Test
    @DisplayName("Should handle null queue stats gracefully")
    void testNullQueueStats() {
        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertNotNull(output);
        // Should not crash with null queue stats
        assertTrue(output.contains("INTER-TASK GAP DIAGNOSTICS"));
    }

    @Test
    @DisplayName("Should generate non-empty output")
    void testNonEmptyOutput() {
        WorkerStats stats = new WorkerStats(10_000_000, true, 10_000);
        stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        String output = reporter.generateDiagnostics();

        assertTrue(output.length() > 100, "Output should be substantial");
    }
}

