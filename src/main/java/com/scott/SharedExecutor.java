package com.scott;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Shared Executor – baseline implementation.
 *
 * <p>Design highlights that satisfy the specification:
 * <ul>
 *   <li>Standard JDK {@link ThreadPoolExecutor} under the hood.</li>
 *   <li>Single centralized {@link LinkedBlockingQueue} shared by all workers.</li>
 *   <li>Fixed-size worker pool ({@code corePoolSize == maximumPoolSize}).</li>
 *   <li>Deterministic worker thread names ({@code SharedWorker-0}, {@code SharedWorker-1}, …).</li>
 *   <li>{@link #getQueueSize()} exposes the current queue depth for external measurement.</li>
 *   <li>Every submitted {@link Task} self-records <em>start</em> and <em>finish</em>
 *       timestamps inside its own {@link Task#run()} method. The
 *       dispatcher is responsible for stamping {@code enqueuedNanos}
 *       before calling {@link #submit(Task)}.</li>
 *   <li>Collected tasks are available through {@link #getTasks()} for latency analysis.</li>
 * </ul>
 */
public final class SharedExecutor implements BenchmarkExecutor {

    private final ThreadPoolExecutor executor;
    private final BlockingQueue<Runnable> workQueue;

    // ---- task tracking (DEBUG-only) ----
    // When BenchmarkFlags.DEBUG is true, every submitted Task reference is
    // stored for diagnostic use via getTasks().  When false (default), the
    // list is null and submit() performs zero list mutations — no array
    // growth, no element copies, no GC pressure on the hot path.
    private final ArrayList<Task> taskList;

    // Simple counters — only the (single) submitting thread writes these,
    // so plain int is safe.  Read after shutdown.
    private int totalSubmitCount;

    /**
     * Number of measurement-phase tasks submitted.  Only the submitting
     * thread writes this (single-threaded submit model), so no
     * synchronization is needed.
     */
    private int measurementSubmitCount;

    /**
     * Creates the shared executor.
     *
     * @param poolSize number of fixed worker threads
     */
    public SharedExecutor(int poolSize) {
        this(poolSize, PinningConfig.disabled());
    }

    /**
     * Creates the shared executor with optional CPU pinning.
     *
     * <p>When {@code pinning.enabled()} is {@code true}, each worker thread
     * pins itself to a CPU at startup using round-robin assignment over
     * {@code pinning.coreMap()}:
     * {@code cpuId = coreMap[threadIndex % coreMap.length]}.
     * If affinity setting fails, a warning is logged and the worker
     * continues unpinned.
     *
     * @param poolSize number of fixed worker threads
     * @param pinning  optional pinning config; if null or disabled, behaves identically to the legacy constructor
     */
    public SharedExecutor(int poolSize, PinningConfig pinning) {
        this.workQueue = new LinkedBlockingQueue<>();
        this.taskList  = BenchmarkFlags.DEBUG ? new ArrayList<>() : null;
        this.executor = new ThreadPoolExecutor(
                poolSize,                       // corePoolSize  (fixed)
                poolSize,                       // maximumPoolSize (fixed)
                0L, TimeUnit.MILLISECONDS,      // keep-alive (irrelevant for fixed pool)
                workQueue,                      // single shared queue
                new DeterministicThreadFactory(pinning) // predictable thread names + optional pinning
        );
    }

    /* ---- submission ---- */

    /**
     * Submits a pre-built {@link Task} for execution.
     *
     * <h3>Hot-path design</h3>
     * <p>When {@link BenchmarkFlags#DEBUG} is {@code false} (default),
     * submit performs only: two int increments + executor.execute().
     * No list mutation, no object allocation beyond what the TPE does
     * internally.  When DEBUG is {@code true}, the task is also recorded
     * in an internal list for {@link #getTasks()}.
     *
     * @param task the benchmark task (submit timestamp should already be set)
     */
    @Override
    public void submit(Task task) {
        if (BenchmarkFlags.DEBUG && taskList != null) {
            taskList.add(task);
        }
        totalSubmitCount++;
        if (task.isMeasurement()) {
            measurementSubmitCount++;
        }
        executor.execute(task);
    }


    /* ---- observation ---- */

    /**
     * Returns the current number of tasks waiting in the shared queue
     * (does <em>not</em> include the tasks already picked up by workers).
     */
    public int getQueueSize() {
        return workQueue.size();
    }

    /**
     * Returns an unmodifiable view of all submitted {@link Task}s.
     *
     * <p>Only populated when {@link BenchmarkFlags#DEBUG} is {@code true}.
     * Returns an empty list when task tracking is disabled (default).
     */
    public List<Task> getTasks() {
        if (taskList == null) return Collections.emptyList();
        return Collections.unmodifiableList(taskList);
    }

    /**
     * Returns the number of worker threads in the pool.
     */
    public int getPoolSize() {
        return executor.getCorePoolSize();
    }

    /**
     * Returns the number of measurement-phase tasks submitted.
     */
    public int getMeasurementSubmitCount() {
        return measurementSubmitCount;
    }

    /**
     * Prints a task distribution summary to stdout.
     *
     * <p>Since all workers share a single queue there is no per-worker
     * breakdown.  Reports total and measurement-only counts for parity
     * with {@link ShardedExecutor#printQueueDistribution()}.
     *
     * <p>Call after {@link #shutdown()} and
     * {@link #awaitTermination(long, TimeUnit)} to get final counts.
     */
    public void printQueueDistribution() {
        long completed = executor.getCompletedTaskCount();
        int  poolSize  = executor.getCorePoolSize();

        System.out.println();
        System.out.println("=== Queue Task Distribution (SharedExecutor) ===");
        System.out.printf("  queue topology         : 1 shared queue, %d workers%n", poolSize);
        System.out.printf("  submitted (all phases) : %,d%n", totalSubmitCount);
        System.out.printf("  submitted (measurement): %,d%n", measurementSubmitCount);
        System.out.printf("  completed (all phases) : %,d%n", completed);
        System.out.printf("  remaining queue        : %d%n", workQueue.size());
    }

    /* ---- lifecycle ---- */

    /**
     * Initiates an orderly shutdown – already-submitted tasks will still execute.
     */
    public void shutdown() {
        executor.shutdown();
    }

    /**
     * Blocks until all tasks finish or the timeout expires.
     *
     * @return {@code true} if the executor terminated within the timeout
     */
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    /* ---- deterministic thread naming ---- */

    private static final class DeterministicThreadFactory implements ThreadFactory {
        private final AtomicInteger counter = new AtomicInteger(0);
        private final PinningConfig pinning;

        DeterministicThreadFactory() {
            this(PinningConfig.disabled());
        }

        DeterministicThreadFactory(PinningConfig pinning) {
            this.pinning = (pinning == null) ? PinningConfig.disabled() : pinning;
        }

        @Override
        public Thread newThread(Runnable r) {
            int idx = counter.getAndIncrement();
            Runnable body = r;
            if (pinning.enabled() && pinning.coreMap() != null && pinning.coreMap().length > 0) {
                int[] map = pinning.coreMap();
                final int coreId = map[idx % map.length];
                final int workerIdx = idx;
                body = () -> {
                    try {
                        CpuAffinity.pinCurrentThreadToCore(coreId);
                    } catch (Throwable e) {
                        System.err.printf("[SharedWorker-%d] WARN: failed to pin to core %d: %s%n",
                                workerIdx, coreId, e.getMessage());
                    }
                    r.run();
                };
            }
            Thread t = new Thread(body, "SharedWorker-" + idx);
            t.setDaemon(false);
            return t;
        }
    }
}
