package com.scott;

import java.util.List;
import java.util.LongSummaryStatistics;
import java.util.concurrent.TimeUnit;

/**
 * Demonstration of the Shared Executor (Baseline) with CPU-bound workloads
 * calibrated via {@link WorkloadCalibrator}.
 */
public class Main {

    private static final int  POOL_SIZE  = 4;
    private static final int  TASK_COUNT = 20;
    private static final long SEED       = 0xCAFEBABEL;

    public static void main(String[] args) throws InterruptedException {

        // ---- Calibration phase ----
        System.out.println("=== Workload Calibration ===");
        int shortIters  = WorkloadCalibrator.shortWorkload(SEED);
        int mediumIters = WorkloadCalibrator.mediumWorkload(SEED);
        int longIters   = WorkloadCalibrator.longWorkload(SEED);
        System.out.printf("  short  (~1 ms)   → %,d iterations%n", shortIters);
        System.out.printf("  medium (~10 ms)  → %,d iterations%n", mediumIters);
        System.out.printf("  long   (~100 ms) → %,d iterations%n", longIters);

        // Pick medium workload for the demo
        int workloadIters = mediumIters;
        System.out.println();

        // ---- Executor demo ----
        System.out.println("=== Shared Executor Baseline Demo ===");
        System.out.println("Pool size : " + POOL_SIZE);
        System.out.println("Tasks     : " + TASK_COUNT);
        System.out.printf("Workload  : CpuBoundWorkload (seed=%d, iterations=%,d)%n%n", SEED, workloadIters);

        SharedExecutor executor = new SharedExecutor(POOL_SIZE);

        // Submit CPU-bound tasks
        for (int i = 0; i < TASK_COUNT; i++) {
            var workload = new CpuBoundWorkload(SEED + i, workloadIters);
            executor.submit(i, workload);
            System.out.printf("Submitted Task-%d  |  queue size = %d%n", i, executor.getQueueSize());
        }

        // Graceful shutdown
        executor.shutdown();
        boolean finished = executor.awaitTermination(60, TimeUnit.SECONDS);
        System.out.println("\nAll tasks completed: " + finished);

        // ---- Latency report ----
        List<Task> tasks = executor.getTasks();

        System.out.println("\n--- Per-Task Latency Report ---");
        for (Task t : tasks) {
            System.out.println(t);
        }

        // Aggregate statistics
        LongSummaryStatistics queueWaitStats = tasks.stream()
                .mapToLong(Task::queueWaitTimeNanos).summaryStatistics();
        LongSummaryStatistics completionStats = tasks.stream()
                .mapToLong(Task::endToEndLatencyNanos).summaryStatistics();

        System.out.println("\n--- Aggregate Statistics ---");
        System.out.printf("Queue wait  →  min=%.3f ms  avg=%.3f ms  max=%.3f ms%n",
                queueWaitStats.getMin()     / 1_000_000.0,
                queueWaitStats.getAverage() / 1_000_000.0,
                queueWaitStats.getMax()     / 1_000_000.0);
        System.out.printf("Completion  →  min=%.3f ms  avg=%.3f ms  max=%.3f ms%n",
                completionStats.getMin()     / 1_000_000.0,
                completionStats.getAverage() / 1_000_000.0,
                completionStats.getMax()     / 1_000_000.0);
    }
}