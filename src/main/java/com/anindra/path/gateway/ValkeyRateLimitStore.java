package com.anindra.path.gateway;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.util.List;

/**
 * Fixed one-minute window in Valkey, shared by every instance.
 *
 * Valkey speaks the same protocol as Redis, so Spring Data Redis and Lettuce work
 * unchanged; only the host/port differ.
 */
public class ValkeyRateLimitStore implements RateLimitStore {

    private static final RedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>(
            "local current = redis.call('INCR', KEYS[1]) "
                    + "if current == 1 then redis.call('EXPIRE', KEYS[1], 60) end "
                    + "return current",
            Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;

    public ValkeyRateLimitStore(StringRedisTemplate redis, String prefix) {
        this.redis = redis;
        this.prefix = prefix;
    }

    @Override
    public int acquire(String bucket, int rpmLimit) {
        if (rpmLimit <= 0) {
            return 0;
        }
        String redisKey = prefix + ":rpm:" + bucket;
        Long count = redis.execute(INCR_WITH_TTL, List.of(redisKey));
        if (count == null || count <= rpmLimit) {
            return 0;
        }
        Long ttl = redis.getExpire(redisKey);
        return (int) Math.max(1, ttl == null || ttl <= 0 ? 60 : ttl);
    }
}
