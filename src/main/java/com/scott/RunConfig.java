package com.scott;

public record RunConfig(
        String name,
        String mode,
        String workload,
        HybridConfig hybrid,
        PinningConfig pinning,
        boolean enabled
) {
    /** Convenience constructor: no per-run hybrid override and no pinning. Enabled by default. */
    public RunConfig(String name, String mode, String workload) {
        this(name, mode, workload, null, null, true);
    }

    /** Convenience constructor: per-run hybrid override, no pinning. Enabled by default. */
    public RunConfig(String name, String mode, String workload, HybridConfig hybrid) {
        this(name, mode, workload, hybrid, null, true);
    }

    /** Convenience constructor: hybrid + pinning, enabled by default. */
    public RunConfig(String name, String mode, String workload, HybridConfig hybrid, PinningConfig pinning) {
        this(name, mode, workload, hybrid, pinning, true);
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
