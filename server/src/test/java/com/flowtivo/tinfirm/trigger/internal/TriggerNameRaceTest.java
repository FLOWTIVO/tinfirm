package com.flowtivo.tinfirm.trigger.internal;

import static com.flowtivo.tinfirm.support.TestTriggers.withModel;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.chat.ChatTestBench;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TenantSql;

import tools.jackson.databind.JsonNode;

/**
 * A create and a change read the company's names ({@code nameTaken}) without locks; two
 * saves of one name at once both pass that check, and V011's {@code trigger_name_uq} refuses the second. It is
 * answered {@code 409 DUPLICATE_TRIGGER_NAME}, as the check would have, never {@code 500}.
 */
class TriggerNameRaceTest extends ChatTestBench {

    @Test
    void aCreateThatLosesTheRaceForANameIsAConflictNotAFailure() throws Exception {
        Bench bench = bench(true);
        JsonNode agent = agent(bench);
        UUID other = created(bench.member().post(bench.path("/triggers"),
                withModel(schedule(bench, agent, unique("Other")))));
        String name = unique("Monthly");

        ApiResponse answer = racing(bench, other, name, () -> bench.member().post(bench.path("/triggers"),
                withModel(schedule(bench, agent, name))));
        assertThat(answer.status()).as("%s", answer).isEqualTo(409);
        assertThat(answer.problemCode()).isEqualTo("DUPLICATE_TRIGGER_NAME");
        assertThat(rows(bench.id(), "SELECT id FROM trigger WHERE name = ? AND deleted_at IS NULL", name))
            .extracting(row -> row.get("id"))
            .containsExactly(other);
    }

    @Test
    void aRenameThatLosesTheRaceForANameIsAConflictNotAFailure() throws Exception {
        Bench bench = bench(true);
        JsonNode agent = agent(bench);
        UUID other = created(bench.member().post(bench.path("/triggers"),
                withModel(schedule(bench, agent, unique("Other")))));
        UUID mine = created(bench.member().post(bench.path("/triggers"),
                withModel(schedule(bench, agent, unique("Mine")))));
        String name = unique("Monthly");

        ApiResponse answer = racing(bench, other, name, () -> bench.member().patch(bench.path("/triggers/" + mine),
                Map.of("name", name)));
        assertThat(answer.status()).as("%s", answer).isEqualTo(409);
        assertThat(answer.problemCode()).isEqualTo("DUPLICATE_TRIGGER_NAME");
        assertThat(rows(bench.id(), "SELECT id FROM trigger WHERE name = ? AND deleted_at IS NULL", name))
            .extracting(row -> row.get("id"))
            .containsExactly(other);
    }

    /**
     * Another save gives the other trigger the name and has not committed when the request reads the names; it
     * commits once the request waits on it at the name's index.
     */
    private static ApiResponse racing(Bench bench, UUID other, String name, Callable<ApiResponse> request)
            throws Exception {
        ExecutorService thread = Executors.newSingleThreadExecutor();
        try (TenantSql save = TenantSql.in(bench.id())) {
            save.accepted("UPDATE trigger SET name = ? WHERE id = ?", name, other);
            Future<ApiResponse> answer = thread.submit(request);
            await().atMost(WAIT).until(() -> waitingAtTheIndex(bench.id()));
            save.commit();
            return answer.get(60, TimeUnit.SECONDS);
        }
        finally {
            thread.shutdownNow();
        }
    }

    /** A save of a trigger waits on another transaction: a unique index's entry it has not committed. */
    private static boolean waitingAtTheIndex(UUID companyId) throws java.sql.SQLException {
        try (TenantSql sql = TenantSql.in(companyId)) {
            return sql.count("""
                    SELECT count(*) FROM pg_stat_activity
                     WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'transactionid'
                       AND query ~ '(INSERT INTO|UPDATE)\\s+trigger\\s'""") > 0;
        }
    }

    private static UUID created(ApiResponse response) {
        assertThat(response.status()).as("%s", response).isEqualTo(201);
        return UUID.fromString(response.json().get("id").asString());
    }

    private JsonNode agent(Bench bench) {
        String id = agent(bench.member(), bench, "Named", List.of())[0];
        return bench.member().get(bench.path("/agents/" + id)).json();
    }

    private static Map<String, Object> schedule(Bench bench, JsonNode agent, String name) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("source", "SCHEDULE");
        body.put("schedule", Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague"));
        body.put("agentId", agent.get("id").asString());
        body.put("agentVersion", agent.get("version").asInt());
        body.put("instruction", "Answer briefly.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.memberPrincipal().toString());
        return body;
    }
}
