package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;

import org.jspecify.annotations.Nullable;

/**
 * Where the next pass of a poll starts (the package 4 contract, §3.9 and §5.4): the lower bound {@code since}, the
 * subjects at that bound already handled ({@code offsetKeys}, whose number is the listing's offset) and Jira Cloud's
 * cursor for that offset.
 * <p>
 * The bound follows the system's own clock: the newest change a pass handled, minus an overlap for changes that the
 * system lists late, never later than the pass's cutoff (its start minus the settle time: a subject changed more
 * recently is left for the next pass). It never moves back. A pass of one listing that stopped at its limit continues
 * from the last subject it handled; when that would not move the bound by the precision of the listing's bound (a
 * minute in Jira), it keeps the bound and continues at an offset, so a bulk change never stalls the polling. A pass of
 * several listings moves the bound to the earliest point every listing reached ({@link #next}). A listing that failed
 * holds the bound, so that its changes are read later.
 */
record PollWindow(Instant since, List<String> offsetKeys, @Nullable String cursor) {

    /** The most keys an offset keeps ({@code trigger_poll_offset_ck}). */
    static final int MAX_OFFSET = 500;

    PollWindow {
        offsetKeys = List.copyOf(offsetKeys);
    }

    /** The first pass of a poll: from when its triggers were armed, minus the overlap. */
    static PollWindow first(Instant from, Duration overlap) {
        return new PollWindow(from.minus(overlap), List.of(), null);
    }

    /** The listing's offset: the subjects at the bound already handled. */
    int offset() {
        return offsetKeys.size();
    }

    /**
     * What a pass saw of one listing.
     *
     * @param newest      the newest change it handled, or null when it handled none
     * @param cutAt       when the limit (or a failure after some progress) cut the listing, the change of the last
     *                    entry it read; else null
     * @param failed      the listing failed before it read anything: the bound holds for it
     * @param keysRead    the keys of the entries it read, in order
     * @param cursorAfter Jira Cloud's cursor after the last entry it read
     */
    record Seen(@Nullable Instant newest, @Nullable Instant cutAt, boolean failed, List<String> keysRead,
            @Nullable String cursorAfter) {

        Seen {
            keysRead = List.copyOf(keysRead);
        }

        static Seen failure() {
            return new Seen(null, null, true, List.of(), null);
        }
    }

    /**
     * The window after a pass.
     *
     * @param cutoff    the pass's start minus the settle time: the bound never passes it
     * @param precision the instant floored to the precision of the listing's bound
     */
    static PollWindow next(PollWindow window, List<Seen> seen, Instant cutoff, Duration overlap,
            UnaryOperator<Instant> precision) {
        Instant since = window.since();
        if (seen.size() == 1) {
            Seen only = seen.getFirst();
            if (only.failed()) {
                return window;
            }
            if (only.cutAt() != null) {
                Instant next = earliest(only.cutAt().minus(overlap), cutoff);
                if (precision.apply(next).isAfter(precision.apply(since))) {
                    return new PollWindow(next, List.of(), null);
                }
                Set<String> keys = new LinkedHashSet<>(window.offsetKeys());
                keys.addAll(only.keysRead());
                if (keys.size() > MAX_OFFSET) {
                    // More subjects changed at one bound than an offset keeps: move on by the bound's precision.
                    return new PollWindow(precision.apply(since).plus(precisionStep(precision, since)), List.of(),
                            null);
                }
                return new PollWindow(since, new ArrayList<>(keys), only.cursorAfter());
            }
            if (only.newest() == null) {
                return new PollWindow(since, List.of(), null);
            }
            Instant next = earliest(only.newest().minus(overlap), cutoff);
            return new PollWindow(next.isAfter(since) ? next : since, List.of(), null);
        }
        Instant bound = null;
        Instant progress = null;
        for (Seen listing : seen) {
            Instant candidate;
            if (listing.failed()) {
                candidate = since;
            }
            else if (listing.cutAt() != null) {
                candidate = listing.cutAt().minus(overlap);
                Instant onward = candidate.isAfter(since) ? candidate : listing.cutAt();
                progress = progress == null || onward.isBefore(progress) ? onward : progress;
            }
            else {
                candidate = (listing.newest() != null ? listing.newest() : cutoff).minus(overlap);
            }
            candidate = earliest(candidate, cutoff);
            bound = bound == null || candidate.isBefore(bound) ? candidate : bound;
        }
        if (bound != null && bound.isAfter(since)) {
            return new PollWindow(bound, List.of(), null);
        }
        if (progress != null && progress.isAfter(since) && seen.stream().noneMatch(Seen::failed)) {
            return new PollWindow(earliest(progress, cutoff), List.of(), null);
        }
        return new PollWindow(since, List.of(), null);
    }

    private static Instant earliest(Instant a, Instant b) {
        return a.isBefore(b) ? a : b;
    }

    /** The step of the precision: a minute when it floors to minutes, else a second. */
    private static Duration precisionStep(UnaryOperator<Instant> precision, Instant at) {
        Instant floored = precision.apply(at.plusSeconds(1));
        return precision.apply(floored.plusSeconds(1)).equals(floored) ? Duration.ofMinutes(1) : Duration.ofSeconds(1);
    }
}
