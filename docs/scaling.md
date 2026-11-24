# Scaling

How Konzert scales, what you can rely on today, and what is still missing. The
decision record is [ADR-0010](adr/0010-scaling-model.md).

## Two ways to grow

**More roles.** A new kind of agent. It needs nothing from Kafka: give it its
own consumer group and its own intake rules, and it starts hearing the same
topics as everyone else. Each role sees every message and decides for itself,
with zero tokens, what deserves a model call.

**More replicas.** Several instances of the same role. They share one consumer
group, so Kafka splits the topic's partitions between them, and each incident
is handled by exactly one of them.

```
                         konzert.conversation (6 partitions)
                    ┌──────┬──────┬──────┬──────┬──────┬──────┐
                    │  p0  │  p1  │  p2  │  p3  │  p4  │  p5  │
                    └──────┴──────┴──────┴──────┴──────┴──────┘
 group konzert-atlas     atlas-0: p0 p1 p2      atlas-1: p3 p4 p5        2 replicas, each owns 3
 group konzert-sentinel  sentinel-0: p0 … p5                             1 replica, owns all 6
 group konzert-maestro   maestro-0: p0 … p5
 (random group)          console: p0 … p5                                an observer sees everything
```

## Why one incident stays on one replica

Three things, all required:

1. **Every record is keyed by its correlation ID.** `KafkaBus.publish` does
   this; an incident always lands on the same partition of a topic.
2. **The topics are co-partitioned.** `konzert.events`, `konzert.conversation`
   and `konzert.actions` must have the same number of partitions.
3. **The consumer uses the range assignor.** It gives partition *N* of every
   subscribed topic to the same member of the group. `KafkaBus` pins it.

Break any of the three (publish with another key, give the topics different
partition counts, change the assignor) and a replica can receive the sensor's
finding for an incident whose conversation another replica is holding.

## How to scale a role

1. Make sure the topics have at least as many partitions as the replicas you
   want. A role can have at most one working replica per partition. Raising a
   topic's partition count changes which partition a key maps to, so do it
   while no incident is open.
2. Set `replicas` in the agent's values file. The `kafka.groupId` stays the
   same; that is what makes them one role.

```yaml
# values-atlas.yaml
replicas: 3
kafka:
  groupId: konzert-atlas
```

## What you can rely on today, and what you cannot

Running **one replica per role** is what the reference example does and what
has been exercised. Several replicas of a role is **experimental**, for these
reasons:

**Per-incident state is in memory.** The conversation thread, the forwarded
evidence, and a plan waiting for a human's approval live inside the replica
that owns the incident. When a replica restarts or the group rebalances, the
partition moves and the new owner knows nothing about the incident. An approval
that arrives afterwards is treated as a question. The fix is to make that state
rebuildable from the topic, or to keep it in a compacted topic; neither is done.

**Work is distributed by partition, not by load.** `maxConcurrentIncidents` is
enforced per replica. Two incidents whose correlation IDs hash to partitions
owned by the same replica contend for it even while its siblings are idle. With
the default of one incident in flight, the second is counted and not worked on.

**A turn in progress is not replayed.** Offsets are committed automatically
while a turn is still being reasoned about. A crash drops that turn. This is
deliberate: replaying a turn could repeat an action.

**Funnel counters and the recent-verdict list are per replica.** A Service in
front of three replicas answers `GET /api/config/intake/stats` from whichever
one it picks. Configuration is not affected: every replica watches the same
objects.

**Budgets are per replica.** Three replicas with `llmCallsPerHour: 60` can make
180 calls an hour between them.

## Towards thousands of agents

The mechanism above scales replicas. Scaling the number of *roles* far beyond a
team runs into two things the framework does not have yet:

**Rooms.** Every role reads the whole conversation topic and filters it. That is
exactly right for a team of five and wasteful for five thousand roles, most of
which have nothing to say to each other. The answer is several sets of topics,
one per team or domain, with a dispatcher in each.

**A registry.** A dispatcher today knows its team from configuration
(`konzert.team`) and from its prompt. With rooms and many roles, something has
to record which agents exist, what each one does, which room it sits in, and
whether an administrator admitted it. That registry is also what an
administrator's approval, and Kafka ACLs per agent, would hang on.

Both are on the [roadmap](../ROADMAP.md).
