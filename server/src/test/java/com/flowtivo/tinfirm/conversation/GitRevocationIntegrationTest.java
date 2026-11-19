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

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestCredentials;
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
            assertThat(repository.get("gitRevoked").get("hostTold").asBoolean()).isTrue();
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
            assertThat(run.get("repositories").get(0).get("gitRevoked").get("hostTold").asBoolean()).isFalse();
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
