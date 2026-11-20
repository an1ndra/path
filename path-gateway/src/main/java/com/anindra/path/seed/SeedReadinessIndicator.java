package com.anindra.path.seed;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports OUT_OF_SERVICE until the first-boot seed has committed, so load balancers
 * and CI wait loops do not send traffic to a gateway with no providers or routes.
 */
@Component
@RequiredArgsConstructor
public class SeedReadinessIndicator implements HealthIndicator {

    private final SeedState state;
    private final com.anindra.path.gateway.DrainState drain;

    @Override
    public Health health() {
        if (drain.isDraining()) {
            return Health.outOfService()
                    .withDetail("seeded", state.isReady())
                    .withDetail("draining", true)
                    .build();
        }
        return state.isReady()
                ? Health.up().withDetail("seeded", true).build()
                : Health.outOfService().withDetail("seeded", false).build();
    }
}
