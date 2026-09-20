{{/*
Service, ServiceAccount and HPA.

Service type is ClusterIP for every service including the gateway. The edge is reached
with `kubectl port-forward` on a dev cluster and an Ingress on a real one; publishing a
NodePort for order/payment/inventory would contradict ADR-0009, where the gateway is the
only entry point.

The HPA is templated but disabled by default. It needs metrics-server, and horizontal
scaling past the HikariCP pool (maximumPoolSize 10 per service, ADR-0015) moves the
bottleneck to the shared dev Postgres rather than removing it.
*/}}
{{- define "platform-lib.service" -}}
apiVersion: v1
kind: Service
metadata:
  name: {{ include "platform-lib.fullname" . }}
  labels:
    {{- include "platform-lib.labels" . | nindent 4 }}
  {{- with .Values.service.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - name: {{ .Values.service.portName }}
      port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.portName }}
      protocol: TCP
  selector:
    {{- include "platform-lib.selectorLabels" . | nindent 4 }}
{{- end -}}

{{- define "platform-lib.serviceaccount" -}}
{{- if .Values.serviceAccount.create -}}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "platform-lib.serviceAccountName" . }}
  labels:
    {{- include "platform-lib.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations:
    {{- toYaml . | nindent 4 }}
  {{- end }}
{{/* No pod in this platform calls the Kubernetes API. */}}
automountServiceAccountToken: {{ .Values.serviceAccount.automountServiceAccountToken }}
{{- end -}}
{{- end -}}

{{- define "platform-lib.hpa" -}}
{{- if .Values.autoscaling.enabled -}}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "platform-lib.fullname" . }}
  labels:
    {{- include "platform-lib.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "platform-lib.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    {{- with .Values.autoscaling.targetCPUUtilizationPercentage }}
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ . }}
    {{- end }}
    {{- with .Values.autoscaling.targetMemoryUtilizationPercentage }}
    - type: Resource
      resource:
        name: memory
        target:
          type: Utilization
          averageUtilization: {{ . }}
    {{- end }}
{{- end -}}
{{- end -}}
