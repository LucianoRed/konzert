# Messages

Everything on the bus is a [CloudEvent](https://cloudevents.io) with a
mandatory correlation ID. This page is the contract. Nothing enforces it yet
(a schema registry is planned, see [ADR-0002](adr/0002-event-governance.md)),
so treat it as the source of truth and keep it in step with the code.

## Topics

| Topic | What it carries | Typical producers | Typical consumers |
|---|---|---|---|
| `konzert.events` | facts detected in the world | sensors (no LLM) | the dispatcher, observers |
| `konzert.conversation` | the dialogue between agents and people | agents, the human's console | every agent, observers |
| `konzert.actions` | what was actually executed, and the result | agents that act | observers, audit |

The names are defaults (`konzert.events-topic`, `konzert.conversation-topic`,
`konzert.actions-topic`). The three topics must have the same number of
partitions; see [scaling.md](scaling.md).

## The envelope

```json
{
  "specversion": "1.0",
  "type": "io.konzert.MessagePosted",
  "source": "/konzert/agents/maestro",
  "id": "5f0c1c0e-6f0a-4f43-9d0e-0b6f1a2f7c11",
  "time": "2026-11-23T14:30:00Z",
  "correlationid": "incident-7f3a2b",
  "datacontenttype": "application/json",
  "data": { }
}
```

- `correlationid` links one story from the first detected fact to the close. It
  is also the Kafka record key. A message without one is rejected by the code
  that builds the envelope.
- `type` is a past-tense fact.
- `source` says who produced it: `/konzert/agents/<name>`, `/konzert/sensor`,
  `/konzert/console`.

## A turn in the conversation: `MessagePosted`

```json
{
  "type": "io.konzert.MessagePosted",
  "source": "/konzert/agents/maestro",
  "correlationid": "incident-7f3a2b",
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

| Field | Meaning |
|---|---|
| `from` | the speaker: an agent name, or `human` |
| `to` | a list of addressees: agent names, `human`, or `all` |
| `intent` | what kind of turn this is (below) |
| `turn` | the speaker's turn counter within this correlation ID |
| `text` | what is said |
| `reasoning` | optional: the model's own reasoning trace, when the provider returns one |
| `context` | optional: evidence forwarded so the addressee sees what the speaker saw |
| `plan` | optional: the action an agent proposes, when it needs approval (below) |

`to` is what the intake filter of each agent matches on: an agent admits what is
addressed to it or to `all`, and counts the rest.

### Intents

| Intent | Used for | Expects an answer |
|---|---|---|
| `delegate` | the dispatcher hands an incident to its owner | yes |
| `ask` | a question, or a plan put to the human for approval | yes |
| `answer` | a reply to a question; the human's verdict on a plan | no |
| `plan` | an agent states what it is about to do | no |
| `act` | an agent states what it is doing | no |
| `report` | the outcome of an action | no |
| `ack` | an acknowledgement | no, and it is never answered |
| `observe` | a remark that needs nothing from anyone | no |
| `close` | the dispatcher declares the incident over; always addressed to `all` | no |

Agents use the intent to decide whether a message needs a reply at all. An
`ack` or `observe` from another agent is remembered and never answered, which
is one of the things that keeps a conversation from looping.

## Human approval

Approval is not a side channel. An agent in `propose` mode that decides on a
mutating action posts:

```json
{ "from": "atlas", "to": ["human"], "intent": "ask",
  "text": "checkout-api has no limit, 1.8GiB and climbing. Proposed: set-deployment-limits.yml. Approve?",
  "plan": { "playbook": "set-deployment-limits.yml",
            "extraVars": { "namespace": "konzert-demo", "deployment": "checkout-api", "memory_limit": "1Gi" } } }
```

The verdict comes back on the same topic, under the same correlation ID:

```json
{ "from": "human", "to": ["atlas"], "intent": "answer", "text": "approved" }
```

The agent matches the verdict with a fixed word list (English and Portuguese),
not with a model: nothing probabilistic stands between a "yes" and an action.
Anything that is neither a yes nor a no is treated as a question and answered
with the pending plan in view.

Because the verdict is a message, a replay shows it, and the intake rule
"addressed to me" is what lets it in.

## A record of an action: `PlaybookExecuted`

Published to `konzert.actions` by the worker agent after every playbook run:

```json
{
  "type": "io.konzert.PlaybookExecuted",
  "source": "/konzert/agents/atlas",
  "correlationid": "incident-7f3a2b",
  "data": {
    "agent": "atlas",
    "playbook": "set-deployment-limits.yml",
    "extraVars": { "namespace": "konzert-demo", "deployment": "checkout-api", "memory_limit": "1Gi" },
    "status": "ok",
    "stats": { "ok": 12, "changed": 3, "failed": 0 },
    "durationMs": 8400,
    "runId": "…",
    "output": "PLAY [Apply resource limits] ****…",
    "approvedBy": "human"
  }
}
```

`approvedBy` is `human` when the operator approved the plan and `autonomy` when
the agent ran it on its own in `act` mode.

## Facts from a sensor

Event types are defined by whoever detects them. The incident-response example
publishes three to `konzert.events`:

| Type | Meaning |
|---|---|
| `io.konzert.MemoryPressureDetected` | a pod above a memory threshold in a Deployment that declares no limit |
| `io.konzert.SuspiciousNetworkActivityDetected` | a pod whose log lines match a port-sweep pattern |
| `io.konzert.IncidentResolvedConfirmed` | the fix has held for the configured time |

```json
{
  "type": "io.konzert.MemoryPressureDetected",
  "source": "/konzert/sensor",
  "correlationid": "incident-7f3a2b",
  "data": {
    "namespace": "konzert-demo",
    "workload": "checkout-api",
    "kind": "Deployment",
    "evidence": { "podMemoryMi": 1843, "limitsDeclared": false, "trend": "+180Mi/30s" }
  }
}
```

The example's console also publishes `io.konzert.ProblemInjected` when a fault
is injected. It carries no diagnosis, and the dispatcher's filter counts it
without reasoning about it: the sensor still has to notice on its own.

## Conventions for new event types

- Name them as past-tense facts: `CertificateExpired`, not `CheckCertificate`.
- Put what the event is *about* in `data` fields a filter can match
  (`data.namespace`, `data.workload`), and what was *observed* under
  `data.evidence`.
- Reuse the correlation ID of the story you are continuing. Mint a new one only
  for a new story.
