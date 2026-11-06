package com.flowtivo.tinfirm.shared.tenant;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * M14 (CONVENTIONS §7, ADR-0009): the server does not become ready on a database role that could bypass row-level
 * security. Against the test PostgreSQL, with roles made for the test: one with {@code BYPASSRLS}, a superuser, a
 * member of {@code tinfirm_owner}, and a role that owns a table in {@code public}; the runtime role of the tests
 * passes.
 */
class DatabaseRoleCheckTest {

    private static final String PASSWORD = "role-check-test-password";

    @Test
    void theRuntimeRolePasses() {
        DatabaseRoleCheck check = new DatabaseRoleCheck(
                new DriverManagerDataSource(TestDatabase.jdbcUrl(), TestDatabase.APP, TestDatabase.APP_PASSWORD));

        assertThat(check.check()).isEmpty();
        assertThat(check.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void aRoleWithBypassRlsRefusesReadiness() throws SQLException {
        withRole("BYPASSRLS", role -> {
            DatabaseRoleCheck check = checkAs(role);
            assertThat(check.check()).contains("has BYPASSRLS");
            assertThat(check.health().getStatus()).isEqualTo(Status.DOWN);
        });
    }

    @Test
    void aSuperuserRefusesReadiness() throws SQLException {
        withRole("SUPERUSER", role -> assertThat(checkAs(role).check()).contains("is a superuser"));
    }

    @Test
    void aMemberOfTheOwnerRoleRefusesReadiness() throws SQLException {
        withRole("IN ROLE " + DatabaseRoleCheck.OWNER_ROLE,
                role -> assertThat(checkAs(role).check()).contains("is a member of " + DatabaseRoleCheck.OWNER_ROLE));
    }

    @Test
    void aRoleThatOwnsARelationRefusesReadiness() throws SQLException {
        withRole("", role -> {
            try (Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                    Statement ddl = superuser.createStatement()) {
                ddl.execute("CREATE TABLE public." + role + "_owned (id int)"); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ddl.execute("ALTER TABLE public." + role + "_owned OWNER TO " + role); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            }
            catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            try {
                assertThat(checkAs(role).check()).contains("owns a relation in the schema public");
            }
            finally {
                try (Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                        Statement ddl = superuser.createStatement()) {
                    ddl.execute("DROP TABLE public." + role + "_owned"); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                }
                catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
            }
        });
    }

    private static DatabaseRoleCheck checkAs(String role) {
        return new DatabaseRoleCheck(new DriverManagerDataSource(TestDatabase.jdbcUrl(), role, PASSWORD));
    }

    /** A login role with the attributes, named for this test, dropped after it. */
    private static void withRole(String attributes, Consumer<String> test) throws SQLException {
        String role = "role_check_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            .toLowerCase(Locale.ROOT);
        try (Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                Statement ddl = superuser.createStatement()) {
            // A role name is an identifier and cannot be a bind parameter; it is made here from hex digits.
            ddl.execute("CREATE ROLE " + role + " LOGIN PASSWORD '" + PASSWORD + "' " + attributes); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            ddl.execute("GRANT CONNECT ON DATABASE " + TestDatabase.databaseName() + " TO " + role); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            ddl.execute("GRANT USAGE ON SCHEMA public TO " + role); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
        }
        try {
            test.accept(role);
        }
        finally {
            try (Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                    Statement ddl = superuser.createStatement()) {
                ddl.execute("REVOKE ALL ON SCHEMA public FROM " + role); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ddl.execute("REVOKE ALL ON DATABASE " + TestDatabase.databaseName() + " FROM " + role); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
                ddl.execute("DROP ROLE " + role); // nosemgrep: java.lang.security.audit.formatted-sql-string.formatted-sql-string
            }
        }
    }
}
