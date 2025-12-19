package com.patientcentre.analytics.core;

import com.patientcentre.analytics.api.AnalyticsApi;
import com.patientcentre.analytics.config.BufferConfig;
import com.patientcentre.analytics.config.DropPolicy;
import com.patientcentre.analytics.scheduling.Scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Smart batching analytics buffer.
 *
 * Triggers:
 *  - flush when buffered events >= maxBatchSize
 *  - flush when flushInterval elapsed since the last SUCCESSFUL flush
 *
 * Guarantees:
 *  - track() is non-blocking (no network wait)
 *  - no concurrent "double flush" (single-flight guard)
 *  - on API failure: events are re-queued and retried next time (not lost)
 *  - bounded memory via maxBufferSize + DropPolicy
 */
public final class AnalyticsBuffer<E> implements AutoCloseable {

    private final AnalyticsApi<E> api;
    private final BufferConfig config;
    private final Clock clock;
    private final Scheduler scheduler;
    private final Executor flushExecutor;

    private final Deque<E> buffer = new ArrayDeque<>();
    private final Object mutex = new Object();

    private final AtomicBoolean flushInProgress = new AtomicBoolean(false);
    private volatile Instant lastSuccessfulFlushAt;
    private volatile boolean closed = false;

    public AnalyticsBuffer(
            AnalyticsApi<E> api,
            BufferConfig config,
            Clock clock,
            Scheduler scheduler,
            Executor flushExecutor
    ) {
        this.api = Objects.requireNonNull(api);
        this.config = Objects.requireNonNull(config);
        this.clock = Objects.requireNonNull(clock);
        this.scheduler = Objects.requireNonNull(scheduler);
        this.flushExecutor = Objects.requireNonNull(flushExecutor);

        this.lastSuccessfulFlushAt = clock.instant();

        // periodic ticker checks time-based trigger
        this.scheduler.scheduleAtFixedRate(
                this::maybeFlushByTime,
                config.tickerInterval(),
                config.tickerInterval()
        );
    }

    /** Current number of buffered events. */
    public int getBufferedCount() {
        synchronized (mutex) {
            return buffer.size();
        }
    }

    /** Last time a flush completed successfully (used to compute time trigger). */
    public Instant getLastSuccessfulFlushAt() {
        return lastSuccessfulFlushAt;
    }

    /**
     * Adds an event to the in-memory buffer.
     * Non-blocking: does not wait for API calls.
     *
     * @return true if accepted, false if rejected (null/closed or DROP_NEWEST when full)
     */
    public boolean track(E event) {
        if (closed) return false;
        if (event == null) return false;

        boolean triggerBySize;

        synchronized (mutex) {
            if (buffer.size() >= config.maxBufferSize()) {
                applyDropPolicyBeforeAppend();
            }
            if (closed) return false;

            if (buffer.size() >= config.maxBufferSize() && config.dropPolicy() == DropPolicy.DROP_NEWEST) {
                return false;
            }

            buffer.addLast(event);
            triggerBySize = buffer.size() >= config.maxBatchSize();
        }

        if (triggerBySize) {
            scheduleFlushAsync();
        }
        return true;
    }

    /** Called periodically by Scheduler to check whether the time trigger is satisfied. */
    public void maybeFlushByTime() {
        if (closed) return;
        if (getBufferedCount() == 0) return;

        Duration sinceLastSuccess = Duration.between(lastSuccessfulFlushAt, clock.instant());
        if (sinceLastSuccess.compareTo(config.flushInterval()) >= 0) {
            scheduleFlushAsync();
        }
    }

    private void scheduleFlushAsync() {
        if (closed) return;

        // single-flight: prevents concurrent flushes
        if (!flushInProgress.compareAndSet(false, true)) return;

        CompletableFuture
                .runAsync(this::flushOnce, flushExecutor)
                .whenComplete((ignored, throwable) -> flushInProgress.set(false));
    }

    private void flushOnce() {
        if (closed) return;

        List<E> batch = drainSnapshot();
        if (batch.isEmpty()) return;

        try {
            api.send(batch).join();           // runs on background executor
            lastSuccessfulFlushAt = clock.instant(); // timer resets only on success
        } catch (RuntimeException apiFailure) {
            requeueFront(batch);
            enforceMaxBufferSizeAfterRequeue();
        }

        // If new events arrived while flushing and we're already above batch size, flush again.
        if (!closed && getBufferedCount() >= config.maxBatchSize()) {
            scheduleFlushAsync();
        }
    }

    private List<E> drainSnapshot() {
        synchronized (mutex) {
            if (buffer.isEmpty()) return List.of();
            List<E> snapshot = new ArrayList<>(buffer.size());
            while (!buffer.isEmpty()) {
                snapshot.add(buffer.removeFirst());
            }
            return snapshot;
        }
    }

    private void requeueFront(List<E> events) {
        synchronized (mutex) {
            for (int i = events.size() - 1; i >= 0; i--) {
                buffer.addFirst(events.get(i));
            }
        }
    }

    private void applyDropPolicyBeforeAppend() {
        if (config.dropPolicy() == DropPolicy.RAISE) {
            throw new IllegalStateException("Buffer capacity exceeded");
        }
        if (config.dropPolicy() == DropPolicy.DROP_OLDEST) {
            if (!buffer.isEmpty()) buffer.removeFirst();
        }
        // DROP_NEWEST: reject incoming event (handled in track)
    }

    private void enforceMaxBufferSizeAfterRequeue() {
        synchronized (mutex) {
            if (buffer.size() <= config.maxBufferSize()) return;

            if (config.dropPolicy() == DropPolicy.RAISE) {
                throw new IllegalStateException("Buffer capacity exceeded after requeue");
            }

            if (config.dropPolicy() == DropPolicy.DROP_OLDEST) {
                while (buffer.size() > config.maxBufferSize()) buffer.removeFirst();
            } else if (config.dropPolicy() == DropPolicy.DROP_NEWEST) {
                while (buffer.size() > config.maxBufferSize()) buffer.removeLast();
            }
        }
    }

    @Override
    public void close() {
        closed = true;
        scheduler.close();
    }
}
