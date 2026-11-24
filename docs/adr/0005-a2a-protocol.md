# ADR-0005: Kafka internally, A2A at the edge

## Status

Accepted (2026-07). The internal half is implemented. The A2A edge adapter is
**not** implemented.

## Context

A2A (Agent2Agent, under the Linux Foundation) is a point-to-point protocol over
HTTP/JSON-RPC for interoperability between agents of different vendors and
organisations: discovery through Agent Cards, a task lifecycle, request/response
and streaming.

Collaboration inside a Konzert team is publish/subscribe: several agents react
to the same fact. Kafka models that well and A2A does not. Replacing Kafka with
A2A internally would couple the agents point-to-point again.

## Decision

- **Inside a team: Kafka only.** Agents never call each other directly.
- **At the edge: A2A is an optional adapter.** A team may expose an A2A endpoint
  so external agents can join; the adapter translates A2A tasks into Kafka
  messages and back.
- The **Agent Card** is the intended metadata format for a future agent
  registry.

## Consequences

- A2A never becomes a dependency of the core.
- Agent metadata stays interoperable with the wider ecosystem.
