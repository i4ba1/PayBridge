{{/* Labels. Call with (dict "root" $ "component" "core-api" [ "track" "stable" ]). */}}
{{- define "pb.labels" -}}
app.kubernetes.io/name: paybridge
app.kubernetes.io/component: {{ .component }}
app.kubernetes.io/part-of: paybridge
app.kubernetes.io/version: {{ .root.Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .root.Release.Service }}
{{- if .track }}
paybridge.io/track: {{ .track }}
{{- end }}
{{- end -}}

{{/* Selector labels. Immutable on Deployments, so they never include the version. */}}
{{- define "pb.selector" -}}
app.kubernetes.io/name: paybridge
app.kubernetes.io/component: {{ .component }}
{{- if .track }}
paybridge.io/track: {{ .track }}
{{- end }}
{{- end -}}

{{/* Role table: chart key -> fixed names. Deployment and Service names are the fixed role names (POR 4.1). */}}
{{- define "pb.roleTable" -}}
{{- $t := dict -}}
{{- $_ := set $t "console" (dict "name" "console" "image" "console" "env" "console" "sa" "paybridge-app" "http" true "scaler" "hpa") -}}
{{- $_ := set $t "coreApi" (dict "name" "core-api" "image" "core" "env" "api" "sa" "paybridge-app" "http" true "scaler" "hpa") -}}
{{- $_ := set $t "relay" (dict "name" "relay" "image" "core" "env" "relay" "sa" "paybridge-app" "http" false "scaler" "keda") -}}
{{- $_ := set $t "intake" (dict "name" "intake" "image" "core" "env" "intake" "sa" "paybridge-app" "http" false "scaler" "keda") -}}
{{- $_ := set $t "executor" (dict "name" "executor" "image" "core" "env" "executor" "sa" "paybridge-app" "http" false "scaler" "keda") -}}
{{- $_ := set $t "projection" (dict "name" "projection" "image" "core" "env" "projection" "sa" "paybridge-app" "http" false "scaler" "keda") -}}
{{- $_ := set $t "simulator" (dict "name" "simulator" "image" "simulator" "env" "simulator" "sa" "paybridge-simulator" "http" true "scaler" "none") -}}
{{- toJson $t -}}
{{- end -}}

{{/* Image reference: repository@digest when a digest is set, repository:tag otherwise. Call with (dict "root" $ "group" "core" "override" <image map or nil>). */}}
{{- define "pb.image" -}}
{{- $img := index .root.Values.images .group -}}
{{- $reg := .root.Values.images.registry -}}
{{- $repo := ternary (printf "%s/%s" $reg $img.repository) $img.repository (ne $reg "") -}}
{{- $digest := $img.digest -}}
{{- $tag := $img.tag -}}
{{- if .override -}}
{{- if or .override.digest .override.tag -}}
{{- $digest = .override.digest -}}
{{- $tag = .override.tag -}}
{{- end -}}
{{- end -}}
{{- if $digest -}}{{ $repo }}@{{ $digest }}{{- else -}}{{ $repo }}:{{ $tag }}{{- end -}}
{{- end -}}

{{/* Maximum replicas a role can reach under its configured scaler. Call with (dict "v" <role values>). */}}
{{- define "pb.maxReplicas" -}}
{{- $v := .v -}}
{{- if and $v.autoscaling $v.autoscaling.enabled -}}{{ $v.autoscaling.maxReplicas }}
{{- else if and $v.scaling $v.scaling.enabled -}}{{ $v.scaling.maxReplicas }}
{{- else -}}{{ $v.replicas }}{{- end -}}
{{- end -}}

{{/* Recursive scan for credential-looking keys (rule 7). Call with (dict "node" <map> "path" "data" "allowed" <list of Secret names>). */}}
{{- define "pb.scanSecrets" -}}
{{- $path := .path -}}
{{- $allowed := .allowed -}}
{{- range $k, $val := .node -}}
{{- $here := printf "%s.%s" $path $k -}}
{{- if kindIs "map" $val -}}
{{- include "pb.scanSecrets" (dict "node" $val "path" $here "allowed" $allowed) -}}
{{- else if regexMatch "(?i)password|secret|token|apikey|privatekey|credentials" $k -}}
{{- if not (and (kindIs "string" $val) (has $val $allowed)) -}}
{{- fail (printf "values key %s looks like a credential; reference a Secret by name instead (rule 7)" $here) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- end -}}
