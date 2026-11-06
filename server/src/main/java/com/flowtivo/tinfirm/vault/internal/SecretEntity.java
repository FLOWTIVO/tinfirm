package com.flowtivo.tinfirm.vault.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.jspecify.annotations.Nullable;

/**
 * Secret metadata. The encrypted values live in {@code secret_version} ({@link SecretVersionStore}). A secret with an
 * {@code ownerUserId} is a person's (ADR-0049 §5: the token of their own account on a connection): the Secrets API
 * never lists, changes or deletes it, and it is revealed only for its owner. An {@code internal} secret is one Tinfirm
 * keeps for itself (the package 2 contract, §3.2: a principal's LiteLLM key): a company secret the Secrets API never
 * lists, changes or deletes either, which only the module that keeps it reveals and deletes.
 */
@Entity
@Table(name = "secret")
class SecretEntity {

    @Id
    private UUID id;

    private UUID companyId;

    private String name;

    private @Nullable String description;

    private int currentVersion;

    private Instant createdAt;

    private Instant updatedAt;

    private @Nullable UUID createdBy;

    private @Nullable Instant lastAccessedAt;

    private @Nullable UUID ownerUserId;

    private boolean internal;

    @Version
    private @Nullable Long lockVersion;

    protected SecretEntity() {
    }

    SecretEntity(UUID id, UUID companyId, String name, @Nullable String description, UUID createdBy, Instant now) {
        this(id, companyId, name, description, createdBy, null, false, now);
    }

    /**
     * A secret of the company, with {@code ownerUserId} one that person owns, or with {@code internal} one Tinfirm
     * keeps for itself.
     */
    SecretEntity(UUID id, UUID companyId, String name, @Nullable String description, @Nullable UUID createdBy,
            @Nullable UUID ownerUserId, boolean internal, Instant now) {
        if (internal && ownerUserId != null) {
            throw new IllegalArgumentException("An internal secret is the company's, never a person's");
        }
        this.id = id;
        this.companyId = companyId;
        this.name = name;
        this.description = description;
        this.currentVersion = 1;
        this.createdAt = now;
        this.updatedAt = now;
        this.createdBy = createdBy;
        this.ownerUserId = ownerUserId;
        this.internal = internal;
    }

    /** Moves to the next version and returns its number. */
    int nextVersion(Instant now) {
        currentVersion++;
        updatedAt = now;
        return currentVersion;
    }

    void describe(@Nullable String newDescription, Instant now) {
        this.description = newDescription;
        this.updatedAt = now;
    }

    void markAccessed(Instant now) {
        this.lastAccessedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public String getName() {
        return name;
    }

    @Nullable
    public String getDescription() {
        return description;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Nullable
    public UUID getCreatedBy() {
        return createdBy;
    }

    @Nullable
    public Instant getLastAccessedAt() {
        return lastAccessedAt;
    }

    /** The person who owns the secret (ADR-0049 §5), or null for a secret of the company. */
    @Nullable
    public UUID getOwnerUserId() {
        return ownerUserId;
    }

    /** Whether a person owns the secret. */
    boolean owned() {
        return ownerUserId != null;
    }

    /** Whether Tinfirm keeps the secret for itself (the package 2 contract, §3.2), out of the Secrets API's reach. */
    public boolean isInternal() {
        return internal;
    }
}
