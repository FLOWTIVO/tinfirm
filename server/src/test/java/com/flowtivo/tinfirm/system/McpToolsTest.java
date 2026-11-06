package com.flowtivo.tinfirm.system;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.McpStub;

import tools.jackson.databind.JsonNode;

/**
 * An MCP server's tools through {@link SystemTools} (ADR-0040): {@code initialize}, {@code notifications/initialized},
 * {@code tools/call} and the session's end, with the token in the server's header or an OAuth token as a bearer
 * token; the server's result as it sent it; only tools of its catalogue that are switched on. TEST FIXTURE ONLY: the
 * tokens are test values.
 */
class McpToolsTest extends ToolsTestSupport {

    private static final String TOKEN = "test-mcp-token-0001";

    /** An MCP system serving these tools, with its catalogue read. */
    private Setup server(McpStub.Instance mcp, List<Map<String, Object>> tools) {
        mcp.serves(tools);
        Setup setup = setup("MCP", mcp.url());
        ApiResponse refreshed = setup.admin().post(setup.company().path("/systems/" + setup.systemId()
                + "/tools/refresh"), Map.of());
        assertThat(refreshed.status()).as("%s", refreshed).isEqualTo(200);
        return setup;
    }

    @Test
    void aToolsResultComesAsTheServerSentIt() {
        McpStub.Instance mcp = McpStub.newInstance();
        Setup setup = server(mcp, List.of(McpStub.tool("search", "Searches"), McpStub.tool("lookup", null),
                McpStub.tool("broken", null)));
        mcp.answers("lookup", Map.of("content", List.of(Map.of("type", "text", "text", "Found 2"),
                Map.of("type", "image", "data", "iVBORw0KGgo=", "mimeType", "image/png")),
                "structuredContent", Map.of("count", 2), "isError", false));
        mcp.refuses("broken", -32602, "Invalid params: query is required");

        ToolOutcome searched = call(setup, CallCredential.none(), "search", "{\"query\":\"invoices\"}");
        assertThat(done(searched).result()).isEqualTo(ToolCallResult.text("called search", null, false));
        JsonNode call = mcp.calls().getLast();
        assertThat(call.get("params").get("name").asString()).isEqualTo("search");
        assertThat(call.get("params").get("arguments").get("query").asString()).isEqualTo("invoices");

        ToolOutcome looked = call(setup, CallCredential.none(), "lookup", "{}");
        assertThat(done(looked).result().content()).extracting(ToolCallResult.Content::text)
            .containsExactly("Found 2", "(The tool returned a block of type `image`, which Tinfirm does not "
                    + "forward.)");
        assertThat(done(looked).result().structuredContent().get("count").asInt()).isEqualTo(2);
        assertThat(done(looked).written()).isNull();

        ToolOutcome broken = call(setup, CallCredential.none(), "broken", "{}");
        assertThat(done(broken).result().isError()).isTrue();
        assertThat(text(broken)).contains("The server refused tools/call: Invalid params: query is required");
    }

    @Test
    void aStoredTokenGoesInTheServersHeaderAndAnOAuthTokenAsABearerToken() {
        McpStub.Instance mcp = McpStub.newInstance().sse().withSession();
        Setup setup = server(mcp, List.of(McpStub.tool("search", null)));
        setup.admin().patch(setup.company().path("/systems/" + setup.systemId()), Map.of("authHeader", "X-API-Key"));

        call(setup, token(TOKEN), "search", "{\"query\":\"a\"}");
        call(setup, oauth("test-mcp-oauth-0001"), "search", "{\"query\":\"b\"}");

        List<String> keys = mcp.requests().stream().map(request -> request.getHeader("X-API-Key")).toList();
        List<String> bearers = mcp.requests().stream().map(request -> request.getHeader("Authorization")).toList();
        assertThat(keys).contains(TOKEN);
        assertThat(bearers).contains("Bearer test-mcp-oauth-0001").doesNotContain("Bearer " + TOKEN);
        assertThat(mcp.calls()).hasSize(2);
    }

    @Test
    void onlyAToolOfTheCatalogueThatIsOnRuns() {
        McpStub.Instance mcp = McpStub.newInstance();
        Setup setup = server(mcp, List.of(McpStub.tool("search", null), McpStub.tool("delete_all", null)));
        assertThat(setup.admin().patch(setup.company().path("/systems/" + setup.systemId() + "/tools/delete_all"),
                Map.of("enabled", false)).status()).isEqualTo(200);

        assertThat(call(setup, CallCredential.none(), "delete_all", "{}")).isEqualTo(new ToolOutcome.Refused(
                ToolOutcome.Code.UNKNOWN_TOOL, "`delete_all` is not a tool of Support MCP, or it is switched off."));
        assertThat(call(setup, CallCredential.none(), "unknown", "{}")).isInstanceOf(ToolOutcome.Refused.class);
        assertThat(call(setup, CallCredential.none(), "search", "[1]")).isEqualTo(new ToolOutcome.Refused(
                ToolOutcome.Code.INVALID_REQUEST, "`search` takes its arguments as a JSON object."));
        assertThat(mcp.calls()).isEmpty();
    }

    @Test
    void aRefusedCredentialAsksForTheAccountAndARefusedCallIsTheAgents() {
        McpStub.Instance mcp = McpStub.newInstance();
        Setup setup = server(mcp, List.of(McpStub.tool("search", null), McpStub.tool("admin", null)));
        McpStub.server().stubFor(post(urlEqualTo(mcp.url().substring(mcp.url().indexOf("/mcp-"))))
            .atPriority(3)
            .withRequestBody(matchingJsonPath("$.params[?(@.name == 'admin')]"))
            .willReturn(aResponse().withStatus(403).withBody("{\"error\":{\"message\":\"insufficient_scope\"}}")));

        ToolOutcome forbidden = call(setup, token(TOKEN), "admin", "{}");
        assertThat(done(forbidden).failure().kind()).isEqualTo(CallResult.Kind.FORBIDDEN);
        assertThat(text(forbidden)).contains("The server answered 403 to tools/call: insufficient_scope");

        mcp.failsWith(401);
        assertThat(call(setup, token(TOKEN), "search", "{}")).isEqualTo(new ToolOutcome.Reconnect(
                "The server answered 401 to initialize"));
    }
}
