package com.anindra.path.gateway;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * In-flight permits in Valkey, one sorted set per bucket.
 *
 * <p>Each member is a permit with its lease expiry as the score. Acquiring prunes expired
 * members, counts the rest and adds a new one only when the bucket is under its limit, all
 * inside one script so two replicas cannot both see {@code limit-1}. Releasing pops one
 * member: permits are fungible, so which one goes does not matter, only that the count drops
 * by one. A killed pod's permits simply age out of the set, which is the whole point of the
 * lease.
 */
public class ValkeyConcurrencyStore implements ConcurrencyStore {

    private static final RedisScript<Long> ACQUIRE = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
                    + "if redis.call('ZCARD', KEYS[1]) >= tonumber(ARGV[3]) then return 0 end "
                    + "redis.call('ZADD', KEYS[1], ARGV[2], ARGV[4]) "
                    + "redis.call('PEXPIREAT', KEYS[1], tonumber(ARGV[2]) + 1000) "
                    + "return 1",
            Long.class);

    private static final RedisScript<Long> RELEASE = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
                    + "local popped = redis.call('ZPOPMIN', KEYS[1]) "
                    + "if #popped > 0 then return 1 end "
                    + "return 0",
            Long.class);

    private static final RedisScript<Long> IN_FLIGHT = new DefaultRedisScript<>(
            "redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', ARGV[1]) "
                    + "return redis.call('ZCARD', KEYS[1])",
            Long.class);

    private final StringRedisTemplate redis;
    private final String prefix;
    private final long leaseMs;

    public ValkeyConcurrencyStore(StringRedisTemplate redis, String prefix, Duration lease) {
        this.redis = redis;
        this.prefix = prefix;
        this.leaseMs = Math.max(1, lease.toMillis());
    }

    private String key(String bucket) {
        return prefix + ":conc:" + bucket;
    }

    @Override
    public boolean tryAcquire(String bucket, int limit) {
        if (limit <= 0) {
            return true;
        }
        long now = System.currentTimeMillis();
        Long taken = redis.execute(ACQUIRE, List.of(key(bucket)),
                String.valueOf(now),
                String.valueOf(now + leaseMs),
                String.valueOf(limit),
                UUID.randomUUID().toString());
        return taken != null && taken == 1L;
    }

    @Override
    public void release(String bucket) {
        redis.execute(RELEASE, List.of(key(bucket)), String.valueOf(System.currentTimeMillis()));
    }

    @Override
    public int inFlight(String bucket) {
        Long count = redis.execute(IN_FLIGHT, List.of(key(bucket)),
                String.valueOf(System.currentTimeMillis()));
        return count == null ? 0 : count.intValue();
    }
}
