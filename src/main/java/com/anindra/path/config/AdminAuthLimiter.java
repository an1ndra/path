package com.anindra.path.config;

import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Slows down guessing of the admin token: too many failed attempts from one address
 * within the window return 429 instead of another cheap comparison.
 */
@Component
@RequiredArgsConstructor
public class AdminAuthLimiter {

    private record Entry(long windowStart, int failures) {
    }

    private final GatewayProperties props;
    private final Map<String, Entry> entries = new ConcurrentHashMap<>();

    public long retryAfterSeconds(String address) {
        Entry entry = entries.get(address);
        if (entry == null) {
            return 0;
        }
        long windowMs = props.getAdminAuth().getWindow().toMillis();
        long elapsed = System.currentTimeMillis() - entry.windowStart();
        if (elapsed >= windowMs || entry.failures() < props.getAdminAuth().getMaxFailures()) {
            return 0;
        }
        return Math.max(1, (windowMs - elapsed) / 1000);
    }

    public void onFailure(String address) {
        long now = System.currentTimeMillis();
        long windowMs = props.getAdminAuth().getWindow().toMillis();
        entries.compute(address, (key, current) -> {
            if (current == null || now - current.windowStart() >= windowMs) {
                return new Entry(now, 1);
            }
            return new Entry(current.windowStart(), current.failures() + 1);
        });
    }

    public void onSuccess(String address) {
        entries.remove(address);
    }

    @Scheduled(fixedDelay = 300_000)
    void evictStale() {
        long cutoff = System.currentTimeMillis() - props.getAdminAuth().getWindow().toMillis();
        entries.entrySet().removeIf(e -> e.getValue().windowStart() < cutoff);
    }
}
