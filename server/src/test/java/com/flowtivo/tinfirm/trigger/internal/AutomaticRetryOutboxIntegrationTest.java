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

import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.support.TestSettings;

import tools.jackson.databind.JsonNode;

/**
 * A trigger's automatic retries (ADR-0073): its author chooses them in the trigger's settings, audited as
 * {@code trigger.settings_changed}; a failed turn that is retried by itself writes nothing to the issue, neither its
 * failure's comment nor its on-failure actions, which are written once a retry fails for a reason that does not pass.
 * Against the Jira Data Center stub. TEST FIXTURE ONLY: every token is a test value.
 */
class AutomaticRetryOutboxIntegrationTest extends OutboxTestSupport {

    @Test
    void aFailureRetriedByItselfIsWrittenToTheIssueOnlyOnceTheRetriesStop() {
        Jira jira = jira("Retrier");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of(action("TRANSITION", "In Progress"))));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "901"))));
        stubTransitions(jira);

        String settings = bench.path("/triggers/" + triggerId + "/settings");
        assertThat(bench.alice().get(settings).json().get("autoRetry").asBoolean()).isFalse();
        ApiResponse chosen = bench.alice().put(settings, Map.of("model", TestSettings.MODEL, "autoRetry", true));
        assertThat(chosen.status()).as("%s", chosen).isEqualTo(200);
        assertThat(chosen.json().get("autoRetry").asBoolean()).isTrue();
        JsonNode changed = auditEvents(bench.owner(), bench.company(), "trigger.settings_changed").getFirst();
        assertThat(changed.get("details").get("changed")).extracting(JsonNode::asString).contains("autoRetry");

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn first = take(runner);
            Map<String, Object> unavailable = new java.util.LinkedHashMap<>(finished(first, "FAILED", "It stopped."));
            unavailable.put("failureClass", "SERVER_LIMITED");
            unavailable.put("failureMessage", "Prompt: 503 no available server");
            runner.send(unavailable);

            // Retried by itself: its run waits for its time, and the issue hears nothing yet.
            UUID retry = await().atMost(WAIT).until(() -> scheduled(bench.id(), conversationId), id -> id != null);
            assertThat(retry).isNotEqualTo(first.runId());
            assertThat(column(outbox(bench.id(), conversationId), "cause")).doesNotContain("FAILURE", "ON_FAILURE");
            runner.expectNone("run.assign", Duration.ofSeconds(2));

            // Its time comes; the retry fails for a reason that does not pass: now the failure is written.
            sql(bench.id(), "UPDATE run SET dispatch_after = now() - interval '1 second' WHERE id = ?", retry);
            Turn second = take(runner);
            assertThat(second.runId()).isEqualTo(retry);
            Map<String, Object> crashed = new java.util.LinkedHashMap<>(finished(second, "FAILED", "It crashed."));
            crashed.put("failureClass", "INFRA");
            crashed.put("failureMessage", "The sandbox stopped.");
            runner.send(crashed);
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> column(now, "cause")
                .contains("ON_FAILURE"));
            assertThat(column(rows, "cause")).containsExactly("FAILURE", "ON_FAILURE");
            assertThat(column(rows, "cause_key")).allMatch(key -> key.contains(retry.toString()));
            runner.assertFramesValid();
        }
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
