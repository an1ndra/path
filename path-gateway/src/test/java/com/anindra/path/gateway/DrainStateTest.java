package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.config.GatewayProperties;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrainStateTest {

    private DrainState state() {
        GatewayProperties props = new GatewayProperties();
        props.setDrainTimeout(Duration.ofMillis(200));
        return new DrainState(props);
    }

    @Test
    void tracksInFlightRequests() {
        DrainState drain = state();
        drain.begin();
        drain.begin();
        assertThat(drain.inFlight()).isEqualTo(2);

        drain.end();
        assertThat(drain.inFlight()).isEqualTo(1);
    }

    @Test
    void refusesNewWorkOnceDraining() {
        DrainState drain = state();
        drain.drain();

        assertThat(drain.isDraining()).isTrue();
        assertThatThrownBy(drain::begin)
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "shutting_down");
    }

    @Test
    void drainWaitsForInFlightWorkUpToTheTimeout() {
        DrainState drain = state();
        drain.begin();

        long started = System.currentTimeMillis();
        drain.drain();
        long elapsed = System.currentTimeMillis() - started;

        assertThat(elapsed).isGreaterThanOrEqualTo(150);
        assertThat(drain.isDraining()).isTrue();
    }
}
