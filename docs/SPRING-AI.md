# Use from Spring AI

`path-spring-ai` is a drop-in Spring AI `ChatModel` and `EmbeddingModel` over the gateway's
OpenAI-compatible surface. A Java application that already uses Spring AI changes one bean
and gains Path's routing, fallback, budgets and cache metering without touching its prompts.

The module is a sibling of the gateway in this repository and is the only one that depends
on Spring AI; the gateway never depends on it.

## Dependency

```xml
<dependency>
    <groupId>io.github.an1ndra</groupId>
    <artifactId>path-spring-ai</artifactId>
    <version>0.2.2</version>
</dependency>
```

It targets Spring AI 1.1.x (the line for Spring Boot 3.5). Spring AI 2.x needs Spring Boot 4
and is not supported yet.

## Chat

```java
@Bean
ChatModel chatModel() {
    return PathChatModel.builder()
            .baseUrl("http://path:8080")
            .apiKey(System.getenv("PATH_KEY"))
            .model("gpt-4o-mini")     // used when the Prompt names none
            .idempotency(true)        // send Idempotency-Key so a retry is not charged twice
            .build();
}
```

`ChatClient` works on top of it unchanged:

```java
String answer = ChatClient.builder(chatModel)
        .build()
        .prompt()
        .user("Summarise this incident in one line")
        .call()
        .content();
```

### What is mapped

| Spring AI | Gateway request |
|---|---|
| `SystemMessage`, `UserMessage`, `AssistantMessage` | `messages[]` with the matching role |
| `AssistantMessage.getToolCalls()` | `tool_calls[]` on the assistant turn |
| `ToolResponseMessage` | one `role: "tool"` message per response |
| `ToolCallingChatOptions.getToolCallbacks()` | `tools[].function` (`name`, `description`, `parameters`) |
| `ChatOptions` temperature, maxTokens, topP, penalties, stop | the matching OpenAI fields |

### What comes back

`ChatResponseMetadata` carries `id`, `model` and `Usage`, plus three Path-specific values
copied from the gateway's response headers:

| Metadata key | Header | Meaning |
|---|---|---|
| `path_cache` | `X-Path-Cache` | `hit`, `miss`, `semantic` or `off` |
| `path_provider` | `X-Gateway-Provider` | the provider slug that served the call |
| `path_cost_usd` | `X-Gateway-Cost-Usd` | what the call cost |

`Generation.getMetadata().getFinishReason()` is the upstream finish reason. A gateway error
is thrown as `PathException`, which exposes `status()` and the gateway's `code()` (for example
`unsupported_parameter` with a 422).

## Streaming

`stream(Prompt)` POSTs with `stream: true` and forwards each SSE content delta as a
`ChatResponse` chunk; `ChatClient` aggregates them into the final message. Streamed
tool-call deltas are **not** reassembled: a streaming request that returns tool calls will
show the tool call in the non-streaming `call()` path only. Use `call()` when a conversation
uses tools.

## Embeddings

```java
@Bean
EmbeddingModel embeddingModel() {
    return PathEmbeddingModel.builder()
            .baseUrl("http://path:8080")
            .apiKey(System.getenv("PATH_KEY"))
            .model("text-embedding-3-small")
            .dimensions(1536)
            .build();
}
```

`/v1/embeddings` is served for OpenAI-shaped providers only; anything else fails with the
gateway's own error. `dimensions()` returns the configured value (the gateway does not
report one).

## Building and testing

```bash
./mvnw -pl path-spring-ai -am package   # the adapter and its parent
./mvnw verify                           # both modules
```

The tests run against a stub HTTP server and need no gateway and no provider key.

## Caveats

- Images and other non-text user content are not translated; only text is forwarded.
- There is no automatic retry in the adapter. `idempotency(true)` makes a caller-side retry
  safe; the gateway's own routing/fallback already handles provider failures.
- A `ChatOptions` model id is a Path public model id (a route or an alias such as `cheap`),
  not necessarily an upstream model name.
