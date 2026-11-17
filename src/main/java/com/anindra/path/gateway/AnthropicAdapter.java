package com.anindra.path.gateway;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Anthropic Messages API adapter. Translates OpenAI chat-completions requests,
 * responses and SSE events into the OpenAI shape so clients never notice.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class AnthropicAdapter implements ProviderAdapter {

    private static final String ANTHROPIC_VERSION = "2023-06-01";

    /** OpenAI parameters with no Anthropic equivalent; silently dropping them changes behaviour. */
    private static final List<String> UNSUPPORTED = List.of(
            "logit_bias", "frequency_penalty", "presence_penalty", "seed",
            "logprobs", "top_logprobs", "modalities", "audio", "prediction");

    private final ObjectMapper mapper;

    @Override
    public ProviderType type() {
        return ProviderType.ANTHROPIC;
    }

    // ---------------------------------------------------------------- request

    @Override
    public UpstreamRequest build(ChatRequestSpec spec, ModelRoute route, Provider provider,
                                 String apiKey, boolean stream) {
        ObjectNode body = spec.body();
        validateParameters(body);
        ObjectNode out = mapper.createObjectNode();

        out.put("model", route.getUpstreamModelId());
        out.put("max_tokens", spec.maxTokens() != null ? spec.maxTokens() : 4096);
        out.put("stream", stream);

        copyNumber(body, out, "temperature");
        copyNumber(body, out, "top_p");

        String system = spec.systemText();
        if (!system.isBlank()) {
            out.put("system", system);
        }

        JsonNode user = body.get("user");
        if (user != null && user.isTextual() && !user.asText().isBlank()) {
            out.putObject("metadata").put("user_id", user.asText());
        }

        out.set("messages", convertMessages(body.get("messages")));

        JsonNode stop = body.get("stop");
        if (stop != null) {
            ArrayNode sequences = out.putArray("stop_sequences");
            if (stop.isTextual()) {
                sequences.add(stop.asText());
            } else if (stop.isArray()) {
                stop.forEach(s -> {
                    if (s.isTextual()) {
                        sequences.add(s.asText());
                    }
                });
            }
            if (sequences.isEmpty()) {
                out.remove("stop_sequences");
            }
        }

        boolean toolsWanted = spec.hasTools() && !"none".equals(toolChoiceMode(body.get("tool_choice")));
        if (toolsWanted) {
            ArrayNode tools = out.putArray("tools");
            for (JsonNode tool : body.get("tools")) {
                JsonNode fn = tool.get("function");
                if (fn == null || !fn.hasNonNull("name")) {
                    continue;
                }
                ObjectNode anthropicTool = tools.addObject();
                anthropicTool.put("name", fn.get("name").asText());
                if (fn.hasNonNull("description")) {
                    anthropicTool.put("description", fn.get("description").asText());
                }
                JsonNode params = fn.get("parameters");
                if (params != null && params.isObject()) {
                    anthropicTool.set("input_schema", params);
                } else {
                    ObjectNode schema = anthropicTool.putObject("input_schema");
                    schema.put("type", "object");
                    schema.set("properties", mapper.createObjectNode());
                }
            }
            if (tools.isEmpty()) {
                out.remove("tools");
            } else {
                applyToolChoice(body, out);
            }
        }

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("x-api-key", apiKey);
        headers.put("anthropic-version", ANTHROPIC_VERSION);
        headers.put("Accept", stream ? "text/event-stream" : "application/json");
        return new UpstreamRequest(join(provider.getBaseUrl(), "/messages"), headers, out.toString());
    }

    private static void validateParameters(ObjectNode body) {
        for (String parameter : UNSUPPORTED) {
            if (body.hasNonNull(parameter)) {
                throw unsupported(parameter);
            }
        }
        if (body.hasNonNull("n") && body.get("n").asInt(1) != 1) {
            throw unsupported("n");
        }
        JsonNode format = body.get("response_format");
        if (format != null && !"text".equals(format.path("type").asText("text"))) {
            throw unsupported("response_format");
        }
    }

    private static ApiException unsupported(String parameter) {
        return new ApiException(422, "invalid_request_error", "unsupported_parameter",
                "Parameter '" + parameter + "' is not supported by the anthropic wire format.",
                parameter);
    }

    private static String toolChoiceMode(JsonNode toolChoice) {
        if (toolChoice == null) {
            return "auto";
        }
        if (toolChoice.isTextual()) {
            return toolChoice.asText();
        }
        return toolChoice.path("type").asText("auto");
    }

    private void applyToolChoice(ObjectNode body, ObjectNode out) {
        JsonNode toolChoice = body.get("tool_choice");
        ObjectNode choice = out.putObject("tool_choice");
        String mode = toolChoiceMode(toolChoice);
        switch (mode) {
            case "required", "any" -> choice.put("type", "any");
            case "auto" -> choice.put("type", "auto");
            default -> {
                if (toolChoice != null && toolChoice.isObject() && toolChoice.has("function")) {
                    choice.put("type", "tool");
                    choice.put("name", toolChoice.path("function").path("name").asText());
                } else {
                    choice.put("type", "auto");
                }
            }
        }
        JsonNode parallel = body.get("parallel_tool_calls");
        if (parallel != null && parallel.isBoolean() && !parallel.asBoolean()) {
            choice.put("disable_parallel_tool_use", true);
        }
    }

    private ArrayNode convertMessages(JsonNode messages) {
        ArrayNode result = mapper.createArrayNode();
        // Anthropic wants alternating roles: merge consecutive same-role messages.
        String lastRole = null;
        ArrayNode lastBlocks = null;

        for (JsonNode message : messages) {
            String role = message.path("role").asText();
            if (role.equals("system") || role.equals("developer")) {
                continue;
            }

            ArrayNode blocks = mapper.createArrayNode();
            String targetRole;

            if (role.equals("tool") || role.equals("function")) {
                targetRole = "user";
                ObjectNode block = blocks.addObject();
                block.put("type", "tool_result");
                block.put("tool_use_id", message.path("tool_call_id").asText("unknown"));
                block.set("content", toolResultContent(message.get("content")));
            } else if (role.equals("assistant")) {
                targetRole = "assistant";
                JsonNode content = message.get("content");
                if (content != null && !content.isNull()) {
                    String text = content.isTextual() ? content.asText() : textOfParts(content);
                    if (!text.isEmpty()) {
                        blocks.addObject().put("type", "text").put("text", text);
                    }
                }
                JsonNode toolCalls = message.get("tool_calls");
                if (toolCalls != null && toolCalls.isArray()) {
                    for (JsonNode call : toolCalls) {
                        ObjectNode use = blocks.addObject();
                        use.put("type", "tool_use");
                        use.put("id", call.path("id").asText("call_" + UUID.randomUUID()));
                        use.put("name", call.path("function").path("name").asText());
                        use.set("input", parseArguments(call.path("function").path("arguments")));
                    }
                }
            } else {
                targetRole = "user";
                JsonNode content = message.get("content");
                if (content != null && content.isArray()) {
                    for (JsonNode part : content) {
                        String type = part.path("type").asText("text");
                        if (type.equals("text")) {
                            blocks.addObject().put("type", "text").put("text", part.path("text").asText());
                        } else if (type.equals("image_url")) {
                            ObjectNode image = imageBlock(part.path("image_url").path("url").asText(""));
                            if (image != null) {
                                blocks.add(image);
                            }
                        }
                    }
                } else {
                    String text = content == null || content.isNull() ? "" : content.asText();
                    if (!text.isEmpty()) {
                        blocks.addObject().put("type", "text").put("text", text);
                    }
                }
            }

            if (blocks.isEmpty()) {
                continue;
            }
            if (targetRole.equals(lastRole) && lastBlocks != null) {
                lastBlocks.addAll(blocks);
            } else {
                ObjectNode entry = result.addObject();
                entry.put("role", targetRole);
                entry.set("content", blocks);
                lastRole = targetRole;
                lastBlocks = (ArrayNode) entry.get("content");
            }
        }
        return result;
    }

    private JsonNode toolResultContent(JsonNode content) {
        if (content == null || content.isNull()) {
            return mapper.getNodeFactory().textNode("");
        }
        if (content.isTextual()) {
            return mapper.getNodeFactory().textNode(content.asText());
        }
        ArrayNode parts = mapper.createArrayNode();
        for (JsonNode part : content) {
            if (part.path("type").asText("text").equals("text")) {
                parts.addObject().put("type", "text").put("text", part.path("text").asText());
            }
        }
        return parts;
    }

    private static String textOfParts(JsonNode content) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode part : content) {
            if (part.path("type").asText("text").equals("text")) {
                sb.append(part.path("text").asText());
            }
        }
        return sb.toString();
    }

    private ObjectNode imageBlock(String url) {
        if (url == null || url.isBlank()) {
            return null;
        }
        ObjectNode image = mapper.createObjectNode();
        image.put("type", "image");
        ObjectNode source = image.putObject("source");
        int comma = url.indexOf(',');
        if (url.startsWith("data:") && comma > 0) {
            String meta = url.substring(5, comma);
            String mediaType = meta.split(";")[0];
            source.put("type", "base64");
            source.put("media_type", mediaType.isBlank() ? "image/png" : mediaType);
            source.put("data", url.substring(comma + 1));
        } else {
            source.put("type", "url");
            source.put("url", url);
        }
        return image;
    }

    private JsonNode parseArguments(JsonNode arguments) {
        if (arguments == null || arguments.isNull()) {
            return mapper.createObjectNode();
        }
        if (arguments.isObject()) {
            return arguments;
        }
        String raw = arguments.asText("").trim();
        if (raw.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    // --------------------------------------------------------------- response

    @Override
    public TokenUsage parseUsage(JsonNode upstream) {
        return fromUsageNode(upstream.path("usage"));
    }

    /** Shared by the non-streaming path and the stream's usage events. */
    static TokenUsage fromUsageNode(JsonNode usage) {
        if (usage == null || !usage.isObject()) {
            return TokenUsage.EMPTY;
        }
        Integer input = intOrNull(usage.get("input_tokens"));
        Integer output = intOrNull(usage.get("output_tokens"));
        if (input == null && output == null) {
            return TokenUsage.EMPTY;
        }
        int cacheRead = usage.path("cache_read_input_tokens").asInt(0);
        int cacheWrite = usage.path("cache_creation_input_tokens").asInt(0);
        int prompt = (input == null ? 0 : input) + cacheRead + cacheWrite;
        return new TokenUsage(prompt, output, cacheRead, cacheWrite, 0, 0, 0, false);
    }

    private static Integer intOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asInt() : null;
    }

    @Override
    public JsonNode parseFullResponse(JsonNode upstream, ChatRequestSpec spec, ModelRoute route) {
        ObjectNode out = mapper.createObjectNode();
        String upstreamId = upstream.path("id").asText("msg_" + UUID.randomUUID());
        out.put("id", "chatcmpl-" + upstreamId);
        out.put("object", "chat.completion");
        out.put("created", Instant.now().getEpochSecond());
        out.put("model", spec.publicModel());

        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        JsonNode content = upstream.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText();
                if (type.equals("text")) {
                    text.append(block.path("text").asText());
                } else if (type.equals("tool_use")) {
                    ObjectNode call = toolCalls.addObject();
                    call.put("id", block.path("id").asText());
                    call.put("type", "function");
                    ObjectNode fn = call.putObject("function");
                    fn.put("name", block.path("name").asText());
                    fn.put("arguments", block.path("input").toString());
                }
            }
        }

        ObjectNode message = mapper.createObjectNode();
        message.put("role", "assistant");
        if (text.isEmpty() && !toolCalls.isEmpty()) {
            message.putNull("content");
        } else {
            message.put("content", text.toString());
        }
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }

        ObjectNode choice = mapper.createObjectNode();
        choice.put("index", 0);
        choice.set("message", message);
        choice.put("finish_reason", mapStopReason(upstream.path("stop_reason").asText(null), !toolCalls.isEmpty()));
        out.putArray("choices").add(choice);

        ObjectNode usageOut = out.putObject("usage");
        JsonNode upstreamUsage = upstream.path("usage");
        int inputTokens = upstreamUsage.path("input_tokens").asInt(0);
        int cacheReadTokens = upstreamUsage.path("cache_read_input_tokens").asInt(0);
        int cacheWriteTokens = upstreamUsage.path("cache_creation_input_tokens").asInt(0);
        int promptTokens = inputTokens + cacheReadTokens + cacheWriteTokens;
        int completionTokens = upstreamUsage.path("output_tokens").asInt(0);
        usageOut.put("prompt_tokens", promptTokens);
        usageOut.put("completion_tokens", completionTokens);
        usageOut.put("total_tokens", promptTokens + completionTokens);
        if (cacheReadTokens > 0) {
            usageOut.putObject("prompt_tokens_details").put("cached_tokens", cacheReadTokens);
        }
        if (cacheWriteTokens > 0) {
            usageOut.putObject("cache_creation").put("cache_write_tokens", cacheWriteTokens);
        }
        return out;
    }

    static String mapStopReason(String stopReason, boolean hasToolCalls) {
        if (stopReason == null) {
            return hasToolCalls ? "tool_calls" : "stop";
        }
        return switch (stopReason) {
            case "max_tokens" -> "length";
            case "tool_use" -> "tool_calls";
            case "refusal" -> "content_filter";
            default -> "stop";
        };
    }

    // --------------------------------------------------------------- streaming

    @Override
    public StreamTranslator newStreamTranslator(ChatRequestSpec spec, ModelRoute route) {
        return new Translator(spec, mapper);
    }

    @Override
    public BigDecimal priceInput(ModelRoute route) {
        return route.getInputPricePerMillion() == null ? BigDecimal.ZERO : route.getInputPricePerMillion();
    }

    @Override
    public BigDecimal priceOutput(ModelRoute route) {
        return route.getOutputPricePerMillion() == null ? BigDecimal.ZERO : route.getOutputPricePerMillion();
    }

    private static String join(String base, String path) {
        return base.endsWith("/") ? base.substring(0, base.length() - 1) + path : base + path;
    }

    private static void copyNumber(ObjectNode from, ObjectNode to, String field) {
        JsonNode value = from.get(field);
        if (value != null && value.isNumber()) {
            to.set(field, value);
        }
    }

    private static final class Translator implements StreamTranslator {

        private final ChatRequestSpec spec;
        private final ObjectMapper mapper;
        private final Map<Integer, Integer> blockToToolIndex = new HashMap<>();
        private int nextToolIndex = 0;
        private Integer inputTokens;
        private Integer outputTokens;
        private int cacheReadTokens;
        private int cacheWriteTokens;
        private boolean done;
        private boolean finishSent;
        private String messageId = "msg_" + UUID.randomUUID();
        private final long created = Instant.now().getEpochSecond();

        private Translator(ChatRequestSpec spec, ObjectMapper mapper) {
            this.spec = spec;
            this.mapper = mapper;
        }

        @Override
        public List<JsonNode> translate(String line) {
            if (line == null || line.isBlank() || line.startsWith(":") || line.startsWith("event:")) {
                return List.of();
            }
            if (!line.startsWith("data:")) {
                return List.of();
            }
            String payload = line.substring(5).trim();
            if (payload.isEmpty() || payload.equals("[DONE]")) {
                return List.of();
            }
            JsonNode event;
            try {
                event = mapper.readTree(payload);
            } catch (Exception e) {
                return List.of();
            }

            String type = event.path("type").asText("");
            List<JsonNode> out = new ArrayList<>(1);
            switch (type) {
                case "message_start" -> {
                    messageId = event.path("message").path("id").asText(messageId);
                    readUsage(event.path("message").path("usage"));
                    out.add(chunk(mapper.createObjectNode().put("role", "assistant")
                            .put("content", ""), null));
                }
                case "content_block_start" -> {
                    JsonNode block = event.path("content_block");
                    if ("tool_use".equals(block.path("type").asText())) {
                        int blockIndex = event.path("index").asInt(0);
                        int toolIndex = nextToolIndex++;
                        blockToToolIndex.put(blockIndex, toolIndex);
                        ObjectNode delta = mapper.createObjectNode();
                        ArrayNode calls = delta.putArray("tool_calls");
                        ObjectNode call = calls.addObject();
                        call.put("index", toolIndex);
                        call.put("id", block.path("id").asText());
                        call.put("type", "function");
                        ObjectNode fn = call.putObject("function");
                        fn.put("name", block.path("name").asText());
                        fn.put("arguments", "");
                        out.add(chunk(delta, null));
                    }
                }
                case "content_block_delta" -> {
                    JsonNode delta = event.path("delta");
                    String deltaType = delta.path("type").asText();
                    if (deltaType.equals("text_delta")) {
                        out.add(chunk(mapper.createObjectNode()
                                .put("content", delta.path("text").asText()), null));
                    } else if (deltaType.equals("input_json_delta")) {
                        int blockIndex = event.path("index").asInt(0);
                        Integer toolIndex = blockToToolIndex.get(blockIndex);
                        if (toolIndex == null) {
                            toolIndex = nextToolIndex - 1;
                        }
                        ObjectNode part = mapper.createObjectNode();
                        ArrayNode calls = part.putArray("tool_calls");
                        ObjectNode call = calls.addObject();
                        call.put("index", toolIndex);
                        call.putObject("function")
                                .put("arguments", delta.path("partial_json").asText());
                        out.add(chunk(part, null));
                    }
                }
                case "message_delta" -> {
                    readUsage(event.path("usage"));
                    String stopReason = event.path("delta").path("stop_reason").asText(null);
                    if (stopReason != null) {
                        out.add(chunk(mapper.createObjectNode(),
                                mapStopReason(stopReason, !blockToToolIndex.isEmpty())));
                        finishSent = true;
                    }
                }
                case "message_stop" -> {
                    if (!finishSent) {
                        out.add(chunk(mapper.createObjectNode(),
                                mapStopReason(null, !blockToToolIndex.isEmpty())));
                        finishSent = true;
                    }
                    done = true;
                }
                case "error" -> throw new UpstreamException(502, "upstream_stream_error",
                        event.path("error").path("message").asText("Upstream stream error"));
                default -> {
                    // ping and unknown events are ignored
                }
            }
            return out;
        }

        private void readUsage(JsonNode usage) {
            if (usage == null || !usage.isObject()) {
                return;
            }
            if (usage.hasNonNull("input_tokens")) {
                inputTokens = usage.get("input_tokens").asInt();
            }
            if (usage.hasNonNull("output_tokens")) {
                outputTokens = usage.get("output_tokens").asInt();
            }
            if (usage.hasNonNull("cache_read_input_tokens")) {
                cacheReadTokens = usage.get("cache_read_input_tokens").asInt();
            }
            if (usage.hasNonNull("cache_creation_input_tokens")) {
                cacheWriteTokens = usage.get("cache_creation_input_tokens").asInt();
            }
        }

        private ObjectNode chunk(ObjectNode delta, String finishReason) {
            ObjectNode chunk = mapper.createObjectNode();
            chunk.put("id", "chatcmpl-" + messageId);
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", spec.publicModel());
            ObjectNode choice = chunk.putArray("choices").addObject();
            choice.put("index", 0);
            choice.set("delta", delta);
            if (finishReason == null) {
                choice.putNull("finish_reason");
            } else {
                choice.put("finish_reason", finishReason);
            }
            return chunk;
        }

        @Override
        public boolean done() {
            return done;
        }

        @Override
        public TokenUsage usage() {
            if (inputTokens == null && outputTokens == null) {
                return TokenUsage.EMPTY;
            }
            int prompt = (inputTokens == null ? 0 : inputTokens) + cacheReadTokens + cacheWriteTokens;
            return new TokenUsage(prompt, outputTokens, cacheReadTokens, cacheWriteTokens, 0, 0, 0, false);
        }
    }
}
