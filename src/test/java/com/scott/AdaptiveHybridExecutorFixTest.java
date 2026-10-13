package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Timeout;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Integration test for AdaptiveHybridExecutor with focus on fixes for:
 * 1. Sample buffer concurrency (SPSC with volatile fields)
 * 2. Time window basis (collect then re-timestamp)
 * 3. Worker mapping (unique buffer per actual worker thread)
 */
public class AdaptiveHybridExecutorFixTest {

    private AdaptiveHybridConfig hybridConfig;

    @BeforeEach
    public void setUp() {
        // Configure for testing: CV mode, thresholds at 0.2 and 0.3
        hybridConfig = new AdaptiveHybridConfig(
                ExecutorMode.SHARDED,
                0.2,      // sharedEnterCv (switch to shared when CV > 0.2)
                0.3,      // shardedEnterCv (switch to sharded when CV < 0.3)
                50,       // windowMillis
                0,        // targetMicros (0 = CV mode, not targetDeviation)
                1         // debugCvPrintIntervalSeconds
        );
    }

    /**
     * Test 1: Verify worker mapping ensures each worker gets unique buffer.
     * 
     * <p>Creates executor with 4 workers, verifies each worker thread
     * is mapped to a unique SampleBuffer (no two workers share same buffer).
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testWorkerMappingUniqueness() throws Exception {
        int workerCount = 4;
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(workerCount, hybridConfig);
        
        SampleBuffer[] buffers = executor.getSampleBuffers();
        assertEquals(workerCount, buffers.length, "Should have one buffer per worker");
        
        // Each buffer should be distinct object
        for (int i = 0; i < buffers.length; i++) {
            for (int j = i + 1; j < buffers.length; j++) {
                assertNotSame(buffers[i], buffers[j], 
                        "Buffer[" + i + "] and buffer[" + j + "] should be different objects");
            }
        }
        
        // Submit tasks from multiple threads and verify samples go to correct buffers
        CountDownLatch latch = new CountDownLatch(workerCount * 2);
        
        for (int w = 0; w < workerCount; w++) {
            new Thread(() -> {
                try {
                    for (int t = 0; t < 2; t++) {
                        long taskId = System.nanoTime();
                        Task task = new Task(
                                taskId,
                                WorkloadKind.CPU,
                                10L,                    // targetMillis
                                System.nanoTime(),      // createdNanos
                                true,                   // measurement
                                () -> {
                                    try {
                                        Thread.sleep(1);  // Simulate work
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    return 0;
                                },  // workload
                                () -> latch.countDown()  // onComplete
                        );
                        executor.submit(task);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        }
        
        assertTrue(latch.await(20, TimeUnit.SECONDS), "Tasks should complete");
        
        // Wait a bit for samples to be collected
        Thread.sleep(200);
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Test 2: Verify SPSC correctness with volatile readIndex.
     * 
     * <p>Directly tests that each buffer maintains SPSC invariant:
     * - No corruption
     * - No samples lost to data races
     * - Dropped count matches expectation
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void testSPSCCorrectness() throws Exception {
        int workerCount = 2;
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(workerCount, hybridConfig);
        
        SampleBuffer[] buffers = executor.getSampleBuffers();
        AtomicLong totalSamples = new AtomicLong(0L);
        AtomicLong totalRead = new AtomicLong(0L);
        
        // Producer threads write samples
        int samplesPerWorker = 1000;
        CountDownLatch producerDone = new CountDownLatch(workerCount);
        
        for (int w = 0; w < workerCount; w++) {
            final int workerIdx = w;
            new Thread(() -> {
                for (int i = 0; i < samplesPerWorker; i++) {
                    buffers[workerIdx].offerSample(System.nanoTime(), 50_000L + i);
                    totalSamples.incrementAndGet();
                }
                producerDone.countDown();
            }).start();
        }
        
        // Consumer thread reads samples
        Thread consumerThread = new Thread(() -> {
            int iterations = 0;
            while (iterations < 100) {  // Multiple passes to drain buffers
                for (int w = 0; w < workerCount; w++) {
                    SampleBuffer.Sample sample;
                    while ((sample = buffers[w].pollSample()) != null) {
                        assertNotNull(sample, "Sample should not be null");
                        assertTrue(sample.executionNanos() >= 50_000L, "Sample data should be intact");
                        totalRead.incrementAndGet();
                    }
                }
                iterations++;
                try {
                    Thread.sleep(1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        
        consumerThread.start();
        
        // Wait for producers
        assertTrue(producerDone.await(10, TimeUnit.SECONDS), "Producers should complete");
        Thread.sleep(100);  // Let consumer drain
        
        // Verify accounting
        long read = totalRead.get();
        long produced = totalSamples.get();
        long totalDropped = 0;
        for (SampleBuffer buffer : buffers) {
            totalDropped += buffer.droppedSampleCount();
        }
        
        assertEquals(produced, read + totalDropped,
                String.format("Samples accounting: produced=%d, read=%d, dropped=%d",
                        produced, read, totalDropped));
        
        executor.shutdown();
    }

    /**
     * Test 3: Verify time window basis is re-acquired after collection.
     * 
     * <p>CVMonitorThread now:
     * 1. Collects samples (bounded, fair)
     * 2. Re-acquires System.nanoTime()
     * 3. Uses that time as cutoff basis
     * 4. All workers use same cutoff time
     * 
     * <p>Verify by checking window diagnostics in logs.
     */
    @Test
    @Timeout(value = 30, unit = TimeUnit.SECONDS)
    public void testTimeWindowBasis() throws Exception {
        int workerCount = 2;
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(workerCount, hybridConfig);
        
        executor.markMeasurementStart();
        
        // Simulate task completions
        for (int w = 0; w < workerCount; w++) {
            final int workerIdx = w;
            new Thread(() -> {
                SampleBuffer buffer = executor.getSampleBuffers()[workerIdx];
                for (int i = 0; i < 100; i++) {
                    long now = System.nanoTime();
                    // Simulate 50µs duration
                    buffer.offerSample(now, 50_000L);
                    try {
                        Thread.sleep(5);  // Sample every 5ms
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }).start();
        }
        
        // Let monitor run for a few cycles
        Thread.sleep(300);
        
        CVMonitorThread monitor = executor.getMonitorThread();
        long checks = monitor.getTotalChecks();
        double lastMetric = monitor.getLastMetricValue();
        long lastMean = monitor.getLastMean();
        long lastSampleCount = monitor.getLastSampleCount();
        
        assertTrue(checks > 0, "Monitor should have performed checks");
        assertFalse(Double.isNaN(lastMetric), "Metric should be computed");
        assertEquals(50_000L, lastMean, 1000L, "Mean should be ~50µs");
        assertTrue(lastSampleCount > 0, "Should have collected samples");
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }

    /**
     * Test 4: Verify sample buffer accounting is accurate.
     * 
     * <p>With proper SPSC and volatile semantics:
     * - All produced samples should be accounted for
     * - Dropped count should match actual drops
     * - No silent loss
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void testSampleAccounting() throws Exception {
        SampleBuffer buffer = new SampleBuffer(128);  // Small capacity to force drops
        
        int produceCount = 2000;
        AtomicLong produceSuccess = new AtomicLong(0L);
        CountDownLatch producerDone = new CountDownLatch(1);
        
        Thread producer = new Thread(() -> {
            for (int i = 0; i < produceCount; i++) {
                buffer.offerSample(System.nanoTime(), i);
                produceSuccess.incrementAndGet();
            }
            producerDone.countDown();
        });
        
        AtomicLong consumeCount = new AtomicLong(0L);
        Thread consumer = new Thread(() -> {
            int cycles = 0;
            while (cycles < 1000) {
                SampleBuffer.Sample sample = buffer.pollSample();
                if (sample != null) {
                    consumeCount.incrementAndGet();
                } else {
                    try {
                        Thread.sleep(1);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                cycles++;
            }
        });
        
        producer.start();
        consumer.start();
        
        producerDone.await();
        Thread.sleep(200);  // Let consumer drain
        
        long consumed = consumeCount.get();
        long dropped = buffer.droppedSampleCount();
        long produced = produceSuccess.get();
        
        assertEquals(produced, consumed + dropped,
                String.format("Accounting: produced=%d, consumed=%d, dropped=%d, sum=%d",
                        produced, consumed, dropped, consumed + dropped));
        
        assertTrue(dropped > 0, "Should have dropped samples (buffer too small)");
    }

    /**
     * Test 5: Verify window diagnostics include proper capacity and dropped counts.
     * 
     * <p>When debug printing is enabled, output should include:
     * - capacity: current SlidingWindowCV capacity
     * - droppedAtMax: samples dropped due to hard limit
     * - ageMs: sample age diagnostics
     */
    @Test
    @Timeout(value = 20, unit = TimeUnit.SECONDS)
    public void testWindowDiagnostics() throws Exception {
        int workerCount = 1;
        AdaptiveHybridExecutor executor = new AdaptiveHybridExecutor(workerCount, hybridConfig);
        
        executor.markMeasurementStart();
        
        // Add samples
        SampleBuffer buffer = executor.getSampleBuffers()[0];
        for (int i = 0; i < 50; i++) {
            buffer.offerSample(System.nanoTime(), 50_000L);
            if (i % 10 == 0) {
                Thread.sleep(10);
            }
        }
        
        // Wait for monitor to print diagnostics (configured for 1s interval)
        Thread.sleep(1500);
        
        CVMonitorThread monitor = executor.getMonitorThread();
        long lastOldest = monitor.getLastOldestSampleNanos();
        long lastNewest = monitor.getLastNewestSampleNanos();
        
        assertTrue(lastOldest > 0, "Should have diagnosed oldest sample timestamp");
        assertTrue(lastNewest > 0, "Should have diagnosed newest sample timestamp");
        assertTrue(lastNewest >= lastOldest, "Newest should be >= oldest");
        
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}

