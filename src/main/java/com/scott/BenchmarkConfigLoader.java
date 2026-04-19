package com.scott;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BenchmarkConfigLoader {

    private BenchmarkConfigLoader() {
    }

    @SuppressWarnings("unchecked")
    public static RootConfig load(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("Config file not found: " + path);
        }

        Yaml yaml = new Yaml();
        try (InputStream in = Files.newInputStream(path)) {
            Object rootObj = yaml.load(in);
            if (!(rootObj instanceof Map<?, ?> rootMap)) {
                throw new IllegalArgumentException("YAML root must be a map/object");
            }

            Map<String, Object> root = (Map<String, Object>) rootMap;

            GlobalConfig global = parseGlobal((Map<String, Object>) root.get("global"));
            Map<String, WorkloadConfig> workloads = parseWorkloads((Map<String, Object>) root.get("workloads"));
            ProfilingConfig profiling = parseProfiling((Map<String, Object>) root.get("profiling"));
            List<RunConfig> runs = parseRuns((List<Object>) root.get("runs"));

            RootConfig config = new RootConfig(global, workloads, profiling, runs);
            config.validate();
            return config;
        }
    }

    private static GlobalConfig parseGlobal(Map<String, Object> map) {
        if (map == null) {
            throw new IllegalArgumentException("global section is required");
        }
        int workerCount = intVal(map, "workerCount", Runtime.getRuntime().availableProcessors());
        int maxInflight = intVal(map, "maxInflight", workerCount * 2);
        long seed = longVal(map, "seed", 0xDEADBEEFL);
        long targetTaskNanos = longVal(map, "targetTaskNanos", 100_000L);
        int warmupSeconds = intVal(map, "warmupSeconds", 3);
        int measurementSeconds = intVal(map, "measurementSeconds", 10);
        int taskCount = intVal(map, "taskCount", 0);
        Integer baseIterations = map.containsKey("baseIterations") ? intVal(map, "baseIterations", 0) : null;
        return new GlobalConfig(
                workerCount,
                maxInflight,
                seed,
                targetTaskNanos,
                warmupSeconds,
                measurementSeconds,
                taskCount,
                baseIterations
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, WorkloadConfig> parseWorkloads(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            throw new IllegalArgumentException("workloads section is required");
        }

        Map<String, WorkloadConfig> workloads = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> workloadMap)) {
                throw new IllegalArgumentException("workloads." + entry.getKey() + " must be a map");
            }
            Map<String, Object> wm = (Map<String, Object>) workloadMap;
            String kind = strVal(wm, "kind", null);
            String type = strVal(wm, "type", null);
            Map<String, Integer> distribution = null;
            if (wm.get("distribution") instanceof Map<?, ?> distMap) {
                distribution = new LinkedHashMap<>();
                for (Map.Entry<?, ?> d : distMap.entrySet()) {
                    distribution.put(String.valueOf(d.getKey()), Integer.parseInt(String.valueOf(d.getValue())));
                }
            }
            String generation = strVal(wm, "generation", "shuffled");
            workloads.put(entry.getKey(), new WorkloadConfig(kind, type, distribution, generation));
        }
        return workloads;
    }

    private static ProfilingConfig parseProfiling(Map<String, Object> map) {
        if (map == null) {
            return new ProfilingConfig(false, "cli", "profile", "beforeMeasurement", "afterMeasurement",
                    "${runName}.jfr", null, null);
        }
        boolean enabled = boolVal(map, "enabled", false);
        String control = strVal(map, "control", "cli");
        String settings = strVal(map, "settings", "profile");
        String start = strVal(map, "start", "beforeMeasurement");
        String stop = strVal(map, "stop", "afterMeasurement");
        String filename = strVal(map, "filename", "${runName}.jfr");
        String startCommand = strVal(map, "startCommand", null);
        String stopCommand = strVal(map, "stopCommand", null);
        return new ProfilingConfig(enabled, control, settings, start, stop, filename, startCommand, stopCommand);
    }

    @SuppressWarnings("unchecked")
    private static List<RunConfig> parseRuns(List<Object> list) {
        if (list == null || list.isEmpty()) {
            throw new IllegalArgumentException("runs section is required");
        }
        List<RunConfig> runs = new ArrayList<>();
        for (Object obj : list) {
            if (!(obj instanceof Map<?, ?> runMap)) {
                throw new IllegalArgumentException("Each runs[] item must be a map");
            }
            Map<String, Object> rm = (Map<String, Object>) runMap;
            runs.add(new RunConfig(
                    strVal(rm, "name", null),
                    strVal(rm, "mode", null),
                    strVal(rm, "workload", null)
            ));
        }
        return runs;
    }

    private static int intVal(Map<String, Object> map, String key, int defaultVal) {
        Object value = map.get(key);
        if (value == null) return defaultVal;
        return Integer.parseInt(String.valueOf(value));
    }

    private static long longVal(Map<String, Object> map, String key, long defaultVal) {
        Object value = map.get(key);
        if (value == null) return defaultVal;
        return Long.parseLong(String.valueOf(value));
    }

    private static boolean boolVal(Map<String, Object> map, String key, boolean defaultVal) {
        Object value = map.get(key);
        if (value == null) return defaultVal;
        return Boolean.parseBoolean(String.valueOf(value));
    }

    private static String strVal(Map<String, Object> map, String key, String defaultVal) {
        Object value = map.get(key);
        return value == null ? defaultVal : String.valueOf(value);
    }
}

