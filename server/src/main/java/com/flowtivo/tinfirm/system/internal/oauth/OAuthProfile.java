package com.flowtivo.tinfirm.system.internal.oauth;

import java.net.URI;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.system.SystemKind;

/**
 * How Tinfirm is an OAuth client of one system's authorization server (the package 1 contract, §3.3: the system's
 * app): the endpoints, the default scopes, the extra parameters of the authorization request, how a client with a
 * secret authenticates at the token endpoint, and the resource indicator. A tracker's profile is fixed per kind and
 * derived from the system's base URL ({@link #tracker}); Slack's is its OAuth v2 with the app's user scopes
 * ({@link #slack}); an MCP server's comes from its discovery ({@link #mcp}). Jira Cloud asks for {@code read:me}, so
 * that Atlassian's {@code /me} names the account.
 * <p>
 * Every authorization request carries PKCE with {@code S256}: Jira Data Center and GitLab check it, GitHub accepts it,
 * Atlassian's 3LO ignores it; an MCP server's authorization server must list {@code S256} or the system offers no
 * OAuth ({@link OAuthDiscovery}).
 *
 * @param revocationEndpoint where a disconnect revokes the grant (RFC 7009); null when the system has none
 * @param defaultScopes      the scopes asked for when the app names none, space-separated; null: none
 * @param resource           the resource indicator (RFC 8707) of the authorization and token requests: an MCP
 *                           server's endpoint; null for a tracker
 */
public record OAuthProfile(SystemKind kind, String authorizationEndpoint, String tokenEndpoint,
        @Nullable String revocationEndpoint, @Nullable String defaultScopes, Map<String, String> authorizeParameters,
        TokenAuth secretAuth, @Nullable String resource) {

    /** Atlassian's 3LO authorization server, the same for every Jira Cloud site. */
    public static final String ATLASSIAN_AUTHORIZE = "https://auth.atlassian.com/authorize";

    public static final String ATLASSIAN_TOKEN = "https://auth.atlassian.com/oauth/token";

    /** The Atlassian account of an access token (its {@code account_id}, {@code name} and {@code nickname}). */
    public static final String ATLASSIAN_ME = "https://api.atlassian.com/me";

    /** The Jira Cloud sites an access token may call, with their cloud ids ({@code id}, {@code url}). */
    public static final String ATLASSIAN_RESOURCES = "https://api.atlassian.com/oauth/token/accessible-resources";

    /** Atlassian's gateway: an access token calls a site's REST API at {@code {gateway}{cloudId}}. */
    public static final String ATLASSIAN_GATEWAY = "https://api.atlassian.com/ex/jira/";

    /** Slack's user scopes for {@code history} (the package 5 contract, open point 13), as its app asks for them. */
    public static final String SLACK_USER_SCOPES = "channels:history,groups:history";

    /** How a client that has a secret authenticates at the token and revocation endpoints (RFC 6749 §2.3.1). */
    public enum TokenAuth {
        /** {@code Authorization: Basic} with the form-encoded id and secret. */
        CLIENT_SECRET_BASIC,
        /** {@code client_id} and {@code client_secret} in the form body. */
        CLIENT_SECRET_POST
    }

    public OAuthProfile {
        // In their order, so the authorization URL is the same every time.
        authorizeParameters = Collections.unmodifiableMap(new LinkedHashMap<>(authorizeParameters));
    }

    /**
     * The fixed profile of a tracker, from the system's base URL: Jira Data Center's OAuth 2.0 provider at the Jira
     * root; Atlassian's 3LO for Jira Cloud; GitHub's OAuth application on the web host of the API ({@code github.com}
     * for {@code api.github.com}, else the host of {@code https://<host>/api/v3}); GitLab's application at the
     * instance.
     *
     * @throws IllegalArgumentException for a kind without a tracker profile
     */
    public static OAuthProfile tracker(SystemKind kind, String baseUrl) {
        String base = trimmed(baseUrl);
        return switch (kind) {
            case JIRA_DC -> new OAuthProfile(kind, base + "/rest/oauth2/latest/authorize",
                    base + "/rest/oauth2/latest/token", null, "WRITE", Map.of(), TokenAuth.CLIENT_SECRET_BASIC, null);
            case JIRA_CLOUD -> {
                Map<String, String> parameters = new LinkedHashMap<>();
                parameters.put("audience", "api.atlassian.com");
                parameters.put("prompt", "consent");
                yield new OAuthProfile(kind, ATLASSIAN_AUTHORIZE, ATLASSIAN_TOKEN, null,
                        "read:jira-work read:jira-user write:jira-work offline_access read:me", parameters,
                        TokenAuth.CLIENT_SECRET_POST, null);
            }
            case GITHUB -> {
                String web = githubWebOrigin(base);
                yield new OAuthProfile(kind, web + "/login/oauth/authorize", web + "/login/oauth/access_token", null,
                        "repo", Map.of(), TokenAuth.CLIENT_SECRET_POST, null);
            }
            case GITLAB -> new OAuthProfile(kind, base + "/oauth/authorize", base + "/oauth/token",
                    base + "/oauth/revoke", "api", Map.of(), TokenAuth.CLIENT_SECRET_BASIC, null);
            default -> throw new IllegalArgumentException("A " + kind + " system has no fixed OAuth profile");
        };
    }

    /**
     * Slack's OAuth v2 for a {@code SLACK} system's app (the package 5 contract, open point 13), from the system's base
     * URL, Slack's Web API root ({@code https://slack.com/api}): the authorization page at its web origin
     * ({@code https://slack.com/oauth/v2/authorize}), which takes the user scopes as {@code user_scope}
     * ({@link #userScopes}), {@code oauth.v2.access} as the token endpoint, whose user token is {@code authed_user}'s,
     * and {@code auth.revoke}. The scopes are fixed, {@value #SLACK_USER_SCOPES}; no bot scope is asked for here.
     */
    public static OAuthProfile slack(String baseUrl) {
        String base = trimmed(baseUrl);
        URI uri = URI.create(base);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
        String web = scheme + "://" + Objects.requireNonNull(uri.getRawAuthority(), "the base URL has no host");
        return new OAuthProfile(SystemKind.SLACK, web + "/oauth/v2/authorize", base + "/oauth.v2.access",
                base + "/auth.revoke", SLACK_USER_SCOPES, Map.of(), TokenAuth.CLIENT_SECRET_POST, null);
    }

    /** Whether the authorization request names its scopes as Slack's {@code user_scope}, not {@code scope}. */
    public boolean userScopes() {
        return kind == SystemKind.SLACK;
    }

    /**
     * An MCP server's profile, from its discovery (RFC 9728, RFC 8414): the scopes are the authorization server's
     * {@code scopes_supported}, the resource is the endpoint, and a client with a secret authenticates with
     * {@code client_secret_basic}, the default of RFC 6749 and RFC 8414.
     */
    public static OAuthProfile mcp(String endpoint, String authorizationEndpoint, String tokenEndpoint,
            @Nullable String revocationEndpoint, @Nullable String scopesSupported) {
        return new OAuthProfile(SystemKind.MCP, authorizationEndpoint, tokenEndpoint, revocationEndpoint,
                scopesSupported, Map.of(), TokenAuth.CLIENT_SECRET_BASIC, trimmed(endpoint));
    }

    /**
     * The origin of GitHub's web pages for an API base URL: {@code https://github.com} for
     * {@code https://api.github.com}, else the scheme, host and port of {@code https://<host>/api/v3}.
     */
    static String githubWebOrigin(String apiBaseUrl) {
        URI uri = URI.create(apiBaseUrl);
        String host = Objects.requireNonNull(uri.getHost(), "the base URL has no host").toLowerCase(Locale.ROOT);
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase(Locale.ROOT) : "https";
        if (host.equals("api.github.com")) {
            return "https://github.com";
        }
        return scheme + "://" + uri.getRawAuthority();
    }

    static String trimmed(String url) {
        String trimmed = url.strip();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }
}
