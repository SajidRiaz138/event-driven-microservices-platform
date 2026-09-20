# ADR-0008: Helm as primary packaging, Kustomize deferred

- **Status:** Accepted
- **Date:** 2026-09-19

## Context

The initial structure carried both Helm charts and Kustomize overlays for the same
services, plus per-service `k8s/` folders — three places a manifest for one service
could live. That is ambiguous: a reader cannot tell which is authoritative, and
maintaining two templating systems doubles the work for no added signal. The
architectural signal comes from *choosing* and *justifying*, not from shipping both.

## Decision

**Helm is the single primary packaging and deployment tool.**

- A shared **library chart** defines common templates (Deployment, Service, HPA,
  ServiceAccount, config/secret wiring); each service chart supplies only its
  `values.yaml`. This avoids eight near-identical copied subcharts.
- An **umbrella chart** composes the service charts for one-command install of the
  whole platform.
- Environment differences (dev/staging/prod) are expressed as Helm **values files**
  (`values-dev.yaml`, etc.), not a second toolchain.
- Per-service `k8s/` folders were **removed**; `deploy/k8s/` (raw manifests) is kept
  only for illustrative, tool-agnostic examples, clearly labelled as non-authoritative.

**Kustomize is deferred**, not rejected. It is a legitimate alternative, and the
comparison is documented so the choice is visible. If a GitOps workflow later favours
Kustomize, that will be a new ADR.

## Consequences

- **Positive:** One authoritative deployment path; no duplication; a library chart
  keeps service charts tiny; the trade-off is documented.
- **Negative:** Readers who prefer Kustomize see only the rationale, not a working
  overlay set. Accepted — clarity beats breadth.
