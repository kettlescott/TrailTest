package com.scott;

import java.util.Map;

/**
 * One entry of a workload mix.
 *
 * <p>Describes a single resource-shaped task class:
 * <ul>
 *   <li>{@code kind}         — CPU | MEMORY | IO</li>
 *   <li>{@code targetMillis} — desired wall-clock execution time per task</li>
 *   <li>{@code ratio}        — fraction of generated tasks (entries within
 *       a workload sum to ≈ 1.0)</li>
 *   <li>{@code name}         — optional human-readable label for output</li>
 *   <li>{@code memory}       — optional MEMORY-kind config (access pattern,
 *       buffer size, writeBack); ignored unless {@code kind == MEMORY}</li>
 * </ul>
 */
public record WorkloadEntry(
        String name,
        WorkloadKind kind,
        long targetMillis,
        double ratio,
        MemoryWorkloadConfig memory,
        int cpuIterations
) {

    /** Backwards-compatible constructor: no MEMORY config, no fixed cpuIterations. */
    public WorkloadEntry(String name, WorkloadKind kind, long targetMillis, double ratio) {
        this(name, kind, targetMillis, ratio, null, 0);
    }

    /** Backwards-compatible constructor: explicit MEMORY config, no fixed cpuIterations. */
    public WorkloadEntry(String name, WorkloadKind kind, long targetMillis, double ratio,
                         MemoryWorkloadConfig memory) {
        this(name, kind, targetMillis, ratio, memory, 0);
    }

    public WorkloadEntry {
        if (kind == null) {
            throw new IllegalArgumentException("workload entry: kind is required");
        }
        if (cpuIterations > 0 && kind != WorkloadKind.CPU) {
            throw new IllegalArgumentException(
                    "cpuIterations is only valid for CPU workloads");
        }
        // targetMillis is required UNLESS this is a fixed-iteration CPU
        // entry (calibration is bypassed; targetMillis becomes a
        // display-only label and may be 0 / omitted from YAML).
        if (targetMillis <= 0 && cpuIterations <= 0) {
            throw new IllegalArgumentException(
                    "workload entry: targetMillis must be > 0 (or set cpuIterations > 0 for CPU)");
        }
        if (ratio <= 0.0 || !Double.isFinite(ratio)) {
            throw new IllegalArgumentException("workload entry: ratio must be > 0");
        }
    }

    /** True when this entry runs a fixed iteration count (no calibration). */
    public boolean usesFixedCpuIterations() {
        return kind == WorkloadKind.CPU && cpuIterations > 0;
    }

    public String displayName() {
        if (name != null && !name.isBlank()) return name;
        return kind.name().toLowerCase() + "_" + targetMillis + "ms";
    }

    /** Returns the MEMORY config, falling back to defaults when null. */
    public MemoryWorkloadConfig memoryOrDefaults() {
        return memory != null ? memory : MemoryWorkloadConfig.defaults();
    }

    @SuppressWarnings("unchecked")
    public static WorkloadEntry fromMap(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(path + " must be a map with {kind,targetMillis,ratio}");
        }
        Map<String, Object> em = (Map<String, Object>) m;
        String name = em.get("name") == null ? null : String.valueOf(em.get("name"));
        Object kindRaw = em.get("kind");
        if (kindRaw == null) {
            throw new IllegalArgumentException(path + ".kind is required (CPU|MEMORY|IO)");
        }
        WorkloadKind kind = WorkloadKind.fromLabel(String.valueOf(kindRaw));

        // Optional fixed-iteration count. CPU-only validity is enforced
        // by the record's compact constructor below.
        Object ciRaw = em.get("cpuIterations");
        int cpuIterations = ciRaw == null ? 0 : Integer.parseInt(String.valueOf(ciRaw));

        // targetMillis is optional only for fixed-iteration CPU entries.
        Object tm = em.get("targetMillis");
        long targetMillis;
        if (tm != null) {
            targetMillis = Long.parseLong(String.valueOf(tm));
        } else if (cpuIterations > 0) {
            targetMillis = 0L;
        } else {
            throw new IllegalArgumentException(
                    path + ".targetMillis is required (or set cpuIterations > 0 for CPU)");
        }
        Object r = em.get("ratio");
        double ratio = r == null ? 1.0 : Double.parseDouble(String.valueOf(r));

        // Optional MEMORY block: only consumed when kind == MEMORY.
        MemoryWorkloadConfig memory = null;
        Object memRaw = em.get("memory");
        if (memRaw != null) {
            if (kind != WorkloadKind.MEMORY) {
                System.err.printf("[yaml] %s.memory ignored: only valid when kind=MEMORY%n", path);
            } else if (memRaw instanceof Map<?, ?> mm) {
                memory = parseMemory((Map<String, Object>) mm, path + ".memory");
            } else {
                throw new IllegalArgumentException(path + ".memory must be a map");
            }
        }

        return new WorkloadEntry(name, kind, targetMillis, ratio, memory, cpuIterations);
    }

    private static MemoryWorkloadConfig parseMemory(Map<String, Object> mm, String path) {
        Object apRaw = mm.get("accessPattern");
        MemoryBoundWorkload.AccessPattern pattern = apRaw == null
                ? MemoryWorkloadConfig.DEFAULT_PATTERN
                : MemoryBoundWorkload.parsePattern(String.valueOf(apRaw));
        Object bmRaw = mm.get("bufferMB");
        int bufferMB = bmRaw == null
                ? MemoryWorkloadConfig.DEFAULT_BUFFER_MB
                : Integer.parseInt(String.valueOf(bmRaw));
        Object wbRaw = mm.get("writeBack");
        boolean writeBack = wbRaw == null
                ? MemoryWorkloadConfig.DEFAULT_WRITE_BACK
                : Boolean.parseBoolean(String.valueOf(wbRaw));
        return new MemoryWorkloadConfig(pattern, bufferMB, writeBack);
    }

    public String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("name=").append(displayName())
          .append(", kind=").append(kind.name())
          .append(", targetMillis=").append(targetMillis)
          .append(", ratio=").append(String.format("%.4f", ratio));
        if (kind == WorkloadKind.CPU && cpuIterations > 0) {
            sb.append(", cpuIterations=").append(cpuIterations);
        }
        if (kind == WorkloadKind.MEMORY) {
            sb.append(", ").append(memoryOrDefaults().summary());
        }
        return sb.toString();
    }
}
