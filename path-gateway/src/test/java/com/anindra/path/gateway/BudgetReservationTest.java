package com.anindra.path.gateway;

import com.anindra.path.repo.ClientKeyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reservations are what stop two concurrent requests from each passing the balance check
 * and then both spending it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:reservationtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class BudgetReservationTest {

    @Autowired
    private ApiKeyService apiKeys;

    @Autowired
    private ClientKeyRepository keys;

    private Long keyId;

    @BeforeEach
    void setUp() {
        keys.deleteAll();
        keyId = apiKeys.create("reservation-test", new BigDecimal("1.00"), 100).key().getId();
    }

    @Test
    void aReservationReducesTheVisibleBalance() {
        var key = keys.findById(keyId).orElseThrow();
        assertThat(apiKeys.remaining(key)).isEqualByComparingTo("1.00");

        assertThat(apiKeys.reserve(keyId, new BigDecimal("0.40"))).isTrue();

        var after = keys.findById(keyId).orElseThrow();
        assertThat(after.getReservedUsd()).isEqualByComparingTo("0.40");
        assertThat(apiKeys.remaining(after)).isEqualByComparingTo("0.60");
    }

    @Test
    void concurrentReservationsCannotOvershootTheLimit() {
        // Two requests that would each fit alone, but not together.
        assertThat(apiKeys.reserve(keyId, new BigDecimal("0.60"))).isTrue();
        assertThat(apiKeys.reserve(keyId, new BigDecimal("0.60"))).isFalse();

        assertThat(keys.findById(keyId).orElseThrow().getReservedUsd())
                .isEqualByComparingTo("0.60");
    }

    @Test
    void releasingRestoresTheBalance() {
        assertThat(apiKeys.reserve(keyId, new BigDecimal("0.90"))).isTrue();
        apiKeys.release(keyId, new BigDecimal("0.90"));

        var key = keys.findById(keyId).orElseThrow();
        assertThat(key.getReservedUsd()).isEqualByComparingTo("0.00");
        assertThat(apiKeys.reserve(keyId, new BigDecimal("0.90"))).isTrue();
    }

    @Test
    void releasingMoreThanReservedNeverGoesNegative() {
        assertThat(apiKeys.reserve(keyId, new BigDecimal("0.10"))).isTrue();
        apiKeys.release(keyId, new BigDecimal("5.00"));

        assertThat(keys.findById(keyId).orElseThrow().getReservedUsd())
                .isEqualByComparingTo("0.00");
    }
}
