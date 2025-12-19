package com.patientcentre.analytics.scheduling;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/** Production scheduler implementation backed by ScheduledExecutorService. */
public final class ExecutorScheduler implements Scheduler {

    private final ScheduledExecutorService executor;

    public ExecutorScheduler(ScheduledExecutorService executor) {
        this.executor = Objects.requireNonNull(executor);
    }

    @Override
    public void scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
        executor.scheduleAtFixedRate(
                task,
                initialDelay.toMillis(),
                period.toMillis(),
                TimeUnit.MILLISECONDS
        );
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
