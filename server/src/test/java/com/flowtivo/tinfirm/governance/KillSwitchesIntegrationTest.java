package com.flowtivo.tinfirm.governance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.company.CompanyEvents;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RecordedEvents;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.system.SystemEvents;

import tools.jackson.databind.JsonNode;

/**
 * The kill switches of package 6 (the package 6 contract, §5.4; open points 5, 6, 7 and 24): a member's suspension,
 * a user's deactivation, the company's stop and a system's pause, each with what it stops, its running turns, who may
 * pull and undo it, its audit (ids only, never a reason or a person's name) and the switches' page.
 */
class KillSwitchesIntegrationTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(20);

    // ---- A member's suspension ----------------------------------------------------------------------------------

    @Test
    void aSuspendedMemberWritesReadsAndRunsNothingUntilResumed() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID memberUser = company.userId("MEMBER");
        UUID principal = memberPrincipal(company, memberUser);
        // A running turn of the member's conversation, cancelled by the suspension.
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "susp-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, principal, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        TestRuns.ofConversation(company.id(), runId, conversationId, key, agentId, principal, 1, "RUNNING");

        // Only admins suspend; the reason is required.
        assertThat(member.post(company.path("/members/" + company.userId("ADMIN") + "/suspend"),
                Map.of("reason", "test")).status()).isEqualTo(403);
        assertThat(owner.post(company.path("/members/" + memberUser + "/suspend"), Map.of("reason", " "))
            .status()).isEqualTo(400);
        ApiResponse suspended = owner.post(company.path("/members/" + memberUser + "/suspend"),
                Map.of("reason", "Laptop stolen, looking into it"));
        assertThat(suspended.status()).as("%s", suspended).isEqualTo(200);
        assertThat(suspended.json().get("status").asString()).isEqualTo("SUSPENDED");
        assertThat(suspended.json().get("cause").asString()).isEqualTo("ADMIN");
        assertThat(suspended.json().get("principalId").asString()).isEqualTo(principal.toString());

        // Reads are 403, writes 409 PRINCIPAL_SUSPENDED; the company's page and the switches' page stay.
        ApiResponse inbox = member.get(company.path("/inbox"));
        assertThat(inbox.status()).isEqualTo(403);
        assertThat(inbox.problemCode()).isEqualTo("PRINCIPAL_SUSPENDED");
        ApiResponse write = member.post(company.path("/agents"), Map.of("name", unique("Agent"), "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(write.status()).isEqualTo(409);
        assertThat(write.problemCode()).isEqualTo("PRINCIPAL_SUSPENDED");
        ApiResponse decide = member.post(company.path("/decisions/" + UUID.randomUUID() + "/decide"),
                Map.of("answer", "yes"));
        assertThat(decide.problemCode()).isEqualTo("PRINCIPAL_SUSPENDED");
        assertThat(member.get(company.path("")).status()).isEqualTo(200);
        JsonNode page = member.get(company.path("/kill-switches")).json();
        assertThat(page.get("mySuspension").get("kind").asString()).isEqualTo("MEMBER_SUSPENDED");
        assertThat(page.get("mySuspension").get("reason").asString()).isEqualTo("Laptop stolen, looking into it");
        assertThat(page.get("switches")).hasSize(1);

        // Admins see it among the switches, and on the members' list.
        JsonNode adminPage = owner.get(company.path("/kill-switches")).json();
        assertThat(kinds(adminPage)).contains("MEMBER_SUSPENDED");
        assertThat(count(adminPage, "MEMBER_SUSPENDED")).isEqualTo(1);
        // The member's row names their user, which resuming them takes, beside their principal.
        assertThat(adminPage.get("switches")).filteredOn(row -> row.get("kind").asString().equals("MEMBER_SUSPENDED"))
            .singleElement()
            .satisfies(row -> {
                assertThat(row.get("targetId").asString()).isEqualTo(principal.toString());
                assertThat(row.get("userId").asString()).isEqualTo(memberUser.toString());
            });
        assertThat(page.get("mySuspension").get("userId").asString()).isEqualTo(memberUser.toString());
        JsonNode members = owner.get(company.path("/members")).json().get("items");
        for (JsonNode row : members) {
            assertThat(row.get("suspended").asBoolean()).isEqualTo(row.get("userId").asString()
                .equals(memberUser.toString()));
        }

        // Its running turn is cancelled after the commit, with the cause.
        await().atMost(WAIT).until(() -> auditEvents(owner, company, "run.cancelled").stream()
            .anyMatch(event -> event.get("targetId").asString().equals(runId.toString())));
        JsonNode cancelled = auditEvents(owner, company, "run.cancelled").getFirst();
        assertThat(cancelled.get("details").get("cause").asString()).isEqualTo("principal.suspended");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM run_command WHERE run_id = ? AND type = 'CANCEL'", runId))
                .isEqualTo(1);
        }

        // The audit holds the member's user id, never a name or the reason (ADR-0010).
        JsonNode audit = auditEvents(owner, company, "principal.suspended").getFirst();
        assertThat(audit.get("details").get("userId").asString()).isEqualTo(memberUser.toString());
        assertThat(audit.get("details").get("kind").asString()).isEqualTo("MEMBER");
        assertThat(audit.get("details").get("cause").asString()).isEqualTo("ADMIN");
        assertThat(audit.get("details").has("name")).isFalse();
        assertThat(audit.get("details").toString()).doesNotContain("Laptop");

        // Resumed: the member reads and writes again.
        ApiResponse resumed = owner.post(company.path("/members/" + memberUser + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(resumed.json().get("status").asString()).isEqualTo("ACTIVE");
        assertThat(member.get(company.path("/inbox")).status()).isEqualTo(200);
        assertThat(auditEvents(owner, company, "principal.resumed")).hasSize(1);
    }

    @Test
    void nobodySuspendsThemselvesNorTheLastActiveOwner() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient adminRole = loginAs(company.email("ADMIN"));
        UUID ownerUser = company.userId("OWNER");

        ApiResponse self = owner.post(company.path("/members/" + ownerUser + "/suspend"), Map.of("reason", "me"));
        assertThat(self.problemCode()).isEqualTo("CANNOT_SUSPEND_SELF");
        // ADMIN suspends no OWNER.
        assertThat(adminRole.post(company.path("/members/" + ownerUser + "/suspend"), Map.of("reason", "x"))
            .status()).isEqualTo(403);
        // An instance admin under break-glass may, but never the last active OWNER.
        ApiResponse last = admin().post(company.path("/members/" + ownerUser + "/suspend"), Map.of("reason", "x"));
        assertThat(last.status()).as("%s", last).isEqualTo(409);
        assertThat(last.problemCode()).isEqualTo("LAST_OWNER");

        // With a second OWNER, one is suspended; the other is then the last active one for demotion and removal too.
        String second = uniqueEmail("owner2");
        UUID secondUser = createUser(admin(), second, false);
        addMember(owner, company.id(), second, "OWNER");
        assertThat(owner.post(company.path("/members/" + secondUser + "/suspend"), Map.of("reason", "x")).status())
            .isEqualTo(200);
        assertThat(loginAs(second).patch(company.path("/members/" + ownerUser), Map.of("role", "ADMIN")).status())
            .isEqualTo(409);
        assertThat(owner.patch(company.path("/members/" + ownerUser), Map.of("role", "ADMIN")).problemCode())
            .isEqualTo("LAST_OWNER");
        assertThat(owner.delete(company.path("/members/" + ownerUser)).problemCode()).isEqualTo("LAST_OWNER");
    }

    @Test
    void deactivatingAUserSuspendsThemInEveryCompanyAndReactivatingLiftsOnlyThat() {
        TestCompany a = companyWithRoles();
        TestCompany b = companyWithRoles();
        String email = uniqueEmail("roamer");
        UUID user = createUser(admin(), email, false);
        addMember(loginAs(a.email("OWNER")), a.id(), email, "MEMBER");
        addMember(loginAs(b.email("OWNER")), b.id(), email, "MEMBER");
        // In B an admin suspended them already, for a reason of their own.
        assertThat(loginAs(b.email("OWNER")).post(b.path("/members/" + user + "/suspend"), Map.of("reason", "B's"))
            .status()).isEqualTo(200);

        assertThat(admin().patch("/api/admin/users/" + user, Map.of("active", false)).status()).isEqualTo(200);
        JsonNode inA = suspensionOf(loginAs(a.email("OWNER")), a, user);
        assertThat(inA.get("cause").asString()).isEqualTo("USER_DEACTIVATED");
        assertThat(auditEvents(loginAs(a.email("OWNER")), a, "principal.suspended").getFirst().get("details")
            .get("cause").asString()).isEqualTo("USER_DEACTIVATED");
        // An admin does not lift what the deactivation did.
        assertThat(loginAs(a.email("OWNER")).post(a.path("/members/" + user + "/resume"), Map.of()).problemCode())
            .isEqualTo("PRINCIPAL_SUSPENDED");

        assertThat(admin().patch("/api/admin/users/" + user, Map.of("active", true)).status()).isEqualTo(200);
        assertThat(suspensionOrNull(loginAs(a.email("OWNER")), a, user)).isNull();
        // B's own suspension stays.
        assertThat(suspensionOf(loginAs(b.email("OWNER")), b, user).get("cause").asString()).isEqualTo("ADMIN");
    }

    // ---- The company's stop ---------------------------------------------------------------------------------------

    @Test
    void theCompanysStopCancelsWhatRunsAndHoldsEverythingUntilLifted() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "stop-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, principal, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        TestRuns.ofConversation(company.id(), runId, conversationId, key, agentId, principal, 1, "RUNNING");

        assertThat(member.post(company.path("/runs-stop"), Map.of("reason", "no")).status()).isEqualTo(403);
        ApiResponse stopped = loginAs(company.email("ADMIN")).post(company.path("/runs-stop"),
                Map.of("reason", "Incident 42: leaked token"));
        assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);
        assertThat(stopped.json().get("runsCancelled").asInt()).isEqualTo(1);
        assertThat(stopped.json().get("runsStop").get("reason").asString()).isEqualTo("Incident 42: leaked token");

        // Every member sees the banner; nothing starts.
        JsonNode banner = member.get(company.path("/kill-switches")).json();
        assertThat(banner.get("runsStop").get("reason").asString()).isEqualTo("Incident 42: leaked token");
        ApiResponse start = member.post(company.path("/conversations"), Map.of("agentId", agentId.toString(),
                "message", "hello"));
        assertThat(start.status()).as("%s", start).isEqualTo(409);
        assertThat(start.problemCode()).isEqualTo("COMPANY_STOPPED");
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.count("SELECT count(*) FROM run_command WHERE run_id = ? AND type = 'CANCEL'", runId))
                .isEqualTo(1);
        }
        JsonNode audit = auditEvents(owner, company, "company.runs_stopped").getFirst();
        assertThat(audit.get("details").get("runsCancelled").get(0).asString()).isEqualTo(runId.toString());
        assertThat(audit.get("details").toString()).doesNotContain("Incident");
        JsonNode cancel = auditEvents(owner, company, "run.cancelled").getFirst();
        assertThat(cancel.get("details").get("cause").asString()).isEqualTo("company.runs_stopped");

        // Stopping again changes nothing; the lift is the company's.
        assertThat(owner.post(company.path("/runs-stop"), Map.of("reason", "again")).json().get("runsStop")
            .get("reason").asString()).isEqualTo("Incident 42: leaked token");
        assertThat(auditEvents(owner, company, "company.runs_stopped")).hasSize(1);
        assertThat(member.delete(company.path("/runs-stop")).status()).isEqualTo(403);
        ApiResponse lifted = owner.delete(company.path("/runs-stop"));
        assertThat(lifted.status()).as("%s", lifted).isEqualTo(200);
        assertThat(owner.get(company.path("/kill-switches")).json().path("runsStop").isMissingNode()
                || owner.get(company.path("/kill-switches")).json().get("runsStop").isNull()).isTrue();
        assertThat(auditEvents(owner, company, "company.runs_resumed")).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), CompanyEvents.RunsStopped.class)).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), CompanyEvents.RunsResumed.class)).hasSize(1);
    }

    @Test
    void anInstanceAdminUnderBreakGlassStopsTheCompany() {
        TestCompany company = companyWithRoles();
        ApiResponse stopped = admin().post(company.path("/runs-stop"), Map.of("reason", "Operator stop"));
        assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);
        assertThat(admin().delete(company.path("/runs-stop")).status()).isEqualTo(200);
    }

    // ---- A system's pause ------------------------------------------------------------------------------------------

    @Test
    void aPausedSystemIsUsedForNothingUntilResumed() {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient member = loginAs(company.email("MEMBER"));
        UUID git = createSystem(owner, company, unique("Git"), "GIT", null, false);

        assertThat(member.post(company.path("/systems/" + git + "/pause"), Map.of("reason", "x")).status())
            .isEqualTo(403);
        ApiResponse paused = owner.post(company.path("/systems/" + git + "/pause"),
                Map.of("reason", "The app leaked", "stopRunning", true));
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        assertThat(paused.json().get("runsCancelled").asInt()).isZero();
        assertThat(member.get(company.path("/systems/" + git)).json().get("paused").asBoolean()).isTrue();
        assertThat(owner.post(company.path("/systems/" + git + "/test"), Map.of()).problemCode())
            .isEqualTo("SYSTEM_PAUSED");
        JsonNode page = owner.get(company.path("/kill-switches")).json();
        JsonNode system = switchOf(page, "SYSTEM_PAUSED");
        assertThat(system.get("targetId").asString()).isEqualTo(git.toString());
        assertThat(system.get("reason").asString()).isEqualTo("The app leaked");
        assertThat(system.get("pausedTriggers")).isEmpty();
        // A member sees no system's pause on the switches' page.
        assertThat(kinds(member.get(company.path("/kill-switches")).json())).doesNotContain("SYSTEM_PAUSED");
        JsonNode audit = auditEvents(owner, company, "system.paused").getFirst();
        assertThat(audit.get("details").get("stopRunning").asBoolean()).isTrue();
        assertThat(audit.get("details").toString()).doesNotContain("leaked");

        ApiResponse resumed = owner.post(company.path("/systems/" + git + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(member.get(company.path("/systems/" + git)).json().get("paused").asBoolean()).isFalse();
        assertThat(auditEvents(owner, company, "system.resumed").getFirst().get("details").get("resumeTriggers")
            .asBoolean()).isTrue();
        assertThat(RecordedEvents.of(company.id(), SystemEvents.SystemPaused.class)).hasSize(1);
        assertThat(RecordedEvents.of(company.id(), SystemEvents.SystemResumed.class)).hasSize(1);
    }

    @Test
    void aPausedSystemsRepositoryHoldsTheTurnAndItsToolsAreNotListed() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        UUID git = createSystem(owner, company, unique("Git"), "GIT", null, false);
        ApiResponse repository = owner.post(company.path("/systems/" + git + "/repositories"),
                Map.of("url", "https://git.example.test/" + unique("repo") + ".git"));
        assertThat(repository.status()).as("%s", repository).isEqualTo(201);
        UUID repositoryId = UUID.fromString(repository.json().get("id").asString());
        UUID principal = memberPrincipal(company, company.userId("MEMBER"));
        UUID agentId = TestRuns.agent(company.id());
        UUID conversationId = UUID.randomUUID();
        String key = "pause-" + conversationId.toString().substring(0, 8);
        TestConversations.bare(company.id(), conversationId, key, agentId, principal, "CLAUDE_CODE");
        UUID runId = UUID.randomUUID();
        try (TenantSql sql = TenantSql.in(company.id())) {
            sql.update("UPDATE conversation SET workspace = ?::jsonb WHERE id = ?",
                    "[{\"repositoryId\":\"" + repositoryId + "\",\"readOnly\":false,\"source\":\"AGENT\"}]",
                    conversationId);
            sql.update("""
                    INSERT INTO run (id, company_id, conversation_id, conversation_key, agent_id, runtime, turn_reason,
                                     turn_number, acting_principal_id, payer_principal_id, model_access, status,
                                     epoch, branch, created_at)
                    VALUES (?, ?, ?, ?, ?, 'CLAUDE_CODE', 'FIRST', 1, ?, ?, 'BUDGET', 'QUEUED', 0, ?, now())""",
                    runId, company.id(), conversationId, key, agentId, principal, principal, "agent/" + key);
            sql.commit();
        }
        assertThat(owner.post(company.path("/systems/" + git + "/pause"), Map.of("reason", "hold")).status())
            .isEqualTo(200);
        await().atMost(WAIT).until(() -> queueReason(company, runId), "SYSTEM_PAUSED"::equals);
        assertThat(owner.post(company.path("/runs-stop"), Map.of("reason", "hold all")).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> queueReason(company, runId), "COMPANY_STOPPED"::equals);
        // A queued run that no host took is not cancelled by the stop: it waits.
        try (TenantSql sql = TenantSql.in(company.id())) {
            assertThat(sql.column("SELECT status FROM run WHERE id = ?", runId)).containsExactly("QUEUED");
        }
    }

    private static String queueReason(TestCompany company, UUID runId) throws Exception {
        try (TenantSql sql = TenantSql.in(company.id())) {
            List<Object> reasons = sql.column("SELECT queue_reason FROM run WHERE id = ?", runId);
            return reasons.isEmpty() || reasons.getFirst() == null ? "" : reasons.getFirst().toString();
        }
    }

    // ---- Helpers -------------------------------------------------------------------------------------------------

    private JsonNode suspensionOf(ApiClient reader, TestCompany company, UUID userId) {
        JsonNode found = suspensionOrNull(reader, company, userId);
        assertThat(found).as("suspension of %s", userId).isNotNull();
        return found;
    }

    private static JsonNode suspensionOrNull(ApiClient reader, TestCompany company, UUID userId) {
        UUID principal = memberPrincipal(company, userId);
        for (JsonNode item : reader.get(company.path("/kill-switches")).json().get("switches")) {
            if (item.get("targetId").asString().equals(principal.toString())) {
                return item;
            }
        }
        return null;
    }

    private static List<String> kinds(JsonNode page) {
        List<String> kinds = new ArrayList<>();
        page.get("switches").forEach(item -> kinds.add(item.get("kind").asString()));
        return kinds;
    }

    private static int count(JsonNode page, String kind) {
        for (JsonNode count : page.get("counts")) {
            if (count.get("kind").asString().equals(kind)) {
                return count.get("count").asInt();
            }
        }
        return 0;
    }

    private static JsonNode switchOf(JsonNode page, String kind) {
        for (JsonNode item : page.get("switches")) {
            if (item.get("kind").asString().equals(kind)) {
                return item;
            }
        }
        throw new AssertionError("no " + kind + " in " + page);
    }
}
