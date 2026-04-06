package com.scott;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Entry point for the executor benchmark.
 *
 * <ol>
 *   <li>Calibrates {@link CpuBoundWorkload} iteration count for the target duration.</li>
 *   <li>Creates a {@link SharedExecutor} (the dispatcher) with a configurable worker count.</li>
 *   <li>Creates a {@link CountDownLatch} sized to the total number of tasks.</li>
 *   <li>Builds and submits {@link Task} objects, each carrying its own
 *       {@link CpuBoundWorkload} and sharing the completion latch.</li>
 *   <li>Awaits the latch so all tasks finish before proceeding.</li>
 *   <li>Feeds every completed task into a {@link LatencyRecorder}.</li>
 *   <li>Prints benchmark configuration and a percentile latency summary.</li>
 *   <li>Shuts down the dispatcher cleanly.</li>
 * </ol>
 */
public class BenchmarkMain {

    /* ---- tunables ---- */

    private static final int  WORKER_COUNT = 4;
    private static final int  TASK_COUNT   = 100;
    private static final long SEED         = 0xDEADBEEFL;

    public static void main(String[] args) throws InterruptedException {

        // ---- 1. Calibrate workload ----
        System.out.println("=== Calibrating workload ===");
        int iterations = WorkloadCalibrator.mediumWorkload(SEED);    // ~10 ms per task
        System.out.printf("  Target ≈ 10 ms  →  %,d iterations%n%n", iterations);

        // ---- 2. Create dispatcher (SharedExecutor) ----
        SharedExecutor dispatcher = new SharedExecutor(WORKER_COUNT);

        // ---- 3. Completion latch ----
        CountDownLatch latch = new CountDownLatch(TASK_COUNT);

        // ---- 4. Build & submit tasks ----
        System.out.println("=== Submitting tasks ===");
        TaskTimingStore timingStore = new TaskTimingStore(TASK_COUNT);
        List<Task> tasks = new ArrayList<>(TASK_COUNT);

        for (int i = 0; i < TASK_COUNT; i++) {
            Workload workload = new CpuBoundWorkload(SEED + i, iterations);
            long submitTime = System.nanoTime();
            timingStore.recordSubmit(i, submitTime);

            Task task = new Task(i, i, workload, timingStore, latch);
            dispatcher.submit(task);
            tasks.add(task);
        }
        System.out.printf("  %d tasks submitted to %d workers%n%n", TASK_COUNT, WORKER_COUNT);

        // ---- 5. Await completion via latch ----
        boolean completed = latch.await(120, TimeUnit.SECONDS);

        if (!completed) {
            System.err.println("ERROR: timed out waiting for tasks to complete!");
        }

        // ---- 6. Record latencies ----
        LatencyRecorder recorder = new LatencyRecorder(TASK_COUNT);
        recorder.recordAll(tasks);

        // ---- 7. Print results ----
        System.out.println("=== Benchmark Configuration ===");
        System.out.printf("  Workers       : %d%n", WORKER_COUNT);
        System.out.printf("  Tasks         : %d%n", TASK_COUNT);
        System.out.printf("  Workload      : CpuBoundWorkload (seed=0x%X, iterations=%,d)%n", SEED, iterations);
        System.out.printf("  All completed : %s%n%n", completed);

        System.out.println("=== Latency Summary ===");
        System.out.println(recorder.summary());

        // ---- 8. Clean shutdown ----
        dispatcher.shutdown();
        dispatcher.awaitTermination(10, TimeUnit.SECONDS);
    }
}

