package io.konzert.worker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * A deliberately small MCP client: JSON-RPC over the SSE transport, nothing else.
 *
 * The server sends an {@code endpoint} event with the URL to POST to; every
 * request goes there and its response comes back on the event stream, matched
 * by id. Small enough to read on a slide, which is the point — the agents'
 * "hands" must not be a black box.
 */
public class McpSseClient implements AutoCloseable {

    private static final Logger LOG = Logger.getLogger(McpSseClient.class);

    public record ToolResult(String text, boolean isError) {}

    private final URI sseUri;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    private final AtomicLong ids = new AtomicLong(1);
    private final Map<Long, CompletableFuture<JsonNode>> pending = new ConcurrentHashMap<>();

    private volatile URI postUri;
    private volatile CompletableFuture<URI> endpoint = new CompletableFuture<>();
    private volatile boolean connected;

    public McpSseClient(String sseUrl, ObjectMapper mapper) {
        this.sseUri = URI.create(sseUrl);
        this.mapper = mapper;
    }

    public boolean connected() {
        return connected && postUri != null;
    }

    public synchronized void ensureConnected() throws IOException {
        if (connected()) {
            return;
        }
        connect();
    }

    private void connect() throws IOException {
        pending.clear();
        postUri = null;
        endpoint = new CompletableFuture<>();
        HttpRequest req = HttpRequest.newBuilder(sseUri)
                .header("Accept", "text/event-stream")
                .timeout(Duration.ofSeconds(15))
                .GET()
                .build();
        HttpResponse<InputStream> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while connecting to " + sseUri);
        }
        if (res.statusCode() != 200) {
            throw new IOException("SSE endpoint answered HTTP " + res.statusCode());
        }
        connected = true;
        Thread reader = new Thread(() -> readLoop(res.body()), "mcp-sse");
        reader.setDaemon(true);
        reader.start();
        try {
            postUri = endpoint.get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            connected = false;
            throw new IOException("no endpoint event from " + sseUri + ": " + e.getMessage());
        }
        ObjectNode params = mapper.createObjectNode();
        params.put("protocolVersion", "2024-11-05");
        params.putObject("capabilities");
        params.putObject("clientInfo").put("name", "konzert-worker").put("version", "0.1.0");
        request("initialize", params, 20);
        notify("notifications/initialized");
        LOG.infof("MCP session open at %s", postUri);
    }

    private void readLoop(InputStream in) {
        try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            String event = null;
            StringBuilder data = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isEmpty()) {
                    dispatch(event, data.toString());
                    event = null;
                    data.setLength(0);
                    continue;
                }
                if (line.startsWith(":")) {
                    continue;
                }
                if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    if (data.length() > 0) {
                        data.append('\n');
                    }
                    data.append(line.substring(5).trim());
                }
            }
        } catch (IOException e) {
            LOG.warnf("MCP event stream ended: %s", e.getMessage());
        } finally {
            connected = false;
            postUri = null;
            IOException closed = new IOException("MCP event stream closed");
            pending.values().forEach(f -> f.completeExceptionally(closed));
            pending.clear();
            if (!endpoint.isDone()) {
                endpoint.completeExceptionally(closed);
            }
        }
    }

    private void dispatch(String event, String data) {
        if (data.isEmpty()) {
            return;
        }
        if ("endpoint".equals(event)) {
            endpoint.complete(sseUri.resolve(data));
            return;
        }
        try {
            JsonNode n = mapper.readTree(data);
            if (n.hasNonNull("id")) {
                CompletableFuture<JsonNode> f = pending.remove(n.get("id").asLong());
                if (f != null) {
                    f.complete(n);
                }
            }
        } catch (Exception e) {
            LOG.debugf("ignoring non-JSON event: %s", data);
        }
    }

    private JsonNode request(String method, ObjectNode params, long timeoutSeconds) throws IOException {
        long id = ids.getAndIncrement();
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        if (params != null) {
            body.set("params", params);
        }
        CompletableFuture<JsonNode> f = new CompletableFuture<>();
        pending.put(id, f);
        post(body);
        try {
            JsonNode reply = f.get(timeoutSeconds, TimeUnit.SECONDS);
            if (reply.has("error")) {
                throw new IOException("MCP error: " + reply.get("error"));
            }
            return reply.path("result");
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            pending.remove(id);
            throw new IOException(method + " failed: " + e.getMessage());
        }
    }

    private void notify(String method) throws IOException {
        ObjectNode body = mapper.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("method", method);
        post(body);
    }

    private void post(ObjectNode body) throws IOException {
        HttpRequest req = HttpRequest.newBuilder(postUri)
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(30))
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();
        HttpResponse<String> res;
        try {
            res = http.send(req, HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted");
        }
        if (res.statusCode() >= 300) {
            throw new IOException("MCP POST answered HTTP " + res.statusCode() + ": " + res.body());
        }
    }

    public List<JsonNode> listTools() throws IOException {
        ensureConnected();
        List<JsonNode> out = new ArrayList<>();
        request("tools/list", mapper.createObjectNode(), 20).path("tools").forEach(out::add);
        return out;
    }

    public ToolResult callTool(String name, ObjectNode arguments, long timeoutSeconds) throws IOException {
        ensureConnected();
        ObjectNode params = mapper.createObjectNode();
        params.put("name", name);
        params.set("arguments", arguments == null ? mapper.createObjectNode() : arguments);
        JsonNode result = request("tools/call", params, timeoutSeconds);
        StringBuilder text = new StringBuilder();
        for (JsonNode c : result.path("content")) {
            if ("text".equals(c.path("type").asText())) {
                if (text.length() > 0) {
                    text.append('\n');
                }
                text.append(c.path("text").asText(""));
            }
        }
        return new ToolResult(text.toString(), result.path("isError").asBoolean(false));
    }

    @Override
    public void close() {
        connected = false;
    }
}
