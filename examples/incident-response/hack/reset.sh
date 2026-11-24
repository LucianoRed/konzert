#!/usr/bin/env bash
# Put the demo back to its starting state: chaos workloads gone, limits and
# quarantine removed, topics empty, agents' incident books cleared.
#
#   examples/incident-response/hack/reset.sh [-n konzert-demo] [--keep-topics]
set -euo pipefail

NS=konzert-demo
KEEP_TOPICS=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    -n) NS="$2"; shift 2 ;;
    --keep-topics) KEEP_TOPICS=true; shift ;;
    *) echo "unknown option $1" >&2; exit 1 ;;
  esac
done

echo "==> removing chaos workloads"
oc delete deploy -n "$NS" -l konzert.io/chaos --ignore-not-found >/dev/null
# Wait until the scanner is really gone: a terminating pod still logs, and with
# its quarantine removed the sensor would report it one last time.
oc wait pod -n "$NS" -l konzert.io/chaos --for=delete --timeout=120s >/dev/null 2>&1 || true
echo "==> removing quarantine policies"
oc delete networkpolicy -n "$NS" -l konzert.io/quarantine=true --ignore-not-found >/dev/null
echo "==> removing the limits Atlas applied to checkout-api"
oc patch deploy checkout-api -n "$NS" --type=json \
  -p='[{"op":"remove","path":"/spec/template/spec/containers/0/resources"}]' >/dev/null 2>&1 || true

if [[ "$KEEP_TOPICS" == false ]]; then
  echo "==> emptying konzert.* topics (delete + recreate; ~20s)"
  oc delete kafkatopic -n kafka -l app=konzert --ignore-not-found >/dev/null
  sleep 5
  oc apply -f "$(dirname "$0")/../../../deploy/kafka/topics.yaml" >/dev/null
  for i in $(seq 1 30); do
    ready=$(oc get kafkatopic -n kafka -l app=konzert -o jsonpath='{range .items[*]}{.status.conditions[0].type}{"\n"}{end}' | grep -c Ready || true)
    [[ "$ready" -eq 3 ]] && break
    sleep 2
  done
fi

echo "==> restarting the agents and the console (clears in-memory incident state)"
oc rollout restart deploy/maestro deploy/atlas deploy/sentinel deploy/konzert-sensor deploy/konzert-console -n "$NS" >/dev/null
for d in maestro atlas sentinel konzert-sensor konzert-console; do
  oc rollout status "deploy/$d" -n "$NS" --timeout=180s | tail -1
done
echo "ready: the panel should be empty and checkout-api healthy without limits"
