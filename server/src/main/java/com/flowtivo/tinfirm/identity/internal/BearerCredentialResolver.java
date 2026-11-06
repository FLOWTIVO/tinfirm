package com.flowtivo.tinfirm.identity.internal;

import java.util.Optional;

/**
 * Resolves the credentials of an {@code Authorization: Bearer} header on the MCP endpoint (ADR-0045) to a user and a
 * grant. This is the extension point of the bearer chain ({@link BearerSecurityConfiguration}): the chain asks every
 * resolver bean, in {@link org.springframework.core.annotation.Order} order, and the first one that
 * {@linkplain #supports supports} the token's format decides alone.
 * <p>
 * The personal access token resolver ({@link PersonalAccessTokenResolver}, order 0) supports {@code tfp_} tokens.
 * {@link OAuthAccessTokenResolver}, ordered after it, resolves the JWT access tokens of Tinfirm's authorization
 * server: it supports a token shaped as a JWT, validates signature, issuer, expiry and the audience (the company
 * endpoint), and maps the grant's user and scope to a {@link ResolvedBearer} of kind
 * {@link com.flowtivo.tinfirm.shared.security.BearerGrant.Kind#OAUTH_GRANT}.
 */
interface BearerCredentialResolver {

    /** Whether the token has this resolver's format; decided from the token alone, without I/O. */
    boolean supports(String token);

    /**
     * The user and grant of a supported token; empty when the token is unknown, malformed, revoked or expired, or its
     * user is deactivated. The caller answers empty with 401 {@code TOKEN_INVALID}.
     */
    Optional<ResolvedBearer> resolve(String token);
}
