package com.anindra.path.gateway;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Caps concurrent in-flight requests per client key.
 *
 * The rate limiter bounds requests per minute; this bounds requests at an instant, which
 * is what stops one key from opening thousands of streams and starving the pool.
 *
 * <p>The counting lives in a {@link ConcurrencyStore}: semaphores in this JVM by default,
 * a leased sorted set in Valkey when {@code gateway.shared-state.backend=valkey}. With the
 * shared store, a killed pod's permits expire instead of holding a key forever.
 */
@Component
public class ConcurrencyLimiter {

    private final ConcurrencyStore store;

    /** Kept so a unit test (or a non-Spring caller) gets the old behaviour. */
    public ConcurrencyLimiter() {
        this(new InMemoryConcurrencyStore());
    }

    @Autowired
    public ConcurrencyLimiter(ConcurrencyStore store) {
        this.store = store;
    }

    public static String keyBucket(Long keyId) {
        return "key:" + keyId;
    }

    public static String projectBucket(Long projectId) {
        return "project:" + projectId;
    }

    /**
     * Takes the key's permit and, when the key belongs to a project with a limit, the
     * project's permit too. Either both are held or neither is.
     *
     * @return true when permits were taken; callers must pass the same key to {@code release}
     */
    public boolean tryAcquire(com.anindra.path.domain.ClientKey key, int keyLimit,
                             Integer projectLimit) {
        if (!tryAcquire(keyBucket(key.getId()), keyLimit)) {
            return false;
        }
        if (projectLimit == null || projectLimit <= 0 || key.getProjectId() == null) {
            return true;
        }
        if (tryAcquire(projectBucket(key.getProjectId()), projectLimit)) {
            return true;
        }
        release(keyBucket(key.getId()));
        return false;
    }

    /** Releases both permits taken by {@link #tryAcquire(ClientKey, int, Integer)}. */
    public void release(com.anindra.path.domain.ClientKey key) {
        release(keyBucket(key.getId()));
        if (key.getProjectId() != null) {
            release(projectBucket(key.getProjectId()));
        }
    }

    /** @return true when a permit was taken; callers must release it. */
    public boolean tryAcquire(Long keyId, int limit) {
        return tryAcquire(keyBucket(keyId), limit);
    }

    /** @return true when a permit was taken; callers must release the same bucket. */
    public boolean tryAcquire(String bucket, int limit) {
        return store.tryAcquire(bucket, limit);
    }

    public void release(Long keyId) {
        release(keyBucket(keyId));
    }

    public void release(String bucket) {
        store.release(bucket);
    }

    public int inFlight(Long keyId) {
        return inFlight(keyBucket(keyId));
    }

    public int inFlight(String bucket) {
        return store.inFlight(bucket);
    }

    @Scheduled(fixedDelay = 600_000)
    void evictIdle() {
        store.evictIdle();
    }
}
