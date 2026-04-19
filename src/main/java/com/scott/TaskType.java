package com.scott;

/**
 * Logical workload types used by YAML task generation.
 *
 * <p>Each type carries a label and an iteration multiplier. The generator
 * applies the multiplier to calibrated base iterations to build short,
 * medium, and long CPU-bound tasks.
 */
public enum TaskType {

    /** ~1× base iterations — fast tasks. */
    SHORT("short", 1),

    /** ~10× base iterations — moderate tasks. */
    MEDIUM("medium", 10),

    /** ~100× base iterations — heavy tasks. */
    LONG("long", 100);

    private final String label;
    private final int iterationMultiplier;

    TaskType(String label, int iterationMultiplier) {
        this.label = label;
        this.iterationMultiplier = iterationMultiplier;
    }

    /** Human-readable name (e.g. "short"). */
    public String label() {
        return label;
    }

    /**
     * Factor applied to the base calibrated iteration count to produce
     * a workload of the corresponding duration.
     */
    public int iterationMultiplier() {
        return iterationMultiplier;
    }

    public static TaskType fromLabel(String value) {
        if (value == null) {
            throw new IllegalArgumentException("Task type is required");
        }
        for (TaskType type : values()) {
            if (type.label.equalsIgnoreCase(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown task type: " + value + " (expected short|medium|long)");
    }
}

