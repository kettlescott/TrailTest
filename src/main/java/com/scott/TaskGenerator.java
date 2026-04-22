package com.scott;

import java.util.ArrayList;
import java.util.List;

/**
 * Central factory for per-task {@link Workload} instances, driven by a
 * {@link WorkloadConfig}.
 *
 * <p>Determinism: all random choices use a SplitMix-style hash of
 * {@code seed ^ taskId}; the same YAML + seed therefore produces the
 * same stream of tasks across runs.
 */
public final class TaskGenerator {

    // ---- Size-hint tier thresholds (estimated wall-clock nanoseconds per task) ----
    // Chosen so typical CPU base-iteration tasks (~targetTaskNanos = 100 us) land
    // in SHORT, ~1 ms tasks in MEDIUM, and >= 5 ms tasks in LONG.
    private static final long SHORT_MAX_NANOS  = 100_000L;     // < 100 us  -> SHORT
    private static final long MEDIUM_MAX_NANOS = 5_000_000L;   // < 5 ms    -> MEDIUM; else LONG

    // Rough per-step cost estimates for MEMORY workloads.
    private static final long MEM_NS_PER_STEP_SEQUENTIAL = 2L;    // L1-hit-ish
    private static final long MEM_NS_PER_STEP_RANDOM     = 60L;   // LLC / DRAM miss-ish

    private static final class ComponentState {
        final WorkloadResourceType resource;
        final WorkloadProfile profile;
        final int iterations;                                // cpu only
        final long waitNanos;                                // io only
        final int steps;                                     // memory only
        final MemoryBoundWorkload.AccessPattern pattern;     // memory only
        final long[] memoryBuffer;                           // memory only, shared

        ComponentState(WorkloadResourceType resource,
                       WorkloadProfile profile,
                       int baseIterations,
                       long baseSeed) {
            this.resource = resource;
            // Guarantee a non-null profile so downstream helpers
            // (explicitTaskType, sizeHint) never NPE for EMPTY workloads.
            this.profile = profile != null ? profile : WorkloadProfile.empty();
            switch (resource) {
                case CPU -> {
                    this.iterations   = Math.max(1, baseIterations * this.profile.iterationsMultiplier());
                    this.waitNanos    = 0L;
                    this.steps        = 0;
                    this.pattern      = null;
                    this.memoryBuffer = null;
                }
                case IO -> {
                    this.iterations   = 0;
                    this.waitNanos    = this.profile.totalWaitNanos();
                    this.steps        = 0;
                    this.pattern      = null;
                    this.memoryBuffer = null;
                }
                case MEMORY -> {
                    this.iterations   = 0;
                    this.waitNanos    = 0L;
                    this.steps        = this.profile.steps();
                    this.pattern      = MemoryBoundWorkload.parsePattern(this.profile.accessPattern());
                    int n = this.profile.arraySize();
                    long[] buf = new long[n];
                    long x = baseSeed == 0L ? 0x9E3779B97F4A7C15L : baseSeed;
                    for (int i = 0; i < n; i++) {
                        x ^= (x << 13); x ^= (x >>> 7); x ^= (x << 17);
                        buf[i] = x;
                    }
                    this.memoryBuffer = buf;
                }
                case EMPTY -> {
                    this.iterations   = 0;
                    this.waitNanos    = 0L;
                    this.steps        = 0;
                    this.pattern      = null;
                    this.memoryBuffer = null;
                }
                default -> throw new IllegalStateException("Unexpected resource: " + resource);
            }
        }
    }

    private final WorkloadConfig workload;
    private final long seed;
    private final boolean single;

    private final ComponentState singleState;
    private final ComponentState[] mixStates;
    private final int[] mixCumulativeWeight;

    public TaskGenerator(WorkloadConfig workload, int baseIterations, long seed) {
        this.workload = workload;
        this.seed = seed;
        this.single = workload.isSingle();

        if (single) {
            this.singleState = new ComponentState(
                    workload.resourceType(), workload.profile(), baseIterations, seed);
            this.mixStates = null;
            this.mixCumulativeWeight = null;
        } else {
            this.singleState = null;
            List<WorkloadComponentConfig> comps = workload.components();
            List<ComponentState> states = new ArrayList<>(comps.size());
            int[] cum = new int[comps.size()];
            int acc = 0;
            for (int i = 0; i < comps.size(); i++) {
                WorkloadComponentConfig c = comps.get(i);
                states.add(new ComponentState(c.resourceType(), c.profile(), baseIterations, seed + i));
                acc += c.weight();
                cum[i] = acc;
            }
            this.mixStates = states.toArray(new ComponentState[0]);
            this.mixCumulativeWeight = cum;
        }
    }

    public Task nextTask(long taskId, boolean measurement, Runnable onComplete) {
        long taskSeed = seed + taskId;
        ComponentState cs = single ? singleState : selectComponent(taskId);
        Workload w = createWorkload(cs, taskSeed);
        TaskType size = sizeHint(cs);
        int iters = cs.resource == WorkloadResourceType.CPU ? cs.iterations : 0;
        long createdNanos = System.nanoTime();
        return new Task(taskId, size, iters, createdNanos, measurement, w, onComplete);
    }

    private Workload createWorkload(ComponentState cs, long taskSeed) {
        return switch (cs.resource) {
            case CPU    -> new CpuBoundWorkload(taskSeed, cs.iterations);
            case IO     -> new IoBoundWorkload(cs.waitNanos, taskSeed);
            case MEMORY -> new MemoryBoundWorkload(cs.memoryBuffer, cs.steps, cs.pattern, taskSeed);
            case EMPTY  -> new EmptyWorkload(taskSeed);
            case MIXED  -> throw new IllegalStateException("nested mixed not supported");
        };
    }

    private ComponentState selectComponent(long taskId) {
        int draw = drawPercent(taskId);
        for (int i = 0; i < mixCumulativeWeight.length; i++) {
            if (draw < mixCumulativeWeight[i]) {
                return mixStates[i];
            }
        }
        return mixStates[mixStates.length - 1];
    }

    /**
     * Classify a component into SHORT / MEDIUM / LONG.
     *
     * <p>Precedence:
     * <ol>
     *   <li>explicit {@code profile.taskType} from YAML, if set;</li>
     *   <li>resource-specific estimated cost in nanoseconds, bucketed
     *       by {@link #SHORT_MAX_NANOS} / {@link #MEDIUM_MAX_NANOS}.</li>
     * </ol>
     * <p>This replaces the previous "any non-CPU =&gt; SHORT" rule, which
     * mis-routed heavy IO/MEMORY tasks through the sharded executor.
     */
    private static TaskType sizeHint(ComponentState cs) {
        TaskType explicit = cs.profile.explicitTaskType();
        if (explicit != null) return explicit;

        long estNanos = estimatedCostNanos(cs);
        if (estNanos >= MEDIUM_MAX_NANOS) return TaskType.LONG;
        if (estNanos >= SHORT_MAX_NANOS)  return TaskType.MEDIUM;
        return TaskType.SHORT;
    }

    /**
     * Per-resource wall-clock cost estimate in nanoseconds. Used only for
     * size-tier classification; orders of magnitude matter, not precision.
     */
    private static long estimatedCostNanos(ComponentState cs) {
        return switch (cs.resource) {
            // CPU: anchor so multiplier 1 / 10 / 100 lines up with
            // SHORT / MEDIUM / LONG, matching legacy behavior.
            case CPU -> {
                int m = Math.max(1, cs.profile.iterationsMultiplier());
                yield SHORT_MAX_NANOS * m / 10L;    // m=1 -> 10us, m=10 -> 100us, m=100 -> 1ms
            }
            // IO: wall-clock wait dominates; already in nanoseconds.
            case IO -> cs.waitNanos;
            // MEMORY: steps * access-pattern-dependent per-step cost.
            case MEMORY -> {
                long perStep = (cs.pattern == MemoryBoundWorkload.AccessPattern.RANDOM)
                        ? MEM_NS_PER_STEP_RANDOM
                        : MEM_NS_PER_STEP_SEQUENTIAL;
                yield (long) cs.steps * perStep;
            }
            // EMPTY: no payload -> 0 ns -> SHORT (unless explicit override).
            case EMPTY -> 0L;
            // Unreachable: mix is flattened to per-component states.
            case MIXED -> 0L;
        };
    }

    private int drawPercent(long taskId) {
        long z = seed ^ (taskId * 0x9E3779B97F4A7C15L);
        z += 0x9E3779B97F4A7C15L;
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        z = z ^ (z >>> 31);
        return (int) Long.remainderUnsigned(z, 100);
    }

    public WorkloadConfig workload() { return workload; }
}

