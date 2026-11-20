package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TokenCounterTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode body(String json) throws Exception {
        return (ObjectNode) mapper.readTree(json);
    }

    @Test
    void countsAGrowingConversation() throws Exception {
        int one = TokenCounter.count(body(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"}]}"), "gpt-4o-mini").tokens();
        int many = TokenCounter.count(body(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"hello\"},"
                        + "{\"role\":\"assistant\",\"content\":\"hi there, how can I help you today?\"},"
                        + "{\"role\":\"user\",\"content\":\"explain vector databases\"}]}"),
                "gpt-4o-mini").tokens();

        assertThat(one).isPositive();
        assertThat(many).isGreaterThan(one);
    }

    @Test
    void countsToolDefinitions() throws Exception {
        String tool = "{\"type\":\"function\",\"function\":{\"name\":\"lookup\","
                + "\"description\":\"Look up a customer by id\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"string\"}}}}}";
        int without = TokenCounter.count(body(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"find customer 42\"}]}"),
                "gpt-4o-mini").tokens();
        int with = TokenCounter.count(body(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"find customer 42\"}],"
                        + "\"tools\":[" + tool + "]}"), "gpt-4o-mini").tokens();

        assertThat(with).isGreaterThan(without);
    }

    @Test
    void fallsBackToTheHeuristicForAnUnknownModel() throws Exception {
        var count = TokenCounter.count(body(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"some prompt text here\"}]}"),
                "totally-unknown-model");

        assertThat(count.estimated()).isTrue();
        assertThat(count.tokens()).isPositive();
    }

    @Test
    void knownModelsAreNotMarkedEstimated() throws Exception {
        var count = TokenCounter.count(body(
                "{\"messages\":[{\"role\":\"user\",\"content\":\"some prompt text here\"}]}"),
                "gpt-4o-mini");

        assertThat(count.estimated()).isFalse();
    }
}
