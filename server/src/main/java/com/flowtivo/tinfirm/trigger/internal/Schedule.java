package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * A SCHEDULE trigger's schedule: its cron read in its IANA time zone (the package 4 contract, §3.1 and §5.10;
 * ADR-0032). A schedule fires at most every 15 minutes.
 */
public record Schedule(Cron cron, ZoneId zone) {

    /** The shortest time between two firings. */
    static final Duration MIN_INTERVAL = Duration.ofMinutes(15);

    /** The longest time zone the API takes. */
    static final int MAX_ZONE_LENGTH = 64;

    /** How many of the next firings {@link #requireMinInterval} compares. */
    private static final int CHECKED_FIRINGS = 50;

    private static final DateTimeFormatter LOCAL = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private static final Set<String> ZONES = ZoneId.getAvailableZoneIds();

    /**
     * @throws InvalidScheduleException for an invalid cron or zone, with the reason
     */
    static Schedule parse(String cron, String timeZone) {
        return new Schedule(Cron.parse(cron), zone(timeZone));
    }

    /** An IANA zone such as {@code Europe/Prague} or {@code UTC}; offsets such as {@code +02:00} are not zones. */
    static ZoneId zone(String timeZone) {
        String id = timeZone.strip();
        if (id.isEmpty() || id.length() > MAX_ZONE_LENGTH || !ZONES.contains(id)) {
            throw new InvalidScheduleException("The time zone " + (id.isEmpty() ? "is empty" : id + " is unknown")
                    + "; give an IANA time zone, e.g. Europe/Prague");
        }
        return ZoneId.of(id);
    }

    /** The first firing strictly after {@code after}; empty for a cron that never fires. */
    Optional<Instant> next(Instant after) {
        return cron.next(after, zone);
    }

    /** The next {@code count} firings after {@code after}, fewer for a cron that stops firing. */
    List<Instant> next(Instant after, int count) {
        List<Instant> firings = new ArrayList<>();
        Instant cursor = after;
        while (firings.size() < count) {
            Optional<Instant> next = next(cursor);
            if (next.isEmpty()) {
                break;
            }
            firings.add(next.get());
            cursor = next.get();
        }
        return firings;
    }

    /**
     * Refuses a schedule that never fires, or fires more often than every 15 minutes: two minutes of an hour that are
     * closer, or two of the next firings after {@code from} that are.
     *
     * @throws InvalidScheduleException with the reason
     */
    void requireMinInterval(Instant from) {
        List<Integer> minutes = cron.minutes();
        for (int i = 1; i < minutes.size(); i++) {
            requireGap(minutes.get(i - 1), minutes.get(i), minutes.get(i) - minutes.get(i - 1));
        }
        if (minutes.size() > 1 && cron.consecutiveHours()) {
            requireGap(minutes.getLast(), minutes.getFirst(), 60 - minutes.getLast() + minutes.getFirst());
        }
        List<Instant> firings = next(from, CHECKED_FIRINGS);
        if (firings.isEmpty()) {
            throw new InvalidScheduleException("The cron never fires: no date matches it, e.g. 30 February");
        }
        for (int i = 1; i < firings.size(); i++) {
            Duration gap = Duration.between(firings.get(i - 1), firings.get(i));
            if (gap.compareTo(MIN_INTERVAL) < 0) {
                throw new InvalidScheduleException("The cron fires at " + local(firings.get(i - 1)) + " and at "
                        + local(firings.get(i)) + " (" + zone.getId() + "), " + gap.toMinutes() + " minutes apart; a "
                        + "schedule fires at most every 15 minutes");
            }
        }
    }

    private static void requireGap(int from, int to, int minutes) {
        if (minutes < MIN_INTERVAL.toMinutes()) {
            throw new InvalidScheduleException("The cron fires at minute " + from + " and at minute " + to + ", "
                    + minutes + (minutes == 1 ? " minute" : " minutes") + " apart; a schedule fires at most every "
                    + "15 minutes");
        }
    }

    private String local(Instant instant) {
        return LOCAL.format(instant.atZone(zone));
    }
}
