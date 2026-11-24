#!/usr/bin/env bash
# Build every image of the incident-response example with OpenShift binary
# builds (no local Docker or Podman needed).
#
#   examples/incident-response/hack/build-all.sh [-n konzert-demo] [component ...]
#
# Components: mcp-ansible maestro worker sensor console (default: all).
# The worker image serves both Atlas and Sentinel.
#
# MVN_FLAGS adds Maven flags, e.g. MVN_FLAGS=-o to build offline once the
# dependencies are in the local repository.
set -euo pipefail

NS=konzert-demo
if [[ "${1:-}" == "-n" ]]; then NS="$2"; shift 2; fi
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
EX="$ROOT/examples/incident-response"
COMPONENTS=("$@")
[[ ${#COMPONENTS[@]} -eq 0 ]] && COMPONENTS=(mcp-ansible maestro worker sensor console)
# shellcheck disable=SC2206
MVN=(mvn -q ${MVN_FLAGS:-})

# Every component depends on konzert-core; install it once into the local repository.
(cd "$ROOT/core" && "${MVN[@]}" clean install -DskipTests)

dir_of() {
  case "$1" in
    mcp-ansible) echo "$EX/mcp-ansible" ;;
    maestro)     echo "$ROOT/agents/maestro" ;;
    worker)      echo "$ROOT/agents/worker" ;;
    sensor)      echo "$EX/sensor" ;;
    console)     echo "$EX/console" ;;
    *) echo "unknown component $1" >&2; exit 1 ;;
  esac
}
image_of() {
  case "$1" in
    mcp-ansible) echo konzert-mcp-ansible ;;
    maestro)     echo konzert-maestro ;;
    worker)      echo konzert-worker ;;
    sensor)      echo konzert-sensor ;;
    console)     echo konzert-console ;;
  esac
}

for c in "${COMPONENTS[@]}"; do
  d="$(dir_of "$c")"; img="$(image_of "$c")"
  echo "==> $c → $NS/$img"
  (cd "$d" && rm -rf target && "${MVN[@]}" clean package -DskipTests)
  oc get bc "$img" -n "$NS" >/dev/null 2>&1 || \
    oc new-build --binary --name="$img" --strategy=docker -n "$NS" >/dev/null
  # Binary Docker builds expect the Dockerfile at the root of the upload.
  cp "$d/src/main/docker/Dockerfile.jvm" "$d/Dockerfile"
  trap 'rm -f "$d/Dockerfile"' EXIT
  oc start-build "$img" --from-dir="$d" --follow -n "$NS" | tail -3
  rm -f "$d/Dockerfile"
done
echo "done: $(oc get istag -n "$NS" -o name | wc -l | tr -d ' ') image tag(s) in $NS"
