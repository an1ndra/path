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

## Removing the example catalogue

`GATEWAY_SEED_DEFAULTS=true` creates four providers (`openai`, `anthropic`, `groq`,
`gemini`), a starter route table and, with `GATEWAY_BOOTSTRAP_DEMO_KEY`, a `demo-local`
key. They carry no credentials, so they show up in the console as rows you cannot call.
Deleting them in the console is enough — the seeder no longer recreates a deleted provider
on the next boot. From a shell:

```bash
A='X-Admin-Token: pick-something-long'

# what is there, and whether the credential actually resolves
curl -s localhost:8080/admin/providers -H "$A" \
  | jq -r '.[] | [.id, .slug, (.credential_configured|tostring)] | @tsv'

# delete each example provider; its routes cascade with it
for id in 1 2 3 4; do
  curl -s -X DELETE "localhost:8080/admin/providers/$id" -H "$A"; echo
done

# revoke the demo key if it was created
curl -s localhost:8080/admin/keys -H "$A" \
  | jq -r '.[] | select(.name == "demo-local") | .id' \
  | xargs -r -I{} curl -s -X POST "localhost:8080/admin/keys/{}/revoke" -H "$A"
```

Then set `GATEWAY_SEED_DEFAULTS=false` in the deployment and restart, so a fresh database
is not filled again. Usage rows already written by the examples are history, not
configuration; leave them or delete them with the retention job.

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

- **Email alerts.** Set `SPRING_MAIL_*` and `GATEWAY_ALERT_EMAIL_TO` and every alert also
  arrives by email (`[Path] <event>` with the payload as `key: value` lines). Webhook and
  email are independent channels: either, both, or neither. Email is fire-and-forget on a
  virtual thread, and a mailbox failure only logs a warning.
- **Webhook delivery is retried.** A 429, a 5xx, a connect failure or a timeout is retried
  up to three times with exponential backoff (250ms, 1s, 4s) and jitter, honouring
  `Retry-After` when the endpoint sends one (capped at 30s so one endpoint cannot pin a
  worker). A 4xx is never retried: it is a permanent rejection.
  `path_alerts_sent_total{channel="webhook",outcome}` (tagged `delivered`, `retried`,
  `dropped`) makes the failure rate visible.
- **A dead channel cannot grow without bound.** Both webhook and email run on two workers
  with a 64-entry queue. When a queue is full the alert is dropped and counted as
  `outcome="dropped"` on that channel instead of blocking the request that raised it.
  Email is not retried: a real SMTP server has its own queue, and a retry here could
  duplicate a message it already accepted. `channel="email"` and `channel="digest"` carry `delivered` and
  `dropped` only.
- **Mail settings are editable from the console** (`GET/PUT /admin/mail-settings`): host,
  port, username, password, from and recipients. They win over `SPRING_MAIL_*`, which stays
  as the config-file route; `source` in the response says which is in force. The password is
  AES-GCM encrypted, only ever written, and `-` clears it. `POST /admin/alerts/test` sends
  the test through whatever is configured.
- **`POST /admin/alerts/test`** sends a test alert and reports which channels are
  configured, so "do alerts work?" is answered before you need them.
- **Provider health** at `/actuator/health/providers`: circuit state and retry-after per
  provider. Overall health stays UP while fallback exists; only a total outage returns
  OUT_OF_SERVICE.
- **Metrics** at `/actuator/prometheus`: `path_requests_total` (model, provider, status,
  streamed), `path_latency_seconds`, `path_tokens_total` (prompt/completion/cached),
  `path_cost_usd_total`, `path_cache_hits_total` (model, tier exact/semantic),
  `path_fallbacks_total`,
  `path_provider_circuit_open` (provider_id; 1 while the breaker is open),
  `path_errors_total` (provider, class 4xx/5xx/other, gateway error code) and
  `path_alerts_sent_total` (channel, outcome).
- **Traces** via Micrometer + OpenTelemetry. Off by default (`GATEWAY_TRACE_SAMPLE=0`);
  set a sampling probability and `OTEL_EXPORTER_OTLP_ENDPOINT` to export.
- **Correlation** — every response carries `X-Request-Id`, echoed into the MDC and into
  structured logs as `requestId`, alongside `traceId` and `spanId`.
- **Structured logs** — the `prod` profile emits ECS JSON; the default profile stays
  human-readable.
- **OpenAPI** — springdoc publishes `/v3/api-docs`; CI uploads the generated spec as a
  build artifact so drift is visible.

## Cluster state

Circuit-breaker and in-flight counters default to memory, one node each. Set
`GATEWAY_SHARED_STATE_BACKEND=valkey` (same Redis connection as the rate limiter) to share
them:

- parking a provider on one replica blocks it on the others within one probe interval
- exactly one replica sends the HALF_OPEN probe; the claim also expires after
  `GATEWAY_BREAKER_PROBE_LEASE`, so a dead prober cannot wedge a provider
- an OPEN entry expires on its own after its retry-after, even if the opener is gone
- in-flight permits are leased (`GATEWAY_CONCURRENCY_LEASE`), so a killed pod's permits
  age out instead of blocking a key forever

`/actuator/health/providers` and `/admin/providers` read the same store, so with Valkey the
circuit state they show is the cluster's, not the answering pod's.

## Retention, archiving and partitioning

`GATEWAY_USAGE_RETENTION` (default `180d`, `0` keeps everything) drives a nightly purge at
03:30 that deletes rows older than the window. It runs whether or not you archive, so an
archive for finance has to be taken before the cutoff passes.

**Archiving.** The CSV export takes an explicit slice, which is the only safe way to
capture the rows a purge is about to remove:

```bash
# one month at a time, oldest first
curl -s 'localhost:8080/admin/usage/export?from=2026-01-01&to=2026-02-01&limit=50000' \
  -H "$A" -o usage-2026-01.csv
```

`from` and `to` accept an ISO-8601 instant or a plain date (UTC midnight); `window` still
works and ignores them when they are absent. The export is row-per-request, so raise
`limit` (max 50,000) or split the month if a slice overflows.

**Partitioning (PostgreSQL, opt-in).** [`deploy/postgres/partitions.sql`](../deploy/postgres/partitions.sql)
converts `usage_records` to monthly range partitions: rename, recreate the parent with the
same columns and identity, create one partition per month from the oldest row through three
months ahead, move the rows, restore the indexes and a primary key that includes
`created_at`, and leave a DEFAULT partition for anything outside the prepared months.

It is **not** a Flyway migration because it rewrites the table. Run it on purpose, with the
gateway stopped, after a `pg_dump`:

```bash
pg_dump "$DB_URL" -Fc -f path-before-partitions.dump
psql "$DB_URL" -v ON_ERROR_STOP=1 -f deploy/postgres/partitions.sql
```

Afterwards, keep the calendar ahead of the data. Either schedule the provided function in
pg_cron:

```sql
select cron.schedule('path-usage-partitions', '0 3 1 * *',
  $$select path_ensure_usage_partitions(
      date_trunc('month', now())::date,
      (date_trunc('month', now()) + interval '3 months')::date)$$);
```

or call `select path_ensure_usage_partitions(...)` from your own job. Once a month has been
archived, detaching and dropping it is cheaper than the row-by-row retention delete:

```sql
alter table usage_records detach partition usage_records_2025_01;
drop table usage_records_2025_01;
```

The application is unchanged by the conversion: columns, identity and indexes stay the
same, so Hibernate's `ddl-auto=validate` still passes. Partitioning is what keeps
`/admin/stats` and `/admin/usage` fast once the history is older than a few months.

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
`provider.circuit_open`, `provider.rate_limited`, `key.budget_low`, `project.budget_low`,
`project.budget_exhausted`, `provider.error_rate_high`, `provider.error_rate_normal` and
`alert.test`, at most once per event per cooldown. Payloads are JSON with `source`,
`event`, `at` and event-specific fields.

Webhook sends are retried (429/5xx/connect/timeout, three attempts, exponential backoff)
and both channels are bounded by a small queue; see "Observability" for the exact
behaviour and the `path_alerts_sent_total` counter. Losing an alert is therefore visible,
not silent. Email is not retried, deliberately.

**Provider error rate.** The circuit breaker reacts to consecutive failures, so a provider
failing 30% of calls with an occasional success is never parked and requests are quietly
retried onto a fallback. A check runs every minute over the routing EWMA and fires
`provider.error_rate_high` when the count-weighted rate per provider exceeds
`GATEWAY_ALERT_ERROR_RATE` (default 0.25) with at least `GATEWAY_ALERT_MIN_SAMPLES`
(default 20) samples recorded. The payload carries `provider`, `error_rate`, `samples`,
`threshold` and the EWMA `latency_ms`. One alert per provider per hour; when the rate falls
below half the threshold, `provider.error_rate_normal` closes the incident. A provider with
too few samples is ignored: a cold start is not an incident.

**Daily digest.** One email a day (`GATEWAY_DIGEST_CRON`, default 08:00 in the gateway's
time zone) summarises yesterday: requests with the day-over-day delta, spend, tokens with
the cache hit ratio and savings, failures with the top error code, the slowest model's p95,
the top three models by spend, and any provider whose circuit is open at send time. It
needs the same mail settings as alert email and is silently off without them; a day with no
requests sends nothing, and a mailbox failure only logs a warning. Disable it with
`GATEWAY_DIGEST=false`. `POST /admin/digest/test?date=2026-11-19` sends it on demand and
returns the rendered text (and the reason when it did not send), so the schedule can be
trusted before the first morning.

### Prometheus rules

[`deploy/prometheus/path-rules.yml`](../deploy/prometheus/path-rules.yml) ships the
recording and alerting rules; `helm template ... --set prometheusRule.enabled=true`
renders the same rules as a `PrometheusRule` for the Prometheus Operator. CI validates
them with `promtool check rules`.

| Alert | Fires when | What to do |
|---|---|---|
| `PathHighFallbackRate` | fallback requests are over 20% of traffic for 10m | one route is failing; check `path_requests_total{status="error"}` and the route's probe |
| `PathCacheHitRatioLow` | the 30m cache hit ratio is under half its 7-day average for 30m | a prompt prefix changed, caching was disabled on a route, or a client uses `temperature > 0` |
| `PathSpendRateHigh` | the last hour, extrapolated, is above 2× the whole previous day | check top models and keys at `/admin/stats` |
| `PathErrorRateHigh` | more than 2% of requests fail with a 5xx for 10m | fallback is exhausted; read `error_code` in `/admin/usage` |
| `PathProviderParked` | `path_provider_circuit_open` was 1 for the whole last 15m | a provider has been unreachable since then; every route on it is skipped |

Every rule has a `for` duration so a single bad minute does not page. The cache rule uses
the recorded `path:cache_hit_ratio:rate30m`, because comparing a ratio with its own average
needs a series to average.

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
