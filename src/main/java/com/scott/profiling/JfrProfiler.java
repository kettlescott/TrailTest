package com.scott.profiling;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.ParseException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import jdk.jfr.Configuration;
import jdk.jfr.Recording;

/**
 * {@link Profiler} that drives a single JFR recording for one benchmark run.
 *
 * <p>Two control back-ends are supported:
 * <ul>
 *   <li><b>api</b> (default, recommended) — {@link jdk.jfr.Recording}.
 *       No external binary required; works on JRE, slim containers, and
 *       non-Linux hosts. Lowest start/stop latency.</li>
 *   <li><b>cli</b> — shells out to {@code jcmd} (resolved from
 *       {@code $JAVA_HOME/bin/jcmd} first, then {@code PATH}). Requires a
 *       full JDK. Provided for parity with external tooling only.</li>
 * </ul>
 *
 * <p>The recording is configured with
 * {@link Recording#setDestination(Path)} before {@code start()} so that
 * {@link Recording#stop()} flushes the final chunk directly to the target
 * path. We explicitly do <em>not</em> call {@code dump()} afterwards
 * (that would double the I/O on the measurement-boundary thread).
 */
public final class JfrProfiler implements Profiler {

    public enum Control { API, CLI }

    /**
     * JFR event names whose threshold we lower to 0 ms when
     * {@code captureLocks} is enabled. This is the minimal set needed to
     * see queue / semaphore contention in this benchmark:
     * <ul>
     *   <li>{@code jdk.JavaMonitorEnter} — {@code synchronized} contention</li>
     *   <li>{@code jdk.JavaMonitorWait}  — {@code Object.wait()}</li>
     *   <li>{@code jdk.JavaMonitorInflate} — monitor inflation</li>
     *   <li>{@code jdk.ThreadPark} — {@code LockSupport.park()}, which is
     *       what {@code ReentrantLock}, {@code Semaphore},
     *       {@code LinkedBlockingQueue}, and all {@code AQS}-based
     *       primitives ultimately call</li>
     * </ul>
     */
    private static final String[] LOCK_EVENTS = {
            "jdk.JavaMonitorEnter",
            "jdk.JavaMonitorWait",
            "jdk.JavaMonitorInflate",
            "jdk.ThreadPark"
    };

    private final Control control;
    private final String  runName;
    private final String  settings;
    private final Path    outputFile;
    private final String  startCommandTemplate;   // CLI only; nullable
    private final String  stopCommandTemplate;    // CLI only; nullable
    private final boolean captureLocks;
    private final String  lockEventThreshold;   // JFR duration string, e.g. "0ms", "500us"

    private Recording recording;       // API mode only
    private boolean started;
    private boolean stopped;

    /** Back-compat constructor: captureLocks defaults to {@code false}. */
    public JfrProfiler(Control control,
                       String runName,
                       String settings,
                       Path outputFile,
                       String startCommandTemplate,
                       String stopCommandTemplate) {
        this(control, runName, settings, outputFile,
                startCommandTemplate, stopCommandTemplate, false, "0ms");
    }

    /** Back-compat constructor: lockEventThreshold defaults to "0ms". */
    public JfrProfiler(Control control,
                       String runName,
                       String settings,
                       Path outputFile,
                       String startCommandTemplate,
                       String stopCommandTemplate,
                       boolean captureLocks) {
        this(control, runName, settings, outputFile,
                startCommandTemplate, stopCommandTemplate, captureLocks, "0ms");
    }

    public JfrProfiler(Control control,
                       String runName,
                       String settings,
                       Path outputFile,
                       String startCommandTemplate,
                       String stopCommandTemplate,
                       boolean captureLocks,
                       String lockEventThreshold) {
        this.control              = control;
        this.runName              = runName;
        this.settings             = settings;
        this.outputFile           = outputFile;
        this.startCommandTemplate = startCommandTemplate;
        this.stopCommandTemplate  = stopCommandTemplate;
        this.captureLocks         = captureLocks;
        this.lockEventThreshold   = (lockEventThreshold == null || lockEventThreshold.isBlank())
                ? "0ms" : lockEventThreshold;
    }

    @Override public String name() { return "jfr(" + control.name().toLowerCase() + ")"; }

    public Path outputFile() { return outputFile; }

    @Override
    public void start() throws IOException {
        if (started) return;
        Files.createDirectories(outputFile.getParent());
        if (control == Control.API) {
            startWithApi();
        } else {
            executeJcmdArgs(buildDefaultJcmdArgs(true), startCommandTemplate);
        }
        started = true;
    }

    @Override
    public void stop() throws IOException {
        if (!started || stopped) return;
        try {
            if (control == Control.API) {
                stopWithApi();
            } else {
                executeJcmdArgs(buildDefaultJcmdArgs(false), stopCommandTemplate);
            }
        } finally {
            stopped = true;
        }
    }

    /* ================================================================
     *  API back-end
     * ================================================================ */

    private void startWithApi() throws IOException {
        try {
            Configuration cfg = loadConfiguration(settings);
            Recording r = (cfg != null) ? new Recording(cfg) : new Recording();
            r.setName(runName);
            r.setDestination(outputFile);
            r.setToDisk(true);
            if (captureLocks) {
                applyLockEventOverrides(r);
            }
            r.start();
            this.recording = r;
        } catch (ParseException e) {
            throw new IOException("Failed to load JFR configuration '" + settings + "'", e);
        }
    }

    /**
     * Lowers the threshold for the {@link #LOCK_EVENTS} set and forces
     * stack-trace capture so contention sites are diagnosable. Called
     * only when {@code captureLocks=true} (API back-end).
     */
    private void applyLockEventOverrides(Recording r) {
        Duration threshold = parseJfrDuration(lockEventThreshold);
        for (String event : LOCK_EVENTS) {
            r.enable(event)
                    .withThreshold(threshold)
                    .withStackTrace();
        }
    }

    /**
     * Parses a JFR-style duration string such as {@code "0ms"},
     * {@code "500us"}, {@code "1ms"}, {@code "2s"}, {@code "100ns"}.
     * Used for the {@code captureLocks} threshold. Falls back to
     * {@link Duration#ZERO} on a blank/unrecognised input rather than
     * failing the run, since this is a diagnostic-only setting.
     */
    private static Duration parseJfrDuration(String s) {
        if (s == null || s.isBlank()) return Duration.ZERO;
        String t = s.trim().toLowerCase();
        try {
            if (t.endsWith("ns")) return Duration.ofNanos (Long.parseLong(t.substring(0, t.length() - 2).trim()));
            if (t.endsWith("us") || t.endsWith("µs")) {
                String num = t.endsWith("µs") ? t.substring(0, t.length() - 2) : t.substring(0, t.length() - 2);
                return Duration.ofNanos(Long.parseLong(num.trim()) * 1_000L);
            }
            if (t.endsWith("ms")) return Duration.ofMillis(Long.parseLong(t.substring(0, t.length() - 2).trim()));
            if (t.endsWith("s"))  return Duration.ofSeconds(Long.parseLong(t.substring(0, t.length() - 1).trim()));
            // Bare number → assume milliseconds (matches JFR convention).
            return Duration.ofMillis(Long.parseLong(t));
        } catch (NumberFormatException e) {
            System.err.println("[jfr] Unrecognised lockEventThreshold '" + s + "', falling back to 0ms");
            return Duration.ZERO;
        }
    }

    private void stopWithApi() {
        if (recording == null) return;
        try {
            recording.stop();
        } finally {
            recording.close();
            recording = null;
        }
    }

    /**
     * Resolves a JFR {@link Configuration}. Accepts a built-in preset
     * name ({@code "default"}, {@code "profile"}) or a path to a
     * {@code .jfc} file.
     */
    private static Configuration loadConfiguration(String settings) throws IOException, ParseException {
        if (settings == null || settings.isBlank()) {
            return Configuration.getConfiguration("default");
        }
        Path asPath = Paths.get(settings);
        if (Files.exists(asPath)) {
            return Configuration.create(asPath);
        }
        return Configuration.getConfiguration(settings);
    }

    /* ================================================================
     *  CLI (jcmd) back-end
     * ================================================================ */

    private String expand(String template) {
        return template
                .replace("${runName}",    runName)
                .replace("${settings}",   settings == null ? "" : settings)
                .replace("${outputFile}", outputFile.toString());
    }

    private List<String> buildDefaultJcmdArgs(boolean startOp) {
        List<String> args = new ArrayList<>(4);
        if (startOp) {
            args.add("JFR.start");
            args.add("name=" + runName);
            args.add("settings=" + settings);
            args.add("filename=" + outputFile.toString());
            if (captureLocks) {
                // Per-event overrides: jcmd accepts
                // "<event-name>#<setting>=<value>" tokens that take
                // precedence over the loaded preset. The threshold
                // string is passed through verbatim ("0ms", "500us",
                // "1ms", ...) — jcmd parses JFR duration syntax.
                for (String event : LOCK_EVENTS) {
                    args.add(event + "#enabled=true");
                    args.add(event + "#threshold=" + lockEventThreshold);
                    args.add(event + "#stackTrace=true");
                }
            }
        } else {
            args.add("JFR.stop");
            args.add("name=" + runName);
            args.add("filename=" + outputFile.toString());
        }
        return args;
    }

    private void executeJcmdArgs(List<String> defaultArgs, String userTemplate) throws IOException {
        boolean isDefault =
                userTemplate == null
             || "JFR.start name=${runName} settings=${settings} filename=${outputFile}".equals(userTemplate)
             || "JFR.stop name=${runName} filename=${outputFile}".equals(userTemplate);

        List<String> jfrArgs = isDefault
                ? defaultArgs
                : Arrays.asList(expand(userTemplate).trim().split("\\s+"));
        executeJcmd(jfrArgs);
    }

    private static String resolveJcmd() {
        String javaHome = System.getProperty("java.home");
        if (javaHome != null && !javaHome.isBlank()) {
            String name = System.getProperty("os.name", "").toLowerCase().contains("win")
                    ? "jcmd.exe" : "jcmd";
            Path candidate = Paths.get(javaHome, "bin", name);
            if (Files.isExecutable(candidate)) {
                return candidate.toString();
            }
        }
        return "jcmd";
    }

    private static void executeJcmd(List<String> jfrArgs) throws IOException {
        long pid = ProcessHandle.current().pid();
        List<String> parts = new ArrayList<>(jfrArgs.size() + 2);
        parts.add(resolveJcmd());
        parts.add(String.valueOf(pid));
        parts.addAll(jfrArgs);

        Process process;
        try {
            process = new ProcessBuilder(parts).redirectErrorStream(true).start();
        } catch (IOException e) {
            throw new IOException(
                    "Failed to launch jcmd (" + parts.get(0) + "). "
                            + "You are likely on a JRE or jcmd is not on PATH. "
                            + "Either run on a full JDK, or use control: api.",
                    e);
        }

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append(System.lineSeparator());
            }
        }

        try {
            int exit = process.waitFor();
            if (exit != 0) {
                throw new IOException("jcmd failed (exit=" + exit + "): " + String.join(" ", parts)
                        + System.lineSeparator() + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for jcmd", e);
        }
    }
}

