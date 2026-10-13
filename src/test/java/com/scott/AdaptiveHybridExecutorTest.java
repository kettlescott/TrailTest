package com.scott;

import org.junit.jupiter.api.Test;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test for AdaptiveHybridExecutor basic functionality.
 */
class AdaptiveHybridExecutorTest {

    @Test
    void createsExecutorWithValidConfig() {
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED,  // initialMode
                0.16,                  // sharedEnterCv
                0.08,                  // shardedEnterCv
                100L                   // windowMillis
        );
        
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(32, config);
        assertNotNull(executor);
        assertEquals(ExecutorState.SHARDED, executor.getCurrentState());
    }

    @Test
    void startsInConfiguredInitialMode() {
        AdaptiveHybridConfig configSharded = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor execSharded = new AdaptiveHybridExecutor(16, configSharded);
        assertEquals(ExecutorState.SHARDED, execSharded.getCurrentState());

        AdaptiveHybridConfig configShared = new AdaptiveHybridConfig(
                ExecutorMode.SHARED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor execShared = new AdaptiveHybridExecutor(16, configShared);
        assertEquals(ExecutorState.SHARED, execShared.getCurrentState());
    }

    @Test
    void submitsAndExecutesBasicTask() throws Exception {
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(4, config);

        CountDownLatch latch = new CountDownLatch(1);
        Workload workload = new CpuBoundWorkload(System.nanoTime(), 100);  // seed, iterations
        
        Task task = new Task(
                0,
                WorkloadKind.CPU,
                1L,
                System.nanoTime(),
                true,
                workload,
                latch,
                null  // onComplete runnable
        );

        executor.submit(task);
        
        // Wait for task to complete
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Task should complete within 5s");
        assertTrue(task.finishNanos() > task.startNanos(), "Task should have execution time");
        assertTrue(java.util.Arrays.stream(executor.getSampleBuffers()).anyMatch(SampleBuffer::hasData),
                "Hybrid completion sink should record at least one CV sample");
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void handlesMultipleTasks() throws Exception {
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(8, config);

        int taskCount = 50;
        CountDownLatch latch = new CountDownLatch(taskCount);
        
        for (int i = 0; i < taskCount; i++) {
            Workload workload = new CpuBoundWorkload(System.nanoTime() ^ i, 50);  // seed, iterations
            Task task = new Task(
                    i,
                    WorkloadKind.CPU,
                    1L,
                    System.nanoTime(),
                    true,
                    workload,
                    latch,
                    null  // onComplete runnable
            );
            executor.submit(task);
        }

        // Wait for all tasks
        assertTrue(latch.await(30, TimeUnit.SECONDS), 
                "All tasks should complete within 30s");
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void rejectsSubmitAfterShutdown() throws InterruptedException {
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(4, config);
        executor.shutdown();

        CountDownLatch latch = new CountDownLatch(1);
        Workload workload = new CpuBoundWorkload(123L, 10);  // seed, iterations
        Task task = new Task(0, WorkloadKind.CPU, 1L, System.nanoTime(), true, workload, latch, null);

        assertThrows(IllegalStateException.class, () -> executor.submit(task),
                "Should reject submission after shutdown");
    }

    @Test
    void monitorThreadStartsAndStops() throws Exception {
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(4, config);
        CVMonitorThread monitor = executor.getMonitorThread();

        assertNotNull(monitor);
        assertFalse(monitor.isAlive(), "Monitor thread should not start automatically");

        executor.markMeasurementStart();
        assertTrue(monitor.isAlive(), "Monitor thread should be alive after markMeasurementStart");

        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    @Test
    void sampleBuffersCreatedPerWorker() {
        int workerCount = 8;
        AdaptiveHybridConfig config = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED, 0.16, 0.08, 100L
        );
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(workerCount, config);

        SampleBuffer[] buffers = executor.getSampleBuffers();
        assertNotNull(buffers);
        assertEquals(workerCount, buffers.length);

        for (SampleBuffer buf : buffers) {
            assertNotNull(buf);
            assertFalse(buf.hasData());
        }
    }
}

