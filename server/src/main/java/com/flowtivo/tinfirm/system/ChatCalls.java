package com.flowtivo.tinfirm.system;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.jspecify.annotations.Nullable;

/**
 * Tinfirm's calls to the chat platforms (the package 5 contract, §2.2), for the {@code chat} module: Slack's Web API,
 * its files and its Socket Mode WebSocket, and the Bot Framework's connector, token and signing keys. Every call goes
 * through the outbound client and its guard, to the fixed endpoints of the system's kind only (Slack's API at the
 * system's base URL, {@code files.slack.com}, the Socket Mode hosts {@code apps.connections.open} names; Microsoft's
 * login, the Bot Framework's OpenID metadata, and the connector at the system's base URL, to which an activity's
 * {@code serviceUrl} must belong). The bot's credential is the caller's, revealed for this use only; it is never
 * logged, and never in an answer. A call runs outside any database transaction ({@link IllegalStateException} inside
 * one) and answers a value, never an exception.
 * <p>
 * What the bot posts cannot ping, mention or unfurl (C11): {@link #slackPost} always sends {@code parse: none},
 * {@code link_names: false}, {@code unfurl_links: false} and {@code unfurl_media: false}.
 */
public interface ChatCalls {

    // ---- Slack -----------------------------------------------------------------------------------------------------

    /**
     * Slack's bot as {@code auth.test} names it.
     *
     * @param teamId    the workspace, which becomes the system's {@code teamId}
     * @param botUserId the bot's own user id
     */
    record SlackBot(String teamId, @Nullable String teamName, String botUserId, @Nullable String botId) {
    }

    /**
     * A Slack user as {@code users.info} answers.
     *
     * @param teamId    the user's own workspace; another's for a Slack Connect user
     * @param name      the display name, else the real name, else the handle
     * @param realName  {@code profile.real_name}, or null
     * @param avatarUrl {@code profile.image_72}, or null
     */
    record SlackUser(String id, @Nullable String teamId, @Nullable String name, boolean bot, boolean deleted,
            boolean restricted, boolean ultraRestricted, boolean stranger, @Nullable String realName,
            @Nullable String avatarUrl) {
    }

    /**
     * A message the bot posts.
     *
     * @param threadTs the thread's root, or null for a top-level message
     * @param text     the plain fallback (notifications, screen readers), at most 3000 characters
     * @param blocks   Block Kit blocks, or none
     * @param username the name it shows, e.g. {@code Support (Tinfirm agent)}; null for the bot's own
     */
    record SlackMessage(String channel, @Nullable String threadTs, String text, List<Map<String, Object>> blocks,
            @Nullable String username) {
    }

    /** Checks a bot token: {@code auth.test}. */
    CallResult<SlackBot> slackAuthTest(SystemInfo system, String botToken);

    /** {@code users.info}. */
    CallResult<SlackUser> slackUser(SystemInfo system, String botToken, String userId);

    /**
     * A Slack profile picture as a {@code data:} URL (PNG, JPEG, GIF or WebP, at most 64 KiB), read without a token,
     * only from Slack's avatar host; {@code INVALID} for another host or kind, or a larger picture.
     */
    CallResult<String> slackAvatar(SystemInfo system, String url);

    /**
     * A Slack channel as {@code users.conversations} lists it (the package 5 contract, §3.4).
     *
     * @param shared Slack Connect: shared with another organisation
     */
    record SlackChannel(String id, @Nullable String name, boolean privateChannel, boolean archived, boolean shared) {
    }

    /**
     * {@code users.conversations} with the bot's token, public and private channels, not archived: the bot's own
     * channels, or with {@code userId} those of the user's channels the bot is in too (package 5, §5.7). At most
     * 2 000 channels (10 pages).
     */
    CallResult<List<SlackChannel>> slackChannels(SystemInfo system, String botToken, @Nullable String userId);

    /** {@code conversations.open} with the user: the direct message's channel. */
    CallResult<String> slackOpenDirect(SystemInfo system, String botToken, String userId);

    /** {@code chat.postMessage}: the message's {@code ts}. */
    CallResult<String> slackPost(SystemInfo system, String botToken, SlackMessage message);

    /** {@code chat.update} of a message the bot posted. */
    CallResult<Boolean> slackUpdate(SystemInfo system, String botToken, String channel, String ts, String text,
            List<Map<String, Object>> blocks);

    /** {@code chat.postEphemeral}: a message only the user sees. */
    CallResult<Boolean> slackEphemeral(SystemInfo system, String botToken, String channel, String userId,
            String text);

    /** {@code reactions.add}. */
    CallResult<Boolean> slackReact(SystemInfo system, String botToken, String channel, String ts, String name);

    /** A file of a message, from its {@code url_private_download} on Slack's file host, at most {@code maxBytes}. */
    CallResult<byte[]> slackDownload(SystemInfo system, String botToken, String url, long maxBytes);

    /**
     * Uploads a file into a thread: {@code files.getUploadURLExternal}, the bytes to the URL it names on Slack's file
     * host, then {@code files.completeUploadExternal} with the channel and thread. Answers the file's id.
     */
    CallResult<String> slackUpload(SystemInfo system, String botToken, String channel, @Nullable String threadTs,
            String fileName, byte[] content);

    /** {@code apps.connections.open} with the app-level token: a Socket Mode URL to connect to. */
    CallResult<String> slackSocketUrl(SystemInfo system, String appToken);

    /** What a socket hands its listener, on the socket's own thread. */
    interface SocketListener {

        /** A whole text frame. */
        void onText(String text);

        /** The socket closed or failed; {@code reason} names why. */
        void onClosed(int code, String reason);

        /** A message longer than the socket's limit was dropped as it arrived, never put together nor handed over. */
        void onTooLong();
    }

    /** An open socket. */
    interface ChatSocket {

        /** Sends a text frame; false when the socket is closed. */
        boolean send(String text);

        void close();

        boolean open();
    }

    /**
     * Connects to a Socket Mode URL that {@link #slackSocketUrl} answered; only a Slack host. A message longer than
     * {@code maxChars} characters is dropped as its frames arrive, and the connection carries on.
     */
    CallResult<ChatSocket> slackConnect(SystemInfo system, String url, int maxChars, SocketListener listener);

    // ---- Teams (the Bot Framework) ---------------------------------------------------------------------------------

    /** The bot of a Teams system: its tenant, its app id and its app password. */
    record TeamsBot(String tenantId, String appId, String password) {

        @Override
        public String toString() {
            return "TeamsBot[tenantId=" + tenantId + ", appId=" + appId + ", password=***]";
        }
    }

    /**
     * A member of a Teams conversation, as the roster answers.
     *
     * @param userRole {@code user}, {@code guest}, {@code anonymous}; null when Teams does not say
     */
    record TeamsMember(String id, @Nullable String name, @Nullable String aadObjectId, @Nullable String userRole,
            @Nullable String tenantId) {
    }

    /**
     * Proves the app password: a {@code client_credentials} token of the tenant for the Bot Framework. Answers when it
     * expires. The token is kept until then, per system and password.
     */
    CallResult<Instant> teamsCheck(SystemInfo system, TeamsBot bot);

    /** Posts an activity into a conversation at its {@code serviceUrl}: the activity's id. */
    CallResult<String> teamsPost(SystemInfo system, TeamsBot bot, String serviceUrl, String conversationId,
            Map<String, Object> activity);

    /** Replaces an activity the bot posted. */
    CallResult<Boolean> teamsUpdate(SystemInfo system, TeamsBot bot, String serviceUrl, String conversationId,
            String activityId, Map<String, Object> activity);

    /** One member of a conversation, by the user's {@code 29:} id. */
    CallResult<TeamsMember> teamsMember(SystemInfo system, TeamsBot bot, String serviceUrl, String conversationId,
            String userId);

    /** Opens the bot's personal chat with a user: its conversation id. */
    CallResult<String> teamsOpenDirect(SystemInfo system, TeamsBot bot, String serviceUrl, String userId);

    /** The Bot Framework's signing keys (a JWK set, as JSON), from the keys its OpenID metadata names. */
    CallResult<String> teamsKeys(SystemInfo system);
}
