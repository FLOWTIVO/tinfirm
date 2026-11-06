package com.flowtivo.tinfirm.support;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Deque;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A LiteLLM for the tests (TEST FIXTURE ONLY): an embedded HTTP server that serves the admin API Tinfirm uses as
 * LiteLLM v1.105.1 does ({@code /key/generate}, {@code /key/update}, {@code /key/block}, {@code /key/unblock},
 * {@code /key/delete}, {@code /key/info}, {@code /key/list}, {@code /team/info}, {@code /team/new}, {@code /team/update},
 * {@code /v1/models}) with its master key, keeps the keys and teams it was asked for, records every request, and
 * fails on demand. A key's id is the SHA-256 of the key, as LiteLLM's {@code token}; its alias is unique, a new key
 * may start with a {@code spend}, {@code /key/update} sets one, and the budget's reset time follows the period and the
 * calendar as v1.105.1's {@code get_budget_reset_time} has it (UTC, midnight). The model routes
 * ({@code /v1/messages}, {@code /v1/responses}, ...) take a virtual key, refuse a blocked or unknown one (401), a spent
 * budget (429 {@code budget_exceeded}) and a model the key does not allow (401), and are answered by
 * {@link #onModel}.
 * <p>
 * For package 6's spend it also serves {@code GET /spend/logs?api_key=&start_date=&end_date=&summarize=false} (the
 * entries a test logged with {@link #logSpend}, of that key) and {@code GET /model/info} (the prices set with
 * {@link #price}), both with the master key.
 * <p>
 * {@link #bundled()} is the deployment's bundled gateway of every test context (IntegrationTest registers its URL and
 * application-test.yaml its master key); {@link #start(String)} starts another, a company's own.
 */
public final class FakeLiteLlm implements AutoCloseable {

    /** The bundled gateway's master key, as application-test.yaml sets it. TEST FIXTURE. */
    public static final String BUNDLED_MASTER_KEY = "sk-test-master-key-fixture";

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final SecureRandom RANDOM = new SecureRandom();

    private static volatile FakeLiteLlm bundled;

    private final HttpServer server;

    private volatile String masterKey;

    private final Map<String, Key> keys = new LinkedHashMap<>();

    private final Map<String, String> teams = new HashMap<>();

    /** Every request of the run, in order; appended to on each, so not a copy-on-write list (it copies them all). */
    private final Queue<Request> requests = new ConcurrentLinkedQueue<>();

    private final Map<String, Deque<Integer>> failures = new HashMap<>();

    /** The paths whose next request is answered with a large body, and its size. */
    private final Map<String, Long> large = new HashMap<>();

    private volatile ModelHandler modelHandler = FakeLiteLlm::echo;

    private volatile boolean down;

    private volatile Consumer<String> beforeInfoAnswer = keyId -> {
    };

    private final List<ObjectNode> spendLogs = new CopyOnWriteArrayList<>();

    private final Map<String, ObjectNode> prices = new java.util.concurrent.ConcurrentHashMap<>();

    /** A key as the fake keeps it. */
    public static final class Key {

        public final String key;

        public final String token;

        public final String alias;

        public final String teamId;

        public volatile double maxBudget;

        public volatile String budgetDuration;

        public volatile List<String> models;

        public final Map<String, String> metadata;

        public volatile boolean blocked;

        public volatile double spend;

        public volatile Instant resetAt;

        Key(String key, String token, String alias, String teamId, double maxBudget, String budgetDuration,
                List<String> models, Map<String, String> metadata, boolean blocked) {
            this.key = key;
            this.token = token;
            this.alias = alias;
            this.teamId = teamId;
            this.maxBudget = maxBudget;
            this.budgetDuration = budgetDuration;
            this.models = List.copyOf(models);
            this.metadata = Map.copyOf(metadata);
            this.blocked = blocked;
            this.resetAt = resetTime(budgetDuration, Instant.now());
        }
    }

    /** A request the fake received: its method, path with query, headers (lower-case names) and body. */
    public record Request(String method, String path, Map<String, List<String>> headers, byte[] body) {

        public String header(String name) {
            List<String> values = headers.get(name.toLowerCase());
            return values == null || values.isEmpty() ? null : values.getFirst();
        }

        public JsonNode json() {
            return JSON.readTree(body);
        }

        public String text() {
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    /** Answers a model request that passed the key's checks. */
    @FunctionalInterface
    public interface ModelHandler {

        void handle(HttpExchange exchange, Request request, Key key) throws IOException;
    }

    private FakeLiteLlm(String masterKey) {
        this.masterKey = masterKey;
        try {
            server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        }
        catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
        server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        server.createContext("/", this::handle);
        server.start();
    }

    /** The bundled gateway of the tests, started once. */
    public static FakeLiteLlm bundled() {
        FakeLiteLlm current = bundled;
        if (current == null) {
            synchronized (FakeLiteLlm.class) {
                current = bundled;
                if (current == null) {
                    current = new FakeLiteLlm(BUNDLED_MASTER_KEY);
                    bundled = current;
                }
            }
        }
        return current;
    }

    /** Another gateway with its own master key: a company's own LiteLLM. */
    public static FakeLiteLlm start(String masterKey) {
        return new FakeLiteLlm(masterKey);
    }

    public String url() {
        return "http://localhost:" + server.getAddress().getPort();
    }

    public String masterKey() {
        return masterKey;
    }

    /** From now on only {@code next} is the master key, as when an operator rotates it: the former one is refused. */
    public void rotateMasterKey(String next) {
        this.masterKey = next;
    }

    // ---- what it holds -----------------------------------------------------------------------------------------

    public synchronized Optional<Key> keyOfAlias(String alias) {
        return keys.values().stream().filter(key -> key.alias.equals(alias)).findFirst();
    }

    public synchronized Optional<Key> key(String token) {
        return Optional.ofNullable(keys.get(token));
    }

    public synchronized List<Key> keysOfTeam(String teamId) {
        return keys.values().stream().filter(key -> key.teamId.equals(teamId)).toList();
    }

    public synchronized boolean hasTeam(String teamId) {
        return teams.containsKey(teamId);
    }

    /** The team's alias (the company's name, as Tinfirm names it); empty when the fake knows no such team. */
    public synchronized Optional<String> teamAlias(String teamId) {
        return Optional.ofNullable(teams.get(teamId));
    }

    /** Puts a key into a team as if someone had made it outside Tinfirm (or Tinfirm lost it). */
    public synchronized Key addKey(String alias, String teamId) {
        String key = "sk-" + random();
        Key created = new Key(key, hash(key), alias, teamId, 0, "30d", List.of(), Map.of(), false);
        keys.put(created.token, created);
        return created;
    }

    /** Forgets a key, as if someone deleted it in LiteLLM directly. */
    public synchronized void forget(String token) {
        keys.remove(token);
    }

    /** The requests received so far whose path starts with the prefix. */
    public List<Request> requests(String pathPrefix) {
        return requests.stream().filter(request -> request.path().startsWith(pathPrefix)).toList();
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** The next {@code times} requests to the path (without its query) answer {@code status}. */
    public synchronized void failNext(String path, int status, int times) {
        Deque<Integer> queue = failures.computeIfAbsent(path, ignored -> new ArrayDeque<>());
        for (int i = 0; i < times; i++) {
            queue.add(status);
        }
    }

    /**
     * The next request to the path (without its query) answers 200 with a JSON body of {@code bytes} bytes, its length
     * declared, as a misconfigured or hostile gateway might ({@link #largeJson}).
     */
    public synchronized void answerLargeNext(String path, long bytes) {
        large.put(path, bytes);
    }

    /**
     * Answers 200 with a JSON array of {@code bytes} bytes (whitespace between its brackets), its length declared or sent
     * in chunks without one, written until all of it went or the client closed the connection; closes the exchange.
     */
    public static void largeJson(HttpExchange exchange, long bytes, boolean declared) {
        try (exchange) {
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, declared ? bytes : 0);
            OutputStream out = exchange.getResponseBody();
            byte[] spaces = new byte[64 * 1024];
            Arrays.fill(spaces, (byte) ' ');
            out.write('[');
            for (long left = bytes - 2; left > 0; left -= spaces.length) {
                out.write(spaces, 0, (int) Math.min(left, spaces.length));
            }
            out.write(']');
            out.flush();
        }
        catch (IOException ex) {
            // The client closed the connection: it reads no more of the answer.
        }
    }

    /** While down, every request is answered 503, as by a gateway that is not up. */
    public void down(boolean down) {
        this.down = down;
    }

    /** How model requests are answered from now on. */
    public void onModel(ModelHandler handler) {
        this.modelHandler = handler;
    }

    /** Model requests are echoed again: their body as JSON with the path. */
    public void resetModelHandler() {
        this.modelHandler = FakeLiteLlm::echo;
    }

    /**
     * Runs the hook with the key's id once a {@code /key/info} answer is read from the key's state and before it is
     * written, outside the fake's lock: a test holds a sync's read of a key in flight with it, as a slow gateway would,
     * while the key changes. {@link #resetInfo} removes it.
     */
    public void beforeInfoAnswer(Consumer<String> hook) {
        this.beforeInfoAnswer = hook;
    }

    /** {@code /key/info} answers at once again. */
    public void resetInfo() {
        this.beforeInfoAnswer = keyId -> {
        };
    }

    // ---- spend (package 6) -----------------------------------------------------------------------------------------

    /**
     * Logs a request of the key as LiteLLM's spend logs keep it: its id, the key's id, the model group, the request's
     * tags, its start, its spend and its tokens ({@code prompt_tokens} includes the cache's, as LiteLLM counts them).
     */
    public void logSpend(String keyToken, String model, List<String> tags, Instant startTime, double spend,
            long promptTokens, long completionTokens, long cacheReadTokens) {
        ObjectNode row = JSON.createObjectNode()
            .put("request_id", "chatcmpl-" + random())
            .put("api_key", keyToken)
            .put("model", "anthropic/" + model)
            .put("model_group", model)
            .put("spend", spend)
            .put("prompt_tokens", promptTokens)
            .put("completion_tokens", completionTokens)
            .put("total_tokens", promptTokens + completionTokens)
            .put("startTime", startTime.toString())
            .put("endTime", startTime.plusSeconds(1).toString());
        ArrayNode list = row.putArray("request_tags");
        tags.forEach(list::add);
        row.putObject("metadata").putObject("usage_object").put("prompt_tokens", promptTokens)
            .put("completion_tokens", completionTokens).put("cache_read_input_tokens", cacheReadTokens);
        spendLogs.add(row);
    }

    /** The price of a model per token, as {@code /model/info} names it from now on. */
    public void price(String model, double inputPerToken, double outputPerToken) {
        ObjectNode entry = JSON.createObjectNode().put("model_name", model);
        entry.putObject("litellm_params").put("model", "anthropic/" + model);
        entry.putObject("model_info").put("input_cost_per_token", inputPerToken)
            .put("output_cost_per_token", outputPerToken);
        prices.put(model, entry);
    }

    private void spendLogsOf(HttpExchange exchange, Map<String, String> query) throws IOException {
        if (!"false".equals(query.get("summarize")) || query.get("start_date") == null
                || query.get("end_date") == null) {
            error(exchange, 400, "bad_request", "The fake serves summarize=false with a start and an end date");
            return;
        }
        String key = query.get("api_key");
        ArrayNode answer = JSON.createArrayNode();
        for (ObjectNode row : spendLogs) {
            if (key == null || key.equals(row.get("api_key").asString())) {
                answer.add(row);
            }
        }
        json(exchange, 200, answer);
    }

    private void modelInfo(HttpExchange exchange) throws IOException {
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode data = answer.putArray("data");
        prices.values().forEach(data::add);
        json(exchange, 200, answer);
    }

    @Override
    public void close() {
        server.stop(0);
    }

    // ---- the HTTP side ------------------------------------------------------------------------------------------

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            byte[] body;
            try (InputStream in = exchange.getRequestBody()) {
                body = in.readAllBytes();
            }
            Map<String, List<String>> headers = new HashMap<>();
            exchange.getRequestHeaders().forEach((name, values) -> headers.put(name.toLowerCase(), List.copyOf(values)));
            String pathAndQuery = exchange.getRequestURI().getRawPath()
                    + (exchange.getRequestURI().getRawQuery() != null ? "?" + exchange.getRequestURI().getRawQuery()
                            : "");
            Request request = new Request(exchange.getRequestMethod(), pathAndQuery, Map.copyOf(headers), body);
            requests.add(request);
            if (down) {
                error(exchange, 503, "service_unavailable", "LiteLLM is down");
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            Integer failure = nextFailure(path);
            if (failure != null) {
                error(exchange, failure, "fake_failure", "Failure asked for by the test");
                return;
            }
            Long size = nextLarge(path);
            if (size != null) {
                largeJson(exchange, size, true);
                return;
            }
            String bearer = bearer(request.header("authorization"));
            String master = masterKey;
            if (isModelRoute(path) && !(path.equals("/v1/models") && master.equals(bearer))) {
                model(exchange, request, path, bearer);
                return;
            }
            if (!master.equals(bearer)) {
                error(exchange, 401, "auth_error", invalidKey(bearer));
                return;
            }
            admin(exchange, request, path);
        }
        catch (RuntimeException ex) {
            error(exchange, 500, "fake_error", String.valueOf(ex.getMessage()));
        }
    }

    private synchronized Long nextLarge(String path) {
        return large.remove(path);
    }

    private synchronized Integer nextFailure(String path) {
        Deque<Integer> queue = failures.get(path);
        return queue == null ? null : queue.poll();
    }

    private static boolean isModelRoute(String path) {
        return path.equals("/v1/messages") || path.equals("/v1/messages/count_tokens") || path.equals("/v1/responses")
                || path.equals("/v1/chat/completions") || path.equals("/v1/models");
    }

    private void admin(HttpExchange exchange, Request request, String path) throws IOException {
        Map<String, String> query = query(exchange.getRequestURI().getRawQuery());
        switch (request.method() + " " + path) {
            case "GET /team/info" -> {
                String alias;
                synchronized (this) {
                    if (!teams.containsKey(query.get("team_id"))) {
                        error(exchange, 404, "not_found", "Team not found, passed team id: " + query.get("team_id"));
                        return;
                    }
                    alias = teams.get(query.get("team_id"));
                }
                ObjectNode answer = JSON.createObjectNode().put("team_id", query.get("team_id"));
                answer.putObject("team_info").put("team_id", query.get("team_id")).put("team_alias", alias);
                json(exchange, 200, answer);
            }
            case "POST /team/update" -> {
                JsonNode body = request.json();
                String teamId = body.get("team_id").asString();
                synchronized (this) {
                    if (!teams.containsKey(teamId)) {
                        error(exchange, 404, "not_found", "Team not found, passed team_id=" + teamId);
                        return;
                    }
                    if (body.has("metadata")) {
                        error(exchange, 400, "bad_request",
                                "The fake refuses metadata on a team update: it would replace the stored one");
                        return;
                    }
                    if (body.has("team_alias")) {
                        teams.put(teamId, body.get("team_alias").asString());
                    }
                }
                json(exchange, 200, JSON.createObjectNode().put("team_id", teamId));
            }

            case "POST /team/new" -> {
                JsonNode body = request.json();
                String teamId = body.get("team_id").asString();
                synchronized (this) {
                    if (teams.containsKey(teamId)) {
                        error(exchange, 400, "bad_request", "Team id = " + teamId + " already exists.");
                        return;
                    }
                    teams.put(teamId, body.path("team_alias").asString());
                }
                json(exchange, 200, JSON.createObjectNode().put("team_id", teamId));
            }
            case "POST /key/generate" -> generate(exchange, request.json());
            case "POST /key/update" -> update(exchange, request.json());
            case "POST /key/block" -> block(exchange, request.json(), true);
            case "POST /key/unblock" -> block(exchange, request.json(), false);
            case "POST /key/delete" -> delete(exchange, request.json());
            case "GET /key/info" -> info(exchange, query.get("key"));
            case "GET /key/list" -> list(exchange, query);
            case "GET /v1/models" -> models(exchange);
            case "GET /spend/logs" -> spendLogsOf(exchange, query);
            case "GET /model/info" -> modelInfo(exchange);
            default -> error(exchange, 404, "not_found", "No route " + request.method() + " " + path);
        }
    }

    private synchronized void generate(HttpExchange exchange, JsonNode body) throws IOException {
        String alias = body.get("key_alias").asString();
        String teamId = body.get("team_id").asString();
        if (!teams.containsKey(teamId)) {
            error(exchange, 400, "bad_request", "Team not found");
            return;
        }
        if (keys.values().stream().anyMatch(key -> key.alias.equals(alias))) {
            error(exchange, 400, "bad_request", "Key with alias '" + alias + "' already exists.");
            return;
        }
        if (!"llm_api".equals(body.path("key_type").asString())) {
            error(exchange, 400, "bad_request", "The fake expects key_type llm_api");
            return;
        }
        Map<String, String> metadata = new LinkedHashMap<>();
        body.path("metadata").properties().forEach(entry -> metadata.put(entry.getKey(), entry.getValue().asString()));
        String key = "sk-" + random();
        Key created = new Key(key, hash(key), alias, teamId, body.get("max_budget").asDouble(),
                body.get("budget_duration").asString(), strings(body.get("models")), metadata,
                body.path("blocked").asBoolean(false));
        created.spend = body.path("spend").asDouble(0);
        keys.put(created.token, created);
        ObjectNode answer = JSON.createObjectNode().put("key", key).put("token", created.token)
            .put("key_alias", alias);
        json(exchange, 200, answer);
    }

    private synchronized void update(HttpExchange exchange, JsonNode body) throws IOException {
        Key key = keys.get(body.get("key").asString());
        if (key == null) {
            error(exchange, 404, "not_found", "Key not found");
            return;
        }
        if (body.has("metadata")) {
            error(exchange, 400, "bad_request", "The fake refuses metadata on update: it would replace the stored one");
            return;
        }
        if (body.has("max_budget")) {
            key.maxBudget = body.get("max_budget").asDouble();
        }
        if (body.has("budget_duration")) {
            key.budgetDuration = body.get("budget_duration").asString();
            key.resetAt = resetTime(key.budgetDuration, Instant.now());
        }
        if (body.has("spend")) {
            key.spend = body.get("spend").asDouble();
        }
        if (body.has("models")) {
            key.models = strings(body.get("models"));
        }
        json(exchange, 200, JSON.createObjectNode().put("key", key.token));
    }

    private synchronized void block(HttpExchange exchange, JsonNode body, boolean blocked) throws IOException {
        Key key = keys.get(body.get("key").asString());
        if (key == null) {
            error(exchange, 404, "not_found", "Key not found.");
            return;
        }
        key.blocked = blocked;
        json(exchange, 200, JSON.createObjectNode().put("token", key.token).put("blocked", blocked));
    }

    private synchronized void delete(HttpExchange exchange, JsonNode body) throws IOException {
        List<String> deleted = new ArrayList<>();
        if (body.has("keys")) {
            for (String token : strings(body.get("keys"))) {
                if (keys.remove(token) != null) {
                    deleted.add(token);
                }
            }
        }
        if (body.has("key_aliases")) {
            for (String alias : strings(body.get("key_aliases"))) {
                keys.values().removeIf(key -> {
                    boolean match = key.alias.equals(alias);
                    if (match) {
                        deleted.add(alias);
                    }
                    return match;
                });
            }
        }
        if (deleted.isEmpty()) {
            error(exchange, 404, "not_found", "No keys found");
            return;
        }
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode list = answer.putArray("deleted_keys");
        deleted.forEach(list::add);
        json(exchange, 200, answer);
    }

    private void info(HttpExchange exchange, String token) throws IOException {
        ObjectNode answer;
        synchronized (this) {
            Key key = token != null ? keys.get(token) : null;
            if (key == null) {
                error(exchange, 404, "not_found", "Key not found in database");
                return;
            }
            answer = JSON.createObjectNode().put("key", token);
            answer.set("info", keyJson(key, false));
        }
        beforeInfoAnswer.accept(token);
        json(exchange, 200, answer);
    }

    private synchronized void list(HttpExchange exchange, Map<String, String> query) throws IOException {
        String teamId = query.get("team_id");
        int size = Integer.parseInt(query.getOrDefault("size", "10"));
        int page = Integer.parseInt(query.getOrDefault("page", "1"));
        List<Key> team = keys.values().stream().filter(key -> key.teamId.equals(teamId)).toList();
        int pages = Math.max(1, (team.size() + size - 1) / size);
        ObjectNode answer = JSON.createObjectNode();
        ArrayNode items = answer.putArray("keys");
        team.stream().skip((long) (page - 1) * size).limit(size).forEach(key -> items.add(keyJson(key, true)));
        answer.put("total_count", team.size()).put("current_page", page).put("total_pages", pages);
        json(exchange, 200, answer);
    }

    private void models(HttpExchange exchange) throws IOException {
        ObjectNode answer = JSON.createObjectNode().put("object", "list");
        ArrayNode data = answer.putArray("data");
        for (String model : List.of("claude-sonnet-4-5", "claude-haiku-4-5", "gpt-5.4")) {
            data.addObject().put("id", model).put("object", "model");
        }
        json(exchange, 200, answer);
    }

    private ObjectNode keyJson(Key key, boolean withToken) {
        ObjectNode node = JSON.createObjectNode();
        if (withToken) {
            node.put("token", key.token);
        }
        node.put("key_alias", key.alias).put("team_id", key.teamId).put("spend", key.spend)
            .put("max_budget", key.maxBudget).put("budget_duration", key.budgetDuration)
            .put("budget_reset_at", key.resetAt.toString()).put("blocked", key.blocked)
            .put("status", key.blocked ? "revoked" : "active");
        ArrayNode models = node.putArray("models");
        key.models.forEach(models::add);
        ObjectNode metadata = node.putObject("metadata");
        key.metadata.forEach(metadata::put);
        return node;
    }

    private void model(HttpExchange exchange, Request request, String path, String bearer) throws IOException {
        Key key;
        synchronized (this) {
            key = bearer != null ? keys.get(hash(bearer)) : null;
        }
        if (key == null) {
            error(exchange, 401, "auth_error", invalidKey(bearer));
            return;
        }
        if (key.blocked) {
            error(exchange, 401, "auth_error",
                    "Authentication Error - Key is blocked. Update via `/key/unblock` if you're an admin.");
            return;
        }
        if (key.spend >= key.maxBudget) {
            error(exchange, 429, "budget_exceeded",
                    "Budget has been exceeded! Current cost: " + key.spend + ", Max budget: " + key.maxBudget);
            return;
        }
        if (!path.equals("/v1/models") && request.body().length > 0) {
            String model = request.json().path("model").asString();
            if (!key.models.isEmpty() && !key.models.contains(model)) {
                error(exchange, 401, "key_model_access_denied", "key not allowed to access model " + model);
                return;
            }
        }
        modelHandler.handle(exchange, request, key);
    }

    /** The default model answer: the path, the model and the body's field names, as JSON. */
    private static void echo(HttpExchange exchange, Request request, Key key) throws IOException {
        ObjectNode answer = JSON.createObjectNode().put("path", request.path()).put("alias", key.alias);
        if (request.body().length > 0) {
            JsonNode body = request.json();
            answer.put("model", body.path("model").asString());
            ArrayNode fields = answer.putArray("fields");
            body.propertyNames().forEach(fields::add);
        }
        json(exchange, 200, answer);
    }

    /**
     * LiteLLM's answer to a key it does not know, which echoes the key abbreviated and its hash (v1.105.1,
     * {@code user_api_key_auth.py}).
     */
    private static String invalidKey(String bearer) {
        String key = bearer != null ? bearer : "";
        String abbreviated = key.length() < 16 ? "sk-..." : "sk-..." + key.substring(key.length() - 4);
        return "Authentication Error, Invalid proxy server token passed. Received API Key = " + abbreviated
                + ", Key Hash (Token) =" + hash(key)
                + ". Unable to find token in cache or `LiteLLM_VerificationTokenTable`";
    }

    private static void error(HttpExchange exchange, int status, String type, String message) throws IOException {
        ObjectNode error = JSON.createObjectNode();
        error.putObject("error").put("message", message).put("type", type).put("code", String.valueOf(status));
        json(exchange, status, error);
    }

    public static void json(HttpExchange exchange, int status, JsonNode body) throws IOException {
        byte[] bytes = JSON.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String bearer(String header) {
        return header != null && header.regionMatches(true, 0, "Bearer ", 0, 7) ? header.substring(7).trim() : null;
    }

    private static Map<String, String> query(String rawQuery) {
        Map<String, String> query = new HashMap<>();
        if (rawQuery == null) {
            return query;
        }
        for (String pair : rawQuery.split("&")) {
            int equals = pair.indexOf('=');
            if (equals > 0) {
                query.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
            }
        }
        return query;
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        if (array != null && array.isArray()) {
            array.forEach(value -> values.add(value.asString()));
        }
        return List.copyOf(values);
    }

    /**
     * When a budget of the period resets, as LiteLLM v1.105.1's {@code get_next_standardized_reset_time} has it in UTC
     * with resets at midnight: {@code 1d} at the next midnight, {@code 7d} on the next Monday, {@code 30d} and
     * {@code 1mo} on the 1st of the next month, other day counts at today's midnight plus the days, weeks as days,
     * hours on the next boundary of their count.
     */
    public static Instant resetTime(String duration, Instant now) {
        Matcher parsed = Pattern.compile("(\\d+)(mo|[dwhms])").matcher(duration);
        ZonedDateTime current = now.atZone(ZoneOffset.UTC);
        ZonedDateTime midnight = current.truncatedTo(ChronoUnit.DAYS);
        if (!parsed.matches()) {
            return midnight.plusDays(1).toInstant();
        }
        int value = Integer.parseInt(parsed.group(1));
        String unit = parsed.group(2);
        if (unit.equals("w")) {
            value *= 7;
            unit = "d";
        }
        return switch (unit) {
            case "d" -> switch (value) {
                case 1 -> midnight.plusDays(1).toInstant();
                case 7 -> {
                    ZonedDateTime monday = midnight
                        .plusDays((7 - (current.getDayOfWeek().getValue() - 1)) % 7);
                    yield (monday.isAfter(current) ? monday : monday.plusDays(7)).toInstant();
                }
                case 30 -> firstOfNextMonth(midnight, current);
                default -> midnight.plusDays(value).toInstant();
            };
            case "mo" -> firstOfNextMonth(midnight, current);
            case "h" -> {
                int next = current.getHour() + value - current.getHour() % value;
                yield next >= 24 ? midnight.plusDays(1).plusHours(next % 24).toInstant()
                        : midnight.plusHours(next).toInstant();
            }
            default -> now.plus(1, ChronoUnit.HOURS);
        };
    }

    private static Instant firstOfNextMonth(ZonedDateTime midnight, ZonedDateTime current) {
        ZonedDateTime first = midnight.withDayOfMonth(1);
        return (first.isAfter(current) ? first : first.plusMonths(1)).toInstant();
    }

    /** LiteLLM's id of a key: the hex SHA-256 of the key. */
    public static String hash(String key) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(key.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String random() {
        byte[] bytes = new byte[24];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
