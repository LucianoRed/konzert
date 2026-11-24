package io.konzert.core.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Credentials. Stored in a Kubernetes Secret, never in the ConfigMap, never
 * logged, and masked on every read that leaves the process.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class AgentSecrets {

    public static final String MASK = "••••••••";

    public String llmApiKey = "";

    /** True when a key is actually set — the panel shows this, not the key. */
    public boolean llmConfigured() {
        return llmApiKey != null && !llmApiKey.isBlank() && !MASK.equals(llmApiKey);
    }

    /** What goes to the browser: presence, never the value. */
    public AgentSecrets masked() {
        AgentSecrets out = new AgentSecrets();
        out.llmApiKey = llmConfigured() ? MASK : "";
        return out;
    }

    /**
     * Blank or the mask means "keep what is stored". This is why a key never has
     * to round-trip through a browser to save an unrelated field.
     */
    public void mergeFrom(AgentSecrets incoming) {
        if (incoming == null) {
            return;
        }
        if (incoming.llmApiKey != null && !incoming.llmApiKey.isBlank()
                && !MASK.equals(incoming.llmApiKey)) {
            this.llmApiKey = incoming.llmApiKey;
        }
    }
}
