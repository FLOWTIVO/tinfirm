package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * Row-level security at the database level, as the runtime role {@code tinfirm_app}: with the context of company
 * A, rows of company B do not exist, whatever the application does. And over the whole catalog (M14): every table of
 * {@code public} with a {@code company_id} column has row-level security enabled and forced, and at least one policy,
 * but for the instance-level tables listed here, which the code scopes (CONVENTIONS §7).
 */
class RowLevelSecurityTest extends IntegrationTest {

    /**
     * The instance-level tables: a user, a user's tokens and grants, a person's own runner hosts, the sessions, the
     * licence, the instance's keys, the error reports, SAML's state and the job lock. A new table joins this list only
     * on purpose, with the reason in its migration.
     */
    static final Set<String> INSTANCE_LEVEL_TABLES = Set.of("app_user", "api_token", "oauth2_authorization",
            "oauth2_authorization_consent", "oauth2_registered_client", "oauth_authorization_request",
            "personal_runner_host", "personal_runner_credential", "spring_session", "spring_session_attributes",
            "licence_key", "instance_key", "error_report", "error_reporting", "saml_assertion_seen",
            "saml_authn_request", "saml_idp_metadata", "shedlock");

    @Test
    void everyTableWithACompanyIdHasForcedRowLevelSecurityAndAPolicy() throws SQLException {
        List<String> unprotected = new ArrayList<>();
        Set<String> tables = new TreeSet<>();
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement query = owner.prepareStatement("""
                        SELECT c.relname, c.relrowsecurity, c.relforcerowsecurity,
                               (SELECT count(*) FROM pg_catalog.pg_policy p WHERE p.polrelid = c.oid) AS policies,
                               EXISTS (SELECT 1 FROM pg_catalog.pg_attribute a
                                        WHERE a.attrelid = c.oid AND a.attname = 'company_id'
                                          AND a.attnum > 0 AND NOT a.attisdropped) AS has_company_id
                          FROM pg_catalog.pg_class c
                          JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                         WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p')
                         ORDER BY c.relname""");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                String table = rows.getString("relname");
                tables.add(table);
                if (!rows.getBoolean("has_company_id") || INSTANCE_LEVEL_TABLES.contains(table)) {
                    continue;
                }
                if (!rows.getBoolean("relrowsecurity") || !rows.getBoolean("relforcerowsecurity")
                        || rows.getLong("policies") == 0) {
                    unprotected.add(table + " (enabled " + rows.getBoolean("relrowsecurity") + ", forced "
                            + rows.getBoolean("relforcerowsecurity") + ", policies " + rows.getLong("policies") + ")");
                }
            }
        }
        assertThat(unprotected).as("tables with company_id without forced row-level security and a policy").isEmpty();
        // The list stays honest: every table it names exists.
        assertThat(tables).containsAll(INSTANCE_LEVEL_TABLES);
    }

    @Test
    void everySecurityDefinerFunctionSearchesOnlyTheCatalogAndTemp() throws SQLException {
        // L20: a definer function runs as the schema owner; it names the schema's objects qualified and searches only
        // pg_catalog and pg_temp, so that nothing a caller creates can stand in for them.
        List<String> loose = new ArrayList<>();
        try (Connection owner = TestDatabase.connectAsOwner();
                PreparedStatement query = owner.prepareStatement("""
                        SELECT p.proname, coalesce(array_to_string(p.proconfig, ','), '') AS config
                          FROM pg_catalog.pg_proc p
                          JOIN pg_catalog.pg_namespace n ON n.oid = p.pronamespace
                         WHERE n.nspname = 'public' AND p.prosecdef
                         ORDER BY p.proname""");
                ResultSet rows = query.executeQuery()) {
            while (rows.next()) {
                if (!rows.getString("config").contains("search_path=pg_catalog, pg_temp")) {
                    loose.add(rows.getString("proname") + " [" + rows.getString("config") + "]");
                }
            }
        }
        assertThat(loose).as("SECURITY DEFINER functions with another search path").isEmpty();
    }

    @Test
    void companyContextHidesOtherCompaniesRows() throws SQLException {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        createSecret(loginAs(b.email("OWNER")), b.id(), "B_SECRET", "b-value");
        UUID userOfA = a.userId("OWNER");

        try (Connection app = TestDatabase.connectAsApp()) {
            app.setAutoCommit(false);
            setContext(app, a.id(), userOfA, false, false);

            assertThat(count(app, "SELECT count(*) FROM company WHERE id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE company_id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM secret WHERE company_id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM secret_version WHERE company_id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM audit_chain_head WHERE company_id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id IS NULL", null)).isZero();
            // The own company is visible.
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE company_id = ?", a.id())).isEqualTo(3);
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id = ?", a.id())).isPositive();

            // Writes to B are impossible.
            try (PreparedStatement rename = app.prepareStatement("UPDATE company SET name = 'pwned' WHERE id = ?")) {
                rename.setObject(1, b.id());
                assertThat(rename.executeUpdate()).isZero();
            }
            try (PreparedStatement insert = app.prepareStatement("""
                    INSERT INTO company_membership (company_id, user_id, role, created_at, updated_at)
                    VALUES (?, ?, 'OWNER', now(), now())""")) {
                insert.setObject(1, b.id());
                insert.setObject(2, userOfA);
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            }
            app.rollback();
        }
    }

    @Test
    void withoutContextNothingIsVisibleAndOnlyTheSystemSeesEveryCompany() throws SQLException {
        TestCompany a = companyWithRoles();
        createSecret(loginAs(a.email("OWNER")), a.id(), "A_SECRET", "a-value");

        try (Connection app = TestDatabase.connectAsApp()) {
            app.setAutoCommit(false);
            assertThat(count(app, "SELECT count(*) FROM company", null)).isZero();
            assertThat(count(app, "SELECT count(*) FROM company_membership", null)).isZero();
            assertThat(count(app, "SELECT count(*) FROM secret", null)).isZero();
            assertThat(count(app, "SELECT count(*) FROM audit_event", null)).isZero();
            app.rollback();

            // Server-side work across companies (bootstrap, the vault's rewrap): app.system.
            setContext(app, null, null, true, true);
            assertThat(count(app, "SELECT count(*) FROM company WHERE id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE company_id = ?", a.id()))
                .isEqualTo(3);
            assertThat(count(app, "SELECT count(*) FROM secret WHERE company_id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM secret_version WHERE company_id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id = ?", a.id())).isPositive();
            assertThat(count(app, "SELECT count(*) FROM audit_chain_head WHERE company_id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id IS NULL", null)).isPositive();
            app.rollback();

            // The context is transaction-local: after the transaction it is gone.
            assertThat(count(app, "SELECT count(*) FROM company WHERE id = ?", a.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM secret WHERE company_id = ?", a.id())).isZero();
            app.rollback();
        }
    }

    @Test
    void anInstanceAdminsSessionSeesCompaniesAndTheInstanceChainButNoCompanysRows() throws SQLException {
        // CONVENTIONS §7: the instance admin's flag of a browser session lists the companies and reads the instance
        // chain; a company's secrets, audit events and memberships need its context, which the access guard sets.
        TestCompany a = companyWithRoles();
        createSecret(loginAs(a.email("OWNER")), a.id(), "A_SECRET", "a-value");
        UUID instanceAdmin = UUID.randomUUID();

        try (Connection app = TestDatabase.connectAsApp()) {
            app.setAutoCommit(false);
            setContext(app, null, instanceAdmin, true, false);
            assertThat(count(app, "SELECT count(*) FROM company WHERE id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id IS NULL", null)).isPositive();
            assertThat(count(app, "SELECT count(*) FROM secret WHERE company_id = ?", a.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM secret_version WHERE company_id = ?", a.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id = ?", a.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM audit_chain_head WHERE company_id = ?", a.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE company_id = ?", a.id())).isZero();
            try (PreparedStatement insert = app.prepareStatement("""
                    INSERT INTO company_membership (company_id, user_id, role, created_at, updated_at)
                    VALUES (?, ?, 'OWNER', now(), now())""")) {
                insert.setObject(1, a.id());
                insert.setObject(2, a.userId("MEMBER"));
                assertThatThrownBy(insert::executeUpdate).isInstanceOf(SQLException.class)
                    .hasMessageContaining("row-level security");
            }
            app.rollback();

            setContext(app, null, instanceAdmin, true, false);
            try (PreparedStatement rename = app.prepareStatement("UPDATE company SET name = 'pwned' WHERE id = ?")) {
                rename.setObject(1, a.id());
                assertThat(rename.executeUpdate()).isZero();
            }
            app.rollback();

            // In the company's context, which the guard sets after its check, the company's rows are there.
            setContext(app, a.id(), instanceAdmin, true, false);
            assertThat(count(app, "SELECT count(*) FROM secret WHERE company_id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM audit_event WHERE company_id = ?", a.id())).isPositive();
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE company_id = ?", a.id()))
                .isEqualTo(3);
            app.rollback();
        }
    }

    @Test
    void usersSeeTheirOwnMembershipsAndCompaniesAcrossCompanies() throws SQLException {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        UUID memberOfA = a.userId("MEMBER");

        try (Connection app = TestDatabase.connectAsApp()) {
            app.setAutoCommit(false);
            setContext(app, null, memberOfA, false, false);
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE user_id = ?", memberOfA)).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM company WHERE id = ?", a.id())).isEqualTo(1);
            assertThat(count(app, "SELECT count(*) FROM company WHERE id = ?", b.id())).isZero();
            assertThat(count(app, "SELECT count(*) FROM company_membership WHERE company_id = ?", a.id())).isEqualTo(1);
            app.rollback();
        }
    }

    private static void setContext(Connection connection, UUID companyId, UUID userId, boolean instanceAdmin,
            boolean system) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('app.company_id', ?, true), set_config('app.user_id', ?, true),
                       set_config('app.instance_admin', ?, true), set_config('app.system', ?, true)""")) {
            statement.setString(1, companyId != null ? companyId.toString() : "");
            statement.setString(2, userId != null ? userId.toString() : "");
            statement.setString(3, Boolean.toString(instanceAdmin));
            statement.setString(4, Boolean.toString(system));
            statement.execute();
        }
    }

    private static long count(Connection connection, String sql, UUID parameter) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            if (parameter != null) {
                query.setObject(1, parameter);
            }
            try (ResultSet row = query.executeQuery()) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
