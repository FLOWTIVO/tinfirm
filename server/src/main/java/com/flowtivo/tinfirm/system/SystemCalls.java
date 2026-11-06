package com.flowtivo.tinfirm.system;

import java.util.Optional;

import org.jspecify.annotations.Nullable;

/**
 * Tinfirm's calls to a system for the account module (the package 1 contract, §3.6 and §6): who a credential is
 * there, and the OAuth flows of the system's app, whose client secret never leaves this module. Every call goes
 * through the outbound client and its guard: https unless the operator allows http, no redirects, never to loopback,
 * link-local or private addresses unless the operator allows them for company systems, and never for a personal MCP
 * server, whatever the instance allows (the security review, row 12). Results are values, never exceptions, and never
 * hold a credential. A call to the system ({@link #whoAmI}, {@link #exchange}, {@link #refresh}, {@link #revoke}) runs
 * outside any database transaction, in the caller's tenant context: it reads the system, and the app's client secret,
 * in a short transaction of its own, and the caller reads before it and writes its outcome after it in transactions of
 * their own. Inside a transaction it throws {@link IllegalStateException} (CONVENTIONS §10).
 */
public interface SystemCalls {

    /**
     * A credential to call the system with: a stored token (in the system's header: an MCP server's own, GitLab's
     * {@code PRIVATE-TOKEN}, Jira Cloud's {@code email:token} as Basic authentication), or an OAuth access token,
     * always {@code Authorization: Bearer}. Never logged.
     */
    record Credential(String token, boolean oauth) {

        @Override
        public String toString() {
            return "Credential[token=***, oauth=" + oauth + "]";
        }
    }

    /**
     * Who a credential is on the system, from its "who am I".
     *
     * @param externalId a stable id: Jira's user key, the Atlassian account id, GitHub's or GitLab's numeric id; null
     *                   where the system has no "who am I" (MCP, GIT)
     * @param login      a readable login or name; null where the system has none
     * @param apiBaseUrl where an OAuth grant calls the system when that is not its base URL (Jira Cloud's gateway,
     *                   {@code https://api.atlassian.com/ex/jira/{cloudId}}); null otherwise
     */
    record Identity(@Nullable String externalId, @Nullable String login, @Nullable String apiBaseUrl) {
    }

    /**
     * Checks the credential with the system: its "who am I" for the trackers and code hosts, an {@code initialize}
     * for an MCP server (refused when it answers 401 or 403); a {@code GIT} system has nothing to ask, so its token is
     * taken as it is. Fails {@code UNAUTHORIZED} when the system refuses the credential.
     */
    CallResult<Identity> whoAmI(SystemInfo system, Credential credential);

    /**
     * Mints an access token with the client's own credentials ({@code client_credentials}, RFC 6749 §4.4): on Jira
     * Cloud, an Atlassian service account's OAuth 2.0 credential at Atlassian's token endpoint. The system's app plays
     * no part. Fails {@code UNAUTHORIZED} when the authorization server refuses the client, {@code INVALID} for a
     * system of another kind ({@link SystemKind#clientCredentials}).
     */
    CallResult<OAuthTokens> clientToken(SystemInfo system, ClientCredentials client);

    /**
     * Who a client's access token is on Jira Cloud: the site's cloud id from its {@code /_edge/tenant_info}, then
     * {@code myself} through Atlassian's gateway, which proves that the token reaches the site. The identity's
     * {@code apiBaseUrl} is the gateway, {@code https://api.atlassian.com/ex/jira/{cloudId}}.
     */
    CallResult<Identity> clientIdentity(SystemInfo system, String accessToken);

    /** Whether accounts on the system may sign in with OAuth: its kind takes OAuth and it has an app. */
    boolean oauthAvailable(SystemInfo system);

    /**
     * The URL the browser is sent to for a sign-in with the system's app, with the {@code state} and the PKCE
     * {@code S256} challenge; the redirect URI is Tinfirm's one callback ({@code /api/oauth/callback}). Empty when
     * the system has no app.
     */
    Optional<String> authorizationUrl(SystemInfo system, String state, String codeChallenge);

    /** Exchanges the callback's code with the PKCE verifier at the app's token endpoint. */
    CallResult<OAuthTokens> exchange(SystemInfo system, String code, String codeVerifier);

    /** Refreshes an access token; a server that rotates refresh tokens answers a new one. */
    CallResult<OAuthTokens> refresh(SystemInfo system, String refreshToken);

    /**
     * Revokes the grant where the system offers revocation (RFC 7009): its refresh token, else its access token.
     * {@code ok(false)} when the system offers none, so there was nothing to call.
     */
    CallResult<Boolean> revoke(SystemInfo system, OAuthTokens tokens);
}
