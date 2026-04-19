package com.scott;

public record GlobalConfig(
        int workerCount,
        int maxInflight,
        long seed,
        long targetTaskNanos,
        int warmupSeconds,
        int measurementSeconds,
        int taskCount,
        Integer baseIterations
) {
    public void validate() {
        if (workerCount <= 0) throw new IllegalArgumentException("global.workerCount must be > 0");
        if (maxInflight <= 0) throw new IllegalArgumentException("global.maxInflight must be > 0");
        if (targetTaskNanos <= 0) throw new IllegalArgumentException("global.targetTaskNanos must be > 0");
        if (warmupSeconds < 0) throw new IllegalArgumentException("global.warmupSeconds must be >= 0");
        if (measurementSeconds <= 0) throw new IllegalArgumentException("global.measurementSeconds must be > 0");
        if (taskCount < 0) throw new IllegalArgumentException("global.taskCount must be >= 0");
        if (baseIterations != null && baseIterations <= 0) {
            throw new IllegalArgumentException("global.baseIterations must be > 0 when provided");
        }
    }
}

