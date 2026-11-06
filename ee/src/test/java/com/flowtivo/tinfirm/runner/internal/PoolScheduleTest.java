package com.flowtivo.tinfirm.runner.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The pool's schedule (ADR-0099; the Kubernetes runner analysis, §6.4): windows in a time zone, with its summer time,
 * the higher count where windows overlap, overnight windows, and the week's change points.
 */
class PoolScheduleTest {

    private static final List<String> WORKING_DAYS = List.of("MON", "TUE", "WED", "THU", "FRI");

    private static PoolSchedule.WindowSettings window(List<String> days, String from, String to, int ready) {
        return new PoolSchedule.WindowSettings(days, from, to, ready, null);
    }

    private static PoolSchedule schedule(String zone, int readyDefault, PoolSchedule.WindowSettings... windows) {
        return PoolSchedule.of(new PoolSchedule.Settings(zone, readyDefault, List.of(windows)), 8);
    }

    private static Instant at(String instant) {
        return Instant.parse(instant);
    }

    @Test
    void workingDaysHaveTheirCountAndOtherTimesTheDefault() {
        // The example of the analysis: Monday to Friday 08:00 to 18:00 two, else one.
        PoolSchedule pool = schedule("Europe/Prague", 1, window(WORKING_DAYS, "08:00", "18:00", 2));
        // Monday 19 October 2026, Prague in summer time (UTC+2).
        assertThat(pool.countAt(at("2026-10-19T05:59:00Z"))).isEqualTo(1);
        assertThat(pool.countAt(at("2026-10-19T06:00:00Z"))).isEqualTo(2);
        assertThat(pool.countAt(at("2026-10-19T15:59:00Z"))).isEqualTo(2);
        assertThat(pool.countAt(at("2026-10-19T16:00:00Z"))).isEqualTo(1);
        // Saturday.
        assertThat(pool.countAt(at("2026-10-17T10:00:00Z"))).isEqualTo(1);

        PoolSchedule.Evaluation monday = pool.evaluate(at("2026-10-19T05:00:00Z"));
        assertThat(monday.readyTarget()).isEqualTo(1);
        assertThat(monday.next()).isEqualTo(new PoolSchedule.Change(at("2026-10-19T06:00:00Z"), 2));
        // Now, then two changes on each of the five working days of the next 7 days.
        assertThat(monday.week()).hasSize(11);
        assertThat(monday.week().getFirst()).isEqualTo(new PoolSchedule.Change(at("2026-10-19T05:00:00Z"), 1));
        assertThat(monday.week().get(2)).isEqualTo(new PoolSchedule.Change(at("2026-10-19T16:00:00Z"), 1));
    }

    @Test
    void aWindowKeepsItsLocalTimeAcrossTheChangeOfSummerTime() {
        PoolSchedule pool = schedule("Europe/Prague", 0, window(WORKING_DAYS, "08:00", "18:00", 2));
        // Friday in summer time (UTC+2), Monday after the change back on Sunday 25 October (UTC+1).
        assertThat(pool.countAt(at("2026-10-23T06:00:00Z"))).isEqualTo(2);
        assertThat(pool.countAt(at("2026-10-26T06:30:00Z"))).isZero();
        assertThat(pool.countAt(at("2026-10-26T07:00:00Z"))).isEqualTo(2);
        assertThat(pool.evaluate(at("2026-10-24T12:00:00Z")).next())
            .isEqualTo(new PoolSchedule.Change(at("2026-10-26T07:00:00Z"), 2));
    }

    @Test
    void aSkippedTimeMovesForwardAndARepeatedTimeTakesItsFirstOccurrence() {
        // Sunday 29 March 2026: Prague skips 02:00 to 03:00 (at 01:00 UTC). A window from 02:30 starts at 03:30.
        PoolSchedule spring = schedule("Europe/Prague", 0, window(List.of("SUN"), "02:30", "04:00", 3));
        assertThat(spring.countAt(at("2026-03-29T01:15:00Z"))).isZero();
        assertThat(spring.countAt(at("2026-03-29T01:30:00Z"))).isEqualTo(3);
        assertThat(spring.countAt(at("2026-03-29T02:00:00Z"))).isZero();
        // Sunday 25 October 2026: Prague repeats 02:00 to 03:00. A window from 02:30 starts at the first one (UTC+2).
        PoolSchedule autumn = schedule("Europe/Prague", 0, window(List.of("SUN"), "02:30", "05:00", 1));
        assertThat(autumn.countAt(at("2026-10-25T00:29:00Z"))).isZero();
        assertThat(autumn.countAt(at("2026-10-25T00:30:00Z"))).isEqualTo(1);
    }

    @Test
    void anOvernightWindowEndsTheNextDayAndIsAnHourLongerOrShorterOverTheChange() {
        // Saturday 22:00 to Sunday 06:00 over the change back in Prague: nine hours, not eight.
        PoolSchedule prague = schedule("Europe/Prague", 0, window(List.of("SAT"), "22:00", "06:00", 2));
        assertThat(prague.countAt(at("2026-10-24T19:59:00Z"))).isZero();
        assertThat(prague.countAt(at("2026-10-24T20:00:00Z"))).isEqualTo(2);
        assertThat(prague.countAt(at("2026-10-25T04:59:00Z"))).isEqualTo(2);
        assertThat(prague.countAt(at("2026-10-25T05:00:00Z"))).isZero();
        // New York changes back on 1 November: Saturday 20:00 to Sunday 06:00 lasts eleven hours.
        PoolSchedule newYork = schedule("America/New_York", 0, window(List.of("SAT"), "20:00", "06:00", 1));
        assertThat(newYork.countAt(at("2026-11-01T00:00:00Z"))).isEqualTo(1);
        assertThat(newYork.countAt(at("2026-11-01T10:59:00Z"))).isEqualTo(1);
        assertThat(newYork.countAt(at("2026-11-01T11:00:00Z"))).isZero();
        // And forward on 8 March: Saturday 20:00 to Sunday 06:00 lasts nine.
        assertThat(newYork.countAt(at("2026-03-08T00:59:00Z"))).isZero();
        assertThat(newYork.countAt(at("2026-03-08T01:00:00Z"))).isEqualTo(1);
        assertThat(newYork.countAt(at("2026-03-08T09:59:00Z"))).isEqualTo(1);
        assertThat(newYork.countAt(at("2026-03-08T10:00:00Z"))).isZero();
    }

    @Test
    void windowsInTwoZonesOverlapAndTheHigherCountApplies() {
        PoolSchedule pool = PoolSchedule.of(new PoolSchedule.Settings("Europe/Prague", 0, List.of(
                window(WORKING_DAYS, "09:00", "17:00", 1),
                new PoolSchedule.WindowSettings(WORKING_DAYS, "09:00", "17:00", 3, "America/New_York"))), 8);
        // Monday 19 October: Prague 07:00 to 15:00 UTC, New York 13:00 to 21:00 UTC.
        assertThat(pool.countAt(at("2026-10-19T08:00:00Z"))).isEqualTo(1);
        assertThat(pool.countAt(at("2026-10-19T14:00:00Z"))).isEqualTo(3);
        assertThat(pool.countAt(at("2026-10-19T16:00:00Z"))).isEqualTo(3);
        assertThat(pool.countAt(at("2026-10-19T21:00:00Z"))).isZero();
        // A window may lower the count below the default: the window's count applies while it does.
        PoolSchedule nights = schedule("UTC", 2, window(List.of("SAT", "SUN"), "00:00", "23:59", 0));
        assertThat(nights.countAt(at("2026-10-17T12:00:00Z"))).isZero();
        assertThat(nights.countAt(at("2026-10-19T12:00:00Z"))).isEqualTo(2);
    }

    @Test
    void withoutWindowsTheCountNeverChanges() {
        PoolSchedule.Evaluation evaluation = schedule(null, 1).evaluate(at("2026-10-19T05:00:00Z"));
        assertThat(evaluation.readyTarget()).isEqualTo(1);
        assertThat(evaluation.next()).isNull();
        assertThat(evaluation.week()).hasSize(1);
        assertThat(PoolSchedule.none().evaluate(Instant.EPOCH).readyTarget()).isZero();
    }

    @Test
    void settingsThatCannotBeAScheduleAreRefusedWithTheReason() {
        record Bad(PoolSchedule.Settings settings, String reason) {
        }
        List<Bad> bad = List.of(
                new Bad(new PoolSchedule.Settings("Mars/Olympus", 0, List.of()), "Mars/Olympus is unknown"),
                new Bad(new PoolSchedule.Settings("+02:00", 0, List.of()), "+02:00 is unknown"),
                new Bad(new PoolSchedule.Settings(null, 0, List.of(window(WORKING_DAYS, "08:00", "18:00", 1))),
                        "has no time zone"),
                new Bad(new PoolSchedule.Settings("UTC", 0, List.of(window(List.of(), "08:00", "18:00", 1))),
                        "has no days"),
                new Bad(new PoolSchedule.Settings("UTC", 0, List.of(window(List.of("MO"), "08:00", "18:00", 1))),
                        "the day MO"),
                new Bad(new PoolSchedule.Settings("UTC", 0, List.of(window(WORKING_DAYS, "08:00", "08:00", 1))),
                        "starts when it ends"),
                new Bad(new PoolSchedule.Settings("UTC", 0, List.of(window(WORKING_DAYS, "8:00", "18:00", 1))),
                        "windows[0].from is not a time"),
                new Bad(new PoolSchedule.Settings("UTC", 0, List.of(window(WORKING_DAYS, "08:00", "24:00", 1))),
                        "windows[0].to is not a time"),
                new Bad(new PoolSchedule.Settings("UTC", 0, List.of(window(WORKING_DAYS, "08:00", "18:00", 9))),
                        "the pool size 8"),
                new Bad(new PoolSchedule.Settings("UTC", 9, List.of()), "readyDefault 9"),
                new Bad(new PoolSchedule.Settings("UTC", 0,
                        Collections.nCopies(15, window(WORKING_DAYS, "08:00", "18:00", 1))), "at most 14 windows"));
        for (Bad each : bad) {
            assertThatThrownBy(() -> PoolSchedule.of(each.settings(), 8))
                .isInstanceOf(PoolSchedule.InvalidSchedule.class)
                .hasMessageContaining(each.reason());
        }
    }
}
