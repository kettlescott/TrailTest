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
        this(poolSize, PinningConfig.disabled(), null);
    }

    /**
     * Creates the shared executor with optional CPU pinning.
     */
    public SharedExecutor(int poolSize, PinningConfig pinning) {
        this(poolSize, pinning, null);
    }

    /**
     * Diagnostics-aware constructor. {@code stats} may be null
     * (default) — when non-null, it is updated once per completed
     * task via a {@link ThreadPoolExecutor#afterExecute} override.
     * No per-task allocation: {@code afterExecute} is invoked by the
     * JDK on the worker thread itself.
     *
     * <p>Ordering note: {@code afterExecute} runs <em>after</em> the
     * {@link Task#run()} finally block (and therefore after the
     * in-flight permit has been released). For the SHARED aggregate
     * view used by the diagnostics correlator this lag is bounded by
     * {@code poolSize} tasks and is statistically irrelevant.
     */
    public SharedExecutor(int poolSize, PinningConfig pinning, WorkerStats stats) {
        this.workQueue = new LinkedBlockingQueue<>();
        this.taskList  = BenchmarkFlags.DEBUG ? new ArrayList<>() : null;
        if (stats == null) {
            this.executor = new ThreadPoolExecutor(
                    poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                    workQueue,
                    new DeterministicThreadFactory(pinning));
        } else {
            // Subclass override is the JDK-blessed way to observe per-task
            // completion on the worker thread without wrapping Runnables.
            this.executor = new ThreadPoolExecutor(
                    poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                    workQueue,
                    new DeterministicThreadFactory(pinning)) {
                @Override
                protected void afterExecute(Runnable r, Throwable t) {
                    super.afterExecute(r, t);
                    if (r instanceof Task task) {
                        stats.onTaskCompleted(task);
                    }
                }
            };
        }
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
            final int workerIdx = idx;
            final boolean doPin = pinning.enabled()
                    && pinning.coreMap() != null
                    && pinning.coreMap().length > 0;
            final int coreId = doPin ? pinning.coreMap()[idx % pinning.coreMap().length] : -1;
            // Always wrap: the wrapper performs (optional) CPU pinning
            // AND a one-shot attribution-buffer registration at worker
            // startup. When attribution is disabled the registration
            // is a single static volatile read + null check.
            Runnable body = () -> {
                if (doPin) {
                    try {
                        CpuAffinity.pinCurrentThreadToCore(coreId);
                    } catch (Throwable e) {
                        System.err.printf("[SharedWorker-%d] WARN: failed to pin to core %d: %s%n",
                                workerIdx, coreId, e.getMessage());
                    }
                }
                AttributionRecorder _attrib = AttributionRecorder.active();
                if (_attrib != null) {
                    // SHARED worker (incl. hybrid shared side): shardId = -1.
                    _attrib.ensureBufferForCurrentThread(workerIdx, -1);
                }
                // Always close perf fds when the worker thread exits,
                // even on abnormal termination. Idempotent — the
                // recorder's flushAndClose() also calls close on any
                // surviving fds, so calling it here is safe.
                try {
                    r.run();
                } finally {
                    if (_attrib != null) {
                        _attrib.closePerfFdsForCurrentThread();
                    }
                }
            };
            Thread t = new Thread(body, "SharedWorker-" + idx);
            t.setDaemon(false);
            return t;
        }
    }
}
