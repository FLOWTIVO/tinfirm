package com.flowtivo.tinfirm.governance.internal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.api.model.HoldConversationRef;
import com.flowtivo.tinfirm.shared.api.model.LegalHold;
import com.flowtivo.tinfirm.shared.api.model.LegalHoldRequest;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Setting legal holds (ADR-0077, decision 4, {@code LEGAL_HOLDS}; the package 6 contract, §3.7, §5.9), by OWNER, in
 * the caller's transaction after its access check ({@link HoldService}, which lists and lifts them in every edition).
 */
@Component
class LegalHoldsImpl implements LegalHolds {

    private final RetentionStore store;

    private final RetentionWrites writes;

    private final Conversations conversations;

    private final Principals principals;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final Licensing licensing;

    LegalHoldsImpl(RetentionStore store, RetentionWrites writes, Conversations conversations, Principals principals,
            AuditLog auditLog, TimeSource time, Licensing licensing) {
        this.store = store;
        this.writes = writes;
        this.conversations = conversations;
        this.principals = principals;
        this.auditLog = auditLog;
        this.time = time;
        this.licensing = licensing;
    }

    /**
     * {@code GET /holds/conversations/{key}}: a conversation to hold, by its key; its id and key alone, so not a read
     * of it ({@code conversation.read}); an unknown key is 404.
     */
    @Override
    public HoldConversationRef conversation(UUID companyId, String key) {
        // Only to set a hold, so Enterprise's as setting one is: the same answer as a server without ee/.
        licensing.require(Feature.LEGAL_HOLDS);
        UUID id = conversations.idOfKey(companyId, key)
            .orElseThrow(() -> ApiException.notFound("No conversation with this key"));
        return new HoldConversationRef(id, conversations.find(companyId, id).orElseThrow().key());
    }

    @Override
    public LegalHold set(UUID companyId, LegalHoldRequest request) {
        // ADR-0077, decisions 4 and 7: holds are set under Enterprise; once it expired the open ones stay in force,
        // are listed and lifted, and none is set.
        licensing.require(Feature.LEGAL_HOLDS);
        UUID userId = CurrentActor.requireUser().id();
        String reason = request.getReason() != null ? request.getReason().strip() : "";
        if (reason.isEmpty() || reason.length() > 500) {
            throw ApiException.validation("reason must be 1 to 500 characters");
        }
        UUID conversationId = request.getConversationId();
        UUID principalId = request.getPrincipalId();
        if ((conversationId == null) == (principalId == null)) {
            throw ApiException.validation("A hold names exactly one of conversationId and principalId");
        }
        Map<UUID, String> names = Map.of();
        Map<UUID, String> keys = Map.of();
        if (conversationId != null) {
            ConversationInfo conversation = conversations.find(companyId, conversationId)
                .orElseThrow(() -> ApiException.notFound("Conversation not found"));
            keys = Map.of(conversationId, conversation.key());
        }
        else {
            PrincipalInfo principal = principals.find(companyId, principalId)
                .orElseThrow(() -> ApiException.notFound("Principal not found"));
            if (!principal.member()) {
                throw ApiException.validation("A hold names a member's principal: a service identity has no content"
                        + " of its own");
            }
            names = principals.displayNames(companyId, List.of(principalId));
        }
        store.lockHolds(companyId, true);
        RetentionStore.HoldRow hold = new RetentionStore.HoldRow(Ids.newId(), companyId, conversationId, principalId,
                reason, time.now(), userId, null, null);
        writes.insertHold(hold);
        auditLog.record(HoldService.target(AuditRecord.company(companyId, AuditAction.HOLD_SET, CurrentActor.actor())
            .target(HoldService.TARGET, hold.id())
            .detail("holdId", hold.id().toString()), hold).build());
        return HoldService.dto(hold, names, keys);
    }
}
