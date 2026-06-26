package com.scott;

import java.util.List;
import java.util.Map;

public record RootConfig(
        GlobalConfig global,
        Map<String, WorkloadConfig> workloads,
        ProfilingConfig profiling,
        HybridConfig hybrid,
        DiagnosticsConfig diagnostics,
        AttributionConfig attribution,
        List<RunConfig> runs
) {
    /** Returns diagnostics config, never null (disabled when YAML omits it). */
    public DiagnosticsConfig diagnosticsOrDisabled() {
        return diagnostics == null ? DiagnosticsConfig.disabled() : diagnostics;
    }
    /** Returns attribution config, never null. */
    public AttributionConfig attributionOrDisabled() {
        return attribution == null ? AttributionConfig.disabled() : attribution;
    }
    public void validate() {
        if (global == null) {
            throw new IllegalArgumentException("global section is required");
        }
        global.validate();

        if (workloads == null || workloads.isEmpty()) {
            throw new IllegalArgumentException("workloads section is required and must not be empty");
        }
        workloads.forEach((name, cfg) -> cfg.validate(name));

        if (profiling != null) {
            profiling.validate();
        }

        if (hybrid != null) {
            hybrid.validate();
        }

        if (runs == null || runs.isEmpty()) {
            throw new IllegalArgumentException("runs section is required and must not be empty");
        }

        for (RunConfig run : runs) {
            run.validate();
            if (!workloads.containsKey(run.workload())) {
                throw new IllegalArgumentException("Run '" + run.name() + "' references unknown workload '" + run.workload() + "'");
            }
            // Hybrid runs MUST have an effective HybridConfig (either per-run
            // override or the top-level one). No implicit defaults.
            if (BenchmarkMode.fromConfigValue(run.mode()) == BenchmarkMode.HYBRID) {
                HybridConfig effective = run.hybrid() != null ? run.hybrid() : hybrid;
                if (effective == null) {
                    throw new IllegalArgumentException(
                            "Run '" + run.name() + "' uses mode=hybrid but no hybrid config was provided. "
                                    + "Define a top-level 'hybrid:' section, or a per-run 'hybrid:' override, "
                                    + "with sharedWorkers, shardedWorkers, and an explicit routing for "
                                    + "every WorkloadKind (CPU, MEMORY, IO).");
                }
                effective.validate();
            }
        }
    }
}
