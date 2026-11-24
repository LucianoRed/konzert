#!/usr/bin/env bash
# Install (or upgrade) every component of the incident-response example into
# the demo namespace.
#
#   LLM_API_KEY=... examples/incident-response/hack/install.sh [-n konzert-demo]
#
# The key is read from the environment (or from an existing <agent>-secrets)
# and never appears on a command line. Souls are seeded from
# examples/incident-response/souls/<name>.md.
set -euo pipefail

NS=konzert-demo
if [[ "${1:-}" == "-n" ]]; then NS="$2"; shift 2; fi
ROOT="$(cd "$(dirname "$0")/../../.." && pwd)"
EX="$ROOT/examples/incident-response"
H="$EX/deploy/helm"
AGENT_CHART="$ROOT/deploy/helm/agent"
REG="image-registry.openshift-image-registry.svc:5000/$NS"

oc get ns "$NS" >/dev/null 2>&1 || oc new-project "$NS" >/dev/null
oc apply -f "$ROOT/deploy/kafka/topics.yaml" >/dev/null

# Each agent reads its key from <name>-secrets (key: llm-api-key). Seed it once.
for a in maestro atlas sentinel; do
  if ! oc get secret "$a-secrets" -n "$NS" >/dev/null 2>&1; then
    [[ -z "${LLM_API_KEY:-}" ]] && { echo "LLM_API_KEY is not set and $a-secrets does not exist" >&2; exit 1; }
    # Passed on stdin so the key never shows up in a process listing.
    printf '%s' "$LLM_API_KEY" | oc create secret generic "$a-secrets" -n "$NS" --from-file=llm-api-key=/dev/stdin >/dev/null
    oc label secret "$a-secrets" -n "$NS" app="$a" konzert.io/config=seed >/dev/null
    echo "seeded $a-secrets"
  fi
done

helm upgrade --install konzert-mcp-ansible "$H/mcp-ansible" -n "$NS" \
  --set demoNamespace="$NS" --set image.repository="$REG/konzert-mcp-ansible" >/dev/null
helm upgrade --install konzert-sensor "$H/sensor" -n "$NS" \
  --set demoNamespace="$NS" --set image.repository="$REG/konzert-sensor" >/dev/null
# Maestro's intake only admits sensor findings about the demo namespace; the
# shipped rule names konzert-demo, so it is rewritten when -n says otherwise.
sed "s/value: konzert-demo$/value: $NS/" "$H/values-maestro.yaml" | \
  helm upgrade --install maestro "$AGENT_CHART" -n "$NS" -f - \
  --set image.repository="$REG/konzert-maestro" \
  --set-file agentConfig.identity.soul="$EX/souls/maestro.md" >/dev/null
for a in atlas sentinel; do
  helm upgrade --install "$a" "$AGENT_CHART" -n "$NS" -f "$H/values-$a.yaml" \
    --set image.repository="$REG/konzert-worker" \
    --set env.KONZERT_NAMESPACE="$NS" \
    --set-file agentConfig.identity.soul="$EX/souls/$a.md" >/dev/null
done
helm upgrade --install konzert-console "$H/console" -n "$NS" \
  --set demoNamespace="$NS" --set image.repository="$REG/konzert-console" >/dev/null

# The victim: healthy, and shipped without limits on purpose.
sed "s/namespace: konzert-demo$/namespace: $NS/" "$EX/chaos/checkout-api.yaml" | oc apply -n "$NS" -f - >/dev/null

for d in konzert-mcp-ansible konzert-sensor maestro atlas sentinel konzert-console; do
  oc rollout status "deploy/$d" -n "$NS" --timeout=180s | tail -1
done
echo
echo "Situation Room: https://$(oc get route konzert-console -n "$NS" -o jsonpath='{.spec.host}')"
