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
            HybridConfig hybrid = parseHybrid((Map<String, Object>) root.get("hybrid"), "hybrid");
            List<RunConfig> runs = parseRuns((List<Object>) root.get("runs"));

            RootConfig config = new RootConfig(global, workloads, profiling, hybrid, runs);
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
        if (map.containsKey("targetTaskNanos")) {
            System.err.println("[yaml] global.targetTaskNanos is deprecated and ignored — "
                    + "task sizing is per-entry via WorkloadEntry.targetMillis.");
        }
        int warmupSeconds = intVal(map, "warmupSeconds", 3);
        int measurementSeconds = intVal(map, "measurementSeconds", 10);
        int taskCount = intVal(map, "taskCount", 0);
        return new GlobalConfig(
                workerCount,
                maxInflight,
                seed,
                warmupSeconds,
                measurementSeconds,
                taskCount
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
            Object value = entry.getValue();
            String path = "workloads." + name;

            List<WorkloadEntry> entries;

            // -- New shape A: list of {kind, targetMillis, ratio, name?} --
            if (value instanceof List<?> list) {
                entries = new ArrayList<>(list.size());
                for (int i = 0; i < list.size(); i++) {
                    entries.add(WorkloadEntry.fromMap(
                            list.get(i), path + "[" + i + "]"));
                }
                entries = normalizeRatios(entries, path);
            }
            // -- Map-shaped: either single-entry shorthand, or legacy --
            else if (value instanceof Map<?, ?> wmRaw) {
                Map<String, Object> wm = (Map<String, Object>) wmRaw;
                entries = parseWorkloadMap(name, wm);
            }
            else {
                throw new IllegalArgumentException(
                        path + " must be a list of entries or a map");
            }

            workloads.put(name, new WorkloadConfig(entries));
        }
        return workloads;
    }

    /**
     * Parses a map-shaped workload value. Supports:
     * <ol>
     *   <li><b>Single-entry shorthand</b>: {@code {kind, targetMillis, ratio?}}.</li>
     *   <li><b>Legacy schema</b>: {@code {resource, mode, profile, components}} or
     *       {@code {kind: single|mix, type|distribution}} — translated with
     *       a {@code [legacy-yaml]} stderr warning.</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private static List<WorkloadEntry> parseWorkloadMap(String name, Map<String, Object> wm) {
        String path = "workloads." + name;

        // --- Shorthand: {kind: CPU|MEMORY|IO, targetMillis: N} ---
        Object kindRaw = wm.get("kind");
        if (kindRaw != null && isResourceKind(String.valueOf(kindRaw))) {
            return List.of(WorkloadEntry.fromMap(wm, path));
        }

        // --- Legacy schema below ---
        // Cases:
        //   (a) kind: single, type: short|medium|long
        //   (b) kind: mix,    distribution: {short:..,medium:..,long:..}
        //   (c) resource: cpu|io|memory|mixed, mode: single|mix, profile/components

        if (kindRaw != null) {
            String kindStr = String.valueOf(kindRaw).trim().toLowerCase();
            if ("single".equals(kindStr)) {
                String type = wm.get("type") == null ? null : String.valueOf(wm.get("type"));
                System.err.printf("[legacy-yaml] workloads.%s: 'kind: single, type: %s' → translating to new schema%n",
                        name, type);
                return List.of(legacyTypeToEntry(type, 1.0));
            }
            if ("mix".equals(kindStr)) {
                Object distRaw = wm.get("distribution");
                if (!(distRaw instanceof Map<?, ?> distMap)) {
                    throw new IllegalArgumentException(
                            path + ": legacy 'kind: mix' requires a 'distribution' map");
                }
                System.err.printf("[legacy-yaml] workloads.%s: 'kind: mix' → translating to new schema%n", name);
                Map<String, Object> dm = (Map<String, Object>) distMap;
                double total = 0.0;
                for (Object v : dm.values()) total += Double.parseDouble(String.valueOf(v));
                if (total <= 0) {
                    throw new IllegalArgumentException(path + ".distribution must sum to > 0");
                }
                List<WorkloadEntry> out = new ArrayList<>(dm.size());
                for (Map.Entry<String, Object> e : dm.entrySet()) {
                    double w = Double.parseDouble(String.valueOf(e.getValue()));
                    if (w <= 0) continue;
                    out.add(legacyTypeToEntry(e.getKey(), w / total));
                }
                return out;
            }
        }

        Object resourceRaw = wm.get("resource");
        if (resourceRaw != null) {
            System.err.printf("[legacy-yaml] workloads.%s: legacy 'resource/mode/profile/components' schema → translating%n",
                    name);
            return legacyResourceSchemaToEntries(name, wm);
        }

        throw new IllegalArgumentException(
                path + ": unrecognized workload shape. Use a list of "
                        + "{kind: CPU|MEMORY|IO, targetMillis: N, ratio: R} entries.");
    }

    private static boolean isResourceKind(String s) {
        if (s == null) return false;
        String v = s.trim().toUpperCase();
        return "CPU".equals(v) || "MEMORY".equals(v) || "IO".equals(v);
    }

    /** Map legacy short/medium/long size labels to new entries. */
    private static WorkloadEntry legacyTypeToEntry(String type, double ratio) {
        if (type == null) {
            throw new IllegalArgumentException("legacy task type is required (short|medium|long)");
        }
        return switch (type.trim().toLowerCase()) {
            case "short"  -> new WorkloadEntry("legacy_short",  WorkloadKind.CPU, 1L,  ratio);
            case "medium" -> new WorkloadEntry("legacy_medium", WorkloadKind.CPU, 5L,  ratio);
            case "long"   -> new WorkloadEntry("legacy_long",   WorkloadKind.IO,  20L, ratio);
            default -> throw new IllegalArgumentException(
                    "unknown legacy task type: '" + type + "' (expected short|medium|long)");
        };
    }

    @SuppressWarnings("unchecked")
    private static List<WorkloadEntry> legacyResourceSchemaToEntries(String name, Map<String, Object> wm) {
        String resource = String.valueOf(wm.get("resource")).trim().toLowerCase();
        String mode = wm.get("mode") == null ? "single" : String.valueOf(wm.get("mode")).trim().toLowerCase();

        if ("mix".equals(mode) || "mixed".equals(resource)) {
            Object compsRaw = wm.get("components");
            if (!(compsRaw instanceof List<?> compList)) {
                throw new IllegalArgumentException(
                        "workloads." + name + ": legacy mix requires components[]");
            }
            int weightSum = 0;
            for (Object o : compList) {
                Map<String, Object> cm = (Map<String, Object>) o;
                weightSum += cm.get("weight") == null ? 0 : Integer.parseInt(String.valueOf(cm.get("weight")));
            }
            if (weightSum <= 0) weightSum = 100;
            List<WorkloadEntry> out = new ArrayList<>(compList.size());
            for (Object o : compList) {
                Map<String, Object> cm = (Map<String, Object>) o;
                String compName = cm.get("name") == null ? null : String.valueOf(cm.get("name"));
                String compRes  = cm.get("resource") == null ? null : String.valueOf(cm.get("resource"));
                int weight      = cm.get("weight") == null ? 0 : Integer.parseInt(String.valueOf(cm.get("weight")));
                double ratio    = (double) weight / weightSum;
                Map<String, Object> profile = cm.get("profile") instanceof Map<?, ?> p
                        ? (Map<String, Object>) p : null;
                out.add(legacyResourceToEntry(compName, compRes, profile, ratio));
            }
            return out;
        }

        Map<String, Object> profile = wm.get("profile") instanceof Map<?, ?> p
                ? (Map<String, Object>) p : null;
        return List.of(legacyResourceToEntry(name, resource, profile, 1.0));
    }

    private static WorkloadEntry legacyResourceToEntry(String name,
                                                      String resource,
                                                      Map<String, Object> profile,
                                                      double ratio) {
        if (resource == null) {
            throw new IllegalArgumentException("legacy entry missing resource");
        }
        String r = resource.trim().toLowerCase();
        return switch (r) {
            case "cpu" -> {
                int mult = profile == null || profile.get("iterationsMultiplier") == null
                        ? 1 : Integer.parseInt(String.valueOf(profile.get("iterationsMultiplier")));
                long targetMs = legacyCpuMultiplierToMillis(mult);
                yield new WorkloadEntry(name, WorkloadKind.CPU, targetMs, ratio);
            }
            case "io" -> {
                long waitNanos = 0L;
                if (profile != null) {
                    if (profile.get("waitNanos")  != null) waitNanos += Long.parseLong(String.valueOf(profile.get("waitNanos")));
                    if (profile.get("waitMicros") != null) waitNanos += Long.parseLong(String.valueOf(profile.get("waitMicros"))) * 1_000L;
                    if (profile.get("waitMillis") != null) waitNanos += Long.parseLong(String.valueOf(profile.get("waitMillis"))) * 1_000_000L;
                }
                long targetMs = Math.max(1L, (waitNanos + 999_999L) / 1_000_000L);
                yield new WorkloadEntry(name, WorkloadKind.IO, targetMs, ratio);
            }
            case "memory" -> {
                int steps = profile == null || profile.get("steps") == null
                        ? 10_000 : Integer.parseInt(String.valueOf(profile.get("steps")));
                String pattern = profile == null || profile.get("accessPattern") == null
                        ? "sequential" : String.valueOf(profile.get("accessPattern"));
                long perStepNs = "random".equalsIgnoreCase(pattern) ? 60L : 2L;
                long targetMs = Math.max(1L, ((long) steps * perStepNs + 999_999L) / 1_000_000L);
                yield new WorkloadEntry(name, WorkloadKind.MEMORY, targetMs, ratio);
            }
            case "empty" -> new WorkloadEntry(name, WorkloadKind.CPU, 1L, ratio);
            default -> throw new IllegalArgumentException(
                    "unknown legacy resource: '" + resource + "' (expected cpu|io|memory|empty)");
        };
    }

    /** SHORT(m=1) → 1ms, MEDIUM(m≈10) → 5ms, LONG(m≈100) → 20ms; lossy. */
    private static long legacyCpuMultiplierToMillis(int mult) {
        if (mult <= 1)   return 1L;
        if (mult <= 5)   return 1L;
        if (mult <= 25)  return 5L;
        if (mult <= 75)  return 10L;
        return 20L;
    }

    /**
     * Normalizes ratios so they sum to 1.0. Accepts either fractional
     * inputs already summing to ~1.0 (within 1e-3) or integer inputs
     * summing to 100 (auto-divided).
     */
    private static List<WorkloadEntry> normalizeRatios(List<WorkloadEntry> in, String path) {
        double sum = 0.0;
        for (WorkloadEntry e : in) sum += e.ratio();
        if (sum <= 0) {
            throw new IllegalArgumentException(path + ": ratios must sum to > 0");
        }
        // Auto-detect integer-percent style.
        boolean rescale = Math.abs(sum - 1.0) > 1e-3;
        if (!rescale) return in;
        List<WorkloadEntry> out = new ArrayList<>(in.size());
        for (WorkloadEntry e : in) {
            // Preserve the optional memory() block — dropping it here
            // would silently revert MEMORY entries to default
            // accessPattern / bufferMB / writeBack whenever ratios
            // needed rescaling. Likewise preserve cpuIterations so
            // fixed-iteration CPU entries are not silently reverted to
            // the calibration path.
            out.add(new WorkloadEntry(
                    e.name(), e.kind(), e.targetMillis(),
                    e.ratio() / sum, e.memory(), e.cpuIterations()));
        }
        return out;
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
        // When true, JFR will record j.u.c lock / monitor events with a
        // 0 ms threshold (jdk.JavaMonitorEnter, jdk.JavaMonitorWait,
        // jdk.JavaMonitorInflate, jdk.ThreadPark). The default 'profile'
        // preset uses a 10–20 ms threshold, which filters out almost all
        // queue / semaphore contention in this benchmark.
        boolean captureLocks      = boolVal(map, "captureLocks", false);
        // JFR threshold for the lock/park event family (only used when
        // captureLocks=true). Accepts JFR duration strings: "0ms",
        // "500us", "1ms", "100us", etc. Smaller threshold = more events
        // = larger .jfr file. "500us" is a good middle ground for
        // queue-contention diagnosis without flooding the recording.
        String lockEventThreshold = strVal(map, "lockEventThreshold", "0ms");
        PerfConfig perf = parsePerf(map.get("perf"));
        AsyncProfilerConfig async = parseAsyncProfiler(map.get("asyncProfiler"));
        return new ProfilingConfig(enabled, control, settings, start, stop, filename,
                startCommand, stopCommand, startupQuietPeriodMs, shutdownFlushMs,
                perf, async, captureLocks, lockEventThreshold);
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
        // Optional `perf stat` aggregate counters. Both fields default to
        // "absent" so configs that don't mention them behave exactly as before.
        Boolean enablePerfStat = pm.get("enablePerfStat") == null
                ? null : Boolean.parseBoolean(String.valueOf(pm.get("enablePerfStat")));
        List<String> perfStatEvents = null;
        Object pse = pm.get("perfStatEvents");
        if (pse instanceof List<?> pselist) {
            perfStatEvents = new ArrayList<>();
            for (Object o : pselist) perfStatEvents.add(String.valueOf(o));
        }
        return new PerfConfig(penabled, binary, freq, clock, callGraph, extra, pfile, mmapPages,
                enablePerfStat, perfStatEvents);
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
            String name = strVal(rm, "name", null);
            HybridConfig perRunHybrid = parseHybrid(
                    (Map<String, Object>) rm.get("hybrid"),
                    "runs[" + name + "].hybrid");
            PinningConfig pinning = parsePinning(
                    (Map<String, Object>) rm.get("pinning"),
                    "runs[" + name + "].pinning");
            // Per-run on/off switch. Defaults to true so existing
            // configs without the field continue to run unchanged.
            boolean enabled = boolVal(rm, "enabled", true);
            runs.add(new RunConfig(
                    name,
                    strVal(rm, "mode", null),
                    strVal(rm, "workload", null),
                    perRunHybrid,
                    pinning,
                    enabled
            ));
        }
        return runs;
    }

    @SuppressWarnings("unchecked")
    private static PinningConfig parsePinning(Map<String, Object> map, String path) {
        if (map == null) return null;
        boolean enabled = boolVal(map, "enabled", false);
        int[] coreMap = null;
        Object cm = map.get("coreMap");
        if (cm instanceof List<?> list) {
            coreMap = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                coreMap[i] = Integer.parseInt(String.valueOf(list.get(i)));
            }
        } else if (cm != null) {
            throw new IllegalArgumentException(path + ".coreMap must be a list of integers");
        }
        return new PinningConfig(enabled, coreMap);
    }

    /**
     * Parses a {@code hybrid:} block. Returns {@code null} when absent.
     * Performs no defaulting on routing — every WorkloadKind must be
     * specified explicitly (validated by {@link HybridConfig#validate()}).
     *
     * <pre>{@code
     * hybrid:
     *   sharedWorkers: 8
     *   shardedWorkers: 8
     *   routing:
     *     CPU:    SHARDED
     *     MEMORY: SHARED
     *     IO:     SHARED
     * }</pre>
     */
    @SuppressWarnings("unchecked")
    private static HybridConfig parseHybrid(Map<String, Object> map, String path) {
        if (map == null) return null;

        if (!map.containsKey("sharedWorkers")) {
            throw new IllegalArgumentException(path + ".sharedWorkers is required");
        }
        if (!map.containsKey("shardedWorkers")) {
            throw new IllegalArgumentException(path + ".shardedWorkers is required");
        }
        int sharedWorkers  = intVal(map, "sharedWorkers", 0);
        int shardedWorkers = intVal(map, "shardedWorkers", 0);

        Object routingRaw = map.get("routing");
        if (!(routingRaw instanceof Map<?, ?> routingMap)) {
            throw new IllegalArgumentException(
                    path + ".routing is required and must be a map of WorkloadKind -> SHARED|SHARDED. "
                            + "Every kind (CPU, MEMORY, IO) must be specified explicitly — there are no defaults.");
        }

        java.util.EnumMap<WorkloadKind, HybridConfig.RouteTarget> routing =
                new java.util.EnumMap<>(WorkloadKind.class);
        for (Map.Entry<?, ?> e : routingMap.entrySet()) {
            String kindStr = String.valueOf(e.getKey()).trim().toUpperCase();
            WorkloadKind kind;
            try {
                kind = WorkloadKind.valueOf(kindStr);
            } catch (IllegalArgumentException iae) {
                throw new IllegalArgumentException(
                        path + ".routing has unknown WorkloadKind '" + e.getKey()
                                + "' (allowed: CPU, MEMORY, IO)");
            }
            HybridConfig.RouteTarget target = HybridConfig.RouteTarget.parse(String.valueOf(e.getValue()));
            routing.put(kind, target);
        }

        return new HybridConfig(sharedWorkers, shardedWorkers, routing);
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

