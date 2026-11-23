package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * A run lost because its runner host restarted before the run's agent got a prompt ({@code LOST},
 * {@code HOST_RESTARTED}, and nothing in the run past the host's preparation: no {@code STATUS} {@code running}, no
 * event of the agent, no model request through the relay) is started again by itself, also when its settings' owner
 * did not choose automatic retries (ADR-0073, amended 2026-11-23): in the same row of at most three as the chosen
 * retries, with the same waits, its own note and an audit that tells it apart. A run whose agent may have had its
 * prompt, and a run lost for another cause, are as before. Through the API and fake runner hosts that restart: the
 * host drops its connection and comes back with a {@code hello} that does not list the run. Time is moved by setting
 * the scheduled run's {@code dispatch_after}.
 */
class StartRetryIntegrationTest extends ConversationTestBench {

    private static final String MESSAGES = "/api/runner/v1/model/anthropic/v1/messages";

    private static final String HOST_TOKEN = "X-Tinfirm-Runner-Token";

    private final List<FakeRunner> opened = new ArrayList<>();

    @AfterEach
    void closeHosts() {
        opened.forEach(FakeRunner::close);
        opened.clear();
    }

    @Test
    void aRunLostBeforeItsAgentGotAPromptIsStartedAgainWithoutTheChoice() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        assertThat(bench.member().get(bench.path("/agents/" + agentId + "/settings/me")).json().get("autoRetry")
            .asBoolean()).isFalse();
        Host host = host(bench);
        String id = start(bench.member(), bench, agentId, "Fix the flaky test").get("id").asString();

        // The host prepares the run (directories, clone, sandbox) and restarts before the agent's prompt.
        JsonNode assign = accept(bench, id, host.runner().expect("run.assign"), host);
        String lostRun = assign.get("runId").asString();
        prepare(bench, host, assign);
        host.restart();

        JsonNode first = awaitScheduled(bench, id, 1);
        assertThat(lostCause(bench, lostRun)).isEqualTo("HOST_RESTARTED");
        assertThat(lastSystem(first)).isEqualTo("The runner host restarted before the agent started on this turn, so "
                + "nothing was lost. Tinfirm starts the turn again by itself in 1 minute (automatic retry 1 of 3); "
                + "cancel the turn to stop it.");
        assertThat(first.get("waitingOn").asString()).isEqualTo("RUNNER");
        assertThat(first.get("waitingReason").asString()).isEqualTo("RETRY_SCHEDULED");
        JsonNode started = auditEvents(bench.owner(), bench.company(), "run.started").getFirst();
        assertThat(started.get("actor").get("type").asString()).isEqualTo("SYSTEM");
        assertThat(started.get("details").get("automatic").asBoolean()).isTrue();
        assertThat(started.get("details").get("attempt").asInt()).isEqualTo(1);
        assertThat(started.get("details").get("beforePrompt").asBoolean()).isTrue();
        // It waits for its time, as every automatic retry does.
        host.runner().expectNone("run.assign", Duration.ofSeconds(2));

        // Its time comes: the turn's request runs again, and this time it succeeds.
        JsonNode retried = due(bench, id, host);
        assertThat(retried.get("prompt").asString() + retried.path("resumePrompt").asString())
            .contains("Fix the flaky test");
        host.runner().accepted(UUID.fromString(retried.get("runId").asString()), retried.get("epoch").asLong());
        host.runner().send(finished(retried.get("runId").asString(), retried.get("epoch").asLong(), "SUCCEEDED",
                "Fixed."));
        JsonNode done = awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus")
            .asString()));
        assertThat(done.get("turnCount").asInt()).isEqualTo(2);
        host.runner().assertFramesValid();
    }

    @Test
    void aRunWhoseAgentMayHaveHadAPromptIsNotStartedAgainWithoutTheChoiceAndIsAsBeforeWithIt() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        Host host = host(bench);
        String id = start(bench.member(), bench, agentId, "Fix the flaky test").get("id").asString();

        // The host said the agent runs (STATUS running), then restarted: the person retries.
        JsonNode assign = accept(bench, id, host.runner().expect("run.assign"), host);
        prepare(bench, host, assign);
        event(bench, host, assign, 4, "STATUS", Map.of("phase", "running"));
        host.restart();
        awaitWaitsForThePerson(bench, id, host);

        // No STATUS running reached the server, but an event of the agent did.
        bench.member().post(bench.path("/conversations/" + id + "/retry"), null);
        assign = accept(bench, id, host.runner().expect("run.assign"), host);
        prepare(bench, host, assign);
        event(bench, host, assign, 4, "AGENT_MESSAGE", Map.of("text", "Looking at the test."));
        host.restart();
        awaitWaitsForThePerson(bench, id, host);

        // No event past the preparation, but the agent's model request went through the relay.
        bench.member().post(bench.path("/conversations/" + id + "/retry"), null);
        assign = accept(bench, id, host.runner().expect("run.assign"), host);
        prepare(bench, host, assign);
        HttpResponse<String> answer = relay(assign, host);
        assertThat(answer.statusCode()).as(answer.body()).isEqualTo(200);
        host.restart();
        awaitWaitsForThePerson(bench, id, host);

        // With the choice, nothing changes: a run lost before its prompt is retried as any HOST_RESTARTED loss...
        ApiResponse chosen = bench.member().put(bench.path("/agents/" + agentId + "/settings/me"), Map.of("model",
                TestSettings.MODEL, "autoRetry", true));
        assertThat(chosen.status()).as("%s", chosen).isEqualTo(200);
        bench.member().post(bench.path("/conversations/" + id + "/retry"), null);
        assign = accept(bench, id, host.runner().expect("run.assign"), host);
        prepare(bench, host, assign);
        host.restart();
        JsonNode first = awaitScheduled(bench, id, 1);
        assertThat(lastSystem(first)).isEqualTo("The turn was lost: its runner host stopped reporting it. The runner "
                + "host restarted and no longer runs it. Tinfirm retries it by itself in 1 minute (automatic retry 1 of "
                + "3); cancel the turn to stop it.");
        assertThat(auditEvents(bench.owner(), bench.company(), "run.started").getFirst().get("details")
            .has("beforePrompt")).isFalse();

        // ...and so is one whose agent ran.
        JsonNode retried = due(bench, id, host);
        accept(bench, id, retried, host);
        prepare(bench, host, retried);
        event(bench, host, retried, 4, "STATUS", Map.of("phase", "running"));
        host.restart();
        assertThat(lastSystem(awaitScheduled(bench, id, 2))).startsWith("The turn was lost: its runner host stopped "
                + "reporting it.").contains("in 2 minutes (automatic retry 2 of 3)");
        host.runner().assertFramesValid();
    }

    @Test
    void theStartsAgainShareTheRowOfThreeWithTheChosenRetries() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        String settings = bench.path("/agents/" + agentId + "/settings/me");
        assertThat(bench.member().put(settings, Map.of("model", TestSettings.MODEL, "autoRetry", true)).status())
            .isEqualTo(200);
        Host host = host(bench);
        String id = start(bench.member(), bench, agentId, "Fix the flaky test").get("id").asString();

        // A chosen retry first: the provider was down.
        JsonNode assign = host.runner().expect("run.assign");
        host.runner().send(Map.of("type", "run.finished", "runId", assign.get("runId").asString(), "epoch",
                assign.get("epoch").asLong(), "outcome", "FAILED", "lastSeq", 0, "failureClass", "SERVER_LIMITED",
                "failureMessage", "Prompt: 503 no available server"));
        awaitScheduled(bench, id, 1);
        // The person no longer wants retries; the row goes on with the starts again.
        assertThat(bench.member().put(settings, Map.of("model", TestSettings.MODEL, "autoRetry", false)).status())
            .isEqualTo(200);

        for (int attempt = 2; attempt <= 3; attempt++) {
            JsonNode retried = accept(bench, id, due(bench, id, host), host);
            prepare(bench, host, retried);
            host.restart();
            assertThat(lastSystem(awaitScheduled(bench, id, attempt))).startsWith("The runner host restarted before "
                    + "the agent started").contains("(automatic retry " + attempt + " of 3)");
        }

        // The third is lost the same way: the row is spent, and the turn waits for the person.
        JsonNode third = accept(bench, id, due(bench, id, host), host);
        prepare(bench, host, third);
        host.restart();
        JsonNode spent = awaitConversation(bench.member(), bench, id, now -> "FAILED".equals(now.path("turnStatus")
            .asString()));
        assertThat(lastSystem(spent)).endsWith("Tinfirm retried it by itself 3 times in a row; retry the turn, or say "
                + "what to do next.");
        assertThat(spent.get("waitingOn").asString()).isEqualTo("PERSON");
        assertThat(spent.get("turnCount").asInt()).isEqualTo(4);
        host.runner().expectNone("run.assign", Duration.ofSeconds(1));
        host.runner().assertFramesValid();
    }

    @Test
    void aRunStaleOnReconnectIsNotStartedAgainWithoutTheChoice() {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Fixer", "", List.<UUID[]>of(), "READ", List.of());
        Host host = host(bench);
        String id = start(bench.member(), bench, agentId, "Fix the flaky test").get("id").asString();

        JsonNode assign = accept(bench, id, host.runner().expect("run.assign"), host);
        prepare(bench, host, assign);
        // The host comes back with another execution of the run, before its prompt.
        host.reconnect(List.of(Map.of("runId", assign.get("runId").asString(), "epoch",
                assign.get("epoch").asLong() + 1, "lastSeq", 3)));
        JsonNode failed = awaitConversation(bench.member(), bench, id, now -> "FAILED".equals(now.path("turnStatus")
            .asString()));
        assertThat(lostCause(bench, assign.get("runId").asString())).isEqualTo("STALE_ON_RECONNECT");
        assertThat(lastSystem(failed)).endsWith("Retry the turn, or say what to do next.");
        assertThat(failed.get("waitingOn").asString()).isEqualTo("PERSON");
        host.runner().expectNone("run.assign", Duration.ofSeconds(1));
    }

    // ---- helpers ---------------------------------------------------------------------------------------------------

    /** A company runner host of protocol v2 that may restart: its connection drops, and a new one says hello. */
    private final class Host {

        private final RunnerHostRef ref;

        private FakeRunner runner;

        Host(RunnerHostRef ref, FakeRunner runner) {
            this.ref = ref;
            this.runner = runner;
        }

        FakeRunner runner() {
            return runner;
        }

        /** The host's process died and started again: its hello lists none of its earlier runs. */
        void restart() {
            reconnect(List.of());
        }

        void reconnect(List<Map<String, Object>> activeRuns) {
            runner.abort();
            await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
            runner = FakeRunner.connect(port, ref.token());
            opened.add(runner);
            runner.helloV2AndWelcome(List.of(), activeRuns, RUNNER_VERSION, 4);
            await().atMost(WAIT).until(() -> connections.isConfigured(ref.id()));
        }
    }

    private Host host(Bench bench) {
        RunnerHostRef ref = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = FakeRunner.connect(port, ref.token());
        opened.add(runner);
        runner.helloV2AndWelcome(List.of(), List.of(), RUNNER_VERSION, 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(ref.id()));
        return new Host(ref, runner);
    }

    /** The host accepts the assignment, and the server has the run RUNNING. */
    private JsonNode accept(Bench bench, String id, JsonNode assign, Host host) {
        host.runner().accepted(UUID.fromString(assign.get("runId").asString()), assign.get("epoch").asLong());
        awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));
        return assign;
    }

    /** What a host sends before the agent's prompt: the phases up to the sandbox's start, and a log line. */
    private void prepare(Bench bench, Host host, JsonNode assign) {
        event(bench, host, assign, 1, "STATUS", Map.of("phase", "preparing"));
        event(bench, host, assign, 2, "LOG", Map.of("level", "info", "message", "Cloning the repositories."));
        event(bench, host, assign, 3, "STATUS", Map.of("phase", "starting"));
    }

    /** Sends the event, and waits until the server stored it. */
    private void event(Bench bench, Host host, JsonNode assign, long seq, String type, Map<String, Object> payload) {
        UUID runId = UUID.fromString(assign.get("runId").asString());
        host.runner().event(runId, assign.get("epoch").asLong(), seq, type, payload);
        await().atMost(WAIT).until(() -> {
            try (TenantSql sql = TenantSql.in(bench.id())) {
                return !sql.column("SELECT seq FROM run_event WHERE run_id = ? AND seq = ?", runId, seq).isEmpty();
            }
        });
    }

    /** A model request of the run's agent, as the host's model proxy sends it to the relay. */
    private HttpResponse<String> relay(JsonNode assign, Host host) {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + MESSAGES))
            .timeout(Duration.ofSeconds(30))
            .header("Content-Type", "application/json")
            .header("Authorization", "Bearer " + assign.get("modelAccess").get("apiKey").asString())
            .header(HOST_TOKEN, host.ref.token())
            .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"claude-sonnet-4-5\",\"max_tokens\":16,"
                    + "\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}"))
            .build();
        try (HttpClient http = HttpClient.newHttpClient()) {
            return http.send(request, HttpResponse.BodyHandlers.ofString());
        }
        catch (java.io.IOException ex) {
            throw new IllegalStateException(ex);
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }

    /** The lost turn waits for the person, with the note of any lost turn, and nothing is started by itself. */
    private void awaitWaitsForThePerson(Bench bench, String id, Host host) {
        JsonNode failed = awaitConversation(bench.member(), bench, id, now -> "FAILED".equals(now.path("turnStatus")
            .asString()));
        assertThat(failed.get("failureClass").asString()).isEqualTo("LOST");
        assertThat(failed.get("waitingOn").asString()).isEqualTo("PERSON");
        assertThat(lastSystem(failed)).isEqualTo("The turn was lost: its runner host stopped reporting it. The runner "
                + "host restarted and no longer runs it. Retry the turn, or say what to do next.");
        assertThat(lastTurn(failed).get("status").asString()).isEqualTo("LOST");
        host.runner().expectNone("run.assign", Duration.ofSeconds(1));
    }

    private static String lostCause(Bench bench, String runId) {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return (String) sql.column("SELECT lost_cause FROM run WHERE id = ?", UUID.fromString(runId)).getFirst();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
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
        return conversation;
    }

    /** The scheduled retry's time comes now: the dispatcher sends it to the host. */
    private JsonNode due(Bench bench, String id, Host host) {
        String runId = lastTurn(conversation(bench.member(), bench, id)).get("id").asString();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            assertThat(sql.update("UPDATE run SET dispatch_after = now() - interval '1 second' WHERE id = ? AND status "
                    + "= 'QUEUED'", UUID.fromString(runId))).isEqualTo(1);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
        JsonNode assign = host.runner().expect("run.assign");
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
