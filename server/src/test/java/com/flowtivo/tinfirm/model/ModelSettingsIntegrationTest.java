package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;

import tools.jackson.databind.JsonNode;

/**
 * The company's model settings over HTTP (the package 2 contract, §3.1, §8, §9, §10): who reads and changes them, the
 * bundled gateway and a company's own, the admin test that enables the relay, the models every member lists, and the
 * audit, which never holds a key.
 */
class ModelSettingsIntegrationTest extends IntegrationTest {

    private static final String WRONG_KEY_END = "QzXw";

    private static final String WRONG_KEY = "sk-wrong-admin-key-" + WRONG_KEY_END;

    @Test
    void ownersAndAdminsConfigureTheGatewayAndEveryMemberListsItsModels() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));

        ApiResponse fresh = admin.get(company.path("/model-settings"));
        assertThat(fresh.status()).isEqualTo(200);
        assertThat(fresh.json().get("configured").asBoolean()).isFalse();
        assertThat(fresh.json().get("bundledAvailable").asBoolean()).isTrue();
        assertThat(fresh.json().get("gateway").asString()).isEqualTo("BUNDLED");
        assertThat(fresh.json().get("relayBaseUrl").asString()).isEqualTo("http://localhost:8080/api/runner/v1/model");
        assertThat(member.get(company.path("/model-settings")).status()).isEqualTo(403);
        assertThat(member.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 1))
            .status()).isEqualTo(403);
        assertThat(member.post(company.path("/model-settings/test"), null).status()).isEqualTo(403);
        // Nothing to test yet, and no models to list.
        ApiResponse untested = admin.post(company.path("/model-settings/test"), null);
        assertThat(untested.status()).isEqualTo(409);
        assertThat(untested.problemCode()).isEqualTo("GATEWAY_NOT_CONFIGURED");
        assertThat(member.get(company.path("/model-settings/models")).problemCode())
            .isEqualTo("GATEWAY_NOT_CONFIGURED");

        ApiResponse put = admin.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd",
                12.5, "defaultModels", List.of(" claude-sonnet-4-5 ", "claude-sonnet-4-5", "claude-haiku-4-5")));
        assertThat(put.status()).as("%s", put).isEqualTo(200);
        JsonNode settings = put.json();
        assertThat(settings.get("configured").asBoolean()).isTrue();
        assertThat(settings.get("relayEnabled").asBoolean()).isFalse();
        assertThat(settings.get("adminKeySet").asBoolean()).isTrue();
        assertThat(settings.has("adminUrl")).isFalse();
        assertThat(settings.get("defaultBudgetUsd").asDouble()).isEqualTo(12.5);
        assertThat(settings.get("budgetPeriod").asString()).isEqualTo("30d");
        assertThat(settings.get("defaultModels")).extracting(JsonNode::asString)
            .containsExactly("claude-sonnet-4-5", "claude-haiku-4-5");
        assertThat(settings.get("updatedBy").asString()).isEqualTo(company.userId("ADMIN").toString());
        // Runs paid by a budget wait for the test.
        assertThat(member.get(company.path("/model-settings/models")).problemCode())
            .isEqualTo("GATEWAY_NOT_CONFIGURED");

        ApiResponse tested = owner.post(company.path("/model-settings/test"), null);
        assertThat(tested.status()).as("%s", tested).isEqualTo(200);
        assertThat(tested.json().get("ok").asBoolean()).isTrue();
        assertThat(tested.json().get("models")).extracting(JsonNode::asString)
            .containsExactly("claude-haiku-4-5", "claude-sonnet-4-5", "gpt-5.4");
        assertThat(owner.get(company.path("/model-settings")).json().get("relayEnabled").asBoolean()).isTrue();
        ApiResponse models = member.get(company.path("/model-settings/models"));
        assertThat(models.status()).isEqualTo(200);
        assertThat(models.json().get("models")).hasSize(3);

        // Invalid values.
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("gateway", "BUNDLED", "defaultBudgetUsd", -1),
                Map.<String, Object>of("gateway", "BUNDLED", "defaultBudgetUsd", 1, "budgetPeriod", "monthly"),
                Map.<String, Object>of("gateway", "BUNDLED", "defaultBudgetUsd", 1, "defaultModels", List.of("a b")),
                Map.<String, Object>of("gateway", "BUNDLED", "defaultBudgetUsd", 1, "adminUrl",
                        "https://litellm.example.com"),
                Map.<String, Object>of("gateway", "EXTERNAL", "defaultBudgetUsd", 1))) {
            ApiResponse refused = admin.put(company.path("/model-settings"), bad);
            assertThat(refused.status()).as("%s -> %s", bad, refused).isEqualTo(400);
        }
        // A request that changes nothing is not audited.
        List<JsonNode> changes = auditEvents(owner, company, "model_settings.changed");
        assertThat(admin.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 12.5))
            .status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "model_settings.changed")).hasSameSizeAs(changes);
        JsonNode first = changes.getLast().get("details");
        assertThat(first.get("changed")).extracting(JsonNode::asString)
            .contains("gateway", "defaultBudgetUsd", "budgetPeriod", "defaultModels");
        JsonNode test = changes.getFirst().get("details");
        assertThat(test.get("tested").asBoolean()).isTrue();
        assertThat(test.get("ok").asBoolean()).isTrue();
    }

    @Test
    void aCompanysOwnGatewayTakesItsKeyAndTheKeysMoveThere() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String externalKey = "sk-external-admin-" + UUID.randomUUID();
        try (FakeLiteLlm own = FakeLiteLlm.start(externalKey)) {
            ModelGateways.configureBundled(owner, company);
            UUID member = memberPrincipal(company, company.userId("MEMBER"));
            FakeLiteLlm.Key bundledKey = ModelGateways.awaitKey(FakeLiteLlm.bundled(), company, member);
            ModelGateways.awaitStatus(owner, company, member, "OK");

            // The URL needs its key; the scheme must be http(s).
            ApiResponse noKey = owner.put(company.path("/model-settings"), Map.of("gateway", "EXTERNAL",
                    "defaultBudgetUsd", 25, "adminUrl", own.url()));
            assertThat(noKey.status()).isEqualTo(400);
            assertThat(noKey.problemCode()).isEqualTo("VALIDATION_FAILED");
            assertThat(noKey.json().get("errors")).singleElement().satisfies(error -> {
                assertThat(error.get("field").asString()).isEqualTo("adminKey");
                assertThat(error.get("message").asString()).startsWith("adminKey is required");
            });
            ApiResponse badUrl = owner.put(company.path("/model-settings"), Map.of("gateway", "EXTERNAL",
                    "defaultBudgetUsd", 25, "adminUrl", "ftp://litellm.example.com", "adminKey", externalKey));
            assertThat(badUrl.status()).isEqualTo(400);
            assertThat(badUrl.problemCode()).isEqualTo("INVALID_BASE_URL");
            assertThat(badUrl.json().get("errors")).extracting(error -> error.get("field").asString())
                .containsExactly("adminUrl");

            // A wrong key: the test fails with LiteLLM's reason and the relay stays off.
            ApiResponse wrong = owner.put(company.path("/model-settings"), Map.of("gateway", "EXTERNAL",
                    "defaultBudgetUsd", 25, "adminUrl", own.url() + "/", "adminKey", WRONG_KEY));
            assertThat(wrong.status()).as("%s", wrong).isEqualTo(200);
            assertThat(wrong.json().get("adminUrl").asString()).isEqualTo(own.url());
            assertThat(wrong.json().get("relayEnabled").asBoolean()).isFalse();
            assertThat(wrong.body()).doesNotContain(WRONG_KEY);
            // The keys at the bundled gateway were deleted there.
            await().atMost(ModelGateways.WAIT).until(() -> FakeLiteLlm.bundled().key(bundledKey.token).isEmpty());
            ApiResponse failed = owner.post(company.path("/model-settings/test"), null);
            assertThat(failed.status()).isEqualTo(502);
            assertThat(failed.problemCode()).isEqualTo("GATEWAY_UNREACHABLE");
            assertThat(failed.json().get("detail").asString()).contains("401");
            // LiteLLM's 401 echoes the key it refused, abbreviated, and its hash: neither is passed on.
            assertThat(failed.body()).contains("Received API Key = sk-***").doesNotContain(WRONG_KEY_END)
                .doesNotContain(FakeLiteLlm.hash(WRONG_KEY));
            JsonNode settings = owner.get(company.path("/model-settings")).json();
            assertThat(settings.get("relayEnabled").asBoolean()).isFalse();
            assertThat(settings.get("lastError").asString()).contains("401").doesNotContain(WRONG_KEY_END)
                .doesNotContain(FakeLiteLlm.hash(WRONG_KEY));

            // The right key, given again for the same URL: the test passes and the keys are made at the new gateway.
            ApiResponse right = owner.put(company.path("/model-settings"), Map.of("gateway", "EXTERNAL",
                    "defaultBudgetUsd", 25, "adminKey", externalKey));
            assertThat(right.status()).as("%s", right).isEqualTo(200);
            assertThat(owner.post(company.path("/model-settings/test"), null).status()).isEqualTo(200);
            FakeLiteLlm.Key ownKey = ModelGateways.awaitKey(own, company, member);
            assertThat(ownKey.teamId).isEqualTo("tinfirm-" + company.id());
            ModelGateways.awaitStatus(owner, company, member, "OK");
            // The admin key is a company secret that the audit and the settings never show.
            ApiResponse secrets = owner.get(company.path("/secrets"));
            assertThat(secrets.body()).contains("LITELLM_ADMIN_KEY").doesNotContain(externalKey);
            // The Secrets API does not delete it while the settings keep it.
            String adminKeyId = null;
            for (JsonNode secret : secrets.json().get("items")) {
                if (secret.get("name").asString().equals("LITELLM_ADMIN_KEY")) {
                    adminKeyId = secret.get("id").asString();
                }
            }
            ApiResponse kept = owner.delete(company.path("/secrets/" + adminKeyId));
            assertThat(kept.status()).as("%s", kept).isEqualTo(409);
            assertThat(kept.problemCode()).isEqualTo("SECRET_MANAGED");
            Map<String, Integer> actions = new HashMap<>();
            auditEvents(owner, company).forEach(event -> {
                assertThat(event.toString()).doesNotContain(externalKey).doesNotContain(WRONG_KEY)
                    .doesNotContain(WRONG_KEY_END).doesNotContain(FakeLiteLlm.hash(WRONG_KEY))
                    .doesNotContain(ownKey.key);
                actions.merge(event.get("action").asString(), 1, Integer::sum);
            });
            assertThat(actions).containsKey("model_key.deleted");

            // Back to the bundled gateway: the stored admin key goes.
            ApiResponse back = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED",
                    "defaultBudgetUsd", 25));
            assertThat(back.status()).isEqualTo(200);
            assertThat(back.json().has("adminUrl")).isFalse();
            assertThat(owner.get(company.path("/secrets")).body()).doesNotContain("LITELLM_ADMIN_KEY");
            await().atMost(ModelGateways.WAIT).until(() -> own.key(ownKey.token).isEmpty());
        }
    }
}
