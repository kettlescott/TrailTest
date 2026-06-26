package com.scott;

import java.util.concurrent.TimeUnit;

/**
 * Dispatcher that routes every task to a single {@link SharedExecutor}.
 *
 * <p>This is the baseline policy — identical to the original benchmark
 * behaviour before the dispatcher abstraction was introduced.
 */
public final class SharedOnlyDispatcher implements Dispatcher {

    private final SharedExecutor executor;

    public SharedOnlyDispatcher(int workerCount) {
        this(workerCount, PinningConfig.disabled(), null);
    }

    public SharedOnlyDispatcher(int workerCount, PinningConfig pinning) {
        this(workerCount, pinning, null);
    }

    /**
     * Diagnostics-aware constructor. {@code stats} may be null; when
     * non-null, the single aggregate WorkerStats is wired into the
     * shared {@link ThreadPoolExecutor} via an
     * {@code afterExecute} override (no per-task allocation).
     */
    public SharedOnlyDispatcher(int workerCount, PinningConfig pinning, WorkerStats stats) {
        this.executor = new SharedExecutor(workerCount,
                pinning == null ? PinningConfig.disabled() : pinning,
                stats);
    }

    @Override
    public void submit(Task task) throws InterruptedException {
        // Stamp the post-routing / pre-enqueue moment. Queue wait is
        // measured from this stamp; any work done in submit() itself
        // is attributed to "submit overhead" instead of "queue wait".
        task.markEnqueued();
        executor.submit(task);
    }

    @Override
    public void shutdown() {
        executor.shutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        return executor.awaitTermination(timeout, unit);
    }

    @Override
    public String label() {
        return "SharedOnly";
    }

    @Override
    public int totalQueueSize() {
        return executor.getQueueSize();
    }

    /** Exposes the backing executor for diagnostics (queue distribution, counts). */
    public SharedExecutor executor() {
        return executor;
    }
}

