package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Builds the OpenAI-shaped model catalogue from enabled routes. */
@Service
@RequiredArgsConstructor
public class ModelCatalogService {

    private static final long CATALOG_EPOCH = 1_700_000_000L;

    private final ModelRouteRepository routes;
    private final ProviderRepository providers;
    private final ModelAliasService aliases;

    public Map<String, Object> list() {
        Map<Long, Provider> byId = providers.findAll().stream()
                .collect(Collectors.toMap(Provider::getId, Function.identity()));

        Map<String, List<ModelRoute>> grouped = new LinkedHashMap<>();
        for (ModelRoute route : routes.findByEnabledTrueOrderByPublicModelIdAscPriorityAsc()) {
            Provider provider = byId.get(route.getProviderId());
            if (provider == null || !provider.isEnabled()) {
                continue;
            }
            grouped.computeIfAbsent(route.getPublicModelId(), k -> new ArrayList<>()).add(route);
        }

        List<Map<String, Object>> data = new ArrayList<>();
        grouped.forEach((modelId, modelRoutes) -> data.add(describe(modelId, modelRoutes, byId)));

        data.addAll(aliases.describeAll());

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "list");
        out.put("data", data);
        return out;
    }

    public Optional<Map<String, Object>> find(String modelId) {
        if (aliases.isAlias(modelId)) {
            return aliases.resolve(modelId).flatMap(this::find).or(() ->
                    Optional.of(resolveAliasEntry(modelId)));
        }
        List<ModelRoute> modelRoutes = routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(modelId);
        if (modelRoutes.isEmpty()) {
            return Optional.empty();
        }
        Map<Long, Provider> byId = providers.findAll().stream()
                .collect(Collectors.toMap(Provider::getId, Function.identity()));
        return Optional.of(describe(modelId, modelRoutes, byId));
    }

    private Map<String, Object> resolveAliasEntry(String alias) {
        Map<String, Object> entry = new LinkedHashMap<>();
        entry.put("id", alias);
        entry.put("object", "model");
        entry.put("owned_by", "path");
        entry.put("resolves_to", null);
        return entry;
    }

    private Map<String, Object> describe(String modelId, List<ModelRoute> modelRoutes,
                                         Map<Long, Provider> byId) {
        List<Map<String, Object>> routing = new ArrayList<>();
        List<String> providerSlugs = new ArrayList<>();
        BigDecimal cheapestInput = null;
        BigDecimal cheapestOutput = null;
        Integer context = null;
        String owner = null;

        for (ModelRoute route : modelRoutes) {
            Provider provider = byId.get(route.getProviderId());
            if (provider == null || !provider.isEnabled()) {
                continue;
            }
            if (owner == null) {
                owner = provider.getSlug();
            }
            if (!providerSlugs.contains(provider.getSlug())) {
                providerSlugs.add(provider.getSlug());
            }
            if (route.getContextLength() != null) {
                context = context == null ? route.getContextLength()
                        : Math.max(context, route.getContextLength());
            }
            if (route.getInputPricePerMillion() != null
                    && (cheapestInput == null || route.getInputPricePerMillion().compareTo(cheapestInput) < 0)) {
                cheapestInput = route.getInputPricePerMillion();
            }
            if (route.getOutputPricePerMillion() != null
                    && (cheapestOutput == null || route.getOutputPricePerMillion().compareTo(cheapestOutput) < 0)) {
                cheapestOutput = route.getOutputPricePerMillion();
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("provider", provider.getSlug());
            entry.put("upstream_model", route.getUpstreamModelId());
            entry.put("priority", route.getPriority());
            entry.put("input_price_per_million", route.getInputPricePerMillion());
            entry.put("output_price_per_million", route.getOutputPricePerMillion());
            routing.add(entry);
        }

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("id", modelId);
        model.put("object", "model");
        model.put("created", CATALOG_EPOCH);
        model.put("owned_by", owner == null ? "gateway" : owner);
        if (context != null) {
            model.put("context_length", context);
        }
        model.put("providers", providerSlugs);
        Map<String, Object> pricing = new LinkedHashMap<>();
        pricing.put("prompt_per_million", cheapestInput == null ? BigDecimal.ZERO : cheapestInput);
        pricing.put("completion_per_million", cheapestOutput == null ? BigDecimal.ZERO : cheapestOutput);
        model.put("pricing", pricing);
        model.put("routing", routing);
        return model;
    }
}
