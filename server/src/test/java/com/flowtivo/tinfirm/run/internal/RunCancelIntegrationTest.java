package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.support.ApiClient;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * Cancel and retry (the package 3 contract, §5.7), and who sees a run (§5.10, I17): its person and OWNER see and cancel
 * it; ADMIN, another member and another company's member get 404. A queued run ends at once, a running one through
 * its host, and the conversation's retry is a new turn once nothing is active.
 */
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunCancelIntegrationTest extends RunTestBench {

    @Test
    void aRunIsItsPersonsAndOwnersOnly() {
        Bench bench = bench(false);
        Turn turn = start(bench);
        ApiClient admin = loginAs(bench.company().email("ADMIN"));
        TestCompany other = companyWithRoles();
        ApiClient stranger = loginAs(other.email("OWNER"));
        String member = uniqueEmail("member");
        createUser(admin(), member, false);
        addMember(bench.owner(), bench.companyId(), member, "MEMBER");
        ApiClient colleague = loginAs(member);

        for (String path : List.of("/runs/" + turn.runId(), "/runs/" + turn.runId() + "/events")) {
            assertThat(bench.person().get(bench.path(path)).status()).as(path).isEqualTo(200);
            assertThat(bench.owner().get(bench.path(path)).status()).as(path).isEqualTo(200);
            assertThat(admin.get(bench.path(path)).status()).as(path).isEqualTo(404);
            assertThat(colleague.get(bench.path(path)).status()).as(path).isEqualTo(404);
            assertThat(stranger.get(bench.path(path)).status()).as(path).isEqualTo(404);
            assertThat(stranger.get(other.path(path)).status()).as(path).isEqualTo(404);
        }
        for (ApiClient refused : List.of(admin, colleague, stranger)) {
            assertThat(refused.post(bench.path("/runs/" + turn.runId() + "/cancel"), null).status()).isEqualTo(404);
        }
        assertThat(getRun(bench, turn.runId()).get("status").asString()).isEqualTo("QUEUED");
        assertThat(bench.person().get(bench.path("/runs/" + UUID.randomUUID())).status()).isEqualTo(404);
    }

    @Test
    void thePersonCancelsAQueuedRunAtOnceAndRetriesTheTurn() {
        Bench bench = bench(false);
        Turn turn = start(bench);
        ApiResponse cancelled = bench.person().post(bench.path("/runs/" + turn.runId() + "/cancel"), null);
        assertThat(cancelled.status()).as("%s", cancelled).isEqualTo(200);
        assertThat(cancelled.json().get("status").asString()).isEqualTo("CANCELLED");
        assertThat(cancelled.json().has("queueReason")).isFalse();
        // Idempotent.
        assertThat(bench.person().post(bench.path("/runs/" + turn.runId() + "/cancel"), null).json().get("status")
            .asString()).isEqualTo("CANCELLED");
        List<TurnEnded.TurnEnd> ends = TestTurns.conversation(turn.conversationId()).ends;
        assertThat(ends).singleElement().satisfies(end -> assertThat(end.status()).isEqualTo(RunStatus.CANCELLED));
        List<JsonNode> audits = auditEvents(bench.owner(), bench.company(), "run.cancelled");
        assertThat(audits).singleElement().satisfies(event -> {
            assertThat(event.get("details").get("previousStatus").asString()).isEqualTo("QUEUED");
            assertThat(event.get("actor").get("type").asString()).isEqualTo("USER");
        });

        // The retry is the conversation's next turn: a new run.
        Optional<UUID> retry = startTurn(bench, turn.conversationId(), turn.key(), TurnReason.RETRY, 2,
                ModelAccessChoice.BUDGET);
        assertThat(retry).isPresent().get().isNotEqualTo(turn.runId());
        JsonNode run = getRun(bench, retry.orElseThrow());
        assertThat(run.get("turnReason").asString()).isEqualTo("RETRY");
        assertThat(run.get("turnNumber").asInt()).isEqualTo(2);

        // Cancelled through the conversation module, as when its person leaves.
        assertThat(transactions.inCompany(bench.companyId(), () -> turns.cancelActive(bench.companyId(),
                turn.conversationId()))).contains(retry.orElseThrow());
        assertThat(getRun(bench, retry.orElseThrow()).get("status").asString()).isEqualTo("CANCELLED");
        assertThat(transactions.inCompany(bench.companyId(), () -> turns.cancelActive(bench.companyId(),
                turn.conversationId()))).isEmpty();
    }

    @Test
    void theOwnerCancelsARunningRunThroughItsHost() {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        FakeRunner runner = connect(host, GATEWAY_VERSION, 4);
        Turn turn = start(bench);
        expectAssign(runner, turn.runId());
        runner.accepted(turn.runId(), 1);
        awaitRun(bench, turn.runId(), "RUNNING");

        ApiResponse cancel = bench.owner().post(bench.path("/runs/" + turn.runId() + "/cancel"), null);
        assertThat(cancel.status()).as("%s", cancel).isEqualTo(200);
        assertThat(cancel.json().get("status").asString()).isEqualTo("RUNNING");
        JsonNode frame = runner.expect("run.cancel");
        assertThat(frame.get("runId").asString()).isEqualTo(turn.runId().toString());
        assertThat(frame.get("epoch").asLong()).isEqualTo(1);

        // The host reconnects before it stopped the run: the cancel is sent again.
        runner.abort();
        try (FakeRunner again = FakeRunner.connect(port, host.token())) {
            again.helloV2AndWelcome(List.of(), List.of(Map.of("runId", turn.runId().toString(), "epoch", 1,
                    "lastSeq", 0)), GATEWAY_VERSION, 4);
            assertThat(again.expect("run.cancel").get("runId").asString()).isEqualTo(turn.runId().toString());
            again.send(Map.of("type", "run.finished", "runId", turn.runId().toString(), "epoch", 1, "outcome",
                    "CANCELLED", "lastSeq", 0));
            awaitRun(bench, turn.runId(), "CANCELLED");
            again.expectNone("run.cancel", Duration.ofMillis(500));
            again.assertFramesValid();
        }
        assertThat(TestTurns.conversation(turn.conversationId()).ends).singleElement()
            .satisfies(end -> assertThat(end.status()).isEqualTo(RunStatus.CANCELLED));
    }

    @Test
    void anAssignedRunCancelledBeforeItsHostAcceptedIsStoppedThere() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, GATEWAY_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            assertThat(bench.person().post(bench.path("/runs/" + turn.runId() + "/cancel"), null).json()
                .get("status").asString()).isEqualTo("CANCELLED");
            assertThat(runner.expect("run.cancel").get("runId").asString()).isEqualTo(turn.runId().toString());
            // The accept that crossed the cancel is answered with another cancel.
            runner.accepted(turn.runId(), 1);
            assertThat(runner.expect("run.cancel").get("runId").asString()).isEqualTo(turn.runId().toString());
            runner.assertFramesValid();
        }
    }
}
