# ADR-0001: Kafka as the collaboration bus

## Status

Accepted (2026-07). Implemented in `core` (`KafkaBus`).

## Context

Agents collaborate asynchronously, and several of them often care about the
same fact: an isolated workload matters to the security agent that isolated it
and to the infrastructure agent that owns its neighbours.

An event-sourcing database (KurrentDB, formerly EventStoreDB) was considered.
It makes events the source of truth for domain state, with per-entity streams
and projections. What agents need from each other is *communication*, not
domain-state persistence.

## Decision

Kafka is the one collaboration bus between agents. Agents never call each
other directly.

- Publish/subscribe fan-out matches multi-agent collaboration.
- Consumer groups give horizontal scaling per agent role without new
  machinery (see [ADR-0010](0010-scaling-model.md)).
- A topic is replayable, which gives observers, post-mortems and filter dry
  runs for free.
- Kafka is operator-native on OpenShift (Strimzi / Streams for Apache Kafka).

## Consequences

- One broker to operate, and no point-to-point coupling between agents.
- The implementation uses the plain Kafka client, not a messaging framework,
  so that the consumer loop and its commit behaviour stay explicit.
- If an agent ever needs durable, replayable state of its own, use a compacted
  Kafka topic first; revisit an event-sourcing database only if that proves
  insufficient.
