package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * One WireMock server for the whole test run, standing in for the MCP servers of companies (ADR-0040): MCP
 * Streamable HTTP with JSON answers, or server-sent events when asked. Every test gets its own endpoint
 * ({@link #newInstance()}), so its stubs never meet another test's. TEST FIXTURE ONLY: the tokens are test values.
 */
public final class McpStub {

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options()
        .dynamicPort()
        .globalTemplating(true));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    static {
        SERVER.start();
    }

    private McpStub() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** An endpoint of its own, e.g. {@code http://localhost:1234/mcp-<random>}. */
    public static Instance newInstance() {
        String path = "/mcp-" + UUID.randomUUID().toString().substring(0, 8);
        return new Instance("http://localhost:" + SERVER.port() + path, path);
    }

    /** A tool as {@code tools/list} lists it. */
    public static Map<String, Object> tool(String name, @Nullable String description) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        if (description != null) {
            tool.put("description", description);
        }
        tool.put("inputSchema", Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string")),
                "required", List.of("query")));
        return tool;
    }

    /** A text result of {@code tools/call}. */
    public static Map<String, Object> textResult(String text, boolean isError) {
        return Map.of("content", List.of(Map.of("type", "text", "text", text)), "isError", isError);
    }

    /** One MCP server of a test. */
    public static final class Instance {

        private final String url;

        private final String path;

        private @Nullable String headerName;

        private @Nullable String headerValue;

        private boolean sse;

        private boolean session;

        Instance(String url, String path) {
            this.url = url;
            this.path = path;
        }

        public String url() {
            return url;
        }

        /** Every request must carry this header; a request without it is answered 401. */
        public Instance requiring(String headerName, String headerValue) {
            this.headerName = headerName;
            this.headerValue = headerValue;
            return this;
        }

        /** Answers come as server-sent events instead of JSON. */
        public Instance sse() {
            this.sse = true;
            return this;
        }

        /** {@code initialize} gives a session id, which every later request must send back. */
        public Instance withSession() {
            this.session = true;
            return this;
        }

        /**
         * Serves {@code initialize}, {@code notifications/initialized}, {@code tools/list} with these tools (one page)
         * and {@code tools/call} with a result that names the tool, unless {@link #answers} says otherwise.
         */
        public Instance serves(List<Map<String, Object>> tools) {
            if (headerName != null) {
                SERVER.stubFor(post(urlEqualTo(path)).atPriority(10).willReturn(aResponse().withStatus(401)));
            }
            Map<String, Object> init = Map.of("protocolVersion", "2025-06-18", "capabilities", Map.of("tools",
                    Map.of()), "serverInfo", Map.of("name", "stub", "version", "1"));
            ResponseDefinitionBuilder initialized = rpc(init);
            if (session) {
                initialized.withHeader("Mcp-Session-Id", "session-" + path.substring(5));
            }
            SERVER.stubFor(method("initialize").atPriority(5).willReturn(initialized));
            SERVER.stubFor(method("notifications/initialized").atPriority(5)
                .willReturn(aResponse().withStatus(202)));
            SERVER.stubFor(method("tools/list").atPriority(5).willReturn(rpc(Map.of("tools", tools))));
            SERVER.stubFor(method("tools/call").atPriority(6).willReturn(rpc(Map.of("content", List.of(
                    Map.of("type", "text", "text", "called {{jsonPath request.body '$.params.name'}}")),
                    "isError", false))));
            return this;
        }

        /** {@code tools/call} of this tool answers this result. */
        public Instance answers(String tool, Map<String, Object> result) {
            SERVER.stubFor(method("tools/call").atPriority(4)
                .withRequestBody(matchingJsonPath("$.params[?(@.name == '" + tool + "')]"))
                .willReturn(rpc(result)));
            return this;
        }

        /** {@code tools/call} of this tool answers a JSON-RPC error. */
        public Instance refuses(String tool, int code, String message) {
            SERVER.stubFor(method("tools/call").atPriority(4)
                .withRequestBody(matchingJsonPath("$.params[?(@.name == '" + tool + "')]"))
                .willReturn(body("{\"jsonrpc\":\"2.0\",\"id\":{{jsonPath request.body '$.id'}},\"error\":{\"code\":"
                        + code + ",\"message\":" + JSON.writeValueAsString(message) + "}}")));
            return this;
        }

        /** Every request is answered with this status. */
        public Instance failsWith(int status) {
            SERVER.stubFor(post(urlEqualTo(path)).atPriority(1).willReturn(aResponse().withStatus(status)));
            return this;
        }

        /** The bodies of the {@code tools/call} requests the server took, oldest first. */
        public List<JsonNode> calls() {
            List<JsonNode> calls = new ArrayList<>();
            for (LoggedRequest request : SERVER.findAll(postRequestedFor(urlEqualTo(path)))) {
                JsonNode body = JSON.readTree(request.getBodyAsString());
                if (body.get("method") != null && "tools/call".equals(body.get("method").asString())) {
                    calls.add(body);
                }
            }
            return calls;
        }

        /** The requests the server took, oldest first. */
        public List<LoggedRequest> requests() {
            return SERVER.findAll(postRequestedFor(urlEqualTo(path)));
        }

        private MappingBuilder method(String name) {
            MappingBuilder builder = post(urlEqualTo(path))
                .withRequestBody(matchingJsonPath("$[?(@.method == '" + name + "')]"));
            if (headerName != null) {
                builder.withHeader(headerName, equalTo(headerValue));
            }
            if (session && !name.equals("initialize")) {
                builder.withHeader("Mcp-Session-Id", equalTo("session-" + path.substring(5)));
            }
            return builder;
        }

        /** A JSON-RPC answer with the request's id and this result, as JSON or as one server-sent event. */
        private ResponseDefinitionBuilder rpc(Map<String, Object> result) {
            return body("{\"jsonrpc\":\"2.0\",\"id\":{{jsonPath request.body '$.id'}},\"result\":"
                    + JSON.writeValueAsString(result) + "}");
        }

        private ResponseDefinitionBuilder body(String json) {
            if (sse) {
                return aResponse().withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("event: message\ndata: " + json + "\n\n");
            }
            return aResponse().withStatus(200).withHeader("Content-Type", "application/json").withBody(json);
        }
    }
}
