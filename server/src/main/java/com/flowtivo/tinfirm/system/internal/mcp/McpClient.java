package com.flowtivo.tinfirm.system.internal.mcp;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * The server's MCP client (ADR-0040): the part of MCP Streamable HTTP that checking a token, reading a server's tools
 * (the package 1 contract, §3.5) and calling one (the package 3 contract, §5.4) need, one session per use, over the
 * system's outbound client (https unless the operator allows
 * http, no redirects, the guard against private addresses, the timeout per request, only the server's origin).
 * <ul>
 * <li>Every request is one JSON-RPC message in a {@code POST} to the server's endpoint, with
 * {@code Accept: application/json, text/event-stream}; an answer is read as JSON, or as a stream of server-sent
 * events whose {@code data} lines hold the JSON-RPC answer with the request's id.</li>
 * <li>A session is {@code initialize} (the protocol version {@value #PROTOCOL_VERSION}; the server's answer wins),
 * {@code notifications/initialized}, the calls, and a best-effort {@code DELETE} with the {@code Mcp-Session-Id}
 * the server gave, which is sent with every request after {@code initialize}, as is {@code MCP-Protocol-Version}.</li>
 * <li>{@code tools/list} is read page by page ({@code nextCursor}), at most {@value #MAX_PAGES} pages of at most 1 MiB
 * each. A tool whose name is not {@link #TOOL_NAME}, or that repeats an earlier name ignoring case, is skipped and
 * counted. A catalogue of more than {@value #MAX_TOOLS} tools, a tool whose {@code inputSchema} is larger than 64 KiB,
 * or tools larger than 2 MiB in all (names, texts and schemas as JSON) fail the read with the reason, so that a
 * server cannot spend the server's CPU and memory, or every run's tool list, on its catalogue (the security review of
 * 2026-10-27, M9).</li>
 * <li>The token goes in the header the system names, or as {@code Authorization: Bearer}, as an OAuth access token
 * always does; a failure never
 * contains it, and names the status or the reason, cut to {@value #MAX_REASON_LENGTH} characters.</li>
 * </ul>
 */
public class McpClient {

    public static final String PROTOCOL_VERSION = "2025-06-18";

    /** A tool name the catalogue takes: letters, digits, {@code _} and {@code -}, at most 64 (V001's system_tool). */
    public static final Pattern TOOL_NAME = Pattern.compile("^[A-Za-z0-9_-]{1,64}$");

    public static final int MAX_TITLE_LENGTH = 200;

    public static final int MAX_DESCRIPTION_LENGTH = 4000;

    static final int MAX_PAGES = 10;

    /** The largest answer to one {@code tools/list} read. */
    static final int MAX_LIST_PAGE_BYTES = 1024 * 1024;

    /** The most tools a catalogue takes. */
    public static final int MAX_TOOLS = 500;

    /** The largest {@code inputSchema} of one tool, as JSON. */
    public static final int MAX_SCHEMA_BYTES = 64 * 1024;

    /** The largest catalogue: its tools' names, titles, descriptions and schemas, as JSON, together. */
    public static final int MAX_CATALOGUE_BYTES = 2 * 1024 * 1024;

    static final int MAX_REASON_LENGTH = 300;

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final OutboundHttp http;

    public McpClient(OutboundHttp http) {
        this.http = http;
    }

    /** Where and how to call a server: its endpoint, and the header its token goes in (null: a bearer token). */
    public record Target(String url, @Nullable String authHeader, @Nullable String token) {

        @Override
        public String toString() {
            return "Target[url=" + url + ", authHeader=" + authHeader + ", token=" + (token != null ? "***" : "none")
                    + "]";
        }
    }

    /**
     * A tool as the server listed it, with its texts cut to the catalogue's limits, and its annotations
     * {@code readOnlyHint} and {@code destructiveHint} as the server sent them (null when absent).
     */
    public record ListedTool(String name, @Nullable String title, @Nullable String description, JsonNode inputSchema,
            @Nullable Boolean readOnlyHint, @Nullable Boolean destructiveHint) {
    }

    /**
     * Opens a session and ends it: whether the server takes the target's token (a 401 or 403 fails
     * {@code UNAUTHORIZED}).
     */
    public CallResult<Boolean> probe(Target target) {
        Session session = new Session();
        try {
            CallResult<JsonNode> opened = open(target, session);
            return opened.failure() != null ? opened.failedAs() : CallResult.ok(true);
        }
        finally {
            close(target, session);
        }
    }

    /** The tools the server listed, and how many entries were skipped (a name outside the rule, or repeated). */
    public record Listed(List<ListedTool> tools, int skipped) {
    }

    /** Reads the server's tools: one session with {@code tools/list} page by page. */
    public CallResult<Listed> listTools(Target target) {
        Session session = new Session();
        try {
            CallResult<JsonNode> opened = open(target, session);
            if (opened.failure() != null) {
                return opened.failedAs();
            }
            List<ListedTool> tools = new ArrayList<>();
            Set<String> lowerNames = new HashSet<>();
            int skipped = 0;
            long catalogueBytes = 0;
            String cursor = null;
            for (int page = 0; page < MAX_PAGES; page++) {
                ObjectNode params = JSON.createObjectNode();
                if (cursor != null) {
                    params.put("cursor", cursor);
                }
                CallResult<JsonNode> result = call(target, session, "tools/list", params, MAX_LIST_PAGE_BYTES);
                if (result.failure() != null) {
                    return result.failedAs();
                }
                JsonNode items = Objects.requireNonNull(result.value()).get("tools");
                if (items == null || !items.isArray()) {
                    return CallResult.failed(CallResult.Kind.INVALID,
                            "The server answered tools/list without a list of tools");
                }
                for (JsonNode item : items) {
                    ListedTool tool = tool(item);
                    if (tool == null || !lowerNames.add(tool.name().toLowerCase(Locale.ROOT))) {
                        skipped++;
                        continue;
                    }
                    if (tools.size() == MAX_TOOLS) {
                        return CallResult.failed(CallResult.Kind.INVALID, "The server lists more than " + MAX_TOOLS
                                + " tools; Tinfirm reads at most " + MAX_TOOLS + " of one server");
                    }
                    int schemaBytes = utf8Length(JSON.writeValueAsString(tool.inputSchema()));
                    if (schemaBytes > MAX_SCHEMA_BYTES) {
                        return CallResult.failed(CallResult.Kind.INVALID, "The inputSchema of the tool " + tool.name()
                                + " is larger than " + MAX_SCHEMA_BYTES / 1024 + " KiB");
                    }
                    catalogueBytes += schemaBytes + utf8Length(tool.name()) + utf8Length(tool.title())
                            + utf8Length(tool.description());
                    if (catalogueBytes > MAX_CATALOGUE_BYTES) {
                        return CallResult.failed(CallResult.Kind.INVALID, "The server's tools are larger than "
                                + MAX_CATALOGUE_BYTES / 1024 / 1024 + " MiB in all");
                    }
                    tools.add(tool);
                }
                String next = Json.text(result.value(), "nextCursor");
                if (next == null || next.isBlank()) {
                    break;
                }
                cursor = next;
            }
            return CallResult.ok(new Listed(tools, skipped));
        }
        finally {
            close(target, session);
        }
    }

    /**
     * What the server answered to {@code tools/call} (ADR-0040): its content blocks as it sent them, its structured
     * content when it is an object, and whether it marked the result as an error the agent should see.
     */
    public record Called(List<JsonNode> content, @Nullable JsonNode structuredContent, boolean isError) {

        public Called {
            content = List.copyOf(content);
        }
    }

    /**
     * Calls one tool: {@code initialize}, {@code notifications/initialized}, {@code tools/call} with the name and the
     * arguments, and the session's end. A JSON-RPC error fails {@code INVALID} with the server's words; a {@code 401}
     * {@code UNAUTHORIZED}, a {@code 403} to the call {@code FORBIDDEN}.
     */
    public CallResult<Called> callTool(Target target, String name, JsonNode arguments) {
        Session session = new Session();
        try {
            CallResult<JsonNode> opened = open(target, session);
            if (opened.failure() != null) {
                return opened.failedAs();
            }
            ObjectNode params = JSON.createObjectNode();
            params.put("name", name);
            params.set("arguments", arguments);
            CallResult<JsonNode> result = call(target, session, "tools/call", params);
            if (result.failure() != null) {
                return result.failedAs();
            }
            JsonNode answer = Objects.requireNonNull(result.value());
            JsonNode items = answer.get("content");
            List<JsonNode> content = new ArrayList<>();
            if (items != null && items.isArray()) {
                items.forEach(item -> {
                    if (item.isObject()) {
                        content.add(item);
                    }
                });
            }
            JsonNode structured = answer.get("structuredContent");
            return CallResult.ok(new Called(content, structured != null && structured.isObject() ? structured : null,
                    Json.flag(answer, "isError")));
        }
        finally {
            close(target, session);
        }
    }

    // ---- the session ------------------------------------------------------------------------------------------------

    /** The state of one session: the ids of its requests, the version the server speaks, its session id. */
    private static final class Session {

        private int nextId = 1;

        private String protocolVersion = PROTOCOL_VERSION;

        private @Nullable String id;
    }

    /** {@code initialize} and {@code notifications/initialized}. */
    private CallResult<JsonNode> open(Target target, Session session) {
        ObjectNode params = JSON.createObjectNode();
        params.put("protocolVersion", PROTOCOL_VERSION);
        params.set("capabilities", JSON.createObjectNode());
        params.set("clientInfo", JSON.createObjectNode().put("name", "tinfirm").put("version", "1"));
        CallResult<JsonNode> init = call(target, session, "initialize", params);
        if (init.failure() != null) {
            return init;
        }
        String version = Json.text(init.value(), "protocolVersion");
        if (version != null && !version.isBlank()) {
            session.protocolVersion = version;
        }
        ObjectNode notification = JSON.createObjectNode();
        notification.put("jsonrpc", "2.0");
        notification.put("method", "notifications/initialized");
        CallResult<OutboundHttp.Response> sent = http.send(target.url(), request(target, session, notification));
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), "The server answered "
                    + response.status() + " to notifications/initialized");
        }
        return init;
    }

    /** A JSON-RPC request and its {@code result}; a JSON-RPC error, a bad status or an unusable answer is a failure. */
    private CallResult<JsonNode> call(Target target, Session session, String method, JsonNode params) {
        return call(target, session, method, params, Integer.MAX_VALUE);
    }

    /** As {@link #call(Target, Session, String, JsonNode)}, the answer read up to {@code maxBodyBytes}. */
    private CallResult<JsonNode> call(Target target, Session session, String method, JsonNode params,
            int maxBodyBytes) {
        int id = session.nextId++;
        ObjectNode body = JSON.createObjectNode();
        body.put("jsonrpc", "2.0");
        body.put("id", id);
        body.put("method", method);
        body.set("params", params);
        CallResult<OutboundHttp.Response> sent = http.send(target.url(), request(target, session, body), maxBodyBytes);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (!response.success()) {
            // A 403 to a tool's call is the server's answer for the account, not a refused credential.
            CallResult.Kind kind = response.status() == 403 && "tools/call".equals(method) ? CallResult.Kind.FORBIDDEN
                    : OutboundHttp.kindOf(response.status());
            return CallResult.failed(kind, scrubbed(target, "The server answered " + response.status() + " to "
                    + method + reason(response.body())));
        }
        response.header("Mcp-Session-Id").filter(value -> !value.isBlank()).ifPresent(value -> session.id = value);
        JsonNode message = message(response, id);
        if (message == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The server's answer to " + method
                    + " is not a JSON-RPC message with the request's id");
        }
        JsonNode error = message.get("error");
        if (error != null && !error.isNull()) {
            return CallResult.failed(CallResult.Kind.INVALID, scrubbed(target, "The server refused " + method + ": "
                    + cut(Json.text(error, "message") != null ? Json.text(error, "message") : error.toString())));
        }
        JsonNode result = message.get("result");
        if (result == null || !result.isObject()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The server's answer to " + method
                    + " has no result");
        }
        return CallResult.ok(result);
    }

    /**
     * A failure's text without the token, should the server have quoted it: the text ends in an account's
     * {@code last_error}, the audit log and an answer to a person.
     */
    private static String scrubbed(Target target, String text) {
        String token = target.token();
        return token == null || token.isBlank() ? text : text.replace(token, "***");
    }

    /** Ends the session when the server gave it an id; nothing depends on the answer. */
    private void close(Target target, Session session) {
        if (session.id == null) {
            return;
        }
        HttpRequest.Builder request = http.request(target.url())
            .DELETE()
            .header("Mcp-Session-Id", session.id)
            .header("MCP-Protocol-Version", session.protocolVersion);
        authenticate(request, target);
        http.send(target.url(), request.build());
    }

    private HttpRequest request(Target target, Session session, JsonNode body) {
        HttpRequest.Builder request = http.request(target.url())
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .header("MCP-Protocol-Version", session.protocolVersion);
        if (session.id != null) {
            request.header("Mcp-Session-Id", session.id);
        }
        authenticate(request, target);
        return request.build();
    }

    private static void authenticate(HttpRequest.Builder request, Target target) {
        if (target.token() == null) {
            return;
        }
        if (target.authHeader() != null) {
            request.header(target.authHeader(), target.token());
        }
        else {
            request.header("Authorization", "Bearer " + target.token());
        }
    }

    /** The JSON-RPC message with the id, from a JSON body or from the {@code data} of server-sent events. */
    private static @Nullable JsonNode message(OutboundHttp.Response response, int id) {
        String contentType = response.header("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("text/event-stream")) {
            for (String data : eventData(response.body())) {
                JsonNode node = Json.parse(data);
                if (node != null && hasId(node, id)) {
                    return node;
                }
            }
            return null;
        }
        JsonNode node = Json.parse(response.body());
        return node != null && hasId(node, id) ? node : null;
    }

    private static boolean hasId(JsonNode node, int id) {
        JsonNode value = node.get("id");
        return value != null && ((value.isIntegralNumber() && value.intValue() == id)
                || (value.isString() && value.stringValue().equals(Integer.toString(id))));
    }

    /** The {@code data} of each event of a server-sent event stream, joined with newlines as the format says. */
    static List<String> eventData(String body) {
        List<String> events = new ArrayList<>();
        StringBuilder data = new StringBuilder();
        boolean any = false;
        for (String line : body.split("\r\n|\r|\n", -1)) {
            if (line.isEmpty()) {
                if (any) {
                    events.add(data.toString());
                }
                data.setLength(0);
                any = false;
                continue;
            }
            if (line.startsWith("data:")) {
                String value = line.substring(5);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                if (any) {
                    data.append('\n');
                }
                data.append(value);
                any = true;
            }
        }
        if (any) {
            events.add(data.toString());
        }
        return events;
    }

    /** A listed tool that fits the catalogue, or null: no usable name. */
    private static @Nullable ListedTool tool(JsonNode item) {
        String name = Json.text(item, "name");
        if (name == null || !TOOL_NAME.matcher(name).matches()) {
            return null;
        }
        JsonNode schema = item.get("inputSchema");
        if (schema == null || !schema.isObject()) {
            schema = JSON.createObjectNode().put("type", "object");
        }
        String title = Json.text(item, "title");
        String description = Json.text(item, "description");
        JsonNode annotations = item.get("annotations");
        if (title == null) {
            title = Json.text(annotations, "title");
        }
        return new ListedTool(name, title != null && !title.isBlank() ? cut(title.strip(), MAX_TITLE_LENGTH) : null,
                description != null && !description.isBlank() ? cut(description.strip(), MAX_DESCRIPTION_LENGTH)
                        : null, schema, Json.bool(annotations, "readOnlyHint"), Json.bool(annotations,
                                "destructiveHint"));
    }

    /** The server's own words from an error body, short, after a colon; empty when there are none. */
    private static String reason(String body) {
        JsonNode node = Json.parse(body);
        String message = null;
        if (node != null) {
            JsonNode error = node.get("error");
            message = error != null ? Json.text(error, "message") : null;
            if (message == null) {
                message = Json.text(node, "message");
            }
        }
        if (message == null && !body.isBlank() && !body.strip().startsWith("<")) {
            message = body.strip();
        }
        return message != null && !message.isBlank() ? ": " + cut(message) : "";
    }

    private static int utf8Length(@Nullable String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }

    private static String cut(String text) {
        return cut(text, MAX_REASON_LENGTH);
    }

    private static String cut(String text, int max) {
        String oneLine = text.replaceAll("\\s+", " ").strip();
        return oneLine.length() <= max ? oneLine : oneLine.substring(0, max - 1) + "…";
    }
}
