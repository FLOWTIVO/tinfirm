package com.flowtivo.tinfirm.system.internal.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;
import com.flowtivo.tinfirm.system.internal.http.OutboundProperties;
import com.flowtivo.tinfirm.system.internal.http.PersonalStubHosts;

import tools.jackson.databind.json.JsonMapper;

/**
 * The caps of an MCP server's catalogue (the security review of 2026-10-27, M9): at most 1 MiB per {@code tools/list}
 * page, 500 tools, 64 KiB per {@code inputSchema} and 2 MiB in all; beyond them the read fails with the reason, and
 * names repeated ignoring case are skipped in constant time each.
 */
class McpClientTest {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static McpClient client() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("test");
        return new McpClient(new OutboundHttp(new OutboundProperties(true, false, Duration.ofSeconds(10),
                Duration.ofSeconds(10), Set.of("localhost"), Set.of()), environment,
                new DefaultListableBeanFactory().getBeanProvider(PersonalStubHosts.class)));
    }

    private static CallResult<McpClient.Listed> list(McpStub.Instance server) {
        return client().listTools(new McpClient.Target(server.url(), null, null));
    }

    private static List<Map<String, Object>> tools(String prefix, int count) {
        List<Map<String, Object>> tools = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            tools.add(McpStub.tool(prefix + i, null));
        }
        return tools;
    }

    /** A tool whose schema is about {@code bytes} long, in a description of one property. */
    private static Map<String, Object> toolWithSchema(String name, int bytes) {
        Map<String, Object> tool = new LinkedHashMap<>();
        tool.put("name", name);
        tool.put("inputSchema", Map.of("type", "object", "properties", Map.of("q", Map.of("type", "string",
                "description", "x".repeat(bytes)))));
        return tool;
    }

    /** {@code tools/list} with this cursor (none: the first page) answers this page and the next cursor. */
    private static void page(McpStub.Instance server, @Nullable String cursor, List<Map<String, Object>> tools,
            @Nullable String nextCursor) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("tools", tools);
        if (nextCursor != null) {
            result.put("nextCursor", nextCursor);
        }
        var request = post(urlEqualTo(URI.create(server.url()).getPath()))
            .atPriority(cursor == null ? 3 : 2)
            .withRequestBody(matchingJsonPath("$[?(@.method == 'tools/list')]"));
        if (cursor != null) {
            request.withRequestBody(matchingJsonPath("$.params[?(@.cursor == '" + cursor + "')]"));
        }
        McpStub.server().stubFor(request.willReturn(aResponse().withStatus(200)
            .withHeader("Content-Type", "application/json")
            .withBody("{\"jsonrpc\":\"2.0\",\"id\":{{jsonPath request.body '$.id'}},\"result\":"
                    + JSON.writeValueAsString(result) + "}")));
    }

    @Test
    void fiveHundredToolsAreReadAndRepeatedNamesSkipped() {
        List<Map<String, Object>> listed = tools("tool_", McpClient.MAX_TOOLS);
        listed.add(McpStub.tool("TOOL_0", null));
        McpStub.Instance server = McpStub.newInstance().serves(listed);

        CallResult<McpClient.Listed> result = list(server);

        assertThat(result.failure()).as("%s", result.failureMessage()).isNull();
        assertThat(result.value().tools()).hasSize(McpClient.MAX_TOOLS);
        assertThat(result.value().skipped()).isEqualTo(1);
    }

    @Test
    void moreThanFiveHundredToolsFailTheRead() {
        McpStub.Instance server = McpStub.newInstance().serves(tools("tool_", McpClient.MAX_TOOLS + 1));

        CallResult<McpClient.Listed> result = list(server);

        assertThat(result.failure()).isNotNull();
        assertThat(result.failure().kind()).isEqualTo(CallResult.Kind.INVALID);
        assertThat(result.failureMessage()).isEqualTo("The server lists more than 500 tools; Tinfirm reads at most "
                + "500 of one server");
    }

    @Test
    void aSchemaLargerThan64KiBFailsTheRead() {
        McpStub.Instance server = McpStub.newInstance().serves(List.of(McpStub.tool("small", null),
                toolWithSchema("large", McpClient.MAX_SCHEMA_BYTES)));

        CallResult<McpClient.Listed> result = list(server);

        assertThat(result.failureMessage()).isEqualTo("The inputSchema of the tool large is larger than 64 KiB");
    }

    @Test
    void aPageLargerThan1MiBFailsTheRead() {
        List<Map<String, Object>> listed = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            listed.add(toolWithSchema("tool_" + i, 60 * 1024));
        }
        McpStub.Instance server = McpStub.newInstance().serves(listed);

        CallResult<McpClient.Listed> result = list(server);

        assertThat(result.failure()).isNotNull();
        assertThat(result.failureMessage()).isEqualTo("The answer is larger than 1 MiB");
    }

    @Test
    void toolsLargerThan2MiBInAllFailTheRead() {
        // Three pages of 15 tools of 60 KiB each: every page and schema within its cap, 2.6 MiB in all.
        McpStub.Instance server = McpStub.newInstance().serves(List.of());
        List<List<Map<String, Object>>> pages = new ArrayList<>();
        for (int p = 0; p < 3; p++) {
            List<Map<String, Object>> tools = new ArrayList<>();
            for (int i = 0; i < 15; i++) {
                tools.add(toolWithSchema("page" + p + "_" + i, 60 * 1024));
            }
            pages.add(tools);
        }
        page(server, null, pages.get(0), "p1");
        page(server, "p1", pages.get(1), "p2");
        page(server, "p2", pages.get(2), null);

        CallResult<McpClient.Listed> result = list(server);

        assertThat(result.failure()).isNotNull();
        assertThat(result.failureMessage()).isEqualTo("The server's tools are larger than 2 MiB in all");
    }
}
