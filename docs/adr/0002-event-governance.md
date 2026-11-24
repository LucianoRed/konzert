# ADR-0002: Event governance — CloudEvents and correlation IDs

## Status

Accepted (2026-07). The envelope and the correlation ID are implemented. The
schema registry is **not** implemented.

## Context

With several agents exchanging messages, unstructured payloads make the system
impossible to observe, debug or explain. Governance is far cheaper from day one
than retrofitted.

## Decision

- Every message uses the **CloudEvents** envelope (`specversion`, `type`,
  `source`, `id`, `time`, `data`).
- Every message carries a **correlation ID** (the CloudEvents extension
  `correlationid`) that links one whole story across agents, from the first
  detected fact to the close. It is also the Kafka record key.
- Event type names are past-tense facts: `MemoryPressureDetected`,
  `PlaybookExecuted`.
- Producing a message without a correlation ID is a bug.
- Event schemas should be registered in a schema registry (Apicurio Registry),
  so agents can evolve independently as long as schemas stay compatible.

## Consequences

- Any observer can reconstruct the full story of an incident from the topics.
- The correlation ID doing double duty as the record key is what makes the
  scaling model of [ADR-0010](0010-scaling-model.md) possible.
- Slightly more ceremony per message. Accepted.
- Until the registry exists, the contract is the documentation in
  [messages.md](../messages.md) and nothing enforces it.
