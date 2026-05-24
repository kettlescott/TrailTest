package com.scott;

import java.util.Collections;
import java.util.List;

/**
 * Optional per-task attribution / sampling. Two independent sampling
 * dimensions:
 *
 * <ul>
 *   <li>{@code sampleRate} — fraction of measurement tasks for which a
 *       CSV row is written. Deterministic by {@code taskId}:
 *       {@code (taskId % sampleInterval) == 0}. Disabling perf
 *       counters still produces these execNs-only rows.</li>
 *   <li>{@code sampledPerfRate} — among the sampled rows, the fraction
 *       that additionally brackets workload execution with
 *       {@code perf_event_open} reads. Independent stride so the
 *       expensive native-syscall pair only runs on a subset.
 *       Default {@code 1.0} (every sampled row reads counters when
 *       {@code perfEnabled}).</li>
 * </ul>
 *
 * <p>{@code perfCounters} selects which counters to open per worker
 * thread. Unknown names are logged and ignored. When the list is
 * {@code null} or empty, a small default set is used
 * (see {@link com.scott.perf.PerfBridge#DEFAULT_COUNTERS}).
 *
 * <p>Disabled by default. When {@code enabled=false} the entire
 * recorder is bypassed: no allocation, no CSV file, no per-task
 * overhead beyond a single static volatile read in {@link Task#run()}.
 */
public record AttributionConfig(
        boolean enabled,
        double  sampleRate,
        String  outputCsv,
        int     bufferCapacityPerWorker,
        boolean perfEnabled,
        double  sampledPerfRate,
        List<String> perfCounters
) {
    public static AttributionConfig disabled() {
        return new AttributionConfig(false, 0.0, null, 65_536,
                false, 1.0, Collections.emptyList());
    }

    /** {@code 1 / sampleRate} clamped to {@code [1, Integer.MAX_VALUE]}. */
    public int sampleInterval() {
        return rateToInterval(sampleRate);
    }

    /** {@code 1 / sampledPerfRate} clamped — applied on top of
     *  {@link #sampleInterval()} so the effective perf cadence is
     *  {@code sampleInterval * perfStride}. */
    public int perfStride() {
        return rateToInterval(sampledPerfRate);
    }

    private static int rateToInterval(double rate) {
        if (rate <= 0.0) return Integer.MAX_VALUE;
        if (rate >= 1.0) return 1;
        long n = Math.round(1.0 / rate);
        if (n < 1L) return 1;
        if (n > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) n;
    }

    public void validate() {
        if (!enabled) return;
        if (sampleRate < 0.0)
            throw new IllegalArgumentException("attribution.sampleRate must be >= 0");
        if (bufferCapacityPerWorker <= 0)
            throw new IllegalArgumentException(
                    "attribution.bufferCapacityPerWorker must be > 0");
        if (sampledPerfRate < 0.0 || sampledPerfRate > 1.0)
            throw new IllegalArgumentException(
                    "attribution.sampledPerfRate must be in [0, 1]");
    }
}

