package com.flowtivo.tinfirm.run.internal;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.runner.ConnectedHost;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.runner.RunnerProperties;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import io.github.bucket4j.TimeMeter;
import tools.jackson.databind.JsonNode;

/**
 * The server's own bounds on what a runner host sends about its runs (the security review of 2026-10-27, M4; threat
 * model 5.12), checked before any transaction, so that a host that floods costs no database connection:
 * <ul>
 * <li>a token bucket per host (the machine) and per company on {@code run.request}, and per run, per host and per
 * company on {@code run.event}: {@code tinfirm.runner.*-per-second}, each holding
 * {@value RunnerProperties#BURST_SECONDS} seconds' worth, refilled greedily;</li>
 * <li>at most {@code tinfirm.runner.tool-calls-in-flight} {@code TOOL} requests of a run waiting for their answer;</li>
 * <li>a {@code run.event} payload of at most {@value RunnerMessages#MAX_EVENT_PAYLOAD_BYTES} bytes, and a text chunk
 * of at most {@value RunnerMessages#MAX_TEXT_CHUNK_BYTES} bytes, in UTF-8 (the schema's sizes, which a host keeps).</li>
 * </ul>
 * In memory and per replica, as the MCP endpoint's limit is: a host connects to one replica at a time, and one that
 * reconnects elsewhere gets fresh buckets there. The per-run byte budget, which must survive that, is the run row's
 * ({@link RunnerSessionHandler}).
 */
@Component
class RunnerFloodControl {

    /** The most hosts, companies or runs tracked at once; the least recently used bucket is dropped beyond it. */
    static final long MAX_TRACKED = 100_000;

    private static final Duration SECOND = Duration.ofSeconds(1);

    private final RunnerProperties properties;

    private final TimeMeter clock;

    private final Cache<UUID, Bucket> hostRequests;

    private final Cache<UUID, Bucket> companyRequests;

    private final Cache<UUID, Bucket> runEvents;

    private final Cache<UUID, Bucket> hostEvents;

    private final Cache<UUID, Bucket> companyEvents;

    private final ConcurrentMap<UUID, Integer> toolCalls = new ConcurrentHashMap<>();

    @Autowired
    RunnerFloodControl(RunnerProperties properties) {
        this(properties, TimeMeter.SYSTEM_NANOTIME);
    }

    RunnerFloodControl(RunnerProperties properties, TimeMeter clock) {
        this.properties = properties;
        this.clock = clock;
        // A bucket untouched for its burst is full again: dropping it then loses nothing.
        Duration idle = Duration.ofSeconds(RunnerProperties.BURST_SECONDS);
        this.hostRequests = cache(idle);
        this.companyRequests = cache(idle);
        this.runEvents = cache(idle);
        this.hostEvents = cache(idle);
        this.companyEvents = cache(idle);
    }

    /** Takes one {@code run.request} of the host's and of its company's buckets; false when either is empty. */
    boolean admitRequest(ConnectedHost host) {
        return take(hostRequests, host.machineId(), properties.requestsPerSecond())
                && take(companyRequests, host.company(), properties.companyRequestsPerSecond());
    }

    /**
     * Takes one {@code run.event} of the run's, the host's and the company's buckets, in that order, so that a run
     * that floods empties its own bucket before its host's; false when one is empty.
     */
    boolean admitEvent(ConnectedHost host, UUID runId) {
        return take(runEvents, runId, properties.eventsPerSecond())
                && take(hostEvents, host.machineId(), properties.hostEventsPerSecond())
                && take(companyEvents, host.company(), properties.companyEventsPerSecond());
    }

    /**
     * Counts a {@code TOOL} request of the run as waiting for its answer, unless
     * {@code tinfirm.runner.tool-calls-in-flight} already are; every true is followed by {@link #toolCallEnded}.
     */
    boolean toolCallStarted(UUID runId) {
        int most = properties.toolCallsInFlight();
        boolean[] admitted = { false };
        toolCalls.compute(runId, (id, count) -> {
            int current = count != null ? count : 0;
            if (current >= most) {
                return count;
            }
            admitted[0] = true;
            return current + 1;
        });
        return admitted[0];
    }

    /** A {@code TOOL} request of the run was answered (or will not be). */
    void toolCallEnded(UUID runId) {
        toolCalls.computeIfPresent(runId, (id, count) -> count <= 1 ? null : count - 1);
    }

    /** The {@code TOOL} requests of the run waiting for their answer on this replica. */
    int toolCallsInFlight(UUID runId) {
        return toolCalls.getOrDefault(runId, 0);
    }

    /**
     * Why the event is too large to store, for the host: null when it is within the schema's sizes.
     *
     * @param payloadBytes the payload's size as stored, UTF-8 JSON ({@link #utf8Length})
     */
    static @Nullable String oversized(RunnerMessages.EventType type, JsonNode payload, long payloadBytes) {
        if (payloadBytes > RunnerMessages.MAX_EVENT_PAYLOAD_BYTES) {
            return "An event's payload is at most " + RunnerMessages.MAX_EVENT_PAYLOAD_BYTES + " bytes; this one has "
                    + payloadBytes + ". It was not stored.";
        }
        if (type == RunnerMessages.EventType.AGENT_MESSAGE || type == RunnerMessages.EventType.AGENT_THOUGHT) {
            JsonNode text = payload.get("text");
            long textBytes = text != null && text.isString() ? utf8Length(text.asString()) : 0;
            if (textBytes > RunnerMessages.MAX_TEXT_CHUNK_BYTES) {
                return "A text chunk is at most " + RunnerMessages.MAX_TEXT_CHUNK_BYTES + " bytes; this one has "
                        + textBytes + ". It was not stored: split long texts.";
            }
        }
        return null;
    }

    /** The length of the text in UTF-8, without encoding it. */
    static long utf8Length(CharSequence text) {
        long bytes = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x80) {
                bytes++;
            }
            else if (c < 0x800) {
                bytes += 2;
            }
            else if (Character.isHighSurrogate(c) && i + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(i + 1))) {
                bytes += 4;
                i++;
            }
            else {
                bytes += 3;
            }
        }
        return bytes;
    }

    private boolean take(Cache<UUID, Bucket> buckets, UUID key, int perSecond) {
        if (perSecond <= 0) {
            return true;
        }
        return buckets.get(key, ignored -> bucket(perSecond)).tryConsume(1);
    }

    private Bucket bucket(int perSecond) {
        return Bucket.builder()
            .withCustomTimePrecision(clock)
            .addLimit(limit -> limit.capacity((long) perSecond * RunnerProperties.BURST_SECONDS)
                .refillGreedy(perSecond, SECOND))
            .build();
    }

    private static Cache<UUID, Bucket> cache(Duration idle) {
        return Caffeine.newBuilder().maximumSize(MAX_TRACKED).expireAfterAccess(idle).build();
    }
}
