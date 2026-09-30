package com.lpsm.vod;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;

/** Work owned by one screen. Closing and accepting work use the same lock. */
public final class LifecycleExecutor {
    private final Object lock = new Object();
    private final ExecutorService executor;
    private volatile boolean closed;

    public LifecycleExecutor(int threads) {
        executor = Executors.newFixedThreadPool(threads);
    }

    /** A late callback is cancelled instead of crashing the Android UI thread. */
    public boolean execute(Runnable task) {
        synchronized (lock) {
            if (closed) return false;
            try {
                executor.execute(() -> {
                    if (!closed) task.run();
                });
                return true;
            } catch (RejectedExecutionException cancelled) {
                // Covers rejection during termination without hiding task failures.
                return false;
            }
        }
    }

    public boolean isShutdown() {
        return closed;
    }

    public void shutdownNow() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
            executor.shutdownNow();
        }
    }
}
