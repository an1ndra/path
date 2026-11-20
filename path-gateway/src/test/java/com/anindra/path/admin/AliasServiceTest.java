package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.repo.ModelAliasRepository;
import com.anindra.path.repo.ModelRouteRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:aliastest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.master-key-path=./target/test-master.key"
})
class AliasServiceTest {

    @Autowired
    private AliasService aliases;

    @Autowired
    private ModelAliasRepository repository;

    @Autowired
    private ModelRouteRepository routes;

    @BeforeEach
    void clean() {
        repository.deleteAll();
    }

    private boolean routeExists(String model) {
        return !routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(model).isEmpty();
    }

    private String anySeededModel() {
        for (ModelRoute route : routes.findAll()) {
            if (route.isEnabled()) {
                return route.getPublicModelId();
            }
        }
        throw new IllegalStateException("no seeded routes");
    }

    @Test
    void createsAndResolvesAChainInOrder() {
        String model = anySeededModel();
        Map<String, Object> created = aliases.create("prod-chat", List.of(model), "stable chain");

        assertThat(created.get("alias")).isEqualTo("prod-chat");
        assertThat(created.get("resolves_to")).isEqualTo(model);
        assertThat(aliases.chainFor("prod-chat")).containsExactly(model);

        var listed = aliases.list();
        assertThat(listed).anySatisfy(row -> assertThat(row.get("alias")).isEqualTo("prod-chat"));
    }

    @Test
    void rejectsAnUnknownModelAtWriteTime() {
        assertThatThrownBy(() -> aliases.create("broken", List.of("no-such-model"), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("not a model this gateway serves");
    }

    @Test
    void rejectsSelfReference() {
        assertThatThrownBy(() -> aliases.create("loop", List.of("loop"), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("cannot point at itself");
    }

    @Test
    void allowsNestingOneAliasInsideAnother() {
        String model = anySeededModel();
        aliases.create("base-alias", List.of(model), null);

        Map<String, Object> nested = aliases.create("outer-alias", List.of("base-alias"), null);

        assertThat(nested.get("resolves_to")).isEqualTo("base-alias");
        assertThat(aliases.chainFor("outer-alias")).containsExactly("base-alias");
    }

    @Test
    void rejectsDuplicateNames() {
        String model = anySeededModel();
        aliases.create("dup", List.of(model), null);

        assertThatThrownBy(() -> aliases.create("dup", List.of(model), null))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "alias_exists");
    }

    @Test
    void flagsAnAliasThatShadowsAComputedOne() {
        String model = anySeededModel();
        Map<String, Object> created = aliases.create("cheap", List.of(model), "pinned cheapest");

        assertThat(created.get("shadows_computed")).isEqualTo(true);
    }

    @Test
    void deletesAndThenReportsMissing() {
        String model = anySeededModel();
        aliases.create("temp", List.of(model), null);
        aliases.delete("temp");

        assertThat(aliases.exists("temp")).isFalse();
        assertThatThrownBy(() -> aliases.delete("temp"))
                .isInstanceOf(ApiException.class)
                .hasFieldOrPropertyWithValue("code", "alias_not_found");
    }

    @Test
    void validatesTheName() {
        String model = anySeededModel();
        assertThatThrownBy(() -> aliases.create("Not Valid Name", List.of(model), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("a-z");
        assertThatThrownBy(() -> aliases.create("empty", List.of(), null))
                .isInstanceOf(ApiException.class)
                .hasMessageContaining("at least one model");
    }

    @Test
    void pricesAreIrrelevantToWhetherTheChainIsValid() {
        // Guards against a regression where the check used to require a price.
        String model = anySeededModel();
        ModelRoute route = routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(model).get(0);
        route.setInputPricePerMillion(BigDecimal.ZERO);
        routes.save(route);

        assertThat(routeExists(model)).isTrue();
        assertThat(aliases.create("free-model", List.of(model), null).get("resolves_to")).isEqualTo(model);
    }
}
