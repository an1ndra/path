package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.repo.ModelRouteRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Capability aliases resolved from the live route table, so clients can ask for
 * "cheap" or "long-context" instead of hardcoding a vendor model id.
 */
@Service
@RequiredArgsConstructor
public class ModelAliasService {

    public static final String CHEAP = "cheap";
    public static final String FAST = "fast";
    public static final String LONG_CONTEXT = "long-context";
    public static final String VISION = "vision";

    private final ModelRouteRepository routes;

    public boolean isAlias(String model) {
        return List.of(CHEAP, FAST, LONG_CONTEXT, VISION).contains(model);
    }

    /** Maps an alias to a concrete public model id, or empty when nothing matches. */
    public Optional<String> resolve(String alias) {
        List<ModelRoute> enabled = routes.findByEnabledTrueOrderByPublicModelIdAscPriorityAsc();
        if (enabled.isEmpty()) {
            return Optional.empty();
        }
        return switch (alias) {
            case CHEAP -> enabled.stream()
                    .filter(r -> r.getInputPricePerMillion() != null && r.getInputPricePerMillion().signum() >= 0)
                    .min(Comparator.comparing(ModelRoute::getInputPricePerMillion))
                    .map(ModelRoute::getPublicModelId);
            case FAST -> enabled.stream()
                    .filter(r -> r.getOutputPricePerMillion() != null)
                    .min(Comparator.comparing(ModelRoute::getOutputPricePerMillion))
                    .map(ModelRoute::getPublicModelId);
            case LONG_CONTEXT -> enabled.stream()
                    .filter(r -> r.getContextLength() != null)
                    .max(Comparator.comparing(ModelRoute::getContextLength))
                    .map(ModelRoute::getPublicModelId);
            case VISION -> enabled.stream()
                    .filter(r -> r.getDescription() != null
                            && r.getDescription().toLowerCase().contains("vision"))
                    .min(Comparator.comparing(ModelRoute::getPriority))
                    .map(ModelRoute::getPublicModelId);
            default -> Optional.empty();
        };
    }

    /** Public catalogue entries for the aliases, so clients can discover them. */
    public List<Map<String, Object>> describeAll() {
        return List.of(CHEAP, FAST, LONG_CONTEXT, VISION).stream()
                .map(alias -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("id", alias);
                    entry.put("object", "model");
                    entry.put("owned_by", "path");
                    entry.put("resolves_to", resolve(alias).orElse(null));
                    entry.put("description", "Capability alias resolved from the live route table");
                    return entry;
                })
                .toList();
    }

    static BigDecimal orZero(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
