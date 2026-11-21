package com.flowtivo.tinfirm.run;

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
 * V087 over the data of V086 (ADR-0116, the threat model's Q48 (d) and (i)): the repositories of a run under way at the
 * upgrade get their assignment's epoch, the version of their binding (0, every binding's at the upgrade), and a record
 * of their assignment with the host it went to; an ended run's rows and a row without a binding get neither. A fresh
 * database of its own, migrated to V086, seeded as the superuser with the foreign keys' triggers off (the rows the
 * migration reads and those its rows point at), then migrated on.
 */
class GitRevocationMigrationTest {

    /** Who acts and pays: the same person, as a member's own turn has it. */
    private static final UUID PERSON = UUID.randomUUID();

    @Test
    void theRowsOfARunUnderWayGetTheirBindingsVersionAndTheirAssignmentsRecord() throws SQLException {
        String url = TestDatabase.createDatabase("v087");
        migrate(url, "86");
        UUID company = UUID.randomUUID();
        UUID host = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        UUID ended = UUID.randomUUID();
        UUID system = UUID.randomUUID();
        UUID binding = UUID.randomUUID();
        UUID shop = UUID.randomUUID();
        UUID web = UUID.randomUUID();
        UUID docs = UUID.randomUUID();
        try (Connection sql = TestDatabase.connectAsSuperuser(url)) {
            sql.setAutoCommit(false);
            update(sql, "SET LOCAL session_replication_role = replica");
            update(sql, """
                    INSERT INTO runner_host (id, company_id, name, scope, token_hash, created_at)
                    VALUES (?, ?, 'ci-1', 'COMPANY', ?, now())""", host, company, "a".repeat(64));
            update(sql, """
                    INSERT INTO external_system (id, company_id, name, handle, kind, base_url, created_at, updated_at)
                    VALUES (?, ?, 'Git', 'git', 'GIT', NULL, now(), now())""", system, company);
            for (UUID repository : List.of(shop, web, docs)) {
                update(sql, """
                        INSERT INTO system_repository (id, company_id, system_id, url, name, default_branch,
                                                       created_at)
                        VALUES (?, ?, ?, ?, ?, 'main', now())""", repository, company, system,
                        "https://git.example.test/acme/" + repository, "acme/" + repository);
            }
            update(sql, """
                    INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how,
                                                      account_id, holder_principal_id, bound_at, created_at,
                                                      updated_at)
                    VALUES (?, ?, ?, ?, 'BOUND', 'OWN', ?, ?, now(), now(), now())""", binding, company,
                    UUID.randomUUID(), system, UUID.randomUUID(), PERSON);
            run(sql, running, company, host, "RUNNING", 3);
            run(sql, ended, company, host, "SUCCEEDED", 2);
            // The running run's shop with the binding's token, and docs without a binding; the ended run's web.
            repository(sql, running, company, 0, shop, system, binding, "acme/shop", "shop");
            repository(sql, running, company, 1, docs, system, null, "acme/docs", "docs");
            repository(sql, ended, company, 0, web, system, binding, "acme/web", "web");
            sql.commit();
        }
        migrate(url, "87");
        try (Connection sql = TestDatabase.connectAsSuperuser(url)) {
            assertThat(rows(sql, """
                    SELECT run_id, component_id, epoch, binding_version FROM run_repository ORDER BY run_id, position"""))
                .containsExactlyInAnyOrder(
                        row(running, shop, 3L, 0L),
                        row(running, docs, 3L, null),
                        row(ended, web, null, null));
            assertThat(rows(sql, """
                    SELECT run_id, component_id, epoch, binding_version, runner_host_id, personal_host_id, binding_id,
                           name
                      FROM run_assignment_repository"""))
                .containsExactly(row(running, shop, 3L, 0L, host, null, binding, "acme/shop"));
        }
    }

    private static void run(Connection sql, UUID id, UUID company, UUID host, String status, long epoch)
            throws SQLException {
        boolean active = "RUNNING".equals(status);
        update(sql, """
                INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                 turn_number, acting_principal_id, payer_principal_id, model_access, status, epoch,
                                 runner_host_id, runner_host_name, runner_host_kind, lease_expires_at, branch,
                                 created_at, started_at, finished_at)
                VALUES (?, ?, ?, 'shop-1', ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', ?, ?, ?, 'ci-1', 'COMPANY',
                        CASE WHEN ? THEN now() + interval '1 hour' END, 'agent/shop-1', now(), now(),
                        CASE WHEN ? THEN NULL ELSE now() END)""", id, company, UUID.randomUUID(), UUID.randomUUID(),
                PERSON, PERSON, status, epoch, host, active, active);
    }

    private static void repository(Connection sql, UUID run, UUID company, int position, UUID repository,
            UUID system, UUID binding, String name, String dir) throws SQLException {
        update(sql, """
                INSERT INTO run_repository (run_id, company_id, position, repository_id, system_id, binding_id, name,
                                            dir)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)""", run, company, position, repository, system, binding, name, dir);
    }

    private static List<Object> row(Object... values) {
        List<Object> row = new ArrayList<>();
        java.util.Collections.addAll(row, values);
        return row;
    }

    private static List<List<Object>> rows(Connection sql, String query) throws SQLException {
        List<List<Object>> rows = new ArrayList<>();
        try (PreparedStatement prepared = sql.prepareStatement(query); ResultSet result = prepared.executeQuery()) {
            int columns = result.getMetaData().getColumnCount();
            while (result.next()) {
                Map<Integer, Object> values = new LinkedHashMap<>();
                for (int column = 1; column <= columns; column++) {
                    values.put(column, result.getObject(column));
                }
                rows.add(new ArrayList<>(values.values()));
            }
        }
        return rows;
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
            prepared.execute();
        }
    }
}
