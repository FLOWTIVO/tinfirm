package com.flowtivo.tinfirm.trigger.internal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

/**
 * The 5-field cron of a SCHEDULE trigger (CONVENTIONS §15.15): the form, the reasons for a cron that cannot work, and
 * the next firing read in a time zone, on the days the clock changes too.
 */
class CronTest {

    private static final ZoneId PRAGUE = ZoneId.of("Europe/Prague");

    @Test
    void readsNumbersRangesStepsAndLists() {
        Cron cron = Cron.parse("  0-30/15,45 9-17/4 * * 1-5 ");
        assertThat(cron.expression()).isEqualTo("0-30/15,45 9-17/4 * * 1-5");
        assertThat(cron.minutes()).containsExactly(0, 15, 30, 45);
        assertThat(Cron.parse("*/20 * * * *").minutes()).containsExactly(0, 20, 40);
        assertThat(Cron.parse("7 * * * *").minutes()).containsExactly(7);
        // Hours 9, 13 and 17 on weekdays.
        assertThat(cron.next(Instant.parse("2026-10-03T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-10-05T09:00:00Z"));
        assertThat(cron.next(Instant.parse("2026-10-05T09:45:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-10-05T13:00:00Z"));
        assertThat(cron.next(Instant.parse("2026-10-05T17:45:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-10-06T09:00:00Z"));
        assertThat(Cron.parse("0 9 1 * *").consecutiveHours()).isFalse();
        assertThat(Cron.parse("0 9,10 1 * *").consecutiveHours()).isTrue();
        assertThat(Cron.parse("0 0,23 1 * *").consecutiveHours()).isTrue();
    }

    @Test
    void namesTheReasonOfACronThatCannotWork() {
        assertInvalid("", "The cron is empty");
        assertInvalid("0 9 1 *", "A cron has five fields, minute hour day-of-month month day-of-week; this one has 4");
        assertInvalid("0 9 1 * * 2026", "this one has 6");
        assertInvalid("60 * * * *", "The minute 60 is out of range; it is 0 to 59");
        assertInvalid("0 24 * * *", "The hour 24 is out of range; it is 0 to 23");
        assertInvalid("0 9 0 * *", "The day-of-month 0 is out of range; it is 1 to 31");
        assertInvalid("0 9 1 13 *", "The month 13 is out of range; it is 1 to 12");
        assertInvalid("0 9 * * 8", "The day-of-week 8 is out of range; it is 0 to 7");
        assertInvalid("0 9 1 JAN *", "The month field has JAN; use numbers, e.g. 1-12");
        assertInvalid("0 9 * * MON-FRI", "The day-of-week field has MON; use numbers, e.g. 1-5 for Monday to Friday");
        assertInvalid("30-10 * * * *", "The range 30-10 in the minute field goes backwards");
        assertInvalid("5/10 * * * *", "a step needs * or a range before it, e.g. */15 or 0-30/15; 5/10 has neither");
        assertInvalid("*/0 * * * *", "The step 0 in the minute field is out of range; it is 1 to 60");
        assertInvalid("0,,30 * * * *", "The minute field 0,,30 has an empty entry");
        assertInvalid("0 9 L * *", "The day-of-month field has L; use numbers");
        assertInvalid("0 9 ? * *", "The day-of-month field has the value ?, which is not a number");
        assertInvalid("0 9 1 * -1", "The day-of-week field has an empty value, which is not a number");
    }

    @Test
    void firesInTheTimeZoneAcrossWinterAndSummer() {
        Cron monthly = Cron.parse("0 9 1 * *");
        // 09:00 in Prague is 08:00 UTC in winter (CET) and 07:00 UTC in summer (CEST).
        assertThat(monthly.next(Instant.parse("2026-10-03T12:00:00Z"), PRAGUE))
            .contains(Instant.parse("2026-11-01T08:00:00Z"));
        assertThat(monthly.next(Instant.parse("2026-11-01T08:00:00Z"), PRAGUE))
            .contains(Instant.parse("2026-12-01T08:00:00Z"));
        assertThat(monthly.next(Instant.parse("2027-04-15T00:00:00Z"), PRAGUE))
            .contains(Instant.parse("2027-05-01T07:00:00Z"));
        // Strictly after: a firing at the given instant is not the next one.
        assertThat(monthly.next(Instant.parse("2026-11-01T07:59:59Z"), PRAGUE))
            .contains(Instant.parse("2026-11-01T08:00:00Z"));
        // The local date counts, not the UTC date: 00:30 on the 1st in Prague is still the 31st in UTC.
        assertThat(Cron.parse("30 0 1 * *").next(Instant.parse("2026-10-03T00:00:00Z"), PRAGUE))
            .contains(Instant.parse("2026-10-31T23:30:00Z"));
    }

    @Test
    void aLocalTimeThatTheChangeToSummerTimeSkipsFiresAfterIt() {
        // On 28 March 2027 Prague's clocks jump from 02:00 to 03:00: 02:30 does not exist that day.
        Cron cron = Cron.parse("30 2 * * *");
        Instant first = cron.next(Instant.parse("2027-03-27T12:00:00Z"), PRAGUE).orElseThrow();
        assertThat(first).isEqualTo(Instant.parse("2027-03-28T01:30:00Z"));
        assertThat(first.atZone(PRAGUE).toLocalTime()).hasToString("03:30");
        assertThat(cron.next(first, PRAGUE)).contains(Instant.parse("2027-03-29T00:30:00Z"));

        // Every 15 minutes keeps its pace across the jump: 01:45 CET, then 03:00 CEST.
        Cron quarterly = Cron.parse("*/15 * * * *");
        assertThat(quarterly.next(Instant.parse("2027-03-28T00:45:00Z"), PRAGUE))
            .contains(Instant.parse("2027-03-28T01:00:00Z"));
    }

    @Test
    void aLocalTimeThatTheChangeBackRepeatsFiresOnce() {
        // On 25 October 2026 Prague's clocks go back from 03:00 to 02:00: 02:30 happens twice.
        Cron cron = Cron.parse("30 2 * * *");
        Instant first = cron.next(Instant.parse("2026-10-24T12:00:00Z"), PRAGUE).orElseThrow();
        assertThat(first).isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        assertThat(cron.next(first, PRAGUE)).contains(Instant.parse("2026-10-26T01:30:00Z"));
    }

    @Test
    void aDayMatchesEitherDayFieldWhenBothNameDays() {
        // The 13th of the month or any Friday.
        Cron either = Cron.parse("0 9 13 * 5");
        assertThat(either.next(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-11-06T09:00:00Z"));
        assertThat(either.firesOn(LocalDate.parse("2026-12-13"))).isTrue();
        assertThat(either.firesOn(LocalDate.parse("2026-12-11"))).isTrue();
        assertThat(either.firesOn(LocalDate.parse("2026-12-12"))).isFalse();
        // A field that starts with * restricts nothing on its own: odd days that are Fridays.
        Cron both = Cron.parse("0 9 */2 * 5");
        assertThat(both.next(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-11-13T09:00:00Z"));
        // 0 and 7 are Sunday.
        assertThat(Cron.parse("0 0 * * 7").next(Instant.parse("2026-10-01T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-10-04T00:00:00Z"));
        assertThat(Cron.parse("0 0 * * 0").firesOn(LocalDate.parse("2026-10-04"))).isTrue();
        // A month restricts both: Fridays of December only.
        assertThat(Cron.parse("0 9 * 12 5").next(Instant.parse("2026-11-01T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2026-12-04T09:00:00Z"));
    }

    @Test
    void looksYearsAheadAndKnowsACronThatNeverFires() {
        assertThat(Cron.parse("0 0 29 2 *").next(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2028-02-29T00:00:00Z"));
        assertThat(Cron.parse("0 0 29 2 *").next(Instant.parse("2096-03-01T00:00:00Z"), ZoneOffset.UTC))
            .contains(Instant.parse("2104-02-29T00:00:00Z"));
        assertThat(Cron.parse("0 0 30 2 *").next(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC)).isEmpty();
        assertThat(Cron.parse("0 0 31 4,6,9,11 *").next(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC))
            .isEmpty();
    }

    private static void assertInvalid(String cron, String reason) {
        assertThatThrownBy(() -> Cron.parse(cron)).as(cron)
            .isInstanceOf(InvalidScheduleException.class)
            .hasMessageContaining(reason);
    }
}
