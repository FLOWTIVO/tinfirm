package com.flowtivo.tinfirm.chat.internal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jspecify.annotations.Nullable;

import tools.jackson.databind.JsonNode;

/**
 * Slack's events and button presses, normalized (the package 5 contract, §5.2): an Events API {@code event_callback}
 * (also the payload of a Socket Mode {@code events_api} envelope) and a {@code block_actions} interaction. What is never
 * handled is {@code IGNORED} at once with its reason: the messages of bots and apps and the bot's own ({@code BOT}),
 * edits and deletions ({@code EDITED}), other subtypes, and a channel message that does not mention the bot
 * ({@code NOT_ADDRESSED}, C7).
 */
final class SlackEvents {

    /** The lifecycle events the manifest subscribes to (§5.1: the private channels' too). */
    static final Set<String> LIFECYCLE = Set.of("member_joined_channel", "member_left_channel", "channel_archive",
            "channel_unarchive", "channel_rename", "channel_deleted", "channel_left", "channel_created",
            "group_archive", "group_unarchive", "group_rename", "group_deleted", "group_left", "channel_shared",
            "channel_unshared", "user_change", "app_uninstalled", "tokens_revoked", "app_home_opened");

    private SlackEvents() {
    }

    /** An {@code event_callback}, or empty for another type. */
    static Optional<Inbound> event(JsonNode envelope, int retryNum) {
        if (!"event_callback".equals(text(envelope, "type"))) {
            return Optional.empty();
        }
        JsonNode event = envelope.path("event");
        String type = text(event, "type");
        String eventId = text(envelope, "event_id");
        if (type == null || eventId == null) {
            return Optional.empty();
        }
        String workspace = text(envelope, "team_id");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", "SLACK");
        payload.put("type", type);
        if ("message".equals(type) || "app_mention".equals(type)) {
            String channel = text(event, "channel");
            String ts = text(event, "ts");
            String threadTs = text(event, "thread_ts");
            String user = text(event, "user");
            String subtype = text(event, "subtype");
            String channelType = text(event, "channel_type");
            payload.put("user", user);
            payload.put("userTeam", text(event, "user_team"));
            payload.put("channel", channel);
            payload.put("channelType", "app_mention".equals(type) ? "channel" : channelType);
            payload.put("ts", ts);
            payload.put("threadTs", threadTs);
            payload.put("text", text(event, "text"));
            payload.put("files", files(event.path("files")));
            String kind = "app_mention".equals(type) ? Inbound.MENTION : "im".equals(channelType) ? Inbound.MESSAGE
                    : Inbound.MENTION;
            String threadKey = channel + ":" + (threadTs != null ? threadTs : ts);
            Inbound inbound = new Inbound(kind, eventId, user, threadKey, Inbound.slackTs(ts), retryNum,
                    Inbound.RECEIVED, null, payload, workspace);
            if (event.hasNonNull("bot_id") || "bot_message".equals(subtype) || user == null) {
                return Optional.of(inbound.ignored("BOT"));
            }
            if ("message_changed".equals(subtype) || "message_deleted".equals(subtype)) {
                return Optional.of(inbound.ignored("EDITED"));
            }
            if (subtype != null && !"file_share".equals(subtype) && !"thread_broadcast".equals(subtype)) {
                return Optional.of(inbound.ignored("NOT_ADDRESSED"));
            }
            if ("message".equals(type) && !"im".equals(channelType)) {
                // A channel's message reaches the bot only as an app_mention (C7): the manifest asks for no more.
                return Optional.of(inbound.ignored("NOT_ADDRESSED"));
            }
            return Optional.of(inbound);
        }
        if (LIFECYCLE.contains(type)) {
            JsonNode channelNode = event.path("channel");
            String channel = channelNode.isObject() ? text(channelNode, "id") : text(event, "channel");
            payload.put("channel", channel);
            payload.put("channelName", channelNode.isObject() ? text(channelNode, "name") : null);
            payload.put("user", event.path("user").isObject() ? text(event.path("user"), "id") : text(event, "user"));
            payload.put("channelType", text(event, "channel_type"));
            payload.put("private", type.startsWith("group_"));
            String key = "lifecycle:" + (channel != null ? channel : type);
            return Optional.of(new Inbound(Inbound.LIFECYCLE, eventId, null, key,
                    Inbound.slackTs(text(event, "event_ts")), retryNum, Inbound.RECEIVED, null, payload, workspace));
        }
        return Optional.of(new Inbound(Inbound.LIFECYCLE, eventId, null, "lifecycle:" + type,
                Inbound.slackTs(text(event, "event_ts")), retryNum, Inbound.RECEIVED, null, payload, workspace)
            .ignored("NOT_ADDRESSED"));
    }

    /** A {@code block_actions} press, or empty for another interaction. */
    static Optional<Inbound> action(JsonNode interaction, int retryNum) {
        if (!"block_actions".equals(text(interaction, "type"))) {
            return Optional.empty();
        }
        JsonNode action = interaction.path("actions").path(0);
        String user = text(interaction.path("user"), "id");
        String actionTs = text(action, "action_ts");
        if (user == null || actionTs == null) {
            return Optional.empty();
        }
        String channel = text(interaction.path("channel"), "id");
        if (channel == null) {
            channel = text(interaction.path("container"), "channel_id");
        }
        JsonNode message = interaction.path("message");
        String messageTs = text(message, "ts");
        if (messageTs == null) {
            messageTs = text(interaction.path("container"), "message_ts");
        }
        String threadTs = text(message, "thread_ts");
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("platform", "SLACK");
        payload.put("type", "action");
        payload.put("user", user);
        payload.put("userName", text(interaction.path("user"), "name"));
        payload.put("channel", channel);
        payload.put("messageTs", messageTs);
        payload.put("threadTs", threadTs);
        payload.put("actionId", text(action, "action_id"));
        payload.put("value", text(action, "value"));
        String workspace = text(interaction.path("team"), "id");
        String threadKey = channel + ":" + (threadTs != null ? threadTs : messageTs);
        return Optional.of(new Inbound(Inbound.ACTION, user + ":" + actionTs, user, threadKey,
                Inbound.slackTs(actionTs), retryNum, Inbound.RECEIVED, null, payload, workspace));
    }

    private static List<Map<String, Object>> files(JsonNode files) {
        List<Map<String, Object>> list = new ArrayList<>();
        if (!files.isArray()) {
            return list;
        }
        for (JsonNode file : files) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", text(file, "id"));
            item.put("name", text(file, "name"));
            item.put("mimetype", text(file, "mimetype"));
            item.put("size", file.path("size").isNumber() ? file.path("size").longValue() : null);
            item.put("url", text(file, "url_private_download"));
            list.add(item);
            if (list.size() >= 50) {
                break;
            }
        }
        return list;
    }

    static @Nullable String text(@Nullable JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }
}
