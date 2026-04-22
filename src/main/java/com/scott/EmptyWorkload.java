package com.scott;

/**
 * No-op baseline workload.
 *
 * <p>Used as a control in benchmark runs: it traverses the full
 * {@code TaskGenerator → Dispatcher → Executor → Task.run() → callback}
 * path but performs essentially no payload work.  The difference between
 * this workload's end-to-end latency and any other workload's end-to-end
 * latency therefore isolates the cost of the payload itself (CPU mix,
 * IO park, memory walk) from the fixed-cost framework overhead
 * (dispatch, queue, wakeup, timing).
 *
 * <p>The single ALU op plus return prevents the JIT from eliding the
 * call entirely — {@link Workload#execute()} still returns a value that
 * depends on a field, so dead-code elimination cannot drop the call
 * site.  No allocation, no blocking, no shared state.
 */
public final class EmptyWorkload implements Workload {

    /** Singleton — the workload is stateless. */
    public static final EmptyWorkload INSTANCE = new EmptyWorkload();

    private final long tag;

    public EmptyWorkload() {
        this(0L);
    }

    public EmptyWorkload(long tag) {
        this.tag = tag;
    }

    @Override
    public long execute() {
        // One cheap data-dependent op so the JIT keeps the call.
        return tag ^ 0x9E3779B97F4A7C15L;
    }

    public long tag() { return tag; }
}

