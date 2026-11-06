package com.flowtivo.tinfirm.conversation.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.support.JiraStub;

import tools.jackson.databind.JsonNode;

/**
 * A call Tinfirm lost track of (package R, §3.2): an {@code UNSAFE} call a stopped replica left {@code RUNNING} is
 * answered {@code TOOL_INTERRUPTED}, audited ({@code tool.interrupted}) and noted in the conversation, and never called
 * again; a {@code SAFE} one is called again (attempt 2); past the tool timeout an {@code UNSAFE} call is
 * {@code TOOL_INTERRUPTED} and a {@code SAFE} one {@code TOOL_FAILED}, neither run again for its request, the call cut
 * off before the timeout so that its record fits; a call running elsewhere is answered within the host's wait, never
 * nothing; a record that fails settles its call; a {@code SAFE} call lost while its system is paused is refused; and
 * the sweep settles the calls whose request never comes back. Time passes on a clock the test moves ({@link MovableClock}) and
 * through a replica with a short tool timeout, never by sleeping. TEST FIXTURE ONLY: tokens are test values.
 */
class ToolInterruptedIntegrationTest extends ToolJournalBench {

    /** The person's agent with Jira's get, search and comment, each ALLOW: a comment runs at once. */
    private Bench writer() {
        return bench(List.of(Map.of("name", "get", "mode", "ALLOW"), Map.of("name", "search", "mode", "ALLOW"),
                Map.of("name", "comment", "mode", "ALLOW")));
    }

    @Test
    void anUnsafeCallAStoppedReplicaLeftRunningIsInterruptedAndASafeOneRunsAgain() {
        Bench bench = writer();
        connectOwnJira(bench, "test-interrupted-restart", "ruth");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        String comment = stubComment(bench, "test-interrupted-restart", "SHOP-31", 0);
        stubIssue(bench, "test-interrupted-restart", "SHOP-32", 0);
        UUID commented = UUID.randomUUID();
        UUID read = UUID.randomUUID();
        Map<String, Object> commentArguments = Map.of("key", "SHOP-31", "body", "Done.");
        // A replica claimed both calls and stopped during them.
        leftRunning(conversation, commented, handle, "comment", commentArguments, "WRITE", "UNSAFE");
        leftRunning(conversation, read, handle, "get", Map.of("key", "SHOP-32"), "READ", "SAFE");
        // The host sends both again to another replica, after the tool timeout and the margin.
        MovableClock clock = new MovableClock();
        clock.advance(journal.toolTimeout().plus(ToolJournal.RUNNING_MARGIN).plusSeconds(1));
        ToolCalls other = replica(clock, journal.toolTimeout());

        RunnerMessages.RunRequestAck interrupted = callOn(other, conversation, commented, handle + "_comment",
                commentArguments);

        // UNSAFE: a tool error the agent reads, never a second call.
        assertThat(outcome(interrupted)).isEqualTo("DONE");
        assertThat(isError(interrupted)).isTrue();
        assertThat(structured(interrupted).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(structured(interrupted).get("cause").asString()).isEqualTo("RESTART");
        assertThat(resultText(interrupted)).contains(handle + "_comment", "may or may not have taken effect",
                "Check");
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));
        assertThat(journalRow(conversation, commented)).containsEntry("state", "INTERRUPTED");
        JsonNode audited = auditEvents(bench.owner(), bench.company(), "tool.interrupted").getFirst();
        assertThat(audited.get("targetType").asString()).isEqualTo("conversation");
        assertThat(audited.get("targetId").asString()).isEqualTo(conversation.id().toString());
        assertThat(audited.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        JsonNode details = audited.get("details");
        assertThat(details.get("runId").asString()).isEqualTo(conversation.runId().toString());
        assertThat(details.get("requestId").asString()).isEqualTo(commented.toString());
        assertThat(details.get("systemId").asString()).isEqualTo(bench.jiraId().toString());
        assertThat(details.get("tool").asString()).isEqualTo("comment");
        assertThat(details.get("attempt").asInt()).isEqualTo(1);
        assertThat(details.get("cause").asString()).isEqualTo("RESTART");
        assertThat(details.has("arguments")).isFalse();
        // The conversation shows it.
        assertThat(rows(bench.companyId(), "SELECT body FROM message WHERE conversation_id = ? AND kind = 'SYSTEM'",
                conversation.id())).extracting(message -> String.valueOf(message.get("body")))
            .anySatisfy(body -> assertThat(body).contains(handle + "_comment", "was interrupted"));
        // Sent again: the same answer, on any replica; nothing is called or audited again.
        assertThat(call(conversation, commented, handle + "_comment", commentArguments)).isEqualTo(interrupted);
        assertThat(callOn(other, conversation, commented, handle + "_comment", commentArguments))
            .isEqualTo(interrupted);
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).hasSize(1);

        // SAFE: called again, attempt 2.
        RunnerMessages.RunRequestAck again = callOn(other, conversation, read, handle + "_get",
                Map.of("key", "SHOP-32"));
        assertThat(outcome(again)).isEqualTo("DONE");
        assertThat(resultText(again)).contains("SHOP-32");
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-32"))));
        assertThat(journalRow(conversation, read)).containsEntry("state", "DONE").containsEntry("attempt", 2);
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).hasSize(1);
    }

    @Test
    void aCallRunningElsewhereIsWaitedForAndAnsweredWithinTheHostsWaitWithoutBeingTakenOver() {
        Bench bench = writer();
        connectOwnJira(bench, "test-interrupted-young", "yuri");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        String comment = stubComment(bench, "test-interrupted-young", "SHOP-34", 0);
        UUID requestId = UUID.randomUUID();
        Map<String, Object> arguments = Map.of("key", "SHOP-34", "body", "Hold on.");
        leftRunning(conversation, requestId, handle, "comment", arguments, "WRITE", "UNSAFE");
        // Within the tool timeout and the margin, it runs elsewhere: the request waits for it, at most until
        // answer-within after its claim (1 s on this replica; the host waits 65 s from its first send and never sends
        // the request again on its own), and then answers TOOL_INTERRUPTED: it may or may not have taken effect.
        ToolCalls other = replica(Clock.systemUTC(), Duration.ofSeconds(4), Duration.ofSeconds(1), runner);
        long started = System.nanoTime();
        RunnerMessages.RunRequestAck unknown = callOn(other, conversation, requestId, handle + "_comment", arguments);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(outcome(unknown)).isEqualTo("DONE");
        assertThat(isError(unknown)).isTrue();
        assertThat(structured(unknown).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(structured(unknown).get("cause").asString()).isEqualTo("TIMEOUT");
        assertThat(resultText(unknown)).contains("may or may not have taken effect", "Check");
        // Nothing is settled: the replica that runs it records it, and a request sent again later gets its answer.
        assertThat(journalRow(conversation, requestId)).containsEntry("state", "RUNNING");
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).isEmpty();
        RunnerMessages.RunRequestAck done = RunnerMessages.RunRequestAck.done(conversation.runId(), requestId,
                RunnerMessages.ToolResult.text("Commented.", null, false), null);
        recorded(conversation, requestId, done);
        assertThat(callOn(other, conversation, requestId, handle + "_comment", arguments)).isEqualTo(done);

        // A SAFE call running elsewhere is answered TOOL_FAILED instead: it may be called again.
        UUID read = UUID.randomUUID();
        leftRunning(conversation, read, handle, "get", Map.of("key", "SHOP-34"), "READ", "SAFE");
        assertThat(outcome(callOn(other, conversation, read, handle + "_get", Map.of("key", "SHOP-34"))))
            .isEqualTo("TOOL_FAILED");
        assertThat(journalRow(conversation, read)).containsEntry("state", "RUNNING");
    }

    @Test
    void aCallIsCutOffBeforeItsTimeoutSoThatItsRecordFitsAndAFailedRecordSettlesIt() {
        Bench bench = writer();
        connectOwnJira(bench, "test-interrupted-record", "rita");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        // This replica's tool timeout is 2 s: a call may run 1.5 s, its record has the rest. Jira takes 1.8 s.
        String slow = stubComment(bench, "test-interrupted-record", "SHOP-39", 1800);
        UUID cut = UUID.randomUUID();
        long started = System.nanoTime();
        RunnerMessages.RunRequestAck interrupted = callOn(replica(Clock.systemUTC(), Duration.ofSeconds(2)),
                conversation, cut, handle + "_comment", Map.of("key", "SHOP-39", "body", "Slow."));
        // Answered within the tool timeout, and cut off before Jira answered (else it would be DONE).
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(structured(interrupted).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(structured(interrupted).get("cause").asString()).isEqualTo("TIMEOUT");
        assertThat(journalRow(conversation, cut)).containsEntry("state", "INTERRUPTED");
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(slow)));

        // The call ran, but its record failed: an UNSAFE call is settled TOOL_INTERRUPTED (cause ERROR), never DONE
        // behind the agent's back; a SAFE one TOOL_FAILED.
        String quick = stubComment(bench, "test-interrupted-record", "SHOP-40", 0);
        stubIssue(bench, "test-interrupted-record", "SHOP-40", 0);
        ToolCalls failing = replica(Clock.systemUTC(), journal.toolTimeout(), answerWithin, runnerThat(new Around() {

            @Override
            public void recording() {
                throw new IllegalStateException("test: the record fails");
            }
        }));
        UUID commented = UUID.randomUUID();
        Map<String, Object> arguments = Map.of("key", "SHOP-40", "body", "Recorded?");
        RunnerMessages.RunRequestAck unknown = callOn(failing, conversation, commented, handle + "_comment",
                arguments);
        assertThat(isError(unknown)).isTrue();
        assertThat(structured(unknown).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(structured(unknown).get("cause").asString()).isEqualTo("ERROR");
        assertThat(journalRow(conversation, commented)).containsEntry("state", "INTERRUPTED");
        assertThat(callOn(failing, conversation, commented, handle + "_comment", arguments)).isEqualTo(unknown);
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(quick)));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted"))
            .anySatisfy(event -> {
                assertThat(event.get("details").get("requestId").asString()).isEqualTo(commented.toString());
                assertThat(event.get("details").get("cause").asString()).isEqualTo("ERROR");
            });
        UUID read = UUID.randomUUID();
        RunnerMessages.RunRequestAck failed = callOn(failing, conversation, read, handle + "_get",
                Map.of("key", "SHOP-40"));
        assertThat(outcome(failed)).isEqualTo("TOOL_FAILED");
        assertThat(journalRow(conversation, read)).containsEntry("state", "DONE");
    }

    @Test
    void aSafeCallLostWhileItsSystemIsPausedIsRefusedAndNotRunAgain() {
        Bench bench = writer();
        connectOwnJira(bench, "test-interrupted-paused", "pia");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        stubIssue(bench, "test-interrupted-paused", "SHOP-41", 0);
        UUID read = UUID.randomUUID();
        leftRunning(conversation, read, handle, "get", Map.of("key", "SHOP-41"), "READ", "SAFE");
        pauseJira(bench);
        MovableClock clock = new MovableClock();
        clock.advance(journal.toolTimeout().plus(ToolJournal.RUNNING_MARGIN).plusSeconds(1));

        RunnerMessages.RunRequestAck refused = callOn(replica(clock, journal.toolTimeout()), conversation, read,
                handle + "_get", Map.of("key", "SHOP-41"));

        // Claimed again (attempt 2) and checked again: the paused system refuses it, and the row ends with that.
        assertThat(outcome(refused)).isEqualTo("TOOL_DENIED");
        assertThat(refused.message()).contains("is paused by an admin");
        assertThat(journalRow(conversation, read)).containsEntry("state", "DONE").containsEntry("attempt", 2);
        assertThat(call(conversation, read, handle + "_get", Map.of("key", "SHOP-41"))).isEqualTo(refused);
        JiraStub.server().verify(0, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-41"))));
    }

    @Test
    void pastTheToolTimeoutAnUnsafeCallIsInterruptedAndASafeOneFailedNeitherRunAgain() {
        Bench bench = writer();
        connectOwnJira(bench, "test-interrupted-timeout", "tom");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        // Jira answers after 2.5 s; this replica's tool timeout is 1 s.
        String comment = stubComment(bench, "test-interrupted-timeout", "SHOP-35", 2500);
        stubIssue(bench, "test-interrupted-timeout", "SHOP-36", 2500);
        ToolCalls quick = replica(Clock.systemUTC(), Duration.ofSeconds(1));
        UUID commented = UUID.randomUUID();
        Map<String, Object> commentArguments = Map.of("key", "SHOP-35", "body", "Slowly.");

        RunnerMessages.RunRequestAck interrupted = callOn(quick, conversation, commented, handle + "_comment",
                commentArguments);

        assertThat(outcome(interrupted)).isEqualTo("DONE");
        assertThat(isError(interrupted)).isTrue();
        assertThat(structured(interrupted).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        assertThat(structured(interrupted).get("cause").asString()).isEqualTo("TIMEOUT");
        assertThat(resultText(interrupted)).contains("did not answer within 1 seconds");
        assertThat(journalRow(conversation, commented)).containsEntry("state", "INTERRUPTED");
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).singleElement()
            .satisfies(event -> assertThat(event.get("details").get("cause").asString()).isEqualTo("TIMEOUT"));
        // Sent again: the same answer, the comment never posted twice.
        assertThat(callOn(quick, conversation, commented, handle + "_comment", commentArguments))
            .isEqualTo(interrupted);
        JiraStub.server().verify(1, postRequestedFor(urlEqualTo(comment)));

        UUID read = UUID.randomUUID();
        RunnerMessages.RunRequestAck failed = callOn(quick, conversation, read, handle + "_get",
                Map.of("key", "SHOP-36"));
        assertThat(outcome(failed)).isEqualTo("TOOL_FAILED");
        assertThat(journalRow(conversation, read)).containsEntry("state", "DONE").containsEntry("attempt", 1);
        assertThat(callOn(quick, conversation, read, handle + "_get", Map.of("key", "SHOP-36"))).isEqualTo(failed);
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-36"))));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).hasSize(1);
        // The calls that ended after their answers went out are still on record.
        awaitWorkers();
        await().atMost(WAIT).until(() -> auditEvents(bench.owner(), bench.company(), "tool.called").size() == 2);
    }

    @Test
    void theSweepSettlesTheCallsWhoseRequestNeverComesBack() {
        Bench bench = writer();
        connectOwnJira(bench, "test-interrupted-sweep", "sam");
        Conversation conversation = conversation(bench);
        String handle = handle(bench);
        String comment = stubComment(bench, "test-interrupted-sweep", "SHOP-37", 0);
        stubIssue(bench, "test-interrupted-sweep", "SHOP-38", 0);
        UUID commented = UUID.randomUUID();
        UUID read = UUID.randomUUID();
        Map<String, Object> commentArguments = Map.of("key", "SHOP-37", "body", "Swept.");
        leftRunning(conversation, commented, handle, "comment", commentArguments, "WRITE", "UNSAFE");
        leftRunning(conversation, read, handle, "get", Map.of("key", "SHOP-38"), "READ", "SAFE");

        // Not yet: within the tool timeout and the margin, they may still run.
        sweep.sweep(Instant.now());
        assertThat(journalRow(conversation, commented)).containsEntry("state", "RUNNING");
        // A minute later their replica is gone.
        sweep.sweep(Instant.now().plus(Duration.ofMinutes(1)));

        assertThat(journalRow(conversation, commented)).containsEntry("state", "INTERRUPTED");
        assertThat(journalRow(conversation, read)).containsEntry("state", "INTERRUPTED");
        assertThat(journalRow(conversation, read).get("result")).isNull();
        // The UNSAFE one is audited, the SAFE one not.
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).singleElement()
            .satisfies(event -> {
                assertThat(event.get("details").get("requestId").asString()).isEqualTo(commented.toString());
                assertThat(event.get("details").get("cause").asString()).isEqualTo("RESTART");
            });
        // Their requests come back after all: the UNSAFE one is TOOL_INTERRUPTED, the SAFE one runs (attempt 2).
        RunnerMessages.RunRequestAck interrupted = call(conversation, commented, handle + "_comment",
                commentArguments);
        assertThat(structured(interrupted).get("code").asString()).isEqualTo("TOOL_INTERRUPTED");
        RunnerMessages.RunRequestAck again = call(conversation, read, handle + "_get", Map.of("key", "SHOP-38"));
        assertThat(resultText(again)).contains("SHOP-38");
        assertThat(journalRow(conversation, read)).containsEntry("state", "DONE").containsEntry("attempt", 2);
        JiraStub.server().verify(0, postRequestedFor(urlEqualTo(comment)));
        JiraStub.server().verify(1, getRequestedFor(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/SHOP-38"))));
        assertThat(auditEvents(bench.owner(), bench.company(), "tool.interrupted")).hasSize(1);
    }

    private static String stubComment(Bench bench, String token, String key, int delayMillis) {
        String comment = bench.jira().url("/rest/api/2/issue/" + key + "/comment");
        JiraStub.server().stubFor(post(urlEqualTo(comment)).withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(JiraStub.json(201, Map.of("id", "10" + key.substring(key.indexOf('-') + 1)))
                .withFixedDelay(delayMillis)));
        return comment;
    }

    private static void stubIssue(Bench bench, String token, String key, int delayMillis) {
        JiraStub.server().stubFor(get(urlPathEqualTo(bench.jira().url("/rest/api/2/issue/" + key)))
            .withHeader("Authorization", equalTo("Bearer " + token))
            .willReturn(JiraStub.json(200, JiraStub.issue("1" + key.substring(key.indexOf('-') + 1), key, "Issue "
                    + key, "", null, List.of(), "Open", "2026-10-01T10:00:00.000+0200", List.of()))
                .withFixedDelay(delayMillis)));
    }
}
