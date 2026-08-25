package com.scott;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Focused algorithm tests for {@link DynamicHybridDispatcher}. */
class DynamicHybridDispatcherTest {

    private DynamicHybridDispatcher d;

    @AfterEach
    void tearDown() throws Exception {
        if (d != null) {
            d.shutdown();
            d.awaitTermination(5, TimeUnit.SECONDS);
            d = null;
        }
    }

    private static DynamicHybridConfig cfg(int nmin, long tcUs, long h, long l) {
        // 1s controller interval keeps the controller idle during tests so we
        // can drive scale-out/scale-in deterministically ourselves.
        return new DynamicHybridConfig(tcUs, nmin, 1, 0.5, h, l, 1_000_000L);
    }

    private static Task noopTask(long id, long targetMillis) {
        Workload w = () -> 0L;
        return new Task(id, WorkloadKind.CPU, targetMillis, System.nanoTime(),
                /*measurement*/false, w, /*onComplete*/null);
    }

    private static Task blockingTask(CountDownLatch release) {
        Workload w = () -> { try { release.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } return 0L; };
        return new Task(0, WorkloadKind.CPU, 0L, System.nanoTime(), false, w, null);
    }

    // ------------------------------------------------------------------
    // TEST 5 — Initial state: Nmin ACTIVE, rest INACTIVE, total == N.
    // ------------------------------------------------------------------
    @Test
    void initialStateHasNminActiveAndRestInactive() {
        d = new DynamicHybridDispatcher(cfg(2, 200, 500, 150), 8);
        assertEquals(8, d.workerCount());
        assertEquals(2, d.activeShardCount());
        int[] h = d.stateHistogram();
        assertEquals(2, h[ShardState.ACTIVE.ordinal()]);
        assertEquals(6, h[ShardState.INACTIVE.ordinal()]);
        assertEquals(0, h[ShardState.ACTIVATING.ordinal()]);
        assertEquals(0, h[ShardState.DRAINING.ordinal()]);
    }

    // ------------------------------------------------------------------
    // TEST 6 — Classification: exec <= Tc SHORT, exec > Tc LONG.
    // ------------------------------------------------------------------
    @Test
    void classifyShortAndLong() {
        assertTrue(DynamicHybridDispatcher.isShort(200, 200));   // boundary is SHORT
        assertTrue(DynamicHybridDispatcher.isShort(199, 200));
        assertFalse(DynamicHybridDispatcher.isShort(201, 200));
    }

    // ------------------------------------------------------------------
    // TEST 7 — Routing: LONG -> shared queue; SHORT -> ACTIVE shard only.
    // ------------------------------------------------------------------
    @Test
    void routingLongGoesToSharedShortGoesToActive() throws Exception {
        d = new DynamicHybridDispatcher(cfg(1, 500, 5_000_000, 1), /*N*/4);
        CountDownLatch gate = new CountDownLatch(1);
        // Freeze every worker so nothing drains during the assertions.
        //   shard 0 starts ACTIVE  -> block via its local queue
        //   shards 1..3 start INACTIVE -> block via the shared queue
        d.localQueue(0).offer(blockingTask(gate));
        d.sharedQueue().offer(blockingTask(gate));
        d.sharedQueue().offer(blockingTask(gate));
        d.sharedQueue().offer(blockingTask(gate));
        Thread.sleep(80); // let workers pick up their blockers

        // targetMillis=1 -> 1000us > Tc=500 -> LONG
        Task longTask  = noopTask(1, 1);
        // targetMillis=0 -> 0us    <= Tc=500 -> SHORT
        Task shortTask = noopTask(2, 0);
        d.submit(longTask);
        d.submit(shortTask);

        // Blockers are IN-FLIGHT on workers, so they no longer occupy the queues.
        assertEquals(1, d.sharedQueue().size(), "LONG must land in shared queue");
        assertEquals(1, d.localQueue(0).size(), "SHORT must land in the only ACTIVE shard's queue");
        assertEquals(0, d.localQueue(1).size());
        assertEquals(0, d.localQueue(2).size());
        assertEquals(0, d.localQueue(3).size());
        gate.countDown();
    }

    // ------------------------------------------------------------------
    // TEST 8 — Round-robin across ACTIVE shards.
    // TEST 9 — Round-robin skips DRAINING shards.
    // ------------------------------------------------------------------
    @Test
    void roundRobinOverActiveShards() {
        // N=5 (was 4) so nmin(4) + minSharedWorkers(1) <= N.
        d = new DynamicHybridDispatcher(cfg(4, 500, 5_000_000, 1), 5);
        // Force shard 4 ACTIVE too (bypasses maxSharded cap via test hook)
        // so RR distributes evenly across all 5 shards.
        d.forceState(4, ShardState.ACTIVE);
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < 5; i++) d.localQueue(i).offer(blockingTask(gate));
        try { Thread.sleep(80); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        for (int i = 0; i < 10; i++) {
            try { d.submit(noopTask(100 + i, 0)); } catch (InterruptedException e) { fail(e); }
        }
        // Each blocker is now IN-FLIGHT (not counted in queue size).
        // 10 short tasks distributed round-robin across 5 ACTIVE shards -> 2 each.
        for (int i = 0; i < 5; i++) {
            assertEquals(2, d.localQueue(i).size(),
                    "shard " + i + " must have received exactly 2 short tasks");
        }
        gate.countDown();
    }

    @Test
    void roundRobinSkipsDrainingShards() {
        // N=5 (was 4) so nmin(4) + minSharedWorkers(1) <= N.
        d = new DynamicHybridDispatcher(cfg(4, 500, 5_000_000, 1), 5);
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) d.localQueue(i).offer(blockingTask(gate));
        try { Thread.sleep(80); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }

        d.forceState(1, ShardState.DRAINING);

        Set<Integer> hit = new HashSet<>();
        for (int i = 0; i < 12; i++) {
            int picked = d.pickActiveShard();
            hit.add(picked);
        }
        assertFalse(hit.contains(1), "DRAINING shard must not appear in RR");
        assertFalse(hit.contains(-1));
        // All three remaining ACTIVE shards must be hit.
        assertTrue(hit.contains(0) && hit.contains(2) && hit.contains(3));
        gate.countDown();
    }

    // ------------------------------------------------------------------
    // TEST 10 — EWMA.
    // ------------------------------------------------------------------
    @Test
    void ewmaFirstSampleAndUpdate() {
        d = new DynamicHybridDispatcher(cfg(1, 200, 500, 150), 4);
        // alpha=0.5 (from cfg(...)).
        d.updateShortEwma(100);
        assertEquals(100, d.shortEwmaNanos(), "first sample seeds EWMA");
        d.updateShortEwma(200);
        assertEquals(150, d.shortEwmaNanos(), "0.5*200 + 0.5*100 = 150");
    }

    // ------------------------------------------------------------------
    // TEST 11 — Pressure Ps = Qs * Ts / Ns.
    // ------------------------------------------------------------------
    @Test
    void pressureFormula() throws Exception {
        d = new DynamicHybridDispatcher(cfg(8, 500, 1_000_000, 1), 9);
        // Freeze workers: submit one blocker per ACTIVE shard through
        // the public API (RR distributes evenly across the 8 shards).
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < 8; i++) d.submit(blockingTask(gate));
        Thread.sleep(80);
        // Now 40 more shorts land 5-per-shard via RR.
        for (int k = 0; k < 40; k++) d.submit(noopTask(k, 0));
        // Seed EWMA to exactly 100 microseconds = 100_000 ns.
        d.updateShortEwma(100_000L);
        assertEquals(100_000L, d.shortEwmaNanos());
        // Ns=8, Qs=40, Ts=100us -> Ps = 40*100us/8 = 500us = 500_000 ns.
        assertEquals(500_000L, d.pressureNanos());
        gate.countDown();
    }

    // ------------------------------------------------------------------
    // TEST 12 + 13 — Scale-out: INACTIVE -> ACTIVATING, then -> ACTIVE at boundary.
    // ------------------------------------------------------------------
    @Test
    void scaleOutPromotesOneInactiveToActivating() throws Exception {
        d = new DynamicHybridDispatcher(cfg(2, 200, 500, 150), 8);
        int before = d.activeShardCount();
        int shard = d.requestScaleOut();
        assertTrue(shard >= 0);
        assertEquals(ShardState.ACTIVATING, d.state(shard));
        // ACTIVATING must NOT immediately count toward Ns.
        assertEquals(before, d.activeShardCount(), "Ns must not increase until ACTIVATING -> ACTIVE");
        assertEquals(1, d.scaleOutCount());
        // Worker loop must complete the ACTIVATING -> ACTIVE transition
        // at its next safe task boundary. With an empty local queue the
        // loop iterates on the shared queue's 1 ms poll, so wait briefly.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline && d.state(shard) != ShardState.ACTIVE) {
            Thread.sleep(5);
        }
        assertEquals(ShardState.ACTIVE, d.state(shard),
                "worker must promote ACTIVATING -> ACTIVE at safe boundary");
        assertEquals(before + 1, d.activeShardCount());
    }

    // ------------------------------------------------------------------
    // TEST 14 + 15 — Scale-in: ACTIVE -> DRAINING immediately excluded from RR;
    //                DRAINING -> INACTIVE only after local queue empties.
    // ------------------------------------------------------------------
    @Test
    void scaleInDrainingRemovedFromRoutingImmediately() throws Exception {
        d = new DynamicHybridDispatcher(cfg(2, 200, 500, 150), 4);
        // Make all shards ACTIVE first.
        for (int i = 0; i < 4; i++) d.forceState(i, ShardState.ACTIVE);
        // Freeze workers with a blocker so DRAINING transition is observable.
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) d.localQueue(i).offer(blockingTask(gate));
        Thread.sleep(80);

        int nsBefore = d.activeShardCount();
        int shard = d.requestScaleIn();
        assertTrue(shard >= 0);
        assertEquals(ShardState.DRAINING, d.state(shard));
        assertEquals(nsBefore - 1, d.activeShardCount(), "Ns must decrement on scale-in");

        // pickActiveShard must never return the DRAINING shard.
        for (int i = 0; i < 20; i++) {
            assertNotEquals(shard, d.pickActiveShard());
        }
        gate.countDown();

        // Drain completion: after the queue empties, worker returns to INACTIVE.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline && d.state(shard) != ShardState.INACTIVE) {
            Thread.sleep(10);
        }
        assertEquals(ShardState.INACTIVE, d.state(shard),
                "DRAINING -> INACTIVE once local queue is empty");
    }

    // ------------------------------------------------------------------
    // TEST 16 — Ns == Nmin blocks scale-in even with low pressure & pending shared.
    // TEST 17 — Empty shared queue blocks scale-in even with low pressure.
    // TEST 18 — Hysteresis L <= Ps <= H: no change.
    // ------------------------------------------------------------------
    @Test
    void policyRespectsNmin() {
        // N=5 so nmin(4)+minShared(1)<=N. Ns==Nmin==4 blocks scale-in.
        d = new DynamicHybridDispatcher(cfg(/*Nmin*/4, 200, 500, 150), 5);
        // Ns == Nmin == 4. Force a pending shared task and low Ps.
        d.sharedQueue().offer(noopTask(1, 1));
        d.updateShortEwma(1L); // Ts tiny -> Ps tiny (< L)
        assertEquals(DynamicHybridDispatcher.Decision.NONE, d.evaluateScalingDecision());
    }

    @Test
    void policyRequiresPendingSharedForScaleIn() {
        d = new DynamicHybridDispatcher(cfg(/*Nmin*/1, 200, 500, 150), 4);
        d.updateShortEwma(1L); // low pressure
        // Shared queue empty -> no scale-in.
        assertEquals(DynamicHybridDispatcher.Decision.NONE, d.evaluateScalingDecision());
    }

    @Test
    void policyHysteresisWindowProducesNoChange() throws Exception {
        d = new DynamicHybridDispatcher(cfg(1, 200, 500, 150), 4);
        // Force exactly one shard ACTIVE (shard 0). Others INACTIVE.
        for (int i = 1; i < 4; i++) d.forceState(i, ShardState.INACTIVE);
        // Freeze the sole ACTIVE worker via submit(). RR has only shard 0
        // to pick, so the blocker lands there.
        CountDownLatch gate = new CountDownLatch(1);
        d.submit(blockingTask(gate));
        Thread.sleep(60);
        // 3 shorts via submit() → all go to shard 0, Qs=3.
        for (int k = 0; k < 3; k++) d.submit(noopTask(k, 0));
        d.updateShortEwma(100_000L); // 100us
        // Ps = 3*100us/1 = 300us  -> in (L=150us, H=500us).
        assertEquals(300_000L, d.pressureNanos());
        assertEquals(DynamicHybridDispatcher.Decision.NONE, d.evaluateScalingDecision());
        gate.countDown();
    }

    // ------------------------------------------------------------------
    // TEST 19 — All workers ACTIVE + Ps > H => no new worker created.
    // ------------------------------------------------------------------
    @Test
    void scaleOutCannotExceedN() {
        // N=5 (was 4) so nmin(4)+minSharedWorkers(1) <= N.
        // forceState is a test hook that bypasses the maxShardedWorkers
        // cap in requestScaleOut; we use it to drive all shards ACTIVE
        // and then observe requestScaleOut() returning -1 (no INACTIVE
        // shard exists).
        d = new DynamicHybridDispatcher(cfg(4, 200, 500, 150), 5);
        for (int i = 0; i < 5; i++) d.forceState(i, ShardState.ACTIVE);
        assertEquals(-1, d.requestScaleOut());
        assertEquals(5, d.workerCount());
    }

    // ------------------------------------------------------------------
    // TEST 20 — Repeated scale-out / scale-in cycles keep the thread count constant.
    // ------------------------------------------------------------------
    @Test
    void repeatedScalingKeepsThreadCountConstant() throws Exception {
        d = new DynamicHybridDispatcher(cfg(2, 200, 500, 150), 8);
        int expected = 8;
        Set<Long> allThreadIds = new HashSet<>();
        // Repeatedly cycle scale-out then scale-in and record every shard's
        // current thread id from within the dispatcher. Thread count is
        // exposed indirectly through the dispatcher's private workerThreads;
        // we rely on the total worker count invariant.
        for (int cycle = 0; cycle < 10; cycle++) {
            int outShard = d.requestScaleOut();
            if (outShard >= 0) {
                // Wait for the ACTIVATING boundary.
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(500);
                while (System.nanoTime() < deadline && d.state(outShard) != ShardState.ACTIVE) {
                    Thread.sleep(2);
                }
            }
            // Deposit a shared task so scale-in is legal.
            d.sharedQueue().offer(noopTask(9000 + cycle, 5));
            d.updateShortEwma(1L); // low pressure -> scale-in eligible
            d.requestScaleIn();
            // Total workers unchanged: the dispatcher exposes workerCount().
            assertEquals(expected, d.workerCount());
            int[] h = d.stateHistogram();
            int sum = h[0] + h[1] + h[2] + h[3];
            assertEquals(expected, sum, "state histogram must sum to N");
        }
        // Also verify Thread.getAllStackTraces has exactly N daemon/worker
        // threads whose name starts with DynHybridWorker-.
        AtomicInteger workerThreadCount = new AtomicInteger();
        Thread.getAllStackTraces().keySet().forEach(t -> {
            if (t.getName().startsWith("DynHybridWorker-")) {
                workerThreadCount.incrementAndGet();
                allThreadIds.add(t.threadId());
            }
        });
        assertEquals(expected, workerThreadCount.get(),
                "exactly N DynHybridWorker threads must exist");
    }
}

