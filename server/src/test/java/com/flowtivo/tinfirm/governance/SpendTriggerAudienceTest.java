package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

import tools.jackson.databind.JsonNode;

/**
 * A trigger's spend through package 4's trigger module (the package 6 contract, §5.2, G1; the package 4 contract,
 * §5.13): its author and the members of its team see its runs' spend under its name, a member outside it sees none of
 * it, a filter on it included; deleted, it keeps its name and its audience.
 */
class SpendTriggerAudienceTest extends IntegrationTest {

    private static final String RANGE = "from=2026-01-01&to=2026-12-31";

    @Test
    void aTriggersAuthorAndTeamSeeItsSpendUnderItsNameAndNobodyElse() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String teamEmail = uniqueEmail("team");
        UUID teamUser = createUser(admin(), teamEmail, false);
        addMember(owner, company.id(), teamEmail, "MEMBER");
        String outsiderEmail = uniqueEmail("outsider");
        createUser(admin(), outsiderEmail, false);
        addMember(owner, company.id(), outsiderEmail, "MEMBER");
        UUID author = memberPrincipal(company, company.userId("MEMBER"));
        UUID team = createGroup(owner, company, unique("Team"), List.of(teamUser));
        UUID bot = createServiceIdentity(owner, company, unique("bot"));
        UUID agentId = TestRuns.agent(company.id());

        UUID triggerId = TestTriggers.schedule(company.id(), agentId, author, bot);
        UUID conversationId = UUID.randomUUID();
        String key = "sched-" + conversationId.toString().substring(0, 8);
        TestTriggers.conversation(company.id(), conversationId, key, triggerId, agentId, author, bot);
        UUID runId = UUID.randomUUID();
        TestRuns.ofTrigger(company.id(), runId, conversationId, key, agentId, triggerId, author, bot, "SUCCEEDED");
        Instant march = Instant.parse("2026-03-10T12:00:00Z");
        String name;
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("UPDATE trigger SET team_group_id = ? WHERE id = ?", team, triggerId);
            sql.accepted("""
                    INSERT INTO run_spend (company_id, run_id, model, conversation_id, agent_id, trigger_id,
                                           payer_principal_id, acting_principal_id, personal, day, ended_at, requests,
                                           cost_usd, source, recorded_at)
                    VALUES (?, ?, 'claude-sonnet-4-5', ?, ?, ?, ?, ?, false, ?, ?, 3, 7, 'GATEWAY', now())""",
                    company.id(), runId, conversationId, agentId, triggerId, bot, author,
                    march.atOffset(ZoneOffset.UTC).toLocalDate(), Timestamp.from(march));
            sql.commit();
            name = (String) sql.column("SELECT name FROM trigger WHERE id = ?", triggerId).getFirst();
        }
        String byTrigger = company.path("/spend?" + RANGE + "&groupBy=trigger");

        for (String email : new String[] {company.email("MEMBER"), teamEmail}) {
            JsonNode rows = ok(loginAs(email).get(byTrigger)).get("rows");
            assertThat(rows).as(email).hasSize(1);
            assertThat(rows.get(0).get("keys").get(0).get("label").asString()).isEqualTo(name);
            assertThat(rows.get(0).get("amounts").get("costUsd").decimalValue()).isEqualByComparingTo("7");
        }
        ApiClient outsider = loginAs(outsiderEmail);
        assertThat(ok(outsider.get(byTrigger)).get("rows")).isEmpty();
        assertThat(ok(outsider.get(company.path("/spend?" + RANGE + "&triggerId=" + triggerId))))
            .isEqualTo(ok(outsider.get(company.path("/spend?" + RANGE + "&triggerId=" + UUID.randomUUID()))));

        // Deleted, the trigger keeps its name and its audience for the spend it had.
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("UPDATE trigger SET deleted_at = now(), deleted_cause = 'USER' WHERE id = ?", triggerId);
            sql.commit();
        }
        JsonNode after = ok(loginAs(company.email("MEMBER")).get(byTrigger)).get("rows");
        assertThat(after).hasSize(1);
        assertThat(after.get(0).get("keys").get(0).get("label").asString()).isEqualTo(name);
    }

    private static JsonNode ok(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(200);
        return response.json();
    }
}
