package com.scott;

import java.util.concurrent.TimeUnit;

/**
 * Dispatcher that routes tasks based on their {@link TaskType}:
 * <ul>
 *   <li>{@link TaskType#SHORT}  → sharded executor (low contention, fast dequeue)</li>
 *   <li>{@link TaskType#MEDIUM} → shared executor (work-stealing load balancing)</li>
 *   <li>{@link TaskType#LONG}   → shared executor (avoids shard head-of-line blocking)</li>
 * </ul>
 *
 * <p>Routing is a single {@code switch} on a pre-set enum — no map lookup,
 * no synchronization, no allocation on the hot path.
 */
public final class TypeAwareDispatcher implements Dispatcher {

    private final SharedExecutor  sharedExecutor;
    private final ShardedExecutor shardedExecutor;

    /**
     * Creates both backing executors with the given worker count.
     * Each executor gets its own pool of {@code workerCount} threads.
     *
     * @param workerCount threads per executor
     */
    public TypeAwareDispatcher(int workerCount) {
        this.sharedExecutor  = new SharedExecutor(workerCount);
        this.shardedExecutor = new ShardedExecutor(workerCount);
    }

    @Override
    public void submit(Task task) throws InterruptedException {
        switch (task.taskType()) {
            case SHORT  -> shardedExecutor.submit(task);
            case MEDIUM, LONG -> sharedExecutor.submit(task);
        }
    }

    @Override
    public void shutdown() {
        sharedExecutor.shutdown();
        shardedExecutor.shutdown();
    }

    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);

        long remaining = deadlineNanos - System.nanoTime();
        boolean sharedDone = sharedExecutor.awaitTermination(
                Math.max(remaining, 0), TimeUnit.NANOSECONDS);

        remaining = deadlineNanos - System.nanoTime();
        boolean shardedDone = shardedExecutor.awaitTermination(
                Math.max(remaining, 0), TimeUnit.NANOSECONDS);

        return sharedDone && shardedDone;
    }

    @Override
    public String label() {
        return "TypeAware";
    }

    /** Exposes the shared backend for diagnostics. */
    public SharedExecutor sharedExecutor() {
        return sharedExecutor;
    }

    /** Exposes the sharded backend for diagnostics. */
    public ShardedExecutor shardedExecutor() {
        return shardedExecutor;
    }
}

