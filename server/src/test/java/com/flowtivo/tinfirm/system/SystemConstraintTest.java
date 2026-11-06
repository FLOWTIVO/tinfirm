package com.flowtivo.tinfirm.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * What the database itself refuses about systems (the package 1 contract, §3.3 to §3.5, §9), as the runtime role: I6
 * (a personal system is an MCP server, owned by a member's principal), the kind's base URL, a handle's form, a tool's
 * confirmation, and another company's rows.
 */
class SystemConstraintTest extends IntegrationTest {

    private static final String INSERT = """
            INSERT INTO external_system (id, company_id, name, handle, kind, base_url, owner_principal_id, created_at,
                                         updated_at)
            VALUES (?, ?, ?, ?, ?, ?, ?, now(), now())""";

    @Test
    void aPersonalSystemIsAMembersMcpServer() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID bot = UUID.fromString(owner.post(company.path("/service-identities"), Map.of("name", "Bot")).json()
            .get("id").asString());
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID member = (UUID) sql.column("SELECT id FROM principal WHERE user_id = ? AND status = 'ACTIVE'",
                    company.userId("MEMBER")).getFirst();
            // I6: a personal system is an MCP server.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "Mine", "mine", "JIRA_DC",
                    "https://jira.example.test", member)).isEqualTo("external_system_personal_ck");
            // Owned by a member's principal, never a service identity's.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "Bots", "bots", "MCP",
                    "https://mcp.example.test", bot)).isEqualTo("external_system_owner_fk");
            sql.accepted(INSERT, UUID.randomUUID(), company.id(), "Mine", "mine", "MCP", "https://mcp.example.test",
                    member);

            // A base URL for every kind but GIT and WEBHOOK; a handle's form; one name and one handle per company.
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "Git", "git", "GIT",
                    "https://git.example.test", null)).isEqualTo("external_system_base_url_ck");
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "Jira", "jira", "JIRA_DC", null, null))
                .isEqualTo("external_system_base_url_ck");
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "Bad", "Bad-Handle", "MCP",
                    "https://mcp.example.test", null)).isEqualTo("external_system_handle_ck");
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "MINE", "other", "MCP",
                    "https://mcp.example.test", null)).isEqualTo("external_system_name_uq");
            assertThat(sql.refused(INSERT, UUID.randomUUID(), company.id(), "Other", "mine", "MCP",
                    "https://mcp.example.test", null)).isEqualTo("external_system_handle_uq");
        }
    }

    @Test
    void aToolIsConfirmedWithAnEffectAndAnotherCompanysSystemsAreInvisible() throws SQLException {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        UUID systemOfB = createSystem(ownerOfB, b, "Code", "GIT", null, false);
        UUID systemOfA = createSystem(loginAs(a.email("OWNER")), a, "Code", "GIT", null, false);
        try (TenantSql sql = TenantSql.in(a.id())) {
            String insertTool = """
                    INSERT INTO system_tool (company_id, system_id, name, input_schema, effect, confirmed_at,
                                             created_at, updated_at)
                    VALUES (?, ?, ?, '{}'::jsonb, ?, ?, now(), now())""";
            assertThat(sql.refused(insertTool, a.id(), systemOfA, "search", "READ", null))
                .isEqualTo("system_tool_confirmed_ck");
            assertThat(sql.refused(insertTool, a.id(), systemOfA, "bad name", "UNKNOWN", null))
                .isEqualTo("system_tool_name_ck");
            assertThat(sql.refused(insertTool, a.id(), systemOfB, "search", "UNKNOWN", null))
                .isEqualTo("system_tool_system_fk");

            assertThat(sql.count("SELECT count(*) FROM external_system WHERE id = ?", systemOfB)).isZero();
            assertThat(sql.update("UPDATE external_system SET name = 'pwned' WHERE id = ?", systemOfB)).isZero();
            sql.context(null);
            for (String table : new String[] { "external_system", "system_repository", "system_tool",
                    "system_setting" }) {
                assertThat(sql.count("SELECT count(*) FROM " + table)).as(table).isZero();
            }
        }
    }
}
