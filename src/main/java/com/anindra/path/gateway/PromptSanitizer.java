package com.anindra.path.gateway;

import com.anindra.path.config.GatewayProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Applies {@link PiiRedactor} to a request body: message contents, tool results and tool
 * definitions. Used both for "redact before send" and for prompt logs, so what is stored
 * and what a provider sees are redacted by the same rules.
 */
@Component
@RequiredArgsConstructor
public class PromptSanitizer {

    private final GatewayProperties props;

    public boolean redactBeforeSend() {
        return props.getGuardrails().isRedactBeforeSend();
    }

    public boolean redactPromptLogs() {
        return props.getGuardrails().isPromptLogRedaction();
    }

    /** @return a redacted deep copy; the input is never modified. */
    public ObjectNode redactBody(ObjectNode body) {
        ObjectNode copy = body.deepCopy();
        JsonNode messages = copy.get("messages");
        if (messages != null && messages.isArray()) {
            for (JsonNode message : messages) {
                if (message instanceof ObjectNode node) {
                    redactField(node, "content");
                    redactField(node, "tool_calls");
                    redactField(node, "name");
                }
            }
        }
        redactField(copy, "tools");
        redactField(copy, "prompt");
        redactField(copy, "input");
        redactField(copy, "system");
        return copy;
    }

    public String redactText(String text) {
        return PiiRedactor.redact(text).text();
    }

    private void redactField(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null) {
            return;
        }
        if (value.isTextual()) {
            node.put(field, redactText(value.asText()));
        } else if (value.isArray()) {
            ArrayNode array = (ArrayNode) value;
            for (int i = 0; i < array.size(); i++) {
                JsonNode part = array.get(i);
                if (part.isTextual()) {
                    array.set(i, TextNode.valueOf(redactText(part.asText())));
                } else if (part instanceof ObjectNode obj) {
                    redactField(obj, "text");
                    redactField(obj, "content");
                }
            }
        }
    }
}
