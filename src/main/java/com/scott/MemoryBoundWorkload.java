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
 * <p>The computed value is returned so the JIT cannot eliminate the loop.
 */
public final class MemoryBoundWorkload implements Workload {

    public enum AccessPattern { SEQUENTIAL, RANDOM }

    private final long[] buffer;
    private final int steps;
    private final AccessPattern pattern;
    private final long seed;

    public MemoryBoundWorkload(long[] buffer, int steps, AccessPattern pattern, long seed) {
        this.buffer = buffer;
        this.steps = steps;
        this.pattern = pattern;
        this.seed = seed;
    }

    @Override
    public long execute() {
        final long[] buf = buffer;
        final int n = buf.length;
        long x = seed;
        if (pattern == AccessPattern.SEQUENTIAL) {
            int idx = (int) Long.remainderUnsigned(seed, n);
            for (int i = 0; i < steps; i++) {
                x += buf[idx];
                buf[idx] = x;
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
                buf[idx] = x;
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

