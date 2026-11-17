# Operations

## Admin API

Every `/admin/**` call needs `X-Admin-Token: $GATEWAY_ADMIN_TOKEN`. If that variable is
empty the admin API returns 403. Admin JSON accepts snake_case (`credit_limit_usd`) and
camelCase (`creditLimitUsd`); responses are snake_case.

```bash
A='X-Admin-Token: pick-something-long'

# providers
curl -s localhost:8080/admin/providers -H "$A"
curl -s -X PATCH localhost:8080/admin/providers/1 -H "$A" -H 'Content-Type: application/json' \
  -d '{"base_url":"https://api.openai.com/v1","api_key":"sk-..."}'
curl -s -X POST localhost:8080/admin/providers -H "$A" -H 'Content-Type: application/json' \
  -d '{"slug":"together","display_name":"Together","type":"OPENAI",
       "base_url":"https://api.together.xyz/v1","api_key":"..."}'

# routes
curl -s -X POST localhost:8080/admin/routes -H "$A" -H 'Content-Type: application/json' \
  -d '{"public_model_id":"gpt-4o-mini","provider_id":1,"upstream_model_id":"gpt-4o-mini",
       "input_price_per_million":0.15,"output_price_per_million":0.60,"priority":10}'

# client keys — the plaintext is returned exactly once
curl -s -X POST localhost:8080/admin/keys -H "$A" -H 'Content-Type: application/json' \
  -d '{"name":"team-a","credit_limit_usd":25,"rpm_limit":120}'
curl -s -X POST localhost:8080/admin/keys/1/topup -H "$A" -H 'Content-Type: application/json' \
  -d '{"amount_usd":10}'
curl -s -X POST localhost:8080/admin/keys/1/revoke -H "$A"

# import each provider's model list (built-in prices for well-known ids, 0.00 otherwise)
curl -s -X POST localhost:8080/admin/models/sync -H "$A"
```

## Provider operations

```bash
# reachability check: one /models call per provider, reports latency and model count
curl -s -X POST localhost:8080/admin/providers/1/probe -H "$A"

# fleet view: circuit state and credential status
curl -s localhost:8080/admin/providers/health -H "$A"
```

## Reporting

```bash
curl -s 'localhost:8080/admin/stats?window=24h' -H "$A"
curl -s 'localhost:8080/admin/usage?window=7d&limit=50' -H "$A"
curl -s 'localhost:8080/admin/usage/export?window=30d&limit=5000' -H "$A" -o usage.csv
curl -s 'localhost:8080/admin/audit?action=route.update&limit=50' -H "$A"
curl -s 'localhost:8080/admin/prompt-logs?limit=20' -H "$A"
```

## Observability

- **Metrics** at `/actuator/prometheus`: `path_requests_total` (model, provider, status,
  streamed), `path_latency_seconds`, `path_tokens_total` (prompt/completion/cached),
  `path_cost_usd_total`, `path_cache_hits_total`.
- **Traces** via Micrometer + OpenTelemetry. Off by default (`GATEWAY_TRACE_SAMPLE=0`);
  set a sampling probability and `OTEL_EXPORTER_OTLP_ENDPOINT` to export.
- **Correlation** — every response carries `X-Request-Id`, echoed into the MDC and into
  structured logs as `requestId`, alongside `traceId` and `spanId`.
- **Structured logs** — the `prod` profile emits ECS JSON; the default profile stays
  human-readable.
- **OpenAPI** — springdoc publishes `/v3/api-docs`; CI uploads the generated spec as a
  build artifact so drift is visible.

## Schema migrations

Flyway owns the schema; Hibernate runs with `ddl-auto: validate` and fails fast when an
entity and the database disagree.

- `src/main/resources/db/migration/V1__baseline.sql` is the baseline, written in portable
  SQL (`identity` columns, `timestamp with time zone`, enums as varchar).
- **Never edit an applied migration.** Add `V<n>__name.sql`.
- An existing pre-Flyway database is baselined at V1 automatically, so upgrading a
  running install does not drop data.
- Tests run the same migrations against in-memory H2, so a broken migration fails CI.

## Shutdown

On SIGTERM the readiness probe goes DOWN, new chat requests get `503 shutting_down`, and
in-flight streams drain for up to `GATEWAY_DRAIN_TIMEOUT`. Keep that value below your
orchestrator's termination grace period.

## Alerts

Set `GATEWAY_ALERT_WEBHOOK` to a Slack-compatible URL to receive
`provider.circuit_open`, `provider.rate_limited` and `key.budget_low`, at most once per
event per cooldown. Payloads are JSON with `source`, `event`, `at` and event-specific
fields.

## Audit

Every provider, route and key mutation is recorded with actor, action, target and
payload in `admin_audit`, readable at `GET /admin/audit`. Auditing never blocks the
operation it describes.

## Load testing

```bash
k6 run -e BASE=http://127.0.0.1:8080 -e KEY=sk-gw-... tools/load-test.js
```

Two scenarios (steady chat and a streaming arrival rate) with thresholds on error rate and
p95 latency, so a regression fails the run. Not part of CI.
