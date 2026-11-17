package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
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
    private final GatewayProperties props;

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
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
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
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        synchronized (entry) {
            entry.state = State.CLOSED;
            entry.failures = 0;
            entry.openUntilMs = 0;
        }
    }

    public void onFailure(Long providerId) {
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        synchronized (entry) {
            entry.failures++;
            if (entry.state == State.HALF_OPEN
                    || entry.failures >= Math.max(1, props.getBreaker().getFailureThreshold())) {
                openUntil(entry, props.getBreaker().getOpenDuration());
            }
        }
    }

    /** Upstream asked us to back off; park the provider for exactly that long. */
    public void onRateLimited(Long providerId, Duration retryAfter) {
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        synchronized (entry) {
            openUntil(entry, retryAfter == null ? props.getBreaker().getOpenDuration() : retryAfter);
        }
    }

    private static void openUntil(Entry entry, Duration duration) {
        entry.state = State.OPEN;
        entry.openUntilMs = System.currentTimeMillis() + Math.max(1, duration.toMillis());
    }
}
