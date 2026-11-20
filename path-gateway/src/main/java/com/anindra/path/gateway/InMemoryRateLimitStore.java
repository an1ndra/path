package com.anindra.path.gateway;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Fixed one-minute window per key. Exact on a single instance; a cluster needs
 * {@link ValkeyRateLimitStore} instead.
 */
public class InMemoryRateLimitStore implements RateLimitStore {

    private record Window(long minute, int used) {
    }

    private final Map<String, Window> windows = new ConcurrentHashMap<>();

    @Override
    public int acquire(String bucket, int rpmLimit) {
        if (rpmLimit <= 0) {
            return 0;
        }
        long minute = Instant.now().getEpochSecond() / 60;
        int[] retryAfter = {0};
        windows.compute(bucket, (id, current) -> {
            if (current == null || current.minute() != minute) {
                return new Window(minute, 1);
            }
            if (current.used() < rpmLimit) {
                return new Window(minute, current.used() + 1);
            }
            retryAfter[0] = (int) Math.max(1, 60 - (Instant.now().getEpochSecond() % 60));
            return current;
        });
        return retryAfter[0];
    }

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 300_000)
    void evictStale() {
        long cutoff = Instant.now().getEpochSecond() / 60 - 10;
        windows.entrySet().removeIf(e -> e.getValue().minute() < cutoff);
    }
}
