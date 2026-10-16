#!/usr/bin/env bash
# Checks local prerequisites and records the environment to artifacts/environment.json.
# Exit 0 when every required tool is present and Docker answers, 3 otherwise.
set -euo pipefail
# shellcheck source=lib/common.sh
source "$(dirname "${BASH_SOURCE[0]}")/lib/common.sh"

missing=0
check() { # check NAME VERSION_COMMAND
  local v
  if command -v "$1" >/dev/null 2>&1 && v="$(eval "$2" 2>&1 | head -1)"; then printf '  ok       %-8s %s\n' "$1" "$v"
  else printf '  MISSING  %-8s\n' "$1"; missing=1; fi
}

echo "Tools"
check java 'java -version'
check docker 'docker --version'
check kubectl 'kubectl version --client'
check kind 'kind version'
check helm 'helm version --short'
check git 'git --version'

echo "Docker"
if docker info >/dev/null 2>&1; then
  mem="$(docker info --format '{{.MemTotal}}')"; cpus="$(docker info --format '{{.NCPU}}')"
  printf '  engine up, %s CPUs, %s GiB memory available to Docker\n' "$cpus" "$((mem / 1073741824))"
  [ "$((mem / 1073741824))" -ge 12 ] || warn "Docker has less than 12 GiB; the local cluster plus data tier needs roughly 8 to 10 GiB"
else
  echo "  Docker engine is NOT reachable"; missing=1
fi

echo "Java"
jv="$(java -version 2>&1 | head -1)"
case "$jv" in *'"25'*) echo "  JDK 25 active" ;; *) warn "default java is not 25 ($jv); Gradle toolchains still select 25"; ;; esac

echo "Host ports"
for p in 80 443; do
  owner="$(powershell.exe -NoProfile -Command "\$c = Get-NetTCPConnection -State Listen -LocalPort $p -ErrorAction SilentlyContinue | Select-Object -First 1; if (\$c) { (Get-Process -Id \$c.OwningProcess -ErrorAction SilentlyContinue).ProcessName }; exit 0" 2>/dev/null | tr -d '\r')"
  if [ -z "$owner" ]; then echo "  $p free"; else echo "  $p in use by $owner (use --http-port/--https-port)"; fi
done

mkdir -p "$PB_ROOT/artifacts"
ps() { powershell.exe -NoProfile -Command "$1" 2>/dev/null | tr -d '\r' | head -1; }
export PB_CPU="$(ps '(Get-CimInstance Win32_Processor | Select-Object -First 1).Name')"
export PB_LCPU="$(ps '(Get-CimInstance Win32_ComputerSystem).NumberOfLogicalProcessors')"
export PB_RAM="$(ps '[math]::Round((Get-CimInstance Win32_ComputerSystem).TotalPhysicalMemory/1GB)')"
export PB_DISK="$(ps '(Get-PhysicalDisk | Select-Object -First 1).MediaType')"
export PB_OS="$(ps '(Get-CimInstance Win32_OperatingSystem).Caption + " " + (Get-CimInstance Win32_OperatingSystem).Version')"
export PB_DOCKER="$(docker --version 2>/dev/null || true)"
export PB_DOCKER_MEM="$(( $(docker info --format '{{.MemTotal}}' 2>/dev/null || echo 0) / 1073741824 ))"
export PB_KIND="$(kind version 2>/dev/null | awk '{print $2}' || true)"
export PB_HELM="$(helm version --short 2>/dev/null || true)"
export PB_JAVA="$(java -version 2>&1 | head -1)"
export PB_NOW="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
python -I - "$(win_path "$PB_ROOT/artifacts/environment.json")" <<'PY'
import json, os, sys
e = os.environ
json.dump({
    "recordedAt": e["PB_NOW"], "cpuModel": e["PB_CPU"], "logicalCpus": int(e["PB_LCPU"] or 0),
    "ramGiB": int(e["PB_RAM"] or 0), "diskType": e["PB_DISK"], "os": e["PB_OS"], "docker": e["PB_DOCKER"],
    "dockerMemGiB": int(e["PB_DOCKER_MEM"]), "kind": e["PB_KIND"], "helm": e["PB_HELM"], "java": e["PB_JAVA"],
}, open(sys.argv[1], "w", newline=chr(10)), indent=2)
PY
echo "recorded artifacts/environment.json"
exit $((missing ? 3 : 0))
