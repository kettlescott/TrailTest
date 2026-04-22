package com.scott;

public record ProfilingConfig(
        boolean enabled,
        String control,
        String settings,
        String start,
        String stop,
        String filename,
        String startCommand,
        String stopCommand,
        long startupQuietPeriodMs,
        long shutdownFlushMs,
        PerfConfig perf,
        AsyncProfilerConfig asyncProfiler
) {
    /** Back-compat constructor used by older call sites (no quiet/flush, no async). */
    public ProfilingConfig(boolean enabled,
                           String control,
                           String settings,
                           String start,
                           String stop,
                           String filename,
                           String startCommand,
                           String stopCommand,
                           PerfConfig perf) {
        this(enabled, control, settings, start, stop, filename,
             startCommand, stopCommand, 200L, 100L, perf, null);
    }

    /** Back-compat constructor (pre-async-profiler callers). */
    public ProfilingConfig(boolean enabled,
                           String control,
                           String settings,
                           String start,
                           String stop,
                           String filename,
                           String startCommand,
                           String stopCommand,
                           long startupQuietPeriodMs,
                           long shutdownFlushMs,
                           PerfConfig perf) {
        this(enabled, control, settings, start, stop, filename,
             startCommand, stopCommand, startupQuietPeriodMs, shutdownFlushMs, perf, null);
    }

    public void validate() {
        if (!enabled) {
            return;
        }
        if (control == null || control.isBlank()) {
            throw new IllegalArgumentException("profiling.control is required when profiling is enabled");
        }
        if (!"cli".equalsIgnoreCase(control) && !"api".equalsIgnoreCase(control)) {
            throw new IllegalArgumentException("profiling.control must be 'cli' or 'api'");
        }
        if (settings == null || settings.isBlank()) {
            throw new IllegalArgumentException("profiling.settings is required when profiling is enabled");
        }
        if (start == null || start.isBlank()) {
            throw new IllegalArgumentException("profiling.start is required when profiling is enabled");
        }
        if (stop == null || stop.isBlank()) {
            throw new IllegalArgumentException("profiling.stop is required when profiling is enabled");
        }
        if (!"beforeMeasurement".equalsIgnoreCase(start)) {
            throw new IllegalArgumentException("profiling.start currently supports only 'beforeMeasurement'");
        }
        if (!"afterMeasurement".equalsIgnoreCase(stop)) {
            throw new IllegalArgumentException("profiling.stop currently supports only 'afterMeasurement'");
        }
        if (filename == null || filename.isBlank()) {
            throw new IllegalArgumentException("profiling.filename is required when profiling is enabled");
        }
        if (startCommand != null && startCommand.isBlank()) {
            throw new IllegalArgumentException("profiling.startCommand must not be blank when provided");
        }
        if (stopCommand != null && stopCommand.isBlank()) {
            throw new IllegalArgumentException("profiling.stopCommand must not be blank when provided");
        }
        if (startupQuietPeriodMs < 0) {
            throw new IllegalArgumentException("profiling.startupQuietPeriodMs must be >= 0");
        }
        if (shutdownFlushMs < 0) {
            throw new IllegalArgumentException("profiling.shutdownFlushMs must be >= 0");
        }
        if (perf != null) {
            perf.validate();
        }
        if (asyncProfiler != null) {
            asyncProfiler.validate();
        }
    }
}
