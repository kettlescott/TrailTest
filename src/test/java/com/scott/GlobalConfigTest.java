package com.scott;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive unit tests for {@link GlobalConfig} validation.
 * Ensures all validation rules are correctly enforced.
 */
@DisplayName("GlobalConfig Validation Tests")
class GlobalConfigTest {

    private static GlobalConfig validConfig() {
        return new GlobalConfig(
            32,           // workerCount
            32,           // maxInflight
            3735928559L,  // seed
            0,            // warmupSeconds
            60,           // measurementSeconds
            2_000_000,    // taskCount
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L,
            BlackholeMode.SHARED_VOLATILE,
            false,        // retainCompletedTasks
            1,            // sharedQueueCount
            null,         // shardImbalance
            false         // hybrid
        );
    }

    @Test
    @DisplayName("should validate correct configuration")
    void validatesCorrectConfiguration() {
        GlobalConfig config = validConfig();
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should reject zero workerCount")
    void rejectsZeroWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            0, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject negative workerCount")
    void rejectsNegativeWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            -1, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject zero maxInflight")
    void rejectsZeroMaxInflight() {
        GlobalConfig config = new GlobalConfig(
            32, 0, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject negative maxInflight")
    void rejectsNegativeMaxInflight() {
        GlobalConfig config = new GlobalConfig(
            32, -5, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject negative warmupSeconds")
    void rejectsNegativeWarmupSeconds() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, -1, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should accept zero warmupSeconds")
    void acceptsZeroWarmupSeconds() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should reject zero measurementSeconds")
    void rejectsZeroMeasurementSeconds() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 0, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject negative measurementSeconds")
    void rejectsNegativeMeasurementSeconds() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, -10, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should accept zero taskCount")
    void acceptsZeroTaskCount() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 0,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should reject negative taskCount")
    void rejectsNegativeTaskCount() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, -1,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject null shardedRouting")
    void rejectsNullShardedRouting() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            null,  // shardedRouting
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject null workloadSeedMode")
    void rejectsNullWorkloadSeedMode() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            null,  // workloadSeedMode
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject null blackholeMode")
    void rejectsNullBlackholeMode() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, null,  // blackholeMode
            false, 1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject zero sharedQueueCount")
    void rejectsZeroSharedQueueCount() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 0, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should reject negative sharedQueueCount")
    void rejectsNegativeSharedQueueCount() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, -1, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should accept sharedQueueCount equal to workerCount")
    void acceptsSharedQueueCountEqualToWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            8, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 8, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should reject sharedQueueCount greater than workerCount")
    void rejectsSharedQueueCountGreaterThanWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            8, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 9, null, false);
        
        assertThrows(IllegalArgumentException.class, config::validate);
    }

    @Test
    @DisplayName("should accept null shardImbalance")
    void acceptsNullShardImbalance() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should validate shardImbalance when present")
    void validatesShardImbalanceWhenPresent() {
        ShardImbalanceConfig imbalance = new ShardImbalanceConfig(0.5, 0, 123L);
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, imbalance, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should accept all valid workloadSeedMode values")
    void acceptsAllWorkloadSeedModes() {
        for (WorkloadSeedMode mode : WorkloadSeedMode.values()) {
            GlobalConfig config = new GlobalConfig(
                32, 32, 123L, 0, 60, 1000,
                ShardedRoutingConfig.defaults(),
                mode,
                0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
            
            assertDoesNotThrow(config::validate);
        }
    }

    @Test
    @DisplayName("should accept all valid blackholeMode values")
    void acceptsAllBlackhholeModes() {
        for (BlackholeMode mode : BlackholeMode.values()) {
            GlobalConfig config = new GlobalConfig(
                32, 32, 123L, 0, 60, 1000,
                ShardedRoutingConfig.defaults(),
                WorkloadSeedMode.SEQUENTIAL_TASK_ID,
                0L, mode, false, 1, null, false);
            
            assertDoesNotThrow(config::validate);
        }
    }

    @Test
    @DisplayName("should accept large valid workerCount")
    void acceptsLargeWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            256, 256, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 256, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should accept large taskCount")
    void acceptsLargeTaskCount() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, Integer.MAX_VALUE,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 1, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should validate with retainCompletedTasks true")
    void validatesWithRetainedTasks() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, true, 1, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("backwards-compat constructor should create valid config")
    void backwardsCompatConstructorIsValid() {
        GlobalConfig config = new GlobalConfig(32, 32, 123L, 0, 60, 1000);
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("intermediate backwards-compat constructor should create valid config")
    void intermediateBackwardsCompatConstructorIsValid() {
        GlobalConfig config = new GlobalConfig(
            32, 32, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should handle edge case: maxInflight equals workerCount")
    void handlesMaxInflightEqualsWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            16, 16, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 16, null, false);
        
        assertDoesNotThrow(config::validate);
    }

    @Test
    @DisplayName("should handle edge case: maxInflight much larger than workerCount")
    void handlesMaxInflightMuchLargerThanWorkerCount() {
        GlobalConfig config = new GlobalConfig(
            8, 1000, 123L, 0, 60, 1000,
            ShardedRoutingConfig.defaults(),
            WorkloadSeedMode.SEQUENTIAL_TASK_ID,
            0L, BlackholeMode.SHARED_VOLATILE, false, 8, null, false);
        
        assertDoesNotThrow(config::validate);
    }
}

