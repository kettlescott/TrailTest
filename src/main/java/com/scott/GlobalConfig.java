package com.scott;

public record GlobalConfig(
        int workerCount,
        int maxInflight,
        long seed,
        int warmupSeconds,
        int measurementSeconds,
        int taskCount,
        ShardedRoutingConfig shardedRouting,
        WorkloadSeedMode workloadSeedMode,
        long workloadSeed,
        BlackholeMode blackholeMode,
        boolean retainCompletedTasks
) {
    /** Back-compat ctor — applies legacy defaults for the new knobs. */
    public GlobalConfig(int workerCount,
                        int maxInflight,
                        long seed,
                        int warmupSeconds,
                        int measurementSeconds,
                        int taskCount) {
        this(workerCount, maxInflight, seed, warmupSeconds, measurementSeconds, taskCount,
                ShardedRoutingConfig.defaults(),
                WorkloadSeedMode.SEQUENTIAL_TASK_ID,
                0L,
                BlackholeMode.SHARED_VOLATILE,
                false);
    }

    public void validate() {
        if (workerCount <= 0) throw new IllegalArgumentException("global.workerCount must be > 0");
        if (maxInflight <= 0) throw new IllegalArgumentException("global.maxInflight must be > 0");
        if (warmupSeconds < 0) throw new IllegalArgumentException("global.warmupSeconds must be >= 0");
        if (measurementSeconds <= 0) throw new IllegalArgumentException("global.measurementSeconds must be > 0");
        if (taskCount < 0) throw new IllegalArgumentException("global.taskCount must be >= 0");
        if (shardedRouting == null) throw new IllegalArgumentException("global.shardedRouting must not be null");
        if (workloadSeedMode == null) throw new IllegalArgumentException("global.workloadSeedMode must not be null");
        if (blackholeMode == null) throw new IllegalArgumentException("global.blackholeMode must not be null");
    }
}
