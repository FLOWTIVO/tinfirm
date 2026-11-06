package com.flowtivo.tinfirm.company.internal;

import java.io.Serializable;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.access.RoleSource;

@Entity
@Table(name = "company_membership")
@IdClass(MembershipEntity.Key.class)
class MembershipEntity {

    @Id
    private UUID companyId;

    @Id
    private UUID userId;

    @Enumerated(EnumType.STRING)
    private CompanyRole role;

    /** {@link RoleSource}'s stored form (ADR-0088). */
    private String roleSource = RoleSource.APP.value();

    private Instant createdAt;

    private Instant updatedAt;

    /** The identity provider's stable id of the person, set by the company's SCIM (ADR-0087, decision 4). */
    private @Nullable String scimExternalId;

    /** SCIM's suspension, kept apart from an admin's (ADR-0087, decision 7). */
    private @Nullable Instant scimSuspendedAt;

    @Version
    private @Nullable Long version;

    protected MembershipEntity() {
    }

    MembershipEntity(UUID companyId, UUID userId, CompanyRole role, Instant now) {
        this(companyId, userId, role, RoleSource.APP, now);
    }

    MembershipEntity(UUID companyId, UUID userId, CompanyRole role, RoleSource source, Instant now) {
        this.companyId = companyId;
        this.userId = userId;
        this.role = role;
        this.roleSource = ownersSource(role, source).value();
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** A change of the role by the app or the deployment's declared owner: the role becomes the app's. */
    void changeRole(CompanyRole newRole, Instant now) {
        changeRole(newRole, RoleSource.APP, now);
    }

    /** A change of the role and of who decides it; an OWNER's is always the app's (ADR-0088 decision 4). */
    void changeRole(CompanyRole newRole, RoleSource source, Instant now) {
        this.role = newRole;
        this.roleSource = ownersSource(newRole, source).value();
        this.updatedAt = now;
    }

    void scimExternalId(@Nullable String externalId, Instant now) {
        this.scimExternalId = externalId;
        this.updatedAt = now;
    }

    void scimSuspended(boolean suspended, Instant now) {
        this.scimSuspendedAt = suspended ? (scimSuspendedAt != null ? scimSuspendedAt : now) : null;
        this.updatedAt = now;
    }

    public @Nullable String getScimExternalId() {
        return scimExternalId;
    }

    public @Nullable Instant getScimSuspendedAt() {
        return scimSuspendedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private static RoleSource ownersSource(CompanyRole role, RoleSource source) {
        return role == CompanyRole.OWNER ? RoleSource.APP : source;
    }

    public UUID getCompanyId() {
        return companyId;
    }

    public UUID getUserId() {
        return userId;
    }

    public CompanyRole getRole() {
        return role;
    }

    public RoleSource getRoleSource() {
        return RoleSource.parse(roleSource);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    record Key(UUID companyId, UUID userId) implements Serializable {
    }
}
