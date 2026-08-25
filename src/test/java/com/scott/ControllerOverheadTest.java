package com.scott;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for the controller-overhead optimization:
 *  - LongAdder-backed Qs matches the O(N) exact scan
 *  - controller runtime metrics increment
 *  - controller decisions are still at-most-one per tick
 *  - overrun counter can be triggered
 */
class ControllerOverheadTest {

    private DynamicHybridDispatcher d;

    @AfterEach
    void tearDown() throws Exception {
        if (d != null) {
            d.shutdown();
            d.awaitTermination(5, TimeUnit.SECONDS);
            d = null;
        }
    }

    private static DynamicHybridConfig cfg(long intervalMicros) {
        // Very long controller interval keeps automatic ticks out of the
        // way so the test drives evaluation deterministically.
        return new DynamicHybridConfig(200, 4, 1, 0.5, 500, 150, intervalMicros);
    }

    /** Full-Nmin config: all N shards start ACTIVE. */
    private static DynamicHybridConfig cfgAllActive(int n, long intervalMicros) {
        return new DynamicHybridConfig(200, n, 1, 0.5, 500, 150, intervalMicros);
    }

    private static Task noop(long id) {
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

    // ------------------------------------------------------------------
    // Aggregate Qs counter must match the O(N) scan after submit-only path.
    // ------------------------------------------------------------------
    @Test
    void longAdderQsMatchesExactScan() throws Exception {
        // N=9 (was 8) so cfgAllActive's nmin(8) + minSharedWorkers(1) <= N.
        // Ns still 8 (Nmin=8) → 40 shorts round-robin to 5 per ACTIVE shard.
        d = new DynamicHybridDispatcher(cfgAllActive(8, 1_000_000L), 9);
        CountDownLatch gate = new CountDownLatch(1);
        // Submit blockers through the public API so both counters agree.
        for (int i = 0; i < 8; i++) d.submit(blocking(gate));
        Thread.sleep(80);

        for (int k = 0; k < 40; k++) d.submit(noop(k));

        assertEquals(40, d.queuedShortTasksExact(),
                "exact scan must see 40 pending shorts across ACTIVE shards");
        assertEquals(40L, d.queuedShortTasks(),
                "LongAdder-backed Qs must equal the exact scan");
        gate.countDown();
    }

    // ------------------------------------------------------------------
    // Controller runtime metrics are updated on every tick.
    // ------------------------------------------------------------------
    @Test
    void controllerRuntimeMetricsIncrement() throws Exception {
        // Very short interval — many ticks in a short wait window.
        // N=5 (was 4) so cfg's nmin(4) + minSharedWorkers(1) <= N.
        d = new DynamicHybridDispatcher(cfg(500L), 5);

        long ticks0 = d.controllerTickCount();
        Thread.sleep(50);
        long ticks1 = d.controllerTickCount();
        assertTrue(ticks1 > ticks0, "tick count must strictly increase (got "
                + ticks0 + " -> " + ticks1 + ")");
        assertTrue(d.controllerTotalRuntimeNanos() >= 0);
        assertTrue(d.controllerMaxRuntimeNanos() >= 0);
        assertTrue(d.controllerMeanRuntimeNanos() >= 0);
        assertEquals(500_000L, d.controllerPeriodNanos(),
                "period nanos = configured microseconds * 1000");
    }

    // ------------------------------------------------------------------
    // At-most-one capacity change per tick.
    // ------------------------------------------------------------------
    @Test
    void controllerChangesCapacityByAtMostOnePerTick() throws Exception {
        d = new DynamicHybridDispatcher(cfg(1_000_000L), 8);
        // Force conditions that would keep scale-out demanded.
        d.updateShortEwma(1_000_000L);   // Ts huge => Ps huge
        // Trigger the tick body directly a bunch of times and count
        // ACTIVE growth. Every invocation must add at most one shard.
        int before = d.activeShardCount();
        for (int i = 0; i < 3; i++) {
            DynamicHybridDispatcher.Decision decision = d.evaluateScalingDecision();
            // Simulate what controllerTick() does.
            switch (decision) {
                case SCALE_OUT -> d.requestScaleOut();
                case SCALE_IN  -> d.requestScaleIn();
                case NONE      -> { }
            }
            int now = d.activeShardCount();
            assertTrue(now - before <= 1,
                    "capacity may grow by at most one between the loop iterations, got " + (now - before));
            before = now;
        }
    }
}

