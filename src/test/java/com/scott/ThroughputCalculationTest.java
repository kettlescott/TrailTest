package com.scott;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("LatencyRecorder throughput calculation tests")
class ThroughputCalculationTest {

    @Test
    @DisplayName("Should calculate throughput correctly from recorded tasks and duration")
    void testThroughputCalculation() {
        LatencyRecorder recorder = new LatencyRecorder(1000);
        
        // Record 1000 tasks with duration 10 seconds
        for (int i = 0; i < 1000; i++) {
            recorder.recordRaw(0, 1000, 5000, 6000);
        }
        
        double throughput = recorder.throughputTasksPerSecond(10.0);
        assertEquals(100.0, throughput, 0.01, "Should calculate 100 tasks/sec for 1000 tasks in 10 seconds");
    }

    @Test
    @DisplayName("Should return 0 throughput when duration is zero")
    void testThroughputWithZeroDuration() {
        LatencyRecorder recorder = new LatencyRecorder(100);
        for (int i = 0; i < 100; i++) {
            recorder.recordRaw(0, 1000, 5000, 6000);
        }
        
        double throughput = recorder.throughputTasksPerSecond(0.0);
        assertEquals(0.0, throughput, "Should return 0 when duration is 0");
    }

    @Test
    @DisplayName("Should return 0 throughput when no tasks recorded")
    void testThroughputWithNoTasks() {
        LatencyRecorder recorder = new LatencyRecorder(100);
        
        double throughput = recorder.throughputTasksPerSecond(10.0);
        assertEquals(0.0, throughput, "Should return 0 when no tasks recorded");
    }

    @Test
    @DisplayName("Should calculate throughput for large task count")
    void testThroughputForLargeTaskCount() {
        LatencyRecorder recorder = new LatencyRecorder(35_000_000);
        
        // Simulate 35 million tasks over 120 seconds (as in the example)
        // We'll use a smaller loop for speed, but verify the calculation formula
        for (int i = 0; i < 100_000; i++) {
            recorder.recordRaw(0, 1000, 5000, 6000);
        }
        
        // 100,000 tasks in 1 second = 100,000 tasks/sec
        double throughput = recorder.throughputTasksPerSecond(1.0);
        assertEquals(100_000.0, throughput, 1.0, "Should handle large throughput values");
    }
}

