package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpAuthorizationServer;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.OAuthStubs;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * Sign-ins through a system's app and the one callback (the package 1 contract, §3.8 and §9): a state is used once,
 * only in the session that started it, only within ten minutes; {@code returnTo} is a path of this site; a person who
 * declines, and a system that refuses the grant, leave no account.
 */
class SignInIntegrationTest extends IntegrationTest {

    @Test
    void aMemberSignsInWithTheSystemsAppAndTheStateIsUsedOnce() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        String mine = company.path("/me/accounts/" + systemId);

        // No app yet: no sign-in.
        assertThat(member.post(mine + "/oauth", Map.of("returnTo", "/accounts")).problemCode())
            .isEqualTo("OAUTH_NOT_AVAILABLE");
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));

        // The authorization URL: the app's client, Tinfirm's callback, PKCE S256, and a state.
        ApiResponse started = member.post(mine + "/oauth", Map.of("returnTo", "/c/x/accounts?tab=mine"));
        URI url = URI.create(started.json().get("authorizationUrl").asString());
        Map<String, String> query = OAuthStubs.query(url.getRawQuery());
        assertThat(url.toString()).startsWith(jira.baseUrl() + "/rest/oauth2/latest/authorize?");
        assertThat(query).containsEntry("client_id", OAuthStubs.JIRA_CLIENT_ID)
            .containsEntry("code_challenge_method", "S256")
            .containsKeys("state", "code_challenge");
        assertThat(query.get("redirect_uri")).endsWith("/api/oauth/callback");
        String state = query.get("state");
        String code = "test-jira-code-" + UUID.randomUUID();
        OAuthStubs.jiraTokens(jira, "code=" + code, "test-access-1", "test-refresh-1", 3600);
        jira.myself("test-access-1", "mia", "UTC");

        ApiResponse callback = member.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(callback.status()).isEqualTo(303);
        assertThat(callback.header("Location")).isEqualTo("/c/x/accounts?tab=mine&account=connected&system="
                + systemId);
        JsonNode account = member.get(company.path("/me/accounts")).json().get("items").get(0).get("account");
        assertThat(account.get("kind").asString()).isEqualTo("OAUTH");
        assertThat(account.get("login").asString()).isEqualTo("mia");
        // The code exchange carried the verifier of the challenge, and the client authenticated.
        assertThat(JiraStub.server().findAll(com.github.tomakehurst.wiremock.client.WireMock
            .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(jira.path()
                    + "/rest/oauth2/latest/token")))
            .stream()
            .map(OAuthStubs::form)
            .filter(form -> code.equals(form.get("code"))))
            .singleElement()
            .satisfies(form -> assertThat(form.get("code_verifier")).hasSize(43));

        // The state is used once.
        ApiResponse reused = member.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(reused.header("Location")).isEqualTo("/?account=invalid_state");
        assertThat(member.get("/api/oauth/callback?code=x&state=unknown").header("Location"))
            .isEqualTo("/?account=invalid_state");
        assertThat(member.get("/api/oauth/callback?code=x").header("Location")).isEqualTo("/?account=invalid_state");
    }

    @Test
    void aMemberSigningInAsASharedAccountsUserIsToldItIsShared() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        UUID bot = createServiceIdentity(admin, company, "Support bot");
        connectServiceToken(admin, company, bot, systemId, jira,
                "test-bot-token", "support-bot");

        // The member signs in as the Jira user the service identity's account is: I2 refuses it, and says why.
        ApiResponse started = member.post(company.path("/me/accounts/" + systemId) + "/oauth",
                Map.of("returnTo", "/accounts"));
        String state = OAuthStubs.query(URI.create(started.json().get("authorizationUrl").asString()).getRawQuery())
            .get("state");
        String code = "test-jira-code-" + UUID.randomUUID();
        OAuthStubs.jiraTokens(jira, "code=" + code, "test-access-shared", "test-refresh-shared", 3600);
        jira.myself("test-access-shared", "support-bot", "UTC");
        ApiResponse callback = member.get("/api/oauth/callback?code=" + code + "&state=" + state);
        assertThat(callback.header("Location")).isEqualTo("/accounts?account=identity_shared");
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isFalse();
    }

    @Test
    void aPendingSignInKeepsNoVerifier() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        ApiResponse started = member.post(company.path("/me/accounts/" + systemId + "/oauth"),
                Map.of("returnTo", "/accounts"));
        Map<String, String> query = OAuthStubs
            .query(URI.create(started.json().get("authorizationUrl").asString()).getRawQuery());
        String challenge = query.get("code_challenge");

        // Nothing kept with the pending sign-in yields the verifier: no column holds it, no value is its S256's.
        try (Connection owner = TestDatabase.connectAsOwner(); PreparedStatement select = owner.prepareStatement(
                "SELECT * FROM oauth_authorization_request WHERE company_id = ?")) {
            select.setObject(1, company.id());
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                ResultSetMetaData columns = row.getMetaData();
                for (int i = 1; i <= columns.getColumnCount(); i++) {
                    assertThat(columns.getColumnName(i)).isNotEqualTo("code_verifier");
                    Object value = row.getObject(i);
                    if (value != null) {
                        assertThat(s256(value.toString())).as(columns.getColumnName(i)).isNotEqualTo(challenge);
                    }
                }
                assertThat(row.next()).isFalse();
            }
        }

        // The callback has it again from the state: the code exchange carries the verifier of the challenge.
        String code = "test-jira-code-" + UUID.randomUUID();
        OAuthStubs.jiraTokens(jira, "code=" + code, "test-access-pkce", "test-refresh-pkce", 3600);
        jira.myself("test-access-pkce", "pia", "UTC");
        assertThat(member.get("/api/oauth/callback?code=" + code + "&state=" + query.get("state")).header("Location"))
            .startsWith("/accounts?account=connected");
        assertThat(JiraStub.server().findAll(com.github.tomakehurst.wiremock.client.WireMock
            .postRequestedFor(com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo(jira.path()
                    + "/rest/oauth2/latest/token")))
            .stream()
            .map(OAuthStubs::form)
            .filter(form -> code.equals(form.get("code"))))
            .singleElement()
            .satisfies(form -> {
                assertThat(form.get("code_verifier")).hasSize(43).matches("[A-Za-z0-9_-]+");
                assertThat(s256(form.get("code_verifier"))).isEqualTo(challenge);
            });
    }

    @Test
    void aSignInBelongsToTheSessionThatStartedIt() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiClient sameMemberElsewhere = loginAs(company.email("MEMBER"));
        ApiClient owner = loginAs(company.email("OWNER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        String state = OAuthStubs.startSignIn(member, company.path("/me/accounts/" + systemId), "/accounts");
        String code = "test-jira-code-" + UUID.randomUUID();
        OAuthStubs.jiraTokens(jira, "code=" + code, "test-access-2", "test-refresh-2", 3600);
        jira.myself("test-access-2", "mia", "UTC");

        // Another session of the same person, and another person: refused, and the sign-in stays pending.
        assertThat(sameMemberElsewhere.get("/api/oauth/callback?code=" + code + "&state=" + state).header("Location"))
            .isEqualTo("/?account=invalid_state");
        assertThat(owner.get("/api/oauth/callback?code=" + code + "&state=" + state).header("Location"))
            .isEqualTo("/?account=invalid_state");
        assertThat(client().get("/api/oauth/callback?code=" + code + "&state=" + state).status()).isEqualTo(401);
        assertThat(member.get("/api/oauth/callback?code=" + code + "&state=" + state).header("Location"))
            .startsWith("/accounts?account=connected");
    }

    @Test
    void aSignInExpiresAfterTenMinutes() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        String state = OAuthStubs.startSignIn(member, company.path("/me/accounts/" + systemId), "/accounts");
        // Ten minutes and a second later (as the schema owner: the runtime role never updates a sign-in).
        try (Connection owner = TestDatabase.connectAsOwner(); PreparedStatement age = owner.prepareStatement("""
                UPDATE oauth_authorization_request
                   SET created_at = created_at - interval '11 minutes', expires_at = now() - interval '1 second'
                 WHERE company_id = ?""")) {
            age.setObject(1, company.id());
            assertThat(age.executeUpdate()).isEqualTo(1);
        }
        assertThat(member.get("/api/oauth/callback?code=test-code&state=" + state).header("Location"))
            .isEqualTo("/accounts?account=expired");
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isFalse();
    }

    @Test
    void returnToIsAPathOfThisSiteAndDeclinesAndRefusalsLeaveNoAccount() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(admin, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        OAuthStubs.jiraClient(admin, company.path("/systems/" + systemId));
        String mine = company.path("/me/accounts/" + systemId);

        for (String foreign : List.of("https://evil.example/accounts", "//evil.example/accounts", "/\\evil.example",
                "javascript:alert(1)", "accounts", "/accounts#fragment", "/a b", " ")) {
            ApiResponse refused = member.post(mine + "/oauth", Map.of("returnTo", foreign));
            assertThat(refused.status()).as("returnTo %s", foreign).isEqualTo(400);
        }

        // The person declines at the system.
        String declined = OAuthStubs.startSignIn(member, mine, "/accounts");
        assertThat(member.get("/api/oauth/callback?error=access_denied&state=" + declined).header("Location"))
            .isEqualTo("/accounts?account=denied");

        // The system refuses the code: no account, an audit with the reason and never a token.
        String refusedState = OAuthStubs.startSignIn(member, mine, "/accounts");
        OAuthStubs.jiraRefuses(jira, "code=test-refused-code", "invalid_grant");
        assertThat(member.get("/api/oauth/callback?code=test-refused-code&state=" + refusedState).header("Location"))
            .isEqualTo("/accounts?account=refused");
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isFalse();
        assertThat(auditEvents(owner, company, "account.refused")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("reason").asString()).contains("invalid_grant"));
    }

    @Test
    void anAdminSignsInASystemCredentialOnAnMcpServer() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        McpStub.Instance mcp = McpStub.newInstance();
        McpAuthorizationServer server = McpAuthorizationServer.of(mcp, true, false);
        // Its app is set up as it is registered: nobody sets it before the first sign-in.
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        String credentials = company.path("/systems/" + systemId + "/credentials");

        assertThat(member.post(credentials + "/oauth", Map.of("name", "CRM bot", "returnTo", "/accounts")).status())
            .isEqualTo(403);
        server.serves("test-crm-access", List.of(McpStub.tool("search", "Searches")));
        server.signInCredential(admin, credentials, "CRM bot", "test-crm-access", "test-crm-refresh", 3600);
        JsonNode signedIn = admin.get(credentials).json().get("items");
        assertThat(signedIn).singleElement().satisfies(item -> {
            assertThat(item.get("name").asString()).isEqualTo("CRM bot");
            assertThat(item.get("kind").asString()).isEqualTo("OAUTH");
            assertThat(item.get("scopes").asString()).isEqualTo("crm:read crm:write");
        });
        String credential = credentials + "/" + signedIn.get(0).get("id").asString();
        // The sign-in named the server as the resource (RFC 8707), and Tinfirm's metadata document as the client.
        Map<String, String> form = OAuthStubs.form(server.requests("/token").getLast());
        assertThat(form.get("resource")).isEqualTo(mcp.url());
        assertThat(form.get("client_id")).endsWith("/oauth/client-metadata.json");

        // Signed in again: the same credential, its name kept.
        server.serves("test-crm-access-2", List.of(McpStub.tool("search", "Searches")));
        server.signIn(admin, credential, "test-crm-access-2", "test-crm-refresh-2", 3600);
        assertThat(admin.get(credentials).json().get("items")).singleElement()
            .satisfies(item -> assertThat(item.get("name").asString()).isEqualTo("CRM bot"));
        assertThat(auditEvents(admin, company, "account.reconnected")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("credential").asString()).isEqualTo("CRM bot"));
        // A new one under a name the system's credentials hold already is refused before the round trip.
        ApiResponse taken = admin.post(credentials + "/oauth", Map.of("name", "crm BOT", "returnTo", "/accounts"));
        assertThat(taken.status()).as("%s", taken).isEqualTo(409);
        assertThat(taken.problemCode()).isEqualTo("DUPLICATE_CREDENTIAL_NAME");

        // Removed: the grant is revoked at the server first (outside any transaction), then the credential goes.
        assertThat(admin.delete(credential).status()).isEqualTo(204);
        assertThat(server.requests("/revoke")).singleElement()
            .satisfies(revoke -> assertThat(OAuthStubs.form(revoke)).containsEntry("token", "test-crm-refresh-2")
                .containsEntry("token_type_hint", "refresh_token"));
        assertThat(auditEvents(admin, company, "account.disconnected")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("revoked").asBoolean()).isTrue());
        assertThat(admin.get(credentials).json().get("items")).isEmpty();
    }

    @Test
    void aGrantWhoseSecretCannotBeDecryptedGoesUnrevoked() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        McpStub.Instance mcp = McpStub.newInstance();
        McpAuthorizationServer server = McpAuthorizationServer.of(mcp, true, false);
        UUID systemId = createSystem(admin, company, "CRM", "MCP", mcp.url(), false);
        assertThat(admin.put(company.path("/systems/" + systemId + "/app"), Map.of()).status()).isEqualTo(200);
        String mine = company.path("/me/accounts/" + systemId);
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));

        // The operator dropped the master key that wraps the grant's data key before rewrapping it.
        server.serves("test-lost-access-1", List.of(McpStub.tool("search", "Searches")));
        server.signIn(member, mine, "test-lost-access-1", "test-lost-refresh-1", 3600);
        try (Unreadable lost = Unreadable.secretOf(company, systemId, memberPrincipal)) {
            // A disconnect: nothing to revoke with, and the account goes all the same.
            assertThat(member.delete(mine).status()).isEqualTo(204);
        }
        assertThat(member.get(company.path("/me/accounts")).json().get("items").get(0).has("account")).isFalse();
        assertThat(auditEvents(owner, company, "account.disconnected")).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("revoked").asBoolean()).isFalse();
            assertThat(event.get("details").get("reason").asString()).isEqualTo("SECRET_UNREADABLE");
        });

        // A system's credential removed: it goes all the same, unrevoked.
        String credentials = company.path("/systems/" + systemId + "/credentials");
        server.serves("test-lost-access-2", List.of(McpStub.tool("search", "Searches")));
        server.signInCredential(admin, credentials, "CRM bot", "test-lost-access-2", "test-lost-refresh-2", 3600);
        UUID credential = UUID.fromString(admin.get(credentials).json().get("items").get(0).get("id").asString());
        try (Unreadable lost = Unreadable.secretWhere(company, "id = ?", credential)) {
            assertThat(admin.delete(credentials + "/" + credential).status()).isEqualTo(204);
        }
        JsonNode deleted = auditEvents(owner, company, "account.disconnected").getFirst().get("details");
        assertThat(deleted.get("credential").asString()).isEqualTo("CRM bot");
        assertThat(deleted.get("revoked").asBoolean()).isFalse();
        assertThat(deleted.get("reason").asString()).isEqualTo("SECRET_UNREADABLE");
        assertThat(deleted.has("revocation")).isFalse();

        // A member who leaves: removed, with the account.
        server.serves("test-lost-access-3", List.of(McpStub.tool("search", "Searches")));
        server.signIn(member, mine, "test-lost-access-3", "test-lost-refresh-3", 3600);
        try (Unreadable lost = Unreadable.secretOf(company, systemId, memberPrincipal)) {
            assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        }
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT status FROM principal WHERE id = ?", memberPrincipal))
                .containsExactly("LEFT");
            assertThat(sql.count("SELECT count(*) FROM account WHERE principal_id = ?", memberPrincipal)).isZero();
        }
        JsonNode left = auditEvents(owner, company, "account.disconnected").getFirst().get("details");
        assertThat(left.get("cause").asString()).isEqualTo("principal.left");
        assertThat(left.get("revoked").asBoolean()).isFalse();
        assertThat(left.get("reason").asString()).isEqualTo("SECRET_UNREADABLE");
        // Nothing was sent to revoke, and no grant reached the log.
        assertThat(server.requests("/revoke")).isEmpty();
        auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain("test-lost-"));
    }

    /** The {@code S256} code challenge of a verifier (RFC 7636 §4.2). */
    private static String s256(String verifier) {
        try {
            return Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /**
     * The secret of the account the condition names, wrapped under a master key the server does not have, as after an
     * operator dropped an old key before rewrapping; {@link #close()} puts the key id back on what is left of it.
     */
    private record Unreadable(UUID secretId, String keyId) implements AutoCloseable {

        /** The member's own account on the system. */
        static Unreadable secretOf(TestCompany company, UUID systemId, UUID principalId) throws SQLException {
            return secretWhere(company, "system_id = ? AND principal_id = ?", systemId, principalId);
        }

        static Unreadable secretWhere(TestCompany company, String where, Object... args) throws SQLException {
            UUID secretId;
            try (TenantSql sql = TenantSql.in(company.id())) {
                secretId = (UUID) sql.column("SELECT secret_id FROM account WHERE " + where, args).getFirst();
            }
            String keyId;
            try (Connection owner = ownerAsAdmin();
                    PreparedStatement query = owner.prepareStatement("SELECT key_id FROM secret_version"
                            + " WHERE secret_id = ? ORDER BY version DESC LIMIT 1");
                    PreparedStatement update = owner.prepareStatement("UPDATE secret_version"
                            + " SET key_id = 'test-dropped-key' WHERE secret_id = ?")) {
                query.setObject(1, secretId);
                try (ResultSet row = query.executeQuery()) {
                    assertThat(row.next()).isTrue();
                    keyId = row.getString(1);
                }
                update.setObject(1, secretId);
                assertThat(update.executeUpdate()).isPositive();
            }
            return new Unreadable(secretId, keyId);
        }

        @Override
        public void close() throws SQLException {
            try (Connection owner = ownerAsAdmin();
                    PreparedStatement update = owner.prepareStatement("UPDATE secret_version SET key_id = ?"
                            + " WHERE secret_id = ? AND key_id = 'test-dropped-key'")) {
                update.setString(1, keyId);
                update.setObject(2, secretId);
                update.executeUpdate();
            }
        }

        /** The owner is subject to FORCE ROW LEVEL SECURITY too; the system context (app.system) lets it see all rows. */
        private static Connection ownerAsAdmin() throws SQLException {
            Connection connection = TestDatabase.connectAsOwner();
            connection.createStatement().execute("SET app.system = 'true'");
            return connection;
        }
    }
}
