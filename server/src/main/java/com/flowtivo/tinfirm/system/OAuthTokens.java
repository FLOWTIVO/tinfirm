package com.flowtivo.tinfirm.system;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The tokens of an OAuth grant, as an account's secret keeps them:
 * {@code {"accessToken","refreshToken","tokenType","expiresAt","scope"}}. Never log them; {@link #toString()} does not
 * show them.
 *
 * @param expiresAt when the access token expires; null when the server named no lifetime
 * @param scope     the granted scopes, space-separated, when the server named them
 */
public record OAuthTokens(String accessToken, @Nullable String refreshToken, String tokenType,
        @Nullable Instant expiresAt, @Nullable String scope) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** The JSON object of the account's secret. */
    public String json() {
        Map<String, Object> grant = new LinkedHashMap<>();
        grant.put("accessToken", accessToken);
        grant.put("refreshToken", refreshToken);
        grant.put("tokenType", tokenType);
        grant.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
        grant.put("scope", scope);
        return JSON.writeValueAsString(grant);
    }

    /** The grant of an account's secret; null when it is not one. */
    public static @Nullable OAuthTokens fromJson(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        }
        catch (JacksonException ex) {
            return null;
        }
        String accessToken = text(node, "accessToken");
        if (node == null || !node.isObject() || accessToken == null || accessToken.isBlank()) {
            return null;
        }
        Instant expires = null;
        String expiresAt = text(node, "expiresAt");
        if (expiresAt != null) {
            try {
                expires = Instant.parse(expiresAt);
            }
            catch (DateTimeParseException ex) {
                expires = null;
            }
        }
        String tokenType = text(node, "tokenType");
        return new OAuthTokens(accessToken, text(node, "refreshToken"), tokenType != null ? tokenType : "Bearer",
                expires, text(node, "scope"));
    }

    /**
     * The tokens after a refresh: the new access token, the new refresh token where the server rotated it (else this
     * one), the scope it named (else this one).
     */
    public OAuthTokens refreshedBy(OAuthTokens fresh) {
        return new OAuthTokens(fresh.accessToken(), fresh.refreshToken() != null ? fresh.refreshToken() : refreshToken,
                fresh.tokenType(), fresh.expiresAt(), fresh.scope() != null ? fresh.scope() : scope);
    }

    /** Whether the access token expired, or does within the margin. */
    public boolean expiresWithin(Instant now, java.time.Duration margin) {
        return expiresAt != null && !expiresAt.isAfter(now.plus(margin));
    }

    private static @Nullable String text(@Nullable JsonNode node, String field) {
        JsonNode value = node != null ? node.get(field) : null;
        return value != null && value.isString() ? value.stringValue() : null;
    }

    @Override
    public String toString() {
        return "OAuthTokens[accessToken=***, refreshToken=" + (refreshToken != null ? "***" : "none") + ", tokenType="
                + tokenType + ", expiresAt=" + expiresAt + ", scope=" + scope + "]";
    }
}
