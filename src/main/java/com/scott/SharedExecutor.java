package com.scott;

import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
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
 *       timestamps inside its own {@link Task#run()} method.</li>
 *   <li>Collected tasks are available through {@link #getTasks()} for latency analysis.</li>
 * </ul>
 */
public final class SharedExecutor {

    private final ThreadPoolExecutor executor;
    private final BlockingQueue<Runnable> workQueue;
    private final List<Task> taskList = new CopyOnWriteArrayList<>();

    /**
     * Creates the shared executor.
     *
     * @param poolSize number of fixed worker threads
     */
    public SharedExecutor(int poolSize) {
        this.workQueue = new LinkedBlockingQueue<>();
        this.executor = new ThreadPoolExecutor(
                poolSize,                       // corePoolSize  (fixed)
                poolSize,                       // maximumPoolSize (fixed)
                0L, TimeUnit.MILLISECONDS,      // keep-alive (irrelevant for fixed pool)
                workQueue,                      // single shared queue
                new DeterministicThreadFactory() // predictable thread names
        );
    }

    /* ---- submission ---- */

    /**
     * Submits a pre-built {@link Task} for execution.
     *
     * @param task the benchmark task (submit timestamp should already be set)
     * @return the same {@link Task} instance for further inspection after completion
     */
    public Task submit(Task task) {
        taskList.add(task);
        executor.execute(task);
        return task;
    }

    /**
     * Convenience overload: builds a {@link Task} from an id and workload,
     * stamps the submit time, and submits it.
     *
     * @param taskId   unique task identifier
     * @param workload the workload to execute
     * @return the newly created {@link Task}
     */
    public Task submit(long taskId, Workload workload) {
        Task task = new Task(taskId, System.nanoTime(), workload);
        return submit(task);
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
     */
    public List<Task> getTasks() {
        return Collections.unmodifiableList(taskList);
    }

    /**
     * Returns the number of worker threads in the pool.
     */
    public int getPoolSize() {
        return executor.getCorePoolSize();
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

        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "SharedWorker-" + counter.getAndIncrement());
            t.setDaemon(false);
            return t;
        }
    }
}

