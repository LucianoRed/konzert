package io.konzert.examples.incident.console;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The injector. Two buttons on the panel, two real workloads on the cluster.
 *
 * Chaos lives with the console rather than with an agent on purpose: the demo
 * has to be one screen, and nothing that causes a problem should be able to
 * pretend it also detected it.
 */
@ApplicationScoped
public class ChaosService {

    private static final Logger LOG = Logger.getLogger(ChaosService.class);

    public static final Map<String, String> SCENARIOS = Map.of(
            "memory-leak", "chaos/memory-leak.yaml",
            "port-scan", "chaos/port-scan.yaml");

    @ConfigProperty(name = "konzert.namespace", defaultValue = "konzert-demo")
    String namespace;

    private KubernetesClient client;

    private synchronized KubernetesClient client() {
        if (client == null) {
            client = new KubernetesClientBuilder().build();
        }
        return client;
    }

    public String inject(String scenario) {
        String path = SCENARIOS.get(scenario);
        if (path == null) {
            return "Unknown scenario '" + scenario + "'. Available: " + SCENARIOS.keySet();
        }
        String yaml = render(path);
        client().load(stream(yaml)).inNamespace(namespace).serverSideApply();
        LOG.infof("injected %s into %s", scenario, namespace);
        return "Injected " + scenario + " into " + namespace;
    }

    public String revert(String scenario) {
        String path = SCENARIOS.get(scenario);
        if (path == null) {
            return "Unknown scenario '" + scenario + "'";
        }
        client().load(stream(render(path))).inNamespace(namespace).delete();
        return "Reverted " + scenario;
    }

    /** One button that puts the cluster back to the state the demo starts from. */
    public String revertAll() {
        StringBuilder sb = new StringBuilder();
        for (String s : SCENARIOS.keySet()) {
            sb.append(revert(s)).append("; ");
        }
        // The quarantine policies are Sentinel's doing, not a chaos manifest,
        // so they have to be cleaned up explicitly.
        try {
            client().network().v1().networkPolicies().inNamespace(namespace)
                    .withLabel("konzert.io/quarantine", "true").delete();
            sb.append("removed quarantine policies");
        } catch (RuntimeException e) {
            sb.append("could not remove quarantine policies: ").append(e.getMessage());
        }
        return sb.toString();
    }

    /** What is currently broken — so the panel's buttons can show state. */
    public Map<String, Object> state() {
        try {
            var deps = client().apps().deployments().inNamespace(namespace)
                    .withLabel("konzert.io/example", "true").list().getItems();
            List<String> running = deps.stream().map(d -> d.getMetadata().getName()).toList();
            var policies = client().network().v1().networkPolicies().inNamespace(namespace)
                    .list().getItems().stream().map(p -> p.getMetadata().getName()).toList();
            return Map.of("namespace", namespace, "workloads", running, "networkPolicies", policies);
        } catch (RuntimeException e) {
            return Map.of("namespace", namespace, "error", e.getMessage());
        }
    }

    private String render(String path) {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Manifest missing from the image: " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8)
                    .replace("${NAMESPACE}", namespace);
        } catch (Exception e) {
            throw new IllegalStateException("Could not read " + path, e);
        }
    }

    private static InputStream stream(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @PreDestroy
    void stop() {
        if (client != null) {
            client.close();
        }
    }
}
