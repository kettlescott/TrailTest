package com.scott.profiling;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * A {@link Profiler} that composes an ordered list of children.
 *
 * <p><b>Start semantics:</b> children are started in insertion order. If
 * any child fails, already-started children are stopped in <em>reverse</em>
 * order, their failures are suppressed onto the original exception, and the
 * exception is rethrown so the caller never observes a half-started
 * session.
 *
 * <p><b>Stop semantics:</b> children are stopped in reverse insertion
 * order (perf child typically stopped before JFR so its timeline brackets
 * the JFR window). Stop continues through individual failures; the first
 * exception is rethrown with the rest attached as suppressed so no data
 * is silently dropped.
 */
public final class CompositeProfiler implements Profiler {

    private final List<Profiler> children;
    private final List<Profiler> started = new ArrayList<>();

    public CompositeProfiler(List<Profiler> children) {
        this.children = List.copyOf(children);
    }

    @Override public String name() { return "composite"; }

    public List<Profiler> children() { return children; }

    @Override
    public void start() throws Exception {
        for (Profiler p : children) {
            try {
                p.start();
                started.add(p);
            } catch (Exception primary) {
                // Roll back in reverse order.
                List<Profiler> toStop = new ArrayList<>(started);
                Collections.reverse(toStop);
                for (Profiler s : toStop) {
                    try { s.stop(); } catch (Exception cleanup) { primary.addSuppressed(cleanup); }
                }
                started.clear();
                throw new RuntimeException(
                        "Profiler '" + p.name() + "' failed to start; rolled back "
                                + toStop.size() + " already-started profiler(s).", primary);
            }
        }
    }

    @Override
    public void stop() throws Exception {
        if (started.isEmpty()) return;
        List<Profiler> toStop = new ArrayList<>(started);
        Collections.reverse(toStop);
        started.clear();

        Exception first = null;
        for (Profiler s : toStop) {
            try {
                s.stop();
            } catch (Exception e) {
                if (first == null) first = new RuntimeException(
                        "Profiler '" + s.name() + "' failed to stop.", e);
                else first.addSuppressed(e);
            }
        }
        if (first != null) throw first;
    }
}


