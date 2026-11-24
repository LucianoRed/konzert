package io.konzert.examples.incident.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The roster strip: is each member of the team alive, and what has its intake
 * filter been doing. Read straight from the agents' own Control Panel API, so
 * the numbers on the projector are the agents' numbers.
 */
@ApplicationScoped
@Path("/api/agents")
@Produces(MediaType.APPLICATION_JSON)
public class AgentsResource {

    @Inject
    ObjectMapper mapper;

    /** name=url pairs, e.g. maestro=http://maestro:8080 */
    @ConfigProperty(name = "konzert.agents")
    List<String> agents;

    /** Only these carry an intake filter and a model; the rest just answer health. */
    @ConfigProperty(name = "konzert.agents.with-intake", defaultValue = "maestro,atlas,sentinel")
    List<String> withIntake;

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    @GET
    public List<Map<String, Object>> roster() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (String entry : agents) {
            String[] kv = entry.split("=", 2);
            String name = kv[0].trim();
            String base = kv.length > 1 ? kv[1].trim() : "http://" + name + ":8080";
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("ready", get(base + "/q/health/ready") != null);
            JsonNode stats = withIntake.contains(name) ? get(base + "/api/config/intake/stats") : null;
            if (stats != null) {
                row.put("autonomy", stats.path("autonomy").asText(""));
                JsonNode f = stats.path("funnel");
                row.put("seen", f.path("seen").asInt(0));
                row.put("reasoned", f.path("reasoned").asInt(0));
                row.put("tokens", f.path("promptTokens").asInt(0) + f.path("completionTokens").asInt(0));
                row.put("open", stats.path("incidents").path("open"));
                row.put("turns", stats.path("incidents").path("turns"));
                // The filter's last verdicts: what the team view animates as
                // "admitted" or "filtered" is the agent's own decision, not a guess.
                JsonNode recent = stats.path("recent");
                int n = recent.size();
                row.put("recent", n > 40 ? mapper.createArrayNode().addAll(
                        java.util.stream.StreamSupport.stream(recent.spliterator(), false)
                                .skip(n - 40).toList()) : recent);
            }
            out.add(row);
        }
        return out;
    }

    private JsonNode get(String url) {
        try {
            HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 300) {
                return null;
            }
            return mapper.readTree(res.body());
        } catch (Exception e) {
            return null;
        }
    }
}
