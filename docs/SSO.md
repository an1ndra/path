# SSO, 2FA and passkeys

Path does not implement an identity provider, and it should not. Google/GitHub/enterprise
SSO, TOTP 2FA and passkeys are all **delegated to a reverse proxy that authenticates the
human** and then tells the gateway who it is:

```
browser → IdP (Authentik, Keycloak, oauth2-proxy, Cloudflare Access)
        → proxy injects X-Forwarded-User: anindra@example.com
        → gateway trusts that header for /admin/**
```

That gives you passkeys and 2FA in an afternoon, from software that already does them
properly, instead of a home-grown WebAuthn/TOTP implementation in an LLM gateway.

> **The one rule:** `GATEWAY_PROXY_AUTH=true` means `/admin/**` accepts anything behind the
> proxy without a token. If the gateway is also reachable directly, anyone can send the
> header themselves and own the gateway. Bind it to loopback or a private network, or make
> the proxy the only ingress.

## Gateway configuration

```yaml
GATEWAY_PROXY_AUTH: "true"
GATEWAY_PROXY_AUTH_HEADER: X-Forwarded-User     # or Cf-Access-Authenticated-User-Email
GATEWAY_PROXY_AUTH_SCOPES: read,write           # use "read" for a viewer group
```

On startup the log says, once:

```
WARN  Admin auth is DELEGATED to a reverse proxy (X-Forwarded-User): the gateway trusts
      that header for /admin/**. It must not be reachable except through the proxy.
```

`GET /admin/settings` reports `"auth_mode": "proxy"`, and the admin console uses that to
skip its token screen — a signed-in browser lands straight on the dashboard.

Every admin write is audited against the identity the proxy supplied, so
`GET /admin/audit` answers "who changed this" with an email, not "shared-admin".

## Authentik (recommended, self-hosted with Dokploy)

1. Authentik → **Providers → Create → Proxy**, mode *Forward auth (single application)*.
2. **Applications → Create**, bind the provider, attach a policy (your user or group).
3. In Dokploy, add a Traefik middleware on the UI *and* the gateway:

```yaml
# Traefik labels on the gateway service
- "traefik.http.middlewares.path-auth.forwardauth.address=http://authentik:9000/outpost.goauthentik.io/auth/traefik"
- "traefik.http.middlewares.path-auth.forwardauth.authResponseHeaders=X-Forwarded-User"
- "traefik.http.middlewares.path-auth.forwardauth.trustForwardHeader=true"
- "traefik.http.routers.path.middlewares=path-auth"
```

Authentik's flow can require **passkeys/WebAuthn** and **TOTP**, set per stage. Nothing in
Path changes.

## Keycloak / any OIDC provider

Same shape with `oauth2-proxy` in forward-auth mode:

```bash
oauth2-proxy --provider=oidc \
  --oidc-issuer-url=https://keycloak.example.com/realms/main \
  --email-domain=example.com \
  --set-xauthrequest --pass-user-headers \
  --upstream=http://path:8080/
```

`--pass-user-headers` sets `X-Forwarded-User`; Keycloak's authentication flows supply 2FA
and WebAuthn.

## Cloudflare Access

If the gateway is behind Cloudflare Tunnel, Access can authenticate with Google/GitHub/
SAML and inject:

```
Cf-Access-Authenticated-User-Email: anindra@example.com
```

Set `GATEWAY_PROXY_AUTH_HEADER=Cf-Access-Authenticated-User-Email`. Access supports
one-time PIN and SSO; passkeys come from the identity provider behind it.

**Do not** stop here: a request that reaches the origin without Cloudflare can spoof that
header. Cloudflare Tunnel with `cloudflared` (no public origin) is what makes it safe, and
optionally verify the `Cf-Access-Jwt-Assertion` signature at the gateway.

## What the console does

| Auth mode | Login screen | Sign out |
|---|---|---|
| `token` (default) | paste an admin token | clears it from sessionStorage |
| `proxy` | skipped — the proxy already authenticated the browser | handled by the IdP / proxy |

## What is still token-only

`/v1/**` (client keys) and Maven Central publishing. SSO covers the human-facing admin
surface, not machine credentials.
