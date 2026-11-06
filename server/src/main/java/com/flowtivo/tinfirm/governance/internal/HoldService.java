package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.HoldConversationRef;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.api.model.LegalHold;
import com.flowtivo.tinfirm.shared.api.model.LegalHoldPage;
import com.flowtivo.tinfirm.shared.api.model.LegalHoldRequest;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Legal holds (the package 6 contract, §3.7, §5.9; open point 22), by OWNER ({@code MANAGE_RETENTION}): on a
 * conversation (all of it, its runs' events included) or on a member's principal (their personal conversations, and
 * what they said, added and decided in others). The sweep skips what an open hold covers and an erasure of any of it
 * is {@code 409 ON_HOLD} (G16); a hold brings back nothing a sweep removed before it, and the person is not told.
 * Setting and lifting a hold takes the company's hold lock exclusively ({@link RetentionStore#lockHolds}), which every
 * purge takes shared. Audited {@code hold.set} and {@code hold.lifted} with the ids; the reason stays on the hold.
 * <p>
 * Setting a hold is Enterprise's ({@link LegalHolds}, under {@code ee/}); every hold that exists stays in force, is
 * listed and is lifted in every edition, as after a licence expired (ADR-0077, decisions 4 and 7).
 */
@Service
class HoldService {

    static final String TARGET = "legal_hold";

    private final RetentionStore store;

    private final Conversations conversations;

    private final Principals principals;

    private final CompanyAccessGuard companyAccess;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final ObjectProvider<LegalHolds> holds;

    HoldService(RetentionStore store, Conversations conversations, Principals principals,
            CompanyAccessGuard companyAccess, AuditLog auditLog, TimeSource time, ObjectProvider<LegalHolds> holds) {
        this.holds = holds;
        this.store = store;
        this.conversations = conversations;
        this.principals = principals;
        this.companyAccess = companyAccess;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * {@code GET /holds/conversations/{key}}: a conversation to hold, by its key, to set a hold on ({@link LegalHolds}).
     */
    @Transactional(readOnly = true)
    public HoldConversationRef conversation(UUID companyId, String key) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        return setting().conversation(companyId, key);
    }

    @Transactional(readOnly = true)
    public LegalHoldPage list(UUID companyId, boolean lifted, @Nullable Integer limit, @Nullable String cursor) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        PageRequest request = PageRequest.of(limit, cursor);
        Instant beforeAt = request.firstPage() ? null : request.instantKey(0, 2);
        UUID beforeId = request.firstPage() ? null : request.uuidKey(1, 2);
        Page<RetentionStore.HoldRow> page = Page.of(store.holds(lifted, beforeAt, beforeId, request.fetchSize()),
                request, row -> List.of(row.setAt().toString(), row.id().toString()));
        return new LegalHoldPage(dtos(companyId, page.items())).nextCursor(page.nextCursor());
    }

    /** The holds as the API shows them: with the held person's name and the held conversation's key. */
    List<LegalHold> dtos(UUID companyId, List<RetentionStore.HoldRow> rows) {
        Map<UUID, String> names = principals.displayNames(companyId, rows.stream()
            .map(RetentionStore.HoldRow::principalId)
            .filter(Objects::nonNull)
            .toList());
        Map<UUID, String> keys = new HashMap<>();
        for (RetentionStore.HoldRow row : rows) {
            if (row.conversationId() != null) {
                keys.computeIfAbsent(row.conversationId(), id -> conversations.find(companyId, id)
                    .map(ConversationInfo::key)
                    .orElse(null));
            }
        }
        return rows.stream().map(row -> dto(row, names, keys)).toList();
    }

    /** Sets a hold: Enterprise's ({@link LegalHolds}). */
    @Transactional
    public LegalHold set(UUID companyId, LegalHoldRequest request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        return setting().set(companyId, request);
    }

    @Transactional
    public void lift(UUID companyId, UUID holdId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        UUID userId = CurrentActor.requireUser().id();
        RetentionStore.HoldRow hold = store.hold(holdId).orElseThrow(() -> ApiException.notFound("Hold not found"));
        store.lockHolds(companyId, true);
        if (store.lift(holdId, time.now(), userId)) {
            auditLog.record(target(AuditRecord.company(companyId, AuditAction.HOLD_LIFTED, CurrentActor.actor())
                .target(TARGET, holdId)
                .detail("holdId", holdId.toString()), hold).build());
        }
    }

    /** Setting holds, under {@code ee/}; without it the server answers as Community, {@code 403 EDITION_REQUIRED}. */
    private LegalHolds setting() {
        LegalHolds found = holds.getIfAvailable();
        if (found == null) {
            throw Licensing.editionRequired(Feature.LEGAL_HOLDS);
        }
        return found;
    }

    static AuditRecord.Builder target(AuditRecord.Builder record, RetentionStore.HoldRow hold) {
        return hold.conversationId() != null ? record.detail("conversationId", hold.conversationId().toString())
                : record.detail("principalId", Objects.requireNonNull(hold.principalId()).toString());
    }

    static LegalHold dto(RetentionStore.HoldRow row, Map<UUID, String> names, Map<UUID, String> keys) {
        return new LegalHold(row.id(), row.reason(), row.setAt().atOffset(ZoneOffset.UTC))
            .conversationId(row.conversationId())
            .conversationKey(row.conversationId() != null ? keys.get(row.conversationId()) : null)
            .principalId(row.principalId())
            .principalName(row.principalId() != null ? names.get(row.principalId()) : null)
            .setByUserId(row.setBy())
            .liftedAt(row.liftedAt() != null ? row.liftedAt().atOffset(ZoneOffset.UTC) : null)
            .liftedByUserId(row.liftedBy());
    }
}
