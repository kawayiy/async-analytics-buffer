package com.patientcentre.analytics;

import com.patientcentre.analytics.api.AnalyticsApi;
import com.patientcentre.analytics.config.AnalyticsConfigLoader;
import com.patientcentre.analytics.config.BufferConfig;
import com.patientcentre.analytics.config.DropPolicy;
import com.patientcentre.analytics.core.AnalyticsBuffer;
import com.patientcentre.analytics.scheduling.Scheduler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class AnalyticsBufferTest {

    private final ExecutorService flushExecutor = Executors.newSingleThreadExecutor();

    @AfterEach
    void tearDown() {
        flushExecutor.shutdownNow();
    }

    /** Fake clock that can be advanced instantly (no sleeping). */
    static final class MutableClock extends Clock {
        private Instant now;
        private final ZoneId zoneId;

        MutableClock(Instant start, ZoneId zoneId) {
            this.now = start;
            this.zoneId = zoneId;
        }

        void advance(Duration delta) { now = now.plus(delta); }

        @Override public ZoneId getZone() { return zoneId; }
        @Override public Clock withZone(ZoneId zone) { return new MutableClock(now, zone); }
        @Override public Instant instant() { return now; }
    }

    /** Manual scheduler: test calls tick() to run the scheduled task. */
    static final class ManualScheduler implements Scheduler {
        private Runnable task;
        @Override public void scheduleAtFixedRate(Runnable task, Duration initialDelay, Duration period) {
            this.task = task;
        }
        void tick() { if (task != null) task.run(); }
        @Override public void close() {}
    }

    @Test
    void loadsConfigFromProperties() {
        BufferConfig cfg = AnalyticsConfigLoader.loadFromClasspath("analytics-test.properties");
        assertEquals(3, cfg.maxBatchSize());
        assertEquals(Duration.ofSeconds(2), cfg.flushInterval());
        assertEquals(Duration.ofMillis(10), cfg.tickerInterval());
        assertEquals(20, cfg.maxBufferSize());
        assertEquals(DropPolicy.DROP_OLDEST, cfg.dropPolicy());
    }

    @Test
    void flushesWhenBatchSizeReached() throws Exception {
        List<List<String>> sent = new ArrayList<>();
        AnalyticsApi<String> api = batch -> {
            sent.add(List.copyOf(batch));
            return CompletableFuture.completedFuture(null);
        };

        MutableClock clock = new MutableClock(Instant.EPOCH, ZoneId.of("UTC"));
        ManualScheduler scheduler = new ManualScheduler();
        BufferConfig cfg = new BufferConfig(10, Duration.ofSeconds(5), Duration.ofMillis(50), 1000, DropPolicy.DROP_OLDEST);

        try (AnalyticsBuffer<String> buffer = new AnalyticsBuffer<>(api, cfg, clock, scheduler, flushExecutor)) {
            for (int i = 0; i < 10; i++) assertTrue(buffer.track("e" + i));

            // wait for background flush
            flushExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);

            assertEquals(1, sent.size());
            assertEquals(10, sent.get(0).size());
            assertEquals(0, buffer.getBufferedCount());
        }
    }

    @Test
    void flushesWhenTimeIntervalElapsedWithoutRealWaiting() throws Exception {
        List<List<String>> sent = new ArrayList<>();
        AnalyticsApi<String> api = batch -> {
            sent.add(List.copyOf(batch));
            return CompletableFuture.completedFuture(null);
        };

        MutableClock clock = new MutableClock(Instant.EPOCH, ZoneId.of("UTC"));
        ManualScheduler scheduler = new ManualScheduler();
        BufferConfig cfg = new BufferConfig(10, Duration.ofSeconds(5), Duration.ofMillis(50), 1000, DropPolicy.DROP_OLDEST);

        try (AnalyticsBuffer<String> buffer = new AnalyticsBuffer<>(api, cfg, clock, scheduler, flushExecutor)) {
            assertTrue(buffer.track("page_view"));
            clock.advance(Duration.ofSeconds(5));
            scheduler.tick();

            flushExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);

            assertEquals(1, sent.size());
            assertEquals(List.of("page_view"), sent.get(0));
            assertEquals(0, buffer.getBufferedCount());
        }
    }

    @Test
    void doesNotDoubleFlushWhenSizeAndTimeCoincide() throws Exception {
        CountDownLatch apiStarted = new CountDownLatch(1); // used to "confirm that the flush has begun"
        CountDownLatch allowFinish = new CountDownLatch(1); //  used to "prevent the flush from ending"
        AtomicInteger calls = new AtomicInteger(0); // used to record how many times the API (flush) has been called

        AnalyticsApi<String> api = batch -> CompletableFuture.runAsync(() -> {
            calls.incrementAndGet();
            apiStarted.countDown();
            try { allowFinish.await(1, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
        });

        MutableClock clock = new MutableClock(Instant.EPOCH, ZoneId.of("UTC"));
        ManualScheduler scheduler = new ManualScheduler();
        BufferConfig cfg = new BufferConfig(10, Duration.ofSeconds(5), Duration.ofMillis(50), 1000, DropPolicy.DROP_OLDEST);

        try (AnalyticsBuffer<String> buffer = new AnalyticsBuffer<>(api, cfg, clock, scheduler, flushExecutor)) {
            for (int i = 0; i < 10; i++) buffer.track("e" + i);
            assertTrue(apiStarted.await(1, TimeUnit.SECONDS));

            // While flush in progress, also satisfy time trigger
            clock.advance(Duration.ofSeconds(5));
            scheduler.tick();

            allowFinish.countDown();
            flushExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);

            assertEquals(1, calls.get());
        }
    }

    /**
     * This test verifies that failed flushes requeue events and are retried on the next trigger,
     * ensuring at-least-once delivery semantics
     */
    @Test
    void apiFailureKeepsEventsAndRetriesNextTime() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0); // Used to count the number of times the API is called
        List<List<String>> sent = new ArrayList<>(); // Used to record the batches that have been successfully sent out

        AnalyticsApi<String> api = batch -> {
            if (callCount.incrementAndGet() == 1) {  // If this is the first call, then follow the failure logic.
                CompletableFuture<Void> failed = new CompletableFuture<>();
                failed.completeExceptionally(new RuntimeException("mock failure"));
                return failed;
            }
            sent.add(List.copyOf(batch)); // If it is not the first call, consider it a success
            return CompletableFuture.completedFuture(null);
        };

        MutableClock clock = new MutableClock(Instant.EPOCH, ZoneId.of("UTC"));
        ManualScheduler scheduler = new ManualScheduler();
        BufferConfig cfg = new BufferConfig(10, Duration.ofSeconds(5), Duration.ofMillis(50), 1000, DropPolicy.DROP_OLDEST);

        try (AnalyticsBuffer<String> buffer = new AnalyticsBuffer<>(api, cfg, clock, scheduler, flushExecutor)) {
            buffer.track("a");
            buffer.track("b");
            buffer.track("c");

            // trigger flush -> fail
            clock.advance(Duration.ofSeconds(5));
            scheduler.tick();
            // Wait for the previously submitted flush tasks in the flushExecutor to complete.
            flushExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);
            // the first call to the API will fail，it is asserted that the bufferedCount is still 3
            assertEquals(3, buffer.getBufferedCount());

            // trigger flush -> success
            clock.advance(Duration.ofSeconds(5));
            scheduler.tick();
            flushExecutor.submit(() -> {}).get(1, TimeUnit.SECONDS);

            assertEquals(0, buffer.getBufferedCount());
            assertEquals(1, sent.size());
            assertEquals(List.of("a", "b", "c"), sent.get(0));
        }
    }

    @Test
    void trackNullIsHandledGracefully() {
        AnalyticsApi<String> api = batch -> CompletableFuture.completedFuture(null);

        MutableClock clock = new MutableClock(Instant.EPOCH, ZoneId.of("UTC"));
        ManualScheduler scheduler = new ManualScheduler();
        BufferConfig cfg = new BufferConfig(10, Duration.ofSeconds(5), Duration.ofMillis(50), 1000, DropPolicy.DROP_OLDEST);

        try (AnalyticsBuffer<String> buffer = new AnalyticsBuffer<>(api, cfg, clock, scheduler, flushExecutor)) {
            assertFalse(buffer.track(null));
            assertEquals(0, buffer.getBufferedCount());
        }
    }
}
