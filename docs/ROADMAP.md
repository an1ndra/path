# Roadmap

Everything below is tracked as a GitHub issue; this page is the short version for
anyone reading the repo rather than the issue tracker.

## Open stories

From the post-0.1.0 audit. Severity is the reviewer's call, not a promise.

### Security

| # | Story | Severity |
|---|---|---|
| [#39](https://github.com/an1ndra/path/issues/39) | 500 responses leak internal exception messages | high |
| [#41](https://github.com/an1ndra/path/issues/41) | Demo API key enabled by default, including the prod profile | high |
| [#40](https://github.com/an1ndra/path/issues/40) | Request size limit bypassable with chunked encoding | medium |
| [#42](https://github.com/an1ndra/path/issues/42) | Admin API has no rate limit or lockout | medium |
| [#43](https://github.com/an1ndra/path/issues/43) | No master-key rotation path | medium |
| [#51](https://github.com/an1ndra/path/issues/51) | OpenAPI document served without authentication | low |

### Billing

| # | Story | Severity |
|---|---|---|
| [#38](https://github.com/an1ndra/path/issues/38) | Model sync publishes unpriced, enabled routes (free inference) | high |
| [#44](https://github.com/an1ndra/path/issues/44) | Worst-case reservation can reject a request that would fit | medium |

### API and operations

| # | Story | Severity |
|---|---|---|
| [#45](https://github.com/an1ndra/path/issues/45) | Project filters missing from `/admin/usage`, `/admin/stats`, CSV export | low |
| [#46](https://github.com/an1ndra/path/issues/46) | In-memory rate-limit windows are never evicted | low |
| [#49](https://github.com/an1ndra/path/issues/49) | Release tags are not verified to be on `main` | medium |
| [#50](https://github.com/an1ndra/path/issues/50) | `UpstreamClient` allocates an `ObjectMapper` per error | low |

Closed as already delivered: #47 (shared rate limiting, shipped in #20) and #48 (circuit
breaker, shipped in #11).

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
