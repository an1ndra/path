package com.anindra.path.gateway;

import com.anindra.path.domain.Provider;
import com.anindra.path.repo.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Surfaces provider circuit state in /actuator/health.
 *
 * Reports DEGRADED (not DOWN) when a provider is parked: a single failing upstream should
 * not take the gateway out of a load-balancer rotation, because fallback still works.
 */
@Component
@RequiredArgsConstructor
public class ProviderHealthIndicator implements HealthIndicator {

    /** Boot ranks unknown status codes below UP, so DEGRADED would never win the aggregate. */
    private static final Status ALL_PARKED =
            new Status("OUT_OF_SERVICE", "every enabled provider is circuit-open");

    private final ProviderRepository providers;
    private final ProviderCircuitBreaker breaker;

    @Override
    public Health health() {
        Map<String, Object> details = new LinkedHashMap<>();
        int enabled = 0;
        int open = 0;

        for (Provider provider : providers.findByEnabledTrueOrderBySlugAsc()) {
            ProviderCircuitBreaker.State state = breaker.state(provider.getId());
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("circuit", state.name().toLowerCase());
            entry.put("retry_after_seconds", breaker.retryAfterSeconds(provider.getId()));
            details.put(provider.getSlug(), entry);
            enabled++;
            if (state == ProviderCircuitBreaker.State.OPEN) {
                open++;
            }
        }

        // One parked provider still has fallback, so the gateway stays in rotation.
        // Only a total outage is OUT_OF_SERVICE (503).
        boolean totalOutage = enabled > 0 && open == enabled;
        return (totalOutage ? Health.status(ALL_PARKED) : Health.up())
                .withDetail("open_circuits", open)
                .withDetail("enabled_providers", enabled)
                .withDetail("providers", details)
                .build();
    }
}
