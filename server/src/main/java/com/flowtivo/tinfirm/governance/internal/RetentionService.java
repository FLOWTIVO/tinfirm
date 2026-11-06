package com.flowtivo.tinfirm.governance.internal;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.run.RunPurge;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.RetentionDue;
import com.flowtivo.tinfirm.shared.api.model.RetentionOpenConversation;
import com.flowtivo.tinfirm.shared.api.model.RetentionOpenConversationPage;
import com.flowtivo.tinfirm.shared.api.model.RetentionPeriods;
import com.flowtivo.tinfirm.shared.api.model.RetentionPreview;
import com.flowtivo.tinfirm.shared.api.model.RetentionSettings;
import com.flowtivo.tinfirm.shared.api.model.RetentionSweepCounts;
import com.flowtivo.tinfirm.shared.licence.Feature;
import com.flowtivo.tinfirm.shared.licence.Licensing;
import com.flowtivo.tinfirm.shared.pagination.Page;
import com.flowtivo.tinfirm.shared.pagination.PageRequest;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.trigger.TriggerDirectory;

/**
 * The company's retention periods (the package 6 contract, §3.7, §5.9; open points 16 and 25): admins read them
 * ({@code VIEW_AUDIT}), the OWNER changes them ({@code MANAGE_RETENTION}). The answer says what the next sweep
 * purges under them, outside what an open hold covers, and how many open conversations are older than their period,
 * which retention never purges (G12). A change is audited {@code retention.changed} with the four periods after it.
 * <p>
 * Changing the periods and previewing a change are Pro's and Enterprise's ({@link RetentionChanges}, under
 * {@code ee/}); the periods in force ({@link RetentionPolicy}) and what the next sweep purges under them are shown in
 * every edition.
 */
@Service
class RetentionService {

    private final RetentionStore store;

    private final ConversationPurge conversations;

    private final RunPurge runs;

    private final CompanyAccessGuard companyAccess;

    private final TimeSource time;

    private final AgentMemory memory;

    private final TriggerDirectory triggers;

    private final RetentionPolicy policy;

    private final ObjectProvider<RetentionChanges> changes;

    RetentionService(RetentionStore store, ConversationPurge conversations, RunPurge runs,
            CompanyAccessGuard companyAccess, TimeSource time, AgentMemory memory, TriggerDirectory triggers,
            RetentionPolicy policy, ObjectProvider<RetentionChanges> changes) {
        this.policy = policy;
        this.changes = changes;
        this.store = store;
        this.conversations = conversations;
        this.runs = runs;
        this.companyAccess = companyAccess;
        this.time = time;
        this.memory = memory;
        this.triggers = triggers;
    }

    @Transactional(readOnly = true)
    public RetentionSettings get(UUID companyId) {
        companyAccess.require(companyId, CompanyPermission.VIEW_AUDIT);
        return settings(companyId);
    }

    /** The OWNER's change of the periods: Pro's and Enterprise's ({@link RetentionChanges}). */
    @Transactional
    public RetentionSettings update(UUID companyId, RetentionPeriods request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        changes().update(companyId, request);
        return settings(companyId);
    }

    /** {@code POST /retention/preview}: Pro's and Enterprise's ({@link RetentionChanges}). */
    @Transactional(readOnly = true)
    public RetentionPreview preview(UUID companyId, RetentionPeriods request) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        return changes().preview(companyId, request);
    }

    /**
     * What one sweep as of {@code now} would purge under the periods, outside what the holds cover: the sweep's own
     * selection ({@link RetentionSweep#sweep}) and its purges' rows, counted through the purge APIs. Runs in the
     * company's tenant context.
     */
    Forecast forecast(UUID companyId, Periods periods, Instant now, ConversationPurge.Held held) {
        Instant contentBefore = periods.contentBefore(now);
        ConversationPurge.Purged purged = conversations.countSweep(periods.chatBefore(now), contentBefore,
                periods.filesBefore(now), held);
        long runTexts = contentBefore == null ? 0
                : runs.countTextsIn(conversations.dueSelection(ConversationPurge.Kind.CONTENT, contentBefore, held));
        long runEvents = runs.countEventsKeptIn(periods.runEventsBefore(now),
                conversations.endedUnheldSelection(held));
        // Agents' memory notes past their period, and proposals not reviewed in time (ADR-0063), as the sweep's
        // purgeMemory takes them.
        long memoryNotes = memory.countPurge(companyId, periods.memoryBefore(now), RetentionSweep.proposalsBefore(now),
                held.principalIds(), RetentionSweep.heldTriggers(triggers, companyId, held), held.conversationIds());
        return new Forecast(purged, runTexts, runEvents, memoryNotes);
    }

    /** What a sweep would purge: the conversations' rows, and the runs whose texts and whose events would go. */
    record Forecast(ConversationPurge.Purged purged, long runTexts, long runEvents, long memoryNotes) {

        static final Forecast NONE = new Forecast(ConversationPurge.Purged.NONE, 0, 0, 0);

        Forecast minus(Forecast other) {
            return new Forecast(new ConversationPurge.Purged(
                    purged.conversations() - other.purged.conversations(),
                    purged.messages() - other.purged.messages(), purged.decisions() - other.purged.decisions(),
                    purged.outputs() - other.purged.outputs(), purged.files() - other.purged.files()),
                    runTexts - other.runTexts, runEvents - other.runEvents, memoryNotes - other.memoryNotes);
        }

        RetentionSweepCounts dto() {
            return new RetentionSweepCounts(purged.conversations(), purged.messages(), purged.decisions(),
                    purged.outputs(), purged.files(), runTexts, runEvents, memoryNotes);
        }
    }

    @Transactional(readOnly = true)
    public RetentionOpenConversationPage openConversations(UUID companyId, @Nullable Integer limit,
            @Nullable String cursor) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_RETENTION);
        PageRequest request = PageRequest.of(limit, cursor);
        ConversationPurge.Open after = request.firstPage() ? null
                : new ConversationPurge.Open(request.uuidKey(1, 2), "", "", request.instantKey(0, 2));
        Periods periods = policy.inForce(companyId);
        Instant now = time.now();
        Page<ConversationPurge.Open> page = Page.of(conversations.openOlder(periods.chatBefore(now),
                periods.contentBefore(now), after, request.fetchSize()), request,
                open -> List.of(open.createdAt().toString(), open.id().toString()));
        return new RetentionOpenConversationPage(page.items().stream()
            .map(open -> new RetentionOpenConversation(open.id(), open.key(),
                    RetentionOpenConversation.PlaceEnum.fromValue(open.place()),
                    open.createdAt().atOffset(ZoneOffset.UTC),
                    (int) Duration.between(open.createdAt(), now).toDays()))
            .toList()).nextCursor(page.nextCursor());
    }

    /**
     * Changing the periods, under {@code ee/}; without it the server answers as Community, {@code 403
     * EDITION_REQUIRED}.
     */
    private RetentionChanges changes() {
        RetentionChanges found = changes.getIfAvailable();
        if (found == null) {
            throw Licensing.editionRequired(Feature.RETENTION_SETTINGS);
        }
        return found;
    }

    private RetentionSettings settings(UUID companyId) {
        Instant now = time.now();
        java.util.Optional<RetentionStore.SettingsRow> row = store.settings(companyId);
        RetentionPolicy.Mode mode = policy.mode();
        Periods periods = mode == RetentionPolicy.Mode.KEEP_EVERYTHING ? policy.inForce(companyId)
                : row.map(RetentionStore.SettingsRow::periods).orElse(Periods.DEFAULTS);
        // What the next sweep purges: the preview's forecast of the stored periods, so that the page's count and
        // the preview's `current` are one and the same, each conversation counted once.
        Forecast next = forecast(companyId, periods, now, store.held());
        RetentionSettings settings = new RetentionSettings(periods.runEventsDays(), periods.chatContentDays(),
                row.isEmpty(), RetentionSettings.ModeEnum.fromValue(mode.name()), (int) Math.min(Integer.MAX_VALUE,
                        conversations.countOpenOlder(periods.chatBefore(now), periods.contentBefore(now))),
                new RetentionDue(next.purged().conversations(), next.runEvents(), next.purged().files()),
                next.dto())
            .contentDays(periods.contentDays())
            .filesDays(periods.filesDays())
            .memoryDays(periods.memoryDays() != null ? periods.memoryDays() : 0)
            .lastSweptAt(store.lastSwept(companyId).map(at -> at.atOffset(ZoneOffset.UTC)).orElse(null));
        row.ifPresent(found -> settings.updatedAt(found.updatedAt().atOffset(ZoneOffset.UTC))
            .updatedByUserId(found.updatedBy()));
        return settings;
    }
}
