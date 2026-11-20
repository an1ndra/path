# Path Helm chart

Path is an I/O-bound HTTP service with one JVM per pod, a database for keys and usage,
and — in a cluster — Valkey for shared rate limiting.

```bash
helm install path deploy/helm/path \
  --namespace path --create-namespace \
  --set existingSecret=path-credentials

kubectl -n path port-forward svc/path 8080:8080
curl -s localhost:8080/actuator/health
curl -s -H "X-Admin-Token: $GATEWAY_ADMIN_TOKEN" localhost:8080/actuator/health/providers
```

## What the chart makes hard to get wrong

| Decision | Why it is in the chart, not in a comment |
|---|---|
| `terminationGracePeriodSeconds` > `GATEWAY_DRAIN_TIMEOUT` | Path drains in-flight SSE streams on SIGTERM. If the grace period is shorter, a rolling update truncates them. The template **fails to render** when the relation is violated. |
| `maxUnavailable: 0`, `maxSurge: 1` | A chat gateway should not lose capacity during a deploy while it is also draining. |
| Readiness on `/actuator/health` | A single parked provider must not remove the pod from the Service: fallback still works. Only a total outage reports `OUT_OF_SERVICE`. |
| `emptyDir` for `/app/data` | That directory holds the H2 database and `master.key`. With Postgres those are not needed, and an `emptyDir` makes it obvious rather than silently losing a key on reschedule. |
| No inline Secret by default | `existingSecret` is required unless `secrets` is set, because a chart-managed Secret is a bad place for `GATEWAY_MASTER_KEY`. |

## Values worth changing first

- `replicaCount` (default 2) — below 2 there is no disruption budget to honour.
- `config.dbUrl` — point it at a managed Postgres; `postgres.enabled` exists for a demo.
- `valkey.enabled` — without it, rate limits are per pod and the effective limit is
  `replicaCount × rpm`. Budget reservations are database-backed either way, so money is
  safe without it.
- `autoscaling` — CPU is a poor signal for a gateway. If you have the Prometheus adapter,
  scale on in-flight requests instead.
- `prometheusRule.enabled` — renders the fallback/cache/spend/error/parked alerts from
  `deploy/prometheus/path-rules.yml` as a `PrometheusRule`. Needs the Prometheus Operator
  CRDs; `runbookBase` sets where the annotations point.

## What this chart does not do

- No ingress or TLS: terminate at your own gateway, and keep `GATEWAY_API_DOCS=false`.
- No migrations Job: Flyway runs at startup. With `replicaCount > 1` and a fresh database,
  Flyway takes a lock, so the pods serialise; that is slow but correct. For a large
  schema, run `./mvnw flyway:migrate` as a pre-install Job instead.
