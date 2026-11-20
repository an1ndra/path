package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-provider circuit breaker.
 *
 * A provider that keeps failing is parked for {@code open-duration}, during which routes
 * on it are skipped in microseconds instead of costing every request a connect timeout.
 * After the window a single probe request is allowed; success closes the circuit, failure
 * reopens it. An upstream 429 with {@code Retry-After} parks the provider for exactly that long.
 *
 * <p>State lives in a {@link CircuitBreakerStore}: memory by default, Valkey when
 * {@code gateway.shared-state.backend=valkey}. The interface is what makes "exactly one
 * replica probes" possible at all.
 */
@Component
@RequiredArgsConstructor
public class ProviderCircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private final GatewayProperties props;
    private final com.anindra.path.admin.AlertService alerts;
    private final MeterRegistry metrics;
    private final CircuitBreakerStore store;

    /** Providers whose Prometheus gauge is registered; the gauge reads the store on scrape. */
    private final java.util.Set<Long> gauged = ConcurrentHashMap.newKeySet();

    private void registerGauge(Long providerId) {
        if (gauged.add(providerId)) {
            metrics.gauge("path.provider.circuit.open",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of(
                            "provider_id", String.valueOf(providerId))),
                    providerId,
                    id -> store.load(id).state() == CircuitBreakerStore.State.OPEN ? 1.0 : 0.0);
        }
    }

    /** Read-only projection used by the admin API. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        store.snapshot().forEach((providerId, entry) -> out.put(String.valueOf(providerId), Map.of(
                "state", entry.state().name().toLowerCase(),
                "failures", entry.failures(),
                "retry_after_seconds", retryAfterSeconds(entry, System.currentTimeMillis()))));
        return out;
    }

    public State state(Long providerId) {
        registerGauge(providerId);
        return stateOf(store.load(providerId));
    }

    public long retryAfterSeconds(Long providerId) {
        return retryAfterSeconds(store.load(providerId), System.currentTimeMillis());
    }

    /** True when the provider is parked right now, without consuming the half-open probe. */
    public boolean isParked(Long providerId) {
        CircuitBreakerStore.Snapshot entry = store.load(providerId);
        return entry.state() == CircuitBreakerStore.State.OPEN
                && System.currentTimeMillis() < entry.openUntilMs();
    }

    /**
     * True when a request may be sent to this provider right now. When an OPEN window has
     * elapsed, the first caller claims the single HALF_OPEN probe; everyone else is told no
     * until that probe reports back or its lease expires.
     */
    public boolean isAvailable(Long providerId) {
        registerGauge(providerId);
        CircuitBreakerStore.Snapshot entry = store.load(providerId);
        if (entry.state() == CircuitBreakerStore.State.CLOSED) {
            return true;
        }
        if (entry.state() == CircuitBreakerStore.State.HALF_OPEN) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (now < entry.openUntilMs()) {
            return false;
        }
        return store.tryProbe(providerId, now, props.getBreaker().getProbeLease().toMillis());
    }

    public void onSuccess(Long providerId) {
        store.close(providerId);
    }

    public void onFailure(Long providerId) {
        long now = System.currentTimeMillis();
        CircuitBreakerStore.Snapshot opened = store.recordFailure(providerId,
                props.getBreaker().getFailureThreshold(), now,
                props.getBreaker().getOpenDuration().toMillis());
        if (opened != null) {
            alerts.notify("provider.circuit_open", Map.of(
                    "provider_id", providerId,
                    "failures", opened.failures(),
                    "retry_after_seconds", retryAfterSeconds(opened, now)));
        }
    }

    /** Upstream asked us to back off; park the provider for exactly that long. */
    public void onRateLimited(Long providerId, Duration retryAfter) {
        Duration duration = retryAfter == null ? props.getBreaker().getOpenDuration() : retryAfter;
        long now = System.currentTimeMillis();
        long openFor = Math.max(1, duration.toMillis());
        CircuitBreakerStore.Snapshot current = store.load(providerId);
        store.open(providerId, current.failures(), now + openFor, openFor + 5_000);
        alerts.notify("provider.rate_limited", Map.of(
                "provider_id", providerId,
                "retry_after_seconds", openFor / 1000));
    }

    private static State stateOf(CircuitBreakerStore.Snapshot entry) {
        return State.valueOf(entry.state().name());
    }

    private static long retryAfterSeconds(CircuitBreakerStore.Snapshot entry, long now) {
        if (entry.state() != CircuitBreakerStore.State.OPEN) {
            return 0;
        }
        return Math.max(0, (entry.openUntilMs() - now) / 1000);
    }
}
