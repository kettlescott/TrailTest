package com.scott;

/**
 * Duration-controlled memory-bound workload.
 *
 * <p>Runs memory operations for a target duration rather than a fixed step count.
 * Performs batches of accesses and checks elapsed time between batches to minimize
 * timing overhead (avoid nanoTime call per access).
 *
 * <p>Preserves all the behavioural properties of {@link MemoryBoundWorkload}:
 * - SEQUENTIAL or RANDOM access patterns
 * - Shared read-only buffer (or read-modify-write with writeBack=true)
 * - Deterministic pseudo-random seeding
 * - JIT-defeating blackhole sink
 *
 * <h3>Batch timing</h3>
 * The workload performs {@code batchSize} operations, then checks if the target
 * duration has been reached. This amortises the cost of {@link System#nanoTime()}.
 * The actual execution time will exceed the target by at most one batch.
 */
public final class MemoryBoundWorkloadDuration implements Workload {

    private final long[] buffer;
    private final MemoryBoundWorkload.AccessPattern pattern;
    private final long seed;
    private final boolean writeBack;
    private final long targetNanos;
    private final int batchSize;

    /**
     * Creates a duration-controlled memory workload.
     *
     * @param buffer        shared long[] buffer (read-only by default)
     * @param targetMicros  target execution time in microseconds
     * @param pattern       SEQUENTIAL or RANDOM access pattern
     * @param seed          per-task deterministic seed
     * @param writeBack     when true, write back to buffer; when false, read-only
     * @param batchSize     number of accesses per batch before checking elapsed time
     */
    public MemoryBoundWorkloadDuration(long[] buffer,
                                       long targetMicros,
                                       MemoryBoundWorkload.AccessPattern pattern,
                                       long seed,
                                       boolean writeBack,
                                       int batchSize) {
        this.buffer = buffer;
        this.pattern = pattern;
        this.seed = seed;
        this.writeBack = writeBack;
        this.targetNanos = targetMicros * 1_000L;
        this.batchSize = Math.max(1, batchSize);
    }

    @Override
    public long execute() {
        final long[] buf = buffer;
        final int n = buf.length;
        final boolean wb = writeBack;
        final int bs = batchSize;
        long x = seed;
        long totalOps = 0L;

        long start = System.nanoTime();
        long elapsed = 0L;

        if (pattern == MemoryBoundWorkload.AccessPattern.SEQUENTIAL) {
            int idx = (int) Long.remainderUnsigned(seed, n);
            while (elapsed < targetNanos) {
                // Perform one batch
                for (int i = 0; i < bs; i++) {
                    x += buf[idx];
                    if (wb) buf[idx] = x;
                    idx++;
                    if (idx >= n) idx = 0;
                }
                totalOps += bs;
                elapsed = System.nanoTime() - start;
            }
        } else {
            // RANDOM pattern
            while (elapsed < targetNanos) {
                // Perform one batch
                for (int i = 0; i < bs; i++) {
                    x ^= (x << 13);
                    x ^= (x >>> 7);
                    x ^= (x << 17);
                    int idx = (int) ((x >>> 1) % n);
                    x += buf[idx];
                    if (wb) buf[idx] = x;
                }
                totalOps += bs;
                elapsed = System.nanoTime() - start;
            }
        }

        // Store memory operations count in a thread-local for diagnostics
        // (if needed by enhanced TaskGenerator)
        memoryOperationsCounts.set(totalOps);

        // Blackhole sink (same as MemoryBoundWorkload)
        // Use the same blackhole strategy as MemoryBoundWorkload for consistency
        long[] tlSink = TL_SINK.get();
        tlSink[0] = x;  // Always use thread-local to avoid cross-core invalidation
        return x;
    }

    // Diagnostic support: track memory operations per task
    private static final ThreadLocal<Long> memoryOperationsCounts =
            ThreadLocal.withInitial(() -> 0L);

    /**
     * Returns the memory operation count from the last executed workload on
     * this thread. Diagnostics use only. Calling on a thread that hasn't
     * run this workload returns 0.
     */
    public static long lastMemoryOperations() {
        return memoryOperationsCounts.get();
    }

    /**
     * Volatile blackhole sink (default mode).
     */
    @SuppressWarnings("unused")
    private static volatile long BLACKHOLE;

    /**
     * Per-thread blackhole sink (alternative mode).
     */
    private static final ThreadLocal<long[]> TL_SINK =
            ThreadLocal.withInitial(() -> new long[16]);
}

