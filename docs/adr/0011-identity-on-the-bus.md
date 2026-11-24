# ADR-0011: Identity on the bus — a credential per agent, and signed messages

## Status

Accepted (2026-11). **Nothing in this record is implemented.** The shipped
Kafka configuration still has a plaintext listener and no authentication
([SECURITY.md](../../SECURITY.md)).

## Context

Two things have to become true before a team of agents can be trusted with
anything that matters:

- **only an admitted agent takes part**: something that was never approved
  cannot read the conversation or speak in it;
- **nobody speaks as someone else**: a message that says `from: human` was
  written by the component that represents people, and one that says
  `from: maestro` was written by Maestro.

The second one matters most where it is weakest today. Approval is a message
with `from: human` ([messages.md](../messages.md)), and nothing proves who
wrote it.

An idea that comes up first is to encrypt every message with one key, held by
the dispatcher and handed to each agent that joins. It was considered and
rejected:

- A shared key proves membership of the group and nothing else. Every member
  can produce a valid message in any other member's name, including `human`.
  It does not help against the case worth defending against, an agent that was
  compromised or steered by an injected prompt.
- Removing one agent means changing the key for all of them.
- The dispatcher becomes the distributor of keys. Today it has no tools and no
  rights on purpose; this would give it one.
- Observers stop being free. A panel or an audit sink that today only needs to
  read the topic would need the key as well.
- Confidentiality in transit does not need it: TLS on the Kafka listener gives
  that without touching the messages.

Kafka's own access control solves the first requirement and cannot solve the
second. An ACL grants an operation on a resource, such as writing to a topic.
It says nothing about the content of a record. Agents and people write to the
same conversation topic, so an ACL that lets an agent speak there also lets it
write `from: human`. And a consumer has no way to ask Kafka who produced a
record: the broker authenticates the producer and does not pass that identity
on.

## Decision

Identity on the bus has two layers, built in this order.

1. **A Kafka credential per agent role.** The listener requires TLS and
   authentication. Each role has its own Kafka user (a Strimzi `KafkaUser`),
   with ACLs for the topics and the consumer group its role needs and nothing
   else. A component with no credential does not connect. Revoking an agent is
   deleting its user, and touches nobody else.

2. **A signature on every message.** Each participant (an agent, a sensor, the
   component that represents people) has its own signing key pair. It signs
   what it publishes; the signature and the identifier of the key travel as
   CloudEvents extension attributes. The public keys of the participants that
   were admitted are published where every agent can read them and only an
   administrator can change them.

   Verification is the first step of the intake pipeline
   ([ADR-0007](0007-agent-intake-filtering.md)), before any rule and before any
   model: the signature must be valid, and the key must belong to the
   participant named in `from` (for a conversation turn) or in `source` (for a
   fact). A message that fails is dropped, counted in the funnel with its
   reason, and costs zero tokens.

   A setting chooses between not verifying, verifying and only reporting, and
   enforcing, so an existing installation can turn it on in steps.

**Payload encryption is not part of this decision.** It may be added later for
individual sensitive fields, if the threat is someone with access to the
brokers or their disks. It would use a key per recipient, not a shared one.

Admission by an administrator ([ROADMAP.md](../../ROADMAP.md), control plane) is
what ties the two layers together: approving an agent is what creates its Kafka
user and publishes its public key.

## Consequences

- "Needs a key to take part" becomes true per agent, and each key can be
  revoked alone.
- A forged approval needs the private key of the component that represents
  people, not just a network path to the brokers.
- Observers keep working with a read-only Kafka user and no signing key. They
  can verify signatures, since the public keys are readable.
- The dispatcher gains no new power. It verifies and signs like everyone else.
- **This is worth little while agents can read each other's Secrets.** An
  agent's Role lets it read every Secret in its namespace
  ([SECURITY.md](../../SECURITY.md)). Private keys and Kafka credentials would
  be among them. Either each agent gets a namespace of its own, or that
  limitation is removed first.
- Who the person behind an approval was is still the job of the component that
  represents people. The signature proves that component wrote the message; it
  must authenticate its users and record who approved.
- Every message costs a signature to produce and a verification per consumer.
  This has not been measured.

## Open questions

These must be settled, in this record or a later one, before code is written.

- **What exactly is signed.** A JSON envelope can be re-serialised on the way,
  so the signature needs a defined byte sequence: the fields it covers, in
  which order, and how `data` is represented.
- **Replay.** A signed message stays valid if someone with write access
  publishes it again. An approval should name the message it answers, and the
  signature should cover that reference, the correlation ID and the time, so an
  old "approved" cannot be reused for a new plan.
- **Where the public keys live** until there is an agent registry: probably a
  ConfigMap an administrator owns, later the `Agent` resource.
- **Key rotation**: how long an old key stays valid for messages already on the
  topic, which matters when an incident is read back later.
- **The algorithm and how keys are generated and stored.**
