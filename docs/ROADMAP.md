# Roadmap

Everything below is tracked as a GitHub issue; this page is the short version for
anyone reading the repo rather than the issue tracker.

## Open stories

Ordered by the value they add per day of work, not by severity.

### Product

| # | Story | Why it matters |
|---|---|---|
| [#62](https://github.com/an1ndra/path/issues/62) | Spring AI `ChatModel` adapter module | a JVM-native gateway a Spring AI app cannot use is not much of a JVM gateway |
| [#64](https://github.com/an1ndra/path/issues/64) | End-user attribution and budgets | a key and a project are operator concepts; without an end-user axis the gateway cannot bill a product's own customers |
| [#71](https://github.com/an1ndra/path/issues/71) | Semantic cache tier | exact-match caching only catches literal repeats; needs explicit opt-in because a near-duplicate hit is a different prompt |
| [#70](https://github.com/an1ndra/path/issues/70) | PII redaction and moderation passthrough | prompt logging is a PII store today; a product embedding the gateway has no policy hook |

### Reach

| # | Story | Why it matters |
|---|---|---|
| [#73](https://github.com/an1ndra/path/issues/73) | Azure OpenAI support | the default enterprise OpenAI path, currently unimplementable through the adapter |
| [#75](https://github.com/an1ndra/path/issues/75) | Vertex AI / Gemini native | `thinkingConfig` and `safetySettings` are dropped today, which contradicts the 422 contract |

### Money and operations

| # | Story | Why it matters |
|---|---|---|
| [#74](https://github.com/an1ndra/path/issues/74) | Valkey-shared breaker and limiter | per-JVM state means replicas disagree about a dead provider |
| [#61](https://github.com/an1ndra/path/issues/61) | Usage retention and partitioning | retention shipped; monthly partitioning has not |
| [#59](https://github.com/an1ndra/path/issues/59) | Publish to Maven Central | needs a verified namespace, a GPG key and a Portal token |

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
| Kubernetes | `deploy/helm/path` (#72): drain-aware grace period enforced at render time, HPA, PDB, optional Postgres and Valkey |

## How to pick one up

1. Branch from `Develop` (direct pushes allowed; `main` is release-only behind a PR).
2. Read `AGENTS.md` for conventions and gotchas.
3. Add a unit test and, if it touches routing, streaming or billing, a smoke check in
   `tools/smoke_test.py`.
4. `./mvnw verify` and the smoke suite must pass before the PR.
