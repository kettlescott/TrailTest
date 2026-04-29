package com.scott;

/**
 * A synthetic, deterministic memory-bound workload.
 *
 * <p>Walks a shared {@code long[]} buffer either sequentially or in a
 * deterministic pseudo-random order so that cache / TLB behaviour dominates
 * the runtime. Per-task determinism comes from {@code seed}; the buffer
 * itself is allocated once (per component) by {@link TaskGenerator} and
 * shared across tasks to keep per-task allocation cost near zero.
 *
 * <h3>Read-only by default</h3>
 * <p>By default the workload only <em>reads</em> the shared buffer.
 * Writing back to a shared buffer from many concurrent worker threads
 * causes false sharing and write-back traffic that confounds queue /
 * dispatcher measurements (and is a different experimental variable
 * altogether). Pass {@code writeBack=true} to opt-in to the
 * shared-write variant for a future "memory contention" experiment.
 *
 * <p>The computed value is returned so the JIT cannot eliminate the loop.
 */
public final class MemoryBoundWorkload implements Workload {

    public enum AccessPattern { SEQUENTIAL, RANDOM }

    private final long[] buffer;
    private final int steps;
    private final AccessPattern pattern;
    private final long seed;
    private final boolean writeBack;

    /** Read-only by default. */
    public MemoryBoundWorkload(long[] buffer, int steps, AccessPattern pattern, long seed) {
        this(buffer, steps, pattern, seed, false);
    }

    public MemoryBoundWorkload(long[] buffer, int steps, AccessPattern pattern, long seed, boolean writeBack) {
        this.buffer = buffer;
        this.steps = steps;
        this.pattern = pattern;
        this.seed = seed;
        this.writeBack = writeBack;
    }

    @Override
    public long execute() {
        final long[] buf = buffer;
        final int n = buf.length;
        final boolean wb = writeBack;
        long x = seed;
        if (pattern == AccessPattern.SEQUENTIAL) {
            int idx = (int) Long.remainderUnsigned(seed, n);
            for (int i = 0; i < steps; i++) {
                x += buf[idx];
                if (wb) buf[idx] = x;
                idx++;
                if (idx >= n) idx = 0;
            }
        } else {
            for (int i = 0; i < steps; i++) {
                x ^= (x << 13);
                x ^= (x >>> 7);
                x ^= (x << 17);
                int idx = (int) ((x >>> 1) % n);
                x += buf[idx];
                if (wb) buf[idx] = x;
            }
        }
        return x;
    }

    public static AccessPattern parsePattern(String raw) {
        if (raw == null) return AccessPattern.SEQUENTIAL;
        if (raw.equalsIgnoreCase("sequential")) return AccessPattern.SEQUENTIAL;
        if (raw.equalsIgnoreCase("random"))     return AccessPattern.RANDOM;
        throw new IllegalArgumentException("Unknown accessPattern: " + raw);
    }
}
