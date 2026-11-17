# Roadmap

Everything below is tracked as a GitHub issue; this page is the short version for
anyone reading the repo rather than the issue tracker.

## Open stories

| # | Story | Why it matters | Effort |
|---|---|---|---|
| [#20](https://github.com/an1ndra/path/issues/20) | **Redis-backed rate limiter and budget reservation** | The limiter and circuit breaker are per-JVM maps, so two instances allow twice the RPM, and concurrent requests can overshoot a budget by a burst. Needs an optional Redis backend plus a short-lived reservation before the upstream call, reconciled after. | ~2 days |
| [#33](https://github.com/an1ndra/path/issues/33) | **Org / project / key hierarchy and scoped admin tokens** | One shared `X-Admin-Token` and a flat key list do not survive a second team. Projects own keys and usage; admin tokens carry scopes (read, keys, routes, providers), optionally with OIDC for humans. | ~3 days |
| [#37](https://github.com/an1ndra/path/issues/37) | **Fuzz the request parser** | `ChatRequestSpec` is the widest untrusted surface — arbitrary JSON, deeply nested content parts. It has property tests but has never been fuzzed. Needs a Jazzer harness with a bounded CI run and regression cases from any crash. | ~1 day |

## Delivered

| Area | What shipped |
|---|---|
| Routing | priority chains, cross-provider fallback, capability aliases (`cheap`, `fast`, `long-context`, `vision`), weighted canary splits |
| Money | cache-aware token accounting, itemised cost, idempotent retries, lifetime/daily/monthly budgets, `max_tokens` clamping, model allowlists, CSV statements |
| Safety | per-provider circuit breaker with `Retry-After`, request size limits, per-key concurrency caps, parameter-fidelity 422s |
| Operations | Flyway migrations, readiness gating, shutdown drain, provider probe/health, admin audit log, webhook alerts, Prometheus metrics, OpenTelemetry tracing, ECS JSON logs, OpenAPI |
| Features | exact-match response cache (streaming included), embeddings, opt-in redacted prompt logging, nightly provider canary |

## How to pick one up

1. Branch from `Develop` (direct pushes allowed; `main` is release-only behind a PR).
2. Read `AGENTS.md` for conventions and gotchas.
3. Add a unit test and, if it touches routing, streaming or billing, a smoke check in
   `tools/smoke_test.py`.
4. `./mvnw verify` and the smoke suite must pass before the PR.
