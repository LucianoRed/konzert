# Concepts

The vocabulary used across the code and the documentation.

**Agent.** A long-running service with a name, a role, a model, a soul and an
intake filter. It consumes messages from Kafka, decides which ones deserve a
model call, reasons, and publishes messages. An agent is not a chatbot: nobody
has to be talking to it for it to work.

**Role and replica.** A *role* is a kind of agent (the infrastructure agent). A
*replica* is one running instance of it. Replicas of a role share a Kafka
consumer group; different roles do not. See [scaling.md](scaling.md).

**Dispatcher.** The agent that triages a new fact and decides who owns it. It
has no tools and no rights over anything it could break. In this repository it
is Maestro ([agents/maestro](../agents/maestro)).

**Worker.** An agent that owns a domain and acts in it through tools. In this
repository one image ([agents/worker](../agents/worker)) is every worker; its
identity, soul, filter and catalogue of allowed actions are configuration.

**Sensor.** A component with no model that turns reality into events: a metric
correlated with a spec, a log pattern. Nothing becomes an event, and nothing
costs a token, until a sensor says so.

**Soul and system prompt.** The system prompt is the agent's job; the soul is
its character. They are separate fields, edited separately
([ADR-0004](adr/0004-soul-vs-system-prompt.md)).

**Correlation ID.** The identifier of one story, carried by every message about
it. It groups a conversation, bounds a turn budget, and is the Kafka record key
that keeps one story on one replica.

**Incident.** One story being worked on: a correlation ID that an agent has
opened and not yet closed. The name comes from the first example; the mechanism
is general.

**Intent.** What kind of turn a message is: `delegate`, `ask`, `answer`, `plan`,
`act`, `report`, `ack`, `observe`, `close`. See [messages.md](messages.md).

**Intake filter.** The deterministic pipeline between a Kafka topic and an
agent's model. Default deny: a message reaches the model only if a rule admits
it. See [agent-control-panel.md](agent-control-panel.md).

**Lane.** Where an admitted message goes: `count` (counted only), `reason` (the
model), and two that are designed but not wired yet, `triage` and
`deterministic`.

**Funnel.** The counters of the intake pipeline: seen, subscribed, matched,
deduplicated, reasoned, tokens. It is the answer to "what is this agent costing,
and why".

**Autonomy.** `off` (consume nothing), `propose` (reason, but a mutating action
waits for a human), `act` (execute).

**Hands.** The tools through which an agent changes anything. Konzert's rule is
that an agent has no credentials of its own for the systems it acts on: it
calls an [MCP](https://modelcontextprotocol.io) server, and that server holds
the rights and decides what may be asked of it.

**Seed and runtime configuration.** An agent's configuration is two layers of
Kubernetes objects: what was installed (the seed, owned by Helm or GitOps) and
what was changed while it ran (owned by the agent). Runtime wins, per field.

**Control Panel API.** The REST API every agent exposes to read and change its
configuration and to inspect its filter. A web UI on top of it is planned.

**Observer.** Anything that reads the bus without being an agent: a panel, an
audit sink. It joins no role's consumer group and passes no intake filter,
because it never calls a model.
