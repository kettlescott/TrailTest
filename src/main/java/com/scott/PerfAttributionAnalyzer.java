package com.scott;

import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Offline analyzer for the perf-augmented attribution CSV written by
 * {@link AttributionRecorder}. Computes derived per-task metrics
 * (IPC, CPI, cache miss rate, LLC load miss rate, branch miss rate)
 * and compares the <b>normal</b> band (p40–p60 by executionNs) vs the
 * <b>slow</b> band (p95–p99) vs the <b>extreme</b> tail (&gt; p99).
 *
 * <p>Output is plain text on stdout; no external deps.
 *
 * <p>Usage:
 * <pre>
 *   java -cp ... com.scott.PerfAttributionAnalyzer \
 *        results/&lt;runName&gt;/task_attribution_cpu.csv
 * </pre>
 *
 * <p>Interpretation hint: when slow / extreme rows show <em>higher
 * LLC-load-misses, lower IPC, but no context-switches / cpu-migrations
 * / gcNearby</em>, that is suggestive that memory-hierarchy stalls
 * (not scheduling or GC) drive the executionNs inflation.
 */
public final class PerfAttributionAnalyzer {

    private PerfAttributionAnalyzer() {}

    /** Snapshot of one row's parsed fields (only the columns we need). */
    private static final class Row {
        long execNs;
        long cycles, instr;
        long cacheRefs, cacheMiss;
        long llcLoads, llcLoadMiss;
        long llcStores, llcStoreMiss;
        long branchInstr, branchMiss;
        long ctxSwitches, cpuMigr;
        long pageFaults, minorFaults, majorFaults;
        boolean gcNearby, safepointNearby;
    }

    public static void main(String[] args) throws Exception {
        if (args.length < 1) {
            System.err.println("usage: PerfAttributionAnalyzer <task_attribution_cpu.csv>");
            System.exit(1);
        }
        Path csv = Paths.get(args[0]);
        if (!Files.exists(csv)) {
            System.err.println("file not found: " + csv);
            System.exit(1);
        }

        Row[] rows = readRows(csv);
        if (rows.length == 0) {
            System.out.println("no rows in " + csv);
            return;
        }

        // Sort by execNs to compute percentile bands.
        long[] execs = new long[rows.length];
        for (int i = 0; i < rows.length; i++) execs[i] = rows[i].execNs;
        long[] sorted = execs.clone();
        Arrays.sort(sorted);

        long p40 = percentile(sorted, 40.0);
        long p60 = percentile(sorted, 60.0);
        long p95 = percentile(sorted, 95.0);
        long p99 = percentile(sorted, 99.0);

        System.out.printf("file        : %s%n", csv);
        System.out.printf("rows        : %,d%n", rows.length);
        System.out.printf("execNs band : p40=%s p60=%s p95=%s p99=%s max=%s%n",
                fmt(p40), fmt(p60), fmt(p95), fmt(p99),
                fmt(sorted[sorted.length - 1]));
        System.out.println();

        Row[] normal  = filter(rows, p40, p60, true);   // [p40, p60]
        Row[] slow    = filter(rows, p95, p99, true);   // [p95, p99]
        Row[] extreme = filterStrictlyGreater(rows, p99);

        report("normal  (p40-p60)", normal);
        report("slow    (p95-p99)", slow);
        report("extreme (> p99)  ", extreme);

        System.out.println();
        System.out.println("memory-stall heuristic (slow vs normal):");
        memoryStallHeuristic(normal, slow);
        System.out.println();
        System.out.println("memory-stall heuristic (extreme vs normal):");
        memoryStallHeuristic(normal, extreme);
    }

    /* ============================================================
     *  Reporting
     * ============================================================ */

    private static void report(String label, Row[] g) {
        if (g.length == 0) {
            System.out.printf("%s : empty%n", label);
            return;
        }
        long execAvg = avgLong(g, r -> r.execNs);
        double ipc   = ratio(g, r -> r.instr,        r -> r.cycles);
        double cpi   = ratio(g, r -> r.cycles,       r -> r.instr);
        double cmr   = ratio(g, r -> r.cacheMiss,    r -> r.cacheRefs);
        double llmr  = ratio(g, r -> r.llcLoadMiss,  r -> r.llcLoads);
        double bmr   = ratio(g, r -> r.branchMiss,   r -> r.branchInstr);
        long llcLM   = avgLong(g, r -> r.llcLoadMiss);
        long llcL    = avgLong(g, r -> r.llcLoads);
        long ctx     = avgLong(g, r -> r.ctxSwitches);
        long mig     = avgLong(g, r -> r.cpuMigr);
        long pf      = avgLong(g, r -> r.pageFaults);
        long maj     = avgLong(g, r -> r.majorFaults);
        long gcN     = countBool(g, r -> r.gcNearby);
        long spN     = countBool(g, r -> r.safepointNearby);

        System.out.printf("%s : n=%,d  execAvg=%s  IPC=%.3f  CPI=%.3f  cacheMissRate=%.4f"
                          + "  LLCloadMissRate=%.4f  branchMissRate=%.4f%n",
                label, g.length, fmt(execAvg), ipc, cpi, cmr, llmr, bmr);
        System.out.printf("%s            llcLoadsAvg=%,d llcLoadMissAvg=%,d"
                          + "  ctxSw=%,d  migr=%,d  pgFault=%,d  majFault=%,d"
                          + "  gcNearby=%,d/%d  safepointNearby=%,d/%d%n",
                pad(label.length()),
                llcL, llcLM, ctx, mig, pf, maj,
                gcN, g.length, spN, g.length);
    }

    private static void memoryStallHeuristic(Row[] baseline, Row[] candidate) {
        if (baseline.length == 0 || candidate.length == 0) {
            System.out.println("  insufficient data");
            return;
        }
        double ipcB = ratio(baseline,  r -> r.instr,       r -> r.cycles);
        double ipcC = ratio(candidate, r -> r.instr,       r -> r.cycles);
        double llmrB = ratio(baseline,  r -> r.llcLoadMiss, r -> r.llcLoads);
        double llmrC = ratio(candidate, r -> r.llcLoadMiss, r -> r.llcLoads);
        long llmAvgB = avgLong(baseline,  r -> r.llcLoadMiss);
        long llmAvgC = avgLong(candidate, r -> r.llcLoadMiss);
        long ctxC = sumLong(candidate, r -> r.ctxSwitches);
        long migC = sumLong(candidate, r -> r.cpuMigr);
        long gcC  = countBool(candidate, r -> r.gcNearby);

        boolean higherLlc   = llmAvgC > llmAvgB * 1.10;
        boolean higherRate  = llmrC   > llmrB   * 1.10 && llmrC > 0.0;
        boolean lowerIpc    = ipcC    < ipcB    * 0.90 && ipcC > 0.0;
        boolean noCtx       = ctxC == 0;
        boolean noMigr      = migC == 0;
        boolean noGc        = gcC  == 0;

        System.out.printf("  LLC-load-misses avg : baseline=%,d  candidate=%,d  %s%n",
                llmAvgB, llmAvgC, higherLlc ? "HIGHER" : "~same");
        System.out.printf("  LLC-load miss rate  : baseline=%.4f candidate=%.4f %s%n",
                llmrB, llmrC, higherRate ? "HIGHER" : "~same");
        System.out.printf("  IPC                 : baseline=%.3f candidate=%.3f %s%n",
                ipcB, ipcC, lowerIpc ? "LOWER" : "~same");
        System.out.printf("  contextSwitches sum : %,d   %s%n", ctxC, noCtx ? "(none)" : "PRESENT");
        System.out.printf("  cpuMigrations sum   : %,d   %s%n", migC, noMigr ? "(none)" : "PRESENT");
        System.out.printf("  gcNearby count      : %,d   %s%n", gcC,  noGc  ? "(none)" : "PRESENT");

        if (higherLlc && higherRate && lowerIpc && noCtx && noMigr && noGc) {
            System.out.println("  => memory-hierarchy stalls likely contribute to execution-time inflation");
        } else {
            System.out.println("  => no clear memory-stall signal (see counters above)");
        }
    }

    /* ============================================================
     *  CSV parse
     * ============================================================ */

    private static Row[] readRows(Path csv) throws Exception {
        try (BufferedReader br = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = br.readLine();
            if (header == null) return new Row[0];
            Map<String, Integer> idx = new HashMap<>();
            String[] cols = header.split(",");
            for (int i = 0; i < cols.length; i++) idx.put(cols[i].trim(), i);

            int iExec = req(idx, "executionNs");
            int iCyc  = req(idx, "cyclesDelta");
            int iIns  = req(idx, "instructionsDelta");
            int iCr   = req(idx, "cacheReferencesDelta");
            int iCm   = req(idx, "cacheMissesDelta");
            int iLl   = req(idx, "llcLoadsDelta");
            int iLlm  = req(idx, "llcLoadMissesDelta");
            int iLs   = req(idx, "llcStoresDelta");
            int iLsm  = req(idx, "llcStoreMissesDelta");
            int iBi   = req(idx, "branchInstructionsDelta");
            int iBm   = req(idx, "branchMissesDelta");
            int iCtx  = req(idx, "contextSwitchesDelta");
            int iMig  = req(idx, "cpuMigrationsDelta");
            int iPf   = req(idx, "pageFaultsDelta");
            int iMin  = req(idx, "minorFaultsDelta");
            int iMaj  = req(idx, "majorFaultsDelta");
            int iGc   = req(idx, "gcNearby");
            int iSp   = req(idx, "safepointNearby");

            java.util.ArrayList<Row> out = new java.util.ArrayList<>(1 << 16);
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isEmpty()) continue;
                String[] p = line.split(",");
                if (p.length <= iSp) continue;
                Row r = new Row();
                try {
                    r.execNs       = Long.parseLong(p[iExec].trim());
                    r.cycles       = Long.parseLong(p[iCyc].trim());
                    r.instr        = Long.parseLong(p[iIns].trim());
                    r.cacheRefs    = Long.parseLong(p[iCr].trim());
                    r.cacheMiss    = Long.parseLong(p[iCm].trim());
                    r.llcLoads     = Long.parseLong(p[iLl].trim());
                    r.llcLoadMiss  = Long.parseLong(p[iLlm].trim());
                    r.llcStores    = Long.parseLong(p[iLs].trim());
                    r.llcStoreMiss = Long.parseLong(p[iLsm].trim());
                    r.branchInstr  = Long.parseLong(p[iBi].trim());
                    r.branchMiss   = Long.parseLong(p[iBm].trim());
                    r.ctxSwitches  = Long.parseLong(p[iCtx].trim());
                    r.cpuMigr      = Long.parseLong(p[iMig].trim());
                    r.pageFaults   = Long.parseLong(p[iPf].trim());
                    r.minorFaults  = Long.parseLong(p[iMin].trim());
                    r.majorFaults  = Long.parseLong(p[iMaj].trim());
                    r.gcNearby        = "true".equalsIgnoreCase(p[iGc].trim());
                    r.safepointNearby = "true".equalsIgnoreCase(p[iSp].trim());
                } catch (NumberFormatException e) {
                    continue;
                }
                out.add(r);
            }
            return out.toArray(new Row[0]);
        }
    }

    private static int req(Map<String, Integer> idx, String name) {
        Integer v = idx.get(name);
        if (v == null) throw new IllegalArgumentException(
                "missing column '" + name + "' in CSV header");
        return v;
    }

    /* ============================================================
     *  Helpers
     * ============================================================ */

    @FunctionalInterface private interface LongOf { long get(Row r); }
    @FunctionalInterface private interface BoolOf { boolean get(Row r); }

    private static long avgLong(Row[] g, LongOf f) {
        if (g.length == 0) return 0L;
        long s = 0L;
        for (Row r : g) s += f.get(r);
        return s / g.length;
    }
    private static long sumLong(Row[] g, LongOf f) {
        long s = 0L;
        for (Row r : g) s += f.get(r);
        return s;
    }
    private static long countBool(Row[] g, BoolOf f) {
        long c = 0;
        for (Row r : g) if (f.get(r)) c++;
        return c;
    }
    /** sum(num) / sum(den) — guards against div-by-zero. */
    private static double ratio(Row[] g, LongOf num, LongOf den) {
        long n = 0L, d = 0L;
        for (Row r : g) { n += num.get(r); d += den.get(r); }
        return d == 0L ? 0.0 : (double) n / (double) d;
    }

    private static Row[] filter(Row[] all, long lo, long hi, boolean inclusiveHi) {
        java.util.ArrayList<Row> out = new java.util.ArrayList<>();
        for (Row r : all) {
            if (r.execNs < lo) continue;
            if (inclusiveHi ? r.execNs > hi : r.execNs >= hi) continue;
            out.add(r);
        }
        return out.toArray(new Row[0]);
    }
    private static Row[] filterStrictlyGreater(Row[] all, long lo) {
        java.util.ArrayList<Row> out = new java.util.ArrayList<>();
        for (Row r : all) if (r.execNs > lo) out.add(r);
        return out.toArray(new Row[0]);
    }

    private static long percentile(long[] sortedAsc, double p) {
        if (sortedAsc.length == 0) return 0L;
        double rank = (p / 100.0) * (sortedAsc.length - 1);
        int idx = (int) Math.round(rank);
        if (idx < 0) idx = 0;
        if (idx >= sortedAsc.length) idx = sortedAsc.length - 1;
        return sortedAsc[idx];
    }

    private static String fmt(long ns) {
        if (ns < 10_000L)     return String.format("%dns", ns);
        if (ns < 10_000_000L) return String.format("%.1fus", ns / 1_000.0);
        return String.format("%.3fms", ns / 1_000_000.0);
    }

    private static String pad(int n) {
        char[] c = new char[n];
        Arrays.fill(c, ' ');
        return new String(c);
    }
}

