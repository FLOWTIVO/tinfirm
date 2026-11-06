package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.internal.KeyReconciliation;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;

import tools.jackson.databind.JsonNode;

/**
 * The company's own LiteLLM key against the fake LiteLLM (ADR-0065): no principal's, created once the company's gateway
 * passed its test, with a budget and models of its own or the company's default, which OWNER and ADMIN read and set as
 * a principal's; kept by the reconciliation's sweep, which deletes a stray key of its alias; rotated at once on an
 * admin's request. Its audit names the company's key, never a principal or the key.
 */
class CompanyModelKeyTest extends IntegrationTest {

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private KeyReconciliation reconciliation;

    private String path(TestCompany company) {
        return company.path("/model-access/company-key");
    }

    /** A company whose gateway passed its test, once the reconciliation after the test is done with its keys. */
    private ApiClient gateway(TestCompany company) {
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        await().atMost(ModelGateways.WAIT)
            .until(() -> !litellm.requests("/key/list?team_id=tinfirm-" + company.id()).isEmpty());
        ModelGateways.awaitCompanyKey(owner, company, "OK");
        return owner;
    }

    @Test
    void theCompanysKeyIsCreatedWithTheGatewayAndHasABudgetOfItsOwn() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        JsonNode before = owner.get(path(company)).json();
        assertThat(before.get("gatewayConfigured").asBoolean()).isFalse();
        assertThat(before.has("principalId")).isFalse();
        assertThat(before.path("keyStatus").isNull() || before.path("keyStatus").isMissingNode()).isTrue();
        assertThat(member.get(path(company)).status()).isEqualTo(403);
        assertThat(member.patch(path(company), Map.of("budgetUsd", 1)).status()).isEqualTo(403);
        assertThat(member.post(path(company) + "/rotate-key", null).status()).isEqualTo(403);

        gateway(company);

        FakeLiteLlm.Key key = litellm.keyOfAlias(ModelGateways.companyKeyAlias(company, 0)).orElseThrow();
        assertThat(key.teamId).isEqualTo("tinfirm-" + company.id());
        assertThat(key.maxBudget).isEqualTo(25.0);
        assertThat(key.models).containsExactlyInAnyOrderElementsOf(ModelGateways.DEFAULT_MODELS);
        String companyName = owner.get(company.path("")).json().get("name").asString();
        assertThat(litellm.teamAlias("tinfirm-" + company.id())).contains(companyName);
        assertThat(key.metadata).containsEntry("tinfirm_company", company.id().toString())
            .containsEntry("tinfirm_company_name", companyName)
            .containsEntry("tinfirm_kind", "COMPANY")
            .doesNotContainKey("tinfirm_principal")
            .doesNotContainKey("tinfirm_principal_name");

        assertThat(key.blocked).isFalse();

        ApiResponse own = owner.patch(path(company), Map.of("budgetUsd", 3, "models", List.of("claude-haiku-4-5")));
        assertThat(own.status()).as("%s", own).isEqualTo(200);
        assertThat(own.json().get("budgetOverridden").asBoolean()).isTrue();
        assertThat(own.json().get("budgetUsd").asDouble()).isEqualTo(3.0);
        assertThat(own.json().get("keyStatus").asString()).isEqualTo("OK");
        assertThat(key.maxBudget).isEqualTo(3.0);
        assertThat(key.models).containsExactly("claude-haiku-4-5");
        JsonNode changed = auditEvents(owner, company, "model_key.budget_changed").getFirst();
        assertThat(changed.get("targetType").asString()).isEqualTo("company_model_key");
        assertThat(changed.get("targetId").asString()).isEqualTo(company.id().toString());
        assertThat(changed.get("details").get("holder").asString()).isEqualTo("company");
        assertThat(changed.get("details").has("principalId")).isFalse();

        // Its spend reaches its own budget: spent, as a principal's would be.
        key.spend = 3.0;
        reconciliation.reconcile(company.id());
        ModelGateways.awaitCompanyKey(owner, company, "BUDGET_SPENT");
        assertThat(auditEvents(owner, company, "model_key.budget_spent")).anySatisfy(event -> assertThat(
                event.get("targetType").asString()).isEqualTo("company_model_key"));

        // Back to the company's default.
        ApiResponse cleared = owner.patch(path(company), Map.of("clearBudget", true, "clearModels", true));
        assertThat(cleared.json().get("budgetOverridden").asBoolean()).isFalse();
        assertThat(cleared.json().get("budgetUsd").asDouble()).isEqualTo(25.0);
        assertThat(key.models).containsExactlyInAnyOrderElementsOf(ModelGateways.DEFAULT_MODELS);
        assertNoKeyInAudit(owner, company, key);
    }

    @Test
    void theSweepKeepsTheCompanysKeyAndDeletesAStrayKeyOfItsAlias() {
        TestCompany company = companyWithRoles();
        gateway(company);
        FakeLiteLlm.Key own = litellm.keyOfAlias(ModelGateways.companyKeyAlias(company, 0)).orElseThrow();
        FakeLiteLlm.Key stray = litellm.addKey(ModelGateways.companyKeyAlias(company, 7), "tinfirm-" + company.id());

        reconciliation.reconcile(company.id());

        assertThat(litellm.key(own.token)).isPresent();
        assertThat(litellm.key(stray.token)).isEmpty();
    }

    @Test
    void anAdminRotatesTheCompanysKeyAtOnce() {
        TestCompany company = companyWithRoles();
        ApiClient owner = gateway(company);
        FakeLiteLlm.Key first = litellm.keyOfAlias(ModelGateways.companyKeyAlias(company, 0)).orElseThrow();
        first.spend = 1.25;

        ApiResponse rotated = owner.post(path(company) + "/rotate-key", null);

        assertThat(rotated.status()).as("%s", rotated).isEqualTo(200);
        assertThat(first.blocked).isTrue();
        FakeLiteLlm.Key next = litellm.keyOfAlias(ModelGateways.companyKeyAlias(company, 1)).orElseThrow();
        assertThat(next.blocked).isFalse();
        assertThat(next.spend).isEqualTo(1.25);
        assertThat(next.metadata).isEqualTo(first.metadata);
        assertThat(rotated.json().get("keyStatus").asString()).isEqualTo("OK");
        JsonNode requested = auditEvents(owner, company, "model_key.rotation_requested").getFirst();
        assertThat(requested.get("details").get("scope").asString()).isEqualTo("company_key");
        assertThat(requested.get("targetType").asString()).isEqualTo("company_model_key");
        assertThat(auditEvents(owner, company, "model_key.rotated")).anySatisfy(event -> assertThat(
                event.get("details").get("holder").asString()).isEqualTo("company"));
        assertNoKeyInAudit(owner, company, first, next);
    }

    private void assertNoKeyInAudit(ApiClient reader, TestCompany company, FakeLiteLlm.Key... keys) {
        String audit = auditEvents(reader, company).toString();
        for (FakeLiteLlm.Key key : keys) {
            assertThat(audit).doesNotContain(key.key).doesNotContain(key.token);
        }
    }
}
