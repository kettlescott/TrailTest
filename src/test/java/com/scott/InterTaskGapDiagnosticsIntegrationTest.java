package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for inter-task gap diagnostics being written to summary files.
 */
@DisplayName("Inter-Task Gap Diagnostics Integration Tests")
class InterTaskGapDiagnosticsIntegrationTest {

    @Test
    @DisplayName("Should generate diagnostics that can be written to file")
    void testDiagnosticsFileOutput() throws Exception {
        Path tmpDir = Files.createTempDirectory("gap-diag-test");
        try {
            List<WorkerStats> workerStats = new ArrayList<>();
            for (int w = 0; w < 2; w++) {
                WorkerStats stats = new WorkerStats(10_000_000, true, 1024);
                for (int i = 0; i < 100; i++) {
                    stats.recordInterTaskGap(10_000 + i, 2_000, 1_400, 600, 50_000);
                }
                workerStats.add(stats);
            }

            // Generate diagnostics
            InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
            String diagnosticsOutput = reporter.generateDiagnostics();

            // Write to file
            Path summaryFile = tmpDir.resolve("summary_shared.txt");
            Files.writeString(summaryFile, diagnosticsOutput);

            // Verify file was written
            assertTrue(Files.exists(summaryFile));
            
            // Verify content
            String content = Files.readString(summaryFile);
            assertNotNull(content);
            assertTrue(content.contains("INTER-TASK GAP DIAGNOSTICS"));
            assertTrue(content.contains("Per-Worker Gap Metrics"));
            assertTrue(content.contains("Aggregate"));
            assertTrue(content.contains("Key Insights"));
        } finally {
            // Cleanup
            Files.walk(tmpDir)
                .sorted((a, b) -> b.compareTo(a))
                .forEach(p -> {
                    try { Files.delete(p); } catch (Exception e) { }
                });
        }
    }

    @Test
    @DisplayName("Should append diagnostics to existing summary content")
    void testAppendDiagnosticsToSummary() throws Exception {
        Path tmpDir = Files.createTempDirectory("gap-diag-append-test");
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("=== Benchmark Summary ===\n");
            summary.append("Throughput: 100,000 tasks/s\n");

            List<WorkerStats> workerStats = new ArrayList<>();
            WorkerStats stats = new WorkerStats(10_000_000, true, 512);
            stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
            workerStats.add(stats);

            InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
            summary.append(reporter.generateDiagnostics());

            Path summaryFile = tmpDir.resolve("summary_shared.txt");
            Files.writeString(summaryFile, summary.toString());

            String content = Files.readString(summaryFile);
            assertTrue(content.contains("Benchmark Summary"));
            assertTrue(content.contains("INTER-TASK GAP DIAGNOSTICS"));
        } finally {
            Files.walk(tmpDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception e) { }
            });
        }
    }

    @Test
    @DisplayName("Should format diagnostics for different queue counts")
    void testDiagnosticsForMultipleWorkers() throws Exception {
        Path tmpDir = Files.createTempDirectory("gap-diag-multi-test");
        try {
            List<WorkerStats> workerStats = new ArrayList<>();
            for (int w = 0; w < 32; w++) {
                WorkerStats stats = new WorkerStats(10_000_000, true, 1024);
                for (int i = 0; i < 50; i++) {
                    long gapNs = 10_000 + (w * 1_000);
                    long pollNs = 2_000 + (w * 100);
                    stats.recordInterTaskGap(gapNs, pollNs, 1_400, 600, 50_000);
                }
                workerStats.add(stats);
            }

            InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
            String diagnosticsOutput = reporter.generateDiagnostics();
            
            Path summaryFile = tmpDir.resolve("summary_sharded.txt");
            Files.writeString(summaryFile, diagnosticsOutput);

            String content = Files.readString(summaryFile);
            assertTrue(content.contains("Worker-0"));
            assertTrue(content.contains("Worker-31"));
            assertTrue(content.contains("Aggregate (all 32 workers)"));
        } finally {
            Files.walk(tmpDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception e) { }
            });
        }
    }

    @Test
    @DisplayName("Should include percentile information in file output")
    void testPercentileOutputInFile() throws Exception {
        Path tmpDir = Files.createTempDirectory("gap-diag-percentile-test");
        try {
            List<WorkerStats> workerStats = new ArrayList<>();
            WorkerStats stats = new WorkerStats(10_000_000, true, 1024);
            
            for (int i = 1; i <= 100; i++) {
                long gapNs = i * 100;
                stats.recordInterTaskGap(gapNs, 20, 14, 6, 50_000);
            }
            
            workerStats.add(stats);

            InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
            String diagnosticsOutput = reporter.generateDiagnostics();

            Path summaryFile = tmpDir.resolve("summary_shared.txt");
            Files.writeString(summaryFile, diagnosticsOutput);

            String content = Files.readString(summaryFile);
            assertTrue(content.contains("p50") || content.contains("p95") || content.contains("p99"));
        } finally {
            Files.walk(tmpDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception e) { }
            });
        }
    }

    @Test
    @DisplayName("Should handle zero-measurement case gracefully")
    void testDiagnosticsWithZeroMeasurements() throws Exception {
        Path tmpDir = Files.createTempDirectory("gap-diag-zero-test");
        try {
            List<WorkerStats> workerStats = new ArrayList<>();
            WorkerStats stats = new WorkerStats(10_000_000, true, 512);
            // Don't record any data - just create empty stats
            workerStats.add(stats);

            InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
            String diagnosticsOutput = reporter.generateDiagnostics();

            assertNotNull(diagnosticsOutput);
            assertFalse(diagnosticsOutput.isEmpty());

            Path summaryFile = tmpDir.resolve("summary_shared.txt");
            Files.writeString(summaryFile, diagnosticsOutput);
            assertTrue(Files.exists(summaryFile));
        } finally {
            Files.walk(tmpDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception e) { }
            });
        }
    }

    @Test
    @DisplayName("Should produce consistent output across multiple generations")
    void testConsistentOutput() {
        List<WorkerStats> workerStats = new ArrayList<>();
        WorkerStats stats = new WorkerStats(10_000_000, true, 1024);
        stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
        workerStats.add(stats);

        InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
        
        String output1 = reporter.generateDiagnostics();
        String output2 = reporter.generateDiagnostics();

        assertEquals(output1, output2, "Output should be consistent across multiple calls");
    }

    @Test
    @DisplayName("Should be compatible with typical summary file format")
    void testCompatibilityWithSummaryFormat() throws Exception {
        Path tmpDir = Files.createTempDirectory("gap-diag-compat-test");
        try {
            StringBuilder summary = new StringBuilder();
            summary.append("=== Benchmark Configuration ===\n");
            summary.append("Mode: SHARED\n");
            summary.append("Workers: 32\n");
            summary.append("Queue Count: 8\n");
            summary.append("\n");

            summary.append("=== Results ===\n");
            summary.append("Completed: 39,500,000 tasks\n");
            summary.append("Duration: 60.0 seconds\n");
            summary.append("Throughput: 658,333 tasks/s\n");
            summary.append("\n");

            List<WorkerStats> workerStats = new ArrayList<>();
            for (int w = 0; w < 8; w++) {
                WorkerStats stats = new WorkerStats(10_000_000, true, 1024);
                stats.recordInterTaskGap(10_000, 2_000, 1_400, 600, 50_000);
                workerStats.add(stats);
            }

            InterTaskGapReporter reporter = new InterTaskGapReporter(workerStats, null);
            summary.append(reporter.generateDiagnostics());

            Path summaryFile = tmpDir.resolve("summary_shared.txt");
            Files.writeString(summaryFile, summary.toString());

            String content = Files.readString(summaryFile);
            assertTrue(content.length() > 200);
            assertTrue(content.contains("Benchmark Configuration"));
            assertTrue(content.contains("INTER-TASK GAP DIAGNOSTICS"));
        } finally {
            Files.walk(tmpDir).sorted((a, b) -> b.compareTo(a)).forEach(p -> {
                try { Files.delete(p); } catch (Exception e) { }
            });
        }
    }
}

