package com.anindra.path.admin;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.anindra.path.gateway.ProviderCircuitBreaker;
import com.anindra.path.gateway.ProviderCredentials;
import com.anindra.path.gateway.UpstreamClient;
import com.anindra.path.repo.ProviderRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** On-demand reachability check for a provider, plus a fleet-wide summary. */
@Service
@RequiredArgsConstructor
public class ProviderProbeService {

    private final ProviderRepository providers;
    private final ProviderCredentials credentials;
    private final ProviderCircuitBreaker breaker;
    private final UpstreamClient upstream;
    private final ObjectMapper mapper;

    public Map<String, Object> probe(Long providerId) {
        Provider provider = providers.findById(providerId)
                .orElseThrow(() -> ApiException.notFound("No provider with id " + providerId, "provider_not_found"));
        String apiKey = credentials.apiKey(provider);

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("provider", provider.getSlug());
        out.put("circuit_state", breaker.state(providerId).name().toLowerCase());
        if (apiKey == null) {
            out.put("ok", false);
            out.put("error", "no credential configured");
            return out;
        }

        Map<String, String> headers = new LinkedHashMap<>();
        if (provider.getType() == ProviderType.ANTHROPIC) {
            headers.put("x-api-key", apiKey);
            headers.put("anthropic-version", "2023-06-01");
        } else {
            headers.putAll(com.anindra.path.gateway.ProviderUrls.headers(provider, apiKey, false));
        }

        long start = System.nanoTime();
        try {
            UpstreamClient.Response response = upstream.get(
                    com.anindra.path.gateway.ProviderUrls.models(provider), headers);
            long latency = (System.nanoTime() - start) / 1_000_000;
            out.put("ok", response.ok());
            out.put("status", response.status());
            out.put("latency_ms", latency);
            if (response.ok()) {
                JsonNode data = mapper.readTree(response.body()).path("data");
                out.put("model_count", data.isArray() ? data.size() : 0);
            } else {
                out.put("error", abbreviate(response.body()));
            }
        } catch (Exception e) {
            out.put("ok", false);
            out.put("latency_ms", (System.nanoTime() - start) / 1_000_000);
            out.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        return out;
    }

    public List<Map<String, Object>> summary() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Provider provider : providers.findAllByOrderBySlugAsc()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", provider.getId());
            row.put("provider", provider.getSlug());
            row.put("enabled", provider.isEnabled());
            row.put("credential_configured", credentials.apiKey(provider) != null);
            row.put("circuit_state", breaker.state(provider.getId()).name().toLowerCase());
            row.put("retry_after_seconds", breaker.retryAfterSeconds(provider.getId()));
            out.add(row);
        }
        return out;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "…";
    }
}
