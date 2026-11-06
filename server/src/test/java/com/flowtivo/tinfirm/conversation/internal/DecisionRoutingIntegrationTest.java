package com.flowtivo.tinfirm.conversation.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.tenant.TenantScope;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

/**
 * ADR-0077, decision 5: an open decision whose only candidates became Viewers goes to the company's Owners and Admins,
 * and back once a candidate holds a seat again: when a role changes, when a trigger's team or author changes, and, for
 * the decisions stuck before this, by V055.
 */
class DecisionRoutingIntegrationTest extends IntegrationTest {

    @Autowired
    TriggerConversations triggerConversations;

    @Autowired
    TenantScope tenantScope;

    @Autowired
    PlatformTransactionManager transactionManager;

    /** The decision's approver: its principal, its group and whether the Owners and Admins decide it. */
    private static Map<String, Object> approver(UUID companyId, UUID decisionId) throws Exception {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.rows("SELECT approver_principal_id, approver_group_id, approver_admins FROM decision WHERE id = ?",
                    decisionId).getFirst();
        }
    }

    private UUID personalQuestion(TestCompany company, UUID person) {
        UUID conversation = UUID.randomUUID();
        TestConversations.bare(company.id(), conversation, unique("routed").toLowerCase(), TestRuns.agent(company.id()),
                person, "CLAUDE_CODE");
        return TestConversations.question(company.id(), conversation, person, "Which project?");
    }

    @Test
    void aDecisionGoesToTheOwnersAndAdminsWhileItsPersonIsAViewer() throws Exception {
        TestCompany company = companyWithRoles();
        UUID member = company.userId("MEMBER");
        UUID person = memberPrincipal(company, member);
        UUID decision = personalQuestion(company, person);

        role(company, member, "VIEWER");
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", true)
            .containsEntry("approver_principal_id", null);
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("""
                    SELECT details->>'toAdmins' FROM audit_event
                     WHERE action = 'decision.approver_changed' AND target_id = ?""", decision.toString()))
                .containsExactly("true");
        }

        role(company, member, "MEMBER");
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", false)
            .containsEntry("approver_principal_id", person);
    }

    @Test
    void anApprovalMovedToATeamOfViewersGoesToTheOwnersAndAdmins() throws Exception {
        TestCompany company = companyWithRoles();
        UUID author = memberPrincipal(company, company.userId("MEMBER"));
        UUID agent = TestRuns.agent(company.id());
        UUID trigger = TestTriggers.schedule(company.id(), agent, author, author);
        UUID conversation = UUID.randomUUID();
        TestTriggers.conversation(company.id(), conversation, unique("triggered").toLowerCase(), trigger, agent, author,
                author);
        UUID decision = TestConversations.hostApproval(company.id(), conversation, author, "api.example.com");
        String viewer = uniqueEmail("viewer");
        UUID viewerId = createUser(admin(), viewer, false);
        addMember(loginAs(company.email("OWNER")), company.id(), viewer, "VIEWER");
        UUID viewers = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("INSERT INTO user_group (id, company_id, name, created_at, updated_at) VALUES (?, ?, ?, now(), "
                    + "now())", viewers, company.id(), unique("Viewers"));
            sql.update("INSERT INTO user_group_member (company_id, group_id, user_id) VALUES (?, ?, ?)", company.id(),
                    viewers, viewerId);
            sql.commit();
        }

        approversChanged(company.id(), trigger, viewers, author);
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", true)
            .containsEntry("approver_group_id", null);

        // Back to its author, who holds a seat: approver_admins is cleared only by a move that calls for it.
        approversChanged(company.id(), trigger, null, author);
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", false)
            .containsEntry("approver_principal_id", author);
    }

    /** A reassigned trigger's approval follows its new author, not the one the conversation started with. */
    @Test
    void anApprovalFollowsTheTriggersAuthorNowWhenRolesChange() throws Exception {
        TestCompany company = companyWithRoles();
        UUID first = memberPrincipal(company, company.userId("MEMBER"));
        UUID agent = TestRuns.agent(company.id());
        UUID trigger = TestTriggers.schedule(company.id(), agent, first, first);
        UUID conversation = UUID.randomUUID();
        TestTriggers.conversation(company.id(), conversation, unique("reassigned").toLowerCase(), trigger, agent, first,
                first);
        UUID decision = TestConversations.hostApproval(company.id(), conversation, first, "api.example.com");
        UUID adminUser = company.userId("ADMIN");
        UUID second = memberPrincipal(company, adminUser);
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("UPDATE trigger SET author_principal_id = ?, payer_principal_id = ? WHERE id = ?", second, second,
                    trigger);
            sql.commit();
        }
        approversChanged(company.id(), trigger, null, second);
        assertThat(approver(company.id(), decision)).containsEntry("approver_principal_id", second);

        role(company, adminUser, "VIEWER");
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", true);
        role(company, adminUser, "ADMIN");
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", false)
            .containsEntry("approver_principal_id", second);
    }

    @Test
    void aDecisionStuckWithAViewerBeforeV055GoesToTheOwnersAndAdmins() throws Exception {
        TestCompany company = companyWithRoles();
        UUID member = company.userId("MEMBER");
        UUID decision = personalQuestion(company, memberPrincipal(company, member));
        // Made a Viewer as before the routing: no event, the decision keeps its person.
        try (Connection owner = TestDatabase.connectAsOwner(); Statement statement = owner.createStatement()) {
            statement.execute("SELECT set_config('app.company_id', '" + company.id() + "', false)");
            assertThat(statement.executeUpdate("UPDATE company_membership SET role = 'VIEWER' WHERE company_id = '"
                    + company.id() + "' AND user_id = '" + member + "'")).isOne();
            statement.execute("SELECT set_config('app.company_id', '', false)");
            assertThat(approver(company.id(), decision)).containsEntry("approver_admins", false);

            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V055__decisions_of_viewers.sql"),
                    StandardCharsets.UTF_8));
        }

        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", true)
            .containsEntry("approver_principal_id", null);

        // And back: a seat again, before the routing, is reconciled the other way.
        try (Connection owner = TestDatabase.connectAsOwner(); Statement statement = owner.createStatement()) {
            statement.execute("SELECT set_config('app.company_id', '" + company.id() + "', false)");
            statement.executeUpdate("UPDATE company_membership SET role = 'MEMBER' WHERE company_id = '" + company.id()
                    + "' AND user_id = '" + member + "'");
            statement.execute("SELECT set_config('app.company_id', '', false)");
            statement.execute(Files.readString(Path.of("src/main/resources/db/migration/V055__decisions_of_viewers.sql"),
                    StandardCharsets.UTF_8));
        }
        assertThat(approver(company.id(), decision)).containsEntry("approver_admins", false)
            .containsEntry("approver_principal_id", memberPrincipal(company, member));
    }

    private void approversChanged(UUID companyId, UUID triggerId, @Nullable UUID team, UUID author) {
        SystemContext.call(() -> new TransactionTemplate(transactionManager).execute(status -> {
            tenantScope.enterCompany(companyId);
            triggerConversations.approversChanged(companyId, triggerId, team, author);
            return null;
        }));
    }

    /** The company's OWNER changes the member's role through the API. */
    private void role(TestCompany company, UUID userId, String role) {
        assertThat(loginAs(company.email("OWNER")).patch(company.path("/members/" + userId), Map.of("role", role))
            .status()).isEqualTo(200);
    }
}
