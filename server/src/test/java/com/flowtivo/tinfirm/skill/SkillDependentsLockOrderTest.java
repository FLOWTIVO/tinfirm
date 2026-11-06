package com.flowtivo.tinfirm.skill;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * The lock order of skills and agents (CONVENTIONS §10): a skill's row before the agents' rows. A skill's delete with
 * {@code removeFromAgents} locks the skill, then each agent that pins it; a member's leaving and a group's deletion ran
 * the agent module's dependent first, which locked their agents, and then the skill module's, which locked their
 * skills, so a leaving member's agent that pinned their own skill, deleted by an admin at that moment, made the two
 * wait on each other ({@code 40P01}, the delete answered {@code 500}). The skill module's dependent now runs first.
 * Each test holds the agent's row, so that the leaving or the group's deletion waits for it with whatever it locked
 * before, and the delete starts meanwhile; then the agent goes, and both must succeed.
 */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SkillDependentsLockOrderTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void aMemberLeavingWhileAnAdminDeletesTheirSkillThatTheirAgentPinsNeverDeadlock(CapturedOutput output)
            throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        String email = uniqueEmail("dana");
        UUID dana = createUser(admin(), email, false);
        addMember(owner, company.id(), email, "MEMBER");
        ApiClient danaClient = loginAs(email);
        UUID skillId = skill(danaClient, company);
        UUID agentId = agent(danaClient, company);
        pin(danaClient, company, agentId, skillId);

        Results results = race(company, agentId,
                () -> owner.delete(company.path("/members/" + dana)),
                () -> owner.delete(company.path("/skills/" + skillId + "?removeFromAgents=true")));

        assertThat(results.first().status()).as("leaving: %s", results.first()).isEqualTo(204);
        assertThat(results.second().status()).as("delete: %s", results.second()).isEqualTo(204);
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
        // The leaving passed the agent and the skill to the admins; the delete then took the skill from the agent.
        JsonNode agent = owner.get(company.path("/agents/" + agentId)).json();
        assertThat(agent.has("ownerPrincipalId")).isFalse();
        assertThat(agent.get("skills")).isEmpty();
        JsonNode deleted = auditEvents(owner, company, "skill.deleted").getFirst().get("details");
        assertThat(deleted.get("agentCount").asInt()).isEqualTo(1);
        assertThat(deleted.get("removedFromAgents").asBoolean()).isTrue();
        assertThat(auditEvents(owner, company, "skill.passed_to_admins")).hasSize(1);
    }

    @Test
    void aGroupsDeletionWhileASkillSharedWithItIsDeletedNeverDeadlock(CapturedOutput output) throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID group = createGroup(owner, company, unique("Writers"), List.of());
        UUID skillId = skill(alice, company);
        UUID agentId = agent(alice, company);
        assertThat(alice.put(company.path("/skills/" + skillId + "/shares"), Map.of("groupIds",
                List.of(group.toString()))).status()).isEqualTo(200);
        assertThat(alice.put(company.path("/agents/" + agentId + "/shares"), Map.of("groupIds",
                List.of(group.toString()))).status()).isEqualTo(200);
        pin(alice, company, agentId, skillId);

        Results results = race(company, agentId,
                () -> owner.delete(company.path("/groups/" + group)),
                () -> alice.delete(company.path("/skills/" + skillId + "?removeFromAgents=true")));

        assertThat(results.first().status()).as("group: %s", results.first()).isEqualTo(204);
        assertThat(results.second().status()).as("delete: %s", results.second()).isEqualTo(204);
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
        JsonNode agent = alice.get(company.path("/agents/" + agentId)).json();
        assertThat(agent.path("shareGroupIds")).extracting(JsonNode::asString).doesNotContain(group.toString());
        assertThat(agent.get("skills")).isEmpty();
        assertThat(auditEvents(owner, company, "skill.deleted").getFirst().get("details").get("agentCount").asInt())
            .isEqualTo(1);
    }

    private record Results(ApiResponse first, ApiResponse second) {
    }

    /**
     * Holds the agent's row while {@code first} runs until it waits for it, then starts {@code second} and lets it run
     * until it waits too (for a skill {@code first} holds, or for the agent behind {@code first}); then the agent goes.
     */
    private static Results race(TestCompany company, UUID agentId, java.util.concurrent.Callable<ApiResponse> first,
            java.util.concurrent.Callable<ApiResponse> second) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (TenantSql holder = TenantSql.in(company.id())) {
            holder.rows("SELECT id FROM agent WHERE id = ? FOR NO KEY UPDATE", agentId);
            Future<ApiResponse> one = threads.submit(first);
            await().atMost(WAIT).until(() -> waitingForLocks(company.id()) == 1);
            Future<ApiResponse> two = threads.submit(second);
            await().atMost(WAIT).until(() -> waitingForLocks(company.id()) == 2);
            holder.commit();
            return new Results(one.get(60, TimeUnit.SECONDS), two.get(60, TimeUnit.SECONDS));
        }
        finally {
            threads.shutdownNow();
        }
    }

    private static UUID skill(ApiClient actor, TestCompany company) {
        String name = "write-story-" + UUID.randomUUID().toString().substring(0, 8);
        ApiResponse created = actor.upload(company.path("/skills"), SkillIntegrationTest.skillMd(name,
                "Writes stories"));
        assertThat(created.status()).as("skill: %s", created).isEqualTo(201);
        return UUID.fromString(created.json().get("id").asString());
    }

    private static UUID agent(ApiClient actor, TestCompany company) {
        ApiResponse created = actor.post(company.path("/agents"), Map.of("name", unique("Analyst"), "runtimes",
                List.of("CLAUDE_CODE")));
        assertThat(created.status()).as("agent: %s", created).isEqualTo(201);
        return UUID.fromString(created.json().get("id").asString());
    }

    private static void pin(ApiClient actor, TestCompany company, UUID agentId, UUID skillId) {
        ApiResponse pinned = actor.put(company.path("/agents/" + agentId + "/skills"), Map.of("skills",
                List.of(Map.of("skillId", skillId.toString()))));
        assertThat(pinned.status()).as("pin: %s", pinned).isEqualTo(200);
    }

    private static long waitingForLocks(UUID companyId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'");
        }
    }
}
