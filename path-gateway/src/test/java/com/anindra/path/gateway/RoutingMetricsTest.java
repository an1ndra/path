package com.anindra.path.gateway;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoutingMetricsTest {

    private final RoutingMetrics metrics = new RoutingMetrics();

    @Test
    void startsUnknown() {
        assertThat(metrics.latencyMs("openai", "gpt-4o")).isNaN();
        assertThat(metrics.sampleCount("openai", "gpt-4o")).isZero();
        assertThat(metrics.errorRate("openai", "gpt-4o")).isZero();
    }

    @Test
    void theFirstSampleIsTakenAsIs() {
        metrics.recordSuccess("openai", "gpt-4o", 500);
        assertThat(metrics.latencyMs("openai", "gpt-4o")).isEqualTo(500.0);
    }

    @Test
    void convergesTowardRepeatedSamples() {
        metrics.recordSuccess("openai", "gpt-4o", 1000);
        for (int i = 0; i < 30; i++) {
            metrics.recordSuccess("openai", "gpt-4o", 100);
        }
        assertThat(metrics.latencyMs("openai", "gpt-4o")).isBetween(100.0, 120.0);
    }

    @Test
    void failuresRaiseTheErrorRateWithoutZeroingLatency() {
        metrics.recordSuccess("openai", "gpt-4o", 400);
        metrics.recordFailure("openai", "gpt-4o");

        assertThat(metrics.errorRate("openai", "gpt-4o")).isGreaterThan(0.0);
        assertThat(metrics.latencyMs("openai", "gpt-4o")).isEqualTo(400.0);
    }

    @Test
    void providersAreTrackedSeparately() {
        metrics.recordSuccess("openai", "chat", 100);
        metrics.recordSuccess("groq", "chat", 900);

        assertThat(metrics.latencyMs("openai", "chat")).isLessThan(metrics.latencyMs("groq", "chat"));
    }
}
