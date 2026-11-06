package com.flowtivo.tinfirm.shared.security;

import java.io.Serial;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.security.authentication.AbstractAuthenticationToken;

/**
 * A request of a company's SCIM client, authenticated by one of the company's SCIM tokens (ADR-0087, decision 3). Per
 * request, never stored. Audited with actor {@code SCIM} and the token's id; it is no user, so {@link CurrentActor#user}
 * is empty for it and no company path of the API admits it.
 */
public final class ScimAuthentication extends AbstractAuthenticationToken {

    @Serial
    private static final long serialVersionUID = 1L;

    private final UUID tokenId;

    private final UUID companyId;

    public ScimAuthentication(UUID tokenId, UUID companyId) {
        super(List.of());
        this.tokenId = tokenId;
        this.companyId = companyId;
        setAuthenticated(true);
    }

    public UUID tokenId() {
        return tokenId;
    }

    /** The company the token names: the only one whose members its requests see. */
    public UUID companyId() {
        return companyId;
    }

    @Override
    public String getPrincipal() {
        return "scim:" + tokenId;
    }

    @Override
    public @Nullable Object getCredentials() {
        return null;
    }
}
