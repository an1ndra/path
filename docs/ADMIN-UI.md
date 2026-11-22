# Admin UI

There is no UI in this repository: the gateway is a single Java process and stays that way.
The admin console lives in its own repository, [an1ndra/path-admin](https://github.com/an1ndra/path-admin)
(Vite + React, served as static files).

## What it covers

Read and write against this API: dashboard (spend, cache, latency, provider circuits, top
models, recent failures), providers (create/edit with a provider API key, probe, delete),
routes (add a model, enable/disable, delete), client keys (create with budgets and
allowlists, top up, revoke), **configuration** (the gateway switches, alert email and the
daily digest) and **settings** (the session: identity, scopes, token, sign out).

## Auth

The console logs in with an **admin token** (`X-Admin-Token`), kept in `sessionStorage`.
There are no user accounts, passwords or cookies. Mint per-person tokens so actions stay
attributable:

```bash
curl -X POST http://localhost:8080/admin/tokens \
  -H "X-Admin-Token: $GATEWAY_ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"anindra-laptop","scopes":"read,write"}'
```

`scopes` is `read` or `read,write`. Revoke with `POST /admin/tokens/{id}/revoke`.
Every admin write is recorded in `GET /admin/audit`.

`GET /admin/settings` reports the caller's own scopes so the console can adapt instead of
discovering a restriction through a failed write:

```json
{"auth_mode": "token", "subject": "anindra-laptop", "scopes": ["read"], "can_write": false}
```

`subject` is the token name, or the SSO identity in proxy mode. The console hides (or
disables with a tooltip) every create, edit, delete and revoke control when `scopes` does
not contain `write`, and shows a "read-only token" badge. Neither value is a credential.

## Single sign-on

SSO, 2FA and passkeys are delegated to an authenticating proxy — Authentik, Keycloak,
oauth2-proxy or Cloudflare Access — which injects an identity header the gateway trusts.
See [`SSO.md`](SSO.md); the console detects that mode and skips its token screen.

## Hosting it

Two shapes, and the choice decides whether you need CORS:

| Shape | Gateway setting |
|---|---|
| **Same hostname** — proxy `/admin-ui/*` to the UI and everything else to the gateway | nothing |
| **Separate hostname** — `admin.example.com` → UI, `gw.example.com` → gateway | `GATEWAY_CORS_ORIGINS=https://admin.example.com` |

The console needs `X-Admin-Token` to pass through the preflight; the default CORS mapping
already allows `*` headers and the admin methods, and it must **not** enable credentials
because the token is a header, not a cookie.

Do not expose `/admin/**` to the internet without something in front of it — Cloudflare
Access, a VPN, or an IP allowlist. The admin auth limiter (10 failures / 5 min) is a
backstop, not an access control.

## Related

- [`PUBLISHING.md`](PUBLISHING.md) — Maven Central
- [`OPERATIONS.md`](OPERATIONS.md) — admin endpoints, metrics, migrations
