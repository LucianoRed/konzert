# Roadmap

What is planned, in the order it is likely to happen. Nothing here is a
promise or a date. Each item becomes an ADR before it becomes code.

## 1. Make the extracted code trustworthy

- Re-run the incident-response example end to end from this repository and
  record the result in its install guide. What to watch for is listed in
  [docs/extraction-notes.md](docs/extraction-notes.md).
- Continuous integration: build, unit tests, chart lint, secret scanning.
- Tests for the dispatcher and the worker around the paths that matter most:
  approval, refusal of a playbook outside the catalogue, loop control.

## 2. Close the security gaps listed in SECURITY.md

- Authentication and authorization on the Control Panel API.
- Authentication on the example's panel.
- Kafka authentication and per-agent ACLs (Strimzi `KafkaUser`), so that only
  an admitted agent connects, and it can only read and write what its role
  needs.
- Signed messages, verified by the intake filter before any rule, so that
  nobody can speak as another agent or as `human`. An ACL cannot do this: it
  controls who writes to a topic, not what a record says.
  ([ADR-0011](docs/adr/0011-identity-on-the-bus.md))
- Caller authentication on the MCP server, and an evaluation of putting an MCP
  gateway in front of tool servers.

## 3. Finish what the intake filter already promises

- The `triage` lane: a cheap model that decides whether to escalate.
- Deterministic handlers: coded reactions with no model.
- Debounce: hold related messages and reason once about the batch.
- `onExceed: queue`, with a bounded depth.

## 4. Scale a role for real

- Durable per-incident state, rebuilt from the topic or kept in a compacted
  topic, so a rebalance or a restart does not lose an open incident or a
  pending approval.
- Funnel counters and budgets that make sense across replicas.
- A load test, and published numbers.

See [docs/scaling.md](docs/scaling.md).

## 5. A registry, and more than one team

- An **agent registry**: which agents exist, what each one does, which topics
  it sits on. The dispatcher builds its roster from it instead of from a prompt.
  The Agent Card of the A2A protocol is the candidate metadata format.
- **Rooms**: several sets of topics, one per team or domain, each with its own
  dispatcher.
- Domain-neutral defaults for the dispatcher and the worker; today their
  shipped prompts describe an incident team.

## 6. A control plane on OpenShift

- An **`Agent` custom resource** and an operator that reconciles it into what
  the Helm chart creates today.
- **Admission by an administrator**: a new agent does not get its Deployment or
  its Kafka credentials until someone with the right to do so approves it. The
  approval is a Kubernetes object, so the audit log records who and when.
- A **fleet view**: every agent, its autonomy, its funnel, its cost.
- A **creation wizard** in the OpenShift console that produces the `Agent`
  resource, and a web UI for each agent's Control Panel.

Before building this, evaluate delegating agent lifecycle and governance to an
existing open-source control plane rather than writing another one
([ADR-0009](docs/adr/0009-konzert-scope-and-positioning.md)).

## 7. Integrations

- **Agent sandboxing.** Find out whether a long-running Konzert agent can run
  inside an agent sandbox runtime on OpenShift, and what its network policy has
  to allow (the Kafka brokers, the model endpoint, the tool server). This is a
  spike first; it is not known to work.
- **Ansible Automation Platform** as a backend for the Ansible MCP server.
- **A schema registry** for the message contract
  ([ADR-0002](docs/adr/0002-event-governance.md)).
- **An A2A edge adapter**, so an external agent can join a team
  ([ADR-0005](docs/adr/0005-a2a-protocol.md)).
- **LangChain4j** for model access and tool calling, where it replaces code the
  core carries today.
