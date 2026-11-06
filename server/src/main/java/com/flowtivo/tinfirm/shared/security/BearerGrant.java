package com.flowtivo.tinfirm.shared.security;

import java.util.UUID;

/**
 * The bearer credential that authenticated the current request on the MCP endpoint (ADR-0045), or the Gmail add-on's
 * link of the Google account that Google called it for (ADR-0083): which credential, and with which scope. The user is
 * the request's {@link ActorAuthentication}, as for a browser session.
 * <p>
 * Audited actions done through a bearer credential put {@link #auditDetailKey()} with {@link #id()} into their
 * {@code details}, so the audit chain shows which actions came from a token or a grant rather than from the web app.
 *
 * @param kind  the kind of credential
 * @param id    the personal access token's id, the OAuth authorization's id, or the Gmail link's id
 * @param scope what the credential allows
 */
public record BearerGrant(Kind kind, UUID id, ApiScope scope) {

    public enum Kind {
        /** A personal access token ({@code tfp_...}) created on {@code /me}. */
        PERSONAL_ACCESS_TOKEN,
        /** An OAuth 2.1 access token issued by Tinfirm's authorization server. */
        OAUTH_GRANT,
        /** A Google account's link to its person, through which the Gmail add-on acts (ADR-0083). */
        GMAIL_LINK
    }

    /** {@code tokenId} for a personal access token, {@code grantId} for an OAuth grant, {@code gmailLinkId}. */
    public String auditDetailKey() {
        return switch (kind) {
            case PERSONAL_ACCESS_TOKEN -> "tokenId";
            case OAUTH_GRANT -> "grantId";
            case GMAIL_LINK -> "gmailLinkId";
        };
    }

    /** Whether the credential allows what {@code required} allows. */
    public boolean allows(ApiScope required) {
        return scope.includes(required);
    }
}
