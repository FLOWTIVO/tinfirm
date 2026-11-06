package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TestConversations;

import tools.jackson.databind.JsonNode;

/**
 * What a restart leaves of the calls that run after a commit (the package 3 contract, §5.3, §5.8; CONVENTIONS §10):
 * an approved call whose transaction committed but which never ran, and a parked call allowed but never taken, run
 * once at the next pass of the recovery; a call that was running when Tinfirm stopped runs again when its tool is
 * {@code SAFE} (package R, §3.3), and is otherwise not run again, since it may have written, but settled as of unknown
 * outcome with the words of {@code TOOL_INTERRUPTED}. Then the turn that tells it is requested. Two passes at once, as
 * two replicas run them, run a call once. The tests stand in for the restart by writing the rows as the stopped server
 * left them. TEST FIXTURE ONLY: tokens are test values.
 */
class CallRecoveryIntegrationTest extends ConversationBench {

    @Autowired
    CallRecovery recovery;

    @Test
    void anApprovedCallThatNeverRanRunsOnceAfterARestart() {
        Approved approved = approvedComment("test-recover-approved", "SHOP-7");
        // Approved ten minutes ago; the server stopped before the call ran.
        update(approved.bench().companyId(), """
                UPDATE decision SET status = 'DECIDED', outcome = 'APPROVED', decided_by_principal_id = ?,
                                    decided_at = now() - interval '10 minutes'
                 WHERE id = ?""", approved.bench().personPrincipalId(), approved.decisionId());

        recovery.recover();

        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(approved.comment())));
        assertThat(result(approved)).contains("Commented on SHOP-7 (comment 10900)");
        assertThat(requested(approved.conversation())).containsExactly("DECIDED");
        assertThat(auditEvents(approved.bench().owner(), approved.bench().company(), "tool.called")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("decisionId").asString())
                .isEqualTo(approved.decisionId().toString()));
        // A later pass finds nothing to do.
        recovery.recover();
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(approved.comment())));
    }

    @Test
    void anApprovedCallThatWasRunningAtARestartIsNotRunTwice() {
        Approved approved = approvedComment("test-recover-running", "SHOP-8");
        // Taken to run ten minutes ago; the server stopped during the call, which may have written.
        update(approved.bench().companyId(), """
                UPDATE decision SET status = 'DECIDED', outcome = 'APPROVED', decided_by_principal_id = ?,
                                    decided_at = now() - interval '11 minutes',
                                    call_started_at = now() - interval '10 minutes'
                 WHERE id = ?""", approved.bench().personPrincipalId(), approved.decisionId());

        recovery.recover();

        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(approved.comment())));
        // UNSAFE: the words of TOOL_INTERRUPTED, which name the tool and the system.
        assertThat(result(approved)).contains("_comment", "TOOL_INTERRUPTED", "Tinfirm restarted during the call",
                "may or may not have taken effect");
        assertThat(requested(approved.conversation())).containsExactly("DECIDED");
    }

    @Test
    void aSafeApprovedCallThatWasRunningAtARestartRunsAgain() {
        // A read the person approves each time: get under ASK.
        Bench bench = bench(List.of(Map.of("name", "get", "mode", "ASK"), Map.of("name", "search", "mode", "ALLOW")));
        String handle = String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
        connectOwnJira(bench, "test-recover-safe", "sara");
        Conversation conversation = conversation(bench);
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-14")))
            .withHeader("Authorization", equalTo("Bearer test-recover-safe"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10014", "SHOP-14", "Safe again", "", null, List.of(),
                    "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        UUID decision = pendingId(call(conversation, handle + "_get", Map.of("key", "SHOP-14")));
        endTurn(conversation);
        // Taken to run ten minutes ago; the server stopped during the call. A read: it runs again.
        update(bench.companyId(), """
                UPDATE decision SET status = 'DECIDED', outcome = 'APPROVED', decided_by_principal_id = ?,
                                    decided_at = now() - interval '11 minutes',
                                    call_started_at = now() - interval '10 minutes'
                 WHERE id = ?""", bench.personPrincipalId(), decision);

        recovery.recover();

        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-14"))));
        assertThat(String.valueOf(transactions.inCompany(bench.companyId(), () -> decisionStore.find(decision))
            .orElseThrow().result())).contains("Safe again").doesNotContain("TOOL_INTERRUPTED");
        assertThat(requested(conversation)).containsExactly("DECIDED");
        // A later pass finds nothing to do.
        recovery.recover();
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-14"))));
    }

    @Test
    void twoPassesAtOnceRunAnApprovedCallOnce() throws Exception {
        Approved approved = approvedComment("test-recover-twice", "SHOP-9");
        update(approved.bench().companyId(), """
                UPDATE decision SET status = 'DECIDED', outcome = 'APPROVED', decided_by_principal_id = ?,
                                    decided_at = now() - interval '10 minutes'
                 WHERE id = ?""", approved.bench().personPrincipalId(), approved.decisionId());

        ExecutorService replicas = Executors.newFixedThreadPool(2);
        try {
            CyclicBarrier together = new CyclicBarrier(2);
            List<Future<Integer>> passes = new ArrayList<>();
            for (int i = 0; i < 2; i++) {
                passes.add(replicas.submit(() -> {
                    together.await();
                    return recovery.recover();
                }));
            }
            for (Future<Integer> pass : passes) {
                pass.get(60, TimeUnit.SECONDS);
            }
        }
        finally {
            replicas.shutdownNow();
        }

        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(approved.comment())));
        assertThat(result(approved)).contains("Commented on SHOP-9 (comment 10900)");
        assertThat(auditEvents(approved.bench().owner(), approved.bench().company(), "tool.called")).hasSize(1);
    }

    @Test
    void parkedCallsLostAtARestartRunOnceOrSettleAsOfUnknownOutcome() {
        Bench bench = bench();
        UUID accountId = connectOwnJira(bench, "test-recover-parked", "rita");
        Conversation conversation = conversation(bench);
        String handle = String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
        UUID bindingId = TestConversations.bind(bench.companyId(), conversation.id(), bench.jiraId(), accountId,
                bench.personPrincipalId(), null);
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-11")))
            .withHeader("Authorization", equalTo("Bearer test-recover-parked"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10011", "SHOP-11", "Stuck", "", null, List.of(), "Open",
                    "2026-10-01T10:00:00.000+0200", List.of()))));
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-12")))
            .withHeader("Authorization", equalTo("Bearer test-recover-parked"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10012", "SHOP-12", "Read again", "", null, List.of(),
                    "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        String comment = bench.jira().url("/rest/api/2/issue/SHOP-13/comment");
        JiraStub.server().stubFor(post(urlEqualTo(comment)).withHeader("Authorization",
                equalTo("Bearer test-recover-parked")).willReturn(JiraStub.json(201, Map.of("id", "10913"))));
        // The binding was bound ten minutes ago: one call allowed and never taken, a read and a comment taken and
        // running when the server stopped.
        UUID allowed = UUID.randomUUID();
        UUID running = UUID.randomUUID();
        UUID writing = UUID.randomUUID();
        update(bench.companyId(), "UPDATE conversation_binding SET waiting_calls = CAST(? AS jsonb) WHERE id = ?",
                "[" + parkedCall(conversation, allowed, handle, "SHOP-11", "ALLOWED") + ","
                        + parkedCall(conversation, running, handle, "SHOP-12", "RUNNING") + ","
                        + parkedComment(conversation, writing, handle, "SHOP-13") + "]",
                bindingId);
        endTurn(conversation);

        recovery.recover();

        // The read is SAFE (package R, §3.3): it runs again. The comment may have written: never run again.
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-11"))));
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-12"))));
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));
        List<JsonNode> calls = new ArrayList<>();
        JSON.readTree(String.valueOf(bindingRow(conversation, bench.jiraId()).get("calls"))).forEach(calls::add);
        assertThat(calls).extracting(call -> call.get("requestId").asString() + ":" + call.get("status").asString())
            .containsExactly(allowed + ":DONE", running + ":DONE", writing + ":REFUSED");
        assertThat(calls.get(1).get("result").asString()).contains("Read again");
        assertThat(calls.get(2).get("result").asString()).contains(handle + "_comment", "TOOL_INTERRUPTED",
                "Tinfirm restarted during the call", "may or may not have taken effect");
        assertThat(requested(conversation)).containsExactly("CONNECTED");
        // A later pass finds nothing to do.
        recovery.recover();
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-11"))));
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-12"))));
    }

    /** An approval of a comment, raised by a call of the conversation's running turn, which then ended. */
    private record Approved(Bench bench, Conversation conversation, UUID decisionId, String comment) {
    }

    private Approved approvedComment(String token, String key) {
        Bench bench = bench();
        String handle = String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
        connectOwnJira(bench, token, "ron");
        Conversation conversation = conversation(bench);
        String comment = bench.jira().url("/rest/api/2/issue/" + key + "/comment");
        JiraStub.server().stubFor(post(urlEqualTo(comment)).withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(JiraStub.json(201, Map.of("id", "10900"))));
        UUID decision = pendingId(call(conversation, handle + "_comment", Map.of("key", key, "body", "Done.")));
        endTurn(conversation);
        return new Approved(bench, conversation, decision, comment);
    }

    private String result(Approved approved) {
        return String.valueOf(transactions.inCompany(approved.bench().companyId(),
                () -> decisionStore.find(approved.decisionId())).orElseThrow().result());
    }

    /** A parked comment taken ten minutes ago, running when the server stopped. */
    private static String parkedComment(Conversation conversation, UUID requestId, String handle, String key) {
        return "{\"runId\":\"" + conversation.runId() + "\",\"requestId\":\"" + requestId + "\",\"name\":\"" + handle
                + "_comment\",\"tool\":\"comment\",\"arguments\":{\"key\":\"" + key + "\",\"body\":\"Done.\"},"
                + "\"status\":\"RUNNING\",\"parkedAt\":\"" + Instant.now().minusSeconds(900) + "\",\"claimedAt\":\""
                + Instant.now().minusSeconds(600) + "\"}";
    }

    /** A parked call as the binding's row holds it, taken ten minutes ago when ALLOWED or RUNNING. */
    private static String parkedCall(Conversation conversation, UUID requestId, String handle, String key,
            String status) {
        return "{\"runId\":\"" + conversation.runId() + "\",\"requestId\":\"" + requestId + "\",\"name\":\"" + handle
                + "_get\",\"tool\":\"get\",\"arguments\":{\"key\":\"" + key + "\"},\"status\":\"" + status
                + "\",\"parkedAt\":\"" + Instant.now().minusSeconds(900) + "\",\"claimedAt\":\""
                + Instant.now().minusSeconds(600) + "\"}";
    }
}
