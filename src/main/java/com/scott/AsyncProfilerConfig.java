package com.scott;

import java.util.List;

/**
 * Optional async-profiler integration, driven via the {@code asprof} CLI.
 *
 * <p>Purpose: tail-latency root-cause analysis. Unlike perf's on-CPU
 * sampling, async-profiler's {@code wall}-clock mode attributes samples
 * to threads while they are <b>off-CPU</b> (parked on AQS, blocked on a
 * futex, waiting for a GC safepoint, etc.). This is exactly where p99/
 * p99.9 latency originates in queue/executor benchmarks, so the default
 * event here is {@code wall} with a 1 ms interval.
 *
 * <p>The harness emits {@code .jfr} by default so the async-profiler
 * output can be overlaid with the main JFR recording and with perf using
 * the {@code com.scott.MeasurementAnchor} events as fiducials.
 *
 * <p>YAML example (minimal, tail-latency preset):
 * <pre>
 *   profiling:
 *     asyncProfiler:
 *       enabled: true
 *       event: wall        # wall | cpu | lock | alloc | itimer | ...
 *       interval: 1ms      # 100us, 1ms, 10ms, or raw ns integer
 *       format: jfr        # jfr | html | collapsed | tree
 *       filename: ${runName}.async.jfr
 * </pre>
 *
 * <p>The {@code asprof} binary must be on {@code PATH} or given as an
 * absolute path via {@link #binary()}. async-profiler requires
 * {@code -XX:+UnlockDiagnosticVMOptions -XX:+DebugNonSafepoints} for
 * accurate stacks; with Java 17+ AP's built-in agent adds these flags
 * automatically when attached through {@code asprof}.
 */
public record AsyncProfilerConfig(
        boolean enabled,
        String binary,
        String event,
        String interval,
        String format,
        String filename,
        List<String> extraArgs
) {
    public String binaryOrDefault()   { return blankToNull(binary)   == null ? "asprof"                : binary; }
    public String eventOrDefault()    { return blankToNull(event)    == null ? "wall"                  : event; }
    public String intervalOrDefault() { return blankToNull(interval) == null ? "1ms"                   : interval; }
    public String formatOrDefault()   { return blankToNull(format)   == null ? "jfr"                   : format.toLowerCase(); }
    public String filenameOrDefault() { return blankToNull(filename) == null ? defaultFilename()       : filename; }
    public List<String> extraArgsOrDefault() { return extraArgs == null ? List.of() : extraArgs; }

    private String defaultFilename() {
        return switch (formatOrDefault()) {
            case "jfr"       -> "${runName}.async.jfr";
            case "html"      -> "${runName}.async.html";
            case "collapsed" -> "${runName}.async.collapsed";
            case "tree"      -> "${runName}.async.tree.html";
            default          -> "${runName}.async." + formatOrDefault();
        };
    }

    private static String blankToNull(String s) { return (s == null || s.isBlank()) ? null : s; }

    public void validate() {
        if (!enabled) return;
        String fmt = formatOrDefault();
        if (!fmt.equals("jfr") && !fmt.equals("html") && !fmt.equals("collapsed") && !fmt.equals("tree")) {
            throw new IllegalArgumentException(
                    "profiling.asyncProfiler.format must be jfr|html|collapsed|tree (got: " + format + ")");
        }
        // asprof accepts integer (ns) or suffixed (ns/us/ms/s). Cheap sanity check:
        String iv = intervalOrDefault().trim().toLowerCase();
        if (!iv.matches("\\d+(ns|us|ms|s)?")) {
            throw new IllegalArgumentException(
                    "profiling.asyncProfiler.interval must be an integer with optional ns/us/ms/s suffix (got: " + interval + ")");
        }
    }
}

