package com.flowtivo.tinfirm.identity;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

@ExtendWith(EnterpriseLicence.class)
class AdminUsersIntegrationTest extends IntegrationTest {

    @Test
    void adminEndpointsAreForInstanceAdminsOnly() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));

        for (String path : new String[] { "/api/admin/users", "/api/admin/audit-events",
                "/api/admin/audit-events/verification" }) {
            assertThat(owner.get(path).status()).as(path).isEqualTo(403);
            assertThat(client().get(path).status()).as(path).isEqualTo(401);
        }
        assertThat(owner.post("/api/admin/vault/rewrap", null).status()).isEqualTo(403);
        assertThat(owner.post("/api/admin/users", Map.of("email", uniqueEmail("x"), "displayName", "X", "password",
                PASSWORD)).status()).isEqualTo(403);
    }

    @Test
    void createsLocalUsersWithUniqueEmails() {
        ApiClient admin = admin();
        String email = uniqueEmail("Created");

        ApiResponse created = admin.post("/api/admin/users",
                Map.of("email", email, "displayName", "Created User", "password", PASSWORD));

        assertThat(created.status()).isEqualTo(201);
        JsonNode user = created.json();
        assertThat(user.get("email").asString()).isEqualTo(email.toLowerCase());
        assertThat(user.get("authSource").asString()).isEqualTo("LOCAL");
        assertThat(user.get("instanceAdmin").asBoolean()).isFalse();
        assertThat(user.get("active").asBoolean()).isTrue();
        assertThat(created.body()).doesNotContain(PASSWORD).doesNotContainIgnoringCase("hash");

        ApiResponse duplicate = admin.post("/api/admin/users",
                Map.of("email", email.toUpperCase(), "displayName", "Again", "password", PASSWORD));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_EMAIL");

        ApiResponse weak = admin.post("/api/admin/users",
                Map.of("email", uniqueEmail("weak"), "displayName", "Weak", "password", "short"));
        assertThat(weak.status()).isEqualTo(400);
        assertThat(weak.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(weak.body()).doesNotContain("short\"");

        assertThat(loginAs(email).get("/api/me").json().get("displayName").asString()).isEqualTo("Created User");
    }

    @Test
    void listsUsersPageByPage() {
        ApiClient admin = admin();
        for (int i = 0; i < 3; i++) {
            createUser(admin, uniqueEmail("listed"), false);
        }
        Set<String> emails = new HashSet<>();
        String cursor = null;
        int pages = 0;
        do {
            JsonNode page = admin.get("/api/admin/users?limit=2" + (cursor != null ? "&cursor=" + cursor : "")).json();
            page.get("items").forEach(user -> emails.add(user.get("email").asString()));
            cursor = page.has("nextCursor") ? page.get("nextCursor").asString() : null;
            pages++;
        }
        while (cursor != null && pages < 10_000);

        assertThat(emails).contains(ADMIN_EMAIL);
        assertThat(emails.size()).isGreaterThanOrEqualTo(4);
        assertThat(pages).isGreaterThan(1);
    }

    @Test
    void updatesUsersAndAuditsWithoutPersonalData() {
        ApiClient admin = admin();
        UUID userId = createUser(admin, uniqueEmail("renamed"), false);

        ApiResponse updated = admin.patch("/api/admin/users/" + userId,
                Map.of("displayName", "New Name", "instanceAdmin", true));

        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("displayName").asString()).isEqualTo("New Name");
        assertThat(updated.json().get("instanceAdmin").asBoolean()).isTrue();
        JsonNode event = admin.get("/api/admin/audit-events?limit=1").json().get("items").get(0);
        assertThat(event.get("action").asString()).isEqualTo("user.updated");
        assertThat(event.get("targetId").asString()).isEqualTo(userId.toString());
        assertThat(event.get("details").toString()).doesNotContain("New Name").contains("displayName");
        assertThat(event.get("actor").get("display").asString()).isNotBlank();
        assertThat(admin.patch("/api/admin/users/" + UUID.randomUUID(), Map.of("active", false)).status())
            .isEqualTo(404);
    }

    @Test
    void setsPasswordsOfLocalUsersOnly() {
        ApiClient admin = admin();
        String email = uniqueEmail("reset");
        UUID userId = createUser(admin, email, false);
        ApiClient oldSession = loginAs(email);

        ApiResponse set = admin.put("/api/admin/users/" + userId + "/password", Map.of("newPassword", "brand-new-password"));

        assertThat(set.status()).isEqualTo(204);
        assertThat(client().login(email, PASSWORD).status()).isEqualTo(401);
        assertThat(loginAs(email, "brand-new-password").get("/api/me").status()).isEqualTo(200);
        // The user's existing sessions were logged out.
        assertThat(oldSession.get("/api/me").status()).isEqualTo(401);
        assertThat(admin.put("/api/admin/users/" + UUID.randomUUID() + "/password",
                Map.of("newPassword", "brand-new-password")).status()).isEqualTo(404);
    }

    @Test
    void usersChangeTheirOwnPassword() {
        String email = uniqueEmail("self");
        createUser(admin(), email, false);
        ApiClient user = loginAs(email);
        ApiClient otherDevice = loginAs(email);

        ApiResponse wrong = user.put("/api/me/password",
                Map.of("currentPassword", "not-my-password", "newPassword", "another-good-password"));
        assertThat(wrong.status()).isEqualTo(400);
        assertThat(wrong.problemCode()).isEqualTo("WRONG_PASSWORD");

        ApiResponse changed = user.put("/api/me/password",
                Map.of("currentPassword", PASSWORD, "newPassword", "another-good-password"));
        assertThat(changed.status()).isEqualTo(204);
        assertThat(user.get("/api/me").status()).isEqualTo(200);
        assertThat(otherDevice.get("/api/me").status()).isEqualTo(401);
        assertThat(loginAs(email, "another-good-password").get("/api/me").status()).isEqualTo(200);
    }

    @Test
    void instanceChainIsListedAndVerified() {
        ApiClient admin = admin();
        createUser(admin, uniqueEmail("chained"), false);

        JsonNode events = admin.get("/api/admin/audit-events?limit=5").json().get("items");
        assertThat(events.get(0).get("action").asString()).isEqualTo("user.created");
        assertThat(events.get(0).has("companyId")).isFalse();
        assertThat(events.get(0).get("seq").asLong()).isGreaterThan(events.get(1).get("seq").asLong());

        ApiResponse verification = admin.get("/api/admin/audit-events/verification");
        assertThat(verification.status()).isEqualTo(200);
        assertThat(verification.json().get("valid").asBoolean()).isTrue();
        assertThat(verification.json().get("checkedEvents").asLong()).isGreaterThan(1);
        assertThat(verification.json().has("firstInvalidSeq")).isFalse();
    }
}
