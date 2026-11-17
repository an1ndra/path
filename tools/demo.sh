#!/usr/bin/env bash
# 60-second demo of Path against a mock upstream. No API keys required.
#
#   bash tools/demo.sh
#
# Starts the mock provider and the gateway, then walks through catalogue, chat,
# streaming, the response cache and usage metering, and cleans up afterwards.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
JAR="$(ls "$ROOT"/target/path-*.jar 2>/dev/null | head -1 || true)"
MOCK_PORT="${MOCK_PORT:-9099}"
PORT="${PORT:-8080}"
ADMIN="demo-admin"
LOG="$(mktemp -d)"

bold() { printf '\n\033[1m%s\033[0m\n' "$1"; }

cleanup() {
  [[ -n "${GATEWAY_PID:-}" ]] && kill "$GATEWAY_PID" 2>/dev/null || true
  [[ -n "${MOCK_PID:-}" ]] && kill "$MOCK_PID" 2>/dev/null || true
  rm -rf "$LOG"
}
trap cleanup EXIT

if [[ -z "$JAR" ]]; then
  echo "Build first: ./mvnw -DskipTests package" >&2
  exit 1
fi

bold "1. starting the mock upstream and the gateway"
python3 "$ROOT/tools/mock-upstream.py" "$MOCK_PORT" > "$LOG/mock.log" 2>&1 &
MOCK_PID=$!
(cd "$ROOT" && rm -rf data && exec env GATEWAY_ADMIN_TOKEN="$ADMIN" GATEWAY_BOOTSTRAP_DEMO_KEY=true \
  java -jar "$JAR" --server.port="$PORT" > "$LOG/gateway.log" 2>&1) &
GATEWAY_PID=$!

for _ in $(seq 1 60); do
  curl -sf "http://127.0.0.1:$PORT/actuator/health" > /dev/null 2>&1 && break
  sleep 1
done

KEY="$(grep -o 'sk-gw-[A-Za-z0-9]*' "$LOG/gateway.log" | head -1)"
[[ -n "$KEY" ]] || { echo "gateway did not start; see $LOG/gateway.log" >&2; exit 1; }
AUTH=(-H "Authorization: Bearer $KEY" -H 'Content-Type: application/json')
API="http://127.0.0.1:$PORT"

# Point the seeded providers at the mock so the demo spends nothing.
for id in 1 2 3 4; do
  curl -s -X PATCH "$API/admin/providers/$id" -H "X-Admin-Token: $ADMIN" \
    -H 'Content-Type: application/json' \
    -d "{\"base_url\":\"http://127.0.0.1:$MOCK_PORT/v1\",\"api_key\":\"demo\"}" > /dev/null
done

bold "2. the catalogue (aliases included)"
curl -s "$API/v1/models" | python3 -c "
import json,sys
for m in json.load(sys.stdin)['data']:
    label = m['id'] + (' -> ' + m['resolves_to'] if m.get('resolves_to') else '')
    print('  ', label)
"

bold "3. chat through the auto-fast alias"
curl -s -D "$LOG/headers" "$API/v1/chat/completions" "${AUTH[@]}" \
  -d '{"model":"auto-fast","messages":[{"role":"user","content":"Say hi in five words."}]}' \
  | python3 -c "import json,sys; print('  ', json.load(sys.stdin)['choices'][0]['message']['content'])"
grep -iE "x-gateway-(provider|cost-usd)" "$LOG/headers" | sed 's/^/  /'

bold "4. streaming"
curl -sN "$API/v1/chat/completions" "${AUTH[@]}" \
  -d '{"model":"gpt-4o-mini","stream":true,"messages":[{"role":"user","content":"Count to three."}]}' \
  | grep '^data:' | head -6 | sed 's/^/  /'

bold "5. the same deterministic request twice (response cache)"
CACHED='{"model":"gpt-4o-mini","temperature":0,"messages":[{"role":"user","content":"cache me"}]}'
for attempt in 1 2; do
  curl -s -o /dev/null -D "$LOG/cache" "$API/v1/chat/completions" "${AUTH[@]}" -d "$CACHED"
  printf '  attempt %s: %s\n' "$attempt" "$(grep -i '^x-path-cache' "$LOG/cache" | tr -d '\r')"
done
# The route must opt in first; enable it and repeat.
ROUTE_ID="$(curl -s "$API/admin/routes?model=gpt-4o-mini" -H "X-Admin-Token: $ADMIN" \
  | python3 -c "import json,sys; print(json.load(sys.stdin)[0]['id'])")"
curl -s -X PATCH "$API/admin/routes/$ROUTE_ID" -H "X-Admin-Token: $ADMIN" \
  -H 'Content-Type: application/json' -d '{"cache_enabled":true}' > /dev/null
for attempt in 1 2; do
  curl -s -o /dev/null -D "$LOG/cache" "$API/v1/chat/completions" "${AUTH[@]}" -d "$CACHED"
  printf '  cached run %s: %s %s\n' "$attempt" \
    "$(grep -i '^x-path-cache' "$LOG/cache" | tr -d '\r')" \
    "$(grep -i '^x-gateway-cost-usd' "$LOG/cache" | tr -d '\r')"
done

bold "6. usage for the key"
curl -s "$API/v1/usage?window=1h" "${AUTH[@]}" | python3 -c "
import json,sys
d = json.load(sys.stdin)
print('  requests:', d['requests'], '| tokens:', d['total_tokens'], '| cost:', d['cost_usd'])
print('  cached tokens:', d['cached_input_tokens'], '| savings:', d['cached_savings_usd'])
"

bold "done - shutting down"
