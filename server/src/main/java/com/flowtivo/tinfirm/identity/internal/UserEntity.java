package com.flowtivo.tinfirm.identity.internal;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.jspecify.annotations.Nullable;

import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;

/**
 * A user of the instance. Local users have a password hash; OIDC users an (issuer, subject) identity; SAML users the
 * (IdP's entity ID, subject) in the same columns (ADR-0086).
 */
@Entity
@Table(name = "app_user")
class UserEntity {

    @Id
    private UUID id;

    private String email;

    private String displayName;

    @Enumerated(EnumType.STRING)
    private AuthSource authSource;

    private @Nullable String passwordHash;

    private @Nullable String oidcIssuer;

    private @Nullable String oidcSubject;

    /**
     * The Entra ID tenant and object of the last login through an Entra v2.0 issuer, read from the ID token itself
     * (the package 5 contract, §3.8); null otherwise. Teams links a chat user automatically by these two only.
     */
    private @Nullable String oidcTenantId;

    private @Nullable String oidcObjectId;

    private boolean instanceAdmin;

    /**
     * The user's standing as a whole: false while an instance admin's deactivation ({@code adminDeactivated}) or
     * SCIM's ({@code scimDeactivatedAt}) holds (ADR-0087, decision 7; {@code app_user_active_ck}).
     */
    private boolean active;

    private boolean adminDeactivated;

    private @Nullable Instant scimDeactivatedAt;

    /** The company whose SCIM created or bound the user (ADR-0087, decision 7); null for none. */
    private @Nullable UUID scimCompanyId;

    private Instant createdAt;

    private Instant updatedAt;

    private @Nullable Instant lastLoginAt;

    @Version
    private @Nullable Long version;

    protected UserEntity() {
    }

    private UserEntity(UUID id, String email, String displayName, AuthSource authSource, boolean instanceAdmin,
            Instant now) {
        this.id = id;
        this.email = email;
        this.displayName = displayName;
        this.authSource = authSource;
        this.instanceAdmin = instanceAdmin;
        this.active = true;
        this.createdAt = now;
        this.updatedAt = now;
    }

    static UserEntity local(UUID id, String email, String displayName, String passwordHash, boolean instanceAdmin,
            Instant now) {
        UserEntity user = new UserEntity(id, email, displayName, AuthSource.LOCAL, instanceAdmin, now);
        user.passwordHash = passwordHash;
        return user;
    }

    static UserEntity oidc(UUID id, String email, String displayName, String issuer, String subject, Instant now) {
        UserEntity user = new UserEntity(id, email, displayName, AuthSource.OIDC, false, now);
        user.oidcIssuer = issuer;
        user.oidcSubject = subject;
        return user;
    }

    /** A user a SAML provider signed in (ADR-0086): identified by (the IdP's entity ID, the subject). */
    static UserEntity saml(UUID id, String email, String displayName, String entityId, String subject, Instant now) {
        UserEntity user = new UserEntity(id, email, displayName, AuthSource.SAML, false, now);
        user.oidcIssuer = entityId;
        user.oidcSubject = subject;
        return user;
    }

    /** A user a company's SCIM client creates (ADR-0087): no password, no identity until their first sign-in. */
    static UserEntity scim(UUID id, String email, String displayName, UUID companyId, Instant now) {
        UserEntity user = new UserEntity(id, email, displayName, AuthSource.SCIM, false, now);
        user.scimCompanyId = companyId;
        return user;
    }

    boolean isLocal() {
        return authSource == AuthSource.LOCAL;
    }

    void changePassword(String newPasswordHash, Instant now) {
        if (!isLocal()) {
            throw new IllegalStateException("Only local users have a password");
        }
        this.passwordHash = newPasswordHash;
        this.updatedAt = now;
    }

    void rename(String newDisplayName, Instant now) {
        this.displayName = newDisplayName;
        this.updatedAt = now;
    }

    void grantInstanceAdmin(boolean admin, Instant now) {
        this.instanceAdmin = admin;
        this.updatedAt = now;
    }

    /** An instance admin's deactivation or reactivation; SCIM's stays as it is. */
    void activate(boolean newActive, Instant now) {
        this.adminDeactivated = !newActive;
        this.active = !adminDeactivated && scimDeactivatedAt == null;
        this.updatedAt = now;
    }

    /** SCIM's deactivation or its lifting (ADR-0087, decision 7); an instance admin's stays as it is. */
    void scimActivate(boolean newActive, Instant now) {
        this.scimDeactivatedAt = newActive ? null : (scimDeactivatedAt != null ? scimDeactivatedAt : now);
        this.active = !adminDeactivated && scimDeactivatedAt == null;
        this.updatedAt = now;
    }

    /** Whether an instance admin has not deactivated the user, whatever SCIM did. */
    boolean isAdminActive() {
        return !adminDeactivated;
    }

    @Nullable Instant getScimDeactivatedAt() {
        return scimDeactivatedAt;
    }

    @Nullable UUID getScimCompanyId() {
        return scimCompanyId;
    }

    void changeEmail(String newEmail, Instant now) {
        this.email = newEmail;
        this.updatedAt = now;
    }

    /**
     * Binds a SCIM user's first sign-in through an OIDC provider of the company that created them (ADR-0087, decision
     * 6): from now on they are identified by this (issuer, subject).
     */
    void bindOidc(String issuer, String subject, Instant now) {
        bind(AuthSource.OIDC, issuer, subject, now);
    }

    /** As {@link #bindOidc}, through a SAML provider: the (IdP's entity ID, subject) from now on (ADR-0086). */
    void bindSaml(String entityId, String subject, Instant now) {
        bind(AuthSource.SAML, entityId, subject, now);
    }

    private void bind(AuthSource source, String issuer, String subject, Instant now) {
        if (authSource != AuthSource.SCIM) {
            throw new IllegalStateException("Only a SCIM user without an identity is bound");
        }
        this.authSource = source;
        this.oidcIssuer = issuer;
        this.oidcSubject = subject;
        this.updatedAt = now;
    }

    /** The user's last change. */
    Instant getUpdatedAt() {
        return updatedAt;
    }

    void recordLogin(Instant now) {
        this.lastLoginAt = now;
    }

    /** The Entra tenant and object of this login; both null for a login that names none. */
    void recordEntra(@Nullable String tenantId, @Nullable String objectId) {
        this.oidcTenantId = tenantId != null && objectId != null ? tenantId : null;
        this.oidcObjectId = tenantId != null && objectId != null ? objectId : null;
    }

    @Nullable String getOidcTenantId() {
        return oidcTenantId;
    }

    @Nullable String getOidcObjectId() {
        return oidcObjectId;
    }

    AuthenticatedUser toAuthenticatedUser() {
        return new AuthenticatedUser(id, email, displayName, instanceAdmin);
    }

    UserSummary toSummary() {
        return new UserSummary(id, email, displayName, instanceAdmin, active);
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public AuthSource getAuthSource() {
        return authSource;
    }

    @Nullable
    String getPasswordHash() {
        return passwordHash;
    }

    public boolean isInstanceAdmin() {
        return instanceAdmin;
    }

    public boolean isActive() {
        return active;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public @Nullable Instant getLastLoginAt() {
        return lastLoginAt;
    }

    @Override
    public String toString() {
        return "UserEntity[id=" + id + ", authSource=" + authSource + "]";
    }
}
