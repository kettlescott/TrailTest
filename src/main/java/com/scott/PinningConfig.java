package com.scott;

import java.util.Arrays;

/**
 * Per-run worker thread pinning configuration.
 *
 * <p>When {@link #enabled()} is {@code true}, the dispatcher will pin each
 * worker thread to the CPU core given by {@code coreMap[workerIndex]}.
 * When {@code false}, {@code coreMap} is ignored.
 *
 * <p>This is intentionally minimal — no NUMA logic, no automatic core
 * discovery, no placement policies.  It exists so a single YAML can
 * define both pinned and unpinned ShardedExecutor runs side-by-side.
 */
public record PinningConfig(boolean enabled, int[] coreMap) {

    /** Disabled-pinning singleton helper. */
    public static PinningConfig disabled() {
        return new PinningConfig(false, null);
    }

    /**
     * Validates this pinning config against a given worker count and
     * benchmark mode.
     *
     * <ul>
     *   <li>If {@code enabled == false}, no checks are performed.</li>
     *   <li>If {@code enabled == true}, {@code coreMap} must be non-null
     *       and non-empty.  It may be shorter than {@code workerCount};
     *       worker-to-core assignment is round-robin:
     *       {@code coreMap[workerIndex % coreMap.length]}.</li>
     * </ul>
     */
    public void validate(String runName, BenchmarkMode mode, int workerCount) {
        if (!enabled) return;
        if (mode != BenchmarkMode.SHARDED && mode != BenchmarkMode.SHARED) {
            throw new IllegalArgumentException(
                    "runs[" + runName + "].pinning.enabled=true is only supported for mode=sharded or mode=shared "
                            + "(got mode=" + mode + ")");
        }
        if (coreMap == null || coreMap.length == 0) {
            throw new IllegalArgumentException(
                    "runs[" + runName + "].pinning.coreMap must not be null or empty when enabled=true");
        }
    }

    public String describe() {
        if (!enabled) return "Pinning: enabled=false";
        return "Pinning: enabled=true coreMap=" + Arrays.toString(coreMap);
    }
}

