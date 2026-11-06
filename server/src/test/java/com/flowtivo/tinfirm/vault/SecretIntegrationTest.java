package com.flowtivo.tinfirm.vault;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

@ExtendWith(OutputCaptureExtension.class)
class SecretIntegrationTest extends IntegrationTest {

    @Autowired
    VaultService vaultService;

    @Autowired
    SecretWriter secretWriter;

    @Autowired
    SystemTransactions transactions;

    @Test
    void secretValuesGoInButNeverComeOut(CapturedOutput output) throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String value = "s3cr3t-" + UUID.randomUUID();

        ApiResponse created = owner.post(company.path("/secrets"),
                Map.of("name", "GITHUB_TOKEN", "value", value, "description", "Bot token"));

        assertThat(created.status()).isEqualTo(201);
        assertThat(created.body()).doesNotContain(value);
        JsonNode meta = created.json();
        assertThat(meta.get("name").asString()).isEqualTo("GITHUB_TOKEN");
        assertThat(meta.get("version").asInt()).isEqualTo(1);
        assertThat(meta.get("createdBy").asString()).isEqualTo("User " + company.email("OWNER"));
        assertThat(meta.has("value")).isFalse();
        assertThat(meta.has("lastAccessedAt")).isFalse();
        UUID secretId = UUID.fromString(meta.get("id").asString());

        ApiResponse listed = loginAs(company.email("ADMIN")).get(company.path("/secrets"));
        assertThat(listed.status()).isEqualTo(200);
        assertThat(listed.body()).doesNotContain(value).contains("GITHUB_TOKEN");

        String newValue = "rotated-" + UUID.randomUUID();
        ApiResponse updated = owner.put(company.path("/secrets/" + secretId), Map.of("value", newValue));
        assertThat(updated.status()).isEqualTo(200);
        assertThat(updated.json().get("version").asInt()).isEqualTo(2);
        assertThat(updated.json().get("description").asString()).isEqualTo("Bot token");
        assertThat(updated.body()).doesNotContain(newValue);

        // Stored encrypted under the active master key, never in plaintext.
        try (Connection owner2 = TestDatabase.connectAsOwner();
                PreparedStatement query = owner2.prepareStatement("""
                        SELECT version, key_id, ciphertext, wrapped_dek FROM secret_version
                         WHERE secret_id = ? ORDER BY version""")) {
            owner2.createStatement().execute("SET app.system = 'true'");
            query.setObject(1, secretId);
            try (ResultSet rows = query.executeQuery()) {
                int count = 0;
                while (rows.next()) {
                    count++;
                    assertThat(rows.getString("key_id")).isEqualTo("test-k1");
                    assertThat(new String(rows.getBytes("ciphertext"), StandardCharsets.ISO_8859_1))
                        .doesNotContain(value)
                        .doesNotContain(newValue);
                    assertThat(rows.getBytes("wrapped_dek")).hasSize(48);
                }
                assertThat(count).isEqualTo(2);
            }
        }

        // Audit details carry the name and version only.
        JsonNode events = owner.get(company.path("/audit-events?limit=2")).json().get("items");
        assertThat(events.get(0).get("action").asString()).isEqualTo("secret.updated");
        assertThat(events.get(1).get("action").asString()).isEqualTo("secret.created");
        assertThat(events.toString()).doesNotContain(value).doesNotContain(newValue);

        // No value in the logs either.
        assertThat(output.getAll()).doesNotContain(value).doesNotContain(newValue);
    }

    @Test
    void internalRevealDecryptsAuditsAndRecordsAccess() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String value = "reveal-" + UUID.randomUUID();
        UUID secretId = createSecret(owner, company.id(), "DEPLOY_KEY", value);

        try (RevealedSecret revealed = SystemContext.call(() -> vaultService.reveal(company.id(), secretId, "test:reveal"))) {
            assertThat(revealed.asString()).isEqualTo(value);
            assertThat(revealed.toString()).doesNotContain(value);
        }

        JsonNode listed = owner.get(company.path("/secrets")).json().get("items").get(0);
        assertThat(listed.has("lastAccessedAt")).isTrue();
        JsonNode accessed = owner.get(company.path("/audit-events?limit=1")).json().get("items").get(0);
        assertThat(accessed.get("action").asString()).isEqualTo("secret.accessed");
        assertThat(accessed.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(accessed.get("details").get("purpose").asString()).isEqualTo("test:reveal");
        assertThat(accessed.toString()).doesNotContain(value);
    }

    @Test
    void rolesAndNamesAreEnforced() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));

        ApiClient member = loginAs(company.email("MEMBER"));
        assertThat(member.get(company.path("/secrets")).status()).isEqualTo(403);
        assertThat(member.post(company.path("/secrets"), Map.of("name", "NOPE", "value", "x")).status())
            .isEqualTo(403);

        UUID secretId = createSecret(admin, company.id(), "API_KEY", "value-1");
        ApiResponse duplicate = admin.post(company.path("/secrets"), Map.of("name", "API_KEY", "value", "value-2"));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_SECRET_NAME");

        ApiResponse badName = admin.post(company.path("/secrets"), Map.of("name", "1-bad name", "value", "v"));
        assertThat(badName.status()).isEqualTo(400);
        assertThat(badName.problemCode()).isEqualTo("VALIDATION_FAILED");
        ApiResponse emptyValue = admin.post(company.path("/secrets"), Map.of("name", "EMPTY", "value", ""));
        assertThat(emptyValue.status()).isEqualTo(400);

        assertThat(member.put(company.path("/secrets/" + secretId), Map.of("value", "v")).status()).isEqualTo(403);
        assertThat(member.delete(company.path("/secrets/" + secretId)).status()).isEqualTo(403);
        assertThat(admin.delete(company.path("/secrets/" + secretId)).status()).isEqualTo(204);
        assertThat(admin.put(company.path("/secrets/" + secretId), Map.of("value", "v")).status()).isEqualTo(404);
        assertThat(admin.delete(company.path("/secrets/" + secretId)).status()).isEqualTo(404);
        assertThat(admin.get(company.path("/audit-events?limit=1")).json().get("items").get(0).get("action")
            .asString()).isEqualTo("secret.deleted");
    }

    /**
     * The secrets another part of Tinfirm keeps (the token or grant of a service identity's account, a system's OAuth
     * client secret) are listed as managed and say what maintains them; a pasted value would bypass the token's check
     * or break the grant, so the API refuses a replace, while the module that keeps the secret still replaces it (an
     * OAuth refresh) and an admin may delete it (which disconnects the account). The values are test fixtures.
     */
    @Test
    void aManagedSecretIsListedAsSuchItsValueIsNotReplacedByHandAndOneNothingKeepsMayBeDeleted() {
        TestCompany company = companyWithRoles();
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        Map<String, UUID> ids = new HashMap<>();
        for (String name : List.of("GITHUB_TOKEN", "MY_SERVICE_ACCOUNT")) {
            ids.put(name, createSecret(admin, company.id(), name, "test-value-of-" + name));
        }
        // A managed name is refused by hand (the security review of 2026-10-27, I1); the account and system
        // modules give it.
        for (String name : List.of("SERVICE_ACCOUNT", "SERVICE_ACCOUNT_2", "SYSTEM_OAUTH_CLIENT_SECRET")) {
            ApiResponse reserved = admin.post(company.path("/secrets"),
                    Map.of("name", name, "value", "test-value", "description", "by hand"));
            assertThat(reserved.status()).as("%s: %s", name, reserved).isEqualTo(400);
            assertThat(reserved.problemCode()).isEqualTo("VALIDATION_FAILED");
            ids.put(name, createManagedNamedSecret(admin, company.id(), name, "test-value-of-" + name));
        }

        // Everyone who sees the page sees what is managed and by what; an ordinary secret says neither.
        Map<String, JsonNode> listed = new HashMap<>();
        admin.get(company.path("/secrets?limit=200")).json().get("items")
            .forEach(secret -> listed.put(secret.get("name").asString(), secret));
        assertThat(listed).hasSize(5);
        for (String ordinary : List.of("GITHUB_TOKEN", "MY_SERVICE_ACCOUNT")) {
            assertThat(listed.get(ordinary).get("managed").asBoolean()).as(ordinary).isFalse();
            JsonNode by = listed.get(ordinary).path("managedBy");
            assertThat(by.isMissingNode() || by.isNull()).as(ordinary + " managedBy %s", by).isTrue();
        }
        Map<String, String> managedBy = Map.of(
                "SERVICE_ACCOUNT", "a system's credential",
                "SERVICE_ACCOUNT_2", "a system's credential",
                "SYSTEM_OAUTH_CLIENT_SECRET", "a system's OAuth client");
        managedBy.forEach((name, by) -> {
            assertThat(listed.get(name).get("managed").asBoolean()).as(name).isTrue();
            assertThat(listed.get(name).get("managedBy").asString()).as(name).isEqualTo(by);
        });

        // A replace by hand is refused whatever the managed secret, and leaves it as it was.
        UUID account = ids.get("SERVICE_ACCOUNT");
        ApiResponse refused = admin.put(company.path("/secrets/" + account), Map.of("value", "pasted-over-the-grant"));
        assertThat(refused.status()).as("%s", refused).isEqualTo(400);
        assertThat(refused.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(refused.json().get("detail").asString()).isEqualTo("The value of `SERVICE_ACCOUNT` is maintained "
                + "by Tinfirm (a system's credential); connect the account or set the app again "
                + "on its page");
        assertThat(refused.body()).doesNotContain("pasted-over-the-grant");
        managedBy.forEach((name, by) -> {
            ApiResponse response = admin.put(company.path("/secrets/" + ids.get(name)), Map.of("value", "pasted"));
            assertThat(response.status()).as(name).isEqualTo(400);
            assertThat(response.json().get("detail").asString()).as(name).contains("`" + name + "`").contains(by);
        });
        assertThat(version(admin, company, "SERVICE_ACCOUNT")).isEqualTo(1);
        assertThat(auditEvents(admin, company, "secret.updated")).isEmpty();
        // An ordinary secret is replaced as ever, also one whose name only contains a managed name.
        assertThat(admin.put(company.path("/secrets/" + ids.get("MY_SERVICE_ACCOUNT")),
                Map.of("value", "test-rotated")).status()).isEqualTo(200);

        // The module that keeps the secret replaces it (an OAuth refresh): the vault's trusted replace is unaffected.
        assertThat(transactions.inCompany(company.id(), () -> secretWriter.replace(company.id(), account,
                "test-refreshed-grant"))).isTrue();
        assertThat(version(admin, company, "SERVICE_ACCOUNT")).isEqualTo(2);

        // No account keeps this one (they were made by hand), so it is deleted as any other; one that an account or
        // an app keeps is 409 SECRET_MANAGED (AccountIntegrationTest, SystemIntegrationTest).
        assertThat(member.delete(company.path("/secrets/" + account)).status()).isEqualTo(403);
        assertThat(admin.delete(company.path("/secrets/" + account)).status()).isEqualTo(204);
        assertThat(admin.delete(company.path("/secrets/" + account)).status()).isEqualTo(404);
        assertThat(admin.get(company.path("/secrets?limit=200")).json().get("items"))
            .extracting(secret -> secret.get("name").asString())
            .doesNotContain("SERVICE_ACCOUNT")
            .contains("SERVICE_ACCOUNT_2");
        JsonNode deleted = auditEvents(admin, company, "secret.deleted").getFirst();
        assertThat(deleted.get("targetId").asString()).isEqualTo(account.toString());
        assertThat(deleted.get("details").get("name").asString()).isEqualTo("SERVICE_ACCOUNT");
    }

    private static int version(ApiClient actor, TestCompany company, String name) {
        for (JsonNode secret : actor.get(company.path("/secrets?limit=200")).json().get("items")) {
            if (secret.get("name").asString().equals(name)) {
                return secret.get("version").asInt();
            }
        }
        throw new AssertionError("No secret named " + name);
    }

    @Test
    void secretsArePagedByName() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        for (String name : new String[] { "C_KEY", "A_KEY", "B_KEY" }) {
            createSecret(owner, company.id(), name, "v");
        }

        JsonNode first = owner.get(company.path("/secrets?limit=2")).json();
        JsonNode second = owner.get(company.path("/secrets?limit=2&cursor=" + first.get("nextCursor").asString()))
            .json();

        assertThat(first.get("items").get(0).get("name").asString()).isEqualTo("A_KEY");
        assertThat(first.get("items").get(1).get("name").asString()).isEqualTo("B_KEY");
        assertThat(second.get("items").get(0).get("name").asString()).isEqualTo("C_KEY");
        assertThat(second.has("nextCursor")).isFalse();
    }
}
