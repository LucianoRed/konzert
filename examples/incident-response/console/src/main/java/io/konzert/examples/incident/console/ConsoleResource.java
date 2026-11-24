package io.konzert.examples.incident.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.konzert.core.events.CloudEvent;
import io.konzert.core.events.KafkaBus;
import io.quarkus.runtime.StartupEvent;
import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.operators.multi.processors.BroadcastProcessor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.jboss.resteasy.reactive.RestStreamElementType;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Situation Room's backend: everything on the bus, streamed to the browser,
 * plus the two chaos buttons and the human's way into the conversation.
 *
 * Deliberately dumb. It subscribes, it fans out, it publishes what a human
 * types. It never interprets, never summarises, never calls a model — so what
 * the room sees is what actually happened.
 */
@ApplicationScoped
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class ConsoleResource {

    private static final Logger LOG = Logger.getLogger(ConsoleResource.class);
    private static final int HISTORY = 300;

    @Inject
    KafkaBus bus;
    @Inject
    ChaosService chaos;
    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "konzert.topics")
    List<String> topics;

    private final BroadcastProcessor<String> live = BroadcastProcessor.create();
    /** So a browser opened mid-incident does not see an empty screen. */
    private final Deque<String> recent = new ArrayDeque<>();
    /** "Clear panel": history older than this is not replayed. Only the screen forgets; the bus and the agents do not. */
    private volatile long clearedAt = 0L;

    void onStart(@Observes StartupEvent ev) {
        bus.consume(topics, (topic, event) -> publish(topic, event));
        LOG.infof("Situation Room watching %s", topics);
    }

    private void publish(String topic, JsonNode event) {
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("topic", topic);
        wrapped.set("event", event);
        String json = wrapped.toString();
        synchronized (recent) {
            recent.addLast(json);
            while (recent.size() > HISTORY) {
                recent.removeFirst();
            }
        }
        live.onNext(json);
    }

    @GET
    @Path("/stream")
    @Produces(MediaType.SERVER_SENT_EVENTS)
    @RestStreamElementType(MediaType.APPLICATION_JSON)
    public Multi<String> stream() {
        // Drop for a slow browser rather than pushing back into the Kafka loop.
        return live.onOverflow().drop();
    }

    @GET
    @Path("/history")
    public List<JsonNode> history() {
        List<JsonNode> out = new ArrayList<>();
        // Replay what is on the topics first: a console restarted mid-demo still
        // shows the incident that is already under way.
        for (var s : bus.readRecent(topics, 200, 3_600_000L)) {
            if (clearedBefore(s.event())) {
                continue;
            }
            ObjectNode wrapped = mapper.createObjectNode();
            wrapped.put("topic", s.topic());
            wrapped.set("event", s.event());
            out.add(wrapped);
        }
        return out;
    }

    private boolean clearedBefore(JsonNode event) {
        if (clearedAt == 0L || event == null || !event.hasNonNull("time")) {
            return false;
        }
        try {
            return Instant.parse(event.get("time").asText()).toEpochMilli() < clearedAt;
        } catch (RuntimeException e) {
            return false; // an event with an odd timestamp is still shown
        }
    }

    /**
     * Clear the panel: a watermark for the history and a note to every open browser.
     * Nothing is written to Kafka and no agent hears about it — an incident under way
     * keeps running and its next message shows up on the clean screen.
     */
    @POST
    @Path("/clear")
    public Map<String, Object> clear() {
        clearedAt = System.currentTimeMillis();
        synchronized (recent) {
            recent.clear();
        }
        ObjectNode event = mapper.createObjectNode();
        event.put("id", "clear-" + clearedAt);
        event.put("type", "io.konzert.PanelCleared");
        event.put("source", "/konzert/console");
        event.put("time", Instant.ofEpochMilli(clearedAt).toString());
        ObjectNode wrapped = mapper.createObjectNode();
        wrapped.put("topic", "console");
        wrapped.set("event", event);
        live.onNext(wrapped.toString());
        LOG.infof("panel cleared (history before %s hidden)", Instant.ofEpochMilli(clearedAt));
        return Map.of("status", "ok", "clearedAt", clearedAt, "message", "panel cleared");
    }

    /** The human's line into the conversation. Addressed to Maestro by default. */
    @POST
    @Path("/chat")
    @Consumes(MediaType.APPLICATION_JSON)
    public Map<String, Object> chat(Map<String, String> body) {
        String text = body.getOrDefault("text", "").trim();
        if (text.isEmpty()) {
            return Map.of("status", "ignored", "reason", "empty message");
        }
        String correlation = body.get("correlationId");
        if (correlation == null || correlation.isBlank()) {
            correlation = CloudEvent.newIncidentId();
        }
        ObjectNode data = mapper.createObjectNode();
        data.put("from", "human");
        data.putArray("to").add(body.getOrDefault("to", "maestro"));
        // "ask" is a question for Maestro; "answer" is the operator's verdict on a
        // plan an agent proposed. Both are ordinary messages on the bus.
        String intent = body.getOrDefault("intent", "ask");
        data.put("intent", "answer".equals(intent) ? "answer" : "ask");
        data.put("text", text);
        bus.publish("konzert.conversation",
                CloudEvent.of("io.konzert.MessagePosted", "/konzert/console", correlation, data));
        return Map.of("status", "sent", "correlationId", correlation);
    }

    @POST
    @Path("/inject/{scenario}")
    public Map<String, Object> inject(@PathParam("scenario") String scenario) {
        String result = chaos.inject(scenario);
        // The injection is announced on the bus too, so the panel's ticker shows
        // it in the same timeline as everything else. It carries no diagnosis:
        // the sensor still has to notice on its own.
        ObjectNode data = mapper.createObjectNode();
        data.put("scenario", scenario);
        data.put("namespace", chaos.state().get("namespace").toString());
        data.put("by", "human");
        bus.publish("konzert.events", CloudEvent.of("io.konzert.ProblemInjected",
                "/konzert/console", CloudEvent.newIncidentId(), data));
        return Map.of("status", "ok", "message", result);
    }

    @POST
    @Path("/revert")
    public Map<String, Object> revert() {
        return Map.of("status", "ok", "message", chaos.revertAll());
    }

    @GET
    @Path("/state")
    public Map<String, Object> state() {
        Map<String, Object> out = new LinkedHashMap<>(chaos.state());
        out.put("topics", topics);
        out.put("scenarios", ChaosService.SCENARIOS.keySet());
        out.put("clearedAt", clearedAt);
        return out;
    }
}
