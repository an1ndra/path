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
import time
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


def key_works(key):
    """A demo key scraped from a stale log may belong to a deleted database."""
    status, _, _ = req("GET", "/v1/models", key=key, timeout=10)
    return status == 200


def main():
    key = demo_key()
    if key and not key_works(key):
        print("Demo key in", LOG, "does not authenticate; creating a fresh one")
        key = None
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

    # ------------------------------------------------------------ response cache
    _, _, route_list = jreq("GET", "/admin/routes?model=gpt-4o-mini", admin=True)
    targets = [r for r in route_list if r["provider"] == "openai"]
    check("found the route to cache", bool(targets))
    for route in targets:
        jreq("PATCH", f"/admin/routes/{route['id']}",
             {"cache_enabled": True, "cache_ttl_seconds": 60}, admin=True)

    # Unique prompt so a warm database does not already hold this answer.
    marker_text = f"cached prompt {time.time_ns()}"
    cached_body = {"model": "gpt-4o-mini", "temperature": 0,
                   "messages": [{"role": "user", "content": marker_text}]}
    _, ch1, cb1 = jreq("POST", "/v1/chat/completions", cached_body, key=key)
    _, ch2, cb2 = jreq("POST", "/v1/chat/completions", cached_body, key=key)
    check("cache miss then hit",
          ch1.get("X-Path-Cache") == "miss" and ch2.get("X-Path-Cache") == "hit",
          f"{ch1.get('X-Path-Cache')}/{ch2.get('X-Path-Cache')}")
    check("cached body is identical", cb1 == cb2)
    check("cache hit costs nothing", float(ch2.get("X-Gateway-Cost-Usd", "1")) == 0.0,
          str(ch2.get("X-Gateway-Cost-Usd")))

    _, warm_h, _ = jreq("POST", "/v1/chat/completions",
                        {**cached_body, "temperature": 1}, key=key)
    check("non-zero temperature bypasses the cache", warm_h.get("X-Path-Cache") == "off",
          str(warm_h.get("X-Path-Cache")))

    stream_cached = {"model": "gpt-4o-mini", "temperature": 0, "stream": True,
                     "messages": [{"role": "user", "content": f"cached stream {time.time_ns()}"}]}
    _, sh1, sp1 = sse_post("/v1/chat/completions", stream_cached, key)
    _, sh2, sp2 = sse_post("/v1/chat/completions", stream_cached, key)
    check("stream cache miss then hit",
          sh1.get("X-Path-Cache") == "miss" and sh2.get("X-Path-Cache") == "hit",
          f"{sh1.get('X-Path-Cache')}/{sh2.get('X-Path-Cache')}")
    check("stream cache replays identical chunks", sp1 == sp2 and len(sp1) > 1,
          f"{len(sp1)}/{len(sp2)}")

    _, _, recent = jreq("GET", "/admin/usage?window=1h&limit=25", admin=True)
    cache_rows = [r for r in recent.get("data", []) if r.get("finish_reason") == "cache"]
    check("cache hits are metered at zero cost",
          bool(cache_rows) and all(float(r["cost_usd"]) == 0 for r in cache_rows),
          str(len(cache_rows)))

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

    # ----------------------------------------------------------------- budgets
    _, _, daily = jreq("POST", "/admin/keys",
                       {"name": "daily-key", "daily_limit_usd": 0.000001, "rpm_limit": 100}, admin=True)
    dk = daily.get("key")
    s1, _, _ = jreq("POST", "/v1/chat/completions",
                    {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key=dk)
    s2, _, b2 = jreq("POST", "/v1/chat/completions",
                     {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key=dk)
    check("daily budget blocks the next call",
          s1 == 200 and s2 == 402 and "Daily budget" in b2.get("error", {}).get("message", ""),
          f"{s1}/{s2}")

    # ---------------------------------------------------------- provider probe
    status, _, probe = jreq("POST", f"/admin/providers/{providers['openai']['id']}/probe", admin=True)
    check("provider probe reaches the upstream",
          status == 200 and probe.get("ok") is True and probe.get("model_count", 0) > 0,
          str(probe)[:140])
    status, _, health = jreq("GET", "/admin/providers/health", admin=True)
    check("provider health summary lists circuits",
          status == 200 and any(p.get("circuit_state") for p in health), str(status))

    # ------------------------------------------------------------- usage export
    status, _, raw_csv = req("GET", "/admin/usage/export?window=30d&limit=100", admin=True)
    csv_text = raw_csv.decode()
    check("usage export returns a CSV header",
          status == 200 and csv_text.splitlines()[0].startswith("created_at,key_id"), str(status))
    check("usage export contains rows", len(csv_text.strip().splitlines()) > 1,
          str(len(csv_text.splitlines())))

    # ------------------------------------------------- metrics, docs, tracing
    status, docs_headers, docs = jreq("GET", "/v3/api-docs")
    check("OpenAPI spec is served", status == 200 and "/v1/chat/completions" in json.dumps(docs), str(status))

    status, _, prom = jreq("GET", "/actuator/prometheus")
    check("Prometheus metrics are exposed",
          status == 200 and "path_requests_total" in prom.get("_raw", ""), str(status))

    status, chat_headers, _ = jreq("POST", "/v1/chat/completions",
                                   {"model": "gpt-4o-mini",
                                    "messages": [{"role": "user", "content": "id please"}]}, key=key)
    check("responses carry X-Request-Id", bool(chat_headers.get("X-Request-Id")),
          str(chat_headers.get("X-Request-Id")))

    # ----------------------------------------------------------------- aliases
    status, _, created_alias = jreq("POST", "/admin/aliases", {
        "alias": "smoke-alias",
        "models": ["gpt-4o-mini", "claude-3.5-haiku"],
        "description": "smoke chain"}, admin=True)
    check("an operator alias can be created",
          status == 200 and created_alias.get("resolves_to") == "gpt-4o-mini", str(created_alias)[:120])

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "smoke-alias", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("the alias serves traffic", status == 200 and body.get("choices"), str(status))

    _, _, catalogue = jreq("GET", "/v1/models")
    ids = [m["id"] for m in catalogue["data"]]
    check("the alias appears in the catalogue", "smoke-alias" in ids, str(ids)[:120])

    status, _, dup = jreq("POST", "/admin/aliases", {
        "alias": "smoke-alias", "models": ["gpt-4o-mini"]}, admin=True)
    check("a duplicate alias is rejected", status == 409, str(status))

    status, _, bad = jreq("POST", "/admin/aliases", {
        "alias": "broken-alias", "models": ["not-a-real-model"]}, admin=True)
    check("an alias to an unknown model is rejected at write time",
          status == 400 and "not-a-real-model" in bad["error"]["message"], str(bad)[:120])

    status, _, _ = jreq("DELETE", "/admin/aliases/smoke-alias", admin=True)
    check("an alias can be deleted", status == 200, str(status))
    status, _, gone = jreq("POST", "/v1/chat/completions", {
        "model": "smoke-alias", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("a deleted alias returns 404, not 500", status == 404, str(status))

    # -------------------------------------------------------- adaptive routing
    adaptive_payload = {
        "public_model_id": "adaptive-test", "provider_id": providers["openai"]["id"],
        "upstream_model_id": "gpt-4o-mini",
        "input_price_per_million": 0.15, "output_price_per_million": 0.60,
        "strategy": "fastest", "priority": 10}
    status, _, route_row = jreq("POST", "/admin/routes", adaptive_payload, admin=True)
    if status == 409:
        _, _, existing = jreq("GET", "/admin/routes?model=adaptive-test", admin=True)
        status, _, route_row = jreq("PATCH", f"/admin/routes/{existing[0]['id']}",
                                    adaptive_payload, admin=True)
    check("a route can declare a routing strategy",
          status == 200 and route_row.get("strategy") == "fastest", str(route_row)[:120])
    status, _, bad = jreq("POST", "/admin/routes", {
        "public_model_id": "adaptive-bad", "provider_id": providers["openai"]["id"],
        "upstream_model_id": "gpt-4o-mini", "strategy": "telepathy"}, admin=True)
    check("an unknown strategy is rejected", status == 400, str(status))

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "adaptive-test", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("an adaptive route still serves traffic", status == 200, str(status))

    _, _, adaptive_rows = jreq("GET", "/admin/routes?model=adaptive-test", admin=True)
    adaptive_row = adaptive_rows[0] if adaptive_rows else {}
    check("route views expose the measured latency",
          (adaptive_row.get("ewma_latency_ms") or 0) > 0
          and adaptive_row.get("strategy") == "fastest", str(adaptive_row)[:150])

    # ---------------------------------------------------------------- routing
    status, _, models = jreq("GET", "/v1/models", key=key)
    ids = [m["id"] for m in models.get("data", [])]
    check("catalogue advertises capability aliases",
          all(a in ids for a in ("cheap", "fast", "long-context")), str(ids[-6:]))

    status, headers, body = jreq("POST", "/v1/chat/completions", {
        "model": "cheap", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("cheap alias routes to a model", status == 200 and headers.get("X-Gateway-Provider") is not None,
          str(status) + str(body)[:120])

    status, _, routes_body = jreq("GET", "/admin/routes?model=auto-fast", admin=True)
    if routes_body:
        jreq("PATCH", f"/admin/routes/{routes_body[0]['id']}", {"weight": 100}, admin=True)
    check("auto-fast keeps both routes", len(routes_body) == 2, str(len(routes_body)))

    # ------------------------------------------------------------------ audit
    status, _, audit_rows = jreq("GET", "/admin/audit?limit=50", admin=True)
    check("admin audit records mutations",
          status == 200 and isinstance(audit_rows, list)
          and any(e.get("action") == "key.create" for e in audit_rows),
          str(status) + str(audit_rows)[:120])

    # ------------------------------------------------------------- embeddings
    payload = {"public_model_id": "embed-test", "provider_id": providers["openai"]["id"],
               "upstream_model_id": "text-embedding-3-small",
               "input_price_per_million": 0.02, "output_price_per_million": 0.0}
    status, _, created = jreq("POST", "/admin/routes", payload, admin=True)
    if status == 409:
        _, _, existing = jreq("GET", "/admin/routes?model=embed-test", admin=True)
        for route in existing:
            jreq("PATCH", f"/admin/routes/{route['id']}", payload, admin=True)

    status, eheaders, ebody = jreq("POST", "/v1/embeddings", {
        "model": "embed-test", "input": "hello"}, key=key)
    check("embeddings endpoint returns vectors",
          status == 200 and ebody.get("data") and ebody["data"][0].get("embedding"),
          str(status) + str(ebody)[:120])
    check("embeddings are metered", float(eheaders.get("X-Gateway-Cost-Usd", "0")) > 0,
          str(eheaders.get("X-Gateway-Cost-Usd")))

    status, _, ebody = jreq("POST", "/v1/embeddings", {
        "model": "claude-3.5-haiku", "input": "hello"}, key=key)
    check("embeddings reject Anthropic routes", status == 501
          and ebody["error"]["code"] == "unsupported_endpoint", str(status))

    # ------------------------------------------------- embeddings idempotency
    idem = {"Idempotency-Key": "embed-" + str(int(time.time() * 1000))}
    status, first_headers, first_body = jreq("POST", "/v1/embeddings", {
        "model": "embed-test", "input": "idempotent"}, key=key, headers=idem)
    status2, second_headers, second_body = jreq("POST", "/v1/embeddings", {
        "model": "embed-test", "input": "idempotent"}, key=key, headers=idem)
    check("embeddings replay a repeated Idempotency-Key",
          status == 200 and status2 == 200
          and second_headers.get("X-Idempotent-Replay") == "true"
          and second_body == first_body,
          str(status) + str(status2) + str(second_headers.get("X-Idempotent-Replay")))

    status, _, conflict = jreq("POST", "/v1/embeddings", {
        "model": "embed-test", "input": "different body"}, key=key, headers=idem)
    check("embeddings reject a different body on the same key",
          status == 409 and conflict["error"]["code"] == "idempotency_conflict", str(status))

    # ------------------------------------------------------- end-user billing
    status, _, eu_key = jreq("POST", "/admin/keys", {
        "name": "end-user-key", "end_user_daily_limit_usd": 1000}, admin=True)
    check("a key can carry an end-user daily cap",
          status == 200 and float(eu_key.get("end_user_daily_limit_usd") or 0) == 1000.0,
          str(status))
    eu_key_value = eu_key.get("key")

    status, _, _ = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "end_user": "alice",
        "messages": [{"role": "user", "content": "bill me"}]}, key=eu_key_value)
    check("a chat request carrying end_user succeeds", status == 200, str(status))

    _, _, eu_usage = jreq("GET", "/v1/usage?window=1h", key=eu_key_value)
    end_users = {row["end_user"]: row for row in eu_usage.get("end_users", [])}
    check("usage is attributed to the end user",
          "alice" in end_users and float(end_users["alice"]["cost_usd"]) > 0,
          str(eu_usage.get("end_users"))[:150])

    _, _, filtered = jreq("GET", "/admin/usage?endUser=alice&window=1h", admin=True)
    rows = filtered.get("data", [])
    check("admin usage filters by end user",
          filtered.get("end_user") == "alice" and rows
          and all(r.get("end_user") == "alice" for r in rows), str(len(rows)))

    status, _, csv_bytes = req("GET", "/admin/usage/export?endUser=alice&window=1h", admin=True)
    check("the CSV export filters by end user", status == 200 and b"end_user" in csv_bytes, str(status))

    # A cap just above one call: alice is spent, carol is not.
    jreq("PATCH", f"/admin/keys/{eu_key['id']}", {"end_user_daily_limit_usd": 0.000006}, admin=True)
    # max_tokens 1 keeps the pre-flight estimate small, so the cap is exercised without
    # having to spend 0.0003 USD to reach it.
    status, _, alice_body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "end_user": "alice", "max_tokens": 1,
        "messages": [{"role": "user", "content": "again"}]}, key=eu_key_value)
    check("the end-user cap stops a spent end user",
          status == 402 and "alice" in alice_body.get("error", {}).get("message", ""),
          str(status) + str(alice_body)[:120])

    status, _, _ = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "end_user": "carol", "max_tokens": 1,
        "messages": [{"role": "user", "content": "first time"}]}, key=eu_key_value)
    check("another end user on the same key is unaffected", status == 200, str(status))

    # lift the cap again so the alias check is not blocked by it
    jreq("PATCH", f"/admin/keys/{eu_key['id']}", {"end_user_daily_limit_usd": 1000}, admin=True)

    status, _, _ = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "user": "dave",
        "messages": [{"role": "user", "content": "openai style"}]}, key=eu_key_value)
    _, _, dave_usage = jreq("GET", "/v1/usage?window=1h", key=eu_key_value)
    dave_rows = {row["end_user"] for row in dave_usage.get("end_users", [])}
    check("the OpenAI 'user' field is accepted as an alias",
          status == 200 and "dave" in dave_rows, str(dave_rows))

    # ------------------------------------------------------------ token count
    status, _, counted = jreq("POST", "/v1/tokens/count", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "count me"}]}, key=key)
    check("tokens/count reports prompt tokens",
          status == 200 and counted.get("prompt_tokens", 0) > 0
          and counted.get("object") == "token_count", str(status) + str(counted)[:120])

    tools_payload = {"model": "gpt-4o-mini",
                     "messages": [{"role": "user", "content": "count me"}],
                     "tools": [{"type": "function", "function": {
                         "name": "lookup", "description": "look something up",
                         "parameters": {"type": "object", "properties": {}}}}]}
    _, _, with_tools = jreq("POST", "/v1/tokens/count", tools_payload, key=key)
    check("tokens/count counts tool definitions",
          with_tools.get("prompt_tokens", 0) > counted.get("prompt_tokens", 0),
          str(with_tools.get("prompt_tokens")))

    status, _, unknown = jreq("POST", "/v1/tokens/count", {
        "model": "nope-not-real", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("tokens/count rejects an unknown model",
          status == 404 and unknown["error"]["code"] == "model_not_found", str(status))

    # -------------------------------------------------------------- guardrails
    _, _, settings = jreq("GET", "/admin/settings", admin=True)
    guard = settings.get("guardrails", {})
    check("settings report the active guardrails",
          "moderation" in guard and "redact_before_send" in guard, str(settings)[:150])

    status, _, _ = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini",
        "messages": [{"role": "user", "content": "mail me at alice@example.com"}]}, key=key)
    check("a prompt containing PII still succeeds", status == 200, str(status))

    if guard.get("moderation"):
        status, _, flagged = jreq("POST", "/v1/chat/completions", {
            "model": "gpt-4o-mini",
            "messages": [{"role": "user", "content": "please flag-me"}]}, key=key)
        check("moderation blocks a flagged prompt",
              status == 400 and flagged.get("error", {}).get("code") == "content_flagged",
              str(status) + str(flagged)[:120])

        status, _, _ = jreq("POST", "/v1/chat/completions", {
            "model": "gpt-4o-mini",
            "messages": [{"role": "user", "content": "a perfectly ordinary request"}]}, key=key)
        check("moderation lets a clean prompt through", status == 200, str(status))
    elif settings.get("prompt_logging"):
        pass

    # ------------------------------------------------------------ prompt logs
    status, _, plogs = jreq("GET", "/admin/prompt-logs?limit=5", admin=True)
    check("prompt log endpoint responds", status == 200 and isinstance(plogs, list), str(status))

    if settings.get("prompt_logging") and guard.get("prompt_log_redaction"):
        # The PII request is not necessarily in the newest five, so read a wider window.
        _, _, wide = jreq("GET", "/admin/prompt-logs?limit=200", admin=True)
        bodies = " ".join(str(row.get("request", "")) for row in wide)
        check("prompt logs carry no email address", "@example.com" not in bodies, bodies[:160])
        check("prompt logs show the redaction token", "[EMAIL_" in bodies, bodies[:160])

    # ------------------------------------------------------------- allowlist
    _, _, allow_key = jreq("POST", "/admin/keys",
                           {"name": "allowlist-key", "allowed_models": "gpt-4o-mini"}, admin=True)
    ak = allow_key.get("key")
    a1, _, _ = jreq("POST", "/v1/chat/completions",
                    {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]}, key=ak)
    a2, _, ab = jreq("POST", "/v1/chat/completions",
                     {"model": "claude-3.5-haiku", "messages": [{"role": "user", "content": "hi"}]}, key=ak)
    check("model allowlist blocks other models",
          a1 == 200 and a2 == 403 and ab["error"]["code"] == "model_not_allowed", f"{a1}/{a2}")

    # --------------------------------------------------------------- tenancy
    status, _, _ = jreq("POST", "/admin/projects", {"slug": "smoke-team", "name": "Smoke Team"}, admin=True)
    _, _, project_list = jreq("GET", "/admin/projects", admin=True)
    project = next((p for p in project_list if p["slug"] == "smoke-team"), None)
    check("project is created", project is not None, str(status))

    _, _, token = jreq("POST", "/admin/tokens", {"name": "smoke-read", "scopes": "read"}, admin=True)
    read_only = token.get("token")
    check("scoped token is issued once", bool(read_only) and read_only.startswith("adm_"),
          str(token)[:80])

    r1, _, _ = jreq("GET", "/admin/providers", headers={"X-Admin-Token": read_only})
    r2, _, r2body = jreq("POST", "/admin/keys", {"name": "nope"},
                         headers={"X-Admin-Token": read_only})
    check("read-only token can GET but not POST",
          r1 == 200 and r2 == 403, f"{r1}/{r2} {r2body}"[:120])

    if project:
        status, _, scoped_key = jreq("POST", "/admin/keys",
                                     {"name": "project-key", "project_id": project["id"]}, admin=True)
        check("key can belong to a project",
              status == 200 and scoped_key.get("project_id") == project["id"],
              str(status) + str(scoped_key)[:100])
        _, _, scoped_keys = jreq("GET", f"/admin/keys?projectId={project['id']}", admin=True)
        check("keys filter by project",
              any(k.get("name") == "project-key" for k in scoped_keys),
              str(len(scoped_keys)))

        _, _, summary = jreq("GET", f"/admin/projects/{project['id']}/usage", admin=True)
        check("project usage rollup is available",
              "spend_today_usd" in summary and "key_count" in summary, str(summary)[:120])

        # Reset any limit from a previous run, spend once, then set the tiny budget.
        jreq("PATCH", f"/admin/projects/{project['id']}",
             {"daily_limit_usd": 1000, "monthly_limit_usd": 1000}, admin=True)
        p1, _, _ = jreq("POST", "/v1/chat/completions",
                        {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]},
                        key=scoped_key.get("key"))
        jreq("PATCH", f"/admin/projects/{project['id']}",
             {"daily_limit_usd": 0.000001}, admin=True)
        p2, _, p2body = jreq("POST", "/v1/chat/completions",
                             {"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "hi"}]},
                             key=scoped_key.get("key"))
        check("project daily budget blocks its keys",
              p1 == 200 and p2 == 402 and "daily budget" in p2body.get("error", {}).get("message", ""),
              f"{p1}/{p2} {str(p2body)[:100]}")

    # ----------------------------------------------------- project reporting
    if project:
        status, _, scoped_usage = jreq("GET", f"/admin/usage?projectId={project['id']}&window=1h", admin=True)
        rows = scoped_usage.get("data", [])
        _, _, project_keys = jreq("GET", f"/admin/keys?projectId={project['id']}", admin=True)
        project_key_ids = {k["id"] for k in project_keys}
        scoped_ok = status == 200 and scoped_usage.get("project_id") == project["id"]
        if rows:
            scoped_ok = scoped_ok and all(r["key_id"] in project_key_ids for r in rows)
        check("usage can be filtered by project", scoped_ok, str(status) + " rows=" + str(len(rows)))

        status, _, scoped_stats = jreq("GET", f"/admin/stats?projectId={project['id']}&window=1h", admin=True)
        check("stats can be scoped to a project",
              status == 200 and scoped_stats.get("project_id") == project["id"],
              str(scoped_stats)[:120])

        status, _, csv_bytes = req("GET", f"/admin/usage/export?projectId={project['id']}&window=1h", admin=True)
        check("export can be scoped to a project", status == 200 and b"created_at" in csv_bytes, str(status))

        # ------------------------------------------------- project rate limits
        # A dedicated project: the rpm window is per minute, so reusing the budget
        # project would carry a previous run's counts into this one.
        slug = "smoke-rpm-" + str(int(time.time()))
        _, _, rpm_project = jreq("POST", "/admin/projects", {"slug": slug, "name": "Smoke RPM"},
                                 admin=True)
        status, _, second_key = jreq("POST", "/admin/keys",
                                     {"name": "project-key-2", "project_id": rpm_project["id"]},
                                     admin=True)
        check("a project can own several keys", status == 200 and second_key.get("key"), str(status))

        jreq("PATCH", f"/admin/projects/{rpm_project['id']}",
             {"rpm_limit": 2, "max_concurrent_requests": 8}, admin=True)
        codes = []
        for _ in range(3):
            code, _, _ = jreq("POST", "/v1/chat/completions",
                              {"model": "gpt-4o-mini",
                               "messages": [{"role": "user", "content": "hi"}]},
                              key=second_key.get("key"))
            codes.append(code)
        check("project rpm caps every key in the project",
              codes[:2] == [200, 200] and codes[2] == 429, str(codes))

        status, _, limited = jreq("POST", "/v1/chat/completions",
                                  {"model": "gpt-4o-mini",
                                   "messages": [{"role": "user", "content": "hi"}]},
                                  key=second_key.get("key"))
        check("the 429 names the project limit",
              status == 429 and "Project" in limited.get("error", {}).get("message", ""),
              str(limited)[:120])

        jreq("PATCH", f"/admin/projects/{rpm_project['id']}", {"rpm_limit": 0}, admin=True)
        status, _, _ = jreq("POST", "/v1/chat/completions",
                            {"model": "gpt-4o-mini",
                             "messages": [{"role": "user", "content": "hi"}]},
                            key=second_key.get("key"))
        check("zero project rpm means unlimited", status == 200, str(status))

        _, _, refreshed = jreq("GET", "/admin/projects", admin=True)
        row = next((p for p in refreshed if p["id"] == rpm_project["id"]), {})
        check("project limits are reported by the admin API",
              "rpm_limit" in row and "max_concurrent_requests" in row, str(row)[:200])

    # -------------------------------------------------------- master key rotation
    import base64 as _b64
    new_master = _b64.b64encode(b"0123456789abcdef0123456789abcdef").decode()
    status, _, rotated = jreq("POST", "/admin/rotate-master-key", {"new_key": new_master}, admin=True)
    check("master key rotation re-encrypts credentials",
          status == 200 and rotated.get("rotated", 0) >= 1, str(rotated)[:120])

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "gpt-4o-mini", "messages": [{"role": "user", "content": "after rotation"}]}, key=key)
    check("provider credentials still decrypt after rotation", status == 200, str(status) + str(body)[:100])

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

    _, _, unpriced = jreq("GET", "/admin/routes?model=acme-unknown-model", admin=True)
    check("unpriced synced routes stay disabled",
          bool(unpriced) and all(not r["enabled"] for r in unpriced), str(unpriced)[:150])

    status, _, body = jreq("POST", "/v1/chat/completions", {
        "model": "acme-unknown-model", "messages": [{"role": "user", "content": "hi"}]}, key=key)
    check("unpriced synced model is not callable",
          status == 404 and body["error"]["code"] == "model_not_found", str(status))

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
