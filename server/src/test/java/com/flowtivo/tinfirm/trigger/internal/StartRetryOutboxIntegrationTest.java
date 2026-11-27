package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * A trigger's run lost because its runner host restarted before the agent got a prompt is started again by itself,
 * although the trigger's author did not choose automatic retries (ADR-0073, amended 2026-11-23): the issue hears
 * nothing of that loss, neither the failure's comment nor the on-failure actions; both are written once the retry is
 * lost after its agent ran. Against the Jira Data Center stub. TEST FIXTURE ONLY: every token is a test value.
 */
class StartRetryOutboxIntegrationTest extends OutboxTestSupport {

    @Test
    void aRunLostBeforeItsPromptIsStartedAgainAndTheIssueHearsOnlyOfTheLossThatEndsIt() {
        Jira jira = jira("Restarter");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of(action("TRANSITION", "In Progress"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "902"))));
        stubTransitions(jira);
        assertThat(bench.alice().get(bench.path("/triggers/" + triggerId + "/settings")).json().get("autoRetry")
            .asBoolean()).isFalse();

        RunnerHostRef host = registerRunnerHost(bench.owner(), bench.id(), unique("host"), "COMPANY");
        FakeRunner runner = connect(host);
        try {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn first = take(runner);
            awaitRunning(bench.id(), first);
            event(bench.id(), runner, first, 1, Map.of("phase", "preparing"));
            runner = restart(runner, host);

            // Started again by itself: its run waits for its time, and the issue hears nothing yet.
            UUID retry = await().atMost(WAIT).until(() -> scheduled(bench.id(), conversationId), id -> id != null);
            assertThat(retry).isNotEqualTo(first.runId());
            assertThat(column(outbox(bench.id(), conversationId), "cause")).doesNotContain("FAILURE", "ON_FAILURE");
            runner.expectNone("run.assign", Duration.ofSeconds(2));

            // Its time comes; the agent runs, and its host restarts again: now the loss is written.
            sql(bench.id(), "UPDATE run SET dispatch_after = now() - interval '1 second' WHERE id = ?", retry);
            Turn second = take(runner);
            assertThat(second.runId()).isEqualTo(retry);
            awaitRunning(bench.id(), second);
            event(bench.id(), runner, second, 1, Map.of("phase", "running"));
            runner = restart(runner, host);
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> column(now, "cause")
                .contains("ON_FAILURE"));
            assertThat(column(rows, "cause")).containsExactly("FAILURE", "ON_FAILURE");
            assertThat(column(rows, "cause_key")).allMatch(key -> key.contains(retry.toString()));
            runner.expectNone("run.assign", Duration.ofSeconds(1));
        }
        finally {
            runner.close();
        }
    }

    private FakeRunner connect(RunnerHostRef host) {
        FakeRunner runner = FakeRunner.connect(port, host.token());
        runner.helloV2AndWelcome(List.of(), List.of(), "0.2.0", 4);
        await().atMost(WAIT).until(() -> connections.isConfigured(host.id()));
        return runner;
    }

    /** The host's process died and started again: its hello lists none of its earlier runs. */
    private FakeRunner restart(FakeRunner runner, RunnerHostRef host) {
        runner.abort();
        await().atMost(WAIT).until(() -> !connections.isConnected(host.id()));
        return connect(host);
    }

    /** A STATUS event of the run, once the server stored it. */
    private static void event(UUID companyId, FakeRunner runner, Turn turn, long seq, Map<String, Object> payload) {
        runner.event(turn.runId(), turn.epoch(), seq, "STATUS", payload);
        await().atMost(WAIT).until(() -> {
            try (TenantSql sql = TenantSql.in(companyId)) {
                return !sql.column("SELECT seq FROM run_event WHERE run_id = ? AND seq = ?", turn.runId(), seq)
                    .isEmpty();
            }
        });
    }

    /** The conversation's queued run that waits {@code RETRY_SCHEDULED}, or null. */
    private static UUID scheduled(UUID companyId, UUID conversationId) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            List<Object> ids = sql.column("SELECT id FROM run WHERE conversation_id = ? AND status = 'QUEUED' AND "
                    + "queue_reason = 'RETRY_SCHEDULED' AND dispatch_after > now()", conversationId);
            return ids.isEmpty() ? null : (UUID) ids.getFirst();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static void sql(UUID companyId, String statement, Object... params) {
        try (TenantSql sql = TenantSql.in(companyId)) {
            assertThat(sql.update(statement, params)).isEqualTo(1);
            sql.commit();
        }
        catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
