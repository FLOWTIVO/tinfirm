package com.flowtivo.tinfirm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
 * Personal access tokens outlive nobody's intent (the security review of 2026-10-27, D9 and L34; the owner's decision
 * Q6; CONVENTIONS §5): they expire 90 days after their creation unless renewed, reach only the companies they name,
 * are created only with the current password, and are revoked on a password change, an instance admin's reset and on
 * leaving a company they name. An instance admin's reset is recorded in every company of the user (L35, Q7).
 */
class ApiTokenIntegrationTest extends IntegrationTest {

    private record Token(UUID id, String secret, JsonNode metadata) {
    }

    private Token create(ApiClient session, Map<String, Object> body) {
        ApiResponse response = session.post("/api/me/tokens", body);
        assertThat(response.status()).as("create token: %s", response).isEqualTo(201);
        JsonNode metadata = response.json().get("personalAccessToken");
        return new Token(UUID.fromString(metadata.get("id").asString()), response.json().get("token").asString(),
                metadata);
    }

    private Token create(ApiClient session) {
        return create(session, Map.of("name", "client", "scope", "READ", "currentPassword", PASSWORD));
    }

    /** A company the user is a member of: the endpoint answers a token at any other with 404 (§14.11). */
    private UUID memberOf(String email) {
        TestCompany company = companyWithRoles();
        addMember(loginAs(company.email("OWNER")), company.id(), email, "MEMBER");
        return company.id();
    }

    private ApiResponse ping(Token token, UUID companyId) {
        return client().send("POST", "/api/companies/" + companyId + "/mcp",
                Map.of("jsonrpc", "2.0", "id", 1, "method", "ping"),
                Map.of("Authorization", "Bearer " + token.secret()));
    }

    @Test
    void aTokenExpiresIn90DaysAndIsRenewedForAnother90() throws Exception {
        String email = uniqueEmail("renew");
        createUser(admin(), email, false);
        UUID company = memberOf(email);
        ApiClient session = loginAs(email);
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);

        Token token = create(session);
        assertThat(OffsetDateTime.parse(token.metadata().get("expiresAt").asString()))
            .isBetween(now.plusDays(89), now.plusDays(91));
        ApiResponse tooLong = session.post("/api/me/tokens", Map.of("name", "long", "scope", "READ",
                "currentPassword", PASSWORD, "expiresAt", now.plusDays(120).toString()));
        assertThat(tooLong.status()).as("%s", tooLong).isEqualTo(400);
        assertThat(tooLong.problemCode()).isEqualTo("VALIDATION_FAILED");

        // Close to its end, renewed: 90 days from now again, the same secret working.
        setExpiry(token.id(), "1 day");
        ApiResponse renewed = session.post("/api/me/tokens/" + token.id() + "/renew", Map.of());
        assertThat(renewed.status()).as("%s", renewed).isEqualTo(200);
        assertThat(OffsetDateTime.parse(renewed.json().get("expiresAt").asString()))
            .isBetween(now.plusDays(89), now.plusDays(91));
        assertThat(ping(token, company).status()).isEqualTo(200);

        // An expired token is not renewed: a new one is created.
        setExpiry(token.id(), "-1 minute");
        ApiResponse expired = session.post("/api/me/tokens/" + token.id() + "/renew", Map.of());
        assertThat(expired.status()).as("%s", expired).isEqualTo(409);
        assertThat(expired.problemCode()).isEqualTo("TOKEN_EXPIRED");
        // Another user's token is unknown.
        assertThat(loginAs(uniqueUser()).post("/api/me/tokens/" + token.id() + "/renew", Map.of()).status())
            .isEqualTo(404);

        JsonNode events = admin().get("/api/admin/audit-events?limit=200").json().get("items");
        assertThat(events(events, "token.renewed", token.id())).hasSize(1);
    }

    @Test
    void creatingATokenNeedsTheCurrentPassword() {
        String email = uniqueEmail("password");
        createUser(admin(), email, false);
        ApiClient session = loginAs(email);

        ApiResponse missing = session.post("/api/me/tokens", Map.of("name", "x", "scope", "READ"));
        assertThat(missing.status()).as("%s", missing).isEqualTo(400);
        assertThat(missing.problemCode()).isEqualTo("WRONG_PASSWORD");
        ApiResponse wrong = session.post("/api/me/tokens",
                Map.of("name", "x", "scope", "READ", "currentPassword", "not-the-password"));
        assertThat(wrong.status()).as("%s", wrong).isEqualTo(400);
        assertThat(wrong.problemCode()).isEqualTo("WRONG_PASSWORD");
        assertThat(session.get("/api/me/tokens").json()).isEmpty();
    }

    @Test
    void aTokenReachesOnlyTheCompaniesItNames() {
        TestCompany first = companyWithRoles();
        TestCompany second = companyWithRoles();
        String email = first.email("MEMBER");
        addMember(loginAs(second.email("OWNER")), second.id(), email, "MEMBER");
        ApiClient session = loginAs(email);

        Token scoped = create(session, Map.of("name", "first only", "scope", "READ", "currentPassword", PASSWORD,
                "companyIds", List.of(first.id().toString())));
        assertThat(scoped.metadata().get("companyIds").get(0).asString()).isEqualTo(first.id().toString());
        assertThat(ping(scoped, first.id()).status()).isEqualTo(200);
        assertThat(ping(scoped, second.id()).status()).isEqualTo(401);
        assertThat(ping(scoped, UUID.randomUUID()).status()).isEqualTo(401);

        Token everywhere = create(session);
        assertThat(ping(everywhere, first.id()).status()).isEqualTo(200);
        assertThat(ping(everywhere, second.id()).status()).isEqualTo(200);

        // Only companies the user is a member of, and at least one when a list is given.
        ApiResponse foreign = session.post("/api/me/tokens", Map.of("name", "x", "scope", "READ", "currentPassword",
                PASSWORD, "companyIds", List.of(companyWithRoles().id().toString())));
        assertThat(foreign.status()).as("%s", foreign).isEqualTo(400);
        ApiResponse empty = session.post("/api/me/tokens",
                Map.of("name", "x", "scope", "READ", "currentPassword", PASSWORD, "companyIds", List.of()));
        assertThat(empty.status()).as("%s", empty).isEqualTo(400);
    }

    @Test
    void leavingANamedCompanyRevokesTheTokensThatNameIt() {
        TestCompany first = companyWithRoles();
        TestCompany second = companyWithRoles();
        String email = first.email("MEMBER");
        addMember(loginAs(second.email("OWNER")), second.id(), email, "MEMBER");
        ApiClient session = loginAs(email);
        Token namesFirst = create(session, Map.of("name", "first", "scope", "READ", "currentPassword", PASSWORD,
                "companyIds", List.of(first.id().toString(), second.id().toString())));
        Token namesSecond = create(session, Map.of("name", "second", "scope", "READ", "currentPassword", PASSWORD,
                "companyIds", List.of(second.id().toString())));
        Token everywhere = create(session);

        ApiResponse removed = loginAs(first.email("OWNER"))
            .delete("/api/companies/" + first.id() + "/members/" + first.userId("MEMBER"));
        assertThat(removed.status()).as("%s", removed).isEqualTo(204);

        assertThat(ping(namesFirst, second.id()).status()).isEqualTo(401);
        assertThat(ping(namesSecond, second.id()).status()).isEqualTo(200);
        assertThat(ping(everywhere, second.id()).status()).isEqualTo(200);
        JsonNode events = admin().get("/api/admin/audit-events?limit=200").json().get("items");
        List<JsonNode> revoked = events(events, "token.revoked", namesFirst.id());
        assertThat(revoked).hasSize(1);
        assertThat(revoked.getFirst().get("details").get("reason").asString()).isEqualTo("LEFT_COMPANY");
        assertThat(revoked.getFirst().get("details").get("companyId").asString()).isEqualTo(first.id().toString());
    }

    @Test
    void aPasswordChangeRevokesEveryToken() {
        String email = uniqueEmail("change");
        createUser(admin(), email, false);
        UUID company = memberOf(email);
        ApiClient session = loginAs(email);
        Token token = create(session);
        assertThat(ping(token, company).status()).isEqualTo(200);

        ApiResponse changed = session.put("/api/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "another-password-456"));
        assertThat(changed.status()).as("%s", changed).isEqualTo(204);

        assertThat(ping(token, company).status()).isEqualTo(401);
        assertThat(session.get("/api/me/tokens").json()).isEmpty();
        JsonNode events = admin().get("/api/admin/audit-events?limit=200").json().get("items");
        assertThat(events(events, "token.revoked", token.id()).getFirst().get("details").get("reason").asString())
            .isEqualTo("PASSWORD_CHANGED");
    }

    @Test
    void anInstanceAdminsResetRevokesTheTokensAndIsRecordedInEveryCompanyOfTheUser() {
        TestCompany first = companyWithRoles();
        TestCompany second = companyWithRoles();
        String email = first.email("OWNER");
        UUID userId = first.userId("OWNER");
        addMember(loginAs(second.email("OWNER")), second.id(), email, "ADMIN");
        Token token = create(loginAs(email));
        UUID adminId = UUID.fromString(admin().get("/api/me").json().get("id").asString());

        ApiResponse set = admin().put("/api/admin/users/" + userId + "/password",
                Map.of("newPassword", "reset-by-the-admin-1"));
        assertThat(set.status()).as("%s", set).isEqualTo(204);

        assertThat(ping(token, first.id()).status()).isEqualTo(401);
        JsonNode events = admin().get("/api/admin/audit-events?limit=200").json().get("items");
        assertThat(events(events, "token.revoked", token.id()).getFirst().get("details").get("reason").asString())
            .isEqualTo("PASSWORD_RESET");
        for (TestCompany company : List.of(first, second)) {
            ApiClient owner = loginAs(company == first ? email : company.email("OWNER"),
                    company == first ? "reset-by-the-admin-1" : PASSWORD);
            List<JsonNode> resets = auditEvents(owner, company, "member.password_reset_by_instance_admin");
            assertThat(resets).as("the chain of %s", company.id()).hasSize(1);
            assertThat(resets.getFirst().get("actor").get("id").asString()).isEqualTo(adminId.toString());
            assertThat(resets.getFirst().get("targetId").asString()).isEqualTo(userId.toString());
        }
    }

    private String uniqueUser() {
        String email = uniqueEmail("other");
        createUser(admin(), email, false);
        return email;
    }

    /** Moves the token's expiry to now plus {@code interval} (PostgreSQL's interval syntax, negative for the past). */
    private static void setExpiry(UUID tokenId, String interval) throws Exception {
        try (Connection app = TestDatabase.connectAsApp(); PreparedStatement update = app.prepareStatement(
                "UPDATE api_token SET created_at = now() - interval '100 days', expires_at = now() + ?::interval"
                        + " WHERE id = ?")) {
            update.setString(1, interval);
            update.setObject(2, tokenId);
            assertThat(update.executeUpdate()).isEqualTo(1);
        }
    }

    private static List<JsonNode> events(JsonNode events, String action, UUID target) {
        return StreamSupport.stream(events.spliterator(), false)
            .filter(event -> event.get("action").asString().equals(action))
            .filter(event -> event.path("targetId").asString("").equals(target.toString()))
            .toList();
    }
}
