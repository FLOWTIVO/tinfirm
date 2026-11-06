package com.flowtivo.tinfirm.trigger.internal;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import com.flowtivo.tinfirm.ee.licence.EnterpriseLicence;
import com.flowtivo.tinfirm.support.FakeRunner;
import com.flowtivo.tinfirm.support.JiraStub;
import com.flowtivo.tinfirm.support.TenantSql;

/**
 * The write-back's place in the lock order (CONVENTIONS §10; the package 4 contract, T13): a trigger, its
 * conversations, their runs, bindings, decisions and outbox rows, and the company's audit chain head last. Each check
 * holds one lock in a transaction of the test's and finds the server's transaction waiting for it with what it holds
 * already:
 * <ul>
 * <li>a turn's end queues its write-back before it takes the audit chain's head, although its hooks recorded events
 * before ({@code decision.created} of a {@code BLOCKED} result's question): the head is taken when it commits;</li>
 * <li>a refused credential's record ({@code 401}) locks the row and the binding before the account it refuses, and
 * the head when it commits.</li>
 * </ul>
 * Under the former orders, the turn's end waited for the head at its hook's first record, before it queued anything,
 * and the refusal held the account when it waited for the binding.
 */
@ExtendWith(EnterpriseLicence.class)
class OutboxLockOrderTest extends OutboxTestSupport {

    @Test
    void aTurnsEndQueuesItsWriteBackBeforeItTakesTheAuditChainsHead() throws Exception {
        Jira jira = jira("Holder");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(JiraStub.json(201, Map.of("id", "1201"))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn turn = take(runner);
            try (TenantSql head = TenantSql.in(bench.id())) {
                // The company's audit chain head, held: whoever takes it waits for this transaction.
                assertThat(head.column("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE",
                        bench.id())).hasSize(1);
                Map<String, Object> blocked = new LinkedHashMap<>(finished(turn, "SUCCEEDED", "Blocked."));
                blocked.put("result", Map.of("outcome", "BLOCKED", "summary", "Blocked on the currency.",
                        "nextSteps", List.of(), "openQuestions", List.of("Which currency?")));
                runner.send(blocked);
                // The turn's end waits for the head with its question's comment queued already.
                await().atMost(WAIT).until(() -> waitingWith(head, "outbox"));
                head.commit();
            }
            List<Map<String, Object>> rows = awaitOutbox(bench.id(), conversationId, now -> allDelivered(now, 1));
            assertThat(rows.getFirst().get("cause")).isEqualTo("QUESTION");
            runner.assertFramesValid();
        }
        assertThat(bench.owner().get(bench.path("/audit-events/verification")).json().get("valid").asBoolean())
            .isTrue();
    }

    @Test
    void aRefusedCredentialLocksTheRowAndTheBindingBeforeTheAccount() throws Exception {
        Jira jira = jira("Refused");
        Bench bench = jira.bench();
        UUID triggerId = trigger(bench.alice(), bench, jira.system(), "SHOP", jira.account(), bench.alicePrincipal(),
                bench.team(), results(true, false, List.of(), List.of()));
        // The refusal comes late enough for the test to take the binding meanwhile.
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/comment")))
            .willReturn(aResponse().withStatus(401).withFixedDelay(3_000)));
        JiraStub.server().stubFor(post(urlEqualTo(jira.issue("/remotelink")))
            .willReturn(JiraStub.json(201, Map.of("id", 121))));

        try (FakeRunner runner = runner(bench)) {
            UUID conversationId = start(bench, triggerId, jira.system(), "10001", KEY, jira.stub().baseUrl()
                    + "/browse/" + KEY).conversation().id();
            Turn turn = take(runner);
            runner.send(completed(turn, "Ready.", List.of()));
            // The delivery took the binding's token and called Jira, which has not answered yet.
            await().atMost(WAIT).until(() -> !JiraStub.server().findAll(postRequestedFor(urlEqualTo(
                    jira.issue("/comment")))).isEmpty());
            UUID binding = (UUID) outbox(bench.id(), conversationId).getFirst().get("binding_id");
            try (TenantSql held = TenantSql.in(bench.id())) {
                assertThat(held.column("SELECT id FROM conversation_binding WHERE id = ? FOR UPDATE", binding))
                    .hasSize(1);
                // The refusal's record waits for the binding with the row's wait written and the account untouched.
                await().atMost(WAIT).until(() -> waitingWith(held, "outbox"));
                assertThat(waitingWith(held, "account")).isFalse();
                held.commit();
            }
            await().atMost(WAIT).until(() -> "ACCOUNT_BROKEN".equals(bench.alice().get(bench.path("/triggers/"
                    + triggerId)).json().path("pausedReason").asString()));
            awaitOutbox(bench.id(), conversationId, now -> now.stream().allMatch(row -> "HELD".equals(row.get(
                    "status"))));
            runner.assertFramesValid();
        }
        assertThat(auditEvents(bench.owner(), bench.company(), "account.refused")).hasSize(1);
    }

    /**
     * Whether a transaction waits for the one of {@code sql} while it holds a row it changed in the table (its
     * {@code RowExclusiveLock}).
     */
    private static boolean waitingWith(TenantSql sql, String table) throws SQLException {
        return sql.count("""
                SELECT count(*) FROM pg_locks waiting
                  JOIN pg_locks mine ON mine.locktype = 'transactionid' AND mine.transactionid = waiting.transactionid
                                    AND mine.granted AND mine.pid = pg_backend_pid()
                  JOIN pg_locks changed ON changed.pid = waiting.pid AND changed.locktype = 'relation'
                                       AND changed.relation = CAST(? AS regclass) AND changed.mode = 'RowExclusiveLock'
                                       AND changed.granted
                 WHERE waiting.locktype = 'transactionid' AND NOT waiting.granted""", table) > 0;
    }
}
