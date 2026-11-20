package com.anindra.path.gateway;

/**
 * Where per-bucket in-flight permits live.
 *
 * <p>Memory is the default: a semaphore per bucket, per JVM, so a cluster's effective cap is
 * {@code replicas × limit}. Valkey shares the count and gives every permit a lease, so a
 * pod that is killed mid-request does not hold a permit forever.
 */
public interface ConcurrencyStore {

    /** @return true when a permit was taken; callers must release the same bucket. */
    boolean tryAcquire(String bucket, int limit);

    void release(String bucket);

    int inFlight(String bucket);

    /** Drops idle bookkeeping; a no-op for a store that expires entries itself. */
    default void evictIdle() {
    }
}
