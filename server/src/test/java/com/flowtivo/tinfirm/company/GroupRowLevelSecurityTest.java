package com.flowtivo.tinfirm.company;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

/**
 * The tenant tables of groups (ADR-0049 §2) as the runtime role sees them: groups and their members are visible in
 * their company's context only, and their composite foreign keys refuse a row that points at another company's group
 * or member.
 */
class GroupRowLevelSecurityTest extends IntegrationTest {

    private static final List<String> TENANT_TABLES = List.of("user_group", "user_group_member");

    @Test
    void theCompanyContextHidesTheGroupsOfOtherCompanies() throws SQLException {
        Fixture a = fixture();
        Fixture b = fixture();
        try (Connection app = TestDatabase.connectAsApp()) {
            app.setAutoCommit(false);
            setContext(app, a.companyId());
            for (String table : TENANT_TABLES) {
                assertThat(count(app, "SELECT count(*) FROM " + table + " WHERE company_id = ?", b.companyId()))
                    .as("rows of company B in %s", table)
                    .isZero();
                assertThat(count(app, "SELECT count(*) FROM " + table + " WHERE company_id = ?", a.companyId()))
                    .as("rows of company A in %s", table)
                    .isPositive();
            }
            try (PreparedStatement steal = app.prepareStatement("UPDATE user_group SET name = 'pwned' WHERE id = ?")) {
                steal.setObject(1, b.groupId());
                assertThat(steal.executeUpdate()).isZero();
            }
            app.rollback();

            // Rows that point at another company's group or member are refused.
            refused(app, a, "INSERT INTO user_group_member (company_id, group_id, user_id) VALUES (?, ?, ?)",
                    b.groupId(), a.memberId());
            refused(app, a, "INSERT INTO user_group_member (company_id, group_id, user_id) VALUES (?, ?, ?)",
                    a.groupId(), b.memberId());

            // Without a company nothing is visible.
            setContext(app, null);
            for (String table : TENANT_TABLES) {
                assertThat(count(app, "SELECT count(*) FROM " + table, null)).as(table).isZero();
            }
            app.rollback();
        }
    }

    /** A company with a group of one member. */
    private record Fixture(UUID companyId, UUID groupId, UUID memberId) {
    }

    private Fixture fixture() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID member = company.userId("MEMBER");
        ApiResponse group = owner.post(company.path("/groups"), Map.of("name", "Finance", "memberIds",
                List.of(member.toString())));
        assertThat(group.status()).as("group: %s", group).isEqualTo(201);
        return new Fixture(company.id(), UUID.fromString(group.json().get("id").asString()), member);
    }

    /**
     * The insert, in company A's context with A's id first and then the given values, fails; the transaction is rolled
     * back after it.
     */
    private static void refused(Connection app, Fixture a, String sql, UUID... values) throws SQLException {
        setContext(app, a.companyId());
        assertThatThrownBy(() -> {
            try (PreparedStatement insert = app.prepareStatement(sql)) {
                insert.setObject(1, a.companyId());
                for (int index = 0; index < values.length; index++) {
                    insert.setObject(index + 2, values[index]);
                }
                insert.executeUpdate();
            }
        }).isInstanceOf(SQLException.class);
        app.rollback();
    }

    private static void setContext(Connection connection, @Nullable UUID companyId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
                SELECT set_config('app.company_id', ?, true), set_config('app.user_id', '', true),
                       set_config('app.instance_admin', 'false', true)""")) {
            statement.setString(1, companyId != null ? companyId.toString() : "");
            statement.execute();
        }
    }

    private static long count(Connection connection, String sql, @Nullable UUID parameter) throws SQLException {
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
