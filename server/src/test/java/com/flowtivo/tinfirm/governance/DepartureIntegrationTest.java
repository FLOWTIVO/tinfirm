package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.company.GroupEvents;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestCredentials;

import tools.jackson.databind.JsonNode;

/**
 * A member who leaves (the package 6 contract, §5.5; open points 13 and 21): the preview, the groups the membership's
 * cascade took them out of announced, the admins' {@code DEPARTURE} item and checklist, closing it only once its open
 * items are resolved (or with {@code force}), and giving a passed agent a new owner.
 */
class DepartureIntegrationTest extends IntegrationTest {

    @Test
    void theChecklistOfAMemberWhoLeftStaysOpenUntilItsItemsAreResolved() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient leaver = loginAs(company.email("ADMIN"));
        UUID leaverUser = company.userId("ADMIN");
        UUID leaverPrincipal = memberPrincipal(company, leaverUser);
        UUID memberUser = company.userId("MEMBER");

        // What was theirs: a group they alone are in, one they share, a system's credential they connected, and an
        // agent they own.
        UUID alone = createGroup(owner, company, unique("Alone"), List.of(leaverUser));
        UUID shared = createGroup(owner, company, unique("Shared"), List.of(leaverUser, memberUser));
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID systemId = createSystem(owner, company, unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        jira.myself("leaver-made-token", "bot", "UTC");
        UUID credential = TestCredentials.add(leaver, company.path(""), systemId, "Bot", "leaver-made-token");
        String botAccount = company.path("/systems/" + systemId + "/credentials/" + credential);
        ApiResponse agent = leaver.post(company.path("/agents"), Map.of("name", unique("Analyst"), "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        UUID agentId = UUID.fromString(agent.json().get("id").asString());

        // The preview, for admins only.
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/members/" + leaverUser + "/departure"))
            .status()).isEqualTo(403);
        JsonNode preview = owner.get(company.path("/members/" + leaverUser + "/departure")).json();
        assertThat(preview.get("principalId").asString()).isEqualTo(leaverPrincipal.toString());
        assertThat(preview.get("agentsPassing").asInt()).isEqualTo(1);
        assertThat(preview.get("groupsLeft").asInt()).isEqualTo(2);
        assertThat(preview.get("triggersPausing").asInt()).isZero();

        assertThat(owner.delete(company.path("/members/" + leaverUser)).status()).isEqualTo(204);

        // Each group the cascade took them out of is announced.
        List<GroupEvents.GroupMembersChanged> changed = RecordedEvents.of(company.id(),
                GroupEvents.GroupMembersChanged.class)
            .stream()
            .filter(event -> event.removedUserIds().contains(leaverUser))
            .toList();
        assertThat(changed).extracting(GroupEvents.GroupMembersChanged::groupId).containsExactlyInAnyOrder(alone,
                shared);

        // Admins are told until the checklist is closed.
        assertThat(departures(owner, company)).isEqualTo(1);
        assertThat(departures(loginAs(company.email("MEMBER")), company)).isZero();
        // The open departures, without the inbox: OWNER and ADMIN.
        assertThat(owner.get(company.path("/departures")).json().get("items")).singleElement().satisfies(open -> {
            assertThat(open.get("principalId").asString()).isEqualTo(leaverPrincipal.toString());
            assertThat(open.get("userId").asString()).isEqualTo(leaverUser.toString());
            assertThat(open.get("name").asString()).isNotBlank();
            assertThat(open.get("leftAt").asString()).isNotBlank();
        });
        assertThat(loginAs(company.email("MEMBER")).get(company.path("/departures")).status()).isEqualTo(403);

        String path = company.path("/principals/" + leaverPrincipal + "/departure");
        JsonNode checklist = owner.get(path).json();
        assertThat(checklist.get("open").asBoolean()).isTrue();
        assertThat(checklist.get("userId").asString()).isEqualTo(leaverUser.toString());
        assertThat(checklist.get("emptyGroups")).hasSize(1);
        assertThat(checklist.get("emptyGroups").get(0).get("id").asString()).isEqualTo(alone.toString());
        assertThat(checklist.get("connectedCredentials")).hasSize(1);
        assertThat(checklist.get("connectedCredentials").get(0).get("kind").asString()).isEqualTo("TOKEN");
        assertThat(checklist.get("connectedCredentials").get(0).get("credentialId").asString())
            .isEqualTo(credential.toString());
        assertThat(checklist.get("connectedCredentials").get(0).get("credentialName").asString()).isEqualTo("Bot");
        assertThat(checklist.get("agents")).hasSize(1);
        assertThat(checklist.get("agents").get(0).get("id").asString()).isEqualTo(agentId.toString());
        assertThat(checklist.get("triggers")).isEmpty();
        ApiResponse refused = owner.post(path + "/close", Map.of());
        assertThat(refused.status()).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("DEPARTURE_OPEN");

        // Resolved: the account connected again with a new token, the group filled, the agent given a new owner.
        connectToken(owner, botAccount, jira, "owner-made-token", "bot");
        assertThat(owner.put(company.path("/groups/" + alone), Map.of("name", unique("Alone"), "memberIds",
                List.of(memberUser.toString()))).status()).isEqualTo(200);
        UUID memberPrincipal = memberPrincipal(company, memberUser);
        ApiResponse given = owner.put(company.path("/agents/" + agentId + "/owner"),
                Map.of("principalId", memberPrincipal.toString()));
        assertThat(given.status()).as("%s", given).isEqualTo(200);
        JsonNode ownerChanged = auditEvents(owner, company, "agent.owner_changed").getFirst();
        assertThat(ownerChanged.get("details").get("ownerPrincipalId").asString())
            .isEqualTo(memberPrincipal.toString());
        assertThat(ownerChanged.get("details").path("formerOwnerPrincipalId").asString(null)).isNull();
        // A member edits it as its owner now.
        assertThat(loginAs(company.email("MEMBER")).patch(company.path("/agents/" + agentId),
                Map.of("description", "Mine now")).status()).isEqualTo(200);

        JsonNode resolved = owner.get(path).json();
        assertThat(resolved.get("open").asBoolean()).isFalse();
        assertThat(resolved.get("agents")).isEmpty();
        ApiResponse closed = owner.post(path + "/close", Map.of());
        assertThat(closed.status()).as("%s", closed).isEqualTo(200);
        assertThat(closed.json().path("closedAt").asString(null)).isNotNull();
        assertThat(departures(owner, company)).isZero();
        JsonNode audit = auditEvents(owner, company, "principal.departure_closed").getFirst();
        assertThat(audit.get("details").get("force").asBoolean()).isFalse();
    }

    @Test
    void anAdminClosesAnOpenChecklistWithForce() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID leaverUser = company.userId("MEMBER");
        UUID leaverPrincipal = memberPrincipal(company, leaverUser);
        createGroup(owner, company, unique("Solo"), List.of(leaverUser));
        assertThat(owner.delete(company.path("/members/" + leaverUser)).status()).isEqualTo(204);
        String path = company.path("/principals/" + leaverPrincipal + "/departure");
        assertThat(owner.post(path + "/close", Map.of()).problemCode()).isEqualTo("DEPARTURE_OPEN");
        ApiResponse forced = owner.post(path + "/close", Map.of("force", true));
        assertThat(forced.status()).as("%s", forced).isEqualTo(200);
        JsonNode audit = auditEvents(owner, company, "principal.departure_closed").getFirst();
        assertThat(audit.get("details").get("force").asBoolean()).isTrue();
        assertThat(audit.get("details").get("openItems").get("emptyGroups").asInt()).isEqualTo(1);
        // A principal that did not leave has no checklist.
        UUID active = memberPrincipal(company, company.userId("ADMIN"));
        assertThat(owner.get(company.path("/principals/" + active + "/departure")).status()).isEqualTo(404);
    }

    /**
     * Closing a checklist changes no key of the principal, so it locks the row {@code FOR NO KEY UPDATE} (CONVENTIONS
     * §10): a foreign key's check of the principal ({@code FOR KEY SHARE}) held by another transaction does not hold
     * it up, as it would {@code FOR UPDATE}.
     */
    @Test
    void closingAChecklistDoesNotWaitForAForeignKeysCheckOfThePrincipal() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID leaverUser = company.userId("MEMBER");
        UUID leaverPrincipal = memberPrincipal(company, leaverUser);
        assertThat(owner.delete(company.path("/members/" + leaverUser)).status()).isEqualTo(204);
        String path = company.path("/principals/" + leaverPrincipal + "/departure");
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (TenantSql holder = TenantSql.in(company.id())) {
            assertThat(holder.column("SELECT id FROM principal WHERE id = ? FOR KEY SHARE", leaverPrincipal))
                .hasSize(1);
            Future<ApiResponse> closing = threads.submit(() -> owner.post(path + "/close", Map.of("force", true)));
            ApiResponse closed = closing.get(20, TimeUnit.SECONDS);
            assertThat(closed.status()).as("%s", closed).isEqualTo(200);
            holder.commit();
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(auditEvents(owner, company, "principal.departure_closed"))
            .filteredOn(event -> event.get("details").get("userId").asString().equals(leaverUser.toString()))
            .hasSize(1);
    }

    @Test
    void anAgentsNewOwnerIsAnActiveMember() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", unique("Agent"), "runtimes",
                List.of("CLAUDE_CODE")));
        UUID agentId = UUID.fromString(agent.json().get("id").asString());
        UUID bot = createServiceIdentity(owner, company, unique("Bot"));
        assertThat(owner.put(company.path("/agents/" + agentId + "/owner"), Map.of("principalId", bot.toString()))
            .status()).isEqualTo(400);
        assertThat(loginAs(company.email("MEMBER")).put(company.path("/agents/" + agentId + "/owner"),
                Map.of("principalId", memberPrincipal(company, company.userId("MEMBER")).toString())).status())
            .isIn(403, 404);
    }

    private static int departures(ApiClient reader, TestCompany company) {
        JsonNode inbox = reader.get(company.path("/inbox")).json();
        int items = 0;
        for (JsonNode item : inbox.get("items")) {
            if (item.get("kind").asString().equals("DEPARTURE")) {
                items++;
            }
        }
        // DEPARTURE items are counted with the other modules' items, in notices.
        assertThat(inbox.get("counts").get("notices").asInt()).isGreaterThanOrEqualTo(items);
        return items;
    }
}
