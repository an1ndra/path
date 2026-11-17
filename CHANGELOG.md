# Changelog

All notable changes to this project are documented here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/) and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html).

**Before tagging a release**, move the relevant entries from `Unreleased` into a new
`## [x.y.z] - YYYY-MM-DD` section. The release workflow reads that section and uses it
as the GitHub release body; a tag with no matching section fails the release.

## [Unreleased]

### Added

- Nothing yet.

## [0.1.0] - 2026-11-17

First public release: one OpenAI-compatible surface in front of OpenAI, Anthropic,
Groq and any OpenAI-compatible endpoint.

### Added

- **Routing** — priority chains with cross-provider fallback, `models[]` and
  `provider.order`/`allow_fallbacks` request controls, capability aliases (`cheap`,
  `fast`, `long-context`, `vision`) resolved from the live price table, and weighted
  canary splits that stay stable per client key.
- **Providers** — native OpenAI chat-completions passthrough and a full Anthropic
  Messages adapter (system messages, images, tools, tool results, streaming), with
  per-provider base URL, credential, timeouts and circuit state.
- **Streaming** — SSE relay with `[DONE]`, optional usage chunks, time-to-first-token
  and throughput recorded per call; fallback only before the first byte.
- **Keys and money** — `sk-gw-...` client keys stored as SHA-256, lifetime/daily/monthly
  USD budgets with pre-flight estimation and atomic reservation, RPM and in-flight
  limits, per-key model allowlists, revocation.
- **Metering** — prompt/completion/cached/cache-write/reasoning tokens, itemised cost,
  cache savings, request id, finish reason, attempts and fallback source per call;
  `/v1/usage` and `/admin/stats` aggregates with TTFT and throughput percentiles.
- **Cache and idempotency** — opt-in exact-match response cache (streams included) and
  `Idempotency-Key` replay with conflict detection.
- **Catalogues and sync** — `/v1/models` with pricing and routing detail,
  `POST /admin/models/sync` importing each provider's model list.
- **Operations** — Flyway migrations, readiness gating, shutdown drain, provider probe
  and health, admin audit trail, webhook alerts, Prometheus metrics, OpenTelemetry
  tracing, ECS JSON logs and an OpenAPI document.
- **Features** — `/v1/embeddings` for OpenAI-compatible providers, opt-in redacted
  prompt logging, projects and scoped admin tokens.

### Security

- Upstream credentials encrypted with AES-GCM at rest; the master key is generated to a
  0600 file when not supplied.
- Admin tokens are hashed, scoped (`read` / `read,write`) and revocable.
- CI runs gitleaks, dependency review, CodeQL and Trivy; releases scan the container
  image and publish a CycloneDX SBOM with SHA-256 checksums.
- The Maven wrapper verifies its distribution against a pinned SHA-256.

### Fixed

- Anthropic prompt-cache reads were billed at the full input rate rather than 0.1×.
- Requests could reach providers before first-boot seeding finished, because health
  reported UP before the seeder ran.
- System-only conversations produced an invalid empty Anthropic message list.
- Weighted routing applied to ordinary fallback chains, turning routing into a lottery.
