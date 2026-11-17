package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.repo.IdempotencyRecordRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:idempotencytest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class IdempotencyServiceTest {

    @Autowired
    private IdempotencyService service;

    @Autowired
    private IdempotencyRecordRepository records;

    @BeforeEach
    void clean() {
        records.deleteAll();
    }

    @Test
    void replaysACompletedResponse() {
        assertThat(service.begin(42L, "idem-1", "hash-a")).isEmpty();

        service.complete(42L, "idem-1", 200, "application/json", "{\"ok\":true}");

        IdempotencyService.Replay replay = service.begin(42L, "idem-1", "hash-a").orElseThrow();
        assertThat(replay.httpStatus()).isEqualTo(200);
        assertThat(replay.contentType()).isEqualTo("application/json");
        assertThat(replay.body()).isEqualTo("{\"ok\":true}");
    }

    @Test
    void rejectsTheSameKeyWithADifferentBody() {
        service.begin(43L, "idem-2", "hash-a");
        service.complete(43L, "idem-2", 200, "application/json", "{}");

        assertThatThrownBy(() -> service.begin(43L, "idem-2", "hash-b"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "idempotency_conflict");
    }

    @Test
    void rejectsAConcurrentDuplicate() {
        assertThat(service.begin(44L, "idem-3", "hash-a")).isEmpty();

        assertThatThrownBy(() -> service.begin(44L, "idem-3", "hash-a"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "idempotency_in_progress");
    }

    @Test
    void abandonedClaimCanBeReused() {
        assertThat(service.begin(45L, "idem-4", "hash-a")).isEmpty();
        service.abandon(45L, "idem-4");

        assertThat(service.begin(45L, "idem-4", "hash-a")).isEmpty();
    }

    @Test
    void stalePendingClaimsAreReclaimed() {
        assertThat(service.begin(50L, "idem-stale", "hash-a")).isEmpty();

        var record = records.findAll().get(0);
        record.setCreatedAt(java.time.Instant.now().minus(java.time.Duration.ofMinutes(10)));
        records.save(record);

        // The instance that claimed this key died; a retry must not be blocked for 24h.
        assertThat(service.begin(50L, "idem-stale", "hash-a")).isEmpty();
    }

    @Test
    void keysAreScopedToTheClientKey() {
        service.begin(46L, "shared-key", "hash-a");
        service.complete(46L, "shared-key", 200, "application/json", "{\"tenant\":1}");

        // A different client key with the same header value gets its own claim.
        assertThat(service.begin(47L, "shared-key", "hash-a")).isEmpty();
    }

    @Test
    void storedKeyIsHashedNotPlaintext() {
        service.begin(48L, "plain-text-key", "hash-a");
        var stored = records.findAll().get(0);
        assertThat(stored.getIdempotencyKey()).isNotEqualTo("plain-text-key");
        assertThat(stored.getIdempotencyKey()).hasSize(64);
    }
}
