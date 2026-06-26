package com.scott.perf;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Thin JNI wrapper around Linux {@code perf_event_open(2)}. Exposes
 * just enough surface to open a small <em>configurable</em> subset of
 * counters per worker thread and read them cheaply per perf-sampled
 * task.
 *
 * <h3>Counter table (15 fixed slots)</h3>
 *
 * <p>The row schema is fixed; what changes per run is which slots are
 * actually opened (others remain {@code fd == -1} and read as 0).
 *
 * <pre>
 *   0  cycles               HW   PERF_COUNT_HW_CPU_CYCLES
 *   1  instructions         HW   PERF_COUNT_HW_INSTRUCTIONS
 *   2  cache-references     HW   PERF_COUNT_HW_CACHE_REFERENCES
 *   3  cache-misses         HW   PERF_COUNT_HW_CACHE_MISSES
 *   4  LLC-loads            HW_CACHE  (LLC | READ  | ACCESS)
 *   5  LLC-load-misses      HW_CACHE  (LLC | READ  | MISS)
 *   6  LLC-stores           HW_CACHE  (LLC | WRITE | ACCESS)
 *   7  LLC-store-misses     HW_CACHE  (LLC | WRITE | MISS)
 *   8  branch-instructions  HW   PERF_COUNT_HW_BRANCH_INSTRUCTIONS
 *   9  branch-misses        HW   PERF_COUNT_HW_BRANCH_MISSES
 *  10  context-switches     SW   PERF_COUNT_SW_CONTEXT_SWITCHES
 *  11  cpu-migrations       SW   PERF_COUNT_SW_CPU_MIGRATIONS
 *  12  page-faults          SW   PERF_COUNT_SW_PAGE_FAULTS
 *  13  minor-faults         SW   PERF_COUNT_SW_PAGE_FAULTS_MIN
 *  14  major-faults         SW   PERF_COUNT_SW_PAGE_FAULTS_MAJ
 * </pre>
 *
 * <h3>Counter accuracy under multiplexing</h3>
 *
 * <p>Counters are opened with
 * {@code PERF_FORMAT_TOTAL_TIME_ENABLED | PERF_FORMAT_TOTAL_TIME_RUNNING}.
 * {@link #readAllRaw} returns the three raw u64 fields
 * {@code (value, time_enabled, time_running)} per fd <em>without
 * scaling</em>. The Java caller computes
 * <pre>
 *   deltaValue   = afterValue   - beforeValue
 *   deltaEnabled = afterEnabled - beforeEnabled
 *   deltaRunning = afterRunning - beforeRunning
 *   scaledDelta  = (deltaRunning > 0)
 *                  ? deltaValue * deltaEnabled / deltaRunning
 *                  : 0
 * </pre>
 * via {@link #scaleDelta(long, long, long)}.
 *
 * <p>This is the correct definition under multiplexing. Scaling
 * absolute values <em>before</em> subtracting carries the cumulative
 * history of every previous schedule slice — see the long comment
 * at the top of {@code perf_bridge.c}.
 *
 * <h3>Availability</h3>
 *
 * <p>{@link #LOADED} is {@code true} only if {@code
 * libperfbridge.so} loaded successfully <em>and</em> {@code
 * nativeAvailable()} returned 1. On macOS / Windows / a JVM without
 * the native library this class still loads but every public entry
 * point degrades to a safe no-op.
 */
public final class PerfBridge {

    private PerfBridge() {}

    /* ------------- counter index table ------------- */

    public static final int IDX_CYCLES               = 0;
    public static final int IDX_INSTRUCTIONS         = 1;
    public static final int IDX_CACHE_REFERENCES     = 2;
    public static final int IDX_CACHE_MISSES         = 3;
    public static final int IDX_LLC_LOADS            = 4;
    public static final int IDX_LLC_LOAD_MISSES      = 5;
    public static final int IDX_LLC_STORES           = 6;
    public static final int IDX_LLC_STORE_MISSES     = 7;
    public static final int IDX_BRANCH_INSTRUCTIONS  = 8;
    public static final int IDX_BRANCH_MISSES        = 9;
    public static final int IDX_CONTEXT_SWITCHES     = 10;
    public static final int IDX_CPU_MIGRATIONS       = 11;
    public static final int IDX_PAGE_FAULTS          = 12;
    public static final int IDX_MINOR_FAULTS         = 13;
    public static final int IDX_MAJOR_FAULTS         = 14;

    public static final int N_COUNTERS = 15;

    /** Canonical names, indexed by IDX_*. */
    public static final String[] NAMES = {
            "cycles", "instructions", "cache-references", "cache-misses",
            "LLC-loads", "LLC-load-misses", "LLC-stores", "LLC-store-misses",
            "branch-instructions", "branch-misses",
            "context-switches", "cpu-migrations",
            "page-faults", "minor-faults", "major-faults"
    };

    /** Default minimal counter set when the YAML omits {@code perfCounters}. */
    public static final List<String> DEFAULT_COUNTERS = Collections.unmodifiableList(Arrays.asList(
            "cycles", "instructions",
            "LLC-loads", "LLC-load-misses",
            "context-switches", "cpu-migrations"
    ));

    /** Case-insensitive name → IDX_* table. Accepts dashes, camelCase
     *  and underscores. Built once at class init. */
    private static final Map<String, Integer> NAME_TO_IDX = buildAliasTable();

    private static Map<String, Integer> buildAliasTable() {
        Map<String, Integer> m = new HashMap<>();
        // canonical names
        for (int i = 0; i < NAMES.length; i++) m.put(norm(NAMES[i]), i);
        // also accept camelCase / variants commonly used in YAML
        m.put(norm("cacheReferences"),     IDX_CACHE_REFERENCES);
        m.put(norm("cacheMisses"),         IDX_CACHE_MISSES);
        m.put(norm("llcLoads"),            IDX_LLC_LOADS);
        m.put(norm("llcLoadMisses"),       IDX_LLC_LOAD_MISSES);
        m.put(norm("llcStores"),           IDX_LLC_STORES);
        m.put(norm("llcStoreMisses"),      IDX_LLC_STORE_MISSES);
        m.put(norm("branchInstructions"),  IDX_BRANCH_INSTRUCTIONS);
        m.put(norm("branchMisses"),        IDX_BRANCH_MISSES);
        m.put(norm("contextSwitches"),     IDX_CONTEXT_SWITCHES);
        m.put(norm("cpuMigrations"),       IDX_CPU_MIGRATIONS);
        m.put(norm("pageFaults"),          IDX_PAGE_FAULTS);
        m.put(norm("minorFaults"),         IDX_MINOR_FAULTS);
        m.put(norm("majorFaults"),         IDX_MAJOR_FAULTS);
        return m;
    }

    private static String norm(String s) {
        return s.toLowerCase().replace("-", "").replace("_", "");
    }

    /**
     * Resolve a list of user-supplied counter names to a boolean mask
     * of length {@link #N_COUNTERS}. Unknown names are reported on
     * stderr and ignored. {@code null} or empty input falls back to
     * {@link #DEFAULT_COUNTERS}.
     */
    public static boolean[] resolveEnableMask(List<String> names) {
        boolean[] mask = new boolean[N_COUNTERS];
        List<String> src = (names == null || names.isEmpty()) ? DEFAULT_COUNTERS : names;
        for (String raw : src) {
            if (raw == null) continue;
            Integer idx = NAME_TO_IDX.get(norm(raw.trim()));
            if (idx == null) {
                System.err.println("[perf] unknown counter name ignored: '" + raw + "'");
                continue;
            }
            mask[idx] = true;
        }
        return mask;
    }

    /* ----- raw perf_event_attr (type, config) values ----- */

    private static final int PERF_TYPE_HARDWARE = 0;
    private static final int PERF_TYPE_SOFTWARE = 1;
    private static final int PERF_TYPE_HW_CACHE = 3;

    private static final int HW_CPU_CYCLES          = 0;
    private static final int HW_INSTRUCTIONS        = 1;
    private static final int HW_CACHE_REFERENCES    = 2;
    private static final int HW_CACHE_MISSES        = 3;
    private static final int HW_BRANCH_INSTRUCTIONS = 4;
    private static final int HW_BRANCH_MISSES       = 5;

    private static final int SW_PAGE_FAULTS         = 2;
    private static final int SW_CONTEXT_SWITCHES    = 3;
    private static final int SW_CPU_MIGRATIONS      = 4;
    private static final int SW_PAGE_FAULTS_MIN     = 6;
    private static final int SW_PAGE_FAULTS_MAJ     = 7;

    private static long hwCacheConfig(int cacheId, int opId, int resultId) {
        return (cacheId & 0xFFL) | ((opId & 0xFFL) << 8) | ((resultId & 0xFFL) << 16);
    }
    private static final int CACHE_LL          = 2;
    private static final int CACHE_OP_READ     = 0;
    private static final int CACHE_OP_WRITE    = 1;
    private static final int CACHE_RESULT_ACC  = 0;
    private static final int CACHE_RESULT_MISS = 1;

    public static final int[] TYPES = {
            PERF_TYPE_HARDWARE, PERF_TYPE_HARDWARE,
            PERF_TYPE_HARDWARE, PERF_TYPE_HARDWARE,
            PERF_TYPE_HW_CACHE, PERF_TYPE_HW_CACHE,
            PERF_TYPE_HW_CACHE, PERF_TYPE_HW_CACHE,
            PERF_TYPE_HARDWARE, PERF_TYPE_HARDWARE,
            PERF_TYPE_SOFTWARE, PERF_TYPE_SOFTWARE,
            PERF_TYPE_SOFTWARE, PERF_TYPE_SOFTWARE, PERF_TYPE_SOFTWARE
    };

    public static final long[] CONFIGS = {
            HW_CPU_CYCLES,
            HW_INSTRUCTIONS,
            HW_CACHE_REFERENCES,
            HW_CACHE_MISSES,
            hwCacheConfig(CACHE_LL, CACHE_OP_READ,  CACHE_RESULT_ACC),
            hwCacheConfig(CACHE_LL, CACHE_OP_READ,  CACHE_RESULT_MISS),
            hwCacheConfig(CACHE_LL, CACHE_OP_WRITE, CACHE_RESULT_ACC),
            hwCacheConfig(CACHE_LL, CACHE_OP_WRITE, CACHE_RESULT_MISS),
            HW_BRANCH_INSTRUCTIONS,
            HW_BRANCH_MISSES,
            SW_CONTEXT_SWITCHES,
            SW_CPU_MIGRATIONS,
            SW_PAGE_FAULTS,
            SW_PAGE_FAULTS_MIN,
            SW_PAGE_FAULTS_MAJ
    };

    /* -------------------- library loading -------------------- */

    public static final boolean LOADED;
    public static final String  LOAD_ERROR;

    static {
        boolean loaded = false;
        String err = null;
        try {
            String explicit = System.getProperty("perfbridge.library.path");
            if (explicit != null && !explicit.isBlank()) {
                // System.load() requires an ABSOLUTE path. The user
                // may have passed a relative path (e.g. "..") or a
                // directory containing libperfbridge.so. Normalise:
                //   1. resolve to absolute against cwd
                //   2. if it points at a directory, append the file
                java.nio.file.Path p =
                        java.nio.file.Paths.get(explicit).toAbsolutePath().normalize();
                if (java.nio.file.Files.isDirectory(p)) {
                    p = p.resolve("libperfbridge.so");
                }
                System.load(p.toString());
            } else {
                String cwd = System.getProperty("user.dir", ".");
                String[] candidates = {
                        cwd + "/native/linux-x86_64/libperfbridge.so",
                        cwd + "/libperfbridge.so",
                };
                Throwable last = null;
                boolean ok = false;
                for (String p : candidates) {
                    try {
                        // Defensive: absolutize even though cwd is
                        // typically already absolute on Linux.
                        String abs = java.nio.file.Paths.get(p)
                                .toAbsolutePath().normalize().toString();
                        System.load(abs);
                        ok = true;
                        break;
                    } catch (Throwable t) { last = t; }
                }
                if (!ok) {
                    try { System.loadLibrary("perfbridge"); ok = true; }
                    catch (Throwable t) { last = t; }
                }
                if (!ok) throw last;
            }
            int avail = nativeAvailable();
            if (avail == 1) loaded = true;
            else err = "nativeAvailable() returned " + avail + " (non-Linux build)";
        } catch (Throwable t) {
            err = t.getClass().getSimpleName() + ": " + t.getMessage();
        }
        LOADED = loaded;
        LOAD_ERROR = err;
    }

    /* -------------------- public API -------------------- */

    /**
     * Open only the counters where {@code enable[i] == true}. Other
     * slots are returned as fd = -1 so the row schema stays the same.
     * Pass {@code null} to open the full set (legacy behaviour).
     */
    public static int[] openForCurrentThread(boolean[] enable) {
        int[] fds = new int[N_COUNTERS];
        if (!LOADED) {
            Arrays.fill(fds, -1);
            return fds;
        }
        int[]  types   = new int[N_COUNTERS];
        long[] configs = new long[N_COUNTERS];
        for (int i = 0; i < N_COUNTERS; i++) {
            if (enable == null || enable[i]) {
                types[i]   = TYPES[i];
                configs[i] = CONFIGS[i];
            } else {
                types[i]   = -1;  // sentinel: native bridge skips this slot
                configs[i] = 0L;
            }
        }
        return nativeOpen(types, configs);
    }

    /**
     * Read every counter's raw triple {@code (value, time_enabled,
     * time_running)} into the three parallel output arrays. NO
     * scaling is performed: the caller computes deltas and applies
     * scaling on the delta (see class doc and {@link #scaleDelta}).
     *
     * <p>Each output array must be at least {@link #N_COUNTERS}
     * long. For unavailable counters (fd == -1) all three outputs
     * are 0. Returns the number of fds whose read produced 24 bytes.
     */
    public static int readAllRaw(int[] fds,
                                 long[] values,
                                 long[] enabled,
                                 long[] running) {
        if (!LOADED || fds == null) {
            if (values  != null) Arrays.fill(values,  0L);
            if (enabled != null) Arrays.fill(enabled, 0L);
            if (running != null) Arrays.fill(running, 0L);
            return 0;
        }
        return nativeReadAllRaw(fds, values, enabled, running);
    }

    /**
     * Apply the standard perf multiplexing correction to a delta:
     * <pre>
     *   if deltaRunning > 0:  scaled = deltaValue * deltaEnabled / deltaRunning
     *   else                 scaled = 0
     * </pre>
     *
     * <p>Uses {@code double} arithmetic and {@link Math#round}.
     * Precision is adequate for per-task counts &lt; 2^53 (~9e15),
     * which exceeds any realistic per-task counter.
     *
     * <p>Allocation-free.
     */
    public static long scaleDelta(long deltaValue, long deltaEnabled, long deltaRunning) {
        if (deltaRunning <= 0L) return 0L;
        if (deltaEnabled == deltaRunning) return deltaValue;
        return Math.round((double) deltaValue * (double) deltaEnabled / (double) deltaRunning);
    }

    public static void closeAll(int[] fds) {
        if (!LOADED || fds == null) return;
        nativeCloseAll(fds);
    }

    public static int currentCpu() {
        if (!LOADED) return -1;
        return nativeGetCpu();
    }

    /* -------------------- native methods -------------------- */

    private static native int[] nativeOpen(int[] types, long[] configs);
    private static native int   nativeReadAllRaw(int[] fds,
                                                 long[] values,
                                                 long[] enabled,
                                                 long[] running);
    private static native void  nativeCloseAll(int[] fds);
    private static native int   nativeGetCpu();
    private static native int   nativeAvailable();
}

