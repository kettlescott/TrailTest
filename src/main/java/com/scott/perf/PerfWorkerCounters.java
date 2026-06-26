package com.scott.perf;

/**
 * Per-worker container for the perf fds opened by
 * {@link PerfBridge#openForCurrentThread(boolean[])}. Single-threaded
 * (no synchronization).
 */
public final class PerfWorkerCounters {

    /** Per-counter fd array; entries are -1 for slots that were not
     *  requested or whose perf_event_open failed. */
    public final int[] fds;

    /** Number of slots in {@code fds} that successfully opened. */
    public final int openedCount;

    /** True iff at least one counter opened successfully. */
    public final boolean anyAvailable;

    private PerfWorkerCounters(int[] fds) {
        this.fds = fds;
        int n = 0;
        if (fds != null) {
            for (int fd : fds) if (fd >= 0) n++;
        }
        this.openedCount = n;
        this.anyAvailable = n > 0;
    }

    /**
     * Open the requested subset of counters for the calling thread.
     * Always returns a non-null instance; check {@link #anyAvailable}.
     * Pass {@code null} for {@code enable} to open the full set.
     */
    public static PerfWorkerCounters openForCurrentThread(boolean[] enable) {
        int[] fds = PerfBridge.openForCurrentThread(enable);
        return new PerfWorkerCounters(fds);
    }

    /**
     * Read raw counter triples for all opened slots. Each output
     * array must be at least {@link PerfBridge#N_COUNTERS} long;
     * unavailable slots receive 0/0/0. Returns the number of fds
     * whose read produced 24 bytes.
     */
    public int read(long[] values, long[] enabled, long[] running) {
        return PerfBridge.readAllRaw(fds, values, enabled, running);
    }

    public void close() {
        PerfBridge.closeAll(fds);
    }
}

