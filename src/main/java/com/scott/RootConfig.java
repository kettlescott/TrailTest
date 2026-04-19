package com.scott;

import java.util.List;
import java.util.Map;

public record RootConfig(
        GlobalConfig global,
        Map<String, WorkloadConfig> workloads,
        ProfilingConfig profiling,
        List<RunConfig> runs
) {
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

        if (runs == null || runs.isEmpty()) {
            throw new IllegalArgumentException("runs section is required and must not be empty");
        }

        for (RunConfig run : runs) {
            run.validate();
            if (!workloads.containsKey(run.workload())) {
                throw new IllegalArgumentException("Run '" + run.name() + "' references unknown workload '" + run.workload() + "'");
            }
        }
    }
}

