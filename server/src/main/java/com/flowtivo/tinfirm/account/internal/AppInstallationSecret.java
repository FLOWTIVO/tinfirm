package com.flowtivo.tinfirm.account.internal;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jspecify.annotations.Nullable;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The secret of a credential of kind {@code APP} (ADR-0116, decision 7), an installation of a {@code GITHUB} system's
 * GitHub App: {@code {"installationId", "accessToken", "expiresAt"}}, the token the server minted last for its own
 * calls and when it expires. A run's own tokens are never kept here. Never log it; {@link #toString()} does not show
 * the token.
 *
 * @param accessToken the token minted last for the server's own calls; null before the first one
 */
record AppInstallationSecret(long installationId, @Nullable String accessToken, @Nullable Instant expiresAt) {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    /** An owner and a repository's name on GitHub, as a clone URL's path has them. */
    private static final Pattern OWNER_REPO = Pattern
        .compile("^/([A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?)/([A-Za-z0-9._-]+?)(?:\\.git)?/?$");

    String json() {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("installationId", installationId);
        if (accessToken != null) {
            value.put("accessToken", accessToken);
            value.put("expiresAt", expiresAt != null ? expiresAt.toString() : null);
        }
        return JSON.writeValueAsString(value);
    }

    /** The secret's value read; null when it is not an installation's. */
    static @Nullable AppInstallationSecret fromJson(String json) {
        JsonNode node;
        try {
            node = JSON.readTree(json);
        }
        catch (JacksonException ex) {
            return null;
        }
        JsonNode id = node != null ? node.get("installationId") : null;
        if (id == null || !id.isIntegralNumber() || id.longValue() <= 0) {
            return null;
        }
        JsonNode token = node.get("accessToken");
        JsonNode expires = node.get("expiresAt");
        Instant expiresAt = null;
        if (expires != null && expires.isString()) {
            try {
                expiresAt = Instant.parse(expires.stringValue());
            }
            catch (DateTimeParseException ex) {
                expiresAt = null;
            }
        }
        return new AppInstallationSecret(id.longValue(), token != null && token.isString() ? token.stringValue()
                : null, expiresAt);
    }

    /** The same installation with the token just minted. */
    AppInstallationSecret minted(String token, Instant expires) {
        return new AppInstallationSecret(installationId, token, expires);
    }

    /** Whether the token minted last is usable beyond {@code margin} from {@code now}. */
    boolean usable(Instant now, Duration margin) {
        return accessToken != null && expiresAt != null && expiresAt.isAfter(now.plus(margin));
    }

    /** {@code installation:{id}}, the credential's identity on the system. */
    static String externalId(long installationId) {
        return "installation:" + installationId;
    }

    /** A clone URL's owner and repository name, {@code https://{host}/{owner}/{repo}(.git)}; null otherwise. */
    static String @Nullable [] ownerAndName(String url) {
        try {
            String path = new URI(url.strip()).getPath();
            Matcher matcher = path != null ? OWNER_REPO.matcher(path) : null;
            if (matcher == null || !matcher.matches() || matcher.group(2).equals(".")
                    || matcher.group(2).equals("..")) {
                return null;
            }
            return new String[] { matcher.group(1), matcher.group(2) };
        }
        catch (URISyntaxException ex) {
            return null;
        }
    }

    @Override
    public String toString() {
        return "AppInstallationSecret[installationId=" + installationId + ", accessToken=***, expiresAt=" + expiresAt
                + "]";
    }
}
