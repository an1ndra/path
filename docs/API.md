# API reference

A machine-readable OpenAPI 3 document is served at `/v3/api-docs` (Swagger UI at
`/swagger-ui.html` when `GATEWAY_SWAGGER_UI=true`). Everything below is the same
contract in prose.

Base URL: `http://localhost:8080` (override with `PORT`).

Two auth schemes:

| Surface | Header | Secret |
|---|---|---|
| `/v1/**` | `Authorization: Bearer sk-gw-...` | client key from `/admin/keys` |
| `/admin/**` | `X-Admin-Token: ...` | `GATEWAY_ADMIN_TOKEN` |

All bodies are JSON. Streaming responses are `text/event-stream`.

A ready-to-import Postman collection lives in
[`path.postman_collection.json`](path.postman_collection.json) with
[`path.postman_environment.json`](path.postman_environment.json).

---

## Errors

Every failure uses the OpenAI envelope:

```json
{"error": {"message": "The model 'nope' does not exist.", "type": "invalid_request_error", "code": "model_not_found"}}
```

| Code | HTTP | Meaning |
|---|---|---|
| `missing_api_key` | 401 | no `Authorization` header |
| `invalid_api_key` | 401 | unknown or malformed key |
| `key_disabled` | 401 | key revoked or disabled |
| `invalid_admin_token` | 401 | bad `X-Admin-Token` |
| `forbidden` | 403 | admin API disabled (no token configured) |
| `model_not_found` | 404 | no enabled route for that model id |
| `route_not_found` / `provider_not_found` / `key_not_found` | 404 | unknown admin target |
| `invalid_request` / `invalid_json` | 400 | body validation |
| `insufficient_credits` | 402 | balance cannot cover the call |
| `rate_limit_exceeded` | 429 | per-key RPM limit (has `Retry-After`) |
| `provider_not_configured` | 502 | provider row has no credential |
| `provider_auth_error` | 502 | upstream rejected the gateway's key |
| `upstream_invalid_request` | 400 | upstream rejected the client's body |
| `upstream_timeout` | 504 | connect/read/idle timeout |
| `upstream_error` | 502 | upstream 5xx or unmapped failure |
| `all_providers_failed` | 502 | every route in the chain failed |
| `circuit_open` | 503 | provider parked by the circuit breaker |
| `concurrency_limit_exceeded` | 429 | too many in-flight requests for this key |
| `payload_too_large` | 413 | request body above `GATEWAY_MAX_REQUEST_BYTES` |
| `unsupported_parameter` | 422 | parameter has no equivalent for this provider |
| `idempotency_conflict` | 409 | same `Idempotency-Key`, different body |
| `idempotency_in_progress` | 409 | duplicate while the first attempt is running |
| `shutting_down` | 503 | instance is draining; retry elsewhere |
| `route_exists` / `provider_exists` | 409 | duplicate admin create |

---

## Public API

### `POST /v1/chat/completions`

OpenAI chat-completions body. The gateway adds three optional routing fields:

| Field | Type | Meaning |
|---|---|---|
| `models` | string[] | fallback model ids, tried after `model` |
| `provider.order` | string[] | provider slug preference, e.g. `["groq","openai"]` |
| `provider.allow_fallbacks` | bool | `false` pins the first route; upstream errors surface |

Everything the provider understands is forwarded untouched; `model` is rewritten to
the route's upstream id. Gateway-only fields are stripped before forwarding.

**Non-streaming**

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","messages":[{"role":"user","content":"Say hi"}]}'
```

```json
{
  "id": "chatcmpl-...",
  "object": "chat.completion",
  "created": 1700000000,
  "model": "gpt-4o-mini",
  "choices": [{"index": 0, "message": {"role": "assistant", "content": "Hi"}, "finish_reason": "stop"}],
  "usage": {"prompt_tokens": 11, "completion_tokens": 7, "total_tokens": 18,
            "prompt_tokens_details": {"cached_tokens": 5}}
}
```

Response headers:

| Header | Example | Notes |
|---|---|---|
| `X-Gateway-Provider` | `groq` | provider that actually served it |
| `X-Gateway-Upstream-Model` | `llama-3.3-70b-versatile` | upstream id used |
| `X-Gateway-Cost-Usd` | `0.00000548` | 8-decimal itemised total |
| `X-Gateway-Latency-Ms` | `412` | end-to-end |
| `X-Idempotent-Replay` | `true` | present only when replaying a stored response |
| `X-Path-Cache` | `hit` / `miss` / `off` | response cache outcome for this call |

**Response cache**

Opt-in per route (`cache_enabled`, `cache_ttl_seconds`). A request qualifies when it
sets `temperature: 0` explicitly and uses no tools. A hit replays the stored body
(streams replay as the same SSE sequence), costs nothing, and counts as savings in
`/v1/usage`. Cache keys are scoped to the client key.

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"gpt-4o-mini","temperature":0,"messages":[{"role":"user","content":"Hi"}]}' \
  -D - | grep -i x-path-cache
```

**Idempotency**

Send an `Idempotency-Key` header (up to 255 characters) to make a request safe to retry:

```bash
curl -s http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: 7f2c-orders-2026-11-17-001" \
  -d '{...}'
```

| Situation | Result |
|---|---|
| First request | runs normally, response stored for `GATEWAY_IDEMPOTENCY_TTL` |
| Repeat, same body | stored response replayed, `X-Idempotent-Replay: true`, no upstream call, no charge |
| Repeat, different body | `409 idempotency_conflict` |
| Repeat while first is still running | `409 idempotency_in_progress` |

Streaming responses are captured and replayed as the same SSE sequence. A response
larger than `GATEWAY_IDEMPOTENCY_MAX_BYTES` is not stored; a retry will call the
provider again.

**Streaming**

```bash
curl -N http://localhost:8080/v1/chat/completions \
  -H "Authorization: Bearer $KEY" -H 'Content-Type: application/json' \
  -d '{"model":"claude-3.5-haiku","stream":true,
       "stream_options":{"include_usage":true},
       "messages":[{"role":"user","content":"Say hi"}]}'
```

```
: open

data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"role":"assistant","content":""}}]}
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{"content":"Hi"}}]}
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}
data: {"id":"chatcmpl-...","object":"chat.completion.chunk","choices":[],"usage":{"prompt_tokens":19,"completion_tokens":7,"total_tokens":26}}
data: [DONE]
```

- `stream_options.include_usage` adds the usage-only chunk before `[DONE]`.
- A failure after the first chunk arrives as an error object then `[DONE]`; the HTTP
  status is already 200, so clients must inspect the payload.
- Fallback happens only before the first chunk.

**Tool calls** are passed through for OpenAI-shaped providers and translated to
`tool_use` / `tool_result` blocks for Anthropic.

### `GET /v1/models`

Public when `gateway.public-models=true` (default).

```json
{
  "object": "list",
  "data": [{
    "id": "auto-fast", "object": "model", "created": 1700000000, "owned_by": "openai",
    "context_length": 128000,
    "providers": ["openai", "groq"],
    "pricing": {"prompt_per_million": 0.15, "completion_per_million": 0.60},
    "routing": [
      {"provider": "openai", "upstream_model": "gpt-4o-mini", "priority": 1,
       "input_price_per_million": 0.15, "output_price_per_million": 0.60}
    ]
  }]
}
```

### `GET /v1/models/{id}`

One catalogue entry. Dots and slashes in the id are supported. 404 `model_not_found`
when unknown.

### `GET /v1/usage?window=24h`

Per-key totals for the window (`30s`, `15m`, `24h`, `7d`, `4w` or ISO-8601).

```json
{
  "object": "usage", "window": "PT24H", "requests": 18,
  "prompt_tokens": 213, "completion_tokens": 117, "total_tokens": 330,
  "cached_input_tokens": 69, "cache_hit_ratio": 0.3239,
  "cached_savings_usd": 0.0000195, "reasoning_tokens": 0,
  "cost_usd": 0.000378,
  "cost_breakdown": {"input": 0.0000858, "cached_input": 0.0000192,
                     "cache_write": 0.000012, "output": 0.0002622},
  "credit_limit_usd": null, "used_usd": 0.000378, "remaining_usd": null,
  "recent": [{"id": 42, "at": "...", "model": "gpt-4o-mini", "provider": "openai",
              "cached_input_tokens": 5, "ttft_ms": 12, "tokens_per_second": 61.5,
              "attempts": 1, "usage_estimated": false}]
}
```

### `GET /v1/credits`

```json
{"object":"credits","key_id":1,"key_name":"demo-local","currency":"USD",
 "credit_limit_usd":25.00,"used_usd":0.000378,"remaining_usd":24.999622,"unlimited":false}
```

---

## Admin API

All admin calls need `X-Admin-Token`. When `GATEWAY_ADMIN_TOKEN` is empty the whole
surface returns 403.

### Providers

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/providers` | list with credential source/hint |
| POST | `/admin/providers` | create |
| PATCH | `/admin/providers/{id}` | update |
| DELETE | `/admin/providers/{id}` | delete (cascades routes) |

```json
{
  "slug": "together",
  "display_name": "Together",
  "type": "OPENAI",
  "base_url": "https://api.together.xyz/v1",
  "api_key": "optional; omit to use GATEWAY_UPSTREAM_TOGETHER_KEY",
  "enabled": true
}
```

`type` is `OPENAI` (chat-completions) or `ANTHROPIC` (Messages API). Response adds
`credential_source` (`database` / `config` / `env`), `credential_configured`,
`credential_hint` (last 4 only), `route_count`.

### Routes

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/routes?model=` | list (all enabled, or one public id) |
| POST | `/admin/routes` | create |
| PATCH | `/admin/routes/{id}` | update |
| DELETE | `/admin/routes/{id}` | delete |

```json
{
  "public_model_id": "fast",
  "provider_id": 1,
  "upstream_model_id": "gpt-4o-mini",
  "input_price_per_million": 0.15,
  "cached_input_price_per_million": 0.075,
  "output_price_per_million": 0.60,
  "cache_write_price_per_million": null,
  "priority": 1,
  "context_length": 128000,
  "supports_streaming": true,
  "supports_tools": true,
  "enabled": true,
  "description": "cheap default"
}
```

Lower `priority` runs first. Cached/write prices fall back to the input price when
omitted.

### Client keys

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/keys` | list (masked) |
| POST | `/admin/keys` | create, returns the plaintext key **once** |
| PATCH | `/admin/keys/{id}` | rename, change limits, enable/disable |
| POST | `/admin/keys/{id}/revoke` | disable permanently |
| POST | `/admin/keys/{id}/topup` | `{"amount_usd": 10}` |

```json
{"name": "team-a", "credit_limit_usd": 25, "rpm_limit": 120}
```

```json
{"id": 4, "name": "team-a", "masked_key": "sk-gw-AbCd...Wxyz",
 "key": "sk-gw-...", "warning": "Store this key now. It is not shown again.",
 "credit_limit_usd": 25, "used_usd": 0, "rpm_limit": 120, "enabled": true}
```

### Model sync

`POST /admin/models/sync` — calls each provider's `/models`, filters non-chat ids,
upserts routes at priority 100. Known ids get prices from a built-in table; unknown
ids land at `0.00` and are flagged in `description`.

```json
{"created": 4, "updated": 0, "skipped_providers": ["groq: no credential"], "errors": []}
```

### Reporting

| Method | Path | Purpose |
|---|---|---|
| GET | `/admin/stats?window=24h` | totals, percentiles, cache, providers/models |
| GET | `/admin/usage?window=24h&limit=100&keyId=` | raw usage rows |

```json
{
  "requests": 21, "total_tokens": 384, "cost_usd": 0.000393,
  "latency_ms": {"p50": 2, "p95": 44, "p99": 44},
  "ttft_ms": {"p50": 1, "p95": 5},
  "tokens_per_second": {"p50": 7000.0},
  "cache": {"prompt_tokens": 246, "cached_input_tokens": 84,
            "hit_ratio": 0.3415, "savings_usd": 0.00002061},
  "fallback_requests": 3,
  "estimated_usage_requests": 0,
  "by_provider": {"openai": {"requests": 12, "cost_usd": 0.0002}},
  "by_model": [{"model": "gpt-4o-mini", "requests": 12, "tokens": 180, "cost_usd": 0.0002}]
}
```

---

## Metering fields

Each usage row records:

| Field | Meaning |
|---|---|
| `prompt_tokens` | total prompt, including cache read and cache write |
| `completion_tokens` | output tokens, including reasoning |
| `cached_input_tokens` | subset of prompt served from cache |
| `cache_write_tokens` | Anthropic cache writes (subset of prompt) |
| `reasoning_tokens` | reasoning/thinking tokens |
| `input_cost_usd`, `cached_input_cost_usd`, `cache_write_cost_usd`, `output_cost_usd` | itemised |
| `cost_usd` | total, 8 decimals |
| `cached_savings_usd` | what cached tokens would have cost at the input rate |
| `ttft_ms` | time to first streamed token |
| `tokens_per_second` | generation throughput (post-TTFT window) |
| `upstream_request_id` | provider request id, for support |
| `finish_reason` | `stop`, `length`, `tool_calls`, ... |
| `attempts` | 1 = first route worked; >1 = fallback happened |
| `fallback_from` | provider that failed first |
| `usage_estimated` | counts were inferred, not reported |

Billing rule: `billableInput = prompt_tokens − cached_input_tokens − cache_write_tokens`.
