package com.flowtivo.tinfirm.audit.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditPrincipals;
import com.flowtivo.tinfirm.audit.AuditTargetNames;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.AuditEventPage;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * A page of the audit as the app shows it, in every edition and with the audit's filters ({@link AuditTools}): the
 * history shown, who reads it, and its events with the names they refer to.
 */
@Component
class AuditPages {

    /** How far back the app shows the audit without {@code AUDIT_FULL_HISTORY}. */
    static final int COMMUNITY_HISTORY_DAYS = 90;

    private final AuditNames names;

    private final AuditEventMapper mapper;

    private final ObjectProvider<AuditPrincipals> principals;

    private final Licensing licensing;

    private final TimeSource time;

    AuditPages(AuditNames names, AuditEventMapper mapper, ObjectProvider<AuditPrincipals> principals,
            Licensing licensing, TimeSource time) {
        this.names = names;
        this.mapper = mapper;
        this.principals = principals;
        this.licensing = licensing;
        this.time = time;
    }

    /** The last 90 days of the audit, without {@code AUDIT_FULL_HISTORY} (ADR-0077, decision 4). */
    AuditQuery withinHistory(AuditQuery query) {
        if (licensing.has(Feature.AUDIT_FULL_HISTORY)) {
            return query;
        }
        Instant since = time.now().minus(Duration.ofDays(COMMUNITY_HISTORY_DAYS));
        return query.from() != null && query.from().isAfter(since) ? query : query.from(since);
    }

    /** The signed-in reader of the company's chain, with the access the guard gave them. */
    AuditTargetNames.Reader reader(UUID companyId, CompanyAccess access) {
        UUID reader = CurrentActor.requireUser().id();
        return new AuditTargetNames.Reader(companyId, reader, readerPrincipal(companyId, reader),
                access.role() == CompanyRole.OWNER);
    }

    /** The instance admin who reads the instance chain. */
    AuditTargetNames.Reader instanceReader() {
        UUID reader = CurrentActor.requireInstanceAdmin().id();
        return new AuditTargetNames.Reader(null, reader, null, false);
    }

    /** The page of these events, read for the request (one more than its size when a next page exists). */
    AuditEventPage page(PageRequest request, List<StoredAuditEvent> rows, AuditTargetNames.Reader reader) {
        Page<StoredAuditEvent> page = Page.of(rows, request, event -> List.of(Long.toString(event.seq())));
        AuditNames.Names resolved = names.of(reader, page.items());
        return new AuditEventPage(page.items().stream().map(event -> mapper.toDto(event, resolved)).toList())
            .nextCursor(page.nextCursor());
    }

    /** The reader's own principal in the company, the one that has not left; null for an instance admin who is none. */
    private @Nullable UUID readerPrincipal(UUID companyId, UUID userId) {
        AuditPrincipals directory = principals.getIfAvailable();
        if (directory == null) {
            return null;
        }
        return directory.ofUsers(companyId, List.of(userId)).stream()
            .filter(principal -> !principal.left())
            .map(AuditPrincipals.Principal::id)
            .findFirst()
            .orElse(null);
    }
}
