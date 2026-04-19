package com.scott;

public record RunConfig(
        String name,
        String mode,
        String workload
) {
    public void validate() {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("runs[].name is required");
        }
        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException("runs[" + name + "].mode is required");
        }
        if (workload == null || workload.isBlank()) {
            throw new IllegalArgumentException("runs[" + name + "].workload is required");
        }
        BenchmarkMode parsed = BenchmarkMode.fromConfigValue(mode);
        if (parsed != BenchmarkMode.SHARED && parsed != BenchmarkMode.SHARDED) {
            throw new IllegalArgumentException(
                    "runs[" + name + "].mode must be 'shared' or 'sharded', got '" + mode + "'");
        }
    }
}

