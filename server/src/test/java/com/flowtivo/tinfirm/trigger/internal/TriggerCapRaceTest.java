package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.FakeSlack;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * A create counted a cap's live triggers and inserted its own without a lock, so parallel
 * creates one short of a cap all passed the count; and only a tracker's triggers counted against their system's cap.
 * Each create now counts under its cap's advisory lock (CONVENTIONS §10): a source system's 100 live triggers, whatever
 * their source, or the company's 50 schedules. The creates race released together while the company's audit chain
 * head is held, so that a create past its count waits at its commit with its trigger inserted: without the lock every
 * one of them would be created; with it exactly one is, and the others are {@code 409 TOO_MANY_TRIGGERS}.
 */
class TriggerCapRaceTest extends ChatTestBench {

    private static final int CREATES = 4;

    @Test
    void parallelCreatesOneShortOfAWebhookSystemsCapCreateOne() throws Exception {
        Bench bench = bench(true);
        ApiResponse system = bench.admin().post(bench.path("/systems"), Map.of("name", unique("Desk"), "kind",
                "WEBHOOK", "webhook", Map.of("scheme", "STANDARD", "mapping", Map.of("event", "/type"))));
        assertThat(system.status()).as("%s", system).isEqualTo(201);
        UUID systemId = UUID.fromString(system.json().get("id").asString());
        JsonNode agent = agent(bench);
        Supplier<Map<String, Object>> webhook = () -> {
            Map<String, Object> body = body(bench, agent, "WEBHOOK");
            body.put("systemId", systemId.toString());
            body.put("events", List.of(Map.of("event", "DELIVERED")));
            body.put("webhookConditions", List.of());
            body.put("acknowledgeOpenEvents", true);
            return body;
        };
        UUID first = created(bench.member().post(bench.path("/triggers"), withModel(webhook.get())));
        copies(bench, first, TriggerService.MAX_PER_SYSTEM - 2);

        race(bench, webhook);
        assertThat(rows(bench.id(), "SELECT id FROM trigger WHERE system_id = ? AND deleted_at IS NULL", systemId))
            .hasSize(TriggerService.MAX_PER_SYSTEM);
    }

    @Test
    void parallelSchedulesOneShortOfTheCompanysCapCreateOne() throws Exception {
        Bench bench = bench(true);
        JsonNode agent = agent(bench);
        Supplier<Map<String, Object>> schedule = () -> {
            Map<String, Object> body = body(bench, agent, "SCHEDULE");
            body.put("schedule", Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague"));
            return body;
        };
        UUID first = created(bench.member().post(bench.path("/triggers"), withModel(schedule.get())));
        copies(bench, first, TriggerService.MAX_SCHEDULES - 2);

        race(bench, schedule);
        assertThat(rows(bench.id(), "SELECT id FROM trigger WHERE source = 'SCHEDULE' AND deleted_at IS NULL"))
            .hasSize(TriggerService.MAX_SCHEDULES);
    }

    @Test
    void aChannelTriggerCountsAgainstItsChatSystemsCap() {
        Bench bench = bench(true);
        Slack slack = slack(bench);
        FakeSlack.Workspace workspace = slack.workspace();
        String support = workspace.channel("support", false);
        String desk = workspace.channel("desk", false);
        String author = workspace.member("Max Member");
        linkSlack(bench, slack, bench.member(), author);
        workspace.inChannels(author, support, desk);
        assertThat(bench.member().get(bench.path("/systems/" + slack.systemId() + "/chat/channels")).status())
            .isEqualTo(200);
        JsonNode agent = agent(bench);
        UUID first = created(bench.member().post(bench.path("/triggers"),
                withModel(channel(bench, slack, agent, support))));
        copies(bench, first, TriggerService.MAX_PER_SYSTEM - 1);

        // The system holds 100 live triggers: another channel's is refused as a tracker's would be.
        ApiResponse refused = bench.member().post(bench.path("/triggers"),
                withModel(channel(bench, slack, agent, desk)));
        assertThat(refused.status()).as("%s", refused).isEqualTo(409);
        assertThat(refused.problemCode()).isEqualTo("TOO_MANY_TRIGGERS");
        // A deleted one frees its place.
        assertThat(bench.member().delete(bench.path("/triggers/" + first)).status()).isEqualTo(204);
        created(bench.member().post(bench.path("/triggers"), withModel(channel(bench, slack, agent, desk))));
    }

    /**
     * The creates, released together while the company's audit chain head is held; once each waits, at the head with
     * its trigger inserted or at its cap's lock, the head goes. Exactly one is created.
     */
    private void race(Bench bench, Supplier<Map<String, Object>> body) throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(CREATES);
        try (TenantSql holder = TenantSql.in(bench.id())) {
            holder.rows("SELECT last_seq FROM audit_chain_head WHERE company_id = ? FOR UPDATE", bench.id());
            CountDownLatch start = new CountDownLatch(1);
            List<Future<ApiResponse>> creates = new ArrayList<>();
            for (int create = 0; create < CREATES; create++) {
                Map<String, Object> request = body.get();
                creates.add(threads.submit(() -> {
                    start.await();
                    return bench.member().post(bench.path("/triggers"), withModel(request));
                }));
            }
            start.countDown();
            await().atMost(WAIT).until(() -> waitingCreates(bench.id()) == CREATES);
            holder.commit();

            List<String> answers = new ArrayList<>();
            for (Future<ApiResponse> create : creates) {
                ApiResponse answer = create.get(60, TimeUnit.SECONDS);
                answers.add(answer.status() == 201 ? "201" : answer.status() + " " + answer.problemCode());
            }
            List<String> expected = new ArrayList<>(Collections.nCopies(CREATES - 1, "409 TOO_MANY_TRIGGERS"));
            expected.add("201");
            assertThat(answers).containsExactlyInAnyOrderElementsOf(expected);
        }
        finally {
            threads.shutdownNow();
        }
    }

    /**
     * The sessions that wait as a create does here: at a cap's advisory lock, or for a lock (the head) with a trigger
     * inserted.
     */
    private static long waitingCreates(UUID companyId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("""
                    SELECT count(*) FROM pg_stat_activity a
                     WHERE a.datname = current_database() AND a.wait_event_type = 'Lock'
                       AND (a.wait_event = 'advisory'
                            OR EXISTS (SELECT 1 FROM pg_locks l
                                        WHERE l.pid = a.pid AND l.granted AND l.mode = 'RowExclusiveLock'
                                          AND l.relation = 'trigger'::regclass))""");
        }
    }

    /** Copies of the trigger with names of their own, live, as many as asked: the cap's count, quickly. */
    private static void copies(Bench bench, UUID triggerId, int count) {
        inCompany(bench.id(), """
                INSERT INTO trigger
                SELECT copy.*
                  FROM trigger t
                 CROSS JOIN generate_series(1, ?) AS n
                 CROSS JOIN LATERAL jsonb_populate_record(t, jsonb_build_object('id', gen_random_uuid(),
                                                              'name', t.name || ' ' || n)) AS copy
                 WHERE t.id = ?""", count, triggerId);
    }

    private static UUID created(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    private JsonNode agent(Bench bench) {
        String id = agent(bench.member(), bench, "Capped", List.of())[0];
        return bench.member().get(bench.path("/agents/" + id)).json();
    }

    private static Map<String, Object> body(Bench bench, JsonNode agent, String source) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", unique("Capped"));
        body.put("source", source);
        body.put("agentId", agent.get("id").asString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Answer briefly.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        return body;
    }

    private static Map<String, Object> channel(Bench bench, Slack slack, JsonNode agent, String channelId) {
        Map<String, Object> body = body(bench, agent, "CHANNEL");
        body.put("systemId", slack.systemId().toString());
        body.put("channels", List.of(Map.of("channelId", channelId, "default", true)));
        body.put("acknowledgeOpenEvents", true);
        return body;
    }
}
