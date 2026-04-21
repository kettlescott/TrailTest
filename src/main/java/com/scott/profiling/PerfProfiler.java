package com.scott.profiling;

import java.io.IOException;
import java.nio.file.Path;

import com.scott.PerfConfig;
import com.scott.PerfRecorder;

/**
 * {@link Profiler} that runs an external Linux {@code perf record}
 * subprocess for the measurement window.
 *
 * <p>Implementation delegates to {@link PerfRecorder}, which handles
 * {@link ProcessBuilder} command construction, SIGINT-based graceful
 * termination (SIGTERM truncates {@code perf.data}), fail-fast detection
 * when perf exits immediately, and clock alignment with JFR via
 * {@code perf record -k monotonic}.
 *
 * <p>Non-Linux hosts: {@link #start()} throws
 * {@link UnsupportedOperationException}.
 */
public final class PerfProfiler implements Profiler {

    private final PerfConfig config;
    private final String runName;
    private final Path runDir;

    private PerfRecorder recorder;
    private boolean stopped;

    public PerfProfiler(PerfConfig config, String runName, Path runDir) {
        this.config  = config;
        this.runName = runName;
        this.runDir  = runDir;
    }

    @Override public String name() { return "perf"; }

    public Path outputFile() {
        if (config == null || !config.enabled()) return null;
        String resolved = config.filenameOrDefault().replace("${runName}", runName);
        return runDir.resolve(resolved);
    }

    @Override
    public void start() throws IOException {
        if (recorder != null) return;
        recorder = PerfRecorder.start(config, runName, runDir);
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

