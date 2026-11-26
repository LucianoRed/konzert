# ADR-0012: Installing Konzert — one manifest, an operator, and a console that only writes resources

## Status

Proposed (2026-11). **Nothing in this record is implemented.** The points under
*Decision* were agreed by the maintainer. The points under *Proposed, not yet
agreed* are a recommendation waiting for an answer.

## Context

Installing Konzert today means cloning the repository, building five images
inside the cluster with a script, and running Helm several times. That is fine
for the people who wrote it and a wall for everyone else.

The goal is the opposite experience: one command, then a graphical path. Apply
one manifest, open a page, and install the rest from there, with a wizard for
each thing that can be added: an agent, a tool server, and whatever comes
later.

An installer is also the most dangerous thing a project like this can ship. It
is applied by someone with wide rights over a cluster, from a URL, and it
leaves behind a web page that can create workloads. Each decision below is
shaped by that.

## Decision

1. **One manifest per release.** It is attached to the GitHub release, and it
   refers to every image by digest on `ghcr.io`
   ([releasing.md](../releasing.md)). The documented command names a version,
   not a branch.

2. **The manifest installs two things: an operator and a console.** Nothing
   else. Kafka, the topics, Maestro, agents and tool servers are installed
   afterwards, from the console or with `oc apply`.

3. **The console holds no power of its own.** A wizard produces a custom
   resource, and the operator turns that resource into Deployments, Services,
   configuration and Kafka objects. Everything a wizard can do, a YAML file can
   do, and the wizard shows the YAML it is about to create. This keeps GitOps
   possible and keeps the page from being a second, weaker way in.

4. **No password that the first visitor gets to set.** A page that can install
   workloads and belongs to whoever reaches it first is a takeover waiting to
   happen. How people sign in instead is under *Proposed* below.

5. **Thin slices, operator and console together.** Each slice is one resource,
   its reconciler and its wizard:
   - an installation resource: Kafka (an existing cluster, or one created
     through Strimzi), the three topics, and Maestro;
   - an agent;
   - a tool server;
   - then admission by an administrator and identity on the bus
     ([ADR-0011](0011-identity-on-the-bus.md)).

   This replaces the earlier order, in which the resource and the operator came
   first and the wizard last.

6. **English first, other languages possible.** Every text the console shows
   comes from a message catalogue from the first screen on. No text is written
   into a template or a script.

## Proposed, not yet agreed

- **Sign-in is the OpenShift login.** The console sits behind the cluster's
  OAuth server. There is no user database and no password in Konzert.
- **The console acts as the person who signed in**, with that person's token,
  not with a ServiceAccount of its own. What someone can do in the console is
  then exactly what the cluster's RBAC lets them do, and the audit log shows
  the person rather than the console.
- **The operator is written with the Java Operator SDK on Quarkus**, the stack
  everything else here uses.
- **The console is a Quarkus application that renders its pages on the
  server**, with no JavaScript build chain. A browser application would bring a
  second dependency ecosystem to keep safe.

## Consequences

- **The operator becomes the most privileged component of the project.** Its
  rights must be written out one by one and reviewed, limited to the namespaces
  it manages wherever Kubernetes allows, and never include rights it does not
  use.
- **Applying a manifest from a URL is trusting the release.** Digests make that
  trust exact. Signed images and build provenance, which the release workflow
  does not produce yet, would make it verifiable.
- **Published images come first.** No release has been published, and the
  extracted code has not run on a cluster yet. Both precede the first slice.
- **This touches the scope set in [ADR-0009](0009-konzert-scope-and-positioning.md).**
  That record says Konzert is the collaboration layer and not a platform for
  deploying and governing agents. An installer for Konzert's own components is
  inside that scope. Agent lifecycle, admission and a fleet view lean on the
  edge of it, and the roadmap still asks for an evaluation of existing control
  planes before those are built here.
- **Strimzi stays a prerequisite or becomes something the wizard installs.**
  Installing an operator from the catalogue needs cluster-wide rights that the
  person at the console may not have; the wizard must say so instead of
  failing.

## Open questions

- **The API group of the custom resources.** The package `io.konzert` and the
  label prefix `konzert.io/` use a domain this project does not own. A resource
  definition would make that name part of a public interface, so the name has
  to be settled before the first one is written.
- **Which permissions the sign-in grants** when the console acts for the
  person, and whether that needs a cluster-wide OAuth client.
- **One namespace or several** per installation.
- **Upgrade and removal**: what applying a newer manifest does to what is
  already installed, and what is left behind when it is deleted.
- **Plain Kubernetes**, where there is no OpenShift login.
