package com.scott;

import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experiment 1 — shared-queue contention.
 *
 * <p>Properties under test:
 * <ul>
 *   <li>Deterministic {@code taskId % K} routing (no atomic on submit path).</li>
 *   <li>Sub-pool worker-count split is as-even-as-possible.</li>
 *   <li>Total worker count and total offered workload are unchanged.</li>
 *   <li>Global worker IDs are unique across sub-pools.</li>
 *   <li>{@code sharedQueueCount == 1} takes the single-executor fast path.</li>
 * </ul>
 */
class SharedQueueContentionTest {

    private static Workload noopWorkload() { return () -> 0L; }

    private static Task noopTask(long id, Runnable onDone) {
        return new Task(id, WorkloadKind.CPU, 0L, System.nanoTime(), true,
                noopWorkload(), onDone);
    }

    private static void submitAndDrain(SharedOnlyDispatcher d, int n) throws Exception {
        AtomicInteger done = new AtomicInteger();
        for (int i = 0; i < n; i++) d.submit(noopTask(i, done::incrementAndGet));
        d.shutdown();
        assertTrue(d.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(n, done.get());
    }

    @Test
    void singleQueue_holdsOneExecutorAndDrains() throws Exception {
        SharedOnlyDispatcher d = new SharedOnlyDispatcher(
                4, PinningConfig.disabled(), null, null, 1);
        assertEquals(1, d.executors().length);
        assertEquals("SharedOnly", d.label());
        submitAndDrain(d, 1000);
        long[] m = d.measurementSubmitCounts();
        assertEquals(1, m.length);
        assertEquals(1000L, m[0]);
    }

    @Test
    void twoQueues_taskIdModRouting_perfectSplit() throws Exception {
        SharedOnlyDispatcher d = new SharedOnlyDispatcher(
                4, PinningConfig.disabled(), null, null, 2);
        assertEquals(2, d.executors().length);
        submitAndDrain(d, 2000);
        long[] m = d.measurementSubmitCounts();
        assertEquals(2000L, m[0] + m[1]);
        // taskId % 2 splits 2000 dense ids evenly: 1000 each.
        assertEquals(1000L, m[0]);
        assertEquals(1000L, m[1]);
    }

    @Test
    void fourQueues_allQueuesReceiveWork() throws Exception {
        SharedOnlyDispatcher d = new SharedOnlyDispatcher(
                8, PinningConfig.disabled(), null, null, 4);
        assertEquals(4, d.executors().length);
        submitAndDrain(d, 4000);
        long sum = 0;
        for (long c : d.measurementSubmitCounts()) {
            assertEquals(1000L, c);   // taskId % 4 splits 4000 dense ids evenly
            sum += c;
        }
        assertEquals(4000L, sum);
    }

    @Test
    void workerSizes_areAsEvenAsPossible() {
        // 7 workers over 3 queues => sizes [3, 2, 2]
        SharedOnlyDispatcher d = new SharedOnlyDispatcher(
                7, PinningConfig.disabled(), null, null, 3);
        SharedExecutor[] execs = d.executors();
        assertEquals(3, execs[0].getPoolSize());
        assertEquals(2, execs[1].getPoolSize());
        assertEquals(2, execs[2].getPoolSize());
        // Total worker count preserved.
        int total = 0;
        for (SharedExecutor e : execs) total += e.getPoolSize();
        assertEquals(7, total);
        d.shutdown();
    }

    @Test
    void globalWorkerIds_areUniqueAcrossSubPools() throws Exception {
        // Verify via thread names, which now include the global worker id.
        SharedOnlyDispatcher d = new SharedOnlyDispatcher(
                8, PinningConfig.disabled(), null, null, 4);
        Set<String> seen = ConcurrentHashMap.newKeySet();
        AtomicInteger done = new AtomicInteger();
        // Each task records the thread name it ran on.
        int n = 4000;
        for (int i = 0; i < n; i++) {
            final int id = i;
            d.submit(new Task(id, WorkloadKind.CPU, 0L, System.nanoTime(), true,
                    () -> { seen.add(Thread.currentThread().getName()); return 0L; },
                    done::incrementAndGet));
        }
        d.shutdown();
        assertTrue(d.awaitTermination(10, TimeUnit.SECONDS));
        assertEquals(n, done.get());
        // Names should be exactly SharedWorker-0..7 — 8 unique global ids,
        // no duplicates across the 4 sub-pools.
        assertEquals(8, seen.size(), "expected 8 unique worker names, got: " + seen);
        for (int i = 0; i < 8; i++) {
            assertTrue(seen.contains("SharedWorker-" + i),
                    "missing global worker id " + i + " in " + seen);
        }
    }

    @Test
    void queueCountLargerThanWorkers_isRejected() {
        assertThrows(IllegalArgumentException.class, () ->
                new SharedOnlyDispatcher(2, PinningConfig.disabled(), null, null, 4));
    }
}

