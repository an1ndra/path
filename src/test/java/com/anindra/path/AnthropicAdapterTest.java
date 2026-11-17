package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnthropicAdapterTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final AnthropicAdapter adapter = new AnthropicAdapter(mapper);

    private final Provider provider =
            new Provider("anthropic", "Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com/v1");
    private final ModelRoute route =
            new ModelRoute("claude-3.5-haiku", 1L, "claude-3-5-haiku-latest",
                    new BigDecimal("0.80"), new BigDecimal("4.00"), 10);

    private ChatRequestSpec spec(String json) throws Exception {
        return ChatRequestSpec.parse((ObjectNode) mapper.readTree(json));
    }

    @Test
    void translatesSystemMessagesImagesToolsAndToolResults() throws Exception {
        ChatRequestSpec spec = spec("""
                {
                  "model": "claude-3.5-haiku",
                  "max_tokens": 100,
                  "messages": [
                    {"role": "system", "content": "be terse"},
                    {"role": "user", "content": [
                        {"type": "text", "text": "what is this?"},
                        {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAA"}}
                    ]},
                    {"role": "assistant", "content": null, "tool_calls": [
                        {"id": "call_1", "type": "function",
                         "function": {"name": "lookup", "arguments": "{\\"q\\":\\"x\\"}"}}
                    ]},
                    {"role": "tool", "tool_call_id": "call_1", "content": "result"},
                    {"role": "user", "content": "thanks"}
                  ],
                  "tools": [{"type": "function", "function": {
                      "name": "lookup", "description": "d",
                      "parameters": {"type": "object", "properties": {}}}}]
                }
                """);

        UpstreamRequest request = adapter.build(spec, route, provider, "sk-ant-test", false);
        JsonNode body = mapper.readTree(request.body());

        assertThat(request.url()).isEqualTo("https://api.anthropic.com/v1/messages");
        assertThat(request.headers()).containsEntry("x-api-key", "sk-ant-test")
                .containsEntry("anthropic-version", "2023-06-01");
        assertThat(body.get("model").asText()).isEqualTo("claude-3-5-haiku-latest");
        assertThat(body.get("max_tokens").asInt()).isEqualTo(100);
        assertThat(body.get("system").asText()).isEqualTo("be terse");
        assertThat(body.get("stream").asBoolean()).isFalse();

        JsonNode messages = body.get("messages");
        assertThat(messages).hasSize(3);

        JsonNode first = messages.get(0);
        assertThat(first.get("role").asText()).isEqualTo("user");
        assertThat(first.get("content").get(0).get("text").asText()).isEqualTo("what is this?");
        assertThat(first.get("content").get(1).get("type").asText()).isEqualTo("image");
        assertThat(first.get("content").get(1).get("source").get("type").asText()).isEqualTo("base64");
        assertThat(first.get("content").get(1).get("source").get("media_type").asText()).isEqualTo("image/png");

        JsonNode assistant = messages.get(1);
        assertThat(assistant.get("role").asText()).isEqualTo("assistant");
        JsonNode toolUse = assistant.get("content").get(0);
        assertThat(toolUse.get("type").asText()).isEqualTo("tool_use");
        assertThat(toolUse.get("name").asText()).isEqualTo("lookup");
        assertThat(toolUse.get("input").get("q").asText()).isEqualTo("x");

        // tool result and the following user message are merged into one user turn
        JsonNode merged = messages.get(2);
        assertThat(merged.get("role").asText()).isEqualTo("user");
        assertThat(merged.get("content").get(0).get("type").asText()).isEqualTo("tool_result");
        assertThat(merged.get("content").get(0).get("tool_use_id").asText()).isEqualTo("call_1");
        assertThat(merged.get("content").get(1).get("text").asText()).isEqualTo("thanks");

        JsonNode tools = body.get("tools");
        assertThat(tools.get(0).get("name").asText()).isEqualTo("lookup");
        assertThat(tools.get(0).get("input_schema").get("type").asText()).isEqualTo("object");
    }

    @Test
    void rejectsParametersAnthropicCannotExpress() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"claude-3.5-haiku","seed":42,
                 "messages":[{"role":"user","content":"hi"}]}
                """);

        assertThatThrownBy(() -> adapter.build(spec, route, provider, "k", false))
                .isInstanceOf(com.anindra.path.api.ApiException.class)
                .hasMessageContaining("seed")
                .hasFieldOrPropertyWithValue("code", "unsupported_parameter");
    }

    @Test
    void rejectsMultipleChoices() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"claude-3.5-haiku","n":2,
                 "messages":[{"role":"user","content":"hi"}]}
                """);

        assertThatThrownBy(() -> adapter.build(spec, route, provider, "k", false))
                .hasMessageContaining("'n'");
    }

    @Test
    void allowsTextResponseFormat() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"claude-3.5-haiku","response_format":{"type":"text"},
                 "messages":[{"role":"user","content":"hi"}]}
                """);

        UpstreamRequest request = adapter.build(spec, route, provider, "k", false);
        assertThat(request.body()).isNotEmpty();
    }

    @Test
    void mapsUserToMetadata() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"claude-3.5-haiku","user":"tenant-42",
                 "messages":[{"role":"user","content":"hi"}]}
                """);

        UpstreamRequest request = adapter.build(spec, route, provider, "k", false);
        JsonNode body = mapper.readTree(request.body());
        assertThat(body.get("metadata").get("user_id").asText()).isEqualTo("tenant-42");
    }

    @Test
    void translatesAResponseWithToolUse() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"claude-3.5-haiku","messages":[{"role":"user","content":"hi"}]}
                """);
        JsonNode upstream = mapper.readTree("""
                {"id":"msg_1","type":"message","role":"assistant","model":"claude-3-5-haiku-latest",
                 "content":[{"type":"text","text":"Let me check."},
                            {"type":"tool_use","id":"toolu_1","name":"lookup","input":{"q":"x"}}],
                 "stop_reason":"tool_use",
                 "usage":{"input_tokens":13,"output_tokens":9,
                          "cache_read_input_tokens":4,"cache_creation_input_tokens":2}}
                """);

        JsonNode out = adapter.parseFullResponse(upstream, spec, route);

        assertThat(out.get("object").asText()).isEqualTo("chat.completion");
        assertThat(out.get("model").asText()).isEqualTo("claude-3.5-haiku");
        JsonNode choice = out.get("choices").get(0);
        assertThat(choice.get("message").get("content").asText()).isEqualTo("Let me check.");
        assertThat(choice.get("message").get("tool_calls").get(0).get("function").get("name").asText())
                .isEqualTo("lookup");
        assertThat(choice.get("finish_reason").asText()).isEqualTo("tool_calls");
        // prompt = 13 uncached + 4 cache read + 2 cache write
        assertThat(out.get("usage").get("total_tokens").asInt()).isEqualTo(28);
        assertThat(out.get("usage").get("prompt_tokens").asInt()).isEqualTo(19);
        assertThat(out.get("usage").get("prompt_tokens_details").get("cached_tokens").asInt()).isEqualTo(4);

        TokenUsage usage = adapter.parseUsage(upstream);
        assertThat(usage.prompt()).isEqualTo(19);
        assertThat(usage.cached()).isEqualTo(4);
        assertThat(usage.cacheWrite()).isEqualTo(2);
        assertThat(usage.billableInput()).isEqualTo(13);
        assertThat(usage.completion()).isEqualTo(9);
    }

    @Test
    void translatesStreamEventsIncludingToolArguments() throws Exception {
        ChatRequestSpec spec = spec("""
                {"model":"claude-3.5-haiku","stream":true,
                 "messages":[{"role":"user","content":"hi"}]}
                """);
        StreamTranslator translator = adapter.newStreamTranslator(spec, route);

        List<String> lines = List.of(
                "event: message_start",
                """
                data: {"type":"message_start","message":{"id":"msg_1","usage":{"input_tokens":5,"cache_read_input_tokens":3}}}""",
                "event: content_block_start",
                """
                data: {"type":"content_block_start","index":0,"content_block":{"type":"text","text":""}}""",
                """
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"Hel"}}""",
                """
                data: {"type":"content_block_delta","index":0,"delta":{"type":"text_delta","text":"lo"}}""",
                """
                data: {"type":"content_block_start","index":1,"content_block":{"type":"tool_use","id":"toolu_1","name":"lookup"}}""",
                """
                data: {"type":"content_block_delta","index":1,"delta":{"type":"input_json_delta","partial_json":"{\\"q\\":1}"}}""",
                """
                data: {"type":"message_delta","delta":{"stop_reason":"tool_use"},"usage":{"output_tokens":2}}""",
                "data: {\"type\":\"message_stop\"}"
        );

        StringBuilder text = new StringBuilder();
        String finish = null;
        String toolArgs = null;
        for (String line : lines) {
            for (JsonNode chunk : translator.translate(line)) {
                assertThat(chunk.get("object").asText()).isEqualTo("chat.completion.chunk");
                assertThat(chunk.get("model").asText()).isEqualTo("claude-3.5-haiku");
                for (JsonNode choice : chunk.get("choices")) {
                    JsonNode delta = choice.get("delta");
                    if (delta.hasNonNull("content")) {
                        text.append(delta.get("content").asText());
                    }
                    if (delta.has("tool_calls")) {
                        toolArgs = delta.get("tool_calls").get(0).get("function").get("arguments").asText();
                    }
                    if (choice.hasNonNull("finish_reason")) {
                        finish = choice.get("finish_reason").asText();
                    }
                }
            }
        }

        assertThat(text).hasToString("Hello");
        assertThat(finish).isEqualTo("tool_calls");
        assertThat(toolArgs).isEqualTo("{\"q\":1}");
        assertThat(translator.done()).isTrue();
        assertThat(translator.usage().promptTokens()).isEqualTo(8);
        assertThat(translator.usage().cached()).isEqualTo(3);
        assertThat(translator.usage().completionTokens()).isEqualTo(2);
    }
}
