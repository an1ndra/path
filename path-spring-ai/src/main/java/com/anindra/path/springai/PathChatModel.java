package com.anindra.path.springai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatGenerationMetadata;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.tool.ToolCallback;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * A Spring AI {@link ChatModel} that speaks to a Path gateway.
 *
 * <pre>{@code
 * @Bean
 * ChatModel chatModel() {
 *     return PathChatModel.builder()
 *             .baseUrl("http://path:8080")
 *             .apiKey(System.getenv("PATH_KEY"))
 *             .idempotency(true)
 *             .build();
 * }
 * }</pre>
 *
 * <p>Streaming assembles the SSE content deltas into {@link ChatResponse} chunks; Spring AI's
 * {@code ChatClient} aggregates them. Tool calls work on the non-streaming path, where the
 * gateway returns a complete {@code tool_calls} array; streamed tool-call deltas are not
 * reassembled here (documented in {@code docs/SPRING-AI.md}).
 *
 * <p>Path-specific response headers are copied into the response metadata:
 * {@code path_cache}, {@code path_provider} and {@code path_cost_usd}, so a Spring AI caller
 * can see what the gateway did without knowing the headers.
 */
public final class PathChatModel implements ChatModel {

    public static final String DEFAULT_MODEL = "gpt-4o-mini";

    private final PathHttp http;
    private final ChatOptions defaultOptions;

    private PathChatModel(PathHttp http, ChatOptions defaultOptions) {
        this.http = http;
        this.defaultOptions = defaultOptions;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private String baseUrl = "http://localhost:8080";
        private String apiKey;
        private String model = DEFAULT_MODEL;
        private boolean idempotency;
        private Duration timeout = Duration.ofSeconds(180);
        private ObjectMapper mapper = new ObjectMapper();

        public Builder baseUrl(String baseUrl) {
            this.baseUrl = baseUrl;
            return this;
        }

        public Builder apiKey(String apiKey) {
            this.apiKey = apiKey;
            return this;
        }

        /** The public model id sent when the Prompt's options do not name one. */
        public Builder model(String model) {
            this.model = model;
            return this;
        }

        /** Send an {@code Idempotency-Key} so a caller-side retry is not charged twice. */
        public Builder idempotency(boolean idempotency) {
            this.idempotency = idempotency;
            return this;
        }

        public Builder timeout(Duration timeout) {
            this.timeout = timeout;
            return this;
        }

        public Builder objectMapper(ObjectMapper mapper) {
            this.mapper = mapper;
            return this;
        }

        public PathChatModel build() {
            ChatOptions options = ChatOptions.builder().model(model).build();
            return new PathChatModel(new PathHttp(baseUrl, apiKey, idempotency, timeout, mapper), options);
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        ObjectNode body = body(prompt, false);
        HttpResponse<String> response = http.postJson("/v1/chat/completions", body);
        PathHttp.requireSuccess(response, response.body());
        return parse(response, http.mapper());
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        ObjectNode body = body(prompt, true);
        return Flux.create(sink -> {
            HttpResponse<java.io.InputStream> response = http.postStream("/v1/chat/completions", body);
            if (response.statusCode() / 100 != 2) {
                PathHttp.requireSuccess(response, readAll(response.body()));
                sink.complete();
                return;
            }
            Thread.ofVirtual().name("path-spring-ai-sse").start(() -> pump(response, sink));
            sink.onCancel(() -> closeQuietly(response.body()));
        });
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return defaultOptions;
    }

    // ------------------------------------------------------------------ request

    private ObjectNode body(Prompt prompt, boolean stream) {
        ObjectMapper mapper = http.mapper();
        ObjectNode body = mapper.createObjectNode();
        ChatOptions options = prompt.getOptions() == null ? defaultOptions : prompt.getOptions();
        body.put("model", resolveModel(options));
        body.put("stream", stream);
        if (options.getTemperature() != null) {
            body.put("temperature", options.getTemperature());
        }
        if (options.getMaxTokens() != null) {
            body.put("max_tokens", options.getMaxTokens());
        }
        if (options.getTopP() != null) {
            body.put("top_p", options.getTopP());
        }
        if (options.getFrequencyPenalty() != null) {
            body.put("frequency_penalty", options.getFrequencyPenalty());
        }
        if (options.getPresencePenalty() != null) {
            body.put("presence_penalty", options.getPresencePenalty());
        }
        if (options.getStopSequences() != null && !options.getStopSequences().isEmpty()) {
            ArrayNode stop = body.putArray("stop");
            options.getStopSequences().forEach(stop::add);
        }

        ArrayNode messages = body.putArray("messages");
        for (Message message : prompt.getInstructions()) {
            append(messages, message, mapper);
        }
        if (options instanceof ToolCallingChatOptions tooling) {
            appendTools(body, tooling.getToolCallbacks(), mapper);
        }
        return body;
    }

    private static void append(ArrayNode messages, Message message, ObjectMapper mapper) {
        if (message instanceof ToolResponseMessage toolResponses) {
            for (ToolResponseMessage.ToolResponse response : toolResponses.getResponses()) {
                ObjectNode row = messages.addObject();
                row.put("role", "tool");
                row.put("tool_call_id", response.id());
                row.put("content", response.responseData());
            }
            return;
        }
        ObjectNode row = messages.addObject();
        row.put("role", role(message.getMessageType()));
        if (message instanceof AssistantMessage assistant) {
            row.put("content", assistant.getText());
            if (assistant.hasToolCalls()) {
                ArrayNode calls = row.putArray("tool_calls");
                for (AssistantMessage.ToolCall call : assistant.getToolCalls()) {
                    ObjectNode entry = calls.addObject();
                    entry.put("id", call.id());
                    entry.put("type", call.type() == null ? "function" : call.type());
                    ObjectNode function = entry.putObject("function");
                    function.put("name", call.name());
                    function.put("arguments", call.arguments());
                }
            }
        } else {
            row.put("content", message.getText());
        }
    }

    private static void appendTools(ObjectNode body, List<ToolCallback> callbacks, ObjectMapper mapper) {
        if (callbacks == null || callbacks.isEmpty()) {
            return;
        }
        ArrayNode tools = body.putArray("tools");
        for (ToolCallback callback : callbacks) {
            var definition = callback.getToolDefinition();
            ObjectNode tool = tools.addObject();
            tool.put("type", "function");
            ObjectNode function = tool.putObject("function");
            function.put("name", definition.name());
            if (definition.description() != null) {
                function.put("description", definition.description());
            }
            try {
                function.set("parameters", mapper.readTree(definition.inputSchema()));
            } catch (Exception e) {
                function.putObject("parameters").put("type", "object");
            }
        }
    }

    private String resolveModel(ChatOptions options) {
        if (options != null && options.getModel() != null && !options.getModel().isBlank()) {
            return options.getModel();
        }
        return DEFAULT_MODEL;
    }

    private static String role(MessageType type) {
        return switch (type) {
            case SYSTEM -> "system";
            case USER -> "user";
            case ASSISTANT -> "assistant";
            case TOOL -> "tool";
        };
    }

    // ----------------------------------------------------------------- response

    private static ChatResponse parse(HttpResponse<String> response, ObjectMapper mapper) {
        try {
            return toResponse(mapper.readTree(response.body()), response);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new PathException(response.statusCode(), "invalid_response",
                    "The gateway returned a body that is not JSON", e);
        }
    }

    private static ChatResponse toResponse(JsonNode root, HttpResponse<?> response) {
        JsonNode choice = root.path("choices").path(0);
        JsonNode message = choice.path("message");

        List<AssistantMessage.ToolCall> calls = new ArrayList<>();
        for (JsonNode call : message.path("tool_calls")) {
            JsonNode function = call.path("function");
            calls.add(new AssistantMessage.ToolCall(
                    call.path("id").asText(null),
                    call.path("type").asText("function"),
                    function.path("name").asText(null),
                    function.path("arguments").asText("{}")));
        }

        AssistantMessage output = AssistantMessage.builder()
                .content(message.path("content").isNull() ? null : message.path("content").asText(null))
                .toolCalls(calls)
                .build();

        ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                .finishReason(choice.path("finish_reason").asText(null))
                .build();
        Generation generation = new Generation(output, generationMetadata);

        ChatResponseMetadata.Builder metadata = ChatResponseMetadata.builder()
                .id(root.path("id").asText(null))
                .model(root.path("model").asText(null));
        JsonNode usage = root.path("usage");
        if (usage.isObject()) {
            metadata.usage(new DefaultUsage(usage.path("prompt_tokens").asInt(0),
                    usage.path("completion_tokens").asInt(0),
                    usage.path("total_tokens").asInt(0)));
        }
        header(response, "X-Path-Cache").ifPresent(value -> metadata.keyValue("path_cache", value));
        header(response, "X-Gateway-Provider").ifPresent(value -> metadata.keyValue("path_provider", value));
        header(response, "X-Gateway-Cost-Usd").ifPresent(value -> metadata.keyValue("path_cost_usd", value));

        return new ChatResponse(List.of(generation), metadata.build());
    }

    private static java.util.Optional<String> header(HttpResponse<?> response, String name) {
        return response.headers().firstValue(name);
    }

    private void pump(HttpResponse<java.io.InputStream> response, FluxSink<ChatResponse> sink) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.startsWith("data:")) {
                    continue;
                }
                String data = line.substring(5).trim();
                if (data.equals("[DONE]")) {
                    break;
                }
                JsonNode chunk = http.mapper().readTree(data);
                JsonNode choice = chunk.path("choices").path(0);
                String delta = choice.path("delta").path("content").asText("");
                String finish = choice.path("finish_reason").asText(null);
                if (delta.isEmpty() && finish == null) {
                    continue;
                }
                AssistantMessage message = AssistantMessage.builder().content(delta).build();
                ChatGenerationMetadata generationMetadata = ChatGenerationMetadata.builder()
                        .finishReason(finish)
                        .build();
                ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                        .id(chunk.path("id").asText(null))
                        .model(chunk.path("model").asText(null))
                        .build();
                sink.next(new ChatResponse(List.of(new Generation(message, generationMetadata)), metadata));
            }
            sink.complete();
        } catch (Exception e) {
            sink.error(e instanceof PathException p ? p : PathException.network(e));
        }
    }

    private static String readAll(java.io.InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    private static void closeQuietly(java.io.InputStream stream) {
        try {
            stream.close();
        } catch (IOException ignored) {
            // The read loop is already unwinding.
        }
    }

    /** Convenience for callers that only need one message. */
    public String call(String message) {
        return ChatModel.super.call(message);
    }
}
