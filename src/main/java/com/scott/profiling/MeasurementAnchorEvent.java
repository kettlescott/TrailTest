package com.scott.profiling;

import jdk.jfr.Category;
import jdk.jfr.Description;
import jdk.jfr.Event;
import jdk.jfr.Label;
import jdk.jfr.Name;
import jdk.jfr.StackTrace;

/**
 * JFR fiducial emitted exactly once at the start and once at the end of the
 * measurement window. Used to align JFR events with an external perf
 * timeline (both clocks are {@code CLOCK_MONOTONIC} /
 * {@link System#nanoTime()}).
 *
 * <p>Not emitted per task — this is a lifecycle-boundary event only.
 * Fields are intentionally flat and cheap to record.
 */
@Name("com.scott.MeasurementAnchor")
@Label("Measurement Anchor")
@Category({"Benchmark", "TrailTest"})
@Description("Marks the exact nanoTime at which the measurement window opens or closes.")
@StackTrace(false)
public final class MeasurementAnchorEvent extends Event {
    @Label("Phase")          public String phase;       // "start" | "stop"
    @Label("Run ID")         public String runId;
    @Label("Benchmark Mode") public String benchmarkMode;
    @Label("Workload Type")  public String workloadType;
    @Label("Worker Count")   public int    workerCount;
    @Label("Note")           public String note;
    @Label("nanoTime")       public long   nanoTime;
    @Label("epochMillis")    public long   epochMillis;
}

