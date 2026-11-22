# Configuration

Every setting is an environment variable with a working default; nothing is required
except the provider credentials you actually use.

## Server

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `GATEWAY_ADMIN_TOKEN` | empty (admin off) | admin shared secret |
| `GATEWAY_PUBLIC_MODELS` | `false` | allow keyless `/v1/models` (it names every model and alias) |
| `GATEWAY_CLIENT_IP_HEADER` | empty | header holding the real client address, e.g. `CF-Connecting-IP`; empty ignores `X-Forwarded-For` |
| `GATEWAY_CORS_ORIGINS` | `*` | comma-separated origin patterns |
| `GATEWAY_MAX_REQUEST_BYTES` | `1048576` | request bodies larger than this get 413 |
| `GATEWAY_DRAIN_TIMEOUT` | `30s` | how long shutdown waits for in-flight streams |

## Keys and budgets

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_BOOTSTRAP_DEMO_KEY` | `false` | create a demo key when the table is empty (ignored in `prod`) |
| `GATEWAY_SEED_DEFAULTS` | `false` | seed the example provider/route catalogue into an empty database (demo instances only) |
| `GATEWAY_DEFAULT_RPM` | `120` | default requests/minute for new keys |
| `GATEWAY_DEFAULT_CREDIT_USD` | unlimited | default credit limit for new keys |
| `GATEWAY_DEFAULT_MAX_CONCURRENT` | `8` | in-flight requests per key (0 = unlimited) |
| `GATEWAY_MAX_OUTPUT_TOKENS` | `0` (off) | hard cap on `max_tokens` for every request |
| `GATEWAY_USAGE_RETENTION` | `180d` | usage rows older than this are deleted nightly (0 keeps them) |

## Upstream behaviour

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_BREAKER_FAILURES` | `5` | consecutive failures before a provider is parked |
| `GATEWAY_BREAKER_OPEN` | `30s` | how long a parked provider is skipped |
| `GATEWAY_MAX_ATTEMPTS` | `3` | routes tried per request, including the first |
| `GATEWAY_IDEMPOTENCY_TTL` | `24h` | how long a completed response stays replayable |
| `GATEWAY_IDEMPOTENCY_MAX_BYTES` | `2097152` | larger responses are not stored for replay |
| `GATEWAY_IDEMPOTENCY_PENDING` | `2m` | abandoned PENDING claims are reclaimed after this |
| `GATEWAY_CACHE_MAX_BYTES` | `2097152` | larger responses are not cached |

Timeouts live in `application.yml` under `gateway.*`: `connect-timeout` (10s),
`request-timeout` (180s), `stream-idle-timeout` (120s). A provider row can override the
last two with `request_timeout_ms` and `stream_idle_timeout_ms`.

## Observability

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_TRACE_SAMPLE` | `0` | trace sampling probability (0 disables export) |
| `GATEWAY_SWAGGER_UI` | `false` | serve Swagger UI at `/swagger-ui.html` |
| `GATEWAY_ALERT_WEBHOOK` | empty | Slack-compatible webhook for alerts |
| `GATEWAY_ALERT_COOLDOWN` | `1h` | minimum gap between alerts of the same event |
| `GATEWAY_ALERT_BUDGET_THRESHOLD` | `0.8` | alert when a key has less than this fraction left |
| `GATEWAY_SHARED_STATE_BACKEND` | `memory` | `valkey` shares circuit-breaker and in-flight state across replicas |
| `GATEWAY_SHARED_STATE_PREFIX` | `path` | key prefix for that state, same convention as the rate limiter |
| `GATEWAY_CONCURRENCY_LEASE` | `300s` | how long a Valkey in-flight permit survives a dead pod; must exceed the slowest request |
| `GATEWAY_BREAKER_PROBE_LEASE` | `10s` | how long the single HALF_OPEN probe may hold its claim |
| `GATEWAY_ALERT_ERROR_RATE` | `0.25` | provider EWMA error rate above which `provider.error_rate_high` fires; `0` disables the check |
| `GATEWAY_ALERT_MIN_SAMPLES` | `20` | samples a provider needs before its error rate is trusted (a cold start is not an incident) |
| `GATEWAY_SEMANTIC_MODEL` | blank (off) | public model used to embed prompts for the semantic cache |
| `GATEWAY_SEMANTIC_MAX_ENTRIES` | `200` | candidates compared per key and model |
| `GATEWAY_ALERT_EMAIL_TO` (or the console) | blank (off) | comma-separated recipients for alert email |
| `GATEWAY_ALERT_EMAIL_FROM` | `path-gateway@localhost` | From address |
| `GATEWAY_DIGEST` | `true` | send one daily summary email (needs the alert email channel; off without it) |
| `GATEWAY_DIGEST_CRON` | `0 0 8 * * *` | when to send it, in the gateway's time zone |
| `SPRING_MAIL_HOST` / `_PORT` / `_USERNAME` / `_PASSWORD` | — | SMTP server; without `SPRING_MAIL_HOST` email alerting stays off |
| `GATEWAY_GUARDRAILS_LOG_REDACTION` | `true` | redact PII before a prompt is written to the prompt log |
| `GATEWAY_GUARDRAILS_REDACT` | `false` | redact the prompt the provider receives |
| `GATEWAY_GUARDRAILS_MODERATION` | `false` | pre-flight moderation call (OpenAI-shaped providers) |
| `GATEWAY_GUARDRAILS_MODERATION_MODEL` | `omni-moderation-latest` | model used for that call |
| `GATEWAY_ADMIN_AUTH_FAILURES` | `10` | failed admin auth attempts per address before 429s |
| `GATEWAY_ADMIN_AUTH_GLOBAL_FAILURES` | `0` (off) | failures across all addresses in the window; only for an origin that cannot be reached except through a trusted proxy |
| `GATEWAY_ADMIN_AUTH_WINDOW` | `5m` | window for those failures |
| `GATEWAY_CLIENT_AUTH_FAILURES` | `60` | rejected API keys per address per window before 429s (stops the key-validity oracle being usable as a guessing channel) |
| `GATEWAY_CLIENT_AUTH_WINDOW` | `1m` | window for those rejections |
| `GATEWAY_CLIENT_AUTH_GLOBAL_FAILURES` | `0` (off) | rejections across all addresses in the window |
| `GATEWAY_API_DOCS` | `true` (false in `prod`) | serve `/v3/api-docs` |
| `GATEWAY_PROMPT_LOGGING` | `false` | store redacted request/response bodies |
| `GATEWAY_PROMPT_LOG_RETENTION` | `7d` | how long prompt logs are kept |
| `GATEWAY_PROMPT_LOG_MAX_BYTES` | `65536` | per-body cap for prompt logs |

## Rate limiting

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_RATE_LIMIT_BACKEND` | `memory` | `memory` (one node) or `valkey` (shared) |
| `GATEWAY_RATE_LIMIT_PREFIX` | `path` | key prefix for Valkey counters |
| `SPRING_DATA_REDIS_HOST` / `_PORT` | `localhost` / `6379` | Valkey endpoint |

Valkey speaks the Redis protocol, so the Spring Data Redis client works unchanged.
Point it at a Valkey instance and set `GATEWAY_RATE_LIMIT_BACKEND=valkey` to make the RPM
limit exact across instances. Budget reservations are enforced in the database either
way, so they are correct in a cluster regardless of this setting.

## Provider credentials

| Variable | Meaning |
|---|---|
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GROQ_API_KEY` / `GEMINI_API_KEY` | credentials for the providers with those slugs |
| `GATEWAY_UPSTREAM_<SLUG>_KEY` | credential for any other provider slug (e.g. `GATEWAY_UPSTREAM_TOGETHER_KEY`) |

Resolution order: **database → `application.yml` → environment**. A key stored through
the admin API silently wins over the environment, which makes rotation possible without
a restart. Credentials in the database are AES-GCM encrypted with a key from
`GATEWAY_MASTER_KEY` or the generated `./data/master.key` (0600).

A provider row must exist before its environment variable is read. Nothing is seeded by
default any more, so create the provider once (console or `POST /admin/providers`) and the
well-known variable for that slug takes over on the next boot. The four conventional slugs
are `openai`, `anthropic`, `groq` and `gemini`; anything else uses
`GATEWAY_UPSTREAM_<SLUG>_KEY`.

To see the example catalogue on a throwaway instance, set `GATEWAY_SEED_DEFAULTS=true`.
It only fills an empty catalogue, and a provider deleted later stays deleted.

### Common upstreams

Ready-made values for the providers most people add first. Everything here is an
`OPENAI`-type row except Gemini native and Vertex:

| Provider | Slug | Type | Base URL |
|---|---|---|---|
| ChatGPT / OpenAI | `openai` | `OPENAI` | `https://api.openai.com/v1` |
| Gemini (OpenAI-compatible) | `gemini` | `OPENAI` | `https://generativelanguage.googleapis.com/v1beta/openai` |
| Gemini native (AI Studio) | `gemini-native` | `GEMINI` | `https://generativelanguage.googleapis.com/v1beta` |
| OpenCode | `opencode` | `OPENAI` | `https://opencode.ai/inference/openai/v1` |
| OpenCode Go | `opencode-go` | `OPENAI` | `https://opencode.ai/inference/go/openai/v1` |
| Anthropic | `anthropic` | `ANTHROPIC` | `https://api.anthropic.com/v1` |
| Groq | `groq` | `OPENAI` | `https://api.groq.com/openai/v1` |

For a slug not in this table the credential variable is `GATEWAY_UPSTREAM_<SLUG>_KEY`
(OpenCode uses `OPENCODE_API_KEY` by convention, so set
`GATEWAY_UPSTREAM_OPENCODE_KEY` or paste the key into the console).

Each route needs the upstream model id and a price per million tokens; a `0.00` price
records usage but never charges it. `POST /admin/models/sync` imports a provider's list
and prices only ids in its built-in table, so check anything it leaves at `0.00`.

**OpenCode Go** requires two things no OpenAI SDK sends: a client that names itself in
`User-Agent` and a stable `x-opencode-session` per conversation, otherwise it answers 400
`upstream_invalid_request`. The gateway sends `User-Agent: path-gateway` and derives the
session id from the model plus the first user message, so the same conversation keeps one
id across retries and fallbacks. Point the provider at
`https://opencode.ai/inference/go/openai/v1`.

### Gemini

Google exposes an OpenAI-compatible endpoint, so a plain `OPENAI`-type provider works:

```
base_url = https://generativelanguage.googleapis.com/v1beta/openai
```

Create that provider (console → Providers → Add, or `POST /admin/providers`), set
`GEMINI_API_KEY`, and add a route per model you want to expose; the upstream id is
whatever Gemini calls it (`gemini-2.5-flash`, `gemini-3-flash`, …):

```bash
A='X-Admin-Token: ...'
curl -s -X POST localhost:8080/admin/routes -H "$A" -H 'Content-Type: application/json' \
  -d '{"public_model_id":"gemini-2.5-flash","provider_id":<gemini-provider-id>,
       "upstream_model_id":"gemini-2.5-flash",
       "input_price_per_million":0.30,"output_price_per_million":2.50,
       "priority":10}'
```

`POST /admin/models/sync` imports Gemini's list (image and embedding ids are filtered
out) but leaves unknown prices at 0.00, so set them from Google's pricing page before
sending traffic.

### Gemini native (AI Studio and Vertex)

The `OPENAI`-type row above uses Google's compatible endpoint. A `GEMINI` or `VERTEX`
provider speaks native `generateContent` instead, which is what carries `thinkingConfig`,
`safetySettings` and cached-content accounting:

```bash
# AI Studio: an API key, like any other provider
curl -s -X POST localhost:8080/admin/providers -H "$A" -H 'Content-Type: application/json' \
  -d '{"slug":"gemini-native","type":"GEMINI",
       "base_url":"https://generativelanguage.googleapis.com/v1beta","api_key":"AIza..."}'

# Vertex: a service-account JSON as the credential, plus project and location
curl -s -X POST localhost:8080/admin/providers -H "$A" -H 'Content-Type: application/json' \
  -d "{\"slug\":\"vertex\",\"type\":\"VERTEX\",\"project\":\"my-project\",
       \"location\":\"us-central1\",\"api_key\":$(jq -Rs . < service-account.json)}"
```

`base_url` may be omitted for `VERTEX`: it defaults to
`https://<location>-aiplatform.googleapis.com`. The service-account JSON is encrypted with
the same master key as every other credential and is never returned by the admin API.
Vertex has no model-listing endpoint, so `/admin/models/sync` skips it and its probe says
so; add its routes by hand.

## Database

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:h2:file:./data/gateway;MODE=PostgreSQL;AUTO_SERVER=TRUE` | JDBC URL |
| `DB_USER`, `DB_PASSWORD` | `sa`, empty | credentials |

### Postgres

```bash
export SPRING_PROFILES_ACTIVE=postgres
export DB_URL=jdbc:postgresql://localhost:5432/aigateway
export DB_USER=aigateway DB_PASSWORD=aigateway
./mvnw -pl path-gateway spring-boot:run
```

or `docker compose up --build`.

### Profiles

| Profile | Effect |
|---|---|
| default | H2 file database, human-readable logs |
| `postgres` | Postgres dialect and driver |
| `prod` | ECS JSON logs with `requestId`/`traceId`/`spanId`, conservative tracing |

## Ports and endpoints

| Path | Notes |
|---|---|
| `/v1/**` | public API, bearer key |
| `/admin/**` | `X-Admin-Token` |
| `/actuator/health` | liveness + readiness |
| `/actuator/health/providers` | per-provider circuit state and retry-after |
| `/actuator/prometheus` | metrics |
| `/v3/api-docs` | OpenAPI 3 |
