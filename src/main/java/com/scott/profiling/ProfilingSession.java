package com.scott.profiling;

/**
 * Orchestrates the lifecycle of all profilers for a single benchmark run.
 *
 * <p>Intended call sequence from the benchmark driver:
 * <pre>
 *   session.start();                    // after warmup
 *   session.beforeMeasurement();        // quiet period; lets perf/JFR settle
 *   session.markMeasurementStart(ctx);  // JFR anchor #1
 *   ... runMeasurement() ...
 *   session.markMeasurementStop(ctx);   // JFR anchor #2
 *   session.stop();                     // flush all outputs
 * </pre>
 *
 * <p>No profiling-specific logic should leak outside this class: the
 * benchmark hot path never calls into this session.
 */
public final class ProfilingSession {

    private final Profiler profiler;                 // composite or single
    private final long startupQuietPeriodMs;
    private final long shutdownFlushMs;

    private boolean started;
    private boolean stopped;

    public ProfilingSession(Profiler profiler,
                            long startupQuietPeriodMs,
                            long shutdownFlushMs) {
        this.profiler             = profiler;
        this.startupQuietPeriodMs = Math.max(0, startupQuietPeriodMs);
        this.shutdownFlushMs      = Math.max(0, shutdownFlushMs);
    }

    /** No-op session: profiling disabled. */
    public static ProfilingSession disabled() {
        return new ProfilingSession(new Profiler() {
            @Override public String name() { return "disabled"; }
            @Override public void start() {}
            @Override public void stop()  {}
        }, 0, 0);
    }

    public Profiler profiler() { return profiler; }

    public void start() throws Exception {
        if (started) return;
        profiler.start();
        started = true;
    }

    /**
     * Wait long enough for profiler startup noise (perf kernel event
     * install, JFR first-chunk flush) to settle <b>before</b> the
     * measurement window opens. Called on the benchmark thread, never
     * inside the hot path.
     */
    public void beforeMeasurement() throws InterruptedException {
        if (!started || startupQuietPeriodMs == 0) return;
        Thread.sleep(startupQuietPeriodMs);
    }

    /** Emit the JFR measurement-start anchor. */
    public void markMeasurementStart(MeasurementContext ctx) {
        emit("start", ctx);
    }

    /** Emit the JFR measurement-stop anchor. */
    public void markMeasurementStop(MeasurementContext ctx) {
        emit("stop", ctx);
    }

    public void stop() throws Exception {
        if (!started || stopped) return;
        try {
            if (shutdownFlushMs > 0) {
                // Give perf's kernel ring buffer a last-chance moment to
                // drain user-space writes before SIGINT-flushing.
                try { Thread.sleep(shutdownFlushMs); }
                catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
            }
            profiler.stop();
        } finally {
            stopped = true;
        }
    }

    private static void emit(String phase, MeasurementContext ctx) {
        MeasurementAnchorEvent ev = new MeasurementAnchorEvent();
        ev.phase         = phase;
        ev.runId         = ctx.runId();
        ev.benchmarkMode = ctx.benchmarkMode();
        ev.workloadType  = ctx.workloadType();
        ev.workerCount   = ctx.workerCount();
        ev.note          = ctx.note();
        ev.nanoTime      = System.nanoTime();
        ev.epochMillis   = System.currentTimeMillis();
        ev.commit();
    }
}

