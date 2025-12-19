package com.patientcentre.analytics.scheduling;

import java.time.Duration;

/**
 * Abstraction for scheduling periodic tasks.
 * This makes time-based behavior testable without real waiting.
 */
public interface Scheduler extends AutoCloseable {
    void scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period);
    @Override void close();
}
