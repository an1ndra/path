package com.anindra.path.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ClientAuthLimiterTest {

    private ClientAuthLimiter limiter(int maxFailures) {
        GatewayProperties props = new GatewayProperties();
        props.getClientAuth().setMaxFailures(maxFailures);
        props.getClientAuth().setWindow(Duration.ofMinutes(1));
        return new ClientAuthLimiter(props);
    }

    @Test
    void stopsASourceAfterTooManyRejectedKeys() {
        ClientAuthLimiter limiter = limiter(3);

        for (int i = 0; i < 3; i++) {
            limiter.onFailure("203.0.113.5");
        }

        assertThat(limiter.retryAfterSeconds("203.0.113.5")).isGreaterThan(0);
        assertThat(limiter.retryAfterSeconds("203.0.113.6")).isZero();
    }

    @Test
    void aWorkingKeyClearsTheCounter() {
        ClientAuthLimiter limiter = limiter(2);
        limiter.onFailure("203.0.113.7");
        limiter.onFailure("203.0.113.7");
        assertThat(limiter.retryAfterSeconds("203.0.113.7")).isGreaterThan(0);

        limiter.onSuccess("203.0.113.7");
        assertThat(limiter.retryAfterSeconds("203.0.113.7")).isZero();
    }
}
