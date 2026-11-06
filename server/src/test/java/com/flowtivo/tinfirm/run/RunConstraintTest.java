package com.flowtivo.tinfirm.run;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;

/**
 * What the database itself refuses of runs (the package 3 contract, §3.11, §9): one run at a time per conversation
 * (I11), an agent of the run's company, a personal run's payer is its person (I10), a model access with its
 * subscription or without, a read-only
 * repository says why, a running run has a lease; and row-level security: another company's runs, events and commands
 * are invisible, and the runtime role deletes no run.
 */
class RunConstraintTest extends IntegrationTest {

    private static final String INSERT = """
            INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                             turn_number, acting_principal_id, payer_principal_id, model_access, subscription_host_id,
                             subscription_label, status, lease_expires_at, branch, created_at)
            VALUES (?, ?, ?, 'conv-1', ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, ?, NULL, ?, ?, ?, 'agent/conv-1', ?)""";

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private UUID member(TestCompany company) throws SQLException {
        try (TenantSql sql = TenantSql.in(company.id())) {
            return (UUID) sql.column("SELECT id FROM principal WHERE user_id = ?", company.userIds().get("MEMBER"))
                .getFirst();
        }
    }

    /** A bare conversation of the person with the agent: a run's conversation is a foreign key (V005). */
    private static UUID conversation(TestCompany company, UUID agent, UUID person) {
        UUID id = UUID.randomUUID();
        TestConversations.bare(company.id(), id, "conv-" + id.toString().substring(0, 8), agent, person,
                "CLAUDE_CODE");
        return id;
    }

    @Test
    void theDatabaseKeepsTheInvariantsOfRuns() throws SQLException {
        TestCompany company = companyWithRoles();
        UUID person = member(company);
        UUID owner;
        try (TenantSql sql = TenantSql.in(company.id())) {
            owner = (UUID) sql.column("SELECT id FROM principal WHERE user_id = ?", company.userIds().get("OWNER"))
                .getFirst();
        }
        UUID agent = TestRuns.agent(company.id());
        UUID conversation = conversation(company, agent, person);
        UUID agentOfAnother = TestRuns.agent(companyWithRoles().id());
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID first = UUID.randomUUID();
            assertThat(sql.update(INSERT, first, company.id(), conversation, agent, person, person, "BUDGET", null,
                    "QUEUED", null, now())).isEqualTo(1);
            // A run's agent is one of its company's.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(),
                    conversation(company, agent, person), agentOfAnother, person,
                    person, "BUDGET", null, "QUEUED", null, now())).isEqualTo("run_agent_fk");
            // I11: one active run per conversation; another conversation runs in parallel.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), conversation, agent, person, person,
                    "BUDGET", null, "QUEUED", null, now())).isEqualTo("run_one_active_per_conversation_uq");
            assertThat(sql.update(INSERT, UUID.randomUUID(), company.id(),
                    conversation(company, agent, person), agent, person, person,
                    "BUDGET", null, "QUEUED", null, now())).isEqualTo(1);
            // I10: nobody else pays for a person's turn.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(),
                    conversation(company, agent, person), agent, person, owner,
                    "BUDGET", null, "QUEUED", null, now())).isEqualTo("run_payer_ck");
            // A subscription names its label; a budget names none.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(),
                    conversation(company, agent, person), agent, person, person,
                    "SUBSCRIPTION", null, "QUEUED", null, now())).isEqualTo("run_model_access_ck");
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(),
                    conversation(company, agent, person), agent, person, person,
                    "BUDGET", "claude-max", "QUEUED", null, now())).isEqualTo("run_model_access_ck");
            // A running run has a lease.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(),
                    conversation(company, agent, person), agent, person, person,
                    "BUDGET", null, "RUNNING", null, now())).isEqualTo("run_lease_ck");
            // A read-only repository says why, a writable one does not.
            assertThat(sql.refused("""
                    INSERT INTO run_repository (run_id, company_id, position, name, dir, read_only, read_only_cause)
                    VALUES (?, ?, 0, 'server', 'server', true, NULL)""", first, company.id()))
                .isEqualTo("run_repository_read_only_cause_ck");
            assertThat(sql.update("""
                    INSERT INTO run_repository (run_id, company_id, position, name, dir, read_only, read_only_cause)
                    VALUES (?, ?, 0, 'server', 'server', true, 'GRANT')""", first, company.id())).isEqualTo(1);
            // Runs are never deleted by the server.
            assertThat(sql.refused("DELETE FROM run WHERE id = ?", first)).contains("permission denied");
        }
    }

    @Test
    void anotherCompanysRunsAreInvisible() throws SQLException {
        TestCompany company = companyWithRoles();
        TestCompany other = companyWithRoles();
        UUID person = member(company);
        UUID run = UUID.randomUUID();
        UUID agent = TestRuns.agent(company.id());
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update(INSERT, run, company.id(), conversation(company, agent, person), agent,
                    person, person, "BUDGET", null, "QUEUED",
                    null, now());
            sql.update("""
                    INSERT INTO run_event (run_id, seq, company_id, type, at, payload)
                    VALUES (?, 1, ?, 'LOG', ?, '{"message":"hi"}')""", run, company.id(), now());
            sql.update("""
                    INSERT INTO run_command (id, company_id, run_id, type, status, created_at)
                    VALUES (?, ?, ?, 'CANCEL', 'PENDING', ?)""", UUID.randomUUID(), company.id(), run, now());
            assertThat(sql.count("SELECT count(*) FROM run WHERE id = ?", run)).isEqualTo(1);

            sql.context(other.id());
            assertThat(sql.count("SELECT count(*) FROM run WHERE id = ?", run)).isZero();
            assertThat(sql.count("SELECT count(*) FROM run_event WHERE run_id = ?", run)).isZero();
            assertThat(sql.count("SELECT count(*) FROM run_command WHERE run_id = ?", run)).isZero();
            assertThat(sql.update("UPDATE run SET status = 'CANCELLED' WHERE id = ?", run)).isZero();
            // A row of another company is refused, whatever its context names.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), UUID.randomUUID(), agent, person, person,
                    "BUDGET", null, "QUEUED", null, now())).contains("row-level security");
            sql.context(null);
            assertThat(sql.count("SELECT count(*) FROM run WHERE id = ?", run)).isZero();
        }
    }
}
