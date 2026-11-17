package com.anindra.path.gateway;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Caps concurrent in-flight requests per client key.
 *
 * The rate limiter bounds requests per minute; this bounds requests at an instant, which
 * is what stops one key from opening thousands of streams and starving the pool.
 */
@Component
public class ConcurrencyLimiter {

    private record Entry(int limit, Semaphore semaphore) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

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
        if (limit <= 0) {
            return true;
        }
        Entry entry = entries.compute(bucket, (id, current) ->
                current == null || current.limit() != limit
                        ? new Entry(limit, new Semaphore(limit))
                        : current);
        return entry.semaphore().tryAcquire();
    }

    public void release(Long keyId) {
        release(keyBucket(keyId));
    }

    public void release(String bucket) {
        Entry entry = entries.get(bucket);
        if (entry != null && entry.semaphore().availablePermits() < entry.limit()) {
            entry.semaphore().release();
        }
    }

    public int inFlight(Long keyId) {
        return inFlight(keyBucket(keyId));
    }

    public int inFlight(String bucket) {
        Entry entry = entries.get(bucket);
        return entry == null ? 0 : entry.limit() - entry.semaphore().availablePermits();
    }

    @Scheduled(fixedDelay = 600_000)
    void evictIdle() {
        entries.entrySet().removeIf(e ->
                e.getValue().semaphore().availablePermits() == e.getValue().limit());
    }
}
