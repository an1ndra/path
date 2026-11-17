package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingRegistry;

/**
 * Token counting shared by pre-authorisation and {@code POST /v1/tokens/count}.
 *
 * <p>Counts real tokens when the upstream model has a known encoding and falls back to
 * {@code chars / 4} otherwise. The estimate only ever gates spend; billing uses the
 * provider-reported usage.
 */
public final class TokenCounter {

    private static final EncodingRegistry TOKENIZERS = Encodings.newLazyEncodingRegistry();

    private TokenCounter() {
    }

    /** @param estimated true when the chars/4 heuristic was used instead of a real encoding. */
    public record Count(int tokens, boolean estimated) {
    }

    public static Count count(JsonNode body, String model) {
        String text = promptText(body);
        if (model != null && !model.isBlank()) {
            try {
                Encoding encoding = TOKENIZERS.getEncodingForModel(model).orElse(null);
                if (encoding != null) {
                    return new Count(Math.max(1, encoding.countTokens(text)), false);
                }
            } catch (Exception ignored) {
                // unknown model or unsupported encoding: fall through to the heuristic
            }
        }
        return new Count(Math.max(1, text.length() / 4), true);
    }

    /**
     * The text a prompt costs for: roles, contents, tool calls and the tool definitions,
     * which is where naive estimates are most wrong.
     */
    public static String promptText(JsonNode body) {
        StringBuilder sb = new StringBuilder();
        JsonNode messages = body.path("messages");
        if (messages.isArray()) {
            for (JsonNode message : messages) {
                JsonNode role = message.get("role");
                if (role != null) {
                    sb.append(role.asText()).append(' ');
                }
                JsonNode content = message.get("content");
                if (content != null) {
                    sb.append(textOf(content));
                }
                JsonNode toolCalls = message.get("tool_calls");
                if (toolCalls != null) {
                    sb.append(toolCalls.toString());
                }
                sb.append('\n');
            }
        }
        JsonNode tools = body.get("tools");
        if (tools != null) {
            sb.append(tools.toString());
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
}
