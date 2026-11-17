package com.anindra.path.config;

import com.anindra.path.gateway.InMemoryRateLimitStore;
import com.anindra.path.gateway.RateLimitStore;
import com.anindra.path.gateway.ValkeyRateLimitStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

@Configuration
public class RateLimitConfig {

    @Bean
    @ConditionalOnProperty(name = "gateway.rate-limit.backend", havingValue = "memory", matchIfMissing = true)
    public RateLimitStore inMemoryRateLimitStore() {
        return new InMemoryRateLimitStore();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.rate-limit.backend", havingValue = "valkey")
    public RateLimitStore valkeyRateLimitStore(StringRedisTemplate redis, GatewayProperties props) {
        return new ValkeyRateLimitStore(redis, props.getRateLimit().getPrefix());
    }
}
