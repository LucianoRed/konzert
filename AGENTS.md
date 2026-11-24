# AGENTS.md

Guidance for anyone changing this repository: people, and AI coding agents.

Konzert is a framework for agents that collaborate over Kafka. Read
[README.md](README.md) and [ARCHITECTURE.md](ARCHITECTURE.md) first; the
vocabulary is in [docs/concepts.md](docs/concepts.md).

## Current state

This repository was extracted from another one on 2026-11-23 and has not yet
been run on a cluster in its new form. Before changing anything, read
[docs/extraction-notes.md](docs/extraction-notes.md): what changed, what was
verified, what was not, and the next steps.

## Golden rules

A change that breaks one of these is wrong, whatever else it achieves.

1. **Agents communicate over Kafka, and only over Kafka.** No agent calls
   another agent directly.
2. **Every message is a CloudEvent with a correlation ID**, and the correlation
   ID is the Kafka record key.
3. **Nothing reaches a model without passing the agent's intake filter.** The
   filter is default deny, configurable at runtime, and its verdicts are
   visible.
4. **Agents hold no rights over the systems they act on.** Anything an agent
   changes, it changes through an MCP server that holds the rights and offers a
   closed catalogue of actions.
5. **Limits are enforced in code, never requested in a prompt.** Budgets, turn
   limits, approval, which actions an agent may run.
6. **Nothing probabilistic sits between a human's approval and an action.**
7. **Configuration is a Kubernetes object.** The seed belongs to Helm or GitOps,
   the runtime layer belongs to the agent, and neither writes the other's.
8. **Credentials live in Secrets.** Never in a ConfigMap, a values file, a log
   line, or this repository.
9. **Documentation is part of the implementation**, and it does not claim what
   the code does not do.
10. **Architectural decisions are recorded as ADRs** in [docs/adr/](docs/adr/).

## Every agent has

- a name, a role, a display name, an emoji or avatar, a colour;
- a system prompt (its job) and a soul (its character), kept apart;
- an intake filter: subscriptions, rules, dedup, budgets;
- a fixed Kafka consumer group for its role;
- an autonomy level: `off`, `propose`, `act`;
- the Control Panel API, which comes with `konzert-core`;
- configuration seeded through the Helm chart and overridable at runtime
  (`code defaults < seed < runtime`).

## Repository layout

```
core/                         the library every agent is built on
agents/maestro/               the dispatcher
agents/worker/                the worker image (Atlas, Sentinel, and any other worker)
deploy/helm/agent/            one chart, installed once per agent
deploy/kafka/                 a minimal Kafka cluster and the topics
examples/incident-response/   the reference example
examples/minimal-agent/       the smallest agent, as a template
docs/                         concepts, contracts, guides
docs/adr/                     decision records
```

## Stack

Java 25, Quarkus, the plain Kafka client, MCP, Helm, Ansible for the example's
actions. Target platform: OpenShift; plain Kubernetes where an
OpenShift-specific resource is optional.

Do not add a dependency without a reason that fits in a sentence, and do not
add a second way of doing something the core already does.

## Working here

- Build with Java 25: `mvn verify`. Lint charts with `helm lint`.
- Keep modules self-contained: the root `pom.xml` aggregates and is not a
  parent.
- A change to a message format updates [docs/messages.md](docs/messages.md). A
  change to the filter or the configuration API updates
  [docs/agent-control-panel.md](docs/agent-control-panel.md).
- When you change a default that the example relies on, check the example's
  values files and documentation.
- Names, code comments and messages between agents are in English. Text
  addressed to a human operator is in the configured language
  (`konzert.human-language`).
- **This repository is public.** Never write a real key, token, hostname,
  e-mail address or kubeconfig into any file, including tests and
  documentation. Use placeholders. If you find one, stop and report it rather
  than moving it.
- If you could not verify something (the example needs a live cluster), say so
  in the change description. Do not describe untested behaviour as working.
