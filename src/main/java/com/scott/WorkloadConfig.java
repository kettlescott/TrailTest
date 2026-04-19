package com.scott;

import java.util.Map;

public record WorkloadConfig(
        String kind,
        String type,
        Map<String, Integer> distribution,
        String generation
) {
    public boolean isSingle() {
        return "single".equalsIgnoreCase(kind);
    }

    public boolean isMix() {
        return "mix".equalsIgnoreCase(kind);
    }

    public void validate(String name) {
        if (kind == null || kind.isBlank()) {
            throw new IllegalArgumentException("workloads." + name + ".kind is required");
        }
        if (isSingle()) {
            TaskType.fromLabel(type);
            return;
        }
        if (isMix()) {
            if (distribution == null || distribution.isEmpty()) {
                throw new IllegalArgumentException("workloads." + name + ".distribution is required for mix");
            }
            int shortPct = valueFor("short");
            int mediumPct = valueFor("medium");
            int longPct = valueFor("long");
            if (shortPct + mediumPct + longPct != 100) {
                throw new IllegalArgumentException("workloads." + name + ".distribution must sum to 100");
            }
            if (generation != null && !generation.equalsIgnoreCase("shuffled")) {
                throw new IllegalArgumentException("workloads." + name + ".generation only supports 'shuffled'");
            }
            return;
        }
        throw new IllegalArgumentException("workloads." + name + ".kind must be single|mix");
    }

    public int valueFor(String key) {
        Integer value = distribution == null ? null : distribution.get(key);
        if (value == null || value < 0) {
            throw new IllegalArgumentException("Invalid distribution value for '" + key + "'");
        }
        return value;
    }
}

