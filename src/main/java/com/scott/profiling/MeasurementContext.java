package com.scott.profiling;

/**
 * Immutable context describing the measurement window. Passed to
 * {@link ProfilingSession#markMeasurementStart(MeasurementContext)} /
 * {@link ProfilingSession#markMeasurementStop(MeasurementContext)} so the
 * JFR anchor events can carry identifying metadata without the session
 * needing to know about the rest of the benchmark model.
 */
public record MeasurementContext(
        String runId,
        String benchmarkMode,
        String workloadType,
        int    workerCount,
        String note
) {
    public MeasurementContext {
        if (runId == null)        runId = "";
        if (benchmarkMode == null) benchmarkMode = "";
        if (workloadType == null)  workloadType  = "";
        if (note == null)          note = "";
    }
}


