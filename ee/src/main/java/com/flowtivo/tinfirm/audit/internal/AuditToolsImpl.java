package com.flowtivo.tinfirm.audit.internal;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditConversationKeys;
import com.flowtivo.tinfirm.audit.AuditPrincipals;
import com.flowtivo.tinfirm.audit.AuditTargetNames;
import com.flowtivo.tinfirm.shared.api.model.AuditCheck;
import com.flowtivo.tinfirm.shared.api.model.AuditCheckPage;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.api.model.AuditVerification;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * The audit's tools (ADR-0077, decision 4, {@code AUDIT_TOOLS}), each in force while the licence holds them: the
 * filters, a chain's verification on demand ({@link AuditChainVerifier}), the export ({@link AuditExport}, which the
 * licence's expiry leaves for 90 days) and the daily checks' results ({@link AuditCheckResults}).
 */
@Component
class AuditToolsImpl implements AuditTools {

    private final AuditFilteredEvents events;

    private final AuditPages pages;

    private final AuditChainVerifier verifier;

    private final AuditExport export;

    private final AuditCheckResults checks;

    private final ObjectProvider<AuditPrincipals> principals;

    private final ObjectProvider<AuditConversationKeys> conversationKeys;

    private final Licensing licensing;

    AuditToolsImpl(AuditFilteredEvents events, AuditPages pages, AuditChainVerifier verifier, AuditExport export,
            AuditCheckResults checks, ObjectProvider<AuditPrincipals> principals,
            ObjectProvider<AuditConversationKeys> conversationKeys, Licensing licensing) {
        this.events = events;
        this.pages = pages;
        this.verifier = verifier;
        this.export = export;
        this.checks = checks;
        this.principals = principals;
        this.conversationKeys = conversationKeys;
        this.licensing = licensing;
    }

    /**
     * The filters (the package 6 contract, §5.8; CONVENTIONS §15.11): a conversation's key is resolved to its id in the
     * company, and a principal's member to their user, before the chain is read; an unknown key matches nothing.
     */
    @Override
    public AuditEventPage companyEvents(UUID companyId, @Nullable Integer limit, @Nullable String cursor,
            AuditQuery query, AuditTargetNames.Reader reader) {
        licensing.require(Feature.AUDIT_TOOLS);
        if (query.actorType() != null && !Arrays.asList("USER", "SYSTEM", "AGENT").contains(query.actorType())) {
            throw ApiException.validation("actorType must be USER, SYSTEM or AGENT");
        }
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            throw ApiException.validation("from must be before to");
        }
        query = pages.withinHistory(query);
        if (query.from() != null && query.to() != null && !query.from().isBefore(query.to())) {
            // A range wholly before the history shown: nothing of it is shown.
            return new AuditEventPage(List.of());
        }
        PageRequest request = PageRequest.of(limit, cursor);
        if (query.conversationKey() != null) {
            String key = query.conversationKey().strip().toLowerCase(Locale.ROOT);
            AuditConversationKeys keys = conversationKeys.getIfAvailable();
            Optional<UUID> id = key.isEmpty() || keys == null ? Optional.empty() : keys.idOfKey(companyId, key);
            if (id.isEmpty() || query.conversationId() != null && !query.conversationId().equals(id.get())) {
                return new AuditEventPage(List.of());
            }
            query = query.withConversation(id.get());
        }
        UUID principalUser = null;
        if (query.principalId() != null) {
            AuditPrincipals directory = principals.getIfAvailable();
            AuditPrincipals.Principal principal = directory == null ? null
                    : directory.find(companyId, List.of(query.principalId())).get(query.principalId());
            principalUser = principal != null ? principal.userId() : null;
        }
        Long beforeSeq = request.firstPage() ? null : request.longKey(0, 1);
        return pages.page(request, events.newestFirst(companyId, query, principalUser, beforeSeq,
                request.fetchSize()), reader);
    }

    @Override
    public AuditVerification verify(@Nullable UUID companyId) {
        licensing.require(Feature.AUDIT_TOOLS);
        return verifier.verify(companyId);
    }

    @Override
    public Export export(UUID companyId, @Nullable Long fromSeq, @Nullable Long toSeq, @Nullable Instant from,
            @Nullable Instant to) {
        AuditExport.Plan plan = export.plan(companyId, fromSeq, toSeq, from, to);
        return () -> export.response(plan);
    }

    @Override
    public AuditCheckPage checks(@Nullable UUID companyId, @Nullable Integer limit, @Nullable String cursor) {
        return checks.checks(companyId, limit, cursor);
    }

    @Override
    public AuditCheck acknowledge(@Nullable UUID companyId, UUID checkId, String note) {
        return checks.acknowledge(companyId, checkId, note);
    }

    @Override
    public Optional<AuditCheck> openFailure(UUID companyId) {
        return checks.openFailure(companyId);
    }
}
