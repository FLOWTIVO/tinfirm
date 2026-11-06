package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.model.internal.KeyReconciliation;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * A principal's LiteLLM key through its life, against the fake LiteLLM's admin API (the package 2 contract, §6, §11;
 * M1, M6): created when the company's gateway passes its test and when a principal is created, changed with a budget
 * override or the company's defaults, blocked on suspend and unblocked on resume, deleted when the principal leaves or
 * is deleted, and repaired by the reconciliation after a failed call. The key is never shown.
 */
@ExtendWith(EnterpriseLicence.class)
class ModelKeyLifecycleTest extends IntegrationTest {

    @Autowired
    KeyReconciliation reconciliation;

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Test
    void theTestCreatesTheTeamAndEveryPrincipalsKey() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String botName = unique("bot");
        UUID bot = createServiceIdentity(owner, company, botName);
        ModelGateways.configureBundled(owner, company);

        // The team carries the company's name, which LiteLLM's admin shows beside every key of the team.
        String companyName = owner.get(company.path("")).json().get("name").asString();
        assertThat(litellm.hasTeam("tinfirm-" + company.id())).isTrue();
        assertThat(litellm.teamAlias("tinfirm-" + company.id())).contains(companyName);
        for (UUID principal : List.of(memberPrincipal(company, company.userId("OWNER")),
                memberPrincipal(company, company.userId("ADMIN")), memberPrincipal(company, company.userId("MEMBER")),
                bot)) {
            FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, principal);
            assertThat(key.teamId).isEqualTo("tinfirm-" + company.id());
            assertThat(key.maxBudget).isEqualTo(25.0);
            assertThat(key.budgetDuration).isEqualTo("30d");
            assertThat(key.models).containsExactlyElementsOf(ModelGateways.DEFAULT_MODELS);
            assertThat(key.metadata).containsEntry("tinfirm_company", company.id().toString())
                .containsEntry("tinfirm_company_name", companyName)
                .containsEntry("tinfirm_principal", principal.toString());
            assertThat(key.metadata.get("tinfirm_principal_name")).isNotBlank();
            assertThat(key.blocked).isFalse();
            ModelGateways.awaitStatus(owner, company, principal, "OK");
        }
        assertThat(litellm.keyOfAlias(ModelGateways.alias(company, bot)).orElseThrow().metadata)
            .containsEntry("tinfirm_kind", "SERVICE")
            .containsEntry("tinfirm_principal_name", botName);

        // A member reads their own model access; the key is never in it.
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse mine = member.get(company.path("/me/model-access"));
        assertThat(mine.status()).isEqualTo(200);
        JsonNode access = mine.json();
        assertThat(access.get("gatewayConfigured").asBoolean()).isTrue();
        assertThat(access.get("keyStatus").asString()).isEqualTo("OK");
        assertThat(access.get("budgetUsd").asDouble()).isEqualTo(25.0);
        assertThat(access.get("budgetOverridden").asBoolean()).isFalse();
        assertThat(access.get("budgetPeriod").asString()).isEqualTo("30d");
        assertThat(access.get("spentUsd").asDouble()).isZero();
        // When LiteLLM said that spend: the page says how old the figure is.
        assertThat(java.time.Instant.parse(access.get("syncedAt").asString())).isBefore(java.time.Instant.now()
            .plusSeconds(1));
        assertThat(access.has("budgetResetsAt")).isTrue();
        assertThat(access.get("models")).extracting(JsonNode::asString)
            .containsExactlyElementsOf(ModelGateways.DEFAULT_MODELS);
        String memberKey = litellm.keyOfAlias(ModelGateways.alias(company,
                memberPrincipal(company, company.userId("MEMBER")))).orElseThrow().key;
        assertThat(mine.body()).doesNotContain(memberKey);
        // Nor on the Secrets page: the key is an internal secret.
        ApiResponse secrets = owner.get(company.path("/secrets"));
        assertThat(secrets.body()).doesNotContain("MODEL_KEY");
        // Every principal's key, and the company's own (ADR-0065).
        ModelGateways.awaitCompanyKey(owner, company, "OK");
        assertThat(auditEvents(owner, company, "model_key.created")).hasSize(5);
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void aNewPrincipalGetsItsKeyAfterTheCommit() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);

        String email = uniqueEmail("joiner");
        UUID user = createUser(admin(), email, false);
        addMember(owner, company.id(), email, "MEMBER");
        UUID principal = memberPrincipal(company, user);
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, principal);
        assertThat(key.metadata).containsEntry("tinfirm_kind", "MEMBER");
        ModelGateways.awaitStatus(owner, company, principal, "OK");

        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, bot, "OK");
    }

    @Test
    void anOverrideAndTheDefaultsReachTheKeys() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ModelGateways.configureBundled(owner, company);
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID adminPrincipal = memberPrincipal(company, company.userId("ADMIN"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, member);
        FakeLiteLlm.Key adminKey = ModelGateways.awaitKey(litellm, company, adminPrincipal);
        String path = company.path("/principals/" + member + "/model-access");

        ApiResponse patched = admin.patch(path, Map.of("budgetUsd", 100, "models", List.of("gpt-5.4")));
        assertThat(patched.status()).as("%s", patched).isEqualTo(200);
        assertThat(patched.json().get("budgetUsd").asDouble()).isEqualTo(100.0);
        assertThat(patched.json().get("budgetOverridden").asBoolean()).isTrue();
        assertThat(patched.json().get("modelsOverridden").asBoolean()).isTrue();
        await().atMost(ModelGateways.WAIT)
            .until(() -> key.maxBudget == 100.0 && key.models.equals(List.of("gpt-5.4")));
        JsonNode audit = auditEvents(owner, company, "model_key.budget_changed").getFirst().get("details");
        assertThat(audit.get("principalId").asString()).isEqualTo(member.toString());
        assertThat(audit.get("budgetUsd").asString()).isEqualTo("100.0000");
        assertThat(audit.get("changed")).extracting(JsonNode::asString).containsExactly("budgetUsd", "models");

        // The defaults change: the key with an override keeps it, the others follow; the period too.
        ApiResponse defaults = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED",
                "defaultBudgetUsd", 40, "budgetPeriod", "7d"));
        assertThat(defaults.status()).as("%s", defaults).isEqualTo(200);
        assertThat(defaults.json().get("relayEnabled").asBoolean()).isTrue();
        await().atMost(ModelGateways.WAIT)
            .until(() -> adminKey.maxBudget == 40.0 && "7d".equals(adminKey.budgetDuration));
        await().atMost(ModelGateways.WAIT).until(() -> "7d".equals(key.budgetDuration));
        assertThat(key.maxBudget).isEqualTo(100.0);
        assertThat(adminKey.models).containsExactlyElementsOf(ModelGateways.DEFAULT_MODELS);

        // Cleared, the member's key takes the defaults again; a value and its clear together are refused.
        ApiResponse both = admin.patch(path, Map.of("budgetUsd", 5, "clearBudget", true));
        assertThat(both.status()).isEqualTo(400);
        // The fields refused, for the form to mark, in the shape a schema's validation answers.
        assertThat(both.json().get("errors")).extracting(error -> error.get("field").asString())
            .containsExactly("budgetUsd", "clearBudget");
        ApiResponse cleared = admin.patch(path, Map.of("clearBudget", true, "clearModels", true));
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json().get("budgetOverridden").asBoolean()).isFalse();
        await().atMost(ModelGateways.WAIT).until(() -> key.maxBudget == 40.0
                && key.models.equals(ModelGateways.DEFAULT_MODELS));
        // A request that changes nothing is not audited.
        int changes = auditEvents(owner, company, "model_key.budget_changed").size();
        assertThat(admin.patch(path, Map.of("clearBudget", true)).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "model_key.budget_changed")).hasSize(changes);
    }

    @Test
    void theAnswerToABudgetChangeShowsTheStatusItGivesTheKey() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, bot, "OK");
        String path = company.path("/principals/" + bot + "/model-access");

        // The default budget of 25 USD is spent.
        key.spend = 30.0;
        reconciliation.reconcile(company.id());
        ModelGateways.awaitStatus(owner, company, bot, "BUDGET_SPENT");

        // Raised above the spend, the key is usable again in the answer itself, not only after the next sync.
        ApiResponse raised = owner.patch(path, Map.of("budgetUsd", 50));
        assertThat(raised.status()).as("%s", raised).isEqualTo(200);
        assertThat(key.maxBudget).isEqualTo(50.0);
        assertThat(raised.json().get("keyStatus").asString()).isEqualTo("OK");
        assertThat(raised.json().get("budgetUsd").asDouble()).isEqualTo(50.0);
        assertThat(raised.json().get("spentUsd").asDouble()).isEqualTo(30.0);

        // Lowered below the spend, it is spent again.
        ApiResponse lowered = owner.patch(path, Map.of("budgetUsd", 10));
        assertThat(lowered.status()).as("%s", lowered).isEqualTo(200);
        assertThat(lowered.json().get("keyStatus").asString()).isEqualTo("BUDGET_SPENT");
    }

    @Test
    void theKeysADefaultsChangeFreesOrSpendsShowItWhenItIsAnswered() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID spent = createServiceIdentity(owner, company, unique("spent"));
        UUID frugal = createServiceIdentity(owner, company, unique("frugal"));
        FakeLiteLlm.Key spentKey = ModelGateways.awaitKey(litellm, company, spent);
        FakeLiteLlm.Key frugalKey = ModelGateways.awaitKey(litellm, company, frugal);
        ModelGateways.awaitStatus(owner, company, spent, "OK");
        ModelGateways.awaitStatus(owner, company, frugal, "OK");

        // Both follow the default budget of 25 USD: one spent it, the other a fifth of it.
        spentKey.spend = 30.0;
        frugalKey.spend = 5.0;
        reconciliation.reconcile(company.id());
        ModelGateways.awaitStatus(owner, company, spent, "BUDGET_SPENT");
        ModelGateways.awaitStatus(owner, company, frugal, "OK");

        // Raised above the spend, the spent key is usable again when the page reads it after the answer, not only
        // once the reconciliation reaches it.
        ApiResponse raised = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED",
                "defaultBudgetUsd", 50));
        assertThat(raised.status()).as("%s", raised).isEqualTo(200);
        assertThat(spentKey.maxBudget).isEqualTo(50.0);
        assertThat(keyStatus(owner, company, spent)).isEqualTo("OK");

        // Lowered below a spend, that key is spent at once.
        ApiResponse lowered = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED",
                "defaultBudgetUsd", 4));
        assertThat(lowered.status()).as("%s", lowered).isEqualTo(200);
        assertThat(frugalKey.maxBudget).isEqualTo(4.0);
        assertThat(keyStatus(owner, company, frugal)).isEqualTo("BUDGET_SPENT");
    }

    @Test
    void suspendingBlocksTheKeyAndResumingUnblocksIt() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, bot, "OK");

        assertThat(owner.post(company.path("/service-identities/" + bot + "/suspend"), null).status())
            .isEqualTo(200);
        await().atMost(ModelGateways.WAIT).until(() -> key.blocked);
        ModelGateways.awaitStatus(owner, company, bot, "BLOCKED");
        assertThat(auditEvents(owner, company, "model_key.blocked")).hasSize(1);

        assertThat(owner.post(company.path("/service-identities/" + bot + "/resume"), null).status())
            .isEqualTo(200);
        await().atMost(ModelGateways.WAIT).until(() -> !key.blocked);
        ModelGateways.awaitStatus(owner, company, bot, "OK");
        assertThat(auditEvents(owner, company, "model_key.unblocked")).hasSize(1);
    }

    @Test
    void aLeavingMemberOrADeletedServiceIdentityTakesItsKeyAlong() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        ModelGateways.awaitKey(litellm, company, member);
        ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, member, "OK");
        ModelGateways.awaitStatus(owner, company, bot, "OK");

        assertThat(owner.delete(company.path("/members/" + company.userId("MEMBER"))).status()).isEqualTo(204);
        assertThat(owner.delete(company.path("/service-identities/" + bot)).status()).isEqualTo(204);
        await().atMost(ModelGateways.WAIT).until(() -> litellm.keyOfAlias(ModelGateways.alias(company, member))
            .isEmpty() && litellm.keyOfAlias(ModelGateways.alias(company, bot)).isEmpty());
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM principal_model_key WHERE principal_id IN (?, ?)", member, bot))
                .isZero();
            assertThat(sql.count("SELECT count(*) FROM secret WHERE name LIKE 'MODEL_KEY_%' AND name IN (?, ?)",
                    secretName(member), secretName(bot)))
                .isZero();
        }
        Map<String, String> causes = new HashMap<>();
        auditEvents(owner, company, "model_key.deleted").forEach(event -> causes
            .put(event.get("details").get("principalId").asString(), event.get("details").get("cause").asString()));
        assertThat(causes).containsEntry(member.toString(), "principal.left")
            .containsEntry(bot.toString(), "principal.deleted");
        assertThat(owner.get(company.path("/principals/" + member + "/model-access")).status()).isEqualTo(404);
    }

    @Test
    void theReconciliationRepairsWhatFailed() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(owner, company, bot, "OK");

        // A block that LiteLLM refuses leaves the key ERROR, with LiteLLM's reason, until the reconciliation.
        litellm.failNext("/key/block", 500, 1);
        assertThat(owner.post(company.path("/service-identities/" + bot + "/suspend"), null).status())
            .isEqualTo(200);
        ModelGateways.awaitStatus(owner, company, bot, "ERROR");
        assertThat(owner.get(company.path("/principals/" + bot + "/model-access")).json().get("lastError")
            .asString()).contains("500");
        assertThat(key.blocked).isFalse();
        reconciliation.reconcile(company.id());
        assertThat(key.blocked).isTrue();
        ModelGateways.awaitStatus(owner, company, bot, "BLOCKED");

        // A principal created while LiteLLM is down gets its key from the reconciliation.
        litellm.down(true);
        UUID late;
        try {
            late = createServiceIdentity(owner, company, unique("late"));
            ModelGateways.awaitStatus(owner, company, late, "ERROR");
        }
        finally {
            litellm.down(false);
        }
        reconciliation.reconcile(company.id());
        ModelGateways.awaitKey(litellm, company, late);
        ModelGateways.awaitStatus(owner, company, late, "OK");

        // A key LiteLLM lost is made again; a key of Tinfirm's alias that no principal holds is deleted; a key that
        // is not Tinfirm's is left alone.
        FakeLiteLlm.Key lost = litellm.keyOfAlias(ModelGateways.alias(company, late)).orElseThrow();
        litellm.forget(lost.token);
        FakeLiteLlm.Key orphan = litellm.addKey(ModelGateways.alias(company, UUID.randomUUID()),
                "tinfirm-" + company.id());
        FakeLiteLlm.Key foreign = litellm.addKey("someone-elses-key-" + UUID.randomUUID(), "tinfirm-" + company.id());
        reconciliation.reconcile(company.id());
        FakeLiteLlm.Key again = litellm.keyOfAlias(ModelGateways.alias(company, late)).orElseThrow();
        assertThat(again.token).isNotEqualTo(lost.token);
        assertThat(litellm.key(orphan.token)).isEmpty();
        assertThat(litellm.key(foreign.token)).isPresent();
        ModelGateways.awaitStatus(owner, company, late, "OK");
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void theKeysSecretIsOutOfTheSecretsApisReach() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        ModelGateways.awaitStatus(owner, company, member, "OK");
        UUID secretId;
        try (TenantSql sql = TenantSql.in(company.id())) {
            secretId = (UUID) sql.column("SELECT secret_id FROM principal_model_key WHERE principal_id = ?", member)
                .getFirst();
            assertThat(sql.column("SELECT internal FROM secret WHERE id = ?", secretId)).containsExactly(true);
        }
        assertThat(owner.put(company.path("/secrets/" + secretId), Map.of("value", "sk-replaced")).status())
            .isEqualTo(404);
        assertThat(owner.delete(company.path("/secrets/" + secretId)).status()).isEqualTo(404);
        assertThat(owner.get(company.path("/secrets")).body()).doesNotContain(secretId.toString());
    }

    private static String keyStatus(ApiClient admin, TestCompany company, UUID principalId) {
        return admin.get(company.path("/principals/" + principalId + "/model-access")).json().get("keyStatus")
            .asString();
    }

    @Test
    void aRenamedCompanyGivesItsTeamTheNewName() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        String teamId = "tinfirm-" + company.id();

        // The reconciliation's syncs give the team the new name.
        String renamed = unique("Renamed");
        ApiResponse rename = owner.patch(company.path(""), Map.of("name", renamed));
        assertThat(rename.status()).as("rename: %s", rename).isEqualTo(200);
        reconciliation.reconcile(company.id());
        await().atMost(ModelGateways.WAIT).untilAsserted(() -> assertThat(litellm.teamAlias(teamId)).contains(renamed));

        // So does the settings' test, at once.
        String again = unique("Again");
        assertThat(owner.patch(company.path(""), Map.of("name", again)).status()).isEqualTo(200);
        ApiResponse test = owner.post(company.path("/model-settings/test"), null);
        assertThat(test.status()).as("model settings test: %s", test).isEqualTo(200);
        assertThat(litellm.teamAlias(teamId)).contains(again);

        // A key made from then on carries the new name; the keys made before keep the one they were made with.
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        assertThat(ModelGateways.awaitKey(litellm, company, bot).metadata).containsEntry("tinfirm_company_name", again);
        FakeLiteLlm.Key ownerKey = litellm.keyOfAlias(ModelGateways.alias(company,
                memberPrincipal(company, company.userId("OWNER")))).orElseThrow();
        assertThat(ownerKey.metadata.get("tinfirm_company_name")).isNotEqualTo(again);
    }

    private static String secretName(UUID principalId) {
        return "MODEL_KEY_" + principalId.toString().replace("-", "");
    }
}

