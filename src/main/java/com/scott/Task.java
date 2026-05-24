package com.scott;

import java.util.concurrent.CountDownLatch;

/**
 * Benchmark task: pairs a {@link Workload} with identity, dispatch
 * metadata ({@link WorkloadKind}, {@code targetMillis}), and the full
 * 4-stage timing lifecycle (created / enqueued / start / finish).
 *
 * <pre>
 *   createdNanos   : set by TaskGenerator, before dispatch/routing
 *   enqueuedNanos  : set by Dispatcher, just before the backing
 *                    executor enqueues the task (post-routing)
 *   startNanos     : set by the worker thread, right before run()
 *   finishNanos    : set by the worker thread, right after run()
 *
 *   submit/build overhead = enqueuedNanos - createdNanos
 *   queue wait            = startNanos    - enqueuedNanos
 *   execution             = finishNanos   - startNanos
 *   end-to-end            = finishNanos   - createdNanos
 * </pre>
 */
public final class Task implements Runnable {

    /** Optional completion hook used by online aggregators. */
    public interface CompletionObserver {
        void onTaskCompleted(Task task);
    }

    private final long taskId;
    private final WorkloadKind workloadKind;
    private final long targetMillis;
    private final long createdNanos;
    private final boolean measurement;
    private final Workload workload;
    private final CountDownLatch completionLatch;
    private final Runnable onComplete;
    private final CompletionObserver completionObserver;

    private long enqueuedNanos;
    private long startNanos;
    private long finishNanos;
    private long workloadResult;

    public Task(long taskId,
                WorkloadKind workloadKind,
                long targetMillis,
                long createdNanos,
                boolean measurement,
                Workload workload,
                CountDownLatch completionLatch,
                Runnable onComplete) {
        this(taskId, workloadKind, targetMillis, createdNanos, measurement,
                workload, completionLatch, onComplete, null);
    }

    public Task(long taskId,
                WorkloadKind workloadKind,
                long targetMillis,
                long createdNanos,
                boolean measurement,
                Workload workload,
                CountDownLatch completionLatch,
                Runnable onComplete,
                CompletionObserver completionObserver) {
        if (workload == null) {
            throw new IllegalArgumentException("Task requires a non-null Workload");
        }
        if (workloadKind == null) {
            throw new IllegalArgumentException("Task requires a non-null workloadKind");
        }
        this.taskId = taskId;
        this.workloadKind = workloadKind;
        this.targetMillis = targetMillis;
        this.createdNanos = createdNanos;
        this.measurement = measurement;
        this.workload = workload;
        this.completionLatch = completionLatch;
        this.onComplete = onComplete;
        this.completionObserver = completionObserver;
    }

    public Task(long taskId,
                WorkloadKind workloadKind,
                long targetMillis,
                long createdNanos,
                boolean measurement,
                Workload workload,
                Runnable onComplete) {
        this(taskId, workloadKind, targetMillis, createdNanos, measurement, workload, null, onComplete, null);
    }

    public Task(long taskId,
                WorkloadKind workloadKind,
                long targetMillis,
                long createdNanos,
                boolean measurement,
                Workload workload,
                Runnable onComplete,
                CompletionObserver completionObserver) {
        this(taskId, workloadKind, targetMillis, createdNanos, measurement,
                workload, null, onComplete, completionObserver);
    }

    /**
     * Records the moment the task is about to be handed to the backing
     * queue.
     *
     * <p>Must be called exactly once, by the top-level
     * {@link Dispatcher} only. Calling twice (e.g. by a wrapper
     * dispatcher and an inner one) would overwrite {@code enqueuedNanos}
     * and corrupt {@link #queueWaitTimeNanos()}; we fail fast in that
     * case so the bug is caught immediately rather than producing
     * silently wrong percentiles.
     */
    public void markEnqueued() {
        if (this.enqueuedNanos != 0L) {
            throw new IllegalStateException(
                    "Task.markEnqueued() called more than once for taskId=" + taskId
                            + " — only the top-level Dispatcher must stamp enqueuedNanos. "
                            + "Backing executors (SharedExecutor, ShardedExecutor) must NOT call markEnqueued().");
        }
        this.enqueuedNanos = System.nanoTime();
    }

    @Override
    public void run() {
        // Single volatile read on the (default) disabled path. When
        // attribution is active, isSampled decides whether a CSV row
        // is written; isPerfSampled further gates the perf-counter
        // pre/post bracket (which costs two read() syscalls per
        // counter). Tasks that are sampled but NOT perf-sampled skip
        // nativeReadAll entirely.
        AttributionRecorder _attrib = AttributionRecorder.active();
        boolean _sampled     = _attrib != null && measurement && _attrib.isSampled(taskId);
        boolean _perfSampled = _sampled && _attrib.isPerfSampled(taskId);
        if (_perfSampled) _attrib.preSample();

        startNanos = System.nanoTime();
        try {
            workloadResult = workload.execute();
        } finally {
            finishNanos = System.nanoTime();
            if (_sampled) {
                _attrib.recordSample(taskId, workloadKind.ordinal(),
                        startNanos, finishNanos, _perfSampled);
            }
            if (completionLatch != null) {
                completionLatch.countDown();
            }
            if (completionObserver != null) {
                completionObserver.onTaskCompleted(this);
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    /**
     * Variant of {@link #run()} that hands the completed task to a
     * {@link WorkerStats} sink <em>before</em> the completion latch is
     * counted down and <em>before</em> {@code onComplete.run()} (which
     * releases the in-flight permit).
     *
     * <p>Why: the in-flight permit must be released only after the
     * diagnostics update has happened. If the permit were released
     * first, {@code runPhase}'s drain would return and
     * {@code BenchmarkMain} could read per-worker counters before the
     * worker has finished updating them — leading to off-by-N totals.
     *
     * <p>No allocation: this is a plain method on {@link Task}; the
     * caller passes the worker-local {@link WorkerStats} reference
     * which is already held in {@link ShardedWorker} as a field.
     *
     * @param stats per-worker diagnostics sink; never null (the caller
     *              must check). When diagnostics are disabled the
     *              caller invokes {@link #run()} directly so this
     *              method is not on the hot path.
     */
    public void runWithBeforeComplete(WorkerStats stats) {
        AttributionRecorder _attrib = AttributionRecorder.active();
        boolean _sampled     = _attrib != null && measurement && _attrib.isSampled(taskId);
        boolean _perfSampled = _sampled && _attrib.isPerfSampled(taskId);
        if (_perfSampled) _attrib.preSample();

        startNanos = System.nanoTime();
        try {
            workloadResult = workload.execute();
        } finally {
            finishNanos = System.nanoTime();

            if (_sampled) {
                _attrib.recordSample(taskId, workloadKind.ordinal(),
                        startNanos, finishNanos, _perfSampled);
            }

            // Diagnostics MUST be recorded before the latch + permit
            // release so the drain handshake in BenchmarkMain observes
            // a consistent (worker stats, semaphore) pair.
            stats.onTaskCompleted(this);

            if (completionObserver != null) {
                completionObserver.onTaskCompleted(this);
            }

            if (completionLatch != null) {
                completionLatch.countDown();
            }
            if (onComplete != null) {
                onComplete.run();
            }
        }
    }

    public long submitOverheadNanos() {
        long e = enqueuedNanos == 0L ? createdNanos : enqueuedNanos;
        return e - createdNanos;
    }

    public long queueWaitTimeNanos() {
        long base = enqueuedNanos == 0L ? createdNanos : enqueuedNanos;
        return startNanos - base;
    }

    public long executionTimeNanos() {
        return finishNanos - startNanos;
    }

    public long endToEndLatencyNanos() {
        return finishNanos - createdNanos;
    }

    public long taskId()              { return taskId; }
    public WorkloadKind workloadKind(){ return workloadKind; }
    public long targetMillis()        { return targetMillis; }
    public long createdNanos()        { return createdNanos; }
    public long enqueuedNanos()       { return enqueuedNanos; }
    public long startNanos()          { return startNanos; }
    public long finishNanos()         { return finishNanos; }
    public long workloadResult()      { return workloadResult; }
    public boolean isMeasurement()    { return measurement; }
    public Workload workload()        { return workload; }

    private static double nsToMs(long ns) { return ns / 1_000_000.0; }

    @Override
    public String toString() {
        return String.format(
                "[Task-%d kind=%s target=%dms]  submitOverhead=%.3f ms  queueWait=%.3f ms  execution=%.3f ms  endToEnd=%.3f ms",
                taskId, workloadKind, targetMillis,
                nsToMs(submitOverheadNanos()),
                nsToMs(queueWaitTimeNanos()),
                nsToMs(executionTimeNanos()),
                nsToMs(endToEndLatencyNanos())
        );
    }
}

