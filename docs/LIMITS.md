# Design notes and limits

## Behaviour worth knowing

- **Idempotency is opt-in per request.** Send `Idempotency-Key` and a repeat of the same
  request replays the stored response (streams included) with `X-Idempotent-Replay: true`;
  a different body under the same key gets 409 `idempotency_conflict`, a concurrent
  duplicate gets 409 `idempotency_in_progress`. Completed responses live for
  `GATEWAY_IDEMPOTENCY_TTL`; responses over `GATEWAY_IDEMPOTENCY_MAX_BYTES` are not
  stored, so a retry calls the provider again.
- **The response cache is opt-in per route and exact-match.** With `cache_enabled` and an
  explicit `temperature: 0`, a tool-free request with an identical body replays the stored
  response (streams included) with `X-Path-Cache: hit`, billed at zero and reported as
  savings. Entries last `cache_ttl_seconds` (default 3600) and are scoped to the client
  key.
- **Keys can be restricted to models.** `allowed_models` accepts ids and globs
  (`gpt-4o-mini,claude-*`); anything else returns 403 `model_not_allowed`.
- **Budgets have three windows.** A key carries a lifetime credit limit plus
  `daily_limit_usd` and `monthly_limit_usd`, both checked against stored spend before the
  call.
- **Limits are enforced before the call.** `max_concurrent_requests` in flight per key
  (default 8) and bodies above `max-request-bytes` rejected with 413 before Jackson reads
  them.
- **Unsupported parameters are rejected, not dropped.** Anthropic has no equivalent for
  `seed`, `logit_bias`, penalties, `logprobs`, `n > 1` or a non-text `response_format`;
  those return 422 `unsupported_parameter`.
- **System-only conversations are rejected.** Anthropic needs a non-empty message list;
  the gateway returns 400 instead of forwarding an empty array.
- **Circuit breaker per provider.** Five consecutive failures park a provider for 30s;
  routes on a parked provider are skipped in microseconds. An upstream 429 with
  `Retry-After` parks it for exactly that long. State appears as `circuit_state` on
  `/admin/providers`.
- **Timeouts are per provider.** `request_timeout_ms` and `stream_idle_timeout_ms`
  override the global defaults.
- **Shutdown drains instead of truncating.** Readiness goes DOWN, new requests get 503,
  in-flight streams finish within `GATEWAY_DRAIN_TIMEOUT`.
- **Credits are charged after the call** from reported usage, with a worst-case estimate
  checked first. A provider that reports nothing is billed from an estimate and flagged
  `usage_estimated=true`.
- **Cost precision is 8 decimals.** Each component is rounded before summing, so a stored
  total can differ from the exact product by at most 1e-8 USD.
- **Streaming has no resume.** Once bytes are sent the route cannot change; failures
  arrive as an SSE error object followed by `data: [DONE]`.
- **Prompt logging is off by default.** When enabled it redacts `sk-`/`gsk_`/`Bearer`
  tokens, caps each body, and purges after the retention window — but it still stores user
  prompts. Treat retention as a compliance decision.

## Known gaps

- **Circuit state is in-memory, one node.** The breaker and concurrency limiter are
  per-JVM; the RPM limiter and budget reservations are shared (Valkey and the database
  respectively).
- **Projects group keys, not billing.** A project is a tag on keys; there is no per-project
  budget or invoice rollup yet.
- **Embeddings are OpenAI-compatible only.** An Anthropic route returns 501
  `unsupported_endpoint`.
- **Images and audio are not proxied** beyond image blocks inside chat messages.
- **Anthropic-specific betas are not forwarded** — prompt caching, extended thinking and
  server-side tools pass through only as ordinary content blocks.
- **Model sync** filters out non-chat ids and prices only the ids in
  `ModelSyncService.PRICES`; everything else lands at 0.00 until edited.
- **Prompt logs cover non-streaming chat only.**
- **Fuzzing is time-boxed.** `ChatRequestFuzzTest` runs Jazzer for 10s per build; deeper
  fuzzing is a manual `jazzer` run rather than a long CI job.
