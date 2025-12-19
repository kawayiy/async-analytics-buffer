package com.patientcentre.analytics.config;

import java.time.Duration;

/**
 * Strongly-typed configuration for batching and safety.
 */
public record BufferConfig(
        int maxBatchSize,
        Duration flushInterval,
        Duration tickerInterval,
        int maxBufferSize,
        DropPolicy dropPolicy
) {
    public BufferConfig {
        if (maxBatchSize <= 0) throw new IllegalArgumentException("maxBatchSize must be > 0");
        if (flushInterval == null || flushInterval.isZero() || flushInterval.isNegative()) {
            throw new IllegalArgumentException("flushInterval must be > 0");
        }
        if (tickerInterval == null || tickerInterval.isZero() || tickerInterval.isNegative()) {
            throw new IllegalArgumentException("tickerInterval must be > 0");
        }
        if (maxBufferSize <= 0) throw new IllegalArgumentException("maxBufferSize must be > 0");
        if (dropPolicy == null) throw new IllegalArgumentException("dropPolicy must not be null");
    }
}
