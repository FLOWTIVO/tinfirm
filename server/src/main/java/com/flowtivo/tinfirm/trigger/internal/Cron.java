package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * The cron of a SCHEDULE trigger in the 5-field form (the package 4 contract, §3.1 and §5.10; ADR-0032):
 * {@code minute hour day-of-month month day-of-week}.
 * <p>
 * Each field is {@code *}, a number, a range {@code a-b}, a step {@code *}{@code /n} or {@code a-b/n}, or a list of
 * those separated by {@code ,}. Minutes are 0-59, hours 0-23, days 1-31, months 1-12 and days of the week 0-7, where
 * 0 and 7 are Sunday. Names such as {@code MON} or {@code JAN}, and {@code ?}, {@code L}, {@code W} or {@code #}, are
 * not part of the form. As in the classic cron, when both day fields name days (neither starts with {@code *}), a
 * day matches either of them; otherwise it must match both.
 * <p>
 * The cron is read in a time zone ({@link #next}). A local time that a change to summer time skips fires at the same
 * wall-clock time after the change (02:30 becomes 03:30); a local time that the change back repeats fires once, at
 * its first occurrence.
 */
public final class Cron {

    /** The longest cron the API takes. */
    static final int MAX_LENGTH = 100;

    /** How far {@link #next} looks ahead: long enough for the 29th of February in every case. */
    private static final int HORIZON_DAYS = 9 * 366;

    private static final Pattern NUMBER = Pattern.compile("\\d{1,4}");

    private static final Pattern LETTERS = Pattern.compile("[A-Za-z]+");

    private record Field(String name, int min, int max) {
    }

    private static final Field MINUTE = new Field("minute", 0, 59);

    private static final Field HOUR = new Field("hour", 0, 23);

    private static final Field DAY = new Field("day-of-month", 1, 31);

    private static final Field MONTH = new Field("month", 1, 12);

    private static final Field WEEKDAY = new Field("day-of-week", 0, 7);

    private final String expression;

    private final BitSet minutes;

    private final BitSet hours;

    private final BitSet days;

    private final BitSet months;

    /** Days of the week, 0 (Sunday) to 6 (Saturday). */
    private final BitSet weekdays;

    /** Both day fields name days, so a day matches either of them. */
    private final boolean eitherDay;

    private Cron(String expression, BitSet minutes, BitSet hours, BitSet days, BitSet months, BitSet weekdays,
            boolean eitherDay) {
        this.expression = expression;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
        this.eitherDay = eitherDay;
    }

    /**
     * @throws InvalidScheduleException with the reason, written for the person who typed the cron
     */
    static Cron parse(String expression) {
        String trimmed = expression.strip();
        if (trimmed.isEmpty()) {
            throw new InvalidScheduleException("The cron is empty: give five fields, minute hour day-of-month month "
                    + "day-of-week, e.g. 0 9 1 * *");
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new InvalidScheduleException("The cron has more than " + MAX_LENGTH + " characters");
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            throw new InvalidScheduleException("A cron has five fields, minute hour day-of-month month day-of-week; "
                    + "this one has " + fields.length);
        }
        BitSet weekdays = field(fields[4], WEEKDAY);
        if (weekdays.get(7)) {
            weekdays.clear(7);
            weekdays.set(0);
        }
        boolean eitherDay = !fields[2].startsWith("*") && !fields[4].startsWith("*");
        return new Cron(trimmed, field(fields[0], MINUTE), field(fields[1], HOUR), field(fields[2], DAY),
                field(fields[3], MONTH), weekdays, eitherDay);
    }

    private static BitSet field(String text, Field field) {
        BitSet values = new BitSet();
        for (String item : text.split(",", -1)) {
            if (item.isEmpty()) {
                throw new InvalidScheduleException("The " + field.name() + " field " + text + " has an empty entry");
            }
            String range = item;
            int step = 1;
            int slash = item.indexOf('/');
            if (slash >= 0) {
                range = item.substring(0, slash);
                step = number(item.substring(slash + 1), field, "step");
                if (step < 1 || step > field.max() - field.min() + 1) {
                    throw new InvalidScheduleException("The step " + step + " in the " + field.name()
                            + " field is out of range; it is 1 to " + (field.max() - field.min() + 1));
                }
                if (!range.equals("*") && range.indexOf('-') < 0) {
                    throw new InvalidScheduleException("In the " + field.name() + " field, a step needs * or a "
                            + "range before it, e.g. */15 or 0-30/15; " + item + " has neither");
                }
            }
            int from;
            int to;
            if (range.equals("*")) {
                from = field.min();
                to = field.max();
            }
            else {
                int dash = range.indexOf('-');
                if (dash >= 0) {
                    from = value(range.substring(0, dash), field);
                    to = value(range.substring(dash + 1), field);
                    if (from > to) {
                        throw new InvalidScheduleException("The range " + range + " in the " + field.name()
                                + " field goes backwards; write the smaller number first");
                    }
                }
                else {
                    from = value(range, field);
                    to = from;
                }
            }
            for (int value = from; value <= to; value += step) {
                values.set(value);
            }
        }
        return values;
    }

    private static int value(String text, Field field) {
        int value = number(text, field, "value");
        if (value < field.min() || value > field.max()) {
            throw new InvalidScheduleException("The " + field.name() + " " + value + " is out of range; it is "
                    + field.min() + " to " + field.max());
        }
        return value;
    }

    private static int number(String text, Field field, String what) {
        if (NUMBER.matcher(text).matches()) {
            return Integer.parseInt(text);
        }
        if (LETTERS.matcher(text).matches()) {
            throw new InvalidScheduleException("The " + field.name() + " field has " + text + "; use numbers, e.g. "
                    + (field == WEEKDAY ? "1-5 for Monday to Friday (0 and 7 are Sunday)" : field.min() + "-"
                            + field.max()));
        }
        throw new InvalidScheduleException("The " + field.name() + " field has " + (text.isEmpty() ? "an empty "
                + what : "the " + what + " " + text) + ", which is not a number");
    }

    /** The cron as given, without surrounding whitespace. */
    String expression() {
        return expression;
    }

    /** The minutes of an hour at which the cron fires, in order. */
    List<Integer> minutes() {
        List<Integer> list = new ArrayList<>();
        minutes.stream().forEach(list::add);
        return list;
    }

    /** Whether the cron has two hours in a row, so that it fires across the end of an hour. */
    boolean consecutiveHours() {
        for (int hour = hours.nextSetBit(0); hour >= 0; hour = hours.nextSetBit(hour + 1)) {
            if (hours.get((hour + 1) % 24)) {
                return true;
            }
        }
        return false;
    }

    /** Whether the cron fires on this local date. */
    boolean firesOn(LocalDate date) {
        if (!months.get(date.getMonthValue())) {
            return false;
        }
        boolean day = days.get(date.getDayOfMonth());
        boolean weekday = weekdays.get(date.getDayOfWeek().getValue() % 7);
        return eitherDay ? day || weekday : day && weekday;
    }

    /**
     * The first firing strictly after {@code after}, with the cron read in {@code zone}; empty when it does not fire
     * in the next nine years (e.g. 30 February).
     */
    Optional<Instant> next(Instant after, ZoneId zone) {
        LocalDate date = after.atZone(zone).toLocalDate();
        for (int day = 0; day < HORIZON_DAYS; day++, date = date.plusDays(1)) {
            if (!firesOn(date)) {
                continue;
            }
            // Around a change of the clock the local times of a day are not in the order of their instants, so the
            // day's earliest firing after `after` wins, not the first local time.
            Instant first = null;
            for (int hour = hours.nextSetBit(0); hour >= 0; hour = hours.nextSetBit(hour + 1)) {
                for (int minute = minutes.nextSetBit(0); minute >= 0; minute = minutes.nextSetBit(minute + 1)) {
                    Instant at = ZonedDateTime.of(date, LocalTime.of(hour, minute), zone).toInstant();
                    if (at.isAfter(after) && (first == null || at.isBefore(first))) {
                        first = at;
                    }
                }
            }
            if (first != null) {
                return Optional.of(first);
            }
        }
        return Optional.empty();
    }

    @Override
    public String toString() {
        return expression;
    }
}
