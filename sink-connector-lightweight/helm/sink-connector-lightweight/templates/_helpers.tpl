{{/*
Expand the name of the chart.
*/}}
{{- define "sink-connector-lightweight.name" -}}
{{- default .Chart.Name .Values.nameOverride | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Create a default fully qualified app name.
We truncate at 63 chars because some Kubernetes name fields are limited to this (by the DNS naming spec).
If release name contains chart name it will be used as a full name.
*/}}
{{- define "sink-connector-lightweight.fullname" -}}
{{- if .Values.fullnameOverride }}
{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- $name := default .Chart.Name .Values.nameOverride }}
{{- if contains $name .Release.Name }}
{{- .Release.Name | trunc 63 | trimSuffix "-" }}
{{- else }}
{{- printf "%s-%s" .Release.Name $name | trunc 63 | trimSuffix "-" }}
{{- end }}
{{- end }}
{{- end }}

{{/*
Gives the configmap a default name
*/}}
{{- define "sink-connector-lightweight.configmapName" -}}
{{- if not .Values.configmapName -}}
sink-connector-lightweight-config
{{- else -}}
{{ .Values.configmapName }}
{{- end }}
{{- end }}

{{/*
Create chart name and version as used by the chart label.
*/}}
{{- define "sink-connector-lightweight.chart" -}}
{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}
{{- end }}

{{/*
Common labels
*/}}
{{- define "sink-connector-lightweight.labels" -}}
helm.sh/chart: {{ include "sink-connector-lightweight.chart" . }}
{{ include "sink-connector-lightweight.selectorLabels" . }}
{{- if .Chart.AppVersion }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
{{- end }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}

{{/*
Selector labels
*/}}
{{- define "sink-connector-lightweight.selectorLabels" -}}
app.kubernetes.io/name: {{ include "sink-connector-lightweight.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
{{- end }}

{{/*
Create the name of the service account to use
*/}}
{{- define "sink-connector-lightweight.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}
{{- default (include "sink-connector-lightweight.fullname" .) .Values.serviceAccount.name }}
{{- else }}
{{- default "default" .Values.serviceAccount.name }}
{{- end }}
{{- end }}

{{/*
Storage account class name
*/}}
{{- define "sink-connector-lightweight.storageAccountClass" -}}
{{- if .Values.storageAccountClass -}}
{{ default "manual" .Values.storageAccountClass }}
{{- else -}}
manual
{{- end }}
{{- end }}

{{/*
Adds a label of type: local for persistent volume types of manual
*/}}
{{- define "sink-connector-lightweight.storageAccountLabel" -}}
{{- if eq (include "sink-connector-lightweight.storageAccountClass" .) "manual" }}
    type: local
{{- else -}}
{}
{{- end }}
{{- end }}

{{/*
Host pathing
*/}}
{{- define "sink-connector-lightweight.hostpathing" -}}
{{- if eq (include "sink-connector-lightweight.storageAccountClass" .) "manual" }}
hostPath:
  path: {{ .Values.persistentvolume.hostPath }}
{{- end }}
{{- end }}

