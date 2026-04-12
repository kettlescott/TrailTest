package com.scott;

/**
 * Immutable specification for a single benchmark task.
 *
 * <p>{@code TaskSpec} captures everything needed to <em>reproduce</em> a task's
 * workload — the logical ID, the random seed, and the iteration count — without
 * any run-specific mutable state (no timing data, no latch, no thread affinity).
 *
 * <h3>Usage</h3>
 * <p>Pre-generate an array of {@code TaskSpec} objects before any executor runs.
 * Both {@link SharedExecutor} and {@link ShardedExecutor} then iterate the
 * <em>same</em> array, creating fresh {@link Task} and {@link CpuBoundWorkload}
 * instances from each spec.  This guarantees an apples-to-apples comparison:
 * identical workload input, identical task count, identical seed sequence.</p>
 *
 * @param taskId       logical task identifier (unique across the entire run)
 * @param workloadSeed seed passed to {@link CpuBoundWorkload}
 * @param iterations   iteration count passed to {@link CpuBoundWorkload}
 */
public record TaskSpec(long taskId, long workloadSeed, int iterations) { }

