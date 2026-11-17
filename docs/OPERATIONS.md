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

## Projects and admin tokens

Projects group keys and usage. Admin tokens carry `read` or `read,write`; the bootstrap
`GATEWAY_ADMIN_TOKEN` always has full access.

```bash
curl -s -X POST localhost:8080/admin/projects -H "$A" -H 'Content-Type: application/json' \
  -d '{"slug":"team-a","name":"Team A"}'

# issue a read-only token for a dashboard
curl -s -X POST localhost:8080/admin/tokens -H "$A" -H 'Content-Type: application/json' \
  -d '{"name":"dashboard","scopes":"read"}'

curl -s 'localhost:8080/admin/keys?projectId=1' -H "$A"
curl -s -X POST localhost:8080/admin/tokens/1/revoke -H "$A"
```

A read-only token can `GET` any admin endpoint and gets 403 on anything mutating.

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

## CI runners

All workflows run on `ubuntu-26.04`, the current Ubuntu LTS, pinned rather than
`ubuntu-latest` so an image migration cannot change the build under us. Bump the label
deliberately when the next LTS ships.

## Security gates

`main` only accepts a PR that passes all of these:

| Check | Workflow | Fails on |
|---|---|---|
| `Build and unit tests` | CI | compile or test failure |
| `End-to-end smoke test` | CI | any of the 104 checks |
| `Analyze Java` | CodeQL | new code-scanning alerts |
| `Secret scan` | Security | a secret in the git history |
| `Dependency review` | Security | a new dependency at high severity |
| `Config and file scan` | Security | secrets or misconfiguration in the tree |

A release tag additionally builds the image, scans it with Trivy (fails on
HIGH/CRITICAL), and publishes the jar, a CycloneDX SBOM and `checksums.txt`. The image
digest is written to the workflow summary so it can be pinned.

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

## Release checklist

1. Copy `changelog/unreleased.md` to `changelog/vX.Y.Z.md` and rewrite it for the release.
2. Reset `changelog/unreleased.md` to the empty template.
3. Bump the version in `pom.xml` if it changed.
4. Commit on `Develop`, then open a PR into `main` (all security gates must pass).
5. Tag on `main`: `git tag -a vX.Y.Z -m "..." && git push origin vX.Y.Z`.
6. The release workflow publishes `changelog/<tag>.md` verbatim as the release body,
   scans the image, and attaches the jar, SBOM and `checksums.txt`. A missing or empty
   file fails the release before anything is published.

Preview the notes locally with `python3 tools/changelog.py vX.Y.Z`.

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
