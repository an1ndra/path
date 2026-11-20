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
- **Operator aliases are stable; capability aliases are not.** `POST /admin/aliases`
  stores a chain (`prod-chat` → `claude-sonnet-4.5`, then `gpt-4o`) that resolves in that
  order. The four built-in aliases (`cheap`, `fast`, `long-context`, `vision`) are still
  recomputed from prices on every request, so they can move when a price changes. An
  operator alias may shadow a built-in one, and the API flags it with
  `shadows_computed`.
- **Alias chains are validated at write time.** An unknown model, a self-reference or a
  chain deeper than 10 hops is rejected with 400, so a broken alias cannot turn into a
  500 on a customer request.
- **Keys can be restricted to models.** `allowed_models` accepts ids and globs
  (`gpt-4o-mini,claude-*`); anything else returns 403 `model_not_allowed`.
- **Budgets have three windows.** A key carries a lifetime credit limit plus
  `daily_limit_usd` and `monthly_limit_usd`, both checked against stored spend before the
  call.
- **Requests can carry an end user.** `end_user` (Path-specific) or `user` (the OpenAI
  field) is stored on the usage row and indexed with the key and timestamp. A key may set
  `end_user_daily_limit_usd`, which reserves against that end user's own daily budget the
  same way key credit is reserved — a reservation row per (key, end user), taken with an
  atomic conditional update, so two concurrent calls cannot both take the last of it. The
  cap applies even to a key with no credit limit of its own.
- **`/v1/usage` breaks spend down per end user**, so a product can bill its own customers
  without a second system of record. `/admin/usage?endUser=` and the CSV export filter by
  the same field.
- **Project limits sit above key limits.** A project can carry `rpm_limit` and
  `max_concurrent_requests`, enforced in addition to the key's own caps: a request must
  pass both. A project limit of `0` means unlimited. The 429 names which cap was hit
  ("Project 'acme' rate limit exceeded").
- **Limits are enforced before the call.** `max_concurrent_requests` in flight per key
  (default 8) and bodies above `max-request-bytes` rejected with 413 before Jackson reads
  them.
- **Unsupported parameters are rejected, not dropped.** Anthropic has no equivalent for
  `seed`, `logit_bias`, penalties, `logprobs`, `n > 1` or a non-text `response_format`;
  those return 422 `unsupported_parameter`.
- **System-only conversations are rejected.** Anthropic needs a non-empty message list;
  the gateway returns 400 instead of forwarding an empty array.
- **Semantic tier sits above the exact cache and is off by default.** With
  `semantic_cache_enabled` on a route and `GATEWAY_SEMANTIC_MODEL` set, a request that
  misses the exact lookup is embedded and matched against stored entries by cosine
  similarity (default threshold 0.95, per route). Every exact-cache rule still applies:
  the route opted into caching, the request is `temperature: 0`, tool-free, and entries
  are scoped per client key. It is off by default because a hit here answers a *different*
  prompt.
- **The semantic tier costs an embedding call per lookup.** That is why it is opt-in: the
  gateway embeds the prompt before it can know whether anything is similar. Candidates
  are bounded by `GATEWAY_SEMANTIC_MAX_ENTRIES` per key and model, so the comparison is a
  small scan rather than a table scan; a large deployment wants an ANN index instead.
- **The semantic tier serves non-streaming requests only.** Streaming still gets the exact
  tier, which is byte-identical and cheaper to reason about.
- **Azure OpenAI is a provider shape, not a provider type.** Setting `api_version` on a
  provider switches three things: the deployment name goes in the path
  (`/openai/deployments/<upstream_model_id>/chat/completions`), the version goes in
  `?api-version`, and auth uses the `api-key` header instead of a bearer token. The route
  table therefore maps a public model to an Azure *deployment*, which is what Azure
  actually requires. The provider probe uses the deployment listing, because Azure has no
  `/models`, and an unsupported version surfaces Azure's own message rather than a
  generic 502.
- **Routing strategies are opt-in per route.** `strategy` defaults to `priority`, which
  keeps the declared order. `fastest` orders the chain by exponentially weighted latency
  (~5-sample half-life); `cheapest-viable` prefers the cheapest route whose EWMA latency
  is under the route's `latency_slo_ms`, and demotes the rest to fallback. A route with no
  measurements yet counts as viable, so a cold start cannot deadlock routing. The EWMA is
  per JVM, like the circuit breaker.
- **Circuit breaker per provider.** Five consecutive failures park a provider for 30s;
  routes on a parked provider are skipped in microseconds. An upstream 429 with
  `Retry-After` parks it for exactly that long. State appears as `circuit_state` on
  `/admin/providers`, and only one replica at a time may send the half-open probe.
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
- **Unpriced synced models are disabled.** `/admin/models/sync` imports unknown ids with
  `enabled=false`, so a model cannot serve traffic for free before someone sets its price.
- **Reservations use the expected cost, not the cap.** A call reserves what a typical
  completion costs (512 output tokens) while still refusing when that estimate exceeds the
  balance, so a big `max_tokens` no longer blocks a small balance.
- **Admin auth is rate limited.** Ten failed attempts from one address in five minutes get
  429s; a successful auth clears the counter.
- **Usage history is pruned.** Rows older than `GATEWAY_USAGE_RETENTION` (default 180
  days) are deleted nightly; set it to `0` to keep history forever and plan for
  partitioning at that point.
- **Abandoned idempotency claims are reclaimed.** A PENDING claim older than
  `GATEWAY_IDEMPOTENCY_PENDING` (default 2 min) is treated as crashed and the retry runs,
  so one dead instance cannot block a key for the full TTL.
- **Traces continue upstream.** The W3C `traceparent` header is injected into every
  provider call, so provider-side logs join the same trace.
- **Prompt logging covers streaming too.** The SSE payload is captured and stored the
  same way as a non-streaming body, capped and redacted.
- **Anthropic prompt caching is honoured.** A `cache_control` marker on a text or image
  part is forwarded unchanged, so clients can cache long prefixes.
- **Projects have their own budgets.** A project can carry a lifetime, daily and monthly
  limit; they are checked against the spend of every key in it, and
  `GET /admin/projects/{id}/usage` rolls up today, this month and all time.
- **Prompt logs are redacted twice.** Credentials first (`sk-`, `gsk_`, bearer tokens),
  then personal identifiers: emails, phones, card numbers that pass the Luhn checksum,
  Aadhaar and PAN. Redaction is on by default for logs (`GATEWAY_GUARDRAILS_LOG_REDACTION`)
  and is a pure function, so the same prompt always redacts the same way. Tokens are
  numbered per call (`[EMAIL_1]`), which keeps a stored conversation readable.
- **Redaction before send is off by default.** `GATEWAY_GUARDRAILS_REDACT` makes the
  provider see redacted text too. It changes what the model can answer, so it is an
  operator decision, not a default. When on, redaction happens before the request is
  hashed, stored or replayed, so a retry cannot resurrect the original text.
- **Moderation is off by default and never silent.** `GATEWAY_GUARDRAILS_MODERATION` adds a
  pre-flight call to the provider's moderation endpoint (OpenAI-shaped providers only,
  since it needs a compatible `/moderations`). A flagged request is refused with 400
  `content_flagged` and the categories; a moderation outage is logged and the request
  proceeds, because a moderation dependency must not become a gateway outage.
- **Phone detection is deliberately narrow.** Contiguous digits are only treated as a
  phone when they look like a mobile number (10-13 digits starting 6-9), so a 10-digit
  epoch timestamp is not redacted into `[PHONE_1]`. Numbers with separators or a leading
  `+` are treated as phones; ISO dates and dotted versions are not.
- **`GET /admin/settings` reports what is active**, so an operator (or the smoke suite)
  can see whether logging, redaction and moderation are on without reading the env.
- **Prompt logging is off by default.** When enabled it redacts `sk-`/`gsk_`/`Bearer`
  tokens, caps each body, and purges after the retention window — but it still stores user
  prompts. Treat retention as a compliance decision.

## Known gaps

**Per-node by design**

- **Circuit state and the in-flight limiter default to the JVM.** Without
  `GATEWAY_SHARED_STATE_BACKEND=valkey`, each breaker sees only its own failures and the
  concurrency cap is per instance. With `valkey`, state is shared: parking a provider on
  one replica parks it everywhere, exactly one replica claims the HALF_OPEN probe, an OPEN
  entry expires even if the replica that opened it dies, and a killed pod's concurrency
  permits expire after `GATEWAY_CONCURRENCY_LEASE` instead of blocking the key. The RPM
  limiter and budget reservations are shared as before, so money and rate limits stay
  correct in a cluster either way. Memory remains the default because the Valkey path is a
  network call on the hot path.
- **Fuzzing is time-boxed.** `ChatRequestFuzzTest` runs Jazzer for 10s per build; deeper
  fuzzing is a manual `jazzer` run rather than a long CI job.

**Provider-inherent**

- **Embeddings are OpenAI-compatible only.** Anthropic publishes no embeddings endpoint,
  so such a route returns 501 `unsupported_endpoint`.
- **Audio is not proxied.** Chat images are supported (base64 and URL); audio inputs
  would need a per-provider upload flow.
- **Anthropic beta features beyond prompt caching are not forwarded** — extended
  thinking and server-side tools pass through only as ordinary content blocks.

**Operational**

- **`/v3/api-docs` is off in the prod profile** unless `GATEWAY_API_DOCS=true`.
- **Idempotency applies to chat only.** `/v1/embeddings` honours drain, concurrency and
  budgets, but not `Idempotency-Key` or the response cache.
- **Model sync pricing uses a built-in table.** Ids in `ModelSyncService.PRICES` get a
  price; anything else lands at 0.00 until edited, so a synced model can look free.
- **Prompt logs are per-instance settings.** They apply to the whole gateway, not to a
  single key.
