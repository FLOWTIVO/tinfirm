package com.flowtivo.tinfirm.trigger.internal;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * A webhook system's rates in this replica (the package 5 contract, §5.12, open point 11), in the order the inbox
 * applies them so that nobody without the secret spends the sender's rate (C1):
 * <ol>
 * <li>a client address with more than {@value #REFUSALS_PER_MINUTE} refused deliveries for the system in the last
 * minute is refused at once ({@code 429}), before anything else: the flood of a stranger who learned the URL, counted
 * apart from the sender's deliveries;</li>
 * <li>only a verified delivery counts toward the system's deliveries a minute ({@code 429} with {@code Retry-After}).</li>
 * </ol>
 * Also the audit's pace: {@code webhook.refused} once a minute per system and reason, with the count since. In memory,
 * per replica, as package 2's {@code LoginThrottle} is: a sliding minute of instants per key.
 */
@Component
class WebhookLimiter {

    static final int REFUSALS_PER_MINUTE = 60;

    private static final long MINUTE = 60_000;

    /** Keys kept before the stale ones are swept. */
    private static final int SWEEP_AT = 10_000;

    private final Map<String, Deque<Long>> refusals = new ConcurrentHashMap<>();

    private final Map<UUID, Deque<Long>> deliveries = new ConcurrentHashMap<>();

    private final Map<String, long[]> audits = new ConcurrentHashMap<>();

    /**
     * Whether the client address was refused more than its share for the system within the last minute: keyed by the
     * system id as {@code UUID.toString()} writes it (the inbox parses the path's first, the security review of
     * 2026-10-27, L6), so that it is answered before the system is looked up and no other spelling escapes it.
     */
    boolean flooded(String systemId, String address, long now) {
        Deque<Long> times = refusals.get(systemId + "|" + address);
        if (times == null) {
            return false;
        }
        synchronized (times) {
            prune(times, now);
            return times.size() > REFUSALS_PER_MINUTE;
        }
    }

    /** A refusal of the client address for the system. */
    void refused(String systemId, String address, long now) {
        sweep(now);
        Deque<Long> times = refusals.computeIfAbsent(systemId + "|" + address, key -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            if (times.size() <= REFUSALS_PER_MINUTE) {
                times.addLast(now);
            }
        }
    }

    /**
     * A verified delivery to the system, counted when admitted: empty when it is, else the seconds until it would be.
     */
    OptionalInt admit(UUID systemId, int perMinute, long now) {
        Deque<Long> times = deliveries.computeIfAbsent(systemId, key -> new ArrayDeque<>());
        synchronized (times) {
            prune(times, now);
            if (times.size() < perMinute) {
                times.addLast(now);
                return OptionalInt.empty();
            }
            long oldest = times.peekFirst() != null ? times.peekFirst() : now;
            return OptionalInt.of((int) Math.max(1, (oldest + MINUTE - now + 999) / 1000));
        }
    }

    /**
     * A refusal to audit, once a minute per system and reason: the count to audit now (this one and those since the
     * last audit), or empty while the minute runs.
     */
    OptionalInt toAudit(UUID systemId, String reason, long now) {
        long[] state = audits.computeIfAbsent(systemId + "|" + reason, key -> new long[] { Long.MIN_VALUE, 0 });
        synchronized (state) {
            state[1]++;
            if (state[0] != Long.MIN_VALUE && now - state[0] < MINUTE) {
                return OptionalInt.empty();
            }
            int count = (int) state[1];
            state[0] = now;
            state[1] = 0;
            return OptionalInt.of(count);
        }
    }

    /** Forgets everything: tests start each from a clean slate. */
    void clear() {
        refusals.clear();
        deliveries.clear();
        audits.clear();
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
