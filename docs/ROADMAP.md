# Roadmap

Everything below is tracked as a GitHub issue; this page is the short version for
anyone reading the repo rather than the issue tracker.

## Open stories

| # | Story | Why it matters |
|---|---|---|
| [#62](https://github.com/an1ndra/path/issues/62) | Spring AI `ChatModel` adapter module | a JVM-native gateway a Spring AI app cannot use is not much of a JVM gateway; also gives the published artifact a reason to exist |
| [#63](https://github.com/an1ndra/path/issues/63) | Adaptive routing by latency and cost | routing is static today, so a slow-but-alive provider keeps the traffic until it 5xxs |
| [#64](https://github.com/an1ndra/path/issues/64) | End-user attribution and budgets | a key and a project are operator concepts; without an end-user axis the gateway cannot bill a product's own customers |
| [#61](https://github.com/an1ndra/path/issues/61) | Retention and partitioning for `usage_records` | retention shipped; monthly partitioning has not |
| [#59](https://github.com/an1ndra/path/issues/59) | Publish to Maven Central | needs a verified namespace, a GPG key and a Portal token |

Everything else from the post-0.1.0 audit (#38–#51) is closed and shipped in `93ae278`;
see Delivered below.

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
| Post-0.1.0 audit | all twelve fixes (#38–#51): unpriced routes disabled, generic 500s with a reference id, chunked-body limits, admin auth limiter, master-key rotation, `projectId` filters, tag-on-main guard |
| Runnability | `tools/demo.sh` (60-second tour against the mock), `docs/WHY-PATH.md` |

## How to pick one up

1. Branch from `Develop` (direct pushes allowed; `main` is release-only behind a PR).
2. Read `AGENTS.md` for conventions and gotchas.
3. Add a unit test and, if it touches routing, streaming or billing, a smoke check in
   `tools/smoke_test.py`.
4. `./mvnw verify` and the smoke suite must pass before the PR.
