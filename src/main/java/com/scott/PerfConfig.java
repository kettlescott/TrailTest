package com.scott;

import java.util.List;

/**
 * Optional Linux {@code perf record} integration.
 *
 * <p>When enabled, a {@code perf record} subprocess is started immediately
 * before {@link jdk.jfr.Recording#start()} and stopped immediately after
 * {@link jdk.jfr.Recording#stop()}, so its sample window brackets the JFR
 * measurement window. Both tools must be told to use the same clock so
 * timestamps can be overlaid; the default {@code clock} is
 * {@code CLOCK_MONOTONIC}, which matches JFR.
 *
 * <p>YAML example:
 * <pre>
 *   profiling:
 *     enabled: true
 *     control: api
 *     settings: profile
 *     ...
 *     perf:
 *       enabled: true
 *       binary: perf            # or absolute path
 *       frequency: 999          # Hz, prefer prime to avoid beating with JFR
 *       clock: CLOCK_MONOTONIC  # MUST match JFR's clock for time alignment
 *       callGraph: fp           # fp | dwarf | lbr | none
 *       extraArgs: []           # passed verbatim before '-p PID -o FILE'
 *       filename: ${runName}.perf.data
 * </pre>
 */
public record PerfConfig(
        boolean enabled,
        String binary,
        Integer frequency,
        String clock,
        String callGraph,
        List<String> extraArgs,
        String filename,
        Integer mmapPages,
        // --- perf stat (aggregate hardware-counter totals) ---
        // Independent of perf record. When true, a `perf stat -p PID` subprocess
        // is attached for the measurement window and its summary is written to
        // <runName>.perf.stat.txt. Defaults to false to preserve existing runs.
        Boolean enablePerfStat,
        List<String> perfStatEvents,
        String perfStatFilename
) {
    public String binaryOrDefault()    { return binary    == null || binary.isBlank()    ? "perf"             : binary;    }
    public int    frequencyOrDefault() { return frequency == null                        ? 99                 : frequency; }
    public String clockOrDefault()     { return normalizeClock(clock); }
    public String callGraphOrDefault() { return callGraph == null || callGraph.isBlank() ? "none"             : callGraph; }
    public String filenameOrDefault()  { return filename  == null || filename.isBlank()  ? "${runName}.perf.data" : filename; }
    public List<String> extraArgsOrDefault() { return extraArgs == null ? List.of() : extraArgs; }

    public boolean perfStatEnabled() { return enablePerfStat != null && enablePerfStat; }

    /** Default event list for perf stat, used when YAML omits {@code perfStatEvents}.
     *  Kept intentionally conservative so it works across CPUs/VMs; advanced
     *  events (L1-dcache-*, LLC-stores, alignment-faults, ...) can still be
     *  requested explicitly via the YAML {@code perfStatEvents} field. */
    public static final List<String> DEFAULT_PERF_STAT_EVENTS = List.of(
            "cycles", "instructions",
            "branches", "branch-misses",
            "cache-references", "cache-misses",
            "LLC-loads", "LLC-load-misses",
            "context-switches", "cpu-migrations",
            "page-faults",
            "task-clock"
    );

    public List<String> perfStatEventsOrDefault() {
        return (perfStatEvents == null || perfStatEvents.isEmpty())
                ? DEFAULT_PERF_STAT_EVENTS : perfStatEvents;
    }

    public String perfStatFilenameOrDefault() {
        return (perfStatFilename == null || perfStatFilename.isBlank())
                ? "${runName}.perf.stat.txt" : perfStatFilename;
    }

    /**
     * Per-CPU ring-buffer size for {@code perf record}, in pages, must be
     * a power of two. The default of 128 pages = 512 KiB per CPU is what
     * upstream {@code perf} uses; for high-frequency call-graph sampling
     * on many cores, 512 (2 MiB) avoids "lost samples" warnings.
     */
    public int mmapPagesOrDefault() { return mmapPages == null ? 512 : mmapPages; }

    /**
     * {@code perf record -k} only accepts a small set of clock names
     * ({@code monotonic}, {@code mono}, {@code monotonic_raw}, {@code realtime},
     * {@code boottime}, {@code tai}). Users naturally write {@code CLOCK_MONOTONIC}
     * (the POSIX constant), which {@code perf} rejects. We normalize here so
     * either spelling works and the alignment guarantee is preserved.
     */
    private static String normalizeClock(String c) {
        if (c == null || c.isBlank()) return "monotonic";
        String s = c.trim().toLowerCase();
        if (s.startsWith("clock_")) s = s.substring("clock_".length());
        return switch (s) {
            case "monotonic", "mono"           -> "monotonic";
            case "monotonic_raw", "mono_raw"   -> "monotonic_raw";
            case "realtime"                    -> "realtime";
            case "boottime"                    -> "boottime";
            case "tai"                         -> "tai";
            default -> throw new IllegalArgumentException(
                    "profiling.perf.clock must be one of monotonic|monotonic_raw|realtime|boottime|tai (got: " + c + ")");
        };
    }

    public void validate() {
        if (!enabled) return;
        if (frequency != null && frequency <= 0) {
            throw new IllegalArgumentException("profiling.perf.frequency must be > 0");
        }
        // Triggers normalizeClock(); throws on unknown clock spellings.
        clockOrDefault();
        String cg = callGraphOrDefault().toLowerCase();
        if (!cg.equals("fp") && !cg.equals("dwarf") && !cg.equals("lbr") && !cg.equals("none")) {
            throw new IllegalArgumentException("profiling.perf.callGraph must be fp|dwarf|lbr|none");
        }
        if (mmapPages != null) {
            if (mmapPages <= 0 || (mmapPages & (mmapPages - 1)) != 0) {
                throw new IllegalArgumentException("profiling.perf.mmapPages must be a positive power of two");
            }
        }
    }
}

