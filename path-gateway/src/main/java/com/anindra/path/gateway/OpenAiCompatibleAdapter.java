package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * OpenAI chat-completions wire format. Used for OpenAI itself and for every
 * OpenAI-compatible endpoint (Groq, Together, vLLM, LM Studio, ...): the request is
 * forwarded with only the model id swapped.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpenAiCompatibleAdapter implements ProviderAdapter {

    private final ObjectMapper mapper;

    @Override
    public ProviderType type() {
        return ProviderType.OPENAI;
    }

    @Override
    public UpstreamRequest build(ChatRequestSpec spec, ModelRoute route, Provider provider,
                                 String apiKey, boolean stream) {
        ObjectNode body = spec.forwardableBody();
        body.put("model", route.getUpstreamModelId());
        body.put("stream", stream);
        if (stream) {
            ObjectNode streamOptions = body.get("stream_options") instanceof ObjectNode existing
                    ? existing : body.putObject("stream_options");
            streamOptions.put("include_usage", true);
        }

        Map<String, String> headers = ProviderUrls.headers(provider, apiKey, stream);
        if (isOpenCode(provider)) {
            headers.put("x-opencode-session", sessionId(route.getUpstreamModelId(), body));
        }
        return new UpstreamRequest(ProviderUrls.chat(provider, route.getUpstreamModelId()),
                headers, body.toString());
    }

    /**
     * OpenCode Go (and its OpenAI-compatible endpoint) rejects a request without
     * {@code x-opencode-session}: "Request is missing x-opencode-session and cannot be
     * routed efficiently". The header is meant to be stable per conversation, so it is
     * derived from the model and the first user message: the same conversation keeps the
     * same id across retries and fallbacks, a new conversation gets a new one.
     */
    private boolean isOpenCode(Provider provider) {
        return provider.getSlug() != null && provider.getSlug().startsWith("opencode");
    }

    private String sessionId(String model, ObjectNode body) {
        StringBuilder seed = new StringBuilder(model).append('\n');
        JsonNode messages = body.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                if ("user".equals(message.path("role").asText())) {
                    seed.append(message.path("content"));
                    break;
                }
            }
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(seed.toString().getBytes(StandardCharsets.UTF_8));
            return "path-" + HexFormat.of().formatHex(digest).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            return "path-gateway";
        }
    }

    @Override
    public JsonNode parseFullResponse(JsonNode upstream, ChatRequestSpec spec, ModelRoute route) {
        ObjectNode copy = upstream.deepCopy();
        copy.put("model", spec.publicModel());
        if (!copy.hasNonNull("id")) {
            copy.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
        }
        if (!copy.hasNonNull("object")) {
            copy.put("object", "chat.completion");
        }
        if (!copy.hasNonNull("created")) {
            copy.put("created", Instant.now().getEpochSecond());
        }
        return copy;
    }

    @Override
    public StreamTranslator newStreamTranslator(ChatRequestSpec spec, ModelRoute route) {
        return new Translator(spec, mapper);
    }

    @Override
    public TokenUsage parseUsage(JsonNode upstream) {
        return fromUsageNode(upstream.path("usage"));
    }

    /** Shared by the non-streaming path and the final stream chunk. */
    static TokenUsage fromUsageNode(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.EMPTY;
        }
        Integer prompt = intOrNull(usage.get("prompt_tokens"));
        Integer completion = intOrNull(usage.get("completion_tokens"));
        if (prompt == null && completion == null) {
            return TokenUsage.EMPTY;
        }
        JsonNode promptDetails = usage.path("prompt_tokens_details");
        JsonNode completionDetails = usage.path("completion_tokens_details");
        return new TokenUsage(
                prompt,
                completion,
                intOrNull(promptDetails.get("cached_tokens")),
                0,
                intOrNull(completionDetails.get("reasoning_tokens")),
                intOrNull(promptDetails.get("audio_tokens")),
                intOrNull(completionDetails.get("audio_tokens")),
                false);
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    @Override
    public BigDecimal priceInput(ModelRoute route) {
        return route.getInputPricePerMillion() == null ? BigDecimal.ZERO : route.getInputPricePerMillion();
    }

    @Override
    public BigDecimal priceOutput(ModelRoute route) {
        return route.getOutputPricePerMillion() == null ? BigDecimal.ZERO : route.getOutputPricePerMillion();
    }

    private static String join(String base, String path) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private static final class Translator implements StreamTranslator {

        private final ChatRequestSpec spec;
        private final ObjectMapper mapper;
        private TokenUsage usage = TokenUsage.EMPTY;
        private boolean done;

        private Translator(ChatRequestSpec spec, ObjectMapper mapper) {
            this.spec = spec;
            this.mapper = mapper;
        }

        @Override
        public List<JsonNode> translate(String line) {
            if (line == null || line.isBlank() || line.startsWith(":")) {
                return List.of();
            }
            String payload;
            if (line.startsWith("data:")) {
                payload = line.substring(5).trim();
            } else if (line.startsWith("{") || line.startsWith("[")) {
                payload = line.trim();
            } else {
                return List.of();
            }
            if (payload.equals("[DONE]")) {
                done = true;
                return List.of();
            }
            try {
                JsonNode node = mapper.readTree(payload);
                if (node.has("error")) {
                    String message = node.get("error").path("message").asText("upstream stream error");
                    throw new UpstreamException(502, "upstream_stream_error", message);
                }
                JsonNode usageNode = node.get("usage");
                if (usageNode != null && usageNode.isObject()) {
                    usage = TokenUsage.merge(usage, fromUsageNode(usageNode));
                }
                JsonNode choices = node.get("choices");
                boolean hasChoices = choices != null && choices.isArray() && !choices.isEmpty();
                if (!hasChoices) {
                    return List.of();
                }
                if (node instanceof ObjectNode object) {
                    object.put("model", spec.publicModel());
                }
                return List.of(node);
            } catch (UpstreamException e) {
                throw e;
            } catch (Exception e) {
                log.debug("Skipping unparseable upstream SSE line: {}", line);
                return List.of();
            }
        }

        @Override
        public boolean done() {
            return done;
        }

        @Override
        public TokenUsage usage() {
            return usage;
        }
    }
}
