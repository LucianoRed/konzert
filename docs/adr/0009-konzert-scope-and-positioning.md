# ADR-0009: Konzert as a standalone framework — scope and positioning

## Status

Accepted (2026-11)

## Context

Konzert started as the collaboration layer underneath a demo of three agents
resolving incidents on OpenShift. The parts that turned out to be reusable are
not about incidents: a bus, a message contract, a default-deny intake filter,
runtime configuration, and human approval as a message. They deserve a
repository of their own and a statement of what the project is for.

The surrounding ecosystem moved at the same time. Two open-source projects
address neighbouring problems (described here as their own documentation
described them in November 2026):

- **OpenShell** is a runtime that sandboxes an agent's execution: network
  egress, filesystem, processes and credentials are governed by policy, with
  egress denied by default. It answers *"what is this agent allowed to touch on
  the machine and the network?"*
- **OpenClaw Enterprise** describes itself as a control plane for deploying and
  managing agents: lifecycle, identity and access, audit. It answers *"who may
  run which agent, and how is it operated?"*

Neither provides the layer in between: how several agents with different jobs
*work together* on the same problem.

## Decision

**Konzert's scope is the collaboration layer.**

In scope:

- the bus and the message contract (CloudEvents, correlation IDs, intents);
- the intake filter between a topic and a model;
- runtime configuration of an agent as Kubernetes objects;
- the dispatcher-and-workers pattern (Maestro and worker agents);
- human approval as a first-class message;
- the scaling model for agent roles.

Out of scope, and integrated rather than rebuilt:

- **sandboxing an agent's execution.** An agent pod can run inside a sandbox
  runtime; Konzert does not implement one. Until that integration is proven it
  is listed as an open question, not as a feature.
- **a general agent lifecycle platform.** Konzert needs a small control plane of
  its own (an agent resource, admission by an administrator, a fleet view), but
  it should stay thin and able to delegate to a platform that already manages
  agents.

**Maestro is a Konzert agent, not a control plane.** A dispatcher triages events
and delegates work on the bus. A control plane deploys and governs agents. They
are different layers and one does not replace the other.

**Target platform: OpenShift and the open-source projects around it.** Kafka
through Strimzi, model serving through any OpenAI-compatible endpoint, tools
through MCP, automation through Ansible, packaging through Helm. Plain
Kubernetes is expected to work wherever an OpenShift-specific resource (a
Route, the internal image registry) is optional.

**Naming.** Java packages are under `io.konzert`, Kubernetes labels and
annotations under `konzert.io/`, default topics under `konzert.`.

Konzert is an independent open-source project. It is not a product of, and is
not endorsed by, any of the companies or projects named in this document.

## Consequences

- The project can be explained in one sentence, and a feature request can be
  judged against it.
- Sandboxing and lifecycle management arrive as integrations, which keeps the
  core small but makes those integrations work to be done, not work already
  done.
- The reference agents still carry incident-response defaults in their prompts
  ([ADR-0006](0006-incident-response-example.md)); making them domain-neutral
  is part of the roadmap.
