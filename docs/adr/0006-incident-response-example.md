# ADR-0006: The incident-response example

## Status

Accepted (2026-08). Implemented in `examples/incident-response`.

## Context

A framework for collaborating agents needs a reference example that is real:
several specialised agents working an incident on a live cluster, with a person
able to watch and to stop them. It also has to be short enough to show in a few
minutes to someone who has never heard of MCP.

## Decision

A self-contained example: three LLM agents (dispatcher, infrastructure,
security), a sensor with no LLM, an Ansible MCP server and a panel, resolving
two real incidents.

1. **The conversation is Kafka**, not point-to-point HTTP
   ([ADR-0005](0005-a2a-protocol.md)). The panel is a passive observer, replay
   is free, and one agent can react to a broadcast meant for the room.
2. **The agents hold no rights over workloads.** All mutation goes through the
   Ansible MCP server, whose playbook catalogue is a closed allow-list baked
   into its image. "The security agent cannot patch a Deployment" is enforced
   by RBAC, not by a prompt.
3. **Remediation is Ansible, and the playbook output is visible.** The MCP
   server is designed for pluggable backends; only `local`
   (`ansible-playbook` in the pod) exists today.
4. **The sensor is a separate component with no LLM.** Detection is a
   correlation of a live metric with an omission in the spec, or a log pattern.
   Token cost is zero until something actually breaks.
5. **Loop control is intake configuration, not prompt instructions**: a turn
   budget per correlation ID, one incident in flight per agent, a dedup window,
   call and token budgets, LLM timeouts with a deterministic fallback line. It
   is the framework's intake filter ([ADR-0007](0007-agent-intake-filtering.md)),
   not example-specific code.
6. **A mutating action needs a human by default.** Workers ship in `propose`
   mode; the approval is a Kafka message matched without a model.

## Consequences

- A second image to maintain, with `ansible-core` and `kubernetes.core`.
- The dispatcher and the worker carry incident-response defaults in their
  shipped prompts. They are overridable through configuration, but a team in
  another domain has to supply its own.
