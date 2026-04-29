package com.scott;

import java.util.concurrent.locks.LockSupport;

/**
 * Synthetic blocking-IO workload: parks the calling thread for
 * {@code targetMillis} milliseconds via {@link LockSupport#parkNanos(long)}.
 *
 * <p>This does not perform real I/O — it deterministically exercises
 * the off-CPU / wakeup paths of the executor without filesystem or
 * network variance. It is named {@code SyntheticBlockingIOWorkload} in
 * paper output to make the synthetic nature explicit.
 */
public final class SyntheticBlockingIOWorkload implements Workload {

    /** Stable label used in summaries and metadata. */
    public static final String LABEL = "SyntheticBlockingIOWorkload";

    private final long targetMillis;
    private final long seed;

    public SyntheticBlockingIOWorkload(long targetMillis, long seed) {
        this.targetMillis = targetMillis;
        this.seed = seed;
    }

    @Override
    public long execute() {
        if (targetMillis > 0L) {
            LockSupport.parkNanos(targetMillis * 1_000_000L);
        }
        // Return a JIT-uncollapsible value.
        return seed ^ targetMillis;
    }

    public long targetMillis() { return targetMillis; }
    public long seed()         { return seed; }
}
