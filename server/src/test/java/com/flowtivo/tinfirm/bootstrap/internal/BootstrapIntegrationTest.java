package com.flowtivo.tinfirm.bootstrap.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.company.DeclaredCompanies;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TestDatabase;

import tools.jackson.databind.JsonNode;

class BootstrapIntegrationTest extends IntegrationTest {

    @Autowired
    BootstrapService bootstrap;

    @Autowired
    DeclaredCompanies companies;

    /** The bootstrap created the admin; the declaration of the test profile, its company with the admin as OWNER. */
    @Test
    void createdTheInstanceAdminTheDeclaredCompanyAndTheOwnerMembershipAsSystem() throws SQLException {
        ApiClient admin = admin();
        JsonNode me = admin.get("/api/me").json();
        assertThat(me.get("instanceAdmin").asBoolean()).isTrue();
        JsonNode membership = null;
        for (JsonNode candidate : me.get("memberships")) {
            if (candidate.get("companyName").asString().equals("Bootstrap Co")) {
                membership = candidate;
            }
        }
        assertThat(membership).isNotNull();
        assertThat(membership.get("role").asString()).isEqualTo("OWNER");
        String companyId = membership.get("companyId").asString();

        JsonNode company = admin.get("/api/companies/" + companyId).json();
        assertThat(company.get("name").asString()).isEqualTo("Bootstrap Co");
        assertThat(company.get("key").asString()).isEqualTo("bootstrap");

        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.createStatement().execute("SET app.system = 'true'");
            assertThat(actorOf(owner, "SELECT actor_type FROM audit_event WHERE company_id IS NULL"
                    + " AND action = 'user.created' AND target_id = ?", me.get("id").asString())).isEqualTo("SYSTEM");
            assertThat(actorOf(owner, "SELECT actor_type FROM audit_event WHERE company_id = ?::uuid"
                    + " AND action = 'company.created'", companyId)).isEqualTo("SYSTEM");
            assertThat(actorOf(owner, "SELECT actor_type FROM audit_event WHERE company_id = ?::uuid"
                    + " AND action = 'member.added' AND seq = 2", companyId)).isEqualTo("SYSTEM");
        }
    }

    @Test
    void isIdempotent() throws SQLException {
        long usersBefore = count("SELECT count(*) FROM app_user");

        assertThat(bootstrap.bootstrapIfNeeded()).isFalse();
        companies.apply();

        assertThat(count("SELECT count(*) FROM app_user")).isEqualTo(usersBefore);
        assertThat(count("SELECT count(*) FROM company WHERE name = 'Bootstrap Co'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM app_user WHERE email = 'admin@tinfirm.test'")).isEqualTo(1);
    }

    private static String actorOf(Connection connection, String sql, String parameter) throws SQLException {
        try (PreparedStatement query = connection.prepareStatement(sql)) {
            query.setString(1, parameter);
            try (ResultSet row = query.executeQuery()) {
                assertThat(row.next()).as(sql).isTrue();
                return row.getString(1);
            }
        }
    }

    private static long count(String sql) throws SQLException {
        try (Connection owner = TestDatabase.connectAsOwner()) {
            owner.createStatement().execute("SET app.system = 'true'");
            try (ResultSet row = owner.createStatement().executeQuery(sql)) {
                row.next();
                return row.getLong(1);
            }
        }
    }
}
