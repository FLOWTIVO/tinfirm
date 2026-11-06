package com.flowtivo.tinfirm.system.internal.oauth;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the OAuth discovery of MCP servers ({@link OAuthDiscovery}).
 *
 * @param allowedEndpointOrigins the origins ({@code https://host[:port]}, no path) on which an authorization server
 *                               may name its authorization, token, revocation and registration endpoints although they
 *                               are not its issuer's ({@code TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS}, empty by
 *                               default): the operator's list of split-hosted providers, such as Google's
 *                               {@code https://oauth2.googleapis.com} for the issuer {@code https://accounts.google.com}.
 *                               Every other endpoint must share the issuer's scheme, host and port (the security review
 *                               of 2026-10-27, M8)
 */
@ConfigurationProperties("tinfirm.oauth.discovery")
public record OAuthDiscoveryProperties(@DefaultValue List<String> allowedEndpointOrigins) {

    public OAuthDiscoveryProperties {
        List<String> origins = new java.util.ArrayList<>();
        for (String entry : allowedEndpointOrigins) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String origin = OAuthDiscovery.origin(entry.strip());
            if (origin == null || !isBareOrigin(entry.strip())) {
                throw new IllegalArgumentException("TINFIRM_OAUTH_DISCOVERY_ALLOWEDENDPOINTORIGINS names origins only, "
                        + "separated by commas, as https://host or https://host:port without a path, not " + entry);
            }
            origins.add(origin);
        }
        allowedEndpointOrigins = List.copyOf(origins);
    }

    /** The allowed origins, normalized as {@link OAuthDiscovery#origin} makes them. */
    public Set<String> origins() {
        return Set.copyOf(new LinkedHashSet<>(allowedEndpointOrigins));
    }

    private static boolean isBareOrigin(String value) {
        try {
            URI uri = new URI(value);
            @Nullable String path = uri.getRawPath();
            return uri.getRawUserInfo() == null && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (path == null || path.isEmpty() || "/".equals(path));
        }
        catch (URISyntaxException ex) {
            return false;
        }
    }
}
