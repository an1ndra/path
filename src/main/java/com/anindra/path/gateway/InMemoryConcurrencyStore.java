package com.anindra.path.gateway;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/** Semaphores in this JVM: the pre-Valkey behaviour, unchanged. */
public class InMemoryConcurrencyStore implements ConcurrencyStore {

    private record Entry(int limit, Semaphore semaphore) {
    }

    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
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

    @Override
    public void release(String bucket) {
        Entry entry = entries.get(bucket);
        if (entry != null && entry.semaphore().availablePermits() < entry.limit()) {
            entry.semaphore().release();
        }
    }

    @Override
    public int inFlight(String bucket) {
        Entry entry = entries.get(bucket);
        return entry == null ? 0 : entry.limit() - entry.semaphore().availablePermits();
    }

    @Override
    public void evictIdle() {
        entries.entrySet().removeIf(e ->
                e.getValue().semaphore().availablePermits() == e.getValue().limit());
    }
}
