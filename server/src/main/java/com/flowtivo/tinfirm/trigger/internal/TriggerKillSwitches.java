package com.flowtivo.tinfirm.trigger.internal;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.agent.AgentEvents;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupDependents;
import com.flowtivo.tinfirm.company.GroupEvents;
import com.flowtivo.tinfirm.company.MemberEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.conversation.TriggerConversations;
import com.flowtivo.tinfirm.model.ModelEvents;
import com.flowtivo.tinfirm.principal.PrincipalDependents;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.trigger.PausedReason;
import com.flowtivo.tinfirm.trigger.TriggerEvents;
import com.flowtivo.tinfirm.trigger.TriggerInfo;

/**
 * What other modules' changes do to triggers (the package 4 contract, §5.3, §5.11 with §11): the kill switches.
 * <ul>
 * <li>In the change's own transaction, where a trigger must not outlive what it names past the commit (the deferred
 * {@code trigger_table_ck}): an author who leaves pauses their triggers {@code AUTHOR_LEFT}, their conversations' turns
 * cancelled and taking none until a reassignment's resume (§11, open point 6); a service identity a live trigger names
 * is not deleted ({@code 409 SERVICE_IDENTITY_IN_USE}); a disconnected account pauses the triggers whose row named it
 * ({@code ACCOUNT_REMOVED}); a removed system deletes the triggers on it ({@code system.deleted}) and pauses those with a
 * row on it ({@code SYSTEM_REMOVED}); a deleted agent deletes its triggers ({@code agent.deleted}), their conversations
 * ending {@code AGENT_DELETED}; a deleted group leaves its triggers without a team, their open approvals moving to the
 * author.</li>
 * <li>After the commit, on a virtual thread as SYSTEM, each trigger in a transaction of its own: the gate
 * ({@link TriggerGate}) for the triggers an event concerns (an account that needs signing in again, a suspended or
 * resumed principal, a change of who may use a service identity or the agent, a changed model key or model access, an
 * author's role changed: a member made VIEWER, by an admin, the identity provider's claim or SCIM, pauses their
 * triggers {@code NOT_ENTITLED}, M7 of the security review of 2026-10-27).</li>
 * </ul>
 * Each transaction locks the triggers by id, then their conversations (CONVENTIONS §10).
 */
@Component
class TriggerKillSwitches implements PrincipalDependents, GroupDependents, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(TriggerKillSwitches.class);

    /** The triggers paused in this transaction because a system's account went, by system (for SYSTEM_REMOVED). */
    private static final String DISCONNECTED = TriggerKillSwitches.class.getName() + ".disconnected";

    private final TriggerStore store;

    private final TriggerStates states;

    private final TriggerGateImpl gate;

    private final TriggerConversations conversations;

    private final Principals principals;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final org.springframework.context.ApplicationEventPublisher events;

    private final TimeSource time;

    private final ExecutorService executor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("trigger-gate-", 0).factory());

    TriggerKillSwitches(TriggerStore store, TriggerStates states, TriggerGateImpl gate,
            TriggerConversations conversations, Principals principals, SystemTransactions transactions,
            AuditLog auditLog, org.springframework.context.ApplicationEventPublisher events, TimeSource time) {
        this.store = store;
        this.states = states;
        this.gate = gate;
        this.conversations = conversations;
        this.principals = principals;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- in the change's transaction -------------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void memberLeaving(UUID companyId, UUID principalId, UUID userId) {
        for (UUID triggerId : store.liveOfAuthor(companyId, principalId)) {
            store.lock(companyId, triggerId)
                .ifPresent(trigger -> states.pause(trigger, PausedReason.AUTHOR_LEFT, null, true));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void principalDeleting(UUID companyId, UUID principalId) {
        List<String> names = store.liveNamesUsing(companyId, principalId);
        if (!names.isEmpty()) {
            throw ApiException.conflict(ProblemCode.SERVICE_IDENTITY_IN_USE, "Live triggers use this service "
                    + "identity's accounts or budget: " + String.join(", ", names) + ". Change or delete them first.",
                    Map.of("details", Map.of("triggers", names)));
        }
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void groupDeleting(UUID companyId, UUID groupId) {
        for (UUID triggerId : store.liveOfTeam(companyId, groupId)) {
            TriggerInfo trigger = store.lock(companyId, triggerId).orElse(null);
            if (trigger == null) {
                continue;
            }
            store.setTeam(companyId, triggerId, null, time.now());
            conversations.approversChanged(companyId, triggerId, null, trigger.authorPrincipalId());
            auditLog.record(AuditRecord.company(companyId, AuditAction.TRIGGER_CHANGED, CurrentActor.actor())
                .target(TriggerStates.TARGET, triggerId)
                .details(TriggerViews.auditDetails(trigger))
                .detail("teamGroupId", null)
                .detail("changed", List.of("team"))
                .detail("cause", "group.deleted")
                .build());
            events.publishEvent(new TriggerEvents.TriggerChanged(companyId, triggerId, Set.of("team")));
        }
    }

    /** Before the conversation module ends the agent's conversations: the trigger's row is locked first. */
    @EventListener
    @Order(0)
    void agentDeleted(AgentEvents.AgentDeleted event) {
        for (TriggerInfo trigger : store.liveOfAgent(event.companyId(), event.agentId())) {
            store.lock(event.companyId(), trigger.id())
                .ifPresent(locked -> states.delete(locked, "agent.deleted", null,
                        ConversationInfo.EndedReason.AGENT_DELETED));
        }
    }

    /** The account went; the rows that named it hold no account now (their foreign key): those triggers pause. */
    @EventListener
    void accountDisconnected(AccountEvents.AccountDisconnected event) {
        for (UUID triggerId : store.liveWithoutAccountOn(event.companyId(), event.systemId())) {
            TriggerInfo trigger = store.lock(event.companyId(), triggerId).orElse(null);
            if (trigger == null) {
                continue;
            }
            states.pause(trigger, PausedReason.ACCOUNT_REMOVED, null, false);
            disconnected().computeIfAbsent(event.systemId(), id -> new LinkedHashSet<>()).add(triggerId);
        }
    }

    /**
     * The system went, its accounts first: the triggers on it are deleted ({@code system.deleted}); the others with a
     * row on it, paused when its account went, pause for that ({@code SYSTEM_REMOVED}), the row gone with the system.
     */
    @EventListener
    void systemDeleted(SystemEvents.SystemDeleted event) {
        for (TriggerInfo trigger : store.liveOnSource(event.companyId(), event.systemId())) {
            store.lock(event.companyId(), trigger.id())
                .ifPresent(locked -> states.delete(locked, "system.deleted", null,
                        ConversationInfo.EndedReason.TRIGGER_DELETED));
        }
        Set<UUID> paused = disconnected().getOrDefault(event.systemId(), Set.of());
        for (UUID triggerId : paused) {
            store.lock(event.companyId(), triggerId)
                .filter(TriggerInfo::live)
                .filter(trigger -> trigger.pausedReason() == PausedReason.ACCOUNT_REMOVED)
                .ifPresent(trigger -> {
                    store.pause(event.companyId(), triggerId, PausedReason.SYSTEM_REMOVED, null, time.now());
                    auditLog.record(AuditRecord.company(event.companyId(), AuditAction.TRIGGER_PAUSED,
                            CurrentActor.actor())
                        .target(TriggerStates.TARGET, triggerId)
                        .detail("reason", PausedReason.SYSTEM_REMOVED.name())
                        .detail("systemId", event.systemId().toString())
                        .build());
                    events.publishEvent(new TriggerEvents.TriggerPaused(event.companyId(), triggerId,
                            PausedReason.SYSTEM_REMOVED));
                });
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, Set<UUID>> disconnected() {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return new HashMap<>();
        }
        Map<UUID, Set<UUID>> memo = (Map<UUID, Set<UUID>>) TransactionSynchronizationManager.getResource(DISCONNECTED);
        if (memo == null) {
            memo = new HashMap<>();
            TransactionSynchronizationManager.bindResource(DISCONNECTED, memo);
            TransactionSynchronizationManager.registerSynchronization(
                    new org.springframework.transaction.support.TransactionSynchronization() {
                        @Override
                        public void afterCompletion(int status) {
                            TransactionSynchronizationManager.unbindResourceIfPossible(DISCONNECTED);
                        }
                    });
        }
        return memo;
    }

    // ---- after the commit: the gate ----------------------------------------------------------------------------------

    @TransactionalEventListener
    void accountNeedsReconnect(AccountEvents.AccountNeedsReconnect event) {
        later(event.companyId(), () -> store.liveWithAccount(event.companyId(), event.accountId()));
    }

    @TransactionalEventListener
    void accountReconnected(AccountEvents.AccountReconnected event) {
        later(event.companyId(), () -> store.liveWithAccount(event.companyId(), event.accountId()));
    }

    /**
     * A credential was disabled or enabled, or a service account's lending changed (ADR-0072): the triggers that name
     * it pass the gate again, which pauses those whose service account no longer borrows it (withdrawn) or whose
     * credential is disabled; their authors resume them once it works again. A replaced lending changed their rows in
     * its transaction ({@link TriggerLendings}).
     */
    @TransactionalEventListener
    void credentialChanged(AccountEvents.CredentialChanged event) {
        later(event.companyId(), () -> store.liveWithAccount(event.companyId(), event.accountId()));
    }

    @TransactionalEventListener
    void principalSuspended(PrincipalEvents.PrincipalSuspended event) {
        later(event.companyId(), () -> store.liveNaming(event.companyId(), event.principalId()));
    }

    @TransactionalEventListener
    void principalResumed(PrincipalEvents.PrincipalResumed event) {
        later(event.companyId(), () -> store.liveNaming(event.companyId(), event.principalId()));
    }

    @TransactionalEventListener
    void serviceIdentityUsersChanged(PrincipalEvents.ServiceIdentityUsersChanged event) {
        later(event.companyId(), () -> store.liveNaming(event.companyId(), event.principalId()));
    }

    @TransactionalEventListener
    void groupMembersChanged(GroupEvents.GroupMembersChanged event) {
        if (event.removedUserIds().isEmpty()) {
            return;
        }
        later(event.companyId(), () -> authorsAmong(event.companyId(), event.removedUserIds()));
    }

    /** M7: a VIEWER authors no trigger; the gate pauses those of a member made one, whoever changed the role. */
    @TransactionalEventListener
    void memberRoleChanged(MemberEvents.MemberRoleChanged event) {
        later(event.companyId(), () -> authorsAmong(event.companyId(), List.of(event.userId())));
    }

    @TransactionalEventListener
    void agentShared(AgentEvents.AgentShared event) {
        if (!event.narrows()) {
            return;
        }
        later(event.companyId(), () -> store.liveOfAgent(event.companyId(), event.agentId()).stream()
            .map(TriggerInfo::id)
            .toList());
    }

    @TransactionalEventListener
    void modelKeyStatusChanged(ModelEvents.ModelKeyStatusChanged event) {
        later(event.companyId(), () -> store.liveNaming(event.companyId(), event.principalId()));
    }

    @TransactionalEventListener
    void modelSettingsChanged(ModelEvents.ModelSettingsChanged event) {
        later(event.companyId(), () -> store.liveIds(event.companyId()));
    }

    private List<UUID> authorsAmong(UUID companyId, Collection<UUID> userIds) {
        Set<UUID> triggers = new LinkedHashSet<>();
        for (UUID userId : userIds) {
            principals.ofMember(companyId, userId)
                .ifPresent(principal -> triggers.addAll(store.liveOfAuthor(companyId, principal.id())));
        }
        return List.copyOf(triggers);
    }

    /** The gate for each trigger the concern names, in a transaction of its own, as SYSTEM, after the commit. */
    private void later(UUID companyId, java.util.function.Supplier<List<UUID>> concerned) {
        executor.execute(() -> {
            try {
                List<UUID> triggerIds = transactions.inCompany(companyId, concerned);
                for (UUID triggerId : triggerIds) {
                    try {
                        transactions.runInCompany(companyId, () -> gate.check(companyId, triggerId));
                    }
                    catch (RuntimeException ex) {
                        log.warn("The gate of trigger {} failed", triggerId, ex);
                    }
                }
            }
            catch (RuntimeException ex) {
                log.warn("The triggers of company {} could not be looked at again", companyId, ex);
            }
        });
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
