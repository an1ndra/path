package com.anindra.path.gateway;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The default backend: one map in this JVM, the same behaviour the breaker had before the
 * store existed. A restart clears it, and two replicas know nothing about each other.
 */
public class InMemoryCircuitBreakerStore implements CircuitBreakerStore {

    private static final class Entry {
        State state = State.CLOSED;
        int failures;
        long openUntilMs;
    }

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Snapshot load(Long providerId) {
        return snapshotOf(entries.get(providerId));
    }

    @Override
    public Snapshot recordFailure(Long providerId, int threshold, long nowMs, long openDurationMs) {
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        synchronized (entry) {
            entry.failures++;
            if (entry.state == State.HALF_OPEN || entry.failures >= Math.max(1, threshold)) {
                entry.state = State.OPEN;
                entry.openUntilMs = nowMs + Math.max(1, openDurationMs);
                return snapshotOf(entry);
            }
            return null;
        }
    }

    @Override
    public void open(Long providerId, int failures, long openUntilMs, long ttlMs) {
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        synchronized (entry) {
            entry.state = State.OPEN;
            entry.failures = Math.max(entry.failures, failures);
            entry.openUntilMs = openUntilMs;
        }
    }

    @Override
    public boolean tryProbe(Long providerId, long nowMs, long probeLeaseMs) {
        Entry entry = entries.get(providerId);
        if (entry == null) {
            return false;
        }
        synchronized (entry) {
            if (entry.state != State.OPEN || nowMs < entry.openUntilMs) {
                return false;
            }
            // Exactly one caller sees the transition: everyone else finds HALF_OPEN.
            entry.state = State.HALF_OPEN;
            return true;
        }
    }

    @Override
    public void close(Long providerId) {
        Entry entry = entries.computeIfAbsent(providerId, k -> new Entry());
        synchronized (entry) {
            entry.state = State.CLOSED;
            entry.failures = 0;
            entry.openUntilMs = 0;
        }
    }

    @Override
    public Map<Long, Snapshot> snapshot() {
        Map<Long, Snapshot> out = new LinkedHashMap<>();
        entries.forEach((providerId, entry) -> {
            synchronized (entry) {
                out.put(providerId, snapshotOf(entry));
            }
        });
        return out;
    }

    @Override
    public void evictIdle() {
        // Bounded by the number of providers, which is small; kept as a hook like the
        // routing metrics so growth is not unbounded by accident.
        if (entries.size() > 10_000) {
            entries.clear();
        }
    }

    private static Snapshot snapshotOf(Entry entry) {
        if (entry == null) {
            return Snapshot.closed();
        }
        return new Snapshot(entry.state, entry.failures, entry.openUntilMs);
    }
}
