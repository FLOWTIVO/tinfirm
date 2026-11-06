package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * V049 over the data of V048 (ADR-0072): every service identity's account becomes a credential of its system, named
 * after the service identity and lent to it WRITE, and what named the account keeps naming it: its grants, a chat
 * system's bot, a trigger's row; a binding through a grant loses its holder, a poll of the credential its holder. Members' own accounts do not change. A
 * fresh database of its own, migrated to V048, seeded as the schema owner's superuser, then migrated on.
 */
class CredentialMigrationTest {

    @Test
    void aServiceIdentitysAccountBecomesACredentialLentToIt() throws SQLException {
        String url = TestDatabase.createDatabase("v049");
        migrate(url, "48");
        Ids ids = new Ids();
        try (Connection sql = TestDatabase.connectAsSuperuser(url)) {
            seed(sql, ids);
        }

        migrate(url, "latest");

        try (Connection sql = TestDatabase.connectAsSuperuser(url)) {
            Map<String, Object> credential = row(sql, "SELECT principal_id, principal_kind, name, enabled, kind,"
                    + " secret_id, external_id FROM account WHERE id = ?", ids.serviceAccount);
            assertThat(credential).containsEntry("principal_id", null)
                .containsEntry("principal_kind", "SYSTEM")
                .containsEntry("name", "Support bot")
                .containsEntry("enabled", true)
                .containsEntry("kind", "TOKEN")
                .containsEntry("secret_id", ids.serviceSecret)
                .containsEntry("external_id", "key-bot");
            assertThat(row(sql, "SELECT principal_id, access FROM service_account_credential WHERE account_id = ?",
                    ids.serviceAccount)).containsEntry("principal_id", ids.service).containsEntry("access", "WRITE");
            assertThat(row(sql, "SELECT account_principal_kind FROM account_grant WHERE id = ?", ids.grant))
                .containsEntry("account_principal_kind", "SYSTEM");

            // The chat system's bot: a credential named after its service identity, which still borrows it.
            assertThat(row(sql, "SELECT bot_account_id, bot_principal_kind FROM external_system WHERE id = ?",
                    ids.slack)).containsEntry("bot_account_id", ids.botAccount)
                .containsEntry("bot_principal_kind", "SYSTEM");
            assertThat(row(sql, "SELECT name FROM account WHERE id = ?", ids.botAccount))
                .containsEntry("name", "Slack bot");
            assertThat(row(sql, "SELECT principal_id FROM service_account_credential WHERE account_id = ?",
                    ids.botAccount)).containsEntry("principal_id", ids.slackService);

            // The member's own account is as it was.
            assertThat(row(sql, "SELECT principal_id, principal_kind, name, enabled FROM account WHERE id = ?",
                    ids.memberAccount)).containsEntry("principal_id", ids.member)
                .containsEntry("principal_kind", "MEMBER")
                .containsEntry("name", null)
                .containsEntry("enabled", true);
            assertThat(count(sql, "SELECT count(*) FROM service_account_credential")).isEqualTo(2);

            // The credential's poll is no service account's any more; the member's own account's keeps its member.
            assertThat(row(sql, "SELECT holder_principal_id FROM trigger_poll WHERE id = ?", ids.servicePoll))
                .containsEntry("holder_principal_id", null);
            assertThat(row(sql, "SELECT holder_principal_id FROM trigger_poll WHERE id = ?", ids.memberPoll))
                .containsEntry("holder_principal_id", ids.member);
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

    /** The ids the seed gives its rows. */
    private static final class Ids {

        final UUID company = UUID.randomUUID();

        final UUID user = UUID.randomUUID();

        final UUID member = UUID.randomUUID();

        final UUID service = UUID.randomUUID();

        final UUID slackService = UUID.randomUUID();

        final UUID jira = UUID.randomUUID();

        final UUID slack = UUID.randomUUID();

        final UUID serviceSecret = UUID.randomUUID();

        final UUID botSecret = UUID.randomUUID();

        final UUID serviceAccount = UUID.randomUUID();

        final UUID memberAccount = UUID.randomUUID();

        final UUID botAccount = UUID.randomUUID();

        final UUID group = UUID.randomUUID();

        final UUID grant = UUID.randomUUID();

        final UUID servicePoll = UUID.randomUUID();

        final UUID memberPoll = UUID.randomUUID();
    }

    /**
     * A company with a member and two service identities; a Jira on which the member holds a LINK and the first
     * service identity a token, lent to a group; a Slack whose bot is the second service identity's account.
     */
    private static void seed(Connection sql, Ids ids) throws SQLException {
        update(sql, "INSERT INTO company (id, name, created_at, updated_at) VALUES (?, 'Acme', now(), now())",
                ids.company);
        update(sql, """
                INSERT INTO app_user (id, email, display_name, auth_source, password_hash, created_at, updated_at)
                VALUES (?, ?, 'Mia', 'LOCAL', 'not-a-hash', now(), now())""", ids.user, ids.user + "@example.test");
        update(sql, """
                INSERT INTO company_membership (company_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, 'MEMBER', now(), now())""", ids.company, ids.user);
        update(sql, """
                INSERT INTO principal (id, company_id, kind, user_id, created_at, updated_at)
                VALUES (?, ?, 'MEMBER', ?, now(), now())""", ids.member, ids.company, ids.user);
        for (Object[] service : List.of(new Object[] {ids.service, "Support bot"},
                new Object[] {ids.slackService, "Slack bot"})) {
            update(sql, """
                    INSERT INTO principal (id, company_id, kind, name, created_at, updated_at)
                    VALUES (?, ?, 'SERVICE', ?, now(), now())""", service[0], ids.company, service[1]);
        }
        update(sql, """
                INSERT INTO external_system (id, company_id, name, handle, kind, base_url, created_at, updated_at)
                VALUES (?, ?, 'Jira', 'jira', 'JIRA_DC', 'https://jira.example.test', now(), now())""", ids.jira,
                ids.company);
        update(sql, """
                INSERT INTO external_system (id, company_id, name, handle, kind, base_url, created_at, updated_at)
                VALUES (?, ?, 'Slack', 'slack', 'SLACK', 'https://slack.example.test', now(), now())""", ids.slack,
                ids.company);
        for (Object[] secret : List.of(new Object[] {ids.serviceSecret, "SERVICE_ACCOUNT"},
                new Object[] {ids.botSecret, "SERVICE_ACCOUNT_2"})) {
            update(sql, """
                    INSERT INTO secret (id, company_id, name, current_version, created_at, updated_at)
                    VALUES (?, ?, ?, 1, now(), now())""", secret[0], ids.company, secret[1]);
        }
        String account = """
                INSERT INTO account (id, company_id, system_id, principal_id, principal_kind, kind, secret_id,
                                     external_id, connected_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, now(), now())""";
        update(sql, account, ids.serviceAccount, ids.company, ids.jira, ids.service, "SERVICE", "TOKEN",
                ids.serviceSecret, "key-bot");
        update(sql, account, ids.memberAccount, ids.company, ids.jira, ids.member, "MEMBER", "LINK", null, "key-mia");
        update(sql, account, ids.botAccount, ids.company, ids.slack, ids.slackService, "SERVICE", "TOKEN",
                ids.botSecret, "U-BOT");
        update(sql, "UPDATE external_system SET bot_account_id = ? WHERE id = ?", ids.botAccount, ids.slack);
        update(sql, """
                INSERT INTO user_group (id, company_id, name, created_at, updated_at)
                VALUES (?, ?, 'Analysts', now(), now())""", ids.group, ids.company);
        update(sql, """
                INSERT INTO account_grant (id, company_id, account_id, group_id, access, created_at, updated_at)
                VALUES (?, ?, ?, ?, 'READ', now(), now())""", ids.grant, ids.company, ids.serviceAccount, ids.group);
        String poll = """
                INSERT INTO trigger_poll (id, company_id, system_id, account_id, holder_principal_id, next_poll_at,
                                          created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, now(), now(), now())""";
        update(sql, poll, ids.servicePoll, ids.company, ids.jira, ids.serviceAccount, ids.service);
        update(sql, poll, ids.memberPoll, ids.company, ids.jira, ids.memberAccount, ids.member);
    }

    private static void update(Connection sql, String statement, Object... params) throws SQLException {
        try (PreparedStatement prepared = sql.prepareStatement(statement)) {
            for (int i = 0; i < params.length; i++) {
                prepared.setObject(i + 1, params[i]);
            }
            prepared.executeUpdate();
        }
    }

    private static Map<String, Object> row(Connection sql, String query, Object... params) throws SQLException {
        List<Map<String, Object>> rows = new ArrayList<>();
        try (PreparedStatement prepared = sql.prepareStatement(query)) {
            for (int i = 0; i < params.length; i++) {
                prepared.setObject(i + 1, params[i]);
            }
            try (ResultSet result = prepared.executeQuery()) {
                while (result.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    for (int column = 1; column <= result.getMetaData().getColumnCount(); column++) {
                        row.put(result.getMetaData().getColumnLabel(column), result.getObject(column));
                    }
                    rows.add(row);
                }
            }
        }
        assertThat(rows).as(query).hasSize(1);
        return rows.getFirst();
    }

    private static long count(Connection sql, String query) throws SQLException {
        return ((Number) row(sql, query).values().iterator().next()).longValue();
    }
}
