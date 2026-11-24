# ADR-0010: Scaling model — one consumer group per agent role

## Status

Accepted (2026-11). The mechanism is implemented. Durable per-incident state is
**not**, and until it is, running more than one replica of an agent is
experimental.

## Context

"More agents" means two different things:

- **more roles**: a new kind of agent (a cost agent next to the infrastructure
  and security ones);
- **more replicas**: several instances of the same role, because one cannot
  keep up.

Both must work without the agents knowing about each other, and without two
replicas of the same role working the same incident.

## Decision

1. **A role is a Kafka consumer group.** Every replica of an agent uses the same
   fixed `group.id`. Different roles use different groups, so each role sees
   every message and filters it with its own intake rules.
2. **A record's key is its correlation ID.** One incident always lands on the
   same partition of a topic, in order.
3. **Topics that carry the same story are co-partitioned.** They have the same
   number of partitions, and the consumer is pinned to the range assignor,
   which gives partition *N* of every subscribed topic to the same replica.
   Together with rule 2, one incident is handled by exactly one replica of each
   role, across topics.
4. **Observers do not join a role's group.** A panel or an audit sink uses its
   own group and sees everything.

Adding a role needs no change to Kafka. Adding replicas needs partitions: a role
can have at most as many working replicas as the topics have partitions.

## Consequences

- Scaling a role is `replicas: N`, with no coordination service and no leader
  election.
- A broadcast (`to: all`) reaches one replica of every role: the one that owns
  the incident's partition.
- **Work is distributed by partition, not by load.** The limit of incidents in
  flight (`maxConcurrentIncidents`) is per replica. Two incidents whose keys
  hash to partitions owned by the same replica contend for that replica even
  while others are idle.
- **Per-incident state is in memory today.** The conversation thread, the
  evidence and a plan waiting for approval live in the replica. A rebalance or
  a restart moves the partition and loses them: the incident stalls, because
  the replica that inherits it does not know it exists. This must be fixed (rebuild from the topic, or keep the state
  in a compacted topic) before multiple replicas are relied upon.
- **Delivery is at most once for a turn in progress.** Offsets are committed
  automatically while a turn is still being reasoned about, so a crash drops
  that turn rather than replaying it. Chosen deliberately: replaying a turn can
  repeat an action.
- **Every role reads the whole conversation.** That is fine for one team and
  wasteful for thousands of roles. Larger deployments need several topic sets
  ("rooms"), which in turn need a registry of which agent sits in which room.
  Neither exists yet.

See [scaling.md](../scaling.md) for the operational view.
