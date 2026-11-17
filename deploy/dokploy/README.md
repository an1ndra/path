# Deploy Path on Dokploy

Two applications, because the console is a separate repository:

| App | Repository | Dockerfile | Port | Health |
|---|---|---|---|---|
| gateway | `an1ndra/path` | `Dockerfile` (repo root) | 8080 | `/actuator/health` |
| console | `an1ndra/path-admin` | `Dockerfile` (repo root) | 8080 | `/healthz` |

Either deploy the gateway as a **Compose** stack (`compose.yml` here, brings Postgres and
optional Valkey) or as a plain **Application** pointing at the Dockerfile with an external
database. The console is always a plain Application.

## 1. Gateway

### Compose route (recommended: includes Postgres)

1. Dokploy → **Create → Compose**.
2. Source: GitHub → `an1ndra/path`, branch `main`.
3. Compose path: `deploy/dokploy/compose.yml`.
4. **Environment** (the compose file references all of these):

```bash
POSTGRES_PASSWORD=<openssl rand -hex 24>
GATEWAY_MASTER_KEY=<openssl rand -base64 32>     # keep it: it decrypts stored provider keys
GATEWAY_ADMIN_TOKEN=<openssl rand -hex 32>      # only for token auth; ignore with SSO
GATEWAY_CORS_ORIGINS=https://admin.example.com  # omit if same hostname as the console
```

5. Domain: point `gw.example.com` at port **8080**, enable HTTPS.
6. Deploy. First boot logs `Gateway ready. Admin API: enabled` and, if `-P release` was
   used, seeds four providers.

### Application route (external Postgres)

Create → **Application** → repository `an1ndra/path`, build type **Dockerfile**, port
**8080**, then:

```bash
SPRING_PROFILES_ACTIVE=postgres
DB_URL=jdbc:postgresql://<host>:5432/path
DB_USER=path
DB_PASSWORD=<...>
GATEWAY_MASTER_KEY=<openssl rand -base64 32>
GATEWAY_API_DOCS=false
```

Mount a volume at `/app/data` if you'd rather keep the H2 database and the master key on
disk than use Postgres.

## 2. Console

1. Dokploy → **Create → Application**, repository `an1ndra/path-admin`, branch `main`.
2. Build: **Dockerfile** (node build stage → nginx). Port **8080**. Health: **`/healthz`**.
3. Environment — one variable:

```bash
# Separate hostnames:
GATEWAY_URL=https://gw.example.com

# Or leave it EMPTY and route /admin-ui to this container on the gateway's hostname,
# which removes CORS entirely.
GATEWAY_URL=
```

4. Domain: `admin.example.com` (or a path prefix on the gateway domain) and HTTPS.

## 3. Wiring, and the one setting people forget

If the console is on a **different hostname** from the gateway, the gateway must allow it:

```bash
GATEWAY_CORS_ORIGINS=https://admin.example.com
```

Exact origin, no trailing slash, no wildcard. `X-Admin-Token` is a custom header, so the
browser sends a preflight; `PUT` is allowed as of 0.2.3, which the console's settings screen
needs.

Same-hostname routing on Dokploy/Traefik:

```
Host(`path.example.com`) && PathPrefix(`/admin-ui`)  → console:8080  (strip prefix)
Host(`path.example.com`)                             → gateway:8080
```

## 4. Before it is public

- Put the console behind **Cloudflare Access**, Authentik or a VPN. A pasted admin token has
  no second factor; see `docs/SSO.md` for the proxy-auth mode that fixes that with
  `GATEWAY_PROXY_AUTH=true`.
- `GATEWAY_API_DOCS=false` in production (the compose file sets it).
- Keep `GATEWAY_MASTER_KEY` in your password manager. Losing it means re-entering every
  provider credential.
- `stop_grace_period` must exceed `GATEWAY_DRAIN_TIMEOUT`, or a redeploy truncates live SSE
  streams (the compose file uses 40s against 25s).

## 5. Smoke test after deploy

```bash
curl -fsS https://gw.example.com/actuator/health          # {"status":"UP"}
curl -fsS https://admin.example.com/healthz               # ok
curl -fsS -H "X-Admin-Token: $TOKEN" https://gw.example.com/admin/settings
```

Then open the console, sign in, and **Settings → Alert email → Send test email**.
