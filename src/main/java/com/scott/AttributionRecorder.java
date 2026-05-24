package com.scott;

import com.scott.perf.PerfBridge;
import com.scott.perf.PerfWorkerCounters;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.LongAdder;

/**
 * Per-task attribution recorder with optional Linux perf hardware-
 * counter integration. Hot-path allocation-free.
 *
 * <h3>Two-stage sampling</h3>
 * <ul>
 *   <li>{@link #isSampled(long)} — does this task get a CSV row?</li>
 *   <li>{@link #isPerfSampled(long)} — does this task additionally
 *       bracket {@code workload.execute()} with perf counter reads?
 *       Only when both are true do {@link #preSample()} +
 *       {@link #recordSample(long, int, long, long, boolean)} fire
 *       {@code nativeReadAll}. The native syscall pair therefore
 *       runs on a strict subset of measurement tasks; the rest get
 *       lightweight execNs-only rows.</li>
 * </ul>
 *
 * <h3>CSV schema</h3>
 * <pre>
 * runName,mode,workload,taskId,kind,workerId,shardId,sampled,
 *   startNs,finishNs,executionNs,startCpu,endCpu,
 *   cyclesDelta,instructionsDelta,
 *   cacheReferencesDelta,cacheMissesDelta,
 *   llcLoadsDelta,llcLoadMissesDelta,llcStoresDelta,llcStoreMissesDelta,
 *   branchInstructionsDelta,branchMissesDelta,
 *   contextSwitchesDelta,cpuMigrationsDelta,
 *   pageFaultsDelta,minorFaultsDelta,majorFaultsDelta,
 *   perfAvailable,perfReadOk,perfMultiplexed,
 *   gcNearby,safepointNearby
 * </pre>
 *
 * <p>{@code perfAvailable} is true iff a pre/post read pair was
 * attempted for this row. {@code perfReadOk} is the number of
 * counters whose per-bracket {@code deltaRunning > 0} — i.e. the
 * count of slots that actually carried meaningful data for this
 * task (≤ the number of counters configured, and equal to
 * "{@code N_COUNTERS minus starvations}" when all opened fds were
 * read successfully). {@code perfMultiplexed} is true iff at least
 * one opened counter was either partially multiplexed
 * ({@code dE != dR} over the bracket) or fully starved
 * ({@code dR == 0}) — useful for deciding whether scaled deltas on
 * this row should be trusted. All {@code *Delta} columns store the
 * multiplex-corrected scaled deltas (see
 * {@link com.scott.perf.PerfBridge#scaleDelta}). Counters that
 * weren't opened, and counters whose bracket had {@code dR == 0},
 * appear as 0.
 */
public final class AttributionRecorder {

    private static volatile AttributionRecorder ACTIVE;

    public static AttributionRecorder active() { return ACTIVE; }

    static void setActive(AttributionRecorder r) { ACTIVE = r; }

    /* ----- per-run identity ----- */

    private final String runName;
    private final String mode;
    private final String workload;
    private final int sampleInterval;
    private final int perfStride;          // 1 = every sampled row, 2 = every 2nd, ...
    private final int bufferCapacity;
    private final boolean perfEnabled;
    private final boolean[] perfEnableMask;
    private final Path csvPath;

    /* ----- per-worker buffers ----- */

    private final ConcurrentLinkedQueue<Buffer> buffers = new ConcurrentLinkedQueue<>();
    private final ThreadLocal<Buffer> local = new ThreadLocal<>();

    private final LongAdder droppedRecords     = new LongAdder();
    private final LongAdder missingBufferCount = new LongAdder();
    private final LongAdder unmatchedPreSample = new LongAdder();
    private final LongAdder perfReadFailed     = new LongAdder();

    private long flushedRowCount = 0L;

    private final GarbageCollectorMXBean[] gcBeans;

    private AttributionRecorder(String runName, String mode, String workload,
                                int sampleInterval, int perfStride, int bufferCapacity,
                                boolean perfEnabled, boolean[] perfEnableMask,
                                Path csvPath) {
        this.runName         = runName;
        this.mode            = mode;
        this.workload        = workload;
        this.sampleInterval  = sampleInterval;
        this.perfStride      = perfStride;
        this.bufferCapacity  = bufferCapacity;
        this.perfEnabled     = perfEnabled;
        this.perfEnableMask  = perfEnableMask;
        this.csvPath         = csvPath;
        List<GarbageCollectorMXBean> bl = ManagementFactory.getGarbageCollectorMXBeans();
        this.gcBeans = bl.toArray(new GarbageCollectorMXBean[0]);
    }

    public static AttributionRecorder createIfEnabled(AttributionConfig cfg,
                                                      String runName,
                                                      String mode,
                                                      String workload,
                                                      Path defaultDir) {
        if (cfg == null || !cfg.enabled()) return null;
        Path csvPath;
        if (cfg.outputCsv() == null || cfg.outputCsv().isBlank()) {
            csvPath = defaultDir.resolve("task_attribution_cpu.csv");
        } else {
            csvPath = defaultDir.getFileSystem().getPath(cfg.outputCsv());
        }
        boolean perf = cfg.perfEnabled();
        if (perf && !PerfBridge.LOADED) {
            System.err.println(
                    "[attribution] perfEnabled=true but native bridge unavailable ("
                            + PerfBridge.LOAD_ERROR + "); perf counter deltas will be 0.");
        }
        boolean[] mask = perf ? PerfBridge.resolveEnableMask(cfg.perfCounters()) : null;
        return new AttributionRecorder(runName, mode, workload,
                cfg.sampleInterval(), cfg.perfStride(),
                cfg.bufferCapacityPerWorker(),
                perf, mask, csvPath);
    }

    /**
     * Deterministic taskId-based sampler. Uses a SplitMix64 hash of
     * the taskId rather than {@code taskId % sampleInterval} so the
     * decision does not alias with the sharded router's
     * {@code taskId mod workerCount} routing — otherwise some
     * workers can produce zero sampled rows whenever
     * {@code gcd(workerCount, sampleInterval) > 1} (e.g. 8 workers
     * with sampleRate=0.01 ⇒ sampleInterval=100, gcd=4, six of the
     * eight workers see no sampled rows at all).
     */
    public boolean isSampled(long taskId) {
        if (sampleInterval <= 1) return true;
        return Long.remainderUnsigned(splitmix64(taskId),
                (long) sampleInterval) == 0L;
    }

    /**
     * Decides whether a sampled task should additionally read perf
     * counters. Deterministic on taskId so a re-run picks the same
     * perf-sampled subset. Independent of {@link #isSampled(long)};
     * callers must check both.
     *
     * <p>Uses a SplitMix64 hash for the same anti-aliasing reason as
     * {@link #isSampled(long)}. We hash {@code taskId ^ MIX_SALT} so
     * the two predicates are statistically independent — a row being
     * CSV-sampled does not bias the perf-sample decision.
     */
    public boolean isPerfSampled(long taskId) {
        if (!perfEnabled) return false;
        if (perfStride <= 1) return true;
        return Long.remainderUnsigned(splitmix64(taskId ^ 0xDEADBEEFCAFEBABEL),
                (long) perfStride) == 0L;
    }

    /** SplitMix64 finalizer — strong avalanche, allocation-free. */
    private static long splitmix64(long x) {
        long h = x + 0x9E3779B97F4A7C15L;
        h = (h ^ (h >>> 30)) * 0xBF58476D1CE4E5B9L;
        h = (h ^ (h >>> 27)) * 0x94D049BB133111EBL;
        return h ^ (h >>> 31);
    }

    public boolean perfEnabled() { return perfEnabled; }

    /**
     * One-shot per-thread registration. When {@code perfEnabled} this
     * also opens the configured subset of perf fds for the calling
     * thread. Idempotent.
     */
    public void ensureBufferForCurrentThread(int workerId, int shardId) {
        if (local.get() == null) {
            PerfWorkerCounters perf = null;
            if (perfEnabled) {
                perf = PerfWorkerCounters.openForCurrentThread(perfEnableMask);
            }
            Buffer b = new Buffer(workerId, shardId, bufferCapacity, perf);
            local.set(b);
            buffers.add(b);
        }
    }

    /**
     * Close the perf fds opened for the calling worker thread, if
     * any. Must be invoked from each worker's {@code finally} block
     * so file descriptors are released promptly even on abnormal
     * worker exit (uncaught exception, interrupt during shutdown,
     * dispatcher-termination timeout). Idempotent — safe to call
     * multiple times and from threads that never registered. The
     * sampled rows already in the per-worker buffer are NOT
     * discarded; {@link #flushAndClose()} can still drain them.
     */
    public void closePerfFdsForCurrentThread() {
        Buffer b = local.get();
        if (b == null) return;
        PerfWorkerCounters p = b.perf;
        if (p != null) {
            // PerfBridge.closeAll() overwrites every fd with -1, so a
            // subsequent close (e.g. during flushAndClose) becomes a
            // no-op. We deliberately do NOT null out b.perf — the
            // Buffer remains in the global queue with its sampled
            // rows so flushAndClose can still write them to CSV.
            p.close();
        }
    }

    private long totalGcCount() {
        long sum = 0L;
        for (GarbageCollectorMXBean b : gcBeans) {
            long c = b.getCollectionCount();
            if (c > 0L) sum += c;
        }
        return sum;
    }

    /* ===== sampled hot path =====
     *
     * Call sequence per sampled task:
     *
     *   if (perfSampled)  preSample();      // before workload.execute()
     *   ... workload.execute() ...
     *   recordSample(taskId, kind, start, finish, perfSampled);
     *
     * recordSample() is called for every sampled task (including those
     * that did NOT call preSample), so the CSV always contains a row.
     * Non-perf rows store zero deltas and perfAvailable=false.
     */

    /** Read the "before" raw triples. Only call when
     *  {@link #isPerfSampled(long)} returned true. */
    public void preSample() {
        Buffer b = local.get();
        if (b == null) { missingBufferCount.increment(); return; }
        b.preCpu     = PerfBridge.currentCpu();
        b.preGcCount = totalGcCount();
        if (b.perfFds != null) {
            b.preReadOk = PerfBridge.readAllRaw(
                    b.perfFds, b.preValues, b.preEnabled, b.preRunning);
        } else {
            b.preReadOk = 0;
        }
        b.preArmed = true;
    }

    /**
     * Write one row. When {@code perfSampled} the "after" snapshot is
     * read and deltas are computed; otherwise zero deltas are written.
     * Drops the row and counts the drop when the buffer is full.
     */
    public void recordSample(long taskId, int kindOrdinal,
                             long startNs, long finishNs,
                             boolean perfSampled) {
        Buffer b = local.get();
        if (b == null) { missingBufferCount.increment(); return; }

        int i = b.size;
        if (i >= b.taskIds.length) { droppedRecords.increment(); return; }

        b.taskIds[i]  = taskId;
        b.kinds[i]    = (byte) kindOrdinal;
        b.startNs[i]  = startNs;
        b.finishNs[i] = finishNs;
        b.execNs[i]   = finishNs - startNs;

        boolean perfAvail = false;
        byte    readOk    = 0;

        if (perfSampled && b.preArmed) {
            b.preArmed = false;
            int  endCpu      = PerfBridge.currentCpu();
            long postGcCount = totalGcCount();

            int postOk = 0;
            if (b.perfFds != null) {
                postOk = PerfBridge.readAllRaw(
                        b.perfFds, b.postValues, b.postEnabled, b.postRunning);
            }
            // Raw "bytes-OK" count from the two read() syscalls. We
            // also need the per-counter "data is meaningful" count
            // below — they can differ when the PMU was starved.
            int rawBytesOk = Math.min(b.preReadOk, postOk);
            if (rawBytesOk <= 0 && b.perfFds != null) {
                perfReadFailed.increment();
            }
            perfAvail = true;

            b.startCpu[i] = b.preCpu;
            b.endCpu[i]   = endCpu;

            // Per-counter scaled delta. For each opened fd we compute
            //   dV = postValue   - preValue
            //   dE = postEnabled - preEnabled
            //   dR = postRunning - preRunning
            //   scaled = scaleDelta(dV, dE, dR)
            // which is correct under PMU multiplexing because the
            // scale factor is the multiplexing fraction observed
            // OVER THIS BRACKET, not over the run's history. See the
            // header comment in src/main/native/perf_bridge.c.
            //
            // Two observability counters per row:
            //   liveCount     = #counters with dR > 0 (had any PMU time
            //                   in this bracket — value is meaningful)
            //   anyMux flag   = ANY opened counter was multiplexed
            //                   (partial: dR>0 && dE!=dR) OR fully
            //                   starved (dR==0 while the fd was open).
            //                   Set perfMultiplexed=true so consumers
            //                   can filter the row.
            long[] pv = b.preValues,  pe = b.preEnabled,  pr = b.preRunning;
            long[] qv = b.postValues, qe = b.postEnabled, qr = b.postRunning;
            int  liveCount = 0;
            boolean anyMux = false;
            int[] fds = b.perfFds;
            for (int k = 0; k < PerfBridge.N_COUNTERS; k++) {
                long dV = qv[k] - pv[k];
                long dE = qe[k] - pe[k];
                long dR = qr[k] - pr[k];
                if (dR > 0L) {
                    liveCount++;
                    if (dE != dR) anyMux = true;        // partial multiplex
                } else if (fds != null && fds[k] >= 0) {
                    anyMux = true;                       // full starvation on an OPEN fd
                }
                b.scratchDelta[k] = PerfBridge.scaleDelta(dV, dE, dR);
            }
            // perfReadOk now reflects "counters with usable data"
            // rather than "raw 24-byte reads OK" — a much more useful
            // signal for downstream analysis.
            readOk = (byte) Math.min(liveCount, 127);

            long[] sd = b.scratchDelta;
            b.cyclesDelta[i]              = sd[PerfBridge.IDX_CYCLES];
            b.instructionsDelta[i]        = sd[PerfBridge.IDX_INSTRUCTIONS];
            b.cacheReferencesDelta[i]     = sd[PerfBridge.IDX_CACHE_REFERENCES];
            b.cacheMissesDelta[i]         = sd[PerfBridge.IDX_CACHE_MISSES];
            b.llcLoadsDelta[i]            = sd[PerfBridge.IDX_LLC_LOADS];
            b.llcLoadMissesDelta[i]       = sd[PerfBridge.IDX_LLC_LOAD_MISSES];
            b.llcStoresDelta[i]           = sd[PerfBridge.IDX_LLC_STORES];
            b.llcStoreMissesDelta[i]      = sd[PerfBridge.IDX_LLC_STORE_MISSES];
            b.branchInstructionsDelta[i]  = sd[PerfBridge.IDX_BRANCH_INSTRUCTIONS];
            b.branchMissesDelta[i]        = sd[PerfBridge.IDX_BRANCH_MISSES];
            b.contextSwitchesDelta[i]     = sd[PerfBridge.IDX_CONTEXT_SWITCHES];
            b.cpuMigrationsDelta[i]       = sd[PerfBridge.IDX_CPU_MIGRATIONS];
            b.pageFaultsDelta[i]          = sd[PerfBridge.IDX_PAGE_FAULTS];
            b.minorFaultsDelta[i]         = sd[PerfBridge.IDX_MINOR_FAULTS];
            b.majorFaultsDelta[i]         = sd[PerfBridge.IDX_MAJOR_FAULTS];

            b.flags[i] = (byte) (
                    ((postGcCount > b.preGcCount) ? 0x1 : 0x0) |
                    0x4 /* perfAvailable */ |
                    (anyMux ? 0x8 : 0x0) /* perfMultiplexed */
            );
        } else {
            // Sampled-but-not-perf row: leave counter arrays at default 0.
            b.startCpu[i] = -1;
            b.endCpu[i]   = -1;
            if (perfSampled) {
                // perf was requested but preSample never armed (e.g.
                // preSample ran on a different thread). Count it.
                unmatchedPreSample.increment();
            }
            b.flags[i] = 0;
        }
        b.perfReadOkArr[i] = readOk;
        if (perfAvail) b.flags[i] |= 0x4;

        b.size = i + 1;
    }

    /* ============================================================
     *  Flush + shutdown
     * ============================================================ */

    public long flushAndClose() throws IOException {
        Path parent = csvPath.getParent();
        if (parent != null) Files.createDirectories(parent);
        long total = 0L;
        try (BufferedWriter w = Files.newBufferedWriter(csvPath,
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING)) {
            w.write("runName,mode,workload,taskId,kind,workerId,shardId,sampled,"
                  + "startNs,finishNs,executionNs,startCpu,endCpu,"
                  + "cyclesDelta,instructionsDelta,"
                  + "cacheReferencesDelta,cacheMissesDelta,"
                  + "llcLoadsDelta,llcLoadMissesDelta,llcStoresDelta,llcStoreMissesDelta,"
                  + "branchInstructionsDelta,branchMissesDelta,"
                  + "contextSwitchesDelta,cpuMigrationsDelta,"
                  + "pageFaultsDelta,minorFaultsDelta,majorFaultsDelta,"
                  + "perfAvailable,perfReadOk,perfMultiplexed,"
                  + "gcNearby,safepointNearby\n");
            for (Buffer b; (b = buffers.poll()) != null; ) {
                total += b.size;
                writeBuffer(w, b);
                if (b.perf != null) b.perf.close();
                b.release();
            }
        }
        this.flushedRowCount = total;
        return total;
    }

    private void writeBuffer(BufferedWriter w, Buffer b) throws IOException {
        int n = b.size;
        String widStr = Integer.toString(b.workerId);
        String sidStr = Integer.toString(b.shardId);
        StringBuilder sb = new StringBuilder(384);
        for (int i = 0; i < n; i++) {
            sb.setLength(0);
            byte f = b.flags[i];
            sb.append(runName).append(',')
              .append(mode).append(',')
              .append(workload).append(',')
              .append(b.taskIds[i]).append(',')
              .append(kindLabel(b.kinds[i])).append(',')
              .append(widStr).append(',')
              .append(sidStr).append(',')
              .append("true").append(',')
              .append(b.startNs[i]).append(',')
              .append(b.finishNs[i]).append(',')
              .append(b.execNs[i]).append(',')
              .append(b.startCpu[i]).append(',')
              .append(b.endCpu[i]).append(',')
              .append(b.cyclesDelta[i]).append(',')
              .append(b.instructionsDelta[i]).append(',')
              .append(b.cacheReferencesDelta[i]).append(',')
              .append(b.cacheMissesDelta[i]).append(',')
              .append(b.llcLoadsDelta[i]).append(',')
              .append(b.llcLoadMissesDelta[i]).append(',')
              .append(b.llcStoresDelta[i]).append(',')
              .append(b.llcStoreMissesDelta[i]).append(',')
              .append(b.branchInstructionsDelta[i]).append(',')
              .append(b.branchMissesDelta[i]).append(',')
              .append(b.contextSwitchesDelta[i]).append(',')
              .append(b.cpuMigrationsDelta[i]).append(',')
              .append(b.pageFaultsDelta[i]).append(',')
              .append(b.minorFaultsDelta[i]).append(',')
              .append(b.majorFaultsDelta[i]).append(',')
              .append(((f & 0x4) != 0) ? "true" : "false").append(',')
              .append((int) b.perfReadOkArr[i]).append(',')
              .append(((f & 0x8) != 0) ? "true" : "false").append(',')
              .append(((f & 0x1) != 0) ? "true" : "false").append(',')
              .append(((f & 0x2) != 0) ? "true" : "false")
              .append('\n');
            w.write(sb.toString());
        }
    }

    private static String kindLabel(byte k) {
        if (k < 0) return "";
        WorkloadKind[] vals = WorkloadKind.values();
        if (k >= vals.length) return Byte.toString(k);
        return vals[k].name();
    }

    public Path csvPath()             { return csvPath; }
    public int  sampleInterval()      { return sampleInterval; }
    public int  perfStride()          { return perfStride; }
    public long totalRecorded()       { return flushedRowCount; }
    public long droppedRecords()      { return droppedRecords.sum(); }
    public long missingBufferCount()  { return missingBufferCount.sum(); }
    public long unmatchedPreSamples() { return unmatchedPreSample.sum(); }
    public long perfReadFailed()      { return perfReadFailed.sum(); }

    /** Bitmask of {@link PerfBridge#N_COUNTERS} bits: a 1 means the
     *  counter was requested for this run. {@code 0L} when perf is
     *  disabled. Useful for run-summary logging. */
    public long requestedCountersMask() {
        if (perfEnableMask == null) return 0L;
        long m = 0L;
        for (int i = 0; i < perfEnableMask.length; i++) {
            if (perfEnableMask[i]) m |= (1L << i);
        }
        return m;
    }

    /* ============================================================
     *  Per-worker buffer — single-writer. Fixed capacity; no grow.
     * ============================================================ */
    private static final class Buffer {
        final int workerId;
        final int shardId;

        long[] taskIds;
        byte[] kinds;
        long[] startNs;
        long[] finishNs;
        long[] execNs;
        int[]  startCpu;
        int[]  endCpu;

        long[] cyclesDelta;
        long[] instructionsDelta;
        long[] cacheReferencesDelta;
        long[] cacheMissesDelta;
        long[] llcLoadsDelta;
        long[] llcLoadMissesDelta;
        long[] llcStoresDelta;
        long[] llcStoreMissesDelta;
        long[] branchInstructionsDelta;
        long[] branchMissesDelta;
        long[] contextSwitchesDelta;
        long[] cpuMigrationsDelta;
        long[] pageFaultsDelta;
        long[] minorFaultsDelta;
        long[] majorFaultsDelta;

        byte[] perfReadOkArr;
        /** bit 0 = gcNearby, bit 1 = safepointNearby, bit 2 = perfAvailable. */
        byte[] flags;
        int size;

        // Per-thread scratch — reused across every perf-sampled task
        // on this worker, so the hot path never allocates. Six small
        // arrays of length N_COUNTERS hold the raw triples for the
        // pre/post snapshots; scratchDelta is the temporary buffer
        // for the per-counter scaled deltas, written through to the
        // long[] columns above.
        final PerfWorkerCounters perf;
        final int[]  perfFds;
        final long[] preValues;
        final long[] preEnabled;
        final long[] preRunning;
        final long[] postValues;
        final long[] postEnabled;
        final long[] postRunning;
        final long[] scratchDelta;
        long preGcCount;
        int  preCpu;
        int  preReadOk;
        boolean preArmed;

        Buffer(int workerId, int shardId, int capacity, PerfWorkerCounters perf) {
            this.workerId = workerId;
            this.shardId  = shardId;

            this.taskIds  = new long[capacity];
            this.kinds    = new byte[capacity];
            this.startNs  = new long[capacity];
            this.finishNs = new long[capacity];
            this.execNs   = new long[capacity];
            this.startCpu = new int[capacity];
            this.endCpu   = new int[capacity];

            this.cyclesDelta             = new long[capacity];
            this.instructionsDelta       = new long[capacity];
            this.cacheReferencesDelta    = new long[capacity];
            this.cacheMissesDelta        = new long[capacity];
            this.llcLoadsDelta           = new long[capacity];
            this.llcLoadMissesDelta      = new long[capacity];
            this.llcStoresDelta          = new long[capacity];
            this.llcStoreMissesDelta     = new long[capacity];
            this.branchInstructionsDelta = new long[capacity];
            this.branchMissesDelta       = new long[capacity];
            this.contextSwitchesDelta    = new long[capacity];
            this.cpuMigrationsDelta      = new long[capacity];
            this.pageFaultsDelta         = new long[capacity];
            this.minorFaultsDelta        = new long[capacity];
            this.majorFaultsDelta        = new long[capacity];

            this.perfReadOkArr = new byte[capacity];
            this.flags = new byte[capacity];
            this.size  = 0;

            this.perf = perf;
            this.perfFds = (perf != null && perf.anyAvailable) ? perf.fds : null;
            int K = PerfBridge.N_COUNTERS;
            this.preValues   = new long[K];
            this.preEnabled  = new long[K];
            this.preRunning  = new long[K];
            this.postValues  = new long[K];
            this.postEnabled = new long[K];
            this.postRunning = new long[K];
            this.scratchDelta = new long[K];
            this.preCpu = -1;
            this.preReadOk = 0;
            this.preArmed = false;
        }

        void release() {
            taskIds = null; kinds = null;
            startNs = null; finishNs = null; execNs = null;
            startCpu = null; endCpu = null;
            cyclesDelta = null; instructionsDelta = null;
            cacheReferencesDelta = null; cacheMissesDelta = null;
            llcLoadsDelta = null; llcLoadMissesDelta = null;
            llcStoresDelta = null; llcStoreMissesDelta = null;
            branchInstructionsDelta = null; branchMissesDelta = null;
            contextSwitchesDelta = null; cpuMigrationsDelta = null;
            pageFaultsDelta = null; minorFaultsDelta = null; majorFaultsDelta = null;
            perfReadOkArr = null; flags = null;
        }
    }
}

