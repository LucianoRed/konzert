# Extraction notes

The state of this repository after the first extraction, on 2026-11-23. Read
this before continuing the work: it says what was changed, what was verified,
what was not, and what to do next. Delete or archive it once the checklist at
the end is done.

## Where the code came from

Konzert was incubated inside another repository, as the plumbing underneath a
demo of three agents resolving incidents on OpenShift. That demo ran end to end
on a real cluster.

The reusable parts were copied here as files, without git history, into a new
layout and under new names (packages, topics, labels, images, namespace). The
copy was mechanical where possible and reviewed by hand where it touched
prompts, user-facing text or documentation.

## What changed in behaviour during the extraction

Renaming is not supposed to change behaviour. These changes do, and each one is
a candidate cause if the example behaves differently from the original demo:

| Change | Where | Why |
|---|---|---|
| The three topics have 6 partitions instead of 1 | `deploy/kafka/topics.yaml` | lets an agent role run several replicas ([ADR-0010](adr/0010-scaling-model.md)) |
| The consumer is pinned to the range assignor | `core` `KafkaBus` | keeps one incident on one replica across co-partitioned topics |
| The language of text addressed to the operator is configurable, default English | worker `konzert.human-language` | it was fixed in the prompt before |
| The team roster is configuration (`konzert.team`) | Maestro and worker | adding a member must not need a new image |
| A worker refuses a playbook the catalogue does not assign to it | worker `refuseIfNotOwned` | staying in its lane was only a prompt instruction before |
| The agent chart creates no Route by default | `deploy/helm/agent/values.yaml` | the Control Panel API has no authentication |
| The agent chart takes `replicas` and extra `env` | `deploy/helm/agent` | scaling, and settings such as `KONZERT_NAMESPACE` |
| The model key is handed to `oc` on standard input | `hack/install.sh` | it was visible in the process list before |
| `install.sh -n <namespace>` rewrites the namespace in Maestro's filter and sets `KONZERT_NAMESPACE` on the workers | `hack/install.sh` | a non-default namespace silently did not work |
| Image and release names carry a `konzert-` prefix | charts, `hack/` | consistency |
| The panel is in English | example console | public repository |
| Maestro's fallback line to a person is in English | Maestro | same |
| Everything compiles for and runs on Java 25 instead of 17 | every `pom.xml` and `Dockerfile.jvm` | the original demo ran on Java 17 images |
| The panel escapes quotes, and escapes the playbook counters and the reasoning length | example console, `index.html` and `team.html` | a crafted message on the bus, or a model steered by a log line, could run script in the operator's browser, where approvals are given |
| `release-workload.yml` with a `policy_name` deletes it only if it is a quarantine policy | example `mcp-ansible` | it deleted any NetworkPolicy of that name in the namespace |
| Every Dockerfile names its base image by digest | every `Dockerfile.jvm` | a tag can be moved after a release was built from it |

Added, not carried over: `examples/minimal-agent`, the unit test
`ApprovalVerdictTest`, the root aggregator `pom.xml`, and most of the
documentation.

## What was verified

- `mvn verify` with Java 25, after the move from Java 17: seven modules build,
  14 unit tests pass (10 for the intake pipeline, 4 for the approval verdict).
- The base image tag the Dockerfiles name exists in the registry.
- `release-workload.yml` passes `ansible-playbook --syntax-check`, and its new
  filter expression was evaluated against a sample list of policies. The
  scripts of the two panel pages parse.
- `helm lint` on the four charts, and `helm template` of the agent chart with
  each values file.
- The four `hack/` scripts parse (`bash -n`).
- `oc create secret ... --from-file=llm-api-key=/dev/stdin` produces the right
  value in a client-side dry run.
- Every relative link in the Markdown files resolves.
- A scan for credentials, hostnames, e-mail addresses and names of the original
  environment found nothing.

## What was NOT verified

**Nothing in this repository has been run on a cluster.** In particular:

- no image was built from this layout (`hack/build-all.sh`,
  `hack/build-layered.sh`);
- `hack/install.sh` and `hack/reset.sh` were never executed, only parsed;
- the example was never run with the renamed topics, six partitions, the
  English prompts or the new refusal path;
- the minimal agent was compiled, never deployed;
- nothing has run on a Java 25 image: the demo that worked ran on Java 17;
- the panel changes were never exercised in a browser, and the changed
  playbook never ran against a cluster.

## What to watch on the first cluster run

1. **Images and names.** Five `konzert-*` image streams after `build-all.sh`;
   the values files and `install.sh` must agree on them.
2. **Maestro's filter after `install.sh`.** The script pipes the values file
   through `sed` into `helm -f -`. Check the `sensor findings` rule in
   `maestro-config` names the namespace you installed into.
3. **Ordering on the panel with six partitions.** Order is guaranteed per
   incident, not across incidents. The panel sorts history by timestamp; watch
   the live stream when two incidents overlap.
4. **The refusal path.** If a worker answers "not in my catalogue" for a
   playbook it does own, the model is naming the playbook differently from the
   catalogue (for example without `.yml`). Before this change the MCP server
   would have rejected the same name.
5. **English towards the operator.** The prompts were tuned with Portuguese as
   the operator's language. Set `KONZERT_HUMAN_LANGUAGE` on the workers to
   compare.
6. **No Route for the agents.** Reach the Control Panel API with
   `oc port-forward`.
7. **A second incident while one is open** is counted, not worked on
   (`maxConcurrentIncidents: 1`). That was already so; it is now documented.

## Decisions waiting for the maintainer

- **The namespace `io.konzert` and the label prefix `konzert.io/`.** They were
  chosen without checking who owns the domain. A lookup on 2026-11-24 shows
  `konzert.io` registered to someone else since 2024. Changing the names is one
  global replacement, cheap now and expensive after a release, and it has to
  happen before a custom resource makes the name a public interface
  ([ADR-0012](adr/0012-installation-operator-and-console.md)).
- **How to make the first commit.** Everything except the license is
  uncommitted. One initial commit, or one per area (core, agents, example,
  docs).
- **Repository settings.** [SECURITY.md](../SECURITY.md) points to GitHub's
  private vulnerability reporting, which has to be switched on in the
  repository settings. Secret scanning and push protection are worth enabling
  at the same time.
- **Who the operator of the first cluster run is, and on which cluster.**

## Next steps

- [ ] Review the working tree and make the first commit.
- [ ] Enable private vulnerability reporting, secret scanning and push
      protection on the repository.
- [ ] Run the incident-response example from this repository on a throwaway
      cluster, both incidents, and fix what the list above turns up.
- [ ] Record the result in the example's install guide and update the status
      note at the top of the [README](../README.md).
- [ ] Add continuous integration: `mvn verify`, `helm lint`, a secret scanner.
- [ ] Continue with [ROADMAP.md](../ROADMAP.md), starting with the security
      gaps.
