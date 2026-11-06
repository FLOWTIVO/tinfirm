package com.flowtivo.tinfirm.governance.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.agent.AgentMemory;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationPurge;
import com.flowtivo.tinfirm.run.RunPurge;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.retention.RetainedRecords;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.trigger.TriggerDirectory;

/**
 * The daily retention sweep (the package 6 contract, §5.9; open points 16 and 25), on replicas with the {@code worker}
 * role: every company not swept for {@code sweep-interval} (a day) is swept once, as SYSTEM in its tenant context,
 * through the content modules' purge APIs:
 * <ol>
 * <li>{@code CHAT}: chats' and webhooks' messages and decisions' texts, of conversations ended longer than
 * {@code chat_content_days} ago;</li>
 * <li>{@code CONTENT}: every message, the title, decisions' texts, outputs' titles, the latest result, and the runs'
 * texts, of conversations ended longer than {@code content_days} ago (kept when it is {@code NULL});</li>
 * <li>{@code FILES}: files' content, of conversations ended longer than {@code files_days} ago (likewise);</li>
 * <li>run events, of runs ended longer than {@code run_events_days} ago, in ended conversations;</li>
 * <li>the records other modules keep for a fixed time ({@link RetainedRecords}), lifted holds among them;</li>
 * <li>agents' memory notes not used for {@code memory_days} (kept when it is {@code NULL}), and proposals of a
 * trigger's memory not reviewed in {@value #PROPOSAL_DAYS} days, but those of a held person or trigger, or learned in
 * a held conversation (ADR-0063), counted among the records as {@code memoryNotes}.</li>
 * </ol>
 * Each company purged is then queued for the compaction of its memories that changed, at most once in 30 days each,
 * each company's for at most 15 minutes of model requests, audited per memory ({@code memory.compacted}). It runs on a
 * thread of its own ({@link MemoryCompactions}), never on the scheduler's: a slow model holds up neither a purge, nor
 * the next pass, nor any other scheduled job.
 * Open conversations are never purged (G12); the sweep counts those older than their period, for the OWNER's inbox.
 * Nothing an open legal hold covers is purged (G16): each purge locks its conversations first (the lock order), then
 * takes the company's hold lock shared and reads the open holds again, so that a hold set meanwhile wins. One
 * conversation, or up to {@value #BATCH} runs, a transaction. The sweep is audited once per company,
 * {@code content.purged} with the counts per kind, when it purged anything; the audit chain itself is never purged
 * (G11).
 */
@Component
class RetentionSweep {

    private static final Logger log = LoggerFactory.getLogger(RetentionSweep.class);

    /** Candidates read at a time, and runs purged a transaction. */
    static final int BATCH = 500;

    /** How long a proposal of a trigger's memory waits for its review (ADR-0063, decision 5). */
    static final int PROPOSAL_DAYS = 30;

    private final RetentionStore store;

    private final ConversationPurge conversations;

    private final RunPurge runs;

    private final ObjectProvider<RetainedRecords> retained;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final boolean worker;

    private final Duration interval;

    private final AgentMemory memory;

    private final MemoryCompactions compactions;

    private final TriggerDirectory triggers;

    private final RetentionPolicy policy;

    RetentionSweep(RetentionStore store, ConversationPurge conversations, RunPurge runs,
            ObjectProvider<RetainedRecords> retained, SystemTransactions transactions, AuditLog auditLog,
            TimeSource time, TinfirmProperties tinfirm,
            @Value("${tinfirm.retention.sweep-interval:P1D}") Duration interval, AgentMemory memory,
            MemoryCompactions compactions, TriggerDirectory triggers, RetentionPolicy policy) {
        this.policy = policy;
        this.memory = memory;
        this.compactions = compactions;
        this.triggers = triggers;
        this.store = store;
        this.conversations = conversations;
        this.runs = runs;
        this.retained = retained;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.interval = interval;
    }

    @Scheduled(fixedDelayString = "${tinfirm.retention.sweep-tick:PT1H}",
            initialDelayString = "${tinfirm.retention.sweep-initial-delay:PT15M}")
    void scheduledSweep() {
        if (worker) {
            try {
                sweepDue();
            }
            catch (RuntimeException ex) {
                log.warn("The retention sweep failed to run", ex);
            }
        }
    }

    /** Sweeps every company due and queues their agents' memories' compaction; answers how many were swept. */
    int sweepDue() {
        Instant now = time.now();
        List<UUID> swept = new ArrayList<>();
        for (UUID companyId : transactions.unscoped(() -> store.due(now.minus(interval), 100))) {
            try {
                purge(companyId, now);
                swept.add(companyId);
            }
            catch (RuntimeException ex) {
                log.warn("The retention sweep of company {} failed", companyId, ex);
            }
        }
        // Agents' memories on the compactions' own thread (ADR-0063), so that a slow model holds up neither the
        // next pass nor the scheduler's other jobs: each company's compaction as of its own start, within its own time.
        swept.forEach(compactions::queue);
        return swept.size();
    }

    /** What one sweep of a company purged. */
    record Swept(ConversationPurge.Purged chat, ConversationPurge.Purged content, long runTexts,
            ConversationPurge.Purged files, long runEvents, Map<String, Long> records, long openOlder) {

        boolean any() {
            return chat.any() || content.any() || runTexts > 0 || files.any() || runEvents > 0
                    || records.values().stream().anyMatch(count -> count > 0);
        }
    }

    /** Sweeps one company as of {@code now}: its purges, then its agents' memories, on the caller's thread. */
    Swept sweep(UUID companyId, Instant now) {
        Swept swept = purge(companyId, now);
        compactions.compact(companyId, now);
        return swept;
    }

    /** Purges one company as of {@code now}, and records its sweep. */
    private Swept purge(UUID companyId, Instant now) {
        // The periods in force (ADR-0077, decision 8): under Community the content is kept, and only the memory notes'
        // period and the operational purges below run.
        Periods periods = transactions.inCompany(companyId, () -> policy.inForce(companyId));
        ConversationPurge.Purged chat = purgeKind(companyId, ConversationPurge.Kind.CHAT, periods.chatBefore(now),
                now, null);
        long[] runTexts = new long[1];
        Instant contentBefore = periods.contentBefore(now);
        ConversationPurge.Purged content = contentBefore == null ? ConversationPurge.Purged.NONE
                : purgeKind(companyId, ConversationPurge.Kind.CONTENT, contentBefore, now, runTexts);
        Instant filesBefore = periods.filesBefore(now);
        ConversationPurge.Purged files = filesBefore == null ? ConversationPurge.Purged.NONE
                : purgeKind(companyId, ConversationPurge.Kind.FILES, filesBefore, now, null);
        long runEvents = purgeRunEvents(companyId, periods.runEventsBefore(now), now);
        Map<String, Long> records = new LinkedHashMap<>();
        for (RetainedRecords kind : retained.orderedStream().toList()) {
            records.merge(kind.kind(), transactions.inCompany(companyId, () -> kind.sweep(companyId, now)),
                    Long::sum);
        }
        long notes = transactions.inCompany(companyId, () -> purgeMemory(companyId, periods.memoryBefore(now), now));
        if (notes > 0) {
            records.merge("memoryNotes", notes, Long::sum);
        }
        long openOlder = transactions.inCompany(companyId,
                () -> conversations.countOpenOlder(periods.chatBefore(now), contentBefore));
        Swept swept = new Swept(chat, content, runTexts[0], files, runEvents, records, openOlder);
        transactions.runInCompany(companyId, () -> {
            store.recordSweep(companyId, now, openOlder);
            if (swept.any()) {
                auditLog.record(AuditRecord.company(companyId, AuditAction.CONTENT_PURGED, Actor.SYSTEM)
                    .target("company", companyId)
                    .detail("chat", counts(chat))
                    .detail("content", counts(content))
                    .detail("runTexts", swept.runTexts())
                    .detail("files", swept.files().files())
                    .detail("runEvents", swept.runEvents())
                    .detail("records", records)
                    .build());
            }
        });
        if (openOlder > 0) {
            log.info("Company {} has {} open conversations older than their retention period; retention purges them"
                    + " once they end", companyId, openOlder);
        }
        return swept;
    }

    /**
     * Agents' memory notes past their period, under the hold lock taken shared: a held person's notes, a held person's
     * triggers' (those they author or are in the team of: a hold keeps more rather than less), and those learned in a
     * held conversation stay.
     */
    private long purgeMemory(UUID companyId, @Nullable Instant before, Instant now) {
        store.lockHolds(companyId, false);
        ConversationPurge.Held held = store.held();
        return memory.purge(companyId, before, proposalsBefore(now), held.principalIds(),
                heldTriggers(triggers, companyId, held), held.conversationIds());
    }

    /** A trigger's proposals for its memory not reviewed before this go. */
    static Instant proposalsBefore(Instant now) {
        return now.minus(Duration.ofDays(PROPOSAL_DAYS));
    }

    /**
     * The triggers whose memory a hold keeps: those of a held person, whether they author them or are in their team
     * (a hold keeps more rather than less).
     */
    static Set<UUID> heldTriggers(TriggerDirectory triggers, UUID companyId, ConversationPurge.Held held) {
        Set<UUID> heldTriggers = new HashSet<>();
        held.principalIds().forEach(principalId -> heldTriggers.addAll(triggers.ofPrincipal(companyId, principalId)));
        return heldTriggers;
    }

    /**
     * Purges one kind in each ended conversation due, one conversation a transaction; {@code runTexts} counts the
     * runs whose texts went with a conversation's content, when given.
     */
    private ConversationPurge.Purged purgeKind(UUID companyId, ConversationPurge.Kind kind, Instant endedBefore,
            Instant now, long @Nullable [] runTexts) {
        ConversationPurge.Purged total = ConversationPurge.Purged.NONE;
        ConversationPurge.Due after = null;
        List<ConversationPurge.Due> due;
        do {
            ConversationPurge.Due from = after;
            due = transactions.inCompany(companyId,
                    () -> conversations.due(kind, endedBefore, store.held(), from, BATCH));
            for (ConversationPurge.Due conversation : due) {
                ConversationPurge.Purged purged = transactions.inCompany(companyId, () -> {
                    conversations.lock(List.of(conversation.conversationId()));
                    store.lockHolds(companyId, false);
                    ConversationPurge.Held held = store.held();
                    if (conversations.endedUnheld(List.of(conversation.conversationId()), held).isEmpty()) {
                        return ConversationPurge.Purged.NONE;
                    }
                    ConversationPurge.Purged done = conversations.purge(conversation.conversationId(), kind, held,
                            now);
                    if (runTexts != null) {
                        runTexts[0] += runs.purgeTextsOf(conversation.conversationId(), now);
                    }
                    return done;
                });
                total = total.plus(purged);
                after = conversation;
            }
        }
        while (due.size() == BATCH);
        return total;
    }

    /** Purges the events of runs ended before the cutoff, in ended conversations no hold covers. */
    private long purgeRunEvents(UUID companyId, Instant endedBefore, Instant now) {
        long purged = 0;
        RunPurge.EndedRun after = null;
        List<RunPurge.EndedRun> ended;
        do {
            RunPurge.EndedRun from = after;
            ended = transactions.inCompany(companyId, () -> runs.eventsKept(endedBefore, from, BATCH));
            if (ended.isEmpty()) {
                break;
            }
            List<RunPurge.EndedRun> batch = ended;
            purged += transactions.inCompany(companyId, () -> {
                Set<UUID> conversationIds = new java.util.TreeSet<>(batch.stream()
                    .map(RunPurge.EndedRun::conversationId)
                    .toList());
                conversations.lock(conversationIds);
                store.lockHolds(companyId, false);
                Set<UUID> purgeable = conversations.endedUnheld(conversationIds, store.held());
                List<UUID> runIds = new ArrayList<>();
                for (RunPurge.EndedRun run : batch) {
                    if (purgeable.contains(run.conversationId())) {
                        runIds.add(run.runId());
                    }
                }
                // The runs' tool calls' journal rows go with their events (package R).
                conversations.purgeToolCalls(runIds);
                return (long) runs.purgeEvents(runIds, now);
            });
            after = ended.getLast();
        }
        while (ended.size() == BATCH);
        return purged;
    }

    private static Map<String, Long> counts(ConversationPurge.Purged purged) {
        Map<String, Long> counts = new LinkedHashMap<>();
        counts.put("conversations", purged.conversations());
        counts.put("messages", purged.messages());
        counts.put("decisions", purged.decisions());
        counts.put("outputs", purged.outputs());
        counts.put("files", purged.files());
        return counts;
    }
}
