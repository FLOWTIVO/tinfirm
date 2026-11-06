package com.flowtivo.tinfirm.system;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * An OAuth client's own credentials for the {@code client_credentials} grant (RFC 6749 §4.4): an Atlassian service
 * account's OAuth 2.0 credential on Jira Cloud, which a credential of the system holds ({@code CLIENT}). The client
 * mints its access tokens itself, without a sign-in and without a refresh token; the last one it minted is kept beside
 * it until it expires. As an account's secret keeps them:
 * {@code {"clientId","clientSecret","accessToken","tokenType","expiresAt","scope"}}, the token's members those of
 * {@link OAuthTokens}. Never log them; {@link #toString()} does not show them.
 *
 * @param tokens the access token minted last; null before the first one
 */
public record ClientCredentials(String clientId, String clientSecret, @Nullable OAuthTokens tokens) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The longest client id taken. */
    public static final int MAX_CLIENT_ID = 500;

    /** The longest client secret taken. */
    public static final int MAX_CLIENT_SECRET = 2000;

    /** The JSON object of the account's secret. */
    public String json() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("clientId", clientId);
        value.put("clientSecret", clientSecret);
        if (tokens != null) {
            value.put("accessToken", tokens.accessToken());
            value.put("tokenType", tokens.tokenType());
            value.put("expiresAt", tokens.expiresAt() != null ? tokens.expiresAt().toString() : null);
            value.put("scope", tokens.scope());
        }
        return JSON.writeValueAsString(value);
    }

    /** The client credentials of an account's secret; null when it holds none. */
    public static @Nullable ClientCredentials fromJson(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        }
        catch (JacksonException ex) {
            return null;
        }
        if (node == null || !node.isObject()) {
            return null;
        }
        String clientId = text(node, "clientId");
        String clientSecret = text(node, "clientSecret");
        if (clientId == null || clientId.isBlank() || clientSecret == null || clientSecret.isBlank()) {
            return null;
        }
        return new ClientCredentials(clientId, clientSecret, OAuthTokens.fromJson(json));
    }

    /** The same client with the access token it has just minted. */
    public ClientCredentials minted(OAuthTokens fresh) {
        return new ClientCredentials(clientId, clientSecret, fresh);
    }

    private static @Nullable String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isString() ? value.stringValue() : null;
    }

    @Override
    public String toString() {
        return "ClientCredentials[clientId=" + clientId + ", clientSecret=***, tokens=" + tokens + "]";
    }
}
