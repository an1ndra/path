package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ModelAlias;
import com.anindra.path.repo.ModelAliasRepository;
import com.anindra.path.repo.ModelRouteRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Operator-defined aliases: a name a product can depend on, pointing at an ordered chain
 * of models. Kept separate from {@link com.anindra.path.gateway.ModelAliasService}, whose
 * four capability aliases are recomputed from the route table and can therefore move.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AliasService {

    /** Names that would collide with the computed capability aliases. */
    private static final Set<String> COMPUTED = Set.of("cheap", "fast", "long-context", "vision");

    private static final int MAX_HOPS = 10;

    private final ModelAliasRepository aliases;
    private final ModelRouteRepository routes;

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (ModelAlias alias : aliases.findAllByOrderByAliasAsc()) {
            out.add(view(alias));
        }
        return out;
    }

    @Transactional
    public Map<String, Object> create(String alias, List<String> models, String description) {
        String name = normalizeName(alias);
        if (aliases.findByAlias(name).isPresent()) {
            throw new ApiException(409, "invalid_request_error", "alias_exists",
                    "An alias named '" + name + "' already exists.");
        }
        List<String> chain = validateChain(name, models);
        if (COMPUTED.contains(name)) {
            // Allowed, because an operator may want a stable version of a computed alias,
            // but it shadows globally and that must be visible.
            log.warn("Alias '{}' shadows the computed capability alias of the same name", name);
        }
        ModelAlias created = aliases.save(new ModelAlias(name, chain,
                description == null || description.isBlank() ? null : description.trim()));
        return view(created);
    }

    @Transactional
    public void delete(String alias) {
        ModelAlias existing = aliases.findByAlias(normalizeName(alias))
                .orElseThrow(() -> ApiException.notFound(
                        "No alias named '" + alias + "'.", "alias_not_found"));
        aliases.delete(existing);
    }

    /** @return the alias chain when the name is an operator-defined alias. */
    public List<String> chainFor(String model) {
        return aliases.findByAlias(model).map(ModelAlias::modelList).orElse(List.of());
    }

    public boolean exists(String model) {
        return aliases.findByAlias(model).isPresent();
    }

    private static String normalizeName(String alias) {
        if (alias == null || alias.isBlank()) {
            throw ApiException.badRequest("'alias' is required.", "alias");
        }
        String name = alias.trim().toLowerCase();
        if (!name.matches("[a-z0-9][a-z0-9._-]{0,79}")) {
            throw ApiException.badRequest(
                    "'alias' must be 1-80 characters of a-z, 0-9, dot, dash or underscore.",
                    "alias");
        }
        return name;
    }

    /**
     * Rejects an alias that would not resolve: an unknown model, a reference to itself, or
     * a chain that passes through another alias and comes back. Failing at write time is
     * the whole point — a broken alias must not turn into a 500 on a customer request.
     */
    private List<String> validateChain(String name, List<String> models) {
        if (models == null || models.isEmpty()) {
            throw ApiException.badRequest("'models' must list at least one model id.", "models");
        }
        List<String> cleaned = models.stream()
                .filter(m -> m != null && !m.isBlank())
                .map(String::trim)
                .toList();
        if (cleaned.isEmpty()) {
            throw ApiException.badRequest("'models' must list at least one model id.", "models");
        }
        if (cleaned.size() > MAX_HOPS) {
            throw ApiException.badRequest(
                    "'models' accepts at most " + MAX_HOPS + " entries.", "models");
        }

        for (String model : cleaned) {
            if (model.equals(name)) {
                throw ApiException.badRequest("An alias cannot point at itself.", "models");
            }
            if (routes.findByPublicModelIdAndEnabledTrueOrderByPriorityAscIdAsc(model).isEmpty()
                    && aliases.findByAlias(model).isEmpty()) {
                throw ApiException.badRequest(
                        "'" + model + "' is not a model this gateway serves.", "models");
            }
        }
        assertNoCycle(name, cleaned, 0);
        return cleaned;
    }

    private void assertNoCycle(String name, List<String> chain, int depth) {
        if (depth > MAX_HOPS) {
            throw ApiException.badRequest(
                    "The alias chain is deeper than " + MAX_HOPS + " hops.", "models");
        }
        for (String model : chain) {
            List<String> nested = aliases.findByAlias(model)
                    .map(ModelAlias::modelList).orElse(List.of());
            if (nested.isEmpty()) {
                continue;
            }
            if (nested.contains(name)) {
                throw ApiException.badRequest(
                        "That chain loops back to '" + name + "' through '" + model + "'.", "models");
            }
            assertNoCycle(name, nested, depth + 1);
        }
    }

    private static Map<String, Object> view(ModelAlias alias) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", alias.getId());
        out.put("alias", alias.getAlias());
        out.put("models", alias.modelList());
        out.put("resolves_to", alias.modelList().isEmpty() ? null : alias.modelList().get(0));
        out.put("description", alias.getDescription());
        out.put("shadows_computed", COMPUTED.contains(alias.getAlias()));
        out.put("created_at", alias.getCreatedAt().toString());
        return out;
    }
}
