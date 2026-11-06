package com.flowtivo.tinfirm.governance.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.flowtivo.tinfirm.run.RunEvents;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

import tools.jackson.databind.JsonNode;

/**
 * Spend of triggers' runs through the seam {@link TriggerSpend} (the package 6 contract, §5.2; G1), its answers
 * stood in for (a trigger's conversation and run are package 4's rows): a trigger's author and team see its runs' spend, titled; a member outside its
 * audience sees none of it, a filter on it included; ADMIN sees a trigger's conversation titled, never folded; the
 * sync copies a run's trigger onto its spend.
 */
@Import(SpendTriggerScopeTest.Triggers.class)
class SpendTriggerScopeTest extends IntegrationTest {

    /** Triggers as the seam answers them in this test. */
    static final class FakeTriggers implements TriggerSpend {

        final Map<UUID, UUID> runs = new ConcurrentHashMap<>();

        final Map<UUID, Set<UUID>> audience = new ConcurrentHashMap<>();

        final Map<UUID, String> names = new ConcurrentHashMap<>();

        @Override
        public @Nullable UUID triggerOf(UUID companyId, UUID runId) {
            return runs.get(runId);
        }

        @Override
        public Set<UUID> audienceOf(UUID companyId, UUID principalId) {
            return audience.getOrDefault(principalId, Set.of());
        }

        @Override
        public Map<UUID, String> names(UUID companyId, Collection<UUID> triggerIds) {
            Map<UUID, String> found = new HashMap<>();
            triggerIds.forEach(id -> {
                if (names.containsKey(id)) {
                    found.put(id, names.get(id));
                }
            });
            return found;
        }

        @Override
        public Set<UUID> authorsPaidBy(UUID companyId, UUID payerPrincipalId) {
            return Set.of();
        }

        @Override
        public int livePaidBy(UUID companyId, UUID payerPrincipalId) {
            return 0;
        }
    }

    @TestConfiguration
    static class Triggers {

        @Bean
        @Primary
        FakeTriggers fakeTriggers() {
            return new FakeTriggers();
        }
    }

    @Autowired
    private FakeTriggers triggers;

    @Autowired
    private SystemTransactions transactions;

    @Autowired
    private ApplicationEventPublisher publisher;

    private static final String RANGE = "from=2026-01-01&to=2026-12-31";

    @Test
    void aTriggersAudienceSeesItsSpendTitledAndNobodyElseDoes() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String teamEmail = uniqueEmail("team");
        UUID teamUser = createUser(admin(), teamEmail, false);
        addMember(owner, company.id(), teamEmail, "MEMBER");
        String outsiderEmail = uniqueEmail("outsider");
        createUser(admin(), outsiderEmail, false);
        addMember(owner, company.id(), outsiderEmail, "MEMBER");
        UUID author = memberPrincipal(company, company.userId("MEMBER"));
        UUID team = memberPrincipal(company, teamUser);
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        UUID agentId = TestRuns.agent(company.id());

        // A trigger of the author's, paid by the service identity (package 4's tables); its audience and name as the
        // seam answers them.
        UUID triggerId = TestTriggers.schedule(company.id(), agentId, author, bot);
        triggers.names.put(triggerId, "Triage SHOP");
        Set<UUID> ofTrigger = new HashSet<>(Set.of(triggerId));
        triggers.audience.put(author, ofTrigger);
        triggers.audience.put(team, ofTrigger);

        UUID conversationId = UUID.randomUUID();
        String key = "shop-" + conversationId.toString().substring(0, 8);
        TestTriggers.conversation(company.id(), conversationId, key, triggerId, agentId, author, bot);
        UUID runId = UUID.randomUUID();
        TestRuns.ofTrigger(company.id(), runId, conversationId, key, agentId, triggerId, author, bot, "SUCCEEDED");
        Instant march = Instant.parse("2026-03-10T12:00:00Z");
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    INSERT INTO run_spend (company_id, run_id, model, conversation_id, agent_id, trigger_id,
                                           payer_principal_id, acting_principal_id, personal, day, ended_at, requests,
                                           cost_usd, source, recorded_at)
                    VALUES (?, ?, 'claude-sonnet-4-5', ?, ?, ?, ?, ?, false, ?, ?, 3, 5, 'GATEWAY', now())""",
                    company.id(), runId, conversationId, agentId, triggerId, bot, author,
                    march.atOffset(ZoneOffset.UTC).toLocalDate(), Timestamp.from(march));
            sql.commit();
        }
        String byTrigger = company.path("/spend?" + RANGE + "&groupBy=trigger");
        String byConversation = company.path("/spend?" + RANGE + "&groupBy=conversation");

        for (String email : new String[] {company.email("MEMBER"), teamEmail}) {
            ApiClient member = loginAs(email);
            JsonNode rows = ok(member.get(byTrigger)).get("rows");
            assertThat(rows).hasSize(1);
            assertThat(rows.get(0).get("keys").get(0).get("label").asString()).isEqualTo("Triage SHOP");
            assertThat(rows.get(0).get("amounts").get("costUsd").decimalValue()).isEqualByComparingTo("5");
            JsonNode conversation = ok(member.get(byConversation)).get("rows").get(0).get("keys").get(0);
            assertThat(conversation.get("title").asString()).startsWith("Test schedule conversation");
        }

        ApiClient outsider = loginAs(outsiderEmail);
        assertThat(ok(outsider.get(byTrigger)).get("rows")).isEmpty();
        JsonNode filtered = ok(outsider.get(company.path("/spend?" + RANGE + "&triggerId=" + triggerId)));
        JsonNode unknown = ok(outsider.get(company.path("/spend?" + RANGE + "&triggerId=" + UUID.randomUUID())));
        assertThat(filtered).isEqualTo(unknown);

        ApiClient admin = loginAs(company.email("ADMIN"));
        JsonNode adminKey = ok(admin.get(byConversation)).get("rows").get(0).get("keys").get(0);
        assertThat(adminKey.get("folded").asBoolean()).isFalse();
        assertThat(adminKey.get("key").asString()).isEqualTo(key);
        assertThat(adminKey.get("title").asString()).startsWith("Test schedule conversation");

        // The conversation's own cost is the same spend in the same scope: its author and ADMIN see it.
        String conversation = company.path("/conversations/" + conversationId);
        for (ApiClient reader : new ApiClient[] {loginAs(company.email("MEMBER")), admin}) {
            JsonNode cost = ok(reader.get(conversation)).get("cost");
            assertThat(cost.get("costUsd").decimalValue()).isEqualByComparingTo("5");
            assertThat(cost.get("turns").get(0).get("runId").asString()).isEqualTo(runId.toString());
        }
        // The author it started with still reads it once they are out of the trigger's audience, but its spend is
        // not theirs to see any more (G1): it costs them nothing, as an unknown conversation's would.
        triggers.audience.remove(author);
        JsonNode outOfScope = ok(loginAs(company.email("MEMBER")).get(conversation)).get("cost");
        assertThat(outOfScope.get("costUsd").decimalValue()).isZero();
        assertThat(outOfScope.get("turns")).isEmpty();
    }

    @Test
    void theSyncCopiesARunsTriggerOntoItsSpend() throws SQLException {
        TestCompany company = companyWithRoles();
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "sub-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, person, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        TestRuns.ofConversation(company.id(), runId, conversationId, key, agentId, person, 1, "SUCCEEDED");
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("""
                    UPDATE run SET model_access = 'SUBSCRIPTION', subscription_label = 'max', input_tokens = 9,
                                   output_tokens = 1
                     WHERE id = ?""", runId);
            sql.commit();
        }
        UUID triggerId = UUID.randomUUID();
        triggers.runs.put(runId, triggerId);
        transactions.runInCompany(company.id(), () -> publisher.publishEvent(new RunEvents.RunEnded(company.id(),
                runId, conversationId, RunStatus.SUCCEEDED)));
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT trigger_id FROM run_spend WHERE run_id = ?", runId))
                .containsExactly(triggerId);
            assertThat(sql.column("SELECT model FROM run_spend WHERE run_id = ?", runId))
                .containsExactly(SpendSync.DEFAULT_MODEL);
        }
    }

    private static JsonNode ok(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }
}
