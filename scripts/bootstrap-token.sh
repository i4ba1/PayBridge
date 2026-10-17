#!/usr/bin/env bash
# Creates or replaces the first-run setup secret (Secret paybridge-bootstrap, key token) and, with --rearm, reruns the
# seed Job so the core stores the token's hash and a fresh expiry (DMR section 3). Works only while no administrator
# exists: once setup is consumed the seed ignores the token. The raw token is never printed unless --show is given.
#
#   scripts/bootstrap-token.sh              create the Secret if it does not exist (keeps an existing token)
#   scripts/bootstrap-token.sh --rearm      replace the token, then rerun the seed Job to arm it
#   scripts/bootstrap-token.sh --show       also print the token once, for the person who runs setup
#   scripts/bootstrap-token.sh --delete     remove the Secret (setup-admin.sh does this after a successful setup)
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need kubectl; need python

NS=paybridge
SECRET=paybridge-bootstrap
rearm=false; show=false; delete=false
for arg in "$@"; do
  case "$arg" in
    --rearm) rearm=true ;;
    --show) show=true ;;
    --delete) delete=true ;;
    *) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
  esac
done

if "$delete"; then
  kc -n "$NS" delete secret "$SECRET" --ignore-not-found
  exit 0
fi

new_token() { python -I -c 'import base64,secrets;print(base64.urlsafe_b64encode(secrets.token_bytes(32)).decode().rstrip("="))'; }

exists=false
kc -n "$NS" get secret "$SECRET" >/dev/null 2>&1 && exists=true
if ! "$exists" || "$rearm"; then
  tmp="$(mktemp)"; trap 'rm -f "$tmp"' EXIT
  printf 'token=%s\n' "$(new_token)" > "$tmp"
  kc -n "$NS" create secret generic "$SECRET" --from-env-file="$(win_path "$tmp")" --dry-run=client -o yaml | kc apply -f - >/dev/null
  log "secret $NS/$SECRET written (token not printed)"
else
  log "secret $NS/$SECRET already exists; keeping its token (use --rearm to replace it)"
fi

if "$rearm"; then
  if kc -n "$NS" get job paybridge-seed >/dev/null 2>&1; then
    kc -n "$NS" delete job paybridge-seed --wait=true >/dev/null
  fi
  log "rerun the seed Job to arm the new token: helm upgrade/install hook, or scripts/install-local.sh --reseed (T19)"
fi

if "$show"; then
  warn "printing the setup secret once; it is single use and expires"
  kc -n "$NS" get secret "$SECRET" -o jsonpath='{.data.token}' | python -I -c 'import base64,sys;print(base64.b64decode(sys.stdin.read()).decode())'
fi
