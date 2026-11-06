package com.flowtivo.tinfirm.principal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * What the database itself refuses about principals (the package 1 contract, §3.1 and §3.2), as the runtime role: a
 * member's principal that has not left needs its membership, and there is one per member; a membership ends only after
 * its principal left; only service identities have users, and never leave; another company's principals are invisible.
 */
class PrincipalConstraintTest extends IntegrationTest {

    private static final String INSERT_MEMBER = """
            INSERT INTO principal (id, company_id, kind, user_id, status, created_at, updated_at)
            VALUES (?, ?, 'MEMBER', ?, ?, now(), now())""";

    @Test
    void aMembersPrincipalFollowsTheMembership() throws SQLException {
        TestCompany company = companyWithRoles();
        UUID stranger = createUser(admin(), uniqueEmail("stranger"), false);
        UUID member = company.userId("MEMBER");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.refused(INSERT_MEMBER, UUID.randomUUID(), company.id(), stranger, "ACTIVE"))
                .isEqualTo("principal_membership_ck");
            assertThat(sql.refused(INSERT_MEMBER, UUID.randomUUID(), company.id(), member, "ACTIVE"))
                .isEqualTo("principal_member_uq");
            // Any number that left, as for a member who rejoined.
            sql.accepted(INSERT_MEMBER, UUID.randomUUID(), company.id(), stranger, "LEFT");
            sql.accepted(INSERT_MEMBER, UUID.randomUUID(), company.id(), member, "LEFT");

            assertThat(sql.refused("DELETE FROM company_membership WHERE user_id = ?", member))
                .isEqualTo("company_membership_principal_ck");
            sql.accepted("UPDATE principal SET status = 'LEFT' WHERE user_id = ? AND status = 'ACTIVE'", member);
            sql.accepted("DELETE FROM company_membership WHERE user_id = ?", member);
            assertThat(sql.refused("UPDATE principal SET status = 'ACTIVE' WHERE user_id = ?", member))
                .isEqualTo("principal_membership_ck");
        }
    }

    @Test
    void onlyServiceIdentitiesHaveUsersAndNoneLeaves() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID groupId = UUID.fromString(owner.post(company.path("/groups"), Map.of("name", "Ops", "memberIds",
                List.of())).json().get("id").asString());
        ApiResponse bot = owner.post(company.path("/service-identities"), Map.of("name", "Bot"));
        UUID botId = UUID.fromString(bot.json().get("id").asString());
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID memberPrincipal = (UUID) sql.column("SELECT id FROM principal WHERE user_id = ? AND status = 'ACTIVE'",
                    company.userId("MEMBER")).getFirst();
            String insertUser = """
                    INSERT INTO service_identity_user (company_id, principal_id, group_id) VALUES (?, ?, ?)""";
            assertThat(sql.refused(insertUser, company.id(), memberPrincipal, groupId))
                .isEqualTo("service_identity_user_principal_fk");
            assertThat(sql.refused("""
                    INSERT INTO service_identity_user (company_id, principal_id, principal_kind, group_id)
                    VALUES (?, ?, 'MEMBER', ?)""", company.id(), memberPrincipal, groupId))
                .isEqualTo("service_identity_user_service_ck");
            sql.accepted(insertUser, company.id(), botId, groupId);

            assertThat(sql.refused("UPDATE principal SET status = 'LEFT' WHERE id = ?", botId))
                .isEqualTo("principal_service_ck");
            assertThat(sql.refused("""
                    INSERT INTO principal (id, company_id, kind, user_id, name, status, created_at, updated_at)
                    VALUES (?, ?, 'SERVICE', ?, 'Twin', 'ACTIVE', ?, ?)""", UUID.randomUUID(), company.id(),
                    company.userId("MEMBER"), OffsetDateTime.now(), OffsetDateTime.now()))
                .isEqualTo("principal_service_ck");
            assertThat(sql.refused("""
                    INSERT INTO principal (id, company_id, kind, name, status, created_at, updated_at)
                    VALUES (?, ?, 'SERVICE', 'BOT', 'ACTIVE', now(), now())""", UUID.randomUUID(), company.id()))
                .isEqualTo("principal_service_name_uq");
        }
    }

    @Test
    void anotherCompanysPrincipalsAreInvisible() throws SQLException {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        loginAs(b.email("OWNER")).post(b.path("/service-identities"), Map.of("name", "Bot"));
        try (TenantSql sql = TenantSql.in(a.id())) {
            assertThat(sql.count("SELECT count(*) FROM principal WHERE company_id = ?", b.id())).isZero();
            assertThat(sql.count("SELECT count(*) FROM principal WHERE company_id = ?", a.id())).isPositive();
            assertThat(sql.update("UPDATE principal SET status = 'SUSPENDED' WHERE company_id = ?", b.id())).isZero();
            assertThat(sql.refused(INSERT_MEMBER, UUID.randomUUID(), b.id(), b.userId("MEMBER"), "LEFT"))
                .contains("row-level security");
            sql.context(null);
            assertThat(sql.count("SELECT count(*) FROM principal")).isZero();
            assertThat(sql.count("SELECT count(*) FROM service_identity_user")).isZero();
        }
    }
}
