package io.konzert.examples.incident.ansible;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * The tool surface the infrastructure and security agents act through.
 *
 * Everything an agent can do to this cluster is one of these three calls, and
 * run_playbook only accepts what PlaybookCatalog declares. The validation here
 * is not defensive politeness — it is the reason the agents can be trusted with
 * no cluster credentials of their own (ADR-0006).
 */
@Singleton
public class AnsibleMcpTools {

    private static final Logger LOG = Logger.getLogger(AnsibleMcpTools.class);

    /**
     * Values are Kubernetes-ish identifiers and quantities: names, namespaces,
     * label values, "512Mi", "100m". Anything outside this is rejected rather
     * than escaped — an agent has no legitimate reason to send it.
     */
    private static final Pattern SAFE_VALUE = Pattern.compile("^[A-Za-z0-9._:/=,-]{1,128}$");

    @Inject
    PlaybookCatalog catalog;
    @Inject
    AnsibleRunner runner;
    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "ansible.allowed-namespaces")
    List<String> allowedNamespaces;
    @ConfigProperty(name = "ansible.output-tail-chars")
    int outputTailChars;

    @Tool(description = "List the Ansible playbooks this server is allowed to run, with what each "
            + "one does and which variables it needs. Call this before run_playbook if you are "
            + "unsure what is available — the list is closed and nothing outside it can be run.")
    public String list_playbooks() {
        var out = new ArrayList<Map<String, Object>>();
        for (var p : catalog.all()) {
            var m = new LinkedHashMap<String, Object>();
            m.put("name", p.name());
            m.put("owner", p.owner());
            m.put("description", p.description());
            m.put("requiredVars", p.requiredVars());
            m.put("optionalVars", p.optionalVars());
            m.put("mutating", p.mutating());
            out.add(m);
        }
        return json(Map.of("playbooks", out, "allowedNamespaces", allowedNamespaces));
    }

    @Tool(description = "Run one of the catalogued Ansible playbooks against the cluster and return "
            + "its result. Variables are passed as a JSON object, for example: "
            + "{\"namespace\":\"konzert-demo\",\"deployment\":\"checkout-api\",\"memory_limit\":\"1Gi\"}. "
            + "Returns the run status, the ansible task counts and the tail of the output.")
    public String run_playbook(
            @ToolArg(description = "Playbook name exactly as returned by list_playbooks, "
                    + "e.g. set-deployment-limits.yml")
            String playbook,
            @ToolArg(description = "JSON object with the playbook variables. Must contain every "
                    + "requiredVar of the playbook and nothing outside required+optional.")
            String extraVars) {

        var found = catalog.find(playbook == null ? "" : playbook.trim());
        if (found.isEmpty()) {
            return json(Map.of(
                    "status", "rejected",
                    "reason", "Unknown playbook '" + playbook + "'. This server only runs the "
                            + "catalogue; call list_playbooks to see it.",
                    "available", catalog.all().stream().map(PlaybookCatalog.Playbook::name).toList()));
        }
        var pb = found.get();

        Map<String, String> vars;
        try {
            vars = parseVars(extraVars);
        } catch (IllegalArgumentException e) {
            return json(Map.of("status", "rejected", "reason", e.getMessage()));
        }

        String problem = validate(pb, vars);
        if (problem != null) {
            return json(Map.of("status", "rejected", "reason", problem,
                    "requiredVars", pb.requiredVars(), "optionalVars", pb.optionalVars()));
        }

        LOG.infof("running %s with %s", pb.name(), vars.keySet());
        var run = runner.run(pb, vars);

        var result = new LinkedHashMap<String, Object>();
        result.put("runId", run.runId());
        result.put("playbook", run.playbook());
        result.put("status", run.status());
        result.put("stats", Map.of(
                "ok", run.stats().ok(),
                "changed", run.stats().changed(),
                "failed", run.stats().failed(),
                "unreachable", run.stats().unreachable(),
                "skipped", run.stats().skipped()));
        result.put("durationMs", run.durationMs());
        result.put("extraVars", run.extraVars());
        result.put("outputTail", tail(run.output(), outputTailChars));
        result.put("note", "Full output is available with playbook_status(\"" + run.runId() + "\").");
        return json(result);
    }

    @Tool(description = "Return the full output and result of a previous playbook run, by the runId "
            + "that run_playbook returned. Omit the runId to get the most recent run.")
    public String playbook_status(
            @ToolArg(description = "The runId from run_playbook, e.g. run-1a2b3c4d", required = false)
            String runId) {
        var run = (runId == null || runId.isBlank()) ? runner.last() : runner.byId(runId.trim());
        if (run == null) {
            return json(Map.of("status", "not-found",
                    "reason", "No such run. Runs are kept in memory and only the last 50 survive."));
        }
        var out = new LinkedHashMap<String, Object>();
        out.put("runId", run.runId());
        out.put("playbook", run.playbook());
        out.put("status", run.status());
        out.put("startedAt", run.startedAt());
        out.put("durationMs", run.durationMs());
        out.put("extraVars", run.extraVars());
        out.put("output", tail(run.output(), 20000));
        return json(out);
    }

    private Map<String, String> parseVars(String extraVars) {
        var vars = new LinkedHashMap<String, String>();
        if (extraVars == null || extraVars.isBlank()) {
            return vars;
        }
        JsonNode node;
        try {
            node = mapper.readTree(extraVars);
        } catch (Exception e) {
            throw new IllegalArgumentException("extraVars is not valid JSON: " + e.getMessage());
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("extraVars must be a JSON object, for example "
                    + "{\"namespace\":\"konzert-demo\",\"deployment\":\"checkout-api\"}");
        }
        for (Iterator<String> it = node.fieldNames(); it.hasNext(); ) {
            String k = it.next();
            JsonNode v = node.get(k);
            if (!v.isValueNode()) {
                throw new IllegalArgumentException("Variable '" + k + "' must be a scalar value.");
            }
            vars.put(k, v.asText());
        }
        return vars;
    }

    /** @return null when acceptable, otherwise the reason to refuse. */
    private String validate(PlaybookCatalog.Playbook pb, Map<String, String> vars) {
        for (String required : pb.requiredVars()) {
            if (!vars.containsKey(required) || vars.get(required).isBlank()) {
                return "Missing required variable '" + required + "' for " + pb.name() + ".";
            }
        }
        for (var e : vars.entrySet()) {
            if (!pb.requiredVars().contains(e.getKey()) && !pb.optionalVars().contains(e.getKey())) {
                return "Variable '" + e.getKey() + "' is not accepted by " + pb.name() + ".";
            }
            if (!SAFE_VALUE.matcher(e.getValue()).matches()) {
                return "Value of '" + e.getKey() + "' contains characters this server will not pass "
                        + "to a playbook.";
            }
        }
        String ns = vars.get("namespace");
        if (ns != null && !allowedNamespaces.contains(ns)) {
            return "This server may only act on namespaces " + allowedNamespaces + ", not '" + ns + "'.";
        }
        return null;
    }

    private static String tail(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : "…(truncated)…\n" + s.substring(s.length() - max);
    }

    private String json(Object o) {
        try {
            return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(o);
        } catch (Exception e) {
            return "{\"status\":\"error\",\"reason\":\"could not serialise result\"}";
        }
    }
}
