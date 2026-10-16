#!/usr/bin/env bash
# Platform smoke for the gateway layer using the REAL chart objects with throwaway stub backends:
#   public Gateway reachable on the host port, weighted private route splits traffic, edge rate limit rejects a burst,
#   body size limit rejects an oversized request. Writes artifacts/T03/gateway-smoke.txt and removes everything it created.
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need kubectl; need helm; need curl

NS=paybridge
HOST=console.paybridge.localhost
HTTP_PORT="${HTTP_PORT:-80}"
IMAGE="busybox:1.37"
OUT="$PB_ROOT/artifacts/T03/gateway-smoke.txt"
mkdir -p "$(dirname "$OUT")"; : > "$OUT"
fail=0
say() { printf '%s\n' "$*" | tee -a "$OUT"; }
check() { if [ "$1" = ok ]; then say "PASS  $2"; else say "FAIL  $2"; fail=1; fi; }

CHART="$(win_path "$PB_ROOT/deploy/helm/paybridge")"
VALUES="$(win_path "$PB_ROOT/deploy/environments/local-scale.yaml")"

render() { # only the gateway-layer templates
  helm template paybridge "$CHART" -n "$NS" -f "$VALUES" --set gateway.apiInternal.canary.stableWeight=50 \
    --set gateway.apiInternal.canary.canaryWeight=50 -s templates/gateways.yaml -s templates/routes.yaml -s templates/rate-limit.yaml
}

stub() { # stub NAME COMPONENT TRACK BODY
  cat <<YAML
apiVersion: apps/v1
kind: Deployment
metadata: {name: $1, namespace: $NS}
spec:
  replicas: 1
  selector: {matchLabels: {app.kubernetes.io/name: paybridge, app.kubernetes.io/component: $2$([ -n "$3" ] && printf ', paybridge.io/track: %s' "$3")}}
  template:
    metadata:
      labels: {app.kubernetes.io/name: paybridge, app.kubernetes.io/component: $2$([ -n "$3" ] && printf ', paybridge.io/track: %s' "$3")}
    spec:
      automountServiceAccountToken: false
      securityContext: {runAsNonRoot: true, runAsUser: 10001, runAsGroup: 10001, seccompProfile: {type: RuntimeDefault}}
      containers:
        - name: web
          image: $IMAGE
          command: ["sh", "-c", "mkdir -p /tmp/www && echo $4 > /tmp/www/graphql && echo $4 > /tmp/www/index.html && exec httpd -f -p 8080 -h /tmp/www"]
          ports: [{name: http, containerPort: 8080}]
          securityContext: {allowPrivilegeEscalation: false, capabilities: {drop: ["ALL"]}}
          readinessProbe: {tcpSocket: {port: 8080}, periodSeconds: 2}
YAML
}

cleanup() {
  render | kc -n "$NS" delete -f - --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kc -n "$NS" delete deploy/console deploy/core-api-stable deploy/core-api-canary svc/console svc/core-api-stable svc/core-api-canary \
    --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kc -n "$NS" delete pod gwprobe --ignore-not-found --wait=false >/dev/null 2>&1 || true
}
[ "${KEEP:-}" = 1 ] || trap cleanup EXIT

cleanup >/dev/null 2>&1 || true     # leftovers from an earlier KEEP=1 run
kc -n "$NS" wait --for=delete pod/gwprobe --timeout=60s >/dev/null 2>&1 || true
say "Gateway smoke, $(date -u +%Y-%m-%dT%H:%M:%SZ), NGF $(lock_get nginx_gateway_fabric_chart), Gateway API $(lock_get gateway_api_crds)"
{ stub console console "" "console-ok"; echo ---; stub core-api-stable core-api stable stable; echo ---; stub core-api-canary core-api canary canary; } | kc apply -f - >/dev/null
svc() { # svc NAME COMPONENT TRACK
  cat <<YAML
apiVersion: v1
kind: Service
metadata: {name: $1, namespace: $NS}
spec:
  selector: {app.kubernetes.io/name: paybridge, app.kubernetes.io/component: $2$([ -n "$3" ] && printf ', paybridge.io/track: %s' "$3")}
  ports: [{name: http, port: 8080, targetPort: http}]
YAML
}
{ svc console console ""; echo ---; svc core-api-stable core-api stable; echo ---; svc core-api-canary core-api canary; } | kc apply -f - >/dev/null
kc -n "$NS" rollout status deploy/console deploy/core-api-stable deploy/core-api-canary --timeout=3m >/dev/null

render | kc -n "$NS" apply -f - >/dev/null
say "applied chart Gateways, HTTPRoutes and edge policies"

for i in $(seq 1 60); do
  kc -n "$NS" get gateway console-public api-internal -o jsonpath='{range .items[*]}{.status.conditions[?(@.type=="Programmed")].status}{"\n"}{end}' 2>/dev/null | grep -c True | grep -q 2 && break
  sleep 3
done
kc -n "$NS" get gateway
for g in console-public api-internal; do
  [ "$(kc -n "$NS" get gateway "$g" -o jsonpath='{.status.conditions[?(@.type=="Programmed")].status}')" = True ] && check ok "gateway $g is Programmed" || check bad "gateway $g is Programmed"
done
for p in "ratelimitpolicy console-edge-limit" "clientsettingspolicy console-client-settings"; do
  st="$(kc -n "$NS" get $p -o jsonpath='{.status.ancestors[0].conditions[?(@.type=="Accepted")].status}' 2>/dev/null || true)"
  [ "$st" = True ] && check ok "$p Accepted by the gateway" || check bad "$p Accepted (status '$st')"
done
svc="$(kc -n "$NS" get svc -o name | grep -E 'console-public|api-internal' || true)"
say "data plane services: $(echo $svc | tr '\n' ' ')"
say "public service ports: $(kc -n "$NS" get svc console-public-nginx -o jsonpath='{.spec.type} {.spec.ports[*].port}:{.spec.ports[*].nodePort}' 2>/dev/null)"

say ""
say "--- public entry on the host"
# Programmed does not mean the data plane has received its configuration yet: wait for the first good answer.
for _ in $(seq 1 60); do
  [ "$(curl -s -m 5 -o /dev/null -w '%{http_code}' -H "Host: $HOST" "http://127.0.0.1:$HTTP_PORT/" 2>/dev/null || true)" = 200 ] && break
  sleep 3
done
code="$(curl -s -m 10 -o "$(win_path "$PB_ROOT/.tools/pb-body")" -w '%{http_code}' -H "Host: $HOST" "http://127.0.0.1:$HTTP_PORT/" || true)"
[ "$code" = 200 ] && grep -q console-ok "$PB_ROOT/.tools/pb-body" && check ok "GET / with Host $HOST on host port $HTTP_PORT returns 200 from the console backend" || check bad "public GET (HTTP $code)"
code="$(curl -s -m 10 -o /dev/null -w '%{http_code}' -H "Host: unknown.example" "http://127.0.0.1:$HTTP_PORT/" || true)"
[ "$code" = 404 ] && check ok "unknown host is not routed (HTTP 404)" || check bad "unknown host returned $code"

say ""
say "--- weighted private route (50/50 during this test)"
kc -n "$NS" run gwprobe --image="$IMAGE" --restart=Never --labels="app.kubernetes.io/name=paybridge,app.kubernetes.io/component=console" \
  --overrides='{"spec":{"automountServiceAccountToken":false,"securityContext":{"runAsNonRoot":true,"runAsUser":10001,"runAsGroup":10001,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"gwprobe","image":"'"$IMAGE"'","command":["sleep","300"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
kc -n "$NS" wait --for=condition=Ready pod/gwprobe --timeout=2m >/dev/null
for _ in $(seq 1 40); do
  kc -n "$NS" exec gwprobe -- wget -q -T 3 -O - "http://api-internal-nginx.$NS.svc.cluster.local:8080/graphql" >/dev/null 2>&1 && break
  sleep 3
done
counts="$(kc -n "$NS" exec gwprobe -- sh -c 'for i in $(seq 1 60); do wget -q -T 3 -O - http://api-internal-nginx.'"$NS"'.svc.cluster.local:8080/graphql 2>/dev/null || echo fail; done' | sort | uniq -c | tr '
' ' ' || true)"
stable="$(grep -o '[0-9]* stable' <<<"$counts" | cut -d' ' -f1)"; stable="${stable:-0}"
canary="$(grep -o '[0-9]* canary' <<<"$counts" | cut -d' ' -f1)"; canary="${canary:-0}"
say "60 requests through api-internal: $counts"
[ "$stable" -ge 10 ] && [ "$canary" -ge 10 ] && check ok "both backends receive traffic (weights honoured)" || check bad "weighted split did not occur"

say ""
say "--- edge rate limit (50 r/s, burst 100, per client address; nginx limit_req) and body size limit (1m)"
burst_args=(); for _ in $(seq 1 600); do burst_args+=(-o /dev/null "http://127.0.0.1:$HTTP_PORT/"); done
codes="$(curl -Z --parallel-max 100 -s -m 20 -w '%{http_code}
' -H "Host: $HOST" "${burst_args[@]}" 2>/dev/null | sort | uniq -c | tr '
' ' ' || true)"
say "600 parallel requests: $codes"
echo "$codes" | grep -q '503' && check ok "burst above the limit is rejected (503)" || check bad "no request was rate limited"
sleep 3
dd if=/dev/zero bs=1024 count=2048 2>/dev/null | head -c 2097152 > $PB_ROOT/.tools/pb-big
code="$(curl -s -m 20 -o /dev/null -w '%{http_code}' -X POST --data-binary @"$(win_path "$PB_ROOT/.tools/pb-big")" -H "Host: $HOST" "http://127.0.0.1:$HTTP_PORT/" || true)"
[ "$code" = 413 ] && check ok "2 MiB body rejected (413)" || check bad "oversized body returned $code"
rm -f $PB_ROOT/.tools/pb-body $PB_ROOT/.tools/pb-big

exit "$fail"
