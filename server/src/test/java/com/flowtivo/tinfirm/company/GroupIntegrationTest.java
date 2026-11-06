package com.flowtivo.tinfirm.company;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.RecordedEvents;

import tools.jackson.databind.JsonNode;

/**
 * Groups of members over HTTP (ADR-0049 §2): OWNER and ADMIN create, rename, fill (as a whole or one member at a
 * time) and delete them and see their members, every member lists their names; a name is unique ignoring case; members
 * are members of the company and leave every group when they leave it; each change is audited by ids.
 */
@ExtendWith(EnterpriseLicence.class)
class GroupIntegrationTest extends IntegrationTest {

    @Test
    void ownersAndAdminsManageGroupsAndEveryMemberListsTheirNames() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        String secondEmail = uniqueEmail("second");
        UUID secondId = createUser(admin(), secondEmail, false);
        addMember(owner, company.id(), secondEmail, "MEMBER");
        UUID memberId = company.userId("MEMBER");
        // Members are listed by display name, which is "User " and the email here.
        List<UUID> byName = company.email("MEMBER").compareToIgnoreCase(secondEmail) < 0 ? List.of(memberId, secondId)
                : List.of(secondId, memberId);

        // A member named twice counts once; the members are shown as Members shows people.
        ApiResponse created = admin.post(company.path("/groups"), Map.of("name", " Finance ", "description",
                "The people who approve payments", "memberIds", List.of(secondId.toString(), memberId.toString(),
                        secondId.toString())));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        JsonNode group = created.json();
        assertThat(group.get("name").asString()).isEqualTo("Finance");
        assertThat(group.get("description").asString()).isEqualTo("The people who approve payments");
        assertThat(group.get("members")).extracting(item -> item.get("userId").asString())
            .containsExactly(byName.get(0).toString(), byName.get(1).toString());
        JsonNode listedMember = group.get("members").get(0).get("userId").asString().equals(memberId.toString())
                ? group.get("members").get(0) : group.get("members").get(1);
        assertThat(listedMember.get("email").asString()).isEqualTo(company.email("MEMBER"));
        assertThat(listedMember.get("displayName").asString()).isEqualTo("User " + company.email("MEMBER"));
        String path = company.path("/groups/" + group.get("id").asString());

        // The name is unique ignoring case; a blank name, a stranger and a role without MANAGE_GROUPS are refused.
        ApiResponse duplicate = owner.post(company.path("/groups"), Map.of("name", "FINANCE", "memberIds", List.of()));
        assertThat(duplicate.status()).isEqualTo(409);
        assertThat(duplicate.problemCode()).isEqualTo("DUPLICATE_GROUP_NAME");
        assertThat(owner.post(company.path("/groups"), Map.of("name", "   ", "memberIds", List.of())).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        UUID stranger = createUser(admin(), uniqueEmail("stranger"), false);
        ApiResponse foreign = owner.post(company.path("/groups"), Map.of("name", "Strangers", "memberIds",
                List.of(stranger.toString())));
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(foreign.json().get("detail").asString()).contains("not a member");
        assertThat(member.post(company.path("/groups"), Map.of("name", "Ops", "memberIds", List.of())).status())
            .isEqualTo(403);

        // Every member lists the names; only owners and admins see who is in a group.
        JsonNode listed = member.get(company.path("/groups")).json().get("items");
        assertThat(listed).singleElement().satisfies(item -> {
            assertThat(item.get("name").asString()).isEqualTo("Finance");
            assertThat(item.get("memberCount").asInt()).isEqualTo(2);
            assertThat(item.has("members")).isFalse();
        });
        assertThat(member.get(path).status()).isEqualTo(403);
        assertThat(member.put(path, Map.of("name", "Mine", "memberIds", List.of())).status()).isEqualTo(403);
        assertThat(owner.get(path).json().get("members")).hasSize(2);

        // A replace sets everything as a whole and records what changed, by ids.
        ApiResponse replaced = owner.put(path, Map.of("name", "Accounting", "memberIds",
                List.of(secondId.toString(), company.userId("OWNER").toString())));
        assertThat(replaced.status()).as("%s", replaced).isEqualTo(200);
        assertThat(replaced.json().get("name").asString()).isEqualTo("Accounting");
        assertThat(replaced.json().has("description")).as("an absent description clears it").isFalse();
        assertThat(replaced.json().get("members")).extracting(item -> item.get("userId").asString())
            .containsExactlyInAnyOrder(secondId.toString(), company.userId("OWNER").toString());
        JsonNode updated = auditEvents(owner, company, "group.updated").getFirst();
        assertThat(updated.get("targetType").asString()).isEqualTo("group");
        assertThat(updated.get("targetId").asString()).isEqualTo(group.get("id").asString());
        assertThat(updated.get("details").get("changed")).extracting(JsonNode::asString)
            .containsExactly("name", "description", "members");
        assertThat(updated.get("details").get("added")).extracting(JsonNode::asString)
            .containsExactly(company.userId("OWNER").toString());
        assertThat(updated.get("details").get("removed")).extracting(JsonNode::asString)
            .containsExactly(memberId.toString());
        // The same again changes nothing and is not audited.
        assertThat(owner.put(path, Map.of("name", "Accounting", "memberIds", List.of(company.userId("OWNER")
            .toString(), secondId.toString()))).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "group.updated")).hasSize(1);

        // Leaving the company leaves every group.
        assertThat(owner.delete(company.path("/members/" + secondId)).status()).isEqualTo(204);
        assertThat(owner.get(path).json().get("members")).extracting(item -> item.get("userId").asString())
            .containsExactly(company.userId("OWNER").toString());

        assertThat(admin.delete(path).status()).isEqualTo(204);
        assertThat(owner.get(path).status()).isEqualTo(404);
        assertThat(admin.delete(path).status()).isEqualTo(404);
        assertThat(owner.get(company.path("/groups")).json().get("items")).isEmpty();

        JsonNode createdEvent = auditEvents(owner, company, "group.created").getFirst();
        assertThat(createdEvent.get("details").get("name").asString()).isEqualTo("Finance");
        assertThat(createdEvent.get("details").get("memberIds")).hasSize(2);
        assertThat(createdEvent.get("details").toString()).doesNotContain("@").doesNotContain("payments");
        JsonNode deletedEvent = auditEvents(owner, company, "group.deleted").getFirst();
        assertThat(deletedEvent.get("details").get("name").asString()).isEqualTo("Accounting");
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void oneMemberIsAddedOrTakenOutByTheRulesOfAReplace() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID ownerId = company.userId("OWNER");
        UUID memberId = company.userId("MEMBER");
        UUID groupId = createGroup(owner, company, "Finance", List.of(memberId));
        String path = company.path("/groups/" + groupId);
        String updatedAt = owner.get(path).json().get("updatedAt").asString();

        // An admin adds one member; the answer is the group with every member, and the change is a group.updated.
        ApiResponse added = admin.put(path + "/members/" + ownerId, null);
        assertThat(added.status()).as("%s", added).isEqualTo(200);
        assertThat(added.json().get("id").asString()).isEqualTo(groupId.toString());
        assertThat(userIds(added.json())).containsExactlyInAnyOrder(memberId.toString(), ownerId.toString());
        assertThat(added.json().get("updatedAt").asString()).isNotEqualTo(updatedAt);
        JsonNode addedEvent = auditEvents(owner, company, "group.updated").getFirst();
        assertThat(addedEvent.get("targetId").asString()).isEqualTo(groupId.toString());
        assertThat(addedEvent.get("details").get("changed")).extracting(JsonNode::asString).containsExactly("members");
        assertThat(addedEvent.get("details").get("name").asString()).isEqualTo("Finance");
        assertThat(addedEvent.get("details").get("added")).extracting(JsonNode::asString)
            .containsExactly(ownerId.toString());
        assertThat(addedEvent.get("details").get("removed")).isEmpty();
        // Adding them again changes nothing and is not audited.
        ApiResponse again = owner.put(path + "/members/" + ownerId, null);
        assertThat(again.status()).isEqualTo(200);
        assertThat(userIds(again.json())).containsExactlyInAnyOrder(memberId.toString(), ownerId.toString());
        assertThat(again.json().get("updatedAt").asString()).isEqualTo(added.json().get("updatedAt").asString());

        // Taking one out leaves the others; taking out who is not in it changes nothing.
        ApiResponse removed = owner.delete(path + "/members/" + memberId);
        assertThat(removed.status()).as("%s", removed).isEqualTo(200);
        assertThat(userIds(removed.json())).containsExactly(ownerId.toString());
        JsonNode removedEvent = auditEvents(owner, company, "group.updated").getFirst();
        assertThat(removedEvent.get("details").get("added")).isEmpty();
        assertThat(removedEvent.get("details").get("removed")).extracting(JsonNode::asString)
            .containsExactly(memberId.toString());
        assertThat(owner.delete(path + "/members/" + memberId).status()).isEqualTo(200);
        assertThat(owner.delete(path + "/members/" + UUID.randomUUID()).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "group.updated")).hasSize(2);
        assertThat(RecordedEvents.of(company.id(), GroupEvents.GroupMembersChanged.class))
            .filteredOn(event -> event.groupId().equals(groupId))
            .extracting(event -> event.addedUserIds().toString() + event.removedUserIds())
            .containsExactly("[" + ownerId + "][]", "[][" + memberId + "]");

        // As for a replace: only members of the company, not a service identity, nor a user who left; a suspended
        // member is still a member.
        UUID stranger = createUser(admin(), uniqueEmail("stranger"), false);
        ApiResponse foreign = owner.put(path + "/members/" + stranger, null);
        assertThat(foreign.status()).isEqualTo(400);
        assertThat(foreign.problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(foreign.json().get("detail").asString()).contains("not a member");
        UUID identity = createServiceIdentity(owner, company, unique("svc"));
        assertThat(owner.put(path + "/members/" + identity, null).problemCode()).isEqualTo("VALIDATION_FAILED");
        String leaverEmail = uniqueEmail("leaver");
        UUID leaver = createUser(admin(), leaverEmail, false);
        addMember(owner, company.id(), leaverEmail, "MEMBER");
        assertThat(owner.delete(company.path("/members/" + leaver)).status()).isEqualTo(204);
        assertThat(owner.put(path + "/members/" + leaver, null).problemCode()).isEqualTo("VALIDATION_FAILED");
        assertThat(owner.post(company.path("/members/" + memberId + "/suspend"), Map.of("reason", "on leave"))
            .status()).isEqualTo(200);
        assertThat(userIds(owner.put(path + "/members/" + memberId, null).json()))
            .containsExactlyInAnyOrder(ownerId.toString(), memberId.toString());

        // An unknown group and another company's are not found; nor is this one to another company's owner.
        assertThat(admin.put(company.path("/groups/" + UUID.randomUUID() + "/members/" + ownerId), null).status())
            .isEqualTo(404);
        TestCompany elsewhere = companyWithRoles();
        UUID foreignGroup = createGroup(loginAs(elsewhere.email("OWNER")), elsewhere, "Finance", List.of());
        assertThat(owner.put(company.path("/groups/" + foreignGroup + "/members/" + ownerId), null).status())
            .isEqualTo(404);
        assertThat(owner.delete(company.path("/groups/" + foreignGroup + "/members/" + ownerId)).status())
            .isEqualTo(404);
        // Another company's member is not one of this company's; taking them out of its group touches nothing.
        assertThat(owner.put(path + "/members/" + elsewhere.userId("MEMBER"), null).problemCode())
            .isEqualTo("VALIDATION_FAILED");
        assertThat(owner.delete(path + "/members/" + elsewhere.userId("MEMBER")).status()).isEqualTo(200);
        ApiClient outsider = loginAs(elsewhere.email("OWNER"));
        assertThat(outsider.put(path + "/members/" + ownerId, null).status()).isEqualTo(404);
        assertThat(outsider.delete(path + "/members/" + ownerId).status()).isEqualTo(404);

        // A role without MANAGE_GROUPS is refused (once resumed: a suspended member's writes are 409).
        assertThat(owner.post(company.path("/members/" + memberId + "/resume"), null).status()).isEqualTo(200);
        assertThat(member.put(path + "/members/" + company.userId("ADMIN"), null).status()).isEqualTo(403);
        assertThat(member.delete(path + "/members/" + ownerId).status()).isEqualTo(403);
        assertThat(userIds(admin.get(path).json())).containsExactlyInAnyOrder(ownerId.toString(),
                memberId.toString());
        // An instance admin who is not a member has ADMIN's view, audited as break-glass.
        ApiResponse breakGlass = admin().delete(path + "/members/" + memberId);
        assertThat(breakGlass.status()).isEqualTo(200);
        assertThat(userIds(breakGlass.json())).containsExactly(ownerId.toString());
        assertThat(owner.get(company.path("/audit-events/verification")).json().get("valid").asBoolean()).isTrue();
    }

    @Test
    void aGroupsUsageIsWhatItsMembersMayUseThroughItAndNothingElse() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        UUID analysts = createGroup(owner, company, "Analysts", List.of(company.userId("MEMBER")));
        UUID others = createGroup(owner, company, "Others", List.of());
        UUID empty = createGroup(owner, company, "Empty", List.of());
        // Agents: two shared with Analysts (one also with Others), one with Others only, one deleted.
        UUID github = createSystem(owner, company, "GitHub", "GITHUB", "https://api.github.com", false);
        String reviewer = sharedAgent(owner, company, "Reviewer", github, analysts, others);
        String analyst = sharedAgent(owner, company, "Analyst", github, analysts);
        String private_ = sharedAgent(owner, company, "Private", github, others);
        String gone = sharedAgent(owner, company, "Gone", github, analysts);
        assertThat(owner.delete(company.path("/agents/" + gone)).status()).isEqualTo(204);
        // Service identities used by one group each, and their Jira accounts lent to them.
        UUID reader = createServiceIdentity(owner, company, "svc-reader");
        UUID ops = createServiceIdentity(owner, company, "svc-ops");
        assertThat(owner.put(company.path("/service-identities/" + reader + "/users"), Map.of("groupIds",
                List.of(analysts.toString()))).status()).isEqualTo(200);
        assertThat(owner.put(company.path("/service-identities/" + ops + "/users"), Map.of("groupIds",
                List.of(others.toString()))).status()).isEqualTo(200);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraSystem = createSystem(owner, company, "Jira", "JIRA_DC", jira.baseUrl(), false);
        UUID readerAccount = connectServiceToken(owner, company, reader, jiraSystem, jira, unique("reader-token"),
                "svc-reader");
        UUID opsAccount = connectServiceToken(owner, company, ops, jiraSystem, jira, unique("ops-token"), "svc-ops");
        UUID grant = createGrant(owner, company, readerAccount, analysts, "READ");
        createGrant(owner, company, opsAccount, others, "WRITE");

        // An admin reads one group's: its live agents, its grants as listGrants shows them, its service identities.
        ApiResponse usage = admin.get(company.path("/groups/" + analysts + "/usage"));
        assertThat(usage.status()).as("%s", usage).isEqualTo(200);
        assertThat(usage.json().get("agents")).extracting(item -> item.get("id").asString() + " "
                + item.get("name").asString()).containsExactly(analyst + " Analyst", reviewer + " Reviewer");
        JsonNode listed = owner.get(company.path("/grants")).json().get("items");
        JsonNode asListed = java.util.stream.StreamSupport.stream(listed.spliterator(), false)
            .filter(item -> item.get("id").asString().equals(grant.toString()))
            .findFirst()
            .orElseThrow();
        assertThat(usage.json().get("grants")).containsExactly(asListed);
        assertThat(asListed.get("credentialName").asString()).isEqualTo("Credential of " + reader);
        assertThat(asListed.get("groupName").asString()).isEqualTo("Analysts");
        assertThat(usage.json().get("serviceIdentities")).extracting(item -> item.get("id").asString() + " "
                + item.get("name").asString()).containsExactly(reader + " svc-reader");
        // Another group's are its own; a group given nothing uses nothing.
        JsonNode theOthers = owner.get(company.path("/groups/" + others + "/usage")).json();
        assertThat(theOthers.get("agents")).extracting(item -> item.get("id").asString())
            .containsExactly(private_, reviewer);
        assertThat(theOthers.get("grants")).extracting(item -> item.get("access").asString()).containsExactly("WRITE");
        assertThat(theOthers.get("serviceIdentities")).extracting(item -> item.get("name").asString())
            .containsExactly("svc-ops");
        JsonNode nothing = owner.get(company.path("/groups/" + empty + "/usage")).json();
        assertThat(nothing.get("agents")).isEmpty();
        assertThat(nothing.get("grants")).isEmpty();
        assertThat(nothing.get("serviceIdentities")).isEmpty();

        // As getGroup: MANAGE_GROUPS; an unknown group, another company's and another company's owner: 404.
        String path = company.path("/groups/" + analysts + "/usage");
        assertThat(loginAs(company.email("MEMBER")).get(path).status()).isEqualTo(403);
        assertThat(owner.get(company.path("/groups/" + UUID.randomUUID() + "/usage")).status()).isEqualTo(404);
        TestCompany elsewhere = companyWithRoles();
        ApiClient outsider = loginAs(elsewhere.email("OWNER"));
        UUID foreignGroup = createGroup(outsider, elsewhere, "Analysts", List.of());
        assertThat(owner.get(company.path("/groups/" + foreignGroup + "/usage")).status()).isEqualTo(404);
        assertThat(outsider.get(path).status()).isEqualTo(404);
        assertThat(outsider.get(elsewhere.path("/groups/" + analysts + "/usage")).status()).isEqualTo(404);
        // An instance admin who is not a member reads it with ADMIN's view.
        ApiResponse breakGlass = admin().get(path);
        assertThat(breakGlass.status()).isEqualTo(200);
        assertThat(breakGlass.json().get("agents")).hasSize(2);
    }

    /** An agent of the owner's on the code host, shared with the groups; its id. */
    private static String sharedAgent(ApiClient owner, TestCompany company, String name, UUID systemId,
            UUID... groupIds) {
        ApiResponse agent = owner.post(company.path("/agents"), Map.of("name", name, "runtimes",
                List.of("CLAUDE_CODE"), "systems", List.of(Map.of("systemId", systemId.toString())), "shareGroupIds",
                java.util.Arrays.stream(groupIds).map(UUID::toString).toList()));
        assertThat(agent.status()).as("agent: %s", agent).isEqualTo(201);
        return agent.json().get("id").asString();
    }

    private static List<String> userIds(JsonNode group) {
        List<String> ids = new java.util.ArrayList<>();
        group.get("members").forEach(item -> ids.add(item.get("userId").asString()));
        return ids;
    }

    @Test
    void groupsArePagedByName() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        for (String name : List.of("Gamma", "Alpha", "Beta")) {
            assertThat(owner.post(company.path("/groups"), Map.of("name", name, "memberIds", List.of())).status())
                .isEqualTo(201);
        }
        ApiClient member = loginAs(company.email("MEMBER"));
        JsonNode first = member.get(company.path("/groups?limit=2")).json();
        assertThat(first.get("items")).extracting(item -> item.get("name").asString())
            .containsExactly("Alpha", "Beta");
        JsonNode second = member.get(company.path("/groups?limit=2&cursor=" + first.get("nextCursor").asString()))
            .json();
        assertThat(second.get("items")).extracting(item -> item.get("name").asString()).containsExactly("Gamma");
        assertThat(second.has("nextCursor")).isFalse();
    }
}
