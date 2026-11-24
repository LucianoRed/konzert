# Example: the smallest useful agent

One class, [`MinimalAgent`](src/main/java/io/konzert/examples/minimal/MinimalAgent.java):
it answers what is addressed to it, and nothing else. Use it as the starting
point for an agent of your own; the walkthrough is in
[docs/building-an-agent.md](../../docs/building-an-agent.md).

Everything that makes this agent "Scribe" is configuration:
[`values.yaml`](values.yaml) (identity, model, intake rules) and
[`soul.md`](soul.md) (its character). The intake rule admits only messages
from a person that address it by name, so it sits on a busy topic and costs
nothing until someone talks to it.

## Run it next to the incident-response example

It needs a Kafka cluster with the Konzert topics, and the `konzert-demo`
namespace; see the example's
[install guide](../incident-response/docs/install.md).

```bash
NS=konzert-demo
M=examples/minimal-agent

# build the image in the cluster
(cd core && mvn -q clean install -DskipTests)
(cd $M && mvn -q clean package -DskipTests)
oc new-build --binary --name=konzert-minimal-agent --strategy=docker -n $NS
cp $M/src/main/docker/Dockerfile.jvm $M/Dockerfile
oc start-build konzert-minimal-agent --from-dir=$M --follow -n $NS
rm $M/Dockerfile

# the key, from the environment, on standard input
printf '%s' "$LLM_API_KEY" | oc create secret generic scribe-secrets -n $NS \
  --from-file=llm-api-key=/dev/stdin

helm upgrade --install scribe deploy/helm/agent -n $NS -f $M/values.yaml \
  --set-file agentConfig.identity.soul=$M/soul.md
```

Talk to it through the Situation Room's API. The panel's chat box always
addresses Maestro, so name the addressee yourself:

```bash
oc port-forward deploy/konzert-console 8090:8080 -n $NS &
curl -s -X POST localhost:8090/api/chat -H 'Content-Type: application/json' \
  -d '{"to": "scribe", "text": "What is an intake filter, in one sentence?"}'
```

The answer appears on the panel (labelled as coming from the console, because
the panel only knows the example's cast), and on the topic:

```bash
oc port-forward deploy/scribe 8082:8080 -n $NS &
curl -s localhost:8082/api/config/intake/stats | jq '.funnel'
```

The funnel shows every message Scribe saw and the one that reached its model.
