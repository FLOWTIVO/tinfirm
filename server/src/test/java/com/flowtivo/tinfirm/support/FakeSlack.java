package com.flowtivo.tinfirm.support;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.notContaining;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.jspecify.annotations.Nullable;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Slack for the tests (TEST FIXTURE ONLY, the package 5 contract §9's {@code FakeSlack}): one WireMock server for the
 * whole run stands in for Slack's Web API (at {@code /api}, the Slack systems' fixed base URL in the tests) and its
 * file host; {@link SlackSocketStub} for its Socket Mode WebSocket. Every test gets a workspace of its own
 * ({@link #workspace()}): its own team, bot token, signing secret and app-level token, so that its stubs, which match
 * on the token, never meet another test's. Deliveries of the Events API are signed as Slack signs them
 * ({@code v0:{timestamp}:{body}}). Every token and secret here is a test value.
 */
public final class FakeSlack {

    private static final WireMockServer SERVER = new WireMockServer(StubServers.options()
        .dynamicPort()
        .globalTemplating(true));

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    private static final AtomicLong TS = new AtomicLong(1_700_000_000_000_000L);

    static {
        SERVER.start();
    }

    private FakeSlack() {
    }

    public static WireMockServer server() {
        return SERVER;
    }

    /** Slack's Web API root in the tests: the Slack systems' base URL. */
    public static String apiUrl() {
        return "http://localhost:" + SERVER.port() + "/api";
    }

    /** Slack's file host in the tests. */
    public static String filesUrl() {
        return "http://localhost:" + SERVER.port();
    }

    /** A Slack {@code ts}, later than every one before it. */
    public static String ts() {
        long micros = TS.addAndGet(1000);
        return (micros / 1_000_000) + "." + String.format("%06d", micros % 1_000_000);
    }

    private static String random(int length) {
        StringBuilder text = new StringBuilder();
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
        for (int i = 0; i < length; i++) {
            text.append(alphabet.charAt(ThreadLocalRandom.current().nextInt(alphabet.length())));
        }
        return text.toString();
    }

    /** A workspace of its own, with its bot's {@code auth.test} answered. */
    public static Workspace workspace() {
        Workspace workspace = new Workspace("T" + random(9), "Workspace " + random(4), "xoxb-test-" + random(20),
                "U" + random(9), "test-signing-" + random(24), "xapp-test-" + random(20));
        workspace.authTest(workspace.botToken);
        workspace.okMethods();
        return workspace;
    }

    /** The HMAC Slack signs a request with: {@code v0=} and the hex HMAC-SHA256 of {@code v0:{ts}:{body}}. */
    public static String sign(String secret, String timestamp, byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update(("v0:" + timestamp + ":").getBytes(StandardCharsets.UTF_8));
            mac.update(body);
            return "v0=" + HexFormat.of().formatHex(mac.doFinal());
        }
        catch (NoSuchAlgorithmException | InvalidKeyException ex) {
            throw new IllegalStateException(ex);
        }
    }

    /** A Slack workspace of a test, and its app. */
    public static final class Workspace {

        public final String teamId;

        public final String teamName;

        public final String botToken;

        public final String botUserId;

        public final String signingSecret;

        public final String appToken;

        private Workspace(String teamId, String teamName, String botToken, String botUserId, String signingSecret,
                String appToken) {
            this.teamId = teamId;
            this.teamName = teamName;
            this.botToken = botToken;
            this.botUserId = botUserId;
            this.signingSecret = signingSecret;
            this.appToken = appToken;
        }

        /** {@code auth.test} accepts the token as this workspace's bot. */
        public void authTest(String token) {
            SERVER.stubFor(post(urlEqualTo("/api/auth.test"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(json(Map.of("ok", true, "team_id", teamId, "team", teamName, "user_id", botUserId,
                        "bot_id", "B" + botUserId.substring(1)))));
        }

        /** {@code auth.test} answers another workspace's bot for the token. */
        public void authTestOf(String token, String otherTeam, String otherBotUser) {
            SERVER.stubFor(post(urlEqualTo("/api/auth.test"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(json(Map.of("ok", true, "team_id", otherTeam, "team", "Other", "user_id", otherBotUser))));
        }

        /** Slack refuses the token everywhere ({@code invalid_auth}). */
        public void revoke(String token) {
            SERVER.stubFor(post(urlPathEqualTo("/api/auth.test"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .atPriority(1)
                .willReturn(json(Map.of("ok", false, "error", "invalid_auth"))));
        }

        // ---- the app's user accounts, for history (open point 13) ------------------------------------------------

        /**
         * Slack's OAuth v2 for the app's user scopes: {@code oauth.v2.access} answers the code with the user's token as
         * {@code authed_user}'s, and {@code auth.test} names the user with it.
         */
        public void userSignIn(String code, String userId, String userToken) {
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("ok", true);
            answer.put("app_id", "A" + teamId.substring(1));
            answer.put("authed_user", Map.of("id", userId, "scope", "channels:history,groups:history",
                    "access_token", userToken, "token_type", "user"));
            answer.put("team", Map.of("id", teamId, "name", teamName));
            answer.put("is_enterprise_install", false);
            SERVER.stubFor(post(urlEqualTo("/api/oauth.v2.access"))
                .withRequestBody(containing("code=" + code))
                .willReturn(json(answer)));
            userToken(userToken, userId);
        }

        /** {@code auth.test} takes the token as this workspace's user. */
        public void userToken(String token, String userId) {
            SERVER.stubFor(post(urlEqualTo("/api/auth.test"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .willReturn(json(Map.of("ok", true, "team_id", teamId, "team", teamName, "user_id", userId, "user",
                        "user-" + userId.toLowerCase()))));
        }

        /** {@code oauth.v2.access} answers the code with Slack's refusal. */
        public void refuseCode(String code, String error) {
            SERVER.stubFor(post(urlEqualTo("/api/oauth.v2.access"))
                .withRequestBody(containing("code=" + code))
                .willReturn(json(Map.of("ok", false, "error", error))));
        }

        /** The forms {@code oauth.v2.access} took for the code. */
        public static List<String> codeExchanges(String code) {
            return SERVER.findAll(postRequestedFor(urlEqualTo("/api/oauth.v2.access")))
                .stream()
                .map(LoggedRequest::getBodyAsString)
                .filter(body -> body.contains("code=" + code))
                .toList();
        }

        /** A message as {@code conversations.history} answers it. */
        public static Map<String, Object> message(String user, String ts, String text) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("type", "message");
            message.put("user", user);
            message.put("ts", ts);
            message.put("text", text);
            return message;
        }

        /**
         * {@code conversations.history} of the channel with the user token answers these messages, newest first, and
         * the cursor of the next page when not null.
         */
        public void history(String token, String channel, List<Map<String, Object>> newestFirst,
                @Nullable String nextCursor) {
            SERVER.stubFor(post(urlEqualTo("/api/conversations.history"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withRequestBody(containing("channel=" + channel))
                .willReturn(json(Map.of("ok", true, "messages", newestFirst, "has_more", nextCursor != null,
                        "response_metadata", Map.of("next_cursor", nextCursor != null ? nextCursor : "")))));
        }

        /** {@code conversations.replies} of the thread with the user token answers these messages, oldest first. */
        public void replies(String token, String channel, String threadTs, List<Map<String, Object>> oldestFirst) {
            SERVER.stubFor(post(urlEqualTo("/api/conversations.replies"))
                .withHeader("Authorization", equalTo("Bearer " + token))
                .withRequestBody(containing("channel=" + channel))
                .withRequestBody(containing("ts=" + threadTs))
                .willReturn(json(Map.of("ok", true, "messages", oldestFirst, "has_more", false))));
        }

        /** Slack refuses the user token's reads ({@code invalid_auth}). */
        public void refuseHistory(String token) {
            for (String method : List.of("conversations.history", "conversations.replies")) {
                SERVER.stubFor(post(urlEqualTo("/api/" + method))
                    .withHeader("Authorization", equalTo("Bearer " + token))
                    .atPriority(1)
                    .willReturn(json(Map.of("ok", false, "error", "invalid_auth"))));
            }
        }

        /** How many calls of the method came with the token. */
        public static int countWith(String method, String token) {
            return SERVER.findAll(postRequestedFor(urlEqualTo("/api/" + method))
                .withHeader("Authorization", equalTo("Bearer " + token))).size();
        }

        /** The methods that answer {@code ok} with nothing to say, and the posts, which answer a new {@code ts}. */
        private void okMethods() {
            for (String method : List.of("chat.update", "chat.postEphemeral", "reactions.add",
                    "files.completeUploadExternal")) {
                SERVER.stubFor(post(urlEqualTo("/api/" + method))
                    .withHeader("Authorization", equalTo("Bearer " + botToken))
                    .willReturn(json(Map.of("ok", true))));
            }
            SERVER.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true,\"ts\":\"1800000000.{{randomValue length=6 type='NUMERIC'}}\"}")));
            SERVER.stubFor(post(urlEqualTo("/api/conversations.open"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                    .withBody("{\"ok\":true,\"channel\":{\"id\":\"D{{jsonPath request.body '$.users'}}\"}}")));
            SERVER.stubFor(post(urlEqualTo("/api/files.getUploadURLExternal"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .willReturn(json(Map.of("ok", true, "upload_url", filesUrl() + "/upload/" + teamId, "file_id",
                        "F" + random(9)))));
            SERVER.stubFor(post(urlEqualTo("/upload/" + teamId)).willReturn(aResponse().withStatus(200)));
        }

        /** The channels the bot is in, as {@code users.conversations} lists them (package 5b). */
        private final List<Map<String, Object>> channels = new ArrayList<>();

        /**
         * A channel the bot is in, listed by {@code users.conversations} with the bot's token: its id.
         *
         * @param privateChannel a private channel ({@code is_private})
         */
        public String channel(String name, boolean privateChannel) {
            String id = (privateChannel ? "G" : "C") + random(9);
            Map<String, Object> channel = new LinkedHashMap<>();
            channel.put("id", id);
            channel.put("name", name);
            channel.put("is_channel", !privateChannel);
            channel.put("is_private", privateChannel);
            channel.put("is_archived", false);
            channel.put("is_ext_shared", false);
            channels.add(channel);
            listChannels();
            return id;
        }

        /** The bot left the channel: {@code users.conversations} lists it no more. */
        public void leave(String channelId) {
            channels.removeIf(channel -> channelId.equals(channel.get("id")));
            listChannels();
        }

        private void listChannels() {
            SERVER.stubFor(post(urlEqualTo("/api/users.conversations"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(notContaining("user="))
                .willReturn(json(Map.of("ok", true, "channels", List.copyOf(channels), "response_metadata",
                        Map.of("next_cursor", "")))));
        }

        /** How many times {@code users.conversations} listed the bot's own channels (no {@code user}). */
        public int botChannelListings() {
            return SERVER.findAll(postRequestedFor(urlEqualTo("/api/users.conversations"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(notContaining("user="))).size();
        }

        /** The user is in these of the bot's channels, as {@code users.conversations} with {@code user} lists them. */
        public void inChannels(String userId, String... channelIds) {
            List<Map<String, Object>> theirs = channels.stream()
                .filter(channel -> List.of(channelIds).contains((String) channel.get("id")))
                .toList();
            SERVER.stubFor(post(urlEqualTo("/api/users.conversations"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(containing("user=" + userId))
                .willReturn(json(Map.of("ok", true, "channels", theirs, "response_metadata",
                        Map.of("next_cursor", "")))));
        }

        /** {@code chat.postMessage} into the channel answers {@code channel_not_found}, as when the bot left it. */
        public void channelGone(String channelId) {
            SERVER.stubFor(post(urlEqualTo("/api/chat.postMessage"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(containing("\"channel\":\"" + channelId + "\""))
                .atPriority(1)
                .willReturn(json(Map.of("ok", false, "error", "channel_not_found"))));
        }

        /** A member of the workspace, as {@code users.info} answers. */
        public String member(String name) {
            return user(name, teamId, false, false, false);
        }

        /** A user as {@code users.info} answers: of a workspace, a guest (restricted), a stranger, a bot. */
        public String user(String name, String userTeam, boolean restricted, boolean stranger, boolean bot) {
            String id = "U" + random(9);
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", id);
            user.put("team_id", userTeam);
            user.put("name", name.toLowerCase().replace(' ', '.'));
            user.put("deleted", false);
            user.put("is_bot", bot);
            user.put("is_restricted", restricted);
            user.put("is_ultra_restricted", false);
            user.put("is_stranger", stranger);
            user.put("profile", Map.of("display_name", name, "real_name", name));
            SERVER.stubFor(post(urlEqualTo("/api/users.info"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(containing("user=" + id))
                .willReturn(json(Map.of("ok", true, "user", user))));
            return id;
        }

        /**
         * A member of the workspace whose display name is not their real name, with a profile picture on the stub's
         * avatar host, as {@code users.info} answers.
         */
        public String memberWithPicture(String realName, String displayName, byte[] png) {
            String id = "U" + random(9);
            String picture = "/avatars/" + id + "_72.png";
            Map<String, Object> user = new LinkedHashMap<>();
            user.put("id", id);
            user.put("team_id", teamId);
            user.put("name", realName.toLowerCase().replace(' ', '.'));
            user.put("deleted", false);
            user.put("is_bot", false);
            user.put("is_restricted", false);
            user.put("is_ultra_restricted", false);
            user.put("is_stranger", false);
            user.put("profile", Map.of("display_name", displayName, "real_name", realName, "image_72",
                    filesUrl() + picture));
            SERVER.stubFor(post(urlEqualTo("/api/users.info"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(containing("user=" + id))
                .willReturn(json(Map.of("ok", true, "user", user))));
            SERVER.stubFor(get(urlEqualTo(picture))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "image/png").withBody(png)));
            return id;
        }

        /** {@code users.info} answers slowly for the user, as Slack sometimes does. */
        public void slowUser(String userId, Duration delay) {
            SERVER.stubFor(post(urlEqualTo("/api/users.info"))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .withRequestBody(containing("user=" + userId))
                .atPriority(1)
                .willReturn(json(Map.of("ok", true, "user", Map.of("id", userId, "team_id", teamId, "name",
                        "slow", "profile", Map.of("display_name", "Slow")))).withFixedDelay((int) delay.toMillis())));
        }

        /** A file on Slack's file host, which only the bot's token downloads. */
        public String file(String name, byte[] content) {
            String path = "/files-pri/" + teamId + "/" + random(8) + "/" + name;
            SERVER.stubFor(get(urlEqualTo(path))
                .withHeader("Authorization", equalTo("Bearer " + botToken))
                .willReturn(aResponse().withStatus(200).withBody(content)));
            return filesUrl() + path;
        }

        /** {@code apps.connections.open} with the app-level token names the Socket Mode stub. */
        public void socketMode(SlackSocketStub sockets) {
            SERVER.stubFor(post(urlEqualTo("/api/apps.connections.open"))
                .withHeader("Authorization", equalTo("Bearer " + appToken))
                .willReturn(json(Map.of("ok", true, "url", sockets.url(teamId)))));
        }

        /** {@code apps.connections.open} refuses the app-level token. */
        public void refuseAppToken() {
            SERVER.stubFor(post(urlEqualTo("/api/apps.connections.open"))
                .withHeader("Authorization", equalTo("Bearer " + appToken))
                .atPriority(1)
                .willReturn(json(Map.of("ok", false, "error", "invalid_auth"))));
        }

        /** The bodies the bot posted with {@code chat.postMessage}, oldest first. */
        public List<JsonNode> posts() {
            return requests("chat.postMessage");
        }

        /** The posts into a channel. */
        public List<JsonNode> posts(String channel) {
            return posts().stream().filter(post -> channel.equals(post.path("channel").asString())).toList();
        }

        /** The ephemeral messages the bot sent. */
        public List<JsonNode> ephemerals() {
            return requests("chat.postEphemeral");
        }

        /** The updates of the bot's messages. */
        public List<JsonNode> updates() {
            return requests("chat.update");
        }

        public int count(String method) {
            return SERVER.findAll(postRequestedFor(urlEqualTo("/api/" + method))
                .withHeader("Authorization", equalTo("Bearer " + botToken))).size();
        }

        private List<JsonNode> requests(String method) {
            return SERVER.findAll(postRequestedFor(urlEqualTo("/api/" + method))
                .withHeader("Authorization", equalTo("Bearer " + botToken)))
                .stream()
                .sorted(java.util.Comparator.comparing(LoggedRequest::getLoggedDate))
                .map(request -> JSON.readTree(request.getBodyAsString()))
                .toList();
        }

        // ---- deliveries ---------------------------------------------------------------------------------------------

        /** An Events API {@code event_callback} of this workspace. */
        public Map<String, Object> envelope(Map<String, Object> event) {
            return envelopeOf(teamId, event);
        }

        public Map<String, Object> envelopeOf(String team, Map<String, Object> event) {
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("token", "unused");
            envelope.put("team_id", team);
            envelope.put("api_app_id", "A" + teamId.substring(1));
            envelope.put("event", event);
            envelope.put("type", "event_callback");
            envelope.put("event_id", "Ev" + random(10));
            envelope.put("event_time", Instant.now().getEpochSecond());
            return envelope;
        }

        /** A direct message to the bot ({@code message.im}), top-level or in a thread. */
        public Map<String, Object> directMessage(String user, String text, @Nullable String threadTs) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "message");
            event.put("channel", "D" + user);
            event.put("channel_type", "im");
            event.put("user", user);
            event.put("text", text);
            event.put("ts", ts());
            if (threadTs != null) {
                event.put("thread_ts", threadTs);
            }
            event.put("event_ts", event.get("ts"));
            return envelope(event);
        }

        /**
         * A mention of the bot in a channel ({@code app_mention}): {@code <@bot> text}, top-level or in a thread.
         */
        public Map<String, Object> mention(String user, String channel, String text, @Nullable String threadTs) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "app_mention");
            event.put("channel", channel);
            event.put("user", user);
            event.put("text", "<@" + botUserId + "> " + text);
            event.put("ts", ts());
            if (threadTs != null) {
                event.put("thread_ts", threadTs);
            }
            event.put("event_ts", event.get("ts"));
            return envelope(event);
        }

        /** The bot joined a channel ({@code member_joined_channel}). */
        public Map<String, Object> botJoined(String channel, boolean privateChannel) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "member_joined_channel");
            event.put("channel", channel);
            event.put("channel_type", privateChannel ? "G" : "C");
            event.put("user", botUserId);
            event.put("event_ts", ts());
            return envelope(event);
        }

        /** The bot left a channel ({@code member_left_channel}). */
        public Map<String, Object> botLeft(String channel) {
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("type", "member_left_channel");
            event.put("channel", channel);
            event.put("user", botUserId);
            event.put("event_ts", ts());
            return envelope(event);
        }

        /** The {@code ts} of the event of an envelope. */
        @SuppressWarnings("unchecked")
        public static String tsOf(Map<String, Object> envelope) {
            return (String) ((Map<String, Object>) envelope.get("event")).get("ts");
        }

        /** A {@code block_actions} press of a button the bot posted. */
        public Map<String, Object> press(String user, String channel, String messageTs, @Nullable String threadTs,
                String value) {
            Map<String, Object> message = new LinkedHashMap<>();
            message.put("ts", messageTs);
            if (threadTs != null) {
                message.put("thread_ts", threadTs);
            }
            Map<String, Object> interaction = new LinkedHashMap<>();
            interaction.put("type", "block_actions");
            interaction.put("team", Map.of("id", teamId, "domain", "test"));
            interaction.put("user", Map.of("id", user, "name", "user", "team_id", teamId));
            interaction.put("channel", Map.of("id", channel));
            interaction.put("container", Map.of("type", "message", "channel_id", channel, "message_ts", messageTs));
            interaction.put("message", message);
            interaction.put("actions", List.of(Map.of("action_id", "a" + random(4), "block_id", "b", "type",
                    "button", "value", value, "action_ts", ts())));
            return interaction;
        }

        /** Posts the envelope to the system's Events API endpoint, signed now. */
        public Delivery deliver(int port, UUID systemId, Map<String, Object> envelope) {
            return deliver(port, systemId, envelope, signingSecret, Instant.now(), null);
        }

        /** Posts the envelope signed with a secret at a time, with a retry number when not null. */
        public Delivery deliver(int port, UUID systemId, Map<String, Object> envelope, String secret, Instant at,
                @Nullable Integer retryNum) {
            byte[] body = JSON.writeValueAsBytes(envelope);
            return send(port, "/api/chat/slack/" + systemId + "/events", body, "application/json", secret, at,
                    retryNum);
        }

        /** Posts a press to the system's interactivity endpoint, form-encoded and signed. */
        public Delivery deliverPress(int port, UUID systemId, Map<String, Object> interaction) {
            byte[] body = ("payload=" + URLEncoder.encode(JSON.writeValueAsString(interaction),
                    StandardCharsets.UTF_8)).getBytes(StandardCharsets.UTF_8);
            return send(port, "/api/chat/slack/" + systemId + "/interactions", body,
                    "application/x-www-form-urlencoded", signingSecret, Instant.now(), null);
        }

        /** Posts raw bytes to a path, signed with the secret at the time. */
        public static Delivery send(int port, String path, byte[] body, String contentType, String secret,
                Instant at, @Nullable Integer retryNum) {
            String timestamp = Long.toString(at.getEpochSecond());
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", contentType)
                .header("X-Slack-Request-Timestamp", timestamp)
                .header("X-Slack-Signature", sign(secret, timestamp, body))
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            if (retryNum != null) {
                request.header("X-Slack-Retry-Num", retryNum.toString()).header("X-Slack-Retry-Reason",
                        "http_timeout");
            }
            long started = System.nanoTime();
            try {
                HttpResponse<String> response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
                return new Delivery(response.statusCode(), response.body(), response.headers()
                    .firstValue("X-Slack-No-Retry").orElse(null), Duration.ofNanos(System.nanoTime() - started));
            }
            catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }
    }

    /** What the server answered a delivery, and how long it took. */
    public record Delivery(int status, String body, @Nullable String noRetry, Duration took) {
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(Object body) {
        return aResponse().withStatus(200).withHeader("Content-Type", "application/json")
            .withBody(JSON.writeValueAsString(body));
    }
}
