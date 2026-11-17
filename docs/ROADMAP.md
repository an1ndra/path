# Roadmap

Everything below is tracked as a GitHub issue; this page is the short version for
anyone reading the repo rather than the issue tracker.

## Open stories

| # | Story | Blocked on |
|---|---|---|
| [#62](https://github.com/an1ndra/path/issues/62) | Spring AI `ChatModel` adapter module | a decision on the module name and whether it publishes as a second artifact |
| [#75](https://github.com/an1ndra/path/issues/75) | Vertex AI / Gemini native | a decision: Vertex (service-account auth) or AI Studio only |
| [#74](https://github.com/an1ndra/path/issues/74) | Valkey-shared breaker and limiter | a Valkey instance to verify the cross-replica behaviour honestly |
| [#61](https://github.com/an1ndra/path/issues/61) | Usage retention and partitioning | Postgres only; retention shipped, partitioning unverified on H2 |
| [#59](https://github.com/an1ndra/path/issues/59) | Publish to Maven Central | a verified namespace, a GPG key and a Portal token |

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
| Routing and billing (0.2) | adaptive routing strategies (#63), operator-defined aliases (#68), project rate and concurrency limits (#65), project budget alerts (#69), `POST /v1/tokens/count` (#66), idempotent embeddings (#67) |
| Product (0.3) | semantic cache tier (#71), guardrails: PII redaction and moderation (#70), end-user attribution and budgets (#64), token counting (#66), idempotent embeddings (#67) |
| Reach | Azure OpenAI (#73) |
| Kubernetes | `deploy/helm/path` (#72): drain-aware grace period enforced at render time, HPA, PDB, optional Postgres and Valkey |

## How to pick one up

1. Branch from `Develop` (direct pushes allowed; `main` is release-only behind a PR).
2. Read `AGENTS.md` for conventions and gotchas.
3. Add a unit test and, if it touches routing, streaming or billing, a smoke check in
   `tools/smoke_test.py`.
4. `./mvnw verify` and the smoke suite must pass before the PR.
