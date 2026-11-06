package com.flowtivo.tinfirm.chat.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * The per-address refusal limiter of Slack's events and interactivity endpoints (the security review of 2026-10-27,
 * L6), as the generic webhook's is: a client address with more than {@value #REFUSALS_PER_MINUTE} requests refused
 * (a signature that did not verify) for a system in the last minute is answered {@code 429} at once, before its body
 * is read, the system looked up or a signature computed. Keyed by the system id in its canonical form
 * ({@code UUID.toString()}), so that no spelling of the id escapes it. Slack's own deliveries are never refused, so
 * they never count. In memory, per replica: a sliding minute of instants per key.
 */
@Component
class InboundRefusals {

    static final int REFUSALS_PER_MINUTE = 60;

    private static final long MINUTE = 60_000;

    /** Keys kept before the stale ones are swept. */
    private static final int SWEEP_AT = 10_000;

    private final Map<String, Deque<Long>> refusals = new ConcurrentHashMap<>();

    /** Whether the address was refused more than its share for the system within the last minute. */
    boolean flooded(UUID systemId, String address, long now) {
        Deque<Long> times = refusals.get(systemId + "|" + address);
        if (times == null) {
            return false;
        }
        synchronized (times) {
            prune(times, now);
            return times.size() > REFUSALS_PER_MINUTE;
        }
    }

    /** A refusal of the address for the system. */
    void refused(UUID systemId, String address, long now) {
        sweep(now);
        Deque<Long> times = refusals.computeIfAbsent(systemId + "|" + address, key -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            if (times.size() <= REFUSALS_PER_MINUTE) {
                times.addLast(now);
            }
        }
    }

    private static void prune(Deque<Long> times, long now) {
        while (!times.isEmpty() && now - times.peekFirst() >= MINUTE) {
            times.pollFirst();
        }
    }

    private void sweep(long now) {
        if (refusals.size() < SWEEP_AT) {
            return;
        }
        refusals.entrySet().removeIf(entry -> {
            synchronized (entry.getValue()) {
                prune(entry.getValue(), now);
                return entry.getValue().isEmpty();
            }
        });
    }
}
