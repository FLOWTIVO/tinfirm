package com.flowtivo.tinfirm.account;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * A member leaves (the package 1 contract, I8 and §9): in the transaction that ends the membership, their principal
 * becomes {@code LEFT}, and their accounts, the secrets they own in the company and their personal systems are
 * deleted, each audited; a service identity's accounts they connected stay. A member who rejoins starts with nothing.
 */
class MemberLeavesTest extends IntegrationTest {

    @Test
    void aLeavingMembersAccountsOwnedSecretsAndPersonalSystemsGo() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        UUID adminId = company.userId("ADMIN");
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraSystem = createSystem(owner, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID personal = createSystem(admin, company, "Notes", "MCP", McpStub.newInstance().serves(List.of()).url(),
                true);
        UUID own = connectToken(admin, company.path("/me/accounts/" + jiraSystem), jira, "test-leaver-token",
                "leaver");
        assertThat(admin.put(company.path("/me/accounts/" + personal + "/token"), Map.of("token", "test-notes"))
            .status()).isEqualTo(200);
        // The admin also connected a service identity's account: it is the company's, and stays.
        UUID bot = createServiceIdentity(admin, company, "Bot");
        UUID botAccount = connectServiceToken(admin, company, bot, jiraSystem, jira, "test-bot-token-leave",
                "bot-leave");
        UUID principal = memberPrincipal(company, adminId);

        assertThat(owner.delete(company.path("/members/" + adminId)).status()).isEqualTo(204);

        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT status FROM principal WHERE id = ?", principal)).containsExactly("LEFT");
            assertThat(sql.count("SELECT count(*) FROM account WHERE principal_id = ?", principal)).isZero();
            assertThat(sql.count("SELECT count(*) FROM secret WHERE owner_user_id = ?", adminId)).isZero();
            assertThat(sql.count("SELECT count(*) FROM external_system WHERE owner_principal_id = ?", principal))
                .isZero();
            assertThat(sql.count("SELECT count(*) FROM account WHERE id = ?", botAccount)).isEqualTo(1);
        }
        List<JsonNode> disconnected = auditEvents(owner, company, "account.disconnected");
        assertThat(disconnected).hasSize(2).allSatisfy(event -> {
            assertThat(event.get("details").get("cause").asString()).isEqualTo("principal.left");
            assertThat(event.get("details").get("userId").asString()).isEqualTo(adminId.toString());
        });
        assertThat(disconnected).anySatisfy(event -> assertThat(event.get("targetId").asString())
            .isEqualTo(own.toString()));
        assertThat(auditEvents(owner, company, "system.deleted")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("principal.left"));
        assertThat(auditEvents(owner, company, "secret.deleted")).hasSizeGreaterThanOrEqualTo(2);
        assertThat(auditEvents(owner, company, "principal.left")).anySatisfy(event -> assertThat(
                event.get("targetId").asString()).isEqualTo(principal.toString()));
        assertThat(RecordedEvents.of(company.id(), PrincipalEvents.PrincipalLeft.class))
            .contains(new PrincipalEvents.PrincipalLeft(company.id(), principal, adminId));
        assertThat(RecordedEvents.of(company.id(), AccountEvents.AccountDisconnected.class)).hasSize(2);

        // Rejoining starts from nothing: a new principal, no account, no personal system.
        addMember(owner, company.id(), company.email("ADMIN"), "MEMBER");
        ApiClient back = loginAs(company.email("ADMIN"));
        assertThat(memberPrincipal(company, adminId)).isNotEqualTo(principal);
        assertThat(back.get(company.path("/me/accounts")).json().get("items")).singleElement()
            .satisfies(item -> assertThat(item.has("account")).isFalse());
    }
}
