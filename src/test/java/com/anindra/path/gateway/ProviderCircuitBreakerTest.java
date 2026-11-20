package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderCircuitBreakerTest {

    private ProviderCircuitBreaker breaker;

    @BeforeEach
    void setUp() {
        GatewayProperties props = new GatewayProperties();
        props.getBreaker().setFailureThreshold(2);
        props.getBreaker().setOpenDuration(Duration.ofMillis(120));
        breaker = new ProviderCircuitBreaker(props,
                new com.anindra.path.admin.AlertService(props,
                        new com.fasterxml.jackson.databind.ObjectMapper(),
                        org.mockito.Mockito.mock(com.anindra.path.admin.MailSettingsService.class),
                        new io.micrometer.core.instrument.simple.SimpleMeterRegistry()));
    }

    @Test
    void opensAfterThresholdAndClosesOnSuccessfulProbe() throws Exception {
        assertThat(breaker.isAvailable(1L)).isTrue();

        breaker.onFailure(1L);
        assertThat(breaker.isAvailable(1L)).isTrue();

        breaker.onFailure(1L);
        assertThat(breaker.isAvailable(1L)).isFalse();
        assertThat(breaker.state(1L)).isEqualTo(ProviderCircuitBreaker.State.OPEN);

        Thread.sleep(180);
        assertThat(breaker.isAvailable(1L)).isTrue();
        assertThat(breaker.state(1L)).isEqualTo(ProviderCircuitBreaker.State.HALF_OPEN);

        breaker.onSuccess(1L);
        assertThat(breaker.state(1L)).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
    }

    @Test
    void failureDuringHalfOpenReopensImmediately() throws Exception {
        breaker.onFailure(2L);
        breaker.onFailure(2L);
        Thread.sleep(180);

        assertThat(breaker.isAvailable(2L)).isTrue();
        breaker.onFailure(2L);
        assertThat(breaker.isAvailable(2L)).isFalse();
        assertThat(breaker.state(2L)).isEqualTo(ProviderCircuitBreaker.State.OPEN);
    }

    @Test
    void successResetsTheFailureCount() {
        breaker.onFailure(3L);
        breaker.onSuccess(3L);
        breaker.onFailure(3L);
        assertThat(breaker.isAvailable(3L)).isTrue();
    }

    @Test
    void rateLimitParksTheProviderForRetryAfter() {
        breaker.onRateLimited(4L, Duration.ofSeconds(5));

        assertThat(breaker.isAvailable(4L)).isFalse();
        assertThat(breaker.retryAfterSeconds(4L)).isBetween(3L, 5L);
        assertThat(breaker.state(4L)).isEqualTo(ProviderCircuitBreaker.State.OPEN);
    }

    @Test
    void unknownProviderIsClosedAndAvailable() {
        assertThat(breaker.isAvailable(99L)).isTrue();
        assertThat(breaker.state(99L)).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
        assertThat(breaker.retryAfterSeconds(99L)).isZero();
    }
}
