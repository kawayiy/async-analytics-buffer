package com.patientcentre.analytics.api;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Mockable contract for sending analytics batches.
 * In production this would wrap HTTP calls; in tests we stub it.
 */
public interface AnalyticsApi<E> {
    CompletableFuture<Void> send(List<E> batch);
}
