package com.scott;

/**
 * Resource axis a workload exercises. Replaces the legacy SHORT/MEDIUM/LONG
 * sizing (which is now expressed as {@code targetMillis} on each task).
 */
public enum WorkloadKind {
    /** CPU-bound: deterministic compute loop sized by calibration. */
    CPU,
    /** Memory-bound: deterministic buffer traversal sized by calibration. */
    MEMORY,
    /** IO-bound: parks the worker thread for {@code targetMillis}. */
    IO;

    public static WorkloadKind fromLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("workload kind is required (CPU|MEMORY|IO)");
        }
        String v = value.trim().toUpperCase();
        try {
            return WorkloadKind.valueOf(v);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown workload kind: '" + value + "' (expected CPU|MEMORY|IO)");
        }
    }
}

