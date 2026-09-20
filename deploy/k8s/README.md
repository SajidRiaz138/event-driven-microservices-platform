# deploy/k8s — non-authoritative

**Helm is the authoritative deployment path for this platform ([ADR-0008](../../docs/adr/0008-helm-primary-kustomize-deferred.md)).**
The charts live in [`deploy/helm`](../helm); the runbook is
[`docs/deployment/kubernetes-minikube.md`](../../docs/deployment/kubernetes-minikube.md).

This directory exists for illustrative, tool-agnostic raw manifests — the kind of thing
you paste into an issue to show someone a single object. Nothing here is installed by
`helm install`, nothing here is kept in step with the charts, and nothing here should be
applied to a cluster you care about. If a manifest here disagrees with the charts, the
charts are right.

The `base/` and `overlays/` layout is the shape a Kustomize tree would take. ADR-0008
**defers** Kustomize rather than rejecting it: adopting it would be a new ADR, most likely
driven by a GitOps workflow. Until then these directories stay empty on purpose, so that
there is exactly one place a deployable manifest for this platform comes from.
