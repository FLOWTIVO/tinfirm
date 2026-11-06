package com.flowtivo.tinfirm.run.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.agent.SettingsOwner;
import com.flowtivo.tinfirm.model.ModelAccessChoice;
import com.flowtivo.tinfirm.run.RunPlacementStale;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.run.TurnEnded;
import com.flowtivo.tinfirm.run.TurnReason;
import com.flowtivo.tinfirm.run.TurnSource;
import com.flowtivo.tinfirm.runner.HostScope;
import com.flowtivo.tinfirm.runner.RuntimeKind;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeLiteLlm;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.ModelGateways;
import com.flowtivo.tinfirm.support.TestSettings;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * The dispatcher and the assignment (the package 3 contract, §5.7; I11, I13, I14, I18; package 2's M7 and §6.1): a
 * turn's run on a company host with the model relay's one-run token, accepted while the run is RUNNING there with its
 * epoch and refused after its end or a reassignment; the person's settings and the agent's and the conversation's
 * hosts in {@code run.assign}; the contract's order of the dispatcher's checks, from the paused agent on;
 * conversations in parallel, one run at a time each; the conversation's previous host and session; a subscription on
 * its owner's host, and its pool counted by one dispatcher after the other; a spent budget; a delivery that turns out
 * stale while the run goes on under a later assignment; a personal host that may no longer carry the workspace at the
 * reveal (M2).
 */
@ExtendWith(OutputCaptureExtension.class)
@Timeout(value = 300, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
class RunDispatchIntegrationTest extends RunTestBench {

    @Test
    void aTurnRunsOnACompanyHostWithTheRelaysTokenAndEndsForItsConversation() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            JsonNode assign = expectAssign(runner, turn.runId());

            // The conversation is the work item and the job: its own session home and its own branch (I12).
            assertThat(assign.get("epoch").asLong()).isEqualTo(1);
            assertThat(assign.get("workItem").get("id").asString()).isEqualTo(turn.conversationId().toString());
            assertThat(assign.get("job").get("id").asString()).isEqualTo(turn.conversationId().toString());
            assertThat(assign.get("workItem").get("key").asString()).isEqualTo(turn.key());
            assertThat(assign.get("workItem").get("title").asString()).isEqualTo("Test conversation " + turn.key());
            // The agent as the agent module has it, with the bench's settings: their model, the runtime's effort.
            assertThat(assign.get("agent").get("id").asString()).isEqualTo(bench.agentId().toString());
            assertThat(assign.get("agent").get("name").asString()).isEqualTo(bench.agentName());
            assertThat(assign.get("agent").get("runtime").asString()).isEqualTo("CLAUDE_CODE");
            assertThat(assign.get("agent").get("model").asString()).isEqualTo(TestSettings.MODEL);
            assertThat(assign.get("agent").has("effort")).isFalse();
            assertThat(assign.get("prompt").asString()).isEqualTo("Do turn 1.");
            assertThat(assign.has("resumeSessionId") && !assign.get("resumeSessionId").isNull()).isFalse();
            assertThat(assign.get("limits").get("maxTurns").asInt()).isEqualTo(200);
            // The budget's model access: the relay and a one-run token, never a key (I14, M7).
            assertThat(assign.has("credentialLabel")).isFalse();
            JsonNode modelAccess = assign.get("modelAccess");
            assertThat(modelAccess.get("format").asString()).isEqualTo("anthropic");
            assertThat(modelAccess.get("baseUrl").asString()).endsWith("/api/runner/v1/model/anthropic");
            String token = modelAccess.get("apiKey").asString();
            assertThat(token).startsWith("tfm_").hasSize(47);
            String key = litellm.keyOfAlias(ModelGateways.alias(bench.company(), bench.personPrincipalId()))
                .orElseThrow().key;
            assertThat(assign.toString()).doesNotContain(key);

            // Assigned, not yet accepted: the token is not active.
            JsonNode queued = getRun(bench, turn.runId());
            assertThat(queued.get("status").asString()).isEqualTo("QUEUED");
            assertThat(queued.get("queueReason").get("code").asString()).isEqualTo("ASSIGNED");
            assertThat(queued.get("runnerHostKind").asString()).isEqualTo("COMPANY");
            assertThat(relay(token, runner.token())).isEqualTo(401);

            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            assertThat(relay(token, runner.token())).isEqualTo(200);
            runner.event(turn.runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "Working."));
            runner.send(finished(turn.runId(), 1, "SUCCEEDED", 1, "Done.", "session-1"));
            awaitRun(bench, turn.runId(), "SUCCEEDED");
            // The run ended: its token is revoked and refused.
            assertThat(relay(token, runner.token())).isEqualTo(401);

            JsonNode run = getRun(bench, turn.runId());
            assertThat(run.get("conversationId").asString()).isEqualTo(turn.conversationId().toString());
            assertThat(run.get("personPrincipalId").asString()).isEqualTo(bench.personPrincipalId().toString());
            assertThat(run.get("payerPrincipalId").asString()).isEqualTo(bench.personPrincipalId().toString());
            assertThat(run.get("modelAccess").asString()).isEqualTo("BUDGET");
            assertThat(run.get("branch").asString()).isEqualTo("agent/" + turn.key());
            assertThat(run.get("summary").asString()).isEqualTo("Done.");
            assertThat(run.get("relay").get("requests").asLong()).isEqualTo(1);
            assertThat(run.get("counts").get("messages").asInt()).isEqualTo(1);

            // The conversation was told, once, with the run's end.
            List<TurnEnded.TurnEnd> ends = TestTurns.conversation(turn.conversationId()).ends;
            assertThat(ends).singleElement().satisfies(end -> {
                assertThat(end.runId()).isEqualTo(turn.runId());
                assertThat(end.status()).isEqualTo(RunStatus.SUCCEEDED);
                assertThat(end.summary()).isEqualTo("Done.");
                assertThat(end.reason()).isEqualTo(TurnReason.FIRST);
            });
            // The turn's content was made outside any transaction, as the git tokens are revealed there.
            assertThat(TestTurns.conversation(turn.conversationId()).contentInTransaction).containsOnly(false);
            assertThat(auditEvents(bench.owner(), bench.company()).stream()
                .map(event -> event.get("action").asString())
                .filter(action -> action.startsWith("run."))
                .toList()).containsExactly("run.finished", "run.assigned", "run.started");
            runner.assertFramesValid();
        }
    }

    @Test
    void aTurnWhoseContentCannotBeMadeRightNowGoesBackToTheQueueAfterTheBackOff() {
        // A workspace token that could not be got while its binding holds: not an account to connect, so the run
        // waits as OTHER, and is not dispatched again before the back-off, so that it does not spin.
        Bench bench = bench(true);
        UUID conversationId = UUID.randomUUID();
        TestTurns.Script script = TestTurns.conversation(conversationId);
        String message = "Tinfirm could not get a token for GitHub right now; the turn is tried again shortly.";
        script.content = turn -> new TurnSource.TurnContent.Later(message);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench, conversationId, TurnReason.FIRST, 1, ModelAccessChoice.BUDGET);
            await().atMost(WAIT).untilAsserted(() -> assertThat(getRun(bench, turn.runId()).path("queueReason")
                .path("code").asString()).isEqualTo("OTHER"));

            JsonNode run = getRun(bench, turn.runId());
            assertThat(run.get("status").asString()).isEqualTo("QUEUED");
            assertThat(run.get("queueReason").get("text").asString()).isEqualTo(message);
            assertThat(query(bench.companyId(), "SELECT dispatch_after > now() FROM run WHERE id = ?", turn.runId()))
                .isEqualTo(true);
            int placed = script.placements.size();
            dispatcher.dispatch();
            assertThat(script.placements).hasSize(placed);
        }
    }

    @Test
    void aTurnRunsWithThePersonsSettingsAndTheAgentsAndTheConversationsHosts() {
        Bench bench = bench(true);
        ApiResponse hosts = bench.person().put(bench.agentPath("/hosts"), Map.of("hosts", List.of(
                Map.of("host", "registry.npmjs.org"), Map.of("host", "*.example.com"))));
        assertThat(hosts.status()).as("%s", hosts).isEqualTo(200);
        settings(bench, "claude-haiku-4-5", "HIGH");
        UUID conversationId = UUID.randomUUID();
        TestTurns.Script script = TestTurns.conversation(conversationId);
        script.content = turn -> TestTurns.content(turn, List.of(), List.of("registry.npmjs.org", "api.example.org",
                "*.github.io"));
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench, conversationId, TurnReason.FIRST, 1, ModelAccessChoice.BUDGET);
            JsonNode assign = expectAssign(runner, turn.runId());

            // The settings' model and effort (contract §5.7), the agent's name.
            assertThat(assign.get("agent").get("name").asString()).isEqualTo(bench.agentName());
            assertThat(assign.get("agent").get("model").asString()).isEqualTo("claude-haiku-4-5");
            assertThat(assign.get("agent").get("effort").asString()).isEqualTo("HIGH");
            // The agent's hosts and the conversation's granted ones, sorted and distinct, never a wildcard over
            // shared hosting.
            assertThat(assign.get("allowedHosts")).extracting(JsonNode::asString)
                .containsExactly("*.example.com", "api.example.org", "registry.npmjs.org");
            // The conversation's port was given the person's settings, the same reading for both of its calls.
            assertThat(script.settings).hasSize(2).allSatisfy(settings -> {
                assertThat(settings.owner()).isEqualTo(new SettingsOwner.Person(bench.personPrincipalId()));
                assertThat(settings.model()).isEqualTo("claude-haiku-4-5");
            });
            assertThat(script.settings.get(1)).isSameAs(script.settings.get(0));
            runner.assertFramesValid();
        }
    }

    @Test
    void theDispatcherChecksInTheContractsOrder() {
        Bench bench = bench(false);
        Turn turn = start(bench);
        TestTurns.Script script = TestTurns.conversation(turn.conversationId());

        // 1. The agent is paused (I18), whatever the conversation says.
        script.plan = new TurnSource.TurnPlan.AccountRequired("GitHub waits for you.");
        assertThat(bench.person().post(bench.agentPath("/pause"), Map.of()).status()).isEqualTo(200);
        assertThat(awaitQueueReason(bench, turn.runId(), "AGENT_PAUSED").get("text").asString())
            .contains(bench.agentName()).contains("paused");
        int planned = script.planned.size();
        dispatcher.dispatch();
        assertThat(script.planned).hasSize(planned);

        // 2. Resumed: the conversation's workspace binding waits.
        assertThat(bench.person().post(bench.agentPath("/resume"), null).status()).isEqualTo(200);
        assertThat(awaitQueueReason(bench, turn.runId(), "ACCOUNT_REQUIRED").get("text").asString())
            .isEqualTo("GitHub waits for you.");

        // 3. The payer's model access: no gateway yet; then a spent budget, whatever the hosts.
        script.plan = new TurnSource.TurnPlan.Ready(Set.of());
        assertThat(awaitQueueReason(bench, turn.runId(), "MODEL_ACCESS_UNAVAILABLE").get("reason").asString())
            .isEqualTo("GATEWAY_NOT_CONFIGURED");
        ModelGateways.configureBundled(bench.owner(), bench.company());
        ModelGateways.awaitStatus(bench.owner(), bench.company(), bench.personPrincipalId(), "OK");
        // The budget is spent at LiteLLM as well as in the key's row: a sync of the key may still be on its way (the
        // dispatcher's own sync may have made the key before the gateway test's reconciliation got to it), and a sync
        // records what LiteLLM says.
        FakeLiteLlm.Key key = litellm.keyOfAlias(ModelGateways.alias(bench.company(), bench.personPrincipalId()))
            .orElseThrow();
        Instant resetsAt = Instant.now().plus(3, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
        key.resetAt = resetsAt;
        key.spend = key.maxBudget;
        update(bench.companyId(), "UPDATE principal_model_key SET status = 'BUDGET_SPENT', budget_resets_at = ? "
                + "WHERE principal_id = ?", ts(resetsAt), bench.personPrincipalId());
        assertThat(Instant.parse(awaitQueueReason(bench, turn.runId(), "BUDGET_SPENT").get("retryAt").asString()))
            .isEqualTo(resetsAt);
        key.spend = 0;
        update(bench.companyId(), "UPDATE principal_model_key SET status = 'OK', budget_resets_at = NULL "
                + "WHERE principal_id = ?", bench.personPrincipalId());

        // 4. The hosts: none at all; only another member's personal host (it may not carry the person's budget, M4);
        // one that is not ready.
        awaitQueueReason(bench, turn.runId(), "NO_RUNNER_HOST");
        registerRunnerHost(bench.owner(), bench.companyId(), unique("owners-own"), "PERSONAL");
        awaitQueueReason(bench, turn.runId(), "NO_HOST_FOR_CREDENTIALS");
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("company"), "COMPANY");
        // Connected, but it has not confirmed its capacity: it takes no run yet.
        try (FakeRunner unconfigured = FakeRunner.connect(port, host.token()).manualConfig()) {
            unconfigured.helloV2AndWelcome(List.of(), List.of(), RUNNER_VERSION, 1);
            assertThat(awaitQueueReason(bench, turn.runId(), "RUNNER_HOST_OFFLINE").get("runnerHostId").asString())
                .isEqualTo(host.id().toString());
        }

        // 5. The slots: the host's only slot is taken by another conversation's run. The host says a low runner
        // version, and takes the run all the same: the version decides nothing (ADR-0109).
        try (FakeRunner runner = connect(host, "0.0.1", 1)) {
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");
            Turn second = start(bench);
            JsonNode full = awaitQueueReason(bench, second.runId(), "RUNNER_HOST_FULL");
            assertThat(full.get("used").asInt()).isEqualTo(1);
            assertThat(full.get("limit").asInt()).isEqualTo(1);
            assertThat(full.get("text").asString()).contains("(1 of 1 in use)");
            runner.assertFramesValid();
        }

        // A runtime the agent dropped since the conversation started fails the turn (open point 3).
        UUID codexConversation = UUID.randomUUID();
        UUID codex = startTurn(bench, codexConversation, "codex-1", RuntimeKind.CODEX, TurnReason.FIRST, 1,
                ModelAccessChoice.BUDGET).orElseThrow();
        assertThat(bench.person().patch(bench.agentPath(""), Map.of("runtimes", List.of("CLAUDE_CODE"))).status())
            .isEqualTo(200);
        dispatcher.dispatch();
        awaitRun(bench, codex, "FAILED");
        assertThat(TestTurns.conversation(codexConversation).ends).singleElement().satisfies(end -> {
            assertThat(end.failureClass()).isEqualTo("RUNTIME_REMOVED");
            assertThat(end.failureMessage()).contains("CODEX");
        });

        // A model of the person's settings that the payer's key does not allow fails the turn.
        settings(bench, "gpt-9", "DEFAULT");
        Turn model = start(bench);
        dispatcher.dispatch();
        awaitRun(bench, model.runId(), "FAILED");
        assertThat(getRun(bench, model.runId()).get("failureClass").asString()).isEqualTo("MODEL_NOT_ALLOWED");

        // So do settings paid by a budget that name no model: the gateway serves only the models it lists.
        settings(bench, null, "DEFAULT");
        Turn none = start(bench);
        dispatcher.dispatch();
        awaitRun(bench, none.runId(), "FAILED");
        assertThat(getRun(bench, none.runId()).get("failureClass").asString()).isEqualTo("MODEL_REQUIRED");
        assertThat(TestTurns.conversation(none.conversationId()).ends).singleElement()
            .satisfies(end -> assertThat(end.failureMessage()).startsWith("No model is chosen"));
    }

    @Test
    void aTurnWhoseModelTheGatewayDoesNotServeFailsSayingSoAndAnotherFailureKeepsItsClass() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            // The relay saw LiteLLM's "Invalid model name" for a request of the run (ModelRelayTest); the runner
            // classes the runtime's error PROTOCOL_ERROR.
            Turn turn = running(bench, runner);
            notServed(bench, turn, "claude-opus-4-8");
            runner.send(Map.of("type", "run.finished", "runId", turn.runId().toString(), "epoch", 1, "outcome",
                    "FAILED", "lastSeq", 0, "failureClass", "PROTOCOL_ERROR", "failureMessage", "Prompt: 400 {}"));
            awaitRun(bench, turn.runId(), "FAILED");
            assertThat(getRun(bench, turn.runId()).get("failureClass").asString()).isEqualTo("MODEL_NOT_SERVED");
            assertThat(TestTurns.conversation(turn.conversationId()).ends).singleElement().satisfies(end -> {
                assertThat(end.failureClass()).isEqualTo("MODEL_NOT_SERVED");
                assertThat(end.failureMessage()).isEqualTo("The company's model gateway does not serve the model "
                        + "claude-opus-4-8: choose a model it serves in the settings.");
            });

            // A class the runner could tell keeps it, whatever the relay noted.
            Turn other = running(bench, runner);
            notServed(bench, other, "claude-opus-4-8");
            runner.send(Map.of("type", "run.finished", "runId", other.runId().toString(), "epoch", 1, "outcome",
                    "FAILED", "lastSeq", 0, "failureClass", "CONTEXT_OVERFLOW", "failureMessage", "Prompt too long"));
            awaitRun(bench, other.runId(), "FAILED");
            assertThat(getRun(bench, other.runId()).get("failureClass").asString()).isEqualTo("CONTEXT_OVERFLOW");
            runner.assertFramesValid();
        }
    }

    /** What the relay notes of a run when LiteLLM answers that it does not serve the model it asked for. */
    private static void notServed(Bench bench, Turn turn, String model) {
        update(bench.companyId(), "UPDATE run_model_token SET model_not_served = ? WHERE run_id = ?", model,
                turn.runId());
    }

    /** A turn of the bench, assigned to the runner and accepted there: RUNNING, with its one-run model token. */
    private Turn running(Bench bench, FakeRunner runner) {
        Turn turn = start(bench);
        expectAssign(runner, turn.runId());
        runner.accepted(turn.runId(), 1);
        awaitRun(bench, turn.runId(), "RUNNING");
        return turn;
    }

    @Test
    void aTurnWhoseContentFailsAsItWouldAgainFailsInsteadOfGoingBackToTheQueue() {
        Bench bench = bench(true);
        UUID conversationId = UUID.randomUUID();
        TestTurns.Script script = TestTurns.conversation(conversationId);
        // As OutputPrompt.mountNames threw for a pair of names, on every turn of the conversation.
        script.content = turn -> {
            throw new IndexOutOfBoundsException("a name the mount cannot cut");
        };
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench, conversationId, TurnReason.FIRST, 1, ModelAccessChoice.BUDGET);
            dispatcher.dispatch();
            awaitRun(bench, turn.runId(), "FAILED");
            JsonNode run = getRun(bench, turn.runId());
            assertThat(run.get("failureClass").asString()).isEqualTo("INFRA");
            assertThat(run.get("failureMessage").asString()).startsWith("Tinfirm could not prepare the turn")
                .contains("IndexOutOfBoundsException").doesNotContain("cannot cut");
            // The conversation is told, and shows it (a SYSTEM message): the run is not taken back to fail again.
            assertThat(script.ends).singleElement().satisfies(end -> {
                assertThat(end.status()).isEqualTo(RunStatus.FAILED);
                assertThat(end.failureClass()).isEqualTo("INFRA");
            });
            dispatcher.dispatch();
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            assertThat(script.placements).hasSize(1);
        }
    }

    @Test
    void conversationsRunInParallelAndEachOneRunAtATime() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn one = start(bench);
            Turn two = start(bench);
            Set<String> assigned = Set.of(runner.expect("run.assign").get("runId").asString(),
                    runner.expect("run.assign").get("runId").asString());
            assertThat(assigned).containsExactlyInAnyOrder(one.runId().toString(), two.runId().toString());

            // A second turn of a conversation with an active run waits for it (I11).
            assertThat(startTurn(bench, one.conversationId(), one.key(), TurnReason.MESSAGE, 2,
                    ModelAccessChoice.BUDGET)).isEmpty();
            runner.accepted(one.runId(), 1);
            awaitRun(bench, one.runId(), "RUNNING");
            assertThat(startTurn(bench, one.conversationId(), one.key(), TurnReason.MESSAGE, 2,
                    ModelAccessChoice.BUDGET)).isEmpty();
            runner.send(finished(one.runId(), 1, "SUCCEEDED", 0, "First.", "session-one"));
            awaitRun(bench, one.runId(), "SUCCEEDED");
            assertThat(startTurn(bench, one.conversationId(), one.key(), TurnReason.MESSAGE, 2,
                    ModelAccessChoice.BUDGET)).isPresent();
            runner.assertFramesValid();
        }
    }

    @Test
    void theNextTurnGoesToTheConversationsPreviousHostAndResumesItsSession() {
        Bench bench = bench(true);
        try (FakeRunner previous = companyHost(bench, RUNNER_VERSION, 2)) {
            Turn first = start(bench);
            expectAssign(previous, first.runId());
            previous.accepted(first.runId(), 1);
            awaitRun(bench, first.runId(), "RUNNING");
            previous.send(finished(first.runId(), 1, "SUCCEEDED", 0, "First turn.", "session-first"));
            awaitRun(bench, first.runId(), "SUCCEEDED");

            // Another host with more free slots: the conversation still goes back to its previous host (open point
            // 13), where its session is.
            try (FakeRunner roomier = companyHost(bench, RUNNER_VERSION, 4)) {
                for (int turn = 2; turn <= 3; turn++) {
                    UUID runId = startTurn(bench, first.conversationId(), first.key(), TurnReason.MESSAGE, turn,
                            ModelAccessChoice.BUDGET).orElseThrow();
                    JsonNode assign = expectAssign(previous, runId);
                    assertThat(assign.get("resumeSessionId").asString()).isEqualTo("session-first");
                    assertThat(assign.get("resumePrompt").asString()).isEqualTo("Go on with turn " + turn + ".");
                    assertThat(assign.get("workItem").get("id").asString())
                        .isEqualTo(first.conversationId().toString());
                    previous.accepted(runId, 1);
                    awaitRun(bench, runId, "RUNNING");
                    previous.send(finished(runId, 1, "SUCCEEDED", 0, "Turn " + turn + ".", "session-first"));
                    awaitRun(bench, runId, "SUCCEEDED");
                }
                roomier.expectNone("run.assign", Duration.ofMillis(500));
                // Another conversation goes where most slots are free.
                Turn other = start(bench);
                expectAssign(roomier, other.runId());
                roomier.assertFramesValid();
            }
            assertThat(TestTurns.conversation(first.conversationId()).placements.getLast().previous().sessionId())
                .isEqualTo("session-first");
            previous.assertFramesValid();
        }
    }

    @Test
    void aRejectedRunWaitsAndIsAssignedAgainWithANewEpochAndANewToken() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            String first = expectAssign(runner, turn.runId()).get("modelAccess").get("apiKey").asString();
            runner.send(Map.of("type", "run.rejected", "runId", turn.runId().toString(), "epoch", 1, "reason",
                    "The previous turn still ends.", "code", "TASK_RUN_ACTIVE"));
            await().atMost(WAIT).untilAsserted(() -> assertThat(getRun(bench, turn.runId()).get("queueReason")
                .get("code").asString()).isEqualTo("PREVIOUS_RUN_ACTIVE"));
            assertThat(getRun(bench, turn.runId()).get("queueReason").has("retryAt")).isTrue();

            // After its back-off the run goes again, with the next epoch and a new token; the first is refused.
            update(bench.companyId(), "UPDATE run SET dispatch_after = ? WHERE id = ?",
                    ts(Instant.now().minusSeconds(1)), turn.runId());
            dispatcher.dispatch();
            JsonNode again = expectAssign(runner, turn.runId());
            assertThat(again.get("epoch").asLong()).isEqualTo(3);
            String second = again.get("modelAccess").get("apiKey").asString();
            assertThat(second).isNotEqualTo(first);
            runner.accepted(turn.runId(), 3);
            awaitRun(bench, turn.runId(), "RUNNING");
            assertThat(relay(first, runner.token())).isEqualTo(401);
            assertThat(relay(second, runner.token())).isEqualTo(200);

            // A frame of the old epoch is stale.
            runner.event(turn.runId(), 1, 1, "AGENT_MESSAGE", Map.of("text", "Old."));
            assertThat(runner.expect("error").get("code").asString()).isEqualTo("STALE_EPOCH");
            runner.assertFramesValid();
        }
    }

    @Test
    void aStaleDeliveryLeavesTheTokenOfTheAssignmentThatRunsAlone(CapturedOutput output) throws Exception {
        Bench bench = bench(true);
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.companyId(), unique("host"), "COMPANY");
        FakeRunner first = connect(host, RUNNER_VERSION, 4);
        // The turn's first preparation is slow: it waits until the test lets it go on.
        UUID conversationId = UUID.randomUUID();
        CountDownLatch preparing = new CountDownLatch(1);
        CountDownLatch goOn = new CountDownLatch(1);
        AtomicInteger preparations = new AtomicInteger();
        TestTurns.conversation(conversationId).content = turn -> {
            if (preparations.getAndIncrement() == 0) {
                preparing.countDown();
                try {
                    goOn.await(2 * WAIT.toSeconds(), TimeUnit.SECONDS);
                }
                catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
            }
            return TestTurns.content(turn);
        };
        Turn turn = start(bench, conversationId, TurnReason.FIRST, 1, ModelAccessChoice.BUDGET);
        assertThat(preparing.await(WAIT.toSeconds(), TimeUnit.SECONDS)).isTrue();

        // Meanwhile its host reconnects without it: the run goes back to the queue (epoch 2), is assigned again
        // (epoch 3), and runs with the token of that assignment.
        first.abort();
        await().atMost(WAIT).until(() -> !connections.isConnected(host.id()));
        try (FakeRunner again = connect(host, RUNNER_VERSION, 4)) {
            JsonNode assign = expectAssign(again, turn.runId());
            assertThat(assign.get("epoch").asLong()).isEqualTo(3);
            String token = assign.get("modelAccess").get("apiKey").asString();
            again.accepted(turn.runId(), 3);
            awaitRun(bench, turn.runId(), "RUNNING");
            assertThat(relay(token, host.token())).isEqualTo(200);

            // The first preparation ends now, and finds its assignment stale: nothing is sent, the running
            // assignment's token still answers, and no token of the stale one is left unrevoked.
            goOn.countDown();
            await().atMost(WAIT).until(() -> output.getAll().contains("Run " + turn.runId()
                    + " is no longer assigned with epoch 1"));
            assertThat(relay(token, host.token())).isEqualTo(200);
            assertThat(query(bench.companyId(), "SELECT count(*) FROM run_model_token WHERE run_id = ? AND epoch = 1 "
                    + "AND revoked_at IS NULL", turn.runId())).isEqualTo(0L);
            assertThat(query(bench.companyId(), "SELECT count(*) FROM run_model_token WHERE run_id = ? AND epoch = 3 "
                    + "AND revoked_at IS NULL", turn.runId())).isEqualTo(1L);
            again.expectNone("run.assign", Duration.ofMillis(500));
            assertThat(getRun(bench, turn.runId()).get("status").asString()).isEqualTo("RUNNING");
            again.assertFramesValid();
        }
    }

    @Test
    void twoDispatchersCountASubscriptionsPoolOneAfterTheOther() throws Exception {
        Bench bench = bench(false);
        RunnerHostRef personal = registerRunnerHost(bench.person(), bench.companyId(), unique("mine"));
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (FakeRunner runner = FakeRunner.connect(port, personal.token())) {
            // A subscription with a pool of one on a host of four slots.
            runner.helloV2AndWelcome(List.of(FakeRunner.credential("claude-max", "CLAUDE_LOGIN",
                    List.of("CLAUDE_CODE"), 1)), List.of(), "0.1.9", 4);
            awaitConfigured(personal.id());
            ModelAccessChoice subscription = new ModelAccessChoice.Subscription(personal.id(), "claude-max");
            List<UUID> runIds;
            try (Connection holder = holdHost(bench.companyId(), personal.id())) {
                // Another dispatcher holds the host while two conversations' turns start: a dispatcher of each (on two
                // replicas) gets as far as the host's lock and waits there.
                runIds = List.of(start(bench, UUID.randomUUID(), TurnReason.FIRST, 1, subscription).runId(),
                        start(bench, UUID.randomUUID(), TurnReason.FIRST, 1, subscription).runId());
                List<Future<?>> dispatchers = runIds.stream()
                    .<Future<?>>map(runId -> threads.submit(() -> transactions.inCompany(bench.companyId(),
                            () -> dispatcher.assign(runId))))
                    .toList();
                // The first waits for the host, the second for the machine the first holds (CONVENTIONS §10).
                await().atMost(WAIT).until(() -> waitingIn(bench.companyId(), "WHERE h.id IN")
                        + waitingIn(bench.companyId(), "FROM personal_runner_host WHERE id IN") >= 2);
                holder.commit();
                for (Future<?> dispatching : dispatchers) {
                    dispatching.get(30, TimeUnit.SECONDS);
                }
            }

            // They count the pool one after the other: one turn is assigned, the other waits for the pool.
            String settled = "SELECT count(*) FROM run WHERE id IN (?, ?) AND (dispatched_at IS NOT NULL "
                    + "OR queue_reason = 'CREDENTIAL_POOL_FULL')";
            await().atMost(WAIT).until(() -> (Long) query(bench.companyId(), settled, runIds.get(0), runIds.get(1))
                    == 2L);
            assertThat(query(bench.companyId(), "SELECT count(*) FROM run WHERE id IN (?, ?) AND dispatched_at IS NOT "
                    + "NULL", runIds.get(0), runIds.get(1))).as("runs assigned to the pool of one").isEqualTo(1L);
            assertThat(query(bench.companyId(), "SELECT count(*) FROM run WHERE id IN (?, ?) AND queue_reason = "
                    + "'CREDENTIAL_POOL_FULL'", runIds.get(0), runIds.get(1))).isEqualTo(1L);
            runner.assertFramesValid();
        }
        finally {
            threads.shutdownNow();
        }
    }

    @Test
    void aSubscriptionRunsOnlyOnItsOwnersHostWithItsLabel() {
        Bench bench = bench(false);
        RunnerHostRef personal = registerRunnerHost(bench.person(), bench.companyId(), unique("mine"));
        FakeRunner runner = FakeRunner.connect(port, personal.token());
        runner.helloV2AndWelcome(List.of(FakeRunner.credential("claude-max", "CLAUDE_LOGIN",
                List.of("CLAUDE_CODE"), 1)), List.of(), "0.1.9", 4);
        awaitConfigured(personal.id());
        ModelAccessChoice subscription = new ModelAccessChoice.Subscription(personal.id(), "claude-max");
        Turn turn = start(bench, UUID.randomUUID(), TurnReason.FIRST, 1, subscription);
        JsonNode assign = expectAssign(runner, turn.runId());
        assertThat(assign.get("credentialLabel").asString()).isEqualTo("claude-max");
        assertThat(assign.has("modelAccess")).isFalse();
        runner.accepted(turn.runId(), 1);
        awaitRun(bench, turn.runId(), "RUNNING");
        assertThat(getRun(bench, turn.runId()).get("runnerHostKind").asString()).isEqualTo("PERSONAL");
        assertThat(getRun(bench, turn.runId()).get("subscriptionLabel").asString()).isEqualTo("claude-max");

        // Its pool of one is taken; a workspace account of someone else keeps a run off the personal host (M4).
        Turn second = start(bench, UUID.randomUUID(), TurnReason.FIRST, 1, subscription);
        JsonNode full = awaitQueueReason(bench, second.runId(), "CREDENTIAL_POOL_FULL");
        assertThat(full.get("credentialLabel").asString()).isEqualTo("claude-max");
        Turn granted = start(bench, UUID.randomUUID(), TurnReason.FIRST, 1, subscription);
        TestTurns.conversation(granted.conversationId()).plan = new TurnSource.TurnPlan.Ready(
                Set.of(UUID.randomUUID()));
        awaitQueueReason(bench, granted.runId(), "NO_HOST_FOR_CREDENTIALS");

        // The host goes away: the subscription's runs wait for it.
        runner.close();
        await().atMost(WAIT).until(() -> !connections.isConnected(personal.id()));
        awaitQueueReason(bench, second.runId(), "HOST_OFFLINE");
    }

    @Test
    void aRepositoryApprovedBetweenThePlanAndTheRevealTakesTheRunOffThePersonalHost() {
        // M2, D6 (the security review of 2026-10-27): the personal-host rule is asked again at the reveal. The plan saw
        // only the payer's own account and the run was placed on their personal host; between the dispatcher's
        // transaction and the reveal, a repository bound through a group's grant joined the workspace (the hook below
        // approves it as the reveal runs). The conversation's source then refuses to reveal (RunPlacementStale):
        // nothing goes to the host, the run goes back to the queue, and the next pass places it under the rule.
        Bench bench = bench(false);
        RunnerHostRef personal = registerRunnerHost(bench.person(), bench.companyId(), unique("mine"));
        try (FakeRunner runner = FakeRunner.connect(port, personal.token())) {
            runner.helloV2AndWelcome(List.of(FakeRunner.credential("claude-max", "CLAUDE_LOGIN",
                    List.of("CLAUDE_CODE"), 1)), List.of(), "0.1.9", 4);
            awaitConfigured(personal.id());
            UUID conversationId = UUID.randomUUID();
            TestTurns.Script script = TestTurns.conversation(conversationId);
            script.plan = new TurnSource.TurnPlan.Ready(Set.of(bench.personPrincipalId()));
            // A credential a grant lends is no one's: its id stands for it in the holders.
            UUID lentCredential = UUID.randomUUID();
            AtomicInteger reveals = new AtomicInteger();
            script.content = turn -> {
                reveals.incrementAndGet();
                // The approval commits now, after the plan: the workspace holds the lent credential's repository.
                script.plan = new TurnSource.TurnPlan.Ready(Set.of(bench.personPrincipalId(), lentCredential));
                throw new RunPlacementStale("A workspace account of this turn is not its payer's own, which a "
                        + "personal runner host may not carry; the turn is placed again.");
            };
            ModelAccessChoice subscription = new ModelAccessChoice.Subscription(personal.id(), "claude-max");
            Turn turn = start(bench, conversationId, TurnReason.FIRST, 1, subscription);

            // Placed again under the rule: a subscription runs only on its owner's host, which may not carry it now.
            awaitQueueReason(bench, turn.runId(), "NO_HOST_FOR_CREDENTIALS");
            assertThat(reveals.get()).isEqualTo(1);
            assertThat(script.placements).singleElement()
                .satisfies(placement -> assertThat(placement.hostKind()).isEqualTo(HostScope.PERSONAL));
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            JsonNode run = getRun(bench, turn.runId());
            assertThat(run.get("status").asString()).isEqualTo("QUEUED");
            assertThat(run.has("runnerHostId") && !run.get("runnerHostId").isNull()).isFalse();
        }
    }

    @Test
    void aTurnThatHitsItsBudgetFailsWithTheResetAndThePayersOtherTurnsWait() {
        Bench bench = bench(true);
        try (FakeRunner runner = companyHost(bench, RUNNER_VERSION, 4)) {
            Turn turn = start(bench);
            expectAssign(runner, turn.runId());
            runner.accepted(turn.runId(), 1);
            awaitRun(bench, turn.runId(), "RUNNING");

            // LiteLLM refused the budget, and the run fails with USAGE_LIMIT (open point 8).
            FakeLiteLlm.Key key = litellm.keyOfAlias(ModelGateways.alias(bench.company(), bench.personPrincipalId()))
                .orElseThrow();
            Instant resetsAt = Instant.now().plus(5, ChronoUnit.DAYS).truncatedTo(ChronoUnit.SECONDS);
            key.spend = key.maxBudget;
            key.resetAt = resetsAt;
            update(bench.companyId(), "UPDATE principal_model_key SET status = 'BUDGET_SPENT', budget_resets_at = ? "
                    + "WHERE principal_id = ?", ts(resetsAt), bench.personPrincipalId());
            runner.send(Map.of("type", "run.finished", "runId", turn.runId().toString(), "epoch", 1, "outcome",
                    "FAILED", "lastSeq", 0, "failureClass", "USAGE_LIMIT", "failureMessage", "Budget exceeded"));
            awaitRun(bench, turn.runId(), "FAILED");
            assertThat(TestTurns.conversation(turn.conversationId()).ends).singleElement().satisfies(end -> {
                assertThat(end.failureClass()).isEqualTo("USAGE_LIMIT");
                assertThat(end.retryAt()).isEqualTo(resetsAt);
            });

            // The payer's other turn waits for the budget; nobody else pays (I10).
            Turn other = start(bench);
            awaitQueueReason(bench, other.runId(), "BUDGET_SPENT");
            runner.expectNone("run.assign", Duration.ofSeconds(1));

            // The budget is available again: the waiting turn goes.
            key.spend = 0;
            update(bench.companyId(), "UPDATE principal_model_key SET status = 'OK', budget_resets_at = NULL "
                    + "WHERE principal_id = ?", bench.personPrincipalId());
            dispatcher.dispatch();
            expectAssign(runner, other.runId());
            runner.assertFramesValid();
        }
    }
}
