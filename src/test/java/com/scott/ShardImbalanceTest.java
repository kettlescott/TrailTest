package com.scott;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Experiment 2 — controlled sharded load imbalance.
 *
 * <p>Verifies that {@link TaskGenerator#pickImbalancedRoutingKey()} +
 * {@link TaskGenerator#planImbalanceSchedule(long)} produce routing
 * keys whose distribution over shards (computed via the production
 * {@link Hashing#shardOf}) matches the theoretical model:
 * <pre>
 *   p_hot   = alpha + (1 - alpha) / N
 *   p_other = (1 - alpha) / N
 * </pre>
 *
 * <p>Legacy behaviour is preserved <b>only</b> when no
 * {@link ShardImbalanceConfig} is supplied at all. Supplying a config
 * with {@code alpha == 0} activates the Experiment 2 balanced baseline
 * — same code path as alpha &gt; 0, just a balanced distribution.
 */
class ShardImbalanceTest {

    private static final int N = 8;
    private static final ShardedRoutingConfig ROUTING = ShardedRoutingConfig.defaults();

    private static TaskGenerator newGen(double alpha, int hot, long seed) {
        WorkloadEntry e = new WorkloadEntry("t", WorkloadKind.CPU, 1L, 1.0, null, 1, 0);
        WorkloadConfig wc = new WorkloadConfig(java.util.List.of(e));
        return new TaskGenerator(
                wc, 0L, WorkloadSeedMode.SEQUENTIAL_TASK_ID, 0L,
                new ShardImbalanceConfig(alpha, hot, seed),
                N, ROUTING);
    }

    private static TaskGenerator newLegacyGen() {
        WorkloadEntry e = new WorkloadEntry("t", WorkloadKind.CPU, 1L, 1.0, null, 1, 0);
        WorkloadConfig wc = new WorkloadConfig(java.util.List.of(e));
        return new TaskGenerator(wc, 0L, WorkloadSeedMode.SEQUENTIAL_TASK_ID, 0L,
                null, 0, null);
    }

    /** Consume M tasks through the real routing-key path and count shards. */
    private static int[] plannedCounts(TaskGenerator gen, int M) {
        int[] c = new int[N];
        for (int i = 0; i < M; i++) {
            long key = gen.pickImbalancedRoutingKey();
            int s = Hashing.shardOf(key, N, ROUTING);
            assertTrue(s >= 0 && s < N, "shard " + s + " out of range");
            c[s]++;
        }
        return c;
    }

    // ================================================================
    //  Legacy vs Experiment 2 baseline
    // ================================================================

    @Test
    void legacy_noConfig_leavesRoutingKeyEqualToTaskId() {
        TaskGenerator gen = newLegacyGen();
        Task t = gen.nextTask(42L, false, () -> {});
        assertEquals(t.taskId(), t.routingKey(),
                "no ShardImbalanceConfig ⇒ legacy: routingKey == taskId");
    }

    @Test
    void alphaZeroConfig_isNotLegacy_usesExperimentPath() {
        // Presence of the config (even with alpha=0) MUST activate the
        // Experiment 2 workload path: routing keys become representative
        // keys, not taskIds.
        TaskGenerator gen = newGen(0.0, 0, 1L);
        gen.planImbalanceSchedule(1);
        Task t = gen.nextTask(42L, false, () -> {});
        assertNotEquals(t.taskId(), t.routingKey(),
                "alpha=0 with config supplied MUST use Experiment 2 path");
    }

    // ================================================================
    //  Representative-key correctness
    // ================================================================

    @Test
    void representativeKeys_mapExactlyToTheirShard() {
        TaskGenerator gen = newGen(0.5, 0, 1L);
        long[] keys = gen.representativeKeysForTest();
        assertNotNull(keys);
        assertEquals(N, keys.length);
        for (int s = 0; s < N; s++) {
            assertEquals(s, Hashing.shardOf(keys[s], N, ROUTING),
                    "representative key for shard " + s + " must hash to " + s);
        }
    }

    // ================================================================
    //  Planned-schedule correctness across the full alpha grid
    // ================================================================

    @Test
    void plannedSchedule_totalTaskCountUnchangedForAllAlpha() {
        int M = 10_000;
        for (double a : new double[]{0.0, 0.2, 0.4, 0.6, 0.8, 1.0}) {
            TaskGenerator gen = newGen(a, 0, 42L);
            gen.planImbalanceSchedule(M);
            int[] c = plannedCounts(gen, M);
            long sum = 0; for (int v : c) sum += v;
            assertEquals(M, sum, "alpha=" + a + " total must be preserved");
        }
    }

    @Test
    void plannedSchedule_alphaZero_isBalancedWithinRounding() {
        int M = 16_000;   // divisible by N=8
        TaskGenerator gen = newGen(0.0, 0, 1L);
        gen.planImbalanceSchedule(M);
        int[] c = plannedCounts(gen, M);
        int expected = M / N;
        for (int i = 0; i < N; i++) {
            assertTrue(Math.abs(c[i] - expected) <= 1,
                    "shard " + i + " count=" + c[i] + " expected≈" + expected);
        }
    }

    @Test
    void plannedSchedule_alphaOne_allHitHotShard() {
        int M = 5_000;
        int hot = 3;
        TaskGenerator gen = newGen(1.0, hot, 7L);
        gen.planImbalanceSchedule(M);
        int[] c = plannedCounts(gen, M);
        assertEquals(M, c[hot]);
        for (int i = 0; i < N; i++) if (i != hot) assertEquals(0, c[i]);
    }

    @Test
    void plannedSchedule_intermediateAlpha_matchesLargestRemainderAllocation() {
        int M = 10_000;
        int hot = 2;
        for (double a : new double[]{0.2, 0.4, 0.6, 0.8}) {
            TaskGenerator gen = newGen(a, hot, 99L);
            gen.planImbalanceSchedule(M);
            int[] c = plannedCounts(gen, M);
            double pHot   = a + (1.0 - a) / N;
            double pOther = (1.0 - a) / N;
            // Under largest-remainder each integer count must be within
            // 1 of the ideal M*p_i.
            for (int i = 0; i < N; i++) {
                double ideal = M * (i == hot ? pHot : pOther);
                assertTrue(Math.abs(c[i] - ideal) <= 1.0,
                        "alpha=" + a + " shard " + i + " c=" + c[i]
                                + " ideal=" + ideal);
            }
            long sum = 0; for (int v : c) sum += v;
            assertEquals(M, sum);
        }
    }

    // ================================================================
    //  Explicit N=32 hot-shard fractions from the spec
    // ================================================================

    @Test
    void n32_theoreticalHotFractions_match() {
        final int N32 = 32;
        final int M   = 100_000;
        WorkloadEntry e = new WorkloadEntry("t", WorkloadKind.CPU, 1L, 1.0, null, 1, 0);
        WorkloadConfig wc = new WorkloadConfig(java.util.List.of(e));
        double[] alphas   = {0.0,     0.2,   0.4,     0.6,    0.8,     1.0};
        double[] expected = {0.03125, 0.225, 0.41875, 0.6125, 0.80625, 1.0};
        for (int idx = 0; idx < alphas.length; idx++) {
            double a = alphas[idx];
            TaskGenerator gen = new TaskGenerator(wc, 0L,
                    WorkloadSeedMode.SEQUENTIAL_TASK_ID, 0L,
                    new ShardImbalanceConfig(a, 0, 42L), N32,
                    ROUTING);
            gen.planImbalanceSchedule(M);
            int hotCount = 0;
            for (int i = 0; i < M; i++) {
                long key = gen.pickImbalancedRoutingKey();
                if (Hashing.shardOf(key, N32, ROUTING) == 0) hotCount++;
            }
            double observed = hotCount / (double) M;
            assertEquals(expected[idx], observed, 1.0 / M + 1e-9,
                    "N=32 alpha=" + a + " hot fraction " + observed
                            + " (expected " + expected[idx] + ")");
        }
    }

    // ================================================================
    //  Determinism / seed semantics
    // ================================================================

    @Test
    void plannedSchedule_sameSeedReproducesShardSequence() {
        int M = 2_000;
        TaskGenerator g1 = newGen(0.6, 1, 12345L);
        TaskGenerator g2 = newGen(0.6, 1, 12345L);
        g1.planImbalanceSchedule(M);
        g2.planImbalanceSchedule(M);
        assertArrayEquals(g1.plannedShardSequenceForTest(),
                          g2.plannedShardSequenceForTest(),
                "same seed must reproduce identical shard sequence");
    }

    @Test
    void plannedSchedule_differentSeedsChangeOrderNotCounts() {
        int M = 3_000;
        TaskGenerator g1 = newGen(0.5, 4, 1L);
        TaskGenerator g2 = newGen(0.5, 4, 2L);
        g1.planImbalanceSchedule(M);
        g2.planImbalanceSchedule(M);
        int[] s1 = g1.plannedShardSequenceForTest();
        int[] s2 = g2.plannedShardSequenceForTest();
        assertFalse(java.util.Arrays.equals(s1, s2),
                "different seeds should change temporal ordering");
        int[] c1 = new int[N], c2 = new int[N];
        for (int v : s1) c1[v]++;
        for (int v : s2) c2[v]++;
        assertArrayEquals(c1, c2,
                "different seeds must NOT change aggregate shard counts");
    }

    // ================================================================
    //  Warmup + measurement consistency
    // ================================================================

    @Test
    void warmupAndMeasurement_useSameConfiguredAlpha() {
        // Simulate the BenchmarkMain loop: same generator, two phases,
        // each with its own planImbalanceSchedule(M) call. Both phases
        // must show the same hot-shard fraction (up to integer rounding).
        int warmupM = 4_000;
        int measM   = 12_000;
        double a = 0.6;
        int hot = 2;
        TaskGenerator gen = newGen(a, hot, 77L);

        gen.planImbalanceSchedule(warmupM);
        int[] warmupCounts = plannedCounts(gen, warmupM);

        gen.planImbalanceSchedule(measM);
        int[] measCounts = plannedCounts(gen, measM);

        double warmupHotFrac = warmupCounts[hot] / (double) warmupM;
        double measHotFrac   = measCounts[hot]  / (double) measM;
        double pHot          = a + (1.0 - a) / N;

        assertEquals(pHot, warmupHotFrac, 1.0 / warmupM + 1e-9,
                "warmup hot fraction must match configured alpha");
        assertEquals(pHot, measHotFrac,   1.0 / measM   + 1e-9,
                "measurement hot fraction must match configured alpha");
    }

    // ================================================================
    //  Invariants
    // ================================================================

    @Test
    void probabilityMassSumsToOne_forAllAlpha() {
        for (double a : new double[]{0.0, 0.2, 0.4, 0.6, 0.8, 1.0}) {
            double pOther = (1.0 - a) / N;
            double pHot   = pOther + a;
            double sum = pHot + (N - 1) * pOther;
            assertEquals(1.0, sum, 1e-12, "alpha=" + a);
        }
    }

    @Test
    void plannedSchedule_allShardIdsInRange() {
        int M = 5_000;
        TaskGenerator gen = newGen(0.7, 6, 55L);
        gen.planImbalanceSchedule(M);
        for (int v : gen.plannedShardSequenceForTest()) {
            assertTrue(v >= 0 && v < N, "planned shard id " + v + " out of range");
        }
    }

    // ================================================================
    //  Fail-fast: no plan, or plan exhausted
    // ================================================================

    @Test
    void pickWithoutPlan_throws() {
        TaskGenerator gen = newGen(0.5, 0, 1L);
        assertThrows(IllegalStateException.class,
                gen::pickImbalancedRoutingKey,
                "must fail-fast when planImbalanceSchedule() was not called");
    }

    @Test
    void pickBeyondPlan_throws() {
        TaskGenerator gen = newGen(0.5, 0, 1L);
        gen.planImbalanceSchedule(4);
        for (int i = 0; i < 4; i++) gen.pickImbalancedRoutingKey();
        assertThrows(IllegalStateException.class,
                gen::pickImbalancedRoutingKey,
                "must fail-fast when the planned schedule is exhausted");
    }
}

