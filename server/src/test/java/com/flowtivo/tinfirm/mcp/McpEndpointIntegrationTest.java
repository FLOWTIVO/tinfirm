package com.flowtivo.tinfirm.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpRequest;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;

/**
 * The MCP endpoint of a company (ADR-0045) against the running server, through the MCP Java SDK's client: the
 * initialization, and the endpoint's HTTP rules behind the bearer chain. The tools on conversations have their own
 * test ({@code McpConversationToolsIntegrationTest}), the rate limit its own ({@code McpRateLimitIntegrationTest}),
 * the scope filter its unit test ({@code McpRequestFilterTest}), and the credentials theirs in the identity module.
 */
@ExtendWith(EnterpriseLicence.class)
class McpEndpointIntegrationTest extends IntegrationTest {

    private String personalToken(ApiClient session, String scope) {
        ApiResponse response = session.post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "mcp test", "scope", scope));
        assertThat(response.status()).as("create token: %s", response).isEqualTo(201);
        return response.json().get("token").asString();
    }

    private McpSyncClient connect(UUID companyId, String token) {
        HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
            .builder("http://localhost:" + port)
            .endpoint("/api/companies/" + companyId + "/mcp")
            .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
            .build();
        McpSyncClient client = McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
        client.initialize();
        return client;
    }

    private static Map<String, String> bearer(String token) {
        return Map.of("Authorization", "Bearer " + token);
    }

    private ApiResponse post(UUID companyId, Object body, Map<String, String> headers) {
        return client().send("POST", "/api/companies/" + companyId + "/mcp", body, headers);
    }

    @Test
    void initializeNamesTheServer() {
        TestCompany company = companyWithRoles();
        String token = personalToken(loginAs(company.email("MEMBER")), "READ");
        try (McpSyncClient client = connect(company.id(), token)) {
            McpSchema.InitializeResult initialized = client.getCurrentInitializationResult();
            assertThat(initialized.serverInfo().name()).isEqualTo("tinfirm");
            String version = client().get("/api/system/info").json().get("version").asString();
            assertThat(initialized.serverInfo().version()).isEqualTo(version);
            assertThat(initialized.instructions()).contains("Tinfirm").contains("other people's words");
            assertThat(initialized.capabilities().tools()).isNotNull();
            assertThat(initialized.capabilities().resources()).isNull();
            assertThat(initialized.capabilities().prompts()).isNull();
            assertThat(client.listTools().tools()).isNotEmpty();
        }
    }

    /** ADR-0077, decision 5: a Viewer's client reads, and every tool that changes something is refused. */
    @Test
    void aViewersClientChangesNothing() {
        TestCompany company = companyWithRoles();
        String email = uniqueEmail("viewer");
        createUser(admin(), email, false);
        addMember(loginAs(company.email("OWNER")), company.id(), email, "VIEWER");
        String token = personalToken(loginAs(email), "WRITE");

        for (Map<String, Object> call : List.of(
                Map.<String, Object>of("name", "delete_memory_note", "arguments",
                        Map.of("agent", UUID.randomUUID().toString(), "number", 1)),
                Map.<String, Object>of("name", "test_system", "arguments",
                        Map.of("systemId", UUID.randomUUID().toString())))) {
            ApiResponse refused = post(company.id(), Map.of("jsonrpc", "2.0", "id", 9, "method", "tools/call",
                    "params", call), bearer(token));
            assertThat(refused.status()).as("%s", refused).isEqualTo(200);
            assertThat(refused.json().get("result").get("isError").asBoolean()).as("%s", refused).isTrue();
            assertThat(refused.json().get("result").get("content").get(0).get("text").asString()).as("%s", refused)
                .startsWith("VIEWER_READ_ONLY");
        }
        ApiResponse read = post(company.id(), Map.of("jsonrpc", "2.0", "id", 10, "method", "tools/call", "params",
                Map.of("name", "list_agents", "arguments", Map.of())), bearer(token));
        assertThat(read.json().get("result").get("isError").asBoolean()).as("%s", read).isFalse();
    }

    @Test
    void theEndpointsHttpRules() {
        TestCompany company = companyWithRoles();
        String token = personalToken(loginAs(company.email("MEMBER")), "READ");
        Map<String, Object> ping = Map.of("jsonrpc", "2.0", "id", 7, "method", "ping");

        // Without Accept: application/json, text/event-stream the endpoint still answers.
        ApiResponse plain = post(company.id(), ping, bearer(token));
        assertThat(plain.status()).as("%s", plain).isEqualTo(200);
        assertThat(plain.json().get("id").asInt()).isEqualTo(7);
        // Stateless: no GET stream, no session to delete.
        for (String method : List.of("GET", "DELETE")) {
            ApiResponse refused = client().send(method, "/api/companies/" + company.id() + "/mcp", null,
                    bearer(token));
            assertThat(refused.status()).as("%s: %s", method, refused).isEqualTo(405);
            assertThat(refused.header("Allow")).isEqualTo("POST");
        }
        // One message per request: a batch is refused.
        ApiResponse batch = post(company.id(), List.of(ping, ping), bearer(token));
        assertThat(batch.status()).as("%s", batch).isEqualTo(400);
        assertThat(batch.json().get("error").get("message").asString()).contains("Batched");
        // The body is capped at 1 MiB.
        String large = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\",\"params\":{\"x\":\""
                + "a".repeat(1024 * 1024) + "\"}}";
        ApiResponse tooLarge = post(company.id(), large, bearer(token));
        assertThat(tooLarge.status()).as("%s", tooLarge).isEqualTo(413);
        assertThat(tooLarge.json().get("error").get("code").asInt()).isEqualTo(-32600);
        // The bearer chain in front: a browser's Origin is refused, a missing token is 401 with the challenge.
        ApiResponse browser = post(company.id(), ping, Map.of("Authorization", "Bearer " + token, "Origin",
                "https://evil.example"));
        assertThat(browser.status()).isEqualTo(403);
        // Tinfirm's own origin, the Outlook add-in's task pane (ADR-0074), passes; one that only resembles it does not.
        ApiResponse taskPane = post(company.id(), ping, Map.of("Authorization", "Bearer " + token, "Origin",
                "http://localhost:8080"));
        assertThat(taskPane.status()).as("%s", taskPane).isEqualTo(200);
        for (String lookalike : new String[] { "http://localhost:8080.evil.example", "https://localhost:8080",
                "http://localhost:8081", "null" }) {
            assertThat(post(company.id(), ping, Map.of("Authorization", "Bearer " + token, "Origin", lookalike))
                .status()).as(lookalike).isEqualTo(403);
        }
        ApiResponse anonymous = post(company.id(), ping, Map.of());
        assertThat(anonymous.status()).isEqualTo(401);
        assertThat(anonymous.header("WWW-Authenticate")).contains("resource_metadata=");
        // A tool that does not exist passes the scope filter (it needs no scope) and is the server's error.
        ApiResponse unknown = post(company.id(), Map.of("jsonrpc", "2.0", "id", 8, "method", "tools/call", "params",
                Map.of("name", "no_such_tool", "arguments", Map.of())), bearer(token));
        assertThat(unknown.status()).as("%s", unknown).isEqualTo(200);
        assertThat(unknown.header("WWW-Authenticate")).isNull();
        assertThat(unknown.json().toString()).doesNotContain("INSUFFICIENT_SCOPE");
        // A company that is no UUID is not the endpoint.
        assertThat(client().send("POST", "/api/companies/not-a-uuid/mcp", ping, bearer(token)).status())
            .isEqualTo(404);
    }
}
