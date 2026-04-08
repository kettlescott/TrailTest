package com.scott;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Sharded Executor — per-worker queue implementation.
 *
 * <p>Each of the {@code workerCount} worker threads owns a dedicated
 * {@link ArrayBlockingQueue}.  Tasks are routed to a shard by
 * {@code taskId % workerCount} (deterministic round-robin).  Workers
 * consume <em>only</em> their own queue — there is no work stealing.
 *
 * <p>This is the direct counterpart of {@link SharedExecutor} for
 * queue-contention benchmarks: same {@link Task}, same timing model
 * ({@link TaskTimingStore}), same {@link LatencyRecorder} — the
 * <em>only</em> structural difference is the queue topology.
 *
 * <h3>Why this design?</h3>
 * <p>A per-worker queue eliminates the single shared-queue lock as a
 * contention point.  With uniform round-robin routing and identical
 * workloads the load is perfectly balanced, making this the cleanest
 * possible baseline for comparing shared-queue vs.&nbsp;sharded-queue
 * tail latency without confounding variables (no hash skew, no
 * stealing overhead, no adaptive routing).
 *
 * <h3>GC-friendly design</h3>
 * <ul>
 *   <li>{@link ArrayBlockingQueue} uses a pre-allocated {@code Object[]}
 *       — no per-enqueue node allocation (unlike {@code LinkedBlockingQueue}).</li>
 *   <li>No {@link Runnable} wrappers, no {@code FutureTask}, no boxing.</li>
 *   <li>{@link Task#run()} handles all timing internally — the worker
 *       adds zero measurement overhead beyond the queue dequeue.</li>
 * </ul>
 *
 * <h3>Known limitations (first version)</h3>
 * <ul>
 *   <li>No work stealing — a slow shard blocks while others sit idle.</li>
 *   <li>No thread affinity / pinning — OS may migrate workers.</li>
 *   <li>No NUMA awareness.</li>
 *   <li>Round-robin routing assumes uniform task cost; skewed workloads
 *       will cause imbalance.</li>
 * </ul>
 */
public final class ShardedExecutor implements BenchmarkExecutor {

    private final int workerCount;
    private final ArrayBlockingQueue<Task>[] queues;
    private final Thread[] workerThreads;
    private final AtomicBoolean shutdown = new AtomicBoolean(false);

    /**
     * Creates and <em>eagerly starts</em> a sharded executor.
     *
     * <p>Worker threads are started in the constructor so they are ready
     * to accept tasks immediately — matching the behaviour of
     * {@link SharedExecutor}'s underlying {@code ThreadPoolExecutor}
     * which creates core threads on first submit.
     *
     * @param workerCount   number of worker threads (one queue per worker)
     * @param queueCapacity capacity of each per-worker {@link ArrayBlockingQueue}
     */
    @SuppressWarnings("unchecked")
    public ShardedExecutor(int workerCount, int queueCapacity) {
        this.workerCount   = workerCount;
        this.queues        = new ArrayBlockingQueue[workerCount];
        this.workerThreads = new Thread[workerCount];

        for (int i = 0; i < workerCount; i++) {
            queues[i] = new ArrayBlockingQueue<>(queueCapacity);
            workerThreads[i] = new Thread(
                    new ShardedWorker(i, queues[i], shutdown),
                    "ShardedWorker-" + i
            );
            workerThreads[i].setDaemon(false);
        }

        // Start all workers eagerly.
        for (Thread t : workerThreads) {
            t.start();
        }
    }

    /* ================================================================
     *  Submission
     * ================================================================ */

    /**
     * Routes the task to a shard by {@code taskId % workerCount} and
     * enqueues it via {@link ArrayBlockingQueue#put(Object)} (blocking
     * if the shard queue is full).
     *
     * <p>No wrapper objects are created — the {@link Task} reference is
     * placed directly into the queue's pre-allocated {@code Object[]}.
     */
    @Override
    public void submit(Task task) throws InterruptedException {
        int shard = (int) (task.taskId() % workerCount);
        queues[shard].put(task);
    }

    /* ================================================================
     *  Lifecycle
     * ================================================================ */

    /**
     * Signals all workers to finish draining their queues and exit.
     *
     * <p>Sets the shared shutdown flag and interrupts every worker thread
     * so that threads blocked in {@code take()} wake up immediately.
     */
    @Override
    public void shutdown() {
        if (!shutdown.compareAndSet(false, true)) {
            return;
        }
        for (Thread t : workerThreads) {
            t.interrupt();
        }
    }

    /**
     * Blocks until every worker thread has terminated or the timeout
     * expires, whichever comes first.
     */
    @Override
    public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
        long deadlineNanos = System.nanoTime() + unit.toNanos(timeout);
        for (Thread t : workerThreads) {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0) return false;
            t.join(TimeUnit.NANOSECONDS.toMillis(remainingNanos));
            if (t.isAlive()) return false;
        }
        return true;
    }

    /* ================================================================
     *  Observation (for diagnostics / tests)
     * ================================================================ */

    /** Returns the number of worker threads. */
    public int getWorkerCount() {
        return workerCount;
    }

    /** Returns the current depth of a single shard queue. */
    public int getQueueSize(int shard) {
        return queues[shard].size();
    }

    /** Returns the sum of all shard queue depths. */
    public int getTotalQueueSize() {
        int total = 0;
        for (ArrayBlockingQueue<Task> q : queues) {
            total += q.size();
        }
        return total;
    }
}

