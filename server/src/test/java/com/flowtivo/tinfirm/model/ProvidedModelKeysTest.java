package com.flowtivo.tinfirm.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.internal.KeyReconciliation;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;

import tools.jackson.databind.JsonNode;

/**
 * Keys a company's gateway team provides (ADR-0094) over HTTP: the settings of a {@code PROVIDED} gateway and their
 * test without a key of Tinfirm's, a payer without a key waiting ({@code NO_MODEL_KEY}), keys an admin enters for a
 * member, a service account and the company, checked with the gateway's model list and never shown again, a member's
 * own key where the company allows it and never over an admin's, a suspended principal's key blocked here with a
 * request to revoke it, and no call to the gateway's admin API at all.
 */
class ProvidedModelKeysTest extends IntegrationTest {

    private static final String GATEWAY_MASTER = "sk-provided-gateway-master-not-tinfirms";

    @Autowired
    private ModelAccess modelAccess;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private KeyReconciliation reconciliation;

    /** A key the gateway team made, with a budget. */
    private static FakeLiteLlm.Key teamKey(FakeLiteLlm gateway, String alias) {
        FakeLiteLlm.Key key = gateway.addKey(alias, "gateway-team");
        key.maxBudget = 10;
        return key;
    }

    private RunModelAccess forRun(TestCompany company, UUID payer) {
        return transactions.inCompany(company.id(), () -> modelAccess.forRun(new RunModelContext(company.id(), payer,
                ModelAccessChoice.BUDGET, RuntimeKind.CLAUDE_CODE, Set.of())));
    }

    @Test
    void anAdminEntersTheKeysTheGatewayTeamMadeAndAMemberTheirOwnWhereAllowed() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        try (FakeLiteLlm gateway = FakeLiteLlm.start(GATEWAY_MASTER)) {
            // The settings: a model URL, no admin key; GENERIC unless the vendor is named.
            ApiResponse withAdminKey = owner.put(company.path("/model-settings"), Map.of("gateway", "PROVIDED",
                    "defaultBudgetUsd", 0, "modelUrl", gateway.url(), "adminKey", GATEWAY_MASTER));
            assertThat(withAdminKey.status()).isEqualTo(400);
            ApiResponse noUrl = owner.put(company.path("/model-settings"), Map.of("gateway", "PROVIDED",
                    "defaultBudgetUsd", 0));
            assertThat(noUrl.status()).isEqualTo(400);
            ApiResponse put = owner.put(company.path("/model-settings"), Map.of("gateway", "PROVIDED",
                    "defaultBudgetUsd", 0, "modelUrl", gateway.url() + "/"));
            assertThat(put.status()).as("%s", put).isEqualTo(200);
            assertThat(put.json().get("modelUrl").asString()).isEqualTo(gateway.url());
            assertThat(put.json().get("vendor").asString()).isEqualTo("GENERIC");
            assertThat(put.json().get("memberKeys").asBoolean()).isFalse();
            assertThat(owner.put(company.path("/model-settings"), Map.of("gateway", "PROVIDED", "defaultBudgetUsd",
                    0, "vendor", "LITELLM")).json().get("vendor").asString()).isEqualTo("LITELLM");
            // The test reaches the model list with a key no gateway issues, which the gateway refuses: it answers.
            ApiResponse tested = owner.post(company.path("/model-settings/test"), null);
            assertThat(tested.status()).as("%s", tested).isEqualTo(200);
            assertThat(owner.get(company.path("/model-settings")).json().get("relayEnabled").asBoolean()).isTrue();

            // A member without a key waits for one; nothing is made at the gateway.
            UUID memberId = memberPrincipal(company, company.userId("MEMBER"));
            reconciliation.reconcile(company.id());
            assertThat(forRun(company, memberId)).isInstanceOfSatisfying(RunModelAccess.Wait.class,
                    wait -> assertThat(wait.reason()).isEqualTo(RunModelAccess.WaitReason.NO_MODEL_KEY));
            JsonNode waiting = owner.get(company.path("/principals/" + memberId + "/model-access")).json();
            assertThat(waiting.get("keyStatus").asString()).isEqualTo("ERROR");
            assertThat(waiting.get("keyProvided").asBoolean()).isFalse();

            // A key the gateway refuses is not kept.
            ApiResponse refused = owner.put(company.path("/principals/" + memberId + "/model-access/key"),
                    Map.of("key", "sk-not-a-key-of-the-gateway"));
            assertThat(refused.status()).as("%s", refused).isEqualTo(400);
            assertThat(refused.problemCode()).isEqualTo("MODEL_KEY_REFUSED");

            // The admin enters the key the gateway team made: checked, kept, shown by its last four characters only.
            FakeLiteLlm.Key memberKey = teamKey(gateway, "alice");
            ApiResponse entered = owner.put(company.path("/principals/" + memberId + "/model-access/key"),
                    Map.of("key", memberKey.key));
            assertThat(entered.status()).as("%s", entered).isEqualTo(200);
            assertThat(entered.json().get("keyProvided").asBoolean()).isTrue();
            assertThat(entered.json().get("keyProvidedBySelf").asBoolean()).isFalse();
            assertThat(entered.json().get("keyHint").asString()).isEqualTo(memberKey.key.substring(
                    memberKey.key.length() - 4));
            assertThat(entered.json().get("keyStatus").asString()).isEqualTo("OK");
            assertThat(entered.json().has("nextKeyRotationAt")).isFalse();
            assertThat(entered.body()).doesNotContain(memberKey.key);
            assertThat(forRun(company, memberId)).isInstanceOf(RunModelAccess.Budget.class);

            // Members may not enter their own while the company does not let them, nor over an admin's.
            ApiResponse notAllowed = member.put(company.path("/me/model-access/key"), Map.of("key", memberKey.key));
            assertThat(notAllowed.status()).isEqualTo(409);
            assertThat(notAllowed.problemCode()).isEqualTo("MODEL_KEYS_MANAGED");
            assertThat(member.get(company.path("/me/model-access")).json().get("canEnterOwnKey").asBoolean())
                .isFalse();
            assertThat(owner.put(company.path("/model-settings"), Map.of("gateway", "PROVIDED", "defaultBudgetUsd", 0,
                    "memberKeys", true)).json().get("memberKeys").asBoolean()).isTrue();
            // The switch changed no connection: the key the admin entered stays.
            assertThat(forRun(company, memberId)).isInstanceOf(RunModelAccess.Budget.class);
            ApiResponse overAdmin = member.put(company.path("/me/model-access/key"), Map.of("key", memberKey.key));
            assertThat(overAdmin.status()).isEqualTo(409);
            assertThat(overAdmin.problemCode()).isEqualTo("MODEL_KEYS_MANAGED");

            // The admin removes it; the member enters their own.
            assertThat(owner.delete(company.path("/principals/" + memberId + "/model-access/key")).json()
                .get("keyProvided").asBoolean()).isFalse();
            assertThat(forRun(company, memberId)).isInstanceOfSatisfying(RunModelAccess.Wait.class,
                    wait -> assertThat(wait.reason()).isEqualTo(RunModelAccess.WaitReason.NO_MODEL_KEY));
            assertThat(member.get(company.path("/me/model-access")).json().get("canEnterOwnKey").asBoolean()).isTrue();
            FakeLiteLlm.Key ownKey = teamKey(gateway, "alice-own");
            ApiResponse own = member.put(company.path("/me/model-access/key"), Map.of("key", ownKey.key));
            assertThat(own.status()).as("%s", own).isEqualTo(200);
            assertThat(own.json().get("keyProvidedBySelf").asBoolean()).isTrue();
            assertThat(own.json().get("canEnterOwnKey").asBoolean()).isTrue();
            assertThat(forRun(company, memberId)).isInstanceOf(RunModelAccess.Budget.class);

            // The company's own key and a service account's.
            FakeLiteLlm.Key companyKey = teamKey(gateway, "company");
            assertThat(owner.put(company.path("/model-access/company-key/key"), Map.of("key", companyKey.key))
                .json().get("keyStatus").asString()).isEqualTo("OK");
            UUID bot = createServiceIdentity(owner, company, unique("bot"));
            assertThat(owner.put(company.path("/principals/" + bot + "/model-access/key"),
                    Map.of("key", teamKey(gateway, "bot").key)).status()).isEqualTo(200);
            // A key is one holder's: the member's own and the company's are not the service account's too (M1).
            for (String taken : List.of(ownKey.key, companyKey.key)) {
                ApiResponse shared = owner.put(company.path("/principals/" + bot + "/model-access/key"),
                        Map.of("key", taken));
                assertThat(shared.status()).as("%s", shared).isEqualTo(400);
                assertThat(shared.problemCode()).isEqualTo("VALIDATION_FAILED");
            }

            // Its gateway team rotates the keys: Tinfirm does not.
            ApiResponse rotate = owner.post(company.path("/principals/" + bot + "/model-access/rotate-key"), null);
            assertThat(rotate.status()).isEqualTo(409);
            assertThat(rotate.problemCode()).isEqualTo("MODEL_KEYS_MANAGED");

            // A suspended service account's key is blocked here, and its gateway team is asked to revoke it.
            assertThat(owner.post(company.path("/service-identities/" + bot + "/suspend"), null).status())
                .isEqualTo(200);
            reconciliation.reconcile(company.id());
            assertThat(owner.get(company.path("/principals/" + bot + "/model-access")).json().get("keyStatus")
                .asString()).isEqualTo("BLOCKED");
            assertThat(auditEvents(owner, company, "model_key.revoke_requested")).singleElement()
                .satisfies(event -> assertThat(event.get("details").get("cause").asString())
                    .isEqualTo("principal.suspended"));

            // The audit names the keys by their last four characters, never whole; the gateway's admin API was never
            // called.
            assertThat(auditEvents(owner, company, "model_key.provided")).hasSize(4);
            assertThat(auditEvents(owner, company, "model_key.removed")).hasSize(1);
            auditEvents(owner, company).forEach(event -> assertThat(event.toString()).doesNotContain(memberKey.key)
                .doesNotContain(ownKey.key)
                .doesNotContain(companyKey.key));
            assertThat(gateway.requests("/key/")).isEmpty();
            assertThat(gateway.requests("/team/")).isEmpty();
            assertThat(gateway.requests("/spend/")).isEmpty();

            // Leaving the gateway forgets every key entered there; each stays at the gateway, which its team is asked
            // to revoke: the member's own, the company's and the service account's.
            assertThat(owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 0))
                .status()).isEqualTo(200);
            assertThat(auditEvents(owner, company, "model_key.revoke_requested"))
                .filteredOn(event -> event.get("details").get("cause").asString().equals("gateway.changed"))
                .hasSize(3);
        }
    }

    @Test
    void aGatewayTinfirmManagesTakesNoKeyFromOutside() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID memberId = memberPrincipal(company, company.userId("MEMBER"));
        ApiResponse refused = owner.put(company.path("/principals/" + memberId + "/model-access/key"),
                Map.of("key", "sk-a-key-from-outside-0000"));
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("MODEL_KEYS_MANAGED");
        ApiResponse memberKeys = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED",
                "defaultBudgetUsd", 0, "memberKeys", true));
        assertThat(memberKeys.status()).isEqualTo(400);
        ApiResponse generic = owner.put(company.path("/model-settings"), Map.of("gateway", "BUNDLED",
                "defaultBudgetUsd", 0, "vendor", "GENERIC"));
        assertThat(generic.status()).isEqualTo(400);
    }
}
