package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.conversation.BindingInfo;
import com.flowtivo.tinfirm.conversation.ConversationBindings;
import com.flowtivo.tinfirm.conversation.ConversationEvents;
import com.flowtivo.tinfirm.conversation.ConversationInfo;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.PrincipalStatus;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.id.Ids;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.tx.NoTransaction;
import com.flowtivo.tinfirm.shared.tx.SystemTransactions;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;

/**
 * {@link ConversationBindings} (the package 3 contract, §5.3, I6 to I8): the account a conversation uses on a system,
 * resolved once through {@code Accounts.bind} at the system's first use and stored, then checked at every use through
 * {@code Accounts.check}, which never re-binds. A failed check makes the binding wait with its card (audited once per
 * wait) or go for good. Every change is audited, said in the thread where the person should know, announced
 * ({@link ConversationEvents}) and handed to part 2 ({@link BindingHook}), and what the conversation waits on is worked
 * out again.
 */
@Service
class BindingService implements ConversationBindings {

    private static final Logger log = LoggerFactory.getLogger(BindingService.class);

    static final String TARGET = "conversation_binding";

    private final BindingStore store;

    private final ConversationStore conversations;

    private final Accounts accounts;

    private final Principals principals;

    private final BindingTexts texts;

    private final ConversationMessagesImpl messages;

    private final ObjectProvider<ConversationTurns> turns;

    private final List<BindingHook> hooks;

    private final SystemTransactions transactions;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final ConversationHolds holds;

    BindingService(BindingStore store, ConversationStore conversations, Accounts accounts, Principals principals,
            BindingTexts texts, ConversationMessagesImpl messages, ObjectProvider<ConversationTurns> turns,
            List<BindingHook> hooks, SystemTransactions transactions, AuditLog auditLog,
            ApplicationEventPublisher events, TimeSource time, ConversationHolds holds) {
        this.holds = holds;
        this.store = store;
        this.conversations = conversations;
        this.accounts = accounts;
        this.principals = principals;
        this.texts = texts;
        this.messages = messages;
        this.turns = turns;
        this.hooks = hooks;
        this.transactions = transactions;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
    }

    // ---- reads -----------------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<BindingInfo> find(UUID companyId, UUID bindingId) {
        return store.find(companyId, bindingId).map(BindingStore.BindingRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BindingInfo> of(UUID companyId, UUID conversationId, UUID systemId) {
        return store.of(companyId, conversationId, systemId).map(BindingStore.BindingRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<BindingInfo> of(UUID companyId, UUID conversationId) {
        return store.ofConversation(companyId, conversationId).stream().map(BindingStore.BindingRow::info).toList();
    }

    // ---- resolve ---------------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public BindingInfo resolve(UUID companyId, UUID conversationId, UUID systemId, @Nullable UUID runId) {
        // The conversation first (CONVENTIONS §10), at no cost for a caller that holds it: binding writes it.
        ConversationInfo conversation = conversations.lock(companyId, conversationId)
            .orElseThrow(() -> new IllegalArgumentException("No conversation " + conversationId))
            .info();
        Optional<BindingStore.BindingRow> existing = store.of(companyId, conversationId, systemId);
        if (existing.isPresent()) {
            return existing.get().info();
        }
        if (conversation.triggered()) {
            // A trigger's conversation has its trigger's table's bindings, made at its start, and no other (T5), but on
            // an MCP server that needs no account, which it binds without one at its first use (ADR-0071).
            if (!(accounts.open(companyId, systemId, conversation.actingPrincipalId())
                    instanceof AccountBinding.Open)) {
                throw new IllegalStateException("A trigger's conversation binds nothing on a system its trigger has no "
                        + "row for");
            }
            return opened(conversation, systemId, runId);
        }
        AccountBinding.Outcome outcome = accounts.bind(
                new AccountBinding.Personal(companyId, Objects.requireNonNull(conversation.personPrincipalId())),
                systemId);
        if (outcome instanceof AccountBinding.NotEntitled refused
                && refused.reason() == AccountBinding.Refusal.NO_SYSTEM) {
            throw new IllegalArgumentException("No system " + systemId + " to bind");
        }
        Instant now = time.now();
        BindingInfo initial = new BindingInfo(Ids.newId(), companyId, conversationId, systemId,
                BindingInfo.State.WAITING, null, null, null, null, null, BindingInfo.Reason.NONE, List.of(), now,
                null);
        if (!store.insert(initial, null, runId, now)) {
            return store.of(companyId, conversationId, systemId).orElseThrow().info();
        }
        BindingStore.BindingRow row = store.lock(companyId, initial.id()).orElseThrow();
        return apply(row, conversation, outcome, null, true);
    }

    @Override
    @Transactional
    public BindingInfo resolveAgain(UUID companyId, UUID bindingId) {
        lockConversationOf(companyId, bindingId);
        BindingStore.BindingRow row = store.lock(companyId, bindingId)
            .orElseThrow(() -> new IllegalArgumentException("No binding " + bindingId));
        if (!row.info().waiting()) {
            return row.info();
        }
        ConversationInfo conversation = conversations.find(companyId, row.info().conversationId()).orElseThrow()
            .info();
        if (!conversation.open()) {
            return row.info();
        }
        if (row.info().accountId() != null || conversation.triggered()) {
            // Bound before: never bound again to another account (I6, I7); the stored binding is checked instead.
            return checked(row, conversation).binding();
        }
        AccountBinding.Outcome outcome = accounts.bind(
                new AccountBinding.Personal(companyId, Objects.requireNonNull(conversation.personPrincipalId())),
                row.info().systemId());
        return apply(row, conversation, outcome, null, false);
    }

    /** Stores what {@code Accounts.bind} answered for a binding that was never bound. */
    private BindingInfo apply(BindingStore.BindingRow row, ConversationInfo conversation,
            AccountBinding.Outcome outcome, @Nullable UUID boundBy, boolean first) {
        return switch (outcome) {
            case AccountBinding.Own own -> bound(row, BindingInfo.How.OWN, own.account(),
                    own.account().principalId(), null, GrantInfo.Access.WRITE, boundBy);
            case AccountBinding.Granted granted -> bound(row, BindingInfo.How.GRANTED, granted.account(), null,
                    granted.grant().id(), granted.grant().access(), boundBy);
            case AccountBinding.Choose choose -> waiting(row, BindingInfo.Reason.PICK_GRANT,
                    choose.grants().stream().map(GrantInfo::id).toList(), first);
            case AccountBinding.ConnectRequired required -> waiting(row, switch (required.reason()) {
                case NONE -> BindingInfo.Reason.NONE;
                case RECONNECT_YOURS -> BindingInfo.Reason.RECONNECT_YOURS;
                case RECONNECT_COMPANY -> BindingInfo.Reason.RECONNECT_COMPANY;
                case CHOOSE -> BindingInfo.Reason.CHOOSE;
            }, required.choices().stream().map(GrantInfo::id).toList(), first);
            case AccountBinding.NotEntitled refused -> waiting(row, switch (refused.reason()) {
                case PERSONAL_SYSTEMS_OFF -> BindingInfo.Reason.SYSTEM_OFF;
                case PRINCIPAL_INACTIVE -> BindingInfo.Reason.SUSPENDED;
                default -> BindingInfo.Reason.NONE;
            }, List.of(), first);
            case AccountBinding.Open open -> open(row, boundBy);
            case AccountBinding.Fixed fixed -> throw new IllegalStateException("A personal conversation binds no "
                    + "trigger's account");
        };
    }

    // ---- check and use ---------------------------------------------------------------------------------------------

    @Override
    @Transactional
    public BindingCheck check(UUID companyId, UUID bindingId) {
        lockConversationOf(companyId, bindingId);
        BindingStore.BindingRow row = store.lock(companyId, bindingId)
            .orElseThrow(() -> new IllegalArgumentException("No binding " + bindingId));
        ConversationInfo conversation = conversations.find(companyId, row.info().conversationId()).orElseThrow()
            .info();
        return checked(row, conversation);
    }

    /** The checks of §5.3's table on a locked binding; a failure is recorded. */
    private BindingCheck checked(BindingStore.BindingRow row, ConversationInfo conversation) {
        BindingInfo binding = row.info();
        if (binding.gone()) {
            return new BindingCheck.Gone(binding);
        }
        if (binding.open()) {
            return checkedOpen(row, conversation);
        }
        if (binding.accountId() == null) {
            return new BindingCheck.Waiting(binding);
        }
        // A trigger's conversation's bindings act for its author (the package 4 contract, §5.3, T1).
        AccountBinding.Check check = accounts.check(new AccountBinding.Stored(binding.companyId(),
                binding.systemId(), conversation.actingPrincipalId(), binding.holderPrincipalId(),
                binding.accountId(), binding.grantId(), binding.externalId()));
        return switch (check) {
            case AccountBinding.Usable usable -> {
                Instant now = time.now();
                if (!usable.account().id().equals(binding.accountId())) {
                    // The holder connected the same identity again: a new account row, the same account (I6).
                    store.replaceAccount(binding.companyId(), binding.id(), usable.account().id(), now);
                }
                if (binding.waiting()) {
                    BindingInfo bound = bound(store.lock(binding.companyId(), binding.id()).orElseThrow(),
                            Objects.requireNonNull(binding.how()), usable.account(), binding.holderPrincipalId(),
                            binding.grantId(), usable.access(), null);
                    yield new BindingCheck.Usable(bound, usable.account(), usable.access());
                }
                yield new BindingCheck.Usable(store.find(binding.companyId(), binding.id()).orElseThrow().info(),
                        usable.account(), usable.access());
            }
            case AccountBinding.Stopped stopped when stopped.reason() == AccountBinding.StopReason.SYSTEM_PAUSED ->
                // A paused system: the binding keeps its state, nothing waits on it, and nothing uses it (§5.4).
                new BindingCheck.Waiting(binding);
            case AccountBinding.Open open -> throw new IllegalStateException("Accounts.check answers an account");
            case AccountBinding.Stopped stopped -> {
                if (conversation.triggered() && stopped.reason() == AccountBinding.StopReason.USE_RIGHT_GONE) {
                    BindingStore.BindingRow followed = followed(row);
                    if (followed != null) {
                        // Checked again with the credential it follows: that one may be disabled, or the author may
                        // no longer use the service account either.
                        yield checked(followed, conversation);
                    }
                }
                BindingInfo.Reason reason = conversation.triggered()
                        && stopped.reason() == AccountBinding.StopReason.USE_RIGHT_GONE
                        // The author lost the right to the service account, or it no longer borrows the credential:
                        // it waits, and binds again if that comes back, never gone (the package 4 contract, §5.3).
                        ? BindingInfo.Reason.NOT_ENTITLED : reasonOf(stopped.reason(), binding);
                BindingInfo changed = reason.waits() ? waiting(row, reason, List.of(), false) : gone(row, reason);
                yield changed.gone() ? new BindingCheck.Gone(changed) : new BindingCheck.Waiting(changed);
            }
        };
    }

    /**
     * A trigger's binding through a service account that borrows another credential on the system now (ADR-0072): it
     * follows the service account, bound to that credential with its identity, as the trigger's row does since the
     * lending. Audited as {@code conversation.binding.bound} with the credential it {@code followed}, and said in the
     * thread. Null when the service account borrows none, still the one bound (the right to use it is what went), or a
     * chat system's bot, which acts for no trigger (C9).
     */
    private BindingStore.@Nullable BindingRow followed(BindingStore.BindingRow row) {
        BindingInfo before = row.info();
        UUID holder = before.holderPrincipalId();
        if (before.how() != BindingInfo.How.FIXED || holder == null || before.accountId() == null) {
            return null;
        }
        Accounts.Lent lent = accounts.lentTo(before.companyId(), before.systemId(), holder).orElse(null);
        if (lent == null || lent.credential().id().equals(before.accountId())
                || accounts.bot(before.companyId(), lent.credential().id())) {
            return null;
        }
        AccountInfo credential = lent.credential();
        String replaced = accounts.find(before.companyId(), before.accountId()).map(AccountInfo::name).orElse(null);
        store.bound(before.companyId(), before.id(), BindingInfo.How.FIXED, credential.id(), holder,
                credential.externalId(), null, null, time.now());
        BindingInfo binding = store.find(before.companyId(), before.id()).orElseThrow().info();
        auditLog.record(AuditRecord.company(binding.companyId(), AuditAction.CONVERSATION_BINDING_BOUND,
                CurrentActor.actor())
            .target(TARGET, binding.id())
            .detail("conversationId", binding.conversationId().toString())
            .detail("systemId", binding.systemId().toString())
            .detail("how", BindingInfo.How.FIXED.name())
            .detail("accountId", credential.id().toString())
            .detail("holderPrincipalId", holder.toString())
            .detail("grantId", null)
            .detail("access", lent.access().name())
            .detail("followed", before.accountId().toString())
            .build());
        messages.system(binding.companyId(), binding.conversationId(), texts.followed(binding, replaced) + ".", null);
        events.publishEvent(new ConversationEvents.BindingBound(binding.companyId(), binding.conversationId(),
                binding.id(), binding.systemId()));
        hooks.forEach(hook -> hook.bound(binding));
        refresh(binding);
        return store.lock(binding.companyId(), binding.id()).orElseThrow();
    }

    /**
     * The check of a binding without an account (ADR-0071), by {@code Accounts.open} for whom the conversation acts:
     * it holds, or holds again after it waited; a paused system keeps it as it is; once the system needs accounts
     * again, a personal conversation's waits for one ({@code NONE}, which binds the person's own account or a grant
     * when they continue) and a trigger's goes ({@code ACCOUNT_REMOVED}: its table has no account there).
     */
    private BindingCheck checkedOpen(BindingStore.BindingRow row, ConversationInfo conversation) {
        BindingInfo binding = row.info();
        AccountBinding.Check check = accounts.open(binding.companyId(), binding.systemId(),
                conversation.actingPrincipalId());
        return switch (check) {
            case AccountBinding.Open open -> new BindingCheck.Open(binding.waiting() ? open(row, null) : binding);
            case AccountBinding.Stopped stopped when stopped.reason() == AccountBinding.StopReason.SYSTEM_PAUSED ->
                new BindingCheck.Waiting(binding);
            case AccountBinding.Stopped stopped -> {
                BindingInfo.Reason reason = switch (stopped.reason()) {
                    case SYSTEM_GONE -> BindingInfo.Reason.SYSTEM_REMOVED;
                    case PERSONAL_SYSTEMS_OFF, SYSTEM_PAUSED -> BindingInfo.Reason.SYSTEM_OFF;
                    case ACCOUNT_REQUIRED -> conversation.triggered() ? BindingInfo.Reason.ACCOUNT_REMOVED
                            : BindingInfo.Reason.NONE;
                    default -> BindingInfo.Reason.SUSPENDED;
                };
                BindingInfo changed = reason.waits() ? waiting(row, reason, List.of(), false) : gone(row, reason);
                yield changed.gone() ? new BindingCheck.Gone(changed) : new BindingCheck.Waiting(changed);
            }
            case AccountBinding.Usable usable -> throw new IllegalStateException("Accounts.open answers no account");
        };
    }

    /** What a stopped use means for the binding (§5.3's table). */
    private BindingInfo.Reason reasonOf(AccountBinding.StopReason reason, BindingInfo binding) {
        return switch (reason) {
            case PRINCIPAL_INACTIVE -> {
                if (binding.holderPrincipalId() == null) {
                    // A credential a grant lends: the person acting is the one suspended.
                    yield BindingInfo.Reason.SUSPENDED;
                }
                PrincipalInfo holder = principals.find(binding.companyId(), binding.holderPrincipalId()).orElse(null);
                yield holder == null || holder.status() == PrincipalStatus.LEFT ? BindingInfo.Reason.HOLDER_LEFT
                        : BindingInfo.Reason.SUSPENDED;
            }
            case RECONNECT_YOURS -> BindingInfo.Reason.RECONNECT_YOURS;
            case RECONNECT_COMPANY -> BindingInfo.Reason.RECONNECT_COMPANY;
            case IDENTITY_CHANGED -> BindingInfo.Reason.IDENTITY_CHANGED;
            case GRANT_GONE, USE_RIGHT_GONE -> BindingInfo.Reason.GRANT_REMOVED;
            case NOT_IN_GROUP -> BindingInfo.Reason.LEFT_GROUP;
            case SYSTEM_GONE -> BindingInfo.Reason.SYSTEM_REMOVED;
            case PERSONAL_SYSTEMS_OFF, SYSTEM_PAUSED -> BindingInfo.Reason.SYSTEM_OFF;
            // Only a binding without an account stops so (checkedOpen).
            case ACCOUNT_REQUIRED -> BindingInfo.Reason.NONE;
        };
    }

    /**
     * A checked binding, whom the conversation acts for (its person, or a trigger's conversation's author), and the
     * kill switch that holds a binding that holds, if one does.
     */
    private record Checked(BindingCheck check, UUID personPrincipalId, ConversationHolds.@Nullable Held held) {
    }

    @Override
    public BindingUse use(UUID companyId, UUID bindingId, String purpose) {
        NoTransaction.require("Using a conversation's binding");
        Checked checked = transactions.inCompany(companyId, () -> {
            lockConversationOf(companyId, bindingId);
            BindingStore.BindingRow row = store.lock(companyId, bindingId)
                .orElseThrow(() -> new IllegalArgumentException("No binding " + bindingId));
            ConversationInfo conversation = conversations.find(companyId, row.info().conversationId())
                .orElseThrow()
                .info();
            BindingCheck check = checked(row, conversation);
            // The gate (D5): a binding that holds is used for nothing while a kill switch holds its conversation. A
            // waiting or gone one is answered as it is, so that the outbox still learns that it is gone.
            ConversationHolds.Held holding = check instanceof BindingCheck.Usable
                    || check instanceof BindingCheck.Open ? holds.of(conversation).orElse(null) : null;
            return new Checked(check, conversation.actingPrincipalId(), holding);
        });
        ConversationHolds.Held held = checked.held();
        if (held != null) {
            return new BindingUse.Held(checked.check().binding(), held.hold(), held.why());
        }
        if (checked.check() instanceof BindingCheck.Open open) {
            // No account, no credential to reveal (ADR-0071).
            return new BindingUse.Open(open.binding());
        }
        if (!(checked.check() instanceof BindingCheck.Usable usable)) {
            return new BindingUse.Unavailable(checked.check().binding());
        }
        Optional<Accounts.RevealedCredential> credential = accounts.reveal(companyId, usable.account().id(),
                checked.personPrincipalId(), purpose);
        if (credential.isEmpty()) {
            // Checked again: the account went, or its refresh was refused, after the check, and the binding waits for
            // its reconnect or is gone; or it still holds, and the reveal failed for a passing reason (the token
            // endpoint down or busy), which a later try may not meet.
            BindingCheck again = transactions.inCompany(companyId, () -> {
                lockConversationOf(companyId, bindingId);
                BindingStore.BindingRow row = store.lock(companyId, bindingId).orElseThrow();
                ConversationInfo conversation = conversations.find(companyId, row.info().conversationId())
                    .orElseThrow()
                    .info();
                return checked(row, conversation);
            });
            return again instanceof BindingCheck.Usable holds ? new BindingUse.Failed(holds.binding())
                    : new BindingUse.Unavailable(again.binding());
        }
        return new BindingUse.Token(usable.binding(), usable.account(), usable.access(), credential.get());
    }

    /**
     * Locks the binding's conversation's row before the binding's, in every transaction that may change a binding (a
     * check, a resolution again, the use of a token, a package 1 event): a binding that changes writes its
     * conversation, audits, and may start the {@code CONNECTED} turn, whose run comes after the conversation
     * (CONVENTIONS §10). A caller that holds the conversation's lock already pays nothing for it.
     */
    private void lockConversationOf(UUID companyId, UUID bindingId) {
        store.find(companyId, bindingId).ifPresent(row -> conversations.lock(companyId, row.info().conversationId()));
    }

    // ---- the person's choices ----------------------------------------------------------------------------------------

    /**
     * {@code …/bindings/{systemId}/choose}: on {@code PICK_GRANT} or {@code CHOOSE}, a grant among the choices binds
     * {@code CHOSEN}; {@code reconnect} turns {@code CHOOSE} into {@code RECONNECT_YOURS} (and {@code PICK_GRANT} into
     * {@code NONE}). The caller is the conversation's person, checked by the caller.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    BindingInfo choose(ConversationInfo conversation, UUID systemId, @Nullable UUID grantId, boolean reconnect,
            UUID userId) {
        BindingStore.BindingRow row = lockOf(conversation, systemId);
        BindingInfo binding = row.info();
        if (!binding.waiting() || (binding.reason() != BindingInfo.Reason.PICK_GRANT
                && binding.reason() != BindingInfo.Reason.CHOOSE)) {
            throw ApiException.conflict(ProblemCode.BINDING_NOT_WAITING,
                    "This account waits for no choice in this conversation");
        }
        if (reconnect) {
            return waiting(row, binding.reason() == BindingInfo.Reason.CHOOSE ? BindingInfo.Reason.RECONNECT_YOURS
                    : BindingInfo.Reason.NONE, List.of(), false);
        }
        if (grantId == null || !binding.choices().contains(grantId)) {
            throw ApiException.badRequest(ProblemCode.GRANT_NOT_OFFERED, "This grant is not one offered here");
        }
        GrantInfo grant = accounts.grant(conversation.companyId(), grantId)
            .orElseThrow(() -> ApiException.badRequest(ProblemCode.GRANT_NOT_OFFERED, "The grant was withdrawn"));
        AccountInfo account = accounts.find(conversation.companyId(), grant.accountId())
            .orElseThrow(() -> ApiException.badRequest(ProblemCode.GRANT_NOT_OFFERED, "The grant's account is gone"));
        AccountBinding.Check check = accounts.check(AccountBinding.Stored.ofGrant(grant, account,
                conversation.actingPrincipalId()));
        if (!(check instanceof AccountBinding.Usable usable)) {
            throw ApiException.badRequest(ProblemCode.GRANT_NOT_OFFERED, "The grant cannot be used now: "
                    + ((AccountBinding.Stopped) check).reason());
        }
        return bound(row, BindingInfo.How.CHOSEN, usable.account(), null, grant.id(), usable.access(), userId);
    }

    /**
     * {@code …/bindings/{systemId}/continue}: resolves a waiting binding again. One bound already is answered as it is,
     * changing nothing: what the person's continue asks for happened a moment earlier (package 1's event of the same
     * connect bound it, or another tab's continue), so a second continue is the same request answered again. A gone
     * binding is {@code 409 BINDING_NOT_WAITING}: no continue helps it.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    BindingInfo continueWaiting(ConversationInfo conversation, UUID systemId) {
        BindingStore.BindingRow row = lockOf(conversation, systemId);
        if (row.info().bound()) {
            return row.info();
        }
        if (!row.info().waiting()) {
            throw ApiException.conflict(ProblemCode.BINDING_NOT_WAITING, "This account does not wait in this "
                    + "conversation");
        }
        return resolveAgain(conversation.companyId(), row.info().id());
    }

    private BindingStore.BindingRow lockOf(ConversationInfo conversation, UUID systemId) {
        BindingStore.BindingRow found = store.of(conversation.companyId(), conversation.id(), systemId)
            .orElseThrow(() -> ApiException.notFound("This conversation has not used that system"));
        return store.lock(conversation.companyId(), found.info().id()).orElseThrow();
    }

    /**
     * Looks again at the bindings a package 1 event concerns, each in a transaction of its own: a bound one is checked
     * ({@link #check}), a waiting one resolved again ({@link #resolveAgain}).
     */
    void reevaluate(UUID companyId, List<UUID> bindingIds) {
        for (UUID bindingId : bindingIds) {
            try {
                transactions.runInCompany(companyId, () -> {
                    BindingStore.BindingRow row = store.find(companyId, bindingId).orElse(null);
                    if (row == null || row.info().gone()) {
                        return;
                    }
                    lockConversationOf(companyId, bindingId);
                    if (row.info().waiting()) {
                        resolveAgain(companyId, bindingId);
                    }
                    else {
                        check(companyId, bindingId);
                    }
                });
            }
            catch (RuntimeException ex) {
                log.warn("Binding {} could not be looked at again", bindingId, ex);
            }
        }
    }

    // ---- a trigger's conversation ----------------------------------------------------------------------------------

    /**
     * A trigger's conversation's binding of one row of its trigger's table, inserted {@code BOUND}, {@code FIXED}, at
     * its start (the package 4 contract, §5.7, T5): the row's account, holder and identity, no grant. Audited as
     * {@code conversation.binding.bound}. In the starting transaction, which holds the conversation's lock.
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.MANDATORY)
    BindingInfo fixed(ConversationInfo conversation, UUID systemId, UUID accountId, UUID holderPrincipalId,
            @Nullable String externalId) {
        Instant now = time.now();
        BindingInfo binding = new BindingInfo(Ids.newId(), conversation.companyId(), conversation.id(), systemId,
                BindingInfo.State.BOUND, BindingInfo.How.FIXED, accountId, holderPrincipalId, externalId, null, null,
                List.of(), null, now);
        if (!store.insert(binding, null, now)) {
            return store.of(conversation.companyId(), conversation.id(), systemId).orElseThrow().info();
        }
        auditLog.record(AuditRecord.company(binding.companyId(), AuditAction.CONVERSATION_BINDING_BOUND,
                CurrentActor.actor())
            .target(TARGET, binding.id())
            .detail("conversationId", binding.conversationId().toString())
            .detail("systemId", systemId.toString())
            .detail("how", BindingInfo.How.FIXED.name())
            .detail("accountId", accountId.toString())
            .detail("holderPrincipalId", holderPrincipalId.toString())
            .detail("grantId", null)
            .detail("access", GrantInfo.Access.WRITE.name())
            .build());
        events.publishEvent(new ConversationEvents.BindingBound(binding.companyId(), binding.conversationId(),
                binding.id(), binding.systemId()));
        hooks.forEach(hook -> hook.bound(binding));
        return binding;
    }

    // ---- transitions -----------------------------------------------------------------------------------------------

    /**
     * A binding without an account (ADR-0071), first or again after it waited: {@code BOUND}, {@code OPEN}, audited
     * as {@code conversation.binding.bound} without an account, holder or grant. Said nowhere in the thread: nobody's
     * account acts, as for the person's own.
     */
    private BindingInfo open(BindingStore.BindingRow row, @Nullable UUID boundBy) {
        BindingInfo before = row.info();
        store.open(before.companyId(), before.id(), boundBy, time.now());
        BindingInfo binding = store.find(before.companyId(), before.id()).orElseThrow().info();
        recordOpen(binding);
        refresh(binding);
        return binding;
    }

    /**
     * A trigger's conversation's binding without an account, at the system's first use (ADR-0071): inserted
     * {@code BOUND}, {@code OPEN}. In the caller's transaction, which locked the conversation.
     */
    private BindingInfo opened(ConversationInfo conversation, UUID systemId, @Nullable UUID runId) {
        Instant now = time.now();
        BindingInfo binding = new BindingInfo(Ids.newId(), conversation.companyId(), conversation.id(), systemId,
                BindingInfo.State.BOUND, BindingInfo.How.OPEN, null, null, null, null, null, List.of(), null, now);
        if (!store.insert(binding, null, runId, now)) {
            return store.of(conversation.companyId(), conversation.id(), systemId).orElseThrow().info();
        }
        recordOpen(binding);
        return binding;
    }

    private void recordOpen(BindingInfo binding) {
        auditLog.record(AuditRecord.company(binding.companyId(), AuditAction.CONVERSATION_BINDING_BOUND,
                CurrentActor.actor())
            .target(TARGET, binding.id())
            .detail("conversationId", binding.conversationId().toString())
            .detail("systemId", binding.systemId().toString())
            .detail("how", BindingInfo.How.OPEN.name())
            .detail("accountId", null)
            .detail("holderPrincipalId", null)
            .detail("grantId", null)
            .detail("access", GrantInfo.Access.WRITE.name())
            .build());
        events.publishEvent(new ConversationEvents.BindingBound(binding.companyId(), binding.conversationId(),
                binding.id(), binding.systemId()));
        hooks.forEach(hook -> hook.bound(binding));
    }

    /**
     * Binds the account.
     *
     * @param holderPrincipalId whom it acts through: the member for their own account, a trigger's author or service
     *                          account; null for a credential a grant lends (ADR-0072)
     */
    private BindingInfo bound(BindingStore.BindingRow row, BindingInfo.How how, AccountInfo account,
            @Nullable UUID holderPrincipalId, @Nullable UUID grantId, GrantInfo.Access access,
            @Nullable UUID boundBy) {
        BindingInfo before = row.info();
        Instant now = time.now();
        store.bound(before.companyId(), before.id(), how, account.id(), holderPrincipalId, account.externalId(),
                grantId, boundBy, now);
        BindingInfo binding = store.find(before.companyId(), before.id()).orElseThrow().info();
        auditLog.record(AuditRecord.company(binding.companyId(), AuditAction.CONVERSATION_BINDING_BOUND,
                CurrentActor.actor())
            .target(TARGET, binding.id())
            .detail("conversationId", binding.conversationId().toString())
            .detail("systemId", binding.systemId().toString())
            .detail("how", how.name())
            .detail("accountId", account.id().toString())
            .detail("holderPrincipalId", holderPrincipalId != null ? holderPrincipalId.toString() : null)
            .detail("grantId", grantId != null ? grantId.toString() : null)
            .detail("access", access.name())
            .build());
        if (how != BindingInfo.How.OWN && !before.bound()) {
            messages.system(binding.companyId(), binding.conversationId(), texts.bound(binding, access) + ".", null);
        }
        events.publishEvent(new ConversationEvents.BindingBound(binding.companyId(), binding.conversationId(),
                binding.id(), binding.systemId()));
        hooks.forEach(hook -> hook.bound(binding));
        refresh(binding);
        return binding;
    }

    private BindingInfo waiting(BindingStore.BindingRow row, BindingInfo.Reason reason, List<UUID> choices,
            boolean first) {
        BindingInfo before = row.info();
        if (!first && before.waiting() && before.reason() == reason && before.choices().equals(choices)) {
            return before;
        }
        Instant now = time.now();
        store.waiting(before.companyId(), before.id(), reason, choices, now);
        BindingInfo binding = store.find(before.companyId(), before.id()).orElseThrow().info();
        auditLog.record(AuditRecord.company(binding.companyId(), AuditAction.CONVERSATION_BINDING_WAITING,
                CurrentActor.actor())
            .target(TARGET, binding.id())
            .detail("conversationId", binding.conversationId().toString())
            .detail("systemId", binding.systemId().toString())
            .detail("reason", reason.name())
            .build());
        messages.system(binding.companyId(), binding.conversationId(), texts.card(binding), null);
        events.publishEvent(new ConversationEvents.BindingWaiting(binding.companyId(), binding.conversationId(),
                binding.id(), binding.systemId(), reason));
        refresh(binding);
        return binding;
    }

    private BindingInfo gone(BindingStore.BindingRow row, BindingInfo.Reason reason) {
        BindingInfo before = row.info();
        Instant now = time.now();
        store.gone(before.companyId(), before.id(), reason, now);
        BindingInfo binding = store.find(before.companyId(), before.id()).orElseThrow().info();
        auditLog.record(AuditRecord.company(binding.companyId(), AuditAction.CONVERSATION_BINDING_GONE,
                CurrentActor.actor())
            .target(TARGET, binding.id())
            .detail("conversationId", binding.conversationId().toString())
            .detail("systemId", binding.systemId().toString())
            .detail("reason", reason.name())
            .build());
        messages.system(binding.companyId(), binding.conversationId(), texts.gone(binding), null);
        events.publishEvent(new ConversationEvents.BindingGone(binding.companyId(), binding.conversationId(),
                binding.id(), binding.systemId(), reason));
        hooks.forEach(hook -> hook.gone(binding));
        refresh(binding);
        return binding;
    }

    private void refresh(BindingInfo binding) {
        ConversationTurns conversationTurns = turns.getIfAvailable();
        if (conversationTurns != null) {
            conversationTurns.refreshWaiting(binding.companyId(), binding.conversationId());
        }
    }
}
