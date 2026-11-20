package com.anindra.path.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PathChatModelTest {

    private HttpServer server;
    private final ObjectMapper mapper = new ObjectMapper();
    private volatile int status = 200;
    private volatile String responseBody = "{}";
    private volatile String contentType = "application/json";
    private volatile java.util.Map<String, String> responseHeaders = java.util.Map.of();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private final AtomicReference<com.sun.net.httpserver.Headers> requestHeaders = new AtomicReference<>();

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        requestHeaders.set(exchange.getRequestHeaders());
        responseHeaders.forEach((name, value) -> exchange.getResponseHeaders().add(name, value));
        exchange.getResponseHeaders().add("Content-Type", contentType);
        byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private PathChatModel model(String apiKey, boolean idempotency) {
        return PathChatModel.builder()
                .baseUrl("http://127.0.0.1:" + server.getAddress().getPort())
                .apiKey(apiKey)
                .model("gpt-4o-mini")
                .idempotency(idempotency)
                .build();
    }

    private static String completion(String content, String finishReason, int prompt, int completion) {
        return """
                {"id":"chatcmpl-1","model":"gpt-4o-mini","choices":[{"index":0,
                 "message":{"role":"assistant","content":%s},"finish_reason":%s}],
                 "usage":{"prompt_tokens":%d,"completion_tokens":%d,"total_tokens":%d}}
                """.formatted(content == null ? "null" : "\"" + content + "\"",
                finishReason == null ? "null" : "\"" + finishReason + "\"",
                prompt, completion, prompt + completion);
    }

    @Test
    void parsesContentUsageAndThePathHeaders() {
        responseHeaders = java.util.Map.of(
                "X-Path-Cache", "hit",
                "X-Gateway-Provider", "openai",
                "X-Gateway-Cost-Usd", "0.00042");
        responseBody = completion("Hello from Path", "stop", 12, 4);

        ChatResponse response = model("sk-test", false)
                .call(new Prompt(List.of(new UserMessage("hi"))));

        assertThat(response.getResult().getOutput().getText()).isEqualTo("Hello from Path");
        assertThat(response.getMetadata().getUsage().getPromptTokens()).isEqualTo(12);
        assertThat(response.getMetadata().getUsage().getCompletionTokens()).isEqualTo(4);
        assertThat(response.getMetadata().getUsage().getTotalTokens()).isEqualTo(16);
        assertThat((Object) response.getMetadata().get("path_cache")).isEqualTo("hit");
        assertThat((Object) response.getMetadata().get("path_provider")).isEqualTo("openai");
        assertThat((Object) response.getMetadata().get("path_cost_usd")).isEqualTo("0.00042");
        assertThat(response.getResult().getMetadata().getFinishReason()).isEqualTo("stop");
    }

    @Test
    void sendsMessagesOptionsAuthAndAnIdempotencyKey() throws Exception {
        responseBody = completion("ok", "stop", 1, 1);
        ChatOptions options = ChatOptions.builder()
                .model("claude-3.5-haiku")
                .temperature(0.2)
                .maxTokens(64)
                .build();

        model("sk-test", true).call(new Prompt(List.of(
                new SystemMessage("be terse"), new UserMessage("hi")), options));

        JsonNode sent = mapper.readTree(requestBody.get());
        assertThat(sent.path("model").asText()).isEqualTo("claude-3.5-haiku");
        assertThat(sent.path("temperature").asDouble()).isEqualTo(0.2);
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(64);
        assertThat(sent.path("stream").asBoolean()).isFalse();
        assertThat(sent.path("messages").get(0).path("role").asText()).isEqualTo("system");
        assertThat(sent.path("messages").get(1).path("content").asText()).isEqualTo("hi");
        assertThat(requestHeaders.get().getFirst("Authorization")).isEqualTo("Bearer sk-test");
        assertThat(requestHeaders.get().getFirst("Idempotency-Key")).isNotBlank();
    }

    @Test
    void toolCallsRoundTrip() throws Exception {
        responseBody = """
                {"id":"chatcmpl-2","model":"gpt-4o-mini","choices":[{"index":0,
                 "message":{"role":"assistant","content":null,"tool_calls":[
                   {"id":"call_1","type":"function","function":{"name":"weather","arguments":"{\\"city\\":\\"Pune\\"}"}}]},
                 "finish_reason":"tool_calls"}],
                 "usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}
                """;

        ChatResponse response = model("sk-test", false).call(new Prompt(List.of(new UserMessage("weather?"))));

        AssistantMessage output = response.getResult().getOutput();
        assertThat(output.hasToolCalls()).isTrue();
        assertThat(output.getToolCalls().get(0).id()).isEqualTo("call_1");
        assertThat(output.getToolCalls().get(0).name()).isEqualTo("weather");
        assertThat(output.getToolCalls().get(0).arguments()).contains("Pune");

        // The follow-up turn sends the tool definition and the tool's answer back.
        responseBody = completion("It is sunny", "stop", 20, 3);
        ToolCallback callback = new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name("weather")
                        .description("Current weather for a city")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"city\":{\"type\":\"string\"}}}")
                        .build();
            }

            @Override
            public String call(String input) {
                return "sunny";
            }
        };
        ToolCallingChatOptions options = ToolCallingChatOptions.builder()
                .model("gpt-4o-mini")
                .toolCallbacks(List.of(callback))
                .build();
        List<Message> conversation = List.of(
                new UserMessage("weather?"),
                AssistantMessage.builder()
                        .content(null)
                        .toolCalls(List.of(new AssistantMessage.ToolCall("call_1", "function", "weather",
                                "{\"city\":\"Pune\"}")))
                        .build(),
                ToolResponseMessage.builder()
                        .responses(List.of(new ToolResponseMessage.ToolResponse("call_1", "weather", "sunny")))
                        .build());

        model("sk-test", false).call(new Prompt(conversation, options));

        JsonNode sent = mapper.readTree(requestBody.get());
        assertThat(sent.path("tools").get(0).path("function").path("name").asText()).isEqualTo("weather");
        assertThat(sent.path("tools").get(0).path("function").path("parameters").path("type").asText())
                .isEqualTo("object");
        assertThat(sent.path("messages").get(1).path("tool_calls").get(0).path("id").asText())
                .isEqualTo("call_1");
        assertThat(sent.path("messages").get(2).path("role").asText()).isEqualTo("tool");
        assertThat(sent.path("messages").get(2).path("tool_call_id").asText()).isEqualTo("call_1");
        assertThat(sent.path("messages").get(2).path("content").asText()).isEqualTo("sunny");
    }

    @Test
    void streamAssemblesContentChunks() throws Exception {
        contentType = "text/event-stream";
        responseBody = """
                data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"Hel"},"finish_reason":null}]}

                data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":null}]}

                data: {"id":"c1","model":"gpt-4o-mini","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}

                data: [DONE]

                """;

        List<ChatResponse> chunks = model("sk-test", false)
                .stream(new Prompt(List.of(new UserMessage("hi"))))
                .collectList()
                .block();

        assertThat(chunks).isNotNull();
        String text = chunks.stream().map(c -> c.getResult().getOutput().getText())
                .reduce("", String::concat);
        assertThat(text).isEqualTo("Hello");
        assertThat(chunks.get(chunks.size() - 1).getResult().getMetadata().getFinishReason())
                .isEqualTo("stop");
        assertThat(mapper.readTree(requestBody.get()).path("stream").asBoolean()).isTrue();
    }

    @Test
    void anErrorEnvelopeBecomesAPathException() {
        status = 422;
        responseBody = """
                {"error":{"message":"unsupported parameter: seed","type":"invalid_request_error",
                 "code":"unsupported_parameter"}}
                """;

        assertThatThrownBy(() -> model("sk-test", false).call(new Prompt("hi")))
                .isInstanceOf(PathException.class)
                .satisfies(e -> {
                    PathException failure = (PathException) e;
                    assertThat(failure.status()).isEqualTo(422);
                    assertThat(failure.code()).isEqualTo("unsupported_parameter");
                    assertThat(failure.getMessage()).contains("seed");
                });
    }
}
