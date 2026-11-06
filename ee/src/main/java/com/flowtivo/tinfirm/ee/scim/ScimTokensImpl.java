package com.flowtivo.tinfirm.ee.scim;

import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.scim.ScimSettings;
import com.flowtivo.tinfirm.company.scim.ScimTokenFormat;
import com.flowtivo.tinfirm.company.scim.ScimTokenStore;
import com.flowtivo.tinfirm.company.scim.ScimTokens;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.api.model.ScimToken;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreated;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * Issues a company's SCIM token (ADR-0087, decision 3), Enterprise's: {@code tfs_} and 43 base64url characters,
 * shown once, kept as its SHA-256 only, at most two active per company so that the OWNER rotates by overlap. The
 * caller authorised the OWNER and holds the company's lock.
 */
@Component
class ScimTokensImpl implements ScimTokens {

    private static final int MAX_NAME = 100;

    private final ScimTokenStore store;

    private final ScimSettings settings;

    private final Licensing licensing;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final UserDirectory users;

    ScimTokensImpl(ScimTokenStore store, ScimSettings settings, Licensing licensing, AuditLog auditLog,
            TimeSource time, UserDirectory users) {
        this.store = store;
        this.settings = settings;
        this.licensing = licensing;
        this.auditLog = auditLog;
        this.time = time;
        this.users = users;
    }

    @Override
    public ScimTokenCreated issue(UUID companyId, ScimTokenCreateRequest request) {
        licensing.require(Feature.SCIM);
        if (!settings.of(companyId).enabled()) {
            throw ApiException.conflict(ProblemCode.SCIM_NOT_ENABLED, "The deployment does not allow SCIM for this "
                    + "company: its operator sets companies[].scim.enabled (TINFIRM_COMPANIES_<KEY>_SCIM_ENABLED)");
        }
        String name = request.getName() != null ? request.getName().strip() : "";
        if (name.isEmpty() || name.length() > MAX_NAME) {
            throw ApiException.validation("name must be 1 to " + MAX_NAME + " characters");
        }
        Instant now = time.now();
        Instant expiresAt = request.getExpiresAt() != null ? request.getExpiresAt().toInstant() : null;
        if (expiresAt != null && !expiresAt.isAfter(now)) {
            throw ApiException.validation("expiresAt must be in the future");
        }
        if (store.countActive(companyId, now) >= ScimTokenStore.MAX_ACTIVE) {
            throw ApiException.conflict(ProblemCode.SCIM_TOKEN_LIMIT, "The company holds " + ScimTokenStore.MAX_ACTIVE
                    + " active SCIM tokens: revoke the old one once the identity provider holds the new one");
        }
        UUID createdBy = CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
        String secret = ScimTokenFormat.generate();
        ScimTokenStore.TokenRow token = new ScimTokenStore.TokenRow(Ids.newId(), companyId, name, createdBy, now,
                expiresAt, null, null);
        store.insert(token, ScimTokenFormat.hash(secret));
        auditLog.record(AuditRecord.company(companyId, AuditAction.SCIM_TOKEN_CREATED, CurrentActor.actor())
            .target("scim_token", token.id())
            .detail("tokenId", token.id().toString())
            .detail("name", name)
            .detail("expiresAt", expiresAt != null ? expiresAt.toString() : null)
            .build());
        UserSummary creator = createdBy != null ? users.findById(createdBy).orElse(null) : null;
        ScimToken view = new ScimToken(token.id(), name, now.atOffset(java.time.ZoneOffset.UTC))
            .createdBy(createdBy)
            .createdByName(creator != null ? creator.displayName() : null)
            .expiresAt(expiresAt != null ? expiresAt.atOffset(java.time.ZoneOffset.UTC) : null);
        return new ScimTokenCreated(view, secret);
    }
}
