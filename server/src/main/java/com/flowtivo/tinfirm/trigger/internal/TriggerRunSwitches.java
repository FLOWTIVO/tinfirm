package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyRunsDependents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.SystemSwitchDependents;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;
import com.flowtivo.tinfirm.trigger.WaitingReason;

/**
 * What package 6's kill switches do to triggers (the package 6 contract, §5.4, open points 6 and 7), in the switch's
 * transaction and tenant context, as the admin who pulled it. Ordered first among the switches' dependents, so that
 * each trigger's row is locked before the conversation and run modules lock its conversations (CONVENTIONS §10).
 * <ul>
 * <li>The company's stop ({@link CompanyRunsDependents}): every active trigger waits {@code COMPANY_STOPPED} (the gate
 * answers the same while the stop lasts) and the write-back of its open conversations is held; polls go on
 * ({@link TriggerPolls}: they read, keep their snapshots and store comments, and the gate fires nothing and records
 * nothing). The lift arms every active trigger again at the lift, without a resume of each (the stated exception to
 * package 4's decision 9: what happened during the stop fires nothing), clears the wait and releases the held
 * write-back.</li>
 * <li>A system's pause ({@link SystemSwitchDependents}): every active trigger whose source is the system or whose
 * table has a row on it pauses {@code SYSTEM_PAUSED} ({@code cause system.paused}); the running turns are the
 * conversation module's; the system's polls call nothing from their next pass on. The resume moves the windows of the
 * system's polls to the resume (what changed during the pause is history), before any trigger is locked (the lock
 * order: a poll's row before a trigger's), and, with {@code resumeTriggers}, resumes each trigger paused
 * {@code SYSTEM_PAUSED} on the system through the gate, armed at the resume ({@code cause system.resumed}); one the
 * gate stops stays paused with the gate's reason (or is deleted, as the gate deletes it). {@link PollKeeper} wakes
 * the polls after the commit.</li>
 * </ul>
 * The stop and the lift lock every trigger's row, then their open conversations in the order of their ids, then their
 * outbox rows (CONVENTIONS §10: the write-back records a delivery with the conversation before the row; the
 * conversation and run modules lock the conversations after this anyway).
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
class TriggerRunSwitches implements CompanyRunsDependents, SystemSwitchDependents {

    static final String SYSTEM_PAUSED = "system.paused";

    static final String SYSTEM_RESUMED = "system.resumed";

    private final TriggerStore store;

    private final TriggerStates states;

    private final TriggerGateImpl gate;

    private final TriggerConversations conversations;

    private final ConversationLock conversationLock;

    private final PollStore polls;

    private final PollProperties pollProperties;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    TriggerRunSwitches(TriggerStore store, TriggerStates states, TriggerGateImpl gate,
            TriggerConversations conversations, ConversationLock conversationLock, PollStore polls,
            PollProperties pollProperties, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time) {
        this.store = store;
        this.states = states;
        this.gate = gate;
        this.conversations = conversations;
        this.conversationLock = conversationLock;
        this.polls = polls;
        this.pollProperties = pollProperties;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- the company's stop ------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public List<UUID> runsStopping(UUID companyId) {
        Instant now = time.now();
        List<TriggerInfo> stopped = new ArrayList<>();
        for (TriggerInfo listed : store.liveActive(companyId)) {
            TriggerInfo trigger = store.lock(companyId, listed.id()).filter(TriggerInfo::active).orElse(null);
            if (trigger == null) {
                continue;
            }
            states.waiting(trigger, WaitingReason.COMPANY_STOPPED);
            stopped.add(trigger);
        }
        store.holdOutbox(companyId, lockedConversations(companyId, stopped), now);
        return List.of();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Lifted runsLifted(UUID companyId) {
        Instant now = time.now();
        List<TriggerInfo> lifted = new ArrayList<>();
        for (TriggerInfo listed : store.liveActive(companyId)) {
            TriggerInfo trigger = store.lock(companyId, listed.id()).filter(TriggerInfo::active).orElse(null);
            if (trigger == null) {
                continue;
            }
            store.arm(companyId, trigger.id(), now);
            if (trigger.waitingReason() == WaitingReason.COMPANY_STOPPED) {
                states.repaired(trigger);
            }
            lifted.add(trigger);
        }
        int released = store.releaseOutbox(companyId, lockedConversations(companyId, lifted), now);
        return new Lifted(lifted.size(), released);
    }

    // ---- a system's pause --------------------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Pausing systemPausing(UUID companyId, UUID systemId, boolean stopRunning) {
        UUID by = CurrentActor.user().map(user -> user.id()).orElse(null);
        int paused = 0;
        for (TriggerInfo listed : store.liveOnSystem(companyId, systemId)) {
            TriggerInfo trigger = store.lock(companyId, listed.id()).filter(TriggerInfo::active).orElse(null);
            if (trigger == null) {
                continue;
            }
            states.pause(trigger, PausedReason.SYSTEM_PAUSED, by, false, SYSTEM_PAUSED);
            paused++;
        }
        return new Pausing(List.of(), paused);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public Resuming systemResuming(UUID companyId, UUID systemId, boolean resumeTriggers) {
        Instant now = time.now();
        polls.moveWindows(companyId, systemId, now.minus(pollProperties.overlap()), now);
        int resumed = 0;
        int stillPaused = 0;
        for (TriggerInfo listed : store.liveOnSystem(companyId, systemId)) {
            TriggerInfo trigger = store.lock(companyId, listed.id())
                .filter(TriggerInfo::live)
                .filter(found -> found.pausedReason() == PausedReason.SYSTEM_PAUSED)
                .orElse(null);
            if (trigger == null) {
                continue;
            }
            if (!resumeTriggers) {
                stillPaused++;
                continue;
            }
            switch (gate.verdict(trigger)) {
                case TriggerGateImpl.Verdict.Pause pause -> {
                    repause(trigger, pause);
                    stillPaused++;
                }
                case TriggerGateImpl.Verdict.Delete delete -> states.delete(trigger, delete.cause(), null);
                case TriggerGateImpl.Verdict.Wait wait -> {
                    states.resume(trigger, nextRun(trigger), null, SYSTEM_RESUMED);
                    states.waiting(store.find(companyId, trigger.id()).orElseThrow(), wait.reason());
                    resumed++;
                }
                case TriggerGateImpl.Verdict.Passes passes -> {
                    states.resume(trigger, nextRun(trigger), null, SYSTEM_RESUMED);
                    resumed++;
                }
            }
        }
        return new Resuming(resumed, stillPaused);
    }

    /** The gate stops a trigger the resume would resume: it stays paused, for the gate's reason now. */
    private void repause(TriggerInfo trigger, TriggerGateImpl.Verdict.Pause pause) {
        if (pause.systemId() != null) {
            store.deleteRow(trigger.companyId(), trigger.id(), pause.systemId());
        }
        if (pause.reason() == PausedReason.SYSTEM_PAUSED) {
            return;
        }
        store.pause(trigger.companyId(), trigger.id(), pause.reason(), null, time.now());
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_PAUSED, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("reason", pause.reason().name())
            .detail("stopRunning", false)
            .detail("runsCancelled", List.of())
            .detail("cause", SYSTEM_RESUMED)
            .build());
        events.publishEvent(new TriggerEvents.TriggerPaused(trigger.companyId(), trigger.id(), pause.reason()));
    }

    /** A schedule's next firing after now; null for a tracker trigger. */
    private @Nullable Instant nextRun(TriggerInfo trigger) {
        if (trigger.source() != TriggerSource.SCHEDULE || trigger.schedule() == null) {
            return null;
        }
        return Schedule.parse(trigger.schedule().cron(), trigger.schedule().zone()).next(time.now()).orElse(null);
    }

    /**
     * The triggers' open conversations, each locked now in the order of their ids (as the database orders them), after
     * every trigger's row and before their outbox rows.
     */
    private List<UUID> lockedConversations(UUID companyId, List<TriggerInfo> triggers) {
        List<UUID> ids = triggers.stream()
            .flatMap(trigger -> conversations.openOf(companyId, trigger.id()).stream())
            .map(ConversationInfo::id)
            .distinct()
            .sorted(Comparator.comparing(UUID::toString))
            .toList();
        ids.forEach(id -> conversationLock.lock(companyId, id));
        return ids;
    }
}
