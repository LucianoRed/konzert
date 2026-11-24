package io.konzert.examples.incident.ansible;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Optional;

/**
 * The closed allow-list of playbooks this server will run.
 *
 * This is the governance boundary of the whole example (ADR-0006): agents hold
 * no cluster credentials, so the only way anything changes is a playbook that
 * appears in this list AND ships inside this image. An agent cannot ask for an
 * arbitrary playbook, a path, or a shell command — there is no code path that
 * would accept one.
 */
@ApplicationScoped
public class PlaybookCatalog {

    /**
     * @param owner which agent is expected to run it — advisory, shown to the
     *              model so it self-selects; enforcement is the agent's RBAC
     *              story, not this field.
     */
    public record Playbook(
            String name,
            String file,
            String owner,
            String description,
            List<String> requiredVars,
            List<String> optionalVars,
            boolean mutating) {}

    private static final List<Playbook> CATALOG = List.of(
            new Playbook(
                    "set-deployment-limits.yml",
                    "set-deployment-limits.yml",
                    "atlas",
                    "Apply memory/CPU requests and limits to a Deployment's container and wait "
                            + "for the rollout to complete. Use this when a workload is consuming "
                            + "memory without declared limits.",
                    List.of("namespace", "deployment"),
                    List.of("memory_limit", "memory_request", "cpu_request", "container"),
                    true),
            new Playbook(
                    "revert-deployment-limits.yml",
                    "revert-deployment-limits.yml",
                    "atlas",
                    "Remove the resource block from a Deployment's container, putting it back the "
                            + "way it was before set-deployment-limits.yml. Used to reset the demo.",
                    List.of("namespace", "deployment"),
                    List.of("container"),
                    true),
            new Playbook(
                    "isolate-workload.yml",
                    "isolate-workload.yml",
                    "sentinel",
                    "Quarantine a workload with a NetworkPolicy: deny all ingress and all egress "
                            + "except cluster DNS. Use this to contain a compromised or scanning pod "
                            + "without deleting it, so it can still be investigated.",
                    List.of("namespace", "pod_selector_key", "pod_selector_value"),
                    List.of("policy_name"),
                    true),
            new Playbook(
                    "release-workload.yml",
                    "release-workload.yml",
                    "sentinel",
                    "Delete the quarantine NetworkPolicy created by isolate-workload.yml.",
                    List.of("namespace"),
                    List.of("policy_name"),
                    true),
            new Playbook(
                    "describe-workload.yml",
                    "describe-workload.yml",
                    "both",
                    "Read-only: report a Deployment's containers, declared resources, replica "
                            + "status and its pods. Use this to diagnose before acting.",
                    List.of("namespace", "deployment"),
                    List.of(),
                    false));

    public List<Playbook> all() {
        return CATALOG;
    }

    public Optional<Playbook> find(String name) {
        return CATALOG.stream().filter(p -> p.name().equals(name)).findFirst();
    }
}
