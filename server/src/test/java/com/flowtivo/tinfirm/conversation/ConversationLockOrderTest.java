package com.flowtivo.tinfirm.conversation;

import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.RunnerFileRequests;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestDatabase;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * The lock order of a conversation and its runs (CONVENTIONS §10): the conversation's row first, then the run's, then
 * the company's audit chain head. Three writers of one running turn race, released together, round after round: the
 * host's {@code run.finished}, the person's cancel and the host's upload of a {@code save_artifact} file. Under the
 * old order the run's end and the cancel locked the run first and the conversation after it, while the upload locked
 * the conversation and then waited for the run (its attachment's foreign key to the run), so they deadlocked
 * ({@code 40P01}) and one of them was lost: a run's end, a cancel, or a file. Now every one of them locks the
 * conversation first: none fails, the run ends once with the host's outcome, and the turn's status follows from the
 * order they came in. And an agent paused with {@code stopRunning} while a run of it ends and starts the next turn:
 * the pause locked the agent's row and then the run's conversation, while the run's end held the conversation and
 * waited for the agent's row (the next run's foreign key to its agent). Agent rows are locked {@code FOR NO KEY
 * UPDATE} now, so a foreign key's check never waits on them. And the person's leaving while their run ends: the leaving
 * locked the person's principal {@code FOR UPDATE}, and then their conversations ({@code PrincipalLeft}), while the
 * run's end held the conversation and wrote it a second time, which checks its person's principal again (a foreign
 * key). A principal's row is locked {@code FOR NO KEY UPDATE} now, but for its deletion, so that check never waits on
 * it either. And a person's leaving, and an agent's deletion, while a transaction of their conversation holds it and
 * then records an event: the leaving and the deletion recorded theirs before their listeners ({@code PrincipalLeft},
 * {@code AgentDeleted}) locked the conversation, so they held the company's audit chain head while they waited for it.
 * A transaction's events are appended when it commits now ({@code JdbcAuditLog}), so they wait without it.
 * And a tool call's record (package R, §3.2): it takes the account (a credential the system refused) before the call's
 * journal row, and records {@code tool.called} last.
 */
@ExtendWith(OutputCaptureExtension.class)
class ConversationLockOrderTest extends ConversationTestBench {

    private static final int ROUNDS = 15;

    private static final byte[] NOTES = "# Notes\n\nHalf done.\n".getBytes(StandardCharsets.UTF_8);

    @Test
    void aRunsEndThePersonsCancelAndTheHostsUploadNeverDeadlock(CapturedOutput output) throws Exception {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Racer", "", List.<UUID[]>of(), "READ", List.of());
        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        ExecutorService threads = Executors.newFixedThreadPool(3);
        try (FakeRunner runner = FakeRunner.connect(port, host.token())) {
            runner.helloV2AndWelcome(List.of(), List.of(), RUNNER_VERSION, 4);
            await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
            for (int round = 0; round < ROUNDS; round++) {
                String id = start(bench.member(), bench, agentId, "Round " + round).get("id").asString();
                JsonNode assign = runner.expect("run.assign");
                UUID runId = UUID.fromString(assign.get("runId").asString());
                long epoch = assign.get("epoch").asLong();
                runner.accepted(runId, epoch);
                awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus")
                    .asString()));

                CyclicBarrier together = new CyclicBarrier(3);
                String fileName = "notes-" + round + ".md";
                Future<?> finished = threads.submit(() -> {
                    together.await();
                    runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "Done."));
                    return null;
                });
                Future<ApiResponse> cancel = threads.submit(() -> {
                    together.await();
                    return bench.member().post(bench.path("/conversations/" + id + "/cancel"), null);
                });
                Future<HttpResponse<String>> upload = threads.submit(() -> {
                    together.await();
                    return RunnerFileRequests.upload(port, host.token(), runId, epoch,
                            "&artifact=true&source=save_artifact", fileName, "text/markdown", NOTES);
                });
                finished.get(30, TimeUnit.SECONDS);
                ApiResponse cancelled = cancel.get(30, TimeUnit.SECONDS);
                HttpResponse<String> uploaded = upload.get(30, TimeUnit.SECONDS);

                // None of them failed: the cancel answered, and the upload was stored, or refused once the run ended.
                assertThat(cancelled.status()).as("round %d: %s", round, cancelled).isEqualTo(200);
                assertThat(uploaded.statusCode()).as("round %d: %s", round, uploaded.body()).isIn(200, 404);
                // The run ended once, with the host's outcome; the turn is SUCCEEDED, or CANCELLED by a cancel that
                // came after its end; nothing is active and nothing waits.
                JsonNode after = awaitConversation(bench.member(), bench, id, now -> !now.hasNonNull("activeRunId")
                        && !"RUNNING".equals(now.path("turnStatus").asString()));
                assertThat(after.get("turnStatus").asString()).as("round %d", round).isIn("SUCCEEDED", "CANCELLED");
                assertThat(after.get("waitingOn").asString()).isEqualTo("NONE");
                assertThat(after.get("turnCount").asInt()).isEqualTo(1);
                assertThat(lastTurn(after).get("status").asString()).isEqualTo("SUCCEEDED");
                assertThat(after.get("messages")).filteredOn(message -> "AGENT".equals(message.get("kind")
                    .asString())).hasSize(1);
                try (TenantSql sql = TenantSql.in(bench.id())) {
                    assertThat(sql.count("SELECT count(*) FROM audit_event WHERE company_id = ? AND action = "
                            + "'run.finished' AND target_id = ?", bench.id(), runId.toString()))
                        .as("round %d: run.finished audited once", round).isEqualTo(1);
                }
            }
            // The host's frames were all processed: an aborted transaction answers INTERNAL_ERROR.
            assertThat(runner.received()).noneMatch(frame -> "error".equals(frame.path("type").asString())
                    && "INTERNAL_ERROR".equals(frame.path("code").asString()));
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    @Test
    void anAgentsPauseAndARunsEndThatStartsTheNextTurnNeverDeadlock(CapturedOutput output) throws Exception {
        Bench bench = bench(true);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try (FakeRunner runner = companyHost(bench, 4)) {
            for (int round = 0; round < ROUNDS; round++) {
                // A fresh agent each round: a paused one stays paused.
                UUID agentId = agent(bench.member(), bench, "Paused", "", List.<UUID[]>of(), "READ", List.of());
                String id = start(bench.member(), bench, agentId, "Round " + round).get("id").asString();
                JsonNode assign = assignOf(runner, id);
                UUID runId = UUID.fromString(assign.get("runId").asString());
                long epoch = assign.get("epoch").asLong();
                runner.accepted(runId, epoch);
                awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus")
                    .asString()));
                // A message waits: the run's end starts the next turn, a new run of the agent.
                assertThat(bench.member().post(bench.path("/conversations/" + id + "/messages"), java.util.Map.of(
                        "body", "And then the tests.")).status()).isEqualTo(201);

                CyclicBarrier together = new CyclicBarrier(2);
                Future<?> finished = threads.submit(() -> {
                    together.await();
                    runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "Done."));
                    return null;
                });
                Future<ApiResponse> paused = threads.submit(() -> {
                    together.await();
                    return bench.member().post(bench.path("/agents/" + agentId + "/pause"),
                            java.util.Map.of("stopRunning", true));
                });
                finished.get(30, TimeUnit.SECONDS);
                ApiResponse pause = paused.get(30, TimeUnit.SECONDS);

                assertThat(pause.status()).as("round %d: %s", round, pause).isEqualTo(200);
                // The run ended once, with the host's outcome; the next turn waits for the agent or was cancelled.
                JsonNode after = awaitConversation(bench.member(), bench, id, now -> !runId.toString()
                    .equals(now.path("activeRunId").asString(null)) && !"RUNNING".equals(now.path("turnStatus")
                        .asString()));
                assertThat(after.get("turns").get(0).get("status").asString()).isEqualTo("SUCCEEDED");
                assertThat(after.get("turnCount").asInt()).as("round %d", round).isBetween(1, 2);
                try (TenantSql sql = TenantSql.in(bench.id())) {
                    assertThat(sql.count("SELECT count(*) FROM audit_event WHERE company_id = ? AND action = "
                            + "'run.finished' AND target_id = ?", bench.id(), runId.toString()))
                        .as("round %d: run.finished audited once", round).isEqualTo(1);
                }
            }
            assertThat(runner.received()).noneMatch(frame -> "error".equals(frame.path("type").asString())
                    && "INTERNAL_ERROR".equals(frame.path("code").asString()));
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    @Test
    void aRunsEndAndItsPersonsLeavingNeverDeadlock(CapturedOutput output) throws Exception {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Leaving", "", List.<UUID[]>of(), "READ", List.of());
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Before I go").get("id").asString();
            JsonNode assign = assignOf(runner, id);
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            try (TenantSql holder = TenantSql.in(bench.id())) {
                // The person's leaving locks their principal, and then waits at its first dependent, before its first
                // audit record: their model key, or the agent they own, whichever comes first, is held here (the agent
                // FOR NO KEY UPDATE, as the leaving takes it, so that no foreign key's check waits on it).
                holder.rows("SELECT principal_id FROM principal_model_key WHERE principal_id = ? FOR UPDATE",
                        bench.memberPrincipal());
                holder.rows("SELECT id FROM agent WHERE id = ? FOR NO KEY UPDATE", agentId);
                Future<ApiResponse> leaving = threads.submit(() -> bench.owner().delete(bench.path("/members/"
                        + bench.company().userId("MEMBER"))));
                await().atMost(WAIT).until(() -> waitingForLocks(bench.id()) == 1);

                // Meanwhile the run ends: its transaction holds the conversation and writes it a second time, which
                // checks the person's principal again.
                runner.send(finished(runId.toString(), epoch, "SUCCEEDED", "Done."));
                await().atMost(WAIT).until(() -> waitingForLocks(bench.id()) >= 2
                        || "SUCCEEDED".equals(runStatus(bench.id(), runId)));
                holder.commit();

                ApiResponse left = leaving.get(60, TimeUnit.SECONDS);
                assertThat(left.status()).as("%s", left).isEqualTo(204);
            }
            // The run ended once, with the host's outcome, and the leaving ended the conversation; the host's frame was
            // processed (an aborted transaction answers INTERNAL_ERROR).
            await().atMost(WAIT).until(() -> "SUCCEEDED".equals(runStatus(bench.id(), runId)));
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.count("SELECT count(*) FROM audit_event WHERE company_id = ? AND action = "
                        + "'run.finished' AND target_id = ?", bench.id(), runId.toString())).isEqualTo(1);
                assertThat(sql.count("SELECT count(*) FROM conversation WHERE id = ? AND state = 'ENDED'",
                        UUID.fromString(id))).isEqualTo(1);
            }
            assertThat(runner.received()).noneMatch(frame -> "error".equals(frame.path("type").asString())
                    && "INTERNAL_ERROR".equals(frame.path("code").asString()));
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    @Test
    void aPersonsLeavingAndAnAuditingTransactionOfTheirConversationNeverDeadlock(CapturedOutput output)
            throws Exception {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Left behind", "", List.<UUID[]>of(), "READ", List.of());
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (FakeRunner runner = companyHost(bench, 4)) {
            UUID id = idleConversation(bench, runner, agentId, "Before I go");
            try (TenantSql holder = TenantSql.in(bench.id())) {
                // A transaction of the person's conversation holds its row, as a message, a decision or a turn's end
                // does before it records anything.
                holder.rows("SELECT id FROM conversation WHERE id = ? FOR NO KEY UPDATE", id);
                Future<ApiResponse> leaving = threads.submit(() -> bench.owner().delete(bench.path("/members/"
                        + bench.company().userId("MEMBER"))));
                // The leaving's dependents and its principal.left are recorded, and its PrincipalLeft waits for the
                // conversation.
                await().atMost(WAIT).until(() -> waitingFor(bench.id(), "%FROM conversation%FOR NO KEY UPDATE%") == 1);

                // Then the conversation's transaction records its event: the chain's head. While an event locked the
                // head when it was recorded, the leaving held it here, and the two deadlocked (40P01).
                holder.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE", bench.id());
                holder.commit();

                ApiResponse left = leaving.get(60, TimeUnit.SECONDS);
                assertThat(left.status()).as("%s", left).isEqualTo(204);
            }
            assertThat(endedReason(bench.id(), id)).isEqualTo("PERSON_LEFT");
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    @Test
    void anAgentsDeletionAndAnAuditingTransactionOfItsConversationNeverDeadlock(CapturedOutput output)
            throws Exception {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Deleted", "", List.<UUID[]>of(), "READ", List.of());
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (FakeRunner runner = companyHost(bench, 4)) {
            UUID id = idleConversation(bench, runner, agentId, "Before you go");
            try (TenantSql holder = TenantSql.in(bench.id())) {
                holder.rows("SELECT id FROM conversation WHERE id = ? FOR NO KEY UPDATE", id);
                Future<ApiResponse> deleting = threads.submit(() -> bench.member().delete(bench.path("/agents/"
                        + agentId)));
                // The deletion's agent.deleted is recorded, and its AgentDeleted waits for the conversation.
                await().atMost(WAIT).until(() -> waitingFor(bench.id(), "%FROM conversation%FOR NO KEY UPDATE%") == 1);

                holder.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE", bench.id());
                holder.commit();

                ApiResponse deleted = deleting.get(60, TimeUnit.SECONDS);
                assertThat(deleted.status()).as("%s", deleted).isEqualTo(204);
            }
            assertThat(endedReason(bench.id(), id)).isEqualTo("AGENT_DELETED");
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    /**
     * A person's leaving and the dispatcher of their conversation (CONVENTIONS §10): the dispatcher locks the
     * conversation, then its run, then the hosts that may take it ({@code FOR NO KEY UPDATE}). The leaving removed the
     * person's personal runner hosts ({@code FOR UPDATE}, then their {@code DELETE}, which sets the conversations' and
     * runs' {@code subscription_host_id} and {@code runner_host_id} to null) before its {@code PrincipalLeft} locked
     * the conversations, so the two deadlocked ({@code 40P01}). The hosts go after the conversations now.
     */
    @Test
    void aPersonsLeavingAndTheDispatcherOfTheirConversationNeverDeadlock(CapturedOutput output) throws Exception {
        Bench bench = bench(true);
        UUID agentId = agent(bench.member(), bench, "Dispatched", "", List.<UUID[]>of(), "READ", List.of());
        RunnerHostRef personal = registerRunnerHost(bench.member(), bench.id(), unique("laptop"), "PERSONAL");
        ExecutorService threads = Executors.newSingleThreadExecutor();
        try (FakeRunner runner = companyHost(bench, 4)) {
            UUID id = idleConversation(bench, runner, agentId, "Before I go");
            try (TenantSql dispatcher = TenantSql.in(bench.id())) {
                // The dispatcher holds the person's conversation, as it does while it checks a queued run of it.
                dispatcher.rows("SELECT id FROM conversation WHERE id = ? FOR NO KEY UPDATE", id);
                Future<ApiResponse> leaving = threads.submit(() -> bench.owner().delete(bench.path("/members/"
                        + bench.company().userId("MEMBER"))));
                await().atMost(WAIT).until(() -> waitingFor(bench.id(), "%FROM conversation%FOR NO KEY UPDATE%") == 1);

                // Then it locks the hosts that may take the run, the person's own among them: the leaving has not
                // touched them yet, so this neither waits nor deadlocks.
                assertThat(dispatcher.column("SELECT id FROM runner_host WHERE id = ? FOR NO KEY UPDATE",
                        personal.id())).containsExactly(personal.id());
                dispatcher.commit();

                ApiResponse left = leaving.get(60, TimeUnit.SECONDS);
                assertThat(left.status()).as("%s", left).isEqualTo(204);
            }
            assertThat(endedReason(bench.id(), id)).isEqualTo("PERSON_LEFT");
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.count("SELECT count(*) FROM runner_host WHERE id = ?", personal.id())).isZero();
                assertThat(sql.column("SELECT details->>'cause' FROM audit_event WHERE company_id = ? AND action = "
                        + "'runner_host.removed' AND target_id = ?", bench.id(), personal.id().toString()))
                    .containsExactly("principal.left");
            }
        }
        finally {
            threads.shutdownNow();
        }
        assertThat(output.getAll()).doesNotContain("deadlock detected").doesNotContain("40P01");
    }

    /** A conversation of the member with the agent whose first turn ended: open, and nothing runs in it. */
    private UUID idleConversation(Bench bench, FakeRunner runner, UUID agentId, String message) {
        String id = start(bench.member(), bench, agentId, message).get("id").asString();
        JsonNode assign = assignOf(runner, id);
        runner.send(finished(assign.get("runId").asString(), assign.get("epoch").asLong(), "SUCCEEDED", "Ok."));
        awaitConversation(bench.member(), bench, id, now -> "SUCCEEDED".equals(now.path("turnStatus").asString())
                && !now.hasNonNull("activeRunId"));
        return UUID.fromString(id);
    }

    /** The backends of this database that wait for a lock in a statement like the pattern. */
    private static long waitingFor(UUID companyId, String statement) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock' AND query LIKE ?", statement);
        }
    }

    private static String endedReason(UUID companyId, UUID conversationId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return (String) sql.column("SELECT ended_reason FROM conversation WHERE id = ? AND state = 'ENDED'",
                    conversationId).getFirst();
        }
    }

    @Test
    void aToolCallsRecordTakesTheAccountBeforeItsJournalRowAndAuditsLast() throws Exception {
        Bench bench = bench(true);
        JiraStub.Instance jira = JiraStub.newInstance();
        UUID jiraId = createSystem(bench.admin(), bench.company(), unique("Jira"), "JIRA_DC", jira.baseUrl(), false);
        String handle = bench.admin().get(bench.path("/systems/" + jiraId)).json().get("handle").asString();
        ApiResponse created = bench.member().post(bench.path("/agents"), java.util.Map.of("name", unique("Locker"),
                "runtimes", List.of("CLAUDE_CODE"), "systems", List.of(java.util.Map.of("systemId", jiraId.toString(),
                        "tools", List.of(java.util.Map.of("name", "get", "mode", "ALLOW"))))));
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID agentId = UUID.fromString(created.json().get("id").asString());
        TestSettings.nameModel(bench.id(), agentId, bench.memberPrincipal());
        UUID accountId = connectToken(bench.member(), bench.path("/me/accounts/" + jiraId), jira, "test-lock-order",
                "lena");
        // Jira refuses the token on the call (401), after a moment: the account turns RECONNECT in the record.
        String issue = jira.url("/rest/api/2/issue/SHOP-77");
        JiraStub.server().stubFor(get(urlPathEqualTo(issue)).withHeader("Authorization", equalTo(
                "Bearer test-lock-order")).willReturn(JiraStub.json(401, java.util.Map.of("errorMessages",
                        List.of("Unauthorized"))).withFixedDelay(1000)));
        try (FakeRunner runner = companyHost(bench, 4)) {
            String id = start(bench.member(), bench, agentId, "Read SHOP-77").get("id").asString();
            JsonNode assign = runner.expect("run.assign");
            UUID runId = UUID.fromString(assign.get("runId").asString());
            long epoch = assign.get("epoch").asLong();
            runner.accepted(runId, epoch);
            awaitConversation(bench.member(), bench, id, now -> "RUNNING".equals(now.path("turnStatus").asString()));

            UUID requestId = UUID.randomUUID();
            runner.request(runId, epoch, requestId, "TOOL", java.util.Map.of("tool", handle + "_get", "arguments",
                    java.util.Map.of("key", "SHOP-77")));
            // The call reached Jira (its token revealed, the account's last use written); the account's row is held
            // now, as a refresh of it holds it, while Jira takes its moment.
            await().atMost(WAIT).until(() -> !JiraStub.server().findAll(getRequestedFor(urlPathEqualTo(issue)))
                .isEmpty());
            try (TenantSql refresh = TenantSql.in(bench.id())) {
                assertThat(refresh.column("SELECT id FROM account WHERE id = ? FOR UPDATE", accountId)).hasSize(1);
                await().atMost(WAIT).until(ConversationLockOrderTest::aTransactionWaitsForAnAccountRow);
                // The record waits for the account with neither the journal row nor the audit chain head.
                assertThat(refresh.column("SELECT state FROM tool_call WHERE run_id = ? AND request_id = ? "
                        + "FOR UPDATE NOWAIT", runId, requestId)).containsExactly("RUNNING");
                assertThat(refresh.column("SELECT last_seq FROM audit_chain_head WHERE company_id = ? "
                        + "FOR UPDATE NOWAIT", bench.id())).hasSize(1);
            }
            JsonNode ack = runner.expectAck(requestId);
            // The binding waits for its reconnect, and the call with it.
            assertThat(ack.get("status").asString()).as("%s", ack).isEqualTo("PENDING");
            try (TenantSql sql = TenantSql.in(bench.id())) {
                assertThat(sql.column("SELECT state FROM tool_call WHERE run_id = ? AND request_id = ?", runId,
                        requestId)).containsExactly("DONE");
                // The account's audit first, the call's last.
                List<Object> actions = sql.column("SELECT action FROM audit_event WHERE company_id = ? AND action IN "
                        + "('account.refused', 'tool.called') ORDER BY seq", bench.id());
                assertThat(actions).containsExactly("account.refused", "tool.called");
            }
        }
    }

    /** Whether a transaction of this test database waits for a lock on an account's row. */
    private static boolean aTransactionWaitsForAnAccountRow() throws java.sql.SQLException {
        try (java.sql.Connection superuser = TestDatabase.connectAsSuperuser(TestDatabase.jdbcUrl());
                java.sql.PreparedStatement select = superuser.prepareStatement("""
                        SELECT count(*) FROM pg_stat_activity
                         WHERE datname = current_database() AND wait_event_type = 'Lock'
                           AND query LIKE '%FROM account WHERE company_id%FOR UPDATE%'""");
                java.sql.ResultSet row = select.executeQuery()) {
            row.next();
            return row.getLong(1) > 0;
        }
    }

    /** The backends of this database that wait for a lock. */
    private static long waitingForLocks(UUID companyId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                    + "AND wait_event_type = 'Lock'");
        }
    }

    private static String runStatus(UUID companyId, UUID runId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return (String) sql.column("SELECT status FROM run WHERE id = ?", runId).getFirst();
        }
    }

    /** The conversation's next {@code run.assign}; another conversation's (a paused agent's late turn) is skipped. */
    private static JsonNode assignOf(FakeRunner runner, String conversationId) {
        while (true) {
            JsonNode assign = runner.expect("run.assign");
            if (conversationId.equals(assign.get("workItem").get("id").asString())) {
                return assign;
            }
        }
    }
}
