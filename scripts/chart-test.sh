#!/usr/bin/env bash
# Offline chart checks: lint and render every overlay, then prove each guard rail rejects an unsafe render.
# No cluster needed. Usage: scripts/chart-test.sh
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need helm

CHART="$PB_ROOT/deploy/helm/paybridge"
ENVS="$PB_ROOT/deploy/environments"
fail=0
pass() { printf '  ok    %s\n' "$1"; }
bad()  { printf '  FAIL  %s\n' "$1"; fail=1; }

echo "Overlays render"
for f in local local-scale; do
  helm lint "$(win_path "$CHART")" -f "$(win_path "$ENVS/$f.yaml")" >/dev/null 2>&1 && pass "lint $f" || bad "lint $f"
  helm template paybridge "$(win_path "$CHART")" -f "$(win_path "$ENVS/$f.yaml")" >/dev/null 2>&1 && pass "template $f" || bad "template $f"
done

echo "Local profile renders no scaler; local-scale renders exactly one controller per Deployment"
local_out="$(helm template paybridge "$(win_path "$CHART")" -f "$(win_path "$ENVS/local.yaml")")"
scale_out="$(helm template paybridge "$(win_path "$CHART")" -f "$(win_path "$ENVS/local-scale.yaml")")"
grep -q 'kind: HorizontalPodAutoscaler' <<<"$local_out" && bad "local renders an HPA" || pass "local has no HPA"
grep -q 'kind: ScaledObject' <<<"$local_out" && bad "local renders a ScaledObject" || pass "local has no ScaledObject"
[ "$(grep -c 'kind: HorizontalPodAutoscaler' <<<"$scale_out")" = 2 ] && pass "local-scale: 2 HPAs (console, core-api)" || bad "HPA count"
[ "$(grep -c 'kind: ScaledObject' <<<"$scale_out")" = 4 ] && pass "local-scale: 4 ScaledObjects (relay, intake, executor, projection)" || bad "ScaledObject count"
grep -q 'name: core-api-canary' <<<"$scale_out" && pass "canary Deployment and weighted route render" || bad "canary missing"
grep -q "kind IN\|kind IN ('COLLECT','VERIFY_PENDING','RECONCILE','WEBHOOK_APPLY')" <<<"$scale_out" && pass "executor KEDA query excludes REPLAY tasks" || bad "executor query"

echo "Guard rails reject unsafe values"
expect_reject() { # expect_reject DESCRIPTION EXPECTED_MESSAGE_FRAGMENT helm-set-args...
  local desc="$1" frag="$2"; shift 2
  local out
  if out="$(helm template paybridge "$(win_path "$CHART")" -f "$(win_path "$ENVS/local.yaml")" "$@" 2>&1)"; then bad "$desc (rendered)"
  elif grep -qi -- "$frag" <<<"$out"; then pass "$desc"
  else bad "$desc (rejected for another reason: $(head -c 200 <<<"$out"))"; fi
}
expect_reject "connection budget exceeded"      "rule 1"   --set roles.coreApi.autoscaling.enabled=true --set roles.coreApi.autoscaling.maxReplicas=9
expect_reject "provider budget exceeded"        "rule 2"   --set roles.executor.scaling.enabled=true --set roles.executor.scaling.maxReplicas=4
expect_reject "KEDA on an HPA role"             "rule 4"   --set roles.console.scaling.enabled=true
expect_reject "scale to zero"                   "minimum"   --set roles.relay.scaling.enabled=true --set roles.relay.scaling.minReplicas=0
expect_reject "hosted profile without digests"  "rule 6"   --set global.profile=vps
expect_reject "credential in values"            "rule 7"   --set data.mysql.adminPassword=hunter2
expect_reject "TLS without secure cookie"       "rule 9"   --set gateway.console.tls.enabled=true
expect_reject "lease renew above one third"     "rule 10"  --set timing.taskLeaseRenewSeconds=30
expect_reject "grace period too close"          "rule 10"  --set timing.terminationGracePeriodSeconds=35
expect_reject "floating latest tag"             "images/core/tag"   --set images.core.tag=latest
expect_reject "unknown profile"                 "profile"  --set global.profile=prod
expect_reject "canary weights not 100"          "100"      --set gateway.apiInternal.canary.canaryWeight=10

exit "$fail"
