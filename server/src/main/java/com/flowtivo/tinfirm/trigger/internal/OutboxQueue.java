package com.flowtivo.tinfirm.trigger.internal;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.CompanyEvents;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.Conversations;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.ConversationLock;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.run.RunStatus;
import com.flowtivo.tinfirm.runner.RunnerMessages;
import com.flowtivo.tinfirm.shared.config.TinfirmProperties;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;
import com.flowtivo.tinfirm.trigger.PlaceWriter;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * Fills the write-back outbox in the transaction that commits its cause (the package 4 contract, §5.8, §5.9, T13,
 * T14), for the conversations of tracker triggers ({@code TRACKER_ISSUE}; a {@code SCHEDULE} conversation writes nothing
 * outside Tinfirm), and starts their delivery after the commit:
 * <ul>
 * <li>{@code TurnRecorded} (in the run's end transaction, after the hooks): the turn's open questions
 * ({@code QUESTION}); a succeeded turn's result and its actions ({@code RESULT}, {@code ON_SUCCESS}) or, with
 * {@code results.review}, the review of the result instead ({@code raiseResultReview}), its writes kept exactly
 * ({@code outbox_review}); a failed or lost turn's failure and its actions ({@code FAILURE}, {@code ON_FAILURE}),
 * unless it is retried by itself (ADR-0073). A cancelled turn writes nothing.</li>
 * <li>{@code ResultReviewed}: approved, the review's writes are queued (T14); returned, they go.</li>
 * <li>{@code DecisionDecided}: an answer the trigger's team or author gave in Tinfirm is written to the issue
 * ({@code ANSWER}, §5.8 and §11, point 4), once per answering message, quoting every question it answered.</li>
 * <li>{@code SystemWritten} of a change request the conversation opened: on Jira, a remote link to it
 * ({@code CHANGE_REQUEST}).</li>
 * </ul>
 * Nothing is queued for a place no {@code PlaceWriter} writes to; a chat's rows are its module's
 * ({@link PlaceOutboxImpl}, package 5), whose reviewed result this queues once approved. A row of a
 * paused trigger is queued {@code HELD} (§5.11). After the commit: an ended conversation's undelivered rows
 * are dropped (but a {@code SUBJECT_CLOSED} one's, whose last turn's result is still written; an author who leaves ends
 * none, §11, point 6), and its reviews go; a binding bound again, a trigger resumed (and its conversations rebound after
 * a reassignment) make their waiting rows due at once.
 */
@Component
class OutboxQueue implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(OutboxQueue.class);

    private final OutboxStore store;

    private final TriggerStore triggers;

    private final Conversations conversations;

    private final TriggerConversations triggerConversations;

    private final ConversationBindings bindings;

    private final ConversationLock locks;

    private final Systems systems;

    private final Agents agents;

    private final RunReads runs;

    private final Principals principals;

    private final OutboxDelivery delivery;

    private final PlaceOutboxImpl placeOutbox;

    private final TinfirmProperties tinfirm;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final TimeSource time;

    private final ExecutorService executor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("trigger-outbox-queue-", 0).factory());

    OutboxQueue(OutboxStore store, TriggerStore triggers, Conversations conversations,
            TriggerConversations triggerConversations, ConversationBindings bindings, ConversationLock locks,
            Systems systems, Agents agents, RunReads runs, Principals principals, OutboxDelivery delivery,
            PlaceOutboxImpl placeOutbox, TinfirmProperties tinfirm, SystemTransactions transactions,
            AuditLog auditLog, TimeSource time) {
        this.store = store;
        this.triggers = triggers;
        this.conversations = conversations;
        this.triggerConversations = triggerConversations;
        this.bindings = bindings;
        this.locks = locks;
        this.systems = systems;
        this.agents = agents;
        this.runs = runs;
        this.principals = principals;
        this.delivery = delivery;
        this.placeOutbox = placeOutbox;
        this.tinfirm = tinfirm;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.time = time;
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }

    /** A trigger's conversation's place, its trigger and what writes there. */
    private record Context(OutboxPlans.Place place, ConversationInfo conversation, TriggerInfo trigger) {
    }

    // ---- causes, in their transaction ------------------------------------------------------------------------------

    @EventListener
    void turnRecorded(ConversationEvents.TurnRecorded event) {
        if (event.place() != ConversationInfo.Place.TRACKER_ISSUE || event.triggerId() == null
                || event.status() == RunStatus.CANCELLED) {
            return;
        }
        Context context = context(event.companyId(), event.conversationId()).orElse(null);
        RunInfo run = runs.find(event.companyId(), event.runId()).orElse(null);
        if (context == null || run == null) {
            return;
        }
        List<TriggerConversations.Question> questions = triggerConversations.openQuestionsOf(event.companyId(),
                event.runId());
        if (event.status() == RunStatus.SUCCEEDED) {
            boolean completed = event.outcome() == RunnerMessages.ResultOutcome.COMPLETED
                    || event.outcome() == null && questions.isEmpty();
            if (completed) {
                OutboxPlans.Plan plan = OutboxPlans.success(context.place(), run,
                        triggerConversations.outputsOf(event.companyId(), event.runId()));
                if (!plan.isEmpty() && context.trigger().results().review()) {
                    review(context, run, plan);
                }
                else {
                    plan.rows().forEach(row -> queue(context, row));
                }
            }
        }
        else if ((event.status() == RunStatus.FAILED || event.status() == RunStatus.LOST) && !event.retrying()) {
            // A failure retried by itself is not written yet (ADR-0073): the issue hears of the last one, and an
            // on-failure action such as a close never ends the conversation under its retry.
            OutboxPlans.failure(context.place(), run).rows().forEach(row -> queue(context, row));
        }
        OutboxPlans.questions(context.place(), event.runId(), questions).ifPresent(row -> queue(context, row));
    }

    /** T14: the review instead of the writes, which are kept exactly as the team is shown them. */
    private void review(Context context, RunInfo run, OutboxPlans.Plan plan) {
        if (!context.conversation().open()) {
            log.info("The result of run {} is not reviewed: conversation {} ended", run.id(),
                    context.conversation().id());
            return;
        }
        String summary = run.result() != null && !run.result().summary().isBlank() ? run.result().summary()
                : run.summary() != null ? run.summary() : "";
        UUID decisionId = triggerConversations.raiseResultReview(run.companyId(), context.conversation().id(),
                run.id(), summary, plan.items());
        store.insertReview(run.companyId(), decisionId, context.conversation().id(), run.id(), plan.rows(),
                time.now());
    }

    @EventListener
    void resultReviewed(ConversationEvents.ResultReviewed event) {
        Optional<List<OutboxStore.NewRow>> rows = store.takeReview(event.companyId(), event.decisionId());
        if (rows.isEmpty() || !event.approved()) {
            return;
        }
        if (rows.get().stream().allMatch(row -> row.writtenBy() == PlaceWriter.Writer.BOT)) {
            // A chat's rows (package 5), which its module queued for the review: its bot writes them.
            placeOutbox.queueReviewed(event.companyId(), event.conversationId(), rows.get());
            return;
        }
        Context context = context(event.companyId(), event.conversationId()).orElse(null);
        if (context == null) {
            return;
        }
        rows.get().forEach(row -> queue(context, row));
    }

    /**
     * An answer the trigger's team or author gave in Tinfirm (it names no commenter), in the deciding transaction:
     * once per message (its cause key), quoting every question it answered, which the conversation module decided
     * before it announced any of them.
     */
    @EventListener
    void decisionDecided(ConversationEvents.DecisionDecided event) {
        if (!"QUESTION".equals(event.kind()) || !"ANSWERED".equals(event.outcome())
                || event.decidedByPrincipalId() == null || event.decidedByName() != null) {
            return;
        }
        answered(event);
    }

    private void answered(ConversationEvents.DecisionDecided event) {
        Context context = context(event.companyId(), event.conversationId()).orElse(null);
        TriggerConversations.Answer answer = triggerConversations.answerOf(event.companyId(), event.decisionId())
            .orElse(null);
        if (context == null || answer == null || answer.byPrincipalId() == null) {
            return;
        }
        String member = principals.displayNames(event.companyId(), List.of(answer.byPrincipalId()))
            .getOrDefault(answer.byPrincipalId(), "A member");
        queue(context, OutboxPlans.answer(context.place(), answer, member));
    }

    /** A change request the conversation opened: on Jira, linked to the issue. */
    @EventListener
    void systemWritten(ConversationEvents.SystemWritten event) {
        String url = event.values().url();
        if (!"CHANGE_REQUEST".equals(event.kind()) || event.outboxId() != null || url == null) {
            return;
        }
        Context context = context(event.companyId(), event.conversationId()).orElse(null);
        if (context == null) {
            return;
        }
        String title = event.values().title() != null ? event.values().title() : event.subjectKey();
        OutboxPlans.changeRequest(context.place(), event.values().outputId(), url, title)
            .ifPresent(row -> queue(context, row));
    }

    // ---- after the commit ------------------------------------------------------------------------------------------

    /**
     * The conversation ended: its undelivered rows are dropped with the reason, but a {@code SUBJECT_CLOSED} one's,
     * whose last turn's result is still written; its reviews go, their decisions cancelled with it.
     */
    @TransactionalEventListener
    void conversationEnded(ConversationEvents.ConversationEnded event) {
        later("the end of conversation " + event.conversationId(), () -> transactions.runInCompany(
                event.companyId(), () -> dropEnded(event.companyId(), event.conversationId(), event.reason())));
    }

    private void dropEnded(UUID companyId, UUID conversationId, ConversationInfo.EndedReason reason) {
        locks.lock(companyId, conversationId);
        store.dropReviews(companyId, conversationId);
        if (reason == ConversationInfo.EndedReason.SUBJECT_CLOSED) {
            delivery.requestPassAfterCommit();
            return;
        }
        Instant now = time.now();
        String why = "the conversation ended (" + reason + ")";
        List<OutboxStore.Stored> dropped = new java.util.ArrayList<>();
        for (OutboxStore.Stored stored : store.ofConversation(companyId, conversationId)) {
            if (("PENDING".equals(stored.status()) || "HELD".equals(stored.status()))
                    && store.drop(companyId, stored.row().id(), why, stored.row().attempts(), now)) {
                dropped.add(stored);
            }
        }
        for (OutboxStore.Stored stored : dropped) {
            Map<String, @Nullable Object> details = new HashMap<>();
            details.put("conversationId", conversationId.toString());
            details.put("systemId", stored.row().systemId().toString());
            details.put("subjectKey", stored.row().subjectKey());
            details.put("kind", stored.row().kind().name());
            details.put("cause", stored.row().cause().name());
            details.put("attempts", stored.row().attempts());
            details.put("outcome", "DROPPED");
            details.put("reason", why);
            auditLog.record(AuditRecord.company(companyId, AuditAction.SOURCE_WRITTEN, CurrentActor.actor())
                .target(OutboxDelivery.TARGET, stored.row().id())
                .details(details)
                .build());
        }
    }

    /** A binding bound again: the rows that waited on it are due now. */
    @TransactionalEventListener
    void bindingBound(ConversationEvents.BindingBound event) {
        later("the binding " + event.bindingId(), () -> {
            int due = transactions.inCompany(event.companyId(), () -> {
                locks.lock(event.companyId(), event.conversationId());
                return store.dueNowOfBinding(event.companyId(), event.bindingId(), time.now());
            });
            if (due > 0) {
                delivery.requestPass();
            }
        });
    }

    /**
     * A system resumed (package 6): the rows its pause put off are due now, rather than at their next attempt.
     */
    @TransactionalEventListener
    void systemResumed(SystemEvents.SystemResumed event) {
        later("the resume of the system " + event.systemId(), () -> {
            int due = transactions.inCompany(event.companyId(), () -> store.dueNowOfSystem(event.companyId(),
                    event.systemId(), time.now()));
            if (due > 0) {
                delivery.requestPass();
            }
        });
    }

    /** The company's stop was lifted (package 6): the rows it put off are due now. */
    @TransactionalEventListener
    void runsResumed(CompanyEvents.RunsResumed event) {
        later("the lift of the company's stop", () -> {
            int due = transactions.inCompany(event.companyId(), () -> store.dueNowOfCompany(event.companyId(),
                    time.now()));
            if (due > 0) {
                delivery.requestPass();
            }
        });
    }

    /**
     * A trigger resumed (its held rows released, its conversations rebound after a reassignment, §11) or its wait
     * repaired: its conversations' waiting rows are due now, written with the bindings they hold now.
     */
    @TransactionalEventListener
    void triggerResumed(TriggerEvents.TriggerResumed event) {
        later("the trigger " + event.triggerId(), () -> {
            transactions.runInCompany(event.companyId(), () -> {
                // Its conversations with rows to deliver, locked in the order of their ids before their rows.
                List<UUID> waiting = store.conversationsWithPending(event.companyId(),
                        triggerConversations.allOf(event.companyId(), event.triggerId()));
                waiting.forEach(id -> locks.lock(event.companyId(), id));
                store.dueNow(event.companyId(), waiting, time.now());
            });
            delivery.requestPass();
        });
    }

    // ---- queueing --------------------------------------------------------------------------------------------------

    /** Queues the row in the caller's transaction, held while the trigger is paused; delivered after the commit. */
    private void queue(Context context, OutboxStore.NewRow row) {
        store.insert(context.conversation().companyId(), context.conversation().id(), row,
                context.trigger().paused(), time.now())
            .ifPresent(id -> delivery.requestPassAfterCommit());
    }

    /** The conversation's place when it is a live tracker trigger's, with its binding on the subject's system. */
    private Optional<Context> context(UUID companyId, UUID conversationId) {
        ConversationInfo conversation = conversations.find(companyId, conversationId).orElse(null);
        if (conversation == null || conversation.place() != ConversationInfo.Place.TRACKER_ISSUE
                || !delivery.writes(conversation.place()) || conversation.triggerId() == null
                || conversation.subject() == null
                || conversation.subject().systemId() == null) {
            return Optional.empty();
        }
        TriggerInfo trigger = triggers.find(companyId, conversation.triggerId()).filter(TriggerInfo::live)
            .orElse(null);
        UUID systemId = conversation.subject().systemId();
        SystemInfo system = systems.find(companyId, systemId).orElse(null);
        BindingInfo binding = bindings.of(companyId, conversationId, systemId).orElse(null);
        if (trigger == null || system == null || binding == null) {
            log.info("Nothing is written to the issue of conversation {}: its trigger, system or binding is gone",
                    conversationId);
            return Optional.empty();
        }
        String agentName = agents.find(companyId, conversation.agentId()).map(AgentInfo::name).orElse("An agent");
        String url = tinfirm.publicBaseUrl() + "/c/" + companyId + "/conversations/" + conversationId;
        OutboxPlans.Place place = new OutboxPlans.Place(conversationId, conversation.key(), systemId, system.kind(),
                binding.id(), conversation.subject().key(), trigger.name(), trigger.results(), agentName, url,
                store.queued(companyId, conversationId, OutboxPlans.CONVERSATION_LINK));
        return Optional.of(new Context(place, conversation, trigger));
    }

    /** Work after the commit, on a thread of its own: a transaction's after-commit callbacks still hold its connection. */
    private void later(String what, Runnable work) {
        executor.execute(() -> {
            try {
                work.run();
            }
            catch (RuntimeException ex) {
                log.warn("The write-back's work after {} failed", what, ex);
            }
        });
    }
}
