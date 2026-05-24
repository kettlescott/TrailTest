package com.scott;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Small analysis helper for the attribution CSV produced by
 * {@link AttributionRecorder}.
 *
 * <p>Reads the CSV, sorts {@code executionNs}, and prints summary
 * statistics for three groups defined by percentile bands:
 * <ul>
 *   <li><b>normal</b>  : p40 – p60 (median band; "typical" tasks)</li>
 *   <li><b>slow</b>    : p95 – p99 (upper tail)</li>
 *   <li><b>extreme</b> : &gt; p99   (heavy tail outliers)</li>
 * </ul>
 *
 * <p>Usage:
 * <pre>
 *   java -cp ... com.scott.AttributionAnalyzer results/&lt;runName&gt;/task_attribution_cpu.csv
 * </pre>
 *
 * <p>Intentionally minimal: no external deps, no streaming framework.
 */
public final class AttributionAnalyzer {

    private AttributionAnalyzer() {}

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: AttributionAnalyzer <task_attribution_cpu.csv>");
            System.exit(1);
        }
        Path csv = Paths.get(args[0]);
        if (!Files.exists(csv)) {
            System.err.println("file not found: " + csv);
            System.exit(1);
        }

        List<Long> exec = new ArrayList<>(1 << 16);
        int execCol = -1;
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) {
                System.err.println("empty CSV");
                return;
            }
            String[] cols = header.split(",");
            for (int i = 0; i < cols.length; i++) {
                if ("executionNs".equals(cols[i].trim())) {
                    execCol = i;
                    break;
                }
            }
            if (execCol < 0) {
                System.err.println("missing 'executionNs' column");
                System.exit(1);
            }
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] parts = line.split(",");
                if (parts.length <= execCol) continue;
                try {
                    exec.add(Long.parseLong(parts[execCol].trim()));
                } catch (NumberFormatException ignore) { /* skip */ }
            }
        }

        if (exec.isEmpty()) {
            System.out.println("no rows in " + csv);
            return;
        }

        long[] arr = new long[exec.size()];
        for (int i = 0; i < arr.length; i++) arr[i] = exec.get(i);
        Arrays.sort(arr);

        long p40 = percentile(arr, 40.0);
        long p60 = percentile(arr, 60.0);
        long p95 = percentile(arr, 95.0);
        long p99 = percentile(arr, 99.0);

        System.out.printf("file       : %s%n", csv);
        System.out.printf("rows       : %,d%n", arr.length);
        System.out.printf("overall    : p50=%s p95=%s p99=%s p99.9=%s max=%s%n",
                fmt(percentile(arr, 50.0)),
                fmt(p95),
                fmt(p99),
                fmt(percentile(arr, 99.9)),
                fmt(arr[arr.length - 1]));
        System.out.println();

        printGroup("normal  (p40-p60)", arr, p40, p60, true);
        printGroup("slow    (p95-p99)", arr, p95, p99, true);
        printGroup("extreme (>p99)   ", arr, p99, Long.MAX_VALUE, false);
    }

    private static void printGroup(String label, long[] sorted, long lo, long hi, boolean inclusiveHi) {
        // Sorted array — find range via binarySearch.
        int loIdx = lowerBound(sorted, lo);
        int hiIdx = inclusiveHi ? upperBound(sorted, hi) : sorted.length;
        if (!inclusiveHi) {
            loIdx = upperBound(sorted, lo); // strictly > p99
        }
        if (hiIdx <= loIdx) {
            System.out.printf("%s : empty%n", label);
            return;
        }
        long[] slice = Arrays.copyOfRange(sorted, loIdx, hiIdx);
        // slice is already sorted because parent is sorted.
        double sum = 0.0;
        for (long v : slice) sum += v;
        long p50 = percentile(slice, 50.0);
        long p95 = percentile(slice, 95.0);
        long p99 = percentile(slice, 99.0);
        System.out.printf("%s : count=%,d  avg=%s  p50=%s  p95=%s  p99=%s%n",
                label, slice.length,
                fmt((long)(sum / slice.length)),
                fmt(p50), fmt(p95), fmt(p99));
    }

    private static long percentile(long[] sortedAsc, double p) {
        if (sortedAsc.length == 0) return 0L;
        double rank = (p / 100.0) * (sortedAsc.length - 1);
        int idx = (int) Math.round(rank);
        if (idx < 0) idx = 0;
        if (idx >= sortedAsc.length) idx = sortedAsc.length - 1;
        return sortedAsc[idx];
    }

    /** Index of first element >= key. */
    private static int lowerBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] < key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    /** Index of first element > key. */
    private static int upperBound(long[] a, long key) {
        int lo = 0, hi = a.length;
        while (lo < hi) {
            int mid = (lo + hi) >>> 1;
            if (a[mid] <= key) lo = mid + 1; else hi = mid;
        }
        return lo;
    }

    private static String fmt(long ns) {
        if (ns < 10_000L)       return String.format("%dns", ns);
        if (ns < 10_000_000L)   return String.format("%.1fus", ns / 1_000.0);
        return String.format("%.3fms", ns / 1_000_000.0);
    }
}

