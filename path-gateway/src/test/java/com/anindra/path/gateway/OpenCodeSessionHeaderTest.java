package com.anindra.path.gateway;

import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenCode Go answers 400 "Request is missing x-opencode-session" to any request without
 * that header, including traffic that reaches it through the gateway. The header is meant
 * to be stable per conversation, so it is derived from the model and the first user
 * message.
 */
class OpenCodeSessionHeaderTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final OpenAiCompatibleAdapter adapter = new OpenAiCompatibleAdapter(mapper);

    private Provider provider(String slug) {
        return new Provider(slug, slug, ProviderType.OPENAI,
                "https://opencode.ai/inference/go/openai/v1");
    }

    private ModelRoute route() {
        return new ModelRoute("deepseek-v4.1-flash", 1L, "deepseek-v4.1-flash",
                new BigDecimal("0.15"), new BigDecimal("0.60"), 10);
    }

    private Map<String, String> headers(String slug, String firstMessage) throws Exception {
        ChatRequestSpec spec = ChatRequestSpec.parse((ObjectNode) mapper.readTree(
                "{\"model\":\"deepseek-v4.1-flash\",\"messages\":[{\"role\":\"system\",\"content\":\"be brief\"},"
                        + "{\"role\":\"user\",\"content\":\"" + firstMessage + "\"}]}"));
        return adapter.build(spec, route(), provider(slug), "key", false).headers();
    }

    @Test
    void openCodeRequestsCarryASessionHeader() throws Exception {
        Map<String, String> headers = headers("opencode-go", "first question");

        assertThat(headers).containsKey("x-opencode-session");
        assertThat(headers.get("x-opencode-session")).startsWith("path-").hasSize(37);
    }

    @Test
    void theSessionIsStableWithinAConversation() throws Exception {
        assertThat(headers("opencode-go", "first question"))
                .isEqualTo(headers("opencode-go", "first question"));
    }

    @Test
    void aDifferentConversationGetsADifferentSession() throws Exception {
        assertThat(headers("opencode-go", "first question").get("x-opencode-session"))
                .isNotEqualTo(headers("opencode-go", "another question").get("x-opencode-session"));
    }

    @Test
    void otherProvidersAreUntouched() throws Exception {
        assertThat(headers("groq", "first question")).doesNotContainKey("x-opencode-session");
    }

    @Test
    void everyRequestIdentifiesTheGateway() throws Exception {
        assertThat(headers("groq", "hi")).containsEntry("User-Agent", "path-gateway");
    }
}
