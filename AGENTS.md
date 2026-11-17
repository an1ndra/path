# AGENTS.md — Path

Working notes for agents and contributors. Read this before changing code; the
gotchas at the bottom each cost real debugging time.

## What this is

A provider-agnostic LLM gateway. One HTTP surface in front of OpenAI, Anthropic,
Groq and any OpenAI-compatible endpoint, with model routing, automatic fallback,
per-key credits, rate limits and cache-aware token metering.

The client-facing contract is the OpenAI chat-completions shape because that is what
SDKs and tools already implement. That is a compatibility decision, **not** a vendor
dependency: the gateway translates to each provider's native protocol.

## TL;DR

```bash
# unit + integration tests (40)
./mvnw verify

# full end-to-end suite against a mock upstream (74 checks, no real keys)
python3 tools/mock-upstream.py 9099 &
GATEWAY_ADMIN_TOKEN=test-admin java -jar target/path-*.jar &
python3 tools/smoke_test.py

# run locally
export OPENAI_API_KEY=... ANTHROPIC_API_KEY=... GATEWAY_ADMIN_TOKEN=...
./mvnw spring-boot:run
```

The seeder prints a demo `sk-gw-...` key on the first boot only.

## Layout

```
com.anindra.path
├── api/          ChatCompletionsController (public surface), errors, Durations
├── admin/        AdminController + services (providers, routes, keys, sync, stats)
├── config/       GatewayProperties, AuthFilter, CORS
├── domain/       JPA entities: Provider, ModelRoute, ClientKey, UsageRecord
├── gateway/      the engine: adapters, routing, streaming, metering
│   ├── ProviderAdapter           wire-format contract
│   ├── OpenAiCompatibleAdapter   OpenAI + Groq + vLLM + Together + ...
│   ├── AnthropicAdapter          Messages API, tools, cache tokens
│   ├── ChatService               non-streaming path with fallback
│   ├── StreamService             SSE path; owns commit semantics
│   ├── ProviderCircuitBreaker    per-provider OPEN/HALF_OPEN/CLOSED, Retry-After
│   ├── IdempotencyService        Idempotency-Key claims and replay
│   ├── ConcurrencyLimiter        per-key in-flight permits
│   ├── RouteResolver             public model id → ordered chain
│   ├── CreditGuard               pre-flight balance/worst-case check
│   ├── UsageService              cost breakdown, aggregates, metering
│   └── TokenUsage                normalised token accounting
├── repo/         Spring Data repositories
├── secret/       AES-GCM for stored provider credentials
└── seed/         first-boot bootstrap
```

## Request flow

1. `AuthFilter` resolves `Authorization: Bearer sk-gw-...` → `ClientKey`, applies the
   RPM limiter, or checks `X-Admin-Token` for `/admin/**`.
2. `ChatRequestSpec.parse` validates the body and keeps the original JSON for
   passthrough.
3. `RouteResolver` turns the public model id (plus `models[]` and `provider.order`)
   into an ordered chain of `(route, provider)`.
4. `CreditGuard` refuses the call if the balance cannot cover a worst-case estimate.
5. `ChatService` / `StreamService` walk the chain: one attempt per route, adapter
   translates, `UpstreamClient` sends raw HTTP.
6. `UsageService` prices the call, writes a `UsageRecord`, charges the key.

## Conventions

- **No AI SDKs.** The gateway speaks HTTP + SSE with Jackson. SDKs own the request
  model and drop unknown fields, which breaks passthrough; they also add retries that
  corrupt fallback accounting. See the README design notes.
- **`promptTokens` means total prompt**, including cache reads and cache writes:
  `billableInput = prompt - cached - cacheWrite`. OpenAI reports cache as a subset;
  the Anthropic adapter sums it. Keep this invariant when adding providers.
- **Cost is 8 decimals**, each component rounded before summing. Do not "fix" the
  smoke test's `1e-8` tolerance by widening it further.
- **Admin JSON is snake_case**, with camelCase accepted via `@JsonAlias` on the input
  records. Responses are always snake_case maps.
- **Errors use the OpenAI envelope**: `{"error":{"message","type","code","param"}}`.
- **Never log a credential.** Admin views show `credential_hint` (last 4) only.
- **Streaming commits on the first byte.** Fallback is possible only before that;
  afterwards the client gets an SSE error object and `[DONE]`.

## Where to add things

| Task | Where |
|---|---|
| New provider (OpenAI-shaped or Anthropic-shaped) | No code: `POST /admin/providers` + routes, or `GATEWAY_UPSTREAM_<SLUG>_KEY` |
| New wire format | Implement `ProviderAdapter` + `StreamTranslator`, add tests |
| New public endpoint | `api/`, reuse `ApiException` + the error envelope |
| New metered field | `TokenUsage` → both adapters → `UsageRecord` → `UsageService` → `/v1/usage` and `/admin/usage` |
| Admin action | `AdminService` + a thin `AdminController` method |

## Testing

- `GatewayApiTest` — boots the full app on in-memory H2: auth, catalogue, credits,
  admin token, snake_case inputs.
- `AnthropicAdapterTest` — request translation (system, images, tools, tool results),
  response translation, stream events, cache tokens.
- `UsageCostTest` — cost breakdown, cache pricing, missing prices, usage parsing.
- `ProviderCircuitBreakerTest` — threshold, half-open probe, rate-limit parking.
- `IdempotencyServiceTest` — replay, conflicts, concurrent claims, key scoping.
- `ConcurrencyLimiterTest` — per-key permits, no over-release.
- `TokenEstimateTest` — real tokenization with a heuristic fallback.
- `tools/smoke_test.py` — 74 checks against `tools/mock-upstream.py`. The mock can
  simulate failures by model id: `boom` (500), `ratelimit` (429), `unauthorized` (401),
  `badrequest` (400), `slow`, `empty`.

Any behaviour change needs a unit test **and**, if it touches routing/streaming/billing,
a smoke check. Never claim a fix without running both.

## Branches and CI

- `Develop` is the default branch; direct pushes are allowed.
- `main` is release-only: PR required, `Build and unit tests` + `End-to-end smoke test`
  must pass, no bypassing (admins included), no force-push, no deletion.
- Release: push a `v*.*.*` tag → jar + multi-arch `ghcr.io/an1ndra/path` + release notes.

## Gotchas

- **`ddl-auto: update`** adds columns but cannot rename or drop them cleanly. Flyway is
  still the missing piece before any real deployment.
- **The circuit breaker is in-memory per node.** Like the rate limiter, state is not
  shared between instances; a restart clears it. That is deliberate for v1.
- **Idempotency claims are written before the upstream call.** If the process dies
  mid-request the row stays PENDING until `GATEWAY_IDEMPOTENCY_TTL`, so retries get
  409 `idempotency_in_progress`. The purge job only removes expired rows.
- **Rate limiting is in-memory**, one node. Running two instances doubles the effective
  RPM limit.
- **H2 rejects `AUTO_SERVER=TRUE` together with `DB_CLOSE_ON_EXIT`.** Don't re-add it.
- **Admin credentials are resolved DB → config → env.** A key stored via the admin API
  silently wins over `OPENAI_API_KEY`.
- **Anthropic rejects parameters it has no equivalent for** with 422
  `unsupported_parameter` (`seed`, `logit_bias`, penalties, `logprobs`, `n > 1`,
  non-text `response_format`). Do not "fix" a failing call by dropping the field; the
  divergence is what the 422 exists to prevent. `user` maps to `metadata.user_id`.
- **Token estimates use jtokkit** when the upstream model has a known encoding, and fall
  back to `chars / 4`. The estimate only pre-authorises spend; billing always uses the
  provider-reported usage.
- **`usage_estimated=true`** means the provider returned no `usage` block and the counts
  were inferred from text length. Never present those as observed.
- **The demo key is unlimited** and is created when the key table is empty. Set
  `GATEWAY_BOOTSTRAP_DEMO_KEY=false` anywhere real.
- **`tools/smoke_test.py` needs a clean DB** for the demo-key path; otherwise it creates
  its own key through the admin API.

## Secrets

Never commit `.env`, `data/`, `*.mvdb`, master keys, or provider credentials. Stored
credentials are AES-GCM encrypted; `data/master.key` decrypts them and must be backed
up, never shared.
