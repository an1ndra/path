package com.anindra.path.config;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AdminAuthLimiterTest {

    private AdminAuthLimiter limiter() {
        GatewayProperties props = new GatewayProperties();
        props.getAdminAuth().setMaxFailures(2);
        props.getAdminAuth().setWindow(Duration.ofMinutes(5));
        return new AdminAuthLimiter(props);
    }

    @Test
    void locksOutAfterTheConfiguredFailures() {
        AdminAuthLimiter limiter = limiter();

        limiter.onFailure("10.0.0.1");
        assertThat(limiter.retryAfterSeconds("10.0.0.1")).isZero();

        limiter.onFailure("10.0.0.1");
        assertThat(limiter.retryAfterSeconds("10.0.0.1")).isGreaterThan(0);
        assertThat(limiter.retryAfterSeconds("10.0.0.2")).isZero();
    }

    @Test
    void successClearsTheCounter() {
        AdminAuthLimiter limiter = limiter();
        limiter.onFailure("10.0.0.3");
        limiter.onFailure("10.0.0.3");
        assertThat(limiter.retryAfterSeconds("10.0.0.3")).isGreaterThan(0);

        limiter.onSuccess("10.0.0.3");
        assertThat(limiter.retryAfterSeconds("10.0.0.3")).isZero();
    }
}
