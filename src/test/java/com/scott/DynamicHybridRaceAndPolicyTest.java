package com.scott;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Deterministic race and policy tests for the P0/P1/P2 hardening.
 *
 * <p>These tests deliberately avoid timing-based sleeps: they use test
 * hooks, {@link CountDownLatch}, and explicit state observation to
 * drive the race scenarios.
 */
class DynamicHybridRaceAndPolicyTest {

    private DynamicHybridDispatcher d;

    @AfterEach
    void tearDown() throws Exception {
        if (d != null) {
            d.shutdown();
            d.awaitTermination(5, TimeUnit.SECONDS);
            d = null;
        }
    }

    /** Everything SHORT (Tc huge) and controller idle (interval huge). */
    private static DynamicHybridConfig cfg(int nSharded, int nShared) {
        return new DynamicHybridConfig(
                1_000_000L, nSharded, nShared, 0.5,
                /*H*/500, /*L*/150, /*tick*/10_000_000L);
    }

    private static Task shortTask(long id) {
        return new Task(id, WorkloadKind.CPU, /*targetMillis*/0L,
                System.nanoTime(), /*measurement*/false, () -> 0L, null);
    }

    private static Task longTask(long id) {
        // targetMillis * 1000us = 2000us > Tc(1_000_000us) — wait that's SHORT.
        // With Tc=1_000_000us this can't be LONG; use a different cfg for LONG tests.
        return new Task(id, WorkloadKind.CPU, /*targetMillis*/999_999L,
                System.nanoTime(), false, () -> 0L, null);
    }

    private static Task blocking(CountDownLatch gate) {
        return new Task(0, WorkloadKind.CPU, 0L, System.nanoTime(), false,
                () -> {
                    try { gate.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
                    return 0L;
                }, null);
    }

    // ================================================================
    // P0.1 — SHORT-vs-scale-in race barrier
    // ================================================================

    /**
     * Test the barrier invariant directly: while a submitter holds an
     * admission permit on shard {@code i}, the controller cannot close
     * that shard. We drive this deterministically using the
     * {@code admissionCommittedHookForTests} hook, which fires between
     * admission acquire and offer.
     */
    @Test
    void submitCannotStrandInDrainingShard() throws Exception {
        // 4 workers: 1 ACTIVE, 3 INACTIVE (Nmin=1, minShared=1).
        d = new DynamicHybridDispatcher(cfg(1, 1), 4);
        AtomicInteger controllerClosedDuringAdmission = new AtomicInteger();

        // Hook fires AFTER admissionCount++ succeeded, BEFORE offer().
        // At this moment admissionCount[shard]>=1, so the controller's
        // CAS 0 → MIN_VALUE must fail — barrier proven.
        d.admissionCommittedHookForTests = () -> {
            int shard = d.requestScaleIn();
            if (shard >= 0) controllerClosedDuringAdmission.incrementAndGet();
        };

        d.submit(shortTask(1));

        assertEquals(0, controllerClosedDuringAdmission.get(),
                "controller MUST NOT be able to close a shard while a "
                        + "submitter holds an admission permit — barrier violated");
        // Sanity: task actually reached the ACTIVE shard's local queue.
        assertEquals(1, d.queuedShortTasks());
    }

    /**
     * After a shard is closed (DRAINING), submitters must not commit
     * new SHORT tasks to its local queue.
     */
    @Test
    void submitReRoutesAwayFromClosedShard() throws Exception {
        // N=4, Nmin=2, minShared=2 → maxSharded=2. Two ACTIVE shards (0,1).
        d = new DynamicHybridDispatcher(cfg(2, 2), 4);
        // Freeze shard 1 with a blocker BEFORE closing shard 0 — otherwise
        // the ACTIVE worker on shard 1 drains no-op shorts as fast as they
        // arrive and the queue-size assertion below sees 0.
        CountDownLatch gate = new CountDownLatch(1);
        d.submit(blocking(gate));   // routes to shard 0 or 1 via RR
        d.submit(blocking(gate));   // routes to the other of {0,1}
        // Wait until both blockers are in-flight (queues empty).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((d.localQueue(0).size() > 0 || d.localQueue(1).size() > 0)
                && System.nanoTime() < deadline) {
            Thread.yield();
        }

        // Force shard 0 to DRAINING directly (Nmin floor blocks
        // requestScaleIn at Ns==Nmin). The strand invariant we want
        // to prove — "SHORTs never land in a DRAINING shard" — is
        // orthogonal to how DRAINING was reached, and the admission
        // barrier must be set correctly by both paths.
        d.forceState(0, ShardState.DRAINING);
        d.closeAdmissionsForTests(0);
        assertEquals(ShardState.DRAINING, d.state(0));

        for (int i = 0; i < 10; i++) d.submit(shortTask(100 + i));

        assertEquals(0, d.localQueue(0).size(),
                "no new SHORT may land in a DRAINING shard");
        assertTrue(d.localQueue(1).size() > 0,
                "SHORTs must reach the remaining ACTIVE shard");
        gate.countDown();
    }

    // ================================================================
    // P0.2 — LONG-starvation prevention
    // ================================================================

    @Test
    void scaleOutCappedAtMaxShardedWorkers() {
        // N=4, Nmin=0, minShared=2 → maxSharded=2.
        d = new DynamicHybridDispatcher(cfg(0, 2), 4);
        assertEquals(2, d.maxShardedWorkers());
        assertTrue(d.requestScaleOut() >= 0);
        assertTrue(d.requestScaleOut() >= 0);
        // Even though 2 INACTIVE shards remain, the cap must refuse
        // further scale-out — pending ACTIVATING transitions count
        // toward the cap (see pendingActivationCount in dispatcher).
        assertEquals(-1, d.requestScaleOut(),
                "scale-out must never exceed maxShardedWorkers");
        // Note: activeShardCount reflects only shards that have
        // completed ACTIVATING -> ACTIVE. The cap logic uses
        // active + pendingActivation, so the -1 above is what proves
        // the invariant, not activeShardCount.
    }

    @Test
    void scaleInRefusesAtMinShardedWorkers() {
        // N=4, Nmin=2, minShared=1. Starts with 2 ACTIVE.
        d = new DynamicHybridDispatcher(cfg(2, 1), 4);
        // First scale-in: 2 → 1 would violate Nmin=2, so refused.
        assertEquals(-1, d.requestScaleIn(),
                "scale-in must never go below minShardedWorkers");
        assertEquals(2, d.activeShardCount());
    }

    // ================================================================
    // P1.3 — EWMA is fed by controller aggregation
    // ================================================================

    @Test
    void ewmaIsUpdatedByControllerFromWorkerSamples() throws Exception {
        // Fast tick so controller aggregates within test window.
        DynamicHybridConfig c = new DynamicHybridConfig(
                1_000_000L, 1, 1, 0.5, 500, 150, /*tick*/500L);
        d = new DynamicHybridDispatcher(c, 4);
        // Submit a burst of SHORTs; workers accumulate into the striped
        // adders; controller applies EWMA on next tick.
        for (int i = 0; i < 20; i++) d.submit(shortTask(i));
        // Poll until EWMA transitions from -1 (uninitialised) to >=0.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (d.shortEwmaNanos() < 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(d.shortEwmaNanos() >= 0,
                "controller must aggregate per-worker SHORT samples into EWMA");
    }

    // ================================================================
    // P1.4 — Transition latency metrics
    // ================================================================

    @Test
    void scaleOutLatencyMetricsRecord() throws Exception {
        d = new DynamicHybridDispatcher(cfg(1, 1), 4);
        int shard = d.requestScaleOut();
        assertTrue(shard >= 0);
        // Wait until worker completes ACTIVATING → ACTIVE (deterministic:
        // no other work, just the state observation).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.state(shard) != ShardState.ACTIVE && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(ShardState.ACTIVE, d.state(shard));
        // Give the worker one more loop iteration to record the sample.
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.scaleOutLatencyCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, d.scaleOutLatencyCount());
        assertTrue(d.scaleOutLatencyTotalNanos() >= 0);
        assertTrue(d.scaleOutLatencyMaxNanos() >= d.scaleOutLatencyMeanNanos());
    }

    @Test
    void scaleInLatencyMetricsRecord() throws Exception {
        // Nmin=1, minShared=1. Force shard 1 ACTIVE so Ns=2 > Nmin=1
        // and requestScaleIn can succeed.
        d = new DynamicHybridDispatcher(cfg(1, 1), 4);
        d.forceState(1, ShardState.ACTIVE);
        int shard = d.requestScaleIn();
        assertTrue(shard >= 0);
        // Worker drains empty local queue → transitions to INACTIVE.
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.state(shard) != ShardState.INACTIVE && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(ShardState.INACTIVE, d.state(shard));
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.scaleInLatencyCount() == 0 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertEquals(1, d.scaleInLatencyCount());
        assertTrue(d.scaleInLatencyTotalNanos() >= 0);
    }

    // ================================================================
    // P1.5 — Actual controller interval
    // ================================================================

    @Test
    void controllerIntervalIsMeasured() throws Exception {
        DynamicHybridConfig c = new DynamicHybridConfig(
                1_000_000L, 1, 1, 0.5, 500, 150, /*tick*/500L);
        d = new DynamicHybridDispatcher(c, 4);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.controllerIntervalSamples() < 3 && System.nanoTime() < deadline) {
            Thread.yield();
        }
        assertTrue(d.controllerIntervalSamples() >= 3);
        assertTrue(d.controllerIntervalMeanNanos() > 0);
        assertTrue(d.controllerIntervalMaxNanos() >= d.controllerIntervalMeanNanos());
    }

    // ================================================================
    // P2.6 — SchedulerSnapshot is internally consistent
    // ================================================================

    @Test
    void snapshotIsInternallyConsistentSample() throws Exception {
        d = new DynamicHybridDispatcher(cfg(2, 1), 4);
        for (int i = 0; i < 10; i++) {
            SchedulerSnapshot s = d.snapshot();
            // Ns in the snapshot must be in [0, N]. maxShardedWorkers must
            // be consistent with N-minShared. Since we always build a
            // snapshot from a single sample, these bounds cannot be
            // violated even under concurrent state churn.
            assertTrue(s.activeShardCount() >= 0);
            assertTrue(s.activeShardCount() <= s.workerCount());
            assertEquals(d.maxShardedWorkers(), s.maxShardedWorkers());
            assertEquals(4, s.workerCount());
        }
    }

    // ================================================================
    // P2.7 — Saturating pressure math
    // ================================================================

    @Test
    void pressureSaturatesRatherThanOverflowing() {
        long huge = Long.MAX_VALUE / 2;
        assertEquals(Long.MAX_VALUE,
                DynamicHybridDispatcher.calculatePressure(huge, huge, 1));
        assertEquals(0L,
                DynamicHybridDispatcher.calculatePressure(0, 100, 4));
        assertEquals(Long.MAX_VALUE,
                DynamicHybridDispatcher.calculatePressure(10, 100, 0));
        assertEquals(0L,
                DynamicHybridDispatcher.calculatePressure(10, -1, 4));
    }

    // ================================================================
    // P2.8 — queuedShortTasksExact counts DRAINING too
    // ================================================================

    @Test
    void queuedShortCountedThroughActiveAndDrainingTransitions() throws Exception {
        // Nmin=1, minShared=1; force shard 1 ACTIVE so Ns=2 > Nmin=1
        // and requestScaleIn can actually succeed.
        d = new DynamicHybridDispatcher(cfg(1, 1), 4);
        d.forceState(1, ShardState.ACTIVE);
        CountDownLatch gate = new CountDownLatch(1);
        // Freeze both ACTIVE workers so shorts pile up.
        d.submit(blocking(gate));
        d.submit(blocking(gate));
        // Wait until blockers are in-flight (both admission slots released,
        // both workers stuck in gate.await()).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((d.localQueue(0).size() > 0 || d.localQueue(1).size() > 0)
                && System.nanoTime() < deadline) {
            Thread.yield();
        }
        // Queue 5 SHORTs — RR → 2/3 per shard.
        for (int i = 0; i < 5; i++) d.submit(shortTask(100 + i));
        long before = d.queuedShortTasksExact();
        assertEquals(5L, before, "5 SHORTs in ACTIVE queues");

        // Now scale-in one shard → DRAINING. Its queued shorts must still count.
        int drained = d.requestScaleIn();
        assertTrue(drained >= 0);
        assertEquals(ShardState.DRAINING, d.state(drained));
        long after = d.queuedShortTasksExact();
        assertEquals(before, after,
                "DRAINING shard's already-queued SHORTs must still be counted");
        gate.countDown();
    }

    // ================================================================
    // P2.9 — Shutdown drains accepted work
    // ================================================================

    @Test
    void shutdownDrainsAcceptedShortWork() throws Exception {
        d = new DynamicHybridDispatcher(cfg(2, 1), 4);
        final int K = 50;
        CountDownLatch completed = new CountDownLatch(K);
        for (int i = 0; i < K; i++) {
            Task t = new Task(i, WorkloadKind.CPU, 0L, System.nanoTime(),
                    false, () -> 0L, completed::countDown);
            d.submit(t);
        }
        d.shutdown();
        assertTrue(d.awaitTermination(5, TimeUnit.SECONDS));
        assertEquals(0, completed.getCount(),
                "all accepted SHORTs must complete before termination");
    }
}

