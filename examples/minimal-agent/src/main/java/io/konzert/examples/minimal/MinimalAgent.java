package io.konzert.examples.minimal;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The smallest useful Konzert agent: it answers what is addressed to it.
 *
 * Everything an agent needs is here and nothing else is: start the runtime,
 * receive what the intake filter admitted, reason, publish. Which messages are
 * admitted, which model answers, and who the agent is are all configuration —
 * see values.yaml next to this module.
 */
@ApplicationScoped
public class MinimalAgent {

    private static final Logger LOG = Logger.getLogger(MinimalAgent.class);

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

    /** A model call is slow. Keep it off the Kafka thread, and keep turns in order. */
    private final ExecutorService turns = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "agent-turns");
        t.setDaemon(true);
        return t;
    });

    void onStart(@Observes StartupEvent ev) {
        // The handler only ever sees what the intake filter admitted.
        runtime.start((topic, event) -> turns.submit(() -> answer(event)));
    }

    private void answer(JsonNode event) {
        try {
            String correlation = event.path("correlationid").asText("");
            if (correlation.isEmpty()) {
                return; // not part of any story; nothing to reply to
            }
            JsonNode data = event.path("data");
            String from = data.path("from").asText("someone");
            AgentConfig cfg = store.config();

            // Stable text first (job, then character), volatile text last: the
            // provider's prompt cache only helps when the prefix does not change.
            String system = cfg.systemPrompt + "\nWHO YOU ARE\n" + cfg.identity.soul;
            LlmClient.Reply reply = llm.chat(List.of(
                    LlmClient.Message.system(system),
                    LlmClient.Message.user(from + " says: " + data.path("text").asText(""))));

            // A failed call is never published as if it were an answer.
            String text = reply.ok() ? reply.content().trim() : cfg.model.fallbackText;

            ObjectNode out = mapper.createObjectNode();
            out.put("from", cfg.identity.name);
            out.putArray("to").add(from);
            out.put("intent", "answer");
            out.put("turn", runtime.incidents().turn(correlation));
            out.put("text", text);
            bus.publish(conversationTopic, CloudEvent.of(
                    "io.konzert.MessagePosted", "/konzert/agents/" + cfg.identity.name, correlation, out));
        } catch (RuntimeException e) {
            LOG.errorf(e, "turn failed");
        }
    }
}
