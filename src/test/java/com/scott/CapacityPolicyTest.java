package com.scott;

import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Focused tests for the pluggable {@link CapacityPolicy} extension point.
 * Existing algorithm tests (scale-out / scale-in / hysteresis) still
 * cover the default {@link HysteresisPressurePolicy} end-to-end.
 */
class CapacityPolicyTest {

    private static DynamicHybridConfig cfg() {
        return new DynamicHybridConfig(200, 2, 1, 0.5, 500, 150, 1_000_000L);
    }

    @Test
    void defaultPolicyIsHysteresisPressure() throws Exception {
        DynamicHybridDispatcher d = new DynamicHybridDispatcher(cfg(), 4);
        try {
            // Snapshot must expose all fields the default policy needs.
            SchedulerSnapshot s = d.snapshot();
            assertEquals(4,        s.workerCount());
            assertEquals(2,        s.minShardedWorkers());
            assertEquals(500_000L, s.scaleOutThresholdNanos());
            assertEquals(150_000L, s.scaleInThresholdNanos());
        } finally {
            d.shutdown();
            d.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void customPolicyIsCalledOnEveryDecision() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CapacityPolicy stub = snap -> {
            calls.incrementAndGet();
            return DynamicHybridDispatcher.Decision.NONE;
        };
        DynamicHybridDispatcher d = new DynamicHybridDispatcher(cfg(), 4, stub);
        try {
            d.evaluateScalingDecision();
            d.evaluateScalingDecision();
            assertEquals(2, calls.get());
        } finally {
            d.shutdown();
            d.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void customPolicyCanForceScaleOut() throws Exception {
        // Always scale out — up to N. Ignores pressure entirely.
        CapacityPolicy alwaysOut = snap ->
                snap.activeShardCount() < snap.workerCount()
                        ? DynamicHybridDispatcher.Decision.SCALE_OUT
                        : DynamicHybridDispatcher.Decision.NONE;
        DynamicHybridDispatcher d = new DynamicHybridDispatcher(cfg(), 4, alwaysOut);
        try {
            assertEquals(DynamicHybridDispatcher.Decision.SCALE_OUT,
                    d.evaluateScalingDecision());
            // At Ns == N the same policy must return NONE.
            for (int i = 0; i < 4; i++) d.forceState(i, ShardState.ACTIVE);
            assertEquals(DynamicHybridDispatcher.Decision.NONE,
                    d.evaluateScalingDecision());
        } finally {
            d.shutdown();
            d.awaitTermination(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void nullPolicyIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new DynamicHybridDispatcher(cfg(), 4, null));
    }
}

