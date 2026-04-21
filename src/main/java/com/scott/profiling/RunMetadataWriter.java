package com.scott.profiling;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Writes a per-run {@code run.json}-style text file with everything
 * needed for reproducibility: workload/run parameters, JVM + OS
 * fingerprints, profiler config summary, output paths. Written once, at
 * the end of the run, outside the measurement window.
 *
 * <p>The format is deliberately hand-rolled minimal JSON so we don't add
 * a dependency. All values are quoted strings; consumers should treat
 * numbers as decimal strings.
 */
public final class RunMetadataWriter {

    private RunMetadataWriter() {}

    public static Path write(Path runDir,
                             String runId,
                             Map<String, Object> fields) throws IOException {
        Files.createDirectories(runDir);
        Path out = runDir.resolve("run.json");

        Map<String, Object> full = new LinkedHashMap<>();
        full.put("runId",       runId);
        full.put("timestamp",   Instant.now().toString());
        full.put("hostname",    hostname());
        full.put("os.name",     System.getProperty("os.name",    ""));
        full.put("os.version",  System.getProperty("os.version", ""));
        full.put("os.arch",     System.getProperty("os.arch",    ""));
        full.put("jvm.version", System.getProperty("java.version",""));
        full.put("jvm.vendor",  System.getProperty("java.vendor", ""));
        full.put("cpu.cores",   Runtime.getRuntime().availableProcessors());
        full.putAll(fields);

        StringBuilder sb = new StringBuilder(512);
        sb.append("{\n");
        int i = 0, n = full.size();
        for (Map.Entry<String, Object> e : full.entrySet()) {
            sb.append("  ").append(quote(e.getKey())).append(": ")
              .append(jsonValue(e.getValue()));
            if (++i < n) sb.append(',');
            sb.append('\n');
        }
        sb.append("}\n");
        Files.writeString(out, sb.toString());
        return out;
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (UnknownHostException e) { return "unknown"; }
    }

    private static String jsonValue(Object v) {
        if (v == null)              return "null";
        if (v instanceof Number)    return v.toString();
        if (v instanceof Boolean)   return v.toString();
        return quote(v.toString());
    }

    private static String quote(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 2);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else          sb.append(c);
            }
        }
        sb.append('"');
        return sb.toString();
    }
}

