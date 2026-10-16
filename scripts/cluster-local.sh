#!/usr/bin/env bash
# Local kind cluster with Calico, Gateway API, NGINX Gateway Fabric, metrics-server and KEDA.
# Every version is read from ops/toolchain.lock.yaml; a missing entry exits 3.
#
#   scripts/cluster-local.sh create [--http-port N] [--https-port N] [--with-monitoring]
#   scripts/cluster-local.sh platform [--with-monitoring]   # (re)install prerequisites on the existing cluster
#   scripts/cluster-local.sh status
#   scripts/cluster-local.sh record     # rewrite artifacts/T03/cluster-versions.json from the live cluster
#   scripts/cluster-local.sh delete [--yes]
#   scripts/cluster-local.sh netpol-test
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

HTTP_PORT=80
HTTPS_PORT=443
WITH_MONITORING=false
ASSUME_YES=false

usage() { sed -n '2,11p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2; }

parse_flags() {
  while [ $# -gt 0 ]; do
    case "$1" in
      --http-port) HTTP_PORT="${2:?}"; shift 2 ;;
      --https-port) HTTPS_PORT="${2:?}"; shift 2 ;;
      --with-monitoring) WITH_MONITORING=true; shift ;;
      --yes) ASSUME_YES=true; shift ;;
      *) usage ;;
    esac
  done
}

port_owner() { # prints "name (pid)" when something listens on the host port, nothing otherwise
  powershell.exe -NoProfile -Command "\$c = Get-NetTCPConnection -State Listen -LocalPort $1 -ErrorAction SilentlyContinue | Select-Object -First 1; if (\$c) { \$p = Get-Process -Id \$c.OwningProcess -ErrorAction SilentlyContinue; \"\$(\$p.ProcessName) (\$(\$c.OwningProcess))\" }; exit 0" 2>/dev/null | tr -d '\r'
}

preflight() {
  need docker; need kind; need helm; need kubectl
  docker info >/dev/null 2>&1 || die 3 "Docker is not running"
  local p owner
  for p in "$HTTP_PORT" "$HTTPS_PORT"; do
    owner="$(port_owner "$p")"
    [ -z "$owner" ] || die 1 "host port $p is in use by $owner; retry with --http-port/--https-port"
  done
  kind get clusters 2>/dev/null | grep -qx "$CLUSTER_NAME" && die 1 "cluster '$CLUSTER_NAME' already exists (delete it first)"
  local k; for k in kind_node_image calico_chart calico_crds_chart gateway_api_crds gateway_api_crds_file gateway_api_crds_sha256 \
    nginx_gateway_fabric_chart metrics_server_chart keda_chart kube_prometheus_stack_chart metrics_server_extra_args; do
    lock_get "$k" >/dev/null
  done
}

create_cluster() {
  local out="$PB_ROOT/.tools/rendered"; mkdir -p "$out"
  sed -e "s|__NODE_IMAGE__|$(lock_get kind_node_image)|g" -e "s|__HTTP_PORT__|$HTTP_PORT|" \
      -e "s|__HTTPS_PORT__|$HTTPS_PORT|" "$PB_ROOT/ops/kind/kind-config.yaml" > "$out/kind-config.yaml"
  log "creating kind cluster '$CLUSTER_NAME' (node image $(lock_get kind_node_image | cut -c1-24)...)"
  kind create cluster --config "$(win_path "$out/kind-config.yaml")" --wait 0s
}

install_calico() {
  local v; v="$(lock_get calico_chart)"
  # The operator takes ownership of the Installation fields after the first install, so a second
  # helm upgrade would conflict. Treat an existing release as done and only re-check readiness.
  if hm status calico --namespace tigera-operator >/dev/null 2>&1; then
    log "Calico already installed; checking readiness"
    kc wait --for=condition=Available tigerastatus/calico --timeout=10m
    kc wait --for=condition=Ready nodes --all --timeout=10m
    return 0
  fi
  log "installing Calico (tigera-operator $v)"
  hm repo add projectcalico https://docs.tigera.io/calico/charts >/dev/null 2>&1 || true
  hm repo update projectcalico >/dev/null
  # Since Calico 3.32 the CRDs live in their own chart; server-side apply because some exceed the client-side limit.
  hm template calico-crds projectcalico/crd.projectcalico.org.v1 --version "$(lock_get calico_crds_chart)" | kc apply --server-side -f - >/dev/null
  hm upgrade --install calico projectcalico/tigera-operator --version "$v" --namespace tigera-operator \
    --create-namespace -f "$(win_path "$PB_ROOT/ops/calico/values.yaml")" --wait --timeout 10m
  log "waiting for Calico and for all nodes to be Ready"
  local i
  for i in $(seq 1 60); do kc get tigerastatus calico >/dev/null 2>&1 && break; sleep 5; done
  kc wait --for=condition=Available tigerastatus/calico --timeout=10m
  kc wait --for=condition=Ready nodes --all --timeout=10m
}

install_gateway_api() {
  local f sum; f="$PB_ROOT/$(lock_get gateway_api_crds_file)"
  sum="$(sha256_of "$f")"
  [ "$sum" = "$(lock_get gateway_api_crds_sha256)" ] || die 1 "Gateway API manifest checksum mismatch"
  log "installing Gateway API CRDs $(lock_get gateway_api_crds) (standard channel)"
  kc apply --server-side -f "$(win_path "$f")" >/dev/null
  kc wait --for=condition=Established crd/gateways.gateway.networking.k8s.io --timeout=2m
}

install_ngf() {
  local v; v="$(lock_get nginx_gateway_fabric_chart)"
  log "installing NGINX Gateway Fabric $v"
  hm upgrade --install ngf oci://ghcr.io/nginx/charts/nginx-gateway-fabric --version "$v" \
    --namespace nginx-gateway --create-namespace -f "$(win_path "$PB_ROOT/ops/gateway/ngf-values.yaml")" --wait --timeout 10m
  kc get gatewayclass nginx
}

install_metrics_server() {
  local v; v="$(lock_get metrics_server_chart)"
  log "installing metrics-server $v with $(lock_get metrics_server_extra_args) (local kind only)"
  hm repo add metrics-server https://kubernetes-sigs.github.io/metrics-server/ >/dev/null 2>&1 || true
  hm repo update metrics-server >/dev/null
  hm upgrade --install metrics-server metrics-server/metrics-server --version "$v" --namespace kube-system \
    -f "$(win_path "$PB_ROOT/ops/metrics/values.yaml")" --set "args={$(lock_get metrics_server_extra_args)}" --wait --timeout 5m
  local i
  for i in $(seq 1 30); do
    kc get apiservice v1beta1.metrics.k8s.io -o jsonpath='{.status.conditions[?(@.type=="Available")].status}' 2>/dev/null | grep -q True && return 0
    sleep 5
  done
  die 1 "metrics APIService did not become Available"
}

install_keda() {
  local v; v="$(lock_get keda_chart)"
  log "installing KEDA $v"
  hm repo add kedacore https://kedacore.github.io/charts >/dev/null 2>&1 || true
  hm repo update kedacore >/dev/null
  hm upgrade --install keda kedacore/keda --version "$v" --namespace keda --create-namespace \
    -f "$(win_path "$PB_ROOT/ops/keda/values.yaml")" --wait --timeout 10m
}

install_monitoring() {
  local v; v="$(lock_get kube_prometheus_stack_chart)"
  log "installing kube-prometheus-stack $v (minimal, Grafana disabled)"
  hm repo add prometheus-community https://prometheus-community.github.io/helm-charts >/dev/null 2>&1 || true
  hm repo update prometheus-community >/dev/null
  hm upgrade --install monitoring prometheus-community/kube-prometheus-stack --version "$v" --namespace monitoring \
    --create-namespace -f "$(win_path "$PB_ROOT/ops/monitoring/values.yaml")" --wait --timeout 15m
}

record_versions() {
  mkdir -p "$PB_ROOT/artifacts/T03"
  local out="$PB_ROOT/artifacts/T03/cluster-versions.json"
  {
    printf '{\n  "recordedAt": "%s",\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    printf '  "kind": "%s",\n  "helm": "%s",\n' "$(kind version | awk '{print $2}')" "$(helm version --short)"
    printf '  "kubectlClient": "%s",\n' "$(kubectl version --client -o json | python -I -c 'import sys,json;print(json.load(sys.stdin)["clientVersion"]["gitVersion"])')"
    printf '  "kubernetesServer": "%s",\n' "$(kc version -o json | python -I -c 'import sys,json;print(json.load(sys.stdin)["serverVersion"]["gitVersion"])')"
    printf '  "nodeImage": "%s",\n' "$(lock_get kind_node_image)"
    printf '  "gatewayApiCrds": "%s",\n' "$(lock_get gateway_api_crds)"
    printf '  "nodes": %s,\n' "$(kc get nodes -o json | python -I -c 'import sys,json;print(json.dumps([{"name":n["metadata"]["name"],"kubelet":n["status"]["nodeInfo"]["kubeletVersion"],"os":n["status"]["nodeInfo"]["osImage"]} for n in json.load(sys.stdin)["items"]]))')"
    printf '  "helmReleases": %s\n}\n' "$(hm list -A -o json | python -I -c 'import sys,json;print(json.dumps([{"name":r["name"],"namespace":r["namespace"],"chart":r["chart"],"appVersion":r["app_version"]} for r in json.load(sys.stdin)]))')"
  } > "$out"
  log "recorded $out"
}

cmd_create() {
  parse_flags "$@"
  preflight
  create_cluster
  install_platform
}

install_namespaces() {
  log "creating namespaces with Pod Security labels"
  kc apply -f "$(win_path "$PB_ROOT/ops/namespaces.yaml")" >/dev/null
}

install_platform() {
  install_namespaces
  install_calico
  install_gateway_api
  install_ngf
  install_metrics_server
  install_keda
  if "$WITH_MONITORING"; then install_monitoring; fi
  kc get nodes -o wide
  record_versions
  log "platform ready. public URL: http://console.paybridge.localhost$([ "$HTTP_PORT" = 80 ] || printf ':%s' "$HTTP_PORT")"
}

cmd_platform() { # resume or repeat the prerequisite installs on the existing cluster
  parse_flags "$@"
  need kind; need helm; need kubectl
  kind get clusters | grep -qx "$CLUSTER_NAME" || die 1 "cluster '$CLUSTER_NAME' does not exist"
  install_platform
}

cmd_status() {
  need kind; need kubectl; need helm
  kind get clusters | grep -qx "$CLUSTER_NAME" || die 1 "cluster '$CLUSTER_NAME' does not exist"
  kc get nodes -o wide
  hm list -A
  kc get apiservice v1beta1.metrics.k8s.io v1beta1.external.metrics.k8s.io 2>/dev/null || true
  kc get gatewayclass
}

cmd_delete() {
  parse_flags "$@"
  need kind
  kind get clusters | grep -qx "$CLUSTER_NAME" || die 1 "cluster '$CLUSTER_NAME' does not exist"
  if ! "$ASSUME_YES"; then
    printf "This deletes the kind cluster '%s' and every volume in it. Type the cluster name to confirm: " "$CLUSTER_NAME"
    local a; read -r a
    [ "$a" = "$CLUSTER_NAME" ] || die 1 "not confirmed"
  fi
  kind delete cluster --name "$CLUSTER_NAME"
}

main() {
  local sub="${1:-}"; [ $# -eq 0 ] || shift
  case "$sub" in
    create) cmd_create "$@" ;;
    platform) cmd_platform "$@" ;;
    record) need kind; need kubectl; need helm; record_versions ;;
    status) cmd_status ;;
    delete) cmd_delete "$@" ;;
    netpol-test) exec "$PB_ROOT/scripts/netpol-test.sh" "$@" ;;
    *) usage ;;
  esac
}
main "$@"
