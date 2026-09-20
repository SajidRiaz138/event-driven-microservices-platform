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
* Creating docker container (CPUs=4, Memory=8192MB) ...
* Preparing Kubernetes v1.30.0 on Docker 26.1.1 ...
* Enabled addons: default-storageclass, storage-provisioner
* Done! kubectl is now configured to use "edmp" cluster
```

```bash
kubectl config use-context edmp
kubectl get nodes
```

```
NAME   STATUS   ROLES           AGE   VERSION
edmp   Ready    control-plane   6s    v1.30.0
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

Disk: `--disk-size=30g` was sufficient — all eight images loaded, a PVC-backed Postgres and the
full run produced no disk-pressure events. A precise "used of 30 GiB" figure is not meaningful
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
=== loading edmp/order-service:local
=== loading edmp/payment-service:local
=== loading edmp/inventory-service:local
=== loading edmp/api-gateway:local
```

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

Then install. Two `--set-file` flags feed the chart the realm export and the Postgres init SQL
**from the files the compose stack already uses**, instead of copies living inside the chart —
one copy of each in the repository, so they cannot drift:

```bash
helm upgrade --install platform deploy/helm/platform-umbrella \
  -n edmp --create-namespace \
  -f deploy/helm/platform-umbrella/values-minikube.yaml \
  --set-file postgres.initSql=deploy/local/postgres-init/01-extensions.sql \
  --set-file keycloak.realmJson=services/auth-service/realm/order-platform-realm.json \
  --wait --timeout 10m
```

`make k8s-install` is exactly that command. Both are idempotent — run them again after editing
a chart.

Passwords come from a `Secret` the chart creates with the same dev placeholders as
`.env.example`. Override them, or bring your own Secret:

```bash
#   --set secrets.postgresAppPassword=... --set secrets.keycloakAdminPassword=...
#   --set secrets.create=false            # then create `platform-secrets` yourself
```

To check the chart without a cluster at all — useful in CI:

```bash
make k8s-lint          # helm lint + helm template, no cluster needed
```

## 5. Wait until everything is Ready

```bash
kubectl -n edmp get pods -w
```

```
NAME                                READY   STATUS    RESTARTS   AGE
api-gateway-5ccd488f47-w9vxh        1/1     Running   0          15m
inventory-service-cff5d6f85-xddks   1/1     Running   0          15m
kafka-0                             1/1     Running   0          109s
keycloak-7c7bfb99-qvbs2             1/1     Running   0          15m
order-service-69d6d6846d-spsxp      1/1     Running   0          15m
payment-service-5f5694c6f7-gmhsb    1/1     Running   0          15m
postgres-0                          1/1     Running   0          15m
redis-58bbf87555-znhwv              1/1     Running   0          15m
```

Expect 60–90 seconds for the four JVM services on a warm node: each runs Flyway against
Postgres and joins a Kafka consumer group before it reports ready.

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
  PASS  token for demo-customer (sub 544ce418-8efa-4c62-8d8e-313a9a2b6b80)
scopes: profile orders:write email orders:read   audience: order-platform

==> Scenario 1/2 — happy path (expect CONFIRMED)
response : HTTP 202  Location: /api/v1/orders/11b14acc-b29d-4188-861a-0efe20dc3450
  PASS  202 Accepted, order 11b14acc-b29d-4188-861a-0efe20dc3450
correlationId: ea09d5eb-5683-469f-b641-2c97e6b36482
  polling ... (reserve -> authorize -> capture -> confirm)...
  PASS  order reached CONFIRMED

==> Scenario 2/2 — payment decline (expect CANCELLED / PAYMENT_DECLINED)
response : HTTP 202  Location: /api/v1/orders/474a3218-8780-4622-9ca1-1d3c77e3bff0
  PASS  202 Accepted, order 474a3218-8780-4622-9ca1-1d3c77e3bff0
correlationId: c232f98e-8c51-4e55-aae9-2c58e46ca6ca
  polling ... (reserve -> decline -> release -> cancel)...
  PASS  order reached CANCELLED with reason PAYMENT_DECLINED (stock released)

==> Result
  DEMO PASSED — CONFIRMED and CANCELLED(PAYMENT_DECLINED) both observed through the gateway.
```

`make smoke` is the faster check (edge alive, token works, an order is accepted) and does not
wait for a terminal saga state.

## 8. Follow one saga across the pods

Every service stamps the `correlationId` into its log lines, so one grep reconstructs the flow.
Take a correlationId the demo printed:

```bash
CID=c232f98e-8c51-4e55-aae9-2c58e46ca6ca
kubectl -n edmp logs -l app.kubernetes.io/part-of=event-driven-order-platform \
  --tail=-1 --prefix | grep "$CID"
```

The payment-decline saga, in order, across four pods (timestamps and messages only):

```
15:41:38.939  api-gateway        Routing POST /api/v1/orders to http://order-service:8080/... via route 'orders'
15:41:39.116  inventory-service  Reserved stock for order 474a3218-... as reservation 01aac480-...
15:41:39.652  payment-service    Authorization declined for order 474a3218-...: card_declined
15:41:40.183  inventory-service  Released reservation 01aac480-... for order 474a3218-...
15:41:40.276  order-service      Compensation complete for order 474a3218-...: stock reservation released
```

Reserve, decline, release, cancel — the compensation path, 1.3 seconds end to end, one
identifier the client was handed in the `X-Correlation-Id` response header.

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

### Checking the cluster deployment did what the design says

Schema-per-service ([ADR-0007](../adr/0007-polyglot-persistence-and-dev-simplification.md)) —
one database, three schemas, each service owning its own tables plus its own outbox and dedup
table:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb -c "\dn" \
  -c "SELECT table_schema, count(*) FROM information_schema.tables
      WHERE table_schema NOT IN ('pg_catalog','information_schema')
      GROUP BY table_schema ORDER BY table_schema;"
```

```
   Name    |       Owner            table_schema | count
-----------+-------------------    --------------+-------
 inventory | appuser                inventory    |     6
 payment   | appuser                payment      |     6
 public    | pg_database_owner      public       |     7
```

That the `uuid-ossp` extension is present proves the chart's init ConfigMap really ran through
the image's `docker-entrypoint-initdb.d` hook, before order-service's V1 migration needed it:

```bash
kubectl -n edmp exec postgres-0 -- psql -U appuser -d orderdb \
  -c "SELECT extname, extversion FROM pg_extension ORDER BY extname;" \
  -c "SELECT version, description, success FROM payment.flyway_schema_history ORDER BY 1;"
```

```
  extname  | extversion         version |       description        | success
-----------+------------           ------+--------------------------+---------
 plpgsql   | 1.0                       1 | Create payment tables    | t
 uuid-ossp | 1.1                         | << Flyway Schema Creation >> | t
```

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
