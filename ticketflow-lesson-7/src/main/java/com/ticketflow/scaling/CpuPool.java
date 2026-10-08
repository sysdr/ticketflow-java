package com.ticketflow.scaling;

import com.ticketflow.lifecycle.StageTimer;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The size of the box, expressed as code: exactly {@code cores} threads may do
 * CPU work at the same moment. Everything else waits in line.
 *
 * <p>The line has no limit. That is deliberate for this lesson, because an
 * unlimited queue is what lets a box accept more work than it can ever finish.
 */
public final class CpuPool implements AutoCloseable {

    private final int cores;
    private final ThreadPoolExecutor executor;

    public CpuPool(int cores) {
        if (cores < 1) {
            throw new IllegalArgumentException("a box needs at least one core");
        }
        this.cores = cores;
        AtomicInteger names = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(cores, cores, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(), runnable -> {
                    Thread thread = new Thread(runnable, "cpu-" + names.incrementAndGet());
                    thread.setDaemon(true);
                    return thread;
                });
        this.executor.prestartAllCoreThreads();
    }

    /**
     * Runs CPU work on one of the box's cores and records two stages on the
     * timer: {@code queue} (time spent waiting for a core) and {@code sign}
     * (time spent on it).
     */
    public <T> T run(Callable<T> work, StageTimer timer) {
        long submitted = System.nanoTime();
        Future<T> future = executor.submit(() -> {
            long started = System.nanoTime();
            timer.record("queue", started - submitted);
            try {
                return work.call();
            } finally {
                timer.record("sign", System.nanoTime() - started);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException interrupted) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for a core", interrupted);
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException(cause);
        }
    }

    public int cores() {
        return cores;
    }

    /** Requests waiting for a core right now. */
    public int queueDepth() {
        return executor.getQueue().size();
    }

    /** Cores doing work right now. */
    public int busy() {
        return executor.getActiveCount();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
