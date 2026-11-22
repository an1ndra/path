package com.anindra.path;

import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.repo.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V1's {@code providers_type_check} only allowed OPENAI and ANTHROPIC, so inserting a
 * GEMINI or VERTEX provider failed with a constraint violation even though the enum and
 * the admin API accepted both. V21 widens the constraint; this asserts every wire format
 * the code knows about can actually be stored.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:providertypes;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class ProviderTypeConstraintTest {

    @Autowired
    private ProviderRepository providers;

    @Test
    void everyWireFormatFitsTheCheckConstraint() {
        for (ProviderType type : ProviderType.values()) {
            String slug = "type-" + type.name().toLowerCase();
            providers.saveAndFlush(
                    new Provider(slug, type.name(), type, "https://example.test/v1"));
        }

        assertThat(providers.findAll())
                .extracting(Provider::getType)
                .containsExactlyInAnyOrder(ProviderType.values());
    }
}
