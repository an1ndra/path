#!/usr/bin/env python3
"""End-to-end smoke test against a running gateway + tools/mock-upstream.py.

Covers: auth, catalogue, non-stream and stream chat on both wire formats,
cross-provider fallback, credits, rate limiting, usage metering and errors.

    python3 tools/smoke_test.py [gateway_url] [admin_token] [log_file]

Exit code 0 only when every check passes.
"""
import json
import os
import re
import sys
import urllib.error
import urllib.request

BASE = (sys.argv[1] if len(sys.argv) > 1 else "http://127.0.0.1:8080").rstrip("/")
ADMIN = sys.argv[2] if len(sys.argv) > 2 else "test-admin"
LOG = sys.argv[3] if len(sys.argv) > 3 else "/tmp/opencode/gateway.log"
MOCK = "http://127.0.0.1:9099/v1"

results = []


def check(name, condition, detail=""):
    results.append((name, bool(condition), detail))
    print(("PASS  " if condition else "FAIL  ") + name + (("  [" + detail + "]") if detail and not condition else ""))


def req(method, path, body=None, key=None, admin=False, timeout=30, headers=None):
    data = json.dumps(body).encode() if body is not None else None
    request = urllib.request.Request(BASE + path, data=data, method=method)
    request.add_header("Content-Type", "application/json")
    if key:
        request.add_header("Authorization", "Bearer " + key)
    if admin:
        request.add_header("X-Admin-Token", ADMIN)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        with urllib.request.urlopen(request, timeout=timeout) as resp:
            return resp.status, dict(resp.headers), resp.read()
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), e.read()


def jreq(*args, **kwargs):
    status, headers, raw = req(*args, **kwargs)
    try:
        parsed = json.loads(raw or b"{}")
    except Exception:
        parsed = {"_raw": raw.decode(errors="replace")}
    return status, headers, parsed


def sse_post(path, body, key, timeout=60, headers=None):
    """Returns (status, headers, list_of_data_payloads)."""
    request = urllib.request.Request(BASE + path, data=json.dumps(body).encode(), method="POST")
    request.add_header("Content-Type", "application/json")
    request.add_header("Authorization", "Bearer " + key)
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    try:
        resp = urllib.request.urlopen(request, timeout=timeout)
    except urllib.error.HTTPError as e:
        return e.code, dict(e.headers), [e.read().decode()]
    payloads = []
    for line in resp:
        text = line.decode().rstrip("\r\n")
        if text.startswith(":"):
            continue
        if text.startswith("data: "):
            payloads.append(text[6:])
        elif text.startswith("data:"):
            payloads.append(text[5:])
    return resp.status, dict(resp.headers), [p for p in payloads if p.strip()]


def demo_key():
    try:
        with open(LOG, encoding="utf-8", errors="replace") as fh:
            match = re.search(r"Created demo API key[^:]*: (sk-gw-[A-Za-z0-9]+)", fh.read())
            if match:
                return match.group(1)
    except FileNotFoundError:
        pass
    return None


def main():
    key = demo_key()
    if not key:
        status, _, body = jreq("POST", "/admin/keys", {"name": "smoke-fallback"}, admin=True)
        if status != 200:
            print("Cannot obtain an API key:", status, body)
            sys.exit(2)
        key = body["key"]
        print("Using a freshly created key", key[:12] + "...")
    else:
        print("Using demo key", key[:12] + "...")

    # ---------------------------------------------------------- point at mock
    status, _, providers_body = jreq("GET", "/admin/providers", admin=True)
    check("admin providers listed", status == 200 and isinstance(providers_body, list), str(status))
    providers = {p["slug"]: p for p in providers_body} if isinstance(providers_body, list) else {}

    for slug, provider in providers.items():
        jreq("PATCH", f"/admin/providers/{provider['id']}",
             {"baseUrl": MOCK, "apiKey": "mock-secret"}, admin=True)

    # --------------------------------------------------------------- catalogue
    status, _, models = jreq("GET", "/v1/models", key=key)
    ids = [m["id"] for m in models.get("data", [])]
    check("GET /v1/models 200", status == 200)
    check("catalogue contains gpt-4o-mini", "gpt-4o-mini" in ids, str(ids))

    status, _, single = jreq("GET", "/v1/models/gpt-4o-mini", key=key)
    check("GET /v1/models/gpt-4o-mini 200", status == 200 and single.get("id") == "gpt-4o-mini")

    # -------------------------------------------------------- openai non-stream
    status, headers, body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("openai non-stream 200", status == 200, str(body)[:200])
    content = body.get("choices", [{}])[0].get("message", {}).get("content")
    check("openai non-stream content", content == "Hello from the mock upstream.", str(content))
    check("openai non-stream model rewritten to public id", body.get("model") == "gpt-4o-mini")
    check("openai non-stream provider header", headers.get("X-Gateway-Provider") == "openai",
          str(headers.get("X-Gateway-Provider")))
    cost = float(headers.get("X-Gateway-Cost-Usd", "0"))
    # 11 prompt tokens, 5 of them cached: 6*0.15 + 5*0.075 + 7*0.60, per million, rounded to 8dp
    expected = (6 * 0.15 + 5 * 0.075 + 7 * 0.60) / 1e6
    # cost is stored at 8 decimals, so allow one unit in the last place
    check("openai cost applies the cache discount", abs(cost - expected) < 1e-8, str(cost))

    # ------------------------------------------------------------ openai stream
    status, headers, payloads = sse_post("/v1/chat/completions", {
        "model": "gpt-4o-mini", "stream": True, "stream_options": {"include_usage": True},
        "messages": [{"role": "user", "content": "hi"}]}, key)
    check("openai stream 200", status == 200, str(payloads)[:200])
    check("openai stream content-type", headers.get("Content-Type", "").startswith("text/event-stream"))
    check("openai stream terminates with [DONE]", payloads and payloads[-1] == "[DONE]", str(payloads[-3:]))
    text = ""
    usage = None
    for p in payloads:
        if p == "[DONE]":
            continue
        chunk = json.loads(p)
        if chunk.get("usage"):
            usage = chunk["usage"]
        for choice in chunk.get("choices", []):
            text += choice.get("delta", {}).get("content") or ""
    check("openai stream content assembled", text == "Hello from mock stream", repr(text))
    check("openai stream usage reported", usage and usage.get("total_tokens") == 18, str(usage))

    # ----------------------------------------------------- anthropic non-stream
    status, headers, body = jreq("POST", "/v1/chat/completions", {
        "model": "claude-3.5-haiku", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("anthropic non-stream 200", status == 200, str(body)[:300])
    check("anthropic provider header", headers.get("X-Gateway-Provider") == "anthropic",
          str(headers.get("X-Gateway-Provider")))
    content = body.get("choices", [{}])[0].get("message", {}).get("content")
    check("anthropic content translated", content == "Hello from mock Anthropic.", repr(content))
    check("anthropic usage translated", body.get("usage", {}).get("total_tokens") == 28,
          str(body.get("usage")))
    check("anthropic cached tokens surfaced", body.get("usage", {}).get("prompt_tokens_details", {})
          .get("cached_tokens") == 4, str(body.get("usage")))
    anthropic_cost = float(headers.get("X-Gateway-Cost-Usd", "0"))
    # 13 uncached @0.80 + 4 cache-read @0.08 + 2 cache-write @1.00 + 9 output @4.00, per million
    expected_anthropic = (13 * 0.80 + 4 * 0.08 + 2 * 1.00 + 9 * 4.00) / 1e6
    check("anthropic cost prices cache read and write", abs(anthropic_cost - expected_anthropic) < 1e-8,
          str(anthropic_cost))

    # --------------------------------------------------------- anthropic stream
    status, headers, payloads = sse_post("/v1/chat/completions", {
        "model": "claude-3.5-haiku", "stream": True,
        "messages": [{"role": "system", "content": "be nice"},
                     {"role": "user", "content": "hi"}]}, key)
    check("anthropic stream 200", status == 200, str(payloads)[:200])
    text = ""
    finish = None
    for p in payloads:
        if p == "[DONE]":
            continue
        chunk = json.loads(p)
        for choice in chunk.get("choices", []):
            text += choice.get("delta", {}).get("content") or ""
            finish = choice.get("finish_reason") or finish
    check("anthropic stream content assembled", text == "Hello from mock Anthropic.", repr(text))
    check("anthropic stream finish_reason stop", finish == "stop", str(finish))
    check("anthropic stream [DONE]", payloads and payloads[-1] == "[DONE]")

    # -------------------------------------------------------------- idempotency
    idem_body = {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "idem"}]}
    s1, h1, b1 = jreq("POST", "/v1/chat/completions", idem_body, key=key,
                      headers={"Idempotency-Key": "smoke-idem-a"})
    s2, h2, b2 = jreq("POST", "/v1/chat/completions", idem_body, key=key,
                      headers={"Idempotency-Key": "smoke-idem-a"})
    check("idempotent replay returns the same body", s1 == 200 and s2 == 200 and b1 == b2, f"{s1}/{s2}")
    check("replay is flagged with X-Idempotent-Replay", h2.get("X-Idempotent-Replay") == "true",
          str(h2.get("X-Idempotent-Replay")))

    s3, _, b3 = jreq("POST", "/v1/chat/completions",
                     {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "different"}]},
                     key=key, headers={"Idempotency-Key": "smoke-idem-a"})
    check("reused key with a different body is rejected",
          s3 == 409 and b3.get("error", {}).get("code") == "idempotency_conflict", str(s3) + str(b3)[:120])

    stream_idem = {"model": "gpt-4o-mini", "stream": True,
                   "messages": [{"role": "user", "content": "idem stream"}]}
    _, _, p4 = sse_post("/v1/chat/completions", stream_idem, key, headers={"Idempotency-Key": "smoke-idem-s"})
    _, _, p5 = sse_post("/v1/chat/completions", stream_idem, key, headers={"Idempotency-Key": "smoke-idem-s"})
    check("streamed replay is identical", p4 == p5 and len(p4) > 1, f"{len(p4)}/{len(p5)}")

    # -------------------------------------------------------- fallback by chain
    for payload, label in [
        ({"publicModelId": "fallback-test", "providerId": providers["openai"]["id"],
          "upstreamModelId": "boom-model", "priority": 1, "inputPricePerMillion": 1, "outputPricePerMillion": 1},
         "primary (500)"),
        ({"publicModelId": "fallback-test", "providerId": providers["groq"]["id"],
          "upstreamModelId": "llama-3.3-70b-versatile", "priority": 2, "inputPricePerMillion": 1, "outputPricePerMillion": 1},
         "backup"),
    ]:
        status, _, created = jreq("POST", "/admin/routes", payload, admin=True)
        if status == 409:
            status2, _, existing = jreq("GET", "/admin/routes?model=fallback-test", admin=True)
            for route in existing:
                if route["provider"] == ("openai" if "boom" in payload["upstreamModelId"] else "groq"):
                    jreq("PATCH", f"/admin/routes/{route['id']}", payload, admin=True)

    status, headers, body = jreq("POST", "/v1/chat/completions", {
        "model": "fallback-test", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("fallback request 200", status == 200, str(body)[:300])
    check("fallback used groq after openai 500", headers.get("X-Gateway-Provider") == "groq",
          str(headers.get("X-Gateway-Provider")))

    status, headers, body = jreq("POST", "/v1/chat/completions", {
        "model": "fallback-test", "provider": {"allow_fallbacks": False},
        "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("allow_fallbacks=false surfaces the 500", status == 502 and
          body.get("error", {}).get("code") == "upstream_error", str(status) + str(body)[:200])

    # --------------------------------------------------------------- limits, params
    status, _, body = jreq("POST", "/v1/chat/completions",
                           {"model": "gpt-4o-mini",
                            "messages": [{"role": "user", "content": "x" * 1_100_000}]}, key=key)
    check("oversized body -> 413", status == 413 and body.get("error", {}).get("code") == "payload_too_large",
          str(status))

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "claude-3.5-haiku", "seed": 7,
        "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("anthropic rejects unsupported seed -> 422",
          status == 422 and body.get("error", {}).get("code") == "unsupported_parameter",
          str(status) + str(body)[:120])

    # ------------------------------------------------------------ auth and errors
    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key="sk-gw-nope")
    check("bad key -> 401", status == 401 and body["error"]["code"] == "invalid_api_key", str(status))

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "no-such-model", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("unknown model -> 404", status == 404 and body["error"]["code"] == "model_not_found", str(status))

    status, _, body = jreq("POST", "/v1/chat/completions", {"model": "gpt-4o-mini"}, key=key)
    check("missing messages -> 400", status == 400 and body["error"]["code"] == "invalid_request", str(status))

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]})
    check("missing key -> 401", status == 401 and body["error"]["code"] == "missing_api_key", str(status))

    # ------------------------------------------------------------------ credits
    status, _, poor = jreq("POST", "/admin/keys", {
        "name": "broke-key", "credit_limit_usd": 0.000001, "rpm_limit": 100}, admin=True)
    poor_key = poor.get("key")
    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o", "max_tokens": 500, "messages": [{"role": "user", "content": "hi"}]},
        key=poor_key)
    check("out of credit -> 402", status == 402 and body["error"]["code"] == "insufficient_credits",
          str(status) + str(body)[:200])

    # -------------------------------------------------------------- rate limits
    status, _, limited = jreq("POST", "/admin/keys", {"name": "slow-key", "rpm_limit": 1}, admin=True)
    limited_key = limited.get("key")
    first, _, _ = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key=limited_key)
    second, headers, body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key=limited_key)
    check("rpm limit: first ok", first == 200, str(first))
    check("rpm limit: second 429", second == 429 and body["error"]["code"] == "rate_limit_exceeded",
          str(second))
    check("rpm limit sends Retry-After", "Retry-After" in headers, str(headers.keys()))

    # ------------------------------------------------------- usage and reporting
    status, _, usage = jreq("GET", "/v1/usage?window=7d", key=key)
    check("GET /v1/usage 200", status == 200, str(status))
    check("usage counted requests", usage.get("requests", 0) >= 5, str(usage.get("requests")))
    check("usage counted tokens", usage.get("total_tokens", 0) > 0, str(usage.get("total_tokens")))
    check("usage lists recent calls", isinstance(usage.get("recent"), list) and usage["recent"],
          str(usage.get("recent"))[:200])
    check("key usage exposes cached tokens", usage.get("cached_input_tokens", 0) > 0,
          str(usage.get("cached_input_tokens")))
    check("key usage exposes cache hit ratio", usage.get("cache_hit_ratio", 0) > 0,
          str(usage.get("cache_hit_ratio")))
    check("key usage exposes cache savings", float(usage.get("cached_savings_usd", 0)) > 0,
          str(usage.get("cached_savings_usd")))
    check("key usage exposes a cost breakdown", "cost_breakdown" in usage, str(usage.keys()))

    status, _, credits = jreq("GET", "/v1/credits", key=key)
    check("GET /v1/credits 200", status == 200 and credits.get("unlimited") is True)

    status, _, stats = jreq("GET", "/admin/stats?window=7d", admin=True)
    check("GET /admin/stats 200", status == 200 and stats.get("requests", 0) > 0, str(status))
    check("stats include latency percentiles", "latency_ms" in stats and "p95" in stats["latency_ms"])
    check("stats group by provider", "openai" in stats.get("by_provider", {}), str(stats.get("by_provider")))
    check("stats report cache hit ratio", stats.get("cache", {}).get("hit_ratio", 0) > 0,
          str(stats.get("cache")))
    check("stats report cache savings", float(stats.get("cache", {}).get("savings_usd", 0)) > 0,
          str(stats.get("cache")))
    check("stats report ttft percentiles", stats.get("ttft_ms", {}).get("p50", 0) > 0,
          str(stats.get("ttft_ms")))
    check("stats report throughput", stats.get("tokens_per_second", {}).get("p50", 0) > 0,
          str(stats.get("tokens_per_second")))
    check("stats report fallback requests", stats.get("fallback_requests", 0) >= 1,
          str(stats.get("fallback_requests")))
    check("stats report no estimated usage", stats.get("estimated_usage_requests") == 0,
          str(stats.get("estimated_usage_requests")))

    status, _, records = jreq("GET", "/admin/usage?window=7d&limit=50", admin=True)
    check("GET /admin/usage 200", status == 200 and len(records.get("data", [])) <= 50)
    rows = records.get("data", [])
    openai_rows = [r for r in rows if r["provider"] == "openai" and r["status"] == "success"]
    check("usage rows record cached tokens", any(r["cached_input_tokens"] == 5 for r in openai_rows),
          str([r["cached_input_tokens"] for r in openai_rows]))
    check("usage rows record cache write tokens",
          any(r["provider"] == "anthropic" and r["cache_write_tokens"] == 2 for r in rows),
          str([(r["provider"], r["cache_write_tokens"]) for r in rows]))
    check("usage rows itemise cost", all("cost_breakdown" in r for r in rows))
    check("streamed rows record ttft", any(r["streamed"] and r.get("ttft_ms") for r in rows))
    check("streamed rows record throughput", any(r["streamed"] and r.get("tokens_per_second") for r in rows))
    check("rows carry an upstream request id", any(r.get("upstream_request_id") for r in rows),
          str([r.get("upstream_request_id") for r in rows[:5]]))
    check("rows carry finish reason", any(r.get("finish_reason") for r in rows))

    status, _, body = jreq("GET", "/admin/providers")
    check("admin requires token", status == 401, str(status))

    # ------------------------------------------------------------------- admin sync
    # Remove the seeded gpt-4o route, then prove sync re-imports it from the provider.
    status, _, seeded = jreq("GET", "/admin/routes?model=gpt-4o", admin=True)
    removed = 0
    for route in seeded:
        del_status, _, _ = jreq("DELETE", f"/admin/routes/{route['id']}", admin=True)
        removed += 1 if del_status == 200 else 0
    check("removed seeded route before sync", removed >= 1, str(removed))

    status, _, sync = jreq("POST", "/admin/models/sync", admin=True)
    check("POST /admin/models/sync 200", status == 200 and "created" in sync, str(status) + str(sync)[:200])
    check("sync recreated the removed model", sync.get("created", 0) >= 1, str(sync)[:200])
    check("sync reported no provider errors", sync.get("errors") == [], str(sync.get("errors")))

    status, _, models = jreq("GET", "/v1/models", key=key)
    ids = [m["id"] for m in models.get("data", [])]
    check("synced catalogue gained claude alias", any(i.startswith("claude") for i in ids), str(ids))
    check("sync skipped embedding models", "text-embedding-3-small" not in ids, str(ids))

    # ------------------------------------------------------------------- summary
    passed = sum(1 for _, ok, _ in results if ok)
    failed = [(n, d) for n, ok, d in results if not ok]
    print(f"\n{passed}/{len(results)} checks passed")
    if failed:
        for name, detail in failed:
            print("FAILED:", name, detail)
        sys.exit(1)


if __name__ == "__main__":
    main()
