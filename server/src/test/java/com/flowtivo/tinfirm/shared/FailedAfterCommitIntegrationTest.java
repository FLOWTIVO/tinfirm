package com.flowtivo.tinfirm.shared;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.web.ServerBusy;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.IntegrationTest;
import com.flowtivo.tinfirm.support.RefusedAuditRecords;
import com.flowtivo.tinfirm.support.TestConversations;

/**
 * A step kept for after a request's transaction that fails once the transaction committed: the request is answered
 * 500 with a detail that says a change of it may already be saved, without {@code Retry-After}, and never {@code 503 SERVER_BUSY},
 * even when the step found no free database connection; the change stays. A read whose record finds no connection
 * saved nothing and returned nothing: that one is {@code SERVER_BUSY}.
 */
class FailedAfterCommitIntegrationTest extends IntegrationTest {

    private static final String OWNER_READS = "com.flowtivo.tinfirm.conversation.internal.OwnerReads";

    private static final String READ = "conversation.read";

    private record Setup(TestCompany company, ApiClient owner, ApiClient member, UUID agentId, UUID memberPrincipal) {

        UUID conversation(String key) {
            UUID id = UUID.randomUUID();
            TestConversations.bare(company.id(), id, key, agentId, memberPrincipal, "CLAUDE_CODE");
            return id;
        }
    }

    private Setup setup() {
        TestCompany company = companyWithRoles();
        ApiClient member = loginAs(company.email("MEMBER"));
        ApiResponse agent = member.post(company.path("/agents"), Map.of("name", unique("Helper"), "runtimes",
                List.of("CLAUDE_CODE"), "instructions", "", "systems", List.of(), "repositories", List.of(),
                "shareGroupIds", List.of()));
        assertThat(agent.status()).as("%s", agent).isEqualTo(201);
        return new Setup(company, loginAs(company.email("OWNER")), member,
                UUID.fromString(agent.json().get("id").asString()),
                memberPrincipal(company, company.userId("MEMBER")));
    }

    /** A pool busy at the moment the kept record asks for a connection: the action's change is saved, never "busy". */
    @Test
    void aRecordThatFindsNoConnectionAfterTheActionsCommitIsAFailureAfterTheSaveNeverBusy() throws Exception {
        Setup setup = setup();
        UUID conversation = setup.conversation(unique("c"));

        ApiResponse ended;
        try (AutoCloseable busy = ConnectionWatch.timeOutKeptWorkAt(OWNER_READS)) {
            ended = setup.owner().post(setup.company().path("/conversations/" + conversation + "/end"), Map.of());
        }

        assertThat(ended.status()).as("%s", ended).isEqualTo(500);
        assertThat(ended.problemCode()).isNull();
        assertThat(ended.json().get("detail").asString()).isEqualTo(ServerBusy.SAVED_THEN_FAILED);
        assertThat(ended.header("Retry-After")).isNull();
        assertThat(setup.member().get(setup.company().path("/conversations/" + conversation)).json().get("state")
            .asString()).isEqualTo("ENDED");
    }

    /** A read whose kept record finds the pool busy: nothing was saved and nothing is returned, so it is "busy". */
    @Test
    void aReadWhoseRecordFindsNoConnectionIsAnsweredBusyWithNothingReturned() throws Exception {
        Setup setup = setup();
        String key = unique("c");
        setup.conversation(key);

        ApiResponse list;
        try (AutoCloseable busy = ConnectionWatch.timeOutKeptWorkAt(OWNER_READS)) {
            list = setup.owner().get(setup.company().path("/conversations"));
        }

        assertThat(list.status()).as("%s", list).isEqualTo(503);
        assertThat(list.problemCode()).isEqualTo("SERVER_BUSY");
        assertThat(list.header("Retry-After")).isEqualTo("2");
        assertThat(list.body()).doesNotContain(key);
        assertThat(auditEvents(setup.owner(), setup.company(), READ)).isEmpty();
    }

    /** A break-glass write whose record of the access fails right after its commit: saved, then failed. */
    @Test
    void aBreakGlassWriteWhoseRecordFailsAfterItsCommitIsAFailureAfterTheSave() throws Exception {
        TestCompany company = companyWithRoles();
        ApiClient admin = admin();
        UUID adminId = UUID.fromString(admin.get("/api/me").json().get("id").asString());

        ApiResponse appointed;
        try (RefusedAuditRecords ignored = RefusedAuditRecords.of("admin.company.accessed", adminId)) {
            appointed = admin.patch(company.path("/members/" + company.userId("ADMIN")), Map.of("role", "OWNER"));
        }

        assertThat(appointed.status()).as("%s", appointed).isEqualTo(500);
        assertThat(appointed.json().get("detail").asString()).isEqualTo(ServerBusy.SAVED_THEN_FAILED);
        assertThat(appointed.header("Retry-After")).isNull();
        assertThat(auditEvents(loginAs(company.email("OWNER")), company, "member.role.changed").getFirst()
            .get("details").get("role").asString()).isEqualTo("OWNER");
    }
}
