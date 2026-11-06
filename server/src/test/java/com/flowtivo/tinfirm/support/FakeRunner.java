package com.flowtivo.tinfirm.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Predicate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A runner host for tests: a real WebSocket client on {@code /api/runner/v1/connect} that plays the protocol by
 * hand, in the version of the {@code hello} it sent (v1 until then; {@link #helloV2(List, List)} for v2). Every frame
 * the server sends is validated against {@code protocol/runner-v1.schema.json} or {@code runner-v2.schema.json}; a
 * frame that does not match fails the next {@link #expect} (and {@link #assertFramesValid()}). TEST FIXTURE ONLY.
 * <p>
 * Like the real host (docs/archive/v1/CONVENTIONS.md §15.7), it answers every {@code host.config} it applies with
 * {@code host.config.applied}: the effective values {@code min(desired, ceiling)} for the ceilings of its hello,
 * ignoring a version older than the last one it applied. Without that answer the server assigns it no run.
 * {@link #manualConfig()} turns the answer off, for tests that confirm with {@link #applyConfig} themselves.
 */
public final class FakeRunner implements AutoCloseable {

    public static final Duration TIMEOUT = Duration.ofSeconds(20);

    /** The slots of {@link #hello(List, List)}: the host's ceiling. */
    public static final int DEFAULT_MAX_CONCURRENT_RUNS = 4;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final Object sendLock = new Object();

    /** Answers host.config off the WebSocket's listener thread, in order. */
    private final ExecutorService replies = Executors.newSingleThreadExecutor(Thread.ofVirtual().factory());

    private volatile boolean autoApply = true;

    /** The runner protocol of the last hello sent: the schema the server's frames are validated against. */
    private volatile int protocolVersion = 1;

    /** The capacity state of the host: its ceilings from hello and what it applied on this connection. */
    private final Object capacity = new Object();

    private int ceiling = DEFAULT_MAX_CONCURRENT_RUNS;

    private final Map<String, Integer> poolCeilings = new LinkedHashMap<>();

    /** The host's {@code hello.pool.maxReady}; null for a host without {@code sandbox.pool}. */
    private Integer readyCeiling;

    private long appliedVersion = -1;

    private JsonNode appliedConfig;

    private boolean draining;

    private final List<JsonNode> received = new CopyOnWriteArrayList<>();

    private final List<String> violations = new CopyOnWriteArrayList<>();

    private final CompletableFuture<Integer> closed = new CompletableFuture<>();

    private final Object lock = new Object();

    private int consumed;

    private final List<JsonNode> unconsumed = new ArrayList<>();

    private final WebSocket socket;

    private FakeRunner(int port, String authorization) {
        HttpClient client = HttpClient.newHttpClient();
        WebSocket.Builder builder = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        if (authorization != null) {
            builder.header("Authorization", authorization);
        }
        this.socket = builder.buildAsync(URI.create("ws://localhost:" + port + "/api/runner/v1/connect"),
                new Listener()).join();
    }

    /** Connects with {@code Authorization: Bearer <token>}; no frame is sent yet. */
    public static FakeRunner connect(int port, String token) {
        return new FakeRunner(port, "Bearer " + token);
    }

    /** Connects with a raw Authorization header value, or none when null. */
    public static FakeRunner connectWithHeader(int port, String authorization) {
        return new FakeRunner(port, authorization);
    }

    /** A valid {@code hello} with one credential per entry of {@code credentials} (label, runtime, pool size). */
    public static Map<String, Object> hello(List<Map<String, Object>> credentials, List<Map<String, Object>> activeRuns) {
        return hello(credentials, activeRuns, DEFAULT_MAX_CONCURRENT_RUNS);
    }

    /** A valid {@code hello} with the given ceiling of slots, and the images of both runtimes. */
    public static Map<String, Object> hello(List<Map<String, Object>> credentials, List<Map<String, Object>> activeRuns,
            int maxConcurrentRuns) {
        return hello(credentials, activeRuns, maxConcurrentRuns, List.of("CLAUDE_CODE", "CODEX"));
    }

    /**
     * A valid {@code hello} with the given ceiling of slots that reports the images of these runtimes only: the
     * runtimes the host can run (docs/archive/v1/CONVENTIONS.md §15.1).
     */
    public static Map<String, Object> hello(List<Map<String, Object>> credentials, List<Map<String, Object>> activeRuns,
            int maxConcurrentRuns, List<String> runtimes) {
        Map<String, Object> hello = new LinkedHashMap<>();
        hello.put("type", "hello");
        hello.put("protocolVersion", 1);
        hello.put("runnerVersion", "0.1.0-test");
        hello.put("hostname", "test-host");
        hello.put("os", "linux");
        hello.put("arch", "x86_64");
        hello.put("executors", List.of("docker"));
        hello.put("isolationTiers", List.of("container"));
        hello.put("maxConcurrentRuns", maxConcurrentRuns);
        hello.put("credentials", credentials);
        hello.put("runtimes", runtimes.stream()
            .map(runtime -> Map.of("runtime", runtime, "image",
                    "CODEX".equals(runtime) ? "tinfirm-runner-codex:local" : "tinfirm-runner-claude:local"))
            .toList());
        hello.put("activeRuns", activeRuns);
        return hello;
    }

    /** A valid {@code hello} of protocol v2 from a Kata host ({@code microvm}). */
    public static Map<String, Object> helloV2(List<Map<String, Object>> credentials,
            List<Map<String, Object>> activeRuns) {
        Map<String, Object> hello = hello(credentials, activeRuns);
        hello.put("protocolVersion", 2);
        hello.put("isolationTiers", List.of("microvm"));
        return hello;
    }

    /**
     * A {@code hello} of protocol v2 from a runner host of this version with the given slots and the images of every
     * runtime: from {@code 0.2.0} on, the server sends it runs paid by a budget (the package 2 contract, §7).
     */
    public static Map<String, Object> helloV2(List<Map<String, Object>> credentials,
            List<Map<String, Object>> activeRuns, String runnerVersion, int maxConcurrentRuns) {
        Map<String, Object> hello = hello(credentials, activeRuns, maxConcurrentRuns,
                List.of("CLAUDE_CODE", "CODEX", "PI", "OPENCODE"));
        hello.put("protocolVersion", 2);
        hello.put("runnerVersion", runnerVersion);
        return hello;
    }

    /**
     * A {@code hello} of protocol v2 from a host that keeps ready sandboxes (the feature {@code sandbox.pool},
     * ADR-0099): a VM per run, so over the sandbox bridge (ADR-0100), with these slots and the operator's ceiling of
     * ready sandboxes.
     */
    public static Map<String, Object> helloWithPool(int maxConcurrentRuns, int maxReady) {
        Map<String, Object> hello = helloV2(List.of(), List.of(), "0.3.0", maxConcurrentRuns);
        hello.put("isolationTiers", List.of("microvm"));
        hello.put("features", List.of("sandbox.pool", "sandbox.bridge"));
        hello.put("pool", Map.of("maxReady", maxReady));
        return hello;
    }

    /** Sends a {@code hello} of protocol v2 of this runner version and slots, and waits for welcome. */
    public JsonNode helloV2AndWelcome(List<Map<String, Object>> credentials, List<Map<String, Object>> activeRuns,
            String runnerVersion, int maxConcurrentRuns) {
        send(helloV2(credentials, activeRuns, runnerVersion, maxConcurrentRuns));
        return expect("welcome");
    }

    public static Map<String, Object> credential(String label, String kind, List<String> runtimes, int poolSize) {
        return Map.of("label", label, "kind", kind, "runtimes", runtimes, "poolSize", poolSize);
    }

    public static Map<String, Object> claudeCredential(String label) {
        return credential(label, "CLAUDE_LOGIN", List.of("CLAUDE_CODE"), 2);
    }

    /** Sends a hello of protocol v2 and waits for welcome. */
    public JsonNode helloV2AndWelcome(List<Map<String, Object>> credentials) {
        send(helloV2(credentials, List.of()));
        return expect("welcome");
    }

    /** The runner protocol of the last hello sent. */
    public int protocolVersion() {
        return protocolVersion;
    }

    /** Sends hello and waits for welcome. */
    public JsonNode helloAndWelcome(List<Map<String, Object>> credentials) {
        return helloAndWelcome(credentials, List.of());
    }

    public JsonNode helloAndWelcome(List<Map<String, Object>> credentials, List<Map<String, Object>> activeRuns) {
        send(hello(credentials, activeRuns));
        return expect("welcome");
    }

    /** Sends hello with the given ceiling of slots and the images of these runtimes only, and waits for welcome. */
    public JsonNode helloAndWelcome(List<Map<String, Object>> credentials, int maxConcurrentRuns,
            List<String> runtimes) {
        send(hello(credentials, List.of(), maxConcurrentRuns, runtimes));
        return expect("welcome");
    }

    /** Sends hello with the given ceiling of slots and waits for welcome. */
    public JsonNode helloAndWelcome(List<Map<String, Object>> credentials, int maxConcurrentRuns) {
        send(hello(credentials, List.of(), maxConcurrentRuns));
        return expect("welcome");
    }

    /**
     * Stops answering {@code host.config} by itself; call before hello. The server then assigns nothing until the
     * test confirms a configuration with {@link #applyConfig}.
     */
    public FakeRunner manualConfig() {
        this.autoApply = false;
        return this;
    }

    /** From now on, answer every {@code host.config} again, starting with none: call {@link #applyConfig} once. */
    public void autoConfig() {
        this.autoApply = true;
    }

    /** One frame; a hello also sets the ceilings this host confirms. */
    public void send(Object message) {
        if (message instanceof Map<?, ?> map && "hello".equals(map.get("type"))) {
            rememberCeilings(map);
            if (map.get("protocolVersion") instanceof Number version) {
                protocolVersion = version.intValue();
            }
        }
        String text = message instanceof String raw ? raw : JSON.writeValueAsString(message);
        synchronized (sendLock) {
            socket.sendText(text, true).join();
        }
    }

    /**
     * Applies a {@code host.config} like the real host and answers it with {@code host.config.applied}; a version
     * older than the last one applied on this connection is ignored and not answered.
     *
     * @return the answer, or null when the configuration was ignored
     */
    public Map<String, Object> applyConfig(JsonNode config) {
        Map<String, Object> applied;
        synchronized (capacity) {
            long version = config.get("version").asLong();
            if (version < appliedVersion) {
                return null;
            }
            appliedVersion = version;
            appliedConfig = config;
            applied = appliedFrame();
        }
        send(applied);
        return applied;
    }

    /** Starts to drain, as after SIGHUP: sends {@code host.config.applied} with {@code draining} and 0 slots. */
    public Map<String, Object> startDraining() {
        Map<String, Object> applied;
        synchronized (capacity) {
            draining = true;
            applied = appliedFrame();
        }
        send(applied);
        return applied;
    }

    /** A {@code host.config.applied} for the ceilings of hello and the last applied configuration. */
    private Map<String, Object> appliedFrame() {
        JsonNode config = appliedConfig;
        Integer desiredSlots = config != null && config.hasNonNull("desiredMaxConcurrentRuns")
                ? config.get("desiredMaxConcurrentRuns").asInt() : null;
        Map<String, Integer> desiredPools = new LinkedHashMap<>();
        if (config != null) {
            config.get("credentials").forEach(entry -> desiredPools.put(entry.get("label").asString(),
                    entry.get("desiredPoolSize").asInt()));
        }
        List<Map<String, Object>> credentials = new ArrayList<>();
        poolCeilings.forEach((label, poolCeiling) -> credentials.add(Map.of("label", label, "poolSize",
                effective(desiredPools.get(label), poolCeiling), "poolSizeCeiling", poolCeiling)));
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("type", "host.config.applied");
        applied.put("version", Math.max(appliedVersion, 0));
        applied.put("draining", draining);
        applied.put("maxConcurrentRuns", draining ? 0 : effective(desiredSlots, ceiling));
        applied.put("maxConcurrentRunsCeiling", ceiling);
        applied.put("credentials", credentials);
        if (readyCeiling != null) {
            // Tinfirm's count up to the operator's ceiling; none while draining (ADR-0099).
            int asked = config != null && config.hasNonNull("pool") ? config.get("pool").get("readyTarget").asInt() : 0;
            applied.put("pool", Map.of("readyTarget", draining ? 0 : Math.min(asked, readyCeiling), "maxReadyCeiling",
                    readyCeiling));
        }
        return applied;
    }

    private static int effective(Integer desired, int ceiling) {
        return desired != null ? Math.min(desired, ceiling) : ceiling;
    }

    private void rememberCeilings(Map<?, ?> hello) {
        synchronized (capacity) {
            if (hello.get("maxConcurrentRuns") instanceof Number slots) {
                ceiling = slots.intValue();
            }
            readyCeiling = hello.get("pool") instanceof Map<?, ?> pool && pool.get("maxReady") instanceof Number ready
                    ? ready.intValue() : null;
            poolCeilings.clear();
            if (hello.get("credentials") instanceof List<?> credentials) {
                for (Object credential : credentials) {
                    if (credential instanceof Map<?, ?> entry && entry.get("poolSize") instanceof Number pool) {
                        poolCeilings.put(String.valueOf(entry.get("label")), pool.intValue());
                    }
                }
            }
        }
    }

    public void accepted(UUID runId, long epoch) {
        send(Map.of("type", "run.accepted", "runId", runId.toString(), "epoch", epoch));
    }

    public void event(UUID runId, long epoch, long seq, String eventType, Map<String, Object> payload) {
        send(Map.of("type", "run.event", "runId", runId.toString(), "epoch", epoch, "seq", seq, "eventType",
                eventType, "at", Instant.now().toString(), "payload", payload));
    }

    /** A {@code run.request} of protocol v2. */
    public void request(UUID runId, long epoch, UUID requestId, String kind, Map<String, Object> payload) {
        send(Map.of("type", "run.request", "runId", runId.toString(), "epoch", epoch, "requestId",
                requestId.toString(), "kind", kind, "payload", payload));
    }

    /** Sends a {@code run.request} and waits for its {@code run.request.ack}. */
    public JsonNode requestAndAck(UUID runId, long epoch, String kind, Map<String, Object> payload) {
        UUID requestId = UUID.randomUUID();
        request(runId, epoch, requestId, kind, payload);
        return expectAck(requestId);
    }

    /** The next {@code run.request.ack} of this request; acks of other requests stay available. */
    public JsonNode expectAck(UUID requestId) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        synchronized (lock) {
            while (true) {
                drain();
                assertFramesValid();
                for (Iterator<JsonNode> it = unconsumed.iterator(); it.hasNext();) {
                    JsonNode frame = it.next();
                    if ("run.request.ack".equals(frame.get("type").asString())
                            && requestId.toString().equals(frame.get("requestId").asString())) {
                        it.remove();
                        return frame;
                    }
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    throw new AssertionError("No run.request.ack of " + requestId + " within " + TIMEOUT
                            + "; received " + received);
                }
                try {
                    lock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
        }
    }

    public void heartbeat(UUID runId, long epoch) {
        send(Map.of("type", "run.heartbeat", "runs", List.of(Map.of("runId", runId.toString(), "epoch", epoch))));
    }

    /** The next frame of this type that no earlier {@code expect} returned; other frames stay available. */
    public JsonNode expect(String type) {
        return expect(type, TIMEOUT);
    }

    public JsonNode expect(String type, Duration timeout) {
        return expect(type, frame -> true, timeout);
    }

    /** The next unconsumed frame of this type that matches; the frames of this type before it are consumed. */
    public JsonNode expect(String type, Predicate<JsonNode> matching) {
        return expect(type, matching, TIMEOUT);
    }

    private JsonNode expect(String type, Predicate<JsonNode> matching, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (true) {
            Optional<JsonNode> frame = next(type, Duration.ofNanos(Math.max(0, deadline - System.nanoTime())));
            if (frame.isEmpty()) {
                throw new AssertionError("No matching " + type + " frame within " + timeout + "; received "
                        + received);
            }
            if (matching.test(frame.get())) {
                return frame.get();
            }
        }
    }

    /** Asserts that no frame of this type arrives within the given time. */
    public void expectNone(String type, Duration within) {
        next(type, within).ifPresent(frame -> {
            throw new AssertionError("Unexpected " + type + " frame: " + frame);
        });
    }

    private Optional<JsonNode> next(String type, Duration timeout) {
        long deadline = System.nanoTime() + timeout.toNanos();
        synchronized (lock) {
            while (true) {
                drain();
                assertFramesValid();
                for (Iterator<JsonNode> it = unconsumed.iterator(); it.hasNext();) {
                    JsonNode frame = it.next();
                    if (type.equals(frame.get("type").asString())) {
                        it.remove();
                        return Optional.of(frame);
                    }
                }
                long left = deadline - System.nanoTime();
                if (left <= 0) {
                    return Optional.empty();
                }
                try {
                    lock.wait(Math.max(1, TimeUnit.NANOSECONDS.toMillis(left)));
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(ex);
                }
            }
        }
    }

    public List<JsonNode> received() {
        return List.copyOf(received);
    }

    /** Every frame the server sent matched the runner protocol schema of the connection's version. */
    public void assertFramesValid() {
        assertThat(violations).as("frames that do not match the runner protocol schema").isEmpty();
    }

    /** The close code the server sent. */
    public int awaitClose() {
        try {
            return closed.get(TIMEOUT.toSeconds(), TimeUnit.SECONDS);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AssertionError(ex);
        }
        catch (ExecutionException | TimeoutException ex) {
            throw new AssertionError("The server did not close the connection", ex);
        }
    }

    public boolean isClosed() {
        return closed.isDone();
    }

    @Override
    public void close() {
        replies.shutdownNow();
        if (!closed.isDone()) {
            try {
                socket.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(5, TimeUnit.SECONDS);
            }
            catch (Exception ex) {
                socket.abort();
            }
        }
    }

    /** Abruptly drops the connection, like a network interruption. */
    public void abort() {
        replies.shutdownNow();
        socket.abort();
    }

    private void drain() {
        while (consumed < received.size()) {
            unconsumed.add(received.get(consumed++));
        }
    }

    private final class Listener implements WebSocket.Listener {

        private final StringBuilder partial = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            partial.append(data);
            if (last) {
                String frame = partial.toString();
                partial.setLength(0);
                // The tests' TINFIRM_PUBLIC_URL is plain http, so the model relay's baseUrl in run.assign is too; in
                // production it is https, as the schema asks (the package 2 contract's open point 5). The frame is
                // checked as it would be there.
                List<String> problems = ProtocolSchema.violations(protocolVersion,
                        frame.replace("\"baseUrl\":\"http://localhost:", "\"baseUrl\":\"https://localhost:"));
                if (!problems.isEmpty()) {
                    violations.add(frame + " -> " + problems);
                }
                JsonNode message = JSON.readTree(frame);
                received.add(message);
                synchronized (lock) {
                    lock.notifyAll();
                }
                if (autoApply && message.has("type") && "host.config".equals(message.get("type").asString())) {
                    try {
                        replies.execute(() -> {
                            try {
                                applyConfig(message);
                            }
                            catch (RuntimeException ex) {
                                // The connection closed meanwhile.
                            }
                        });
                    }
                    catch (RuntimeException ex) {
                        // Closed meanwhile.
                    }
                }
            }
            webSocket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
            closed.complete(statusCode);
            synchronized (lock) {
                lock.notifyAll();
            }
            return null;
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            closed.completeExceptionally(error);
        }
    }
}
