package com.scott;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Wraps a Linux {@code perf record} subprocess that targets the current JVM.
 *
 * <p>The process is launched with {@code -k CLOCK_MONOTONIC} (or whatever
 * clock the user configured) so that every perf sample is timestamped in
 * the same clock domain as JFR events emitted by this JVM. This is the
 * single property that makes post-hoc timeline overlay possible.
 *
 * <p>Lifecycle:
 * <pre>
 *   PerfRecorder p = PerfRecorder.start(cfg, runName, runDir);
 *   ... measurement window ...
 *   p.stop();   // sends SIGINT, waits for perf to flush perf.data
 * </pre>
 *
 * <p>Linux only. On other platforms, {@link #start} throws
 * {@link UnsupportedOperationException}.
 */
public final class PerfRecorder {

    private final Process process;
    private final Path output;

    private PerfRecorder(Process process, Path output) {
        this.process = process;
        this.output = output;
    }

    public static PerfRecorder start(PerfConfig cfg, String runName, Path runDir) throws IOException {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            throw new UnsupportedOperationException(
                    "perf integration is Linux-only; got os.name=" + System.getProperty("os.name"));
        }

        String resolvedName = cfg.filenameOrDefault().replace("${runName}", runName);
        Path output = runDir.resolve(resolvedName);
        Files.createDirectories(output.getParent());

        long pid = ProcessHandle.current().pid();
        Path logPath = runDir.resolve(runName + ".perf.log");

        List<String> cmd = new ArrayList<>();
        cmd.add(cfg.binaryOrDefault());
        cmd.add("record");
        cmd.add("-k"); cmd.add(cfg.clockOrDefault());                 // align clock with JFR
        cmd.add("-F"); cmd.add(String.valueOf(cfg.frequencyOrDefault()));
        cmd.add("-m"); cmd.add(String.valueOf(cfg.mmapPagesOrDefault())); // larger ring buffer
        cmd.add("-p"); cmd.add(String.valueOf(pid));
        String cg = cfg.callGraphOrDefault().toLowerCase();
        if (!cg.equals("none")) {
            cmd.add("--call-graph"); cmd.add(cg);                     // space form: most portable
        }
        cmd.addAll(cfg.extraArgsOrDefault());
        cmd.add("-o"); cmd.add(output.toString());

        ProcessBuilder pb = new ProcessBuilder(cmd)
                .redirectErrorStream(true)
                .redirectOutput(logPath.toFile());

        Process proc;
        try {
            proc = pb.start();
        } catch (IOException e) {
            throw new IOException(
                    "Failed to launch perf (" + cmd.get(0) + "). "
                            + "Install linux-tools-perf, or set profiling.perf.enabled: false. "
                            + "Command was: " + String.join(" ", cmd),
                    e);
        }

        // Give perf a moment to install kernel events before the JFR window opens.
        // Without this, the first ~10 ms of samples can be missing.
        try {
            Thread.sleep(150);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
        if (!proc.isAlive()) {
            String tail = tail(logPath, 4096);
            throw new IOException(
                    "perf exited immediately (exit=" + proc.exitValue() + "). "
                            + "Common causes: kernel.perf_event_paranoid too high, "
                            + "invalid -k clock, or missing CAP_PERFMON. "
                            + "Command: " + String.join(" ", cmd)
                            + System.lineSeparator() + "perf log tail:" + System.lineSeparator() + tail);
        }
        return new PerfRecorder(proc, output);
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
        // CRITICAL: perf record only flushes perf.data on SIGINT.
        // Process.destroy() sends SIGTERM, which truncates the file.
        // We therefore invoke `kill -INT` explicitly.
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
            // Fall back to SIGTERM if `kill` is somehow unavailable; data may
            // be truncated but at least the child won't linger.
            process.destroy();
        }

        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) {
                // Last-resort SIGKILL via destroyForcibly() to avoid leaking
                // the perf process; perf.data may be partial.
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                throw new IOException("perf did not exit within 15s after SIGINT; perf.data may be truncated");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for perf to flush", e);
        }
    }

    public Path output() { return output; }
}

