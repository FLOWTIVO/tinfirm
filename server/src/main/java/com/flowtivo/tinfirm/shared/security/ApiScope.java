package com.flowtivo.tinfirm.shared.security;

/**
 * The scope of a bearer credential on the MCP endpoint (ADR-0045): what a personal access token or an OAuth grant
 * lets a person's client do. Each level includes the lower ones. A scope never adds to the member's role: the
 * company's access guard still decides every operation.
 */
public enum ApiScope {

    /** Read tools only. */
    READ("tinfirm:read"),
    /** Read and write tools (comment, start a conversation or thread, cancel, retry), but no decision. */
    WRITE("tinfirm:write"),
    /** Everything WRITE allows, and deciding decisions. */
    DECIDE("tinfirm:decide");

    private final String oauthScope;

    ApiScope(String oauthScope) {
        this.oauthScope = oauthScope;
    }

    /** The OAuth scope name, e.g. {@code tinfirm:read}. */
    public String oauthScope() {
        return oauthScope;
    }

    /** Whether this scope allows what {@code required} allows. */
    public boolean includes(ApiScope required) {
        return compareTo(required) >= 0;
    }
}
