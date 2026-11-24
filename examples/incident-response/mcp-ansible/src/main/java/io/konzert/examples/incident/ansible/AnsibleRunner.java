package io.konzert.examples.incident.ansible;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Runs a catalogued playbook with ansible-playbook and turns the result into
 * something an agent can reason about.
 *
 * Two deliberate choices:
 *
 * - The process is started from an argument list, never through a shell, and
 *   variables are handed over as one JSON blob. There is no string
 *   concatenation anywhere near a command line, so a variable value cannot
 *   become a command.
 * - The model gets the tail of the output, not all of it. Full text is kept in
 *   memory for the panel and for playbook_status.
 */
@ApplicationScoped
public class AnsibleRunner {

    public record Stats(int ok, int changed, int failed, int unreachable, int skipped) {}

    public record Run(
            String runId,
            String playbook,
            Map<String, String> extraVars,
            String status,
            Stats stats,
            long durationMs,
            String startedAt,
            String output) {}

    private static final Pattern RECAP = Pattern.compile(
            "ok=(\\d+)\\s+changed=(\\d+)\\s+unreachable=(\\d+)\\s+failed=(\\d+)(?:\\s+skipped=(\\d+))?");

    @Inject
    ObjectMapper mapper;

    @ConfigProperty(name = "ansible.playbook-dir")
    String playbookDir;
    @ConfigProperty(name = "ansible.timeout-seconds")
    int timeoutSeconds;

    private final java.util.concurrent.ExecutorService readers =
            java.util.concurrent.Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "ansible-output");
                t.setDaemon(true);
                return t;
            });

    /** Recent runs, so the panel and playbook_status can retrieve full output. */
    private final Map<String, Run> runs = new ConcurrentHashMap<>();
    private final List<String> runOrder = java.util.Collections.synchronizedList(new ArrayList<>());

    public Run run(PlaybookCatalog.Playbook playbook, Map<String, String> extraVars) {
        String runId = "run-" + UUID.randomUUID().toString().substring(0, 8);
        String startedAt = Instant.now().toString();
        long t0 = System.currentTimeMillis();

        List<String> cmd = new ArrayList<>(List.of(
                "ansible-playbook",
                Path.of(playbookDir, playbook.file()).toString(),
                "--extra-vars", toJson(extraVars)));

        String output;
        String status;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(Path.of(playbookDir).toFile());
            pb.redirectErrorStream(true);
            // Under restricted-v2 the container runs as a random UID with no home
            // directory, and ansible needs somewhere writable for its temp files.
            Map<String, String> env = pb.environment();
            env.put("HOME", "/tmp");
            env.put("ANSIBLE_LOCAL_TEMP", "/tmp/.ansible/tmp");
            env.put("ANSIBLE_REMOTE_TEMP", "/tmp/.ansible/tmp");
            env.put("ANSIBLE_HOST_KEY_CHECKING", "False");
            // No colour codes: this output ends up in a browser panel and in a prompt.
            env.put("ANSIBLE_FORCE_COLOR", "0");
            env.put("ANSIBLE_NOCOLOR", "1");

            Process proc = pb.start();

            // Drain the output on another thread. Reading it inline would block
            // until EOF — which only happens when the process exits — and would
            // silently defeat the timeout below.
            var drain = readers.submit(() ->
                    new String(proc.getInputStream().readAllBytes(), StandardCharsets.UTF_8));

            boolean finished = proc.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                proc.destroyForcibly();
                status = "timeout";
            } else {
                status = proc.exitValue() == 0 ? "ok" : "failed";
            }
            // Killing the process closes the pipe, so whatever was produced up to
            // that point is still recoverable.
            try {
                output = drain.get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                output = "(playbook output unavailable: " + e.getClass().getSimpleName() + ")";
            }
        } catch (IOException e) {
            output = "Failed to start ansible-playbook: " + e.getMessage();
            status = "error";
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            output = "Interrupted while running the playbook.";
            status = "error";
        }

        Run run = new Run(runId, playbook.name(), extraVars, status,
                parseStats(output), System.currentTimeMillis() - t0, startedAt, output);
        remember(run);
        return run;
    }

    public Run byId(String runId) {
        return runs.get(runId);
    }

    public Run last() {
        synchronized (runOrder) {
            return runOrder.isEmpty() ? null : runs.get(runOrder.get(runOrder.size() - 1));
        }
    }

    private void remember(Run run) {
        runs.put(run.runId(), run);
        synchronized (runOrder) {
            runOrder.add(run.runId());
            // Bounded: this is a demo server, not an audit store. The durable
            // record of an action is the CloudEvent the agent publishes to
            // konzert.actions.
            while (runOrder.size() > 50) {
                runs.remove(runOrder.remove(0));
            }
        }
    }

    private static Stats parseStats(String output) {
        Matcher m = RECAP.matcher(output == null ? "" : output);
        int ok = 0, changed = 0, unreachable = 0, failed = 0, skipped = 0;
        while (m.find()) {
            ok += Integer.parseInt(m.group(1));
            changed += Integer.parseInt(m.group(2));
            unreachable += Integer.parseInt(m.group(3));
            failed += Integer.parseInt(m.group(4));
            if (m.group(5) != null) {
                skipped += Integer.parseInt(m.group(5));
            }
        }
        return new Stats(ok, changed, failed, unreachable, skipped);
    }

    private String toJson(Map<String, String> vars) {
        try {
            return mapper.writeValueAsString(new LinkedHashMap<>(vars));
        } catch (Exception e) {
            throw new IllegalStateException("Could not serialise playbook variables", e);
        }
    }
}
