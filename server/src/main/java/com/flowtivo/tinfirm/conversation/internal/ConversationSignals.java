package com.flowtivo.tinfirm.conversation.internal;

import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.run.RunEvents;

/**
 * In-process fan-out of "the conversation changed" signals to its live streams ({@link ConversationStreams}; one
 * replica, as the run stream's hub: an accepted limit). A signal names the parts that changed and carries no data: the
 * client reads the conversation again, through the one read path with its visibility and its audit. The parts one
 * transaction changes go as one signal once it has committed, and nothing goes when it rolls back; without a
 * transaction, at once. Signalled by the conversation module's writes ({@link ConversationStore}, the outputs, the
 * attachments) and by the events it already publishes or hears (its bindings and decisions, the run module's).
 */
@Component
class ConversationSignals {

    private static final Logger log = LoggerFactory.getLogger(ConversationSignals.class);

    /** What changed, as {@code conversation-changed} names it. */
    enum Part {
        /** Its state, what it waits on, its turn's status, its active run, its title. */
        STATUS,
        MESSAGES,
        /** Its turns: a run created, started, waiting for another reason, ended. */
        TURNS,
        DECISIONS,
        BINDINGS,
        OUTPUTS,
        ATTACHMENTS,
        /** Its workspace and the network hosts it was granted. */
        WORKSPACE
    }

    private final ConcurrentMap<UUID, Set<Consumer<Set<Part>>>> listeners = new ConcurrentHashMap<>();

    /** Registers a stream's listener; close the returned subscription to remove it. */
    AutoCloseable subscribe(UUID conversationId, Consumer<Set<Part>> listener) {
        listeners.computeIfAbsent(conversationId, id -> ConcurrentHashMap.newKeySet()).add(listener);
        return () -> listeners.computeIfPresent(conversationId, (id, set) -> {
            set.remove(listener);
            return set.isEmpty() ? null : set;
        });
    }

    /** The conversation's parts changed: signalled once the current transaction has committed, at once without one. */
    void changed(UUID conversationId, Part first, Part... more) {
        EnumSet<Part> parts = EnumSet.of(first, more);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            fire(conversationId, parts);
            return;
        }
        @SuppressWarnings("unchecked")
        Map<UUID, EnumSet<Part>> pending = (Map<UUID, EnumSet<Part>>) TransactionSynchronizationManager
            .getResource(this);
        if (pending == null) {
            Map<UUID, EnumSet<Part>> collected = new LinkedHashMap<>();
            TransactionSynchronizationManager.bindResource(this, collected);
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {

                @Override
                public void afterCommit() {
                    collected.forEach(ConversationSignals.this::fire);
                }

                @Override
                public void afterCompletion(int status) {
                    TransactionSynchronizationManager.unbindResourceIfPossible(ConversationSignals.this);
                }
            });
            pending = collected;
        }
        pending.computeIfAbsent(conversationId, id -> EnumSet.noneOf(Part.class)).addAll(parts);
    }

    private void fire(UUID conversationId, Set<Part> parts) {
        Set<Consumer<Set<Part>>> set = listeners.get(conversationId);
        if (set == null) {
            return;
        }
        Set<Part> signalled = Collections.unmodifiableSet(EnumSet.copyOf(parts));
        for (Consumer<Set<Part>> listener : set) {
            try {
                listener.accept(signalled);
            }
            catch (RuntimeException ex) {
                log.warn("A stream of conversation {} failed its signal", conversationId, ex);
            }
        }
    }

    // ---- the events the module publishes or hears, in their transactions --------------------------------------------

    @EventListener
    void bindingBound(ConversationEvents.BindingBound event) {
        changed(event.conversationId(), Part.BINDINGS);
    }

    @EventListener
    void bindingWaiting(ConversationEvents.BindingWaiting event) {
        changed(event.conversationId(), Part.BINDINGS);
    }

    @EventListener
    void bindingGone(ConversationEvents.BindingGone event) {
        changed(event.conversationId(), Part.BINDINGS);
    }

    @EventListener
    void rebound(ConversationEvents.ConversationRebound event) {
        changed(event.conversationId(), Part.BINDINGS, Part.STATUS);
    }

    @EventListener
    void decisionRaised(ConversationEvents.DecisionRaised event) {
        changed(event.conversationId(), Part.DECISIONS);
    }

    @EventListener
    void decisionDecided(ConversationEvents.DecisionDecided event) {
        changed(event.conversationId(), Part.DECISIONS);
    }

    @EventListener
    void decisionClosed(ConversationEvents.DecisionClosed event) {
        changed(event.conversationId(), Part.DECISIONS);
    }

    @EventListener
    void resultReviewed(ConversationEvents.ResultReviewed event) {
        changed(event.conversationId(), Part.DECISIONS);
    }

    @EventListener
    void ended(ConversationEvents.ConversationEnded event) {
        changed(event.conversationId(), Part.STATUS);
    }

    @EventListener
    void runAccepted(RunEvents.RunAccepted event) {
        changed(event.conversationId(), Part.TURNS);
    }

    @EventListener
    void runQueueReasonChanged(RunEvents.RunQueueReasonChanged event) {
        changed(event.conversationId(), Part.TURNS);
    }

    @EventListener
    void runResumed(RunEvents.RunResumed event) {
        changed(event.conversationId(), Part.TURNS);
    }
}
