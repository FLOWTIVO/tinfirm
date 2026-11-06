package com.flowtivo.tinfirm.trigger.internal;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.shared.security.SystemContext;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.trigger.TriggerAccount;
import com.flowtivo.tinfirm.trigger.TriggerEvent;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerInfo;
import com.flowtivo.tinfirm.trigger.TriggerSource;

import jakarta.annotation.PreDestroy;

/**
 * The polls' life (the package 4 contract, §3.9, §5.4): a tracker trigger created, given another table or resumed has
 * its source account's poll, made when there is none and due now; a poll that waited goes on after its account was
 * connected again, its holder resumed or its system resumed (package 6's switch, which moved its window to the
 * resume), and the company's lift wakes the polls of the triggers it re-arms (their {@code TriggerResumed}). A poll
 * without an active trigger turns {@code IDLE} at its next pass, and goes with its account. Each runs after the commit
 * of the change, in a transaction of its own, so that a poll's row is never locked after a trigger's (the lock order).
 * Also: a conversation that a {@code CLOSED} firing gave a turn ends
 * {@code SUBJECT_CLOSED} once that turn ended (§5.6, open point 2).
 */
@Component
class PollKeeper {

    private static final Logger log = LoggerFactory.getLogger(PollKeeper.class);

    private final PollStore store;

    private final TriggerStore triggers;

    private final TriggerConversations conversations;

    private final TransactionTemplate separate;

    private final TimeSource time;

    private final ExecutorService executor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("trigger-poll-keeper-", 0).factory());

    PollKeeper(PollStore store, TriggerStore triggers, TriggerConversations conversations,
            PlatformTransactionManager transactionManager, TimeSource time) {
        this.store = store;
        this.triggers = triggers;
        this.conversations = conversations;
        this.separate = new TransactionTemplate(transactionManager);
        this.separate.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.time = time;
    }

    @PreDestroy
    void shutdown() {
        executor.shutdownNow();
    }

    @TransactionalEventListener
    void created(TriggerEvents.TriggerCreated event) {
        ensure(event.companyId(), event.triggerId());
    }

    @TransactionalEventListener
    void accountsChanged(TriggerEvents.TriggerAccountsChanged event) {
        ensure(event.companyId(), event.triggerId());
    }

    @TransactionalEventListener
    void resumed(TriggerEvents.TriggerResumed event) {
        ensure(event.companyId(), event.triggerId());
    }

    @TransactionalEventListener
    void accountReconnected(AccountEvents.AccountReconnected event) {
        activate(event.companyId(), () -> store.pollsOfAccount(event.companyId(), event.accountId()));
    }

    /** A credential enabled again, or lent anew (ADR-0072): its polls may go on. */
    @TransactionalEventListener
    void credentialChanged(AccountEvents.CredentialChanged event) {
        activate(event.companyId(), () -> store.pollsOfAccount(event.companyId(), event.accountId()));
    }

    @TransactionalEventListener
    void principalResumed(PrincipalEvents.PrincipalResumed event) {
        activate(event.companyId(), () -> store.pollsOfHolder(event.companyId(), event.principalId()));
    }

    @TransactionalEventListener
    void systemResumed(SystemEvents.SystemResumed event) {
        activate(event.companyId(), () -> store.pollsOfSystem(event.companyId(), event.systemId()));
    }

    /**
     * The poll of the trigger's source account, made when there is none and due now. In a transaction of its own; the
     * poll's row is the only one it locks.
     */
    void ensure(java.util.UUID companyId, java.util.UUID triggerId) {
        try {
            SystemContext.runInCompany(companyId, () -> separate.executeWithoutResult(status -> {
                TriggerInfo trigger = triggers.find(companyId, triggerId).filter(TriggerInfo::active).orElse(null);
                if (trigger == null || trigger.source() != TriggerSource.TRACKER || trigger.systemId() == null) {
                    return;
                }
                TriggerAccount source = triggers.accounts(companyId, triggerId).stream()
                    .filter(row -> row.systemId().equals(trigger.systemId()) && row.accountId() != null)
                    .findFirst()
                    .orElse(null);
                if (source == null) {
                    return;
                }
                // A credential's poll is no service account's: it serves every trigger that names the credential.
                java.util.UUID holder = source.holderPrincipalId().equals(trigger.authorPrincipalId())
                        ? source.holderPrincipalId() : null;
                PollStore.PollRow poll = store.ensure(companyId, trigger.systemId(),
                        java.util.Objects.requireNonNull(source.accountId()), holder, time.now());
                store.activate(poll.id(), time.now());
            }));
        }
        catch (RuntimeException ex) {
            log.warn("The poll of trigger {} could not be made", triggerId, ex);
        }
    }

    private void activate(UUID companyId, java.util.function.Supplier<List<UUID>> polls) {
        try {
            SystemContext.runInCompany(companyId, () -> separate.executeWithoutResult(status -> {
                for (UUID pollId : polls.get()) {
                    store.activate(pollId, time.now());
                }
            }));
        }
        catch (RuntimeException ex) {
            log.warn("The polls of company {} could not go on", companyId, ex);
        }
    }

    /** A turn that a {@code CLOSED} firing gave ended: its conversation ends {@code SUBJECT_CLOSED}. */
    @TransactionalEventListener
    void turnRecorded(ConversationEvents.TurnRecorded event) {
        if (event.triggerId() == null || event.place() != ConversationInfo.Place.TRACKER_ISSUE) {
            return;
        }
        executor.execute(() -> {
            try {
                SystemContext.runInCompany(event.companyId(), () -> separate.executeWithoutResult(status -> {
                    if (!store.eventsTaken(event.runId()).contains(TriggerEvent.CLOSED)) {
                        return;
                    }
                    triggers.lock(event.companyId(), event.triggerId());
                    conversations.end(event.companyId(), event.conversationId(),
                            ConversationInfo.EndedReason.SUBJECT_CLOSED, null);
                }));
            }
            catch (RuntimeException ex) {
                log.warn("Conversation {} could not end after its subject closed", event.conversationId(), ex);
            }
        });
    }
}
