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
 * A change of an agent's skills read whether each skill it added or moved was live without a lock on
 * the skill, so a delete that committed between the check and the pin left a pin that the delete never saw: a
 * skill added to an agent after its delete, or one that {@code removeFromAgents} missed because it enumerated the pins
 * before the new one committed. A change now locks the skills it names {@code FOR KEY SHARE} before the agent's row, and
 * a delete locks the skill {@code FOR UPDATE} before it counts and enumerates the pins (CONVENTIONS §10), so the two run
 * one after the other. Each test holds the company's audit chain head, so that the side that goes first waits at its
 * commit with its work done, and releases it once the other side waits too: at the skill's row, or, without the lock,
 * at the head with its own work done on what it read before.
 */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class SkillPinDeleteRaceTest extends IntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(30);

    @Test
    void aDeleteWithRemoveFromAgentsWaitsForAPinAddedMeanwhileAndTakesItToo(CapturedOutput output) throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient owner = loginAs(company.email("OWNER"));
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID skillId = skill(alice, company);
        UUID agentId = agent(alice, company);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (TenantSql holder = TenantSql.in(company.id())) {
            holder.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE", company.id());
            // The pin: checked, written, and waiting at its commit.
            Future<ApiResponse> pin = threads.submit(() -> alice.put(company.path("/agents/" + agentId + "/skills"),
                    Map.of("skills", List.of(Map.of("skillId", skillId.toString())))));
            await().atMost(WAIT).until(() -> waiting(company.id(), "%audit_chain_head%") == 1);
            // The delete waits for the pin at the skill's row; without that lock it found no agent that pins the
            // skill, and waited at its commit beside the pin.
            Future<ApiResponse> delete = threads.submit(() -> alice.delete(company.path("/skills/" + skillId
                    + "?removeFromAgents=true")));
            await().atMost(WAIT).until(() -> waiting(company.id(), "%FROM skill WHERE%FOR UPDATE%") == 1
                    || waiting(company.id(), "%audit_chain_head%") == 2);
            holder.commit();

            ApiResponse pinned = pin.get(60, TimeUnit.SECONDS);
            assertThat(pinned.status()).as("%s", pinned).isEqualTo(200);
            ApiResponse deleted = delete.get(60, TimeUnit.SECONDS);
            assertThat(deleted.status()).as("%s", deleted).isEqualTo(204);
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
        // The delete saw the pin and took it: no agent keeps the deleted skill.
        assertThat(alice.get(company.path("/agents/" + agentId)).json().get("skills")).isEmpty();
        assertThat(pins(company.id(), skillId)).isZero();
        JsonNode audit = auditEvents(owner, company, "skill.deleted").getFirst().get("details");
        assertThat(audit.get("agentCount").asInt()).isEqualTo(1);
        // The editor's change, and the delete's own.
        assertThat(auditEvents(owner, company, "agent.skills_changed"))
            .extracting(event -> event.get("details").has("cause") ? event.get("details").get("cause").asString()
                    : "editor")
            .containsExactlyInAnyOrder("editor", "skill.deleted");
    }

    @Test
    void aPinThatWaitsForADeleteIsRefusedAsDeleted(CapturedOutput output) throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient alice = loginAs(company.email("MEMBER"));
        UUID skillId = skill(alice, company);
        String name = alice.get(company.path("/skills/" + skillId)).json().get("name").asString();
        // One agent adds the skill; another pins its first version and moves to its second.
        UUID adding = agent(alice, company);
        UUID moving = agent(alice, company);
        assertThat(alice.put(company.path("/agents/" + moving + "/skills"), Map.of("skills", List.of(Map.of(
                "skillId", skillId.toString())))).status()).isEqualTo(200);
        assertThat(alice.upload(company.path("/skills/" + skillId + "/versions"), SkillIntegrationTest.skillMd(name,
                "Writes stories with acceptance criteria")).status()).isEqualTo(201);
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try (TenantSql holder = TenantSql.in(company.id())) {
            holder.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE", company.id());
            // The delete (the agents keep the skill): written, and waiting at its commit.
            Future<ApiResponse> delete = threads.submit(() -> alice.delete(company.path("/skills/" + skillId)));
            await().atMost(WAIT).until(() -> waiting(company.id(), "%audit_chain_head%") == 1);
            // The changes wait for it at the skill's row; without that lock they read the skill live, wrote their
            // pins, and waited at their commits beside it.
            Future<ApiResponse> add = threads.submit(() -> alice.put(company.path("/agents/" + adding + "/skills"),
                    Map.of("skills", List.of(Map.of("skillId", skillId.toString())))));
            Future<ApiResponse> move = threads.submit(() -> alice.put(company.path("/agents/" + moving + "/skills"),
                    Map.of("skills", List.of(Map.of("skillId", skillId.toString(), "version", 2)))));
            await().atMost(WAIT).until(() -> waiting(company.id(), "%FROM skill WHERE%FOR KEY SHARE%") == 2
                    || waiting(company.id(), "%audit_chain_head%") == 3);
            holder.commit();

            ApiResponse deleted = delete.get(60, TimeUnit.SECONDS);
            assertThat(deleted.status()).as("%s", deleted).isEqualTo(204);
            for (Future<ApiResponse> change : List.of(add, move)) {
                ApiResponse refused = change.get(60, TimeUnit.SECONDS);
                assertThat(refused.status()).as("%s", refused).isEqualTo(400);
                assertThat(refused.problemCode()).isEqualTo("SKILL_DELETED");
            }
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
        // No agent added the deleted skill or moved it: the one that pinned it keeps its version.
        assertThat(alice.get(company.path("/agents/" + adding)).json().get("skills")).isEmpty();
        JsonNode kept = alice.get(company.path("/agents/" + moving)).json().get("skills").get(0);
        assertThat(kept.get("version").asInt()).isEqualTo(1);
        assertThat(kept.get("deleted").asBoolean()).isTrue();
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

    /** The backends of this database that wait for a lock while running a statement like this. */
    private static long waiting(UUID companyId, String like) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock' AND query LIKE ?""", like);
        }
    }

    private static long pins(UUID companyId, UUID skillId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM agent_skill WHERE skill_id = ?", skillId);
        }
    }
}
