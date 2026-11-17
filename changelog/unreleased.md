# Unreleased

Notes for the next release. Nothing is published from this file; copy it to
`vX.Y.Z.md` when cutting a release. See [README.md](README.md).

## Added

- Google Gemini as a seeded provider, using its OpenAI-compatible endpoint
  (`GEMINI_API_KEY`); chat, streaming, tools, images and embeddings all work.
- Project-level lifetime, daily and monthly budgets, enforced across every key in the
  project, plus `GET /admin/projects/{id}/usage` rollups.
- Streaming prompt logs: the captured SSE payload is stored alongside non-streaming
  bodies (still redacted and capped).
- Anthropic `cache_control` markers on text and image parts are forwarded, enabling
  provider-side prompt caching.

## Changed

- Nothing yet.

## Fixed

- `docs/LIMITS.md` gaps that were fixable: project billing, streaming prompt logs and
  Anthropic prompt caching. The rest are now documented as per-node or provider-inherent.

## Security

- The container image now runs as an unprivileged user (`USER path`), fixing
  Trivy DS-0002.
