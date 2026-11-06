package com.flowtivo.tinfirm.system;

import java.util.Objects;

import org.jspecify.annotations.Nullable;

/**
 * The credential one tool call or change request runs with (the package 3 contract, §5.3 and §5.4): the token of the
 * account the conversation is bound to on the system, which the caller revealed for this call
 * ({@code Accounts.reveal}, audited as {@code secret.accessed} with the purpose), where the token is valid when that
 * is not the system's base URL, and the endpoint the system had when the token was revealed, which is the only one the
 * token is sent to (ADR-0079). The caller drops it after the call. Never logged: {@link #toString()} hides the token.
 *
 * @param token      the token as the system takes it. A stored token is sent as the system's scheme says (Jira Data
 *                   Center's and GitHub's as a bearer token, GitLab's in {@code PRIVATE-TOKEN}, Jira Cloud's
 *                   {@code email:api-token} as Basic authentication, an MCP server's in its {@code authHeader}); an
 *                   OAuth access token always as {@code Authorization: Bearer}. Null only to call an MCP server
 *                   without a credential; a tracker refuses that
 * @param oauth      whether the token is an OAuth access token
 * @param apiBaseUrl where the token is valid when that is not the system's base URL: Jira Cloud's gateway
 *                   {@code https://api.atlassian.com/ex/jira/{cloudId}} for an OAuth grant (the account's, from
 *                   {@code Accounts.RevealedCredential}). Ignored for every other kind: a call goes only to the
 *                   system's own origin
 * @param endpoint   the system's kind and base URL when the token was revealed: every call reads the system's row
 *                   again and sends nothing when the row's endpoint is another (an MCP server moved meanwhile,
 *                   ADR-0079), so that a token given for one address never reaches another. Null for no credential
 *                   ({@link #none}), and for a test fixture's token; a credential an account revealed is always
 *                   pinned ({@code Accounts.RevealedCredential.callCredential})
 */
public record CallCredential(@Nullable String token, boolean oauth, @Nullable String apiBaseUrl,
        @Nullable Endpoint endpoint) {

    /**
     * The endpoint a credential was revealed for: the system's kind and base URL as the caller's snapshot had them
     * (ADR-0079).
     */
    public record Endpoint(SystemKind kind, @Nullable String baseUrl) {

        /** The system's endpoint as the snapshot has it. */
        public static Endpoint of(SystemInfo system) {
            return new Endpoint(system.kind(), system.baseUrl());
        }

        /** Whether the system still has this endpoint: the same kind and base URL. */
        public boolean is(SystemKind kind, @Nullable String baseUrl) {
            return this.kind == kind && Objects.equals(this.baseUrl, baseUrl);
        }
    }

    /**
     * The credential an account revealed: its token, where it is valid when that is not the system, and the endpoint
     * the system had when it was revealed.
     */
    public static CallCredential of(SystemCalls.Credential credential, @Nullable String apiBaseUrl,
            Endpoint endpoint) {
        return new CallCredential(credential.token(), credential.oauth(), apiBaseUrl, Objects.requireNonNull(endpoint,
                "endpoint"));
    }

    /** No credential: an MCP server that needs none. Pinned to no endpoint, since there is no token to protect. */
    public static CallCredential none() {
        return new CallCredential(null, false, null, null);
    }

    @Override
    public String toString() {
        return "CallCredential[token=" + (token != null ? "***" : "none") + ", oauth=" + oauth + ", apiBaseUrl="
                + apiBaseUrl + ", endpoint=" + endpoint + "]";
    }
}
