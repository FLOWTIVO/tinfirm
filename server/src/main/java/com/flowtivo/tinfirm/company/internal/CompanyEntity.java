package com.flowtivo.tinfirm.company.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "company")
class CompanyEntity {

    @Id
    private UUID id;

    private String name;

    /** The key the deployment declares the company by (ADR-0075); null for a company from before keys. */
    private @Nullable String key;

    private Instant createdAt;

    private Instant updatedAt;

    @Version
    private @Nullable Long version;

    protected CompanyEntity() {
    }

    CompanyEntity(UUID id, String name, @Nullable String key, Instant now) {
        this.id = id;
        this.name = name;
        this.key = key;
        this.createdAt = now;
        this.updatedAt = now;
    }

    void rename(String newName, Instant now) {
        this.name = newName;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
