package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditChecks;
import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.conversation.RetentionInbox;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * Package 6's items of the inbox (the package 6 contract, §5.11), computed when the inbox is read, never stored:
 * {@code AUDIT_CHECK} for OWNER and ADMIN ({@code VIEW_AUDIT}) while the latest daily check of the audit chain failed
 * and no OWNER acknowledged it; {@code RETENTION_OPEN} for OWNER ({@code MANAGE_RETENTION}) while open conversations
 * are older than their retention period (open point 25), with how many, never which.
 */
@Component
class GovernanceInbox implements RetentionInbox {

    private final AuditChecks checks;

    private final ConversationPurge conversations;

    private final RetentionStore store;

    private final TimeSource time;

    private final RetentionPolicy policy;

    GovernanceInbox(AuditChecks checks, ConversationPurge conversations, RetentionStore store, TimeSource time,
            RetentionPolicy policy) {
        this.policy = policy;
        this.checks = checks;
        this.conversations = conversations;
        this.store = store;
        this.time = time;
    }

    @Override
    public List<InboxItem> items(CompanyAccess access, UUID userId, @Nullable UUID callerPrincipalId) {
        List<InboxItem> items = new ArrayList<>();
        if (access.covers(CompanyPermission.VIEW_AUDIT)) {
            checks.openFailure(access.companyId()).ifPresent(check -> items.add(
                    new InboxItem(InboxItemKind.AUDIT_CHECK, check.getCheckedAt()).auditCheck(check)));
        }
        if (access.covers(CompanyPermission.MANAGE_RETENTION)) {
            Periods periods = policy.inForce(access.companyId());
            Instant now = time.now();
            List<ConversationPurge.Open> oldest = conversations.openOlder(periods.chatBefore(now),
                    periods.contentBefore(now), null, 1);
            if (!oldest.isEmpty()) {
                long count = conversations.countOpenOlder(periods.chatBefore(now), periods.contentBefore(now));
                items.add(new InboxItem(InboxItemKind.RETENTION_OPEN,
                        oldest.getFirst().createdAt().atOffset(ZoneOffset.UTC))
                    .openConversations((int) Math.min(Integer.MAX_VALUE, count)));
            }
        }
        return items;
    }
}
