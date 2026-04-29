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
        MemoryWorkloadConfig memory
) {

    /** Backwards-compatible constructor: no MEMORY config (defaults applied if needed). */
    public WorkloadEntry(String name, WorkloadKind kind, long targetMillis, double ratio) {
        this(name, kind, targetMillis, ratio, null);
    }

    public WorkloadEntry {
        if (kind == null) {
            throw new IllegalArgumentException("workload entry: kind is required");
        }
        if (targetMillis <= 0) {
            throw new IllegalArgumentException("workload entry: targetMillis must be > 0");
        }
        if (ratio <= 0.0 || !Double.isFinite(ratio)) {
            throw new IllegalArgumentException("workload entry: ratio must be > 0");
        }
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
        Object tm = em.get("targetMillis");
        if (tm == null) {
            throw new IllegalArgumentException(path + ".targetMillis is required");
        }
        long targetMillis = Long.parseLong(String.valueOf(tm));
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

        return new WorkloadEntry(name, kind, targetMillis, ratio, memory);
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
        if (kind == WorkloadKind.MEMORY) {
            sb.append(", ").append(memoryOrDefaults().summary());
        }
        return sb.toString();
    }
}
