# Architecture

## Request flow

1. `RequestIdFilter` assigns `X-Request-Id` and puts it in the MDC.
2. `AuthFilter` resolves `Authorization: Bearer sk-gw-...` to a `ClientKey`, applies the
   RPM limiter, or checks `X-Admin-Token` for `/admin/**`.
3. `DrainState` refuses new work while shutting down; `ConcurrencyLimiter` takes a
   per-key in-flight permit.
4. `ChatRequestSpec.parse` validates the body and keeps the original JSON for passthrough.
5. `RouteResolver` turns the public model id (plus `models[]`, `provider.order`, alias and
   weights) into an ordered chain of `(route, provider)`.
6. `CreditGuard` checks allowlist, budgets and the worst-case cost, and clamps `max_tokens`.
7. `ChatService` / `StreamService` walk the chain: one attempt per route, the adapter
   translates, `UpstreamClient` sends raw HTTP. `ProviderCircuitBreaker` skips parked
   providers.
8. `UsageService` prices the call, writes a `UsageRecord`, charges the key and feeds the
   metrics.

## Providers

Two wire formats cover every provider worth proxying:

| Type | Speaks | Works with |
|---|---|---|
| `OPENAI` | chat-completions | OpenAI, Groq, Google Gemini (compatible endpoint), Together, Fireworks, DeepSeek, vLLM, LM Studio, Ollama's `/v1`, any compatible endpoint |
| `ANTHROPIC` | Messages API | Anthropic (native, with prompt caching and tool calls) |

Any other provider is a configuration change, not a code change: register it with
`POST /admin/providers`, give it a base URL and a wire type, then add routes. A new wire
format means implementing `ProviderAdapter` (~200 lines for an OpenAI-shaped API, ~400 for
Anthropic).

## Routing

Routes are rows of `(public_model_id, provider, upstream_model_id, priority, price)`.

`auto-fast` in the seed data has two routes, which gives a cross-provider fallback:

| public id | provider | upstream | priority |
|---|---|---|---|
| auto-fast | openai | gpt-4o-mini | 1 |
| auto-fast | groq | llama-3.3-70b-versatile | 2 |

Request-level controls, OpenRouter style:

```jsonc
{
  "model": "gpt-4o-mini",
  "models": ["gpt-4o-mini", "claude-3.5-haiku"],   // extra fallback models, in order
  "provider": {"order": ["groq", "openai"], "allow_fallbacks": false},
  "messages": [...]
}
```

`provider.allow_fallbacks: false` pins the request to the first route, so an upstream
500 surfaces to the caller instead of being masked.

**Capability aliases** are resolved from the live route table:

| Alias | Resolves to |
|---|---|
| `cheap` | enabled route with the lowest input price |
| `fast` | lowest output price |
| `long-context` | largest declared context window |
| `vision` | first route whose description mentions vision |

They appear in `/v1/models` with a `resolves_to` field.

**Canary / A-B** — each route has a `weight` (default 100). The starting route is chosen
by a stable hash of `(client key, model)` over the weights, so a key keeps landing on the
same variant and the rest of the chain stays as fallback. Set it with
`PATCH /admin/routes/{id} {"weight": 20}`.

## What is metered

Every call records the full token picture both providers report:

| Field | Source (OpenAI / Anthropic) |
|---|---|
| `prompt_tokens` | `usage.prompt_tokens` / `input_tokens` + cache read + cache write |
| `completion_tokens` | `usage.completion_tokens` / `output_tokens` (includes thinking) |
| `cached_input_tokens` | `prompt_tokens_details.cached_tokens` / `cache_read_input_tokens` |
| `cache_write_tokens` | — / `cache_creation_input_tokens` |
| `reasoning_tokens` | `completion_tokens_details.reasoning_tokens` / in `output_tokens` |
| `audio_input/output_tokens` | `*_tokens_details.audio_tokens` / — |

Cost is stored itemised — `input_cost_usd`, `cached_input_cost_usd`,
`cache_write_cost_usd`, `output_cost_usd`, `cost_usd` — plus `cached_savings_usd`.
Cached tokens bill at their own rate when the route has one, otherwise at the plain input
rate; Anthropic cache writes bill at their premium rate.

Also captured: `ttft_ms`, `tokens_per_second`, `upstream_request_id`, `finish_reason`,
`attempts`, `fallback_from`, and `usage_estimated` (true when the provider returned no
usage block and the counts were inferred).

Aggregates: `/v1/usage` adds cache hit ratio and savings for the calling key;
`/admin/stats` adds cache hit ratio, p50/p95 TTFT, median throughput, fallback count and
estimated-usage count.

## Tool calls

OpenAI-shaped providers pass tool calls through untouched. The Anthropic adapter
translates `tool_calls` to `tool_use` blocks and `tool` messages to `tool_result`
blocks, including `tool_choice` and `parallel_tool_calls`. Images are forwarded as
base64 or URL sources.
