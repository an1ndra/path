# Roadmap

Everything below is tracked as a GitHub issue; this page is the short version for
anyone reading the repo rather than the issue tracker.

## Open stories

None. Every story raised against the 0.1.0 line is delivered; new ones go in the issue
tracker and get a row here.

## Delivered

| Area | What shipped |
|---|---|
| Routing | priority chains, cross-provider fallback, capability aliases (`cheap`, `fast`, `long-context`, `vision`), weighted canary splits |
| Money | cache-aware token accounting, itemised cost, idempotent retries, lifetime/daily/monthly budgets, `max_tokens` clamping, model allowlists, CSV statements |
| Safety | per-provider circuit breaker with `Retry-After`, request size limits, per-key concurrency caps, parameter-fidelity 422s |
| Operations | Flyway migrations, readiness gating, shutdown drain, provider probe/health, admin audit log, webhook alerts, Prometheus metrics, OpenTelemetry tracing, ECS JSON logs, OpenAPI |
| Features | exact-match response cache (streaming included), embeddings, opt-in redacted prompt logging, nightly provider canary |
| Scale and tenancy | Valkey-backed shared RPM limiter, database budget reservations, projects, scoped admin tokens |
| Assurance | Jazzer fuzzing of the request parser, property tests for translation invariants |

## How to pick one up

1. Branch from `Develop` (direct pushes allowed; `main` is release-only behind a PR).
2. Read `AGENTS.md` for conventions and gotchas.
3. Add a unit test and, if it touches routing, streaming or billing, a smoke check in
   `tools/smoke_test.py`.
4. `./mvnw verify` and the smoke suite must pass before the PR.
