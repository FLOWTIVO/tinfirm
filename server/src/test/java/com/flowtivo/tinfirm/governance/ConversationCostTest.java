package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

import tools.jackson.databind.JsonNode;

/**
 * What a conversation cost, on its page ({@code Conversation.cost}) and in the list ({@code ConversationSummary.costUsd}
 * and {@code tokens}): its settled turns' spend and tokens over all time, each turn once with its models together, as
 * {@code getSpend} with its {@code conversationId} counts them in the caller's scope (G1). A turn not settled yet is not
 * in it; a subscription's turn has its tokens and no cost. A trigger's conversation is {@code SpendTriggerScopeTest}'s.
 */
class ConversationCostTest extends IntegrationTest {

    private static final Instant MARCH = Instant.parse("2026-03-10T12:00:00Z");

    /** A conversation of the person's with {@code turns} ended turns, and its runs' ids by turn. */
    private record Thread(UUID id, String key, UUID[] runs) {
    }

    private static Thread thread(TestCompany company, UUID agentId, UUID person, int turns) {
        UUID id = UUID.randomUUID();
        String key = "cost-" + id.toString().substring(0, 8);
        TestConversations.bare(company.id(), id, key, agentId, person, "CLAUDE_CODE");
        UUID[] runs = new UUID[turns];
        for (int turn = 0; turn < turns; turn++) {
            runs[turn] = UUID.randomUUID();
            TestRuns.ofConversation(company.id(), runs[turn], id, key, agentId, person, turn + 1, "SUCCEEDED");
        }
        return new Thread(id, key, runs);
    }

    private static void spend(TestCompany company, Thread thread, int turn, UUID agentId, UUID person, String model,
            Instant endedAt, @Nullable String cost, String source) {
        SpendScopeTest.spendRow(company.id(), thread.runs()[turn], model, thread.id(), agentId, null, person, person,
                true, endedAt, cost != null ? new BigDecimal(cost) : null, source);
    }

    /** A settled row of the turn on one model, with its four token counts. */
    private static void spentTokens(TestCompany company, Thread thread, int turn, UUID agentId, UUID person,
            String model, long input, long cacheWrite, long cacheRead, long output, String cost, String source) {
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    INSERT INTO run_spend (company_id, run_id, model, conversation_id, agent_id, payer_principal_id,
                                           acting_principal_id, personal, day, ended_at, requests, input_tokens,
                                           cache_write_tokens, cached_input_tokens, output_tokens, cost_usd, source,
                                           recorded_at)
                    VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?, 1, ?, ?, ?, ?, ?, ?, now())""", company.id(),
                    thread.runs()[turn], model, thread.id(), agentId, person, person,
                    MARCH.atOffset(ZoneOffset.UTC).toLocalDate(), Timestamp.from(MARCH), input, cacheWrite,
                    cacheRead, output, new BigDecimal(cost), source);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static JsonNode ok(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }

    private static Map<String, BigDecimal> listed(ApiClient client, TestCompany company) {
        Map<String, BigDecimal> costs = new HashMap<>();
        ok(client.get(company.path("/conversations"))).get("items")
            .forEach(item -> costs.put(item.get("id").asString(), item.get("costUsd").decimalValue()));
        return costs;
    }

    @Test
    void aConversationCostsWhatItsSettledTurnsCostEachTurnOnce() {
        TestCompany company = companyWithRoles();
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        Thread thread = thread(company, agentId, member, 3);
        // The first turn on two models, the second from the relay's count; the third has not settled yet.
        spend(company, thread, 0, agentId, member, "claude-sonnet-4-5", MARCH, "0.25", "GATEWAY");
        spend(company, thread, 0, agentId, member, "claude-haiku-4-5", MARCH, "0.05", "GATEWAY");
        spend(company, thread, 1, agentId, member, "claude-sonnet-4-5", MARCH.plusSeconds(600), "0.125", "RELAY");
        // Another conversation's spend is its own; a conversation without any costs nothing.
        Thread other = thread(company, agentId, member, 1);
        spend(company, other, 0, agentId, member, "claude-sonnet-4-5", MARCH, "9", "GATEWAY");
        Thread fresh = thread(company, agentId, member, 0);

        ApiClient person = loginAs(company.email("MEMBER"));
        JsonNode cost = ok(person.get(company.path("/conversations/" + thread.id()))).get("cost");
        assertThat(cost.get("costUsd").decimalValue()).isEqualByComparingTo("0.425");
        assertThat(cost.get("subscriptionTokens").asLong()).isZero();
        assertThat(cost.get("turns")).hasSize(2);
        assertThat(cost.get("turns").get(0).get("runId").asString()).isEqualTo(thread.runs()[0].toString());
        assertThat(cost.get("turns").get(0).get("costUsd").decimalValue()).isEqualByComparingTo("0.3");
        assertThat(cost.get("turns").get(0).get("source").asString()).isEqualTo("GATEWAY");
        assertThat(cost.get("turns").get(1).get("runId").asString()).isEqualTo(thread.runs()[1].toString());
        assertThat(cost.get("turns").get(1).get("costUsd").decimalValue()).isEqualByComparingTo("0.125");
        assertThat(cost.get("turns").get(1).get("source").asString()).isEqualTo("RELAY");

        JsonNode none = ok(person.get(company.path("/conversations/" + fresh.id()))).get("cost");
        assertThat(none.get("costUsd").decimalValue()).isZero();
        assertThat(none.get("turns")).isEmpty();

        // The list says the same, and so does the spend view over the conversation.
        Map<String, BigDecimal> listed = listed(person, company);
        assertThat(listed.get(thread.id().toString())).isEqualByComparingTo("0.425");
        assertThat(listed.get(other.id().toString())).isEqualByComparingTo("9");
        assertThat(listed.get(fresh.id().toString())).isZero();
        JsonNode totals = ok(person.get(company.path("/spend?from=2026-01-01&to=2026-12-31&conversationId="
                + thread.id()))).get("totals");
        assertThat(totals.get("costUsd").decimalValue()).isEqualByComparingTo(cost.get("costUsd").decimalValue());

        // OWNER, who reads every conversation and sees every run's spend, sees the same.
        ApiClient owner = loginAs(company.email("OWNER"));
        assertThat(ok(owner.get(company.path("/conversations/" + thread.id()))).get("cost").get("costUsd")
            .decimalValue()).isEqualByComparingTo("0.425");
    }

    @Test
    void aSubscriptionsTurnHasItsTokensAndNoCost() {
        TestCompany company = companyWithRoles();
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        Thread thread = thread(company, agentId, member, 1);
        spend(company, thread, 0, agentId, member, "default", MARCH, null, "SUBSCRIPTION");

        ApiClient person = loginAs(company.email("MEMBER"));
        JsonNode cost = ok(person.get(company.path("/conversations/" + thread.id()))).get("cost");
        assertThat(cost.get("costUsd").decimalValue()).isZero();
        assertThat(cost.get("subscriptionTokens").asLong()).isEqualTo(110);
        assertThat(cost.get("turns")).hasSize(1);
        JsonNode turn = cost.get("turns").get(0);
        assertThat(turn.get("costUsd") == null || turn.get("costUsd").isNull()).isTrue();
        assertThat(turn.get("source").asString()).isEqualTo("SUBSCRIPTION");
        assertThat(listed(person, company).get(thread.id().toString())).isZero();
    }

    @Test
    void itsTokensAreItsSettledTurnsInputCacheWritesCacheReadsAndOutput() {
        TestCompany company = companyWithRoles();
        UUID member = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        Thread thread = thread(company, agentId, member, 3);
        // The first turn on two models, the second from the relay's count; the third has not settled yet.
        spentTokens(company, thread, 0, agentId, member, "claude-sonnet-4-5", 1000, 200, 5000, 300, "0.4", "GATEWAY");
        spentTokens(company, thread, 0, agentId, member, "claude-haiku-4-5", 100, 0, 0, 20, "0.01", "GATEWAY");
        spentTokens(company, thread, 1, agentId, member, "claude-sonnet-4-5", 50, 10, 400, 5, "0.02", "RELAY");
        Thread fresh = thread(company, agentId, member, 0);

        ApiClient person = loginAs(company.email("MEMBER"));
        JsonNode tokens = ok(person.get(company.path("/conversations/" + thread.id()))).get("cost").get("tokens");
        assertThat(tokens.get("inputTokens").asLong()).isEqualTo(1150);
        assertThat(tokens.get("cacheWriteTokens").asLong()).isEqualTo(210);
        assertThat(tokens.get("cachedInputTokens").asLong()).isEqualTo(5400);
        assertThat(tokens.get("outputTokens").asLong()).isEqualTo(325);

        // The list says the same, a conversation without spend zero, and the spend view over it agrees.
        Map<String, JsonNode> listed = new HashMap<>();
        ok(person.get(company.path("/conversations"))).get("items")
            .forEach(item -> listed.put(item.get("id").asString(), item.get("tokens")));
        assertThat(listed.get(thread.id().toString())).isEqualTo(tokens);
        JsonNode none = listed.get(fresh.id().toString());
        assertThat(none.get("inputTokens").asLong() + none.get("cacheWriteTokens").asLong()
                + none.get("cachedInputTokens").asLong() + none.get("outputTokens").asLong()).isZero();
        JsonNode totals = ok(person.get(company.path("/spend?from=2026-01-01&to=2026-12-31&conversationId="
                + thread.id()))).get("totals");
        assertThat(totals.get("inputTokens").asLong()).isEqualTo(1150);
        assertThat(totals.get("cacheWriteTokens").asLong()).isEqualTo(210);
        assertThat(totals.get("cachedInputTokens").asLong()).isEqualTo(5400);
        assertThat(totals.get("outputTokens").asLong()).isEqualTo(325);
    }
}
