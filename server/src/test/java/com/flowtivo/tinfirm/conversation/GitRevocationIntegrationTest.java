package com.flowtivo.tinfirm.conversation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.ConnectionWatch;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestCredentials;
import com.flowtivo.tinfirm.support.TestRunnerSessions;
import com.flowtivo.tinfirm.support.TestRuns;
import com.flowtivo.tinfirm.support.TestTriggers;
import com.flowtivo.tinfirm.support.TestTurns;

import tools.jackson.databind.JsonNode;

/**
 * ADR-0116, decisions 1 to 5, 11 and 12, through the API and a fake runner host: a binding that fails its check while
 * a run holds its git token (a credential disabled, a grant withdrawn), a paused system (with and without
 * {@code stopRunning}) and a trigger's binding that follows another credential each send {@code run.git.revoke} to a
 * host that lists the feature, durably (again at its {@code hello}, before a cancel), audited {@code run.git_revoked};
 * the run's page shows it; a host without the feature gets nothing and the conversation says the run keeps its token; a
 * binding that fails between the reveal and the send keeps the assignment from going; a paused agent or trigger
 * revokes nothing. TEST FIXTURE ONLY: tokens are test values.
 */
class GitRevocationIntegrationTest extends ConversationTestBench {

    private static final String REVOKE = "run.git.revoke";

    private static final String DONE = "welcome.done";

    /** A company host connected over protocol v2 with these features, configured. */
    private FakeRunner host(RunnerHostRef ref, List<String> features, List<Map<String, Object>> activeRuns) {
        FakeRunner runner = FakeRunner.connect(port, ref.token());
        Map<String, Object> hello = FakeRunner.helloV2(List.of(), activeRuns, RUNNER_VERSION, 4);
        hello.put("features", features);
        runner.send(hello);
        runner.expect("welcome");
        await().atMost(WAIT).until(() -> connections.isConfigured(ref.id()));
        return runner;
    }

    private RunnerHostRef hostRef(Bench bench) {
        return registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
    }

    /** A credential of the code host, lent to a group the member is in through a grant: its id, its grant's, its name. */
    private record Lent(UUID credentialId, UUID grantId, String name) {
    }

    private Lent lent(Bench bench, UUID system, String access) {
        String name = unique("CI bot");
        UUID credential = TestCredentials.add(bench.owner(), bench.path(""), system, name, "test-ci-" + name);
        UUID group = createGroup(bench.owner(), bench.company(), unique("Builders"),
                List.of(bench.company().userId("MEMBER")));
        return new Lent(credential, createGrant(bench.owner(), bench.company(), credential, group, access), name);
    }

    /** The member starts a conversation; its run is assigned to the host and accepted: the assignment. */
    private JsonNode running(Bench bench, FakeRunner runner, UUID agentId) {
        start(bench.member(), bench, agentId, "Fix the cart");
        JsonNode assign = runner.expect("run.assign");
        String runId = assign.get("runId").asString();
        runner.accepted(UUID.fromString(runId), assign.get("epoch").asLong());
        await().atMost(WAIT).until(() -> "RUNNING".equals(bench.member().get(bench.path("/runs/" + runId)).json()
            .get("status").asString()));
        return assign;
    }

    private static Map<String, Object> notPushed(JsonNode assign, String message) {
        Map<String, Object> finished = new LinkedHashMap<>();
        finished.put("type", "run.finished");
        finished.put("runId", assign.get("runId").asString());
        finished.put("epoch", assign.get("epoch").asLong());
        finished.put("outcome", "FAILED");
        finished.put("lastSeq", 0);
        finished.put("failureClass", "GIT_ERROR");
        JsonNode workspace = assign.get("workspaces").get(0);
        finished.put("failureMessage", "git failed in " + workspace.get("name").asString() + ": "
                + workspace.get("name").asString() + ": not pushed: " + message);
        finished.put("git", List.of(Map.of("componentId", workspace.get("componentId").asString(), "branch",
                workspace.get("branch").asString(), "pushed", false, "commits", 2)));
        return finished;
    }

    private List<JsonNode> revocations(Bench bench, String runId) {
        List<JsonNode> found = new ArrayList<>();
        for (JsonNode event : auditEvents(bench.owner(), bench.company(), "run.git_revoked")) {
            if (event.get("targetId").asString().equals(runId)) {
                found.add(event);
            }
        }
        return found;
    }

    /** The frames of the class whose second connections the tests look for. */
    private static final String REVOCATIONS = "com.flowtivo.tinfirm.run.internal.GitRevocationService";

    // ---- The ticket's acceptance criterion ---------------------------------------------------------------------------

    @Test
    void aCredentialDisabledDuringARunStopsItsNextPush() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            JsonNode workspace = assign.get("workspaces").get(0);
            assertThat(workspace.get("git").get("token").asString()).isEqualTo("test-ci-" + credential.name());
            // Neither an expiry nor a mirror key: a stored token lives until the run ends (decision 8).
            assertThat(workspace.get("git").has("expiresAt")).isFalse();
            assertThat(workspace.get("git").has("mirrorKey")).isFalse();

            // An admin disables the credential: the host hears it within a second of the commit.
            ApiResponse disabled = bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                    + credential.credentialId()), Map.of("enabled", false));
            assertThat(disabled.status()).as("%s", disabled).isEqualTo(200);
            JsonNode revoke = runner.expect(REVOKE, Duration.ofSeconds(1));
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revoke.get("epoch").asLong()).isEqualTo(assign.get("epoch").asLong());
            assertThat(revoke.get("componentIds")).singleElement()
                .satisfies(id -> assertThat(id.asString()).isEqualTo(workspace.get("componentId").asString()));
            assertThat(revoke.get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            String message = revoke.get("message").asString();
            assertThat(message).startsWith("Tinfirm revoked this run's access to " + workspace.get("name").asString()
                    + ": the shared credential " + credential.name() + " on ")
                .contains(" was disabled.");
            assertThat(message).doesNotContain("test-ci-");

            // The host does what the runner host does: no push of that repository, the run fails GIT_ERROR.
            runner.send(notPushed(assign, message));
            await().atMost(WAIT).until(() -> "FAILED".equals(bench.member().get(bench.path("/runs/" + runId)).json()
                .get("status").asString()));
            JsonNode run = bench.member().get(bench.path("/runs/" + runId)).json();
            assertThat(run.get("failureClass").asString()).isEqualTo("GIT_ERROR");
            JsonNode repository = run.get("repositories").get(0);
            assertThat(repository.get("pushed").asBoolean()).isFalse();
            assertThat(repository.get("gitRevoked").get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            assertThat(repository.get("gitRevoked").get("message").asString()).isEqualTo(message);
            assertThat(repository.get("gitRevoked").get("delivery").asString()).isEqualTo("SENT");
            assertThat(repository.get("gitRevoked").get("revokedAt").asString()).isNotBlank();

            // Audited once, the token never in it. Package 1's event reaches the binding after the admin's commit,
            // as SYSTEM, as the binding's own change is; the admin's is credential.changed.
            JsonNode audit = revocations(bench, runId).getFirst();
            assertThat(revocations(bench, runId)).hasSize(1);
            assertThat(audit.get("actor").get("type").asString()).isEqualTo("SYSTEM");
            assertThat(audit.get("details").get("conversationId").asString()).isEqualTo(run.get("conversationId")
                .asString());
            assertThat(audit.get("details").get("bindingId").asString()).isNotBlank();
            assertThat(audit.get("details").get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            assertThat(audit.get("details").get("sent").asBoolean()).isTrue();
            assertThat(audit.get("details").get("delivery").asString()).isEqualTo("SENT");
            assertThat(audit.toString()).doesNotContain("test-ci-");
            runner.assertFramesValid();
        }
    }

    // ---- A binding gone, and the frame again at hello ----------------------------------------------------------------

    @Test
    void aWithdrawnGrantRevokesAndAHostThatReconnectsGetsTheRevocationAgainBeforeACancel() {
        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
            assertThat(bench.owner().delete(bench.path("/grants/" + credential.grantId())).status()).isEqualTo(204);
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("reason").asString()).isEqualTo("GRANT_REMOVED");
            assertThat(revoke.get("message").asString()).contains("the shared credential's grant was withdrawn");
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        // The person cancels the turn while the host is away: both commands wait for its hello.
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        ApiResponse cancelled = bench.member().post(bench.path("/runs/" + runId + "/cancel"), Map.of());
        assertThat(cancelled.status()).as("%s", cancelled).isBetween(200, 204);
        try (FakeRunner again = host(ref, List.of(REVOKE), List.of(Map.of("runId", runId, "epoch", epoch, "lastSeq",
                0)))) {
            JsonNode revoke = again.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revoke.get("reason").asString()).isEqualTo("GRANT_REMOVED");
            again.expect("run.cancel");
            again.expect(DONE);
            List<String> order = again.received().stream().map(frame -> frame.get("type").asString())
                .filter(type -> type.equals(REVOKE) || type.equals("run.cancel") || type.equals(DONE))
                .toList();
            // The marker ends the hello's answer: every pending revocation went before it.
            assertThat(order).containsExactly(REVOKE, "run.cancel", DONE);
            again.assertFramesValid();
        }
        assertThat(revocations(bench, runId)).hasSize(1);
        // The revocation sent after its commit, and the hello's answer, each with one connection at a time.
        assertThat(mark.at(REVOCATIONS)).as("second connections asked for").isZero();
    }

    // ---- The end of a hello's answer ---------------------------------------------------------------------------------

    @Test
    void aHostWithTheFeatureGetsWelcomeDoneAloneWhenNothingIsPendingAndOneWithoutGetsNone() {
        Bench bench = bench(false);
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            runner.expect(DONE);
            List<String> after = runner.received().stream().map(frame -> frame.get("type").asString())
                .dropWhile(type -> !type.equals("welcome"))
                .filter(type -> !type.equals("host.config") && !type.equals("psl.update"))
                .toList();
            assertThat(after).containsExactly("welcome", DONE);
            runner.expectNone(DONE, Duration.ofMillis(500));
            assertThat(runner.received().stream().filter(frame -> frame.get("type").asString().equals(DONE)))
                .hasSize(1);
            runner.assertFramesValid();
        }
        try (FakeRunner older = host(hostRef(bench), List.of("run.steer"), List.of())) {
            older.expectNone(DONE, Duration.ofSeconds(1));
        }
    }

    /**
     * A hello whose answer cannot be completed after {@code welcome} (its reconciliation, or the end of its answer,
     * fails): the connection is closed with 1011, which the host reconnects after, never with {@code welcome.done};
     * its registration goes; and the next hello is answered whole.
     */
    @Test
    void aHelloWhoseAnswerCannotBeCompletedClosesTheConnectionAndTheNextHelloGetsWelcomeDone() {
        Bench bench = bench(false);
        RunnerHostRef ref = hostRef(bench);
        for (Runnable failure : List.<Runnable>of(() -> TestRunnerSessions.failNextEnd(ref.id()),
                () -> TestRunnerSessions.failNextReconciliation(ref.id()))) {
            failure.run();
            try (FakeRunner runner = FakeRunner.connect(port, ref.token())) {
                Map<String, Object> hello = FakeRunner.helloV2(List.of(), List.of(), RUNNER_VERSION, 4);
                hello.put("features", List.of(REVOKE));
                runner.send(hello);
                runner.expect("welcome");
                assertThat(runner.awaitClose()).isEqualTo(1011);
                assertThat(runner.received()).noneSatisfy(frame -> assertThat(frame.get("type").asString())
                    .isEqualTo(DONE));
            }
            await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
            try (FakeRunner again = host(ref, List.of(REVOKE), List.of())) {
                again.expect(DONE);
                again.assertFramesValid();
            }
            await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        }
    }

    // ---- A paused system ------------------------------------------------------------------------------------------

    @Test
    void aSystemPausedWithStopRunningRevokesBeforeItCancels() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            ApiResponse paused = bench.owner().post(bench.path("/systems/" + git[0] + "/pause"),
                    Map.of("reason", "The token leaked", "stopRunning", true));
            assertThat(paused.status()).as("%s", paused).isEqualTo(200);
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("reason").asString()).isEqualTo("SYSTEM_PAUSED");
            assertThat(revoke.get("message").asString()).contains("was paused by an admin")
                .doesNotContain("The token leaked");
            runner.expect("run.cancel");
            List<String> order = runner.received().stream().map(frame -> frame.get("type").asString())
                .filter(type -> type.equals(REVOKE) || type.equals("run.cancel"))
                .toList();
            assertThat(order).containsExactly(REVOKE, "run.cancel");
            JsonNode audit = revocations(bench, assign.get("runId").asString()).getFirst();
            assertThat(audit.get("details").get("systemId").asString()).isEqualTo(git[0].toString());
            runner.assertFramesValid();
        }
    }

    /** A revocation sent whose mark fails after the pause's commit: the pause is answered, the mark goes at the hello. */
    @Test
    void aRevocationSentWhoseMarkFailsLeavesThePauseAnsweredAndTheRevocationPending() throws Exception {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            ApiResponse paused;
            try (AutoCloseable busy = ConnectionWatch.timeOutKeptWorkAt(REVOCATIONS)) {
                paused = bench.owner().post(bench.path("/systems/" + git[0] + "/pause"),
                        Map.of("reason", "The token leaked"));
            }
            assertThat(paused.status()).as("%s", paused).isEqualTo(200);
            assertThat(runner.expect(REVOKE).get("reason").asString()).isEqualTo("SYSTEM_PAUSED");
            assertThat(deliveryOf(bench, assign.get("runId").asString())).isEqualTo("PENDING");
        }
    }

    @Test
    void aSystemPausedWithoutStopRunningRevokesAndLetsTheRunGoOn() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            assertThat(bench.owner().post(bench.path("/systems/" + git[0] + "/pause"), Map.of("reason", "hold"))
                .status()).isEqualTo(200);
            assertThat(runner.expect(REVOKE).get("reason").asString()).isEqualTo("SYSTEM_PAUSED");
            runner.expectNone("run.cancel", Duration.ofSeconds(1));
            assertThat(bench.member().get(bench.path("/runs/" + assign.get("runId").asString())).json()
                .get("status").asString()).isEqualTo("RUNNING");
            // Resumed and paused again: the repository was revoked for this run already, so nothing more goes.
            assertThat(bench.owner().post(bench.path("/systems/" + git[0] + "/resume"), Map.of()).status())
                .isEqualTo(200);
            assertThat(bench.owner().post(bench.path("/systems/" + git[0] + "/pause"), Map.of("reason", "again"))
                .status()).isEqualTo(200);
            runner.expectNone(REVOKE, Duration.ofSeconds(1));
            assertThat(revocations(bench, assign.get("runId").asString())).hasSize(1);
        }
    }

    // ---- A host without the feature ------------------------------------------------------------------------------

    @Test
    void aHostWithoutTheFeatureGetsNothingAndTheRunKeepsItsTokenWhichTheConversationSays() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                    + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
            await().atMost(WAIT).until(() -> !revocations(bench, runId).isEmpty());
            runner.expectNone(REVOKE, Duration.ofSeconds(1));
            JsonNode audit = revocations(bench, runId).getFirst();
            assertThat(audit.get("details").get("sent").asBoolean()).isFalse();
            assertThat(audit.get("details").get("delivery").asString()).isEqualTo("UNSUPPORTED");
            JsonNode run = bench.member().get(bench.path("/runs/" + runId)).json();
            assertThat(run.get("repositories").get(0).get("gitRevoked").get("delivery").asString())
                .isEqualTo("UNSUPPORTED");
            JsonNode conversation = conversation(bench.member(), bench, run.get("conversationId").asString());
            assertThat(conversation.get("messages")).anySatisfy(message -> assertThat(message.get("body").asString())
                .startsWith("The turn under way keeps its git access to " + assign.get("workspaces").get(0)
                    .get("name").asString() + " until it ends"));
        }
    }

    // ---- The race with an assignment in progress ---------------------------------------------------------------------

    @Test
    void aBindingThatFailsBetweenTheRevealAndTheSendKeepsTheAssignmentFromGoing() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            // The conversation's first content is revealed, then the credential is disabled and its binding waits,
            // before the third phase records the assignment.
            List<String> conversationIds = new ArrayList<>();
            java.util.function.Consumer<com.flowtivo.tinfirm.run.TurnSource.TurnRef> hook = turn -> {
                assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                        + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
                await().atMost(WAIT).until(() -> "WAITING".equals(bench.member().get(bench.path("/conversations/"
                        + turn.conversationId())).json().get("bindings").get(0).get("state").asString()));
                conversationIds.add(turn.conversationId().toString());
            };
            TestTurns.afterAnyContent(agentId, hook);
            JsonNode conversation = start(bench.member(), bench, agentId, "Fix the cart");
            String runId = lastTurn(conversation).get("id").asString();
            await().atMost(WAIT).until(() -> !conversationIds.isEmpty());
            // Nothing went: no assignment, and no revocation, since no run held the token.
            runner.expectNone("run.assign", Duration.ofSeconds(2));
            runner.expectNone(REVOKE, Duration.ofMillis(10));
            await().atMost(WAIT).untilAsserted(() -> assertThat(bench.member().get(bench.path("/runs/" + runId))
                .json().path("queueReason").path("code").asString()).isEqualTo("ACCOUNT_REQUIRED"));
            assertThat(revocations(bench, runId)).isEmpty();
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.count("SELECT count(*) FROM run_repository WHERE run_id = ?", UUID.fromString(runId)))
                    .isZero();
            }
            catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
        }
        finally {
            TestTurns.afterAnyContent(agentId, null);
        }
    }

    @Test
    void aBindingThatWaitsAndIsBoundAgainBetweenTheRevealAndTheSendSendsTheAssignmentPreparedAgain() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        String path = bench.path("/systems/" + git[0] + "/credentials/" + credential.credentialId());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            // After the first reveal, the credential is disabled and enabled again: the binding waits and is bound
            // again, to the same account, before the third phase. Its version moved, so that token does not go.
            java.util.concurrent.atomic.AtomicLong revealedAt = new java.util.concurrent.atomic.AtomicLong(-1);
            TestTurns.afterAnyContent(agentId, turn -> {
                try (TenantSql sql = TenantSql.in(bench.id())) {
                    revealedAt.set(sql.count("SELECT epoch FROM run WHERE id = ?", turn.runId()));
                }
                catch (SQLException ex) {
                    throw new IllegalStateException(ex);
                }
                String conversation = bench.path("/conversations/" + turn.conversationId());
                assertThat(bench.owner().patch(path, Map.of("enabled", false)).status()).isEqualTo(200);
                await().atMost(WAIT).until(() -> "WAITING".equals(bench.member().get(conversation).json()
                    .get("bindings").get(0).get("state").asString()));
                assertThat(bench.owner().patch(path, Map.of("enabled", true)).status()).isEqualTo(200);
                await().atMost(WAIT).until(() -> "BOUND".equals(bench.member().get(conversation).json()
                    .get("bindings").get(0).get("state").asString()));
            });
            JsonNode conversation = start(bench.member(), bench, agentId, "Fix the cart");
            String runId = lastTurn(conversation).get("id").asString();
            // The run goes back to the queue and is prepared again: one assignment reaches the host, a later one.
            JsonNode assign = runner.expect("run.assign");
            assertThat(assign.get("runId").asString()).isEqualTo(runId);
            assertThat(revealedAt.get()).isPositive();
            assertThat(assign.get("epoch").asLong()).isGreaterThan(revealedAt.get());
            runner.expectNone("run.assign", Duration.ofSeconds(1));
            List<JsonNode> assigned = auditEvents(bench.owner(), bench.company(), "run.assigned").stream()
                .filter(event -> event.get("targetId").asString().equals(runId))
                .toList();
            assertThat(assigned).singleElement().satisfies(event -> assertThat(event.get("details").get("epoch")
                .asLong()).isEqualTo(assign.get("epoch").asLong()));
            assertThat(revocations(bench, runId)).isEmpty();
        }
        finally {
            TestTurns.afterAnyContent(agentId, null);
        }
    }

    // ---- A revocation that reaches the host before its assignment ----------------------------------------------------

    @Test
    void aRevocationOfAnAssignedRunGoesAgainWhenTheHostAcceptsIt() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            start(bench.member(), bench, agentId, "Fix the cart");
            JsonNode assign = runner.expect("run.assign");
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            // Assigned, not accepted yet: the revocation goes at once, and a host that had not taken the assignment
            // yet would have dropped it.
            assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                    + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
            JsonNode first = runner.expect(REVOKE);
            assertThat(first.get("runId").asString()).isEqualTo(runId);
            runner.accepted(UUID.fromString(runId), epoch);
            JsonNode again = runner.expect(REVOKE);
            assertThat(again.get("runId").asString()).isEqualTo(runId);
            assertThat(again.get("epoch").asLong()).isEqualTo(epoch);
            assertThat(again.get("componentIds")).isEqualTo(first.get("componentIds"));
            assertThat(revocations(bench, runId)).hasSize(1);
            runner.assertFramesValid();
        }
    }

    // ---- A run the server no longer tracks, reported by its host ------------------------------------------------------

    /**
     * The run's host is gone: its lease expires and the reaper loses the run ({@code LEASE_EXPIRED}); then, as if
     * longer ago than the resume window, its end moves back an hour.
     */
    private void lostLongAgo(Bench bench, RunnerHostRef ref, String runId) {
        lost(bench, ref, runId);
        sql(bench, "UPDATE run SET finished_at = now() - interval '1 hour' WHERE id = ?", UUID.fromString(runId));
    }

    private void lost(Bench bench, RunnerHostRef ref, String runId) {
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        await().atMost(WAIT).until(() -> {
            sql(bench, "UPDATE run SET lease_expires_at = now() - interval '1 minute' WHERE id = ? AND status = "
                    + "'RUNNING'", UUID.fromString(runId));
            return "LOST".equals(bench.member().get(bench.path("/runs/" + runId)).json().get("status").asString());
        });
    }

    private static void sql(Bench bench, String statement, Object... params) {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update(statement, params);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** The first {@code run.git.revoke} the host got went before its first {@code run.cancel}. */
    private void revokedBeforeTheCancel(FakeRunner runner) {
        List<String> order = answerOrder(runner);
        assertThat(order).as("%s", order).contains(REVOKE, "run.cancel");
        assertThat(order.indexOf(REVOKE)).as("%s", order).isLessThan(order.indexOf("run.cancel"));
    }

    /**
     * A revocation that another transaction committed and has not sent yet (a suspension's binding change, side by
     * side with the suspension's cancel) goes before a live cancel of the run: a cancelled run pushes its work.
     */
    @Test
    void aLiveCancelSendsFirstARevocationThatAnotherTransactionCommittedAndHasNotSentYet() throws SQLException {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        connectOwn(bench.member(), bench, git[0], "test-own-token");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            String componentId = assign.get("workspaces").get(0).get("componentId").asString();
            try (TenantSql sql = TenantSql.in(bench.id())) {
                sql.update("""
                        INSERT INTO run_command (id, company_id, run_id, type, status, created_at, epoch,
                                                 component_ids, reason, message, delivery)
                        VALUES (gen_random_uuid(), ?, ?, 'REVOKE_GIT', 'PENDING', now(), ?, ARRAY[?]::uuid[],
                                'SUSPENDED', 'Tinfirm revoked this run''s access: test.', 'ON_RECONNECT')""",
                        bench.id(), UUID.fromString(runId), assign.get("epoch").asLong(), UUID.fromString(componentId));
                sql.commit();
            }
            ApiResponse cancelled = bench.member().post(bench.path("/runs/" + runId + "/cancel"), null);
            assertThat(cancelled.status()).as("%s", cancelled).isLessThan(300);
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("componentIds")).singleElement()
                .satisfies(id -> assertThat(id.asString()).isEqualTo(componentId));
            assertThat(revoke.get("message").asString()).isEqualTo("Tinfirm revoked this run's access: test.");
            runner.expect("run.cancel");
            assertThat(answerOrder(runner)).containsExactly(DONE, REVOKE, "run.cancel");
            runner.assertFramesValid();
        }
    }

    private List<String> answerOrder(FakeRunner runner) {
        return runner.received().stream().map(frame -> frame.get("type").asString())
            .filter(type -> type.equals(REVOKE) || type.equals("run.cancel") || type.equals(DONE))
            .toList();
    }

    @Test
    void aRevocationRecordedWhileTheRunWasTrackedGoesBeforeTheCancelOfARunLostPastTheWindow() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        // The system is paused while the host is away: the revocation waits for its hello.
        assertThat(bench.owner().post(bench.path("/systems/" + git[0] + "/pause"), Map.of("reason", "hold"))
            .status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> !revocations(bench, runId).isEmpty());
        assertThat(revocations(bench, runId).getFirst().get("details").get("delivery").asString())
            .isEqualTo("ON_RECONNECT");
        assertThat(bench.member().get(bench.path("/runs/" + runId)).json().get("repositories").get(0)
            .get("gitRevoked").get("delivery").asString()).isEqualTo("PENDING");
        // The run is lost, longer ago than the resume window: its end completed the revocation.
        lostLongAgo(bench, ref, runId);

        // The host comes back with the run it took back: it cannot be resumed, and is cancelled, after the revocation.
        try (FakeRunner again = host(ref, List.of(REVOKE), List.of(Map.of("runId", runId, "epoch", epoch, "lastSeq",
                0)))) {
            JsonNode revoke = again.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revoke.get("epoch").asLong()).isEqualTo(epoch);
            assertThat(revoke.get("reason").asString()).isEqualTo("SYSTEM_PAUSED");
            again.expect("run.cancel");
            again.expect(DONE);
            assertThat(answerOrder(again)).containsExactly(REVOKE, "run.cancel", DONE);
            again.assertFramesValid();
        }
        assertThat(revocations(bench, runId)).hasSize(1);
        assertThat(bench.member().get(bench.path("/runs/" + runId)).json().get("repositories").get(0)
            .get("gitRevoked").get("delivery").asString()).isEqualTo("SENT");
    }

    @Test
    void aCauseAfterTheServerStoppedTrackingTheRunIsRevokedBeforeTheCancelAtTheHostsHello() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        String conversationId = bench.member().get(bench.path("/runs/" + runId)).json().get("conversationId")
            .asString();
        lostLongAgo(bench, ref, runId);
        // The credential is disabled after the window: no revocation reaches a run the server no longer tracks.
        assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> "WAITING".equals(bench.member().get(bench.path("/conversations/"
                + conversationId)).json().get("bindings").get(0).get("state").asString()));
        assertThat(revocations(bench, runId)).isEmpty();

        // Its host comes back with it: the hello checks the run's bindings before it cancels the run.
        try (FakeRunner again = host(ref, List.of(REVOKE), List.of(Map.of("runId", runId, "epoch", epoch, "lastSeq",
                0)))) {
            JsonNode revoke = again.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revoke.get("epoch").asLong()).isEqualTo(epoch);
            assertThat(revoke.get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            assertThat(revoke.get("componentIds").get(0).asString()).isEqualTo(assign.get("workspaces").get(0)
                .get("componentId").asString());
            assertThat(revoke.get("message").asString()).contains("the shared credential " + credential.name());
            again.expect("run.cancel");
            again.expect(DONE);
            assertThat(answerOrder(again)).containsExactly(REVOKE, "run.cancel", DONE);
            again.assertFramesValid();
        }
        JsonNode audit = revocations(bench, runId).getFirst();
        assertThat(revocations(bench, runId)).hasSize(1);
        assertThat(audit.get("details").get("delivery").asString()).isEqualTo("SENT");
        assertThat(audit.get("details").get("bindingId").asString()).isNotBlank();
        assertThat(bench.member().get(bench.path("/runs/" + runId)).json().get("repositories").get(0)
            .get("gitRevoked").get("reason").asString()).isEqualTo("RECONNECT_COMPANY");

        // It comes back again: the recorded revocation goes again before the cancel, and nothing is recorded twice.
        try (FakeRunner third = host(ref, List.of(REVOKE), List.of(Map.of("runId", runId, "epoch", epoch, "lastSeq",
                0)))) {
            third.expect(REVOKE);
            third.expect(DONE);
            assertThat(answerOrder(third)).containsExactly(REVOKE, "run.cancel", DONE);
        }
        assertThat(revocations(bench, runId)).hasSize(1);
    }

    @Autowired
    com.flowtivo.tinfirm.runner.RunnerSessionListener listener;

    private String deliveryOf(Bench bench, String runId) {
        return bench.member().get(bench.path("/runs/" + runId)).json().get("repositories").get(0).get("gitRevoked")
            .get("delivery").asString();
    }

    @Test
    void aRevocationInAHellosAnswerCountsAsSentOnlyOnceTheAnswerWasWritten() {
        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> !revocations(bench, runId).isEmpty());
        assertThat(deliveryOf(bench, runId)).isEqualTo("PENDING");
        List<Map<String, Object>> reported = List.of(Map.of("runId", runId, "epoch", epoch, "lastSeq", 0));
        try (FakeRunner again = host(ref, List.of(REVOKE), reported)) {
            again.expect(REVOKE);
            again.expect(DONE);
            // Written to the connection: told.
            await().atMost(WAIT).until(() -> "SENT".equals(deliveryOf(bench, runId)));

            // The same reconciliation whose answer is not written (a connection that closed): still pending.
            sql(bench, "UPDATE run_command SET delivery = 'ON_RECONNECT', last_sent_at = NULL WHERE run_id = ?",
                    UUID.fromString(runId));
            ConnectedHost host = new ConnectedHost(ref.id(), bench.id(), 2);
            List<RunnerMessages.Outbound> answer = SystemContext.callInCompany(bench.id(),
                    () -> listener.hostConnected(host, List.of(new RunnerMessages.ActiveRun(UUID.fromString(runId),
                            epoch, 0L)), null));
            List<RunnerMessages.RunGitRevoke> frames = answer.stream()
                .filter(RunnerMessages.RunGitRevoke.class::isInstance)
                .map(RunnerMessages.RunGitRevoke.class::cast)
                .toList();
            assertThat(frames).singleElement().satisfies(frame -> assertThat(frame.runId().toString())
                .isEqualTo(runId));
            assertThat(deliveryOf(bench, runId)).isEqualTo("PENDING");
            // Once written, it counts as sent.
            SystemContext.runInCompany(bench.id(), () -> listener.revocationsWritten(bench.id(), frames));
            assertThat(deliveryOf(bench, runId)).isEqualTo("SENT");
        }
        // Each company's revocations are read in the hello's own transaction.
        assertThat(mark.at(REVOCATIONS)).as("second connections asked for").isZero();
    }

    @Test
    void aRunThatAHostWithoutTheFeatureReportsAndThatIsCancelledShowsItsRevocationUnsupported() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> !revocations(bench, runId).isEmpty());
        lostLongAgo(bench, ref, runId);
        assertThat(deliveryOf(bench, runId)).isEqualTo("PENDING");
        // The host comes back an older release, with the run: it is cancelled and can never be told.
        try (FakeRunner older = host(ref, List.of(), List.of(Map.of("runId", runId, "epoch", epoch, "lastSeq", 0)))) {
            older.expect("run.cancel");
            older.expectNone(REVOKE, Duration.ofMillis(500));
        }
        assertThat(deliveryOf(bench, runId)).isEqualTo("UNSUPPORTED");
    }

    // ---- The host's confirmation (Q48 j) -----------------------------------------------------------------------------

    private List<JsonNode> confirmations(Bench bench, String runId) {
        return auditEvents(bench.owner(), bench.company(), "run.git_revoke_confirmed").stream()
            .filter(event -> event.get("targetId").asString().equals(runId))
            .toList();
    }

    private static Map<String, Object> revoked(String runId, long epoch, List<String> componentIds) {
        return Map.of("type", "run.git.revoked", "runId", runId, "epoch", epoch, "componentIds", componentIds);
    }

    @Test
    void aHostThatConfirmsARevocationShowsItConfirmedAndIsAuditedOnce() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            long epoch = assign.get("epoch").asLong();
            List<String> component = List.of(assign.get("workspaces").get(0).get("componentId").asString());
            assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                    + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
            runner.expect(REVOKE);
            // Written, not confirmed yet.
            await().atMost(WAIT).until(() -> "SENT".equals(deliveryOf(bench, runId)));
            assertThat(confirmations(bench, runId)).isEmpty();

            // Another epoch and an unknown run are ignored, without an error.
            runner.send(revoked(runId, epoch + 1, component));
            runner.send(revoked(UUID.randomUUID().toString(), epoch, component));
            runner.expectNone("error", Duration.ofMillis(500));
            assertThat(deliveryOf(bench, runId)).isEqualTo("SENT");

            // The host dropped the token: confirmed, with the time, and audited with the host.
            runner.send(revoked(runId, epoch, component));
            await().atMost(WAIT).until(() -> "CONFIRMED".equals(deliveryOf(bench, runId)));
            JsonNode gitRevoked = bench.member().get(bench.path("/runs/" + runId)).json().get("repositories").get(0)
                .get("gitRevoked");
            assertThat(gitRevoked.get("confirmedAt").asString()).isNotBlank();
            JsonNode audit = confirmations(bench, runId).getFirst();
            assertThat(audit.get("actor").get("type").asString()).isEqualTo("SYSTEM");
            assertThat(audit.get("details").get("runnerHostId").asString()).isEqualTo(ref.id().toString());
            assertThat(audit.get("details").get("epoch").asLong()).isEqualTo(epoch);
            assertThat(audit.get("details").get("componentIds")).extracting(JsonNode::asString)
                .containsExactlyElementsOf(component);
            assertThat(audit.get("details").get("reason").asString()).isEqualTo("RECONNECT_COMPANY");

            // The same answer again changes nothing.
            runner.send(revoked(runId, epoch, component));
            runner.expectNone("error", Duration.ofMillis(500));
            assertThat(confirmations(bench, runId)).hasSize(1);
            runner.assertFramesValid();
        }
    }

    // ---- A revocation that commits while a hello's answer ends (Q48 a) ----------------------------------------------

    @Test
    void aRevocationThatCommitsBeforeWelcomeDoneIsWrittenGoesBeforeIt() throws Exception {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        // The hello's reconciliation read the run's revocations before this one committed: it is not in its answer.
        assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> !revocations(bench, runId).isEmpty());
        assertThat(deliveryOf(bench, runId)).isEqualTo("PENDING");
        Map<ConnectedHost, List<RunnerMessages.ActiveRun>> listed = Map.of(new ConnectedHost(ref.id(), bench.id(), 2),
                List.of(new RunnerMessages.ActiveRun(UUID.fromString(runId), epoch, 0L)));

        // The answer ends: the revocation goes, then welcome.done, and it counts as sent.
        List<RunnerMessages.Outbound> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        listener.endHelloAnswer(listed, written::add);
        assertThat(written).hasSize(2);
        assertThat(written.getFirst()).isInstanceOfSatisfying(RunnerMessages.RunGitRevoke.class,
                frame -> assertThat(frame.runId().toString()).isEqualTo(runId));
        assertThat(written.getLast()).isInstanceOf(RunnerMessages.WelcomeDone.class);
        assertThat(deliveryOf(bench, runId)).isEqualTo("SENT");

        // A revocation whose transaction holds its lock and has not committed yet: welcome.done waits for its commit,
        // and the revocation goes before it.
        written.clear();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.count("SELECT count(*) FROM (SELECT pg_advisory_xact_lock_shared(hashtextextended(?, 0))) held",
                    "run-git-revocations:" + ref.id());
            sql.update("UPDATE run_command SET delivery = 'ON_RECONNECT', last_sent_at = NULL WHERE run_id = ?",
                    UUID.fromString(runId));
            java.util.concurrent.CompletableFuture<Void> ending = java.util.concurrent.CompletableFuture
                .runAsync(() -> listener.endHelloAnswer(listed, written::add));
            Thread.sleep(500);
            assertThat(written).as("nothing is written while a revocation may still commit").isEmpty();
            sql.commit();
            ending.get(20, java.util.concurrent.TimeUnit.SECONDS);
        }
        assertThat(written).hasSize(2);
        assertThat(written.getFirst()).isInstanceOf(RunnerMessages.RunGitRevoke.class);
        assertThat(written.getLast()).isInstanceOf(RunnerMessages.WelcomeDone.class);

        // A presence that the assignment did not go to hears nothing of the run.
        written.clear();
        sql(bench, "UPDATE run_command SET delivery = 'ON_RECONNECT', last_sent_at = NULL WHERE run_id = ?",
                UUID.fromString(runId));
        listener.endHelloAnswer(Map.of(new ConnectedHost(UUID.randomUUID(), bench.id(), 2),
                List.of(new RunnerMessages.ActiveRun(UUID.fromString(runId), epoch, 0L))), written::add);
        assertThat(written).singleElement().isInstanceOf(RunnerMessages.WelcomeDone.class);
    }

    /** A run of the bench's company host whose revocation waits for the host's hello. */
    private record Pending(Bench bench, RunnerHostRef ref, String runId, long epoch) {

        RunnerMessages.ActiveRun active() {
            return new RunnerMessages.ActiveRun(UUID.fromString(runId), epoch, 0L);
        }
    }

    private Pending pendingRevocation() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> !revocations(bench, runId).isEmpty());
        assertThat(deliveryOf(bench, runId)).isEqualTo("PENDING");
        return new Pending(bench, ref, runId, assign.get("epoch").asLong());
    }

    /**
     * A hello's presences in two companies: each company's revocations are read under that company's tenant context,
     * in the hello's one transaction, and none of another company's: a run listed under a presence in a company that
     * is not the run's is invisible there.
     */
    @Test
    void aHelloWithPresencesInTwoCompaniesReadsEachCompanysRevocationsAndNoneOfAnothers() {
        Pending first = pendingRevocation();
        Pending second = pendingRevocation();
        Map<ConnectedHost, List<RunnerMessages.ActiveRun>> listed = new java.util.LinkedHashMap<>();
        listed.put(new ConnectedHost(first.ref().id(), first.bench().id(), 2), List.of(first.active()));
        listed.put(new ConnectedHost(second.ref().id(), second.bench().id(), 2), List.of(second.active()));
        // The first company's run, listed again by a presence in the second company: row-level security hides it.
        listed.put(new ConnectedHost(first.ref().id(), second.bench().id(), 2), List.of(first.active()));

        ConnectionWatch.Mark mark = ConnectionWatch.mark();
        List<RunnerMessages.Outbound> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        listener.endHelloAnswer(listed, written::add);

        assertThat(written).hasSize(3);
        assertThat(written.subList(0, 2)).extracting(frame -> ((RunnerMessages.RunGitRevoke) frame).runId().toString())
            .containsExactly(first.runId(), second.runId());
        assertThat(written.getLast()).isInstanceOf(RunnerMessages.WelcomeDone.class);
        assertThat(deliveryOf(first.bench(), first.runId())).isEqualTo("SENT");
        assertThat(deliveryOf(second.bench(), second.runId())).isEqualTo("SENT");
        assertThat(mark.at(REVOCATIONS)).as("second connections asked for").isZero();
    }

    // ---- An earlier execution (Q48 c and d) ---------------------------------------------------------------------------

    /**
     * The run is assigned again (as a later assignment would: a new epoch, the earlier rows replaced), and then the
     * credential its earlier assignment cloned with is disabled: nothing is recorded for that earlier execution.
     */
    private long assignedAgainThenDisabled(Bench bench, String runId, UUID system, Lent credential) {
        UUID run = UUID.fromString(runId);
        sql(bench, "UPDATE run SET epoch = epoch + 1 WHERE id = ?", run);
        sql(bench, "DELETE FROM run_repository WHERE run_id = ?", run);
        String conversationId = bench.member().get(bench.path("/runs/" + runId)).json().get("conversationId")
            .asString();
        assertThat(bench.owner().patch(bench.path("/systems/" + system + "/credentials/"
                + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
        await().atMost(WAIT).until(() -> "WAITING".equals(bench.member().get(bench.path("/conversations/"
                + conversationId)).json().get("bindings").get(0).get("state").asString()));
        assertThat(revocations(bench, runId)).isEmpty();
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.count("SELECT epoch FROM run WHERE id = ?", run);
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aHeartbeatOfAnEarlierEpochGetsTheCheckOfItsRepositoriesBeforeItsCancel() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            long earlier = assign.get("epoch").asLong();
            assertThat(assignedAgainThenDisabled(bench, runId, git[0], credential)).isGreaterThan(earlier);

            runner.heartbeat(UUID.fromString(runId), earlier);
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("epoch").asLong()).isEqualTo(earlier);
            assertThat(revoke.get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            assertThat(revoke.get("componentIds").get(0).asString()).isEqualTo(assign.get("workspaces").get(0)
                .get("componentId").asString());
            JsonNode cancel = runner.expect("run.cancel");
            assertThat(cancel.get("epoch").asLong()).isEqualTo(earlier);
            assertThat(answerOrder(runner)).containsExactly(DONE, REVOKE, "run.cancel");
            assertThat(revocations(bench, runId)).singleElement().satisfies(event -> assertThat(event.get("details")
                .get("epoch").asLong()).isEqualTo(earlier));
            runner.assertFramesValid();
        }
    }

    @Test
    void anEarlierExecutionWhoseRowsALaterAssignmentReplacedIsCheckedAtTheHostsHello() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long earlier = assign.get("epoch").asLong();
        await().atMost(WAIT).until(() -> !connections.isConnected(ref.id()));
        assignedAgainThenDisabled(bench, runId, git[0], credential);

        try (FakeRunner again = host(ref, List.of(REVOKE), List.of(Map.of("runId", runId, "epoch", earlier,
                "lastSeq", 0)))) {
            JsonNode revoke = again.expect(REVOKE);
            assertThat(revoke.get("epoch").asLong()).isEqualTo(earlier);
            assertThat(revoke.get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            again.expect("run.cancel");
            again.expect(DONE);
            assertThat(answerOrder(again)).containsExactly(REVOKE, "run.cancel", DONE);
            again.assertFramesValid();
        }
        assertThat(revocations(bench, runId)).hasSize(1);
    }

    // ---- A run of a company the host's machine is no longer in (Q48 e) ------------------------------------------------

    @Test
    void aRunOfACompanyTheMachineLeftGetsItsRevocationsBeforeItsCancelAndOnlyFromTheMachineItWentTo()
            throws SQLException {
        Bench bench = bench(false);
        UUID[] git = gitHost(bench, "Shop");
        RunnerHostRef presence = registerRunnerHost(bench.member(), bench.id(), unique("laptop"), "PERSONAL");
        UUID machine;
        try (TenantSql sql = TenantSql.in(bench.id())) {
            machine = (UUID) sql.column("SELECT personal_host_id FROM runner_host WHERE id = ?", presence.id())
                .getFirst();
        }
        // A turn of the company's that ran on the member's machine, ended; its assignment cloned the repository.
        UUID agentId = TestRuns.agent(bench.id());
        UUID author = bench.ownerPrincipal();
        UUID triggerId = TestTriggers.schedule(bench.id(), agentId, author, author);
        UUID conversationId = UUID.randomUUID();
        String key = "left-" + conversationId.toString().substring(0, 8);
        TestTriggers.conversation(bench.id(), conversationId, key, triggerId, agentId, author, author);
        UUID runId = UUID.randomUUID();
        TestRuns.ofTrigger(bench.id(), runId, conversationId, key, agentId, triggerId, author, author, "SUCCEEDED");
        // Another of its turns, whose assignment went to another machine.
        UUID otherId = UUID.randomUUID();
        TestRuns.ofTrigger(bench.id(), otherId, conversationId, key, agentId, triggerId, author, author, "SUCCEEDED");
        try (TenantSql sql = TenantSql.in(bench.id())) {
            for (Object[] assignment : List.of(new Object[] {runId, machine}, new Object[] {otherId,
                    UUID.randomUUID()})) {
                sql.update("""
                        INSERT INTO run_assignment_repository (run_id, company_id, epoch, component_id, position,
                                                               name, system_id, binding_id, binding_version,
                                                               runner_host_id, personal_host_id)
                        VALUES (?, ?, 1, ?, 0, 'acme/shop', ?, ?, 0, ?, ?)""", assignment[0], bench.id(), git[1],
                        git[0], UUID.randomUUID(), presence.id(), assignment[1]);
            }
            sql.commit();
        }
        assertThat(bench.owner().post(bench.path("/systems/" + git[0] + "/pause"), Map.of("reason", "hold"))
            .status()).isEqualTo(200);
        // The member leaves the company: their machine has no presence there any more.
        assertThat(bench.owner().delete(bench.path("/members/" + bench.company().userId("MEMBER"))).status())
            .isEqualTo(204);
        List<Map<String, Object>> reported = List.of(Map.of("runId", runId.toString(), "epoch", 1, "lastSeq", 0),
                Map.of("runId", otherId.toString(), "epoch", 1, "lastSeq", 0));

        try (FakeRunner laptop = FakeRunner.connect(port, presence.token())) {
            Map<String, Object> hello = FakeRunner.helloV2(List.of(), reported, RUNNER_VERSION, 4);
            hello.put("features", List.of(REVOKE));
            laptop.send(hello);
            laptop.expect("welcome");
            JsonNode revoke = laptop.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId.toString());
            assertThat(revoke.get("epoch").asLong()).isEqualTo(1);
            assertThat(revoke.get("reason").asString()).isEqualTo("SYSTEM_PAUSED");
            assertThat(revoke.get("componentIds").get(0).asString()).isEqualTo(git[1].toString());
            laptop.expect(DONE);
            // The run it held: its revocation, then its cancel. The other: a cancel alone, nothing of the run.
            List<String> order = laptop.received().stream()
                .filter(frame -> List.of(REVOKE, "run.cancel").contains(frame.get("type").asString()))
                .map(frame -> frame.get("type").asString() + " " + frame.get("runId").asString())
                .toList();
            assertThat(order).containsExactlyInAnyOrder(REVOKE + " " + runId, "run.cancel " + runId,
                    "run.cancel " + otherId);
            assertThat(order.indexOf(REVOKE + " " + runId)).isLessThan(order.indexOf("run.cancel " + runId));
            assertThat(answerOrder(laptop).getLast()).isEqualTo(DONE);
            laptop.assertFramesValid();
        }
        assertThat(revocations(bench, runId.toString())).singleElement().satisfies(event -> assertThat(event
            .get("details").get("reason").asString()).isEqualTo("SYSTEM_PAUSED"));
        assertThat(revocations(bench, otherId.toString())).isEmpty();
    }

    // ---- One cause that revokes and cancels: the revocation first ----------------------------------------------------

    @Test
    void aSuspendedMembersRunHearsTheRevocationOfTheirAccountBeforeItsCancel() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        connectOwn(bench.member(), bench, git[0], "test-own-token");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            assertThat(assign.get("workspaces").get(0).get("git").get("token").asString())
                .isEqualTo("test-own-token");
            ApiResponse suspended = bench.owner().post(bench.path("/members/" + bench.company().userId("MEMBER")
                    + "/suspend"), Map.of("reason", "Left the project"));
            assertThat(suspended.status()).as("%s", suspended).isEqualTo(200);
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revoke.get("reason").asString()).isEqualTo("SUSPENDED");
            assertThat(revoke.get("message").asString()).isEqualTo("Tinfirm revoked this run's access to "
                    + assign.get("workspaces").get(0).get("name").asString() + ": the account's holder was suspended. "
                    + "Fetching from and pushing to it no longer works in this run.");
            runner.expect("run.cancel");
            // The cancel's run pushes its work: the token of the suspended member's account went first. The binding's
            // own change after the commit, beside the cancel, may have recorded it (then the cancel sends it again
            // first, and its own frame may come again after), but it is recorded once.
            revokedBeforeTheCancel(runner);
            await().pollDelay(Duration.ofSeconds(1)).atMost(WAIT).until(() -> revocations(bench, runId).size() == 1);
            runner.assertFramesValid();
        }
    }

    @Test
    void aMemberWhoLeavesHasTheirRunsGitAccessRevokedBeforeItsCancel() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(REVOKE), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            assertThat(bench.owner().delete(bench.path("/members/" + bench.company().userId("MEMBER"))).status())
                .isEqualTo(204);
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revoke.get("reason").asString()).isEqualTo("LEFT_GROUP");
            assertThat(revoke.get("message").asString()).contains("the person left the company");
            runner.expect("run.cancel");
            assertThat(answerOrder(runner)).containsExactly(DONE, REVOKE, "run.cancel");
            runner.assertFramesValid();
        }
    }

    /** The conversation's notices that its turn keeps its git access, read as stored. */
    private static long keptNotices(Bench bench, String runId) {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            return sql.count("""
                    SELECT count(*) FROM message m JOIN run r ON r.conversation_id = m.conversation_id
                     WHERE r.id = ? AND m.kind = 'SYSTEM' AND m.body LIKE 'The turn under way keeps its git access to %'""",
                    UUID.fromString(runId));
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void aSuspendedMembersRunOnAHostWithoutTheFeatureSaysItKeepsItsTokenOnce() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        connectOwn(bench.member(), bench, git[0], "test-own-token");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            assertThat(bench.owner().post(bench.path("/members/" + bench.company().userId("MEMBER") + "/suspend"),
                    Map.of("reason", "Left the project")).status()).isEqualTo(200);
            runner.expect("run.cancel");
            runner.expectNone(REVOKE, Duration.ofMillis(500));
            assertThat(revocations(bench, runId)).singleElement().satisfies(event -> assertThat(event.get("details")
                .get("delivery").asString()).isEqualTo("UNSUPPORTED"));
            // In the suspension's transaction, and not again at the binding's own change after the commit.
            assertThat(keptNotices(bench, runId)).isEqualTo(1);
        }
    }

    @Test
    void aLeavingMembersRunOnAHostWithoutTheFeatureSaysItKeepsItsTokenOnce() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        try (FakeRunner runner = host(hostRef(bench), List.of(), List.of())) {
            JsonNode assign = running(bench, runner, agentId);
            String runId = assign.get("runId").asString();
            assertThat(bench.owner().delete(bench.path("/members/" + bench.company().userId("MEMBER"))).status())
                .isEqualTo(204);
            runner.expect("run.cancel");
            runner.expectNone(REVOKE, Duration.ofMillis(500));
            assertThat(revocations(bench, runId)).singleElement().satisfies(event -> assertThat(event.get("details")
                .get("reason").asString()).isEqualTo("LEFT_GROUP"));
            // Written to the conversation before it ended, in the leaving transaction.
            assertThat(keptNotices(bench, runId)).isEqualTo(1);
        }
    }

    // ---- A lost run whose host is connected, resumed by a heartbeat ---------------------------------------------------

    @Test
    void aLostRunsConnectedHostHearsAtOnceAndAgainWhenAHeartbeatResumesTheRun() {
        Bench bench = bench(true);
        UUID[] git = gitHost(bench, "Shop");
        Lent credential = lent(bench, git[0], "WRITE");
        UUID agentId = agent(bench.member(), bench, "Builder", "", List.<UUID[]>of(git), "WRITE", List.of());
        RunnerHostRef ref = hostRef(bench);
        JsonNode assign;
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            assign = running(bench, runner, agentId);
        }
        String runId = assign.get("runId").asString();
        long epoch = assign.get("epoch").asLong();
        lost(bench, ref, runId);
        // The host is back, without the run in its hello (it took the run back later): the run stays lost.
        try (FakeRunner again = host(ref, List.of(REVOKE), List.of())) {
            again.expect(DONE);
            assertThat(bench.owner().patch(bench.path("/systems/" + git[0] + "/credentials/"
                    + credential.credentialId()), Map.of("enabled", false)).status()).isEqualTo(200);
            // The lost run's host is connected and takes revocations: it hears at once.
            JsonNode revoke = again.expect(REVOKE, Duration.ofSeconds(1));
            assertThat(revoke.get("runId").asString()).isEqualTo(runId);
            assertThat(revocations(bench, runId).getFirst().get("details").get("delivery").asString())
                .isEqualTo("SENT");
            // A heartbeat resumes the run: its revocation, which the resumption opened again, goes again.
            again.heartbeat(UUID.fromString(runId), epoch);
            await().atMost(WAIT).until(() -> "RUNNING".equals(bench.member().get(bench.path("/runs/" + runId))
                .json().get("status").asString()));
            JsonNode resent = again.expect(REVOKE);
            assertThat(resent.get("runId").asString()).isEqualTo(runId);
            assertThat(resent.get("reason").asString()).isEqualTo("RECONNECT_COMPANY");
            again.expectNone("run.cancel", Duration.ofMillis(500));
            again.assertFramesValid();
        }
        assertThat(revocations(bench, runId)).hasSize(1);
    }

    // ---- A trigger's binding that follows another credential -------------------------------------------------------

    @Test
    void aTriggersBindingThatFollowsAnotherCredentialRevokesTheOldOnesToken() throws SQLException {
        Bench bench = bench(false);
        UUID[] git = gitHost(bench, "Shop");
        UUID bot = createServiceIdentity(bench.owner(), bench.company(), unique("Bot"));
        // The member, the trigger's author, may use the service account.
        UUID team = createGroup(bench.owner(), bench.company(), unique("Team"),
                List.of(bench.company().userId("MEMBER")));
        assertThat(bench.owner().put(bench.path("/service-identities/" + bot + "/users"),
                Map.of("groupIds", List.of(team.toString()))).status()).isEqualTo(200);
        UUID first = TestCredentials.add(bench.owner(), bench.path(""), git[0], "First", "test-first-token");
        TestCredentials.lend(bench.owner(), bench.path(""), git[0], first, bot, "WRITE");
        UUID agentId = TestRuns.agent(bench.id());
        UUID author = bench.memberPrincipal();
        UUID triggerId = TestTriggers.schedule(bench.id(), agentId, author, author);
        UUID conversationId = UUID.randomUUID();
        String key = "follow-" + conversationId.toString().substring(0, 8);
        TestTriggers.conversation(bench.id(), conversationId, key, triggerId, agentId, author, author);
        RunnerHostRef ref = hostRef(bench);
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            UUID runId = UUID.randomUUID();
            UUID bindingId = UUID.randomUUID();
            try (TenantSql sql = TenantSql.in(bench.id())) {
                sql.update("""
                        INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how,
                                                          account_id, holder_principal_id, bound_at, created_at,
                                                          updated_at)
                        VALUES (?, ?, ?, ?, 'BOUND', 'FIXED', ?, ?, now(), now(), now())""", bindingId, bench.id(),
                        conversationId, git[0], first, bot);
                sql.commit();
            }
            triggerRun(bench, runId, conversationId, key, agentId, triggerId, author, ref);
            repository(bench, runId, 1, git, bindingId);

            // Another credential lent to the service account: the binding follows it, and the run loses the first's.
            UUID second = TestCredentials.add(bench.owner(), bench.path(""), git[0], "Second", "test-second-token");
            TestCredentials.lend(bench.owner(), bench.path(""), git[0], second, bot, "WRITE");
            JsonNode revoke = runner.expect(REVOKE);
            assertThat(revoke.get("runId").asString()).isEqualTo(runId.toString());
            assertThat(revoke.get("reason").asString()).isEqualTo("CREDENTIAL_REPLACED");
            assertThat(revoke.get("componentIds").get(0).asString()).isEqualTo(git[1].toString());
            assertThat(revoke.get("message").asString()).contains("borrows another credential");
            runner.assertFramesValid();
        }
    }

    // ---- Kill switches that hold revoke nothing ----------------------------------------------------------------------

    @Test
    void pausingTheAgentOrTheTriggerRevokesNothing() throws SQLException {
        Bench bench = bench(false);
        UUID[] git = gitHost(bench, "Shop");
        UUID agentId = TestRuns.agent(bench.id());
        UUID author = bench.memberPrincipal();
        UUID account = connectOwn(bench.member(), bench, git[0], "test-own-token");
        UUID triggerId = TestTriggers.schedule(bench.id(), agentId, author, author);
        UUID conversationId = UUID.randomUUID();
        String key = "hold-" + conversationId.toString().substring(0, 8);
        TestTriggers.conversation(bench.id(), conversationId, key, triggerId, agentId, author, author);
        RunnerHostRef ref = hostRef(bench);
        try (FakeRunner runner = host(ref, List.of(REVOKE), List.of())) {
            UUID runId = UUID.randomUUID();
            UUID bindingId = UUID.randomUUID();
            try (TenantSql sql = TenantSql.in(bench.id())) {
                sql.update("""
                        INSERT INTO conversation_binding (id, company_id, conversation_id, system_id, state, how,
                                                          account_id, holder_principal_id, bound_at, created_at,
                                                          updated_at)
                        VALUES (?, ?, ?, ?, 'BOUND', 'FIXED', ?, ?, now(), now(), now())""", bindingId, bench.id(),
                        conversationId, git[0], account, author);
                sql.commit();
            }
            triggerRun(bench, runId, conversationId, key, agentId, triggerId, author, ref);
            repository(bench, runId, 1, git, bindingId);
            assertThat(bench.owner().post(bench.path("/agents/" + agentId + "/pause"), Map.of()).status())
                .isEqualTo(200);
            ApiResponse paused = bench.owner().post(bench.path("/triggers/" + triggerId + "/pause"), Map.of());
            assertThat(paused.status()).as("%s", paused).isEqualTo(200);
            runner.expectNone(REVOKE, Duration.ofSeconds(2));
            assertThat(revocations(bench, runId.toString())).isEmpty();
        }
    }

    /** A RUNNING run of the trigger's conversation, epoch 1, on the host. */
    private static void triggerRun(Bench bench, UUID runId, UUID conversationId, String key, UUID agentId,
            UUID triggerId, UUID author, RunnerHostRef ref) throws SQLException {
        TestRuns.ofTrigger(bench.id(), runId, conversationId, key, agentId, triggerId, author, author, "RUNNING");
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("""
                    UPDATE run SET runner_host_id = ?, runner_host_name = 'test-host', runner_host_kind = 'COMPANY'
                     WHERE id = ?""", ref.id(), runId);
            sql.commit();
        }
    }

    /** The run's assignment of {@code epoch} cloned the repository with the binding's account. */
    private static void repository(Bench bench, UUID runId, long epoch, UUID[] git, UUID bindingId)
            throws SQLException {
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("""
                    INSERT INTO run_repository (run_id, company_id, position, repository_id, system_id, binding_id,
                                                name, dir, component_id, epoch)
                    VALUES (?, ?, 0, ?, ?, ?, 'acme/shop', 'shop', ?, ?)""", runId, bench.id(), git[1], git[0],
                    bindingId, git[1], epoch);
            sql.commit();
        }
    }
}
