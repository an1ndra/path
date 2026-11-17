package com.anindra.path.admin;

import com.anindra.path.repo.AdminAuditRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:audittest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class AuditServiceTest {

    @Autowired
    private AuditService audit;

    @Autowired
    private AdminAuditRepository repository;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    @Test
    void recordsAndListsEntriesNewestFirst() {
        audit.record("key.create", "key:1", Map.of("name", "team-a"));
        audit.record("route.update", "route:4", Map.of("weight", 20));

        var entries = audit.list(null, 10);

        assertThat(entries).hasSize(2);
        assertThat(entries.get(0).get("action")).isEqualTo("route.update");
        assertThat(entries.get(1).get("target")).isEqualTo("key:1");
    }

    @Test
    void filtersByAction() {
        audit.record("key.create", "key:1", null);
        audit.record("route.create", "route:2", null);

        assertThat(audit.list("key.create", 10)).hasSize(1);
    }
}
