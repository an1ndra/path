# Testing

## Unit and integration tests

```bash
./mvnw verify        # 72 tests
```

| Class | Covers |
|---|---|
| `GatewayApiTest` | boots the full app on H2: auth, catalogue, credits, admin token, snake_case inputs, 413, OpenAPI, request ids |
| `AnthropicAdapterTest` | request translation (system, images, tools, tool results), response translation, stream events, cache tokens, rejected parameters |
| `AnthropicTranslationPropertyTest` | 200 generated conversations against translation invariants |
| `UsageCostTest` | cost breakdown, cache pricing, missing prices, usage parsing |
| `ProviderCircuitBreakerTest` | threshold, half-open probe, rate-limit parking |
| `IdempotencyServiceTest` | replay, conflicts, concurrent claims, key scoping |
| `ResponseCacheEligibilityTest` / `ResponseCacheServiceTest` | cache rules, scoping, expiry |
| `CreditGuardTest` | budget windows, max_tokens clamping, allowlist |
| `DrainStateTest` | in-flight accounting, refusal while draining |
| `RoutingTest` | aliases, weighted assignment, fallback order |
| `TokenEstimateTest` | real tokenization with a heuristic fallback |
| `ConcurrencyLimiterTest` | per-key permits, no over-release |
| `AuditServiceTest` / `AlertServiceTest` / `PromptLogServiceTest` | audit trail, webhook cooldown, redaction |

## End-to-end suite

99 checks against a mock upstream — no real keys needed. It exercises both wire formats,
streaming, fallback, credits, rate limits, idempotency, caching, aliases, embeddings, the
admin surface and metering.

```bash
python3 tools/mock-upstream.py 9099 &
GATEWAY_ADMIN_TOKEN=test-admin GATEWAY_SEED_DEFAULTS=true \
  java -jar path-gateway/target/path-0.2.2.jar &
python3 tools/smoke_test.py
```

The suite calls the example catalogue by name (`gpt-4o-mini`, `claude-3.5-haiku`,
`auto-fast`, …), so it needs `GATEWAY_SEED_DEFAULTS=true` against a clean database. That
flag is off by default for real deployments; CI sets it for this job.

The mock switches behaviour by model id, which is how failure paths are tested:

| Model id contains | Behaviour |
|---|---|
| `boom` | HTTP 500 |
| `ratelimit` | HTTP 429 |
| `unauthorized` | HTTP 401 |
| `badrequest` | HTTP 400 |
| `slow` | stream sleeps between chunks |
| `empty` | stream closes without data |

The suite patches providers to point at the mock through the admin API, so it also
verifies the admin surface end to end.

## Load testing

```bash
k6 run -e BASE=http://127.0.0.1:8080 -e KEY=sk-gw-... tools/load-test.js
```

Steady chat plus a streaming arrival rate, with thresholds on error rate and p95 latency.
It is not part of CI.

## CI

`.github/workflows/ci.yml` runs `./mvnw verify`, then starts the gateway against the mock
and runs the full smoke suite, uploading the OpenAPI spec as an artifact.
`.github/workflows/canary.yml` sends one 8-token call per configured provider nightly and
opens an issue on failure.
