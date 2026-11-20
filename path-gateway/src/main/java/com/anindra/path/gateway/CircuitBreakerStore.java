package com.anindra.path.gateway;

import java.util.Map;

/**
 * Where a provider's circuit state lives.
 *
 * <p>Memory is the default and is exactly the v1 behaviour: state is per JVM and a restart
 * clears it. Valkey makes the state shared, so parking a provider on one replica parks it
 * on the others within one probe interval, and an OPEN entry expires on its own even if the
 * replica that opened it dies.
 *
 * <p>All mutating calls must be atomic: the whole point is that exactly one replica wins the
 * OPEN → HALF_OPEN probe.
 */
public interface CircuitBreakerStore {

    enum State {CLOSED, OPEN, HALF_OPEN}

    record Snapshot(State state, int failures, long openUntilMs) {
        public static Snapshot closed() {
            return new Snapshot(State.CLOSED, 0, 0);
        }
    }

    /** The current state; a provider that was never seen reads CLOSED. */
    Snapshot load(Long providerId);

    /** One more failure. Returns the new state when this failure opened the circuit, else null. */
    Snapshot recordFailure(Long providerId, int threshold, long nowMs, long openDurationMs);

    /** Opens the circuit unconditionally (an upstream 429 with Retry-After). */
    void open(Long providerId, int failures, long openUntilMs, long ttlMs);

    /** Claims the single HALF_OPEN probe when the OPEN window has elapsed. */
    boolean tryProbe(Long providerId, long nowMs, long probeLeaseMs);

    /** The provider answered: back to CLOSED. */
    void close(Long providerId);

    /** Every provider this store has state for, for the admin views. */
    Map<Long, Snapshot> snapshot();

    /** Drops state that has been idle; a no-op for a store that expires keys itself. */
    default void evictIdle() {
    }
}
