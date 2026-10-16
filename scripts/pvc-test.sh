#!/usr/bin/env bash
# Proves lab data survives pod deletion: writes a marker to MySQL and MongoDB, deletes both pods, reads the marker back.
# Writes artifacts/T03/pvc-persistence.txt. Leaves no data behind.
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"
need kubectl
NS=paybridge-data
OUT="$PB_ROOT/artifacts/T03/pvc-persistence.txt"
mkdir -p "$(dirname "$OUT")"; : > "$OUT"
say() { printf '%s\n' "$*" | tee -a "$OUT"; }
K() { kc -n "$NS" "$@"; }

say "PVC persistence test, $(date -u +%Y-%m-%dT%H:%M:%SZ)"
K get pvc -o custom-columns=NAME:.metadata.name,STATUS:.status.phase,SIZE:.status.capacity.storage,CLASS:.spec.storageClassName | tee -a "$OUT"
marker="survives-pod-deletion-$RANDOM"
K exec mysql-0 -- sh -c "mysql -uroot -p\"\$MYSQL_ROOT_PASSWORD\" -e \"CREATE DATABASE IF NOT EXISTS pvc_probe; CREATE TABLE IF NOT EXISTS pvc_probe.t (id INT PRIMARY KEY, v VARCHAR(60)); REPLACE INTO pvc_probe.t VALUES (1, '$marker');\" 2>/dev/null"
K exec mongodb-0 -- sh -c "mongosh --quiet -u \"\$MONGO_INITDB_ROOT_USERNAME\" -p \"\$MONGO_INITDB_ROOT_PASSWORD\" --authenticationDatabase admin --eval \"db.getSiblingDB('pvc_probe').t.replaceOne({_id:1},{_id:1,v:'$marker'},{upsert:true}).acknowledged\"" >/dev/null
say "wrote marker $marker to mysql-0 and mongodb-0; deleting both pods"
K delete pod mysql-0 mongodb-0 --wait=true >/dev/null
K wait --for=condition=Ready pod/mysql-0 pod/mongodb-0 --timeout=5m >/dev/null
my="$(K exec mysql-0 -- sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N -e "SELECT v FROM pvc_probe.t WHERE id=1; DROP DATABASE pvc_probe;" 2>/dev/null')"
mo="$(K exec mongodb-0 -- sh -c 'mongosh --quiet -u "$MONGO_INITDB_ROOT_USERNAME" -p "$MONGO_INITDB_ROOT_PASSWORD" --authenticationDatabase admin --eval "const d=db.getSiblingDB(\"pvc_probe\"); const r=d.t.findOne({_id:1}); d.dropDatabase(); r.v"')"
rc=0
if [ "$my" = "$marker" ]; then say "PASS  mysql kept the marker after pod deletion"; else say "FAIL  mysql returned '$my'"; rc=1; fi
if [ "$mo" = "$marker" ]; then say "PASS  mongodb kept the marker after pod deletion"; else say "FAIL  mongodb returned '$mo'"; rc=1; fi
exit "$rc"
