#!/usr/bin/env bash
# Proves NetworkPolicies are enforced: launches labeled throwaway pods and asserts every allowed path connects
# and every denied path times out. Applies the application chart's policies for the test and removes them afterwards
# unless they already existed. Writes artifacts/T03/netpol-denial.txt. Exit 1 on any mismatch.
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need kubectl; need helm

APP_NS=paybridge
DATA_NS=paybridge-data
OTHER_NS=netpol-probe
IMAGE="$(awk '/^  mysql:/ {print $2}' "$LOCK_FILE")"          # already present on the nodes; has bash and /dev/tcp
OUT="$PB_ROOT/artifacts/T03/netpol-denial.txt"
CONNECT_TIMEOUT=4
mkdir -p "$(dirname "$OUT")"
: > "$OUT"
failures=0

say() { printf '%s\n' "$*" | tee -a "$OUT"; }

applied_policies=false
apply_app_policies() {
  if kc -n "$APP_NS" get networkpolicy default-deny-all >/dev/null 2>&1; then say "application policies already present"; return; fi
  say "applying application chart policies for the test"
  helm template paybridge "$(win_path "$PB_ROOT/deploy/helm/paybridge")" -n "$APP_NS" -f "$(win_path "$PB_ROOT/deploy/environments/local.yaml")" \
    -s templates/networkpolicies.yaml | kc -n "$APP_NS" apply -f - >/dev/null
  applied_policies=true
}

cleanup() {
  kc -n "$APP_NS" delete pod -l paybridge.io/netpol-test=true --ignore-not-found --wait=false >/dev/null 2>&1 || true
  kc delete ns "$OTHER_NS" --ignore-not-found --wait=false >/dev/null 2>&1 || true
  if "$applied_policies"; then
    helm template paybridge "$(win_path "$PB_ROOT/deploy/helm/paybridge")" -n "$APP_NS" -f "$(win_path "$PB_ROOT/deploy/environments/local.yaml")" \
      -s templates/networkpolicies.yaml | kc -n "$APP_NS" delete -f - --ignore-not-found >/dev/null 2>&1 || true
  fi
}
trap cleanup EXIT

# probe_pod NS NAME COMPONENT: pod labeled like an application role (COMPONENT empty = unlabeled)
probe_pod() {
  local ns="$1" name="$2" comp="$3" labels="paybridge.io/netpol-test=true"
  [ -z "$comp" ] || labels="$labels,app.kubernetes.io/name=paybridge,app.kubernetes.io/component=$comp"
  kc -n "$ns" delete pod "$name" --ignore-not-found --wait=true >/dev/null 2>&1 || true
  kc -n "$ns" run "$name" --image="$IMAGE" --restart=Never --labels="$labels" \
    --overrides='{"spec":{"automountServiceAccountToken":false,"securityContext":{"runAsNonRoot":true,"runAsUser":999,"runAsGroup":999,"seccompProfile":{"type":"RuntimeDefault"}},"containers":[{"name":"'"$name"'","image":"'"$IMAGE"'","command":["sleep","600"],"securityContext":{"allowPrivilegeEscalation":false,"capabilities":{"drop":["ALL"]}}}]}}' >/dev/null
}

wait_pods() { # wait_pods NS NAME...
  local ns="$1"; shift
  local n; for n in "$@"; do kc -n "$ns" wait --for=condition=Ready "pod/$n" --timeout=3m >/dev/null; done
}

# expect NS POD HOST PORT allow|deny DESCRIPTION
expect() {
  local ns="$1" pod="$2" host="$3" port="$4" want="$5" desc="$6" got
  if kc -n "$ns" exec "$pod" -- timeout "$CONNECT_TIMEOUT" bash -c "echo > /dev/tcp/$host/$port" >/dev/null 2>&1; then got=allow; else got=deny; fi
  if [ "$got" = "$want" ]; then say "PASS  $desc: $got ($pod -> $host:$port)"
  else say "FAIL  $desc: expected $want, got $got ($pod -> $host:$port)"; failures=$((failures + 1)); fi
}

MYSQL="mysql-writer.$DATA_NS.svc.cluster.local"
REDIS="redis.$DATA_NS.svc.cluster.local"
RABBIT="rabbitmq.$DATA_NS.svc.cluster.local"
MONGO="mongodb.$DATA_NS.svc.cluster.local"

say "NetworkPolicy enforcement test, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
say "cluster: $(kc version -o json | python -I -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])'), CNI: Calico $(lock_get calico_chart)"
kc -n "$DATA_NS" get statefulset mysql redis rabbitmq mongodb >/dev/null 2>&1 || die 1 "lab data services are not installed (helm install paybridge-lab-data)"
kc -n "$DATA_NS" get networkpolicy default-deny-all >/dev/null 2>&1 || die 1 "data tier policies are not installed"

apply_app_policies
kc get ns "$OTHER_NS" >/dev/null 2>&1 || kc create ns "$OTHER_NS" >/dev/null
kc label ns "$OTHER_NS" pod-security.kubernetes.io/enforce=baseline --overwrite >/dev/null

probe_pod "$OTHER_NS" outsider ""
probe_pod "$APP_NS" unlabeled ""
probe_pod "$APP_NS" role-core-api core-api
probe_pod "$APP_NS" role-relay relay
probe_pod "$APP_NS" role-executor executor
probe_pod "$APP_NS" role-console console
wait_pods "$OTHER_NS" outsider
wait_pods "$APP_NS" unlabeled role-core-api role-relay role-executor role-console

say ""
say "--- another namespace: every data service must be unreachable"
for t in "$MYSQL:3306" "$REDIS:6379" "$RABBIT:5672" "$MONGO:27017"; do expect "$OTHER_NS" outsider "${t%:*}" "${t##*:}" deny "other namespace"; done

say ""
say "--- application namespace, pod without a role label: default deny"
for t in "$MYSQL:3306" "$REDIS:6379" "$RABBIT:5672" "$MONGO:27017"; do expect "$APP_NS" unlabeled "${t%:*}" "${t##*:}" deny "unlabeled pod"; done

say ""
say "--- core-api: MySQL, Redis, MongoDB allowed; RabbitMQ denied"
expect "$APP_NS" role-core-api "$MYSQL" 3306 allow "core-api"
expect "$APP_NS" role-core-api "$REDIS" 6379 allow "core-api"
expect "$APP_NS" role-core-api "$MONGO" 27017 allow "core-api"
expect "$APP_NS" role-core-api "$RABBIT" 5672 deny "core-api"

say ""
say "--- relay: MySQL and RabbitMQ allowed; Redis and MongoDB denied"
expect "$APP_NS" role-relay "$MYSQL" 3306 allow "relay"
expect "$APP_NS" role-relay "$RABBIT" 5672 allow "relay"
expect "$APP_NS" role-relay "$REDIS" 6379 deny "relay"
expect "$APP_NS" role-relay "$MONGO" 27017 deny "relay"

say ""
say "--- executor: MySQL and Redis allowed; RabbitMQ and MongoDB denied"
expect "$APP_NS" role-executor "$MYSQL" 3306 allow "executor"
expect "$APP_NS" role-executor "$REDIS" 6379 allow "executor"
expect "$APP_NS" role-executor "$RABBIT" 5672 deny "executor"
expect "$APP_NS" role-executor "$MONGO" 27017 deny "executor"

say ""
say "--- console: Redis allowed; the database tier denied (console has no database account)"
expect "$APP_NS" role-console "$REDIS" 6379 allow "console"
expect "$APP_NS" role-console "$MYSQL" 3306 deny "console"
expect "$APP_NS" role-console "$RABBIT" 5672 deny "console"
expect "$APP_NS" role-console "$MONGO" 27017 deny "console"

say ""
say "--- data tier egress: a database pod cannot reach the internet or the application namespace"
expect "$DATA_NS" mysql-0 1.1.1.1 443 deny "mysql egress to internet"
expect "$DATA_NS" mysql-0 console.$APP_NS.svc.cluster.local 8080 deny "mysql egress to application"

say ""
if [ "$failures" -eq 0 ]; then say "RESULT: all paths behaved as specified"; else say "RESULT: $failures path(s) behaved differently from the specification"; fi
exit $((failures > 0))
