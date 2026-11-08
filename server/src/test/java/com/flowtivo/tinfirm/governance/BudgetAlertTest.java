package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.model.internal.KeyReconciliation;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * Budget alerts (the package 6 contract, §5.3, §5.11; open point 4): each threshold once per period, recorded after a
 * sync read the key's spend; a member told of their own, admins of everyone's at 100 % with counts only, admins of a
 * service identity's at each threshold; the item gone after a raise of the budget and at the period's reset; the
 * company's thresholds, and their validation and audit.
 */
class BudgetAlertTest extends IntegrationTest {

    private final FakeLiteLlm litellm = FakeLiteLlm.bundled();

    @Autowired
    private KeyReconciliation reconciliation;

    private record Setup(TestCompany company, ApiClient owner, ApiClient admin, ApiClient member, UUID memberP,
            FakeLiteLlm.Key memberKey) {
    }

    private Setup setUp() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ModelGateways.configureBundled(owner, company);
        UUID memberP = memberPrincipal(company, company.userId("MEMBER"));
        FakeLiteLlm.Key key = ModelGateways.awaitKey(litellm, company, memberP);
        ModelGateways.awaitStatus(owner, company, memberP, "OK");
        return new Setup(company, owner, loginAs(company.email("ADMIN")), loginAs(company.email("MEMBER")), memberP,
                key);
    }

    /** LiteLLM counts this much against the key now; the next sync reads it. */
    private void spent(Setup setup, FakeLiteLlm.Key key, double usd) {
        key.spend = usd;
        reconciliation.reconcile(setup.company().id());
    }

    private static List<JsonNode> budgets(ApiClient client, TestCompany company) {
        ApiResponse inbox = client.get(company.path("/inbox"));
        assertThat(inbox.status()).as("%s", inbox).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        inbox.json().get("items").forEach(item -> {
            if (item.get("kind").asString().equals("BUDGET")) {
                items.add(item.get("budget"));
            }
        });
        return items;
    }

    private static long alerts(TestCompany company, UUID principalId) {
        try (TenantSql sql = TenantSql.in(company.id())) {
            return sql.count("SELECT count(*) FROM budget_alert WHERE principal_id = ?", principalId);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aMemberIsToldOfTheirOwnBudgetAndAdminsOnlyAtTheFullBudgetWithCounts() throws SQLException {
        Setup setup = setUp();
        TestCompany company = setup.company();
        spent(setup, setup.memberKey(), 10);
        assertThat(budgets(setup.member(), company)).isEmpty();

        spent(setup, setup.memberKey(), 20.5);
        List<JsonNode> own = budgets(setup.member(), company);
        assertThat(own).singleElement().satisfies(budget -> {
            assertThat(budget.get("own").asBoolean()).isTrue();
            assertThat(budget.get("threshold").asInt()).isEqualTo(80);
            assertThat(budget.get("spentUsd").decimalValue()).isEqualByComparingTo("20.5");
            assertThat(budget.get("budgetUsd").decimalValue()).isEqualByComparingTo("25");
            assertThat(budget.get("resetsAt").asString()).isNotBlank();
            assertThat(budget.get("waitingRuns") == null || budget.get("waitingRuns").isNull()).isTrue();
        });
        assertThat(budgets(setup.admin(), company)).as("80 %% of a member's is the member's alone").isEmpty();
        assertThat(budgets(setup.owner(), company)).isEmpty();

        // A turn waits for the spent budget: admins see how many, and nothing of it. The dispatcher reads every queued
        // run every 2 s and writes the reason it finds, so this run is one it holds for the budget too: written once the
        // budget is spent, of a conversation it can plan (scripted, as the run module's tests script theirs). Otherwise
        // a pass rewrites the reason before the inbox counts it: OTHER while the member may not use the agent, or
        // NO_RUNNER_HOST while the key is still usable.
        spent(setup, setup.memberKey(), 25);
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "wait-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, setup.memberP(), "CLAUDE_CODE");
        TestTurns.conversation(conversationId);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                     turn_number, acting_principal_id, payer_principal_id, model_access, status, epoch,
                                     branch, created_at, queue_reason, queue_reason_since)
                    VALUES (?, ?, ?, ?, ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', 'QUEUED', 1, ?, now(),
                            'BUDGET_SPENT', now())""", UUID.randomUUID(), company.id(), conversationId, key, agentId,
                    setup.memberP(), setup.memberP(), "agent/" + key);
            sql.commit();
        }
        assertThat(budgets(setup.member(), company)).singleElement()
            .satisfies(budget -> assertThat(budget.get("threshold").asInt()).isEqualTo(100));
        for (ApiClient admin : List.of(setup.admin(), setup.owner())) {
            assertThat(budgets(admin, company)).singleElement().satisfies(budget -> {
                assertThat(budget.get("own").asBoolean()).isFalse();
                assertThat(budget.get("principalId").asString()).isEqualTo(setup.memberP().toString());
                assertThat(budget.get("principalKind").asString()).isEqualTo("MEMBER");
                assertThat(budget.get("waitingRuns").asInt()).isEqualTo(1);
                assertThat(budget.get("waitingTriggers").asInt()).isZero();
            });
        }

        // Each threshold once per period, however often the spend is read.
        reconciliation.reconcile(company.id());
        assertThat(alerts(company, setup.memberP())).isEqualTo(2);
        // Not an audited act (§8).
        assertThat(auditEvents(setup.owner(), company)).noneMatch(event -> event.get("action").asString()
            .startsWith("budget"));
    }

    @Test
    void aRaisedBudgetAndANewPeriodEndTheItem() {
        Setup setup = setUp();
        TestCompany company = setup.company();
        spent(setup, setup.memberKey(), 21);
        assertThat(budgets(setup.member(), company)).hasSize(1);

        ApiResponse raised = setup.owner().patch(company.path("/principals/" + setup.memberP() + "/model-access"),
                Map.of("budgetUsd", 100));
        assertThat(raised.status()).as("%s", raised).isEqualTo(200);
        assertThat(budgets(setup.member(), company)).as("the spend is below 80 %% of the raised budget").isEmpty();

        ApiResponse back = setup.owner().patch(company.path("/principals/" + setup.memberP() + "/model-access"),
                Map.of("clearBudget", true));
        assertThat(back.status()).as("%s", back).isEqualTo(200);
        assertThat(budgets(setup.member(), company)).hasSize(1);

        // LiteLLM's period resets: the spend starts again, and the former period's alert no longer stands.
        setup.memberKey().resetAt = Instant.now().plus(60, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        spent(setup, setup.memberKey(), 1);
        assertThat(budgets(setup.member(), company)).isEmpty();
        // A crossing in the new period is a new alert.
        spent(setup, setup.memberKey(), 22);
        assertThat(budgets(setup.member(), company)).hasSize(1);
        assertThat(alerts(company, setup.memberP())).isEqualTo(2);
    }

    @Test
    void aServiceIdentitysBudgetIsTheAdminsAtEachThreshold() {
        Setup setup = setUp();
        TestCompany company = setup.company();
        UUID bot = createServiceIdentity(setup.owner(), company, unique("bot"));
        FakeLiteLlm.Key botKey = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(setup.owner(), company, bot, "OK");
        spent(setup, botKey, 20);

        assertThat(budgets(setup.admin(), company)).singleElement().satisfies(budget -> {
            assertThat(budget.get("principalKind").asString()).isEqualTo("SERVICE");
            assertThat(budget.get("threshold").asInt()).isEqualTo(80);
            assertThat(budget.get("principalName").asString()).startsWith("bot-");
        });
        assertThat(budgets(setup.member(), company)).isEmpty();

        spent(setup, botKey, 25);
        assertThat(budgets(setup.admin(), company)).singleElement().satisfies(budget -> {
            assertThat(budget.get("threshold").asInt()).isEqualTo(100);
            assertThat(budget.get("waitingRuns").asInt()).isZero();
        });
    }

    @Test
    void aServiceIdentitysBudgetIsAlsoTheAuthorsOfTheTriggersItPays() {
        Setup setup = setUp();
        TestCompany company = setup.company();
        UUID bot = createServiceIdentity(setup.owner(), company, unique("bot"));
        FakeLiteLlm.Key botKey = ModelGateways.awaitKey(litellm, company, bot);
        ModelGateways.awaitStatus(setup.owner(), company, bot, "OK");
        // The member authors a schedule that the service identity pays (package 4, through TriggerDirectory).
        TestTriggers.schedule(company.id(), TestRuns.agent(company.id()), setup.memberP(), bot);
        spent(setup, botKey, 20);

        assertThat(budgets(setup.member(), company)).singleElement().satisfies(budget -> {
            assertThat(budget.get("principalKind").asString()).isEqualTo("SERVICE");
            assertThat(budget.get("own").asBoolean()).isFalse();
            assertThat(budget.get("threshold").asInt()).isEqualTo(80);
        });

        spent(setup, botKey, 25);
        assertThat(budgets(setup.member(), company)).singleElement().satisfies(budget -> {
            assertThat(budget.get("threshold").asInt()).isEqualTo(100);
            assertThat(budget.get("waitingTriggers").asInt()).isEqualTo(1);
        });
    }

    @Test
    void theCompanyChoosesItsThresholdsWithinBounds() {
        Setup setup = setUp();
        TestCompany company = setup.company();
        String path = company.path("/model-settings");
        assertThat(setup.owner().get(path).json().get("budgetAlertThresholds"))
            .extracting(JsonNode::asInt).containsExactly(80, 100);

        for (Object bad : List.of(List.of(0), List.of(101), List.of(), List.of(10, 20, 30, 40), List.of(50, 50))) {
            ApiResponse refused = setup.owner().put(path, Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25,
                    "budgetAlertThresholds", bad));
            assertThat(refused.status()).as("%s: %s", bad, refused).isEqualTo(400);
            // The field refused, for the form to mark.
            assertThat(refused.json().get("errors")).as("%s: %s", bad, refused)
                .anySatisfy(error -> assertThat(error.get("field").asString()).startsWith("budgetAlertThresholds"));
        }
        ApiResponse both = setup.owner().put(path, Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25,
                "monthlyAlertUsd", 5, "clearMonthlyAlert", true));
        assertThat(both.status()).isEqualTo(400);
        assertThat(both.json().get("errors")).extracting(error -> error.get("field").asString())
            .containsExactly("monthlyAlertUsd", "clearMonthlyAlert");

        ApiResponse saved = setup.owner().put(path, Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25,
                "budgetAlertThresholds", List.of(90, 50), "monthlyAlertUsd", 1000));
        assertThat(saved.status()).as("%s", saved).isEqualTo(200);
        assertThat(saved.json().get("budgetAlertThresholds")).extracting(JsonNode::asInt).containsExactly(50, 90);
        assertThat(saved.json().get("monthlyAlertUsd").decimalValue()).isEqualByComparingTo("1000");
        assertThat(saved.json().get("relayEnabled").asBoolean()).as("the gateway is untouched").isTrue();
        JsonNode audit = auditEvents(setup.owner(), company, "model_settings.changed").getFirst().get("details");
        assertThat(audit.get("changed")).extracting(JsonNode::asString)
            .containsExactly("budgetAlertThresholds", "monthlyAlertUsd");
        // A request that changes nothing is not audited.
        int before = auditEvents(setup.owner(), company, "model_settings.changed").size();
        setup.owner().put(path, Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25));
        assertThat(auditEvents(setup.owner(), company, "model_settings.changed")).hasSize(before);

        spent(setup, setup.memberKey(), 13);
        assertThat(budgets(setup.member(), company)).singleElement()
            .satisfies(budget -> assertThat(budget.get("threshold").asInt()).isEqualTo(50));
        assertThat(setup.member().put(path, Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25,
                "budgetAlertThresholds", List.of(10))).status()).isEqualTo(403);

        ApiResponse cleared = setup.owner().put(path, Map.of("gateway", "BUNDLED", "defaultBudgetUsd", 25,
                "clearMonthlyAlert", true));
        assertThat(cleared.json().get("monthlyAlertUsd") == null || cleared.json().get("monthlyAlertUsd").isNull())
            .isTrue();
    }
}
