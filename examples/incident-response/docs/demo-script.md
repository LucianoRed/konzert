# An eight-minute walkthrough

A script for showing the example to an audience: what to click, what to say,
and what to do when it misbehaves. One browser tab is enough.

## Before you start

- Run `hack/reset.sh` and confirm the panel is empty.
- If you rehearsed after the reset, **Clear panel** hides the rehearsal from
  every open browser. It touches neither Kafka nor the agents, so if an
  incident is still open, finish it or **Revert all** first.
- Do one full dry run. Atlas and Sentinel ship in `propose` mode and ask before
  running a playbook. To run with no human at all, set `autonomy: act` on both
  (`PUT /api/config` on each agent, or the values file).
- Check that every dot in the header is green. A red one is a pod that is not
  ready; an agent that only ever posts its fallback line has an LLM key problem.
- Zoom the page so the last four lines are readable from the back of the room.

## Part 1 — the setup (60s)

> "This is a Deployment someone shipped on a Friday. It works. It has no memory
> limits, and nobody noticed."

Point at `checkout-api` in the cluster box. Do not show YAML.

> "Nobody is on call. There are three agents. They talk to each other on Kafka,
> and this panel is only listening in."

## Part 2 — the memory leak (150s)

Click **Memory leak**, then stop talking and let the room read. The silence
while the sensor watches is part of the demo.

Narrate only the beats:

- When 📡 fires: *"Nobody told it that. It correlated a live memory reading
  with a missing limit in the spec. There is no model in the sensor."*
- When 🎼 delegates: *"It decided this is a workload problem, not a security
  problem."* Expand the message's reasoning: *"and this is the model's own
  trace of why."*
- When 🛠️ asks **you**: *"It decided what to do, and it is not allowed to do it
  alone. That is configuration, `propose`, not a sentence in a prompt."* Read
  the plan aloud and click **Approve**. Point out that the click is a Kafka
  message like every other line.
- When 🛠️ expands the playbook output: *"And that is Ansible. A playbook
  someone reviewed, which the agent is allowed to run. The agent has no
  credentials for the cluster."*

## Part 3 — the interruption (60s)

Type into the chat box:

> `why not just restart the pod?`

Maestro answers with its reasoning. This is the moment that shows the
conversation is not a recording. Good follow-ups: `who decided that?`,
`what would you have done if it were a security issue?`

## Part 4 — the port scan (150s)

Click **Revert all**, then **Port scan**.

The beat to wait for: Sentinel posts to everyone, and **Atlas answers without
being asked by Maestro**.

> "Nobody routed that message. Atlas is subscribed to the same topic, and its
> filter admitted a broadcast. That is why this is a bus and not three API
> calls."

## Part 4½ — what does it cost? (45s, for a technical room)

Open the **team view** (link in the header). Each agent shows three bars: what
it saw, what reached its model, and the tokens it spent. A few hundred messages
seen, a dozen reasoned about.

> "The bus is loud on purpose. The filter is what makes it affordable, and it
> is configuration: you tune it without rebuilding an agent."

If you have a terminal ready, show the same numbers from the agent itself
(`GET /api/config/intake/stats`) and run a dry run of a tighter rule
(`POST /api/config/intake/dry-run`): it replays recent traffic through the
candidate filter without calling a model.

## Part 5 — the close (60s)

> "Two incidents, two playbooks, and the whole argument is on a Kafka topic you
> can replay tomorrow morning in the post-mortem."

## When it misbehaves

| What happens | What you say | What you do |
|---|---|---|
| An agent is slow | *"It is thinking. That is a real model call."* | wait; a timeout produces the agent's fallback line |
| An agent posts nonsense | *"This is why we bound what it is allowed to run."* | let it fail; the playbook allow-list makes it harmless |
| The conversation stalls | | type `status?` in the chat; Maestro restates the incident |
| Nothing fires at all | *"Let me inject that again."* | **Revert all**, then inject once more |
| Total failure | *"The honest version: this is a live cluster."* | reload the page: the history endpoint replays the last hour from the topics |
