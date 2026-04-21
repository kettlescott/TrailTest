package com.scott.profiling;

/**
 * A single profiling back-end (JFR, perf, async-profiler, ...).
 *
 * <p>Contract:
 * <ul>
 *   <li>{@link #start()} is called <b>after</b> warmup, <b>before</b> the
 *       measurement window. It MUST fail loudly with a clear exception if
 *       the back-end is misconfigured or unavailable.</li>
 *   <li>{@link #stop()} is called <b>after</b> the measurement window and
 *       <b>before</b> dispatcher shutdown, so measurement cleanup cost is
 *       not attributed to the benchmark. Must be idempotent.</li>
 *   <li>Nothing on the hot path. No logging, no allocation, no I/O between
 *       start and stop from this interface's methods.</li>
 * </ul>
 */
public interface Profiler {

    /** Short, stable name used in error messages and metadata. */
    String name();

    /** Start the back-end. Throws on any failure; caller rolls back. */
    void start() throws Exception;

    /** Stop and flush outputs. Must tolerate repeated calls. */
    void stop() throws Exception;
}


