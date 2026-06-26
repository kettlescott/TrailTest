package com.scott.profiling;

import java.io.IOException;
import java.nio.file.Path;

import com.scott.PerfConfig;
import com.scott.PerfStatRecorder;

/**
 * {@link Profiler} that runs an external Linux {@code perf stat -p PID}
 * subprocess for the measurement window, producing aggregate
 * hardware-counter totals in {@code <runName>.perf.stat.txt}.
 *
 * <p>Independent of {@link PerfProfiler}: enabling one does not affect the
 * other. Non-Linux hosts: {@link #start()} throws
 * {@link UnsupportedOperationException}.
 */
public final class PerfStatProfiler implements Profiler {

    private final PerfConfig config;
    private final String runName;
    private final Path runDir;

    private PerfStatRecorder recorder;
    private boolean stopped;

    public PerfStatProfiler(PerfConfig config, String runName, Path runDir) {
        this.config  = config;
        this.runName = runName;
        this.runDir  = runDir;
    }

    @Override public String name() { return "perf-stat"; }

    public Path outputFile() {
        if (config == null || !config.perfStatEnabled()) return null;
        return runDir.resolve(
                config.perfStatFilenameOrDefault().replace("${runName}", runName));
    }

    @Override
    public void start() throws IOException {
        if (recorder != null) return;
        recorder = PerfStatRecorder.start(config, runName, runDir);
    }

    @Override
    public void stop() throws IOException {
        if (recorder == null || stopped) return;
        try {
            recorder.stop();
        } finally {
            stopped = true;
            recorder = null;
        }
    }
}

