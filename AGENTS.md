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
# unit + integration tests (85)
./mvnw verify

# full end-to-end suite against a mock upstream (104 checks, no real keys)
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
│   ├── RateLimitStore            in-memory or Valkey RPM limiter
│   ├── ResponseCacheService      exact-match replay, opt-in per route
│   ├── DrainState                shutdown drain + readiness
│   └── (config) RequestIdFilter  X-Request-Id + MDC correlation
│   ├── RouteResolver             public model id → ordered chain (aliases, weights)
│   ├── ModelAliasService         cheap/fast/long-context/vision
│   ├── EmbeddingsService         /v1/embeddings for OPENAI-type providers
│   └── (admin) AuditService, AlertService, PromptLogService, TenancyService
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
  corrupt fallback accounting. See docs/LIMITS.md.
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

## Schema

`src/main/resources/db/migration/` is the only source of truth. Hibernate validates on
startup; a mismatch fails the boot. Tests run the same migrations against in-memory H2.

## Documentation map

`README.md` is the landing page. Details live in:

| File | Contents |
|---|---|
| `docs/API.md` | endpoints, errors, idempotency, cache, embeddings |
| `docs/ARCHITECTURE.md` | request flow, routing, metering fields |
| `docs/CONFIGURATION.md` | every environment variable and profile |
| `docs/OPERATIONS.md` | admin API, metrics, migrations, alerts |
| `docs/LIMITS.md` | design notes and known gaps |
| `docs/TESTING.md` | test suites and the mock upstream |
| `docs/ROADMAP.md` | delivered work and the open stories (#20, #33, #37) |

Keep README short: a feature can be one bullet there and a section in the matching doc.

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
- `ResponseCacheEligibilityTest` / `ResponseCacheServiceTest` — cache rules, scoping, expiry.
- `CreditGuardTest` — budget windows and max_tokens clamping.
- `DrainStateTest` — in-flight accounting and refusal while draining.
- `tools/smoke_test.py` — 104 checks against `tools/mock-upstream.py`. The mock can
  simulate failures by model id: `boom` (500), `ratelimit` (429), `unauthorized` (401),
  `badrequest` (400), `slow`, `empty`.

Any behaviour change needs a unit test **and**, if it touches routing/streaming/billing,
a smoke check. Never claim a fix without running both.

## Branches and CI

- `Develop` is the default branch; direct pushes are allowed.
- `main` is release-only: PR required, `Build and unit tests` + `End-to-end smoke test`
  must pass, no bypassing (admins included), no force-push, no deletion.
- Release: move `CHANGELOG.md` entries from `Unreleased` into a `## [x.y.z] - date`
  section, commit, then push a `v*.*.*` tag → jar + SBOM + checksums + multi-arch
  `ghcr.io/an1ndra/path`. The workflow uses that changelog section as the release body
  and fails the release if the section is missing or empty.

## Gotchas

- **Flyway owns the schema; `ddl-auto` is `validate`.** Add `V<n>__name.sql` for every
  entity change and never edit an applied migration. `V1__baseline.sql` is written in
  portable SQL: `bigint generated by default as identity`, `timestamp(6) with time zone`,
  enums as `varchar` + check constraint (native enum types would not port between H2 and
  Postgres). Existing pre-Flyway databases baseline at V1 automatically.
- **The circuit breaker is in-memory per node.** Like the rate limiter, state is not
  shared between instances; a restart clears it. That is deliberate for v1.
- **Idempotency claims are written before the upstream call.** If the process dies
  mid-request the row stays PENDING until `GATEWAY_IDEMPOTENCY_TTL`, so retries get
  409 `idempotency_in_progress`. The purge job only removes expired rows.
- **Spend windows use stored usage, not a counter.** `daily_limit_usd` and
  `monthly_limit_usd` compare against `sum(cost_usd)` since UTC midnight / month start,
  so they survive restarts and stay correct across instances.
- **Shutdown drains for `GATEWAY_DRAIN_TIMEOUT`.** `DrainState` flips readiness and
  refuses new chat requests while streams finish; keep the value below your orchestrator's
  termination grace period.
- **System-only conversations are rejected.** Anthropic needs a non-empty message list;
  `ChatRequestSpec.parse` returns 400 instead of forwarding an empty array. The property
  test found this; keep the guard.
- **`tools/load-test.js`** is a k6 script, not part of CI (it needs a running gateway and
  a real key).
- **`allowed_models` is enforced in `CreditGuard`**, before any provider call. Globs end
  with `*`; blank means every model. Aliases are checked by their alias name, so add the
  alias to the allowlist too if a key should use `cheap`.
- **Prompt logging is a PII store.** Off by default; when enabled it redacts provider
  keys and bearer tokens, but it still stores user prompts. Retention is enforced by a
  scheduled purge.
- **Audit and alert writes must never break the operation.** Both swallow their own
  failures and log a warning.
- **Aliases are computed from the route table on every resolve**, so editing a price can
  change what `cheap` means. There is no alias table to drift out of sync.
- **Canary weights must stay deterministic per key.** `RouteResolver.weighted` hashes
  `keyId` and the model; changing that hash reshuffles every client's assignment.
- **Tracing is off by default** (`GATEWAY_TRACE_SAMPLE=0`). Raising the probability
  without setting `OTEL_EXPORTER_OTLP_ENDPOINT` produces export warnings, not traces.
- **The `prod` profile switches logs to ECS JSON.** `requestId` comes from
  `RequestIdFilter`'s MDC entry; `traceId`/`spanId` are added by the tracing bridge.
- **RPM limiting defaults to memory; set `GATEWAY_RATE_LIMIT_BACKEND=valkey` in a
  cluster.** Budget reservations are database-backed either way, so money is safe
  without Valkey.
- **Admin tokens are scoped.** The bootstrap env token is read+write; DB tokens are
  read-only unless `scopes` contains `write`.
- **Weighting is opt-in.** A chain of default-weight (100) routes keeps priority order;
  canary shuffling only happens once a weight differs from 100. Otherwise fallback would
  become a lottery.
- **Rate limiting is in-memory** by default, one node. Running two instances doubles the effective
  RPM limit.
- **H2 rejects `AUTO_SERVER=TRUE` together with `DB_CLOSE_ON_EXIT`.** Don't re-add it.
- **Admin credentials are resolved DB → config → env.** A key stored via the admin API
  silently wins over `OPENAI_API_KEY`.
- **Anthropic rejects parameters it has no equivalent for** with 422
  `unsupported_parameter` (`seed`, `logit_bias`, penalties, `logprobs`, `n > 1`,
  non-text `response_format`). Do not "fix" a failing call by dropping the field; the
  divergence is what the 422 exists to prevent. `user` maps to `metadata.user_id`.
- **The response cache only serves explicit `temperature: 0`, tool-free requests on a
  route with `cache_enabled`.** Entries are keyed per client key so tenants cannot read
  each other's completions. Do not relax the temperature rule: a cached sample at
  temperature 1 is a correctness bug.
- **Token estimates use jtokkit** when the upstream model has a known encoding, and fall
  back to `chars / 4`. The estimate only pre-authorises spend; billing always uses the
  provider-reported usage.
- **`usage_estimated=true`** means the provider returned no `usage` block and the counts
  were inferred from text length. Never present those as observed.
- **The demo key is unlimited** and is created when the key table is empty. Set
  `GATEWAY_BOOTSTRAP_DEMO_KEY=false` anywhere real.
- **`tools/smoke_test.py` needs a clean DB** for the demo-key path; otherwise it creates
  its own key through the admin API.

## Security gates

`main` requires the CI, CodeQL and Security checks. `.gitleaks.toml` allowlists the
placeholder credentials in tests and docs — do not broaden it to hide a real key.
Release artifacts carry `checksums.txt` and the wrapper pins the Maven distribution
SHA-256.

## Secrets

Never commit `.env`, `data/`, `*.mvdb`, master keys, or provider credentials. Stored
credentials are AES-GCM encrypted; `data/master.key` decrypts them and must be backed
up, never shared.
