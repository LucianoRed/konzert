# ADR-0007: Event intake filtering and the Control Panel

## Status

Accepted (2026-08). The pipeline, the configuration store and the REST API are
implemented in `core`. Parts of the design are not wired yet; see
[Implementation status](#implementation-status).

## Context

Collaboration is event-driven and Kafka topics are shared: the conversation
topic carries every turn of every agent. An agent that subscribes to a topic
and reasons about each message spends a model call, and real money, on messages
that were never its business.

It is also a *correctness* problem. An agent that reacts to everything reacts to
its own output, and the conversation never converges.

Filtering written ad hoc inside each agent ("react only when `to` contains my
name") is the same missing abstraction implemented once per agent, and
configurable in none of them. Runtime configuration (model, credentials,
prompt) has the same problem.

## Decision

**1. Every agent has an intake pipeline between the Kafka consumer and its
reasoning loop.** Deterministic, zero-token stages: subscription, rules, dedup,
correlation join, debounce, lane assignment, budget. Specified in
[agent-control-panel.md](../agent-control-panel.md).

**2. Intake is default deny.** A message that matches no rule is dropped. A new
event type appearing on a shared topic must never silently start costing money.

**3. Filters are structured predicates, not a scripting language.**
`field` / `op` / `value`, grouped by `all` / `any` / `not`. A rule can be read
and explained, rendered as form fields, and cannot loop, throw or be exploited.

**4. Reaching an agent is not the same as reaching a model.** Lanes: `count`,
`deterministic`, `triage` (a cheap model decides whether to escalate) and
`reason`. Most traffic should never leave the first two.

**5. Budgets are enforced in code, not requested in a prompt.** Events per
minute, model calls per hour, tokens per hour, and a turn budget per
correlation ID.

**6. Every agent exposes the same Control Panel API** covering identity and
soul, model and credentials, system prompt, intake and autonomy, so a fleet of
agents can be seeded, inspected and reset by script.

**7. Configuration is stored in Kubernetes objects, not on a volume.** Four per
agent: a ConfigMap and a Secret seeded by Helm or GitOps, and a ConfigMap and a
Secret owned by the agent, holding only what was changed at runtime. Precedence
is `code defaults < seeded < runtime`, merged per field. The split exists
because a runtime change written into the Helm-managed object would be erased
by the next `helm upgrade`. Agents watch their objects and reload without a
restart.

**8. The filter is observable and testable.** Counters per stage (the funnel),
the reason for each recent verdict, and a dry run that replays recent real
traffic against a candidate configuration without calling a model.

## Consequences

- One implementation of filtering and configuration, shared by every agent.
- An agent needs a little RBAC (write its own two runtime objects) and no
  PersistentVolumeClaim.
- Default deny means a new event type is invisible until someone adds a rule.
  That is the intended trade, and the funnel makes the drop visible.
- Loop control (turn budget, one incident in flight, dedup) is configuration,
  tunable while the agents run.

## Implementation status

| Part | State |
|---|---|
| Subscription, rules, dedup, correlation join, turn budget, concurrent-incident cap | implemented |
| Budget stage (`eventsPerMinute`, `llmCallsPerHour`, `tokensPerHour`) | implemented |
| Funnel, recent verdicts, dry run | implemented |
| Config store (seeded + runtime objects, watch, per-field merge), REST API | implemented |
| `count` and `reason` lanes | implemented |
| `triage` lane | the verdict exists and the LLM client supports a triage model, but the shipped agents treat it like `reason` |
| `deterministic:<handler>` lane | counted only; there is no handler registry |
| Debounce / batching | counted only; messages are not held or batched |
| `onExceed: queue` | counts the message; nothing is queued |
| Control Panel web UI | not implemented; the API is the panel |
| Authentication on the API | not implemented; see [SECURITY.md](../../SECURITY.md) |
