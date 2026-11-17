package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Randomised invariants for the OpenAI → Anthropic translation, over generated
 * conversations. Example-based tests only cover cases we thought of; this covers 200.
 */
class AnthropicTranslationPropertyTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicAdapter adapter = new AnthropicAdapter(mapper);
    private final Provider provider =
            new Provider("anthropic", "Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com/v1");
    private final ModelRoute route =
            new ModelRoute("claude-3.5-haiku", 1L, "claude-3-5-haiku-latest",
                    new BigDecimal("0.80"), new BigDecimal("4.00"), 10);

    @Test
    void generatedConversationsTranslateWithStableInvariants() throws Exception {
        Random random = new Random(20261117L);

        for (int iteration = 0; iteration < 200; iteration++) {
            ObjectNode body = generate(random);
            boolean systemOnly = true;
            for (JsonNode message : body.path("messages")) {
                String role = message.path("role").asText();
                if (!role.equals("system") && !role.equals("developer")) {
                    systemOnly = false;
                    break;
                }
            }
            if (systemOnly) {
                // A system-only conversation is rejected rather than forwarded with an
                // empty Anthropic messages array.
                assertThat(org.assertj.core.api.Assertions.catchThrowable(
                        () -> ChatRequestSpec.parse(body)))
                        .isInstanceOf(com.anindra.path.api.ApiException.class);
                continue;
            }
            ChatRequestSpec spec = ChatRequestSpec.parse(body);

            JsonNode translated = mapper.readTree(
                    adapter.build(spec, route, provider, "test-key", false).body());

            assertSystemIsPreserved(body, translated);
            assertMessageShape(translated);
            assertToolCallsSurvive(body, translated);
        }
    }

    private ObjectNode generate(Random random) {
        ObjectNode body = mapper.createObjectNode();
        body.put("model", "claude-3.5-haiku");
        body.put("max_tokens", 50 + random.nextInt(200));

        StringBuilder system = new StringBuilder();
        ArrayNode messages = body.putArray("messages");
        int count = 1 + random.nextInt(6);
        boolean pendingToolResult = false;

        for (int i = 0; i < count; i++) {
            int pick = random.nextInt(10);
            if (pick == 0) {
                String text = "system rule " + i;
                system.append(text);
                messages.addObject().put("role", "system").put("content", text);
            } else if (pick == 1) {
                ObjectNode assistant = messages.addObject();
                assistant.put("role", "assistant");
                assistant.put("content", "thinking " + i);
                ArrayNode calls = assistant.putArray("tool_calls");
                ObjectNode call = calls.addObject();
                call.put("id", "call_" + i);
                call.put("type", "function");
                ObjectNode fn = call.putObject("function");
                fn.put("name", "lookup");
                fn.put("arguments", "{\"q\":\"" + i + "\"}");
                pendingToolResult = true;
            } else if (pick == 2 && pendingToolResult) {
                messages.addObject().put("role", "tool")
                        .put("tool_call_id", "call_" + (i - 1)).put("content", "result " + i);
                pendingToolResult = false;
            } else {
                messages.addObject().put("role", random.nextBoolean() ? "user" : "assistant")
                        .put("content", "message " + i);
            }
        }
        return body;
    }

    private void assertSystemIsPreserved(ObjectNode body, JsonNode translated) {
        List<String> parts = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            if ("system".equals(message.path("role").asText())) {
                parts.add(message.path("content").asText());
            }
        }
        String expected = String.join("\n\n", parts);
        if (expected.isEmpty()) {
            return;
        }
        assertThat(translated.path("system").asText()).isEqualTo(expected);
        for (JsonNode message : translated.path("messages")) {
            assertThat(message.path("role").asText())
                    .as("system messages must be hoisted out of the message list")
                    .isNotEqualTo("system");
        }
    }

    private void assertMessageShape(JsonNode translated) {
        ArrayNode messages = (ArrayNode) translated.path("messages");
        assertThat(messages).isNotEmpty();

        String previousRole = null;
        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            assertThat(role).isIn("user", "assistant");
            assertThat(role).as("consecutive turns must be merged").isNotEqualTo(previousRole);
            assertThat(message.path("content")).as("content must be block array").isNotNull();
            assertThat(message.path("content").isArray()).isTrue();
            assertThat(message.path("content")).isNotEmpty();
            previousRole = role;
        }
    }

    private void assertToolCallsSurvive(ObjectNode body, JsonNode translated) {
        List<String> expectedIds = new ArrayList<>();
        for (JsonNode message : body.path("messages")) {
            for (JsonNode call : message.path("tool_calls")) {
                expectedIds.add(call.path("id").asText());
            }
        }
        if (expectedIds.isEmpty()) {
            return;
        }

        List<String> actualIds = new ArrayList<>();
        for (JsonNode message : translated.path("messages")) {
            for (JsonNode block : message.path("content")) {
                if ("tool_use".equals(block.path("type").asText())) {
                    actualIds.add(block.path("id").asText());
                }
            }
        }
        assertThat(actualIds).containsExactlyElementsOf(expectedIds);
    }
}
