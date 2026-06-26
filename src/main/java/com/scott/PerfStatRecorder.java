package com.scott;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a Linux {@code perf stat -p PID} subprocess attached to the current
 * JVM for the measurement window.
 *
 * <p>This is independent of {@link PerfRecorder} (which produces sampling
 * data into {@code perf.data}). {@code perf stat} produces aggregate
 * hardware-counter totals written to {@code <runName>.perf.stat.txt} via
 * {@code -o FILE}. No parser is required; the file is the artifact.
 *
 * <p>Lifecycle mirrors {@link PerfRecorder}: SIGINT is sent on stop so
 * perf flushes its summary cleanly.
 *
 * <p>Linux only.
 */
public final class PerfStatRecorder {

    private final Process process;
    private final Path output;

    private PerfStatRecorder(Process process, Path output) {
        this.process = process;
        this.output = output;
    }

    public static PerfStatRecorder start(PerfConfig cfg, String runName, Path runDir) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new UnsupportedOperationException(
                    "perf stat is Linux-only; got os.name=" + System.getProperty("os.name"));
        }

        Path output = runDir.resolve(
                cfg.perfStatFilenameOrDefault().replace("${runName}", runName));
        Files.createDirectories(output.getParent());

        long pid = ProcessHandle.current().pid();
        Path logPath = runDir.resolve(runName + ".perf.stat.log");

        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.binaryOrDefault());
        cmd.add("stat");
        cmd.add("-e"); cmd.add(String.join(",", cfg.perfStatEventsOrDefault()));
        cmd.add("-p"); cmd.add(String.valueOf(pid));
        cmd.add("-o"); cmd.add(output.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile());

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "Failed to launch `perf stat` (" + cmd.get(0) + "). "
                            + "Install linux-tools-perf, or set profiling.perf.enablePerfStat: false. "
                            + "Command was: " + String.join(" ", cmd),
                    e);
        }

        // Brief settle delay so counter programming completes before the
        // measurement window opens (matches PerfRecorder's behaviour).
        try {
            Thread.sleep(150);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (!proc.isAlive()) {
            String tail = tail(logPath, 4096);
            throw new IOException(
                    "`perf stat` exited immediately (exit=" + proc.exitValue() + "). "
                            + "Common causes: unsupported event on this CPU, "
                            + "kernel.perf_event_paranoid too high, or missing CAP_PERFMON. "
                            + "Command: " + String.join(" ", cmd)
                            + System.lineSeparator() + "perf stat log tail:" + System.lineSeparator() + tail);
        }
        return new PerfStatRecorder(proc, output);
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

    public void stop() throws IOException {
        if (!process.isAlive()) return;
        // perf stat flushes its summary on SIGINT, same as perf record.
        long pid = process.pid();
        try {
            new ProcessBuilder("kill", "-INT", Long.toString(pid))
                    .redirectErrorStream(true)
                    .inheritIO()
                    .start()
                    .waitFor(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (IOException e) {
            process.destroy();
        }

        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("perf stat did not exit within 15s after SIGINT; "
                        + "output file may be truncated");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for perf stat to flush", e);
        }
    }

    public Path output() { return output; }
}

