package com.scott;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker thread for {@link ShardedExecutor}.
 *
 * <p>Each worker owns a dedicated {@link LinkedBlockingQueue} and is the
 * <em>sole consumer</em> of that queue.  The hot loop is deliberately
 * minimal: dequeue → {@link Task#run()} → repeat.  {@code Task.run()}
 * already handles all timing bookkeeping (recordStart, execute workload,
 * recordFinish, countDown latch), so the worker adds no measurement
 * overhead beyond the queue dequeue itself.
 *
 * <h3>Optional CPU core pinning</h3>
 * <p>When {@code enablePinning} is {@code true} and a valid {@code coreId}
 * is provided, the worker pins itself to the specified logical CPU core at
 * the very beginning of {@link #run()}.  Pinning is performed <em>inside</em>
 * the worker thread because {@code sched_setaffinity(0, ...)} operates on
 * the <em>calling</em> kernel thread.  If we pinned from the thread that
 * creates the worker, we would pin the creator, not the worker.</p>
 *
 * <p>When {@code enablePinning} is {@code false}, the worker runs with the
 * JVM's default affinity mask (all cores).  This enables clean A/B
 * comparison: the only experimental variable is whether workers are pinned.</p>
 *
 * <h3>Shutdown protocol</h3>
 * <ol>
 *   <li>The executor sets {@code shutdown = true} and interrupts this thread.</li>
 *   <li>If blocked in {@code take()}, {@link InterruptedException} is caught.</li>
 *   <li>Any remaining tasks in the local queue are drained and executed.</li>
 *   <li>When {@code shutdown == true && localQueue.isEmpty()}, the worker exits.</li>
 * </ol>
 *
 * <h3>GC notes</h3>
 * <ul>
 *   <li>No objects are allocated on the hot path — no wrappers, no futures,
 *       no lambda captures.</li>
 *   <li>No logging or string formatting in the loop.</li>
 *   <li>The one-time pinning log at startup is acceptable because it happens
 *       before any benchmark task is submitted.</li>
 * </ul>
 */
final class ShardedWorker implements Runnable {

    private final int workerId;
    /**
     * When {@code selfAllocateQueue == false} this is the pre-created queue
     * passed by the executor (legacy path). When {@code true}, this field
     * is null and the queue is allocated by {@link #run()} AFTER pinning
     * so its head/tail cache lines are first-touched on the worker's
     * NUMA node. Published to {@link #publishedQueue} + latch.
     */
    private final LinkedBlockingQueue<Task> localQueue;
    private final AtomicBoolean shutdown;

    /* ---- pinning configuration (immutable after construction) ---- */

    private final boolean enablePinning;
    private final int coreId;    // exact-CPU mode: single logical CPU id (or -1)
    /** NUMA-node-mask mode: full CPU list of the assigned node; null when unused. */
    private final int[] cpuMask;
    /** Node id when in NUMA-node-mask mode; -1 otherwise. Purely for logging. */
    private final int  numaNodeId;

    /* ---- first-touch handoff (only used when selfAllocateQueue == true) ---- */
    private final boolean selfAllocateQueue;
    private final java.util.concurrent.atomic.AtomicReference<LinkedBlockingQueue<Task>> publishedQueue;
    private final java.util.concurrent.CountDownLatch queueReadyLatch;

    /**
     * Optional per-worker diagnostics sink. Null when diagnostics are
     * disabled (default) — hot-path stays allocation-free and the JIT
     * eliminates the {@code stats != null} branch.
     */
    private final WorkerStats stats;

    /** Optional per-worker busy/idle tracker; null when diagnostics disabled. */
    private final WorkerBusyIdleTracker busyIdle;

    /**
     * Number of tasks this worker has executed.  Only written by the
     * owning worker thread and read after the thread has terminated,
     * so no synchronization is needed (plain {@code long} is fine).
     */
    private long processedCount;

    /**
     * Number of <em>measurement-phase</em> tasks this worker has executed.
     * Incremented only when {@link Task#isMeasurement()} is {@code true}.
     * Same thread-safety rationale as {@code processedCount}.
     */
    private long measurementProcessedCount;

    /**
     * Pinning result — captured at worker startup for post-run diagnostics.
     * {@code true} if pinning was requested and succeeded.
     */
    private boolean pinned;

    /**
     * Non-null only when pinning was attempted and failed.
     * Contains the exception message for post-run reporting.
     */
    private String pinningError;


    /**
     * Creates a worker <em>without</em> CPU pinning (original behaviour).
     */
    ShardedWorker(int workerId,
                  LinkedBlockingQueue<Task> localQueue,
                  AtomicBoolean shutdown) {
        this(workerId, localQueue, shutdown, false, -1, null);
    }

    /**
     * Creates a worker with optional CPU pinning.
     *
     * @param workerId      logical worker index (0-based)
     * @param localQueue    the per-worker queue this worker consumes
     * @param shutdown      shared shutdown flag
     * @param enablePinning if {@code true}, the worker will pin itself to
     *                      {@code coreId} at the start of {@link #run()}
     * @param coreId        logical CPU core to pin to (ignored when
     *                      {@code enablePinning} is {@code false})
     */
    ShardedWorker(int workerId,
                  LinkedBlockingQueue<Task> localQueue,
                  AtomicBoolean shutdown,
                  boolean enablePinning,
                  int coreId) {
        this(workerId, localQueue, shutdown, enablePinning, coreId, null);
    }

    /** Full constructor; {@code stats} may be null when diagnostics disabled. */
    ShardedWorker(int workerId,
                  LinkedBlockingQueue<Task> localQueue,
                  AtomicBoolean shutdown,
                  boolean enablePinning,
                  int coreId,
                  WorkerStats stats) {
        this(workerId, localQueue, shutdown, enablePinning, coreId, stats, null);
    }

    /** Full constructor with optional per-worker busy/idle tracker. */
    ShardedWorker(int workerId,
                  LinkedBlockingQueue<Task> localQueue,
                  AtomicBoolean shutdown,
                  boolean enablePinning,
                  int coreId,
                  WorkerStats stats,
                  WorkerBusyIdleTracker busyIdle) {
        this(workerId, localQueue, shutdown, enablePinning, coreId, stats, busyIdle,
             /*cpuMask*/ null, /*numaNodeId*/ -1,
             /*selfAllocateQueue*/ false, /*publishedQueue*/ null, /*queueReadyLatch*/ null);
    }

    /**
     * Full NUMA-aware constructor.
     *
     * <p>Two independent capabilities:
     * <ol>
     *   <li>{@code cpuMask != null} → NUMA-node-mask pinning:
     *       {@link CpuAffinity#pinCurrentThreadToCpuSet(int[])} with
     *       every CPU on the assigned node. Overrides {@code coreId}
     *       when non-null. Set {@code coreId=-1} in that case.</li>
     *   <li>{@code selfAllocateQueue == true} → worker allocates its own
     *       {@link LinkedBlockingQueue} AFTER pinning, publishes it via
     *       {@code publishedQueue}, and counts down {@code queueReadyLatch}.
     *       This first-touches the queue head/tail cache lines on the
     *       worker's NUMA node.
     *       Note: {@link LinkedBlockingQueue} still allocates a Node per
     *       {@code offer()} on the PRODUCER thread, so per-task Node
     *       payloads remain producer-node-local — not fixable without
     *       switching queue implementations.</li>
     * </ol>
     */
    ShardedWorker(int workerId,
                  LinkedBlockingQueue<Task> localQueue,
                  AtomicBoolean shutdown,
                  boolean enablePinning,
                  int coreId,
                  WorkerStats stats,
                  WorkerBusyIdleTracker busyIdle,
                  int[] cpuMask,
                  int numaNodeId,
                  boolean selfAllocateQueue,
                  java.util.concurrent.atomic.AtomicReference<LinkedBlockingQueue<Task>> publishedQueue,
                  java.util.concurrent.CountDownLatch queueReadyLatch) {
        this.workerId          = workerId;
        this.localQueue        = localQueue;
        this.shutdown          = shutdown;
        this.enablePinning     = enablePinning;
        this.coreId            = coreId;
        this.stats             = stats;
        this.busyIdle          = busyIdle;
        this.cpuMask           = cpuMask;
        this.numaNodeId        = numaNodeId;
        this.selfAllocateQueue = selfAllocateQueue;
        this.publishedQueue    = publishedQueue;
        this.queueReadyLatch   = queueReadyLatch;
    }

    @Override
    public void run() {
        // ---- CPU pinning (one-time, before any task is processed) ----
        setupPinning();

        // ---- Queue setup (NUMA first-touch or legacy path) ----
        final LinkedBlockingQueue<Task> effectiveQueue = setupQueue();

        // ---- Optional per-task attribution buffer registration ----
        final AttributionRecorder _attrib = AttributionRecorder.active();
        if (_attrib != null) {
            _attrib.ensureBufferForCurrentThread(workerId, workerId);
        }

        // ---- Main task loop — kept minimal to match src_before behavior.
        //      All setup logic is in helper methods to keep this method small
        //      for optimal JIT compilation and inlining. ----
        try {
            while (true) {
                if (shutdown.get() && effectiveQueue.isEmpty()) {
                    return;
                }

                final Task task;
                try {
                    task = effectiveQueue.take();
                } catch (InterruptedException e) {
                    if (shutdown.get() && effectiveQueue.isEmpty()) {
                        return;
                    }
                    continue;
                }

                // Identical to src_before hot path: single condition, no extra work.
                if (stats != null) {
                    task.runWithBeforeComplete(stats);
                } else {
                    task.run();
                }

                processedCount++;
                if (task.isMeasurement()) {
                    measurementProcessedCount++;
                }
            }
        } finally {
            if (_attrib != null) {
                _attrib.closePerfFdsForCurrentThread();
            }
            reportExitIfPinned();
        }
    }

    /** One-time CPU pinning setup. Extracted to keep run() small. */
    private void setupPinning() {
        if (!enablePinning) return;
        try {
            if (cpuMask != null) {
                CpuAffinity.pinCurrentThreadToCpuSet(cpuMask);
            } else {
                CpuAffinity.pinCurrentThreadToCore(coreId);
            }
            pinned = true;
            if (BenchmarkFlags.DEBUG) {
                System.out.printf("[%s] worker-%d pinned mode=%s coreId=%d nodeId=%d "
                                + "cpuMaskSize=%d (affinity mask: %s)%n",
                        Thread.currentThread().getName(), workerId,
                        (cpuMask != null ? "NUMA_NODE" : "EXACT_CPU"),
                        coreId, numaNodeId, (cpuMask != null ? cpuMask.length : 1),
                        CpuAffinity.getCurrentAffinityMask());
            }
        } catch (Throwable e) {
            pinned = false;
            pinningError = e.getMessage();
            System.err.printf("[ShardedWorker-%d] WARN: failed to pin (mode=%s coreId=%d nodeId=%d): %s%n",
                    workerId, (cpuMask != null ? "NUMA_NODE" : "EXACT_CPU"),
                    coreId, numaNodeId, e.getMessage());
        }
    }

    /** Queue acquisition (NUMA first-touch or legacy). Extracted to keep run() small. */
    private LinkedBlockingQueue<Task> setupQueue() {
        if (selfAllocateQueue) {
            LinkedBlockingQueue<Task> q = new LinkedBlockingQueue<>();
            publishedQueue.set(q);
            queueReadyLatch.countDown();
            int cpuAtAlloc = safeCurrentCpu();
            int nodeAtAlloc = NumaTopology.get().nodeOfCpu(cpuAtAlloc);
            System.out.printf("[ShardedWorker-%d] queue allocated on cpu=%d node=%d (target node=%d)%n",
                    workerId, cpuAtAlloc, nodeAtAlloc, numaNodeId);
            if (numaNodeId >= 0 && nodeAtAlloc >= 0 && nodeAtAlloc != numaNodeId) {
                System.err.printf("[ShardedWorker-%d] WARN: queue first-touch on node %d but target was node %d%n",
                        workerId, nodeAtAlloc, numaNodeId);
            }
            return q;
        }
        if (enablePinning) {
            int cpuNow = safeCurrentCpu();
            int nodeNow = NumaTopology.get().nodeOfCpu(cpuNow);
            System.out.printf("[ShardedWorker-%d] started on cpu=%d node=%d mode=%s%n",
                    workerId, cpuNow, nodeNow,
                    (cpuMask != null ? "NUMA_NODE" : "EXACT_CPU"));
        }
        return localQueue;
    }

    /** Exit-time NUMA validation log. Extracted to keep run() small. */
    private void reportExitIfPinned() {
        if (!enablePinning) return;
        int cpuEnd  = safeCurrentCpu();
        int nodeEnd = NumaTopology.get().nodeOfCpu(cpuEnd);
        System.out.printf("[ShardedWorker-%d] exit on cpu=%d node=%d (target node=%d) processed=%d%n",
                workerId, cpuEnd, nodeEnd, numaNodeId, processedCount);
    }

    /** Best-effort sched_getcpu via PerfBridge; returns -1 if unavailable. */
    private static int safeCurrentCpu() {
        try {
            return com.scott.perf.PerfBridge.currentCpu();
        } catch (Throwable t) {
            return -1;
        }
    }

    /**
     * Returns the number of tasks this worker has executed.
     *
     * <p>Only meaningful after the worker thread has terminated —
     * the caller must {@code join()} the thread first to establish
     * a happens-before relationship.
     */
    long processedCount() {
        return processedCount;
    }

    /**
     * Returns the number of measurement-phase tasks this worker has executed.
     * Only meaningful after the worker thread has terminated.
     */
    long measurementProcessedCount() {
        return measurementProcessedCount;
    }

    int workerId() {
        return workerId;
    }

    /** Returns {@code true} if pinning was requested and succeeded. */
    boolean isPinned() {
        return pinned;
    }

    /** Returns the pinning error message, or {@code null} if not attempted or succeeded. */
    String pinningError() {
        return pinningError;
    }
}
