{{/*
Expand the chart name.
*/}}
{{- define "fraud-inference-demo.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{/*
Create a default fully qualified app name.
*/}}
{{- define "fraud-inference-demo.fullname" -}}
{{- if .Values.fullnameOverride -}}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- $name := default .Chart.Name .Values.nameOverride -}}
{{- if contains $name .Release.Name -}}
{{- .Release.Name | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{- define "fraud-inference-demo.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "fraud-inference-demo.labels" -}}
helm.sh/chart: {{ include "fraud-inference-demo.chart" . }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/part-of: fraud-inference-demo
{{- end -}}

{{- define "fraud-inference-demo.selectorLabels" -}}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end -}}

{{- define "fraud-inference-demo.serviceAccountName" -}}
{{- if .Values.serviceAccount.create -}}
{{- default (include "fraud-inference-demo.fullname" .) .Values.serviceAccount.name -}}
{{- else -}}
{{- default "default" .Values.serviceAccount.name -}}
{{- end -}}
{{- end -}}

{{- define "fraud-inference-demo.redisName" -}}
{{- if .Values.redis.fullnameOverride -}}
{{- .Values.redis.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-redis" (include "fraud-inference-demo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "fraud-inference-demo.feastName" -}}
{{- printf "%s-feast-feature-server" (include "fraud-inference-demo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "fraud-inference-demo.featureWriterName" -}}
{{- printf "%s-feast-feature-writer" (include "fraud-inference-demo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}

{{- define "fraud-inference-demo.postgresName" -}}
{{- if .Values.postgres.fullnameOverride -}}
{{- .Values.postgres.fullnameOverride | trunc 63 | trimSuffix "-" -}}
{{- else -}}
{{- printf "%s-postgres" (include "fraud-inference-demo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
{{- end -}}

{{- define "fraud-inference-demo.configName" -}}
{{- printf "%s-model-feature-config" (include "fraud-inference-demo.fullname" .) | trunc 63 | trimSuffix "-" -}}
{{- end -}}
