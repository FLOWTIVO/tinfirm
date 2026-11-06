package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.McpAuthorizationServer;
import com.flowtivo.tinfirm.support.McpStub;

import tools.jackson.databind.JsonNode;

/**
 * An MCP server's app whose token or revocation endpoint changes (the security review of 2026-10-27, M8: ADR-0079's
 * rule extended from the base URL to the app's endpoints): while OAuth accounts or credentials are on the server, the
 * save is {@code 409 SYSTEM_APP_ENDPOINTS_CHANGED} with what it would remove, and nothing changes; confirmed, they go
 * in the same transaction, each audited, and none of their tokens reaches the new endpoint. Endpoints off the issuer's
 * origin are refused by the discovery itself. TEST FIXTURES ONLY: every token here is a test value.
 */
class SystemServiceIntegrationTest extends IntegrationTest {

    @Test
    void aChangedTokenEndpointRemovesTheOAuthAccountsOnlyOnceConfirmed() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        McpStub.Instance mcp = McpStub.newInstance();
        McpAuthorizationServer server = McpAuthorizationServer.of(mcp, true, false);
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        String app = company.path("/systems/" + systemId + "/app");
        String credentials = company.path("/systems/" + systemId + "/credentials");
        server.serves("test-m8-member-access", List.of(McpStub.tool("search", "Searches")));
        server.signIn(member, company.path("/me/accounts/" + systemId), "test-m8-member-access",
                "test-m8-member-refresh", 3600);
        server.serves("test-m8-bot-access", List.of(McpStub.tool("search", "Searches")));
        server.signInCredential(admin, credentials, "CRM bot", "test-m8-bot-access", "test-m8-bot-refresh", 3600);

        // Set up again with the same endpoints: nothing to confirm, the accounts stay.
        ApiResponse same = admin.put(app, Map.of());
        assertThat(same.status()).as("%s", same).isEqualTo(200);
        assertThat(admin.get(credentials).json().get("items")).hasSize(1);

        // The server's metadata now names another token endpoint, on the issuer's origin.
        server.metadataNames(Map.of("token_endpoint", "/token-elsewhere"));
        ApiResponse preview = admin.put(app, Map.of());
        assertThat(preview.status()).as("%s", preview).isEqualTo(409);
        assertThat(preview.problemCode()).isEqualTo("SYSTEM_APP_ENDPOINTS_CHANGED");
        JsonNode details = preview.json().get("details");
        assertThat(details.get("changed").toString()).isEqualTo("[\"tokenEndpoint\"]");
        assertThat(details.get("tokenEndpoint").asString()).endsWith("-as/token-elsewhere");
        assertThat(details.get("previousTokenEndpoint").asString()).endsWith("-as/token");
        assertThat(details.get("accounts").asInt()).isEqualTo(1);
        assertThat(details.get("credentials")).singleElement()
            .satisfies(credential -> assertThat(credential.get("name").asString()).isEqualTo("CRM bot"));
        // Nothing changed: the app keeps its endpoints, the accounts stay.
        // (The view shows no endpoints: a second set-up previews the same change against the stored one.)
        ApiResponse again = admin.put(app, Map.of());
        assertThat(again.status()).as("%s", again).isEqualTo(409);
        assertThat(again.json().get("details").get("previousTokenEndpoint").asString()).endsWith("-as/token");
        assertThat(admin.get(credentials).json().get("items")).hasSize(1);
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isTrue();

        // Confirmed: both go in the save's transaction, audited, and no token reached either endpoint.
        ApiResponse confirmed = admin.put(app, Map.of("confirmEndpointsChange", true));
        assertThat(confirmed.status()).as("%s", confirmed).isEqualTo(200);
        assertThat(confirmed.json().get("app").get("clientId").asString()).isNotBlank();
        assertThat(admin.get(credentials).json().get("items")).isEmpty();
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isFalse();
        assertThat(auditEvents(owner, company, "account.disconnected")).hasSize(2)
            .allSatisfy(event -> {
                assertThat(event.get("details").get("cause").asString()).isEqualTo("system.app_endpoints_changed");
                assertThat(event.get("details").get("revoked").asBoolean()).isFalse();
            });
        JsonNode changed = auditEvents(owner, company, "system.app_changed").getFirst().get("details");
        assertThat(changed.get("accountsRemoved").asInt()).isEqualTo(2);
        assertThat(changed.get("tokenEndpoint").asString()).endsWith("-as/token-elsewhere");
        assertThat(changed.get("previousTokenEndpoint").asString()).endsWith("-as/token");
        assertThat(server.requests("/token-elsewhere")).isEmpty();
        assertThat(server.refreshes()).isEmpty();
        assertThat(server.requests("/revoke")).isEmpty();
    }

    @Test
    void aChangedRevocationEndpointCountsAndAServerWithoutAccountsChangesFreely() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        McpStub.Instance mcp = McpStub.newInstance();
        McpAuthorizationServer server = McpAuthorizationServer.of(mcp, true, false);
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        String app = company.path("/systems/" + systemId + "/app");

        // No account yet: a new revocation endpoint is taken at once.
        server.metadataNames(Map.of("revocation_endpoint", "/revoke-2"));
        assertThat(admin.put(app, Map.of()).status()).isEqualTo(200);

        server.serves("test-m8-revocation-access", List.of(McpStub.tool("search", "Searches")));
        server.signIn(member, company.path("/me/accounts/" + systemId), "test-m8-revocation-access",
                "test-m8-revocation-refresh", 3600);
        Map<String, @Nullable String> none = new HashMap<>();
        none.put("revocation_endpoint", null);
        server.metadataNames(none);
        ApiResponse preview = admin.put(app, Map.of());
        assertThat(preview.status()).as("%s", preview).isEqualTo(409);
        assertThat(preview.json().get("details").get("changed").toString()).isEqualTo("[\"revocationEndpoint\"]");
        assertThat(preview.json().get("details").get("accounts").asInt()).isEqualTo(1);
    }

    @Test
    void endpointsOffTheIssuersOriginAreRefusedByTheDiscovery() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        McpStub.Instance mcp = McpStub.newInstance();
        McpAuthorizationServer server = McpAuthorizationServer.of(mcp, true, false);
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        String app = company.path("/systems/" + systemId + "/app");

        // The issuer is on the stub's origin; a token endpoint at another host is not used.
        server.metadataNames(Map.of("token_endpoint", "https://tokens.example.com/token"));
        ApiResponse refused = admin.put(app, Map.of());
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("OAUTH_NOT_AVAILABLE");
        assertThat(refused.json().get("detail").asString()).contains("token_endpoint")
            .contains("not on the issuer's origin");
    }
}
