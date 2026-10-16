{{- define "lab.labels" -}}
app.kubernetes.io/name: {{ .app }}
app.kubernetes.io/component: {{ .component | default .app }}
app.kubernetes.io/part-of: paybridge-lab-data
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- end -}}

{{- define "lab.selector" -}}
app.kubernetes.io/name: {{ .app }}
{{- end -}}

{{/* PVCs are never removed with the release: a StatefulSet does not delete them and the retention policy says Retain. */}}
{{- define "lab.pvcTemplate" -}}
volumeClaimTemplates:
  - metadata:
      name: data
    spec:
      accessModes: ["ReadWriteOnce"]
      {{- if .root.Values.storageClass }}
      storageClassName: {{ .root.Values.storageClass }}
      {{- end }}
      resources:
        requests:
          storage: {{ .size }}
{{- end -}}
