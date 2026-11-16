package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anindra.path.api.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Parsed, validated view of an incoming OpenAI-style chat completion request.
 * The original body is kept so OpenAI-shaped providers can be forwarded verbatim.
 */
public final class ChatRequestSpec {

    private static final Set<String> ROLES =
            Set.of("system", "developer", "user", "assistant", "tool", "function");

    /** Fields that only make sense to the gateway and must not reach a provider. */
    private static final List<String> GATEWAY_ONLY_FIELDS =
            List.of("provider", "models", "transforms", "route");

    private final ObjectNode body;
    private final String publicModel;
    private final List<String> fallbackModels;
    private final boolean stream;
    private final boolean includeUsage;
    private final Integer maxTokens;
    private final List<String> providerOrder;
    private final boolean allowFallbacks;

    private ChatRequestSpec(ObjectNode body, String publicModel, List<String> fallbackModels,
                            boolean stream, boolean includeUsage, Integer maxTokens,
                            List<String> providerOrder, boolean allowFallbacks) {
        this.body = body;
        this.publicModel = publicModel;
        this.fallbackModels = fallbackModels;
        this.stream = stream;
        this.includeUsage = includeUsage;
        this.maxTokens = maxTokens;
        this.providerOrder = providerOrder;
        this.allowFallbacks = allowFallbacks;
    }

    public static ChatRequestSpec parse(ObjectNode body) {
        JsonNode messages = body.get("messages");
        if (messages == null || !messages.isArray() || messages.isEmpty()) {
            throw ApiException.badRequest("'messages' must be a non-empty array.", "messages");
        }
        for (JsonNode message : messages) {
            if (!message.isObject() || !message.hasNonNull("role")) {
                throw ApiException.badRequest("Every message needs a 'role'.", "messages");
            }
            String role = message.get("role").asText();
            if (!ROLES.contains(role)) {
                throw ApiException.badRequest("Unsupported message role: " + role, "messages");
            }
            JsonNode content = message.get("content");
            if (content != null && !content.isNull() && !content.isTextual() && !content.isArray()) {
                throw ApiException.badRequest(
                        "message.content must be a string or an array of content parts.", "messages");
            }
        }

        List<String> models = new ArrayList<>();
        JsonNode modelsNode = body.get("models");
        if (modelsNode != null && modelsNode.isArray()) {
            modelsNode.forEach(n -> {
                if (n.isTextual() && !n.asText().isBlank()) {
                    models.add(n.asText());
                }
            });
        }

        String model = body.path("model").isTextual() ? body.get("model").asText() : null;
        if (model == null || model.isBlank()) {
            model = models.isEmpty() ? null : models.get(0);
        }
        if (model == null || model.isBlank()) {
            throw ApiException.badRequest("'model' is required.", "model");
        }

        boolean stream = body.path("stream").asBoolean(false);
        boolean includeUsage = body.path("stream_options").path("include_usage").asBoolean(false);

        Integer maxTokens = null;
        if (body.hasNonNull("max_tokens")) {
            maxTokens = body.get("max_tokens").asInt();
        } else if (body.hasNonNull("max_completion_tokens")) {
            maxTokens = body.get("max_completion_tokens").asInt();
        }
        if (maxTokens != null && maxTokens <= 0) {
            throw ApiException.badRequest("'max_tokens' must be positive.", "max_tokens");
        }

        List<String> providerOrder = new ArrayList<>();
        JsonNode provider = body.get("provider");
        boolean allowFallbacks = true;
        if (provider != null && provider.isObject()) {
            JsonNode order = provider.get("order");
            if (order != null && order.isArray()) {
                order.forEach(n -> {
                    if (n.isTextual()) {
                        providerOrder.add(n.asText());
                    }
                });
            }
            if (provider.has("allow_fallbacks")) {
                allowFallbacks = provider.get("allow_fallbacks").asBoolean(true);
            }
        }

        return new ChatRequestSpec(body, model, models, stream, includeUsage, maxTokens,
                providerOrder, allowFallbacks);
    }

    /** Deep copy with gateway-only fields stripped, ready to forward. */
    public ObjectNode forwardableBody() {
        ObjectNode copy = body.deepCopy();
        GATEWAY_ONLY_FIELDS.forEach(copy::remove);
        return copy;
    }

    public ObjectNode body() { return body; }
    public String publicModel() { return publicModel; }
    public List<String> fallbackModels() { return fallbackModels; }
    public boolean stream() { return stream; }
    public boolean includeUsage() { return includeUsage; }
    public Integer maxTokens() { return maxTokens; }
    public List<String> providerOrder() { return providerOrder; }
    public boolean allowFallbacks() { return allowFallbacks; }

    public JsonNode messages() {
        return body.get("messages");
    }

    public boolean hasTools() {
        JsonNode tools = body.get("tools");
        return tools != null && tools.isArray() && !tools.isEmpty();
    }

    /** All system/developer content concatenated, in order. */
    public String systemText() {
        StringBuilder sb = new StringBuilder();
        for (JsonNode message : messages()) {
            String role = message.path("role").asText();
            if (!role.equals("system") && !role.equals("developer")) {
                continue;
            }
            JsonNode content = message.get("content");
            String text = content == null ? "" : textOf(content);
            if (!text.isBlank()) {
                if (!sb.isEmpty()) {
                    sb.append("\n\n");
                }
                sb.append(text);
            }
        }
        return sb.toString();
    }

    private static String textOf(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder sb = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if (part.path("type").asText().equals("text") && part.hasNonNull("text")) {
                    sb.append(part.get("text").asText());
                }
            }
        }
        return sb.toString();
    }

    /** Rough prompt size used only to pre-authorise spend. */
    public int estimatePromptTokens() {
        int chars = 0;
        for (JsonNode message : messages()) {
            JsonNode content = message.get("content");
            if (content != null) {
                chars += textOf(content).length();
            }
            JsonNode toolCalls = message.get("tool_calls");
            if (toolCalls != null) {
                chars += toolCalls.toString().length();
            }
        }
        JsonNode tools = body.get("tools");
        if (tools != null) {
            chars += tools.toString().length();
        }
        return Math.max(1, chars / 4);
    }
}
