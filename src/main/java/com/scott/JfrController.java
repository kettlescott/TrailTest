package com.scott;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class JfrController implements AutoCloseable {

    private final boolean enabled;
    private final String start;
    private final String stop;
    private final String runName;
    private final String settings;
    private final String startCommandTemplate;
    private final String stopCommandTemplate;
    private final Path outputFile;
    private boolean dumped;

    private JfrController(boolean enabled,
                          String start,
                          String stop,
                          String runName,
                          String settings,
                          String startCommandTemplate,
                          String stopCommandTemplate,
                          Path outputFile) {
        this.enabled = enabled;
        this.start = start;
        this.stop = stop;
        this.runName = runName;
        this.settings = settings;
        this.startCommandTemplate = startCommandTemplate;
        this.stopCommandTemplate = stopCommandTemplate;
        this.outputFile = outputFile;
    }

    public static JfrController create(ProfilingConfig profiling, String runName, Path runDir) throws IOException {
        if (profiling == null || !profiling.enabled()) {
            return new JfrController(false, null, null, null, null, null, null, null);
        }

        String resolvedName = profiling.filename().replace("${runName}", runName);
        Path output = runDir.resolve(resolvedName);
        Files.createDirectories(output.getParent());

        String defaultStart = "JFR.start name=${runName} settings=${settings} filename=${outputFile}";
        String defaultStop = "JFR.stop name=${runName} filename=${outputFile}";

        return new JfrController(
                true,
                profiling.start(),
                profiling.stop(),
                runName,
                profiling.settings(),
                profiling.startCommand() != null ? profiling.startCommand() : defaultStart,
                profiling.stopCommand() != null ? profiling.stopCommand() : defaultStop,
                output
        );
    }

    public void startBeforeMeasurement() throws IOException {
        if (!enabled) return;
        if ("beforeMeasurement".equalsIgnoreCase(start)) {
            executeJcmd(expand(startCommandTemplate));
        }
    }

    public void stopAfterMeasurement() throws IOException {
        if (!enabled) return;
        if ("afterMeasurement".equalsIgnoreCase(stop)) {
            dumpAndClose();
        }
    }

    public Path outputFile() {
        return outputFile;
    }

    @Override
    public void close() throws IOException {
        if (!enabled || dumped) return;
        dumpAndClose();
    }

    private void dumpAndClose() throws IOException {
        executeJcmd(expand(stopCommandTemplate));
        dumped = true;
    }

    private String expand(String template) {
        return template
                .replace("${runName}", runName)
                .replace("${settings}", settings)
                .replace("${outputFile}", outputFile.toString());
    }

    private static void executeJcmd(String command) throws IOException {
        long pid = ProcessHandle.current().pid();
        List<String> parts = new ArrayList<>();
        parts.add("jcmd");
        parts.add(String.valueOf(pid));
        parts.addAll(Arrays.asList(command.trim().split("\\s+")));

        Process process = new ProcessBuilder(parts).redirectErrorStream(true).start();
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
                throw new IOException("jcmd command failed (exit=" + exit + "): " + String.join(" ", parts)
                        + System.lineSeparator() + output);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for jcmd command", e);
        }
    }
}

