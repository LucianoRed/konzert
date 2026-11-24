# The Control Panel API and the intake filter

Every Konzert agent exposes the same HTTP API to read and change its identity,
model, prompt, autonomy and, most importantly, its **event intake filter**, at
runtime, without a rebuild or a redeploy. It comes with `konzert-core`: an
agent gets it by depending on the library.

It exists for two reasons:

1. **Cost.** An agent subscribed to a busy topic that reasons about every
   message burns tokens with nothing to show for it. Filtering has to be
   configurable, tunable live, and *visible*.
2. **Explainability.** "Why did this agent stay quiet?" must have an answer an
   operator can read: which rule matched, at which stage, and why.

There is no web UI yet. The API is the panel; a UI on top of it is on the
[roadmap](../ROADMAP.md).

> **The API has no authentication.** Anyone who can reach it can read an agent's
> prompt, change its model endpoint, replace its key, or switch it from
> `propose` to `act`. Do not expose it with a Route or an Ingress without an
> authenticating proxy in front. See [SECURITY.md](../SECURITY.md).

## API

| Endpoint | Purpose |
|---|---|
| `GET /api/config` | the effective configuration: agent, intake, secrets (masked), and which objects store it |
| `PUT /api/config` | partial update of `agent`, `intake` and/or `secrets`; a blank or masked secret means "keep the stored one" |
| `GET /api/config/intake` · `PUT /api/config/intake` | the filter alone |
| `GET /api/config/intake/stats` | the funnel, open incidents and turn counts, the last 100 verdicts with their reasons, autonomy |
| `POST /api/config/intake/dry-run` | replay recent traffic against a candidate filter; no model call, live counters untouched |
| `POST /api/config/reset` | delete the runtime objects; the seeded defaults apply again |
| `GET /api/config/export` | the effective configuration as a ConfigMap manifest, ready to commit as the next seed |
| `GET /api/config/souls` | identity, soul, system prompt and autonomy |
| `GET /api/config/topics` | the topics this agent subscribes to |

```bash
oc port-forward deploy/atlas 8081:8080 &

curl -s localhost:8081/api/config/intake/stats | jq '.funnel'

# Would a tighter rule have changed anything in the last hour? No model is called.
curl -s -X POST localhost:8081/api/config/intake/dry-run \
  -H 'Content-Type: application/json' \
  -d "{\"sample\": 200, \"intake\": $(curl -s localhost:8081/api/config/intake)}" | jq '.funnel'

curl -s -X PUT localhost:8081/api/config -H 'Content-Type: application/json' \
  -d '{"agent": {"autonomy": "act"}}'
```

## What is configurable

### Identity

`name`, `displayName`, `emoji`, `avatar`, `color`, `role`, and the **soul**
(character, tone, boundaries). The soul is distinct from the system prompt; see
[ADR-0004](adr/0004-soul-vs-system-prompt.md).

### Model

| Field | Notes |
|---|---|
| `provider` | a label: `aigateway`, `deepseek`, `openai`, `openrouter`, `custom` |
| `baseUrl` | any OpenAI-compatible chat-completions endpoint |
| `model` | the model id |
| `triageModel` | the model for the triage lane (not used by the shipped agents yet) |
| `reasoningEffort` | `low` or `high`, for reasoning models that accept it |
| `temperature`, `maxTokens`, `timeoutSeconds` | per-call limits |
| `fallbackText` | what the agent says when a call fails or times out |

The key is not part of this object. It is a secret (`llmApiKey`), stored in a
Kubernetes Secret, masked on every read and never logged.

Three things worth knowing about reasoning models, measured against
`deepseek-v4-flash`:

1. **Do not size `maxTokens` for the answer alone.** Reasoning tokens are spent
   from the same budget *before* any content is produced. A call with
   `maxTokens: 20` returned an empty answer after spending all 20 tokens
   thinking; with 300 it answered, using 155 to 201 reasoning tokens.
2. **Empty content with non-empty reasoning is a failed call, not an answer.**
   The client reports it as such, and an agent must fall back rather than
   publish an empty turn.
3. **Keep the prompt prefix stable.** A repeated call hit the provider's prompt
   cache for 384 of 477 prompt tokens. System prompt and soul first, the
   volatile part (the event, the conversation tail) last.

### System prompt

Free text. Empty means "use the default shipped in the agent's code".

### Autonomy

- **off**: the agent consumes nothing; the intake pipeline is stopped.
- **propose**: the agent reasons, and an action that changes something waits
  for a human's approval (see [messages.md](messages.md#human-approval)).
  Read-only actions run without asking.
- **act**: the agent executes.

### Memory

`windowMessages`: how many recent lines of an incident's conversation go into
the prompt.

### Declared but not used yet

The configuration object also carries fields for RAG (`rag.*`), conversation
compaction (`memory.compaction*`) and MCP server lists (`tools.*`). Nothing
reads them today; they are placeholders for planned features.

---

## The intake filter

The intake pipeline sits between the Kafka consumer and the agent's reasoning
loop. **Every stage is deterministic and costs zero tokens.** A message reaches
the model only by surviving all of them.

```
Kafka ─▶ ① subscription ─▶ ② rules ─▶ ③ dedup ─▶ ④ correlation ─▶ ⑤ debounce ─▶ ⑥ lane ─▶ ⑦ budget ─▶ 🧠
          topic + type     default    fingerprint   in flight?    (not wired)    count |     caps
                            deny       + window     turn budget                  reason
```

Each stage increments a counter. Those counters are the funnel.

### ① Subscription

Topics, and for each an allow-list of CloudEvent `type`s (empty means any).
An agent also drops its own messages here (`selfName`): an agent that answers
itself never stops.

### ② Rules: ordered, default deny

An ordered list. **First match wins.** Anything that matches nothing is
dropped. Default deny is deliberate: a new event type on a shared topic must
never silently start costing money.

```json
{
  "rules": [
    { "name": "not my namespace",
      "when": { "field": "data.namespace", "op": "neq", "value": "konzert-demo" },
      "then": "drop" },
    { "name": "my incidents",
      "when": { "all": [
        { "field": "type", "op": "endsWith", "value": "Detected" },
        { "any": [
          { "field": "data.severity", "op": "in", "value": ["high", "critical"] },
          { "field": "data.workload", "op": "matches", "value": "checkout-.*" }
        ]}
      ]},
      "then": "reason" },
    { "name": "addressed to me",
      "when": { "field": "data.to", "op": "contains", "value": "atlas" },
      "then": "reason" },
    { "name": "everything else on my topics",
      "then": "count" }
  ]
}
```

A rule without `when` matches everything, which makes it the explicit last
line of a filter.

**Predicates are structured, not a scripting language**: `field` / `op` /
`value`, grouped by `all` / `any` / `not`. A rule can be read and explained,
and cannot loop or throw.

| Operator | Notes |
|---|---|
| `eq` `neq` `in` `notIn` | exact matching |
| `contains` `startsWith` `endsWith` | strings and arrays |
| `matches` | a regular expression; input longer than 4096 characters never matches |
| `exists` | presence |
| `gt` `gte` `lt` `lte` | numbers |

`field` is a dotted path into the whole CloudEvent (`type`, `source`,
`correlationid`, `data.namespace`, …). A missing path makes the predicate
false; it never raises.

`then` is the lane: `reason`, `count`, `drop`, and the two that are not wired
yet, `triage` and `deterministic:<handler>` (see below).

### ③ Dedup

A fingerprint (a list of fields) and a window. The same fact arriving fifty
times in a minute produces one reasoning turn.

```json
{ "dedup": { "enabled": true, "fingerprint": ["type", "data.namespace", "data.workload"],
             "window": "5m", "onDuplicate": "count" } }
```

### ④ Correlation join, turn budget, concurrent incidents

A message whose `correlationid` is an incident already in flight joins that
incident, until the incident's **turn budget** (`turnBudget`, default 12) is
spent; after that it is counted and no longer reasoned about.

A message for a *new* correlation ID is admitted only while the agent has fewer
than `maxConcurrentIncidents` (default 1) open. Otherwise it is counted. It is
not queued: if the fact still holds, its sensor will report it again after the
dedup window.

### ⑤ Debounce (not wired)

The design holds matching messages for a short window and hands the model one
batch. The configuration is accepted and the stage is counted, but messages are
not held or batched today.

### ⑥ Lane

| Lane | Cost | State |
|---|---|---|
| `drop` | zero | implemented; not even counted as relevant |
| `count` | zero | implemented; counted, visible in the funnel |
| `reason` | full model call | implemented |
| `triage` | a cheaper model decides whether to escalate | the verdict exists and the client can call a triage model, but the shipped agents treat it like `reason` |
| `deterministic:<handler>` | zero | counted only; there is no handler registry |

### ⑦ Budget

Hard caps, enforced in code:

```json
{ "budget": { "eventsPerMinute": 6, "llmCallsPerHour": 60,
              "tokensPerHour": 100000, "onExceed": "drop" } }
```

`onExceed` is `drop`, `queue` or `downgrade`. Today only `drop` does exactly
what it says. `queue` counts the message without queueing it, and `downgrade`
sends it to the `triage` lane, which the shipped agents do not yet distinguish
from `reason`. Until that is wired, use `drop` if the cap must be a hard one.

Token counts come from the provider's own usage figures on each response.

### The funnel and the dry run

`GET /api/config/intake/stats` returns the counters per stage, the lanes, the
tokens spent, and the last 100 verdicts, each with the stage that decided it,
the rule that matched and a reason in plain words.

`POST /api/config/intake/dry-run` replays the messages already on the
subscribed topics (the last hour, up to `sample` per topic) through a candidate
filter and returns a verdict per message plus the resulting funnel. No model is
called and the live counters do not move. Change a rule, dry-run it, see "of
the last 200 messages, 9 would reach the model instead of 40", then save.

---

## Where configuration is stored

**Configuration lives in Kubernetes objects in the agent's namespace, not on a
volume.** An agent needs no PersistentVolumeClaim.

| Object | Owner | Contents |
|---|---|---|
| `<agent>-config` | Helm / GitOps | seeded defaults: identity, soul, prompt, model, intake |
| `<agent>-config-runtime` | **the agent** | only what was changed through the API |
| `<agent>-secrets` | you, Helm, or an external secrets operator | the seeded key (`llm-api-key`) |
| `<agent>-secrets-runtime` | **the agent** | a key set through the API |

The split is the point. If the API wrote to the object Helm manages, the next
`helm upgrade` would erase what an operator tuned at runtime. The agent never
writes the seeded objects.

### Precedence

```
code defaults  <  <agent>-config / <agent>-secrets  <  <agent>-config-runtime / <agent>-secrets-runtime
```

Merged per field, not per file: overriding the intake rules does not detach the
prompt from its seeded default.

Resetting is deleting the runtime objects, which the agent labels
`konzert.io/config=runtime`:

```bash
oc delete cm,secret -l konzert.io/config=runtime -n <namespace>
```

### Hot reload

Each agent watches its objects through the Kubernetes API and reloads without a
restart. It works both ways: `oc edit cm atlas-config` changes the running
agent, and a change through the API produces a real object an operator can
inspect.

Outside a cluster (`mvn quarkus:dev`) the store degrades to JSON files under
`agent.data-dir` (default `/tmp/agent-data`), so an agent can be developed
without Kubernetes.

### RBAC

The agent chart gives each agent a Role in its own namespace:

- `get`, `list`, `watch` on ConfigMaps and Secrets;
- `create`, `update`, `patch`, `delete` restricted by name to its own two
  runtime objects.

Kubernetes cannot restrict `list` and `watch` by object name. So an agent can
**read every ConfigMap and Secret in its namespace**. Do not run an agent in a
namespace that holds secrets it must not see, and give agents that must not
read each other's keys a namespace each.

An agent has no rights over workloads. Whatever it changes, it changes through
an MCP server that holds those rights.

### Secrets

- Masked on read. A blank or masked value on write means "keep the stored
  one", so a key never has to round-trip through a client to save another
  field.
- Never logged.
- Never in a values file: the chart has no field for the key. Create the
  `<agent>-secrets` Secret yourself, or let an external secrets operator do it.
