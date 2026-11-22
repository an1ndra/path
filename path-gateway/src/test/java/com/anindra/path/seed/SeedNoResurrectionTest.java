package com.anindra.path.seed;

import com.anindra.path.domain.Provider;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Deleting an example provider in the console must stick. The seeder used to recreate any
 * missing provider by slug on every boot, so the "dummy" catalogue came back after a
 * restart even when an operator had removed it.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:seedtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.admin-token=test-admin",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key",
        "gateway.seed-defaults=true"
})
@Transactional
class SeedNoResurrectionTest {

    @Autowired
    private DataSeeder seeder;

    @Autowired
    private ProviderRepository providers;

    @Autowired
    private ModelRouteRepository routes;

    @Test
    void aDeletedExampleProviderIsNotRecreatedOnTheNextBoot() {
        Provider openai = providers.findBySlug("openai").orElseThrow();

        routes.deleteByProviderId(openai.getId());
        providers.deleteById(openai.getId());

        seeder.run(null);

        assertThat(providers.findBySlug("openai")).isEmpty();
        assertThat(routes.findByProviderId(openai.getId())).isEmpty();
        // The other examples are untouched, and the catalogue is not duplicated.
        assertThat(providers.findBySlug("anthropic")).isPresent();
    }

    @Test
    void seedingIsIdempotentAcrossRuns() {
        long providerCount = providers.count();
        long routeCount = routes.count();

        seeder.run(null);

        assertThat(providers.count()).isEqualTo(providerCount);
        assertThat(routes.count()).isEqualTo(routeCount);
    }
}
