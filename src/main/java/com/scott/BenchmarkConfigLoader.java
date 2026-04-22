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
            String name = entry.getKey();
            if (!(entry.getValue() instanceof Map<?, ?> workloadMap)) {
                throw new IllegalArgumentException("workloads." + name + " must be a map");
            }
            Map<String, Object> wm = (Map<String, Object>) workloadMap;

            // --- migration guard: reject the pre-refactor schema with an
            //     explicit, actionable error message ---
            if (wm.containsKey("kind") || wm.containsKey("type") || wm.containsKey("distribution")) {
                throw new IllegalArgumentException(
                        "workloads." + name + ": legacy fields 'kind'/'type'/'distribution' are no "
                        + "longer supported. Use the new schema: resource=cpu|io|memory|mixed, "
                        + "mode=single|mix, profile:{...}, and (for mix) components:[...]. "
                        + "See README / ARCHITECTURE for examples.");
            }

            String resource   = strVal(wm, "resource",   null);
            String mode       = strVal(wm, "mode",       null);
            String generation = strVal(wm, "generation", null);
            WorkloadProfile profile = wm.containsKey("profile")
                    ? WorkloadProfile.fromMap(wm.get("profile"))
                    : null;

            List<WorkloadComponentConfig> components = null;
            Object rawComps = wm.get("components");
            if (rawComps != null) {
                if (!(rawComps instanceof List<?> compList)) {
                    throw new IllegalArgumentException(
                            "workloads." + name + ".components must be a list");
                }
                components = new ArrayList<>();
                for (int i = 0; i < compList.size(); i++) {
                    components.add(WorkloadComponentConfig.fromMap(
                            compList.get(i),
                            "workloads." + name + ".components[" + i + "]"));
                }
            }

            workloads.put(name, new WorkloadConfig(resource, mode, generation, profile, components));
        }
        return workloads;
    }

    private static ProfilingConfig parseProfiling(Map<String, Object> map) {
        if (map == null) {
            return new ProfilingConfig(false, "cli", "profile", "beforeMeasurement", "afterMeasurement",
                    "${runName}.jfr", null, null, 200L, 100L, null, null);
        }
        boolean enabled = boolVal(map, "enabled", false);
        String control = strVal(map, "control", "cli");
        String settings = strVal(map, "settings", "profile");
        String start = strVal(map, "start", "beforeMeasurement");
        String stop = strVal(map, "stop", "afterMeasurement");
        String filename = strVal(map, "filename", "${runName}.jfr");
        String startCommand = strVal(map, "startCommand", null);
        String stopCommand = strVal(map, "stopCommand", null);
        long startupQuietPeriodMs = longVal(map, "startupQuietPeriodMs", 200L);
        long shutdownFlushMs      = longVal(map, "shutdownFlushMs",      100L);
        PerfConfig perf = parsePerf(map.get("perf"));
        AsyncProfilerConfig async = parseAsyncProfiler(map.get("asyncProfiler"));
        return new ProfilingConfig(enabled, control, settings, start, stop, filename,
                startCommand, stopCommand, startupQuietPeriodMs, shutdownFlushMs, perf, async);
    }

    @SuppressWarnings("unchecked")
    private static AsyncProfilerConfig parseAsyncProfiler(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> am = (Map<String, Object>) m;
        boolean aenabled = boolVal(am, "enabled", false);
        String binary    = strVal(am, "binary",   "asprof");
        String event     = strVal(am, "event",    "wall");
        String interval  = strVal(am, "interval", "1ms");
        String format    = strVal(am, "format",   "jfr");
        String afile     = strVal(am, "filename", null);
        List<String> extra = new ArrayList<>();
        Object ea = am.get("extraArgs");
        if (ea instanceof List<?> ealist) {
            for (Object o : ealist) extra.add(String.valueOf(o));
        }
        return new AsyncProfilerConfig(aenabled, binary, event, interval, format, afile, extra);
    }

    @SuppressWarnings("unchecked")
    private static PerfConfig parsePerf(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) return null;
        Map<String, Object> pm = (Map<String, Object>) m;
        boolean penabled = boolVal(pm, "enabled", false);
        String binary = strVal(pm, "binary", "perf");
        Integer freq = pm.get("frequency") == null ? null : Integer.parseInt(String.valueOf(pm.get("frequency")));
        String clock = strVal(pm, "clock", "monotonic");
        String callGraph = strVal(pm, "callGraph", "fp");
        String pfile = strVal(pm, "filename", "${runName}.perf.data");
        Integer mmapPages = pm.get("mmapPages") == null ? null : Integer.parseInt(String.valueOf(pm.get("mmapPages")));
        List<String> extra = new ArrayList<>();
        Object ea = pm.get("extraArgs");
        if (ea instanceof List<?> ealist) {
            for (Object o : ealist) extra.add(String.valueOf(o));
        }
        return new PerfConfig(penabled, binary, freq, clock, callGraph, extra, pfile, mmapPages);
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

