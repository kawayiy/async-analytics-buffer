# The Patient Centre – Pre-Interview Homework (Java)

This project implements a **smart batching analytics buffer** for a client-side library.
It is designed to be **testable**, **extensible**, and **safe** (rubric-aligned), while remaining **framework-agnostic** (plain Java 17).

## What it does

`AnalyticsBuffer.track(event)` stores events in memory and flushes them asynchronously to an API when **either** condition is met:

1. **Batch size trigger**: the buffer reaches `analytics.maxBatchSize`
2. **Time trigger**: `analytics.flushIntervalSeconds` has elapsed since the **last successful flush**

## Key guarantees (rubric-aligned)

- **Non-blocking `track()`**: `track()` never waits for network I/O; flushing runs on a background executor.
- **No double flush**: size- and time-based triggers are deduplicated via a *single-flight* guard (`AtomicBoolean`).
- **Failure safety**: if the API fails, events are **re-queued** (not lost) and retried next time.
- **Bounded memory**: buffer growth is capped by `analytics.maxBufferSize` + `DropPolicy`.
- **Deterministic tests**: time-based behavior is tested without sleeping by injecting a fake `Clock` and a manual `Scheduler`.

## Package layout

```text
src/main/java/com/patientcentre/analytics
  ├─ api/        (AnalyticsApi)
  ├─ config/     (BufferConfig, DropPolicy, AnalyticsConfigLoader)
  ├─ core/       (AnalyticsBuffer)
  └─ scheduling/ (Scheduler, ExecutorScheduler)
```

## Configuration

Edit: `src/main/resources/analytics.properties`

```properties
analytics.maxBatchSize=10
analytics.flushIntervalSeconds=5
analytics.tickerIntervalMillis=250
analytics.maxBufferSize=1000
analytics.dropPolicy=DROP_OLDEST
```

### DropPolicy options

- `DROP_OLDEST`: drop oldest events to make room (keeps newest data)
- `DROP_NEWEST`: reject incoming events when full
- `RAISE`: throw on overflow (fail-fast)

## Run tests

Requirements:
- Java 17+
- Maven 3.9+

```bash
mvn test
```

## Example usage (pseudo)

```java
var config = AnalyticsConfigLoader.loadFromClasspath("analytics.properties");

var scheduler = new ExecutorScheduler(Executors.newSingleThreadScheduledExecutor());
var flushExecutor = Executors.newSingleThreadExecutor();

var buffer = new AnalyticsBuffer<>(api, config, Clock.systemUTC(), scheduler, flushExecutor);
buffer.track("page_viewed");
```

## Notes / potential production extensions

- Add exponential backoff / circuit breaker if the API is repeatedly failing.
- Persist events (e.g., IndexedDB/local storage) if they must survive restarts.
- Add metrics (flush latency, retry count, dropped events).
