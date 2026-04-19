package com.scott;

public final class TaskGenerator {

	private final WorkloadConfig workload;
	private final int baseIterations;
	private final long seed;
	private final boolean single;
	private final TaskType singleType;
	private final int shortPct;
	private final int mediumThreshold;

	public TaskGenerator(WorkloadConfig workload, int baseIterations, long seed) {
		this.workload = workload;
		this.baseIterations = baseIterations;
		this.seed = seed;
		this.single = workload.isSingle();
		this.singleType = single ? TaskType.fromLabel(workload.type()) : null;
		this.shortPct = single ? 0 : workload.valueFor("short");
		this.mediumThreshold = single ? 0 : shortPct + workload.valueFor("medium");
	}

	public TaskType taskTypeFor(long taskId) {
		if (single) {
			return singleType;
		}

		int draw = drawPercent(taskId);
		if (draw < shortPct) {
			return TaskType.SHORT;
		}
		if (draw < mediumThreshold) {
			return TaskType.MEDIUM;
		}
		return TaskType.LONG;
	}

	public Task nextTask(long taskId, boolean measurement, Runnable onComplete) {
		TaskType type = taskTypeFor(taskId);
		int iterations = Math.max(1, baseIterations * type.iterationMultiplier());
		long submitNanos = System.nanoTime();
		long taskSeed = seed + taskId;
		return new Task(taskId, type, iterations, submitNanos, measurement, taskSeed, onComplete);
	}

	private int drawPercent(long taskId) {
		if (!"shuffled".equalsIgnoreCase(workload.generation())) {
			throw new IllegalArgumentException("Only generation=shuffled is currently supported");
		}
		long z = seed ^ (taskId * 0x9E3779B97F4A7C15L);
		z += 0x9E3779B97F4A7C15L;
		z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
		z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
		z = z ^ (z >>> 31);
		return (int) Long.remainderUnsigned(z, 100);
	}
}

