package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RouteResolverAdaptiveTest {

    private final ModelRouteRepository routes = mock(ModelRouteRepository.class);
    private final ProviderRepository providers = mock(ProviderRepository.class);
    private final ModelAliasService aliases = mock(ModelAliasService.class);
    private final com.anindra.path.admin.AliasService operatorAliases =
            mock(com.anindra.path.admin.AliasService.class);
    private final RoutingMetrics metrics = mock(RoutingMetrics.class);
    private final RouteResolver resolver =
            new RouteResolver(routes, providers, aliases, operatorAliases, metrics);
    private final ObjectMapper mapper = new ObjectMapper();

    private ModelRoute route(Long providerId, String price, int priority, String strategy, Integer slo) {
        ModelRoute route = new ModelRoute("chat", providerId, "upstream-" + providerId,
                new BigDecimal(price), new BigDecimal("1.00"), priority);
        route.setStrategy(strategy);
        route.setLatencySloMs(slo);
        return route;
    }

    private Provider provider(Long id, String slug) {
        Provider provider = new Provider(slug, slug, ProviderType.OPENAI, "http://localhost");
        provider.setId(id);
        return provider;
    }

    private ChatRequestSpec spec() throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(
                "{\"model\":\"chat\",\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"));
    }

    private void stub(ModelRoute cheap, ModelRoute dear, Provider cheapProvider,
                      Provider dearProvider, long cheapLatency, long dearLatency) {
        when(routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc("chat"))
                .thenReturn(List.of(cheap, dear));
        when(providers.findById(1L)).thenReturn(Optional.of(cheapProvider));
        when(providers.findById(2L)).thenReturn(Optional.of(dearProvider));
        when(aliases.isAlias(any())).thenReturn(false);
        when(operatorAliases.chainFor(any())).thenReturn(List.of());
        when(metrics.latencyMs("cheap", "chat")).thenReturn((double) cheapLatency);
        when(metrics.latencyMs("dear", "chat")).thenReturn((double) dearLatency);
    }

    @Test
    void priorityLeavesTheDeclaredOrderAlone() throws Exception {
        Provider cheapProvider = provider(1L, "cheap");
        Provider dearProvider = provider(2L, "dear");
        stub(route(1L, "0.10", 10, "priority", null), route(2L, "0.50", 20, "priority", null),
                cheapProvider, dearProvider, 900, 100);

        assertThat(resolver.resolve(spec(), 1L))
                .extracting(RouteResolver.ResolvedRoute::providerSlug)
                .containsExactly("cheap", "dear");
    }

    @Test
    void fastestMovesTheLowerEwmaFirst() throws Exception {
        stub(route(1L, "0.10", 10, "fastest", null), route(2L, "0.50", 20, "fastest", null),
                provider(1L, "cheap"), provider(2L, "dear"), 900, 100);

        assertThat(resolver.resolve(spec(), 1L))
                .extracting(RouteResolver.ResolvedRoute::providerSlug)
                .containsExactly("dear", "cheap");
    }

    @Test
    void cheapestViableDemotesASlowRouteButKeepsItAsFallback() throws Exception {
        stub(route(1L, "0.10", 10, "cheapest-viable", 200), route(2L, "0.50", 20, "cheapest-viable", 200),
                provider(1L, "cheap"), provider(2L, "dear"), 900, 100);

        assertThat(resolver.resolve(spec(), 1L))
                .extracting(RouteResolver.ResolvedRoute::providerSlug)
                .containsExactly("dear", "cheap");
    }

    @Test
    void cheapestViableKeepsTheCheapRouteWhenItIsFastEnough() throws Exception {
        stub(route(1L, "0.10", 10, "cheapest-viable", 200), route(2L, "0.50", 20, "cheapest-viable", 200),
                provider(1L, "cheap"), provider(2L, "dear"), 90, 100);

        assertThat(resolver.resolve(spec(), 1L))
                .extracting(RouteResolver.ResolvedRoute::providerSlug)
                .containsExactly("cheap", "dear");
    }

    @Test
    void anUnknownLatencyCountsAsViable() throws Exception {
        Provider cheapProvider = provider(1L, "cheap");
        Provider dearProvider = provider(2L, "dear");
        ModelRoute cheap = route(1L, "0.10", 10, "cheapest-viable", 200);
        ModelRoute dear = route(2L, "0.50", 20, "cheapest-viable", 200);
        stub(cheap, dear, cheapProvider, dearProvider, 0, 0);
        when(metrics.latencyMs("cheap", "chat")).thenReturn(Double.NaN);
        when(metrics.latencyMs(eq("dear"), eq("chat"))).thenReturn(100.0);

        assertThat(resolver.resolve(spec(), 1L))
                .extracting(RouteResolver.ResolvedRoute::providerSlug)
                .containsExactly("cheap", "dear");
    }
}
