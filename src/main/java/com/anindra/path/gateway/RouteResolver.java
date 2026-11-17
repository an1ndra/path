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
    private final ModelAliasService aliases;

    public List<ResolvedRoute> resolve(ChatRequestSpec spec) {
        return resolve(spec, null);
    }

    /** @param keyId used for stable canary assignment; null means no weighting. */
    public List<ResolvedRoute> resolve(ChatRequestSpec spec, Long keyId) {
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
            String target = aliases.isAlias(model) ? aliases.resolve(model).orElse(model) : model;
            List<ResolvedRoute> group = new ArrayList<>();
            for (ModelRoute route : routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(target)) {
                Provider provider = providers.findById(route.getProviderId()).orElse(null);
                if (provider == null || !provider.isEnabled()) {
                    continue;
                }
                String key = route.getProviderId() + "|" + route.getUpstreamModelId();
                if (!seen.add(key)) {
                    continue;
                }
                group.add(new ResolvedRoute(route, provider));
            }
            chain.addAll(weighted(group, keyId, target));
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

    /** Stable per-key weighted shuffle: the chosen route leads, the rest stay as fallback. */
    private static List<ResolvedRoute> weighted(List<ResolvedRoute> group, Long keyId, String model) {
        if (group.size() < 2 || keyId == null) {
            return group;
        }
        // Weighting is opt-in: a chain of default-weight routes keeps priority order,
        // otherwise fallback would become a lottery.
        boolean configured = group.stream().anyMatch(r -> r.route().getWeight() != 100);
        if (!configured) {
            return group;
        }
        int total = group.stream().mapToInt(r -> Math.max(0, r.route().getWeight())).sum();
        if (total <= 0) {
            return group;
        }
        int point = (int) Math.floorMod(keyId * 131L + model.hashCode(), (long) total);
        int cursor = 0;
        int chosen = 0;
        for (int i = 0; i < group.size(); i++) {
            cursor += Math.max(0, group.get(i).route().getWeight());
            if (point < cursor) {
                chosen = i;
                break;
            }
        }
        List<ResolvedRoute> ordered = new ArrayList<>(group.size());
        ordered.add(group.get(chosen));
        for (int i = 0; i < group.size(); i++) {
            if (i != chosen) {
                ordered.add(group.get(i));
            }
        }
        return ordered;
    }
}
