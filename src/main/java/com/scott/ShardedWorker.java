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
    private final LinkedBlockingQueue<Task> localQueue;
    private final AtomicBoolean shutdown;

    /* ---- pinning configuration (immutable after construction) ---- */

    private final boolean enablePinning;
    private final int coreId;    // meaningful only when enablePinning == true

    /**
     * Optional per-worker diagnostics sink. Null when diagnostics are
     * disabled (default) — hot-path stays allocation-free and the JIT
     * eliminates the {@code stats != null} branch.
     */
    private final WorkerStats stats;

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
        this.workerId      = workerId;
        this.localQueue    = localQueue;
        this.shutdown      = shutdown;
        this.enablePinning = enablePinning;
        this.coreId        = coreId;
        this.stats         = stats;
    }

    @Override
    public void run() {
        // ---- CPU pinning (one-time, before any task is processed) ----
        //
        // Why here and not in the constructor or thread-creator?
        //   sched_setaffinity(0, ...) targets the *calling* kernel thread.
        //   At this point we are executing inside the worker thread, so
        //   pid=0 refers to this thread's TID.  Calling from anywhere else
        //   would pin the wrong thread.
        //
        // The result is stored in fields (pinned / pinningError) for
        // post-run diagnostics.  Console output is only produced when
        // BenchmarkFlags.DEBUG is true, keeping the worker silent during
        // normal benchmark runs.
        if (enablePinning) {
            try {
                CpuAffinity.pinCurrentThreadToCore(coreId);
                pinned = true;
                if (BenchmarkFlags.DEBUG) {
                    System.out.printf("[%s] worker-%d pinned to core %d  (affinity mask: %s)%n",
                            Thread.currentThread().getName(), workerId, coreId,
                            CpuAffinity.getCurrentAffinityMask());
                }
            } catch (Throwable e) {
                // Pinning failure is non-fatal: log a warning and run
                // the worker with the JVM's default affinity mask.
                pinned = false;
                pinningError = e.getMessage();
                System.err.printf("[ShardedWorker-%d] WARN: failed to pin to core %d: %s%n",
                        workerId, coreId, e.getMessage());
            }
        }

        // ---- Optional per-task attribution buffer registration ----
        // One-shot, idempotent. When attribution is disabled (default)
        // the static volatile read returns null and this is a no-op.
        // Registering here — before the main task loop — guarantees the
        // sampled hot path in Task.run() never has to allocate a buffer.
        final AttributionRecorder _attrib = AttributionRecorder.active();
        if (_attrib != null) {
            // SHARDED worker: shardId == workerId by construction.
            _attrib.ensureBufferForCurrentThread(workerId, workerId);
        }

        // ---- main task loop wrapped in try/finally so perf fds are
        //      ALWAYS released, even on abnormal exit (uncaught
        //      throwable, interrupt during shutdown, etc.). The
        //      sampled rows already in the per-worker buffer remain
        //      intact for flushAndClose() to drain. ----
        try {
            while (true) {
                // Fast exit: shutdown requested and nothing left to drain.
                if (shutdown.get() && localQueue.isEmpty()) {
                    return;
                }

                final Task task;
                try {
                    task = localQueue.take();
                } catch (InterruptedException e) {
                    // Woken by interrupt — re-check shutdown + drain condition.
                    if (shutdown.get() && localQueue.isEmpty()) {
                        return;
                    }
                    // Still have queued work or not yet shutting down — keep going.
                    continue;
                }

                // Task.run() records start/finish timestamps, executes the
                // workload, and counts down the batch latch — identical to
                // the SharedExecutor code path via ThreadPoolExecutor.
                //
                // When diagnostics are enabled, route through the variant
                // that records into WorkerStats BEFORE onComplete fires the
                // permit release. This eliminates the diagnostics/drain
                // race without any extra synchronisation.
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
