{{/*
Names and labels shared by every service chart.

A deliberate deviation from the `helm create` default: the resource name is the CHART
name, not "<release>-<chart>". The platform's own configuration is a set of DNS names
that appear inside tokens and inside service config — `http://order-service:8080`,
`http://keycloak:8080/realms/order-platform` — and the JWT `iss` claim is compared by
exact string equality (PlatformJwtDecoders). Keeping the Service name stable means the
Kubernetes values are byte-identical to the compose ones, so there is one set of strings
to review instead of two, and `kubectl port-forward svc/api-gateway 8090:8090` works
without looking up a release prefix.

The cost: one release of this platform per namespace. That is the intended deployment
model (a namespace is the environment), and the release name is still recorded in
app.kubernetes.io/instance, so `kubectl get all -l app.kubernetes.io/instance=<release>`
still selects exactly one release's objects.
*/}}

{{- define "platform-lib.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "platform-lib.fullname" -}}
{{- default (include "platform-lib.name" .) .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "platform-lib.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/* Selector labels: the immutable subset. Never add anything version-dependent here —
     a Deployment's selector cannot be changed after creation. */}}
{{- define "platform-lib.selectorLabels" -}}
app.kubernetes.io/name: {{ include "platform-lib.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "platform-lib.labels" -}}
helm.sh/chart: {{ include "platform-lib.chart" . }}
{{ include "platform-lib.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | default .Chart.Version | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/part-of: event-driven-order-platform
{{- with .Values.component }}
app.kubernetes.io/component: {{ . }}
{{- end }}
{{- end -}}

{{- define "platform-lib.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "platform-lib.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}
