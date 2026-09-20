{{/*
The one Deployment template every service chart uses.

Probes are the part worth reading. Readiness targets /actuator/health/readiness, never the
aggregate /actuator/health: the readiness group holds the application's own readiness state,
while the aggregate holds the dependency indicators (on Boot 4.1.1, measured: db, diskSpace,
ping, ssl). Probing the aggregate would let one Postgres blip pull every pod out of its Service
at the same moment, turning something recoverable into an outage (ADR-0014). Liveness targets
/actuator/health/liveness — a JVM that is alive but cannot reach a dependency must not be
restarted, it must retry.

A startupProbe fronts both: Flyway migrations plus Kafka consumer-group join make first
readiness slow (the compose stack allows ~20s start_period plus 30 retries), and without
a startupProbe you either set initialDelaySeconds so high that real crashes take minutes
to notice, or so low that liveness kills the pod mid-migration.
*/}}
{{- define "platform-lib.probe" -}}
httpGet:
  path: {{ .probe.path }}
  port: {{ .portName }}
initialDelaySeconds: {{ .probe.initialDelaySeconds | default 0 }}
periodSeconds: {{ .probe.periodSeconds | default 10 }}
timeoutSeconds: {{ .probe.timeoutSeconds | default 3 }}
failureThreshold: {{ .probe.failureThreshold | default 3 }}
successThreshold: {{ .probe.successThreshold | default 1 }}
{{- end -}}

{{- define "platform-lib.deployment" -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "platform-lib.fullname" . }}
  labels:
    {{- include "platform-lib.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  revisionHistoryLimit: 2
  strategy:
    type: {{ .Values.strategy.type }}
  selector:
    matchLabels:
      {{- include "platform-lib.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "platform-lib.labels" . | nindent 8 }}
      {{- with .Values.podAnnotations }}
      annotations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
    spec:
      serviceAccountName: {{ include "platform-lib.serviceAccountName" . }}
      {{- with .Values.podSecurityContext }}
      securityContext:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      terminationGracePeriodSeconds: {{ .Values.terminationGracePeriodSeconds }}
      containers:
        - name: {{ .Chart.Name }}
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          {{- with .Values.securityContext }}
          securityContext:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          ports:
            - name: {{ .Values.service.portName }}
              containerPort: {{ .Values.containerPort }}
              protocol: TCP
          env:
            {{- range $name, $value := .Values.env }}
            - name: {{ $name }}
              value: {{ $value | quote }}
            {{- end }}
            {{- range .Values.secretEnv }}
            - name: {{ .name }}
              valueFrom:
                secretKeyRef:
                  name: {{ .secret }}
                  key: {{ .key }}
            {{- end }}
          {{- with .Values.envFrom }}
          envFrom:
            {{- toYaml . | nindent 12 }}
          {{- end }}
          {{- if .Values.probes.startup.enabled }}
          startupProbe:
            {{- include "platform-lib.probe" (dict "probe" .Values.probes.startup "portName" .Values.service.portName) | nindent 12 }}
          {{- end }}
          {{- if .Values.probes.readiness.enabled }}
          readinessProbe:
            {{- include "platform-lib.probe" (dict "probe" .Values.probes.readiness "portName" .Values.service.portName) | nindent 12 }}
          {{- end }}
          {{- if .Values.probes.liveness.enabled }}
          livenessProbe:
            {{- include "platform-lib.probe" (dict "probe" .Values.probes.liveness "portName" .Values.service.portName) | nindent 12 }}
          {{- end }}
          resources:
            {{- toYaml .Values.resources | nindent 12 }}
      {{- with .Values.nodeSelector }}
      nodeSelector:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity:
        {{- toYaml . | nindent 8 }}
      {{- end }}
{{- end -}}
