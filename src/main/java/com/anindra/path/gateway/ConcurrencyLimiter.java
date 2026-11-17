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

    private final Map<Long, Entry> entries = new ConcurrentHashMap<>();

    /** @return true when a permit was taken; callers must release it. */
    public boolean tryAcquire(Long keyId, int limit) {
        if (limit <= 0) {
            return true;
        }
        Entry entry = entries.compute(keyId, (id, current) ->
                current == null || current.limit() != limit
                        ? new Entry(limit, new Semaphore(limit))
                        : current);
        return entry.semaphore().tryAcquire();
    }

    public void release(Long keyId) {
        Entry entry = entries.get(keyId);
        if (entry != null && entry.semaphore().availablePermits() < entry.limit()) {
            entry.semaphore().release();
        }
    }

    public int inFlight(Long keyId) {
        Entry entry = entries.get(keyId);
        return entry == null ? 0 : entry.limit() - entry.semaphore().availablePermits();
    }

    @Scheduled(fixedDelay = 600_000)
    void evictIdle() {
        entries.entrySet().removeIf(e ->
                e.getValue().semaphore().availablePermits() == e.getValue().limit());
    }
}
