package com.flowtivo.tinfirm.system.internal.slack;

import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

import tools.jackson.databind.JsonNode;

/**
 * Slack's Web API for one system (the package 5 contract, §2.2, §5.9): the bot's calls with its token, at the system's
 * base URL ({@code https://slack.com/api}), and its files at Slack's file host; and, with a member's or a service
 * identity's user token, the {@code history} tool and its account's {@code auth.test} (open point 13). Slack answers
 * {@code 200} with {@code ok: false} and an {@code error} for most refusals: {@code invalid_auth}, {@code not_authed},
 * {@code token_revoked}, {@code token_expired} and {@code account_inactive} are {@code UNAUTHORIZED};
 * {@code ratelimited} and a {@code 429} are {@code RATE_LIMITED} with Slack's {@code Retry-After};
 * {@code channel_not_found}, {@code not_in_channel}, {@code is_archived}, {@code user_not_found} and
 * {@code message_not_found} are {@code NOT_FOUND}; the failure's message names Slack's error, never the token.
 */
public final class SlackApi {

    private static final Set<String> UNAUTHORIZED = Set.of("invalid_auth", "not_authed", "token_revoked",
            "token_expired", "account_inactive");

    private static final Set<String> NOT_FOUND = Set.of("channel_not_found", "not_in_channel", "is_archived",
            "user_not_found", "message_not_found", "cant_update_message", "file_not_found");

    private final OutboundHttp http;

    private final ChatEndpoints endpoints;

    /** The system's base URL, Slack's API root. */
    private final String apiBase;

    public SlackApi(OutboundHttp http, ChatEndpoints endpoints, String apiBase) {
        this.http = http;
        this.endpoints = endpoints;
        this.apiBase = apiBase.endsWith("/") ? apiBase.substring(0, apiBase.length() - 1) : apiBase;
    }

    /** A method with a JSON body ({@code chat.postMessage} and the other writes). */
    CallResult<JsonNode> json(String token, String method, Map<String, Object> body) {
        HttpRequest request = http.request(apiBase + "/" + method)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/json; charset=utf-8")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)))
            .build();
        return answer(method, http.send(apiBase, request), token);
    }

    /** A method with a form body ({@code users.info}, {@code auth.test}, {@code files.getUploadURLExternal}). */
    CallResult<JsonNode> form(String token, String method, Map<String, String> params) {
        String body = params.entrySet()
            .stream()
            .map(entry -> Json.encode(entry.getKey()) + "=" + Json.encode(entry.getValue()))
            .collect(Collectors.joining("&"));
        HttpRequest request = http.request(apiBase + "/" + method)
            .header("Authorization", "Bearer " + token)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body))
            .build();
        return answer(method, http.send(apiBase, request), token);
    }

    public CallResult<ChatCalls.SlackBot> authTest(String token) {
        return form(token, "auth.test", Map.of()).map(body -> new ChatCalls.SlackBot(
                Objects.requireNonNullElse(Json.text(body, "team_id"), ""), Json.text(body, "team"),
                Objects.requireNonNullElse(Json.text(body, "user_id"), ""), Json.text(body, "bot_id")));
    }

    /**
     * Whose a token is, as {@code auth.test} says: the workspace, the user and the user's name; a bot's token also
     * names its {@code bot_id}.
     */
    public record TokenOwner(String teamId, String userId, @Nullable String userName, @Nullable String botId) {
    }

    /** {@code auth.test} with any token: whose it is. */
    public CallResult<TokenOwner> owner(String token) {
        return form(token, "auth.test", Map.of()).map(body -> new TokenOwner(
                Objects.requireNonNullElse(Json.text(body, "team_id"), ""),
                Objects.requireNonNullElse(Json.text(body, "user_id"), ""), Json.text(body, "user"),
                Json.text(body, "bot_id")));
    }

    public CallResult<ChatCalls.SlackUser> user(String token, String userId) {
        return form(token, "users.info", Map.of("user", userId)).map(body -> {
            JsonNode user = body.get("user");
            JsonNode profile = user != null ? user.get("profile") : null;
            String name = Json.text(profile, "display_name");
            if (name == null || name.isBlank()) {
                name = Json.text(profile, "real_name");
            }
            if (name == null || name.isBlank()) {
                name = Json.text(user, "name");
            }
            String realName = Json.text(profile, "real_name");
            String avatar = Json.text(profile, "image_72");
            return new ChatCalls.SlackUser(Objects.requireNonNullElse(Json.text(user, "id"), userId),
                    Json.text(user, "team_id"), name, Json.flag(user, "is_bot") || Json.flag(user, "is_app_user"),
                    Json.flag(user, "deleted"), Json.flag(user, "is_restricted"),
                    Json.flag(user, "is_ultra_restricted"), Json.flag(user, "is_stranger"),
                    realName != null && !realName.isBlank() ? realName : null,
                    avatar != null && !avatar.isBlank() ? avatar : null);
        });
    }

    /**
     * {@code users.conversations}: public and private channels, not archived, the bot's or, with {@code userId}, the
     * user's that the bot is in too; page by page, at most 10 pages of 200.
     */
    public CallResult<List<ChatCalls.SlackChannel>> channels(String token, @Nullable String userId) {
        List<ChatCalls.SlackChannel> channels = new java.util.ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            Map<String, String> params = new LinkedHashMap<>();
            params.put("types", "public_channel,private_channel");
            params.put("exclude_archived", "true");
            params.put("limit", "200");
            if (userId != null) {
                params.put("user", userId);
            }
            if (cursor != null) {
                params.put("cursor", cursor);
            }
            CallResult<JsonNode> listed = form(token, "users.conversations", params);
            if (listed.failure() != null) {
                return listed.failedAs();
            }
            JsonNode body = Objects.requireNonNull(listed.value());
            for (JsonNode channel : body.path("channels")) {
                String id = Json.text(channel, "id");
                if (id != null) {
                    channels.add(new ChatCalls.SlackChannel(id, Json.text(channel, "name"),
                            Json.flag(channel, "is_private") || Json.flag(channel, "is_group"),
                            Json.flag(channel, "is_archived"), Json.flag(channel, "is_ext_shared")));
                }
            }
            cursor = Json.text(body.path("response_metadata"), "next_cursor");
            if (cursor == null || cursor.isBlank()) {
                break;
            }
        }
        return CallResult.ok(channels);
    }

    public CallResult<String> openDirect(String token, String userId) {
        return json(token, "conversations.open", Map.of("users", userId))
            .map(body -> Objects.requireNonNullElse(Json.text(body.get("channel"), "id"), ""));
    }

    /**
     * {@code chat.postMessage}, never pinging, mentioning or unfurling (C11), with the agent's name as its
     * {@code username} ({@code chat:write.customize}) and the bot's own picture.
     */
    public CallResult<String> post(String token, ChatCalls.SlackMessage message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", message.channel());
        if (message.threadTs() != null) {
            body.put("thread_ts", message.threadTs());
        }
        body.put("text", Json.cut(message.text(), 3000));
        if (!message.blocks().isEmpty()) {
            body.put("blocks", message.blocks());
        }
        if (message.username() != null) {
            body.put("username", message.username());
        }
        body.put("parse", "none");
        body.put("link_names", false);
        body.put("unfurl_links", false);
        body.put("unfurl_media", false);
        return json(token, "chat.postMessage", body)
            .map(answer -> Objects.requireNonNullElse(Json.text(answer, "ts"), ""));
    }

    public CallResult<Boolean> update(String token, String channel, String ts, String text,
            List<Map<String, Object>> blocks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("channel", channel);
        body.put("ts", ts);
        body.put("text", Json.cut(text, 3000));
        body.put("blocks", blocks);
        body.put("parse", "none");
        body.put("link_names", false);
        return json(token, "chat.update", body).map(answer -> true);
    }

    public CallResult<Boolean> ephemeral(String token, String channel, String userId, String text) {
        return json(token, "chat.postEphemeral", Map.of("channel", channel, "user", userId, "text", text,
                "parse", "none", "link_names", false)).map(answer -> true);
    }

    public CallResult<Boolean> react(String token, String channel, String ts, String name) {
        CallResult<JsonNode> result = json(token, "reactions.add", Map.of("channel", channel, "timestamp", ts,
                "name", name));
        if (result.failure() != null && result.failure().message().contains("already_reacted")) {
            return CallResult.ok(true);
        }
        return result.map(answer -> true);
    }

    /** The largest profile picture read. */
    static final int MAX_AVATAR_BYTES = 64 * 1024;

    /** The picture types a profile picture is read as. */
    private static final java.util.Set<String> AVATAR_TYPES = java.util.Set.of("image/png", "image/jpeg",
            "image/gif", "image/webp");

    /**
     * A profile picture from Slack's avatar host, without a token, as a {@code data:} URL: the web app's pages load
     * images from their own origin only.
     */
    public CallResult<String> avatar(String url) {
        String origin = ChatEndpoints.origin(url);
        if (origin == null || !origin.equals(ChatEndpoints.origin(endpoints.slackAvatars()))) {
            return CallResult.failed(CallResult.Kind.INVALID, "The picture is not on Slack's avatar host");
        }
        HttpRequest request;
        try {
            request = http.request(url).GET().build();
        }
        catch (IllegalArgumentException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, "The picture's URL is not usable");
        }
        CallResult<OutboundHttp.BytesResponse> sent = http.sendForBytes(endpoints.slackAvatars(), request,
                MAX_AVATAR_BYTES);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.BytesResponse response = Objects.requireNonNull(sent.value());
        String type = response.headers().firstValue("Content-Type").orElse("").split(";")[0].strip()
            .toLowerCase(java.util.Locale.ROOT);
        if (response.status() != 200 || !AVATAR_TYPES.contains(type)) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack's avatar host answered " + response.status()
                    + " without a picture");
        }
        return CallResult.ok("data:" + type + ";base64," + java.util.Base64.getEncoder().encodeToString(
                response.body()));
    }

    /** A file from Slack's file host, with the bot's token; only that host's origin. */
    public CallResult<byte[]> download(String token, String url, long maxBytes) {
        String origin = ChatEndpoints.origin(url);
        if (origin == null || !origin.equals(ChatEndpoints.origin(endpoints.slackFiles()))) {
            return CallResult.failed(CallResult.Kind.INVALID, "The file is not on Slack's file host");
        }
        HttpRequest request;
        try {
            request = http.request(url).header("Authorization", "Bearer " + token).GET().build();
        }
        catch (IllegalArgumentException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, "The file's URL is not usable");
        }
        CallResult<OutboundHttp.BytesResponse> sent = http.sendForBytes(endpoints.slackFiles(), request, maxBytes);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.BytesResponse response = Objects.requireNonNull(sent.value());
        if (response.status() < 200 || response.status() > 299) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), "Slack's file host answered "
                    + response.status());
        }
        return CallResult.ok(response.body());
    }

    /** {@code files.getUploadURLExternal}, the bytes, then {@code files.completeUploadExternal} into the thread. */
    public CallResult<String> upload(String token, String channel, @Nullable String threadTs, String fileName,
            byte[] content) {
        CallResult<JsonNode> url = form(token, "files.getUploadURLExternal", Map.of("filename", fileName, "length",
                Integer.toString(content.length)));
        if (url.failure() != null) {
            return url.failedAs();
        }
        String uploadUrl = Json.text(url.value(), "upload_url");
        String fileId = Json.text(url.value(), "file_id");
        if (uploadUrl == null || fileId == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack named no upload URL");
        }
        String origin = ChatEndpoints.origin(uploadUrl);
        if (origin == null || !origin.equals(ChatEndpoints.origin(endpoints.slackFiles()))) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack's upload URL is not on its file host");
        }
        HttpRequest upload;
        try {
            upload = http.request(uploadUrl)
                .header("Content-Type", "application/octet-stream")
                .POST(HttpRequest.BodyPublishers.ofByteArray(content))
                .build();
        }
        catch (IllegalArgumentException ex) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack's upload URL is not usable");
        }
        CallResult<OutboundHttp.Response> sent = http.send(endpoints.slackFiles(), upload);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        if (!Objects.requireNonNull(sent.value()).success()) {
            return CallResult.failed(OutboundHttp.kindOf(sent.value().status()), "Slack's file host answered "
                    + sent.value().status());
        }
        Map<String, Object> complete = new LinkedHashMap<>();
        complete.put("files", List.of(Map.of("id", fileId, "title", fileName)));
        complete.put("channel_id", channel);
        if (threadTs != null) {
            complete.put("thread_ts", threadTs);
        }
        return json(token, "files.completeUploadExternal", complete).map(answer -> fileId);
    }

    /**
     * One message as {@code conversations.history} and {@code conversations.replies} answer it.
     *
     * @param user       the author's user id; null for a bot's or an app's message without one
     * @param botName    a bot's or an app's name, when Slack names one
     * @param threadTs   the thread it starts or belongs to
     * @param replyCount how many replies a thread's first message has
     */
    public record Message(String ts, @Nullable String user, @Nullable String botName, String text,
            @Nullable String threadTs, int replyCount, @Nullable String subtype) {
    }

    /** A page of messages, as Slack answered it, and the cursor of the next page; null when there is none. */
    public record Messages(List<Message> messages, @Nullable String nextCursor) {
    }

    /**
     * Slack's {@code history} tool (the package 5 contract, open point 13), with a user token that has
     * {@code channels:history} and {@code groups:history}: {@code conversations.history} of a channel, newest first, or
     * {@code conversations.replies} of a thread, its first message first; at most {@code limit}, from the cursor.
     */
    public CallResult<Messages> history(String token, String channel, @Nullable String threadTs, int limit,
            @Nullable String cursor) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("channel", channel);
        if (threadTs != null) {
            params.put("ts", threadTs);
        }
        params.put("limit", Integer.toString(limit));
        if (cursor != null) {
            params.put("cursor", cursor);
        }
        return form(token, threadTs != null ? "conversations.replies" : "conversations.history", params)
            .map(body -> {
                List<Message> messages = new java.util.ArrayList<>();
                for (JsonNode message : body.path("messages")) {
                    String ts = Json.text(message, "ts");
                    if (ts == null) {
                        continue;
                    }
                    String botName = Json.text(message, "username");
                    if (botName == null) {
                        botName = Json.text(message.get("bot_profile"), "name");
                    }
                    JsonNode replies = message.get("reply_count");
                    messages.add(new Message(ts, Json.text(message, "user"), botName,
                            Objects.requireNonNullElse(Json.text(message, "text"), ""), Json.text(message, "thread_ts"),
                            replies != null && replies.isIntegralNumber() ? replies.intValue() : 0,
                            Json.text(message, "subtype")));
                }
                String next = Json.text(body.path("response_metadata"), "next_cursor");
                return new Messages(messages, next == null || next.isBlank() ? null : next);
            });
    }

    /** {@code apps.connections.open} with the app-level token: a Socket Mode URL on a Slack host. */
    public CallResult<String> socketUrl(String appToken) {
        CallResult<JsonNode> opened = form(appToken, "apps.connections.open", Map.of());
        if (opened.failure() != null) {
            return opened.failedAs();
        }
        String url = Json.text(opened.value(), "url");
        if (url == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack named no Socket Mode URL");
        }
        return CallResult.ok(url);
    }

    /** Slack's answer as a value: its body on {@code ok: true}, else why it refused. */
    private static CallResult<JsonNode> answer(String method, CallResult<OutboundHttp.Response> sent, String token) {
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (response.status() == 429) {
            Duration wait = response.header("Retry-After").map(OutboundHttp::retryAfter).orElse(Duration.ofSeconds(1));
            return CallResult.failed(new CallResult.Failure(CallResult.Kind.RATE_LIMITED, "Slack's " + method
                    + " is rate limited", wait));
        }
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), "Slack's " + method + " answered "
                    + response.status());
        }
        JsonNode body = Json.parse(response.body());
        if (body == null || !body.isObject()) {
            return CallResult.failed(CallResult.Kind.INVALID, "Slack's " + method + " answered no JSON");
        }
        if (Json.flag(body, "ok")) {
            return CallResult.ok(body);
        }
        String error = Objects.requireNonNullElse(Json.text(body, "error"), "unknown_error").replace(token, "***");
        CallResult.Kind kind = UNAUTHORIZED.contains(error) ? CallResult.Kind.UNAUTHORIZED
                : NOT_FOUND.contains(error) ? CallResult.Kind.NOT_FOUND
                        : "ratelimited".equals(error) ? CallResult.Kind.RATE_LIMITED : CallResult.Kind.INVALID;
        return CallResult.failed(kind, "Slack's " + method + " refused: " + Json.cut(error, 100));
    }
}
