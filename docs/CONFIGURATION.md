# Configuration

Every setting is an environment variable with a working default; nothing is required
except the provider credentials you actually use.

## Server

| Variable | Default | Meaning |
|---|---|---|
| `PORT` | `8080` | HTTP port |
| `GATEWAY_ADMIN_TOKEN` | empty (admin off) | admin shared secret |
| `GATEWAY_PUBLIC_MODELS` | `true` | allow keyless `/v1/models` |
| `GATEWAY_CORS_ORIGINS` | `*` | comma-separated origin patterns |
| `GATEWAY_MAX_REQUEST_BYTES` | `1048576` | request bodies larger than this get 413 |
| `GATEWAY_DRAIN_TIMEOUT` | `30s` | how long shutdown waits for in-flight streams |

## Keys and budgets

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_BOOTSTRAP_DEMO_KEY` | `true` | create a demo key when the table is empty |
| `GATEWAY_DEFAULT_RPM` | `120` | default requests/minute for new keys |
| `GATEWAY_DEFAULT_CREDIT_USD` | unlimited | default credit limit for new keys |
| `GATEWAY_DEFAULT_MAX_CONCURRENT` | `8` | in-flight requests per key (0 = unlimited) |
| `GATEWAY_MAX_OUTPUT_TOKENS` | `0` (off) | hard cap on `max_tokens` for every request |

## Upstream behaviour

| Variable | Default | Meaning |
|---|---|---|
| `GATEWAY_BREAKER_FAILURES` | `5` | consecutive failures before a provider is parked |
| `GATEWAY_BREAKER_OPEN` | `30s` | how long a parked provider is skipped |
| `GATEWAY_MAX_ATTEMPTS` | `3` | routes tried per request, including the first |
| `GATEWAY_IDEMPOTENCY_TTL` | `24h` | how long a completed response stays replayable |
| `GATEWAY_IDEMPOTENCY_MAX_BYTES` | `2097152` | larger responses are not stored for replay |
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
| `OPENAI_API_KEY` / `ANTHROPIC_API_KEY` / `GROQ_API_KEY` | credentials for the seeded providers |
| `GATEWAY_UPSTREAM_<SLUG>_KEY` | credential for any other provider slug (e.g. `GATEWAY_UPSTREAM_TOGETHER_KEY`) |

Resolution order: **database → `application.yml` → environment**. A key stored through
the admin API silently wins over the environment, which makes rotation possible without
a restart. Credentials in the database are AES-GCM encrypted with a key from
`GATEWAY_MASTER_KEY` or the generated `./data/master.key` (0600).

A provider row must exist before its environment variable is read; only `openai`,
`anthropic` and `groq` are seeded.

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
./mvnw spring-boot:run
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
| `/actuator/prometheus` | metrics |
| `/v3/api-docs` | OpenAPI 3 |
