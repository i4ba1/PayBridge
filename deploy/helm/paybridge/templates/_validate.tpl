{{/* Render-time rules from POR 4.3 that JSON schema cannot express. Included once from NOTES.txt-independent validate.yaml. */}}
{{- define "pb.validate" -}}
{{- $v := .Values -}}
{{- $r := $v.roles -}}

{{/* 1: MySQL connection budget */}}
{{- $conns := 0 -}}
{{- $conns = add $conns (mul (include "pb.maxReplicas" (dict "v" $r.coreApi) | int) (add $v.pools.coreApi.writer $v.pools.coreApi.reader)) -}}
{{- range $k := list "relay" "intake" "executor" "projection" -}}
{{- $conns = add $conns (mul (include "pb.maxReplicas" (dict "v" (index $r $k)) | int) (index $v.pools $k)) -}}
{{- end -}}
{{- if $r.simulator.enabled -}}
{{- $conns = add $conns (mul (int $r.simulator.replicas) $v.pools.simulator) -}}
{{- end -}}
{{- if gt (int $conns) (int $v.budgets.mysqlConnections) -}}
{{- fail (printf "rule 1: worst-case MySQL connections %d exceed budgets.mysqlConnections %d" (int $conns) (int $v.budgets.mysqlConnections)) -}}
{{- end -}}

{{/* 2: provider concurrency budget */}}
{{- $prov := mul (include "pb.maxReplicas" (dict "v" $r.executor) | int) (int $r.executor.providerCallsPerPod) -}}
{{- if gt (int $prov) (int $v.budgets.providerConcurrency) -}}
{{- fail (printf "rule 2: executor max replicas x providerCallsPerPod = %d exceeds budgets.providerConcurrency %d" (int $prov) (int $v.budgets.providerConcurrency)) -}}
{{- end -}}

{{/* 4 and 5: one scaling controller per Deployment, bounds */}}
{{- range $k := list "console" "coreApi" -}}
{{- $role := index $r $k -}}
{{- if and (hasKey $role "scaling") $role.scaling.enabled -}}{{- fail (printf "rule 4: KEDA scaling is not allowed on %s (HPA role)" $k) -}}{{- end -}}
{{- if $role.autoscaling.enabled -}}
{{- if or (lt (int $role.autoscaling.minReplicas) 1) (gt (int $role.autoscaling.minReplicas) (int $role.autoscaling.maxReplicas)) -}}
{{- fail (printf "rule 5: %s autoscaling needs 1 <= minReplicas <= maxReplicas" $k) -}}
{{- end -}}
{{- end -}}
{{- end -}}
{{- range $k := list "relay" "intake" "executor" "projection" -}}
{{- $role := index $r $k -}}
{{- if and (hasKey $role "autoscaling") $role.autoscaling.enabled -}}{{- fail (printf "rule 4: HPA autoscaling is not allowed on %s (KEDA role)" $k) -}}{{- end -}}
{{- if $role.scaling.enabled -}}
{{- if or (lt (int $role.scaling.minReplicas) 1) (gt (int $role.scaling.minReplicas) (int $role.scaling.maxReplicas)) -}}
{{- fail (printf "rule 5: %s scaling needs 1 <= minReplicas <= maxReplicas (scale to zero is not allowed)" $k) -}}
{{- end -}}
{{- end -}}
{{- end -}}

{{/* 6: hosted profiles deploy by digest */}}
{{- if has $v.global.profile (list "vps" "aws") -}}
{{- range $g := list "core" "console" "simulator" -}}
{{- if not (index $v.images $g).digest -}}{{- fail (printf "rule 6: images.%s.digest is required for profile %s" $g $v.global.profile) -}}{{- end -}}
{{- end -}}
{{- end -}}

{{/* 7: no credentials in values */}}
{{- $allowed := values $v.secrets -}}
{{- range $sec := list "data" "gateway" "monitoring" "backup" -}}
{{- if hasKey $v $sec -}}
{{- include "pb.scanSecrets" (dict "node" (index $v $sec) "path" $sec "allowed" $allowed) -}}
{{- end -}}
{{- end -}}

{{/* 9: TLS needs an issuer or existing Secret, and a secure cookie */}}
{{- if $v.gateway.console.tls.enabled -}}
{{- if and (not $v.gateway.console.tls.clusterIssuer) (not $v.gateway.console.tls.secretName) -}}{{- fail "rule 9: gateway.console.tls needs clusterIssuer or secretName" -}}{{- end -}}
{{- if not $v.session.secureCookie -}}{{- fail "rule 9: session.secureCookie must be true when TLS is enabled" -}}{{- end -}}
{{- end -}}

{{/* 10: lease and shutdown safety */}}
{{- if gt (mul (int $v.timing.taskLeaseRenewSeconds) 3) (int $v.timing.taskLeaseSeconds) -}}
{{- fail "rule 10: timing.taskLeaseRenewSeconds must be at most one third of taskLeaseSeconds" -}}
{{- end -}}
{{- if lt (sub (int $v.timing.terminationGracePeriodSeconds) (int $v.timing.gracefulShutdownSeconds)) 10 -}}
{{- fail "rule 10: terminationGracePeriodSeconds must exceed gracefulShutdownSeconds by at least 10" -}}
{{- end -}}

{{/* canary weights must add up to 100 */}}
{{- $w := $v.gateway.apiInternal.canary -}}
{{- if ne (add (int $w.stableWeight) (int $w.canaryWeight)) 100 -}}
{{- fail "gateway.apiInternal.canary weights must add up to 100" -}}
{{- end -}}
{{- end -}}
