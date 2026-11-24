# Installing the incident-response example

Every step states what "it worked" looks like. The scripts in
[../hack/](../hack/) do steps 3 and 4 in one command each; the manual commands
are in [Doing it by hand](#doing-it-by-hand) for understanding and repair.

All commands run from the repository root.

## Prerequisites

| Requirement | Why | Check |
|---|---|---|
| `oc` logged into an OpenShift cluster | everything | `oc whoami` |
| `helm` 3.x | the charts | `helm version` |
| Java 25 and Maven | building the Quarkus images | `mvn -v` |
| A Kafka cluster (Strimzi / Streams for Apache Kafka) | the bus | `oc get kafka -A` |
| An OpenAI-compatible LLM endpoint and a key | the three agents | see step 4 |

You need cluster-admin only for step 2 (installing the Kafka operator). The
rest needs the right to create a project and Roles inside it.

Use a throwaway cluster. The example injects real faults, and the Situation
Room has no authentication (see [SECURITY.md](../../../SECURITY.md)).

A full two-incident run costs cents. A misconfigured intake filter on a busy
topic does not. Install the shipped defaults (default deny, plus budgets)
before pointing an agent at a key with real credit on it.

## Step 1 — Namespace

```bash
oc new-project konzert-demo
```

Everything the example creates, breaks and fixes lives here. Deleting the
project is the uninstall.

## Step 2 — Kafka

Skip 2a and 2b if the cluster already has a Kafka you want to use, and set
`kafka.bootstrap` in the values files to point at it.

```bash
# 2a. Operator (cluster-wide, about 2 minutes)
oc apply -f deploy/kafka/00-operator.yaml
oc wait crd/kafkas.kafka.strimzi.io --for=create --timeout=300s

# 2b. A single-node KRaft cluster with ephemeral storage (about 1 minute)
oc apply -f deploy/kafka/01-cluster.yaml
oc wait kafka/konzert-kafka -n kafka --for=condition=Ready --timeout=360s

# 2c. The three Konzert topics
oc apply -f deploy/kafka/topics.yaml
oc get kafkatopic -n kafka -l app=konzert
```

**Looks right when:** three topics report `Ready`, and a message survives a
round trip from the demo namespace:

```bash
IMG=$(oc get pod konzert-kafka-dual-role-0 -n kafka -o jsonpath='{.spec.containers[0].image}')
oc -n konzert-demo run kafka-smoke --rm -i --restart=Never --image="$IMG" --command -- /bin/bash -c '
  echo "{\"type\":\"SmokeTest\"}" | /opt/kafka/bin/kafka-console-producer.sh \
    --bootstrap-server konzert-kafka-kafka-bootstrap.kafka.svc:9092 --topic konzert.events
  /opt/kafka/bin/kafka-console-consumer.sh \
    --bootstrap-server konzert-kafka-kafka-bootstrap.kafka.svc:9092 \
    --topic konzert.events --from-beginning --timeout-ms 8000'
```

This cluster is a demo bus: one broker, no persistence, a plaintext listener
and no authentication. It is not a production configuration.

## Step 3 — Build the images

Five images, built with OpenShift binary builds, so no local Docker or Podman
is needed. The `konzert-worker` image serves both Atlas and Sentinel.

```bash
examples/incident-response/hack/build-all.sh -n konzert-demo
```

The `konzert-mcp-ansible` image is the slow one: it installs `ansible-core` and
the `kubernetes.core` collection on top of the UBI Java base. About four
minutes the first time, seconds afterwards.

If large uploads keep dropping (`FetchSourceFailed`), build once with
`build-all.sh worker`, run `mvn package` in the other modules, and use
`hack/build-layered.sh`: it uploads only the application layer on top of
`konzert-worker:latest`.

**Looks right when:** `oc get istag -n konzert-demo` lists five `:latest` tags:
`konzert-mcp-ansible`, `konzert-maestro`, `konzert-worker`, `konzert-sensor`
and `konzert-console`.

## Step 4 — Install

```bash
read -rs LLM_API_KEY && export LLM_API_KEY     # type or paste the key; it is not echoed
examples/incident-response/hack/install.sh -n konzert-demo
unset LLM_API_KEY
```

The script seeds one Secret per agent (`<agent>-secrets`, key `llm-api-key`),
installs the six releases and waits for the rollouts. The key is read from the
environment and handed to `oc` on standard input, so it never appears in a
process listing, in shell history or in a file in this repository.

The shipped values files point at OpenRouter with `deepseek/deepseek-v4-flash`.
Any OpenAI-compatible endpoint works: change `agentConfig.model` in
`deploy/helm/values-{maestro,atlas,sentinel}.yaml`.

**Looks right when:** six Deployments are available, the script prints the
Situation Room URL, the page opens with green dots for every component, and the
feed says `Waiting for something to go wrong.`

## Step 5 — Smoke test

Click **Memory leak** in the Situation Room. Within about 90 seconds:

1. 📡 `MemoryPressureDetected`
2. 🎼 Maestro delegates to Atlas
3. 🛠️ Atlas asks you to approve `set-deployment-limits.yml`. Click **Approve**.
4. 🛠️ `PlaybookExecuted`, with the Ansible output expandable; Atlas reports
5. 📡 `IncidentResolvedConfirmed` once the limit has held for 30 seconds
6. 🎼 Maestro closes the incident

Then **Revert all**, and repeat with **Port scan** for the three-way
conversation.

**Clear panel** wipes the screen in every open browser and stops earlier
history from being replayed. It is cosmetic on purpose: topics and agents are
untouched, so a running incident carries on. For a real reset use step 6.

## Step 6 — Reset between runs

```bash
examples/incident-response/hack/reset.sh -n konzert-demo
```

Deletes the chaos workloads, removes the limits and the quarantine policy,
recreates the three topics empty, and restarts the agents so nobody remembers
the previous incident. To also discard what was tuned at runtime through the
Control Panel API and return to the seeded configuration:

```bash
oc delete cm,secret -n konzert-demo -l konzert.io/config=runtime
```

## Looking at an agent's Control Panel API

The agents' HTTP API is not exposed by a Route in this example, because it has
no authentication. Use a port-forward:

```bash
oc port-forward deploy/atlas 8081:8080 -n konzert-demo &
curl -s localhost:8081/api/config/intake/stats | jq .funnel     # what the filter has been doing
curl -s localhost:8081/api/config | jq .agent.autonomy          # propose
curl -s -X PUT localhost:8081/api/config -H 'Content-Type: application/json' \
  -d '{"agent":{"autonomy":"act"}}'                             # no more approvals
```

See [agent-control-panel.md](../../../docs/agent-control-panel.md) for the
whole API.

## Doing it by hand

What `install.sh` does, for one agent:

```bash
NS=konzert-demo
REG=image-registry.openshift-image-registry.svc:5000/$NS

# the key, from the environment, on standard input
printf '%s' "$LLM_API_KEY" | oc create secret generic atlas-secrets -n $NS \
  --from-file=llm-api-key=/dev/stdin

# one chart, one release per agent, one values file each; the soul is seeded from the repository
helm upgrade --install atlas deploy/helm/agent -n $NS \
  -f examples/incident-response/deploy/helm/values-atlas.yaml \
  --set image.repository=$REG/konzert-worker \
  --set env.KONZERT_NAMESPACE=$NS \
  --set-file agentConfig.identity.soul=examples/incident-response/souls/atlas.md
```

Configuration lives in Kubernetes objects, not on a volume. `<agent>-config`
and `<agent>-secrets` are the seeded defaults; the agent writes only what was
changed at runtime into `<agent>-config-runtime` and `<agent>-secrets-runtime`.
A later `helm upgrade` therefore cannot erase runtime tuning. These agents have
no PersistentVolumeClaim.

The other three releases:

```bash
helm upgrade --install konzert-mcp-ansible examples/incident-response/deploy/helm/mcp-ansible -n $NS \
  --set demoNamespace=$NS --set image.repository=$REG/konzert-mcp-ansible
helm upgrade --install konzert-sensor examples/incident-response/deploy/helm/sensor -n $NS \
  --set demoNamespace=$NS --set image.repository=$REG/konzert-sensor
helm upgrade --install konzert-console examples/incident-response/deploy/helm/console -n $NS \
  --set demoNamespace=$NS --set image.repository=$REG/konzert-console
oc apply -n $NS -f examples/incident-response/chaos/checkout-api.yaml
```

The `mcp-ansible` chart creates the ServiceAccount and the namespace `Role`
that is the real boundary of this example: patch Deployments, manage
NetworkPolicies, read pods, in one namespace and nothing else anywhere. Its
`aap` backend (launching a Job Template on Ansible Automation Platform) is
**not implemented**; only `local` runs today.

The sensor reads pod logs through the Kubernetes API. Its thresholds are chart
values: `sensor.memoryThresholdMb` (600), `sensor.scanLinesThreshold` (15) and
`sensor.confirmHoldSeconds` (30).

## Troubleshooting

| Symptom | Likely cause | Check |
|---|---|---|
| Panel empty after an injection, sensor quiet | the sensor cannot read `metrics.k8s.io` | `oc logs deploy/konzert-sensor`, and the sensor's Role |
| Event appears, Maestro posts the fallback line | LLM key missing or wrong, or the endpoint timed out | `oc logs deploy/maestro`; `GET /api/config` shows `secrets.llmApiKey` masked when a key is set |
| Event appears, Maestro says nothing | the intake filter did not admit it | `GET /api/config/intake/stats`: `recent` lists each verdict and its reason |
| Atlas proposes but nothing runs | autonomy is `propose` and nobody approved | click **Approve**, or set autonomy to `act` |
| Playbook fails with `Forbidden` | the `konzert-mcp-ansible` Role does not cover the namespace | `demoNamespace` on the mcp-ansible release |
| `ansible --version` fails with `Permission denied` | the container runs as a random UID with no writable home | the image must set `HOME=/tmp` and `ANSIBLE_LOCAL_TEMP` |
| A second incident is ignored while one is open | `maxConcurrentIncidents: 1` per agent | by design; see [scaling.md](../../../docs/scaling.md) |
| Panel shows old incidents | topics not emptied | `hack/reset.sh` |

## Uninstall

```bash
oc delete project konzert-demo
oc delete kafkatopic -n kafka -l app=konzert
```
