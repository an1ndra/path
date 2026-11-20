package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fuzzes the untrusted request path: arbitrary JSON into {@link ChatRequestSpec} and then
 * into the Anthropic translator. The only acceptable failure is {@link ApiException};
 * anything else (NPE, ClassCastException, OOM) is a bug.
 */
class ChatRequestFuzzTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicAdapter adapter = new AnthropicAdapter(mapper);
    private final Provider provider =
            new Provider("anthropic", "Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com/v1");
    private final ModelRoute route =
            new ModelRoute("claude-3.5-haiku", 1L, "claude-3-5-haiku-latest",
                    new BigDecimal("0.80"), new BigDecimal("4.00"), 10);

    @FuzzTest(maxDuration = "10s")
    void parserAndTranslatorOnlyRejectWithApiExceptions(FuzzedDataProvider data) {
        String json = data.consumeString(4096);
        JsonNode node;
        try {
            node = mapper.readTree(json);
        } catch (Exception notJson) {
            return;
        }
        if (!(node instanceof ObjectNode object)) {
            return;
        }
        try {
            ChatRequestSpec spec = ChatRequestSpec.parse(object);
            adapter.build(spec, route, provider, "test-key", data.consumeBoolean());
        } catch (ApiException expected) {
            // rejected input is fine; anything else would escape and fail the test
        }
    }

    @Test
    void aDeeplyNestedBodyDoesNotOverflowTheStack() {
        StringBuilder json = new StringBuilder("{\"model\":\"x\",\"messages\":[{\"role\":\"user\",\"content\":");
        json.append("[".repeat(200)).append("\"x\"").append("]".repeat(200));
        json.append("}]}");

        try {
            JsonNode node = mapper.readTree(json.toString());
            ChatRequestSpec.parse((ObjectNode) node);
        } catch (ApiException | com.fasterxml.jackson.core.JacksonException expected) {
            // either a clean rejection or a parse failure is acceptable
        }
        assertThat(true).isTrue();
    }
}
