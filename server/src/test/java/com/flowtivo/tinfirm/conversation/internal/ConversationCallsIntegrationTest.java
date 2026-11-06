package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.OAuthStubs;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestConversations;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

/**
 * A run's calls and requests where they meet what changes beside them (the package 3 contract, §5.3, §5.4, §5.8;
 * CONVENTIONS §10): a token that cannot be got right now fails the call for now and keeps the binding; a call or a
 * request that waited for the conversation's row while the conversation ended raises nothing; parallel calls while
 * their account breaks never deadlock. TEST FIXTURE ONLY: tokens are test values.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConversationCallsIntegrationTest extends ConversationBench {

    private static final int ROUNDS = 5;

    @Test
    void aTokenThatCannotBeGotNowFailsTheCallForNowAndKeepsTheBinding() {
        Bench bench = bench();
        OAuthStubs.jiraClient(bench.admin(), bench.path("/systems/" + bench.jiraId()));
        OAuthStubs.connectJira(bench.person(), bench.path("/me/accounts/" + bench.jiraId()), bench.jira(),
                "test-access-flaky", "test-refresh-flaky", 0, "flo");
        // Jira's token endpoint is down when the expired grant must be refreshed.
        StubMapping down = JiraStub.server().stubFor(WireMock.post(urlEqualTo(bench.jira().url(
                "/rest/oauth2/latest/token"))).atPriority(1).withRequestBody(containing(
                        "refresh_token=test-refresh-flaky")).willReturn(aResponse().withStatus(503)));
        Conversation conversation = conversation(bench);
        String handle = handle(bench);

        RunnerMessages.RunRequestAck failed = call(conversation, handle + "_get", Map.of("key", "SHOP-4"));
        assertThat(failed.code()).as("%s", failed).isEqualTo(ToolCalls.TOOL_FAILED);
        assertThat(failed.message()).isEqualTo("Tinfirm could not get a token for " + systemName(bench)
                + " right now; try again shortly.");
        // Not gone: the binding holds, and nothing was refused for it.
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("state", "BOUND");
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.denied")).isEmpty();

        // Once the token endpoint answers again, the same call runs.
        JiraStub.server().removeStub(down);
        OAuthStubs.jiraTokens(bench.jira(), "refresh_token=test-refresh-flaky", "test-access-fresh",
                "test-refresh-fresh", 3600);
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-4")))
            .withHeader("Authorization", equalTo("Bearer test-access-fresh"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10004", "SHOP-4", "Totals off", "", null, List.of(),
                    "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        assertThat(resultText(call(conversation, handle + "_get", Map.of("key", "SHOP-4"))))
            .contains("SHOP-4: Totals off");
    }

    @Test
    void aCallThatWaitedForTheConversationWhileItEndedRaisesNothing() throws Exception {
        Bench bench = bench();
        connectOwnJira(bench, "test-ending-call", "eli");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);

        // The call reads the conversation open, then waits for its row while the person ends it.
        RunnerMessages.RunRequestAck answer = whileTheConversationEnds(conversation, () -> call(conversation,
                handle + "_comment", Map.of("key", "SHOP-1", "body", "Looked at it.")));

        assertThat(outcome(answer)).as("%s", answer).isEqualTo("CONVERSATION_ENDED");
        assertThat(rows(bench.companyId(), "SELECT id FROM decision WHERE conversation_id = ?", conversation.id()))
            .isEmpty();
    }

    @Test
    void aRequestThatWaitedForTheConversationWhileItEndedRaisesNothing() throws Exception {
        Bench bench = bench();
        Conversation conversation = conversation(bench);

        RunnerMessages.RunRequestAck answer = whileTheConversationEnds(conversation, () -> request(conversation,
                "ASK", Map.of("question", "Which project?")));

        assertThat(outcome(answer)).as("%s", answer).isEqualTo("CONVERSATION_ENDED");
        assertThat(rows(bench.companyId(), "SELECT id FROM decision WHERE conversation_id = ?", conversation.id()))
            .isEmpty();
    }

    @Test
    void parallelCallsWhileTheirAccountBreaksNeverDeadlock(CapturedOutput output) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(4);
        try {
            for (int round = 0; round < ROUNDS; round++) {
                Bench bench = bench();
                String token = "test-breaking-token-" + round;
                UUID accountId = connectOwnJira(bench, token, "bree" + round);
                // Jira took the token back: it answers every call with it 401.
                JiraStub.server().stubFor(get(urlPathMatching(bench.jira().url("/rest/api/2/issue/.*")))
                    .withHeader("Authorization", equalTo("Bearer " + token))
                    .willReturn(JiraStub.json(401, Map.of("errorMessages", List.of("Unauthorized")))));
                Conversation one = conversation(bench);
                Conversation two = conversation(bench);
                for (Conversation conversation : List.of(one, two)) {
                    TestConversations.bind(bench.companyId(), conversation.id(), bench.jiraId(), accountId,
                            bench.personPrincipalId(), null);
                }
                String handle = handle(bench);

                // Two calls in each conversation, released together: each turns the account RECONNECT or meets it,
                // checks its binding again and parks on it, while the account's event looks at both bindings.
                CyclicBarrier together = new CyclicBarrier(4);
                List<Future<RunnerMessages.RunRequestAck>> calls = new ArrayList<>();
                int key = 0;
                for (Conversation conversation : List.of(one, one, two, two)) {
                    String issue = "SHOP-" + ++key;
                    calls.add(threads.submit(() -> {
                        together.await();
                        return call(conversation, handle + "_get", Map.of("key", issue));
                    }));
                }
                for (Future<RunnerMessages.RunRequestAck> call : calls) {
                    RunnerMessages.RunRequestAck answer = call.get(60, TimeUnit.SECONDS);
                    assertThat(outcome(answer)).as("round %d: %s", round, answer).isEqualTo("PENDING");
                }
                for (Conversation conversation : List.of(one, two)) {
                    await().atMost(WAIT).untilAsserted(() -> assertThat(bindingRow(conversation, bench.jiraId()))
                        .containsEntry("state", "WAITING").containsEntry("reason", "RECONNECT_YOURS"));
                }
            }
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    /**
     * Runs {@code work} on a thread of its own while this test holds the conversation's row and ends it, and commits
     * the end once the work waits for that row; answers what the work answered.
     */
    private <T> T whileTheConversationEnds(Conversation conversation, Callable<T> work) throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try (TenantSql ending = TenantSql.in(conversation.bench().companyId())) {
            ending.accepted("UPDATE conversation SET state = 'ENDED', ended_reason = 'USER', ended_at = now() "
                    + "WHERE id = ?", conversation.id());
            Future<T> result = thread.submit(work);
            await().atMost(WAIT).until(ConversationCallsIntegrationTest::waitsForAConversationsRow);
            ending.commit();
            return result.get(30, TimeUnit.SECONDS);
        }
        finally {
            thread.shutdownNow();
        }
    }

    /** Whether a session of the runtime role waits for a lock on a conversation's row. */
    private static boolean waitsForAConversationsRow() throws SQLException {
        try (Connection connection = TestDatabase.connectAsApp(); PreparedStatement statement = connection
            .prepareStatement("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE wait_event_type = 'Lock' AND query LIKE '%FROM conversation WHERE %FOR NO KEY UPDATE%'""");
                ResultSet count = statement.executeQuery()) {
            count.next();
            return count.getLong(1) > 0;
        }
    }

    private String handle(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
    }

    private String systemName(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT name FROM external_system WHERE id = ?",
                bench.jiraId()).get("name"));
    }
}
