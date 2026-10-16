#!/usr/bin/env bash
# Shared helpers for PayBridge scripts. Source this file; do not execute it.
# Exit codes used by all scripts: 0 ok, 1 failure, 2 usage, 3 missing prerequisite or lock entry.

set -euo pipefail

PB_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
export PB_ROOT
# Project-local tools (kind, helm) win over anything on the system PATH.
export PATH="$PB_ROOT/.tools/bin:$PATH"
export MSYS_NO_PATHCONV=1            # keep Git Bash from rewriting container paths such as /tmp
LOCK_FILE="$PB_ROOT/ops/toolchain.lock.yaml"
CLUSTER_NAME="paybridge"
KUBE_CONTEXT="kind-${CLUSTER_NAME}"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
warn() { printf 'WARN: %s\n' "$*" >&2; }
die() { local code="$1"; shift; printf 'ERROR: %s\n' "$*" >&2; exit "$code"; }

need() { command -v "$1" >/dev/null 2>&1 || die 3 "$1 not found on PATH (run scripts/doctor.sh)"; }

# lock_get KEY: value of a key under the top-level "platform:" block; exit 3 when missing.
lock_get() {
  local v
  v="$(awk -v k="$1" '
    /^platform:/ { p = 1; next }
    /^[^ #]/     { p = 0 }
    p && $1 == k":" { sub(/^[^:]*:[ ]*/, ""); sub(/[ ]+#.*$/, ""); gsub(/"/, ""); print; exit }
  ' "$LOCK_FILE")"
  [ -n "$v" ] || die 3 "missing platform.$1 in ops/toolchain.lock.yaml"
  printf '%s' "$v"
}

# kc: kubectl pinned to the PayBridge kind context so another current context is never touched.
kc() { kubectl --context "$KUBE_CONTEXT" "$@"; }
hm() { helm --kube-context "$KUBE_CONTEXT" "$@"; }

# win_path PATH: path form accepted by native Windows tools (kind.exe, helm.exe) when running under Git Bash.
win_path() { if command -v cygpath >/dev/null 2>&1; then cygpath -m "$1"; else printf '%s' "$1"; fi; }

sha256_of() { sha256sum "$1" | cut -d' ' -f1; }
