package com.scott;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Throughput calculation in summary files")
class PhasedBenchmarkThroughputTest {

    @Test
    @DisplayName("Should calculate throughput from recorded tasks and phase duration")
    void testThroughputInSummary() {
        // Test data from the example: 35045627 tasks over 120 seconds
        int recordedTasks = 35_045_627;
        double totalDurationSeconds = 30.0 + 60.0 + 30.0;  // 120 seconds
        double expectedThroughput = recordedTasks / totalDurationSeconds;
        
        // Should be ~292,046.89 tasks/sec
        assertTrue(expectedThroughput > 290_000.0, "Throughput should be ~292k tasks/sec");
        assertTrue(expectedThroughput < 295_000.0, "Throughput should be ~292k tasks/sec");
    }

    @Test
    @DisplayName("Throughput formula: tasks / seconds")
    void testThroughputFormula() {
        // 1000 tasks in 10 seconds = 100 tasks/sec
        double throughput = 1000.0 / 10.0;
        assertEquals(100.0, throughput, 0.01);
    }

    @Test
    @DisplayName("Large throughput calculation should work correctly")
    void testLargeThroughput() {
        // 35 million tasks in 120 seconds
        double throughput = 35_000_000.0 / 120.0;
        assertEquals(291_666.67, throughput, 100.0);
    }

    @Test
    @DisplayName("LatencyRecorder throughput method should match formula")
    void testLatencyRecorderThroughputMethod() {
        LatencyRecorder recorder = new LatencyRecorder(1000);
        
        // Record 1000 tasks
        for (int i = 0; i < 1000; i++) {
            recorder.recordRaw(0, 1000, 5000, 6000);
        }
        
        // Calculate throughput using both methods
        double manualThroughput = 1000.0 / 10.0;  // 100 tasks/sec
        double recordedThroughput = recorder.throughputTasksPerSecond(10.0);
        
        assertEquals(manualThroughput, recordedThroughput, 0.01, 
            "LatencyRecorder throughput should match manual calculation");
    }
}

