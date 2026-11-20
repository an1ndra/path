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

    @Test
    void aRotatingAddressDoesNotLiftTheCap() {
        // The bypass: Cloudflare edge addresses change per request, and a forged
        // X-Forwarded-For changes per request by construction. Counting per address alone
        // let thousands of guesses through, so the process-wide window has to close first.
        GatewayProperties props = new GatewayProperties();
        props.getAdminAuth().setMaxFailures(2);
        props.getAdminAuth().setGlobalMaxFailures(5);
        props.getAdminAuth().setWindow(Duration.ofMinutes(5));
        AdminAuthLimiter limiter = new AdminAuthLimiter(props);

        for (int i = 0; i < 5; i++) {
            limiter.onFailure("203.0.113." + i);
        }

        assertThat(limiter.retryAfterSeconds("203.0.113.99")).isGreaterThan(0);
        assertThat(limiter.retryAfterSeconds("198.51.100.7")).isGreaterThan(0);
    }

    @Test
    void aLegitimateAdminIsNotLockedOutByOtherAddresses() {
        GatewayProperties props = new GatewayProperties();
        props.getAdminAuth().setMaxFailures(2);
        props.getAdminAuth().setGlobalMaxFailures(5);
        props.getAdminAuth().setWindow(Duration.ofMinutes(5));
        AdminAuthLimiter limiter = new AdminAuthLimiter(props);

        limiter.onFailure("203.0.113.1");
        assertThat(limiter.retryAfterSeconds("198.51.100.7")).isZero();
    }

    @Test
    void theGlobalCapIsOffByDefaultSoAScannerCannotLockTheOperatorOut() {
        // A fail-closed global cap is a denial-of-service: one hostile source spending the
        // budget in the window locks the console for everybody, including the operator.
        GatewayProperties props = new GatewayProperties();
        props.getAdminAuth().setMaxFailures(2);
        props.getAdminAuth().setWindow(Duration.ofMinutes(5));
        AdminAuthLimiter limiter = new AdminAuthLimiter(props);

        for (int i = 0; i < 500; i++) {
            limiter.onFailure("203.0.113.200");
        }

        assertThat(props.getAdminAuth().getGlobalMaxFailures()).isZero();
        assertThat(limiter.retryAfterSeconds("198.51.100.7")).isZero();
        // The noisy address itself is still stopped.
        assertThat(limiter.retryAfterSeconds("203.0.113.200")).isGreaterThan(0);
    }
}
