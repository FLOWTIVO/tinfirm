package com.flowtivo.tinfirm.system.internal.oauth;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpRequest;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.flowtivo.tinfirm.system.CallResult;
import com.flowtivo.tinfirm.system.OAuthTokens;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.internal.http.Json;
import com.flowtivo.tinfirm.system.internal.http.OutboundHttp;

import tools.jackson.databind.JsonNode;

/**
 * Tinfirm's requests as an OAuth client of a system's authorization server (the package 1 contract, §3.3 and §3.8),
 * every one through the system's outbound client and its guard (the endpoint is checked like a system's base URL, and
 * the request may go to its origin only): the code exchange with the PKCE verifier (RFC 6749 §4.1.3, RFC 7636), the
 * refresh (§6), the revocation (RFC 7009), the dynamic registration (RFC 7591), and Jira Cloud's sites of a grant.
 * The token requests are form-encoded and ask for JSON ({@code Accept: application/json}, which GitHub needs); a form
 * answer is read too. A client with a secret authenticates as its profile says, a client without one sends its id.
 * An MCP server's requests carry {@code resource} (RFC 8707). Failures are values that name the status and the
 * authorization server's error code when it is one of RFC 6749's, never its description, a token or a secret.
 */
public class OAuthClient {

    private static final Logger log = LoggerFactory.getLogger(OAuthClient.class);

    /** The error codes of a token endpoint (RFC 6749 §5.2, §4.1.2.1) that a failure may name. */
    static final Set<String> ERROR_CODES = Set.of("invalid_request", "invalid_client", "invalid_grant",
            "unauthorized_client", "unsupported_grant_type", "invalid_scope", "access_denied",
            "temporarily_unavailable", "server_error");

    /**
     * The longest access or refresh token taken, in UTF-8 bytes (the security review of 2026-10-27, L14): a token is
     * kept in the vault and sent as a header on every call; 16 KiB leaves room for Entra ID access tokens that carry many
     * group claims (often 6 to 10 KiB), which 4 KiB refused.
     */
    public static final int MAX_TOKEN_BYTES = 16384;

    private final OutboundHttp http;

    public OAuthClient(OutboundHttp http) {
        this.http = http;
    }

    /**
     * The client Tinfirm is at the authorization server: its id, its secret when it has one (never logged), and how
     * the secret is sent. A client without a secret is a public client and sends its id ({@code none}).
     */
    public record Client(String clientId, @Nullable String secret, OAuthProfile.TokenAuth secretAuth) {

        public boolean confidential() {
            return secret != null;
        }

        @Override
        public String toString() {
            return "Client[clientId=" + clientId + ", secret=" + (secret != null ? "***" : "none") + "]";
        }
    }

    /** A client the authorization server registered (RFC 7591): its id, and a secret when it issued one. */
    public record Registered(String clientId, @Nullable String clientSecret) {

        @Override
        public String toString() {
            return "Registered[clientId=" + clientId + ", clientSecret=" + (clientSecret != null ? "***" : "none")
                    + "]";
        }
    }

    /** Exchanges an authorization code (RFC 6749 §4.1.3) with the PKCE verifier. */
    public CallResult<OAuthTokens> exchange(OAuthProfile profile, Client client, String code, String redirectUri,
            String codeVerifier, Instant now) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code");
        form.put("code", code);
        form.put("redirect_uri", redirectUri);
        form.put("code_verifier", codeVerifier);
        return tokens(profile, client, form, now);
    }

    /** Refreshes an access token (RFC 6749 §6); a server that rotates refresh tokens answers a new one. */
    public CallResult<OAuthTokens> refresh(OAuthProfile profile, Client client, String refreshToken, Instant now) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "refresh_token");
        form.put("refresh_token", refreshToken);
        return tokens(profile, client, form, now);
    }

    /**
     * Mints an access token with the client's own credentials (RFC 6749 §4.4), which needs a client with a secret. The
     * request names the profile's {@code audience} where it has one (Atlassian's {@code api.atlassian.com}, as its
     * authorization requests do). The answer has no refresh token: the client mints the next one the same way, so an
     * answer without {@code expires_in} is refused.
     */
    public CallResult<OAuthTokens> clientCredentials(OAuthProfile profile, Client client, Instant now) {
        if (!client.confidential()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The client credentials grant needs a client secret");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "client_credentials");
        String audience = profile.authorizeParameters().get("audience");
        if (audience != null) {
            form.put("audience", audience);
        }
        CallResult<OAuthTokens> minted = tokens(profile, client, form, now);
        OAuthTokens tokens = minted.value();
        if (tokens == null) {
            return minted;
        }
        if (tokens.expiresAt() == null) {
            // Without a refresh token, a token of no known lifetime would never be minted again.
            return CallResult.failed(CallResult.Kind.INVALID, "The token endpoint answered a client credentials "
                    + "token without expires_in");
        }
        return CallResult.ok(new OAuthTokens(tokens.accessToken(), null, tokens.tokenType(), tokens.expiresAt(),
                tokens.scope()));
    }

    private CallResult<OAuthTokens> tokens(OAuthProfile profile, Client client, Map<String, String> form,
            Instant now) {
        if (profile.resource() != null) {
            form.put("resource", profile.resource());
        }
        CallResult<OutboundHttp.Response> sent = post(profile.tokenEndpoint(), client, form);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        Map<String, String> answer = profile.kind() == SystemKind.SLACK ? slackAnswer(response) : answer(response);
        String error = answer.get("error");
        if (!response.success() || error != null) {
            return CallResult.failed(error != null ? errorKind(error, response.status())
                    : OutboundHttp.kindOf(response.status()), refusal("The token endpoint", response, answer));
        }
        String accessToken = answer.get("access_token");
        if (accessToken == null || accessToken.isBlank()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The token endpoint answered without an "
                    + "access_token");
        }
        String tokenType = answer.getOrDefault("token_type", "Bearer");
        if (!tokenType.equalsIgnoreCase("bearer")) {
            return CallResult.failed(CallResult.Kind.INVALID, "The token endpoint issued a token of another type "
                    + "than Bearer; Tinfirm uses bearer tokens only");
        }
        Instant expiresAt = expiresAt(answer.get("expires_in"), now);
        String refreshToken = answer.get("refresh_token");
        if (tooLong(accessToken) || refreshToken != null && tooLong(refreshToken)) {
            return CallResult.failed(CallResult.Kind.INVALID, "The token endpoint issued a token longer than "
                    + MAX_TOKEN_BYTES / 1024 + " KiB, which Tinfirm does not keep");
        }
        String scope = answer.get("scope");
        return CallResult.ok(new OAuthTokens(accessToken, refreshToken != null && !refreshToken.isBlank() ? refreshToken
                : null, "Bearer", expiresAt, scope != null && !scope.isBlank() ? scope.strip() : null));
    }

    private static boolean tooLong(String token) {
        return token.length() > MAX_TOKEN_BYTES || token.getBytes(StandardCharsets.UTF_8).length > MAX_TOKEN_BYTES;
    }

    /**
     * When an access token expires, from the answer's {@code expires_in}: none without one (GitHub's OAuth App tokens
     * do not expire); a lifetime that is zero, negative or not a number is taken as expired already, a second ago, so
     * that the next call refreshes the token rather than never.
     */
    static @Nullable Instant expiresAt(@Nullable String expiresIn, Instant now) {
        if (expiresIn == null) {
            return null;
        }
        long seconds;
        try {
            seconds = Long.parseLong(expiresIn.strip());
        }
        catch (NumberFormatException ex) {
            seconds = 0;
        }
        if (seconds <= 0) {
            log.info("The token endpoint named an expires_in that is no positive number of seconds; the access token "
                    + "is taken as expired");
            return now.minusSeconds(1);
        }
        return now.plusSeconds(seconds);
    }

    /** Revokes a token (RFC 7009); {@code 200} is success, whatever the token's state was. */
    public CallResult<Boolean> revoke(String revocationEndpoint, Client client, String token, String tokenTypeHint) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("token", token);
        form.put("token_type_hint", tokenTypeHint);
        CallResult<OutboundHttp.Response> sent = post(revocationEndpoint, client, form);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        return response.success() ? CallResult.ok(true)
                : CallResult.failed(OutboundHttp.kindOf(response.status()), refusal("The revocation endpoint",
                        response, answer(response)));
    }

    /**
     * Registers Tinfirm at the registration endpoint (RFC 7591) as a public client with PKCE ({@code none}), as MCP
     * clients register: its name, the callback, the authorization code and refresh token grants.
     */
    public CallResult<Registered> register(String registrationEndpoint, String redirectUri) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("client_name", "Tinfirm");
        metadata.put("redirect_uris", List.of(redirectUri));
        metadata.put("grant_types", List.of("authorization_code", "refresh_token"));
        metadata.put("response_types", List.of("code"));
        metadata.put("token_endpoint_auth_method", "none");
        HttpRequest request = http.request(registrationEndpoint)
            .POST(HttpRequest.BodyPublishers.ofString(Json.write(metadata)))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .build();
        String problem = http.urlProblem(registrationEndpoint);
        if (problem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The registration endpoint is not allowed: "
                    + problem);
        }
        CallResult<OutboundHttp.Response> sent = http.send(registrationEndpoint, request);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        Map<String, String> answer = answer(response);
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), refusal("The registration endpoint",
                    response, answer));
        }
        String clientId = answer.get("client_id");
        if (clientId == null || clientId.isBlank() || clientId.length() > 500) {
            return CallResult.failed(CallResult.Kind.INVALID, "The registration endpoint answered without a "
                    + "usable client_id");
        }
        String secret = answer.get("client_secret");
        if (secret != null && secret.length() > 2000) {
            return CallResult.failed(CallResult.Kind.INVALID, "The registration endpoint answered with a "
                    + "client_secret longer than 2000 characters");
        }
        return CallResult.ok(new Registered(clientId, secret != null && !secret.isBlank() ? secret : null));
    }

    /**
     * A Jira Cloud site an Atlassian access token may call.
     *
     * @param cloudId its cloud id, the path segment of the gateway's {@code /ex/jira/{cloudId}}
     * @param url     its own URL, e.g. {@code https://acme.atlassian.net}
     */
    public record Site(String cloudId, String url) {
    }

    /**
     * The sites the access token may call ({@code GET accessible-resources} with it as a bearer token, through the
     * guard); entries without an id or a URL are left out.
     */
    public CallResult<List<Site>> accessibleResources(String url, String accessToken) {
        String problem = http.urlProblem(url);
        if (problem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The sites' endpoint is not allowed: " + problem);
        }
        HttpRequest request = http.request(url)
            .header("Accept", "application/json")
            .header("Authorization", "Bearer " + accessToken)
            .GET()
            .build();
        CallResult<OutboundHttp.Response> sent = http.send(url, request);
        if (sent.failure() != null) {
            return sent.failedAs();
        }
        OutboundHttp.Response response = Objects.requireNonNull(sent.value());
        if (!response.success()) {
            return CallResult.failed(OutboundHttp.kindOf(response.status()), "The sites' endpoint answered "
                    + response.status());
        }
        JsonNode node = Json.parse(response.body());
        if (node == null || !node.isArray()) {
            return CallResult.failed(CallResult.Kind.INVALID, "The sites' endpoint answered without a list");
        }
        List<Site> sites = new java.util.ArrayList<>();
        for (JsonNode entry : node) {
            String cloudId = Json.text(entry, "id");
            String siteUrl = Json.text(entry, "url");
            if (cloudId != null && !cloudId.isBlank() && siteUrl != null && !siteUrl.isBlank()) {
                sites.add(new Site(cloudId.strip(), siteUrl.strip()));
            }
        }
        return CallResult.ok(sites);
    }

    // ---- requests ------------------------------------------------------------------------------------------------------

    /** A form POST to a token or revocation endpoint, authenticated as the client is. */
    private CallResult<OutboundHttp.Response> post(String endpoint, Client client, Map<String, String> form) {
        String problem = http.urlProblem(endpoint);
        if (problem != null) {
            return CallResult.failed(CallResult.Kind.INVALID, "The endpoint " + endpoint + " is not allowed: "
                    + problem);
        }
        HttpRequest.Builder request = http.request(endpoint)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Accept", "application/json");
        Map<String, String> body = new LinkedHashMap<>(form);
        if (!client.confidential()) {
            body.put("client_id", client.clientId());
        }
        else if (client.secretAuth() == OAuthProfile.TokenAuth.CLIENT_SECRET_POST) {
            body.put("client_id", client.clientId());
            body.put("client_secret", Objects.requireNonNull(client.secret()));
        }
        else {
            request.header("Authorization", "Basic " + Base64.getEncoder().encodeToString((formEncode(
                    client.clientId()) + ":" + formEncode(Objects.requireNonNull(client.secret())))
                .getBytes(StandardCharsets.UTF_8)));
        }
        String encoded = body.entrySet()
            .stream()
            .map(entry -> formEncode(entry.getKey()) + "=" + formEncode(entry.getValue()))
            .collect(Collectors.joining("&"));
        return http.send(endpoint, request.POST(HttpRequest.BodyPublishers.ofString(encoded)).build());
    }

    /** The members of an answer: a JSON object's, or a form's (GitHub's without {@code Accept}). */
    static Map<String, String> answer(OutboundHttp.Response response) {
        Map<String, String> members = new LinkedHashMap<>();
        String contentType = response.header("Content-Type").orElse("").toLowerCase(Locale.ROOT);
        if (contentType.contains("application/x-www-form-urlencoded")) {
            for (String pair : response.body().split("&")) {
                int equals = pair.indexOf('=');
                if (equals > 0) {
                    members.put(URLDecoder.decode(pair.substring(0, equals), StandardCharsets.UTF_8),
                            URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8));
                }
            }
            return members;
        }
        JsonNode node = Json.parse(response.body());
        if (node != null && node.isObject()) {
            node.properties().forEach(entry -> {
                String value = entry.getValue().isString() ? entry.getValue().stringValue()
                        : entry.getValue().isNumber() || entry.getValue().isBoolean() ? entry.getValue().asString()
                                : null;
                if (value != null) {
                    members.put(entry.getKey(), value);
                }
            });
        }
        return members;
    }

    /**
     * Slack's {@code oauth.v2.access} as the members of a token answer (the package 5 contract, open point 13). Slack
     * answers {@code 200} with {@code ok} false and its own error, taken as RFC 6749's nearest code; on {@code ok}, the
     * user token is {@code authed_user}'s, or, for a refresh, the answer's own when its {@code token_type} is
     * {@code user}. A bot's token is never taken: without a user token the answer names no {@code access_token}.
     */
    static Map<String, String> slackAnswer(OutboundHttp.Response response) {
        Map<String, String> members = new LinkedHashMap<>();
        JsonNode body = Json.parse(response.body());
        if (body == null || !body.isObject()) {
            return members;
        }
        if (!Json.flag(body, "ok")) {
            String error = Objects.requireNonNullElse(Json.text(body, "error"), "");
            members.put("error", switch (error) {
                case "invalid_code", "code_already_used", "code_expired", "invalid_refresh_token", "token_revoked",
                        "token_expired" -> "invalid_grant";
                case "invalid_client_id", "bad_client_secret", "invalid_client" -> "invalid_client";
                case "ratelimited" -> "temporarily_unavailable";
                case "internal_error", "fatal_error", "service_unavailable", "request_timeout" -> "server_error";
                default -> "invalid_request";
            });
            return members;
        }
        JsonNode user = body.get("authed_user");
        JsonNode tokens = user != null && user.isObject() && Json.text(user, "access_token") != null ? user
                : "user".equals(Json.text(body, "token_type")) ? body : null;
        if (tokens == null) {
            return members;
        }
        for (String field : List.of("access_token", "refresh_token", "expires_in", "scope")) {
            String value = Json.textOrNumber(tokens, field);
            if (value != null) {
                members.put(field, value);
            }
        }
        members.put("token_type", "Bearer");
        return members;
    }

    /**
     * Why an endpoint refused: its status and its error code as RFC 6749 §5.2 names them, anything else as
     * {@code unknown_error}. Never the {@code error_description} or another text of the answer: it ends in an
     * account's {@code last_error}, the audit log and an agent's error, and a provider might reflect a token in it.
     */
    static String refusal(String what, OutboundHttp.Response response, Map<String, String> answer) {
        String error = answer.get("error");
        StringBuilder reason = new StringBuilder(what).append(" answered ").append(response.status());
        if (error != null) {
            reason.append(" (").append(ERROR_CODES.contains(error) ? error : "unknown_error").append(')');
        }
        return reason.toString();
    }

    /**
     * The failure kind of an OAuth error (RFC 6749 §5.2): a refused grant or client is the token's or the client's
     * fault and does not pass; {@code temporarily_unavailable} and a 5xx may.
     */
    private static CallResult.Kind errorKind(String error, int status) {
        return switch (error) {
            case "invalid_grant", "invalid_client", "unauthorized_client", "access_denied" -> CallResult.Kind.UNAUTHORIZED;
            case "temporarily_unavailable", "server_error" -> CallResult.Kind.UNAVAILABLE;
            default -> status >= 500 ? CallResult.Kind.UNAVAILABLE : CallResult.Kind.INVALID;
        };
    }

    private static String formEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
