package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * The write-back under package 6's switches (the package 6 contract, §5.4): a row queued while the company's runs are
 * stopped is put off at its claim, without an attempt, and written once the stop is lifted; a paused system holds its
 * triggers' rows, and its resume writes them; should a trigger be live on a paused system, the claim puts its row off
 * as well; and the reveal of the binding (the security review of 2026-10-27, D5) puts off the row of a paused agent's
 * conversation, which no claim looks at, until it is resumed. Against the Jira Data Center stub. TEST FIXTURE ONLY:
 * every token is a test value.
 */
class OutboxSwitchesIntegrationTest extends OutboxTestSupport {

    @Autowired
    ApplicationEventPublisher events;

    @Test
    void aRowQueuedWhileTheCompanysRunsAreStoppedIsPutOffWithoutAnAttemptAndWrittenAfterTheLift() throws Exception {
        Jira jira = jira("Stopped");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        UUID conversationId = delivered(jira, bench, triggerId);

        ApiResponse stopped = bench.owner().post(bench.path("/runs-stop"), Map.of("reason", "Incident"));
        assertThat(stopped.status()).as("%s", stopped).isEqualTo(200);
        String pull = "https://github.example.test/acme/shop/pull/7";
        changeRequest(bench, jira, conversationId, pull);
        delivery.deliverDue();
        Map<String, Object> putOff = awaitOutbox(bench.id(), conversationId, now -> now.size() == 3
                && "the company's runs are stopped".equals(now.get(2).get("last_error"))).get(2);
        assertThat(putOff.get("status")).isEqualTo("PENDING");
        assertThat(putOff.get("attempts")).isEqualTo(0);
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", equalTo(pull))));

        // The lift arms the trigger again (TriggerResumed): its put-off rows are due at once, and written.
        ApiResponse lifted = bench.owner().delete(bench.path("/runs-stop"));
        assertThat(lifted.status()).as("%s", lifted).isEqualTo(200);
        Map<String, Object> written = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 3)).get(2);
        assertThat(written.get("cause")).isEqualTo("CHANGE_REQUEST");
        assertThat(written.get("attempts")).isEqualTo(1);
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", equalTo(pull))));
    }

    @Test
    void aPausedSystemHoldsItsRowsItsResumeWritesThemAndALiveTriggerOnItIsPutOff() throws Exception {
        Jira jira = jira("Paused");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        UUID conversationId = delivered(jira, bench, triggerId);

        // The pause pauses the trigger (SYSTEM_PAUSED): a row queued now is held, and the resume writes it.
        ApiResponse paused = bench.owner().post(bench.path("/systems/" + jira.system() + "/pause"),
                Map.of("reason", "The token leaked"));
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        String first = "https://github.example.test/acme/shop/pull/8";
        changeRequest(bench, jira, conversationId, first);
        assertThat(awaitOutbox(bench.id(), conversationId, now -> now.size() == 3).get(2).get("status"))
            .isEqualTo("HELD");
        ApiResponse resumed = bench.owner().post(bench.path("/systems/" + jira.system() + "/resume"), Map.of());
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        assertThat(awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 3)).get(2).get("attempts"))
            .isEqualTo(1);

        // Paused again, its trigger live all the same (a race the gate closes): the claim puts the row off.
        assertThat(bench.owner().post(bench.path("/systems/" + jira.system() + "/pause"), Map.of("reason", "Again"))
            .status()).isEqualTo(200);
        live(bench.id(), triggerId);
        String second = "https://github.example.test/acme/shop/pull/9";
        changeRequest(bench, jira, conversationId, second);
        delivery.deliverDue();
        Map<String, Object> putOff = awaitOutbox(bench.id(), conversationId, now -> now.size() == 4
                && "its system is paused".equals(now.get(3).get("last_error"))).get(3);
        assertThat(putOff.get("status")).isEqualTo("PENDING");
        assertThat(putOff.get("attempts")).isEqualTo(0);
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", equalTo(second))));
    }

    @Test
    void theRevealPutsOffAPausedAgentsRowWithoutAnAttemptAndItsResumeWritesIt() throws Exception {
        // D5 (the security review of 2026-10-27): the reveal is the gate. The claim does not look at the agent; the
        // reveal of the conversation's binding holds the write-back while its agent is paused, as the claim holds it
        // during the company's stop, and nothing reaches the system.
        Jira jira = jira("Held");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        UUID conversationId = delivered(jira, bench, triggerId);

        ApiResponse paused = bench.owner().post(bench.path("/agents/" + bench.agentId() + "/pause"), Map.of());
        assertThat(paused.status()).as("%s", paused).isEqualTo(200);
        String pull = "https://github.example.test/acme/shop/pull/10";
        changeRequest(bench, jira, conversationId, pull);
        delivery.deliverDue();
        Map<String, Object> putOff = awaitOutbox(bench.id(), conversationId, now -> now.size() == 3
                && (bench.agentName() + " is paused").equals(now.get(2).get("last_error"))).get(2);
        assertThat(putOff.get("status")).isEqualTo("PENDING");
        assertThat(putOff.get("attempts")).isEqualTo(0);
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", equalTo(pull))));

        // Resumed: the row is written at its next attempt.
        ApiResponse resumed = bench.owner().post(bench.path("/agents/" + bench.agentId() + "/resume"), null);
        assertThat(resumed.status()).as("%s", resumed).isEqualTo(200);
        dueNow(bench.id(), conversationId);
        delivery.deliverDue();
        Map<String, Object> written = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 3)).get(2);
        assertThat(written.get("attempts")).isEqualTo(1);
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(jira.issue("/remotelink")))
            .withRequestBody(matchingJsonPath("$.object.url", equalTo(pull))));
    }

    /** A turn's result and the conversation's link, written; the conversation. */
    private UUID delivered(Jira jira, Bench bench, UUID triggerId) throws Exception {
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "701"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 171))));
        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            runner.send(completed(take(runner), "Ready.", List.of()));
            awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 2));
            runner.assertFramesValid();
            return conversationId;
        }
    }

    /** A change request the conversation opened, which the write-back links from the issue. */
    private void changeRequest(Bench bench, Jira jira, UUID conversationId, String url) {
        String key = "acme/shop#" + url.substring(url.lastIndexOf('/') + 1);
        transactions.runInCompany(bench.id(), () -> events.publishEvent(new ConversationEvents.SystemWritten(
                bench.id(), conversationId, jira.system(), "CHANGE_REQUEST " + key, null, "CHANGE_REQUEST", key,
                ConversationEvents.WrittenValues.changeRequest(url, key + " Fix EUR", null))));
    }

    /** The trigger live again, as the runtime role may set it in the company, its pause cleared. */
    private static void live(UUID companyId, UUID triggerId) throws SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            sql.update("""
                    UPDATE trigger SET status = 'ACTIVE', paused_reason = NULL, paused_at = NULL, paused_by = NULL
                     WHERE id = ?""", triggerId);
            sql.commit();
        }
    }
}
