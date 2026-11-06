package com.flowtivo.tinfirm.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * What the database itself refuses about agents and settings (the package 3 contract, §4 and §9), as the runtime role:
 * I1 (no credential and no acting principal on an agent's tables), I2 (only company systems of a kind with tools or
 * repositories; tools and repositories only of systems the agent lists), I3 (a mode is ALLOW or ASK), I4 (settings
 * narrow), I5 (one settings row per person per agent), and another company's rows.
 */
class AgentConstraintTest extends IntegrationTest {

    private static final String INSERT_AGENT = """
            INSERT INTO agent (id, company_id, name, handle, runtimes, owner_principal_id, created_at, updated_at)
            VALUES (?, ?, ?, ?, CAST(? AS varchar(16)[]), ?, now(), now())""";

    private static final String INSERT_SYSTEM = "INSERT INTO agent_system (company_id, agent_id, system_id) VALUES (?, ?, ?)";

    private static final String INSERT_TOOL = """
            INSERT INTO agent_tool (company_id, agent_id, system_id, tool, mode, chosen_for_effect)
            VALUES (?, ?, ?, ?, ?, 'READ')""";

    private static final String INSERT_REPOSITORY = """
            INSERT INTO agent_repository (company_id, agent_id, system_id, repository_id, access)
            VALUES (?, ?, ?, ?, 'READ')""";

    private static final String INSERT_SETTINGS = """
            INSERT INTO agent_settings (id, company_id, agent_id, principal_id, accepted_version, created_at, updated_at)
            VALUES (?, ?, ?, ?, 1, now(), now())""";

    private static final String INSERT_SETTINGS_SYSTEM = """
            INSERT INTO agent_settings_system (company_id, settings_id, system_id, state) VALUES (?, ?, ?, ?)""";

    private static final String INSERT_SETTINGS_TOOL = """
            INSERT INTO agent_settings_tool (company_id, settings_id, system_id, tool, mode, chosen_for_effect)
            VALUES (?, ?, ?, ?, ?, 'READ')""";

    @Test
    void anAgentsTablesHoldNoCredentialAndNoActingPrincipal() throws SQLException {
        TestCompany company = companyWithRoles();
        try (TenantSql sql = TenantSql.in(company.id())) {
            // I1: the only principals an agent's tables name are the owner who edits it (and, for the checklist of a
            // member who left, the owner it passed to the admins from: package 6) and the members it is shared with;
            // no secret, account, key, token or credential, and no principal the agent acts as.
            List<Object> columns = sql.column("""
                    SELECT table_name || '.' || column_name FROM information_schema.columns
                     WHERE table_schema = current_schema()
                       AND table_name IN ('agent', 'agent_version', 'agent_system', 'agent_tool', 'agent_repository',
                                          'agent_host', 'agent_share', 'agent_member_share')
                       AND (column_name ~ '(secret|account|credential|token|key|password|principal|grant)')""");
            assertThat(columns).containsExactlyInAnyOrder("agent.owner_principal_id",
                    "agent.former_owner_principal_id", "agent_member_share.principal_id",
                    "agent_member_share.principal_kind");
        }
    }

    @Test
    void anAgentListsOnlyCompanySystemsAndToolsAndRepositoriesOfThem() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID git = createSystem(owner, company, unique("Git"), "GIT", null, false);
        ApiResponse repository = owner.post(company.path("/systems/" + git + "/repositories"),
                Map.of("url", "https://git.example.test/acme/shop.git"));
        assertThat(repository.status()).as("%s", repository).isEqualTo(201);
        UUID repositoryId = UUID.fromString(repository.json().get("id").asString());
        UUID personal = createSystem(member, company, unique("Notes"), "MCP", McpStub.newInstance().url(), true);
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID agent = UUID.randomUUID();
            sql.accepted(INSERT_AGENT, agent, company.id(), "Analyst", "analyst", new String[] { "CLAUDE_CODE" },
                    memberPrincipal);
            // Runtimes: known, without duplicates, at least one; a handle's form; a live name once ignoring case.
            assertThat(sql.refused(INSERT_AGENT, UUID.randomUUID(), company.id(), "Two", "two",
                    new String[] { "CODEX", "CODEX" }, memberPrincipal)).isEqualTo("agent_runtimes_ck");
            assertThat(sql.refused(INSERT_AGENT, UUID.randomUUID(), company.id(), "None", "none", new String[0],
                    memberPrincipal)).isEqualTo("agent_runtimes_ck");
            assertThat(sql.refused(INSERT_AGENT, UUID.randomUUID(), company.id(), "Bad", "-bad",
                    new String[] { "PI" }, memberPrincipal)).isEqualTo("agent_handle_ck");
            assertThat(sql.refused(INSERT_AGENT, UUID.randomUUID(), company.id(), "ANALYST", "other",
                    new String[] { "PI" }, memberPrincipal)).isEqualTo("agent_name_uq");
            assertThat(sql.refused(INSERT_AGENT, UUID.randomUUID(), company.id(), "Other", "analyst",
                    new String[] { "PI" }, memberPrincipal)).isEqualTo("agent_handle_uq");

            // I2: a personal MCP server is not an agent's; a tool or a repository only of a system the agent lists.
            assertThat(sql.refused(INSERT_SYSTEM, company.id(), agent, personal)).isEqualTo("agent_system_personal_ck");
            assertThat(sql.refused(INSERT_TOOL, company.id(), agent, github, "get", "ALLOW"))
                .isEqualTo("agent_tool_system_fk");
            assertThat(sql.refused(INSERT_REPOSITORY, company.id(), agent, git, repositoryId))
                .isEqualTo("agent_repository_system_fk");
            sql.accepted(INSERT_SYSTEM, company.id(), agent, github);
            // A repository is listed only with its own system.
            assertThat(sql.refused(INSERT_REPOSITORY, company.id(), agent, github, repositoryId))
                .isEqualTo("agent_repository_repository_fk");
            sql.accepted(INSERT_SYSTEM, company.id(), agent, git);
            sql.accepted(INSERT_REPOSITORY, company.id(), agent, git, repositoryId);
            // I3: ALLOW or ASK; there is no DENY.
            assertThat(sql.refused(INSERT_TOOL, company.id(), agent, github, "get", "DENY"))
                .isEqualTo("agent_tool_mode_ck");
            sql.accepted(INSERT_TOOL, company.id(), agent, github, "get", "ALLOW");
            // A version is written once.
            sql.accepted("""
                    INSERT INTO agent_version (company_id, agent_id, version, definition, widens, created_at)
                    VALUES (?, ?, 1, '{}'::jsonb, false, now())""", company.id(), agent);
            assertThat(sql.refused("""
                    INSERT INTO agent_version (company_id, agent_id, version, definition, widens, created_at)
                    VALUES (?, ?, 1, '{}'::jsonb, true, now())""", company.id(), agent))
                .isEqualTo("agent_version_first_ck");
            assertThat(sql.refused("UPDATE agent_version SET widens = true WHERE agent_id = ?", agent))
                .contains("permission denied");
        }
    }

    @Test
    void settingsNarrowNeverWidenAndArePerPersonPerAgent() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        UUID github = createSystem(owner, company, unique("GitHub"), "GITHUB", "https://api.github.com", false);
        UUID jira = createSystem(owner, company, unique("Jira"), "JIRA_DC", JiraStub.newInstance().baseUrl(), false);
        UUID companyMcp = createSystem(owner, company, unique("CRM"), "MCP", McpStub.newInstance().url(), false);
        UUID own = createSystem(member, company, unique("Notes"), "MCP", McpStub.newInstance().url(), true);
        UUID others = createSystem(admin, company, unique("Admin notes"), "MCP", McpStub.newInstance().url(), true);
        UUID memberPrincipal = memberPrincipal(company, company.userId("MEMBER"));
        try (TenantSql sql = TenantSql.in(company.id())) {
            UUID agent = UUID.randomUUID();
            sql.accepted(INSERT_AGENT, agent, company.id(), "Analyst", "analyst", new String[] { "CLAUDE_CODE" },
                    memberPrincipal);
            sql.accepted(INSERT_SYSTEM, company.id(), agent, github);
            sql.accepted(INSERT_TOOL, company.id(), agent, github, "get", "ALLOW");
            UUID settings = UUID.randomUUID();
            sql.accepted(INSERT_SETTINGS, settings, company.id(), agent, memberPrincipal);
            // I5: one row per person per agent.
            assertThat(sql.refused(INSERT_SETTINGS, UUID.randomUUID(), company.id(), agent, memberPrincipal))
                .isEqualTo("agent_settings_person_uq");

            // I4: OFF only for a system the agent lists; ADDED only for the person's own MCP server.
            assertThat(sql.refused(INSERT_SETTINGS_SYSTEM, company.id(), settings, jira, "OFF"))
                .isEqualTo("agent_settings_widen_ck");
            assertThat(sql.refused(INSERT_SETTINGS_SYSTEM, company.id(), settings, companyMcp, "ADDED"))
                .isEqualTo("agent_settings_widen_ck");
            assertThat(sql.refused(INSERT_SETTINGS_SYSTEM, company.id(), settings, others, "ADDED"))
                .isEqualTo("agent_settings_widen_ck");
            assertThat(sql.refused(INSERT_SETTINGS_SYSTEM, company.id(), settings, github, "ADDED"))
                .isEqualTo("agent_settings_widen_ck");
            sql.accepted(INSERT_SETTINGS_SYSTEM, company.id(), settings, github, "OFF");
            sql.accepted(INSERT_SETTINGS_SYSTEM, company.id(), settings, own, "ADDED");
            // On the agent's systems ASK or OFF, never ALLOW; nothing on a system that is neither.
            assertThat(sql.refused(INSERT_SETTINGS_TOOL, company.id(), settings, github, "get", "ALLOW"))
                .isEqualTo("agent_settings_widen_ck");
            assertThat(sql.refused(INSERT_SETTINGS_TOOL, company.id(), settings, jira, "get", "ASK"))
                .isEqualTo("agent_settings_widen_ck");
            sql.accepted(INSERT_SETTINGS_TOOL, company.id(), settings, github, "get", "ASK");
            sql.accepted(INSERT_SETTINGS_TOOL, company.id(), settings, github, "search", "OFF");
            // On one's own server ALLOW or ASK.
            sql.accepted(INSERT_SETTINGS_TOOL, company.id(), settings, own, "notes", "ALLOW");
            assertThat(sql.refused(INSERT_SETTINGS_TOOL, company.id(), settings, own, "other", "OFF"))
                .isEqualTo("agent_settings_widen_ck");
            // A subscription only with SUBSCRIPTION.
            assertThat(sql.refused("UPDATE agent_settings SET subscription_label = 'claude-max' WHERE id = ?",
                    settings)).isEqualTo("agent_settings_subscription_ck");
            // Only a member's principal has settings.
            UUID bot = createServiceIdentityViaSql(sql, company);
            assertThat(sql.refused(INSERT_SETTINGS, UUID.randomUUID(), company.id(), agent, bot))
                .isEqualTo("agent_settings_principal_fk");
        }
    }

    @Test
    void anotherCompanysAgentsAreInvisibleAndUnreachable() throws SQLException {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        ApiClient ownerOfB = loginAs(b.email("OWNER"));
        ApiResponse created = ownerOfB.post(b.path("/agents"), Map.of("name", "B only", "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID agentOfB = UUID.fromString(created.json().get("id").asString());
        UUID systemOfB = createSystem(ownerOfB, b, unique("Git"), "GIT", null, false);
        UUID principalOfA = memberPrincipal(a, a.userId("MEMBER"));
        try (TenantSql sql = TenantSql.in(a.id())) {
            assertThat(sql.count("SELECT count(*) FROM agent WHERE id = ?", agentOfB)).isZero();
            assertThat(sql.count("SELECT count(*) FROM agent_version WHERE agent_id = ?", agentOfB)).isZero();
            assertThat(sql.update("UPDATE agent SET name = 'pwned' WHERE id = ?", agentOfB)).isZero();
            UUID agent = UUID.randomUUID();
            sql.accepted(INSERT_AGENT, agent, a.id(), "A agent", "a-agent", new String[] { "CODEX" }, principalOfA);
            assertThat(sql.refused(INSERT_SYSTEM, a.id(), agent, systemOfB)).isEqualTo("agent_system_system_fk");
            assertThat(sql.refused(INSERT_SETTINGS, UUID.randomUUID(), a.id(), agentOfB, principalOfA))
                .isEqualTo("agent_settings_agent_fk");
            assertThat(sql.refused(INSERT_SYSTEM, b.id(), agentOfB, systemOfB)).contains("row-level security");
        }
    }

    private static UUID createServiceIdentityViaSql(TenantSql sql, TestCompany company) throws SQLException {
        UUID id = UUID.randomUUID();
        sql.accepted("""
                INSERT INTO principal (id, company_id, kind, name, status, created_at, updated_at)
                VALUES (?, ?, 'SERVICE', ?, 'ACTIVE', now(), now())""", id, company.id(), unique("Bot"));
        return id;
    }
}
