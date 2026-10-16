#!/usr/bin/env bash
# Generates and installs the local data-tier Secrets (POR 4.6) without ever printing a value.
# Idempotent: values live in ~/.paybridge/local/secrets.env and are reused so database passwords never drift.
# T19 extends this into scripts/install-local.sh (service keys, bootstrap token, demo accounts).
#
#   scripts/lab-secrets.sh create     create or update the Secrets in paybridge and paybridge-data
#   scripts/lab-secrets.sh status     list which Secrets exist (names and keys only)
#   scripts/lab-secrets.sh delete     remove them (the database keeps its old passwords; use only with a fresh volume)
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need kubectl; need python

APP_NS=paybridge
DATA_NS=paybridge-data
STORE_DIR="${HOME}/.paybridge/local"
STORE="$STORE_DIR/secrets.env"

generate_store() { # create the store file once; later runs only add missing keys
  mkdir -p "$STORE_DIR"; chmod 700 "$STORE_DIR" 2>/dev/null || true
  [ -f "$STORE" ] || : > "$STORE"
  chmod 600 "$STORE" 2>/dev/null || true
  python -I - "$(win_path "$STORE")" <<'PY'
import secrets, string, sys
path = sys.argv[1]
have = {}
for line in open(path, encoding="utf8"):
    if "=" in line:
        k, v = line.rstrip("\n").split("=", 1); have[k] = v
alnum = string.ascii_letters + string.digits
def pw(n=32): return "".join(secrets.choice(alnum) for _ in range(n))
wanted = {
  "DB_ROOT_PASSWORD": pw, "DB_WRITER_PASSWORD": pw, "DB_READER_PASSWORD": pw, "DB_MIGRATE_PASSWORD": pw,
  "DB_METRICS_PASSWORD": pw, "DB_BACKUP_PASSWORD": pw, "DB_REPL_PASSWORD": pw,
  "REDIS_PASSWORD": pw, "RABBIT_PASSWORD": pw, "RABBIT_MONITORING_PASSWORD": pw, "RABBIT_ERLANG_COOKIE": lambda: pw(40),
  "MONGO_PASSWORD": pw, "SIM_API_TOKEN": lambda: pw(48), "SIM_DB_PASSWORD": pw,
}
added = 0
with open(path, "a", encoding="utf8", newline="\n") as f:
    for k, gen in wanted.items():
        if k not in have:
            f.write(f"{k}={gen()}\n"); added += 1
print(f"secret store {path}: {added} new value(s), {len(wanted) - added} reused")
PY
}

# put_secret NAMESPACE NAME key=STOREKEY|literal:value ... ; writes via a temporary env file, never argv.
put_secret() {
  local ns="$1" name="$2"; shift 2
  local tmp; tmp="$(mktemp "$STORE_DIR/tmp.XXXXXX")"
  local spec k v
  for spec in "$@"; do
    k="${spec%%=*}"; v="${spec#*=}"
    case "$v" in
      literal:*) printf '%s=%s\n' "$k" "${v#literal:}" >> "$tmp" ;;
      *) printf '%s=%s\n' "$k" "$(grep -m1 "^$v=" "$STORE" | cut -d= -f2-)" >> "$tmp" ;;
    esac
  done
  kc -n "$ns" create secret generic "$name" --from-env-file="$(win_path "$tmp")" --dry-run=client -o yaml | kc apply -f - >/dev/null
  rm -f "$tmp"
  log "secret $ns/$name ($# keys)"
}

cmd_create() {
  kc get ns "$APP_NS" "$DATA_NS" >/dev/null 2>&1 || die 1 "namespaces missing; run scripts/cluster-local.sh create"
  generate_store
  # Data tier namespace: every account the lab chart creates or runs with.
  put_secret "$DATA_NS" paybridge-db \
    root-password=DB_ROOT_PASSWORD writer-username=literal:pb_core writer-password=DB_WRITER_PASSWORD \
    reader-username=literal:pb_reader reader-password=DB_READER_PASSWORD migrate-username=literal:pb_migrate \
    migrate-password=DB_MIGRATE_PASSWORD metrics-username=literal:pb_metrics metrics-password=DB_METRICS_PASSWORD \
    backup-username=literal:pb_backup backup-password=DB_BACKUP_PASSWORD repl-username=literal:pb_repl repl-password=DB_REPL_PASSWORD
  put_secret "$DATA_NS" paybridge-redis password=REDIS_PASSWORD
  put_secret "$DATA_NS" paybridge-rabbit username=literal:paybridge password=RABBIT_PASSWORD \
    monitoring-username=literal:monitoring monitoring-password=RABBIT_MONITORING_PASSWORD erlang-cookie=RABBIT_ERLANG_COOKIE
  put_secret "$DATA_NS" paybridge-mongo username=literal:paybridge password=MONGO_PASSWORD
  put_secret "$DATA_NS" paybridge-simulator db-username=literal:pb_sim db-password=SIM_DB_PASSWORD
  # Application namespace: only what the application needs. No root or replication or backup credentials.
  put_secret "$APP_NS" paybridge-db writer-username=literal:pb_core writer-password=DB_WRITER_PASSWORD \
    reader-username=literal:pb_reader reader-password=DB_READER_PASSWORD \
    migrate-username=literal:pb_migrate migrate-password=DB_MIGRATE_PASSWORD
  put_secret "$APP_NS" paybridge-redis password=REDIS_PASSWORD
  put_secret "$APP_NS" paybridge-rabbit username=literal:paybridge password=RABBIT_PASSWORD
  put_secret "$APP_NS" paybridge-mongo username=literal:paybridge password=MONGO_PASSWORD
  put_secret "$APP_NS" paybridge-simulator api-token=SIM_API_TOKEN db-username=literal:pb_sim db-password=SIM_DB_PASSWORD
  put_secret "$APP_NS" paybridge-keda-mysql host=literal:mysql-writer.$DATA_NS.svc.cluster.local port=literal:3306 \
    username=literal:pb_metrics password=DB_METRICS_PASSWORD dbName=literal:paybridge
  local monpw; monpw="$(grep -m1 '^RABBIT_MONITORING_PASSWORD=' "$STORE" | cut -d= -f2-)"
  put_secret "$APP_NS" paybridge-keda-rabbit "httpHost=literal:http://monitoring:${monpw}@rabbitmq.$DATA_NS.svc.cluster.local:15672/"
  log "done. Values are in $STORE and were not printed."
}

cmd_status() {
  local ns
  for ns in "$DATA_NS" "$APP_NS"; do
    echo "== $ns"
    kc -n "$ns" get secrets -o go-template='{{range .items}}{{if not (eq .type "helm.sh/release.v1")}}{{.metadata.name}}: {{range $k, $_ := .data}}{{$k}} {{end}}{{"\n"}}{{end}}{{end}}'
  done
}

cmd_delete() {
  local ns n
  for ns in "$DATA_NS" "$APP_NS"; do
    for n in paybridge-db paybridge-redis paybridge-rabbit paybridge-mongo paybridge-simulator paybridge-keda-mysql paybridge-keda-rabbit; do
      kc -n "$ns" delete secret "$n" --ignore-not-found
    done
  done
}

case "${1:-}" in
  create) cmd_create ;;
  status) cmd_status ;;
  delete) cmd_delete ;;
  *) sed -n '2,9p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 2 ;;
esac
