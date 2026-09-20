# Running the platform on Kubernetes (minikube)

This is the Helm path from [ADR-0008](../adr/0008-helm-primary-kustomize-deferred.md), end to
end: build the four service images, install one umbrella chart, watch eight pods go Ready,
and run the same `make demo` against the cluster that you would run against compose — a
confirmed order and a payment-decline compensation, through the gateway.

Everything below was executed on the machine this document was written on. The outputs are
real outputs, trimmed for width, not illustrations.

> **Prefer compose for day-to-day work.** `make up` is faster, and it is the source of truth
> for configuration. This path exists to show the platform deploys to Kubernetes, and to give
> the charts somewhere to be exercised. The two are deliberately kept in step: the Kubernetes
> Service names are the same names compose uses, so the environment values are identical
> strings rather than translations.

## Contents

1. [Prerequisites](#1-prerequisites)
2. [Start the cluster](#2-start-the-cluster)
3. [Get the images into the cluster](#3-get-the-images-into-the-cluster)
4. [Install the chart](#4-install-the-chart)
5. [Wait until everything is Ready](#5-wait-until-everything-is-ready)
6. [Reach the platform](#6-reach-the-platform)
7. [Run the demo against the cluster](#7-run-the-demo-against-the-cluster)
8. [Follow one saga across the pods](#8-follow-one-saga-across-the-pods)
9. [Teardown](#9-teardown)
10. [Troubleshooting](#10-troubleshooting)
11. [What the charts contain](#11-what-the-charts-contain)

## 1. Prerequisites

| Tool | Version used here | Notes |
|---|---|---|
| minikube | v1.33.1 | docker driver |
| kubectl | v1.36.3 | newer than the v1.30.0 the cluster runs; fine for these objects |
| helm | v3.12.1 | Helm 3 is required — the library chart is `type: library` |
| Docker | 29.8.1 | builds the images and hosts the minikube node |
| Java 21, Maven wrapper | — | only if you want to rebuild the jars |
| `jq`, `curl` | — | used by `scripts/demo.sh` |

You also need the host resources in §2. Nothing else: no registry, no ingress controller, no
metrics-server, no operators.

## 2. Start the cluster

A dedicated profile, so an existing `minikube` profile on the machine is left alone:

```bash
minikube start -p edmp --driver=docker --memory=8192 --cpus=4 --disk-size=30g
```

```
* [edmp] minikube v1.33.1 on Ubuntu 22.04
* Using the docker driver based on user configuration
* Starting "edmp" primary control-plane node in "edmp" cluster
* Pulling base image v0.0.44 ...
* Creating docker container (CPUs=4, Memory=8192MB) ...
* Preparing Kubernetes v1.30.0 on Docker 26.1.1 ...
* Enabled addons: storage-provisioner, default-storageclass
* Done! kubectl is now configured to use "edmp" cluster
```

On a machine that has never run this profile, `Pulling base image` is the slow line — about a
minute — and the whole start took ~70 s. Afterwards `minikube start -p edmp` reuses it.

`minikube start` switches the kubeconfig context itself; the explicit switch is only needed if
you have been working in another cluster since:

```bash
kubectl config use-context edmp
kubectl get nodes
```

```
NAME   STATUS   ROLES           AGE   VERSION
edmp   Ready    control-plane   10s   v1.30.0
```

### How much does it actually need?

Measured with all eight pods Ready and the demo just finished. metrics-server is not installed,
so these come from each container's cgroup counter, cross-checked against `crictl stats`
(the two agreed within 1 MiB):

| Component | Actual | Request | Limit |
|---|---|---|---|
| keycloak | 784 MiB | 384Mi | 1Gi |
| kafka | 509 MiB | 512Mi | 1Gi |
| inventory-service | 424 MiB | 192Mi | 768Mi |
| order-service | 378 MiB | 192Mi | 768Mi |
| payment-service | 360 MiB | 192Mi | 768Mi |
| api-gateway | 196 MiB | 160Mi | 512Mi |
| postgres | 100 MiB | 128Mi | 512Mi |
| redis | 4 MiB | 32Mi | 128Mi |
| **platform total** | **2754 MiB** | 1792Mi | 5504Mi |
| Kubernetes control plane | 494 MiB | — | — |
| **everything** | **≈3.2 GiB** | | |

Cross-checked from the host on a fresh install with the demo just finished:
`docker stats --no-stream edmp` → `3.211GiB / 8GiB (40.14%)`, which is the same number seen
from the other side. The requests in that table add up to exactly what the cluster reports:
`1792Mi` of memory and `850m` of CPU across the eight pods.

So of the 8 GiB given to the node, about 3.2 GiB is genuinely in use:

| `--memory` | Verdict |
|---|---|
| 4096 | Fits, with ~550 MiB spare. No room for a JVM heap to grow; fine for a quick check |
| 6144 | The comfortable floor — measured baseline plus ~50% |
| 8192 | What this runbook uses. Leaves room for load and for Phase 2/3 services |

Two honest notes about the measurements:

- The four services and Keycloak all sit **above their memory requests** once their JVMs are
  warm. Requests are sized for scheduling, not for steady state, and the pods are Burstable by
  design — but it means a node sized to the requests would not actually hold this platform.
  Keycloak has the least headroom (23% of its limit free) and is the first thing to raise.
- `--cpus=4` is recorded by minikube and advertised as the node's capacity, but on this host the
  docker driver set no CPU quota and no cpuset, so `nproc` inside the node reports all 8 host
  cores and nothing is actually throttled (`nr_throttled=0` everywhere). Treat the CPU number as
  a scheduling hint here, not a limit.

Disk: `--disk-size=30g` was sufficient — all eight images present (four loaded, four pulled by
the install), a PVC-backed Postgres and the full run produced no disk-pressure events. A precise "used of 30 GiB" figure is not meaningful
with the docker driver, because the node's filesystem is an overlay on the host's disk rather
than a separate volume, so `df` inside the node reports the host's usage.

The `default-storageclass` and `storage-provisioner` addons minikube enables by default are the
only ones needed; the Postgres PVC uses `standard`.

## 3. Get the images into the cluster

The four images are built from the repository root (the Dockerfiles are multi-module aware and
need the parent POM and `shared/*`). Build them with the same compose file a developer already
uses, so there is one definition of how an image is built:

```bash
docker compose -f deploy/local/compose.yaml build
```

Then load them into the cluster's own container daemon. `imagePullPolicy: IfNotPresent` in
`values-minikube.yaml` is what makes the cluster use them; no registry has ever heard of
`edmp/*`:

```bash
make k8s-images        # minikube -p edmp image load edmp/<service>:local, for all four
```

```
loading edmp/api-gateway:local
loading edmp/order-service:local
loading edmp/payment-service:local
loading edmp/inventory-service:local
```

That takes about 85 seconds for the four (they are 270–400 MB each) and prints nothing else.
It only *loads*: the images must already exist in the host daemon from the `docker compose
build` above, or the load fails with `Failed to load image: ... not found`.

Loading the four infrastructure images too is optional but makes the install deterministic and
much faster, and lets it work offline:

```bash
for i in postgres:17.6 apache/kafka:4.1.2 redis:7-alpine quay.io/keycloak/keycloak:26.4; do
  minikube -p edmp image load "$i"
done
minikube -p edmp image ls | grep -E 'edmp/|postgres|kafka|redis|keycloak' | sort
```

```
docker.io/apache/kafka:4.1.2
docker.io/edmp/api-gateway:local
docker.io/edmp/inventory-service:local
docker.io/edmp/order-service:local
docker.io/edmp/payment-service:local
docker.io/library/postgres:17.6
docker.io/library/redis:7-alpine
quay.io/keycloak/keycloak:26.4
```

Skipping it is fine — the install below pulls those four instead. That is what the run in §5
did, and it cost roughly 100 seconds of the 3m45s first install. What it does change is what
§5 looks like while you watch it: the three database-backed services crash-loop until Postgres
finishes pulling.

The alternative — `eval $(minikube -p edmp docker-env)` and then `docker compose build` — works
too, and skips the load step by building straight into the cluster's daemon. It is slower here
because that daemon has no layer cache and no Maven repository, so every build downloads the
whole dependency tree again.

> **Rebuilt a service?** `image load` replaces the image but running pods keep the one they
> started with. `kubectl -n edmp rollout restart deploy/<service>` picks it up.

## 4. Install the chart

```bash
make k8s-deps          # helm dependency update for the four service charts, then the umbrella
```

Then install. Three `--set-file` flags feed the chart the realm export and the two Postgres init
files **from the files the compose stack already uses**, instead of copies living inside the
chart — one copy of each in the repository, so they cannot drift:

```bash
helm upgrade --install platform deploy/helm/platform-umbrella \
  -n edmp --create-namespace \
  -f deploy/helm/platform-umbrella/values-minikube.yaml \
  --set-file postgres.initSql=deploy/local/postgres-init/01-extensions.sql \
  --set-file postgres.initRolesSh=deploy/local/postgres-init/02-service-roles.sh \
  --set-file keycloak.realmJson=services/auth-service/realm/order-platform-realm.json \
  --wait --timeout 10m
```

`make k8s-install` is exactly that command. Both are idempotent — run them again after editing
a chart. On a cluster created minutes earlier, with the infrastructure images *not* preloaded,
`--wait` returned after **3m45s**; `--timeout 10m` is therefore generous rather than tight, and
the wait is dominated by pulling `postgres`, `kafka`, `redis` and `keycloak`.

> **Upgrading a cluster installed before the per-service DB roles existed?** The init files run
> only when Postgres initializes an empty data directory, and the PVC survives `helm uninstall`.
> On such a cluster `order_svc` / `payment_svc` / `inventory_svc` do not exist yet and the
> services will fail to authenticate. Delete the volume and let it reinitialize:
> `helm uninstall platform -n edmp && kubectl -n edmp delete pvc data-postgres-0`, then install
> again. (This destroys the dev database — which is the intent here.)
>
> The same applies to *any* redeploy where you expect a changed `02-service-roles.sh` to take
> effect: reinstalling over a surviving `data-postgres-0` silently keeps the old roles and
> grants. `helm upgrade` cannot fix a database that was already initialized — only a new volume
> can. If in doubt, `kubectl -n edmp get pvc data-postgres-0 -o jsonpath='{.metadata.creationTimestamp}'`
> tells you how old the data really is.

### Which role each service authenticates as

Each service gets its own login role and only its own password. The usernames are plain
(non-secret) env values in each service chart; only the passwords come from the Secret:

| Service | DB role | Schema | Username env | Password env | Secret key (`platform-secrets`) |
|---|---|---|---|---|---|
| order-service | `order_svc` | `public` | `POSTGRES_ORDER_USER` | `POSTGRES_ORDER_PASSWORD` | `postgres-order-password` |
| payment-service | `payment_svc` | `payment` | `POSTGRES_PAYMENT_USER` | `POSTGRES_PAYMENT_PASSWORD` | `postgres-payment-password` |
| inventory-service | `inventory_svc` | `inventory` | `POSTGRES_INVENTORY_USER` | `POSTGRES_INVENTORY_PASSWORD` | `postgres-inventory-password` |
| postgres (bootstrap) | `appuser` | owns the database | `POSTGRES_USER` | `POSTGRES_PASSWORD` | `postgres-app-password` |

All three share one `SPRING_DATASOURCE_URL` — `jdbc:postgresql://postgres:5432/orderdb`. The
username/password pair is what differs, and §8 checks it on the cluster rather than trusting it.

Passwords come from a `Secret` the chart creates with the same dev placeholders as
`.env.example` — one key per DB role, so each service mounts only its own credential. Override
them, or bring your own Secret:

```bash
#   --set secrets.postgresAppPassword=...       # bootstrap/admin role
#   --set secrets.postgresOrderPassword=...     # order_svc
#   --set secrets.postgresPaymentPassword=...   # payment_svc
#   --set secrets.postgresInventoryPassword=... # inventory_svc
#   --set secrets.keycloakAdminPassword=...
#   --set secrets.create=false                  # then create `platform-secrets` yourself
```

To check the chart without a cluster at all — useful in CI:

```bash
make k8s-lint          # helm lint + helm template, no cluster needed
```

## 5. Wait until everything is Ready

```bash
kubectl -n edmp get pods -w
```

Ninety seconds into a first install, with the infrastructure images being pulled, it looks
broken and is not:

```
NAME                                 READY   STATUS              RESTARTS     AGE
api-gateway-5ccd488f47-2v95p         1/1     Running             0            91s
inventory-service-7dd4859bcb-2xvgl   0/1     CrashLoopBackOff    2 (5s ago)   91s
kafka-0                              1/1     Running             0            91s
keycloak-7c7bfb99-mtld4              1/1     Running             0            91s
order-service-7d68c5d4c-b2r5q        0/1     CrashLoopBackOff    2 (7s ago)   91s
payment-service-746fdcd98d-csbpl     0/1     CrashLoopBackOff    2 (7s ago)   91s
postgres-0                           0/1     ContainerCreating   0            91s
redis-58bbf87555-6mshm               1/1     Running             0            91s
```

`postgres-0` is still `ContainerCreating` — pulling `postgres:17.6` — and the three services
that run Flyway at startup cannot open a connection, so they exit and Kubernetes backs them off.
Nothing needs doing: the backoff outlives the pull. Two minutes later the same install is
complete, with the restarts left behind as evidence:

```
NAME                                 READY   STATUS    RESTARTS        AGE
api-gateway-5ccd488f47-2v95p         1/1     Running   0               3m38s
inventory-service-7dd4859bcb-2xvgl   1/1     Running   3 (2m12s ago)   3m38s
kafka-0                              1/1     Running   0               3m38s
keycloak-7c7bfb99-mtld4              1/1     Running   0               3m38s
order-service-7d68c5d4c-b2r5q        1/1     Running   3 (2m14s ago)   3m38s
payment-service-746fdcd98d-csbpl     1/1     Running   3 (2m14s ago)   3m38s
postgres-0                           1/1     Running   0               3m38s
redis-58bbf87555-6mshm               1/1     Running   0               3m38s
```

Three restarts each on a cold install, zero if you preloaded the infrastructure images in §3.
`RESTARTS` climbing *after* Postgres is Ready is a different problem — read the logs then.

Expect 60–90 seconds for the four JVM services once Postgres and Kafka are already up: each
runs Flyway against Postgres and joins a Kafka consumer group before it reports ready.

### How to read readiness here

`READY 1/1` means the pod passed `/actuator/health/readiness`, which is **not** the same as
`/actuator/health`:

```bash
kubectl -n edmp exec deploy/order-service -- wget -qO - localhost:8080/actuator/health/readiness
kubectl -n edmp exec deploy/order-service -- wget -qO - localhost:8080/actuator/health \
  | python3 -c "import json,sys; print(sorted(json.load(sys.stdin)['components'].keys()))"
```

```
{"status":"UP"}
['db', 'diskSpace', 'livenessState', 'ping', 'readinessState', 'ssl']
```

The readiness group contains the application's own readiness state and nothing else. The
aggregate endpoint contains the dependency indicators — note `db`. Probing the aggregate would
therefore mean a Postgres hiccup removes every pod from its Service simultaneously, turning a
recoverable blip into an outage, which is the failure mode
[ADR-0014](../adr/0014-message-delivery-semantics.md) is about. Liveness is separate again: a
service that cannot reach a dependency is not wedged, it is retrying, and restarting it does not
help.

That grouping is now **stated** rather than inherited: every service sets
`management.endpoint.health.group.readiness.include: readinessState` in its `application.yml`.
It was previously only Spring Boot's default grouping, so the invariant the whole deployment
depends on was written down nowhere and a future framework default could have moved `db` into
readiness silently. Readiness answers "is this application up and able to serve", not "is every
dependency healthy" — the platform handles transient dependency failure at the application level
instead (consumer retries, the transactional outbox, idempotent replay).

(Worth knowing: on Spring Boot 4.1.1 there is **no** Kafka health indicator in that component
list — the broker cannot affect either endpoint today. The separation still matters for `db`, and
it means the readiness contract does not change if a Kafka indicator appears in a later version.)

| Probe | Path | What it means |
|---|---|---|
| `startupProbe` | `/actuator/health/readiness` | still migrating/joining; be patient (up to 3 min) |
| `readinessProbe` | `/actuator/health/readiness` | can serve traffic |
| `livenessProbe` | `/actuator/health/liveness` | the JVM is wedged; restart it |

Infrastructure is probed on its own terms: `pg_isready` for Postgres, `redis-cli ping` for
Redis, the realm's own OIDC discovery document for Keycloak (a port check would report ready
before the realm import finished, and then tokens would 404), and a socket check for Kafka —
see [§10](#10-troubleshooting) for why that one is not the richer check compose uses.

## 6. Reach the platform

The gateway is the only entry point ([ADR-0009](../adr/0009-per-service-jwt-resource-server.md)),
so it is the only thing that needs forwarding — plus Keycloak, because the demo fetches a real
token. Use the same ports compose publishes and every script works with no configuration:

```bash
kubectl -n edmp port-forward svc/api-gateway 8090:8090 &
kubectl -n edmp port-forward svc/keycloak 8180:8080 &
```

```bash
curl -s localhost:8090/actuator/health | jq -r .status
```

```
UP
```

### Why a token from localhost is accepted inside the cluster

This is the part that usually breaks, so it is worth being explicit. Keycloak stamps the `iss`
claim from `KC_HOSTNAME`, which the chart pins to the **in-cluster** address, and the services
compare `iss` by exact string equality. A token fetched through the port-forward therefore
still carries the in-cluster issuer:

```bash
./scripts/get-token.sh | cut -d. -f2 | tr '_-' '/+' | base64 -d | jq -r '.iss, .aud, .scope'
```

```
http://keycloak:8080/realms/order-platform
order-platform
profile orders:write email orders:read
```

`http://keycloak:8080/realms/order-platform` is the same string the four services are
configured with, and the same string compose uses. Setting `keycloak.hostname` to something
externally reachable would break every request with a 401 — so the chart refuses to render if
the two sides disagree:

```
Error: execution error at (platform-umbrella/templates/validate.yaml:20:4): issuer mismatch:
order-service has JWT_ISSUER_URI="http://keycloak:8080/realms/order-platform" but Keycloak will
stamp iss="http://keycloak.edmp.svc.cluster.local:8080/realms/order-platform" ... Every token
would be rejected. Set them to the same string.
```

## 7. Run the demo against the cluster

No changes and no environment variables — the scripts take the gateway and issuer URLs from
variables whose defaults are the ports forwarded above:

```bash
make demo
```

```
==> Checking the stack is up
  PASS  api-gateway is up (UP)

==> Getting an access token from Keycloak
  PASS  token for demo-customer (sub 8c557e7b-470b-43b3-a49b-111db452cba6)
scopes: orders:write email orders:read profile   audience: order-platform

==> Scenario 1/2 — happy path (expect CONFIRMED)
response : HTTP 202  Location: /api/v1/orders/5f893832-0979-4af3-ab6c-6bce9e5591c0
  PASS  202 Accepted, order 5f893832-0979-4af3-ab6c-6bce9e5591c0
correlationId: b146e751-c494-49eb-9ea4-854ffeef8a5d
  polling ... (reserve -> authorize -> capture -> confirm)...
  PASS  order reached CONFIRMED
        {"orderId":"5f893832-...","status":"CONFIRMED","reason":null,
         "totalAmount":{"minorUnits":3998,"currency":"USD"}, ...}

==> Scenario 2/2 — payment decline (expect CANCELLED / PAYMENT_DECLINED)
response : HTTP 202  Location: /api/v1/orders/db4c2ea3-c5b2-4a55-ad5a-067856caa92b
  PASS  202 Accepted, order db4c2ea3-c5b2-4a55-ad5a-067856caa92b
correlationId: 066dbc2f-2dd1-409d-97e0-7673d4f8c2d0
  polling ... (reserve -> decline -> release -> cancel)...
  PASS  order reached CANCELLED with reason PAYMENT_DECLINED (stock released)

==> Result
  DEMO PASSED — CONFIRMED and CANCELLED(PAYMENT_DECLINED) both observed through the gateway.
```

Both scenarios together take about 6 seconds against the cluster. The scope list arrives in
whatever order Keycloak returns it, so treat that line as a set.

The closing hints the script prints are compose-flavoured (`docker compose ... logs | grep`),
because the same script serves both stacks. The Kubernetes equivalent is §8; the correlationIds
it prints are the same ones.

`make smoke` is the faster check (edge alive, token works, an order is accepted) and does not
wait for a terminal saga state.

## 8. Follow one saga across the pods

Every service stamps the `correlationId` into its log lines, so one grep reconstructs the flow.
Take a correlationId the demo printed:

```bash
CID=066dbc2f-2dd1-409d-97e0-7673d4f8c2d0
kubectl -n edmp logs -l app.kubernetes.io/part-of=event-driven-order-platform \
  --tail=-1 --prefix | grep "$CID"
```

The payment-decline saga, in order, across four pods (timestamps and messages only):

```
17:47:04.866  api-gateway        Routing POST /api/v1/orders to http://order-service:8080/... via route 'orders'
17:47:05.045  inventory-service  Reserved stock for order db4c2ea3-... as reservation ae781bfc-...
17:47:05.538  payment-service    Authorization declined for order db4c2ea3-...: card_declined
17:47:06.064  inventory-service  Released reservation ae781bfc-... for order db4c2ea3-...
17:47:06.502  order-service      Compensation complete for order db4c2ea3-...: stock reservation released
```

Reserve, decline, release, cancel — the compensation path, 1.6 seconds end to end, one
identifier the client was handed in the `X-Correlation-Id` response header. `--prefix` puts
`[pod/<name>/<container>]` in front of every line, which is what makes the interleaving readable.

The happy path reads the same way — `b146e751-...` in this run gave eight lines across the same
four pods: gateway routes, inventory reserves, payment authorizes then captures, inventory
commits the reservation.

One service at a time:

```bash
kubectl -n edmp logs deploy/payment-service --tail=200 | grep "$CID"
kubectl -n edmp logs -f deploy/order-service          # follow live
```

The database is worth a look too — the saga state machine and the outbox are rows, not just
log lines:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb \
  -c "SELECT status, count(*) FROM public.orders GROUP BY status;" \
  -c "SELECT sku, on_hand, reserved FROM inventory.stock_item ORDER BY sku;"
```

```
  status   | count        sku    | on_hand | reserved
-----------+-------    ----------+---------+----------
 CANCELLED |     1      SKU-1001 |      98 |        0
 CONFIRMED |     1      SKU-1002 |     100 |        0
```

One confirmed, one cancelled, and `SKU-1001` down by exactly the two units of the confirmed
order — the declined order's reservation was released, not leaked, and `reserved` is back to 0.

### Checking the cluster deployment did what the design says

Schema-per-service ([ADR-0007](../adr/0007-polyglot-persistence-and-dev-simplification.md)) —
one database, three schemas, each service owning its own tables plus its own outbox and dedup
table, **and each connecting as its own role**:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb -c "\dn" \
  -c "SELECT table_schema, count(*) FROM information_schema.tables
      WHERE table_schema NOT IN ('pg_catalog','information_schema')
      GROUP BY table_schema ORDER BY table_schema;"
```

```
   Name    |     Owner           table_schema | count
-----------+---------------     --------------+-------
 inventory | inventory_svc       inventory    |     6
 payment   | payment_svc         payment      |     6
 public    | pg_database_owner   public       |     7
```

Each schema is owned by the role that migrates into it (`public` keeps its standard owner, with
`order_svc` granted `USAGE, CREATE` on it). `appuser` is the bootstrap/admin role: it owns the
database, installs `uuid-ossp` and backs the `pg_isready` probes, and no service authenticates
as it.

Two things worth separating: what each pod is *configured* with, and who is *actually* connected.
Check both:

```bash
kubectl -n edmp exec deploy/order-service     -- printenv POSTGRES_ORDER_USER
kubectl -n edmp exec deploy/payment-service   -- printenv POSTGRES_PAYMENT_USER
kubectl -n edmp exec deploy/inventory-service -- printenv POSTGRES_INVENTORY_USER

kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb -c \
  "SELECT usename, datname, count(*) AS backends FROM pg_stat_activity
   WHERE usename LIKE '%\_svc' GROUP BY usename, datname ORDER BY usename;"
```

```
order_svc
payment_svc
inventory_svc

    usename    | datname | backends
---------------+---------+----------
 inventory_svc | orderdb |       10
 order_svc     | orderdb |       10
 payment_svc   | orderdb |       10
```

Three distinct roles holding live connections, ten Hikari connections each, and `appuser` not
among them. That is the whole claim of the role model, measured rather than asserted. `usename`
is what proves the identity — `application_name` is empty, since the JDBC driver does not set it
here.

The boundary is also visible without connecting as anybody:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb -c \
  "SELECT has_schema_privilege('public','public','USAGE')        AS public_role_on_public,
          has_schema_privilege('order_svc','public','USAGE')     AS order_on_public,
          has_schema_privilege('order_svc','payment','USAGE')    AS order_on_payment,
          has_schema_privilege('inventory_svc','public','USAGE') AS inventory_on_public;"
```

```
 public_role_on_public | order_on_public | order_on_payment | inventory_on_public
-----------------------+-----------------+------------------+---------------------
 f                     | t               | f                | f
```

The first column is the one that matters most: `REVOKE ALL ON SCHEMA public FROM PUBLIC` really
ran, so `public` is not the open schema Postgres ships by default. Without it the other two
services could read order-service's tables no matter what else was granted.

The privilege boundary is the point, so check it rather than trusting it — every one of these
must be refused:

```bash
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only \
  psql -h localhost -U payment_svc -d orderdb -c "SELECT count(*) FROM public.orders;"
# ERROR:  permission denied for schema public
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only \
  psql -h localhost -U inventory_svc -d orderdb -c "SELECT count(*) FROM public.orders;"
# ERROR:  permission denied for schema public
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only \
  psql -h localhost -U order_svc -d orderdb -c "SELECT count(*) FROM payment.payment_intent;"
# ERROR:  permission denied for schema payment
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only \
  psql -h localhost -U payment_svc -d orderdb -c "SELECT count(*) FROM inventory.stock_item;"
# ERROR:  permission denied for schema inventory
```

`-h localhost` is not decoration: without it `psql` uses the Unix socket, which the image trusts,
and you would be testing nothing. Each denial exits 1, so `kubectl exec` also prints
`command terminated with exit code 1` — that is the pass condition here.

Then the controls, so the denials are demonstrably about privileges and not a broken connection —
each role reading its own schema:

```bash
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only psql -h localhost \
  -U order_svc     -d orderdb -tAc "SELECT count(*) FROM orders;"                    # 2
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only psql -h localhost \
  -U payment_svc   -d orderdb -tAc "SELECT count(*) FROM payment.payment_intent;"    # 2
kubectl -n edmp exec postgres-0 -- env PGPASSWORD=changeme-dev-only psql -h localhost \
  -U inventory_svc -d orderdb -tAc "SELECT count(*) FROM inventory.stock_item;"      # 2
```

(Before the demo those are `0`, `0`, `2` — the two seeded SKUs.) `changeme-dev-only` is the
chart's dev default; if you overrode `secrets.postgres*Password`, read the real value out of the
Secret instead:
`kubectl -n edmp get secret platform-secrets -o jsonpath='{.data.postgres-order-password}' | base64 -d`.

Note that the roles are created by the `postgres-init` ConfigMap's `02-service-roles.sh`, which
— like every `docker-entrypoint-initdb.d` script — runs **only on first initialization of an
empty data directory**. The Postgres PVC deliberately survives `helm uninstall`, so on a cluster
whose volume predates this change the roles will not exist and the services will fail to
authenticate. Drop the volume (see [§9](#9-teardown): `kubectl -n edmp delete pvc data-postgres-0`)
and reinstall, or apply the script by hand as `appuser`.

That the `uuid-ossp` extension is present proves the chart's init ConfigMap really ran through
the image's `docker-entrypoint-initdb.d` hook, before order-service's V1 migration needed it —
which is also what lets `order_svc`, a role with no extension-install rights of its own, run that
migration: PostgreSQL checks for a duplicate extension before it checks privileges, so
`CREATE EXTENSION IF NOT EXISTS` is a NOTICE rather than a permission error once it is installed:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb \
  -c "SELECT extname, extversion FROM pg_extension ORDER BY extname;" \
  -c "SELECT version, description, success FROM payment.flyway_schema_history ORDER BY 1;"
```

```
  extname  | extversion         version |      description      | success
-----------+------------           ------+-----------------------+---------
 plpgsql   | 1.0                       1 | Create payment tables | t
 uuid-ossp | 1.1
```

Just the one migration row, and **no** `<< Flyway Schema Creation >>` row: `02-service-roles.sh`
already created the `payment` schema and handed it to `payment_svc`, so Flyway found it there and
had nothing to record. A cluster that still shows that extra row was initialized before the
per-service roles existed — Flyway created the schema itself, as the old shared role. It is a
quick way to tell the two vintages apart. `public` carries two rows (`Create order tables`,
`saga participant ids`) and `inventory` one, all `success = t`.

And the topics really were created on first use ([ADR-0017](../adr/0017-messaging-technology-kafka.md)
— no registry, no pre-provisioning in dev). Note the capped heap; see
[§10](#10-troubleshooting):

```bash
kubectl -n edmp exec kafka-0 -- /bin/sh -c \
  "KAFKA_HEAP_OPTS='-Xmx64m -Xms32m' /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"
```

```
__consumer_offsets              events.order.cancelled.v1
commands.inventory.release.v1   events.order.confirmed.v1
commands.inventory.reserve.v1   events.order.created.v1
commands.payment.authorize.v1   events.payment.authorized.v1
commands.payment.capture.v1     events.payment.capture-failed.v1
commands.payment.refund.v1      events.payment.captured.v1
events.inventory.released.v1    events.payment.declined.v1
events.inventory.reservation-failed.v1
events.inventory.reserved.v1
```

Fifteen application topics — five commands, ten events — matching the
[event catalog](../EVENT-CATALOG.md).

## 9. Teardown

```bash
helm uninstall platform -n edmp        # or: make k8s-uninstall
```

`helm uninstall` deliberately leaves the Postgres PVC behind — a StatefulSet's volume outlives
its release so that reinstalling keeps the data. Drop it explicitly:

```bash
kubectl -n edmp delete pvc data-postgres-0
kubectl delete namespace edmp
```

Deleting that PVC is not only cleanup — it is the only way to re-run `01-extensions.sql` and
`02-service-roles.sh`. If you changed either file, or you want to prove the roles and grants are
created from scratch rather than inherited from an earlier install, uninstall, delete
`data-postgres-0`, and install again. Reinstalling over a surviving volume keeps whatever roles
that volume was born with.

Stop the cluster, keeping it for next time:

```bash
minikube stop -p edmp
```

Or remove it and everything in it, including the loaded images:

```bash
minikube delete -p edmp
```

Also stop the port-forwards (`kill %1 %2`, or close the shell).

## 10. Troubleshooting

Problems actually hit while building this, with what the symptom looks like.

### Pods are `ErrImagePull` / `ImagePullBackOff`

The images are not in the cluster's daemon, or the pull policy is wrong. Check both:

```bash
minikube -p edmp image ls | grep edmp/
kubectl -n edmp get pod <pod> -o jsonpath='{.spec.containers[0].imagePullPolicy}'
```

It must be `IfNotPresent`, which is what `values-minikube.yaml` sets. With `Always`, the
cluster asks Docker Hub for `edmp/order-service:local` and is told no such repository exists.

### `kafka-0` is `0/1` and restarting, and sagas never progress

Orders return 202 and stay `PENDING` forever. The pod looks alive but the broker is being
killed:

```bash
kubectl -n edmp get pod kafka-0 -o jsonpath='{.status.containerStatuses[0].lastState}'
```

```
{"terminated":{"exitCode":137,"reason":"OOMKilled",...}}
```

This is what happens if you port the compose healthcheck literally. That check runs
`kafka-topics.sh --bootstrap-server localhost:9092 --list`, which starts a **second JVM inside
the broker's 1 GiB cgroup**; both JVMs size themselves from the same limit, the cgroup OOM
killer takes the largest process — the broker — and each restart wipes the ephemeral log dir,
so every saga waits for a reply that no longer exists. The chart therefore probes Kafka with a
socket check. If you want the richer check by hand, cap the heap:

```bash
kubectl -n edmp exec kafka-0 -- /bin/sh -c \
  "KAFKA_HEAP_OPTS='-Xmx64m -Xms32m' /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list"
```

The general lesson: an exec probe runs *inside* the container's limit. For a JVM workload, that
is rarely free.

### A StatefulSet change does not take effect

You fixed `kafka.yaml`, `helm upgrade` reported success, and `kubectl get pods` still shows the
old pod with its original age. A StatefulSet with the default `OrderedReady` policy will not
roll a pod that is not Ready — so a broken pod blocks the very update that would fix it.
Delete it and let the controller recreate it from the new template:

```bash
kubectl -n edmp delete pod kafka-0
```

### `order-service` / `payment-service` / `inventory-service` are in `CrashLoopBackOff`

On a first install this is usually **not** a problem — see [§5](#5-wait-until-everything-is-ready).
The three services that run Flyway at startup exit if Postgres is not accepting connections yet,
and Kubernetes backs them off until it is. Confirm that is all it is:

```bash
kubectl -n edmp get pod postgres-0          # still ContainerCreating/0-1? then just wait
kubectl -n edmp logs deploy/order-service --previous | tail -20
```

`Connection to postgres:5432 refused` means waiting is the fix. What is *not* harmless:

```
FATAL: password authentication failed for user "order_svc"
```
```
FATAL: role "order_svc" does not exist
```

Both mean the database was initialized without `02-service-roles.sh` — an existing
`data-postgres-0` from before the per-service roles, or an install that skipped the
`--set-file postgres.initRolesSh=...` flag. Check which:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb -c \
  "SELECT rolname FROM pg_roles WHERE rolname LIKE '%\_svc';"
kubectl -n edmp get cm postgres-init -o jsonpath='{.data}' | head -c 200
```

No rows means the volume predates the roles: uninstall, `kubectl -n edmp delete pvc
data-postgres-0`, install again ([§4](#4-install-the-chart)). A `postgres-init` ConfigMap with
only `01-extensions.sql` in it means the install was missing the flag — though Helm normally
refuses to render at all in that case, because the ConfigMap template marks that value
`required`.

### Every request returns 401

The issuer strings disagree. Compare what Keycloak stamps with what the services expect:

```bash
./scripts/get-token.sh | cut -d. -f2 | tr '_-' '/+' | base64 -d | jq -r .iss
kubectl -n edmp get deploy order-service \
  -o jsonpath='{.spec.template.spec.containers[0].env[?(@.name=="JWT_ISSUER_URI")].value}'
```

They must be identical strings. The chart's render-time guard catches this for values you set,
but not a token minted by some other Keycloak.

### `port-forward` fails with "address already in use"

Something else owns the port. 8090 and 8180 were chosen to match compose, so the usual cause is
a compose stack still running — `make down` first. Otherwise forward to a different local port
and tell the scripts about it:

```bash
kubectl -n edmp port-forward svc/api-gateway 18090:8090 &
GATEWAY_URL=http://localhost:18090 ./scripts/demo.sh
```

A local Redis on 6379 is common and harmless here: nothing forwards Redis, it is reached only
inside the cluster.

### `kubectl` warns about a version skew

```
! /snap/bin/kubectl is version 1.36.3, which may have incompatibilities with Kubernetes 1.30.0
```

Harmless for these objects (all `apps/v1`, `v1`, `autoscaling/v2`). `minikube kubectl -- get pods`
uses the matching client if you would rather not see it.

### The order stays `PENDING` and nothing is wrong with Kafka

Look at the saga row before the logs — it names the step being waited on:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb \
  -c "SELECT order_id, current_step, updated_at FROM public.saga_instance ORDER BY updated_at DESC LIMIT 5;"
```

A step that never advances with a healthy broker usually means the participant never consumed
the command: check that service's consumer group joined (`kubectl -n edmp logs deploy/payment-service | grep -i "partitions assigned"`).

## 11. What the charts contain

```
deploy/helm/
├── charts/
│   ├── platform-lib/          library chart (ADR-0008): Deployment, Service,
│   │                          ServiceAccount, HPA, probes, config/secret wiring, labels
│   ├── api-gateway/           values.yaml + four one-line templates
│   ├── order-service/         "
│   ├── payment-service/       "
│   └── inventory-service/     "
└── platform-umbrella/         the four services as subcharts + the infrastructure
    ├── templates/             postgres, kafka, redis, keycloak, Secret, render-time guard
    ├── values.yaml            defaults
    └── values-minikube.yaml   small requests, IfNotPresent
```

A service chart is genuinely only its values — `templates/deployment.yaml` is one line,
`{{- include "platform-lib.deployment" . }}`. Adding a fifth service is a `Chart.yaml`, a
`values.yaml` and four one-line templates.

The infrastructure templates are hand-written rather than upstream subcharts. For a single
broker, a single Postgres and a dev-mode Keycloak on a laptop, four small templates are less
work to read and to keep working than four large values files disabling production machinery.
A real cluster should use operators instead, and that is a different chart, not a different
values file.

Names are fixed (`order-service`, `kafka`, `keycloak`) rather than `<release>-<chart>`, which
means one release of the platform per namespace. That is the intended model — a namespace is
the environment — and it buys identical configuration strings between compose and Kubernetes,
which is the difference between reviewing one set of values and reviewing two.
