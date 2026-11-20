package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenEstimateTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ChatRequestSpec spec(String content) throws Exception {
        String json = "{\"model\":\"gpt-4o-mini\",\"messages\":[{\"role\":\"user\",\"content\":"
                + mapper.writeValueAsString(content) + "}]}";
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(json));
    }

    @Test
    void tokenisesKnownModels() throws Exception {
        ChatRequestSpec spec = spec("The quick brown fox jumps over the lazy dog.");
        int tokens = spec.estimatePromptTokens("gpt-4o-mini");

        assertThat(tokens).isBetween(1, 40);
        // Real token counts land close to chars/4 for plain English.
        assertThat(tokens).isCloseTo(spec.estimatePromptTokens(), org.assertj.core.data.Offset.offset(15));
    }

    @Test
    void fallsBackForUnknownModels() throws Exception {
        ChatRequestSpec spec = spec("hello there");
        assertThat(spec.estimatePromptTokens("not-a-real-model-xyz"))
                .isEqualTo(spec.estimatePromptTokens());
    }

    @Test
    void scalesWithInputLength() throws Exception {
        ChatRequestSpec small = spec("hi");
        ChatRequestSpec large = spec("hi ".repeat(500));
        assertThat(large.estimatePromptTokens("gpt-4o-mini"))
                .isGreaterThan(small.estimatePromptTokens("gpt-4o-mini") * 10);
    }
}
