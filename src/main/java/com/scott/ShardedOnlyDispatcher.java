package com.scott;

import java.util.concurrent.TimeUnit;

/**
 * Dispatcher that routes every task to a {@link ShardedExecutor}
 * (per-worker queues, hash-based routing).
 */
public final class ShardedOnlyDispatcher implements Dispatcher {

    private final ShardedExecutor executor;

    public ShardedOnlyDispatcher(int workerCount) {
        this.executor = new ShardedExecutor(workerCount);
    }

    @Override
    public void submit(Task task) throws InterruptedException {
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
        return "ShardedOnly";
    }

    /** Exposes the backing executor for diagnostics (queue distribution, counts). */
    public ShardedExecutor executor() {
        return executor;
    }
}

