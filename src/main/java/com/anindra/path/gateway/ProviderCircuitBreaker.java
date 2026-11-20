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
 * After the window a probe request is allowed; success closes the circuit, failure reopens it.
 * An upstream 429 with {@code Retry-After} parks the provider for exactly that long.
 */
@Component
@RequiredArgsConstructor
public class ProviderCircuitBreaker {

    public enum State {CLOSED, OPEN, HALF_OPEN}

    private static final class Entry {
        State state = State.CLOSED;
        int failures;
        long openUntilMs;
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();
    /** Providers whose gauge has been registered; one gauge per provider, updated on scrape. */
    private final java.util.Set<Long> gauged = ConcurrentHashMap.newKeySet();
    private final GatewayProperties props;
    private final com.anindra.path.admin.AlertService alerts;
    private final MeterRegistry metrics;

    /**
     * One stable entry per provider. The Prometheus gauge reads the entry's state on scrape,
     * so it stays correct without a callback on every transition.
     */
    private Entry entry(Long providerId) {
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        if (gauged.add(providerId)) {
            metrics.gauge("path.provider.circuit.open",
                    java.util.List.of(io.micrometer.core.instrument.Tag.of(
                            "provider_id", String.valueOf(providerId))),
                    entry, e -> e.state == State.OPEN ? 1.0 : 0.0);
        }
        return entry;
    }

    /** Read-only projection used by the admin API. */
    public Map<String, Object> snapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        entries.forEach((providerId, entry) -> out.put(String.valueOf(providerId), Map.of(
                "state", entry.state.name().toLowerCase(),
                "failures", entry.failures,
                "retry_after_seconds", retryAfterSeconds(providerId))));
        return out;
    }

    public State state(Long providerId) {
        Entry entry = entries.get(providerId);
        return entry == null ? State.CLOSED : entry.state;
    }

    public long retryAfterSeconds(Long providerId) {
        Entry entry = entries.get(providerId);
        if (entry == null || entry.state != State.OPEN) {
            return 0;
        }
        return Math.max(0, (entry.openUntilMs - System.currentTimeMillis()) / 1000);
    }

    /** True when a request may be sent to this provider right now. */
    public boolean isAvailable(Long providerId) {
        Entry entry = entry(providerId);
        synchronized (entry) {
            if (entry.state != State.OPEN) {
                return true;
            }
            if (System.currentTimeMillis() >= entry.openUntilMs) {
                entry.state = State.HALF_OPEN;
                return true;
            }
            return false;
        }
    }

    public void onSuccess(Long providerId) {
        Entry entry = entry(providerId);
        synchronized (entry) {
            entry.state = State.CLOSED;
            entry.failures = 0;
            entry.openUntilMs = 0;
        }
    }

    public void onFailure(Long providerId) {
        Entry entry = entry(providerId);
        synchronized (entry) {
            entry.failures++;
            if (entry.state == State.HALF_OPEN
                    || entry.failures >= Math.max(1, props.getBreaker().getFailureThreshold())) {
                openUntil(entry, props.getBreaker().getOpenDuration());
                alerts.notify("provider.circuit_open", Map.of(
                        "provider_id", providerId,
                        "failures", entry.failures,
                        "retry_after_seconds", Math.max(0, (entry.openUntilMs - System.currentTimeMillis()) / 1000)));
            }
        }
    }

    /** Upstream asked us to back off; park the provider for exactly that long. */
    public void onRateLimited(Long providerId, Duration retryAfter) {
        Entry entry = entry(providerId);
        synchronized (entry) {
            openUntil(entry, retryAfter == null ? props.getBreaker().getOpenDuration() : retryAfter);
            alerts.notify("provider.rate_limited", Map.of(
                    "provider_id", providerId,
                    "retry_after_seconds", Math.max(0, (entry.openUntilMs - System.currentTimeMillis()) / 1000)));
        }
    }

    private static void openUntil(Entry entry, Duration duration) {
        entry.state = State.OPEN;
        entry.openUntilMs = System.currentTimeMillis() + Math.max(1, duration.toMillis());
    }
}
