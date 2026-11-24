package io.konzert.core.events;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The envelope every message on this bus wears (ADR-0002).
 *
 * The correlation ID is not optional and has no default: one incident is one
 * correlation ID, and it is the only thing that lets the panel tell a story
 * instead of showing a list.
 */
public record CloudEvent(String type, String source, String correlationId, JsonNode data) {

    public static final String SPEC_VERSION = "1.0";

    public static CloudEvent of(String type, String source, String correlationId, JsonNode data) {
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException(
                    "An event without a correlation ID is a bug (ADR-0002): " + type);
        }
        return new CloudEvent(type, source, correlationId, data);
    }

    public String toJson(ObjectMapper mapper) {
        ObjectNode n = mapper.createObjectNode();
        n.put("specversion", SPEC_VERSION);
        n.put("type", type);
        n.put("source", source);
        n.put("id", UUID.randomUUID().toString());
        n.put("time", Instant.now().toString());
        n.put("correlationid", correlationId);
        n.put("datacontenttype", "application/json");
        n.set("data", data);
        return n.toString();
    }

    /** A fresh incident identifier, short enough to read off a screen. */
    public static String newIncidentId() {
        return "incident-" + UUID.randomUUID().toString().substring(0, 6);
    }
}
