package com.scott;

import java.util.concurrent.locks.LockSupport;

/**
 * A synthetic, deterministic IO-bound workload.
 *
 * <p>This does <em>not</em> perform any real I/O — it simulates blocking
 * latency by parking the calling thread for a configured duration using
 * {@link LockSupport#parkNanos(long)}. This reproducibly exercises the
 * off-CPU / wakeup paths of the executor without introducing filesystem
 * or network variance into benchmark results.
 */
public final class IoBoundWorkload implements Workload {

    private final long waitNanos;
    private final long seed;

    public IoBoundWorkload(long waitNanos, long seed) {
        this.waitNanos = waitNanos;
        this.seed = seed;
    }

    @Override
    public long execute() {
        if (waitNanos > 0L) {
            LockSupport.parkNanos(waitNanos);
        }
        // Return a value the JIT cannot constant-fold away.
        return seed ^ waitNanos;
    }

    public long waitNanos() { return waitNanos; }
    public long seed()      { return seed; }
}

