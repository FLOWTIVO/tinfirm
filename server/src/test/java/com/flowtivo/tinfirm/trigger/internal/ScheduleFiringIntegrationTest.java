package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.support.ApiResponse;
import com.flowtivo.tinfirm.support.TenantSql;
import com.flowtivo.tinfirm.trigger.FiringOutcome;

import tools.jackson.databind.JsonNode;

/**
 * Schedules (the package 4 contract, §5.10, V15, open point 5): a due schedule fires under its row lock with a
 * conversation of its own in the {@code SCHEDULE} place and moves to its next firing in its zone; overlap {@code SKIP}
 * while the last firing's conversation is busy; catch-up {@code NONE} skips a firing missed longer ago than an hour,
 * {@code ONE} fires once for any number missed; the previous idle conversation ends {@code SUPERSEDED}; a paused agent
 * records {@code SKIPPED_AGENT_PAUSED}; run now fires with its overlap and does not move the next firing.
 */
class ScheduleFiringIntegrationTest extends PollingTestSupport {

    static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    Map<String, Object> schedule(Bench bench, String name, String catchUp) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", name);
        body.put("source", "SCHEDULE");
        body.put("schedule", Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague", "overlap", "SKIP", "catchUp",
                catchUp));
        body.put("agentId", bench.agentId().toString());
        body.put("agentVersion", bench.agentVersion());
        body.put("instruction", "Write the monthly report.");
        body.put("accounts", List.of());
        body.put("payerPrincipalId", bench.alicePrincipal().toString());
        body.put("teamGroupId", bench.team().toString());
        return body;
    }

    /** The schedule due {@code ago} before now. */
    static Instant dueAgo(Bench bench, UUID triggerId, Duration ago) throws Exception {
        Instant due = Instant.now().minus(ago).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        try (TenantSql sql = TenantSql.in(bench.id())) {
            sql.update("UPDATE trigger SET next_run_at = ? WHERE id = ?", due.atOffset(ZoneOffset.UTC), triggerId);
            sql.commit();
        }
        return due;
    }

    FiringOutcome fire(Bench bench, UUID triggerId) {
        return SystemContext.callInCompany(bench.id(), () -> schedules.fireOne(bench.id(), triggerId));
    }

    static Instant instant(Object value) {
        return value instanceof Timestamp timestamp ? timestamp.toInstant() : ((OffsetDateTime) value).toInstant();
    }

    @Test
    void aDueScheduleFiresInItsZoneSkipsWhileBusyAndCatchesUpAsItSays() throws Exception {
        Bench bench = bench();
        UUID trigger = create(bench.alice(), bench, schedule(bench, unique("Monthly"), "NONE"));

        // Due: it fires, a conversation of its own in the SCHEDULE place, and moves to 09:00 on the next 1st in Prague.
        Instant due = dueAgo(bench, trigger, Duration.ofMinutes(5));
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.STARTED);
        Map<String, Object> row = triggerRow(bench, trigger);
        assertThat(instant(row.get("last_run_at"))).isEqualTo(due);
        assertThat(row.get("last_outcome")).isEqualTo("STARTED");
        ZonedDateTime next = instant(row.get("next_run_at")).atZone(PRAGUE);
        assertThat(next.getDayOfMonth()).isEqualTo(1);
        assertThat(next.getHour()).isEqualTo(9);
        assertThat(next.toInstant()).isAfter(Instant.now());
        List<Map<String, Object>> conversations = conversations(bench, trigger);
        assertThat(conversations).hasSize(1);
        UUID first = (UUID) conversations.getFirst().get("id");
        JsonNode view = json(bench.bob().get(bench.path("/conversations/" + first)));
        assertThat(view.get("place").asString()).isEqualTo("SCHEDULE");
        // Not due again: nothing.
        assertThat(fire(bench, trigger)).isNull();

        // Overlap SKIP: its first turn is still queued, so the conversation is busy.
        dueAgo(bench, trigger, Duration.ofMinutes(1));
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.SKIPPED_OPEN);
        assertThat(conversations(bench, trigger)).hasSize(1);

        // Catch-up NONE: a firing missed longer ago than an hour is skipped, one record.
        json(bench.owner().post(bench.path("/conversations/" + first + "/cancel"), null));
        dueAgo(bench, trigger, Duration.ofHours(3));
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.SKIPPED_MISSED);
        assertThat(instant(triggerRow(bench, trigger).get("next_run_at"))).isAfter(Instant.now());

        // Catch-up ONE: one firing for any number missed; the previous, idle conversation ends SUPERSEDED.
        ApiResponse changed = bench.alice().patch(bench.path("/triggers/" + trigger), Map.of("schedule",
                Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague", "overlap", "SKIP", "catchUp", "ONE")));
        assertThat(changed.status()).as("%s", changed).isEqualTo(200);
        dueAgo(bench, trigger, Duration.ofDays(40));
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.STARTED);
        assertThat(fire(bench, trigger)).isNull();
        List<Map<String, Object>> after = conversations(bench, trigger);
        assertThat(after).hasSize(2);
        assertThat(after.getFirst().get("state")).isEqualTo("ENDED");
        assertThat(after.getFirst().get("ended_reason")).isEqualTo("SUPERSEDED");
        assertThat(after.getLast().get("state")).isEqualTo("OPEN");
        assertThat(firings(bench, trigger)).containsExactly("SCHEDULE:STARTED", "SCHEDULE:SKIPPED_OPEN",
                "SCHEDULE:SKIPPED_MISSED", "SCHEDULE:STARTED");
        assertThat(auditEvents(bench.owner(), bench.company(), "trigger.fired")).hasSize(4);
    }

    /**
     * Every change used to compute the next firing again from now, so a firing due but not yet
     * claimed by the tick was lost to a rename in between. The due time stays unless the cron or the zone changes.
     */
    @Test
    void aDueScheduleChangedBeforeTheTickStillFiresAndANewCronOrZoneMovesIt() throws Exception {
        Bench bench = bench();
        UUID trigger = create(bench.alice(), bench, schedule(bench, unique("Quarterly"), "NONE"));

        // Due, not yet claimed: a rename and another overlap keep its due time, and the tick fires it.
        Instant due = dueAgo(bench, trigger, Duration.ofSeconds(10));
        ApiResponse renamed = bench.alice().patch(bench.path("/triggers/" + trigger),
                Map.of("name", unique("Renamed")));
        assertThat(renamed.status()).as("%s", renamed).isEqualTo(200);
        ApiResponse overlap = bench.alice().patch(bench.path("/triggers/" + trigger), Map.of("schedule",
                Map.of("cron", "0 9 1 * *", "zone", "Europe/Prague", "overlap", "CREATE", "catchUp", "NONE")));
        assertThat(overlap.status()).as("%s", overlap).isEqualTo(200);
        assertThat(instant(triggerRow(bench, trigger).get("next_run_at"))).isEqualTo(due);
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.STARTED);
        assertThat(instant(triggerRow(bench, trigger).get("last_run_at"))).isEqualTo(due);

        // Another cron is another schedule: its next firing from now, the due one gone.
        dueAgo(bench, trigger, Duration.ofSeconds(10));
        ApiResponse cron = bench.alice().patch(bench.path("/triggers/" + trigger), Map.of("schedule",
                Map.of("cron", "0 9 2 * *", "zone", "Europe/Prague", "overlap", "CREATE", "catchUp", "NONE")));
        assertThat(cron.status()).as("%s", cron).isEqualTo(200);
        ZonedDateTime next = instant(triggerRow(bench, trigger).get("next_run_at")).atZone(PRAGUE);
        assertThat(next.toInstant()).isAfter(Instant.now());
        assertThat(next.getDayOfMonth()).isEqualTo(2);
        assertThat(next.getHour()).isEqualTo(9);
        assertThat(fire(bench, trigger)).isNull();
        // And another zone.
        dueAgo(bench, trigger, Duration.ofSeconds(10));
        ApiResponse zone = bench.alice().patch(bench.path("/triggers/" + trigger), Map.of("schedule",
                Map.of("cron", "0 9 2 * *", "zone", "Europe/London", "overlap", "CREATE", "catchUp", "NONE")));
        assertThat(zone.status()).as("%s", zone).isEqualTo(200);
        ZonedDateTime inLondon = instant(triggerRow(bench, trigger).get("next_run_at")).atZone(ZoneId.of(
                "Europe/London"));
        assertThat(inLondon.toInstant()).isAfter(Instant.now());
        assertThat(inLondon.getHour()).isEqualTo(9);
        assertThat(fire(bench, trigger)).isNull();
        assertThat(firings(bench, trigger)).containsExactly("SCHEDULE:STARTED");
    }

    @Test
    void runNowFiresWithItsOverlapAndAPausedAgentIsRecorded() throws Exception {
        Bench bench = bench();
        UUID trigger = create(bench.alice(), bench, schedule(bench, unique("Weekly"), "NONE"));
        Object nextBefore = triggerRow(bench, trigger).get("next_run_at");

        ApiResponse run = bench.bob().post(bench.path("/triggers/" + trigger + "/run"), Map.of());
        assertThat(run.status()).as("%s", run).isEqualTo(200);
        assertThat(run.json().get("outcome").asString()).isEqualTo("STARTED");
        assertThat(run.json().get("manualBy").asString()).isEqualTo(bench.bobUser().toString());
        ApiResponse busy = bench.bob().post(bench.path("/triggers/" + trigger + "/run"), Map.of());
        assertThat(busy.json().get("outcome").asString()).isEqualTo("SKIPPED_OPEN");
        assertThat(triggerRow(bench, trigger).get("next_run_at")).isEqualTo(nextBefore);

        // The agent paused: the schedule records it and moves on.
        json(bench.owner().post(bench.path("/agents/" + bench.agentId() + "/pause"), Map.of("stopRunning", false)));
        dueAgo(bench, trigger, Duration.ofMinutes(2));
        assertThat(fire(bench, trigger)).isEqualTo(FiringOutcome.SKIPPED_AGENT_PAUSED);
        assertThat(instant(triggerRow(bench, trigger).get("next_run_at"))).isAfter(Instant.now());
        // The tick finds due schedules across companies and takes each under its lock.
        json(bench.owner().post(bench.path("/agents/" + bench.agentId() + "/resume"), null));
        dueAgo(bench, trigger, Duration.ofMinutes(1));
        json(bench.owner().post(bench.path("/conversations/" + run.json().get("conversationId").asString()
                + "/cancel"), null));
        assertThat(schedules.fireDue()).isPositive();
        assertThat(firings(bench, trigger)).containsExactly("SCHEDULE:STARTED", "SCHEDULE:SKIPPED_OPEN",
                "SCHEDULE:SKIPPED_AGENT_PAUSED", "SCHEDULE:STARTED");
    }
}
