package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the database keeps of spend (the package 6 contract, §3.1, §3.2, §9; G3), against PostgreSQL as the runtime
 * role: one spend row per run and model, written once and never changed; never a row of another company's run; a
 * cost exactly as its source allows; a settlement settled exactly with its time; thresholds and alerts in range; and
 * every table behind row-level security.
 */
class SpendConstraintTest extends IntegrationTest {

    private record Run(UUID companyId, UUID runId, UUID conversationId, UUID agentId, UUID person) {
    }

    private Run run() {
        TestCompany company = companyWithRoles();
        UUID person = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "c-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, person, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        TestRuns.ofConversation(company.id(), runId, conversationId, key, agentId, person, 1, "SUCCEEDED");
        return new Run(company.id(), runId, conversationId, agentId, person);
    }

    private static final String INSERT = """
            INSERT INTO run_spend (company_id, run_id, model, conversation_id, agent_id, payer_principal_id,
                                   acting_principal_id, personal, day, ended_at, cost_usd, source, recorded_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, true, ?, ?, ?, ?, now())""";

    private static Object[] row(Run run, UUID companyId, String model, Object cost, String source) {
        LocalDate day = LocalDate.parse("2026-03-10");
        return new Object[] {companyId, run.runId(), model, run.conversationId(), run.agentId(), run.person(),
            run.person(), day, java.sql.Timestamp.valueOf("2026-03-10 12:00:00"), cost, source};
    }

    @Test
    void oneSpendRowPerRunAndModelWrittenOnce() throws SQLException {
        Run run = run();
        try (TenantSql sql = TenantSql.in(run.companyId())) {
            sql.accepted(INSERT, row(run, run.companyId(), "m", new java.math.BigDecimal("1"), "GATEWAY"));
            assertThat(sql.refused(INSERT, row(run, run.companyId(), "m", new java.math.BigDecimal("2"), "RELAY")))
                .isEqualTo("run_spend_pkey");
            sql.accepted(INSERT, row(run, run.companyId(), "other", null, "RELAY"));
            assertThat(sql.refused("UPDATE run_spend SET cost_usd = 0 WHERE run_id = ?", run.runId()))
                .contains("permission denied");
            assertThat(sql.refused("DELETE FROM run_spend WHERE run_id = ?", run.runId()))
                .contains("permission denied");
        }
    }

    @Test
    void aCostAsItsSourceAllowsAndTheDayItsEnd() throws SQLException {
        Run run = run();
        try (TenantSql sql = TenantSql.in(run.companyId())) {
            assertThat(sql.refused(INSERT, row(run, run.companyId(), "a", null, "GATEWAY")))
                .isEqualTo("run_spend_cost_ck");
            assertThat(sql.refused(INSERT, row(run, run.companyId(), "b", new java.math.BigDecimal("1"),
                    "SUBSCRIPTION"))).isEqualTo("run_spend_cost_ck");
            // The sandbox is no source of spend (G2).
            assertThat(sql.refused(INSERT, row(run, run.companyId(), "c", null, "SANDBOX")))
                .isIn("run_spend_source_ck", "run_spend_cost_ck");
            Object[] wrongDay = row(run, run.companyId(), "d", null, "SUBSCRIPTION");
            wrongDay[7] = LocalDate.parse("2026-03-11");
            assertThat(sql.refused(INSERT, wrongDay)).isEqualTo("run_spend_day_ck");
            sql.accepted(INSERT, row(run, run.companyId(), "e", null, "SUBSCRIPTION"));
        }
    }

    @Test
    void neverASpendRowOfAnotherCompanysRunAndNothingAcrossCompanies() throws SQLException {
        Run mine = run();
        Run theirs = run();
        try (TenantSql sql = TenantSql.in(mine.companyId())) {
            // The other company's run, written in this company's context: no such run here.
            assertThat(sql.refused(INSERT, row(theirs, mine.companyId(), "m", new java.math.BigDecimal("1"),
                    "GATEWAY"))).isEqualTo("run_spend_run_fk");
            // Nor in that company's name from here.
            assertThat(sql.refused(INSERT, row(theirs, theirs.companyId(), "m", new java.math.BigDecimal("1"),
                    "GATEWAY"))).contains("row-level security");
        }
        try (TenantSql sql = TenantSql.in(theirs.companyId())) {
            sql.accepted(INSERT, row(theirs, theirs.companyId(), "m", new java.math.BigDecimal("1"), "GATEWAY"));
            sql.accepted("""
                    INSERT INTO spend_settlement (run_id, company_id, payer_principal_id, started_at, ended_at, state,
                                                  due_at)
                    VALUES (?, ?, ?, now(), now(), 'PENDING', now())""", theirs.runId(), theirs.companyId(),
                    theirs.person());
            sql.context(mine.companyId());
            assertThat(sql.count("SELECT count(*) FROM run_spend")).isZero();
            assertThat(sql.count("SELECT count(*) FROM spend_settlement")).isZero();
            assertThat(sql.count("SELECT count(*) FROM relay_usage")).isZero();
            assertThat(sql.count("SELECT count(*) FROM budget_alert")).isZero();
            assertThat(sql.count("SELECT count(*) FROM company_spend_alert")).isZero();
            sql.context(theirs.companyId());
            // The lookup across companies answers ids only, to the runtime role.
            assertThat(sql.count("SELECT count(*) FROM spend_due(now() + interval '1 minute', 10) WHERE run_id = ?",
                    theirs.runId())).isEqualTo(1);
        }
    }

    @Test
    void aSettlementIsSettledExactlyWithItsTime() throws SQLException {
        Run run = run();
        try (TenantSql sql = TenantSql.in(run.companyId())) {
            String insert = """
                    INSERT INTO spend_settlement (run_id, company_id, payer_principal_id, started_at, ended_at, state,
                                                  due_at, settled_at)
                    VALUES (?, ?, ?, now(), now(), ?, now(), ?)""";
            assertThat(sql.refused(insert, run.runId(), run.companyId(), run.person(), "SETTLED", null))
                .isEqualTo("spend_settlement_settled_ck");
            assertThat(sql.refused(insert, run.runId(), run.companyId(), run.person(), "PENDING",
                    java.sql.Timestamp.valueOf("2026-03-10 12:00:00"))).isEqualTo("spend_settlement_settled_ck");
            assertThat(sql.refused(insert, run.runId(), run.companyId(), run.person(), "LOST", null))
                .isEqualTo("spend_settlement_state_ck");
            assertThat(sql.refused("""
                    INSERT INTO spend_settlement (run_id, company_id, payer_principal_id, started_at, ended_at, state,
                                                  due_at, last_error)
                    VALUES (?, ?, ?, now(), now(), 'PENDING', now(), 'down')""", run.runId(), run.companyId(),
                    run.person())).isEqualTo("spend_settlement_failing_ck");
        }
    }

    @Test
    void thresholdsAndAlertsInRange() throws SQLException {
        Run run = run();
        try (TenantSql sql = TenantSql.in(run.companyId())) {
            sql.accepted("""
                    INSERT INTO model_settings (company_id, gateway, relay_enabled, default_budget_usd, budget_period,
                                                default_models, updated_at)
                    VALUES (?, 'BUNDLED', false, 25, '30d', '[]'::jsonb, now())""", run.companyId());
            for (String thresholds : new String[] {"{}", "{0}", "{101}", "{10,20,30,40}", "{80,NULL}"}) {
                assertThat(sql.refused("UPDATE model_settings SET budget_alert_thresholds = CAST(? AS smallint[]) "
                        + "WHERE company_id = ?", thresholds, run.companyId()))
                    .as(thresholds).isEqualTo("model_settings_alert_thresholds_ck");
            }
            assertThat(sql.refused("UPDATE model_settings SET monthly_alert_usd = -1 WHERE company_id = ?",
                    run.companyId())).isEqualTo("model_settings_monthly_alert_ck");
            String alert = """
                    INSERT INTO budget_alert (company_id, principal_id, resets_at, threshold, spent_usd, budget_usd,
                                              crossed_at)
                    VALUES (?, ?, 'infinity', ?, 1, 1, now())""";
            assertThat(sql.refused(alert, run.companyId(), run.person(), 0)).isEqualTo("budget_alert_threshold_ck");
            sql.accepted(alert, run.companyId(), run.person(), 80);
            assertThat(sql.refused(alert, run.companyId(), run.person(), 80)).isEqualTo("budget_alert_pkey");
            assertThat(sql.refused("""
                    INSERT INTO company_spend_alert (company_id, month, crossed_at, total_usd, alert_usd)
                    VALUES (?, '2026-03-02', now(), 1, 1)""", run.companyId()))
                .isEqualTo("company_spend_alert_month_ck");
        }
    }
}
