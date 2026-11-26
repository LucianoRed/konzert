# Konzert

**An event-driven multi-agent framework for OpenShift.** AI agents with
different jobs collaborate over Kafka, with a default-deny filter in front of
every model, no direct access to the infrastructure they act on, and human
approval built in.

*Konzert* is German for concert: several players, one piece, a conductor.

## Why

One agent is easy. A team of agents is an integration problem: who talks to
whom, who pays for every message a model reads, who is allowed to touch
production, and who can tell afterwards what happened. Konzert is one answer to
those four questions.

- **One way to communicate, however many agents there are.** Agents do not call
  each other and do not know each other's addresses. They publish to a bus and
  listen to it. A new agent is a new consumer group on the same topics, not a
  new integration with every agent that already exists.
- **Built to scale out, the way Kafka consumers do.** Every role, Maestro
  included, is a consumer group. Run more replicas of a role and Kafka splits
  the partitions between them, so each incident is worked on by exactly one
  replica, with no coordinator and no leader election. How many replicas a role
  can have is set by the number of partitions, and there are rules the topics
  must follow: [docs/scaling.md](docs/scaling.md). The mechanism is built;
  running several replicas is still experimental, because what an agent knows
  about an open incident lives in memory.
- **Hearing everything without paying for everything.** Every agent hears the
  whole conversation, and a deterministic filter decides, at zero tokens, what
  reaches its model. Budgets for turns, calls and tokens are enforced in code,
  and a funnel shows what each agent is costing and why.
- **Safe by structure, not by prompt.** The agent that decides has no tools.
  The agents that act hold no rights over the systems they change: they ask a
  tool server for one of a closed list of actions, and a person can be required
  to approve it first.
- **Everything is on the record.** Every fact, every turn of the conversation
  and every action taken is a CloudEvent with a correlation ID, on a log.
  Anything can watch without being wired into the agents, and an incident can
  be read back from its first fact to its close.
- **Open at the edges.** Anything that can publish JSON to Kafka can report a
  problem. Models come from any OpenAI-compatible endpoint, tools through MCP.
- **Changed while it runs.** A new agent is usually configuration, not a new
  image, and an agent's prompt, filter and autonomy can be edited while it is
  working.

Not all of it is finished, and the bus itself is not authenticated yet. The
honest list is under
[what works today, and what does not](#what-works-today-and-what-does-not).

> **Status: early and experimental.** This code was extracted from a working
> demo that ran on a real cluster. In this repository it builds and passes its
> unit tests, but the extracted version has not yet been re-run end to end.
> APIs, message formats and names can change, and important parts are not built
> yet. Read [what works and what does not](#what-works-today-and-what-does-not)
> and [SECURITY.md](SECURITY.md) before running it anywhere that matters.

## The idea

Most agent frameworks connect agents to each other with function calls or
point-to-point protocols. Konzert connects them the way teams of services are
connected: with a bus.

![Konzert: a Kafka bus in the middle. A sensor, Maestro the dispatcher and a human above it; the agents below it, each behind an intake filter, reaching your systems only through an MCP tool server.](docs/images/konzert-overview.svg)

## The parts

Four parts make the structure: sensors, Maestro, agents, and the bus between
them. Atlas and Sentinel, in the picture, are two examples of an agent.

### Sensor

A sensor reports that something happened. It contains no model, so nothing
costs a token until a sensor speaks. It can be anything able to publish a JSON
event to Kafka: a process watching metrics, something that forwards alerts from
a monitoring system, a scan of logs.

Only the envelope is fixed: a CloudEvent with a type, a source and a
correlation ID. What goes inside `data` is up to the sensor. There is no schema
to register and no field Maestro requires, because Maestro reads the payload as
it is and interprets it. Whether Maestro reasons about a fact at all is decided
earlier, by a rule in its filter that matches on the type or on any field of
the payload.

```json
{
  "type": "io.konzert.MemoryPressureDetected",
  "source": "/konzert/sensor",
  "correlationid": "incident-7f3a2b",
  "data": {
    "namespace": "konzert-demo",
    "workload": "checkout-api",
    "evidence": { "podMemoryMi": 1843, "limitsDeclared": false, "trend": "+180Mi/30s" }
  }
}
```

The sensor that ships today is the one in the
[incident-response example](examples/incident-response): it watches pod memory
and log lines in one namespace.

### Maestro, the dispatcher

Maestro receives the facts, works out what kind of problem each one is, and
decides which agent owns it. It then puts the problem back on the bus,
addressed to that agent: a message with the owner in `to`, a sentence saying
why, and the sensor's evidence attached as `context`, so the owner sees what
Maestro saw.

```json
{
  "from": "maestro",
  "to": ["atlas"],
  "intent": "delegate",
  "text": "checkout-api has no memory limit and is climbing. Yours.",
  "context": { "namespace": "konzert-demo", "workload": "checkout-api" }
}
```

Maestro also answers people who talk to the team, and closes an incident once
its owner has reported the fix. It has no tools at all. Who is on the team is
configuration, so adding an agent does not mean rebuilding Maestro.

### Agent

An agent owns a domain and deals with the problems that belong to it: a
security agent takes the security problems, an infrastructure agent takes the
infrastructure ones. It hears the whole conversation, and its filter admits
only what is addressed to it or to everyone.

When a problem is its own, the agent reasons about it and chooses an action
from the tools it was given. In the shipped worker, the tools are a list of
reviewed playbooks on an MCP server. Depending on its autonomy, it runs the
action or first asks a person to approve it. Then it reports the outcome on the
bus, and the run itself is recorded on the actions topic.

Every worker is the same image. Atlas (infrastructure) and Sentinel (security)
differ only in configuration: a soul, intake rules, and the playbooks each one
owns.

### The bus

Kafka, with three topics: `konzert.events` for facts, `konzert.conversation`
for the dialogue between agents and people, and `konzert.actions` for what was
actually executed. A person is one more participant on the conversation topic,
and an approval is a message like any other.

## The rules

Four rules follow from that picture, and they are the framework:

1. **Agents talk on Kafka and nowhere else.** Every message is a CloudEvent
   with a correlation ID. Any agent can react to a message meant for the room,
   an observer can watch without being wired into anything, and yesterday's
   incident can be replayed.
2. **Subscribing is not reasoning.** Between a topic and a model sits an
   *intake filter*: deterministic, zero tokens, default deny. An agent reasons
   only about what a rule admits, within budgets enforced in code. You can read
   the filter, change it while the agent runs, and see what it has been doing.
3. **Agents have no hands of their own.** An agent holds no credentials for the
   systems it changes. It calls an [MCP](https://modelcontextprotocol.io)
   server, which holds the rights and offers a closed catalogue of actions.
4. **A person can be in the loop without being in the way.** In `propose` mode
   an action that changes something waits for approval. The approval is a
   message on the same bus, matched without a model.

## What is in the box

| | |
|---|---|
| [`core/`](core) | the library every agent is built on: Kafka bus, CloudEvents, intake filter, LLM client, runtime configuration in Kubernetes objects, and the Control Panel API |
| [`agents/maestro/`](agents/maestro) | the dispatcher: triages facts, delegates, talks to people, closes incidents. It has no tools |
| [`agents/worker/`](agents/worker) | one image for every worker: an agent that owns a domain and acts through playbooks on an MCP server. Identity, soul, filter and catalogue are configuration |
| [`deploy/helm/agent/`](deploy/helm/agent) | one chart, installed once per agent |
| [`deploy/kafka/`](deploy/kafka) | a minimal Kafka cluster and the three topics |
| [`examples/incident-response/`](examples/incident-response) | the reference example: Maestro, **Atlas** (infrastructure) and **Sentinel** (security) resolve two real incidents on a cluster, with a sensor, an Ansible MCP server and a live panel |
| [`examples/minimal-agent/`](examples/minimal-agent) | the smallest useful agent, under a hundred lines, as a starting point |

Built with Quarkus and Java 25. Kafka through Strimzi, models through any
OpenAI-compatible endpoint, tools through MCP, automation through Ansible,
packaging through Helm.

## What works today, and what does not

| Works | Not yet |
|---|---|
| Agents collaborating over three Kafka topics, keyed by correlation ID | Durable per-incident state: it is in memory, so more than one replica per agent is experimental |
| Intake filter: subscriptions, ordered rules, dedup, turn budget, concurrent-incident cap, call and token budgets | Triage lane, deterministic handlers and debounce: configurable, counted, not wired |
| Funnel, per-message verdicts with reasons, dry run against real traffic | A web UI for the Control Panel: it is a REST API today |
| Runtime configuration in ConfigMaps and Secrets, hot reload, seed and runtime layers | **Authentication** on the Control Panel API and on the example's panel |
| Human approval as a message, matched deterministically | Kafka authentication, per-agent ACLs and signed messages ([ADR-0011](docs/adr/0011-identity-on-the-bus.md)) |
| Workers that can only run playbooks from a closed catalogue | An agent registry, several teams ("rooms"), an agent CRD and operator |
| The incident-response example: sensor, three agents, Ansible MCP server, panel | Schema registry, an A2A edge adapter, an Ansible Automation Platform backend |

The plan for the right-hand column is in [ROADMAP.md](ROADMAP.md).

## Try it

Build and test everything (Java 25, Maven):

```bash
mvn verify
```

Run the reference example on an OpenShift cluster you can throw away:
[examples/incident-response/docs/install.md](examples/incident-response/docs/install.md).
A two-incident run costs cents in tokens.

## Write an agent

Most new agents need no code: a worker is a soul, a values file and the
playbooks it may run. When you do need code, an agent is one class on top of
`konzert-core`. Both paths are in
[docs/building-an-agent.md](docs/building-an-agent.md).

## Documentation

- [docs/concepts.md](docs/concepts.md): the vocabulary
- [ARCHITECTURE.md](ARCHITECTURE.md): how the pieces fit
- [docs/messages.md](docs/messages.md): the message contract
- [docs/agent-control-panel.md](docs/agent-control-panel.md): the intake filter and the configuration API
- [docs/scaling.md](docs/scaling.md): roles, replicas, and the limits today
- [docs/building-an-agent.md](docs/building-an-agent.md): adding an agent
- [docs/adr/](docs/adr): why things are the way they are
- [docs/extraction-notes.md](docs/extraction-notes.md): the state of the repository after its first extraction, and what is next
- [docs/releasing.md](docs/releasing.md): how a version is published
- [SECURITY.md](SECURITY.md): known limitations, and how to report a problem
- [CONTRIBUTING.md](CONTRIBUTING.md) and [AGENTS.md](AGENTS.md): how to contribute, for people and for coding agents

## How it relates to other projects

Konzert is the collaboration layer and nothing else. It does not sandbox an
agent's execution and it is not a platform for deploying and governing agents;
other open-source projects do those, and the intent is to integrate with them
rather than rebuild them. The reasoning is in
[ADR-0009](docs/adr/0009-konzert-scope-and-positioning.md).

## License and disclaimer

Apache License 2.0. See [LICENSE](LICENSE).

Konzert is an independent, community project. It is not a product of, and is
not supported or endorsed by, Red Hat or any other company or project mentioned
in this repository. OpenShift is a trademark of Red Hat, Inc.; Apache Kafka is a
trademark of the Apache Software Foundation. Other names are the property of
their owners.
