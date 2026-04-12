package com.scott;

/**
 * Example usage of {@link ShardedExecutor} with and without CPU core pinning.
 *
 * <p>This class demonstrates how to set up the A/B benchmark experiment:
 * <ul>
 *   <li><b>A</b> — {@code ShardedExecutor} without pinning (default OS scheduling)</li>
 *   <li><b>B</b> — {@code ShardedExecutor} with per-worker core pinning</li>
 * </ul>
 *
 * <h3>JVM launch options (required for FFM in Java 21)</h3>
 * <p>The {@link CpuAffinity} helper uses the Foreign Function &amp; Memory API
 * (Project Panama / FFM), which is a <em>preview</em> feature in Java 21.
 * The following JVM flags are needed:</p>
 * <pre>
 *   java --enable-preview \
 *        --enable-native-access=ALL-UNNAMED \
 *        -cp target/classes com.scott.PinningExample
 * </pre>
 * <ul>
 *   <li>{@code --enable-preview} — unlocks preview FFM classes
 *       ({@code java.lang.foreign.*}).  Required at both compile time
 *       (see pom.xml) and runtime.</li>
 *   <li>{@code --enable-native-access=ALL-UNNAMED} — permits the unnamed
 *       module to call native functions via {@code Linker.downcallHandle}.
 *       Without this flag the JVM throws {@code IllegalCallerException}
 *       when {@link CpuAffinity} attempts to link {@code sched_setaffinity}.
 *       If your code is in a named module, replace {@code ALL-UNNAMED} with
 *       the module name.</li>
 * </ul>
 * <p>In <b>Java 22+</b> (JEP 454, FFM finalized), {@code --enable-preview}
 * is no longer needed; only {@code --enable-native-access} remains.</p>
 *
 * <h3>Running on Linux</h3>
 * <pre>
 *   # Check available cores:
 *   lscpu | grep "^CPU(s):"
 *   nproc
 *
 *   # Run:
 *   java --enable-preview --enable-native-access=ALL-UNNAMED \
 *        -cp target/classes com.scott.PinningExample
 *
 *   # Verify pinning from another shell while the benchmark runs:
 *   ps -T -p $(pgrep -f PinningExample) | grep ShardedWorker
 *   # Then for each TID:
 *   taskset -p &lt;tid&gt;
 * </pre>
 *
 * <h3>Running on non-Linux</h3>
 * <p>The non-pinned mode works on any OS.  The pinned mode will print a
 * warning and fall back to unpinned scheduling on Windows/macOS.  Use
 * {@link CpuAffinity#isSupported()} to check before enabling pinning.</p>
 */
public class PinningExample {

    public static void main(String[] args) throws Exception {

        final int workerCount = 4;

        System.out.println("=== Platform check ===");
        System.out.printf("  OS              : %s%n", System.getProperty("os.name"));
        System.out.printf("  Available cores : %d%n", Runtime.getRuntime().availableProcessors());
        System.out.printf("  Pinning support : %s%n", CpuAffinity.isSupported() ? "YES" : "NO");
        System.out.println();

        // ----------------------------------------------------------------
        //  Mode A — ShardedExecutor WITHOUT pinning
        // ----------------------------------------------------------------
        System.out.println("========================================");
        System.out.println("  Mode A: ShardedExecutor (no pinning)");
        System.out.println("========================================");
        {
            // Original constructor — no pinning, identical to the old behaviour.
            ShardedExecutor executor = new ShardedExecutor(workerCount);
            runDemo(executor, "no-pin");
        }

        System.out.println();

        // ----------------------------------------------------------------
        //  Mode B — ShardedExecutor WITH per-worker core pinning
        // ----------------------------------------------------------------
        System.out.println("========================================");
        System.out.println("  Mode B: ShardedExecutor (pinned)");
        System.out.println("========================================");
        {
            // Build a core map: worker i → core i.
            // On a machine with >= workerCount cores this pins each worker
            // to a distinct core.  Adjust the mapping for your topology.
            //
            // Example mappings:
            //   Sequential:     {0, 1, 2, 3}
            //   Skip HT pairs:  {0, 2, 4, 6}   (pin to physical cores only)
            //   Same socket:    {0, 1, 2, 3}
            //   Cross-socket:   {0, 4, 1, 5}    (NUMA interleave)
            int[] coreMap = new int[workerCount];
            for (int i = 0; i < workerCount; i++) {
                coreMap[i] = i;   // worker-0 → core-0, worker-1 → core-1, ...
            }

            if (!CpuAffinity.isSupported()) {
                System.out.println("  (Pinning not supported on this OS — ");
                System.out.println("   workers will run unpinned with a warning.)");
            }

            ShardedExecutor executor = new ShardedExecutor(workerCount, true, coreMap);
            runDemo(executor, "pinned");
        }
    }

    /**
     * Submits a handful of tasks and prints basic results.
     * This is intentionally simple — for a real benchmark use
     * {@link BenchmarkMain}.
     */
    private static void runDemo(ShardedExecutor executor, String label) throws Exception {

        final int taskCount = 16;
        TaskTimingStore store = new TaskTimingStore(taskCount);
        java.util.concurrent.CountDownLatch latch =
                new java.util.concurrent.CountDownLatch(taskCount);

        int iterations = WorkloadCalibrator.calibrateIterations(2_000_000L, 0xCAFEL);

        for (int i = 0; i < taskCount; i++) {
            Workload w = new CpuBoundWorkload(0xCAFE + i, iterations);
            Task task = new Task(i, i, w, store, latch, false);
            store.recordSubmit(i, System.nanoTime());
            executor.submit(task);
        }

        latch.await();

        System.out.printf("  [%s] All %d tasks completed.%n", label, taskCount);
        System.out.printf("  [%s] Pinning enabled: %s%n", label, executor.isPinningEnabled());

        executor.shutdown();
        executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS);

        System.out.printf("  [%s] Executor shut down cleanly.%n", label);
    }
}
