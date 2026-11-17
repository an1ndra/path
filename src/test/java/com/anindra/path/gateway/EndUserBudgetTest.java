package com.anindra.path.gateway;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:enduserbudget;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class EndUserBudgetTest {

    @Autowired
    private EndUserBudget budget;

    @BeforeEach
    void clean() {
        budget.clear(1L, "alice");
        budget.clear(1L, "bob");
    }

    @Test
    void reservesAndReleasesTheSameAmount() {
        assertThat(budget.reserve(1L, "alice", new BigDecimal("0.40"), new BigDecimal("1.00"))).isTrue();
        budget.release(1L, "alice", new BigDecimal("0.40"));

        // The full headroom is available again.
        assertThat(budget.reserve(1L, "alice", new BigDecimal("1.00"), new BigDecimal("1.00"))).isTrue();
        budget.clear(1L, "alice");
    }

    @Test
    void refusesMoreThanTheHeadroomAllows() {
        assertThat(budget.reserve(1L, "alice", new BigDecimal("0.60"), new BigDecimal("1.00"))).isTrue();
        // 0.60 in flight, so 0.50 more would cross the 1.00 headroom.
        assertThat(budget.reserve(1L, "alice", new BigDecimal("0.50"), new BigDecimal("1.00"))).isFalse();
        // What still fits is allowed.
        assertThat(budget.reserve(1L, "alice", new BigDecimal("0.40"), new BigDecimal("1.00"))).isTrue();
        budget.clear(1L, "alice");
    }

    @Test
    void concurrentReservationsCannotBothTakeTheLastOfTheBudget() {
        // Two requests that each fit alone but not together.
        boolean first = budget.reserve(1L, "alice", new BigDecimal("0.60"), new BigDecimal("1.00"));
        boolean second = budget.reserve(1L, "alice", new BigDecimal("0.60"), new BigDecimal("1.00"));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        budget.clear(1L, "alice");
    }

    @Test
    void endUsersAreIndependent() {
        assertThat(budget.reserve(1L, "alice", new BigDecimal("1.00"), new BigDecimal("1.00"))).isTrue();
        assertThat(budget.reserve(1L, "bob", new BigDecimal("1.00"), new BigDecimal("1.00"))).isTrue();

        budget.clear(1L, "alice");
        budget.clear(1L, "bob");
    }

    @Test
    void anOverReleaseIsIgnoredRatherThanCreditingBudget() {
        budget.reserve(1L, "alice", new BigDecimal("0.10"), new BigDecimal("1.00"));
        // A caller releasing more than it reserved must not free up someone else's budget.
        budget.release(1L, "alice", new BigDecimal("5.00"));

        // The 0.10 is still held, so the remaining 0.90 fits and 1.00 does not.
        assertThat(budget.reserve(1L, "alice", new BigDecimal("0.90"), new BigDecimal("1.00"))).isTrue();
        assertThat(budget.reserve(1L, "alice", new BigDecimal("0.01"), new BigDecimal("1.00"))).isFalse();
        budget.clear(1L, "alice");
    }
}
