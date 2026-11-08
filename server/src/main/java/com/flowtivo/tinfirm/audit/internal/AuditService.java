package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditReads;
import com.flowtivo.tinfirm.audit.AuditTargetNames;
import com.flowtivo.tinfirm.audit.AuditTrail;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.AuditAnchorStatus;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;

@Service
class AuditService implements AuditTrail, AuditReads {

    private final AuditEventStore store;

    private final CompanyAccessGuard companyAccess;

    private final AuditPages pages;

    private final ObjectProvider<AuditTools> tools;

    private final ObjectProvider<AuditAnchors> anchors;

    AuditService(AuditEventStore store, CompanyAccessGuard companyAccess, AuditPages pages,
            ObjectProvider<AuditTools> tools, ObjectProvider<AuditAnchors> anchors) {
        this.store = store;
        this.companyAccess = companyAccess;
        this.pages = pages;
        this.tools = tools;
        this.anchors = anchors;
    }

    /**
     * The company's events, newest first, filtered (the package 6 contract, §5.8; CONVENTIONS §15.11). The filters are
     * the audit's tools ({@link AuditTools}).
     */
    @Transactional(readOnly = true)
    public AuditEventPage companyEvents(UUID companyId, @Nullable Integer limit, @Nullable String cursor,
            AuditQuery query) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_AUDIT);
        // ADR-0077, decision 4: the audit is recorded and shown in every edition; its filters are Pro's and
        // Enterprise's, and so is its history beyond the last 90 days.
        if (!query.equals(AuditQuery.ALL)) {
            return tools().companyEvents(companyId, limit, cursor, query, pages.reader(companyId, access));
        }
        PageRequest request = PageRequest.of(limit, cursor);
        return page(companyId, request, pages.reader(companyId, access));
    }

    @Transactional(readOnly = true)
    public AuditVerification verifyCompanyChain(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.VIEW_AUDIT);
        return tools().verify(companyId);
    }

    /** The export of a range of the company's chain (the package 6 contract, §5.8), resolved here, streamed later. */
    @Transactional(readOnly = true)
    public AuditTools.Export export(UUID companyId, @Nullable Long fromSeq, @Nullable Long toSeq,
            @Nullable Instant from, @Nullable Instant to) {
        companyAccess.require(companyId, CompanyPermission.VIEW_AUDIT);
        return tools().export(companyId, fromSeq, toSeq, from, to);
    }

    @Transactional(readOnly = true)
    public AuditCheckPage companyChecks(UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        companyAccess.require(companyId, CompanyPermission.VIEW_AUDIT);
        return tools().checks(companyId, limit, cursor);
    }

    @Transactional
    public AuditCheck acknowledgeCompanyCheck(UUID companyId, UUID checkId, String note) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        return tools().acknowledge(companyId, checkId, note);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditEventPage instanceEvents(@Nullable Integer limit, @Nullable String cursor) {
        AuditTargetNames.Reader reader = pages.instanceReader();
        return page(null, PageRequest.of(limit, cursor), reader);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditVerification verifyInstanceChain() {
        CurrentActor.requireInstanceAdmin();
        return tools().verify(null);
    }

    @Override
    @Transactional(readOnly = true)
    public AuditCheckPage instanceChecks(@Nullable Integer limit, @Nullable String cursor) {
        CurrentActor.requireInstanceAdmin();
        return tools().checks(null, limit, cursor);
    }

    @Override
    @Transactional
    public AuditCheck acknowledgeInstanceCheck(UUID checkId, String note) {
        CurrentActor.requireInstanceAdmin();
        return tools().acknowledge(null, checkId, note);
    }

    /**
     * The anchor's status (ADR-0111), under {@code ee/}; without it the server answers as Community,
     * {@code 403 EDITION_REQUIRED}, once the caller is known to be an instance admin.
     */
    @Override
    @Transactional(readOnly = true)
    public AuditAnchorStatus auditAnchorStatus() {
        CurrentActor.requireInstanceAdmin();
        AuditAnchors found = anchors.getIfAvailable();
        if (found == null) {
            throw Licensing.editionRequired(Feature.AUDIT_ANCHOR);
        }
        return found.status();
    }

    @Override
    @Transactional(readOnly = true)
    public List<ConversationReader> conversationReads(UUID companyId, UUID conversationId) {
        return store.conversationReaders(companyId, conversationId, MAX_READERS).stream()
            .map(row -> new ConversationReader(row.userId(), row.first(), row.last(), row.reads(),
                    row.kinds() == null || row.kinds().isEmpty() ? List.of() : List.of(row.kinds().split(","))))
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public long webhookRefusals(UUID companyId, UUID systemId, Instant since) {
        return store.webhookRefusals(companyId, systemId, since);
    }

    /** A page of the chain within the history shown, without filters: what every edition shows. */
    private AuditEventPage page(@Nullable UUID companyId, PageRequest request, AuditTargetNames.Reader reader) {
        Long beforeSeq = request.firstPage() ? null : request.longKey(0, 1);
        Instant since = pages.withinHistory(AuditQuery.ALL).from();
        return pages.page(request, store.newestFirst(companyId, since, beforeSeq, request.fetchSize()), reader);
    }

    /**
     * The audit's tools, under {@code ee/} (ADR-0077); without them the server answers as Community,
     * {@code 403 EDITION_REQUIRED}, once the caller's access is checked.
     */
    private AuditTools tools() {
        AuditTools found = tools.getIfAvailable();
        if (found == null) {
            throw Licensing.editionRequired(Feature.AUDIT_TOOLS);
        }
        return found;
    }
}
