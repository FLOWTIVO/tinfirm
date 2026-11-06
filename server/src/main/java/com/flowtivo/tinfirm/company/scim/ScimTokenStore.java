package com.flowtivo.tinfirm.company.scim;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;

/**
 * A company's SCIM tokens ({@code scim_token}, V062; ADR-0087, decision 3): resolved by their hash before any tenant
 * is known, listed, issued and revoked in the company's tenant context. Every edition reads and revokes them; issuing
 * is Enterprise's ({@link ScimTokens}). Never a token itself, only its hash.
 */
public interface ScimTokenStore {

    /** A company holds at most this many active tokens, so that one rotates by overlap (decision 3). */
    int MAX_ACTIVE = 2;

    /** The token whose hash this is, across companies; outside any tenant context. */
    Optional<Resolved> resolve(String tokenHash);

    List<TokenRow> list(UUID companyId, int limit);

    Optional<TokenRow> find(UUID companyId, UUID tokenId);

    /** The company's tokens neither revoked nor expired; the caller holds the company's lock. */
    int countActive(UUID companyId, Instant now);

    void insert(TokenRow token, String tokenHash);

    /** Revokes the token unless it is revoked already; whether this revoked it. */
    boolean revoke(UUID companyId, UUID tokenId, Instant now, @Nullable UUID by);

    /** {@code last_used_at}, written at most once a minute per token, so that a request is not a write. */
    void touch(UUID companyId, UUID tokenId, Instant now);

    /** A token as the store keeps it, never its hash. */
    record TokenRow(UUID id, UUID companyId, String name, @Nullable UUID createdBy, Instant createdAt,
            @Nullable Instant expiresAt, @Nullable Instant lastUsedAt, @Nullable Instant revokedAt) {

        public boolean activeAt(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
    }

    /** What the hash of a presented token names: its id, company and standing. */
    record Resolved(UUID id, UUID companyId, @Nullable Instant expiresAt, @Nullable Instant revokedAt) {

        public boolean activeAt(Instant now) {
            return revokedAt == null && (expiresAt == null || expiresAt.isAfter(now));
        }
    }
}
