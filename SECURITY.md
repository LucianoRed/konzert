# Security

Konzert is early, experimental software. This page says what it does not
protect against, so that nobody has to find out by accident, and how to report
a problem.

## Reporting a vulnerability

Please do not open a public issue for a security problem. Use GitHub's
[private vulnerability reporting](https://docs.github.com/en/code-security/security-advisories/guidance-on-reporting-and-writing-information-about-vulnerabilities/privately-reporting-a-security-vulnerability)
on this repository (the **Security** tab, then **Report a vulnerability**).
Include what you found, how to reproduce it, and what an attacker could do with
it.

This is a community project maintained on a best-effort basis. There is no
guaranteed response time.

## What the design does give you

- **Agents hold no rights over the systems they act on.** An agent's
  ServiceAccount can only write its own two configuration objects. Changes go
  through an MCP server with a closed catalogue of actions.
- **A worker runs only what the catalogue assigns to it**, and in the default
  `propose` mode a mutating action waits for a person.
- **Default deny in front of every model**, with budgets enforced in code.
- **Keys are kept apart from configuration**: in a Secret, masked on every read,
  never logged, with no field for them in the Helm chart.

## Known limitations

Treat every item here as true until the [roadmap](ROADMAP.md) says otherwise.

### The Control Panel API has no authentication

Every agent serves `/api/config` on its HTTP port with no authentication and no
authorization. Anyone who can reach that port can read the agent's prompt and
soul, point it at a different model endpoint, replace its key, rewrite its
intake filter, or switch it from `propose` to `act`.

- The agent chart does **not** create a Route by default. Leave it that way, or
  put an authenticating proxy in front.
- Inside the cluster, restrict who can reach the agent's Service with a
  NetworkPolicy.

### The example's panel has no authentication

The Situation Room of the incident-response example can inject faults, post
messages as `human` and therefore **approve a playbook**. Its chart does create
a Route, because a panel nobody can open is no demo. Anyone who can reach that
Route is the operator. Run the example on a throwaway cluster or behind an
authenticating proxy.

### Anyone who can write to the conversation topic is "human"

Approval is a message with `from: human`. Nothing proves who wrote it. The
shipped Kafka configuration has a plaintext listener and no authentication, so
any pod that can reach the brokers can publish an approval, a delegation, or a
fact. Real use needs Kafka authentication with a credential per agent, and
signed messages, so that only the component that represents people can write as
`human`. Authentication and ACLs alone do not give that: agents and people
write to the same topic, and an ACL does not look inside a record. The design
is in [ADR-0011](docs/adr/0011-identity-on-the-bus.md). None of it is
implemented.

### The tool server does not authenticate its callers

The example's MCP server runs any playbook in its catalogue for any caller that
can reach it. The worker enforces which playbooks it may run; the server does
not check who is asking. Restrict access to it with a NetworkPolicy.

### An agent can read every Secret in its namespace

An agent watches its configuration objects, and Kubernetes cannot restrict
`list` and `watch` by object name. An agent's Role therefore lets it read all
ConfigMaps and Secrets in its namespace, including the keys of other agents
that share it. Give agents that must not see each other's keys a namespace
each, and never run an agent in a namespace with unrelated secrets.

### Prompt injection is in scope for you, not solved by this project

Agents read text produced by other agents, by people and by the systems they
watch (log lines become evidence). A crafted log line or message can try to
steer a model. Konzert limits the blast radius (a closed catalogue, parameters
checked against an allowed namespace, approval by default) and does not prevent
the attempt. Do not put a capability in a catalogue that you would not want
invoked with hostile arguments.

### Intake rules can contain regular expressions

The `matches` operator compiles a pattern from the configuration. Input is
capped at 4096 characters but there is no match timeout, so a pathological
pattern can burn CPU. Rules come from configuration, which is one more reason
to protect the Control Panel API.

### State and delivery

Per-incident state is in memory and a turn in progress is not replayed after a
crash. These are availability problems rather than confidentiality ones, but
they mean an incident can stall. See [docs/scaling.md](docs/scaling.md).

## Handling credentials in this repository

This repository is public. Nothing that is a secret, or that identifies a real
environment, belongs in it.

- A model key lives in a Kubernetes Secret (`<agent>-secrets`, key
  `llm-api-key`). Create it from an environment variable on standard input, as
  the install script does; do not pass it on a command line and do not put it
  in a values file.
- Do not commit kubeconfigs, tokens, `.env` files, certificates, or cluster
  hostnames. [.gitignore](.gitignore) blocks the common file names; it is a
  safety net, not a control.
- Examples and tests use obvious placeholders.
- If a secret is committed by mistake, **revoke it first**, then remove it.
  Removing a commit does not un-leak a key.
