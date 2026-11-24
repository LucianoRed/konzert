# Building an agent

There are two ways to add an agent to a Konzert team. Start with the first.

| | You write | Use it when |
|---|---|---|
| [A worker by configuration](#a-worker-by-configuration) | a soul, a values file, playbooks | the agent owns a domain and acts through playbooks |
| [An agent in code](#an-agent-in-code) | one Java class on `konzert-core` | the agent does something the worker image does not |

Whichever you choose, the agent gets the same things from `konzert-core`: the
Kafka consumer, the intake filter, the LLM client, runtime configuration, and
the Control Panel API.

## What every agent has

- **A name**, which is also the name of its Kubernetes objects.
- **A role**: one word for what it owns.
- **A soul**: who it is. A Markdown file. See
  [ADR-0004](adr/0004-soul-vs-system-prompt.md).
- **A system prompt**: what its job is and how it must answer.
- **An intake filter**: which messages may reach its model. Default deny.
- **A consumer group**: fixed, one per role.
- **An autonomy level**: `off`, `propose` or `act`.
- **A key for its model**, in a Secret named `<name>-secrets`.

## A worker by configuration

The worker image ([agents/worker](../agents/worker)) is an agent that listens
to the conversation, reasons, and acts by running Ansible playbooks through an
MCP server. In the incident-response example, Atlas and Sentinel are both this
image. Here is what it takes to add a third, **Ledger**, a cost agent.

### 1. Give it something it can do

A worker can only run playbooks from the MCP server's closed catalogue. Add
the playbook to `examples/incident-response/mcp-ansible/ansible/` and an entry
to
[`PlaybookCatalog`](../examples/incident-response/mcp-ansible/src/main/java/io/konzert/examples/incident/ansible/PlaybookCatalog.java):

```java
new Playbook(
        "report-idle-workloads.yml",
        "report-idle-workloads.yml",
        "ledger",                                   // the agent that owns it
        "Read-only: list Deployments whose pods used under 5% of their CPU request.",
        List.of("namespace"),                       // required vars
        List.of(),                                  // optional vars
        false)                                      // false = read-only, needs no approval
```

`owner` decides which worker is shown the playbook, and a worker refuses to run
one it does not own. `mutating` decides whether `propose` mode asks a human
first. If the playbook touches something new, widen the MCP server's Role to
match: that Role, not the agent, is what holds the rights.

### 2. Write its soul

`souls/ledger.md`, a short description of its character and boundaries. Copy
the shape of [atlas.md](../examples/incident-response/souls/atlas.md).

### 3. Write its values file

Copy
[`values-atlas.yaml`](../examples/incident-response/deploy/helm/values-atlas.yaml)
and change what makes it Ledger:

```yaml
name: ledger
kafka:
  groupId: konzert-ledger          # its own consumer group
agentConfig:
  identity:
    name: ledger
    displayName: Ledger
    emoji: "🧾"
    role: cost
  autonomy: propose
intake:
  selfName: ledger
  subscriptions:
    - topic: konzert.conversation
      types: [io.konzert.MessagePosted]
  rules:
    - name: addressed to me
      when:
        any:
          - { field: data.to, op: contains, value: ledger }
          - { field: data.to, op: contains, value: all }
      then: reason
    - name: everything else
      then: count
```

The worker's default system prompt describes an incident team with Atlas and
Sentinel. For a different team, set `agentConfig.systemPrompt` in the values
file. Keep the `HOW YOU ANSWER` section of the
[default](../agents/worker/src/main/java/io/konzert/worker/Prompts.java): it is
the JSON contract the worker parses. The placeholders `%NAME%`, `%DISPLAY%`,
`%ROLE%`, `%NAMESPACE%`, `%WORKLOADS%`, `%AUTONOMY%`, `%HUMAN_LANGUAGE%` and
`%ADDRESSEES%` are filled in for you.

### 4. Tell the team it exists

The dispatcher and the workers take the list of team members from
configuration:

```yaml
# in every agent's values file
env:
  KONZERT_TEAM: atlas,sentinel,ledger
```

Maestro also has to know *when* to delegate to Ledger. That is part of its
system prompt: add a line under `THE TEAM` and a rule under `WHAT YOU DO` in
`agentConfig.systemPrompt` of `values-maestro.yaml`, starting from the
[default](../agents/maestro/src/main/java/io/konzert/maestro/Prompts.java).

This step is manual today. An agent registry that makes the roster dynamic is
on the [roadmap](../ROADMAP.md).

### 5. Install it

```bash
printf '%s' "$LLM_API_KEY" | oc create secret generic ledger-secrets -n konzert-demo \
  --from-file=llm-api-key=/dev/stdin

helm upgrade --install ledger deploy/helm/agent -n konzert-demo \
  -f values-ledger.yaml \
  --set image.repository=image-registry.openshift-image-registry.svc:5000/konzert-demo/konzert-worker \
  --set-file agentConfig.identity.soul=souls/ledger.md
```

No new image was built for the agent: only the MCP server changed, because that
is where the new capability lives.

## An agent in code

When the worker's shape does not fit, an agent is one class. The complete,
compiling example is [examples/minimal-agent](../examples/minimal-agent); the
part that matters:

```java
@ApplicationScoped
public class MinimalAgent {

    @Inject AgentRuntime runtime;   // bus -> intake filter -> you
    @Inject ConfigStore store;      // identity, soul, prompt, model: live configuration
    @Inject LlmClient llm;          // any OpenAI-compatible endpoint
    @Inject KafkaBus bus;
    @Inject ObjectMapper mapper;

    private final ExecutorService turns = Executors.newSingleThreadExecutor();

    void onStart(@Observes StartupEvent ev) {
        // The handler only ever sees what the intake filter admitted.
        runtime.start((topic, event) -> turns.submit(() -> answer(event)));
    }

    private void answer(JsonNode event) {
        String correlation = event.path("correlationid").asText("");
        JsonNode data = event.path("data");
        AgentConfig cfg = store.config();

        LlmClient.Reply reply = llm.chat(List.of(
                LlmClient.Message.system(cfg.systemPrompt + "\nWHO YOU ARE\n" + cfg.identity.soul),
                LlmClient.Message.user(data.path("from").asText() + " says: " + data.path("text").asText())));

        ObjectNode out = mapper.createObjectNode();
        out.put("from", cfg.identity.name);
        out.putArray("to").add(data.path("from").asText());
        out.put("intent", "answer");
        out.put("turn", runtime.incidents().turn(correlation));
        out.put("text", reply.ok() ? reply.content().trim() : cfg.model.fallbackText);

        bus.publish("konzert.conversation", CloudEvent.of(
                "io.konzert.MessagePosted", "/konzert/agents/" + cfg.identity.name, correlation, out));
    }
}
```

### What the library does for you

- Subscribes to the topics your intake configuration names, in your consumer
  group.
- Runs every message through the filter and hands you only what was admitted.
- Counts everything, so `GET /api/config/intake/stats` is true without any work
  from you.
- Reloads configuration when its Kubernetes objects change.
- Records token usage against the budgets.
- Keys every record you publish by its correlation ID.

### What is yours to get right

- **Do not block the handler.** It is called on the Kafka thread. Hand the work
  to an executor, as above. A single-threaded one keeps an incident's turns in
  order.
- **Call `runtime.incidents().open(id)` and `close(id)`** if your agent works on
  stories that span several messages. The turn budget and the limit of
  concurrent incidents are enforced against what you opened.
- **Never publish a failed model call as an answer.** Check `reply.ok()` and use
  the configured fallback text.
- **Keep the stable part of the prompt first**: system prompt, then soul, then
  the volatile event. The provider's prompt cache depends on it.
- **Do not answer an `ack` or an `observe`.** That is how conversations loop.
- **Act through MCP.** If your agent needs to change something, put the
  capability in an MCP server and give the rights to that server, not to the
  agent.
- **Reuse the correlation ID** of the story you are continuing.

### Build settings

The module needs `io.konzert:konzert-core`, `quarkus-rest-jackson`,
`quarkus-arc` and `quarkus-smallrye-health`; copy the
[minimal agent's pom](../examples/minimal-agent/pom.xml). Three properties
identify it:

```properties
agent.name=${AGENT_NAME:scribe}
kafka.bootstrap=konzert-kafka-kafka-bootstrap.kafka.svc:9092
kafka.group-id=${KAFKA_GROUP_ID:konzert-scribe}
```

Deploy it with the same chart as every other agent
([deploy/helm/agent](../deploy/helm/agent)), with its own values file.

## Before it goes anywhere near a real key

- [ ] The filter is default deny, and the last rule is an explicit `count`.
- [ ] Budgets are set (`llmCallsPerHour`, `tokensPerHour`).
- [ ] You ran a dry run against real traffic and the number that reaches the
      model is the number you expected.
- [ ] The agent ignores its own messages (`intake.selfName` is its name).
- [ ] Anything that changes something is `propose` until you trust it.
- [ ] The key is in a Secret, not in a values file.
