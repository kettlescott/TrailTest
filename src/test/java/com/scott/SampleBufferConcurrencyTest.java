package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.RepeatedTest;
import static org.junit.jupiter.api.Assertions.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Concurrency test for SampleBuffer SPSC (single-producer/single-consumer) correctness.
 * 
 * <p>Verifies:
 * <ul>
 *   <li>Samples are not corrupted or duplicated</li>
 *   <li>Write-read ordering is preserved</li>
 *   <li>Dropped counts are accurate</li>
 *   <li>Volatile semantics ensure visibility</li>
 * </ul>
 */
public class SampleBufferConcurrencyTest {

    /**
     * Test 1: Basic producer-consumer without overflow.
     * Produces N samples, consumes N samples.
     */
    @RepeatedTest(10)
    public void testBasicProducerConsumer() throws InterruptedException {
        int sampleCount = 1000;
        SampleBuffer buffer = new SampleBuffer(8192);
        AtomicLong consumerCount = new AtomicLong(0L);
        CountDownLatch producerDone = new CountDownLatch(1);
        
        Thread producer = new Thread(() -> {
            for (int i = 0; i < sampleCount; i++) {
                long completionTime = System.nanoTime();
                long duration = i * 1000L;  // Incrementing duration
                buffer.offerSample(completionTime, duration);
            }
            producerDone.countDown();
        });
        
        Thread consumer = new Thread(() -> {
            while (consumerCount.get() < sampleCount || buffer.hasData()) {
                SampleBuffer.Sample sample;
                while ((sample = buffer.pollSample()) != null) {
                    // Verify sample is in expected range
                    long idx = consumerCount.getAndIncrement();
                    long expectedDuration = idx * 1000L;
                    assertEquals(expectedDuration, sample.executionNanos(), 
                            "Sample corruption at index " + idx);
                }
                if (consumerCount.get() >= sampleCount) break;
                Thread.yield();
            }
        });
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        
        // Final verification
        assertEquals(sampleCount, consumerCount.get(), "Not all samples consumed");
        assertEquals(0L, buffer.droppedSampleCount(), "Samples should not be dropped");
        assertFalse(buffer.hasData(), "Buffer should be empty");
    }

    /**
     * Test 2: Overflow behavior - dropped samples are tracked correctly.
     */
    @RepeatedTest(5)
    public void testOverflowTracking() throws InterruptedException {
        int capacity = 100;
        int produceCount = 500;  // More than capacity
        SampleBuffer buffer = new SampleBuffer(capacity);
        AtomicLong consumerCount = new AtomicLong(0L);
        
        Thread producer = new Thread(() -> {
            for (int i = 0; i < produceCount; i++) {
                buffer.offerSample(System.nanoTime(), i);
            }
        });
        
        Thread consumer = new Thread(() -> {
            int consumed = 0;
            while (consumed < produceCount * 2) {  // Consume until no more
                SampleBuffer.Sample sample;
                while ((sample = buffer.pollSample()) != null) {
                    consumerCount.incrementAndGet();
                    consumed++;
                }
                if (consumed >= capacity) break;  // Stop after consuming capacity
                Thread.yield();
            }
        });
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        
        long consumed = consumerCount.get();
        long dropped = buffer.droppedSampleCount();
        
        // Verify: produced = consumed + dropped
        assertEquals((long)produceCount, consumed + dropped,
                "Samples accounting: produced=" + produceCount + 
                ", consumed=" + consumed + ", dropped=" + dropped);
        
        // Verify dropped > 0 (since we produced more than capacity)
        assertTrue(dropped > 0, "Should have dropped some samples");
    }

    /**
     * Test 3: Concurrent stress test with multiple rapid cycles.
     * Producer writes rapidly, consumer reads rapidly.
     */
    @RepeatedTest(5)
    public void testRapidCycle() throws InterruptedException {
        int cycles = 10000;
        SampleBuffer buffer = new SampleBuffer(8192);
        AtomicLong successCount = new AtomicLong(0L);
        
        Thread producer = new Thread(() -> {
            for (int i = 0; i < cycles; i++) {
                buffer.offerSample(System.nanoTime(), i);
            }
        });
        
        Thread consumer = new Thread(() -> {
            int consumed = 0;
            while (consumed < cycles) {
                SampleBuffer.Sample sample = buffer.pollSample();
                if (sample != null) {
                    successCount.incrementAndGet();
                    consumed++;
                } else {
                    Thread.yield();
                }
            }
        });
        
        producer.start();
        consumer.start();
        producer.join();
        consumer.join();
        
        assertEquals(cycles, successCount.get(), "All samples should be consumed");
    }

    /**
     * Test 4: Volatile semantics - ensure writeIndex visibility.
     * Producer writes a recognizable pattern, consumer verifies it's seen.
     */
    @Test
    public void testVolatileSemantics() throws InterruptedException {
        SampleBuffer buffer = new SampleBuffer(1024);
        long magicValue = 0xDEADBEEFL;
        AtomicLong receivedValue = new AtomicLong(0L);
        CountDownLatch done = new CountDownLatch(1);
        
        Thread producer = new Thread(() -> {
            // Write known pattern
            buffer.offerSample(System.nanoTime(), magicValue);
        });
        
        Thread consumer = new Thread(() -> {
            while (true) {
                SampleBuffer.Sample sample = buffer.pollSample();
                if (sample != null) {
                    receivedValue.set(sample.executionNanos());
                    done.countDown();
                    return;
                }
                Thread.yield();
            }
        });
        
        consumer.start();
        producer.start();
        done.await();  // Wait for consumer to receive
        
        assertEquals(magicValue, receivedValue.get(),
                "Volatile writeIndex ensures value is visible to consumer");
    }

    /**
     * Test 5: Empty buffer state - no corruption on repeated polls.
     */
    @Test
    public void testEmptyBufferState() {
        SampleBuffer buffer = new SampleBuffer(256);
        
        // Poll from empty buffer
        assertNull(buffer.pollSample(), "Empty buffer should return null");
        assertNull(buffer.pollSample(), "Empty buffer should return null");
        
        // Buffer should remain valid
        buffer.offerSample(100L, 200L);
        SampleBuffer.Sample s = buffer.pollSample();
        assertNotNull(s, "Should be able to add after empty polls");
        assertEquals(200L, s.executionNanos());
    }

    /**
     * Test 6: Verify SampleBuffer is truly SPSC by enforcing single producer/consumer.
     * If multiple threads write to same buffer, dropped count should increase significantly.
     */
    @RepeatedTest(3)
    public void testSPSCInvariantEnforcement() throws InterruptedException {
        SampleBuffer buffer = new SampleBuffer(100);
        int samplesPerThread = 200;
        
        // Create 2 producer threads (violates SPSC)
        Thread producer1 = new Thread(() -> {
            for (int i = 0; i < samplesPerThread; i++) {
                buffer.offerSample(System.nanoTime(), i);
            }
        });
        
        Thread producer2 = new Thread(() -> {
            for (int i = 0; i < samplesPerThread; i++) {
                buffer.offerSample(System.nanoTime(), i + samplesPerThread);
            }
        });
        
        AtomicLong consumerCount = new AtomicLong(0L);
        Thread consumer = new Thread(() -> {
            int read = 0;
            while (read < samplesPerThread * 2 * 10) {
                SampleBuffer.Sample sample;
                while ((sample = buffer.pollSample()) != null) {
                    consumerCount.incrementAndGet();
                    read++;
                }
                if (read >= samplesPerThread * 2) break;
                Thread.yield();
            }
        });
        
        producer1.start();
        producer2.start();
        consumer.start();
        producer1.join();
        producer2.join();
        consumer.join();
        
        long consumed = consumerCount.get();
        long dropped = buffer.droppedSampleCount();
        long total = consumed + dropped;
        
        // Verify samples are accounted for
        assertEquals(samplesPerThread * 2L, total,
                "All produced samples should be either consumed or dropped");
        
        // With 2 producers, we expect many drops due to buffer overflow
        assertTrue(dropped > 0, "Expected drops with multiple producers");
    }
}

