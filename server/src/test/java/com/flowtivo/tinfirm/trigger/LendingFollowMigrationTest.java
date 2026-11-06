package com.flowtivo.tinfirm.trigger;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * V050 over the data of V049 (ADR-0072): a live trigger's row whose service account borrows another credential now,
 * left so by a lending replaced while triggers paused for it, names that one with its identity; a row whose service
 * account still borrows its credential, and a deleted trigger's, stay as they were. A fresh database of its own,
 * migrated to V049, seeded as the schema owner's superuser, then migrated on.
 */
class LendingFollowMigrationTest {

    @Test
    void aRowNamesTheCredentialItsServiceAccountBorrowsNow() throws SQLException {
        String url = TestDatabase.createDatabase("v050");
        migrate(url, "49");
        UUID company = UUID.randomUUID();
        UUID author = UUID.randomUUID();
        UUID replaced = UUID.randomUUID();
        UUID kept = UUID.randomUUID();
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        UUID followed = UUID.randomUUID();
        UUID still = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID jira = UUID.randomUUID();
        try (Connection sql = TestDatabase.connectAsSuperuser(url)) {
            // One transaction: a tracker trigger has its source row at the commit (trigger_table_ck).
            sql.setAutoCommit(false);
            UUID user = UUID.randomUUID();
            update(sql, "INSERT INTO company (id, name, created_at, updated_at) VALUES (?, 'Acme', now(), now())",
                    company);
            update(sql, """
                    INSERT INTO app_user (id, email, display_name, auth_source, password_hash, created_at, updated_at)
                    VALUES (?, ?, 'Mia', 'LOCAL', 'not-a-hash', now(), now())""", user, user + "@example.test");
            update(sql, """
                    INSERT INTO company_membership (company_id, user_id, role, created_at, updated_at)
                    VALUES (?, ?, 'MEMBER', now(), now())""", company, user);
            update(sql, """
                    INSERT INTO principal (id, company_id, kind, user_id, created_at, updated_at)
                    VALUES (?, ?, 'MEMBER', ?, now(), now())""", author, company, user);
            for (UUID service : List.of(replaced, kept)) {
                update(sql, """
                        INSERT INTO principal (id, company_id, kind, name, created_at, updated_at)
                        VALUES (?, ?, 'SERVICE', ?, now(), now())""", service, company, "svc-" + service);
            }
            update(sql, """
                    INSERT INTO external_system (id, company_id, name, handle, kind, base_url, created_at, updated_at)
                    VALUES (?, ?, 'Jira', 'jira', 'JIRA_DC', 'https://jira.example.test', now(), now())""", jira,
                    company);
            UUID secret = UUID.randomUUID();
            update(sql, """
                    INSERT INTO secret (id, company_id, name, current_version, created_at, updated_at)
                    VALUES (?, ?, 'JIRA_BOT', 1, now(), now())""", secret, company);
            for (Object[] credential : List.of(new Object[] {first, "First", "bot-first"},
                    new Object[] {second, "Second", "bot-second"})) {
                update(sql, """
                        INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, name, kind,
                                             secret_id, external_id, connected_at, updated_at)
                        VALUES (?, ?, ?, NULL, 'SYSTEM', ?, 'TOKEN', ?, ?, now(), now())""", credential[0], company,
                        jira, credential[1], secret, credential[2]);
            }
            for (UUID service : List.of(replaced, kept)) {
                update(sql, """
                        INSERT INTO service_account_credential (company_id, principal_id, system_id, account_id,
                                                                access, created_at, updated_at)
                        VALUES (?, ?, ?, ?, 'WRITE', now(), now())""", company, service, jira, first);
            }
            UUID agent = UUID.randomUUID();
            update(sql, """
                    INSERT INTO agent (id, company_id, name, handle, runtimes, created_at, updated_at)
                    VALUES (?, ?, 'Analyst', 'analyst', '{CLAUDE_CODE}', now(), now())""", agent, company);
            for (Object[] trigger : List.of(new Object[] {followed, replaced}, new Object[] {still, kept},
                    new Object[] {deleted, replaced})) {
                update(sql, """
                        INSERT INTO trigger (id, company_id, name, source, system_id, events, agent_id, instruction,
                                             author_principal_id, payer_principal_id, armed_at, created_at, updated_at)
                        VALUES (?, ?, ?, 'TRACKER', ?, '[{"event": "ASSIGNED"}]'::jsonb, ?, 'Prepare the issue.', ?, ?,
                                now(), now(), now())""", trigger[0], company, "Prepare " + trigger[0], jira, agent,
                        author, author);
                update(sql, """
                        INSERT INTO trigger_account (company_id, trigger_id, system_id, account_id,
                                                     holder_principal_id, external_id, filled_from, saved_at)
                        VALUES (?, ?, ?, ?, ?, 'bot-first', 'CHOICE', now())""", company, trigger[0], jira, first,
                        trigger[1]);
            }
            update(sql, "UPDATE trigger SET deleted_at = now() WHERE id = ?", deleted);
            // An admin lent the first service account the second credential in place of the first, and its trigger
            // paused (V049: a trigger never switched).
            update(sql, "UPDATE service_account_credential SET account_id = ? WHERE principal_id = ?", second,
                    replaced);
            update(sql, "UPDATE trigger SET status = 'PAUSED', paused_reason = 'NOT_ENTITLED', paused_at = now() "
                    + "WHERE id = ?", followed);
            sql.commit();
        }

        migrate(url, "latest");

        try (Connection sql = TestDatabase.connectAsSuperuser(url)) {
            assertThat(row(sql, followed)).containsEntry("account_id", second).containsEntry("external_id",
                    "bot-second");
            assertThat(row(sql, still)).containsEntry("account_id", first).containsEntry("external_id", "bot-first");
            assertThat(row(sql, deleted)).containsEntry("account_id", first);
        }
    }

    private static void migrate(String url, String target) {
        Flyway.configure()
            .dataSource(url, TestDatabase.OWNER, TestDatabase.OWNER_PASSWORD)
            .locations("classpath:db/migration")
            .placeholders(Map.of("app_role", TestDatabase.APP))
            .target(target)
            .load()
            .migrate();
    }

    private static void update(Connection sql, String statement, Object... params) throws SQLException {
        try (PreparedStatement prepared = sql.prepareStatement(statement)) {
            for (int i = 0; i < params.length; i++) {
                prepared.setObject(i + 1, params[i]);
            }
            prepared.executeUpdate();
        }
    }

    /** The trigger's row's credential and identity. */
    private static Map<String, Object> row(Connection sql, UUID triggerId) throws SQLException {
        try (PreparedStatement prepared = sql.prepareStatement(
                "SELECT account_id, external_id FROM trigger_account WHERE trigger_id = ?")) {
            prepared.setObject(1, triggerId);
            try (ResultSet result = prepared.executeQuery()) {
                assertThat(result.next()).as("the row of %s", triggerId).isTrue();
                Map<String, Object> row = new java.util.HashMap<>();
                row.put("account_id", result.getObject("account_id"));
                row.put("external_id", result.getObject("external_id"));
                return row;
            }
        }
    }
}
