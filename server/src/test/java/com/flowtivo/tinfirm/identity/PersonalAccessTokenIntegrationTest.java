package com.flowtivo.tinfirm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

/**
 * Personal access tokens (ADR-0045): a user manages only their own tokens on {@code /api/me/tokens}; a token works
 * only on the bearer chain of the MCP endpoint ({@code /api/companies/{companyId}/mcp}), which ignores the session
 * cookie and refuses browser origins; every other {@code /api/**} path stays session-only.
 */
class PersonalAccessTokenIntegrationTest extends IntegrationTest {

    private static final String TOKEN_FORMAT = "^tfp_[A-Za-z0-9_-]{43}$";

    private record Token(UUID id, String secret) {
    }

    private record User(UUID id, String email, ApiClient session) {
    }

    private User newUser(String name) {
        String email = uniqueEmail(name);
        UUID id = createUser(admin(), email, false);
        return new User(id, email, loginAs(email));
    }

    private Token createToken(ApiClient session, String name, String scope) {
        ApiResponse response = session.post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", name, "scope", scope));
        assertThat(response.status()).as("create token: %s", response).isEqualTo(201);
        JsonNode body = response.json();
        return new Token(UUID.fromString(body.get("personalAccessToken").get("id").asString()),
                body.get("token").asString());
    }

    /** Three base64url segments, as a JWT has, built here so that no literal in the test looks like a credential. */
    private static String jwtShaped() {
        Base64.Encoder base64url = Base64.getUrlEncoder().withoutPadding();
        return String.join(".", base64url.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)),
                base64url.encodeToString("{\"sub\":\"x\"}".getBytes(StandardCharsets.UTF_8)),
                base64url.encodeToString("sig".getBytes(StandardCharsets.UTF_8)));
    }

    private ApiResponse mcp(ApiClient client, UUID companyId, String method, Map<String, String> headers) {
        return client.send(method, "/api/companies/" + companyId + "/mcp",
                "POST".equals(method) ? Map.of("jsonrpc", "2.0", "id", 1, "method", "ping") : null, headers);
    }

    /** A company the user is a member of: the endpoint answers a token at any other with 404 (§14.11). */
    private UUID memberOf(User user) {
        TestCompany company = companyWithRoles();
        addMember(loginAs(company.email("OWNER")), company.id(), user.email(), "MEMBER");
        return company.id();
    }

    private ApiResponse mcpWith(String token, UUID companyId) {
        return mcp(client(), companyId, "POST", Map.of("Authorization", "Bearer " + token));
    }

    @Test
    void aUserCreatesListsAndRevokesOnlyTheirOwnTokens() throws Exception {
        User alice = newUser("alice");
        User bob = newUser("bob");

        ApiResponse created = alice.session().post("/api/me/tokens",
                Map.of("currentPassword", PASSWORD, "name", "  laptop claude code ", "scope", "DECIDE"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        String secret = created.json().get("token").asString();
        JsonNode metadata = created.json().get("personalAccessToken");
        assertThat(secret).matches(TOKEN_FORMAT);
        assertThat(metadata.get("name").asString()).isEqualTo("laptop claude code");
        assertThat(metadata.get("scope").asString()).isEqualTo("DECIDE");
        // Every token expires: 90 days from now by default (CONVENTIONS §5).
        assertThat(OffsetDateTime.parse(metadata.get("expiresAt").asString()))
            .isBetween(OffsetDateTime.now(ZoneOffset.UTC).plusDays(89), OffsetDateTime.now(ZoneOffset.UTC).plusDays(91));
        assertThat(metadata.path("companyIds").isMissingNode()).as("every company of the user").isTrue();
        assertThat(metadata.path("lastUsedAt").isMissingNode()).isTrue();
        UUID aliceToken = UUID.fromString(metadata.get("id").asString());
        Token bobToken = createToken(bob.session(), "bob ci", "READ");

        // Only the hash is stored.
        try (Connection app = TestDatabase.connectAsApp();
                PreparedStatement select = app.prepareStatement("SELECT token_hash FROM api_token WHERE id = ?")) {
            select.setObject(1, aliceToken);
            try (ResultSet row = select.executeQuery()) {
                assertThat(row.next()).isTrue();
                assertThat(row.getString(1)).isEqualTo(sha256Hex(secret));
            }
        }

        ApiResponse aliceList = alice.session().get("/api/me/tokens");
        assertThat(aliceList.status()).isEqualTo(200);
        assertThat(ids(aliceList.json())).containsExactly(aliceToken.toString());
        assertThat(aliceList.body()).doesNotContain(secret).doesNotContain(sha256Hex(secret));
        assertThat(aliceList.json().get(0).has("token")).isFalse();
        assertThat(aliceList.json().get(0).has("tokenHash")).isFalse();
        assertThat(ids(bob.session().get("/api/me/tokens").json())).containsExactly(bobToken.id().toString());

        // Bob can neither see nor revoke Alice's token: 404, as for an unknown one.
        assertThat(bob.session().delete("/api/me/tokens/" + aliceToken).status()).isEqualTo(404);
        assertThat(bob.session().delete("/api/me/tokens/" + UUID.randomUUID()).status()).isEqualTo(404);
        assertThat(ids(alice.session().get("/api/me/tokens").json())).containsExactly(aliceToken.toString());

        // Revoking is idempotent; the second call changes nothing and audits nothing.
        assertThat(alice.session().delete("/api/me/tokens/" + aliceToken).status()).isEqualTo(204);
        assertThat(alice.session().delete("/api/me/tokens/" + aliceToken).status()).isEqualTo(204);
        assertThat(alice.session().get("/api/me/tokens").json()).isEmpty();
        assertThat(ids(bob.session().get("/api/me/tokens").json())).containsExactly(bobToken.id().toString());

        JsonNode events = admin().get("/api/admin/audit-events?limit=200").json().get("items");
        List<JsonNode> createdEvents = events(events, "token.created", aliceToken);
        assertThat(createdEvents).hasSize(1);
        JsonNode createdEvent = createdEvents.getFirst();
        assertThat(createdEvent.get("actor").get("id").asString()).isEqualTo(alice.id().toString());
        assertThat(createdEvent.get("targetType").asString()).isEqualTo("api_token");
        assertThat(createdEvent.get("details").get("scope").asString()).isEqualTo("DECIDE");
        assertThat(createdEvent.get("details").has("name")).as("a user's free text stays out of the chain").isFalse();
        assertThat(createdEvent.toString()).doesNotContain(secret).doesNotContain(sha256Hex(secret));
        List<JsonNode> revokedEvents = events(events, "token.revoked", aliceToken);
        assertThat(revokedEvents).hasSize(1);
        assertThat(revokedEvents.getFirst().get("actor").get("id").asString()).isEqualTo(alice.id().toString());
        assertThat(events(events, "token.created", bobToken.id())).hasSize(1);
    }

    @Test
    void creatingATokenNamesTheOwnersOfEveryCompanyOfTheUser() {
        TestCompany first = companyWithRoles();
        TestCompany second = companyWithRoles();
        String email = first.email("MEMBER");
        addMember(loginAs(second.email("OWNER")), second.id(), email, "MEMBER");
        ApiResponse created = loginAs(email).post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "notice", "scope", "READ"));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        // A personal token sees no consent page: its creation is where its person is told (row 18).
        List<JsonNode> companies = created.json().get("ownerNotice").get("companies").valueStream().toList();
        assertThat(companies).extracting(company -> company.get("companyId").asString())
            .containsExactlyInAnyOrder(first.id().toString(), second.id().toString());
        for (JsonNode company : companies) {
            TestCompany which = company.get("companyId").asString().equals(first.id().toString()) ? first : second;
            assertThat(company.get("companyName").asString()).startsWith("Company");
            assertThat(company.get("owners").valueStream().map(JsonNode::asString))
                .containsExactly("User " + which.email("OWNER"));
        }
    }

    @Test
    void creatingValidatesTheRequestAndCapsActiveTokens() {
        User carol = newUser("carol");
        String past = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).toString();
        ApiResponse expired = carol.session().post("/api/me/tokens",
                Map.of("currentPassword", PASSWORD, "name", "old", "scope", "READ", "expiresAt", past));
        assertThat(expired.status()).as("%s", expired).isEqualTo(400);
        assertThat(expired.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(carol.session().post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "   ", "scope", "READ")).status())
            .isEqualTo(400);
        assertThat(carol.session().post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "x", "scope", "ADMIN")).status())
            .isEqualTo(400);

        String future = OffsetDateTime.now(ZoneOffset.UTC).plusDays(30).toString();
        ApiResponse expiring = carol.session().post("/api/me/tokens",
                Map.of("currentPassword", PASSWORD, "name", "expiring", "scope", "WRITE", "expiresAt", future));
        assertThat(expiring.status()).as("%s", expiring).isEqualTo(201);
        assertThat(expiring.json().get("personalAccessToken").path("expiresAt").isString()).isTrue();

        Token last = null;
        for (int i = 2; i <= 25; i++) {
            last = createToken(carol.session(), "token " + i, "READ");
        }
        ApiResponse tooMany = carol.session().post("/api/me/tokens", Map.of("currentPassword", PASSWORD,
                "name", "26", "scope", "READ"));
        assertThat(tooMany.status()).as("%s", tooMany).isEqualTo(409);
        assertThat(tooMany.problemCode()).isEqualTo("TOKEN_LIMIT");

        // A revoked token no longer counts.
        assertThat(carol.session().delete("/api/me/tokens/" + last.id()).status()).isEqualTo(204);
        createToken(carol.session(), "after a revoke", "READ");
    }

    @Test
    void aTokenAuthenticatesOnTheMcpChainUntilItIsRevokedOrExpires() throws Exception {
        User dave = newUser("dave");
        UUID company = memberOf(dave);
        Token token = createToken(dave.session(), "script", "READ");

        // Authenticated: the endpoint behind the chain answers the ping (the mcp module; GET is 405, stateless).
        ApiResponse accepted = mcpWith(token.secret(), company);
        assertThat(accepted.status()).as("%s", accepted).isEqualTo(200);
        assertThat(accepted.setCookies()).isEmpty();
        assertThat(mcp(client(), company, "GET", Map.of("Authorization", "bearer " + token.secret())).status())
            .as("the scheme is case-insensitive")
            .isEqualTo(405);
        JsonNode listed = dave.session().get("/api/me/tokens").json().get(0);
        assertThat(listed.path("lastUsedAt").isString()).as("last use recorded").isTrue();

        assertThat(dave.session().delete("/api/me/tokens/" + token.id()).status()).isEqualTo(204);
        assertInvalid(mcpWith(token.secret(), company));

        Token expiring = createToken(dave.session(), "expiring", "WRITE");
        assertThat(mcpWith(expiring.secret(), company).status()).isEqualTo(200);
        try (Connection app = TestDatabase.connectAsApp(); PreparedStatement expire = app.prepareStatement(
                "UPDATE api_token SET created_at = now() - interval '2 hours', expires_at = now() - interval '1 hour'"
                        + " WHERE id = ?")) {
            expire.setObject(1, expiring.id());
            assertThat(expire.executeUpdate()).isEqualTo(1);
        }
        assertInvalid(mcpWith(expiring.secret(), company));
        // An expired token is still listed (marked by its expiry) until it is revoked.
        assertThat(ids(dave.session().get("/api/me/tokens").json())).containsExactly(expiring.id().toString());
    }

    @Test
    void aDeactivatedUsersTokensAreRefused() {
        User erin = newUser("erin");
        Token token = createToken(erin.session(), "ci", "READ");
        UUID company = memberOf(erin);
        assertThat(mcpWith(token.secret(), company).status()).isEqualTo(200);

        ApiResponse deactivate = admin().patch("/api/admin/users/" + erin.id(), Map.of("active", false));
        assertThat(deactivate.status()).as("%s", deactivate).isEqualTo(200);
        assertInvalid(mcpWith(token.secret(), company));
    }

    @Test
    void theMcpChainRefusesMissingAndMalformedTokensAndBrowsers() {
        UUID company = UUID.randomUUID();
        ApiResponse missing = mcp(client(), company, "POST", Map.of());
        assertThat(missing.status()).isEqualTo(401);
        assertThat(missing.header("WWW-Authenticate")).isEqualTo(challenge(company));
        assertThat(missing.contentType()).startsWith("application/problem+json");
        // The security headers of every other chain (CONVENTIONS §5).
        assertThat(missing.header("Content-Security-Policy")).contains("default-src 'self'", "frame-ancestors 'none'");
        assertThat(missing.header("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(missing.header("Referrer-Policy")).isEqualTo("strict-origin-when-cross-origin");

        assertInvalid(mcpWith("tfp_short", company));
        assertInvalid(mcpWith("tfp_" + "A".repeat(43), company));
        assertInvalid(mcpWith("not-a-known-format", company));
        assertInvalid(mcpWith(jwtShaped(), company));

        // A browser: refused before the token is even looked at, a valid one included.
        Token token = createToken(newUser("frank").session(), "browser", "READ");
        ApiResponse fromBrowser = mcp(client(), company, "POST",
                Map.of("Authorization", "Bearer " + token.secret(), "Origin", "https://evil.example"));
        assertThat(fromBrowser.status()).as("%s", fromBrowser).isEqualTo(403);
        ApiResponse sameOrigin = mcp(client(), company, "POST",
                Map.of("Authorization", "Bearer " + token.secret(), "Origin", "http://localhost:" + port));
        assertThat(sameOrigin.status()).isEqualTo(403);
        assertThat(mcp(client(), company, "GET", Map.of("Origin", "null")).status()).isEqualTo(403);
        // Deeper paths of the endpoint are on the same chain.
        assertThat(client().send("GET", "/api/companies/" + company + "/mcp/anything", null, Map.of()).status())
            .isEqualTo(401);
    }

    @Test
    void aSessionCookieCannotReachTheMcpChain() {
        User grace = newUser("grace");
        // The session works on the REST API...
        assertThat(grace.session().get("/api/me").status()).isEqualTo(200);
        // ...and is ignored on the MCP endpoint, mutating or not, with or without the CSRF header.
        UUID company = UUID.randomUUID();
        ApiResponse post = mcp(grace.session(), company, "POST", Map.of());
        assertThat(post.status()).as("%s", post).isEqualTo(401);
        assertThat(post.header("WWW-Authenticate")).isEqualTo(challenge(company));
        assertThat(post.setCookies()).isEmpty();
        assertThat(mcp(grace.session(), company, "GET", Map.of()).status()).isEqualTo(401);
        // The session is still alive: the chain did not touch it.
        assertThat(grace.session().get("/api/me").status()).isEqualTo(200);
    }

    @Test
    void aTokenCannotReachTheRestApi() {
        User heidi = newUser("heidi");
        Token token = createToken(heidi.session(), "rest", "DECIDE");
        UUID company = createCompany(admin(), unique("Token Co"));
        addMember(admin(), company, heidi.email(), "OWNER");
        Map<String, String> bearer = Map.of("Authorization", "Bearer " + token.secret());

        for (String path : List.of("/api/me", "/api/me/tokens", "/api/companies", "/api/companies/" + company,
                "/api/companies/" + company + "/members", "/api/companies/" + company + "/inbox",
                "/api/admin/users")) {
            ApiResponse response = client().send("GET", path, null, bearer);
            assertThat(response.status()).as("GET %s with a token: %s", path, response).isEqualTo(401);
        }
        // A mutating request without a session: refused (the CSRF check of the session chain answers first).
        ApiResponse create = client().send("POST", "/api/me/tokens", Map.of("name", "x", "scope", "READ"), bearer);
        assertThat(create.status()).isIn(401, 403);
        assertThat(ids(heidi.session().get("/api/me/tokens").json())).containsExactly(token.id().toString());
    }

    private static void assertInvalid(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(401);
        assertThat(response.problemCode()).isEqualTo("TOKEN_INVALID");
        assertThat(response.header("WWW-Authenticate")).startsWith("Bearer error=\"invalid_token\", resource_metadata=\"")
            .endsWith("/mcp\", scope=\"tinfirm:read\"");
    }

    /** The 401 of the MCP endpoint names the company endpoint's resource metadata (RFC 9728) and the first scope. */
    private static String challenge(UUID company) {
        return "Bearer resource_metadata=\"http://localhost:8080/.well-known/oauth-protected-resource/api/companies/"
                + company + "/mcp\", scope=\"tinfirm:read\"";
    }

    private static List<String> ids(JsonNode tokens) {
        return StreamSupport.stream(tokens.spliterator(), false).map(token -> token.get("id").asString()).toList();
    }

    private static List<JsonNode> events(JsonNode events, String action, UUID target) {
        return StreamSupport.stream(events.spliterator(), false)
            .filter(event -> event.get("action").asString().equals(action))
            .filter(event -> event.path("targetId").asString("").equals(target.toString()))
            .toList();
    }

    private static String sha256Hex(String value) throws Exception {
        return HexFormat.of()
            .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
    }
}
