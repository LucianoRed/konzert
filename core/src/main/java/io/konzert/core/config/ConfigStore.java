package io.konzert.core.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.konzert.core.intake.IntakeConfig;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.SecretBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Configuration storage (ADR-0007). Four Kubernetes objects per agent:
 *
 *   &lt;agent&gt;-config          seeded by Helm/GitOps — this process never writes it
 *   &lt;agent&gt;-config-runtime  owned by the agent — only what the panel changed
 *   &lt;agent&gt;-secrets         seeded by Helm/ESO/Vault
 *   &lt;agent&gt;-secrets-runtime owned by the agent
 *
 * Effective value = seeded, then runtime on top, merged per field.
 *
 * The split is the whole point: if the panel wrote into the object Helm
 * manages, the next `helm upgrade` would erase what an operator tuned at runtime.
 *
 * Outside a cluster (mvn quarkus:dev) it degrades to JSON files under the data
 * dir, so the panel is developable without Kubernetes.
 */
@ApplicationScoped
public class ConfigStore {

    private static final Logger LOG = Logger.getLogger(ConfigStore.class);

    public static final String KEY_AGENT = "agent-config.json";
    public static final String KEY_INTAKE = "intake.json";
    public static final String KEY_LLM_API_KEY = "llm-api-key";
    public static final String RUNTIME_LABEL = "konzert.io/config";

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "agent.name", defaultValue = "agent")
    String agentName;
    @ConfigProperty(name = "agent.namespace", defaultValue = "")
    Optional<String> configuredNamespace;
    @ConfigProperty(name = "agent.data-dir", defaultValue = "/tmp/agent-data")
    String dataDir;

    private KubernetesClient client;
    private String namespace;
    private boolean inCluster;

    private volatile AgentConfig effectiveConfig = new AgentConfig();
    private volatile IntakeConfig effectiveIntake = IntakeConfig.denyAll("agent");
    private volatile AgentSecrets effectiveSecrets = new AgentSecrets();

    private final CopyOnWriteArrayList<Consumer<ConfigStore>> listeners = new CopyOnWriteArrayList<>();
    private final java.util.List<AutoCloseable> watches = new CopyOnWriteArrayList<>();

    @PostConstruct
    void start() {
        inCluster = System.getenv("KUBERNETES_SERVICE_HOST") != null;
        if (inCluster) {
            try {
                client = new KubernetesClientBuilder().build();
                namespace = configuredNamespace.filter(s -> !s.isBlank())
                        .orElseGet(() -> Optional.ofNullable(client.getNamespace()).orElse("default"));
            } catch (RuntimeException e) {
                LOG.warnf("No usable Kubernetes client (%s); falling back to files under %s",
                        e.getMessage(), dataDir);
                inCluster = false;
            }
        }
        reload();
        if (inCluster) {
            startWatches();
        }
        LOG.infof("config store ready: agent=%s storage=%s", agentName,
                inCluster ? "configmap+secret in " + namespace : "files in " + dataDir);
    }

    @PreDestroy
    void stop() {
        watches.forEach(w -> {
            try {
                w.close();
            } catch (Exception ignored) {
                // shutting down anyway
            }
        });
        if (client != null) {
            client.close();
        }
    }

    public AgentConfig config() {
        return effectiveConfig;
    }

    public IntakeConfig intake() {
        return effectiveIntake;
    }

    public AgentSecrets secrets() {
        return effectiveSecrets;
    }

    public boolean clusterBacked() {
        return inCluster;
    }

    public String seedConfigMapName() {
        return agentName + "-config";
    }

    public String runtimeConfigMapName() {
        return agentName + "-config-runtime";
    }

    public String seedSecretName() {
        return agentName + "-secrets";
    }

    public String runtimeSecretName() {
        return agentName + "-secrets-runtime";
    }

    /** Anything that must react to a config change — the intake pipeline, the LLM client. */
    public void onChange(Consumer<ConfigStore> listener) {
        listeners.add(listener);
    }

    // ---------------------------------------------------------------- reading

    public synchronized void reload() {
        Map<String, String> seed = readConfigMap(seedConfigMapName());
        Map<String, String> runtime = readConfigMap(runtimeConfigMapName());

        effectiveConfig = merge(AgentConfig.class, new AgentConfig(),
                seed.get(KEY_AGENT), runtime.get(KEY_AGENT));

        IntakeConfig baseIntake = IntakeConfig.denyAll(effectiveConfig.identity.name);
        effectiveIntake = merge(IntakeConfig.class, baseIntake,
                seed.get(KEY_INTAKE), runtime.get(KEY_INTAKE));
        // The agent's own name is not something an operator should have to keep
        // in sync in two places for self-message filtering to work.
        effectiveIntake.selfName = effectiveConfig.identity.name;
        if (!effectiveConfig.consumesEvents()) {
            effectiveIntake.enabled = false;
        }

        AgentSecrets s = new AgentSecrets();
        s.mergeFrom(readSecret(seedSecretName()));
        s.mergeFrom(readSecret(runtimeSecretName()));
        effectiveSecrets = s;

        listeners.forEach(l -> {
            try {
                l.accept(this);
            } catch (RuntimeException e) {
                LOG.warnf(e, "config change listener failed");
            }
        });
    }

    /** Seeded first, then runtime on top — merged field by field, not file by file. */
    private <T> T merge(Class<T> type, T base, String seedJson, String runtimeJson) {
        T out = base;
        for (String json : new String[]{seedJson, runtimeJson}) {
            if (json == null || json.isBlank()) {
                continue;
            }
            try {
                out = mapper.readerForUpdating(out).readValue(json);
            } catch (Exception e) {
                // A malformed override must not take the agent down; it keeps
                // running on what it had and says so loudly.
                LOG.errorf("ignoring malformed %s override: %s", type.getSimpleName(), e.getMessage());
            }
        }
        return out;
    }

    private Map<String, String> readConfigMap(String name) {
        if (!inCluster) {
            return readLocal(name);
        }
        try {
            ConfigMap cm = client.configMaps().inNamespace(namespace).withName(name).get();
            return cm == null || cm.getData() == null ? Map.of() : cm.getData();
        } catch (RuntimeException e) {
            LOG.warnf("cannot read ConfigMap %s: %s", name, e.getMessage());
            return Map.of();
        }
    }

    private AgentSecrets readSecret(String name) {
        AgentSecrets s = new AgentSecrets();
        if (!inCluster) {
            Map<String, String> local = readLocal(name);
            s.llmApiKey = local.getOrDefault(KEY_LLM_API_KEY, "");
            return s;
        }
        try {
            Secret sec = client.secrets().inNamespace(namespace).withName(name).get();
            if (sec != null && sec.getData() != null && sec.getData().containsKey(KEY_LLM_API_KEY)) {
                s.llmApiKey = new String(Base64.getDecoder()
                        .decode(sec.getData().get(KEY_LLM_API_KEY)), StandardCharsets.UTF_8).trim();
            }
        } catch (RuntimeException e) {
            LOG.warnf("cannot read Secret %s: %s", name, e.getMessage());
        }
        return s;
    }

    // ---------------------------------------------------------------- writing

    /** Writes ONLY to the runtime objects. The seeded ones stay Helm's. */
    public synchronized void saveConfig(AgentConfig incoming, IntakeConfig incomingIntake) {
        Map<String, String> data = new LinkedHashMap<>(readConfigMap(runtimeConfigMapName()));
        try {
            if (incoming != null) {
                AgentConfig merged = mapper.readerForUpdating(currentRuntime(AgentConfig.class, KEY_AGENT))
                        .readValue(mapper.writeValueAsString(incoming));
                data.put(KEY_AGENT, mapper.writerWithDefaultPrettyPrinter().writeValueAsString(merged));
            }
            if (incomingIntake != null) {
                data.put(KEY_INTAKE, mapper.writerWithDefaultPrettyPrinter()
                        .writeValueAsString(incomingIntake));
            }
        } catch (Exception e) {
            throw new IllegalArgumentException("Could not serialise configuration: " + e.getMessage(), e);
        }
        writeConfigMap(runtimeConfigMapName(), data);
        reload();
    }

    public synchronized void saveSecrets(AgentSecrets incoming) {
        AgentSecrets current = readSecret(runtimeSecretName());
        current.mergeFrom(incoming);
        if (!current.llmConfigured()) {
            return;
        }
        writeSecret(runtimeSecretName(), Map.of(KEY_LLM_API_KEY, current.llmApiKey));
        reload();
    }

    /** Reset = delete the runtime objects. The seeded defaults simply reappear. */
    public synchronized void resetToDefaults() {
        if (inCluster) {
            try {
                client.configMaps().inNamespace(namespace).withName(runtimeConfigMapName()).delete();
                client.secrets().inNamespace(namespace).withName(runtimeSecretName()).delete();
            } catch (RuntimeException e) {
                LOG.warnf("reset: %s", e.getMessage());
            }
        } else {
            deleteLocal(runtimeConfigMapName());
            deleteLocal(runtimeSecretName());
        }
        reload();
    }

    private <T> T currentRuntime(Class<T> type, String key) throws Exception {
        String json = readConfigMap(runtimeConfigMapName()).get(key);
        T fresh = type.getDeclaredConstructor().newInstance();
        return json == null || json.isBlank() ? fresh : mapper.readerForUpdating(fresh).readValue(json);
    }

    private void writeConfigMap(String name, Map<String, String> data) {
        if (!inCluster) {
            writeLocal(name, data);
            return;
        }
        ConfigMap cm = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("app", agentName)
                .addToLabels(RUNTIME_LABEL, "runtime")
                .addToAnnotations("konzert.io/updated-at", java.time.Instant.now().toString())
                .endMetadata()
                .withData(data)
                .build();
        client.resource(cm).inNamespace(namespace).serverSideApply();
    }

    private void writeSecret(String name, Map<String, String> plain) {
        if (!inCluster) {
            writeLocal(name, plain);
            return;
        }
        Map<String, String> encoded = new LinkedHashMap<>();
        plain.forEach((k, v) -> encoded.put(k,
                Base64.getEncoder().encodeToString(v.getBytes(StandardCharsets.UTF_8))));
        Secret secret = new SecretBuilder()
                .withNewMetadata()
                .withName(name)
                .withNamespace(namespace)
                .addToLabels("app", agentName)
                .addToLabels(RUNTIME_LABEL, "runtime")
                .addToAnnotations("konzert.io/updated-at", java.time.Instant.now().toString())
                .endMetadata()
                .withType("Opaque")
                .withData(encoded)
                .build();
        client.resource(secret).inNamespace(namespace).serverSideApply();
    }

    // --------------------------------------------------------------- watching

    /**
     * Watching rather than polling means an operator doing `oc edit cm` changes
     * the running agent at once, and a change made through the API produces a
     * real Kubernetes object an operator can inspect.
     */
    private void startWatches() {
        try {
            watches.add(client.configMaps().inNamespace(namespace)
                    .withLabel("app", agentName)
                    .watch(new io.fabric8.kubernetes.client.Watcher<>() {
                        public void eventReceived(Action action, ConfigMap resource) {
                            LOG.debugf("configmap %s %s", resource.getMetadata().getName(), action);
                            reload();
                        }
                        public void onClose(io.fabric8.kubernetes.client.WatcherException cause) {
                            LOG.warnf("configmap watch closed: %s",
                                    cause == null ? "normally" : cause.getMessage());
                        }
                    }));
        } catch (RuntimeException e) {
            // Losing the watch costs live reload, not correctness — the next
            // read still sees the current object.
            LOG.warnf("no configmap watch (%s); changes will apply on next read", e.getMessage());
        }
    }

    // ------------------------------------------------------- local fallback

    private Map<String, String> readLocal(String name) {
        try {
            Path f = Path.of(dataDir, name + ".json");
            if (!Files.exists(f)) {
                return Map.of();
            }
            @SuppressWarnings("unchecked")
            Map<String, String> m = mapper.readValue(Files.readString(f), Map.class);
            return m;
        } catch (Exception e) {
            return Map.of();
        }
    }

    private void writeLocal(String name, Map<String, String> data) {
        try {
            Files.createDirectories(Path.of(dataDir));
            Files.writeString(Path.of(dataDir, name + ".json"), mapper.writeValueAsString(data));
        } catch (Exception e) {
            throw new IllegalStateException("Could not persist " + name, e);
        }
    }

    private void deleteLocal(String name) {
        try {
            Files.deleteIfExists(Path.of(dataDir, name + ".json"));
        } catch (Exception ignored) {
            // best effort
        }
    }
}
