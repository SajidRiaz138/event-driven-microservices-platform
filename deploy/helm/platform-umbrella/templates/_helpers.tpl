{{/*
Labels for the infrastructure objects the umbrella owns. The service charts get theirs
from platform-lib; infrastructure is not a service chart, so it has its own helper with
the same label vocabulary. Called as:

    {{- include "platform.labels" (dict "root" $ "name" "postgres" "component" "database") }}
*/}}

{{- define "platform.selectorLabels" -}}
app.kubernetes.io/name: {{ .name }}
app.kubernetes.io/instance: {{ .root.Release.Name }}
{{- end -}}

{{- define "platform.labels" -}}
helm.sh/chart: {{ printf "%s-%s" .root.Chart.Name .root.Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{ include "platform.selectorLabels" . }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
app.kubernetes.io/part-of: event-driven-order-platform
app.kubernetes.io/component: {{ .component }}
{{- end -}}

{{/*
The in-cluster issuer, derived once. Keycloak stamps `iss` from keycloak.hostname, so this
is the string the services must be configured with.
*/}}
{{- define "platform.issuerUri" -}}
{{- printf "%s/realms/%s" (trimSuffix "/" .Values.keycloak.hostname) .Values.keycloak.realmName -}}
{{- end -}}
