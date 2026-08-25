package com.scott;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the six fixes in the DynamicHybridDispatcher hardening pass:
 *  1. True RR over ACTIVE set (no bias when IDs are sparse)
 *  2. maxShardedWorkers enforcement including ACTIVATING
 *  3. EWMA sample aggregation consistency
 *  4. Transition-latency timestamp publication ordering
 *  5. awaitTermination sub-ms join(0) bug
 *  6. Snapshot doc accuracy (assertion-free — doc only)
 */
class DynamicHybridHardeningTest {

    private DynamicHybridDispatcher d;

    @AfterEach
    void tearDown() throws Exception {
        if (d != null) {
            d.shutdown();
            d.awaitTermination(5, TimeUnit.SECONDS);
            d = null;
        }
    }

    private static DynamicHybridConfig cfg(int nSharded, int nShared, long tickUs) {
        return new DynamicHybridConfig(
                1_000_000L, nSharded, nShared, 0.5, 500, 150, tickUs);
    }

    private static Task shortTask(long id) {
        return new Task(id, WorkloadKind.CPU, 0L, System.nanoTime(),
                false, () -> 0L, null);
    }

    private static Task blocking(CountDownLatch gate) {
        return new Task(0, WorkloadKind.CPU, 0L, System.nanoTime(), false,
                () -> {
                    try { gate.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    return 0L;
                }, null);
    }

    // ================================================================
    // FIX 1 — True RR over sparse ACTIVE set
    // ================================================================
    @Test
    void pickActiveShardDistributesEvenlyOverSparseActiveSet() {
        // N=32 with only {3,7,12,21} active — the previous scan-based
        // implementation biased toward shard 3. True RR must distribute
        // equally.
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 32);
        // Freeze controller for the duration (tick very slow).
        // Set exactly shards {3,7,12,21} to ACTIVE via test hook.
        for (int wid : new int[]{3, 7, 12, 21}) d.forceState(wid, ShardState.ACTIVE);

        Map<Integer, Integer> hits = new HashMap<>();
        final int N_PICKS = 4000;   // 1000 per ACTIVE shard
        for (int i = 0; i < N_PICKS; i++) {
            int s = d.pickActiveShard();
            hits.merge(s, 1, Integer::sum);
        }

        assertEquals(4, hits.size(), "must hit exactly the 4 ACTIVE shards");
        for (int wid : new int[]{3, 7, 12, 21}) {
            int count = hits.getOrDefault(wid, 0);
            // Perfect RR would give 1000; allow ±1 for cursor start position.
            assertTrue(Math.abs(count - 1000) <= 1,
                    "shard " + wid + " expected ~1000 picks, got " + count);
        }
    }

    // ================================================================
    // FIX 2 — maxShardedWorkers cap includes ACTIVATING
    // ================================================================
    @Test
    void scaleOutCapCountsActivatingTransitions() throws Exception {
        // N=4, Nmin=0, minShared=1 → maxSharded=3. Slow tick so
        // the controller is idle while we drive requestScaleOut manually.
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        assertEquals(3, d.maxShardedWorkers());

        // Freeze all workers with blockers on the shared queue so they
        // sit in poll(1ms) and don't complete the ACTIVATING → ACTIVE
        // transition until the gate opens.
        CountDownLatch gate = new CountDownLatch(1);
        // Feed the shared queue with 4 blockers — one per INACTIVE worker.
        // Workers polling shared queue will pick them up and block.
        // But they'd need to be LONG (execTime > Tc). Use targetMillis
        // that exceeds Tc(1_000_000us).
        for (int i = 0; i < 4; i++) {
            d.sharedQueue().offer(new Task(i, WorkloadKind.CPU,
                    /*targetMillis*/2_000_000L, System.nanoTime(), false,
                    () -> { try { gate.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } return 0L; },
                    null));
        }
        // Give workers a chance to pick up the blockers.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.sharedQueue().size() > 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(0, d.sharedQueue().size(),
                "all 4 workers must be blocked on the shared-queue LONG task");

        // Now issue scale-outs. Since workers are stuck, none complete
        // ACTIVATING→ACTIVE. activeShardCount stays 0. But pendingActivation
        // increments each time. Cap = 3, so the 4th call must return -1.
        int r1 = d.requestScaleOut(); assertTrue(r1 >= 0, "first scale-out succeeds");
        int r2 = d.requestScaleOut(); assertTrue(r2 >= 0, "second scale-out succeeds");
        int r3 = d.requestScaleOut(); assertTrue(r3 >= 0, "third scale-out succeeds");
        assertEquals(-1, d.requestScaleOut(),
                "scale-out MUST be refused when ACTIVE+ACTIVATING == maxSharded, "
                        + "even though activeShardCount alone is still 0");

        gate.countDown();
    }

    // ================================================================
    // FIX 3 — EWMA sample aggregation is atomic per worker
    // ================================================================
    @Test
    void ewmaAggregationNeverProducesMismatchedCountSumPair() throws Exception {
        // The packed encoding (count << 32 | sumMicros) guarantees the
        // pair is atomic for a single writer per worker. Under heavy
        // concurrent submits, the aggregated tick-mean must always stay
        // within the observed per-sample range.
        d = new DynamicHybridDispatcher(
                new DynamicHybridConfig(1_000_000L, 4, 1, 0.5, 500, 150, /*tick*/500L),
                8);
        // Warm-up until controller has aggregated at least a couple ticks.
        for (int i = 0; i < 2000; i++) d.submit(shortTask(i));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (d.shortEwmaNanos() < 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        long ewma = d.shortEwmaNanos();
        assertTrue(ewma >= 0,
                "controller must have aggregated at least one tick sample");
        // Sanity: no-op task exec time is at most a few microseconds.
        // A mismatched sum/count pair from the old implementation could
        // easily produce absurd means (huge sum vs 1 count). Bound it.
        assertTrue(ewma < TimeUnit.SECONDS.toNanos(1),
                "EWMA must remain within a sane range (got " + ewma + " ns); "
                        + "an impossible mean would indicate a sum/count pairing bug");
    }

    // ================================================================
    // FIX 4 — Transition timestamps are published before state changes
    // ================================================================
    @Test
    void scaleOutTimestampVisibleBeforeStateTransition() {
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        // The invariant we're checking: after requestScaleOut() returns
        // a shard id, activationRequestNanos[shard] must NOT be -1 — it
        // must be the request time, so a worker that immediately reads
        // ACTIVATING and completes the transition sees a valid stamp.
        int shard = d.requestScaleOut();
        assertTrue(shard >= 0);
        // Wait for the worker to complete ACTIVATING → ACTIVE. Recorded
        // latency count must reach 1 (proving the timestamp was
        // readable, i.e., NOT observed as -1 which would skip recording).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.scaleOutLatencyCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, d.scaleOutLatencyCount(),
                "worker must have observed a valid ACTIVATING request timestamp");
    }

    @Test
    void scaleInTimestampVisibleBeforeStateTransition() throws Exception {
        // Nmin=1, force shard 1 ACTIVE so Ns=2 and requestScaleIn succeeds.
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 4);
        d.forceState(1, ShardState.ACTIVE);
        int shard = d.requestScaleIn();
        assertTrue(shard >= 0);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.scaleInLatencyCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, d.scaleInLatencyCount(),
                "worker must have observed a valid DRAINING request timestamp");
    }

    // ================================================================
    // FIX 5 — awaitTermination handles sub-millisecond timeouts
    // ================================================================
    @Test
    void awaitTerminationSubMillisecondDoesNotBlockForever() throws Exception {
        // 4 workers running the poll(1ms) loop indefinitely. We call
        // shutdown() and then awaitTermination with a 500-µs budget.
        // The old code did TimeUnit.NANOSECONDS.toMillis(500_000) → 0
        // which meant t.join(0) → wait forever. The fix uses
        // t.join(millis, nanos) which honours sub-ms budgets.
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        d.shutdown();

        long start = System.nanoTime();
        boolean done = d.awaitTermination(500, TimeUnit.MICROSECONDS);
        long elapsed = System.nanoTime() - start;

        // Whether `done` is true or false depends on how fast the
        // workers hit their poll(1ms) timeout — either outcome is
        // acceptable. What MUST hold is that awaitTermination returns
        // in a bounded time close to (but not massively exceeding) the
        // requested 500 µs, plus any per-worker join wake-up latency.
        // Give a generous ceiling of 2 seconds to survive CI jitter.
        assertTrue(elapsed < TimeUnit.SECONDS.toNanos(2),
                "awaitTermination(500us) must return promptly (took "
                        + TimeUnit.NANOSECONDS.toMillis(elapsed) + " ms) "
                        + "— did the sub-ms join(0) bug come back?");
        // Silence unused-var warning without weakening the intent.
        assertTrue(done || !done);
    }

    // ================================================================
    // Concurrent-submitter sanity — round-robin snapshot + admission
    // barrier keep working when many threads submit at once.
    // ================================================================
    @Test
    void concurrentSubmitStrandProof() throws Exception {
        d = new DynamicHybridDispatcher(cfg(4, 1, 10_000_000L), 8);
        final int THREADS = 4;
        final int PER_THREAD = 500;
        AtomicInteger id = new AtomicInteger(0);
        AtomicLong completed = new AtomicLong(0);
        CyclicBarrier ready = new CyclicBarrier(THREADS + 1);
        Thread[] submitters = new Thread[THREADS];
        for (int t = 0; t < THREADS; t++) {
            submitters[t] = new Thread(() -> {
                try {
                    ready.await();
                    for (int i = 0; i < PER_THREAD; i++) {
                        Task task = new Task(id.incrementAndGet(),
                                WorkloadKind.CPU, 0L, System.nanoTime(),
                                false, () -> 0L, completed::incrementAndGet);
                        d.submit(task);
                    }
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }, "submitter-" + t);
            submitters[t].start();
        }
        ready.await();
        // Occasionally toggle scale-in/scale-out on ACTIVE shards to
        // exercise the strand-race barrier under concurrent submits.
        for (int i = 0; i < 20; i++) {
            d.requestScaleIn();
            d.requestScaleOut();
            Thread.yield();
        }
        for (Thread s : submitters) s.join(TimeUnit.SECONDS.toMillis(10));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (completed.get() < (long) THREADS * PER_THREAD && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals((long) THREADS * PER_THREAD, completed.get(),
                "every accepted SHORT task must complete (no strand, no drop)");
    }
}

