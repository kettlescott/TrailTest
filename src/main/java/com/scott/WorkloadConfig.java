package com.scott;

import java.util.List;

/**
 * YAML-driven workload definition.
 *
 * <p>Two shapes are supported:
 * <ul>
 *   <li><b>single</b> — {@code resource} is cpu|io|memory,
 *       {@code profile} carries resource-specific parameters.</li>
 *   <li><b>mix</b>    — {@code resource} is {@code mixed}, and
 *       {@code components} is a list of weighted single-resource
 *       sub-workloads whose weights sum to 100.</li>
 * </ul>
 */
public record WorkloadConfig(
        String resource,
        String mode,
        String generation,
        WorkloadProfile profile,
        List<WorkloadComponentConfig> components
) {

    public boolean isSingle() { return "single".equalsIgnoreCase(mode); }
    public boolean isMix()    { return "mix".equalsIgnoreCase(mode); }

    public WorkloadResourceType resourceType() {
        return WorkloadResourceType.fromLabel(resource);
    }

    public void validate(String name) {
        final String path = "workloads." + name;

        if (mode == null || mode.isBlank()) {
            throw new IllegalArgumentException(path + ".mode is required (single|mix)");
        }
        if (!isSingle() && !isMix()) {
            throw new IllegalArgumentException(
                    path + ".mode must be 'single' or 'mix', got '" + mode + "'");
        }

        WorkloadResourceType rt = resourceType();

        if (isSingle()) {
            if (rt == WorkloadResourceType.MIXED) {
                throw new IllegalArgumentException(
                        path + ": resource='mixed' requires mode='mix'");
            }
            // EMPTY workloads do not require a profile; any provided profile
            // is still validated (e.g. optional taskType override).
            if (profile == null) {
                if (rt != WorkloadResourceType.EMPTY) {
                    throw new IllegalArgumentException(path + ".profile is required for single workloads");
                }
            } else {
                profile.validateFor(rt, path);
            }
            if (components != null && !components.isEmpty()) {
                throw new IllegalArgumentException(
                        path + ".components must be empty for single workloads");
            }
            return;
        }

        // mix
        if (rt != WorkloadResourceType.MIXED) {
            throw new IllegalArgumentException(
                    path + ": mode='mix' requires resource='mixed', got '" + resource + "'");
        }
        if (components == null || components.isEmpty()) {
            throw new IllegalArgumentException(path + ".components is required for mix workloads");
        }
        if (profile != null) {
            throw new IllegalArgumentException(
                    path + ".profile must not be set for mix workloads (use components[].profile)");
        }
        if (generation == null || generation.isBlank()) {
            throw new IllegalArgumentException(
                    path + ".generation is required for mix workloads (currently only 'shuffled')");
        }
        if (!"shuffled".equalsIgnoreCase(generation)) {
            throw new IllegalArgumentException(
                    path + ".generation currently only supports 'shuffled', got '" + generation + "'");
        }

        int weightSum = 0;
        for (int i = 0; i < components.size(); i++) {
            WorkloadComponentConfig c = components.get(i);
            c.validate(path + ".components[" + i + "]");
            weightSum += c.weight();
        }
        if (weightSum != 100) {
            throw new IllegalArgumentException(
                    path + ".components[].weight must sum to 100, got " + weightSum);
        }
    }
}

