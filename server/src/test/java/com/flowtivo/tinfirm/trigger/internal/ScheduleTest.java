package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** A SCHEDULE trigger's schedule (CONVENTIONS §15.15): an IANA time zone, and at most one firing every 15 minutes. */
class ScheduleTest {

    private static final Instant NOW = Instant.parse("2026-10-03T10:00:00Z");

    @Test
    void takesIanaTimeZonesOnly() {
        assertThat(Schedule.parse("0 9 1 * *", " Europe/Prague ").zone().getId()).isEqualTo("Europe/Prague");
        assertThat(Schedule.parse("0 9 1 * *", "UTC").zone().getId()).isEqualTo("UTC");
        for (String zone : new String[] { "Mars/Olympus", "+02:00", "GMT+2", "europe/prague", "" }) {
            assertThatThrownBy(() -> Schedule.parse("0 9 1 * *", zone)).as(zone)
                .isInstanceOf(InvalidScheduleException.class)
                .hasMessageContaining("give an IANA time zone, e.g. Europe/Prague");
        }
        assertThatThrownBy(() -> Schedule.parse("0 9 1 * *", "Mars/Olympus"))
            .hasMessage("The time zone Mars/Olympus is unknown; give an IANA time zone, e.g. Europe/Prague");
    }

    @Test
    void refusesACronThatFiresMoreOftenThanEvery15Minutes() {
        assertTooOften("* * * * *", "The cron fires at minute 0 and at minute 1, 1 minute apart");
        assertTooOften("*/5 * * * *", "The cron fires at minute 0 and at minute 5, 5 minutes apart");
        assertTooOften("0,10 9 * * *", "at minute 0 and at minute 10, 10 minutes apart");
        // 09:50 and 10:00 are ten minutes apart.
        assertTooOften("0,50 9,10 * * *", "at minute 50 and at minute 0, 10 minutes apart");
        // 23:55 and 00:05 of the next day are too.
        assertTooOften("5,55 0,23 * * *", "at minute 55 and at minute 5, 10 minutes apart");

        for (String cron : new String[] { "*/15 * * * *", "0,15,30,45 * * * *", "0,50 9 * * *", "0 * * * *",
                "0 9 1 * *", "45 23 * * *" }) {
            assertThatCode(() -> Schedule.parse(cron, "Europe/Prague").requireMinInterval(NOW)).as(cron)
                .doesNotThrowAnyException();
        }
    }

    @Test
    void refusesACronThatNeverFires() {
        assertThatThrownBy(() -> Schedule.parse("0 0 30 2 *", "UTC").requireMinInterval(NOW))
            .isInstanceOf(InvalidScheduleException.class)
            .hasMessage("The cron never fires: no date matches it, e.g. 30 February");
    }

    @Test
    void listsTheNextFirings() {
        assertThat(Schedule.parse("0 9 1 * *", "Europe/Prague").next(NOW, 3)).containsExactly(
                Instant.parse("2026-11-01T08:00:00Z"), Instant.parse("2026-12-01T08:00:00Z"),
                Instant.parse("2027-01-01T08:00:00Z"));
        assertThat(Schedule.parse("0 0 30 2 *", "UTC").next(NOW, 3)).isEmpty();
    }

    private static void assertTooOften(String cron, String reason) {
        assertThatThrownBy(() -> Schedule.parse(cron, "Europe/Prague").requireMinInterval(NOW)).as(cron)
            .isInstanceOf(InvalidScheduleException.class)
            .hasMessageContaining(reason)
            .hasMessageEndingWith("a schedule fires at most every 15 minutes");
    }
}
