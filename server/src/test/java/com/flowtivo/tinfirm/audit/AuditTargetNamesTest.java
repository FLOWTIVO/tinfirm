package com.flowtivo.tinfirm.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.McpStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestContent;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;

import tools.jackson.databind.JsonNode;

/**
 * The names of the audit's targets (CONVENTIONS §8, §15.11): read now from what the company keeps, never from the
 * chain, and only as the reader may see them elsewhere; none for a target that is gone; an erased person named nowhere
 * in the company's chain, and named again once they joined again; the instance's chain naming its users.
 */
@ExtendWith(EnterpriseLicence.class)
class AuditTargetNamesTest extends IntegrationTest {

    @Autowired
    private AuditLog auditLog;

    @Autowired
    private SystemTransactions transactions;

    @Test
    void eachTargetIsNamedAsItsReaderMaySeeIt() throws SQLException {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient admin = loginAs(company.email("ADMIN"));
        UUID ownerUser = company.userId("OWNER");
        UUID memberUser = company.userId("MEMBER");
        UUID ownerPrincipal = memberPrincipal(company, ownerUser);
        UUID memberPrincipal = memberPrincipal(company, memberUser);
        String member = "User " + company.email("MEMBER");
        String companyName = owner.get(company.path("")).json().get("name").asString();
        String svcName = unique("svc");
        UUID svc = createServiceIdentity(owner, company, svcName);
        String groupName = unique("Group");
        UUID group = createGroup(owner, company, groupName, List.of(memberUser));
        UUID agent = TestRuns.agent(company.id());
        UUID deletedAgent = TestRuns.agent(company.id());
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.accepted("UPDATE agent SET deleted_at = now() WHERE id = ?", deletedAgent);
            sql.commit();
        }
        String toolsName = unique("Tools");
        UUID tools = createSystem(owner, company, toolsName, "MCP", McpStub.newInstance().url(), false);
        String notesName = unique("Notes");
        UUID ownersNotes = createSystem(owner, company, notesName, "MCP", McpStub.newInstance().url(), true);
        String buildName = unique("build");
        UUID build = registerRunnerHost(owner, company.id(), buildName, "COMPANY").id();
        String laptopName = unique("laptop");
        UUID ownersLaptop = registerRunnerHost(owner, company.id(), laptopName).id();
        UUID trigger = TestTriggers.schedule(company.id(), agent, ownerPrincipal, ownerPrincipal);
        String ownKey = unique("own").toLowerCase();
        UUID own = TestContent.conversation(company.id(), agent, ownerPrincipal, ownerUser, ownKey,
                "TINFIRM_THREAD", false).conversationId();
        String theirsKey = unique("theirs").toLowerCase();
        UUID theirs = TestContent.conversation(company.id(), agent, memberPrincipal, memberUser, theirsKey,
                "TINFIRM_THREAD", false).conversationId();
        String scheduledKey = unique("scheduled").toLowerCase();
        UUID scheduled = TestContent.triggerConversation(company.id(), agent, ownerPrincipal, memberPrincipal,
                scheduledKey);
        UUID gone = UUID.randomUUID();
        Map<String, List<Object>> targets = Map.ofEntries(Map.entry("principal", List.of(svc, memberPrincipal, gone)),
                Map.entry("principal_model_key", List.of(memberPrincipal)), Map.entry("user", List.of(memberUser)),
                Map.entry("company", List.of(company.id())), Map.entry("group", List.of(group)),
                Map.entry("agent", List.of(agent, deletedAgent, gone)), Map.entry("system", List.of(tools,
                        ownersNotes)),
                Map.entry("runner_host", List.of(build, ownersLaptop)), Map.entry("trigger", List.of(trigger)),
                Map.entry("conversation", List.of(own, theirs, scheduled)),
                Map.entry("legal_hold", List.of(UUID.randomUUID())));
        targets.forEach((type, ids) -> ids.forEach(id -> record(company.id(), Actor.user(ownerUser), type, id)));

        // OWNER: every name, a personal server and host of their own, another person's conversation by key only.
        assertThat(nameOf(owner, company, "principal", svc)).isEqualTo(svcName);
        assertThat(nameOf(owner, company, "principal", memberPrincipal)).isEqualTo(member);
        assertThat(nameOf(owner, company, "principal_model_key", memberPrincipal)).isEqualTo(member);
        assertThat(nameOf(owner, company, "user", memberUser)).isEqualTo(member);
        assertThat(nameOf(owner, company, "company", company.id())).isEqualTo(companyName);
        assertThat(nameOf(owner, company, "group", group)).isEqualTo(groupName);
        assertThat(nameOf(owner, company, "agent", agent)).startsWith("Test agent ");
        assertThat(nameOf(owner, company, "system", tools)).isEqualTo(toolsName);
        assertThat(nameOf(owner, company, "system", ownersNotes)).isEqualTo(notesName);
        assertThat(nameOf(owner, company, "runner_host", build)).isEqualTo(buildName);
        assertThat(nameOf(owner, company, "runner_host", ownersLaptop)).isEqualTo(laptopName);
        assertThat(nameOf(owner, company, "trigger", trigger)).startsWith("Test schedule ");
        assertThat(nameOf(owner, company, "conversation", own)).isEqualTo(ownKey + " Title with "
                + TestContent.SECRET);
        assertThat(nameOf(owner, company, "conversation", theirs)).isEqualTo(theirsKey);
        assertThat(nameOf(owner, company, "conversation", scheduled)).isEqualTo(scheduledKey
                + " Test schedule conversation " + scheduledKey);
        // Gone, or of a type without a name: none.
        assertThat(nameOf(owner, company, "agent", deletedAgent)).isNull();
        assertThat(nameOf(owner, company, "agent", gone)).isNull();
        assertThat(nameOf(owner, company, "principal", gone)).isNull();
        assertThat(nameOf(owner, company, "legal_hold", (UUID) targets.get("legal_hold").getFirst())).isNull();

        // ADMIN: not the OWNER's personal server and host, nor another person's personal conversation at all.
        assertThat(nameOf(admin, company, "system", ownersNotes)).isNull();
        assertThat(nameOf(admin, company, "runner_host", ownersLaptop)).isNull();
        assertThat(nameOf(admin, company, "conversation", own)).isNull();
        assertThat(nameOf(admin, company, "conversation", theirs)).isNull();
        assertThat(nameOf(admin, company, "conversation", scheduled)).startsWith(scheduledKey + " ");
        assertThat(nameOf(admin, company, "system", tools)).isEqualTo(toolsName);
        assertThat(nameOf(admin, company, "principal", memberPrincipal)).isEqualTo(member);
        assertThat(items(admin, company, "targetType=conversation").toString()).doesNotContain(TestContent.SECRET);
    }

    @Test
    void anErasedPersonIsNamedNowhereInTheCompanysAuditUntilTheyJoinAgain() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID alex = company.userId("MEMBER");
        UUID principal = memberPrincipal(company, alex);
        String name = "User " + company.email("MEMBER");
        registerRunnerHost(loginAs(company.email("MEMBER")), company.id(), unique("laptop"));
        assertThat(owner.delete(company.path("/members/" + alex)).status()).isEqualTo(204);

        // Left, and not erased: named, as one who left.
        assertThat(first(owner, company, "action=principal.left").get("targetName").asString())
            .isEqualTo(name + " (left)");
        assertThat(first(owner, company, "action=member.removed").get("targetName").asString()).isEqualTo(name);
        assertThat(first(owner, company, "action=runner_host.registered").get("actor").get("display").asString())
            .isEqualTo(name);

        ApiResponse erased = owner.post(company.path("/principals/" + principal + "/erase"), Map.of());
        assertThat(erased.status()).as("%s", erased).isEqualTo(200);

        // Erased: no name as a target (their principal, their user), and "erased person" as the actor.
        List<JsonNode> about = items(owner, company, "principalId=" + principal);
        assertThat(about).extracting(event -> event.get("action").asString())
            .contains("principal.erased", "principal.left", "member.removed", "member.added",
                    "runner_host.registered");
        assertThat(about).allSatisfy(event -> assertThat(event.path("targetName").isNull()
                || event.path("targetName").isMissingNode() || !isAlex(event, alex, principal))
            .as("%s", event)
            .isTrue());
        assertThat(first(owner, company, "action=runner_host.registered").get("actor").get("display").asString())
            .isEqualTo("erased person");
        assertThat(items(owner, company, "limit=200").toString()).doesNotContain(name);
        assertThat(items(loginAs(company.email("ADMIN")), company, "limit=200").toString()).doesNotContain(name);
        // The instance's chain still names the user: their account keeps the name until an instance admin deletes it.
        assertThat(instanceEvent(alex, "user.created").get("targetName").asString()).isEqualTo(name);

        // Joined again: named again, and their first principal as one who left. Their machine is present again
        // (ADR-0092): a registration by the owner who added them, with its cause; their own is the earlier one.
        addMember(owner, company.id(), company.email("MEMBER"), "MEMBER");
        JsonNode present = first(owner, company, "action=runner_host.registered");
        assertThat(present.get("details").get("cause").asString()).isEqualTo("principal.joined");
        assertThat(present.get("actor").get("display").asString()).isEqualTo("User " + company.email("OWNER"));
        assertThat(items(owner, company, "action=runner_host.registered").stream()
            .filter(event -> !event.get("details").has("cause"))
            .findFirst()
            .orElseThrow()
            .get("actor")
            .get("display")
            .asString()).isEqualTo(name);
        assertThat(first(owner, company, "action=principal.left").get("targetName").asString())
            .isEqualTo(name + " (left)");
    }

    @Test
    void theInstancesChainNamesItsUsers() {
        ApiClient admin = admin();
        String email = uniqueEmail("named");
        UUID user = createUser(admin, email, false);
        assertThat(instanceEvent(user, "user.created").get("targetName").asString()).isEqualTo("User " + email);

        assertThat(admin.patch("/api/admin/users/" + user, Map.of("displayName", "Renamed")).status())
            .isEqualTo(200);
        // Read now: the new name, on the events before the change too.
        assertThat(instanceEvent(user, "user.updated").get("targetName").asString()).isEqualTo("Renamed");
        assertThat(instanceEvent(user, "user.created").get("targetName").asString()).isEqualTo("Renamed");
    }

    private static boolean isAlex(JsonNode event, UUID alex, UUID principal) {
        String type = event.path("targetType").asString("");
        String id = event.path("targetId").asString("");
        return type.equals("user") && id.equals(alex.toString())
                || (type.equals("principal") || type.equals("principal_model_key")) && id.equals(principal.toString());
    }

    private void record(UUID companyId, Actor actor, String targetType, Object targetId) {
        transactions.runInCompany(companyId, () -> auditLog.record(AuditRecord
            .company(companyId, AuditAction.HOLD_SET, actor)
            .target(targetType, targetId)
            .build()));
    }

    private static @Nullable String nameOf(ApiClient reader, TestCompany company, String type, UUID id) {
        JsonNode event = items(reader, company, "targetType=" + type + "&targetId=" + id + "&limit=1").getFirst();
        JsonNode name = event.path("targetName");
        return name.isNull() || name.isMissingNode() ? null : name.asString();
    }

    private static JsonNode first(ApiClient reader, TestCompany company, String query) {
        List<JsonNode> items = items(reader, company, query + "&limit=1");
        assertThat(items).as(query).isNotEmpty();
        return items.getFirst();
    }

    private static List<JsonNode> items(ApiClient reader, TestCompany company, String query) {
        ApiResponse response = reader.get(company.path("/audit-events?" + query));
        assertThat(response.status()).as("%s: %s", query, response).isEqualTo(200);
        List<JsonNode> items = new ArrayList<>();
        response.json().get("items").forEach(items::add);
        return items;
    }

    /** The newest event of the instance's chain of this action whose target is this id. */
    private JsonNode instanceEvent(UUID targetId, String action) {
        ApiClient admin = admin();
        String cursor = null;
        for (int page = 0; page < 20; page++) {
            JsonNode body = admin.get("/api/admin/audit-events?limit=200" + (cursor != null ? "&cursor=" + cursor
                    : "")).json();
            for (JsonNode event : body.get("items")) {
                if (targetId.toString().equals(event.path("targetId").asString(""))
                        && action.equals(event.get("action").asString())) {
                    return event;
                }
            }
            cursor = body.path("nextCursor").isNull() || body.path("nextCursor").isMissingNode() ? null
                    : body.get("nextCursor").asString();
            if (cursor == null) {
                break;
            }
        }
        throw new AssertionError("No instance event on " + targetId);
    }
}
