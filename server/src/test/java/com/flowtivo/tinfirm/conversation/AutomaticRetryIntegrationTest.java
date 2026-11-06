package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * Automatic retries (ADR-0073), through the API and a fake runner host: off by default and chosen in a person's
 * settings, audited as a settings' change; a turn that failed for a reason that may pass ({@code SERVER_LIMITED}) is
 * then retried by itself as a {@code RETRY} turn that nobody started, whose run waits {@code RETRY_SCHEDULED} until
 * its time, at most three in a row, after 1, 2 and 4 minutes, each said in the failure's note; then the turn stays
 * failed. A person's retry counts from nothing again, and a person cancels a scheduled retry. A failure that does not
 * pass, or a message that waits, starts no automatic retry. Time is moved by setting the scheduled run's
 * {@code dispatch_after}.
 */
class AutomaticRetryIntegrationTest extends ConversationTestBench {

    @Test
    void aFailureThatMayPassIsRetriedByItselfThreeTimesOnlyWhenThePersonChoseSo() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        String settings = bench.path("/agents/" + agentId + "/settings/me");
        assertThat(bench.member().get(settings).json().get("autoRetry").asBoolean()).isFalse();
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Fix the flaky test").get("id").asString();

            // Off: a provider that is down fails the turn, which waits for the person.
            failMayPass(runner, runner.expect("run.assign"));
            JsonNode failed = awaitConversation(bench.member(), bench, id, now -> "FAILED".equals(now.path(
                    "turnStatus").asString()));
            assertThat(failed.get("failureClass").asString()).isEqualTo("SERVER_LIMITED");
            assertThat(failed.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(lastSystem(failed)).endsWith("Retry the turn, or say what to do next.");
            runner.expectNone("run.assign", Duration.ofSeconds(1));

            // The person chooses automatic retries: a settings' change, audited.
            ApiResponse chosen = bench.member().put(settings, Map.of("model", TestSettings.MODEL, "autoRetry", true));
            assertThat(chosen.status()).as("%s", chosen).isEqualTo(200);
            assertThat(chosen.json().get("autoRetry").asBoolean()).isTrue();
            assertThat(auditEvents(bench.owner(), bench.company(), "settings.changed").getFirst().get("details")
                .get("changed")).extracting(JsonNode::asString).containsExactly("autoRetry");

            // The person's retry fails again: retried by itself in a minute, the first of three.
            bench.member().post(bench.path("/conversations/" + id + "/retry"), null);
            failMayPass(runner, runner.expect("run.assign"));
            JsonNode first = awaitScheduled(bench, id, 1);
            assertThat(lastSystem(first)).endsWith("Tinfirm retries it by itself in 1 minute (automatic retry 1 of "
                    + "3); cancel the turn to stop it.");
            assertThat(first.get("waitingOn").asString()).isEqualTo("RUNNER");
            assertThat(first.get("waitingReason").asString()).isEqualTo("RETRY_SCHEDULED");
            JsonNode reason = lastTurn(first).get("queueReason");
            assertThat(reason.get("text").asString()).isEqualTo("The previous turn failed for a reason that may pass; "
                    + "Tinfirm retries it by itself (automatic retry 1 of 3).");
            assertThat(Instant.parse(reason.get("retryAt").asString()))
                .isBetween(Instant.now().plusSeconds(40), Instant.now().plusSeconds(61));
            // It waits for its time: nothing is sent to the host before.
            runner.expectNone("run.assign", Duration.ofSeconds(3));
            JsonNode started = auditEvents(bench.owner(), bench.company(), "run.started").getFirst();
            assertThat(started.get("actor").get("type").asString()).isEqualTo("SYSTEM");
            assertThat(started.get("details").get("turnReason").asString()).isEqualTo("RETRY");
            assertThat(started.get("details").get("automatic").asBoolean()).isTrue();
            assertThat(started.get("details").get("attempt").asInt()).isEqualTo(1);

            // Its time comes: the retry runs the turn's request again, resuming its session, and fails; the next
            // waits two minutes, then four.
            JsonNode retried = due(bench, id, runner);
            assertThat(retried.get("prompt").asString() + retried.path("resumePrompt").asString())
                .contains("Fix the flaky test");
            failMayPass(runner, retried);
            JsonNode second = awaitScheduled(bench, id, 2);
            assertThat(lastSystem(second)).contains("in 2 minutes (automatic retry 2 of 3)");
            assertThat(Instant.parse(lastTurn(second).get("queueReason").get("retryAt").asString()))
                .isAfter(Instant.now().plusSeconds(100));
            failMayPass(runner, due(bench, id, runner));
            assertThat(lastSystem(awaitScheduled(bench, id, 3))).contains("in 4 minutes (automatic retry 3 of 3)");

            // The third fails too: the retries in a row are spent, and the turn waits for the person.
            failMayPass(runner, due(bench, id, runner));
            JsonNode spent = awaitConversation(bench.member(), bench, id, now -> "FAILED".equals(now.path(
                    "turnStatus").asString()));
            assertThat(lastSystem(spent)).endsWith("Tinfirm retried it by itself 3 times in a row; retry the turn, or "
                    + "say what to do next.");
            assertThat(spent.get("waitingOn").asString()).isEqualTo("PERSON");
            assertThat(spent.get("turnCount").asInt()).isEqualTo(5);
            runner.expectNone("run.assign", Duration.ofSeconds(1));

            // A person's retry counts from nothing again; the person cancels the scheduled retry.
            bench.member().post(bench.path("/conversations/" + id + "/retry"), null);
            failMayPass(runner, runner.expect("run.assign"));
            awaitScheduled(bench, id, 1);
            assertThat(bench.member().post(bench.path("/conversations/" + id + "/cancel"), null).status())
                .isEqualTo(200);
            JsonNode cancelled = awaitConversation(bench.member(), bench, id, now -> "CANCELLED".equals(now.path(
                    "turnStatus").asString()));
            assertThat(lastTurn(cancelled).get("status").asString()).isEqualTo("CANCELLED");
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            runner.assertFramesValid();
        }
    }

    @Test
    void aFailureThatDoesNotPassOrAWaitingMessageStartsNoAutomaticRetry() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        ApiResponse chosen = bench.member().put(bench.path("/agents/" + agentId + "/settings/me"), Map.of("model",
                TestSettings.MODEL, "autoRetry", true));
        assertThat(chosen.status()).as("%s", chosen).isEqualTo(200);
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Fix the flaky test").get("id").asString();

            // A failure that would fail the same way again waits for the person.
            JsonNode assign = runner.expect("run.assign");
            runner.send(failure(assign, "PROTOCOL_ERROR", "unexpected stop reason `boom`"));
            JsonNode failed = awaitConversation(bench.member(), bench, id, now -> "FAILED".equals(now.path(
                    "turnStatus").asString()));
            assertThat(lastSystem(failed)).endsWith("Retry the turn, or say what to do next.");
            runner.expectNone("run.assign", Duration.ofSeconds(1));

            // A message that waited for the failed turn goes first, at once, as after any failure.
            bench.member().post(bench.path("/conversations/" + id + "/retry"), null);
            JsonNode retry = runner.expect("run.assign");
            runner.accepted(UUID.fromString(retry.get("runId").asString()), retry.get("epoch").asLong());
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            bench.member().post(bench.path("/conversations/" + id + "/messages"), Map.of("body", "Use the staging "
                    + "database"));
            failMayPass(runner, retry);
            JsonNode next = runner.expect("run.assign");
            assertThat(next.get("runId").asString()).isNotEqualTo(retry.get("runId").asString());
            JsonNode message = awaitConversation(bench.member(), bench, id, now -> "MESSAGE".equals(lastTurn(now)
                .path("turnReason").asString()));
            assertThat(lastTurn(message).path("queueReason").path("code").asString()).isNotEqualTo(
                    "RETRY_SCHEDULED");
            runner.assertFramesValid();
        }
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** The run fails as a provider's gateway without a backend makes it fail. */
    private static void failMayPass(FakeRunner runner, JsonNode assign) {
        runner.send(failure(assign, "SERVER_LIMITED", "Prompt: 503 no available server"));
    }

    private static Map<String, Object> failure(JsonNode assign, String failureClass, String message) {
        Map<String, Object> finished = new HashMap<>();
        finished.put("type", "run.finished");
        finished.put("runId", assign.get("runId").asString());
        finished.put("epoch", assign.get("epoch").asLong());
        finished.put("outcome", "FAILED");
        finished.put("lastSeq", 0);
        finished.put("failureClass", failureClass);
        finished.put("failureMessage", message);
        finished.put("sessionId", "session-" + assign.get("runId").asString().substring(0, 8));
        return finished;
    }

    /** Waits until the conversation's latest turn is its automatic retry of that number, scheduled. */
    private JsonNode awaitScheduled(Bench bench, String id, int attempt) {
        JsonNode conversation = awaitConversation(bench.member(), bench, id, now -> {
            JsonNode reason = lastTurn(now).path("queueReason");
            return "RETRY_SCHEDULED".equals(reason.path("code").asString()) && reason.path("used").asInt() == attempt;
        });
        JsonNode turn = lastTurn(conversation);
        assertThat(turn.get("turnReason").asString()).isEqualTo("RETRY");
        assertThat(turn.get("status").asString()).isEqualTo("QUEUED");
        assertThat(turn.get("queueReason").get("limit").asInt()).isEqualTo(3);
        assertThat(conversation.get("turnStatus").asString()).isEqualTo("QUEUED");
        return conversation;
    }

    /** The scheduled retry's time comes now: the dispatcher sends it to the host. */
    private JsonNode due(Bench bench, String id, FakeRunner runner) {
        String runId = lastTurn(conversation(bench.member(), bench, id)).get("id").asString();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            assertThat(sql.update("UPDATE run SET dispatch_after = now() - interval '1 second' WHERE id = ? AND status "
                    + "= 'QUEUED'", UUID.fromString(runId))).isEqualTo(1);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        JsonNode assign = runner.expect("run.assign");
        assertThat(assign.get("runId").asString()).isEqualTo(runId);
        return assign;
    }

    /** The body of the conversation's latest SYSTEM message. */
    private static String lastSystem(JsonNode conversation) {
        String body = null;
        for (JsonNode message : conversation.get("messages")) {
            if ("SYSTEM".equals(message.get("kind").asString())) {
                body = message.get("body").asString();
            }
        }
        assertThat(body).isNotNull();
        return body;
    }
}
