package com.scott;

public record ProfilingConfig(
        boolean enabled,
        String control,
        String settings,
        String start,
        String stop,
        String filename,
        String startCommand,
        String stopCommand
) {
    public void validate() {
        if (!enabled) {
            return;
        }
        if (control == null || control.isBlank()) {
            throw new IllegalArgumentException("profiling.control is required when profiling is enabled");
        }
        if (!"cli".equalsIgnoreCase(control)) {
            throw new IllegalArgumentException("profiling.control currently supports only 'cli'");
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
    }
}

