package com.flowtivo.tinfirm.conversation.internal;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.company.GroupEvents;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.system.SystemEvents;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * Package 1's events that concern conversations' bindings (the package 3 contract, §5.3): after their commit, on a
 * virtual thread of its own and as SYSTEM in the company, every binding of an open conversation they may concern is
 * looked at again ({@link BindingService#reevaluate}): a waiting one is resolved again by the same rule, never by a
 * fallback (an account connected or reconnected, a grant created or changed, a principal resumed, personal servers
 * switched back on, a person who joined a group); a bound one is checked, and waits or goes when the check fails (an
 * account disconnected, a grant changed or withdrawn, a person who left a group, a holder suspended, a system removed,
 * personal servers switched off; an MCP server set to need no account, or to need one again). A person's own leaving
 * ends their conversations in its transaction instead
 * ({@link ConversationService}).
 */
@Component
class BindingEvents implements DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BindingEvents.class);

    private final BindingStore store;

    private final BindingService bindings;

    private final Principals principals;

    private final Systems systems;

    private final SystemTransactions transactions;

    private final ExecutorService executor = Executors
        .newThreadPerTaskExecutor(Thread.ofVirtual().name("conversation-bindings-", 0).factory());

    BindingEvents(BindingStore store, BindingService bindings, Principals principals, Systems systems,
            SystemTransactions transactions) {
        this.store = store;
        this.bindings = bindings;
        this.principals = principals;
        this.systems = systems;
        this.transactions = transactions;
    }

    @TransactionalEventListener
    void accountConnected(AccountEvents.AccountConnected event) {
        later(event.companyId(), Set.of(event.systemId()), Set.of(), Set.of(), ids(event.principalId()),
                ids(event.principalId()));
    }

    @TransactionalEventListener
    void accountReconnected(AccountEvents.AccountReconnected event) {
        later(event.companyId(), Set.of(event.systemId()), Set.of(event.accountId()), Set.of(),
                ids(event.principalId()), ids(event.principalId()));
    }

    @TransactionalEventListener
    void accountDisconnected(AccountEvents.AccountDisconnected event) {
        later(event.companyId(), Set.of(), Set.of(event.accountId()), Set.of(), ids(event.principalId()),
                Set.of());
    }

    /**
     * A credential was disabled or enabled, or its lending to a service account changed (ADR-0072): what acts with it
     * is checked again, and a binding that waits on the system may bind now.
     */
    @TransactionalEventListener
    void credentialChanged(AccountEvents.CredentialChanged event) {
        later(event.companyId(), Set.of(event.systemId()), Set.of(event.accountId()), Set.of(),
                ids(event.servicePrincipalId()), Set.of());
    }

    /** The one id, or none: a credential's events name no member. */
    private static Set<UUID> ids(@Nullable UUID id) {
        return id != null ? Set.of(id) : Set.of();
    }

    @TransactionalEventListener
    void accountNeedsReconnect(AccountEvents.AccountNeedsReconnect event) {
        later(event.companyId(), Set.of(), Set.of(event.accountId()), Set.of(), Set.of(), Set.of());
    }

    @TransactionalEventListener
    void grantCreated(AccountEvents.GrantCreated event) {
        // Waiting bindings on the system may now have a grant to bind.
        later(event.companyId(), Set.of(event.systemId()), Set.of(), Set.of(event.grantId()), Set.of(), Set.of());
    }

    @TransactionalEventListener
    void grantChanged(AccountEvents.GrantChanged event) {
        later(event.companyId(), Set.of(event.systemId()), Set.of(), Set.of(event.grantId()), Set.of(), Set.of());
    }

    @TransactionalEventListener
    void grantDeleted(AccountEvents.GrantDeleted event) {
        later(event.companyId(), Set.of(), Set.of(), Set.of(event.grantId()), Set.of(), Set.of());
    }

    @TransactionalEventListener
    void groupMembersChanged(GroupEvents.GroupMembersChanged event) {
        UUID companyId = event.companyId();
        executor.execute(() -> {
            try {
                Set<UUID> users = new java.util.HashSet<>(event.addedUserIds());
                users.addAll(event.removedUserIds());
                Set<UUID> persons = new java.util.HashSet<>();
                transactions.runInCompany(companyId, () -> users.forEach(user -> principals.ofMember(companyId, user)
                    .map(PrincipalInfo::id)
                    .ifPresent(persons::add)));
                reevaluate(companyId, Set.of(), Set.of(), Set.of(), Set.of(), persons);
            }
            catch (RuntimeException ex) {
                log.warn("The bindings of group {}'s members could not be looked at again", event.groupId(), ex);
            }
        });
    }

    @TransactionalEventListener
    void principalSuspended(PrincipalEvents.PrincipalSuspended event) {
        later(event.companyId(), Set.of(), Set.of(), Set.of(), Set.of(event.principalId()), Set.of());
    }

    @TransactionalEventListener
    void principalResumed(PrincipalEvents.PrincipalResumed event) {
        later(event.companyId(), Set.of(), Set.of(), Set.of(), Set.of(event.principalId()),
                Set.of(event.principalId()));
    }

    @TransactionalEventListener
    void systemDeleted(SystemEvents.SystemDeleted event) {
        later(event.companyId(), Set.of(event.systemId()), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @TransactionalEventListener
    void accountsRequiredChanged(SystemEvents.AccountsRequiredChanged event) {
        // ADR-0071: a waiting binding on the system binds without an account now, or one without an account waits for
        // one (a personal conversation's) or goes (a trigger's).
        later(event.companyId(), Set.of(event.systemId()), Set.of(), Set.of(), Set.of(), Set.of());
    }

    @TransactionalEventListener
    void personalSystemsSwitched(SystemEvents.PersonalSystemsSwitched event) {
        UUID companyId = event.companyId();
        executor.execute(() -> {
            try {
                List<UUID> personal = transactions.inCompany(companyId, () -> systems.visibleTo(companyId, null)
                    .stream()
                    .filter(SystemInfo::personal)
                    .map(SystemInfo::id)
                    .toList());
                reevaluate(companyId, personal, Set.of(), Set.of(), Set.of(), Set.of());
            }
            catch (RuntimeException ex) {
                log.warn("The bindings on personal servers of company {} could not be looked at again", companyId,
                        ex);
            }
        });
    }

    /**
     * Looks again, after the commit, at the bindings of open conversations on the systems, of the accounts, through the
     * grants, of the holders, or of the persons' conversations.
     */
    private void later(UUID companyId, Collection<UUID> systemIds, Collection<UUID> accountIds,
            Collection<UUID> grantIds, Collection<UUID> holderIds, Collection<UUID> personIds) {
        executor.execute(() -> {
            try {
                reevaluate(companyId, systemIds, accountIds, grantIds, holderIds, personIds);
            }
            catch (RuntimeException ex) {
                log.warn("Bindings of company {} could not be looked at again", companyId, ex);
            }
        });
    }

    private void reevaluate(UUID companyId, Collection<UUID> systemIds, Collection<UUID> accountIds,
            Collection<UUID> grantIds, Collection<UUID> holderIds, Collection<UUID> personIds) {
        List<UUID> concerned = transactions.inCompany(companyId, () -> store
            .concerned(companyId, systemIds, accountIds, grantIds, holderIds, personIds)
            .stream()
            .map(row -> row.info().id())
            .toList());
        bindings.reevaluate(companyId, concerned);
    }

    @Override
    public void destroy() {
        executor.shutdownNow();
    }
}
