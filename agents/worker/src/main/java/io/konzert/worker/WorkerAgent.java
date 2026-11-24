package io.konzert.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.konzert.core.config.AgentConfig;
import io.konzert.core.config.ConfigStore;
import io.konzert.core.events.CloudEvent;
import io.konzert.core.events.KafkaBus;
import io.konzert.core.llm.LlmClient;
import io.konzert.core.runtime.AgentRuntime;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A worker of the team — Atlas 🛠️ or Sentinel 🛡️ in the incident-response
 * example, depending on configuration.
 *
 * It listens to the conversation, reasons about what it was asked, and acts
 * through Ansible playbooks on the MCP server. In {@code propose} mode a
 * mutating playbook is not run until the human operator approves it on the
 * panel: the plan goes out as an {@code ask} to {@code human}, and the answer
 * comes back on the same topic, under the same correlation ID. Approval is a
 * Kafka message like everything else, so the panel can show it and the topic
 * can replay it.
 */
@ApplicationScoped
public class WorkerAgent {

    private static final Logger LOG = Logger.getLogger(WorkerAgent.class);

    @Inject
    AgentRuntime runtime;
    @Inject
    ConfigStore store;
    @Inject
    LlmClient llm;
    @Inject
    KafkaBus bus;
    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "konzert.conversation-topic", defaultValue = "konzert.conversation")
    String conversationTopic;
    @ConfigProperty(name = "konzert.actions-topic", defaultValue = "konzert.actions")
    String actionsTopic;
    @ConfigProperty(name = "konzert.namespace", defaultValue = "konzert-demo")
    String demoNamespace;
    @ConfigProperty(name = "konzert.idle-close-minutes", defaultValue = "10")
    int idleCloseMinutes;
    @ConfigProperty(name = "mcp.ansible.url", defaultValue = "http://konzert-mcp-ansible:8080/mcp/sse")
    String mcpUrl;
    @ConfigProperty(name = "mcp.call-timeout-seconds", defaultValue = "300")
    long mcpCallTimeout;
    /** The business workloads of the demo namespace — what "still healthy" refers to. */
    @ConfigProperty(name = "konzert.workloads", defaultValue = "checkout-api")
    String knownWorkloads;
    /** The other agents on the team, by name: who this worker may address besides Maestro. */
    @ConfigProperty(name = "konzert.team", defaultValue = "atlas,sentinel")
    List<String> team;
    /** The language of anything addressed to the human operator. Agent-to-agent stays English. */
    @ConfigProperty(name = "konzert.human-language", defaultValue = "English")
    String humanLanguage;

    /** Incidents already declared over: the team does not keep talking about them. */
    private final Deque<String> closed = new ArrayDeque<>();

    record Plan(String playbook, ObjectNode extraVars) {}

    /** Plans we already told the team we are waiting on, so we say it once. */
    private final java.util.Set<String> waitingAnnounced = ConcurrentHashMap.newKeySet();
    record Say(List<String> to, String intent, String text) {}
    record Decision(Say say, Plan plan, String reasoning) {}

    private final Map<String, Deque<String>> threads = new ConcurrentHashMap<>();
    private final Map<String, JsonNode> context = new ConcurrentHashMap<>();
    private final Map<String, Plan> pendingApproval = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final Map<String, Boolean> mutatingByPlaybook = new ConcurrentHashMap<>();
    /**
     * The playbooks the catalogue assigns to this agent (or to everyone). The
     * model is only ever shown these, and a plan naming anything else is
     * refused here rather than sent to the MCP server.
     */
    private final java.util.Set<String> ownedPlaybooks = ConcurrentHashMap.newKeySet();
    private volatile String catalog = "";

    private McpSseClient mcp;

    /** One turn at a time: the room is reading a conversation, not a race. */
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "konzert-worker");
        t.setDaemon(true);
        return t;
    });
    private final ScheduledExecutorService housekeeping = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "konzert-worker-housekeeping");
        t.setDaemon(true);
        return t;
    });

    void onStart(@Observes StartupEvent ev) {
        mcp = new McpSseClient(mcpUrl, mapper);
        refreshCatalog();
        runtime.start((topic, event) -> worker.submit(() -> safeHandle(topic, event)));
        housekeeping.scheduleAtFixedRate(this::closeIdle, 1, 1, TimeUnit.MINUTES);
        LOG.infof("%s awake — autonomy=%s, mcp=%s, %d intake rule(s)",
                name(), store.config().autonomy, mcpUrl, store.intake().rules.size());
    }

    private String name() {
        return store.config().identity.name;
    }

    // ------------------------------------------------------------- the loop

    private void safeHandle(String topic, JsonNode event) {
        try {
            handle(topic, event);
        } catch (RuntimeException e) {
            LOG.errorf(e, "turn failed on %s", topic);
        }
    }

    private void handle(String topic, JsonNode event) {
        String correlation = text(event, "correlationid");
        if (correlation == null) {
            return;
        }
        JsonNode data = event.path("data");
        String from = data.path("from").asText("");
        String intent = data.path("intent").asText("");
        String msg = data.path("text").asText("");
        synchronized (closed) {
            if (closed.contains(correlation) && !"human".equals(from) && !"close".equals(intent)) {
                LOG.infof("%s: ignoring %s's %s on closed incident %s", name(), from, intent, correlation);
                return;
            }
        }
        lastSeen.put(correlation, System.currentTimeMillis());

        if ("close".equals(intent)) {
            remember(correlation, from + ": " + msg);
            closeIncident(correlation, "closed by " + from);
            return;
        }

        runtime.incidents().open(correlation);
        remember(correlation, from + " → " + String.join(",", toList(data.path("to"))) + ": " + msg);
        if (data.hasNonNull("context")) {
            context.put(correlation, data.get("context"));
        }

        // The operator's verdict on a plan is deterministic — no model in the way
        // of a "yes" or a "no".
        if ("human".equals(from) && pendingApproval.containsKey(correlation)) {
            String verdict = approvalVerdict(msg);
            if ("approve".equals(verdict)) {
                Plan p = pendingApproval.remove(correlation);
                waitingAnnounced.remove(correlation);
                remember(correlation, "human approved " + p.playbook());
                execute(correlation, p, "human");
                return;
            }
            if ("deny".equals(verdict)) {
                Plan p = pendingApproval.remove(correlation);
                waitingAnnounced.remove(correlation);
                remember(correlation, "human denied " + p.playbook());
                post(correlation, List.of("maestro", "human"), "report",
                        "Operator declined the plan: " + p.playbook() + " was not run. Standing by.",
                        null, null);
                return;
            }
            // Anything else is a question; the model answers it with the plan in view.
        }

        // While a plan waits for the operator, a nudge from the team is not a
        // reason to think again — and certainly not to propose the same thing twice.
        if (!"human".equals(from) && pendingApproval.containsKey(correlation)) {
            Plan p = pendingApproval.get(correlation);
            if (waitingAnnounced.add(correlation)) {
                post(correlation, List.of(from), "ack",
                        "Plan ready (" + p.playbook() + "), waiting for the operator's approval on the panel.",
                        null, null);
            }
            return;
        }

        // An acknowledgement is not a question. Answering it starts a polite
        // loop that costs tokens and says nothing.
        if (!"human".equals(from) && ("ack".equals(intent) || "observe".equals(intent))) {
            LOG.infof("%s: not answering %s's %s on %s", name(), from, intent, correlation);
            return;
        }

        Decision d = decide(correlation, from, intent, msg);
        if (d == null) {
            return;
        }
        // The model described an action without attaching it. Approval is built
        // from the attached plan, so ask once more for the object, not the prose.
        if (d.plan() == null && d.say() != null && looksLikeAnAction(d.say().text())) {
            Decision again = decide(correlation, from, intent, msg,
                    "Your previous reply said: \"" + d.say().text() + "\" — it describes an action but "
                    + "attaches no playbook object. Reply again with the same say AND the playbook "
                    + "object (name + extraVars). If you truly do not intend to run anything, reply "
                    + "with say only and no talk of proposing or waiting for approval.");
            if (again != null && again.plan() != null) {
                d = again;
            }
        }
        if (d.plan() != null) {
            if (refuseIfNotOwned(correlation, d.plan())) {
                return;
            }
            boolean mutating = mutatingByPlaybook.getOrDefault(d.plan().playbook(), true);
            if (mutating && !store.config().mayExecute()) {
                pendingApproval.put(correlation, d.plan());
                String lead = d.say() != null && d.say().text() != null && !d.say().text().isBlank()
                        ? d.say().text().trim() + " " : "";
                String text = lead + "\n▶ " + d.plan().playbook() + " " + d.plan().extraVars().toString();
                post(correlation, List.of("human"), "ask", text, d.reasoning(), planNode(d.plan()));
                return;
            }
            if (d.say() != null && d.say().text() != null && !d.say().text().isBlank()) {
                post(correlation, d.say().to(), "plan", d.say().text(), d.reasoning(), null);
            }
            execute(correlation, d.plan(), "autonomy");
            return;
        }
        if (d.say() != null && d.say().text() != null && !d.say().text().isBlank()) {
            post(correlation, d.say().to(), d.say().intent(), d.say().text(), d.reasoning(), null);
        } else {
            LOG.infof("%s stays silent on %s", name(), correlation);
        }
    }

    // ------------------------------------------------------------ reasoning

    private Decision decide(String correlation, String from, String intent, String msg) {
        return decide(correlation, from, intent, msg, null);
    }

    private Decision decide(String correlation, String from, String intent, String msg, String nudge) {
        if (catalog.isEmpty()) {
            refreshCatalog();
        }
        LlmClient.Reply reply = llm.chat(List.of(
                LlmClient.Message.system(systemPrompt()),
                LlmClient.Message.user(prompt(correlation) + (nudge == null ? "" : "\n" + nudge + "\n"))));
        if (!reply.ok()) {
            LOG.warnf("llm %s on %s; using fallback", reply.status(), correlation);
            post(correlation, List.of("maestro"), "observe", store.config().model.fallbackText, null, null);
            return null;
        }
        return parse(reply.content(), reply.reasoning());
    }

    private String systemPrompt() {
        AgentConfig cfg = store.config();
        String base = cfg.systemPrompt != null && !cfg.systemPrompt.isBlank()
                ? cfg.systemPrompt : Prompts.DEFAULT_SYSTEM;
        String soul = cfg.identity.soul == null ? "" : cfg.identity.soul.trim();
        return base
                .replace("%DISPLAY%", cfg.identity.displayName)
                .replace("%NAME%", cfg.identity.name)
                .replace("%ROLE%", cfg.identity.role)
                .replace("%NAMESPACE%", demoNamespace)
                .replace("%AUTONOMY%", cfg.autonomy)
                .replace("%WORKLOADS%", knownWorkloads)
                .replace("%HUMAN_LANGUAGE%", humanLanguage)
                .replace("%ADDRESSEES%", addressees())
                + (soul.isEmpty() ? "" : "\nWHO YOU ARE\n" + soul + "\n")
                + "\nPLAYBOOK CATALOGUE (JSON)\n" + catalog + "\n";
    }

    /** Everyone this worker may address: Maestro, the team, the human and the whole room. */
    private String addressees() {
        List<String> names = new ArrayList<>();
        names.add("maestro");
        names.addAll(team);
        names.add("human");
        names.add("all");
        return String.join(" | ", names);
    }

    /** Volatile content last, so the provider's prompt cache keeps hitting. */
    private String prompt(String correlation) {
        StringBuilder sb = new StringBuilder();
        sb.append("Incident: ").append(correlation).append('\n');
        JsonNode ctx = context.get(correlation);
        if (ctx != null) {
            sb.append("\nEvidence the sensor collected:\n").append(ctx.toPrettyString()).append('\n');
        }
        Deque<String> thread = threads.get(correlation);
        if (thread != null && !thread.isEmpty()) {
            sb.append("\nThe conversation so far (oldest first, the last line is the one you are answering):\n");
            thread.forEach(line -> sb.append("- ").append(line).append('\n'));
        }
        Plan pending = pendingApproval.get(correlation);
        if (pending != null) {
            sb.append("\nYou have a plan waiting for the operator's approval: ")
                    .append(pending.playbook()).append(' ').append(pending.extraVars())
                    .append(". Do not propose it again; answer the question.\n");
        }
        sb.append("\nReply with ONLY the JSON object described in your instructions.\n");
        return sb.toString();
    }

    /** Tolerant on purpose: JSON wrapped in prose still gets understood. */
    private Decision parse(String content, String reasoning) {
        String c = content == null ? "" : content.trim();
        int start = c.indexOf('{');
        int end = c.lastIndexOf('}');
        if (start >= 0 && end > start) {
            try {
                JsonNode n = mapper.readTree(c.substring(start, end + 1));
                Say say = null;
                JsonNode s = n.get("say");
                if (s != null && s.isObject()) {
                    List<String> to = toList(s.path("to"));
                    if (to.isEmpty()) {
                        to = List.of("maestro");
                    }
                    say = new Say(to, s.path("intent").asText("observe").trim(), s.path("text").asText("").trim());
                }
                Plan plan = null;
                JsonNode p = n.get("playbook");
                if (p != null && p.isObject() && !p.path("name").asText("").isBlank()) {
                    ObjectNode vars = p.path("extraVars").isObject()
                            ? (ObjectNode) p.get("extraVars") : mapper.createObjectNode();
                    if (!vars.has("namespace")) {
                        vars.put("namespace", demoNamespace);
                    }
                    plan = new Plan(p.get("name").asText().trim(), vars);
                }
                return new Decision(say, plan, reasoning);
            } catch (Exception e) {
                LOG.debugf("reply was not JSON, using it as prose: %s", e.getMessage());
            }
        }
        return new Decision(new Say(List.of("maestro"), "observe", c.isEmpty() ? "(no reply)" : c), null, reasoning);
    }

    // --------------------------------------------------------------- acting

    /**
     * Staying in its lane is not left to the prompt: a playbook that is not in
     * this agent's part of the catalogue is never run, whatever the model said.
     */
    private boolean refuseIfNotOwned(String correlation, Plan plan) {
        if (ownedPlaybooks.isEmpty()) {
            refreshCatalog(); // the MCP server may not have been up when this agent started
        }
        if (ownedPlaybooks.contains(plan.playbook())) {
            return false;
        }
        LOG.warnf("%s: refusing %s — not in this agent's catalogue", name(), plan.playbook());
        post(correlation, List.of("maestro"), "report",
                ownedPlaybooks.isEmpty()
                        ? "I cannot reach my playbook catalogue (mcp-ansible), so I am not running anything."
                        : "I will not run " + plan.playbook() + ": it is not in my catalogue.",
                null, null);
        return true;
    }

    private void execute(String correlation, Plan plan, String approvedBy) {
        if (refuseIfNotOwned(correlation, plan)) {
            return;
        }
        ObjectNode args = mapper.createObjectNode();
        args.put("playbook", plan.playbook());
        args.put("extraVars", plan.extraVars().toString());

        JsonNode result;
        try {
            McpSseClient.ToolResult r = mcp.callTool("run_playbook", args, mcpCallTimeout);
            result = parseJson(r.text());
            if (result == null) {
                result = mapper.createObjectNode().put("status", r.isError() ? "error" : "unknown")
                        .put("outputTail", r.text());
            }
        } catch (Exception e) {
            LOG.errorf("run_playbook failed: %s", e.getMessage());
            post(correlation, List.of("maestro", "human"), "report",
                    "I could not reach my hands (mcp-ansible): " + e.getMessage(), null, null);
            return;
        }

        ObjectNode action = mapper.createObjectNode();
        action.put("agent", name());
        action.put("playbook", plan.playbook());
        action.set("extraVars", plan.extraVars());
        action.put("status", result.path("status").asText("unknown"));
        action.set("stats", result.path("stats"));
        action.put("durationMs", result.path("durationMs").asLong(0));
        action.put("runId", result.path("runId").asText(""));
        action.put("output", result.path("outputTail").asText(""));
        action.put("approvedBy", approvedBy);
        bus.publish(actionsTopic, CloudEvent.of(
                "io.konzert.PlaybookExecuted", "/konzert/agents/" + name(), correlation, action));

        String summary = plan.playbook() + " → " + result.path("status").asText("unknown")
                + " (ok=" + result.path("stats").path("ok").asInt(0)
                + " changed=" + result.path("stats").path("changed").asInt(0)
                + " failed=" + result.path("stats").path("failed").asInt(0) + ")";
        remember(correlation, name() + " ran " + summary);

        // The report is the model's, so it can say what the run means; the
        // fallback is the deterministic summary, never silence.
        LlmClient.Reply reply = llm.chat(List.of(
                LlmClient.Message.system(systemPrompt()),
                LlmClient.Message.user(prompt(correlation)
                        + "\nYou just ran " + plan.playbook() + " with " + plan.extraVars()
                        + ". Result:\n" + abbreviate(result.toString(), 3000)
                        + "\nReport the outcome to maestro in one or two sentences with the numbers. "
                        + "Do not include a playbook unless a follow-up action is genuinely required.\n")));
        Decision d = reply.ok() ? parse(reply.content(), reply.reasoning()) : null;
        if (d != null && d.say() != null && !d.say().text().isBlank()) {
            post(correlation, d.say().to(), "report", d.say().text(), d.reasoning(), null);
        } else {
            post(correlation, List.of("maestro"), "report", "Ran " + summary + ".", null, null);
        }
        if (d != null && d.plan() != null && !mutatingByPlaybook.getOrDefault(d.plan().playbook(), true)) {
            execute(correlation, d.plan(), approvedBy);
        }
    }

    private void refreshCatalog() {
        try {
            McpSseClient.ToolResult r = mcp.callTool("list_playbooks", mapper.createObjectNode(), 20);
            JsonNode n = parseJson(r.text());
            if (n == null) {
                return;
            }
            ArrayNode mine = mapper.createArrayNode();
            java.util.Set<String> owned = new java.util.HashSet<>();
            for (JsonNode p : n.path("playbooks")) {
                String owner = p.path("owner").asText("");
                mutatingByPlaybook.put(p.path("name").asText(""), p.path("mutating").asBoolean(true));
                if (owner.equals(name()) || "both".equals(owner)) {
                    mine.add(p);
                    owned.add(p.path("name").asText(""));
                }
            }
            ownedPlaybooks.addAll(owned);
            ownedPlaybooks.retainAll(owned);
            catalog = mine.toPrettyString();
            LOG.infof("%s may run %d playbook(s)", name(), mine.size());
        } catch (Exception e) {
            LOG.warnf("playbook catalogue unavailable (%s); will retry on the next turn", e.getMessage());
        }
    }

    // ------------------------------------------------------------ plumbing

    private void post(String correlation, List<String> to, String intent, String text,
                      String reasoning, ObjectNode plan) {
        int turn = runtime.incidents().turn(correlation);
        ObjectNode data = mapper.createObjectNode();
        data.put("from", name());
        ArrayNode arr = data.putArray("to");
        to.forEach(arr::add);
        data.put("intent", intent);
        data.put("turn", turn);
        data.put("text", text);
        if (reasoning != null && !reasoning.isBlank()) {
            data.put("reasoning", reasoning);
        }
        if (plan != null) {
            data.set("plan", plan);
        }
        bus.publish(conversationTopic, CloudEvent.of(
                "io.konzert.MessagePosted", "/konzert/agents/" + name(), correlation, data));
        remember(correlation, name() + " → " + String.join(",", to) + ": " + text);
    }

    private ObjectNode planNode(Plan p) {
        ObjectNode n = mapper.createObjectNode();
        n.put("playbook", p.playbook());
        n.set("extraVars", p.extraVars());
        return n;
    }

    private void closeIncident(String correlation, String why) {
        runtime.incidents().close(correlation);
        pendingApproval.remove(correlation);
        waitingAnnounced.remove(correlation);
        lastSeen.remove(correlation);
        synchronized (closed) {
            closed.addLast(correlation);
            while (closed.size() > 50) {
                closed.removeFirst();
            }
        }
        LOG.infof("incident %s %s", correlation, why);
    }

    private void closeIdle() {
        long cutoff = System.currentTimeMillis() - idleCloseMinutes * 60_000L;
        for (var e : lastSeen.entrySet()) {
            if (e.getValue() < cutoff && runtime.incidents().inFlight(e.getKey())) {
                closeIncident(e.getKey(), "released as idle");
            }
        }
    }

    private void remember(String correlation, String line) {
        Deque<String> t = threads.computeIfAbsent(correlation, k -> new ArrayDeque<>());
        t.addLast(line);
        while (t.size() > store.config().memory.windowMessages) {
            t.removeFirst();
        }
    }

    /**
     * Prose that smells like a plan: a catalogue playbook by name, or the verbs
     * of one. English and Portuguese on purpose — the operator's language is
     * configurable, and the model answers the operator in it.
     */
    private boolean looksLikeAnAction(String text) {
        if (text == null) {
            return false;
        }
        String t = text.toLowerCase(Locale.ROOT);
        for (String pb : mutatingByPlaybook.keySet()) {
            if (!pb.isEmpty() && t.contains(pb.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return t.matches("(?s).*(propo|aguardo aprova|aprova[çc][aã]o|apply|aplicar|isolar|isolate|quarantine|quarentena|"
                + "playbook|set memory|memory_limit|networkpolicy).*");
    }

    /** The operator's verdict, matched without a model. Accepts English and Portuguese. */
    static String approvalVerdict(String text) {
        String t = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
        t = t.replaceAll("^[^\\p{L}\\p{N}]+", "");
        if (t.matches("^(approve[d]?|yes|y|ok|okay|go|sim|aprovado|aprovar|aprovo|aprova|pode|autorizado|autorizo|manda|vai)\\b.*")) {
            return "approve";
        }
        if (t.matches("^(deny|denied|no|n|nope|não|nao|nego|negado|negar|cancel|cancelar|abort|abortar|para|pare)\\b.*")) {
            return "deny";
        }
        return "question";
    }

    private JsonNode parseJson(String s) {
        if (s == null) {
            return null;
        }
        String c = s.trim();
        int start = c.indexOf('{');
        int end = c.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        try {
            return mapper.readTree(c.substring(start, end + 1));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<String> toList(JsonNode n) {
        List<String> out = new ArrayList<>();
        if (n != null && n.isArray()) {
            n.forEach(v -> out.add(v.asText()));
        } else if (n != null && n.isTextual()) {
            out.add(n.asText());
        }
        return out;
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? null : v.asText();
    }

    private static String abbreviate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** For the panel: what this agent is waiting on. */
    public Map<String, Object> pending() {
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        pendingApproval.forEach((k, v) -> out.put(k, v.playbook() + " " + v.extraVars()));
        return out;
    }
}
