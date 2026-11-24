package io.konzert.examples.incident.sensor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.konzert.core.events.CloudEvent;
import io.konzert.core.events.KafkaBus;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.Quantity;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.metrics.v1beta1.PodMetrics;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Turns cluster reality into events. No LLM, no opinions: a live metric
 * correlated with a spec omission, or a log pattern, becomes one CloudEvent
 * on {@code konzert.events} — and a fix that holds becomes its confirmation, under
 * the same correlation ID.
 *
 * It does not know a chaos injection happened. That is the point.
 */
@ApplicationScoped
public class Sensor {

    private static final Logger LOG = Logger.getLogger(Sensor.class);
    private static final String KONZERT_LABEL = "konzert.io/example";
    private static final String QUARANTINE_LABEL = "konzert.io/quarantine";

    @Inject
    KafkaBus bus;
    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "konzert.namespace")
    String namespace;
    @ConfigProperty(name = "konzert.events-topic")
    String topic;
    @ConfigProperty(name = "sensor.interval-seconds")
    int intervalSeconds;
    @ConfigProperty(name = "sensor.memory-threshold-mb")
    long memoryThresholdMb;
    @ConfigProperty(name = "sensor.scan-pattern")
    String scanPattern;
    @ConfigProperty(name = "sensor.scan-lines-threshold")
    int scanLinesThreshold;
    @ConfigProperty(name = "sensor.scan-window-seconds")
    int scanWindowSeconds;
    @ConfigProperty(name = "sensor.confirm-hold-seconds")
    int confirmHoldSeconds;

    /** One open story per workload and kind of trouble. */
    static final class Incident {
        final String correlation;
        final String workload;
        final String type;
        final long since = System.currentTimeMillis();
        long fixedSince;

        Incident(String correlation, String workload, String type) {
            this.correlation = correlation;
            this.workload = workload;
            this.type = type;
        }
    }

    private final Map<String, Incident> open = new ConcurrentHashMap<>();
    private final Map<String, Long> lastMemory = new HashMap<>();
    private KubernetesClient client;
    private volatile boolean running;
    private boolean metricsWarned;

    void onStart(@Observes StartupEvent ev) {
        client = new KubernetesClientBuilder().build();
        running = true;
        Thread t = new Thread(this::loop, "konzert-sensor");
        t.setDaemon(true);
        t.start();
        LOG.infof("watching %s (every %ds, memory > %dMi without limits, >= %d '%s' lines / %ds)",
                namespace, intervalSeconds, memoryThresholdMb, scanLinesThreshold, scanPattern, scanWindowSeconds);
    }

    void onStop(@Observes ShutdownEvent ev) {
        running = false;
        if (client != null) {
            client.close();
        }
    }

    private void loop() {
        while (running) {
            try {
                tick();
            } catch (RuntimeException e) {
                LOG.warnf("tick failed: %s", e.getMessage());
            }
            try {
                Thread.sleep(intervalSeconds * 1000L);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    void tick() {
        List<Deployment> deployments = client.apps().deployments().inNamespace(namespace)
                .withLabel(KONZERT_LABEL, "true").list().getItems();
        List<Pod> pods = client.pods().inNamespace(namespace)
                .withLabel(KONZERT_LABEL, "true").list().getItems();
        Map<String, Long> memoryByPod = podMemory();

        watchMemory(deployments, pods, memoryByPod);
        watchLogs(deployments, pods);
    }

    // ------------------------------------------------------ incident 1: memory

    private void watchMemory(List<Deployment> deployments, List<Pod> pods, Map<String, Long> memoryByPod) {
        List<String> alive = new ArrayList<>();
        for (Deployment d : deployments) {
            String name = d.getMetadata().getName();
            alive.add(name);
            String key = "memory/" + name;
            String limit = memoryLimit(d);
            List<Pod> mine = podsOf(d, pods);
            long mem = mine.stream().mapToLong(p -> memoryByPod.getOrDefault(p.getMetadata().getName(), 0L)).max().orElse(0L);
            long memMi = mem / (1024 * 1024);
            Long previous = lastMemory.put(name, mem);
            Incident inc = open.get(key);

            if (inc == null) {
                if (limit == null && memMi > memoryThresholdMb) {
                    String correlation = CloudEvent.newIncidentId();
                    ObjectNode data = base(name);
                    ObjectNode ev = data.putObject("evidence");
                    ev.put("podMemoryBytes", mem);
                    ev.put("podMemoryMi", memMi);
                    ev.put("limitsDeclared", false);
                    ev.put("trend", previous == null ? "unknown"
                            : String.format("%+dMi/%ds", (mem - previous) / (1024 * 1024), intervalSeconds));
                    ev.put("thresholdMi", memoryThresholdMb);
                    ev.put("pods", mine.size());
                    publish("MemoryPressureDetected", correlation, data);
                    open.put(key, new Incident(correlation, name, "memory"));
                    LOG.infof("%s: %dMi with no memory limit -> %s", name, memMi, correlation);
                }
                continue;
            }

            boolean ready = mine.stream().anyMatch(Sensor::isReady);
            if (limit != null && ready) {
                if (inc.fixedSince == 0) {
                    inc.fixedSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - inc.fixedSince >= confirmHoldSeconds * 1000L) {
                    ObjectNode data = base(name);
                    data.put("resolution", "memory limit " + limit + " declared; pod ready for " + confirmHoldSeconds + "s");
                    ObjectNode ev = data.putObject("evidence");
                    ev.put("podMemoryMi", memMi);
                    ev.put("limitsDeclared", true);
                    ev.put("memoryLimit", limit);
                    ev.put("heldFor", confirmHoldSeconds + "s");
                    publish("IncidentResolvedConfirmed", inc.correlation, data);
                    open.remove(key);
                    LOG.infof("%s: bounded by %s, confirmed", name, limit);
                }
            } else {
                inc.fixedSince = 0;
            }
        }
        // A reverted workload takes its story with it; nothing to confirm.
        open.keySet().removeIf(k -> k.startsWith("memory/") && !alive.contains(k.substring("memory/".length())));
    }

    // ---------------------------------------------------- incident 2: port scan

    private void watchLogs(List<Deployment> deployments, List<Pod> pods) {
        List<String> alive = new ArrayList<>();
        for (Pod p : pods) {
            if (!"Running".equals(p.getStatus().getPhase())) {
                continue;
            }
            String workload = workloadOf(p, deployments);
            alive.add(workload);
            String key = "network/" + workload;
            Incident inc = open.get(key);

            if (inc == null) {
                // A quarantined pod keeps logging its attempts; that is contained,
                // not a new incident.
                if (quarantineFor(p) != null) {
                    continue;
                }
                List<String> hits = suspiciousLines(p);
                if (hits.size() >= scanLinesThreshold) {
                    String correlation = CloudEvent.newIncidentId();
                    ObjectNode data = base(workload);
                    data.put("pod", p.getMetadata().getName());
                    ObjectNode ev = data.putObject("evidence");
                    ev.put("pattern", scanPattern);
                    ev.put("matchedLines", hits.size());
                    ev.put("window", scanWindowSeconds + "s");
                    ArrayNode sample = ev.putArray("sample");
                    hits.stream().limit(4).forEach(sample::add);
                    publish("SuspiciousNetworkActivityDetected", correlation, data);
                    open.put(key, new Incident(correlation, workload, "network"));
                    LOG.infof("%s: %d scan-like log lines -> %s", workload, hits.size(), correlation);
                }
                continue;
            }

            NetworkPolicy quarantine = quarantineFor(p);
            if (quarantine != null) {
                if (inc.fixedSince == 0) {
                    inc.fixedSince = System.currentTimeMillis();
                } else if (System.currentTimeMillis() - inc.fixedSince >= confirmHoldSeconds * 1000L) {
                    ObjectNode data = base(workload);
                    data.put("pod", p.getMetadata().getName());
                    data.put("resolution", "quarantined by NetworkPolicy " + quarantine.getMetadata().getName()
                            + "; pod still running for inspection");
                    ObjectNode ev = data.putObject("evidence");
                    ev.put("networkPolicy", quarantine.getMetadata().getName());
                    ev.set("podSelector", mapper.valueToTree(quarantine.getSpec().getPodSelector().getMatchLabels()));
                    ev.put("heldFor", confirmHoldSeconds + "s");
                    publish("IncidentResolvedConfirmed", inc.correlation, data);
                    open.remove(key);
                    LOG.infof("%s: quarantined by %s, confirmed", workload, quarantine.getMetadata().getName());
                }
            } else {
                inc.fixedSince = 0;
            }
        }
        open.keySet().removeIf(k -> k.startsWith("network/") && !alive.contains(k.substring("network/".length())));
    }

    private List<String> suspiciousLines(Pod p) {
        try {
            String log = client.pods().inNamespace(namespace).withName(p.getMetadata().getName())
                    .sinceSeconds(scanWindowSeconds).tailingLines(400).getLog();
            List<String> hits = new ArrayList<>();
            for (String line : log.split("\n")) {
                if (line.contains(scanPattern)) {
                    hits.add(line.length() > 160 ? line.substring(0, 160) : line);
                }
            }
            return hits;
        } catch (RuntimeException e) {
            LOG.debugf("no logs from %s: %s", p.getMetadata().getName(), e.getMessage());
            return List.of();
        }
    }

    private NetworkPolicy quarantineFor(Pod p) {
        try {
            for (NetworkPolicy np : client.network().v1().networkPolicies().inNamespace(namespace)
                    .withLabel(QUARANTINE_LABEL, "true").list().getItems()) {
                Map<String, String> sel = np.getSpec().getPodSelector() == null ? null
                        : np.getSpec().getPodSelector().getMatchLabels();
                if (sel != null && !sel.isEmpty() && p.getMetadata().getLabels() != null
                        && p.getMetadata().getLabels().entrySet().containsAll(sel.entrySet())) {
                    return np;
                }
            }
        } catch (RuntimeException e) {
            LOG.debugf("cannot list network policies: %s", e.getMessage());
        }
        return null;
    }

    // ------------------------------------------------------------- helpers

    private Map<String, Long> podMemory() {
        Map<String, Long> out = new HashMap<>();
        try {
            for (PodMetrics pm : client.top().pods().metrics(namespace).getItems()) {
                long total = 0;
                for (var c : pm.getContainers()) {
                    Quantity q = c.getUsage().get("memory");
                    if (q != null) {
                        total += Quantity.getAmountInBytes(q).longValue();
                    }
                }
                out.put(pm.getMetadata().getName(), total);
            }
            metricsWarned = false;
        } catch (RuntimeException e) {
            if (!metricsWarned) {
                LOG.warnf("metrics.k8s.io unavailable (%s); memory incidents cannot be detected", e.getMessage());
                metricsWarned = true;
            }
        }
        return out;
    }

    private static String memoryLimit(Deployment d) {
        for (Container c : d.getSpec().getTemplate().getSpec().getContainers()) {
            if (c.getResources() != null && c.getResources().getLimits() != null) {
                Quantity q = c.getResources().getLimits().get("memory");
                if (q != null) {
                    return q.toString();
                }
            }
        }
        return null;
    }

    private static List<Pod> podsOf(Deployment d, List<Pod> pods) {
        Map<String, String> sel = d.getSpec().getSelector().getMatchLabels();
        List<Pod> out = new ArrayList<>();
        for (Pod p : pods) {
            Map<String, String> labels = p.getMetadata().getLabels();
            if (labels != null && sel != null && labels.entrySet().containsAll(sel.entrySet())) {
                out.add(p);
            }
        }
        return out;
    }

    private static String workloadOf(Pod p, List<Deployment> deployments) {
        for (Deployment d : deployments) {
            if (!podsOf(d, List.of(p)).isEmpty()) {
                return d.getMetadata().getName();
            }
        }
        Map<String, String> labels = p.getMetadata().getLabels();
        return labels != null && labels.containsKey("app") ? labels.get("app") : p.getMetadata().getName();
    }

    private static boolean isReady(Pod p) {
        return p.getStatus() != null && p.getStatus().getConditions() != null
                && p.getStatus().getConditions().stream()
                .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));
    }

    private ObjectNode base(String workload) {
        ObjectNode data = mapper.createObjectNode();
        data.put("namespace", namespace);
        data.put("workload", workload);
        data.put("kind", "Deployment");
        return data;
    }

    private void publish(String type, String correlation, ObjectNode data) {
        bus.publish(topic, CloudEvent.of("io.konzert." + type, "/konzert/sensor", correlation, data));
    }

    /** For a health/debug endpoint: what the sensor believes is broken. */
    public Map<String, String> openIncidents() {
        Map<String, String> out = new HashMap<>();
        open.forEach((k, v) -> out.put(k, v.correlation));
        return out;
    }
}
