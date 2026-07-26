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

    /**
     * Thread-local logical worker index for pool threads. Set once
     * inside {@link DeterministicThreadFactory} when the thread starts,
     * read by {@link WorkerBusyIdleTracker}-aware TPE hooks. Zero cost
     * when busy/idle tracking is disabled (the value is set anyway but
     * never read).
     */
    static final ThreadLocal<Integer> SHARED_WORKER_ID = new ThreadLocal<>();

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
        this(poolSize, pinning, stats, null);
    }

    /**
     * Full constructor. {@code stats} and {@code busyIdle} may each be
     * null independently. When {@code busyIdle != null}, {@code beforeExecute}
     * and {@code afterExecute} on the pool sample {@code System.nanoTime()}
     * to attribute per-worker wall-clock time to busy vs. idle. Overhead
     * per task: two {@code nanoTime()} + a handful of long ops per hook.
     */
    public SharedExecutor(int poolSize, PinningConfig pinning, WorkerStats stats,
                          WorkerBusyIdleTracker busyIdle) {
        this.workQueue = new LinkedBlockingQueue<>();
        this.taskList  = BenchmarkFlags.DEBUG ? new ArrayList<>() : null;
        if (stats == null && busyIdle == null) {
            this.executor = new ThreadPoolExecutor(
                    poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                    workQueue,
                    new DeterministicThreadFactory(pinning));
        } else {
            final WorkerStats statsF = stats;
            final WorkerBusyIdleTracker bi = busyIdle;
            // Subclass override is the JDK-blessed way to observe per-task
            // execution on the worker thread without wrapping Runnables.
            this.executor = new ThreadPoolExecutor(
                    poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                    workQueue,
                    new DeterministicThreadFactory(pinning, busyIdle)) {
                @Override
                protected void beforeExecute(Thread t, Runnable r) {
                    super.beforeExecute(t, r);
                    if (bi != null) {
                        Integer wid = SHARED_WORKER_ID.get();
                        if (wid != null) bi.beforeTask(wid, System.nanoTime());
                    }
                }
                @Override
                protected void afterExecute(Runnable r, Throwable th) {
                    super.afterExecute(r, th);
                    if (bi != null) {
                        Integer wid = SHARED_WORKER_ID.get();
                        if (wid != null) bi.afterTask(wid, System.nanoTime());
                    }
                    if (statsF != null && r instanceof Task task) {
                        statsF.onTaskCompleted(task);
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

    /** Total tasks completed by the pool (all phases). Cheap wrapper over TPE. */
    public long getCompletedTaskCount() {
        return executor.getCompletedTaskCount();
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
        private final WorkerBusyIdleTracker busyIdle;

        DeterministicThreadFactory() {
            this(PinningConfig.disabled(), null);
        }

        DeterministicThreadFactory(PinningConfig pinning) {
            this(pinning, null);
        }

        DeterministicThreadFactory(PinningConfig pinning, WorkerBusyIdleTracker busyIdle) {
            this.pinning = (pinning == null) ? PinningConfig.disabled() : pinning;
            this.busyIdle = busyIdle;
        }

        @Override
        public Thread newThread(Runnable r) {
            int idx = counter.getAndIncrement();
            final int workerIdx = idx;
            final PinningConfig.Mode pinMode = pinning.mode();
            final boolean doPin = (pinMode != PinningConfig.Mode.DISABLED);
            // EXACT_CPU: pick one CPU. NUMA_NODE: pick node id + full CPU mask.
            final int coreId = (pinMode == PinningConfig.Mode.EXACT_CPU)
                    ? pinning.coreMap()[idx % pinning.coreMap().length]
                    : -1;
            final int nodeId;
            final int[] cpuMask;
            if (pinMode == PinningConfig.Mode.NUMA_NODE) {
                int[] wn = pinning.workerNodes();
                nodeId  = wn[idx % wn.length];
                cpuMask = NumaTopology.get().cpusOfNode(nodeId);
            } else {
                nodeId  = -1;
                cpuMask = null;
            }
            Runnable body = () -> {
                // Install logical worker id for TPE beforeExecute/afterExecute
                // hooks (busy/idle tracker). Set once per worker thread.
                SHARED_WORKER_ID.set(workerIdx);
                if (busyIdle != null) {
                    busyIdle.workerStarted(workerIdx, System.nanoTime());
                }
                if (doPin) {
                    try {
                        if (cpuMask != null) {
                            CpuAffinity.pinCurrentThreadToCpuSet(cpuMask);
                        } else {
                            CpuAffinity.pinCurrentThreadToCore(coreId);
                        }
                        int cpuNow  = safeCurrentCpu();
                        int nodeNow = NumaTopology.get().nodeOfCpu(cpuNow);
                        System.out.printf("[SharedWorker-%d] started on cpu=%d node=%d mode=%s (target node=%d)%n",
                                workerIdx, cpuNow, nodeNow,
                                (cpuMask != null ? "NUMA_NODE" : "EXACT_CPU"), nodeId);
                    } catch (Throwable e) {
                        System.err.printf("[SharedWorker-%d] WARN: failed to pin (mode=%s coreId=%d nodeId=%d): %s%n",
                                workerIdx, (cpuMask != null ? "NUMA_NODE" : "EXACT_CPU"),
                                coreId, nodeId, e.getMessage());
                    }
                }
                AttributionRecorder _attrib = AttributionRecorder.active();
                if (_attrib != null) {
                    // SHARED worker (incl. hybrid shared side): shardId = -1.
                    _attrib.ensureBufferForCurrentThread(workerIdx, -1);
                }
                // Always close perf fds when the worker thread exits,
                // even on abnormal termination.
                try {
                    r.run();
                } finally {
                    if (_attrib != null) {
                        _attrib.closePerfFdsForCurrentThread();
                    }
                    if (doPin) {
                        int cpuEnd  = safeCurrentCpu();
                        int nodeEnd = NumaTopology.get().nodeOfCpu(cpuEnd);
                        System.out.printf("[SharedWorker-%d] exit on cpu=%d node=%d (target node=%d)%n",
                                workerIdx, cpuEnd, nodeEnd, nodeId);
                    }
                }
            };
            Thread t = new Thread(body, "SharedWorker-" + idx);
            t.setDaemon(false);
            return t;
        }

        /** Best-effort sched_getcpu via PerfBridge; returns -1 if unavailable. */
        private static int safeCurrentCpu() {
            try {
                return com.scott.perf.PerfBridge.currentCpu();
            } catch (Throwable t) {
                return -1;
            }
        }
    }
}
