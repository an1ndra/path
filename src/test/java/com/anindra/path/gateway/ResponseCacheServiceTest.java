package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.repo.ResponseCacheEntryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:cachetest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class ResponseCacheServiceTest {

    @Autowired
    private ResponseCacheService cache;

    @Autowired
    private ResponseCacheEntryRepository entries;

    @BeforeEach
    void clean() {
        entries.deleteAll();
    }

    private ModelRoute route(int ttlSeconds) {
        ModelRoute route = new ModelRoute("gpt-4o-mini", 1L, "gpt-4o-mini", null, null, 10);
        route.setCacheEnabled(true);
        route.setCacheTtlSeconds(ttlSeconds);
        return route;
    }

    @Test
    void storesAndReplaysACompletion() {
        String key = cache.key(1L, "gpt-4o-mini", "{\"m\":1}");
        assertThat(cache.lookup(key)).isEmpty();

        cache.store(key, 1L, "gpt-4o-mini", "openai", route(60), "application/json",
                "{\"choices\":[]}", new TokenUsage(10, 5, 0, 0, 0, 0, 0, false), new BigDecimal("0.0001"));

        var entry = cache.lookup(key).orElseThrow();
        assertThat(entry.body()).isEqualTo("{\"choices\":[]}");
        assertThat(entry.providerSlug()).isEqualTo("openai");
        assertThat(entry.costUsd()).isEqualByComparingTo("0.0001");
        assertThat(entry.totalTokens()).isEqualTo(15);
    }

    @Test
    void keysAreScopedToTheClient() {
        String tenantOne = cache.key(1L, "gpt-4o-mini", "{}");
        cache.store(tenantOne, 1L, "gpt-4o-mini", "openai", route(60), "application/json", "{}",
                TokenUsage.of(1, 1), BigDecimal.ZERO);

        assertThat(cache.lookup(tenantOne)).isPresent();
        assertThat(cache.lookup(cache.key(2L, "gpt-4o-mini", "{}"))).isEmpty();
    }

    @Test
    void expiredEntriesAreNotServed() {
        String key = cache.key(1L, "gpt-4o-mini", "{\"m\":2}");
        cache.store(key, 1L, "gpt-4o-mini", "openai", route(60), "application/json", "{}",
                TokenUsage.of(1, 1), BigDecimal.ZERO);

        var record = entries.findByCacheKey(key).orElseThrow();
        record.setExpiresAt(Instant.now().minusSeconds(5));
        entries.save(record);

        assertThat(cache.lookup(key)).isEmpty();
        assertThat(entries.findByCacheKey(key)).isEmpty();
    }

    @Test
    void oversizedBodiesAreNotStored() {
        String key = cache.key(1L, "gpt-4o-mini", "{\"m\":3}");
        String big = "x".repeat(3_000_000);
        cache.store(key, 1L, "gpt-4o-mini", "openai", route(60), "application/json", big,
                TokenUsage.of(1, 1), BigDecimal.ZERO);

        assertThat(cache.lookup(key)).isEmpty();
    }
}
