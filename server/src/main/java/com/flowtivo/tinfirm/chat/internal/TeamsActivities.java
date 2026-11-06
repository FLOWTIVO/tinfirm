package com.flowtivo.tinfirm.chat.internal;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * The Bot Framework's activities, normalized (the package 5 contract, §5.2, §5.8): a {@code message} in a personal
 * chat ({@code MESSAGE}), in a channel ({@code MENTION}), or carrying an {@code Action.Submit}'s data ({@code ACTION},
 * the card's activity in {@code replyToId}); {@code conversationUpdate} and {@code installationUpdate}
 * ({@code LIFECYCLE}). An activity's id is unique only within its conversation, so the event's id is
 * {@code {conversation.id}:{activity id}}. Group chats are refused (§1).
 */
final class TeamsActivities {

    private TeamsActivities() {
    }

    static Optional<Inbound> activity(JsonNode activity) {
        String type = SlackEvents.text(activity, "type");
        String id = SlackEvents.text(activity, "id");
        JsonNode conversation = activity.path("conversation");
        String conversationId = SlackEvents.text(conversation, "id");
        if (type == null || id == null || conversationId == null) {
            return Optional.empty();
        }
        String conversationType = SlackEvents.text(conversation, "conversationType");
        String tenant = SlackEvents.text(activity.path("channelData").path("tenant"), "id");
        JsonNode from = activity.path("from");
        String user = SlackEvents.text(from, "id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", "TEAMS");
        payload.put("type", type);
        payload.put("user", user);
        payload.put("userName", SlackEvents.text(from, "name"));
        payload.put("aadObjectId", SlackEvents.text(from, "aadObjectId"));
        payload.put("channel", conversationId);
        payload.put("channelType", conversationType);
        payload.put("serviceUrl", SlackEvents.text(activity, "serviceUrl"));
        payload.put("tenantId", tenant);
        payload.put("teamId", SlackEvents.text(activity.path("channelData").path("team"), "id"));
        payload.put("ts", id);
        payload.put("botId", SlackEvents.text(activity.path("recipient"), "id"));
        String eventId = conversationId + ":" + id;
        java.math.BigDecimal ts = Inbound.epoch(time(SlackEvents.text(activity, "timestamp")));
        boolean personal = "personal".equals(conversationType);
        String threadKey = personal ? conversationId + ":dm" : conversationId;
        if ("message".equals(type)) {
            JsonNode value = activity.path("value");
            if (value.isObject()) {
                payload.put("value", ChatStore.JSON.convertValue(value, Map.class));
                payload.put("replyToId", SlackEvents.text(activity, "replyToId"));
                return Optional.of(new Inbound(Inbound.ACTION, eventId, user, threadKey, ts, 0, Inbound.RECEIVED,
                        null, payload, tenant));
            }
            payload.put("text", text(activity));
            payload.put("hasFiles", hasFiles(activity.path("attachments")));
            payload.put("mentionsBot", mentionsBot(activity));
            Inbound inbound = new Inbound(personal ? Inbound.MESSAGE : Inbound.MENTION, eventId, user, threadKey,
                    ts, 0, Inbound.RECEIVED, null, payload, tenant);
            if ("groupChat".equals(conversationType)) {
                return Optional.of(inbound.ignored("NOT_SERVED"));
            }
            if (!personal && !mentionsBot(activity)) {
                return Optional.of(inbound.ignored("NOT_ADDRESSED"));
            }
            return Optional.of(inbound);
        }
        if ("conversationUpdate".equals(type) || "installationUpdate".equals(type)) {
            payload.put("eventType", SlackEvents.text(activity.path("channelData"), "eventType"));
            payload.put("channelName", SlackEvents.text(activity.path("channelData").path("channel"), "name"));
            payload.put("action", SlackEvents.text(activity, "action"));
            return Optional.of(new Inbound(Inbound.LIFECYCLE, eventId, null, "lifecycle:" + conversationId, ts, 0,
                    Inbound.RECEIVED, null, payload, tenant));
        }
        return Optional.of(new Inbound(Inbound.LIFECYCLE, eventId, user, "lifecycle:" + conversationId, ts, 0,
                Inbound.RECEIVED, null, payload, tenant).ignored("NOT_ADDRESSED"));
    }

    /** The text without the bot's {@code <at>} mention. */
    private static String text(JsonNode activity) {
        String text = SlackEvents.text(activity, "text");
        if (text == null) {
            return "";
        }
        return text.replaceAll("<at>[^<]*</at>", "").strip();
    }

    private static boolean mentionsBot(JsonNode activity) {
        String bot = SlackEvents.text(activity.path("recipient"), "id");
        for (JsonNode entity : activity.path("entities")) {
            if ("mention".equals(SlackEvents.text(entity, "type"))
                    && bot != null && bot.equals(SlackEvents.text(entity.path("mentioned"), "id"))) {
                return true;
            }
        }
        return false;
    }

    /** Whether the activity carries a file (anything but a card or Teams' own HTML rendering of the text). */
    private static boolean hasFiles(JsonNode attachments) {
        for (JsonNode attachment : attachments) {
            String contentType = SlackEvents.text(attachment, "contentType");
            if (contentType != null && !contentType.equals("text/html")
                    && !contentType.startsWith("application/vnd.microsoft.card")) {
                return true;
            }
        }
        return false;
    }

    private static Instant time(@Nullable String timestamp) {
        if (timestamp != null) {
            try {
                return OffsetDateTime.parse(timestamp).toInstant();
            }
            catch (DateTimeParseException ex) {
                // The time it arrived.
            }
        }
        return Instant.now();
    }
}
