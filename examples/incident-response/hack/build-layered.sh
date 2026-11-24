#!/usr/bin/env bash
# Low-bandwidth rebuild: the four Quarkus components share every runtime jar
# with the konzert-worker image, so this uploads only the application layer
# (~1 MB: app/, quarkus/, quarkus-run.jar and konzert-core) on top of
# konzert-worker:latest instead of the full image. Use it when build-all.sh
# uploads keep dropping (FetchSourceFailed). mcp-ansible is not layered.
#
#   examples/incident-response/hack/build-layered.sh [-n konzert-demo] [worker maestro sensor console]
set -euo pipefail

NS=konzert-demo
if [[ "${1:-}" == "-n" ]]; then NS="$2"; shift 2; fi
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
EX="$ROOT/examples/incident-response"
COMPONENTS=("$@")
[[ ${#COMPONENTS[@]} -eq 0 ]] && COMPONENTS=(worker maestro sensor console)
CTX="$(mktemp -d)"
trap 'rm -rf "$CTX"' EXIT

for c in "${COMPONENTS[@]}"; do
  case "$c" in
    worker)  d="$ROOT/agents/worker";  img=konzert-worker ;;
    maestro) d="$ROOT/agents/maestro"; img=konzert-maestro ;;
    sensor)  d="$EX/sensor";           img=konzert-sensor ;;
    console) d="$EX/console";          img=konzert-console ;;
    *) echo "unknown component $c" >&2; exit 1 ;;
  esac
  [[ -f "$d/target/quarkus-app/quarkus-run.jar" ]] || { echo "$c: run mvn package first" >&2; exit 1; }
  echo "==> $c → $NS/$img (layered)"
  rm -rf "${CTX:?}/$c"; mkdir -p "$CTX/$c/target/quarkus-app/lib/main"
  cp -R "$d/target/quarkus-app/app" "$d/target/quarkus-app/quarkus" "$d/target/quarkus-app/quarkus-run.jar" "$CTX/$c/target/quarkus-app/"
  cp "$d"/target/quarkus-app/lib/main/*konzert-core-*.jar "$CTX/$c/target/quarkus-app/lib/main/"
  cat > "$CTX/$c/Dockerfile" <<DF
FROM image-registry.openshift-image-registry.svc:5000/$NS/konzert-worker:latest
USER root
RUN rm -rf /deployments/app /deployments/quarkus
COPY --chown=185 target/quarkus-app/lib/main/ /deployments/lib/main/
COPY --chown=185 target/quarkus-app/quarkus/ /deployments/quarkus/
COPY --chown=185 target/quarkus-app/app/ /deployments/app/
COPY --chown=185 target/quarkus-app/*.jar /deployments/
USER 185
DF
  oc get bc "$img" -n "$NS" >/dev/null 2>&1 || oc new-build --binary --name="$img" --strategy=docker -n "$NS" >/dev/null
  oc start-build "$img" --from-dir="$CTX/$c" --follow -n "$NS" | tail -2
done
