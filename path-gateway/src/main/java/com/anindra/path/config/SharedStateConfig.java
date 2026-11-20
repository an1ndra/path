package com.anindra.path.config;

import com.anindra.path.gateway.CircuitBreakerStore;
import com.anindra.path.gateway.ConcurrencyStore;
import com.anindra.path.gateway.InMemoryCircuitBreakerStore;
import com.anindra.path.gateway.InMemoryConcurrencyStore;
import com.anindra.path.gateway.ValkeyCircuitBreakerStore;
import com.anindra.path.gateway.ValkeyConcurrencyStore;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Chooses the backend for circuit-breaker and concurrency state.
 *
 * <p>Memory is the default, deliberately: putting Valkey on the hot path must be an
 * operator's explicit choice. Setting {@code GATEWAY_SHARED_STATE_BACKEND=valkey} reuses the
 * existing Redis connection and key prefix conventions used by the rate limiter.
 */
@Configuration
public class SharedStateConfig {

    @Bean
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "memory",
            matchIfMissing = true)
    public CircuitBreakerStore inMemoryCircuitBreakerStore() {
        return new InMemoryCircuitBreakerStore();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "valkey")
    public CircuitBreakerStore valkeyCircuitBreakerStore(StringRedisTemplate redis,
                                                         GatewayProperties props) {
        return new ValkeyCircuitBreakerStore(redis, props.getSharedState().getPrefix());
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "memory",
            matchIfMissing = true)
    public ConcurrencyStore inMemoryConcurrencyStore() {
        return new InMemoryConcurrencyStore();
    }

    @Bean
    @ConditionalOnProperty(name = "gateway.shared-state.backend", havingValue = "valkey")
    public ConcurrencyStore valkeyConcurrencyStore(StringRedisTemplate redis,
                                                   GatewayProperties props) {
        return new ValkeyConcurrencyStore(redis, props.getSharedState().getPrefix(),
                props.getSharedState().getConcurrencyLease());
    }
}
