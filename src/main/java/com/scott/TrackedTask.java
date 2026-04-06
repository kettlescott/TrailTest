package com.scott;

/**
 * A {@link Runnable} decorator that records start and finish timestamps
 * into its associated {@link TaskMetrics}.
 */
public final class TrackedTask implements Runnable {

    private final Runnable delegate;
    private final TaskMetrics metrics;

    public TrackedTask(Runnable delegate, TaskMetrics metrics) {
        this.delegate = delegate;
        this.metrics = metrics;
    }

    public TaskMetrics getMetrics() {
        return metrics;
    }

    @Override
    public void run() {
        metrics.markStart(System.nanoTime());
        try {
            delegate.run();
        } finally {
            metrics.markFinish(System.nanoTime());
        }
    }
}

