package com.flowtivo.tinfirm.system.internal.teams;

import java.net.http.HttpRequest;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.ChatCalls;
import com.flowtivo.tinfirm.system.internal.http.ChatEndpoints;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

import tools.jackson.databind.JsonNode;

/**
 * The Bot Framework (the package 5 contract, §5.1, §5.2, §5.9): the bot's {@code client_credentials} token from
 * Microsoft's login for the system's tenant, the connector at an activity's {@code serviceUrl} (which must be on the
 * system's base URL's origin, threat model 11.9), and the signing keys its OpenID metadata names. A token is kept per
 * system and password until a minute before it expires; a {@code 401} of the connector drops it, so that the next call
 * asks for a new one.
 */
public final class BotFramework {

    private static final Duration EARLY = Duration.ofMinutes(1);

    /** Tokens by system and the hash of the password, never the password. */
    private static final Map<String, Token> TOKENS = new ConcurrentHashMap<>();

    private record Token(String value, Instant expiresAt) {
    }

    private final OutboundHttp http;

    private final ChatEndpoints endpoints;

    /** The system's base URL, the connector's origin. */
    private final String serviceBase;

    private final String systemKey;

    public BotFramework(OutboundHttp http, ChatEndpoints endpoints, String serviceBase, String systemKey) {
        this.http = http;
        this.endpoints = endpoints;
        this.serviceBase = serviceBase;
        this.systemKey = systemKey;
    }

    /** A token of the bot, asked for when none is kept or it expires within a minute. */
    public CallResult<Instant> check(ChatCalls.TeamsBot bot) {
        return token(bot, true).map(Token::expiresAt);
    }

    private CallResult<Token> token(ChatCalls.TeamsBot bot, boolean fresh) {
        String key = systemKey + ":" + bot.appId() + ":" + hash(bot.password());
        Token kept = TOKENS.get(key);
        if (!fresh && kept != null && kept.expiresAt().minus(EARLY).isAfter(Instant.now())) {
            return CallResult.ok(kept);
        }
        String login = endpoints.teamsLogin().endsWith("/") ? endpoints.teamsLogin()
                .substring(0, endpoints.teamsLogin().length() - 1) : endpoints.teamsLogin();
        String url = login + "/" + Json.encode(bot.tenantId()) + "/oauth2/v2.0/token";
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        form.put("client_id", bot.appId());
        form.put("client_secret", bot.password());
        form.put("scope", endpoints.teamsScope());
        HttpRequest request = http.request(url)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(form.entrySet()
                .stream()
                .map(entry -> Json.encode(entry.getKey()) + "=" + Json.encode(entry.getValue()))
                .collect(Collectors.joining("&"))))
            .build();
        CallResult<OutboundHttp.Response> sent = http.send(endpoints.teamsLogin(), request);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        JsonNode body = Json.parse(response.body());
        if (!response.success()) {
            String error = body != null ? Json.text(body, "error") : null;
            CallResult.Kind kind = response.status() == 400 || response.status() == 401
                    ? CallResult.Kind.UNAUTHORIZED : OutboundHttp.kindOf(response.status());
            return CallResult.failed(kind, "Microsoft's login refused the bot's app password ("
                    + (error != null ? Json.cut(error, 100) : response.status()) + ")");
        }
        String value = Json.text(body, "access_token");
        if (value == null) {
            return CallResult.failed(CallResult.Kind.INVALID, "Microsoft's login answered no token");
        }
        int expiresIn = Json.integer(body, "expires_in", 3600);
        Token token = new Token(value, Instant.now().plusSeconds(Math.max(60, expiresIn)));
        TOKENS.put(key, token);
        return CallResult.ok(token);
    }

    /** Posts an activity: its id. */
    public CallResult<String> post(ChatCalls.TeamsBot bot, String serviceUrl, String conversationId,
            Map<String, Object> activity) {
        return send(bot, "POST", serviceUrl, "/v3/conversations/" + Json.encode(conversationId) + "/activities",
                activity).map(body -> Objects.requireNonNullElse(Json.text(body, "id"), ""));
    }

    public CallResult<Boolean> update(ChatCalls.TeamsBot bot, String serviceUrl, String conversationId,
            String activityId, Map<String, Object> activity) {
        return send(bot, "PUT", serviceUrl, "/v3/conversations/" + Json.encode(conversationId) + "/activities/"
                + Json.encode(activityId), activity).map(body -> true);
    }

    public CallResult<ChatCalls.TeamsMember> member(ChatCalls.TeamsBot bot, String serviceUrl, String conversationId,
            String userId) {
        return send(bot, "GET", serviceUrl, "/v3/conversations/" + Json.encode(conversationId) + "/members/"
                + Json.encode(userId), null)
            .map(body -> new ChatCalls.TeamsMember(Objects.requireNonNullElse(Json.text(body, "id"), userId),
                    Json.text(body, "name"), Json.text(body, "aadObjectId"), Json.text(body, "userRole"),
                    Json.text(body, "tenantId")));
    }

    public CallResult<String> openDirect(ChatCalls.TeamsBot bot, String serviceUrl, String userId) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("bot", Map.of("id", "28:" + bot.appId()));
        body.put("members", List.of(Map.of("id", userId)));
        body.put("channelData", Map.of("tenant", Map.of("id", bot.tenantId())));
        body.put("isGroup", false);
        body.put("tenantId", bot.tenantId());
        return send(bot, "POST", serviceUrl, "/v3/conversations", body)
            .map(answer -> Objects.requireNonNullElse(Json.text(answer, "id"), ""));
    }

    /** The JWK set the OpenID metadata names, as JSON; the keys' URL must be on the metadata's origin. */
    public CallResult<String> keys() {
        HttpRequest metadataRequest = http.request(endpoints.teamsOpenId()).header("Accept", "application/json")
            .GET().build();
        CallResult<OutboundHttp.Response> metadata = http.send(endpoints.teamsOpenId(), metadataRequest);
        if (metadata.failure() != null) {
            return metadata.failedAs();
        }
        if (!Objects.requireNonNull(metadata.value()).success()) {
            return CallResult.failed(OutboundHttp.kindOf(metadata.value().status()), "The Bot Framework's OpenID "
                    + "metadata answered " + metadata.value().status());
        }
        String jwksUri = Json.text(Json.parse(metadata.value().body()), "jwks_uri");
        String origin = ChatEndpoints.origin(jwksUri);
        if (jwksUri == null || origin == null || !origin.equals(ChatEndpoints.origin(endpoints.teamsOpenId()))) {
            return CallResult.failed(CallResult.Kind.INVALID, "The Bot Framework's OpenID metadata names keys "
                    + "elsewhere");
        }
        HttpRequest keysRequest = http.request(jwksUri).header("Accept", "application/json").GET().build();
        CallResult<OutboundHttp.Response> keys = http.send(endpoints.teamsOpenId(), keysRequest);
        if (keys.failure() != null) {
            return keys.failedAs();
        }
        if (!Objects.requireNonNull(keys.value()).success()) {
            return CallResult.failed(OutboundHttp.kindOf(keys.value().status()), "The Bot Framework's keys answered "
                    + keys.value().status());
        }
        return CallResult.ok(keys.value().body());
    }

    /**
     * A connector call with the bot's token, to {@code serviceUrl} on the system's origin; a {@code 401} drops the
     * kept token and asks for a new one once.
     */
    private CallResult<JsonNode> send(ChatCalls.TeamsBot bot, String method, String serviceUrl, String path,
            @Nullable Map<String, Object> body) {
        String origin = ChatEndpoints.origin(serviceUrl);
        if (origin == null || !origin.equals(ChatEndpoints.origin(serviceBase))) {
            return CallResult.failed(CallResult.Kind.INVALID, "The activity's serviceUrl is not the system's");
        }
        String base = serviceUrl.endsWith("/") ? serviceUrl.substring(0, serviceUrl.length() - 1) : serviceUrl;
        for (int attempt = 0; attempt < 2; attempt++) {
            CallResult<Token> token = token(bot, attempt > 0);
            if (token.failure() != null) {
                return token.failedAs();
            }
            HttpRequest.Builder builder;
            try {
                builder = http.request(base + path)
                    .header("Authorization", "Bearer " + Objects.requireNonNull(token.value()).value())
                    .header("Accept", "application/json");
            }
            catch (IllegalArgumentException ex) {
                return CallResult.failed(CallResult.Kind.INVALID, "The connector's URL is not usable");
            }
            HttpRequest request = body == null ? builder.GET().build()
                    : builder.header("Content-Type", "application/json")
                        .method(method, HttpRequest.BodyPublishers.ofString(Json.write(body)))
                        .build();
            CallResult<OutboundHttp.Response> sent = http.send(serviceBase, request);
            if (sent.failure() != null) {
                return sent.failedAs();
            }
            OutboundHttp.Response response = Objects.requireNonNull(sent.value());
            if (response.status() == 401 && attempt == 0) {
                continue;
            }
            if (response.status() == 429) {
                Duration wait = response.header("Retry-After").map(OutboundHttp::retryAfter)
                    .orElse(Duration.ofSeconds(1));
                return CallResult.failed(new CallResult.Failure(CallResult.Kind.RATE_LIMITED, "The Bot Framework "
                        + "is rate limited", wait));
            }
            if (!response.success()) {
                return CallResult.failed(OutboundHttp.kindOf(response.status()), "The Bot Framework answered "
                        + response.status());
            }
            JsonNode answer = Json.parse(response.body());
            return CallResult.ok(answer != null ? answer : Json.tree(Map.of()));
        }
        return CallResult.failed(CallResult.Kind.UNAUTHORIZED, "The Bot Framework refused the bot's token");
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
