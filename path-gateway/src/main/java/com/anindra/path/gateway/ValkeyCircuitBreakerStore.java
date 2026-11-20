package com.anindra.path.gateway;

import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Circuit state in Valkey, shared by every replica.
 *
 * <p>One hash per provider: {@code state}, {@code open_until} (epoch ms) and {@code failures}.
 * A provider with no key is CLOSED, so a successful call deletes the key instead of writing
 * a tombstone. The key's TTL is the safety net: an OPEN entry expires shortly after its own
 * retry-after even if the replica that opened it never comes back, and an abandoned
 * HALF_OPEN probe lease expires the same way.
 *
 * <p>The scripts are the atomic part. Two replicas reacting to the same expired OPEN window
 * both run {@code PATH_PROBE}; the first flips the state to HALF_OPEN and the second sees it
 * and loses the claim, so exactly one probe is sent.
 */
public class ValkeyCircuitBreakerStore implements CircuitBreakerStore {

    private static final RedisScript<List> RECORD_FAILURE = new DefaultRedisScript<>(
            "local failures = redis.call('HINCRBY', KEYS[1], 'failures', 1) "
                    + "local state = redis.call('HGET', KEYS[1], 'state') "
                    + "if state == 'HALF_OPEN' or failures >= tonumber(ARGV[1]) then "
                    + "  local untilMs = tonumber(ARGV[2]) + tonumber(ARGV[3]) "
                    + "  redis.call('HSET', KEYS[1], 'state', 'OPEN', 'open_until', untilMs, 'failures', failures) "
                    + "  redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[4])) "
                    + "  return {1, failures, untilMs} "
                    + "end "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[5])) "
                    + "return {0, failures, 0}",
            List.class);

    private static final RedisScript<Long> OPEN = new DefaultRedisScript<>(
            "redis.call('HSET', KEYS[1], 'state', 'OPEN', 'open_until', ARGV[1], 'failures', ARGV[2]) "
                    + "redis.call('PEXPIRE', KEYS[1], ARGV[3]) "
                    + "return 1",
            Long.class);

    private static final RedisScript<Long> TRY_PROBE = new DefaultRedisScript<>(
            "local value = redis.call('HMGET', KEYS[1], 'state', 'open_until') "
                    + "if not value[1] then return 0 end "
                    + "if value[1] ~= 'OPEN' then return 0 end "
                    + "if tonumber(ARGV[1]) < tonumber(value[2]) then return 0 end "
                    + "redis.call('HSET', KEYS[1], 'state', 'HALF_OPEN') "
                    + "redis.call('PEXPIRE', KEYS[1], tonumber(ARGV[2])) "
                    + "return 1",
            Long.class);

    /** Failures kept for an hour when the circuit did not open, so a slow drift still counts. */
    private static final long FAILURE_TTL_MS = 3_600_000;

    private final StringRedisTemplate redis;
    private final String prefix;

    public ValkeyCircuitBreakerStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    private String key(Long providerId) {
        return prefix + ":breaker:" + providerId;
    }

    @Override
    public Snapshot load(Long providerId) {
        List<?> values = redis.opsForHash().multiGet(key(providerId),
                List.of("state", "open_until", "failures"));
        if (values == null || values.get(0) == null) {
            return Snapshot.closed();
        }
        return new Snapshot(State.valueOf(String.valueOf(values.get(0))),
                values.get(2) == null ? 0 : Integer.parseInt(String.valueOf(values.get(2))),
                values.get(1) == null ? 0 : Long.parseLong(String.valueOf(values.get(1))));
    }

    @Override
    public Snapshot recordFailure(Long providerId, int threshold, long nowMs, long openDurationMs) {
        long openFor = Math.max(1, openDurationMs);
        List<?> result = redis.execute(RECORD_FAILURE, List.of(key(providerId)),
                String.valueOf(Math.max(1, threshold)),
                String.valueOf(nowMs),
                String.valueOf(openFor),
                String.valueOf(openFor + 5_000),
                String.valueOf(FAILURE_TTL_MS));
        if (result == null || result.size() < 3 || !"1".equals(String.valueOf(result.get(0)))) {
            return null;
        }
        return new Snapshot(State.OPEN,
                ((Number) result.get(1)).intValue(),
                ((Number) result.get(2)).longValue());
    }

    @Override
    public void open(Long providerId, int failures, long openUntilMs, long ttlMs) {
        redis.execute(OPEN, List.of(key(providerId)),
                String.valueOf(openUntilMs),
                String.valueOf(failures),
                String.valueOf(Math.max(1, ttlMs)));
    }

    @Override
    public boolean tryProbe(Long providerId, long nowMs, long probeLeaseMs) {
        Long claimed = redis.execute(TRY_PROBE, List.of(key(providerId)),
                String.valueOf(nowMs),
                String.valueOf(Math.max(1, probeLeaseMs)));
        return claimed != null && claimed == 1L;
    }

    @Override
    public void close(Long providerId) {
        redis.delete(key(providerId));
    }

    @Override
    public Map<Long, Snapshot> snapshot() {
        Map<Long, Snapshot> out = new LinkedHashMap<>();
        ScanOptions options = ScanOptions.scanOptions()
                .match(prefix + ":breaker:*")
                .count(100)
                .build();
        try (Cursor<String> cursor = redis.scan(options)) {
            while (cursor.hasNext()) {
                String key = cursor.next();
                Long providerId = parseProviderId(key);
                if (providerId != null) {
                    out.put(providerId, load(providerId));
                }
            }
        }
        return out;
    }

    private Long parseProviderId(String key) {
        try {
            return Long.valueOf(key.substring(key.lastIndexOf(':') + 1));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
