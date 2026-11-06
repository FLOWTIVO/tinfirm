package com.flowtivo.tinfirm.identity.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.security.ApiScope;

/** A personal access token of a user (ADR-0045): its metadata and the hash of the token, never the token. */
@Entity
@Table(name = "api_token")
class ApiTokenEntity {

    @Id
    private UUID id;

    private UUID userId;

    private String name;

    @Enumerated(EnumType.STRING)
    private ApiScope scope;

    private String tokenHash;

    private Instant createdAt;

    private @Nullable Instant expiresAt;

    /** The companies the token reaches; null for every company of the user (CONVENTIONS §5). */
    @JdbcTypeCode(SqlTypes.ARRAY)
    private UUID @Nullable [] companyIds;

    private @Nullable Instant lastUsedAt;

    private @Nullable Instant revokedAt;

    protected ApiTokenEntity() {
    }

    ApiTokenEntity(UUID id, UUID userId, String name, ApiScope scope, String tokenHash, Instant createdAt,
            @Nullable Instant expiresAt, @Nullable Set<UUID> companyIds) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.scope = scope;
        this.tokenHash = tokenHash;
        this.createdAt = createdAt;
        this.expiresAt = expiresAt;
        this.companyIds = companyIds == null ? null : companyIds.stream().sorted().toArray(UUID[]::new);
    }

    /** Whether the token reaches the company: it names it, or names none (every company of the user). */
    boolean reaches(UUID companyId) {
        return companyIds == null || Arrays.asList(companyIds).contains(companyId);
    }

    /** Whether the token names the company in its list; a token without a list names none. */
    boolean names(UUID companyId) {
        return companyIds != null && Arrays.asList(companyIds).contains(companyId);
    }

    /** A new expiry, for a renewal. */
    void renew(Instant expiresAt) {
        this.expiresAt = expiresAt;
    }

    boolean isRevoked() {
        return revokedAt != null;
    }

    boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    /** Neither revoked nor expired. */
    boolean isActive(Instant now) {
        return !isRevoked() && !isExpired(now);
    }

    void revoke(Instant now) {
        if (revokedAt == null) {
            revokedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public ApiScope getScope() {
        return scope;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getExpiresAt() {
        return expiresAt;
    }

    /** The companies the token reaches, sorted; null for every company of the user. */
    public @Nullable List<UUID> getCompanyIds() {
        return companyIds == null ? null : List.of(companyIds);
    }

    public @Nullable Instant getLastUsedAt() {
        return lastUsedAt;
    }

    public @Nullable Instant getRevokedAt() {
        return revokedAt;
    }

    @Override
    public String toString() {
        return "ApiTokenEntity[id=" + id + ", scope=" + scope + "]";
    }
}
