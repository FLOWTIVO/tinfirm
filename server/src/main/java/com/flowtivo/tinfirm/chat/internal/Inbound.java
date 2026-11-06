package com.flowtivo.tinfirm.chat.internal;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * An event as a platform sent it, verified and normalized (the package 5 contract, §3.7): what {@code chat_event}
 * stores. The payload holds what handling needs (the platform, user, workspace, channel, thread, text, files, action),
 * and is cleared once handled.
 *
 * @param kind      {@code MESSAGE} (a direct message), {@code MENTION} (in a channel), {@code ACTION} (a button),
 *                  {@code LIFECYCLE}
 * @param eventId   unique per system (C3): Slack's {@code event_id}; a Slack press {@code {user}:{action_ts}}; on Teams
 *                  {@code {conversation.id}:{activity id}}
 * @param sender    the chat user who sent it; null for a lifecycle event without one
 * @param threadKey {@code {channelId}:{threadId}}: a thread's events are handled in {@code platformTs} order
 * @param status    {@code RECEIVED}, or {@code IGNORED} with its reason for what is never handled (a bot's message, an
 *                  edit, an unmentioned channel message)
 * @param workspace the workspace or tenant the event names, for C2; null when it names none
 */
record Inbound(String kind, String eventId, @Nullable String sender, String threadKey, BigDecimal platformTs,
        int retryNum, String status, @Nullable String reason, Map<String, Object> payload,
        @Nullable String workspace) {

    static final String MESSAGE = "MESSAGE";

    static final String MENTION = "MENTION";

    static final String ACTION = "ACTION";

    static final String LIFECYCLE = "LIFECYCLE";

    static final String RECEIVED = "RECEIVED";

    static final String IGNORED = "IGNORED";

    static final String REFUSED = "REFUSED";

    Inbound ignored(String why) {
        return new Inbound(kind, eventId, sender, threadKey, platformTs, retryNum, IGNORED, why, payload, workspace);
    }

    Inbound refused(String why) {
        return new Inbound(kind, eventId, sender, threadKey, platformTs, retryNum, REFUSED, why, payload, workspace);
    }

    /** The event with one more field in its payload. */
    Inbound with(String field, Object value) {
        Map<String, Object> more = new java.util.LinkedHashMap<>(payload);
        more.put(field, value);
        return new Inbound(kind, eventId, sender, threadKey, platformTs, retryNum, status, reason, more, workspace);
    }

    boolean received() {
        return RECEIVED.equals(status);
    }

    /** A Slack {@code ts} ({@code 1700000000.000100}) as a number; now when it is none. */
    static BigDecimal slackTs(@Nullable String ts) {
        if (ts != null) {
            try {
                return new BigDecimal(ts).setScale(6, RoundingMode.DOWN);
            }
            catch (NumberFormatException ex) {
                // Not a ts: the time it arrived.
            }
        }
        return epoch(Instant.now());
    }

    static BigDecimal epoch(Instant instant) {
        return BigDecimal.valueOf(instant.getEpochSecond())
            .add(BigDecimal.valueOf(instant.getNano() / 1000, 6))
            .setScale(6, RoundingMode.DOWN);
    }
}
