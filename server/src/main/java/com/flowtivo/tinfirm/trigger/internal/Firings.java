package com.flowtivo.tinfirm.trigger.internal;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.AgentStatus;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.SystemPolls;
import com.flowtivo.tinfirm.trigger.FiringOutcome;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerGate;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerRepeat;

/**
 * Firings: events to conversations (the package 4 contract, §5.6 with §11, points 1, 10, 12 and 15), in the subject's
 * transaction (a poll's, or a run now's), which locked the snapshot and then the trigger's row. In order:
 * <ol>
 * <li><b>the gate</b> ({@link TriggerGate#check}): a pause, or a wait that stops firing, fires nothing and records
 * nothing (T7, T16); package 6's switches among them: a system an admin paused pauses the trigger
 * {@code SYSTEM_PAUSED}, the company's stop makes it wait {@code COMPANY_STOPPED} (the package 6 contract, §5.4);</li>
 * <li><b>the agent</b> paused: nothing fires, nothing is recorded;</li>
 * <li><b>the rate</b>: past {@code max_firings_per_hour} within the last hour, {@code SKIPPED_RATE}, counted in
 * {@code rate_skipped_count} and recorded once an hour, which the inbox shows once an hour;</li>
 * <li><b>the chain</b> (row 10): the first read of a subject Tinfirm created at depth <i>d</i> fires at depth
 * <i>d</i> + 1; beyond 3 it is {@code SKIPPED_CHAIN};</li>
 * <li><b>the conversation</b> (open point 1): the trigger's open conversation on the subject takes it
 * ({@code CONTINUED}: an {@code EVENT} turn now, or the next turn), else a new conversation starts ({@code STARTED})
 * with the subject's summary and the firing as its first turn's request;</li>
 * <li><b>{@code ONCE}</b>: a firing of the trigger on the subject that another replica recorded first drops this one
 * (T10).</li>
 * </ol>
 * A comment that fires is also stored as the conversation's {@code EXTERNAL} message. Audited as {@code trigger.fired}.
 */
@Component
class Firings {

    /** The deepest chain of triggered conversations (row 10). */
    static final int MAX_CHAIN = 3;

    private static final Duration HOUR = Duration.ofHours(1);

    private final PollStore store;

    private final TriggerGate gate;

    private final Agents agents;

    private final FiringConversations firingConversations;

    private final TriggerConversations conversations;

    private final AuditLog auditLog;

    private final TimeSource time;

    Firings(PollStore store, TriggerGate gate, Agents agents, FiringConversations firingConversations,
            TriggerConversations conversations, AuditLog auditLog, TimeSource time) {
        this.store = store;
        this.gate = gate;
        this.agents = agents;
        this.firingConversations = firingConversations;
        this.conversations = conversations;
        this.auditLog = auditLog;
        this.time = time;
    }

    /**
     * A subject's event for one trigger.
     *
     * @param systemId     the trigger's source system
     * @param chainDepth   the firing's depth: one more than the creating conversation's on the first read of a subject
     *                     Tinfirm created, else 0
     * @param account      the poll's account as people know it, for the request's words
     * @param manualBy     run now: the user, and {@code manualByName} their name
     */
    record SubjectEvent(TriggerInfo trigger, UUID systemId, SystemPolls.Subject subject, EventDetector.Event event,
            int chainDepth, @Nullable String account, @Nullable UUID manualBy, @Nullable String manualByName) {
    }

    /**
     * What a firing did.
     *
     * @param outcome  null when nothing was recorded: the gate or the agent stopped it, or a {@code ONCE} firing or a
     *                 rate skip was recorded first
     * @param stopped  why nothing fired, in words, when nothing did
     */
    record Fired(@Nullable UUID firingId, @Nullable FiringOutcome outcome, @Nullable UUID conversationId,
            @Nullable String stopped) {

        static Fired stopped(String why) {
            return new Fired(null, null, null, why);
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    Fired fire(SubjectEvent fired) {
        TriggerInfo trigger = fired.trigger();
        UUID companyId = trigger.companyId();
        TriggerGate.Result gated = gate.check(companyId, trigger.id());
        if (!gated.fires()) {
            return Fired.stopped(gateWords(gated));
        }
        Optional<AgentInfo> agent = agents.find(companyId, trigger.agentId()).filter(AgentInfo::live);
        if (agent.isEmpty() || agent.get().status() == AgentStatus.PAUSED) {
            return Fired.stopped("its agent is paused");
        }
        Instant now = time.now();
        SystemPolls.Subject subject = fired.subject();
        EventDetector.Event event = fired.event();
        boolean once = trigger.repeat() == TriggerRepeat.ONCE && fired.manualBy() == null;
        String request = FiringTexts.request(subject, event, fired.account(), fired.manualByName());
        if (store.firedSince(trigger.id(), now.minus(HOUR)) >= trigger.maxFiringsPerHour()) {
            store.countRateSkipped(trigger.id());
            if (store.recordedSince(trigger.id(), FiringOutcome.SKIPPED_RATE, now.minus(HOUR))) {
                return new Fired(null, FiringOutcome.SKIPPED_RATE, null, "it fired " + trigger.maxFiringsPerHour()
                        + " times within the last hour");
            }
            return record(fired, FiringOutcome.SKIPPED_RATE, "More than " + trigger.maxFiringsPerHour()
                    + " firings within an hour", false, request, null, null, now);
        }
        if (fired.chainDepth() > MAX_CHAIN) {
            return record(fired, FiringOutcome.SKIPPED_CHAIN, subject.key() + " was created at the end of a chain of "
                    + MAX_CHAIN + " triggered conversations", false, request, null, null, now);
        }
        Optional<ConversationInfo> open = conversations.openOnSubject(companyId, trigger.id(), subject.id());
        if (open.isPresent()) {
            UUID conversationId = open.get().id();
            Fired continued = record(fired, FiringOutcome.CONTINUED, null, once, request, null, conversationId, now);
            if (continued.firingId() == null) {
                return continued;
            }
            if (event.comment() != null) {
                conversations.external(companyId, conversationId, external(event.comment(), true));
            }
            firingConversations.continueWith(companyId, conversationId);
            return continued;
        }
        Fired started = record(fired, FiringOutcome.STARTED, null, once, request, FiringTexts.summary(subject), null,
                now);
        if (started.firingId() == null) {
            return started;
        }
        TriggerConversations.Started conversation = firingConversations.start(companyId, trigger.id(),
                new FiringConversations.Place.Subject(new TriggerConversations.TrackerSubject(fired.systemId(),
                        subject.id(), subject.key(), subject.kind().name(), subject.url(), subject.title())),
                Math.min(fired.chainDepth(), MAX_CHAIN), started.firingId(), fired.manualBy());
        if (event.comment() != null) {
            conversations.external(companyId, conversation.conversation().id(), external(event.comment(), false));
        }
        return new Fired(started.firingId(), FiringOutcome.STARTED, conversation.conversation().id(), null);
    }

    /** Inserts and audits the firing; a {@code ONCE} firing another replica recorded first is not recorded (T10). */
    private Fired record(SubjectEvent fired, FiringOutcome outcome, @Nullable String reason, boolean once,
            String request, @Nullable String summary, @Nullable UUID conversationId, Instant now) {
        TriggerInfo trigger = fired.trigger();
        EventDetector.Event event = fired.event();
        UUID id = Ids.newId();
        boolean inserted = store.insertFiring(new PollStore.Firing(id, trigger.companyId(), trigger.id(),
                fired.subject().id(), fired.subject().key(), event.event(), event.value(),
                FiringTexts.actor(event.actor()), event.comment() != null ? event.comment().id() : null,
                event.changedAt(), null, fired.manualBy(), once, outcome, reason, Math.min(fired.chainDepth(),
                        MAX_CHAIN + 1), request, summary, conversationId, now));
        if (!inserted) {
            return Fired.stopped("it fired on " + fired.subject().key() + " once already");
        }
        audit(trigger, id, fired.subject().key(), event.event(), outcome, conversationId, fired.chainDepth(),
                fired.manualBy());
        return new Fired(id, outcome, conversationId, null);
    }

    void audit(TriggerInfo trigger, UUID firingId, @Nullable String subjectKey, TriggerEvent event,
            FiringOutcome outcome, @Nullable UUID conversationId, int depth, @Nullable UUID manualBy) {
        auditLog.record(AuditRecord.company(trigger.companyId(), AuditAction.TRIGGER_FIRED, CurrentActor.actor())
            .target(TriggerStates.TARGET, trigger.id())
            .detail("firingId", firingId.toString())
            .detail("subjectKey", subjectKey)
            .detail("event", event.name())
            .detail("outcome", outcome.name())
            .detail("conversationId", conversationId != null ? conversationId.toString() : null)
            .detail("chainDepth", depth)
            .detail("manual", manualBy != null)
            .build());
    }

    /** The comment as the conversation stores it ({@code EXTERNAL}, §3.6). */
    static TriggerConversations.ExternalComment external(SystemPolls.Comment comment, boolean answers) {
        Map<String, String> author = new java.util.LinkedHashMap<>();
        if (comment.author() != null) {
            if (comment.author().id() != null) {
                author.put("id", comment.author().id());
            }
            if (comment.author().login() != null) {
                author.put("login", comment.author().login());
            }
            author.put("name", comment.author().shown());
        }
        return new TriggerConversations.ExternalComment(comment.id(), author, null, comment.markdown(),
                comment.created(), answers);
    }

    static String gateWords(TriggerGate.Result result) {
        return switch (result) {
            case TriggerGate.Result.Paused paused -> "it is paused (" + paused.reason() + ")";
            case TriggerGate.Result.Waits waits -> "it waits (" + waits.reason() + ")";
            case TriggerGate.Result.Gone gone -> "it is deleted";
            case TriggerGate.Result.Passes passes -> "it may fire";
        };
    }
}
