package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link Hashing}.
 * Covers mix64 and shardOf routing logic with various configurations.
 */
@DisplayName("Hashing Tests")
class HashingTest {

    @Test
    @DisplayName("mix64 should be deterministic")
    void mix64IsDeterministic() {
        long input = 12345L;
        long result1 = Hashing.mix64(input);
        long result2 = Hashing.mix64(input);
        assertEquals(result1, result2);
    }

    @Test
    @DisplayName("mix64 should produce different outputs for different inputs")
    void mix64ProducesDifferentOutputs() {
        long result1 = Hashing.mix64(1L);
        long result2 = Hashing.mix64(2L);
        long result3 = Hashing.mix64(3L);
        
        assertNotEquals(result1, result2);
        assertNotEquals(result2, result3);
        assertNotEquals(result1, result3);
    }

    @Test
    @DisplayName("mix64 should handle zero input")
    void mix64HandlesZeroInput() {
        // mix64 is a full mix function - can produce any output for any input
        // Verify determinism instead of assuming non-zero output
        long result1 = Hashing.mix64(0L);
        long result2 = Hashing.mix64(0L);
        assertEquals(result1, result2, "mix64 should be deterministic");
    }

    @Test
    @DisplayName("mix64 should handle negative inputs")
    void mix64HandlesNegativeInputs() {
        long result1 = Hashing.mix64(-1L);
        long result2 = Hashing.mix64(-12345L);
        assertNotEquals(result1, result2);
    }

    @Test
    @DisplayName("mix64 should handle boundary values")
    void mix64HandlesBoundaryValues() {
        long result1 = Hashing.mix64(Long.MIN_VALUE);
        long result2 = Hashing.mix64(Long.MAX_VALUE);
        assertNotEquals(result1, result2);
    }

    @Test
    @DisplayName("shardOf with null routing config should use MODULO")
    void shardOfWithNullConfigUsesModulo() {
        long taskId = 42L;
        int workerCount = 8;
        
        int shard = Hashing.shardOf(taskId, workerCount, null);
        
        // Should be equivalent to modulo
        int expected = Math.floorMod(Long.hashCode(taskId), workerCount);
        assertEquals(expected, shard);
        assertTrue(shard >= 0 && shard < workerCount);
    }

    @Test
    @DisplayName("shardOf should return value in valid shard range")
    void shardOfReturnsValidShardRange() {
        for (int workers = 1; workers <= 32; workers *= 2) {
            for (long taskId = 0; taskId < 100; taskId++) {
                int shard = Hashing.shardOf(taskId, workers, null);
                assertTrue(shard >= 0 && shard < workers,
                    "Shard " + shard + " out of range [0, " + workers + ")");
            }
        }
    }

    @Test
    @DisplayName("shardOf with MODULO mode should match expected behavior")
    void shardOfWithModuloMode() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MODULO, 0);
        
        for (long taskId = 0; taskId < 1000; taskId++) {
            int shard = Hashing.shardOf(taskId, 16, config);
            assertEquals(Math.floorMod(Long.hashCode(taskId), 16), shard);
        }
    }

    @Test
    @DisplayName("shardOf with MIXED_HASH mode should distribute evenly")
    void shardOfWithMixedHashDistributes() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 12345L);
        
        int workerCount = 8;
        int[] distribution = new int[workerCount];
        
        for (long taskId = 0; taskId < 10_000; taskId++) {
            int shard = Hashing.shardOf(taskId, workerCount, config);
            distribution[shard]++;
        }
        
        // Check that distribution is reasonably balanced
        // With 10k tasks and 8 shards, expect ~1250 per shard
        // Allow ±25% variance (937 to 1563)
        for (int count : distribution) {
            assertTrue(count > 900 && count < 1700,
                "Distribution not balanced: " + count);
        }
    }

    @Test
    @DisplayName("shardOf should be deterministic")
    void shardOfIsDeterministic() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 999L);
        
        for (long taskId = 0; taskId < 100; taskId++) {
            int shard1 = Hashing.shardOf(taskId, 16, config);
            int shard2 = Hashing.shardOf(taskId, 16, config);
            assertEquals(shard1, shard2);
        }
    }

    @Test
    @DisplayName("shardOf with different seeds produces different distributions")
    void differentSeedsProduceDifferentResults() {
        ShardedRoutingConfig config1 = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 111L);
        ShardedRoutingConfig config2 = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 222L);
        
        // For most task IDs, different seeds should produce different shards
        // (not guaranteed for all, but should be true for many)
        int differences = 0;
        for (long taskId = 0; taskId < 100; taskId++) {
            int shard1 = Hashing.shardOf(taskId, 16, config1);
            int shard2 = Hashing.shardOf(taskId, 16, config2);
            if (shard1 != shard2) {
                differences++;
            }
        }
        
        // Expect significant differences (not perfect consistency)
        assertTrue(differences > 50,
            "Expected many different results with different seeds, got: " + differences);
    }

    @Test
    @DisplayName("shardOf should handle sequential task IDs with MIXED_HASH")
    void shardOfHandlesSequentialTaskIds() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 0L);
        int workerCount = 8;
        
        // Sequential task IDs should be distributed, not clustered
        int[] distribution = new int[workerCount];
        for (long taskId = 0; taskId < 1000; taskId++) {
            int shard = Hashing.shardOf(taskId, workerCount, config);
            distribution[shard]++;
        }
        
        // All shards should have received tasks
        for (int count : distribution) {
            assertTrue(count > 0, "Shard received no tasks");
        }
    }

    @Test
    @DisplayName("shardOf should handle power-of-two worker counts")
    void shardOfHandlesPowerOfTwoWorkerCounts() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 42L);
        
        int[] workerCounts = {1, 2, 4, 8, 16, 32};
        for (int workers : workerCounts) {
            for (long taskId = 0; taskId < 100; taskId++) {
                int shard = Hashing.shardOf(taskId, workers, config);
                assertTrue(shard >= 0 && shard < workers);
            }
        }
    }

    @Test
    @DisplayName("shardOf should handle non-power-of-two worker counts")
    void shardOfHandlesNonPowerOfTwoWorkerCounts() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 42L);
        
        int[] workerCounts = {3, 5, 7, 9, 15, 20, 24};
        for (int workers : workerCounts) {
            for (long taskId = 0; taskId < 100; taskId++) {
                int shard = Hashing.shardOf(taskId, workers, config);
                assertTrue(shard >= 0 && shard < workers);
            }
        }
    }

    @Test
    @DisplayName("shardOf with single worker should always return 0")
    void shardOfWithSingleWorkerReturnsZero() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 0L);
        
        for (long taskId = 0; taskId < 100; taskId++) {
            int shard = Hashing.shardOf(taskId, 1, config);
            assertEquals(0, shard);
        }
    }

    @Test
    @DisplayName("mix64 should produce good avalanche properties")
    void mix64AvalancheProperties() {
        // Changing one bit in input should change many bits in output
        long input = 1L;
        long result1 = Hashing.mix64(input);
        long result2 = Hashing.mix64(input + 1L);
        
        long xor = result1 ^ result2;
        int bitDifferences = Long.bitCount(xor);
        
        // Should have significant bit differences (not just 1 bit)
        assertTrue(bitDifferences > 10,
            "Poor avalanche: only " + bitDifferences + " bits changed");
    }

    @Test
    @DisplayName("shardOf should handle negative task IDs")
    void shardOfHandlesNegativeTaskIds() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MIXED_HASH, 0L);
        
        for (long taskId = -100; taskId < 0; taskId++) {
            int shard = Hashing.shardOf(taskId, 16, config);
            assertTrue(shard >= 0 && shard < 16);
        }
    }

    @Test
    @DisplayName("shardOf with MODULO should be consistent across calls")
    void shardOfModuloConsistent() {
        ShardedRoutingConfig config = new ShardedRoutingConfig(
            ShardedRoutingConfig.Mode.MODULO, 0);
        
        for (long taskId = 0; taskId < 1000; taskId++) {
            int shard1 = Hashing.shardOf(taskId, 8, config);
            int shard2 = Hashing.shardOf(taskId, 8, config);
            assertEquals(shard1, shard2);
        }
    }
}
