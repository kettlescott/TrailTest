package com.scott;

import java.util.Map;

/**
 * Resource-specific workload parameters, parsed from the YAML
 * {@code profile:} block of a workload (or of one mix component).
 *
 * <p>Only a subset of fields is meaningful for each resource type:
 * <ul>
 *   <li><b>cpu</b>    — {@link #iterationsMultiplier}</li>
 *   <li><b>io</b>     — one (or the sum) of {@link #waitNanos},
 *                       {@link #waitMicros}, {@link #waitMillis}</li>
 *   <li><b>memory</b> — {@link #arraySize}, {@link #steps},
 *                       {@link #accessPattern}</li>
 * </ul>
 */
public record WorkloadProfile(
        Integer iterationsMultiplier,
        Long waitNanos,
        Long waitMicros,
        Long waitMillis,
        Integer arraySize,
        Integer steps,
        String accessPattern
) {

    public static WorkloadProfile empty() {
        return new WorkloadProfile(null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    public static WorkloadProfile fromMap(Object raw) {
        if (raw == null) return empty();
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("profile must be a map");
        }
        Map<String, Object> pm = (Map<String, Object>) m;
        return new WorkloadProfile(
                asInt(pm, "iterationsMultiplier"),
                asLong(pm, "waitNanos"),
                asLong(pm, "waitMicros"),
                asLong(pm, "waitMillis"),
                asInt(pm, "arraySize"),
                asInt(pm, "steps"),
                asString(pm, "accessPattern")
        );
    }

    /** Normalized IO wait duration in nanoseconds, or {@code null} if nothing set. */
    public Long totalWaitNanos() {
        long total = 0L;
        boolean any = false;
        if (waitNanos  != null) { total += waitNanos;               any = true; }
        if (waitMicros != null) { total += waitMicros * 1_000L;     any = true; }
        if (waitMillis != null) { total += waitMillis * 1_000_000L; any = true; }
        return any ? total : null;
    }

    public void validateFor(WorkloadResourceType resource, String path) {
        switch (resource) {
            case CPU -> {
                if (iterationsMultiplier == null || iterationsMultiplier <= 0) {
                    throw new IllegalArgumentException(
                            path + ".profile.iterationsMultiplier must be > 0 for cpu workloads");
                }
            }
            case IO -> {
                Long wait = totalWaitNanos();
                if (wait == null || wait <= 0) {
                    throw new IllegalArgumentException(
                            path + ".profile must define waitNanos/waitMicros/waitMillis (>0) for io workloads");
                }
            }
            case MEMORY -> {
                if (arraySize == null || arraySize <= 0) {
                    throw new IllegalArgumentException(
                            path + ".profile.arraySize must be > 0 for memory workloads");
                }
                if (steps == null || steps <= 0) {
                    throw new IllegalArgumentException(
                            path + ".profile.steps must be > 0 for memory workloads");
                }
                if (accessPattern == null
                        || !(accessPattern.equalsIgnoreCase("sequential")
                          || accessPattern.equalsIgnoreCase("random"))) {
                    throw new IllegalArgumentException(
                            path + ".profile.accessPattern must be 'sequential' or 'random'");
                }
            }
            case MIXED -> throw new IllegalArgumentException(
                    path + ": 'mixed' has no top-level profile; use components[].profile");
        }
    }

    public String summary(WorkloadResourceType resource) {
        return switch (resource) {
            case CPU    -> "iterationsMultiplier=" + iterationsMultiplier;
            case IO     -> "waitNanos=" + totalWaitNanos();
            case MEMORY -> "arraySize=" + arraySize + ", steps=" + steps
                    + ", accessPattern=" + accessPattern;
            case MIXED  -> "(mixed)";
        };
    }

    private static Integer asInt(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : Integer.parseInt(String.valueOf(v));
    }

    private static Long asLong(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : Long.parseLong(String.valueOf(v));
    }

    private static String asString(Map<String, Object> m, String key) {
        Object v = m.get(key);
        return v == null ? null : String.valueOf(v);
    }
}

