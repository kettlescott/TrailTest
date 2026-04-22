package com.scott;

/**
 * Logical resource category that a workload stresses.
 *
 * <p>This replaces the old short/medium/long classification as the primary
 * axis along which workloads are defined in YAML.
 */
public enum WorkloadResourceType {
    CPU("cpu"),
    IO("io"),
    MEMORY("memory"),
    /** No-op baseline workload — travels the full path but does no payload work. */
    EMPTY("empty"),
    MIXED("mixed");

    private final String label;

    WorkloadResourceType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }

    public static WorkloadResourceType fromLabel(String value) {
        if (value == null) {
            throw new IllegalArgumentException("workload.resource is required (cpu|io|memory|empty|mixed)");
        }
        for (WorkloadResourceType t : values()) {
            if (t.label.equalsIgnoreCase(value)) {
                return t;
            }
        }
        throw new IllegalArgumentException(
                "Unknown workload.resource: '" + value + "' (expected cpu|io|memory|empty|mixed)");
    }
}

