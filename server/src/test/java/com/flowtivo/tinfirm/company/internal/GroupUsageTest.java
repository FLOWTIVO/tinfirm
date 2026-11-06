package com.flowtivo.tinfirm.company.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.company.GroupUses;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.GroupUsageItem;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;

/**
 * A group's usage at the port ({@link GroupUses}): the agent, account and principal modules each answer their part,
 * which put together is what {@code getGroupUsage} answers; and each answers only to an access that sees its own list
 * in full (the agents with {@code MANAGE_AGENTS}, the grants with {@code MANAGE_GRANTS}, who may use a service identity
 * with {@code MANAGE_SERVICE_IDENTITIES}), so the read shows nothing those lists would hide.
 */
class GroupUsageTest extends IntegrationTest {

    @Autowired
    private List<GroupUses> modules;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void eachModuleAnswersItsOwnPartAndOnlyToWhoSeesItsList() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID groupId = createGroup(owner, company, "Analysts", List.of(company.userId("MEMBER")));
        UUID github = createSystem(owner, company, "GitHub", "GITHUB", "https://api.github.com", false);
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", "Analyst", "runtimes",
                List.of("CLAUDE_CODE"), "systems", List.of(Map.of("systemId", github.toString())), "shareGroupIds",
                List.of(groupId.toString())));
        assertThat(agent.status()).as("agent: %s", agent).isEqualTo(201);
        UUID reader = createServiceIdentity(owner, company, "svc-reader");
        assertThat(owner.put(company.path("/service-identities/" + reader + "/users"), Map.of("groupIds",
                List.of(groupId.toString()))).status()).isEqualTo(200);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraSystem = createSystem(owner, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID account = connectServiceToken(owner, company, reader, jiraSystem, jira, unique("reader-token"),
                "svc-reader");
        UUID grant = createGrant(owner, company, account, groupId, "READ");

        assertThat(modules).hasSize(3);
        GroupUses.Uses all = usage(new CompanyAccess(company.id(), CompanyRole.ADMIN, false), groupId);
        assertThat(all.agents()).containsExactly(new GroupUsageItem(UUID.fromString(agent.json().get("id")
            .asString()), "Analyst"));
        assertThat(all.grants()).singleElement().satisfies(lent -> {
            assertThat(lent.getId()).isEqualTo(grant);
            assertThat(lent.getSystemName()).isEqualTo("Jira");
            assertThat(lent.getCredentialName()).isEqualTo("Credential of " + reader);
            assertThat(lent.getGroupName()).isEqualTo("Analysts");
        });
        assertThat(all.serviceIdentities()).containsExactly(new GroupUsageItem(reader, "svc-reader"));
        // Every module answers its part alone.
        for (GroupUses module : modules) {
            GroupUses.Uses part = inCompany(company.id(), () -> module.of(
                    new CompanyAccess(company.id(), CompanyRole.OWNER, false), groupId));
            int parts = (part.agents().isEmpty() ? 0 : 1) + (part.grants().isEmpty() ? 0 : 1)
                    + (part.serviceIdentities().isEmpty() ? 0 : 1);
            assertThat(parts).as("%s", module).isEqualTo(1);
        }
        // An instance admin under break-glass sees them as ADMIN does; a MEMBER's access, nothing.
        assertThat(usage(new CompanyAccess(company.id(), null, true), groupId)).isEqualTo(all);
        assertThat(usage(new CompanyAccess(company.id(), CompanyRole.MEMBER, false), groupId))
            .isEqualTo(GroupUses.Uses.NONE);
    }

    /** Every module's part put together, as the caller with this access, in the company's tenant context. */
    private GroupUses.Uses usage(CompanyAccess access, UUID groupId) {
        return inCompany(access.companyId(), () -> {
            List<GroupUses.Uses> parts = modules.stream().map(module -> module.of(access, groupId)).toList();
            return new GroupUses.Uses(parts.stream().flatMap(part -> part.agents().stream()).toList(),
                    parts.stream().flatMap(part -> part.grants().stream()).toList(),
                    parts.stream().flatMap(part -> part.serviceIdentities().stream()).toList());
        });
    }

    private <T> T inCompany(UUID companyId, java.util.function.Supplier<T> work) {
        return transactions.inCompany(companyId, work);
    }
}
