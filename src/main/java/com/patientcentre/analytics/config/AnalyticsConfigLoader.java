package com.patientcentre.analytics.config;

import java.io.InputStream;
import java.time.Duration;
import java.util.Properties;

/**
 * Loads BufferConfig from a properties file on the classpath.
 *
 * Required keys:
 *  - analytics.maxBatchSize
 *  - analytics.flushIntervalSeconds
 *  - analytics.tickerIntervalMillis
 *  - analytics.maxBufferSize
 *  - analytics.dropPolicy
 */
public final class AnalyticsConfigLoader {

    private AnalyticsConfigLoader() {}

    public static BufferConfig loadFromClasspath(String fileName) {
        Properties props = new Properties();

        try (InputStream in = AnalyticsConfigLoader.class.getClassLoader().getResourceAsStream(fileName)) {
            if (in == null) {
                throw new IllegalStateException("Config file not found on classpath: " + fileName);
            }
            props.load(in);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load config: " + fileName, e);
        }

        int maxBatchSize = getInt(props, "analytics.maxBatchSize");
        int flushIntervalSeconds = getInt(props, "analytics.flushIntervalSeconds");
        int tickerIntervalMillis = getInt(props, "analytics.tickerIntervalMillis");
        int maxBufferSize = getInt(props, "analytics.maxBufferSize");
        DropPolicy dropPolicy = DropPolicy.valueOf(getString(props, "analytics.dropPolicy").trim());

        return new BufferConfig(
                maxBatchSize,
                Duration.ofSeconds(flushIntervalSeconds),
                Duration.ofMillis(tickerIntervalMillis),
                maxBufferSize,
                dropPolicy
        );
    }

    private static int getInt(Properties props, String key) {
        return Integer.parseInt(getString(props, key).trim());
    }

    private static String getString(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) throw new IllegalStateException("Missing required config key: " + key);
        return value;
    }
}
