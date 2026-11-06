package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.OAuthStubs;
import com.flowtivo.tinfirm.support.TestConversations;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.stubbing.StubMapping;

import tools.jackson.databind.JsonNode;

/**
 * Parked calls (the package 3 contract, §5.3 and §9): a call on a system the conversation has not connected is
 * {@code PENDING} with the binding's id, and parked once per request id, at most 10; connecting runs each parked call
 * once, outside any transaction with {@code binding-call:{bindingId}} ({@code ASK} raises its approval instead), and
 * requests one {@code CONNECTED} turn, whose {@code # Answers} tells the results once; a gone binding answers them
 * with the reason; a cancelled conversation cancels them. The binding is bound by part 1, on the account's event,
 * after the commit. TEST FIXTURE ONLY: tokens are test values.
 */
class ParkedCallsIntegrationTest extends ConversationBench {

    @Autowired
    ParkedCalls parked;

    @Autowired
    OpenDecisions openDecisions;

    @Autowired
    CallRecovery recovery;

    @Test
    void aCallOnAnUnconnectedSystemWaitsAndRunsOnceTheSystemIsConnected() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        String person = "User " + bench.company().email("MEMBER");

        // Nothing connected: each call parks on the waiting binding, PENDING with the binding's id.
        UUID first = UUID.randomUUID();
        RunnerMessages.RunRequestAck parked = call(conversation, first, handle + "_get", Map.of("key", "SHOP-1"));
        UUID bindingId = pendingId(parked);
        assertThat(parked.message()).endsWith("is not connected for this conversation yet: Tinfirm asked " + person
                + " to connect it, and runs your call once they do. End your turn now.");
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("state", "WAITING")
            .containsEntry("reason", "NONE");
        // A request id sent again parks nothing twice.
        assertThat(pendingId(call(conversation, first, handle + "_get", Map.of("key", "SHOP-1"))))
            .isEqualTo(bindingId);
        assertThat(pendingId(call(conversation, handle + "_search", Map.of("query", "project = SHOP"))))
            .isEqualTo(bindingId);
        assertThat(parkedCalls(conversation, bench)).hasSize(2);
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).isEmpty();
        endTurn(conversation);

        // The person connects their own account: part 1 binds the waiting binding, and the parked calls run after the
        // commit, outside any transaction.
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-1")))
            .withHeader("Authorization", equalTo("Bearer test-parked-token"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10001", "SHOP-1", "Checkout fails", "Steps", null,
                    List.of(), "Open", "2026-10-01T10:00:00.000+0200", List.of()))));
        bench.jira().search(List.of(JiraStub.issue("10002", "SHOP-2", "Cart empty", "", null, List.of(), "Open",
                "2026-10-01T10:00:00.000+0200", List.of())));
        connectOwnJira(bench, "test-parked-token", "pia");
        awaitParked(conversation, bench, handle + "_get:DONE", handle + "_search:DONE");
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("state", "BOUND");
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-1"))));
        // One CONNECTED turn, once nothing is open.
        await().atMost(WAIT).until(() -> requested(conversation).equals(List.of("CONNECTED")));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).hasSize(2).allSatisfy(event -> {
            assertThat(event.get("details").get("bindingId").asString()).isEqualTo(bindingId.toString());
            assertThat(event.get("details").get("runId").asString()).isEqualTo(conversation.runId().toString());
        });
        assertThat(auditEvents(bench.owner(), bench.company(), "secret.accessed")).extracting(event -> event
            .get("details").get("purpose").asString()).contains("binding-call:" + bindingId);

        // The CONNECTED turn's # Answers tells the results, once.
        Conversation connected = nextTurn(conversation, 2);
        String answers = answersOf(connected);
        assertThat(answers).contains("**Calls that waited for an account**", "`" + handle + "_get` (read SHOP-1) ran "
                + "once the account was connected", "SHOP-1: Checkout fails", "`" + handle + "_search`");
        assertThat(answersOf(connected)).isEqualTo(answers);
        Conversation later = nextTurn(connected, 3);
        assertThat(answersOf(later)).isNull();
        assertThat(parkedCalls(conversation, bench)).isEmpty();
    }

    @Test
    void aParkedCallOfAnAskToolRaisesItsApprovalOnceConnected() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        UUID request = UUID.randomUUID();
        UUID bindingId = pendingId(call(conversation, request, handle + "_comment", Map.of("key", "SHOP-1", "body",
                "Looked at it.")));
        endTurn(conversation);

        connectOwnJira(bench, "test-parked-ask", "pat");
        await().atMost(WAIT).until(() -> transactions.inCompany(bench.companyId(),
                () -> decisionStore.byRequest(conversation.id(), request)).isPresent());
        DecisionStore.DecisionRow approval = transactions.inCompany(bench.companyId(),
                () -> decisionStore.byRequest(conversation.id(), request)).orElseThrow();
        assertThat(approval.requireSubject().tool()).isEqualTo("comment");
        assertThat(approval.runId()).isEqualTo(conversation.runId());
        assertThat(parkedCalls(conversation, bench)).isEmpty();
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("id", bindingId);
        // The approval is open: no turn yet; once decided, the DECIDED turn.
        assertThat(requested(conversation)).isEmpty();
        assertThat(bench.person().post(bench.path("/decisions/" + approval.id() + "/decide"), Map.of("outcome",
                "DENIED")).status()).isEqualTo(200);
        assertThat(requested(conversation)).containsExactly("DECIDED");
        assertThat(answersOf(nextTurn(conversation, 2))).contains("Your call of `" + handle + "_comment`",
                "denied by");
    }

    @Test
    void aGoneBindingAnswersItsParkedCallsWithTheReason() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        UUID bindingId = pendingId(call(conversation, handle(bench) + "_get", Map.of("key", "SHOP-9")));
        endTurn(conversation);
        update(bench.companyId(), """
                UPDATE conversation_binding SET state = 'GONE', reason = 'SYSTEM_REMOVED', waiting_since = NULL
                 WHERE id = ?""", bindingId);
        BindingInfo gone = transactions.inCompany(bench.companyId(), () -> bindings.find(bench.companyId(),
                bindingId)).orElseThrow();
        transactions.runInCompany(bench.companyId(), () -> parked.gone(gone));

        assertThat(parkedCalls(conversation, bench)).singleElement().satisfies(call -> {
            assertThat(call.get("status").asString()).isEqualTo("REFUSED");
            assertThat(call.get("result").asString()).contains("can no longer be used in this conversation (the "
                    + "system was removed)");
        });
        assertThat(requested(conversation)).containsExactly("CONNECTED");
        assertThat(answersOf(nextTurn(conversation, 2))).contains("did not run", "the system was removed");
        // A gone binding refuses the next call.
        assertThat(outcome(call(conversation, handle(bench) + "_get", Map.of("key", "SHOP-9"))))
            .isEqualTo("ACCOUNT_GONE");
    }

    @Test
    void aBindingParksAtMostTenCallsAndACancelCancelsThem() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        for (int i = 1; i <= ParkedCallStore.MAX_PARKED; i++) {
            assertThat(outcome(call(conversation, handle(bench) + "_get", Map.of("key", "SHOP-" + i))))
                .isEqualTo("PENDING");
        }
        RunnerMessages.RunRequestAck full = call(conversation, handle(bench) + "_get", Map.of("key", "SHOP-11"));
        assertThat(outcome(full)).isEqualTo("TOO_MANY_REQUESTS");
        // An open question, cancelled with the parked calls.
        UUID question = pendingId(request(conversation, "ASK", Map.of("question", "Which project?")));

        transactions.runInCompany(bench.companyId(), () -> openDecisions.cancelAll(bench.companyId(),
                conversation.id(), "CANCELLED"));

        assertThat(parkedCalls(conversation, bench)).hasSize(ParkedCallStore.MAX_PARKED)
            .allSatisfy(call -> assertThat(call.get("status").asString()).isEqualTo("CANCELLED"));
        assertThat(transactions.inCompany(bench.companyId(), () -> decisionStore.find(question)).orElseThrow()
            .status()).isEqualTo(DecisionStore.Status.CANCELLED);
        assertThat(auditEvents(bench.owner(), bench.company(), "decision.cancelled")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("CANCELLED"));
        String answers = answersOf(nextTurn(conversation, 2));
        assertThat(answers).contains("**Q:** Which project?", "Not answered: cancelled, the turn was cancelled",
                "it was cancelled (the turn was cancelled)");
    }

    @Test
    void aPausedAgentsParkedCallDoesNotRunOnceTheSystemIsConnected() {
        // I18, the kill switch: the agent parks a call on Jira, which is not connected yet; its owner pauses it with
        // stopRunning; then the person connects Jira. The call must not run while the agent is paused.
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        UUID bindingId = pendingId(call(conversation, handle + "_get", Map.of("key", "SHOP-5")));
        endTurn(conversation);
        assertThat(bench.person().post(bench.agentPath("/pause"), Map.of("stopRunning", true)).status())
            .isEqualTo(200);

        connectOwnJira(bench, "test-parked-paused", "pam");
        awaitParked(conversation, bench, handle + "_get:REFUSED");
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("id", bindingId)
            .containsEntry("state", "BOUND");
        assertThat(parkedCalls(conversation, bench)).singleElement().satisfies(call -> assertThat(call.get("result")
            .asString()).isEqualTo("The call did not run: " + bench.agentName() + " is paused."));
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-5"))));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.called")).isEmpty();
        // The CONNECTED turn tells it, once the agent is resumed (its run waits AGENT_PAUSED till then).
        await().atMost(WAIT).until(() -> requested(conversation).equals(List.of("CONNECTED")));
    }

    @Test
    void anAllowedCallIsNotTakenWhileItsAgentIsPaused() {
        Bench bench = bench();
        UUID accountId = connectOwnJira(bench, "test-allowed-paused", "pia");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        UUID bindingId = TestConversations.bind(bench.companyId(), conversation.id(), bench.jiraId(), accountId,
                bench.personPrincipalId(), null);
        // The binding was bound and its call allowed; the agent is paused before the call is taken to run.
        update(bench.companyId(), "UPDATE conversation_binding SET waiting_calls = CAST(? AS jsonb) WHERE id = ?",
                "[{\"runId\":\"" + conversation.runId() + "\",\"requestId\":\"" + UUID.randomUUID() + "\",\"name\":\""
                        + handle + "_get\",\"tool\":\"get\",\"arguments\":{\"key\":\"SHOP-6\"},\"status\":\"ALLOWED\","
                        + "\"parkedAt\":\"2026-10-12T08:00:00Z\"}]",
                bindingId);
        endTurn(conversation);
        assertThat(bench.person().post(bench.agentPath("/pause"), Map.of()).status()).isEqualTo(200);

        SystemContext.runInCompany(bench.companyId(), () -> parked.runAllowed(bench.companyId(), bindingId));

        assertThat(parkedCalls(conversation, bench)).singleElement().satisfies(call -> {
            assertThat(call.get("status").asString()).isEqualTo("REFUSED");
            assertThat(call.get("result").asString()).isEqualTo("The call did not run: " + bench.agentName()
                    + " is paused.");
        });
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-6"))));
    }

    @Test
    void connectingRaisesNoApprovalPastTheTenOpenOnes() {
        Bench bench = bench();
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        pendingId(call(conversation, first, handle + "_comment", Map.of("key", "SHOP-1", "body", "One.")));
        pendingId(call(conversation, second, handle + "_comment", Map.of("key", "SHOP-2", "body", "Two.")));
        // Nine approvals open: the first parked call's makes ten, the most a conversation holds.
        for (int i = 1; i < DecisionOps.MAX_OPEN; i++) {
            pendingId(request(conversation, "NETWORK", Map.of("host", "h" + i + ".example.com", "reason", "x")));
        }
        endTurn(conversation);

        connectOwnJira(bench, "test-parked-cap", "cap");
        await().atMost(WAIT).until(() -> transactions.inCompany(bench.companyId(),
                () -> decisionStore.byRequest(conversation.id(), first)).isPresent());
        awaitWorkers();
        assertThat(transactions.inCompany(bench.companyId(), () -> decisionStore.countOpen(conversation.id(),
                DecisionStore.Kind.APPROVAL))).isEqualTo(DecisionOps.MAX_OPEN);
        assertThat(transactions.inCompany(bench.companyId(), () -> decisionStore.byRequest(conversation.id(),
                second))).isEmpty();
        assertThat(parkedCalls(conversation, bench)).singleElement().satisfies(call -> {
            assertThat(call.get("requestId").asString()).isEqualTo(second.toString());
            assertThat(call.get("status").asString()).isEqualTo("REFUSED");
            assertThat(call.get("result").asString()).startsWith("The call did not run (TOO_MANY_REQUESTS): this "
                    + "conversation had " + DecisionOps.MAX_OPEN + " approvals open already");
        });
    }

    @Test
    void aTokenThatCannotBeGotNowKeepsTheParkedCallForLater() {
        Bench bench = bench();
        OAuthStubs.jiraClient(bench.admin(), bench.path("/systems/" + bench.jiraId()));
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        pendingId(call(conversation, handle + "_get", Map.of("key", "SHOP-8")));
        endTurn(conversation);

        // The person connects with OAuth, but Jira's token endpoint is down when the grant must be refreshed.
        StubMapping down = JiraStub.server().stubFor(WireMock.post(urlEqualTo(bench.jira().url(
                "/rest/oauth2/latest/token"))).atPriority(1).withRequestBody(containing(
                        "refresh_token=test-refresh-down")).willReturn(aResponse().withStatus(503)));
        OAuthStubs.connectJira(bench.person(), bench.path("/me/accounts/" + bench.jiraId()), bench.jira(),
                "test-access-down", "test-refresh-down", 0, "dora");
        await().atMost(WAIT).until(() -> !OAuthStubs.jiraRefreshes(bench.jira()).isEmpty());
        awaitWorkers();

        // Nothing is lost: the call waits to run again, the binding holds, and no turn tells anything yet.
        assertThat(bindingRow(conversation, bench.jiraId())).containsEntry("state", "BOUND");
        assertThat(parkedCalls(conversation, bench)).singleElement()
            .satisfies(call -> assertThat(call.get("status").asString()).isEqualTo("ALLOWED"));
        assertThat(requested(conversation)).isEmpty();

        // The token endpoint answers again; once the call has waited longer than the tool timeout, the recovery's
        // pass runs it, and the CONNECTED turn follows.
        JiraStub.server().removeStub(down);
        OAuthStubs.jiraTokens(bench.jira(), "refresh_token=test-refresh-down", "test-access-up", "test-refresh-up",
                3600);
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-8")))
            .withHeader("Authorization", equalTo("Bearer test-access-up"))
            .willReturn(JiraStub.json(200, JiraStub.issue("10008", "SHOP-8", "Back up", "", null, List.of(), "Open",
                    "2026-10-01T10:00:00.000+0200", List.of()))));
        update(bench.companyId(), """
                UPDATE conversation_binding
                   SET waiting_calls = jsonb_set(waiting_calls, '{0,claimedAt}', to_jsonb(?::text))
                 WHERE conversation_id = ?""", java.time.Instant.now().minusSeconds(600).toString(),
                conversation.id());
        recovery.recover();
        assertThat(parkedCalls(conversation, bench)).singleElement().satisfies(call -> {
            assertThat(call.get("status").asString()).isEqualTo("DONE");
            assertThat(call.get("result").asString()).contains("SHOP-8: Back up");
        });
        assertThat(requested(conversation)).containsExactly("CONNECTED");
    }

    /** Waits until the binding's calls are as named, {@code name:STATUS} each. */
    private void awaitParked(Conversation conversation, Bench bench, String... calls) {
        await().atMost(WAIT).untilAsserted(() -> assertThat(parkedCalls(conversation, bench)).extracting(call -> call
            .get("name").asString() + ":" + call.get("status").asString()).containsExactlyInAnyOrder(calls));
        awaitWorkers();
    }

    private List<JsonNode> parkedCalls(Conversation conversation, Bench bench) {
        Map<String, Object> row = bindingRow(conversation, bench.jiraId());
        if (row.isEmpty()) {
            return List.of();
        }
        List<JsonNode> calls = new java.util.ArrayList<>();
        JSON.readTree(String.valueOf(row.get("calls"))).forEach(calls::add);
        return calls;
    }

    private String handle(Bench bench) {
        return String.valueOf(row(bench.companyId(), "SELECT handle FROM external_system WHERE id = ?",
                bench.jiraId()).get("handle"));
    }
}
