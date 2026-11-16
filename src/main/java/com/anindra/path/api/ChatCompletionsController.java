package com.anindra.path.api;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anindra.path.config.AuthFilter;
import com.anindra.path.domain.ClientKey;
import com.anindra.path.gateway.ApiKeyService;
import com.anindra.path.gateway.ChatRequestSpec;
import com.anindra.path.gateway.ChatService;
import com.anindra.path.gateway.ModelCatalogService;
import com.anindra.path.gateway.StreamService;
import com.anindra.path.gateway.UsageService;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** OpenAI-compatible public surface. */
@RestController
@RequestMapping("/v1")
@RequiredArgsConstructor
public class ChatCompletionsController {

    private final ChatService chatService;
    private final StreamService streamService;
    private final ModelCatalogService catalog;
    private final UsageService usageService;
    private final ApiKeyService apiKeys;

    @PostMapping("/chat/completions")
    public ResponseEntity<?> chatCompletions(
            @RequestBody ObjectNode body,
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key,
            HttpServletResponse response) throws IOException {

        if (key == null) {
            throw ApiException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer sk-gw-...'.", "missing_api_key");
        }

        ChatRequestSpec spec = ChatRequestSpec.parse(body);

        if (spec.stream()) {
            // Resolve and authorise before any bytes are written so errors keep their status.
            var chain = streamService.prepare(spec, key);
            streamService.stream(response, spec, key, chain);
            return null;
        }

        ChatService.Result result = chatService.complete(spec, key);
        return ResponseEntity.ok()
                .header("X-Gateway-Provider", result.providerSlug())
                .header("X-Gateway-Upstream-Model", result.upstreamModel())
                .header("X-Gateway-Cost-Usd", result.costUsd().toPlainString())
                .header("X-Gateway-Latency-Ms", String.valueOf(result.latencyMs()))
                .body(result.body());
    }

    @GetMapping("/models")
    public Map<String, Object> models() {
        return catalog.list();
    }

    @GetMapping({"/models/{*modelId}"})
    public Map<String, Object> model(@PathVariable String modelId) {
        String id = modelId.startsWith("/") ? modelId.substring(1) : modelId;
        return catalog.find(id).orElseThrow(() ->
                ApiException.notFound("The model '" + id + "' does not exist.", "model_not_found"));
    }

    @GetMapping("/usage")
    public Map<String, Object> usage(
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key,
            @RequestParam(defaultValue = "24h") String window) {
        requireKey(key);
        Duration parsed = Durations.cap(Durations.parse(window, Duration.ofHours(24)), Duration.ofDays(365));
        return usageService.keySummary(key, parsed);
    }

    @GetMapping("/credits")
    public Map<String, Object> credits(
            @RequestAttribute(name = AuthFilter.CLIENT_KEY_ATTR, required = false) ClientKey key) {
        requireKey(key);
        BigDecimal remaining = apiKeys.remaining(key);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("object", "credits");
        out.put("key_id", key.getId());
        out.put("key_name", key.getName());
        out.put("currency", "USD");
        out.put("credit_limit_usd", key.getCreditLimitUsd());
        out.put("used_usd", key.getUsedUsd());
        out.put("remaining_usd", remaining);
        out.put("unlimited", remaining == null);
        return out;
    }

    private static void requireKey(ClientKey key) {
        if (key == null) {
            throw ApiException.unauthorized(
                    "Missing API key. Send it as 'Authorization: Bearer sk-gw-...'.", "missing_api_key");
        }
    }
}
