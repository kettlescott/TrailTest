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
            this.profile = profile;
            switch (resource) {
                case CPU -> {
                    this.iterations   = Math.max(1, baseIterations * profile.iterationsMultiplier());
                    this.waitNanos    = 0L;
                    this.steps        = 0;
                    this.pattern      = null;
                    this.memoryBuffer = null;
                }
                case IO -> {
                    this.iterations   = 0;
                    this.waitNanos    = profile.totalWaitNanos();
                    this.steps        = 0;
                    this.pattern      = null;
                    this.memoryBuffer = null;
                }
                case MEMORY -> {
                    this.iterations   = 0;
                    this.waitNanos    = 0L;
                    this.steps        = profile.steps();
                    this.pattern      = MemoryBoundWorkload.parsePattern(profile.accessPattern());
                    int n = profile.arraySize();
                    long[] buf = new long[n];
                    long x = baseSeed == 0L ? 0x9E3779B97F4A7C15L : baseSeed;
                    for (int i = 0; i < n; i++) {
                        x ^= (x << 13); x ^= (x >>> 7); x ^= (x << 17);
                        buf[i] = x;
                    }
                    this.memoryBuffer = buf;
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
        long submitNanos = System.nanoTime();
        return new Task(taskId, size, iters, submitNanos, measurement, w, onComplete);
    }

    private Workload createWorkload(ComponentState cs, long taskSeed) {
        return switch (cs.resource) {
            case CPU    -> new CpuBoundWorkload(taskSeed, cs.iterations);
            case IO     -> new IoBoundWorkload(cs.waitNanos, taskSeed);
            case MEMORY -> new MemoryBoundWorkload(cs.memoryBuffer, cs.steps, cs.pattern, taskSeed);
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

    /** Classify a component into SHORT/MEDIUM/LONG for display metadata only. */
    private static TaskType sizeHint(ComponentState cs) {
        if (cs.resource != WorkloadResourceType.CPU) return TaskType.SHORT;
        int m = cs.profile.iterationsMultiplier();
        if (m >= 100) return TaskType.LONG;
        if (m >= 10)  return TaskType.MEDIUM;
        return TaskType.SHORT;
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

