package com.scott;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for the final correctness pass on {@link DynamicHybridDispatcher}:
 * <ol>
 *   <li>{@code requestScaleOut()} timestamp cannot corrupt an existing
 *       ACTIVATING request.</li>
 *   <li>{@code reservedShardedCapacity} accounting stays consistent
 *       across all transitions.</li>
 *   <li>Packed EWMA accumulator cannot corrupt count via sum overflow.</li>
 *   <li>Stale {@code activeShardIds} snapshot is rejected by the
 *       admission barrier.</li>
 *   <li>{@code ACTIVE -> DRAINING} releases reserved capacity exactly
 *       once.</li>
 *   <li>Failed scale-out rolls back both capacity and timestamp.</li>
 * </ol>
 */
class DynamicHybridFinalPassTest {

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

    // ================================================================
    // FIX 1 — ACTIVATING shard's existing timestamp cannot be overwritten
    // ================================================================
    @Test
    void existingActivatingTimestampCannotBeOverwritten() {
        // 4 workers, Nmin=0, minShared=1 → maxSharded=3. Slow tick so
        // controller is idle while we drive scale-out manually.
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        // Directly install an ACTIVATING shard with a specific timestamp.
        // (Bypass the normal requestScaleOut path — this is the very
        // situation that used to corrupt the stamp: a second scale-out
        // attempt clobbering a pre-existing pending request.)
        d.forceState(0, ShardState.ACTIVATING);
        long tsBefore = 42_000_000_000L;   // arbitrary sentinel
        d.activationRequestNanosSetForTests(0, tsBefore);

        // Now call requestScaleOut. It must NOT touch shard 0 (already
        // ACTIVATING) but should be able to pick shard 1/2/3.
        int chosen = d.requestScaleOut();
        assertTrue(chosen >= 0, "scale-out on an INACTIVE shard should succeed");
        assertNotEquals(0, chosen, "requestScaleOut must skip an ACTIVATING shard");

        // Shard 0's timestamp MUST still be the sentinel.
        assertEquals(tsBefore, d.activationRequestNanosForTests(0),
                "requestScaleOut must not overwrite an existing ACTIVATING shard's timestamp");
    }

    // ================================================================
    // FIX 2 — Repeated scale-out cannot exceed maxShardedWorkers
    //         (regression guard for reservedShardedCapacity accounting).
    // ================================================================
    @Test
    void repeatedScaleOutObeysReservedCapacityCap() {
        // maxSharded = 4 - 1 = 3.
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        assertEquals(3, d.maxShardedWorkers());
        assertEquals(0, d.reservedShardedCapacity());

        assertTrue(d.requestScaleOut() >= 0);
        assertEquals(1, d.reservedShardedCapacity(),
                "reserved capacity must increment on every successful scale-out");
        assertTrue(d.requestScaleOut() >= 0);
        assertEquals(2, d.reservedShardedCapacity());
        assertTrue(d.requestScaleOut() >= 0);
        assertEquals(3, d.reservedShardedCapacity());
        assertEquals(-1, d.requestScaleOut(),
                "scale-out must fail once reservedShardedCapacity == maxShardedWorkers");
        assertEquals(3, d.reservedShardedCapacity(),
                "a failed scale-out must not leak reservation");
    }

    // ================================================================
    // FIX 2 — Delayed activation: capacity is reserved BEFORE transition
    // ================================================================
    @Test
    void reservedCapacityHeldWhileWorkerHasNotYetActivated() throws Exception {
        // Freeze all workers on shared-queue LONG blockers so no
        // ACTIVATING → ACTIVE transition can complete during the test.
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        CountDownLatch gate = new CountDownLatch(1);
        for (int i = 0; i < 4; i++) {
            d.sharedQueue().offer(new Task(i, WorkloadKind.CPU,
                    /*targetMillis*/2_000_000L, System.nanoTime(), false,
                    () -> { try { gate.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } return 0L; },
                    null));
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.sharedQueue().size() > 0 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(0, d.sharedQueue().size());

        // Fire two scale-outs. activeShardCount stays 0 (workers can't
        // complete the transition). reservedShardedCapacity must reach 2.
        assertTrue(d.requestScaleOut() >= 0);
        assertTrue(d.requestScaleOut() >= 0);
        assertEquals(0, d.activeShardCount(), "no worker has reached ACTIVE yet");
        assertEquals(2, d.reservedShardedCapacity(),
                "ACTIVE + ACTIVATING accounting must be 2 with delayed activation");
        gate.countDown();
    }

    // ================================================================
    // FIX 1 — reservedShardedCapacity is released ONLY at
    //         DRAINING -> INACTIVE, not at ACTIVE -> DRAINING.
    // ================================================================
    @Test
    void reservedCapacityReleasedOnlyAtDrainingToInactive() throws Exception {
        // Nmin=2, minShared=1. Initial: ACTIVE={0,1}, reserved=2.
        d = new DynamicHybridDispatcher(cfg(2, 1, 10_000_000L), 4);
        assertEquals(2, d.reservedShardedCapacity());
        assertEquals(2, d.activeShardCount());

        // Force a third ACTIVE to satisfy Nmin floor for scale-in.
        d.forceState(2, ShardState.ACTIVE);   // adjusts both counters
        assertEquals(3, d.activeShardCount());
        assertEquals(3, d.reservedShardedCapacity());

        int shard = d.requestScaleIn();
        assertTrue(shard >= 0);
        assertEquals(2, d.activeShardCount(),
                "ACTIVE -> DRAINING must decrement activeShardCount (Ns) by 1");
        assertEquals(3, d.reservedShardedCapacity(),
                "ACTIVE -> DRAINING must NOT release reserved capacity — "
                        + "a DRAINING worker still consumes local SHORTs, not sharedQueue");

        // Wait for worker to complete DRAINING -> INACTIVE (nothing
        // queued locally, so this is immediate on the next loop tick).
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.state(shard) != ShardState.INACTIVE && System.nanoTime() < deadline) Thread.yield();
        assertEquals(ShardState.INACTIVE, d.state(shard));

        // Now — and only now — reservation drops.
        deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (d.reservedShardedCapacity() != 2 && System.nanoTime() < deadline) Thread.yield();
        assertEquals(2, d.reservedShardedCapacity(),
                "DRAINING -> INACTIVE releases exactly one unit of reserved capacity");
        assertEquals(2, d.activeShardCount(),
                "activeShardCount stays unchanged at DRAINING -> INACTIVE");
    }

    // ================================================================
    // FIX 1 — LONG starvation invariant while DRAINING is in flight.
    //
    // N=32, maxShardedWorkers=28, 28 ACTIVE, 4 INACTIVE. Scale one
    // ACTIVE to DRAINING. While that worker remains DRAINING, another
    // scale-out must NOT reduce the number of actual shared-queue
    // workers below 4 (= minSharedWorkers).
    // ================================================================
    @Test
    void drainingWorkerCountsAgainstMaxShardedWorkers() {
        // maxSharded = 32 - 4 = 28. Nmin=1 so we can scale-in freely.
        d = new DynamicHybridDispatcher(cfg(1, 4, 10_000_000L), 32);
        assertEquals(28, d.maxShardedWorkers());
        // Force exactly 28 ACTIVE (bypasses the cap via test hook — the
        // point of the test is what happens NEXT).
        for (int i = 0; i < 28; i++) d.forceState(i, ShardState.ACTIVE);
        assertEquals(28, d.activeShardCount());
        assertEquals(28, d.reservedShardedCapacity());

        // Scale one to DRAINING. Reservation MUST stay at 28.
        int drained = d.requestScaleIn();
        assertTrue(drained >= 0);
        assertEquals(ShardState.DRAINING, d.state(drained));
        assertEquals(27, d.activeShardCount(),
                "Ns drops by 1 at ACTIVE -> DRAINING");
        assertEquals(28, d.reservedShardedCapacity(),
                "reserved capacity must stay at 28 while worker is DRAINING");

        // While DRAINING is in flight, a new scale-out must be refused,
        // otherwise sharedQueue would be left with only 3 workers.
        assertEquals(-1, d.requestScaleOut(),
                "scale-out during in-flight DRAINING must be refused so "
                        + "sharedQueue keeps at least minSharedWorkers=4 workers");
        assertEquals(28, d.reservedShardedCapacity(),
                "refused scale-out must not leak reservation");
        // Global invariant: workerCount - reserved >= minSharedWorkers.
        assertTrue(32 - d.reservedShardedCapacity() >= 4,
                "at least minSharedWorkers=4 workers must remain LONG-eligible");
    }

    // ================================================================
    // FIX 2 — Packed EWMA: a sample is accepted as an atomic pair
    //         (count+1, sum+exec) or dropped entirely.
    // ================================================================
    @Test
    void oversizedSampleIsDroppedEntirely() {
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 4);
        // Feed one legitimate sample: count=1, sum=1000ns.
        d.addShortSampleForTests(0, 1_000L);
        long slot = d.perWorkerShortSampleSlot(0);
        assertEquals(1L, slot >>> 48);
        assertEquals(1_000L, slot & ((1L << 48) - 1L));

        // Feed an exec time larger than the 48-bit sum field could
        // ever hold. Old code: bump count and saturate sum → PAIR
        // INCONSISTENT. New code: drop the entire sample; slot
        // unchanged so count and sum still describe {1 sample of 1000ns}.
        d.addShortSampleForTests(0, Long.MAX_VALUE);
        long slot2 = d.perWorkerShortSampleSlot(0);
        assertEquals(slot, slot2,
                "oversized sample must be dropped without touching the slot");

        // A subsequent legitimate sample is still accepted.
        d.addShortSampleForTests(0, 500L);
        long slot3 = d.perWorkerShortSampleSlot(0);
        assertEquals(2L, slot3 >>> 48);
        assertEquals(1_500L, slot3 & ((1L << 48) - 1L));
    }

    @Test
    void sampleThatWouldOverflowSumIsDroppedEvenWhenCountHasRoom() {
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 4);
        // Pre-load the slot: count=1, sum=(SUM_MASK - 100).
        long preloaded = (1L << 48) | (((1L << 48) - 1L) - 100L);
        d.perWorkerShortSampleSetForTests(0, preloaded);
        // A 200-ns sample would carry sum past SUM_MASK. Even though
        // count has plenty of headroom, the entire sample must be dropped.
        d.addShortSampleForTests(0, 200L);
        long slot = d.perWorkerShortSampleSlot(0);
        assertEquals(preloaded, slot,
                "sample that would overflow sum must be dropped even when count has room");

        // A 100-ns sample exactly fits: it must be accepted.
        d.addShortSampleForTests(0, 100L);
        slot = d.perWorkerShortSampleSlot(0);
        assertEquals(2L, slot >>> 48);
        assertEquals((1L << 48) - 1L, slot & ((1L << 48) - 1L),
                "sample that exactly fits must be accepted");
    }

    @Test
    void sampleThatWouldOverflowCountIsDroppedEvenWhenSumHasRoom() {
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 4);
        // Pre-load: count=MAX, sum=0.
        long preloaded = 0xFFFFL << 48;
        d.perWorkerShortSampleSetForTests(0, preloaded);
        // Even a tiny sample must be dropped because count is at its cap.
        d.addShortSampleForTests(0, 1L);
        long slot = d.perWorkerShortSampleSlot(0);
        assertEquals(preloaded, slot,
                "sample that would overflow count must be dropped even when sum has room");
    }

    @Test
    void acceptedSamplesProduceConsistentCountAndSum() {
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 4);
        // Feed a mix of legal + illegal samples. The final slot must
        // describe exactly the ACCEPTED subset — invariant that count
        // and sum always match a coherent sample set.
        long[] samples = {100, 200, Long.MAX_VALUE /*drop*/,
                          -1 /*drop*/, 300, 400, 500};
        long expectedCount = 0;
        long expectedSum   = 0;
        for (long s : samples) {
            long before = d.perWorkerShortSampleSlot(0);
            d.addShortSampleForTests(0, s);
            long after = d.perWorkerShortSampleSlot(0);
            if (before == after) continue;   // dropped
            expectedCount++;
            expectedSum += s;
        }
        long slot = d.perWorkerShortSampleSlot(0);
        assertEquals(expectedCount, slot >>> 48,
                "count must equal number of accepted samples");
        assertEquals(expectedSum, slot & ((1L << 48) - 1L),
                "sum must equal sum of accepted samples");
    }

    // ================================================================
    // Stale activeShardIds snapshot is rejected by the admission barrier.
    // ================================================================
    @Test
    void staleActiveSnapshotIsRejectedByAdmissionBarrier() throws Exception {
        // Two ACTIVE shards. Take a snapshot pointing at shard 0. Then
        // close shard 0 (simulate mid-scale-in) — the snapshot is stale.
        // A submit that picks shard 0 from the stale snapshot MUST have
        // its admission CAS fail and re-pick.
        d = new DynamicHybridDispatcher(cfg(2, 2, 10_000_000L), 4);
        // Freeze the ACTIVE workers so shorts pile up.
        CountDownLatch gate = new CountDownLatch(1);
        // Block on both ACTIVE shards.
        d.submit(new Task(1, WorkloadKind.CPU, 0L, System.nanoTime(), false,
                () -> { try { gate.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } return 0L; }, null));
        d.submit(new Task(2, WorkloadKind.CPU, 0L, System.nanoTime(), false,
                () -> { try { gate.await(); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); } return 0L; }, null));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while ((d.localQueue(0).size() > 0 || d.localQueue(1).size() > 0)
                && System.nanoTime() < deadline) Thread.yield();

        // Close shard 0's admissions AND transition it to DRAINING —
        // but do NOT rebuild the snapshot. The RR snapshot still lists
        // shard 0. Any submit picking it will fail admission and
        // re-pick.
        d.closeAdmissionsForTests(0);
        d.forceStateWithoutRebuildForTests(0, ShardState.DRAINING);

        // Now submit 20 shorts. None must land in shard 0.
        for (int i = 0; i < 20; i++) {
            d.submit(new Task(100 + i, WorkloadKind.CPU, 0L, System.nanoTime(),
                    false, () -> 0L, null));
        }
        assertEquals(0, d.localQueue(0).size(),
                "no SHORT may land in a closed shard even if the RR snapshot is stale");
        gate.countDown();
    }

    // ================================================================
    // Failed scale-out rolls back reservation and timestamp
    // ================================================================
    @Test
    void failedScaleOutRollsBackReservationAndTimestamp() {
        d = new DynamicHybridDispatcher(cfg(0, 1, 10_000_000L), 4);
        assertEquals(3, d.maxShardedWorkers());
        // Force every shard to non-INACTIVE so no scale-out candidate
        // exists. requestScaleOut should return -1 AND leave the
        // reservation counter at 0.
        for (int i = 0; i < 4; i++) d.forceState(i, ShardState.DRAINING);
        int reservedBefore = d.reservedShardedCapacity();
        assertEquals(-1, d.requestScaleOut());
        assertEquals(reservedBefore, d.reservedShardedCapacity(),
                "a scale-out that finds no candidate must not leak reservation");
        // No shard's timestamp should be set — all should still be -1.
        for (int i = 0; i < 4; i++) {
            assertEquals(-1L, d.activationRequestNanosForTests(i),
                    "shard " + i + " timestamp must be -1 after a failed scale-out");
        }
    }

    // ================================================================
    // Task-failure isolation:
    //   - a RuntimeException from a workload must NOT terminate the
    //     worker thread;
    //   - the failed task must be counted;
    //   - a subsequent normal task on the same executor must complete;
    //   - the failed task must NOT contribute to the SHORT EWMA
    //     sample slot for that worker.
    // ================================================================
    @Test
    void runtimeExceptionInTaskDoesNotKillWorkerAndIsExcludedFromEwma() throws Exception {
        // N=2 so all shorts route to the sole ACTIVE shard (worker 0)
        // via RR — makes the "same worker" check trivial.
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 2);

        // Baseline: worker 0's packed SHORT slot must be empty.
        assertEquals(0L, d.perWorkerShortSampleSlot(0),
                "no SHORT samples recorded before any task ran");

        // 1) Submit a task that throws.
        Task throwing = new Task(1L, WorkloadKind.CPU, 0L, System.nanoTime(),
                /*measurement*/false,
                () -> { throw new RuntimeException("boom"); },
                /*onComplete*/null);
        d.submit(throwing);

        // 2) Submit a normal follow-up task with a completion latch.
        CountDownLatch normalDone = new CountDownLatch(1);
        Task normal = new Task(2L, WorkloadKind.CPU, 0L, System.nanoTime(), false,
                () -> 0L,
                (Runnable) normalDone::countDown);
        d.submit(normal);

        // 3) The follow-up must run — proving the worker survived.
        assertTrue(normalDone.await(3, TimeUnit.SECONDS),
                "worker must survive a RuntimeException from a prior task "
                        + "and continue processing the queue");

        // 4) Failure counter must show exactly 1.
        assertEquals(1L, d.taskFailureCount(),
                "the throwing task must be counted exactly once");

        // 5) The packed SHORT slot on worker 0 must reflect exactly ONE
        //    accepted sample (the normal task), NOT two. This is the
        //    key invariant: a failed task never contributes to the EWMA.
        long slot = d.perWorkerShortSampleSlot(0);
        long count = slot >>> 48;
        assertEquals(1L, count,
                "failed task must be excluded from the SHORT EWMA sample count");
    }

    // ================================================================
    // FIX 2 (shutdown drain): with minSharedWorkers=0 and every worker
    // ACTIVE, the sharedQueue must still be drained during shutdown.
    // Under the old code an ACTIVE worker only polled its local queue
    // and never touched sharedQueue, so LONG tasks queued at shutdown
    // could keep the workers alive forever.
    // ================================================================
    @Test
    void shutdownDrainsSharedQueueWhenAllWorkersActive() throws Exception {
        // We need minSharedWorkers = 0 for this scenario, but the
        // production validator enforces minSharedWorkers >= 1. Build the
        // config manually and skip validate() by keeping N large enough
        // that both floors are respected: use a validation-legal cfg
        // (Nmin=3, minShared=1, N=4 → maxSharded=3) and then force all
        // 4 workers ACTIVE via the test hook.
        d = new DynamicHybridDispatcher(cfg(3, 1, 10_000_000L), 4);
        for (int i = 0; i < 4; i++) d.forceState(i, ShardState.ACTIVE);
        assertEquals(4, d.activeShardCount());

        // Queue 20 LONG tasks directly on sharedQueue with completion
        // hooks so we can count how many actually ran.
        final int K = 20;
        final CountDownLatch done = new CountDownLatch(K);
        for (int i = 0; i < K; i++) {
            Task longTask = new Task(1000L + i, WorkloadKind.CPU,
                    /*targetMillis*/2_000_000L,   // > Tc → LONG
                    System.nanoTime(), false,
                    () -> 0L,
                    (Runnable) done::countDown);
            d.sharedQueue().offer(longTask);
        }

        // Trigger shutdown. With the old code the ACTIVE workers would
        // just poll their (empty) local queues forever; awaitTermination
        // would return false and 20 latches would remain outstanding.
        d.shutdown();
        boolean terminated = d.awaitTermination(10, TimeUnit.SECONDS);

        assertTrue(done.await(2, TimeUnit.SECONDS),
                "all LONG tasks queued at shutdown must be drained by ACTIVE workers");
        assertTrue(terminated, "all workers must exit their loop after drain completes");
    }

    // ================================================================
    // P0 — stale-snapshot admission race.
    //
    // Reproduces the exact interleaving:
    //   1. submitter picks an ACTIVE shard from a snapshot;
    //   2. submitter is paused before the admission CAS;
    //   3. shard goes ACTIVE -> DRAINING -> INACTIVE;
    //   4. worker reopens admissionCount[shard] = 0;
    //   5. submitter resumes and successfully takes admission on the
    //      NOW-INACTIVE shard.
    // Without the post-admission ACTIVE-state revalidation, the offer
    // would land on an INACTIVE local queue whose worker only polls
    // sharedQueue — a stranded task. With the revalidation the
    // submitter must observe non-ACTIVE, release admission, and retry.
    // ================================================================
    @Test
    void submitRevalidatesActiveStateAfterAdmissionAndAvoidsStrand() throws Exception {
        // N=4, Nmin=1, minShared=1 → maxSharded=3. Only shard 0 starts ACTIVE.
        d = new DynamicHybridDispatcher(cfg(1, 1, 10_000_000L), 4);
        assertEquals(1, d.activeShardCount());
        assertEquals(ShardState.ACTIVE, d.state(0));

        // Barriers to drive the interleaving deterministically:
        //   picked   — submitter has selected shard 0 from the snapshot
        //   drained  — orchestrator has fully driven shard 0 to INACTIVE
        //              with admissions reopened
        CountDownLatch picked  = new CountDownLatch(1);
        CountDownLatch drained = new CountDownLatch(1);

        // pre-admission hook: pause the submitter with shard 0 in hand,
        // then wait for the orchestrator to complete the full cycle.
        d.preAdmissionHookForTests = (int shard) -> {
            if (shard != 0) return;
            picked.countDown();
            try {
                assertTrue(drained.await(5, TimeUnit.SECONDS));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        };

        // Submitter thread: submits one SHORT to shard 0.
        Thread submitter = new Thread(() -> {
            try {
                d.submit(new Task(42L, WorkloadKind.CPU, 0L,
                        System.nanoTime(), false, () -> 0L, null));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
        }, "submitter");
        submitter.start();

        // Orchestrator: wait until submitter is paused holding shard 0,
        // then drive ACTIVE -> DRAINING -> INACTIVE and reopen admissions.
        assertTrue(picked.await(2, TimeUnit.SECONDS),
                "submitter must reach the pre-admission hook with shard 0");

        // Nmin=1 blocks requestScaleIn (Ns==Nmin), so force the
        // transition directly. This is exactly the scenario the P0
        // fix targets — the state can reach INACTIVE via ANY path.
        d.forceState(0, ShardState.DRAINING);
        d.closeAdmissionsForTests(0);              // mirror requestScaleIn's barrier
        d.forceState(0, ShardState.INACTIVE);      // mirror worker's DRAINING->INACTIVE
        d.reopenAdmissionsForTests(0);             // mirror worker's admissionCount=0
        assertEquals(ShardState.INACTIVE, d.state(0));
        assertEquals(0, d.admissionCountForTests(0));

        // Release the submitter — it should observe non-ACTIVE after
        // taking admission, release, and fall back to shared.
        drained.countDown();
        submitter.join(5_000);
        assertFalse(submitter.isAlive(), "submitter must complete");

        // Invariant: no SHORT ever offered to the INACTIVE local queue.
        assertEquals(0, d.localQueue(0).size(),
                "task must NOT land in the INACTIVE shard's local queue");

        // The task took the fallback path — recorded once.
        assertEquals(1L, d.shortFallbackCount(),
                "submitter must have fallen back to sharedQueue after revalidation");
        // queuedShortAdder must NOT have been incremented for the
        // rejected admission attempt. (The fallback path deliberately
        // does not touch queuedShortAdder either.) It may be 0 or -1
        // depending on whether an INACTIVE worker has already picked
        // up the fallback task and decremented in error; we allow
        // both since the strand invariant is checked above.
        long qs = d.queuedShortTasks();
        assertTrue(qs == 0L,
                "queuedShortAdder must NOT reflect a permanent increment for the "
                        + "rejected admission (got " + qs + ")");
    }
}

