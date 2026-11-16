package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Turns a public model id (plus optional fallback list and provider preference) into an ordered chain. */
@Service
@RequiredArgsConstructor
public class RouteResolver {

    public record ResolvedRoute(ModelRoute route, Provider provider) {
        public String providerSlug() {
            return provider.getSlug();
        }
    }

    private final ModelRouteRepository routes;
    private final ProviderRepository providers;

    public List<ResolvedRoute> resolve(ChatRequestSpec spec) {
        List<String> wantedModels = new ArrayList<>();
        if (!spec.fallbackModels().isEmpty()) {
            wantedModels.addAll(spec.fallbackModels());
        }
        if (!wantedModels.contains(spec.publicModel())) {
            wantedModels.add(0, spec.publicModel());
        }

        List<ResolvedRoute> chain = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();

        for (String model : wantedModels) {
            for (ModelRoute route : routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(model)) {
                Provider provider = providers.findById(route.getProviderId()).orElse(null);
                if (provider == null || !provider.isEnabled()) {
                    continue;
                }
                String key = route.getProviderId() + "|" + route.getUpstreamModelId();
                if (!seen.add(key)) {
                    continue;
                }
                chain.add(new ResolvedRoute(route, provider));
            }
        }

        if (!spec.providerOrder().isEmpty()) {
            chain.removeIf(r -> !spec.providerOrder().contains(r.providerSlug()));
            chain.sort((a, b) -> {
                int ia = spec.providerOrder().indexOf(a.providerSlug());
                int ib = spec.providerOrder().indexOf(b.providerSlug());
                return Integer.compare(ia, ib);
            });
        }

        if (chain.isEmpty()) {
            throw ApiException.notFound(
                    "The model '" + spec.publicModel() + "' does not exist or has no enabled route. "
                            + "Call GET /v1/models for the catalogue.", "model_not_found");
        }
        if (!spec.allowFallbacks() && chain.size() > 1) {
            chain = chain.subList(0, 1);
        }
        return chain;
    }
}
