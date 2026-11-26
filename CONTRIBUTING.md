# Contributing

Thank you for looking. Konzert is early, and the most useful contributions
right now are running it, telling us where it broke, and closing the gaps in
[ROADMAP.md](ROADMAP.md).

## Build and test

Java 25 and Maven:

```bash
mvn verify                       # everything
mvn -f core/pom.xml test         # one module
helm lint deploy/helm/agent examples/incident-response/deploy/helm/*/
```

Each module has a self-contained `pom.xml`; the root one only aggregates. To
build a module on its own, install the core first:
`mvn -f core/pom.xml install -DskipTests`.

An agent runs outside a cluster with `mvn quarkus:dev` in its module: the
configuration store falls back to JSON files under `/tmp/agent-data`. It still
needs a Kafka broker (`kafka.bootstrap`).

## What a pull request is checked for

Every pull request runs [`.github/workflows/ci.yml`](.github/workflows/ci.yml):

- the build and the unit tests (`mvn verify`);
- `helm lint` on the charts;
- a scan of the whole history for secrets, not only of what the pull request
  adds. If it flags something real, revoke the secret first; removing the
  commit does not un-leak it. If it flags something that is not a secret, say
  so in the pull request and a maintainer decides how to exclude it;
- a review of the dependencies the pull request adds to a `pom.xml`, which
  fails on a known vulnerability of moderate severity or worse. It does not see
  what Maven resolves through the Quarkus BOM, the base images, or the Python
  packages of the Ansible image.

A pull request from a fork runs with a read-only token and no secrets. A
maintainer may have to approve the run for a first-time contributor.

## What a change must include

- **Tests** for logic that decides something: a filter stage, an approval, a
  refusal.
- **Documentation.** Documentation is part of the implementation here. If a
  change alters behaviour, the page that describes that behaviour changes in
  the same pull request. If something is designed but not implemented, the
  documentation says so.
- **Two security checks**, your own and an independent one, and a line in the
  description saying what each covered. How, and what to look for, is in
  [AGENTS.md](AGENTS.md#security-check-twice). The checks the pipeline runs do
  not replace them.
- **An ADR** for an architectural decision: a new component, a new dependency,
  a change to the message contract or to a security boundary. Copy the shape of
  an existing one in [docs/adr/](docs/adr/) and add it to the index.

## The rules a change may not break

They are in [AGENTS.md](AGENTS.md), for people and for coding agents alike. The
short version: agents talk on Kafka only, nothing reaches a model without
passing the intake filter, agents hold no rights over the systems they change,
and every message has a correlation ID.

## Secrets: this repository is public

- Never commit a key, a token, a kubeconfig, a `.env` file, a certificate, or
  the hostname of a real cluster. Not in code, not in a values file, not in a
  test, not in documentation, not in a screenshot.
- Use obvious placeholders in examples.
- Check before you push:

  ```bash
  git diff --cached | grep -nEi 'api[_-]?key|token|secret|password|bearer|BEGIN .*PRIVATE'
  ```

- If a secret is committed, revoke it first and then tell the maintainers.
  Rewriting history does not un-leak it.

## Style

- Java: the style of the file you are editing. Comments explain *why*, not
  what.
- Messages on the bus and names in code are in English.
- Markdown: wrap at about 80 columns, relative links, no trailing whitespace.

## Pull requests

Small and focused. Say what changed, why, and how you checked it. If you could
not run something (the example needs a cluster), say that too.

By contributing you agree that your contribution is licensed under the
[Apache License 2.0](LICENSE).
