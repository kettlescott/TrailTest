package com.scott;

public record RunConfig(
        String name,
        String mode,
        String workload,
        HybridConfig hybrid
) {
    /** Convenience constructor: no per-run hybrid override. */
    public RunConfig(String name, String mode, String workload) {
        this(name, mode, workload, null);
    }

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
        if (parsed != BenchmarkMode.SHARED
                && parsed != BenchmarkMode.SHARDED
                && parsed != BenchmarkMode.HYBRID) {
            throw new IllegalArgumentException(
                    "runs[" + name + "].mode must be 'shared', 'sharded', or 'hybrid', got '" + mode + "'");
        }
        if (hybrid != null) {
            hybrid.validate();
        }
    }
}
