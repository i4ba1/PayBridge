#!/usr/bin/env bash
# Prints the demo account usernames and passwords stored in Secret paybridge-demo-accounts (written by the seed Job).
# Values are printed only on explicit request: run this script yourself; no other script calls it.
#
#   scripts/demo-credentials.sh            print username and password pairs
#   scripts/demo-credentials.sh --names    print usernames only
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need kubectl; need python

NS=paybridge
SECRET=paybridge-demo-accounts
mode="${1:-}"
case "$mode" in ""|--names) ;; *) sed -n '2,7p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;; esac

kc -n "$NS" get secret "$SECRET" >/dev/null 2>&1 || die 1 "Secret $NS/$SECRET not found; has the seed Job run?"
json="$(kc -n "$NS" get secret "$SECRET" -o json)"

[ "$mode" = "--names" ] || warn "these are DEMO credentials for synthetic data only; do not reuse them anywhere else"
python -I -c '
import base64, json, sys
names_only = sys.argv[1] == "--names"
data = json.load(sys.stdin).get("data") or {}
if not data:
    sys.exit("the Secret is empty: the seed Job has not created any demo account yet")
for user in sorted(data):
    print(user if names_only else f"{user}  {base64.b64decode(data[user]).decode()}")
' "$mode" <<<"$json"
