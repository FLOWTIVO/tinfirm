package com.flowtivo.tinfirm.runner.internal;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

/**
 * A pool's schedule of ready sandboxes (ADR-0099; the Kubernetes runner analysis §6.4, D5 and D11): a count when no
 * window applies, and up to {@value #MAX_WINDOWS} weekly windows, each with its days, a start and an end in local time,
 * and its own count. The server evaluates it; the host gets numbers.
 * <ul>
 * <li>A window applies from its start to its end on each of its days; one whose end is before its start ends the next
 * day. Where windows overlap, the higher count applies; outside every window, the default does.</li>
 * <li>Times are local to the window's own zone, else the pool's: an IANA zone, with its summer time. A time that the
 * change to summer time skips moves forward by the gap, and one that the change back repeats takes its first
 * occurrence ({@link ZonedDateTime#of}), so a window over that night is an hour shorter or longer.</li>
 * </ul>
 */
final class PoolSchedule {

    static final int MAX_WINDOWS = 14;

    /** How far ahead {@link #evaluate} looks: the next change, and the week the page draws. */
    static final Duration HORIZON = Duration.ofDays(7);

    static final int MAX_ZONE_LENGTH = 64;

    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    /** {@code HH:mm} from 00:00 to 23:59, as the API's pattern says. */
    private static final Pattern TIME = Pattern.compile("([01][0-9]|2[0-3]):[0-5][0-9]");

    /** A pool's settings as an admin set them, before {@link #of} checks them. */
    record Settings(@Nullable String zone, int readyDefault, List<WindowSettings> windows) {

        Settings {
            windows = List.copyOf(windows);
        }
    }

    /** A window as an admin set it: days ({@code MON} … {@code SUN}), {@code HH:mm} times, a count, maybe a zone. */
    record WindowSettings(List<String> days, String from, String to, int ready, @Nullable String zone) {

        WindowSettings {
            days = List.copyOf(days);
        }
    }

    /** A checked window. */
    record Window(Set<DayOfWeek> days, LocalTime from, LocalTime to, int ready, ZoneId zone) {
    }

    /** From {@code at} on, the count is {@code readyTarget}. */
    record Change(Instant at, int readyTarget) {
    }

    /**
     * The count now, its next change within {@link #HORIZON} (null: none), and the week: the count now and every change
     * of the next 7 days.
     */
    record Evaluation(int readyTarget, @Nullable Change next, List<Change> week) {

        Evaluation {
            week = List.copyOf(week);
        }
    }

    /** Settings that cannot be a schedule, with the reason (the API's 400). */
    static final class InvalidSchedule extends IllegalArgumentException {

        private static final long serialVersionUID = 1L;

        InvalidSchedule(String message) {
            super(message);
        }
    }

    private final int readyDefault;

    private final List<Window> windows;

    private PoolSchedule(int readyDefault, List<Window> windows) {
        this.readyDefault = readyDefault;
        this.windows = List.copyOf(windows);
    }

    /** No pool settings: no sandbox waits (D5). */
    static PoolSchedule none() {
        return new PoolSchedule(0, List.of());
    }

    /**
     * Checks the settings for a pool of {@code poolSize} sandboxes: a known zone for every window, at most
     * {@value #MAX_WINDOWS} windows, each with days and a start other than its end, and no count above the pool size.
     *
     * @throws InvalidSchedule with the reason
     */
    static PoolSchedule of(Settings settings, int poolSize) {
        ZoneId poolZone = settings.zone() != null ? zone(settings.zone()) : null;
        if (settings.readyDefault() < 0 || settings.readyDefault() > poolSize) {
            throw new InvalidSchedule("readyDefault " + settings.readyDefault() + " is not between 0 and the pool size "
                    + poolSize);
        }
        if (settings.windows().size() > MAX_WINDOWS) {
            throw new InvalidSchedule("A pool has at most " + MAX_WINDOWS + " windows, not "
                    + settings.windows().size());
        }
        List<Window> windows = new ArrayList<>();
        for (int i = 0; i < settings.windows().size(); i++) {
            WindowSettings window = settings.windows().get(i);
            String which = "windows[" + i + "]";
            ZoneId zone = window.zone() != null ? zone(window.zone()) : poolZone;
            if (zone == null) {
                throw new InvalidSchedule(which + " has no time zone: give the pool's zone, or the window's");
            }
            if (window.days().isEmpty()) {
                throw new InvalidSchedule(which + " has no days");
            }
            Set<DayOfWeek> days = EnumSet.noneOf(DayOfWeek.class);
            for (String day : window.days()) {
                days.add(day(day, which));
            }
            LocalTime from = time(window.from(), which + ".from");
            LocalTime to = time(window.to(), which + ".to");
            if (from.equals(to)) {
                throw new InvalidSchedule(which + " starts when it ends");
            }
            if (window.ready() < 0 || window.ready() > poolSize) {
                throw new InvalidSchedule(which + ".ready " + window.ready() + " is not between 0 and the pool size "
                        + poolSize);
            }
            windows.add(new Window(days, from, to, window.ready(), zone));
        }
        return new PoolSchedule(settings.readyDefault(), windows);
    }

    /** An IANA zone such as {@code Europe/Prague} or {@code UTC}; offsets such as {@code +02:00} are not zones. */
    static ZoneId zone(String timeZone) {
        String id = timeZone.strip();
        if (id.isEmpty() || id.length() > MAX_ZONE_LENGTH || !ZONES.contains(id)) {
            throw new InvalidSchedule("The time zone " + (id.isEmpty() ? "is empty" : id + " is unknown")
                    + "; give an IANA time zone, e.g. Europe/Prague");
        }
        return ZoneId.of(id);
    }

    private static DayOfWeek day(String day, String which) {
        return switch (day) {
            case "MON" -> DayOfWeek.MONDAY;
            case "TUE" -> DayOfWeek.TUESDAY;
            case "WED" -> DayOfWeek.WEDNESDAY;
            case "THU" -> DayOfWeek.THURSDAY;
            case "FRI" -> DayOfWeek.FRIDAY;
            case "SAT" -> DayOfWeek.SATURDAY;
            case "SUN" -> DayOfWeek.SUNDAY;
            default -> throw new InvalidSchedule(which + " has the day " + day + "; days are MON to SUN");
        };
    }

    private static LocalTime time(@Nullable String value, String which) {
        if (value == null || !TIME.matcher(value).matches()) {
            throw new InvalidSchedule(which + " is not a time HH:mm, 00:00 to 23:59");
        }
        return LocalTime.of(Integer.parseInt(value, 0, 2, 10), Integer.parseInt(value, 3, 5, 10));
    }

    /** The count at {@code at}: the highest of the windows that apply then, else the default. */
    int countAt(Instant at) {
        int count = -1;
        for (Window window : windows) {
            LocalDate date = at.atZone(window.zone()).toLocalDate();
            // A window that ends the next day may have started the day before.
            for (LocalDate day : List.of(date.minusDays(1), date)) {
                if (window.days().contains(day.getDayOfWeek())) {
                    Instant start = start(window, day);
                    Instant end = end(window, day);
                    if (!at.isBefore(start) && at.isBefore(end)) {
                        count = Math.max(count, window.ready());
                    }
                }
            }
        }
        return count >= 0 ? count : readyDefault;
    }

    /** The count now, its next change, and the week ahead. */
    Evaluation evaluate(Instant now) {
        Instant horizon = now.plus(HORIZON);
        TreeSet<Instant> boundaries = new TreeSet<>();
        for (Window window : windows) {
            LocalDate first = now.atZone(window.zone()).toLocalDate().minusDays(1);
            for (int i = 0; i <= HORIZON.toDays() + 2; i++) {
                LocalDate day = first.plusDays(i);
                if (window.days().contains(day.getDayOfWeek())) {
                    for (Instant boundary : List.of(start(window, day), end(window, day))) {
                        if (boundary.isAfter(now) && !boundary.isAfter(horizon)) {
                            boundaries.add(boundary);
                        }
                    }
                }
            }
        }
        int current = countAt(now);
        List<Change> week = new ArrayList<>();
        week.add(new Change(now, current));
        int previous = current;
        for (Instant boundary : boundaries) {
            int count = countAt(boundary);
            if (count != previous) {
                week.add(new Change(boundary, count));
                previous = count;
            }
        }
        Change next = week.size() > 1 ? week.get(1) : null;
        return new Evaluation(current, next, week);
    }

    private static Instant start(Window window, LocalDate day) {
        return ZonedDateTime.of(day, window.from(), window.zone()).toInstant();
    }

    private static Instant end(Window window, LocalDate day) {
        LocalDate endDay = window.to().isAfter(window.from()) ? day : day.plusDays(1);
        return ZonedDateTime.of(endDay, window.to(), window.zone()).toInstant();
    }
}
