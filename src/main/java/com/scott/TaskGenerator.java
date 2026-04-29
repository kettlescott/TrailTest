package com.scott;

import java.util.ArrayList;
import java.util.List;

/**
 * Central factory for per-task {@link Workload} instances, driven by a
 * {@link WorkloadConfig} list of {@link WorkloadEntry}.
 *
 * <p>Per entry, sizing is computed once at startup:
 * <ul>
 *   <li>CPU    — {@link WorkloadCalibrator#calibrateIterations}</li>
 *   <li>MEMORY — shared {@code long[]} buffer + calibrated step count</li>
 *   <li>IO     — no calibration; {@code targetMillis} is parked directly</li>
 * </ul>
 *
 * <p>Determinism: all random choices use a SplitMix-style hash of
 * {@code seed ^ taskId}; identical YAML + seed produces the same task
 * stream across runs.
 */
public final class TaskGenerator {

    /**
     * Per-{@link WorkloadEntry} calibration record. Surfaced via
     * {@link #calibrations()} so {@link BenchmarkMain} can include the
     * actual calibrated sizes (not just the YAML target) in summary
     * output for reproducibility.
     *
     * <p>Fields that don't apply to a given {@code kind} are zero / null:
     * <ul>
     *   <li>CPU    — {@code cpuIterations} populated; memory fields zero/null</li>
     *   <li>MEMORY — {@code memorySteps}, {@code memoryBufferMB},
     *               {@code memoryAccessPattern}, {@code memoryWriteBack}
     *               populated; {@code cpuIterations} = 0</li>
     *   <li>IO     — all fields zero/null (no calibration; parkNanos)</li>
     * </ul>
     */
    public record Calibration(
            String name,
            WorkloadKind kind,
            long targetMillis,
            int cpuIterations,
            int memorySteps,
            int memoryBufferMB,
            MemoryBoundWorkload.AccessPattern memoryAccessPattern,
            boolean memoryWriteBack
    ) {
        public String summary() {
            return switch (kind) {
                case CPU    -> String.format(
                        "name=%s, kind=CPU, targetMillis=%d, cpuIterations=%d",
                        name, targetMillis, cpuIterations);
                case MEMORY -> String.format(
                        "name=%s, kind=MEMORY, targetMillis=%d, memorySteps=%d, "
                                + "bufferMB=%d, accessPattern=%s, writeBack=%s",
                        name, targetMillis, memorySteps,
                        memoryBufferMB, memoryAccessPattern, memoryWriteBack);
                case IO     -> String.format(
                        "name=%s, kind=IO, targetMillis=%d  (no calibration; parkNanos)",
                        name, targetMillis);
            };
        }
    }

    private static final class EntryState {
        final WorkloadEntry entry;
        final int cpuIterations;                                // CPU only
        final int memorySteps;                                  // MEMORY only
        final long[] memoryBuffer;                              // MEMORY only (shared)
        final MemoryBoundWorkload.AccessPattern memoryPattern;  // MEMORY only
        final boolean memoryWriteBack;                          // MEMORY only

        EntryState(WorkloadEntry entry, long baseSeed) {
            this.entry = entry;
            long targetNanos = entry.targetMillis() * 1_000_000L;
            switch (entry.kind()) {
                case CPU -> {
                    this.cpuIterations = WorkloadCalibrator.calibrateIterations(targetNanos, baseSeed);
                    this.memorySteps     = 0;
                    this.memoryBuffer    = null;
                    this.memoryPattern   = null;
                    this.memoryWriteBack = false;
                }
                case MEMORY -> {
                    MemoryWorkloadConfig mem = entry.memoryOrDefaults();
                    long[] buf = allocateBuffer(mem.bufferLongs(), baseSeed);
                    this.memoryBuffer    = buf;
                    this.memoryPattern   = mem.accessPattern();
                    this.memoryWriteBack = mem.writeBack();
                    this.memorySteps     = WorkloadCalibrator.calibrateMemorySteps(
                            targetNanos, buf, this.memoryPattern, this.memoryWriteBack, baseSeed);
                    this.cpuIterations   = 0;
                }
                case IO -> {
                    this.cpuIterations   = 0;
                    this.memorySteps     = 0;
                    this.memoryBuffer    = null;
                    this.memoryPattern   = null;
                    this.memoryWriteBack = false;
                }
                default -> throw new IllegalStateException("Unknown WorkloadKind: " + entry.kind());
            }
        }

        private static long[] allocateBuffer(int n, long seed) {
            long[] buf = new long[n];
            long x = seed == 0L ? 0x9E3779B97F4A7C15L : seed;
            for (int i = 0; i < n; i++) {
                x ^= (x << 13); x ^= (x >>> 7); x ^= (x << 17);
                buf[i] = x;
            }
            return buf;
        }
    }

    private final WorkloadConfig workload;
    private final long seed;
    private final EntryState[] states;
    private final double[] cumulative; // normalized cdf, length == states.length

    public TaskGenerator(WorkloadConfig workload, long seed) {
        this.workload = workload;
        this.seed = seed;

        List<WorkloadEntry> entries = workload.entries();
        this.states = new EntryState[entries.size()];
        double sum = 0.0;
        double[] raw = new double[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            WorkloadEntry e = entries.get(i);
            states[i] = new EntryState(e, seed + i);
            raw[i] = e.ratio();
            sum += e.ratio();
        }
        this.cumulative = new double[entries.size()];
        double acc = 0.0;
        for (int i = 0; i < entries.size(); i++) {
            acc += raw[i] / sum;
            cumulative[i] = acc;
        }
        // Guard against floating-point drift on the last bucket.
        cumulative[cumulative.length - 1] = 1.0;
    }


    public Task nextTask(long taskId, boolean measurement, Runnable onComplete) {
        long taskSeed = seed + taskId;
        EntryState es = selectEntry(taskId);
        Workload w = createWorkload(es, taskSeed);
        long createdNanos = System.nanoTime();
        return new Task(taskId,
                es.entry.kind(),
                es.entry.targetMillis(),
                createdNanos,
                measurement,
                w,
                onComplete);
    }

    private Workload createWorkload(EntryState es, long taskSeed) {
        return switch (es.entry.kind()) {
            case CPU    -> new CpuBoundWorkload(taskSeed, es.cpuIterations);
            case MEMORY -> new MemoryBoundWorkload(
                    es.memoryBuffer, es.memorySteps, es.memoryPattern, taskSeed, es.memoryWriteBack);
            case IO     -> new SyntheticBlockingIOWorkload(es.entry.targetMillis(), taskSeed);
        };
    }

    private EntryState selectEntry(long taskId) {
        if (states.length == 1) return states[0];
        double u = drawUnit(taskId);
        for (int i = 0; i < cumulative.length; i++) {
            if (u < cumulative[i]) return states[i];
        }
        return states[states.length - 1];
    }

    /** Deterministic uniform [0,1) draw. */
    private double drawUnit(long taskId) {
        long z = seed ^ (taskId * 0x9E3779B97F4A7C15L);
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        // Take top 53 bits, divide into [0,1).
        long bits = z >>> 11;
        return bits / (double) (1L << 53);
    }

    public WorkloadConfig workload() { return workload; }

    /**
     * Returns one {@link Calibration} per workload entry, in declared
     * order. Computed at construction time, so this is a cheap accessor.
     */
    public List<Calibration> calibrations() {
        List<Calibration> out = new ArrayList<>(states.length);
        for (EntryState s : states) {
            int bufferMB = s.memoryBuffer == null
                    ? 0
                    : (int) ((long) s.memoryBuffer.length * Long.BYTES / (1L << 20));
            out.add(new Calibration(
                    s.entry.displayName(),
                    s.entry.kind(),
                    s.entry.targetMillis(),
                    s.cpuIterations,
                    s.memorySteps,
                    bufferMB,
                    s.memoryPattern,
                    s.memoryWriteBack));
        }
        return out;
    }
}
