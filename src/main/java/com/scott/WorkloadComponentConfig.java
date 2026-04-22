package com.scott;

import java.util.Map;

/**
 * One weighted component of a {@code mix} workload. Each component has its
 * own resource type and profile; components are selected per-task by weight.
 *
 * <p>Weights across a workload's components must sum to 100.
 */
public record WorkloadComponentConfig(
        String name,
        int weight,
        String resource,
        WorkloadProfile profile
) {

    public WorkloadResourceType resourceType() {
        return WorkloadResourceType.fromLabel(resource);
    }

    public void validate(String path) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException(path + ".name is required");
        }
        if (weight <= 0 || weight > 100) {
            throw new IllegalArgumentException(
                    path + ".weight must be in (0, 100], got " + weight);
        }
        WorkloadResourceType rt = resourceType();
        if (rt == WorkloadResourceType.MIXED) {
            throw new IllegalArgumentException(
                    path + ".resource cannot be 'mixed' (no nested mixes)");
        }
        if (profile == null) {
            throw new IllegalArgumentException(path + ".profile is required");
        }
        profile.validateFor(rt, path);
    }

    @SuppressWarnings("unchecked")
    public static WorkloadComponentConfig fromMap(Object raw, String path) {
        if (!(raw instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException(path + " must be a map");
        }
        Map<String, Object> cm = (Map<String, Object>) m;
        String name     = cm.get("name")     == null ? null : String.valueOf(cm.get("name"));
        int    weight   = cm.get("weight")   == null ? 0    : Integer.parseInt(String.valueOf(cm.get("weight")));
        String resource = cm.get("resource") == null ? null : String.valueOf(cm.get("resource"));
        WorkloadProfile profile = WorkloadProfile.fromMap(cm.get("profile"));
        return new WorkloadComponentConfig(name, weight, resource, profile);
    }
}

