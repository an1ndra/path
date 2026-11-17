# Why Path

Path is for teams that run Java and want their LLM traffic to stay inside their own
JVM, database and process — not behind a Python-only sidecar or a vendor's SaaS.

## Where Path is the right tool

- **One process.** A Spring Boot jar, Flyway migrations and an embedded H2 (or your
  Postgres). No Python runtime, no Node gateway, no sidecar to operate.
- **Deterministic routing you can defend.** Price and priority decide where a request
  goes; a customer dispute can be answered with "OpenAI returned 500, Groq was next".
- **Billing that accounts for caches.** Prompt, completion, cached and cache-write tokens
  are priced separately, so a cache-heavy workload is not overbilled 10×.
- **Self-hosted by construction.** Provider keys are AES-GCM encrypted at rest, admin
  tokens are scoped and audited, and nothing leaves your network except the upstream call.
- **Boring operations.** Readiness gates on seeding, shutdown drains long streams,
  per-provider circuit breakers, Prometheus, OpenTelemetry and an OpenAPI spec.

## Where Path is weaker

Be honest about this before choosing it.

| | Path | LiteLLM | Portkey | OpenRouter |
|---|---|---|---|---|
| Runtime | Java 21 / Spring Boot | Python | Node/Go, hosted | hosted SaaS |
| Model coverage | 2 wire formats, 4 seeded providers | 100+ providers | 250+ models | 300+ models |
| Hosted option | none (self-host only) | community | yes | yes |
| SDKs | any OpenAI SDK | Python SDK | JS/Python SDKs | JS/Python SDKs |
| Guardrails / PII | not built | plugins | built-in | built-in |
| Semantic cache | no (exact match only) | plugins | yes | no |
| Dashboard | JSON APIs + Postman/Bruno | UI | full UI | full UI |
| Licence | GPL-3.0 | MIT | Apache-2.0 core | proprietary |

Model count is the biggest gap. If you need Gemini's native protocol, Bedrock signing,
or a hundred long-tail providers on day one, a larger project covers more ground.

Path's answer is not "more providers". It is "providers you already use, routed and
billed precisely, in the runtime your team already operates".

## A 60-second demo

No API keys needed — the demo talks to a mock upstream, so it shows the mechanics
(routing, streaming, cache, metering) without spending anything.

```bash
bash tools/demo.sh
```

What it prints:

1. the seeded catalogue (`/v1/models`), including capability aliases
2. a non-streaming chat through `auto-fast`, with provider and cost headers
3. a streaming chat, showing SSE chunks and `[DONE]`
4. the same deterministic request twice, showing `X-Path-Cache: hit` and zero cost
5. `/v1/usage` for the key: tokens, cache savings, recent calls

To point it at real providers instead, see the Quick start in the [README](../README.md)
and set `OPENAI_API_KEY`, `ANTHROPIC_API_KEY`, `GROQ_API_KEY` or `GEMINI_API_KEY`.
