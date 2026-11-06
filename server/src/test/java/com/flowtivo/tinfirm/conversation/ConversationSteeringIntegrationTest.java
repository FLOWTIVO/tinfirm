package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;

import tools.jackson.databind.JsonNode;

/**
 * Steering end to end through the API and a fake runner host (the runner protocol 2 feature {@code run.steer}): a
 * person's message written while a turn runs goes to that turn's host, {@code SENDING} until the host answers; the
 * turn takes it ({@code DELIVERED}, the run's) or not, and then the next turn does; a host without the feature, or a
 * turn that ends before its answer, leaves the message to the next turn; and the messages written while the turn was
 * queued go to it once it runs.
 */
class ConversationSteeringIntegrationTest extends ConversationTestBench {

    @Test
    void aMessageWhileTheTurnRunsGoesToItsAgentOrWaitsForTheNextTurn() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Builder", "You build the shop.", List.of(), "WRITE", List.of());
        try (FakeRunner runner = steeringHost(bench, 4)) {
            String id = start(bench, agentId, "Fix the checkout.");
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            // The message goes to the running turn: SENDING until the host answers.
            JsonNode sent = post(bench, id, "Use the staging database.");
            JsonNode message = sent.get("messages").get(1);
            assertThat(message.get("steering").asString()).isEqualTo("SENDING");
            assertThat(message.get("waiting").asBoolean()).isTrue();
            JsonNode steer = runner.expect("run.steer");
            assertThat(steer.get("runId").asString()).isEqualTo(runId.toString());
            assertThat(steer.get("epoch").asLong()).isEqualTo(epoch);
            assertThat(steer.get("steerId").asString()).isEqualTo(message.get("id").asString());
            assertThat(steer.get("text").asString()).startsWith("# Message from ")
                .contains("> Use the staging database.", "`finish`");

            // The turn took it: the message is its run's.
            runner.send(ack(steer, "DELIVERED", null));
            JsonNode delivered = awaitConversation(bench.member(), bench, id,
                    now -> "DELIVERED".equals(now.get("messages").get(1).path("steering").asString()));
            assertThat(delivered.get("messages").get(1).get("runId").asString()).isEqualTo(runId.toString());
            assertThat(delivered.get("messages").get(1).get("waiting").asBoolean()).isFalse();
            assertThat(delivered.get("turnCount").asInt()).isEqualTo(1);

            // The agent cannot take the next one: it waits for the next turn.
            JsonNode refusedMessage = post(bench, id, "And skip the flaky tests.").get("messages").get(2);
            JsonNode refusedSteer = runner.expect("run.steer");
            assertThat(refusedSteer.get("steerId").asString()).isEqualTo(refusedMessage.get("id").asString());
            runner.send(ack(refusedSteer, "REFUSED", "NOT_SUPPORTED"));
            JsonNode refused = awaitConversation(bench.member(), bench, id,
                    now -> noSteering(now.get("messages").get(2)));
            assertThat(refused.get("messages").get(2).get("waiting").asBoolean()).isTrue();
            // A late answer about it changes nothing.
            runner.send(ack(refusedSteer, "DELIVERED", null));

            // The turn ends: the next one takes what waited, and not what the turn took while it worked.
            runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "Fixed the checkout."));
            JsonNode second = runner.expect("run.assign");
            JsonNode after = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 2);
            assertThat(after.get("messages").get(1).get("runId").asString()).isEqualTo(runId.toString());
            assertThat(after.get("messages").get(1).get("steering").asString()).isEqualTo("DELIVERED");
            assertThat(after.get("messages").get(2).get("runId").asString())
                .isEqualTo(second.get("runId").asString());
            assertThat(noSteering(after.get("messages").get(2))).isTrue();
            String prompt = second.get("prompt").asString();
            assertThat(prompt).contains("# Request\n> And skip the flaky tests.");
            assertThat(prompt.substring(prompt.indexOf("# Request"), prompt.indexOf("# Conversation so far")))
                .doesNotContain("staging");

            // A message whose answer never comes before the turn ends waits for the next turn.
            UUID secondRun = UUID.fromString(second.get("runId").asString());
            long secondEpoch = second.get("epoch").asLong();
            runner.accepted(secondRun, secondEpoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            JsonNode thread = post(bench, id, "Also the coupons.").get("messages");
            String unanswered = thread.get(thread.size() - 1).get("id").asString();
            assertThat(runner.expect("run.steer").get("steerId").asString()).isEqualTo(unanswered);
            runner.send(finished(secondRun.toString(), secondEpoch, "SUCCEEDED", "Skipped them."));
            JsonNode third = runner.expect("run.assign");
            assertThat(third.get("prompt").asString()).contains("# Request\n> Also the coupons.");
            JsonNode last = awaitConversation(bench.member(), bench, id, now -> now.get("turnCount").asInt() == 3);
            JsonNode coupons = last.get("messages")
                .valueStream()
                .filter(m -> m.get("id").asString().equals(unanswered))
                .findFirst()
                .orElseThrow();
            assertThat(noSteering(coupons)).isTrue();
            assertThat(coupons.get("runId").asString()).isEqualTo(third.get("runId").asString());
            runner.assertFramesValid();
        }
    }

    @Test
    void aHostWithoutSteeringLeavesTheMessageForTheNextTurn() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Builder", "You build the shop.", List.of(), "WRITE", List.of());
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench, agentId, "Fix the checkout.");
            JsonNode assign = runner.expect("run.assign");
            runner.accepted(UUID.fromString(assign.get("runId").asString()), assign.get("epoch").asLong());
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
            JsonNode message = post(bench, id, "Use the staging database.").get("messages").get(1);
            assertThat(noSteering(message)).isTrue();
            assertThat(message.get("waiting").asBoolean()).isTrue();
            runner.expectNone("run.steer", Duration.ofSeconds(1));
            runner.assertFramesValid();
        }
    }

    @Test
    void theMessagesWrittenWhileTheTurnWasQueuedGoToItOnceItRuns() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Builder", "You build the shop.", List.of(), "WRITE", List.of());
        try (FakeRunner runner = steeringHost(bench, 4)) {
            String id = start(bench, agentId, "Fix the checkout.");
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            // Assigned, not accepted yet: the message waits, and nothing is sent.
            JsonNode message = post(bench, id, "Use the staging database.").get("messages").get(1);
            assertThat(noSteering(message)).isTrue();
            runner.expectNone("run.steer", Duration.ofMillis(500));
            runner.accepted(runId, assign.get("epoch").asLong());
            JsonNode steer = runner.expect("run.steer");
            assertThat(steer.get("steerId").asString()).isEqualTo(message.get("id").asString());
            runner.send(ack(steer, "DELIVERED", null));
            JsonNode delivered = awaitConversation(bench.member(), bench, id,
                    now -> "DELIVERED".equals(now.get("messages").get(1).path("steering").asString()));
            assertThat(delivered.get("messages").get(1).get("runId").asString()).isEqualTo(runId.toString());
            runner.assertFramesValid();
        }
    }

    private String start(Bench bench, UUID agentId, String text) {
        ApiResponse started = bench.member().post(bench.path("/conversations"),
                Map.of("agentId", agentId.toString(), "message", text));
        assertThat(started.status()).as("%s", started).isEqualTo(201);
        return started.json().get("id").asString();
    }

    private JsonNode post(Bench bench, String id, String body) {
        ApiResponse posted = bench.member().post(bench.path("/conversations/" + id + "/messages"),
                Map.of("body", body));
        assertThat(posted.status()).as("%s", posted).isEqualTo(201);
        return posted.json();
    }

    /** The API leaves a null field out. */
    private static boolean noSteering(JsonNode message) {
        JsonNode steering = message.path("steering");
        return steering.isMissingNode() || steering.isNull();
    }

    private static Map<String, Object> ack(JsonNode steer, String status, String code) {
        Map<String, Object> ack = new java.util.LinkedHashMap<>();
        ack.put("type", "run.steer.ack");
        ack.put("runId", steer.get("runId").asString());
        ack.put("epoch", steer.get("epoch").asLong());
        ack.put("steerId", steer.get("steerId").asString());
        ack.put("status", status);
        if (code != null) {
            ack.put("code", code);
        }
        return ack;
    }
}
