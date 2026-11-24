# Incident-response example — architecture

## Design constraints

1. **The conversation must be real.** Nothing on the panel may be scripted. If a
   line appears, a Kafka message carried it.
2. **The fix must be real, and it must be Ansible.** The agents do not `kubectl
   patch`. They run playbooks, and the playbook output is visible.
3. **It must survive a live demo.** Bounded turns, timeouts on every LLM call,
   deterministic fallbacks, one-click revert, and a kill switch.
4. **It must be watchable.** A person who knows nothing about Kafka should
   understand what happened by reading the panel.
5. **It must install standalone.** Kafka is the only external dependency.

## Components

| Component | Runtime | LLM | Cluster access |
|---|---|---|---|
| `konzert-sensor` | Quarkus | no | read: `metrics.k8s.io`, pods, deployments, pod logs |
| `maestro` | Quarkus + LangChain4j | yes | own config objects only — Kafka otherwise |
| `atlas` | Quarkus (`agents/worker`) | yes | own config objects only — acts via `mcp-ansible` |
| `sentinel` | Quarkus (same `worker` image, different values) | yes | own config objects only — acts via `mcp-ansible` |
| `mcp-ansible` | Quarkus MCP (SSE) + `ansible-core` | no | in-cluster SA, scoped by playbook |
| `konzert-console` | Quarkus + SSE + static UI | no | create/delete the chaos manifests |

Maestro and the worker image live at the repository root (`agents/`), because
they are the framework's reference agents; everything else is in this
directory.

The three LLM agents each expose the standard **Control Panel API** (identity
and soul, model and credentials, system prompt, intake filter, autonomy), per
[agent-control-panel.md](../../../docs/agent-control-panel.md). It is how a
filter is tightened, or an agent switched from `propose` to `act`, while the
example is running.

Note that **only the MCP server can change anything in the cluster**. The
agents' only Kubernetes rights are over their own two configuration objects
(see [agent-control-panel.md](../../../docs/agent-control-panel.md)) — they
cannot touch a workload. That is the
governance story, and it is what makes "the security agent could not patch a
Deployment even if it wanted to" a demonstrable fact rather than a claim.

None of the three needs a PersistentVolumeClaim: their configuration is in
Kubernetes objects.

### Why the sensor is a separate pod

The sensor could live inside Maestro. It does not, for two reasons: the story
"something in the cluster noticed, then an agent reacted" collapses if the
reactor is also the observer, and the sensor is the one component that must keep
producing while an agent is being rebuilt.

## Transport: Kafka, not A2A

[ADR-0005](../../../docs/adr/0005-a2a-protocol.md) settles this for the
framework: internal collaboration is Kafka, A2A is an edge concern. The example
gains from it:

- the panel is *just another consumer* — it observes without being wired into
  any agent, so an agent crashing does not blank the screen;
- replay is free: `--from-beginning` on `konzert.conversation` replays the whole
  incident for a second audience;
- the audience sees pub/sub do something A2A cannot — Sentinel isolates a
  workload and Atlas reacts to the same message, unprompted.

A2A stays available as a later addition: an external agent joining the room
through an edge adapter.

## Topics

| Topic | Producers | Consumers | Retention |
|---|---|---|---|
| `konzert.events` | sensor, chaos injector | maestro, console | 1h |
| `konzert.conversation` | maestro, atlas, sentinel, console (human) | all agents, console | 1h |
| `konzert.actions` | atlas, sentinel | console | 1h |

Three topics rather than one because they are different things: events are
facts, the conversation is the dialogue, actions are the audit trail with the
playbook output. Short retention keeps a repeated demo clean.

Every record is keyed by its correlation ID, and the three topics have the same
number of partitions, so one incident is always handled by one replica of each
agent, in order. See [scaling.md](../../../docs/scaling.md).

## Message contract

Everything is a CloudEvent with a mandatory `correlationid`, per
[ADR-0002](../../../docs/adr/0002-event-governance.md). One incident = one
correlation ID = one story the panel can group.

### Detected fact — `konzert.events`

```json
{
  "specversion": "1.0",
  "type": "io.konzert.MemoryPressureDetected",
  "source": "/konzert/sensor",
  "id": "01J...",
  "time": "2026-08-18T14:30:00Z",
  "correlationid": "incident-7f3a",
  "data": {
    "namespace": "konzert-demo",
    "workload": "checkout-api",
    "kind": "Deployment",
    "evidence": {
      "podMemoryBytes": 1932735283,
      "limitsDeclared": false,
      "trend": "+180Mi/30s",
      "observedFor": "90s"
    }
  }
}
```

Event types: `MemoryPressureDetected`, `SuspiciousNetworkActivityDetected`,
`IncidentResolvedConfirmed` (sensor confirming the fix held).

### Conversation turn — `konzert.conversation`

```json
{
  "specversion": "1.0",
  "type": "io.konzert.MessagePosted",
  "source": "/konzert/agents/maestro",
  "correlationid": "incident-7f3a",
  "data": {
    "from": "maestro",
    "to": ["atlas"],
    "intent": "delegate",
    "turn": 2,
    "text": "checkout-api has no memory limit and is climbing. Yours.",
    "reasoning": "The evidence pairs a live memory reading with a missing limit…",
    "context": { "namespace": "konzert-demo", "workload": "checkout-api" }
  }
}
```

`reasoning` is optional and carries the model's own reasoning trace when the
provider returns one (DeepSeek's `reasoning_content`, and equivalents). Those
tokens are already paid for (on `deepseek-v4-flash` they were 83% of the output
on a real delegation turn), so the panel renders them as a collapsible block
under the message. It is the difference between believing the conversation and
*seeing* why Maestro chose Atlas over Sentinel.

`to` is a list and accepts `all` and `human`. `intent` is one of `observe`,
`delegate`, `ack`, `plan`, `act`, `report`, `ask`, `answer`, `close` — the panel
uses it for colour and icon, and the agents use it to decide whether a message
needs an answer at all.

### Action record — `konzert.actions`

```json
{
  "type": "io.konzert.PlaybookExecuted",
  "source": "/konzert/agents/atlas",
  "correlationid": "incident-7f3a",
  "data": {
    "playbook": "set-deployment-limits.yml",
    "extraVars": { "namespace": "konzert-demo", "deployment": "checkout-api",
                   "memory_limit": "1Gi", "memory_request": "512Mi" },
    "status": "ok",
    "stats": { "ok": 12, "changed": 3, "failed": 0 },
    "durationMs": 8400,
    "output": "PLAY [Apply resource limits] ****..."
  }
}
```

## Flow — incident 1: memory leak, no limits

```
chaos           sensor            maestro          atlas           mcp-ansible
  │ deploy        │                  │               │                  │
  ├──────────────>│ metrics poll     │               │                  │
  │               │ rss>threshold    │               │                  │
  │               │ AND no limits    │               │                  │
  │               ├─ MemoryPressureDetected ─────────────> konzert.events    │
  │               │                  │ triage (LLM)  │                  │
  │               │                  ├─ delegate ──> │ konzert.conversation │
  │               │                  │               ├─ ack ─────>      │
  │               │                  │               ├─ tools/list ────>│
  │               │                  │               ├─ plan ────>      │
  │               │                  │               ├─ run_playbook ──>│
  │               │                  │               │  set-deployment- │
  │               │                  │               │  limits.yml      │
  │               │                  │               │<── stats ────────┤
  │               │                  │               ├─ PlaybookExecuted -> konzert.actions
  │               │                  │ <── report ───┤                  │
  │               │ rss flat 60s     │               │                  │
  │               ├─ IncidentResolvedConfirmed ─────────> konzert.events     │
  │                                  ├─ close ────>  │                  │
```

Detection is honest: the sensor correlates a *live memory reading* with a
*missing limit in the spec*. It does not know a chaos injection happened.

## Flow — incident 2: port scan (the three-way)

The scanner pod's own log lines are the signal. The sensor reads pod logs
through the Kubernetes API. (Reading them from a Kafka topic fed by OpenShift
Logging would be the better source on a cluster that forwards logs to Kafka;
that is not implemented.)

1. Sensor emits `SuspiciousNetworkActivityDetected` with the offending lines.
2. Maestro triages and delegates to **Sentinel** — network policy is not
   Atlas's to touch.
3. Sentinel runs `isolate-workload.yml`: a deny-all-egress NetworkPolicy with a
   DNS exception, scoped by label selector.
4. Sentinel posts to `all`: *"scanner isolated. Atlas, the policy is namespace
   wide by label — confirm checkout-api still passes readiness."*
5. **Atlas answers without being asked by Maestro**: it reacted to a broadcast.
   That is the pub/sub moment of the example.
6. Maestro closes the incident and summarises both actions for the room.

## Loop control — how this does not run away

An LLM conversation on a bus can spin forever and burn tokens. None of this is
asked of the model: it is the **intake filter** every Konzert agent carries
([ADR-0007](../../../docs/adr/0007-agent-intake-filtering.md),
[agent-control-panel.md](../../../docs/agent-control-panel.md)), configured
per agent and changeable at runtime.

Three gates stand between a Kafka message and a token:

1. **The sensor** — no LLM at all. Nothing becomes an event until a metric
   correlates with a spec omission, or a log matches a pattern.
2. **Intake** — default deny. An agent reasons only about what its rules admit.
3. **Budget** — hard caps per agent, enforced in code.

The shipped defaults, as intake configuration:

| Agent | Admits | Everything else |
|---|---|---|
| maestro | `konzert.events` incident types; `konzert.conversation` where `data.to` contains `maestro` or `all`, or `data.intent` is `ask` | counted |
| atlas | `konzert.conversation` where `data.to` contains `atlas` or `all` | counted |
| sentinel | `konzert.conversation` where `data.to` contains `sentinel` or `all` | counted |

Plus, for all three:

- an agent never matches its own `from` — no self-reply loops;
- **turn budget per correlation ID** (default 12). At the cap, Maestro is forced
  to post `close` and the incident is over;
- **one incident in flight per agent** (`maxConcurrentIncidents: 1`); an event
  for another incident arriving meanwhile is counted, not reasoned about;
- **dedup window** of 5 min on `type + data.namespace + data.workload`, so a
  flapping sensor cannot restart the story;
- `eventsPerMinute`, `llmCallsPerHour` and `tokensPerHour` capped, so a runaway
  conversation is throttled instead of becoming a bill;
- every LLM call has a timeout and a deterministic fallback line, so a slow
  provider degrades into a terse agent rather than a frozen panel;
- autonomy `propose` (the shipped default for Atlas and Sentinel) — a
  mutating playbook is posted as a plan and waits for the operator; see
  *Human approval* below. `act` executes without asking; `off` consumes
  nothing.

Because these are configuration rather than code, a filter can be tightened
while the example runs, and the funnel shows the effect.

## Human approval

Approval is not a side channel. When a worker in `propose` mode decides on a
mutating playbook it posts, under the incident's correlation ID:

```json
{ "from": "atlas", "to": ["human"], "intent": "ask",
  "text": "checkout-api has no limit, 1.8GiB and climbing. Proposed: run set-deployment-limits.yml with {...}. Approve?",
  "plan": { "playbook": "set-deployment-limits.yml",
            "extraVars": { "namespace": "konzert-demo", "deployment": "checkout-api", "memory_limit": "1Gi" } } }
```

The Situation Room renders the two buttons from `data.plan`; clicking one
publishes `{ "from": "human", "to": ["atlas"], "intent": "answer", "text": "approved" }`
on the same topic. The worker matches the verdict deterministically (no model
between a "yes" and a playbook run), executes, records `PlaybookExecuted` on
`konzert.actions` with `approvedBy: human`, and reports to Maestro. A "denied"
becomes a report that the plan was discarded. Anything else the operator
types is treated as a question and answered with the pending plan in view.

Because the verdict is a message, the replay shows it, the dry run counts it,
and the intake rule "addressed to me" is what lets it in — the same rule that
admits Maestro's delegation.

Two safety nets keep a stalled approval from wedging the team: Maestro
broadcasts every `close` to `all`, and every agent releases an incident with
no activity for ten minutes (`konzert.idle-close-minutes`).

## Ansible: how the agents actually fix things

`mcp-ansible` is a Quarkus MCP server (SSE transport) whose image adds
`ansible-core` and the
`kubernetes.core` collection. The workers talk to it with a deliberately
small JSON-RPC-over-SSE client (`McpSseClient`, ~200 lines) so that the
agents' "hands" are readable on a slide. It exposes three tools:

| Tool | Description |
|---|---|
| `list_playbooks` | catalogue with each playbook's purpose and required vars |
| `run_playbook` | run one playbook from the catalogue with `extraVars` |
| `playbook_status` | last run's stats and output for a correlation ID |

The catalogue is a closed allow-list baked into the image — an agent cannot ask
for an arbitrary playbook or arbitrary shell. Playbooks:

| Playbook | Owner | Effect |
|---|---|---|
| `set-deployment-limits.yml` | atlas | patch `resources` on a Deployment, wait for rollout |
| `revert-deployment-limits.yml` | atlas | remove them again (repeat demos) |
| `isolate-workload.yml` | sentinel | deny-all-egress NetworkPolicy + DNS exception, by label |
| `release-workload.yml` | sentinel | delete that NetworkPolicy |
| `describe-workload.yml` | both | read-only facts for diagnosis |

**Ansible Automation Platform**: the MCP server is designed with a pluggable
backend. `local` runs `ansible-playbook` in the pod and is the only backend
implemented today. An `aap` backend, launching the equivalent Job Template
through the Controller API, is planned; the agents, prompts and panel would be
identical either way.

## RBAC

| ServiceAccount | Rights, all scoped to the demo namespace |
|---|---|
| `konzert-sensor` | read-only: pods, pod logs, deployments, replicasets, networkpolicies, pod metrics |
| `maestro`, `atlas`, `sentinel` (one each) | read ConfigMaps and Secrets; write only their own two `-runtime` config objects. No rights over any workload |
| `konzert-mcp-ansible` | patch Deployments, manage NetworkPolicies, read pods |
| `konzert-console` | create and delete the chaos Deployments, delete the quarantine NetworkPolicies, read pods and services |

One caveat, stated plainly: Kubernetes cannot restrict `list` and `watch` by
object name, and an agent watches its configuration. So an agent can *read*
every ConfigMap and Secret in the namespace it runs in, including another
agent's key when they share a namespace, as the three agents do here. They
still cannot change anything but their own runtime objects. Run agents that
must not see each other's credentials in separate namespaces. See
[SECURITY.md](../../../SECURITY.md).

## Resource budget

The example is sized to fit a small shared cluster. Requests:

| Pod | CPU | Memory |
|---|---|---|
| konzert-sensor | 50m | 256Mi |
| maestro / atlas / sentinel | 100m each | 512Mi each |
| mcp-ansible | 100m | 512Mi |
| konzert-console | 100m | 256Mi |
| chaos workloads | 50m each | 64Mi + whatever the leak takes |

Total ≈ 650m CPU and ≈ 2.5Gi memory, plus the leak. The memory-hog is capped in
the injector so it cannot take a node down: it allocates to a configurable
ceiling (default 2Gi) and holds.
