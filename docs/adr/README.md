# Architecture Decision Records

Every important architectural decision is recorded here. Format: short
Status / Context / Decision / Consequences, one decision per file.

Konzert was incubated inside another repository before it became a project of
its own. ADRs 0001 to 0007 were written there and keep their original numbers
so that code comments and history still point at the right record; 0003 and
0008 were specific to that repository and were not carried over. Each record
was edited for this repository and states what is implemented today.

## Index

- [ADR-0001](0001-kafka-as-collaboration-bus.md) — Kafka as the collaboration bus
- [ADR-0002](0002-event-governance.md) — Event governance: CloudEvents and correlation IDs
- [ADR-0004](0004-soul-vs-system-prompt.md) — Soul vs. system prompt
- [ADR-0005](0005-a2a-protocol.md) — Kafka internally, A2A at the edge
- [ADR-0006](0006-incident-response-example.md) — The incident-response example
- [ADR-0007](0007-agent-intake-filtering.md) — Event intake filtering and the Control Panel
- [ADR-0009](0009-konzert-scope-and-positioning.md) — Konzert as a standalone framework: scope and positioning
- [ADR-0010](0010-scaling-model.md) — Scaling model: one consumer group per agent role
- [ADR-0011](0011-identity-on-the-bus.md) — Identity on the bus: a credential per agent, and signed messages
