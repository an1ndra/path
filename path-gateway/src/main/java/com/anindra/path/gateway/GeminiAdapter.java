package com.anindra.path.gateway;

import com.anindra.path.api.ApiException;
import com.anindra.path.domain.ModelRoute;
import com.anindra.path.domain.Provider;
import com.anindra.path.domain.ProviderType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Gemini native {@code generateContent}, for AI Studio and Vertex AI.
 *
 * <p>The OpenAI-compatible Gemini endpoint covers chat and streaming but silently drops the
 * parts enterprises buy Gemini for: {@code thinkingConfig}, {@code safetySettings},
 * {@code systemInstruction} semantics and cached-content pricing. This adapter translates
 * them properly instead, and rejects an OpenAI parameter it cannot honour with 422
 * {@code unsupported_parameter} rather than dropping it.
 *
 * <p>The two surfaces share the wire format; only the URL and auth differ. AI Studio uses
 * {@code x-goog-api-key}; Vertex uses a service-account access token and puts project and
 * location in the path.
 */
public class GeminiAdapter implements ProviderAdapter {

    /**
     * OpenAI parameters with no Gemini equivalent. Dropping them would change behaviour, so
     * they fail loudly, the same contract the Anthropic adapter keeps.
     */
    private static final List<String> UNSUPPORTED = List.of(
            "logit_bias", "frequency_penalty", "presence_penalty", "seed",
            "logprobs", "top_logprobs", "modalities", "audio", "prediction", "user");

    private final ObjectMapper mapper;
    private final ProviderType type;
    private final VertexTokenService vertexTokens;

    public GeminiAdapter(ObjectMapper mapper, ProviderType type, VertexTokenService vertexTokens) {
        this.mapper = mapper;
        this.type = type;
        this.vertexTokens = vertexTokens;
    }

    @Override
    public ProviderType type() {
        return type;
    }

    private boolean vertex() {
        return type == ProviderType.VERTEX;
    }

    // ---------------------------------------------------------------- request

    @Override
    public UpstreamRequest build(ChatRequestSpec spec, ModelRoute route, Provider provider,
                                 String apiKey, boolean stream) {
        JsonNode body = spec.body();
        validate(body);

        ObjectNode out = mapper.createObjectNode();
        String system = spec.systemText();
        if (system != null && !system.isBlank()) {
            out.putObject("systemInstruction").putArray("parts").addObject().put("text", system);
        }
        out.set("contents", contents(body.path("messages")));

        ObjectNode generation = out.putObject("generationConfig");
        copyNumber(body, generation, "temperature", "temperature");
        copyNumber(body, generation, "top_p", "topP");
        copyNumber(body, generation, "top_k", "topK");
        Integer max = spec.maxTokens();
        if (max != null) {
            generation.put("maxOutputTokens", max);
        }
        JsonNode stop = body.get("stop");
        if (stop != null) {
            ArrayNode sequences = generation.putArray("stopSequences");
            if (stop.isTextual()) {
                sequences.add(stop.asText());
            } else if (stop.isArray()) {
                stop.forEach(value -> {
                    if (value.isTextual()) {
                        sequences.add(value.asText());
                    }
                });
            }
        }
        JsonNode responseFormat = body.get("response_format");
        if (responseFormat != null && "json_object".equals(responseFormat.path("type").asText())) {
            generation.put("responseMimeType", "application/json");
        }
        thinkingConfig(body, generation);
        safetySettings(body, out);
        tools(body, out);

        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        String url = url(provider, route.getUpstreamModelId(), stream, apiKey, headers);
        return new UpstreamRequest(url, headers, out.toString());
    }

    private String url(Provider provider, String upstreamModel, boolean stream,
                       String credential, Map<String, String> headers) {
        String method = stream ? "streamGenerateContent?alt=sse" : "generateContent";
        String base = trimSlash(provider.getBaseUrl());
        if (vertex()) {
            String token = vertexTokens.accessToken(provider, credential);
            headers.put("Authorization", "Bearer " + token);
            if (provider.getProject() == null || provider.getLocation() == null) {
                throw new UpstreamException(400, "vertex_project_missing",
                        "A Vertex provider needs a project and a location.");
            }
            return base + "/v1/projects/" + provider.getProject()
                    + "/locations/" + provider.getLocation()
                    + "/publishers/google/models/" + upstreamModel + ":" + method;
        }
        headers.put("x-goog-api-key", credential);
        return base + "/models/" + upstreamModel + ":" + method;
    }

    private void validate(JsonNode body) {
        for (String parameter : UNSUPPORTED) {
            if (body.hasNonNull(parameter)) {
                throw unsupported(parameter);
            }
        }
        if (body.hasNonNull("n") && body.get("n").asInt(1) != 1) {
            throw unsupported("n");
        }
        JsonNode format = body.get("response_format");
        if (format != null && !"text".equals(format.path("type").asText("text"))
                && !"json_object".equals(format.path("type").asText())) {
            throw unsupported("response_format");
        }
    }

    private static ApiException unsupported(String parameter) {
        return new ApiException(422, "invalid_request_error", "unsupported_parameter",
                "Parameter '" + parameter + "' is not supported by the Gemini wire format.",
                parameter);
    }

    /** OpenAI messages to Gemini contents, with tool calls and tool responses. */
    private ArrayNode contents(JsonNode messages) {
        ArrayNode contents = mapper.createArrayNode();
        Map<String, String> toolNamesById = new HashMap<>();
        for (JsonNode message : messages) {
            String role = message.path("role").asText("user");
            if ("system".equals(role)) {
                continue; // spec.systemText() already collected these
            }
            if ("tool".equals(role)) {
                ObjectNode part = mapper.createObjectNode();
                ObjectNode response = part.putObject("functionResponse");
                response.put("name", toolNamesById.getOrDefault(
                        message.path("tool_call_id").asText(), "tool"));
                response.putObject("response").put("result", message.path("content").asText());
                append(contents, "user", part);
                continue;
            }
            if ("assistant".equals(role)) {
                ObjectNode turn = mapper.createObjectNode();
                turn.put("role", "model");
                ArrayNode parts = turn.putArray("parts");
                String text = textOf(message.get("content"));
                if (text != null && !text.isEmpty()) {
                    parts.addObject().put("text", text);
                }
                for (JsonNode call : message.path("tool_calls")) {
                    JsonNode function = call.path("function");
                    toolNamesById.put(call.path("id").asText(), function.path("name").asText());
                    ObjectNode part = parts.addObject();
                    ObjectNode invocation = part.putObject("functionCall");
                    invocation.put("name", function.path("name").asText());
                    invocation.set("args", parseArguments(function.path("arguments").asText()));
                }
                if (!parts.isEmpty()) {
                    contents.add(turn);
                }
                continue;
            }
            ObjectNode part = mapper.createObjectNode();
            part.put("text", String.valueOf(textOf(message.get("content"))));
            append(contents, "user", part);
        }
        return contents;
    }

    private void append(ArrayNode contents, String role, ObjectNode part) {
        if (!contents.isEmpty()
                && role.equals(contents.get(contents.size() - 1).path("role").asText())) {
            ((ArrayNode) contents.get(contents.size() - 1).path("parts")).add(part);
            return;
        }
        ObjectNode turn = contents.addObject();
        turn.put("role", role);
        turn.putArray("parts").add(part);
    }

    /** OpenAI content is a string or an array of text parts; images are not translated. */
    private String textOf(JsonNode content) {
        if (content == null || content.isNull()) {
            return null;
        }
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        if (content.isArray()) {
            for (JsonNode part : content) {
                if ("text".equals(part.path("type").asText())) {
                    text.append(part.path("text").asText());
                } else if (part.hasNonNull("image_url")) {
                    throw new ApiException(422, "invalid_request_error", "unsupported_parameter",
                            "Image content is not translated by the Gemini adapter yet.",
                            "messages");
                }
            }
        }
        return text.toString();
    }

    private JsonNode parseArguments(String arguments) {
        try {
            return mapper.readTree(arguments == null || arguments.isBlank() ? "{}" : arguments);
        } catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private void thinkingConfig(JsonNode body, ObjectNode generation) {
        JsonNode config = body.get("thinkingConfig");
        if (config == null) {
            config = body.get("thinking_config");
        }
        ObjectNode out = generation.putObject("thinkingConfig");
        if (config != null && config.isObject()) {
            Long budget = budget(config.get("thinkingBudget"), config.get("thinking_budget"));
            if (budget != null) {
                out.put("thinkingBudget", budget);
            }
            JsonNode thoughts = config.has("includeThoughts")
                    ? config.get("includeThoughts") : config.get("include_thoughts");
            if (thoughts != null && thoughts.isBoolean()) {
                out.put("includeThoughts", thoughts.asBoolean());
            }
            if (out.isEmpty()) {
                generation.remove("thinkingConfig");
            }
            return;
        }
        // reasoning_effort is the OpenAI-shaped knob; map it to a budget.
        String effort = body.path("reasoning_effort").asText(null);
        if (effort != null) {
            out.put("thinkingBudget", switch (effort) {
                case "minimal" -> 128;
                case "low" -> 512;
                case "medium" -> 2048;
                case "high" -> 8192;
                default -> throw unsupported("reasoning_effort");
            });
            return;
        }
        generation.remove("thinkingConfig");
    }

    private static Long budget(JsonNode camel, JsonNode snake) {
        JsonNode node = camel != null ? camel : snake;
        return node == null || !node.isNumber() ? null : node.asLong();
    }

    private void safetySettings(JsonNode body, ObjectNode out) {
        JsonNode settings = body.has("safetySettings") ? body.get("safetySettings")
                : body.get("safety_settings");
        if (settings == null) {
            return;
        }
        if (!settings.isArray()) {
            throw new ApiException(422, "invalid_request_error", "unsupported_parameter",
                    "safetySettings must be an array of {category, threshold}.", "safetySettings");
        }
        for (JsonNode setting : settings) {
            String category = setting.path("category").asText(null);
            String threshold = setting.path("threshold").asText(null);
            if (category == null || threshold == null) {
                throw new ApiException(422, "invalid_request_error", "unsupported_parameter",
                        "Each safetySettings entry needs a category and a threshold.",
                        "safetySettings");
            }
        }
        out.set("safetySettings", settings);
    }

    private void tools(JsonNode body, ObjectNode out) {
        JsonNode tools = body.get("tools");
        if (tools == null || !tools.isArray() || tools.isEmpty()) {
            return;
        }
        ArrayNode declarations = out.putArray("tools").addObject().putArray("functionDeclarations");
        for (JsonNode tool : tools) {
            JsonNode function = tool.path("function");
            ObjectNode declaration = declarations.addObject();
            declaration.put("name", function.path("name").asText());
            if (function.hasNonNull("description")) {
                declaration.put("description", function.path("description").asText());
            }
            declaration.set("parameters", function.path("parameters").isObject()
                    ? function.get("parameters") : mapper.createObjectNode().put("type", "object"));
        }
    }

    private static void copyNumber(JsonNode from, ObjectNode to, String source, String target) {
        if (from.hasNonNull(source) && from.get(source).isNumber()) {
            to.set(target, from.get(source));
        }
    }

    // --------------------------------------------------------------- response

    @Override
    public JsonNode parseFullResponse(JsonNode upstream, ChatRequestSpec spec, ModelRoute route) {
        ObjectNode out = mapper.createObjectNode();
        out.put("id", "chatcmpl-" + UUID.randomUUID().toString().replace("-", ""));
        out.put("object", "chat.completion");
        out.put("created", Instant.now().getEpochSecond());
        out.put("model", spec.publicModel());

        JsonNode promptFeedback = upstream.path("promptFeedback");
        if (promptFeedback.hasNonNull("blockReason")) {
            throw blocked(promptFeedback.path("blockReason").asText(), promptFeedback.path("safetyRatings"));
        }

        JsonNode candidate = upstream.path("candidates").path(0);
        if (candidate.isMissingNode() || candidate.isNull()) {
            throw new UpstreamException(400, "content_blocked",
                    "Gemini returned no candidate and no content.");
        }
        String finishReason = mapFinish(candidate.path("finishReason").asText(null));
        JsonNode parts = candidate.path("content").path("parts");
        StringBuilder text = new StringBuilder();
        ArrayNode toolCalls = mapper.createArrayNode();
        for (JsonNode part : parts) {
            if (part.path("thought").asBoolean(false)) {
                continue; // reasoning stays in the token counts, not in the answer
            }
            if (part.hasNonNull("text")) {
                text.append(part.get("text").asText());
            }
            if (part.has("functionCall")) {
                JsonNode call = part.get("functionCall");
                ObjectNode entry = toolCalls.addObject();
                entry.put("id", "call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12));
                entry.put("type", "function");
                ObjectNode function = entry.putObject("function");
                function.put("name", call.path("name").asText());
                function.put("arguments", call.path("args").toString());
            }
        }
        if ("content_filter".equals(finishReason) && text.isEmpty() && toolCalls.isEmpty()) {
            throw blocked(finishReason, candidate.path("safetyRatings"));
        }

        ObjectNode choice = out.putArray("choices").addObject();
        choice.put("index", 0);
        ObjectNode message = choice.putObject("message");
        message.put("role", "assistant");
        message.put("content", text.isEmpty() && !toolCalls.isEmpty() ? null : text.toString());
        if (!toolCalls.isEmpty()) {
            message.set("tool_calls", toolCalls);
        }
        choice.put("finish_reason", finishReason);

        out.set("usage", usageNode(upstream.path("usageMetadata"), tokenUsage(upstream)));
        return out;
    }

    private ApiException blocked(String reason, JsonNode ratings) {
        String category = reason;
        for (JsonNode rating : ratings) {
            if (rating.path("blocked").asBoolean(false)
                    || "HIGH".equals(rating.path("probability").asText())) {
                category = rating.path("category").asText(reason);
                break;
            }
        }
        return new ApiException(400, "invalid_request_error", "content_blocked",
                "Gemini blocked this request (" + category + ").", "safetySettings");
    }

    @Override
    public TokenUsage parseUsage(JsonNode upstream) {
        return tokenUsage(upstream);
    }

    /**
     * Gemini counts thinking separately in {@code thoughtsTokenCount}, but bills it as
     * output. Completion therefore includes it; {@code reasoningTokens} keeps the breakdown.
     */
    private static TokenUsage tokenUsage(JsonNode upstream) {
        JsonNode usage = upstream.path("usageMetadata");
        if (!usage.isObject()) {
            return TokenUsage.EMPTY;
        }
        int prompt = usage.path("promptTokenCount").asInt(0);
        int candidates = usage.path("candidatesTokenCount").asInt(0);
        int thoughts = usage.path("thoughtsTokenCount").asInt(0);
        int cached = usage.path("cachedContentTokenCount").asInt(0);
        return new TokenUsage(prompt, candidates + thoughts, cached, 0, thoughts, 0, 0, false);
    }

    private ObjectNode usageNode(JsonNode usage, TokenUsage tokens) {
        ObjectNode out = mapper.createObjectNode();
        if (!tokens.reported()) {
            return out;
        }
        out.put("prompt_tokens", tokens.prompt());
        out.put("completion_tokens", tokens.completion());
        out.put("total_tokens", tokens.total());
        out.putObject("prompt_tokens_details").put("cached_tokens", tokens.cached());
        out.putObject("completion_tokens_details").put("reasoning_tokens", tokens.reasoning());
        return out;
    }

    private static String mapFinish(String finish) {
        if (finish == null) {
            return "stop";
        }
        return switch (finish) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT", "SPII" -> "content_filter";
            default -> "stop";
        };
    }

    @Override
    public StreamTranslator newStreamTranslator(ChatRequestSpec spec, ModelRoute route) {
        return new Translator(spec);
    }

    @Override
    public BigDecimal priceInput(ModelRoute route) {
        return route.getInputPricePerMillion() == null ? BigDecimal.ZERO : route.getInputPricePerMillion();
    }

    @Override
    public BigDecimal priceOutput(ModelRoute route) {
        return route.getOutputPricePerMillion() == null ? BigDecimal.ZERO : route.getOutputPricePerMillion();
    }

    private static String trimSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    /** Gemini SSE events to OpenAI chunks. Thought parts are emitted as content, not hidden. */
    private final class Translator implements StreamTranslator {

        private final ChatRequestSpec spec;
        private final long created = Instant.now().getEpochSecond();
        private TokenUsage usage = TokenUsage.EMPTY;
        private boolean done;
        private boolean finishSent;

        private Translator(ChatRequestSpec spec) {
            this.spec = spec;
        }

        @Override
        public List<JsonNode> translate(String line) {
            if (line == null || line.isBlank() || line.startsWith(":")
                    || !line.startsWith("data:")) {
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
            if (event.path("promptFeedback").hasNonNull("blockReason")) {
                throw blocked(event.path("promptFeedback").path("blockReason").asText(),
                        event.path("promptFeedback").path("safetyRatings"));
            }
            if (event.path("usageMetadata").isObject()) {
                usage = tokenUsage(event);
            }
            JsonNode candidate = event.path("candidates").path(0);
            if (candidate.isMissingNode()) {
                return List.of();
            }
            StringBuilder text = new StringBuilder();
            for (JsonNode part : candidate.path("content").path("parts")) {
                if (part.hasNonNull("text") && !part.path("thought").asBoolean(false)) {
                    text.append(part.get("text").asText());
                }
            }
            String finish = candidate.hasNonNull("finishReason")
                    ? mapFinish(candidate.get("finishReason").asText()) : null;
            List<JsonNode> out = new ArrayList<>(1);
            if (finish != null) {
                out.add(chunk("", finish));
                done = true;
                finishSent = true;
            } else if (!text.isEmpty()) {
                out.add(chunk(text.toString(), null));
            }
            return out;
        }

        private ObjectNode chunk(String content, String finishReason) {
            ObjectNode chunk = mapper.createObjectNode();
            chunk.put("id", "chatcmpl-gemini");
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", created);
            chunk.put("model", spec.publicModel());
            ObjectNode choice = chunk.putArray("choices").addObject();
            choice.put("index", 0);
            ObjectNode delta = choice.putObject("delta");
            if (!content.isEmpty()) {
                delta.put("content", content);
            } else {
                delta.put("role", "assistant");
            }
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
            return finishSent ? usage : TokenUsage.EMPTY;
        }
    }
}
