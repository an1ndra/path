package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.gateway.UpstreamClient;
import com.anindra.path.gateway.ProviderCredentials;
import com.anindra.path.repo.ModelRouteRepository;
import com.anindra.path.repo.ProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pulls each provider's model list and upserts routes. Prices come from a built-in
 * table for well-known ids; anything else is created at 0.00 and can be edited later.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ModelSyncService {

    private record Price(BigDecimal input, BigDecimal cached, BigDecimal output, BigDecimal write) {
    }

    private static final Map<String, Price> PRICES = new HashMap<>();

    static {
        price("gpt-4o-mini", "0.15", "0.075", "0.60");
        price("gpt-4o", "2.50", "1.25", "10.00");
        price("gpt-4.1", "2.00", "0.50", "8.00");
        price("gpt-4.1-mini", "0.40", "0.10", "1.60");
        price("gpt-4.1-nano", "0.10", "0.025", "0.40");
        price("o4-mini", "1.10", "0.275", "4.40");
        anthropicPrice("claude-3-5-haiku-latest", "0.80", "4.00");
        anthropicPrice("claude-3-5-haiku-20241022", "0.80", "4.00");
        anthropicPrice("claude-sonnet-4-5", "3.00", "15.00");
        anthropicPrice("claude-sonnet-4-20250514", "3.00", "15.00");
        anthropicPrice("claude-opus-4-1", "15.00", "75.00");
        price("llama-3.3-70b-versatile", "0.59", null, "0.79");
        price("llama-3.1-8b-instant", "0.05", null, "0.08");
        price("meta-llama/llama-4-scout-17b-16e-instruct", "0.11", null, "0.34");
        price("qwen/qwen3-32b", "0.29", null, "0.59");
        price("deepseek-r1-distill-llama-70b", "0.75", null, "0.99");
        price("mixtral-8x7b-32768", "0.24", null, "0.24");
        price("gemma2-9b-it", "0.20", null, "0.20");
    }

    private static void price(String model, String input, String cached, String output) {
        PRICES.put(model, new Price(
                new BigDecimal(input),
                cached == null ? null : new BigDecimal(cached),
                new BigDecimal(output),
                null));
    }

    /** Anthropic prices cache reads at 0.1x and cache writes at 1.25x the input rate. */
    private static void anthropicPrice(String model, String input, String output) {
        BigDecimal in = new BigDecimal(input);
        BigDecimal cached = in.multiply(new BigDecimal("0.1")).setScale(6, java.math.RoundingMode.HALF_UP);
        BigDecimal write = in.multiply(new BigDecimal("1.25")).setScale(6, java.math.RoundingMode.HALF_UP);
        PRICES.put(model, new Price(in, cached, new BigDecimal(output), write));
    }

    private static final List<String> NON_CHAT_MARKERS = List.of(
            "embedding", "embed", "tts", "whisper", "dall-e", "moderation", "transcribe",
            "rerank", "realtime", "image", "audio", "speech", "guard", "safety");

    private final ProviderRepository providers;
    private final ModelRouteRepository routes;
    private final UpstreamClient upstream;
    private final ProviderCredentials credentials;
    private final ObjectMapper mapper;

    @Transactional
    public Map<String, Object> sync() {
        int created = 0;
        int updated = 0;
        List<String> errors = new ArrayList<>();
        List<String> skippedProviders = new ArrayList<>();

        for (Provider provider : providers.findByEnabledTrueOrderBySlugAsc()) {
            String apiKey = credentials.apiKey(provider);
            if (apiKey == null) {
                skippedProviders.add(provider.getSlug() + ": no credential");
                continue;
            }
            List<String> modelIds;
            try {
                modelIds = fetchModelIds(provider, apiKey);
            } catch (Exception e) {
                log.warn("Model sync failed for {}: {}", provider.getSlug(), e.getMessage());
                errors.add(provider.getSlug() + ": " + e.getMessage());
                continue;
            }

            for (String upstreamId : modelIds) {
                if (isNonChat(upstreamId)) {
                    continue;
                }
                Price price = PRICES.get(upstreamId);
                var existing = routes.findByPublicModelIdAndProviderIdAndUpstreamModelId(
                        upstreamId, provider.getId(), upstreamId);
                if (existing.isPresent()) {
                    var route = existing.get();
                    if (price != null && (route.getInputPricePerMillion() == null
                            || route.getInputPricePerMillion().signum() == 0)) {
                        route.setInputPricePerMillion(price.input());
                        route.setOutputPricePerMillion(price.output());
                        route.setCachedInputPricePerMillion(price.cached());
                        route.setCacheWritePricePerMillion(price.write());
                        updated++;
                    }
                } else {
                    var route = new com.anindra.path.domain.ModelRoute(
                            upstreamId, provider.getId(), upstreamId,
                            price == null ? BigDecimal.ZERO : price.input(),
                            price == null ? BigDecimal.ZERO : price.output(),
                            100);
                    if (price != null) {
                        route.setCachedInputPricePerMillion(price.cached());
                        route.setCacheWritePricePerMillion(price.write());
                    } else {
                        route.setDescription("Imported by /admin/models/sync; set a price before use.");
                    }
                    routes.save(route);
                    created++;
                }
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("created", created);
        out.put("updated", updated);
        out.put("skipped_providers", skippedProviders);
        out.put("errors", errors);
        return out;
    }

    private boolean isNonChat(String id) {
        String lower = id.toLowerCase();
        return NON_CHAT_MARKERS.stream().anyMatch(lower::contains);
    }

    private List<String> fetchModelIds(Provider provider, String apiKey) throws Exception {
        String url = provider.getBaseUrl() + "/models";
        Map<String, String> headers = new LinkedHashMap<>();
        if (provider.getType() == ProviderType.ANTHROPIC) {
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
        } else {
            headers.put("Authorization", "Bearer " + apiKey);
        }

        UpstreamClient.Response response = upstream.get(url, headers);
        if (response.status() >= 400) {
            throw new ApiException(response.status(), "upstream_error", "models_fetch_failed",
                    "HTTP " + response.status() + ": " + abbreviate(response.body()));
        }
        JsonNode root = mapper.readTree(response.body());
        List<String> ids = new ArrayList<>();
        JsonNode data = root.path("data");
        if (data.isArray()) {
            for (JsonNode node : data) {
                if (node.hasNonNull("id")) {
                    ids.add(node.get("id").asText());
                }
            }
        }
        return ids;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "…";
    }
}
