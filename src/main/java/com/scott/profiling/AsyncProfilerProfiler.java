package com.scott.profiling;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import com.scott.AsyncProfilerConfig;

/**
 * {@link Profiler} backed by the {@code asprof} CLI from async-profiler.
 *
 * <p>Used for <b>tail-latency root-cause analysis</b>: default event is
 * {@code wall} (wall-clock), which samples threads whether they are
 * on-CPU or blocked — so park/unpark on AQS, futex waits on queue locks,
 * and GC safepoint stalls all show up in the flame graph. These are the
 * events responsible for p99/p99.9 spikes in shared-queue vs
 * sharded-queue comparisons and are invisible to plain {@code perf -F 99}.
 *
 * <p>Not a long-running child process: {@code asprof start …} attaches
 * via the JVM attach API and returns; {@code asprof stop …} dumps and
 * detaches. Therefore nothing from async-profiler runs in-process on the
 * benchmark thread during the measurement window.
 */
public final class AsyncProfilerProfiler implements Profiler {

    private final AsyncProfilerConfig config;
    private final String runName;
    private final Path runDir;
    private final long targetPid;

    private Path output;
    private boolean started;
    private boolean stopped;

    public AsyncProfilerProfiler(AsyncProfilerConfig config, String runName, Path runDir) {
        this.config    = config;
        this.runName   = runName;
        this.runDir    = runDir;
        this.targetPid = ProcessHandle.current().pid();
    }

    @Override public String name() { return "async-profiler"; }

    public Path outputFile() {
        if (config == null || !config.enabled()) return null;
        return runDir.resolve(config.filenameOrDefault().replace("${runName}", runName));
    }

    @Override
    public void start() throws IOException {
        if (started) return;
        output = outputFile();
        Files.createDirectories(output.getParent());

        List<String> cmd = new ArrayList<>();
        cmd.add(config.binaryOrDefault());
        cmd.add("start");
        cmd.add("-e"); cmd.add(config.eventOrDefault());
        cmd.add("-i"); cmd.add(config.intervalOrDefault());
        cmd.add("-o"); cmd.add(config.formatOrDefault());
        cmd.add("-f"); cmd.add(output.toString());
        cmd.addAll(config.extraArgsOrDefault());
        cmd.add(Long.toString(targetPid));

        Path logPath = runDir.resolve(runName + ".async.log");
        int exit = runBlocking(cmd, logPath, 15);
        if (exit != 0) {
            throw new IOException(
                    "asprof start exited with " + exit + ". "
                            + "Install async-profiler and put 'asprof' on PATH, or set "
                            + "profiling.asyncProfiler.binary to an absolute path. "
                            + "Command: " + String.join(" ", cmd)
                            + System.lineSeparator() + "log tail:" + System.lineSeparator()
                            + tail(logPath, 4096));
        }
        started = true;
    }

    @Override
    public void stop() throws IOException {
        if (!started || stopped) return;
        stopped = true;

        // `asprof stop <pid>` tells the in-JVM agent to stop and flush
        // to the file registered at start. Passing -o/-f at stop is
        // redundant and rejected by some asprof versions ("unknown
        // option"), so keep this form minimal and version-portable.
        List<String> cmd = List.of(
                config.binaryOrDefault(), "stop",
                Long.toString(targetPid));

        Path logPath = runDir.resolve(runName + ".async.log");
        int exit = runBlocking(cmd, logPath, 20);
        if (exit != 0) {
            throw new IOException("asprof stop exited with " + exit
                    + "; profile may be incomplete. See " + logPath);
        }
    }

    private static int runBlocking(List<String> cmd, Path log, int timeoutSec) throws IOException {
        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()));
        Process p;
        try {
            p = pb.start();
        } catch (IOException e) {
            throw new IOException("Failed to launch " + cmd.get(0) + ": " + e.getMessage()
                    + ". Full command: " + String.join(" ", cmd), e);
        }
        try {
            if (!p.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                throw new IOException(cmd.get(0) + " did not complete within " + timeoutSec + "s");
            }
            return p.exitValue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted waiting for " + cmd.get(0), e);
        }
    }

    private static String tail(Path p, int maxBytes) {
        try {
            byte[] all = Files.readAllBytes(p);
            int from = Math.max(0, all.length - maxBytes);
            return new String(all, from, all.length - from);
        } catch (IOException ignore) {
            return "(no log)";
        }
    }
}

