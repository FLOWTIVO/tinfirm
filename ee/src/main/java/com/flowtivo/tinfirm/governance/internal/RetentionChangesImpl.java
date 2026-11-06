package com.flowtivo.tinfirm.governance.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.shared.api.model.RetentionPeriods;
import com.flowtivo.tinfirm.shared.api.model.RetentionPreview;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;

/**
 * The OWNER's retention periods (ADR-0077, decisions 4, 7 and 8, {@code RETENTION_SETTINGS}; the package 6 contract,
 * §3.7, §5.9): their change, audited {@code retention.changed} with the periods after it, and its preview, in the
 * caller's transaction after its access check ({@link RetentionService}, which shows the periods in force and what
 * the next sweep purges under them in every edition).
 */
@Component
class RetentionChangesImpl implements RetentionChanges {

    private final RetentionStore store;

    private final RetentionWrites writes;

    private final RetentionService retention;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final Licensing licensing;

    RetentionChangesImpl(RetentionStore store, RetentionWrites writes, RetentionService retention,
            AuditLog auditLog, TimeSource time, Licensing licensing) {
        this.store = store;
        this.writes = writes;
        this.retention = retention;
        this.auditLog = auditLog;
        this.time = time;
        this.licensing = licensing;
    }

    @Override
    public void update(UUID companyId, RetentionPeriods request) {
        // ADR-0077, decisions 7 and 8: the OWNER's periods are Pro's and Enterprise's; after a licence expired they
        // run on as they were, and cannot be changed.
        licensing.require(Feature.RETENTION_SETTINGS);
        Periods before = store.periods(companyId);
        Periods after = Periods.of(request, before).validated();
        boolean stored = store.settings(companyId).isPresent();
        if (!after.equals(before) || !stored) {
            writes.save(companyId, after, time.now(), CurrentActor.requireUser().id());
        }
        if (!after.equals(before)) {
            List<String> changed = new ArrayList<>();
            if (!java.util.Objects.equals(after.runEventsDays(), before.runEventsDays())) {
                changed.add("runEventsDays");
            }
            if (!java.util.Objects.equals(after.chatContentDays(), before.chatContentDays())) {
                changed.add("chatContentDays");
            }
            if (!java.util.Objects.equals(after.contentDays(), before.contentDays())) {
                changed.add("contentDays");
            }
            if (!java.util.Objects.equals(after.filesDays(), before.filesDays())) {
                changed.add("filesDays");
            }
            if (!java.util.Objects.equals(after.memoryDays(), before.memoryDays())) {
                changed.add("memoryDays");
            }
            AuditRecord.Builder record = AuditRecord
                .company(companyId, AuditAction.RETENTION_CHANGED, CurrentActor.actor())
                .target("retention_settings", companyId);
            after.audit().forEach(record::detail);
            auditLog.record(record.detail("changed", changed).build());
        }
    }

    /**
     * {@code POST /retention/preview}: what the next sweep would purge if it ran now, under proposed periods (checked as
     * a change is) and under the stored ones, and what the open holds keep from it under the proposed ones. Counts
     * only, by the sweep's own selection; changes nothing and is not audited.
     */
    @Override
    public RetentionPreview preview(UUID companyId, RetentionPeriods request) {
        licensing.require(Feature.RETENTION_SETTINGS);
        Periods stored = store.periods(companyId);
        Periods proposed = Periods.of(request, stored).validated();
        Instant now = time.now();
        ConversationPurge.Held held = store.held();
        RetentionService.Forecast underProposed = retention.forecast(companyId, proposed, now, held);
        RetentionService.Forecast underStored = stored.equals(proposed) ? underProposed
                : retention.forecast(companyId, stored, now, held);
        // What the holds keep: the same sweep without them, less the sweep with them (a hold only ever keeps rows).
        RetentionService.Forecast kept = held.conversationIds().isEmpty() && held.principalIds().isEmpty()
                ? RetentionService.Forecast.NONE
                : retention.forecast(companyId, proposed, now, ConversationPurge.Held.NONE).minus(underProposed);
        return new RetentionPreview(underProposed.dto(), underStored.dto(), kept.dto());
    }
}
