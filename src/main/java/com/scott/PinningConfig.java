package com.scott;

import java.util.Arrays;

/**
 * Per-run worker thread pinning configuration.
 *
 * <h3>Two mutually-exclusive modes (only one applies when enabled)</h3>
 * <ol>
 *   <li><b>EXACT_CPU</b> (legacy) — {@code coreMap[workerIndex]} gives ONE
 *       logical CPU per worker. Thread cannot migrate. Kept untouched
 *       for existing A/B runs.</li>
 *   <li><b>NUMA_NODE</b> — {@code workerNodes[workerIndex]} gives the NUMA
 *       node id; the worker's affinity mask is set to ALL logical CPUs
 *       belonging to that node (read from {@link NumaTopology}). Kernel
 *       is free to migrate the thread within the node but not across
 *       nodes.</li>
 * </ol>
 *
 * <p>When {@link #enabled()} is {@code false}, both maps are ignored.
 */
public record PinningConfig(boolean enabled, int[] coreMap, int[] workerNodes) {

    public enum Mode { DISABLED, EXACT_CPU, NUMA_NODE }

    /** Back-compat 2-arg constructor used across the codebase. */
    public PinningConfig(boolean enabled, int[] coreMap) {
        this(enabled, coreMap, null);
    }

    public static PinningConfig disabled() {
        return new PinningConfig(false, null, null);
    }

    public Mode mode() {
        if (!enabled) return Mode.DISABLED;
        if (workerNodes != null && workerNodes.length > 0) return Mode.NUMA_NODE;
        return Mode.EXACT_CPU;
    }

    public void validate(String runName, BenchmarkMode mode, int workerCount) {
        if (!enabled) return;
        if (mode != BenchmarkMode.SHARDED && mode != BenchmarkMode.SHARED) {
            throw new IllegalArgumentException(
                    "runs[" + runName + "].pinning.enabled=true is only supported for mode=sharded or mode=shared "
                            + "(got mode=" + mode + ")");
        }
        Mode m = mode();
        if (m == Mode.NUMA_NODE) {
            NumaTopology topo = NumaTopology.get();
            if (!topo.isAvailable()) {
                throw new IllegalArgumentException(
                        "runs[" + runName + "].pinning.workerNodes requires /sys/devices/system/node "
                                + "(non-Linux or sysfs unavailable). Fall back to coreMap-based pinning.");
            }
            for (int i = 0; i < workerNodes.length; i++) {
                int n = workerNodes[i];
                if (n < 0 || n >= topo.nodeCount()) {
                    throw new IllegalArgumentException(
                            "runs[" + runName + "].pinning.workerNodes[" + i + "]=" + n
                                    + " out of range 0.." + (topo.nodeCount() - 1));
                }
                if (topo.cpusOfNode(n).length == 0) {
                    throw new IllegalArgumentException(
                            "runs[" + runName + "].pinning.workerNodes[" + i + "]=" + n
                                    + " has no CPUs in sysfs");
                }
            }
        } else { // EXACT_CPU
            if (coreMap == null || coreMap.length == 0) {
                throw new IllegalArgumentException(
                        "runs[" + runName + "].pinning.coreMap must not be null or empty when enabled=true "
                                + "(and workerNodes is not provided)");
            }
        }
    }

    public String describe() {
        if (!enabled) return "Pinning: enabled=false";
        return switch (mode()) {
            case NUMA_NODE -> "Pinning: mode=NUMA_NODE workerNodes=" + Arrays.toString(workerNodes)
                    + " (" + NumaTopology.get().describe() + ")";
            case EXACT_CPU -> "Pinning: mode=EXACT_CPU coreMap=" + Arrays.toString(coreMap);
            default        -> "Pinning: enabled=false";
        };
    }
}

