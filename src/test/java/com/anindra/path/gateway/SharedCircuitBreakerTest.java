package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Two replicas, one store: this is the behaviour #74 asked for. The same assertions run
 * against the Valkey store in production; memory proves the breaker logic does not depend
 * on two JVMs sharing an object.
 */
class SharedCircuitBreakerTest {

    private final GatewayProperties props = new GatewayProperties();
    private final CircuitBreakerStore store = new InMemoryCircuitBreakerStore();

    private ProviderCircuitBreaker replica() {
        props.getBreaker().setFailureThreshold(2);
        props.getBreaker().setOpenDuration(Duration.ofMillis(100));
        props.getBreaker().setProbeLease(Duration.ofSeconds(2));
        return new ProviderCircuitBreaker(props,
                Mockito.mock(com.anindra.path.admin.AlertService.class),
                new SimpleMeterRegistry(),
                store);
    }

    @Test
    void parkingOnOneReplicaIsVisibleOnTheOther() {
        ProviderCircuitBreaker one = replica();
        ProviderCircuitBreaker two = replica();

        one.onFailure(7L);
        one.onFailure(7L);

        assertThat(one.state(7L)).isEqualTo(ProviderCircuitBreaker.State.OPEN);
        assertThat(two.state(7L)).isEqualTo(ProviderCircuitBreaker.State.OPEN);
        assertThat(two.isParked(7L)).isTrue();
        assertThat(two.isAvailable(7L)).isFalse();
    }

    @Test
    void exactlyOneReplicaClaimsTheProbe() throws Exception {
        ProviderCircuitBreaker one = replica();
        ProviderCircuitBreaker two = replica();
        one.onFailure(7L);
        one.onFailure(7L);
        Thread.sleep(150);

        boolean first = one.isAvailable(7L);
        boolean second = two.isAvailable(7L);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(two.isParked(7L)).isFalse(); // HALF_OPEN is not "parked", it is being probed
    }

    @Test
    void aSuccessfulProbeClosesTheCircuitForEveryone() throws Exception {
        ProviderCircuitBreaker one = replica();
        ProviderCircuitBreaker two = replica();
        one.onFailure(7L);
        one.onFailure(7L);
        Thread.sleep(150);

        assertThat(one.isAvailable(7L)).isTrue();
        one.onSuccess(7L);

        assertThat(two.state(7L)).isEqualTo(ProviderCircuitBreaker.State.CLOSED);
        assertThat(two.isAvailable(7L)).isTrue();
    }

    @Test
    void aFailedProbeReopensTheCircuit() throws Exception {
        ProviderCircuitBreaker one = replica();
        ProviderCircuitBreaker two = replica();
        one.onFailure(7L);
        one.onFailure(7L);
        Thread.sleep(150);

        assertThat(one.isAvailable(7L)).isTrue();
        one.onFailure(7L);

        assertThat(two.state(7L)).isEqualTo(ProviderCircuitBreaker.State.OPEN);
    }

    @Test
    void twoLimitersShareOneCapWhenTheyShareTheStore() {
        ConcurrencyStore shared = new InMemoryConcurrencyStore();
        ConcurrencyLimiter one = new ConcurrencyLimiter(shared);
        ConcurrencyLimiter two = new ConcurrencyLimiter(shared);

        assertThat(one.tryAcquire(11L, 2)).isTrue();
        assertThat(two.tryAcquire(11L, 2)).isTrue();
        assertThat(one.tryAcquire(11L, 2)).isFalse();
        assertThat(two.inFlight(11L)).isEqualTo(2);

        one.release(11L);
        assertThat(two.tryAcquire(11L, 2)).isTrue();
    }
}
