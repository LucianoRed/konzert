package io.konzert.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.konzert.core.intake.IntakeConfig;
import io.konzert.core.runtime.AgentRuntime;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Control Panel's API. The panel is only a client of this — everything it
 * can do is scriptable, which is how a room of thirty agents gets seeded and
 * reset without anyone clicking thirty times.
 */
@Path("/api/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConfigResource {

    @Inject
    ConfigStore store;
    @Inject
    AgentRuntime runtime;
    @Inject
    ObjectMapper mapper;

    @GET
    public Map<String, Object> get() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agent", store.config());
        out.put("intake", store.intake());
        // Presence, never the value.
        out.put("secrets", store.secrets().masked());
        out.put("storage", Map.of(
                "clusterBacked", store.clusterBacked(),
                "seedConfigMap", store.seedConfigMapName(),
                "runtimeConfigMap", store.runtimeConfigMapName(),
                "seedSecret", store.seedSecretName(),
                "runtimeSecret", store.runtimeSecretName()));
        return out;
    }

    /**
     * Partial update. A blank secret means "keep the stored one", so saving an
     * unrelated field never requires a key to round-trip through a browser.
     */
    @PUT
    public Map<String, Object> put(Map<String, Object> body) {
        AgentConfig agent = convert(body.get("agent"), AgentConfig.class);
        IntakeConfig intake = convert(body.get("intake"), IntakeConfig.class);
        AgentSecrets secrets = convert(body.get("secrets"), AgentSecrets.class);

        if (agent != null || intake != null) {
            store.saveConfig(agent, intake);
        }
        if (secrets != null) {
            store.saveSecrets(secrets);
        }
        return Map.of("status", "saved", "storage",
                store.clusterBacked() ? store.runtimeConfigMapName() : "local files");
    }

    @GET
    @Path("/intake")
    public IntakeConfig intake() {
        return store.intake();
    }

    @PUT
    @Path("/intake")
    public Map<String, Object> saveIntake(IntakeConfig incoming) {
        store.saveConfig(null, incoming);
        return Map.of("status", "saved", "rules", incoming.rules.size());
    }

    /** The funnel: the honest answer to "what is this agent costing me". */
    @GET
    @Path("/intake/stats")
    public Map<String, Object> stats() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("funnel", runtime.funnel().snapshot());
        out.put("incidents", runtime.incidents().snapshot());
        out.put("recent", runtime.recentVerdicts());
        out.put("enabled", store.intake().enabled);
        out.put("autonomy", store.config().autonomy);
        return out;
    }

    /**
     * Replay recent real traffic against a candidate filter. No model is called
     * and the live counters do not move.
     */
    @POST
    @Path("/intake/dry-run")
    public Map<String, Object> dryRun(Map<String, Object> body) {
        IntakeConfig candidate = convert(body.get("intake"), IntakeConfig.class);
        if (candidate == null) {
            candidate = store.intake();
        }
        int sample = body.get("sample") instanceof Number n ? n.intValue() : 200;
        return runtime.dryRun(candidate, sample);
    }

    @POST
    @Path("/reset")
    public Map<String, Object> reset() {
        store.resetToDefaults();
        return Map.of("status", "reset", "note", "runtime objects deleted; seeded defaults apply");
    }

    /** Export the effective configuration as something oc apply understands. */
    @GET
    @Path("/export")
    @Produces(MediaType.TEXT_PLAIN)
    public String export() throws Exception {
        String agentJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(store.config());
        String intakeJson = mapper.writerWithDefaultPrettyPrinter()
                .writeValueAsString(store.intake());
        return """
                # Effective configuration of %s, exported %s.
                # Commit this as the seed and the next install starts here.
                apiVersion: v1
                kind: ConfigMap
                metadata:
                  name: %s
                data:
                  agent-config.json: |
                %s
                  intake.json: |
                %s
                """.formatted(
                store.config().identity.name,
                java.time.Instant.now(),
                store.seedConfigMapName(),
                indent(agentJson),
                indent(intakeJson));
    }

    private static String indent(String s) {
        return s.lines().map(l -> "    " + l).reduce((a, b) -> a + "\n" + b).orElse("");
    }

    private <T> T convert(Object raw, Class<T> type) {
        return raw == null ? null : mapper.convertValue(raw, type);
    }

    @GET
    @Path("/souls")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> soul() {
        return Map.of(
                "identity", store.config().identity,
                "systemPrompt", store.config().systemPrompt,
                "autonomy", store.config().autonomy);
    }

    @GET
    @Path("/topics")
    public List<String> topics() {
        return store.intake().topics();
    }
}
