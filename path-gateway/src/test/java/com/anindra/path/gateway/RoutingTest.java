package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:routingtest;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.jpa.hibernate.ddl-auto=validate",
        "gateway.bootstrap-demo-key=false",
        "gateway.seed-defaults=true",
        "gateway.master-key-path=./target/test-master.key"
})
class RoutingTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Autowired
    private RouteResolver resolver;

    @Autowired
    private ModelAliasService aliases;

    @Autowired
    private com.anindra.path.repo.ModelRouteRepository routes;

    private ChatRequestSpec spec(String model) throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(
                "{\"model\":\"" + model + "\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
    }

    @Test
    void aliasesFollowTheLivePriceTable() {
        assertThat(aliases.resolve(ModelAliasService.CHEAP)).contains("llama-3.1-8b");
        assertThat(aliases.resolve(ModelAliasService.FAST)).contains("llama-3.1-8b");
        assertThat(aliases.resolve(ModelAliasService.LONG_CONTEXT)).contains("gpt-4.1-mini");
        assertThat(aliases.resolve(ModelAliasService.VISION)).isEmpty();
    }

    @Test
    void anAliasResolvesToARealRouteChain() throws Exception {
        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec("cheap"), 1L);

        assertThat(chain).isNotEmpty();
        assertThat(chain.get(0).route().getPublicModelId()).isEqualTo("llama-3.1-8b");
        assertThat(chain.get(0).providerSlug()).isEqualTo("groq");
    }

    @Test
    void weightedRoutingIsStablePerKeyAndSplitsAcrossKeys() throws Exception {
        // Weighting is opt-in: configure a 50/50 canary first.
        var group = routes.findByPublicModelIdOrderByPriorityAsc("auto-fast");
        group.forEach(route -> route.setWeight(50));
        routes.saveAll(group);
        ChatRequestSpec spec = spec("auto-fast");

        String first = resolver.resolve(spec, 42L).get(0).providerSlug();
        String second = resolver.resolve(spec, 42L).get(0).providerSlug();
        assertThat(first).isEqualTo(second);

        Set<String> providers = new HashSet<>();
        for (long key = 1; key <= 40; key++) {
            providers.add(resolver.resolve(spec, key).get(0).providerSlug());
        }
        assertThat(providers).contains("openai", "groq");
    }

    @Test
    void theRestOfTheChainStaysAsFallback() throws Exception {
        List<RouteResolver.ResolvedRoute> chain = resolver.resolve(spec("auto-fast"), 7L);

        assertThat(chain).hasSize(2);
        assertThat(chain.get(0).providerSlug()).isNotEqualTo(chain.get(1).providerSlug());
    }
}
