{{/* Environment for one role. Secrets are referenced per key with secretKeyRef; nothing is mounted wholesale. */}}
{{- define "pb.env" -}}
{{- $key := .key -}}
{{- $s := .root.Values.secrets -}}
{{- $pools := .root.Values.pools -}}
- name: PAYBRIDGE_ROLES
  value: {{ .def.env | quote }}
- name: PAYBRIDGE_INSTANCE_ID
  valueFrom:
    fieldRef: {fieldPath: metadata.name}
{{- if has $key (list "coreApi" "relay" "intake" "executor" "projection") }}
- name: PAYBRIDGE_DATASOURCE_WRITER_USERNAME
  valueFrom: {secretKeyRef: {name: {{ $s.db }}, key: writer-username}}
- name: PAYBRIDGE_DATASOURCE_WRITER_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $s.db }}, key: writer-password}}
{{- end }}
{{- if eq $key "coreApi" }}
- name: PAYBRIDGE_DATASOURCE_READER_USERNAME
  valueFrom: {secretKeyRef: {name: {{ $s.db }}, key: reader-username}}
- name: PAYBRIDGE_DATASOURCE_READER_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $s.db }}, key: reader-password}}
- name: PAYBRIDGE_DATASOURCE_WRITER_POOL_MAX_SIZE
  value: {{ $pools.coreApi.writer | quote }}
- name: PAYBRIDGE_DATASOURCE_READER_POOL_MAX_SIZE
  value: {{ $pools.coreApi.reader | quote }}
{{- else if has $key (list "relay" "intake" "executor" "projection") }}
- name: PAYBRIDGE_DATASOURCE_WRITER_POOL_MAX_SIZE
  value: {{ index $pools $key | quote }}
{{- end }}
{{- if has $key (list "relay" "intake" "projection") }}
- name: PAYBRIDGE_RABBIT_USERNAME
  valueFrom: {secretKeyRef: {name: {{ $s.rabbit }}, key: username}}
- name: PAYBRIDGE_RABBIT_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $s.rabbit }}, key: password}}
{{- end }}
{{- if has $key (list "coreApi" "projection") }}
- name: MONGO_USERNAME
  valueFrom: {secretKeyRef: {name: {{ $s.mongo }}, key: username}}
- name: MONGO_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $s.mongo }}, key: password}}
- name: PAYBRIDGE_MONGO_URI
  value: {{ printf "mongodb://$(MONGO_USERNAME):$(MONGO_PASSWORD)@%s:%v/%s?authSource=admin" .root.Values.data.mongo.host .root.Values.data.mongo.port .root.Values.data.mongo.database | quote }}
{{- end }}
{{- if has $key (list "console" "coreApi" "executor") }}
- name: PAYBRIDGE_REDIS_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $s.redis }}, key: password}}
{{- end }}
{{- if has $key (list "coreApi" "executor" "simulator") }}
- name: PAYBRIDGE_SIMULATOR_BEARER_TOKEN
  valueFrom: {secretKeyRef: {name: {{ $s.simulator }}, key: api-token}}
{{- end }}
{{- if eq $key "simulator" }}
- name: PAYBRIDGE_SIMULATOR_DATASOURCE_USERNAME
  valueFrom: {secretKeyRef: {name: {{ $s.simulator }}, key: db-username}}
- name: PAYBRIDGE_SIMULATOR_DATASOURCE_PASSWORD
  valueFrom: {secretKeyRef: {name: {{ $s.simulator }}, key: db-password}}
- name: PAYBRIDGE_SIMULATOR_DATASOURCE_POOL_MAX_SIZE
  value: {{ $pools.simulator | quote }}
- name: PAYBRIDGE_SIMULATOR_WEBHOOK_TARGET_URL
  value: http://core-api-stable.{{ .root.Release.Namespace }}.svc.cluster.local:8080/internal/webhooks/simulator
# The webhook HMAC key is a distinct entry of the service keys Secret (API contract 7.5); only the simulator reads it here.
- name: PAYBRIDGE_SIMULATOR_WEBHOOK_SECRET
  valueFrom: {secretKeyRef: {name: {{ $s.serviceKeys }}, key: webhook-hmac-current}}
{{- end }}
{{- end -}}

{{/* Selective mounting of service key files (POR 4.6). Prints a volumes entry and sets nothing when the role needs none. */}}
{{- define "pb.keyItems" -}}
{{- if eq .key "console" -}}
- key: console-signing-key.pem
  path: console-signing-key.pem
{{- else if eq .key "coreApi" -}}
- key: jwks.json
  path: jwks.json
- key: webhook-hmac-current
  path: webhook-hmac-current
- key: webhook-hmac-previous
  path: webhook-hmac-previous
- key: cursor-hmac-key
  path: cursor-hmac-key
{{- else if eq .key "simulator" -}}
- key: webhook-hmac-current
  path: webhook-hmac-current
{{- end -}}
{{- end -}}

{{/* One Deployment. Call with (dict "root" $ "key" "coreApi" "def" <role def> "v" <role values> "track" "stable" "name" "core-api" "image" <override or nil> "replicas" 1). */}}
{{- define "pb.deployment" -}}
{{- $root := .root -}}
{{- $v := .v -}}
{{- $component := .def.name -}}
{{- $keyItems := include "pb.keyItems" (dict "key" .key) -}}
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ .name }}
  labels:
    {{- include "pb.labels" (dict "root" $root "component" $component "track" .track) | nindent 4 }}
spec:
  {{- $scaled := or (and (hasKey $v "autoscaling") $v.autoscaling.enabled) (and (hasKey $v "scaling") $v.scaling.enabled) }}
  {{- if not $scaled }}
  replicas: {{ .replicas }}
  {{- end }}
  revisionHistoryLimit: 3
  selector:
    matchLabels:
      {{- include "pb.selector" (dict "component" $component "track" .track) | nindent 6 }}
  template:
    metadata:
      labels:
        {{- include "pb.labels" (dict "root" $root "component" $component "track" .track) | nindent 8 }}
      annotations:
        checksum/config: {{ include (print $root.Template.BasePath "/configmap.yaml") $root | sha256sum }}
    spec:
      serviceAccountName: {{ .def.sa }}
      automountServiceAccountToken: false
      terminationGracePeriodSeconds: {{ $root.Values.timing.terminationGracePeriodSeconds }}
      securityContext:
        runAsNonRoot: true
        runAsUser: {{ $root.Values.securityContext.runAsUser }}
        runAsGroup: {{ $root.Values.securityContext.runAsGroup }}
        fsGroup: {{ $root.Values.securityContext.runAsGroup }}
        seccompProfile:
          type: {{ $root.Values.securityContext.seccompProfile }}
      {{- with $root.Values.global.imagePullSecrets }}
      imagePullSecrets:
        {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- if and $root.Values.placement.spread.enabled (gt (int (include "pb.maxReplicas" (dict "v" $v))) 1) }}
      topologySpreadConstraints:
        - maxSkew: 1
          topologyKey: topology.kubernetes.io/zone
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: {matchLabels: {app.kubernetes.io/component: {{ $component }}}}
        - maxSkew: 1
          topologyKey: kubernetes.io/hostname
          whenUnsatisfiable: ScheduleAnyway
          labelSelector: {matchLabels: {app.kubernetes.io/component: {{ $component }}}}
      {{- end }}
      containers:
        - name: {{ $component }}
          image: {{ include "pb.image" (dict "root" $root "group" .def.image "override" .image) | quote }}
          imagePullPolicy: {{ $root.Values.images.pullPolicy }}
          ports:
            {{- if .def.http }}
            - {name: http, containerPort: 8080}
            {{- end }}
            - {name: management, containerPort: 8081}
          envFrom:
            - configMapRef: {name: paybridge-config}
          env:
            {{- include "pb.env" (dict "root" $root "key" .key "def" .def) | nindent 12 }}
          resources:
            {{- toYaml $v.resources | nindent 12 }}
          securityContext:
            allowPrivilegeEscalation: false
            readOnlyRootFilesystem: {{ $root.Values.securityContext.readOnlyRootFilesystem }}
            capabilities: {drop: ["ALL"]}
          startupProbe:
            httpGet: {path: /actuator/health/liveness, port: management}
            periodSeconds: 5
            failureThreshold: 36
          livenessProbe:
            httpGet: {path: /actuator/health/liveness, port: management}
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 6
          readinessProbe:
            httpGet: {path: /actuator/health/readiness, port: management}
            periodSeconds: 10
            timeoutSeconds: 3
            failureThreshold: 3
            successThreshold: 1
          {{- if has .key (list "console" "coreApi") }}
          lifecycle:
            preStop:
              exec: {command: ["sleep", "5"]}
          {{- end }}
          volumeMounts:
            - {name: tmp, mountPath: /tmp}
            {{- if $keyItems }}
            - {name: service-keys, mountPath: /etc/paybridge/keys, readOnly: true}
            {{- end }}
      volumes:
        - name: tmp
          emptyDir: {sizeLimit: 128Mi}
        {{- if $keyItems }}
        - name: service-keys
          secret:
            secretName: {{ $root.Values.secrets.serviceKeys }}
            defaultMode: 0440
            items:
              {{- $keyItems | nindent 14 }}
        {{- end }}
{{- end -}}
