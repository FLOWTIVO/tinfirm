package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSchedule;
import com.flowtivo.tinfirm.trigger.TriggerSource;

/**
 * Schedules (the package 4 contract, §5.10 and open point 5; ADR-0032):
 * every {@code tinfirm.trigger.schedule-tick} on {@code worker}
 * replicas, the schedules {@code trigger_schedule_due} returns, each under its row lock ({@code SKIP LOCKED}, due
 * checked again), as SYSTEM. A firing: the gate (a pause or a wait fires nothing and records nothing, and the schedule
 * moves on: a firing due while the company's runs are stopped is not caught up after the lift, the package 6
 * contract, §5.4); the agent paused ({@code SKIPPED_AGENT_PAUSED}); overlap {@code SKIP} while the previous firing's
 * conversation is busy ({@code SKIPPED_OPEN}); catch-up {@code NONE} and due longer ago than
 * {@code tinfirm.trigger.missed-after} ({@code SKIPPED_MISSED}, one record for any number missed); the rate; else
 * {@code STARTED}: a conversation of its own in the {@code SCHEDULE} place, the previous firing's idle one ending
 * {@code SUPERSEDED}. The trigger records the due time, the outcome and the next firing after now. A firing that
 * cannot start rolls back and is recorded {@code FAILED} with the reason in a new transaction; the schedule moves on.
 */
@Component
class ScheduleFirings {

    private static final Logger log = LoggerFactory.getLogger(ScheduleFirings.class);

    /** Schedules a tick takes at most. */
    static final int BATCH = 50;

    private static final Duration HOUR = Duration.ofHours(1);

    private final PollStore store;

    private final TriggerStore triggers;

    private final TriggerGate gate;

    private final Agents agents;

    private final TriggerConversations conversations;

    private final FiringConversations firingConversations;

    private final Firings firings;

    private final SystemTransactions transactions;

    private final JdbcClient jdbc;

    private final PollProperties properties;

    private final boolean worker;

    private final TimeSource time;

    ScheduleFirings(PollStore store, TriggerStore triggers, TriggerGate gate, Agents agents,
            TriggerConversations conversations, FiringConversations firingConversations, Firings firings,
            SystemTransactions transactions, JdbcClient jdbc, PollProperties properties, TinfirmProperties tinfirm,
            TimeSource time) {
        this.store = store;
        this.triggers = triggers;
        this.gate = gate;
        this.agents = agents;
        this.conversations = conversations;
        this.firingConversations = firingConversations;
        this.firings = firings;
        this.transactions = transactions;
        this.jdbc = jdbc;
        this.properties = properties;
        this.worker = tinfirm.hasRole(TinfirmProperties.WORKER_ROLE);
        this.time = time;
    }

    @Scheduled(fixedDelayString = "${tinfirm.trigger.schedule-tick:PT30S}",
            initialDelayString = "${tinfirm.trigger.schedule-tick:PT30S}")
    void tick() {
        if (worker) {
            try {
                fireDue();
            }
            catch (RuntimeException ex) {
                log.warn("The schedule tick failed", ex);
            }
        }
    }

    /** Fires every due schedule, one after the other; safe on several replicas at once. */
    int fireDue() {
        Instant now = time.now();
        List<UUID[]> due = transactions.unscoped(() -> jdbc.sql(
                "SELECT trigger_id, company_id FROM trigger_schedule_due(?, ?)")
            .params(TriggerStore.ts(now), BATCH)
            .query((rs, n) -> new UUID[] {rs.getObject("trigger_id", UUID.class),
                rs.getObject("company_id", UUID.class)})
            .list());
        int fired = 0;
        for (UUID[] ref : due) {
            try {
                if (SystemContext.callInCompany(ref[1], () -> fireOne(ref[1], ref[0])) != null) {
                    fired++;
                }
            }
            catch (RuntimeException ex) {
                log.warn("The schedule of trigger {} failed", ref[0], ex);
            }
        }
        return fired;
    }

    /** One due schedule: fired under its row lock, or, when it cannot start, recorded {@code FAILED}. */
    @Nullable FiringOutcome fireOne(UUID companyId, UUID triggerId) {
        Instant[] due = new Instant[1];
        try {
            return transactions.inCompany(companyId, () -> {
                if (!store.lockSchedule(companyId, triggerId)) {
                    return null;
                }
                TriggerInfo trigger = triggers.lock(companyId, triggerId).orElse(null);
                Instant now = time.now();
                if (trigger == null || !trigger.active() || trigger.source() != TriggerSource.SCHEDULE
                        || trigger.schedule() == null || trigger.schedule().nextRunAt() == null
                        || trigger.schedule().nextRunAt().isAfter(now)) {
                    return null;
                }
                due[0] = trigger.schedule().nextRunAt();
                Outcome outcome = fire(trigger, due[0], null, true);
                Instant next = next(trigger, now);
                if (outcome.outcome() != null) {
                    store.scheduled(triggerId, next, due[0], outcome.outcome(), now);
                }
                else {
                    store.nextRun(triggerId, next);
                }
                return outcome.outcome();
            });
        }
        catch (RuntimeException ex) {
            if (due[0] == null) {
                throw ex;
            }
            log.warn("The schedule of trigger {} could not start its firing", triggerId, ex);
            String reason = "The firing could not start (" + ex.getClass().getSimpleName() + ")";
            return transactions.inCompany(companyId, () -> {
                TriggerInfo trigger = triggers.lock(companyId, triggerId).orElse(null);
                if (trigger == null) {
                    return null;
                }
                Instant now = time.now();
                UUID firingId = Ids.newId();
                store.insertFiring(new PollStore.Firing(firingId, companyId, triggerId, null, null,
                        TriggerEvent.SCHEDULE, null, null, null, null, due[0], null, false, FiringOutcome.FAILED,
                        reason, 0, null, null, null, now));
                firings.audit(trigger, firingId, null, TriggerEvent.SCHEDULE, FiringOutcome.FAILED, null, 0, null);
                store.scheduled(triggerId, next(trigger, now), due[0], FiringOutcome.FAILED, now);
                return FiringOutcome.FAILED;
            });
        }
    }

    /** What a schedule's firing did. */
    record Outcome(@Nullable UUID firingId, @Nullable FiringOutcome outcome, @Nullable String stopped) {
    }

    /**
     * Fires the locked schedule for its due time, in the caller's transaction (§5.10); a run now (§5.6) fires once now,
     * with its overlap and without its catch-up.
     *
     * @param manualBy the user of a run now, else null
     * @param catchUp  whether the catch-up applies (not for a run now)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    Outcome fire(TriggerInfo trigger, Instant dueAt, @Nullable UUID manualBy, boolean catchUp) {
        UUID companyId = trigger.companyId();
        TriggerSchedule schedule = Objects.requireNonNull(trigger.schedule());
        TriggerGate.Result gated = gate.check(companyId, trigger.id());
        if (!gated.fires()) {
            return new Outcome(null, null, Firings.gateWords(gated));
        }
        Instant now = time.now();
        Optional<AgentInfo> agent = agents.find(companyId, trigger.agentId()).filter(AgentInfo::live);
        if (agent.isEmpty() || agent.get().status() == AgentStatus.PAUSED) {
            return record(trigger, dueAt, manualBy, FiringOutcome.SKIPPED_AGENT_PAUSED, "The agent is paused", now);
        }
        List<ConversationInfo> open = conversations.openOf(companyId, trigger.id());
        boolean busy = open.stream().anyMatch(conversation -> conversations.busy(companyId, conversation.id()));
        if (busy && schedule.overlap() == TriggerSchedule.Overlap.SKIP) {
            return record(trigger, dueAt, manualBy, FiringOutcome.SKIPPED_OPEN,
                    "The previous firing's conversation is busy", now);
        }
        if (catchUp && schedule.catchUp() == TriggerSchedule.CatchUp.NONE
                && Duration.between(dueAt, now).compareTo(properties.missedAfter()) > 0) {
            return record(trigger, dueAt, manualBy, FiringOutcome.SKIPPED_MISSED, "The firing was due at " + dueAt
                    + ", longer ago than " + properties.missedAfter().toMinutes() + " minutes", now);
        }
        if (store.firedSince(trigger.id(), now.minus(HOUR)) >= trigger.maxFiringsPerHour()) {
            store.countRateSkipped(trigger.id());
            return record(trigger, dueAt, manualBy, FiringOutcome.SKIPPED_RATE, "More than "
                    + trigger.maxFiringsPerHour() + " firings within an hour", now);
        }
        for (ConversationInfo conversation : open) {
            if (!conversations.busy(companyId, conversation.id())) {
                conversations.end(companyId, conversation.id(), ConversationInfo.EndedReason.SUPERSEDED, null);
            }
        }
        Outcome started = record(trigger, dueAt, manualBy, FiringOutcome.STARTED, null, now);
        TriggerConversations.Started conversation = firingConversations.start(companyId, trigger.id(),
                new FiringConversations.Place.Schedule(dueAt), 0, started.firingId(), manualBy);
        log.debug("Schedule {} started conversation {}", trigger.id(), conversation.conversation().id());
        return started;
    }

    private Outcome record(TriggerInfo trigger, Instant dueAt, @Nullable UUID manualBy, FiringOutcome outcome,
            @Nullable String reason, Instant now) {
        UUID firingId = Ids.newId();
        store.insertFiring(new PollStore.Firing(firingId, trigger.companyId(), trigger.id(), null, null,
                TriggerEvent.SCHEDULE, null, null, null, null, dueAt, manualBy, false, outcome, reason, 0,
                FiringTexts.schedule(dueAt, null), null, null, now));
        firings.audit(trigger, firingId, null, TriggerEvent.SCHEDULE, outcome, null, 0, manualBy);
        return new Outcome(firingId, outcome, null);
    }

    /** The schedule's next firing after now; null for a cron that stops firing or cannot be read. */
    static @Nullable Instant next(TriggerInfo trigger, Instant now) {
        if (trigger.schedule() == null) {
            return null;
        }
        try {
            return Schedule.parse(trigger.schedule().cron(), trigger.schedule().zone()).next(now).orElse(null);
        }
        catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
