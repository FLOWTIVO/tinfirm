package com.flowtivo.tinfirm.company.internal;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.scim.ScimTokenStore;
import com.flowtivo.tinfirm.company.scim.ScimSettings;
import com.flowtivo.tinfirm.company.scim.ScimException;
import com.flowtivo.tinfirm.company.scim.ScimTokens;
import com.flowtivo.tinfirm.company.scim.ScimDirectory;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.identity.UserDirectory;
import com.flowtivo.tinfirm.identity.UserSummary;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Role;
import com.flowtivo.tinfirm.shared.api.model.ScimStatus;
import com.flowtivo.tinfirm.shared.api.model.ScimToken;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenCreated;
import com.flowtivo.tinfirm.shared.api.model.ScimTokenList;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A company's SCIM in the app (ADR-0087, decisions 3 and 11): its status and its tokens, read by OWNER and ADMIN
 * ({@code VIEW_PROVISIONING}), revoked by the OWNER ({@code MANAGE_PROVISIONING}) in every edition, so that a token can
 * be stopped after the licence ended; issuing one is Enterprise's ({@link ScimTokens}, under {@code ee/}).
 */
@Service
class ScimTokenService {

    /** The list is not paged: a company holds two active tokens at most, and sees its newest 200. */
    private static final int LIST_LIMIT = 200;

    private final ScimTokenStore store;

    private final ScimSettings settings;

    private final ObjectProvider<ScimTokens> issuing;

    private final CompanyAccessGuard companyAccess;

    private final CompanyQueries queries;

    private final UserDirectory users;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final TinfirmProperties tinfirm;

    private final ObjectProvider<ScimDirectory> directory;

    ScimTokenService(ScimTokenStore store, ScimSettings settings, ObjectProvider<ScimTokens> issuing,
            CompanyAccessGuard companyAccess, CompanyQueries queries, UserDirectory users, AuditLog auditLog,
            TimeSource time, TinfirmProperties tinfirm, ObjectProvider<ScimDirectory> directory) {
        this.store = store;
        this.settings = settings;
        this.issuing = issuing;
        this.companyAccess = companyAccess;
        this.queries = queries;
        this.users = users;
        this.auditLog = auditLog;
        this.time = time;
        this.tinfirm = tinfirm;
        this.directory = directory;
    }

    @Transactional(readOnly = true)
    public ScimStatus status(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.VIEW_PROVISIONING);
        ScimSettings.Settings scim = settings.of(companyId);
        return new ScimStatus(scim.enabled(), licensed(), baseUrl(tinfirm), Role.fromValue(scim.role().name()),
                ScimStatus.RoleSourceEnum.fromValue(scim.roleSource().name()));
    }

    @Transactional(readOnly = true)
    public ScimTokenList list(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.VIEW_PROVISIONING);
        List<ScimTokenStore.TokenRow> rows = store.list(companyId, LIST_LIMIT);
        Map<UUID, UserSummary> creators = users.findAllById(rows.stream()
            .map(ScimTokenStore.TokenRow::createdBy)
            .filter(Objects::nonNull)
            .distinct()
            .toList());
        return new ScimTokenList(rows.stream().map(row -> view(row, creators.get(row.createdBy()))).toList());
    }

    @Transactional
    public ScimTokenCreated issue(UUID companyId, ScimTokenCreateRequest request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_PROVISIONING);
        ScimTokens tokens = issuing.getIfAvailable();
        if (tokens == null) {
            throw Licensing.editionRequired(Feature.SCIM);
        }
        queries.lockCompany(companyId);
        return tokens.issue(companyId, request);
    }

    @Transactional
    public void revoke(UUID companyId, UUID tokenId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_PROVISIONING);
        ScimTokenStore.TokenRow token = store.find(companyId, tokenId)
            .orElseThrow(() -> ApiException.notFound("No SCIM token with this id"));
        UUID by = CurrentActor.user().map(AuthenticatedUser::id).orElse(null);
        if (store.revoke(companyId, tokenId, time.now(), by)) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.SCIM_TOKEN_REVOKED, CurrentActor.actor())
                .target("scim_token", tokenId)
                .detail("tokenId", tokenId.toString())
                .detail("name", token.name())
                .build());
        }
    }

    private boolean licensed() {
        ScimDirectory found = directory.getIfAvailable();
        if (found == null) {
            return false;
        }
        try {
            found.requireServed();
            return true;
        }
        catch (ScimException ex) {
            return false;
        }
    }

    /** {@code {TINFIRM_PUBLIC_URL}/scim/v2}, the one base URL of the instance (decision 1). */
    static String baseUrl(TinfirmProperties tinfirm) {
        String url = tinfirm.publicUrl().toString();
        return (url.endsWith("/") ? url.substring(0, url.length() - 1) : url) + "/scim/v2";
    }

    static ScimToken view(ScimTokenStore.TokenRow row, UserSummary creator) {
        return new ScimToken(row.id(), row.name(), row.createdAt().atOffset(java.time.ZoneOffset.UTC))
            .createdBy(row.createdBy())
            .createdByName(creator != null ? creator.displayName() : null)
            .expiresAt(row.expiresAt() != null ? row.expiresAt().atOffset(java.time.ZoneOffset.UTC) : null)
            .lastUsedAt(row.lastUsedAt() != null ? row.lastUsedAt().atOffset(java.time.ZoneOffset.UTC) : null)
            .revokedAt(row.revokedAt() != null ? row.revokedAt().atOffset(java.time.ZoneOffset.UTC) : null);
    }
}
