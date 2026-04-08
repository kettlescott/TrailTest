package com.scott;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Worker thread for {@link ShardedExecutor}.
 *
 * <p>Each worker owns a dedicated {@link ArrayBlockingQueue} and is the
 * <em>sole consumer</em> of that queue.  The hot loop is deliberately
 * minimal: dequeue → {@link Task#run()} → repeat.  {@code Task.run()}
 * already handles all timing bookkeeping (recordStart, execute workload,
 * recordFinish, countDown latch), so the worker adds no measurement
 * overhead beyond the queue dequeue itself.
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
 * </ul>
 */
final class ShardedWorker implements Runnable {

    private final int workerId;
    private final ArrayBlockingQueue<Task> localQueue;
    private final AtomicBoolean shutdown;

    ShardedWorker(int workerId,
                  ArrayBlockingQueue<Task> localQueue,
                  AtomicBoolean shutdown) {
        this.workerId   = workerId;
        this.localQueue = localQueue;
        this.shutdown   = shutdown;
    }

    @Override
    public void run() {
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
            task.run();
        }
    }

    int workerId() {
        return workerId;
    }
}

