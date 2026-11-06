package com.flowtivo.tinfirm.diagnostics.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * What this replica captured since its last flush, in memory, one entry per fingerprint (ADR-0070, §3): at most
 * {@code maxPending} kinds of error, the newest sample of each, its count and its first and last time. A kind of error
 * beyond the limit is dropped until the next flush. Nothing is held while the mode is {@code OFF}.
 */
@Component
class ErrorCapture {

    /** The occurrences of one kind of error since the last flush, with the newest sample. */
    record Pending(CapturedError error, long count, Instant firstSeen, Instant lastSeen) {

        Pending plus(Pending other) {
            boolean newer = !other.lastSeen().isBefore(lastSeen);
            return new Pending(newer ? other.error() : error, count + other.count(),
                    firstSeen.isBefore(other.firstSeen()) ? firstSeen : other.firstSeen(),
                    newer ? other.lastSeen() : lastSeen);
        }
    }

    private final ConcurrentHashMap<String, Pending> pending = new ConcurrentHashMap<>();

    private final TimeSource time;

    private final int maxPending;

    private final boolean on;

    ErrorCapture(TimeSource time, ErrorReportProperties properties) {
        this.time = time;
        this.maxPending = properties.maxPending();
        this.on = properties.mode() != ErrorReportingMode.OFF;
    }

    /** Whether errors are captured: every mode but {@code OFF}. */
    boolean on() {
        return on;
    }

    void add(CapturedError error) {
        if (!on) {
            return;
        }
        Instant now = time.now();
        Pending occurrence = new Pending(error, 1, now, now);
        pending.compute(error.fingerprint(), (fingerprint, current) -> current != null ? current.plus(occurrence)
                : pending.size() < maxPending ? occurrence : null);
    }

    /** Takes everything held, for a flush. */
    List<Pending> drain() {
        List<Pending> drained = new ArrayList<>();
        for (String fingerprint : pending.keySet()) {
            Pending taken = pending.remove(fingerprint);
            if (taken != null) {
                drained.add(taken);
            }
        }
        return drained;
    }

    /** Puts back what a flush could not store, merged with what came meanwhile, within the limit. */
    void putBack(List<Pending> unstored) {
        for (Pending back : unstored) {
            pending.compute(back.error().fingerprint(), (fingerprint, current) -> current != null ? back.plus(current)
                    : pending.size() < maxPending ? back : null);
        }
    }
}
