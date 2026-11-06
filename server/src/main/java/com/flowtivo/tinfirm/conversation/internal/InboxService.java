package com.flowtivo.tinfirm.conversation.internal;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.agent.AgentInfo;
import com.flowtivo.tinfirm.agent.Agents;
import com.flowtivo.tinfirm.conversation.InboxItems;
import com.flowtivo.tinfirm.conversation.RetentionInbox;
import com.flowtivo.tinfirm.principal.PrincipalEvents;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.run.RunInfo;
import com.flowtivo.tinfirm.run.RunReads;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.access.CompanyRole;
import com.flowtivo.tinfirm.shared.api.model.Decision;
import com.flowtivo.tinfirm.shared.api.model.Inbox;
import com.flowtivo.tinfirm.shared.api.model.InboxAccount;
import com.flowtivo.tinfirm.shared.api.model.InboxBinding;
import com.flowtivo.tinfirm.shared.api.model.InboxConversation;
import com.flowtivo.tinfirm.shared.api.model.InboxCounts;
import com.flowtivo.tinfirm.shared.api.model.InboxItem;
import com.flowtivo.tinfirm.shared.api.model.InboxItemKind;
import com.flowtivo.tinfirm.shared.api.model.InboxTurn;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialRef;
import com.flowtivo.tinfirm.shared.api.model.SystemKind;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * {@code Inbox.of(caller)} (the package 3 contract, §5.10), longest waiting first: the open decisions the caller
 * decides; the waiting bindings of the caller's conversations they can act on ({@code NONE}, {@code RECONNECT_YOURS},
 * {@code CHOOSE}, {@code PICK_GRANT}); the caller's conversations whose latest turn failed; the caller's conversations
 * waiting on a runner or on their budget, with the reset time; for admins (OWNER, ADMIN), the service identities'
 * accounts that keep conversations waiting ({@code RECONNECT_COMPANY}, {@code SUSPENDED}), each with the number of
 * conversations and never their content; and the items higher modules keep, through the port {@link InboxItems}
 * (package 6's budgets and spend; package 4's triggers that need the caller, {@code TRIGGER}). The open decisions and
 * failed turns of a trigger's conversations are its people's: its team's approvals, and its questions and failures for
 * its author and team (T12). Everything but the admins' list is the caller's own: another member's
 * conversation never shows, OWNER's included. At most {@value #LIMIT} items of each kind; the counts are exact.
 * <p>
 * The higher modules' items are notices, one per {@code noticeKey}. A member dismisses one that only informs them, for
 * themselves ({@link #dismiss}, CONVENTIONS §15.7): it leaves their inbox and {@code counts.notices} while its key
 * stands, and comes back when its condition, and so its key, changes. {@link #restore} shows it again (the web's undo).
 * Not audited: it changes the caller's view of their own inbox and nothing anyone else sees or may do (§8). A member's
 * dismissals go when they leave ({@link #personLeft}) and with their erasure ({@code ConversationPurge}).
 */
@Service
class InboxService {

    static final int LIMIT = 100;

    private final InboxStore store;

    private final DecisionStore decisions;

    private final DecisionPresenter presenter;

    private final Principals principals;

    private final Agents agents;

    private final Systems systems;

    private final RunReads runs;

    private final CompanyAccessGuard companyAccess;

    private final ObjectProvider<RetentionInbox> others;

    private final ObjectProvider<InboxItems> sources;

    private final BindingTexts texts;

    private final SystemCalls calls;

    private final ConversationsImpl conversations;

    private final ConversationStore conversationStore;

    private final InboxDismissalStore dismissals;

    private final TimeSource time;

    /** The system credentials the admins' account items name (ADR-0072). */
    private final Accounts credentials;

    InboxService(InboxStore store, DecisionStore decisions, DecisionPresenter presenter, Principals principals,
            Agents agents, Systems systems, RunReads runs, CompanyAccessGuard companyAccess,
            ObjectProvider<RetentionInbox> others, ObjectProvider<InboxItems> sources, BindingTexts texts,
            SystemCalls calls, ConversationsImpl conversations, ConversationStore conversationStore,
            InboxDismissalStore dismissals, TimeSource time, Accounts credentials) {
        this.credentials = credentials;
        this.dismissals = dismissals;
        this.time = time;
        this.texts = texts;
        this.calls = calls;
        this.conversations = conversations;
        this.conversationStore = conversationStore;
        this.sources = sources;
        this.store = store;
        this.decisions = decisions;
        this.presenter = presenter;
        this.principals = principals;
        this.agents = agents;
        this.systems = systems;
        this.runs = runs;
        this.companyAccess = companyAccess;
        // The higher modules implement the port; looked up when used, since they depend on this module.
        this.others = others;
    }

    @Transactional(readOnly = true)
    public Inbox inbox(UUID companyId) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        UUID userId = CurrentActor.requireUser().id();
        UUID caller = principals.ofMember(companyId, userId).map(PrincipalInfo::id).orElse(null);
        List<Item> items = new ArrayList<>();
        InboxCounts counts = new InboxCounts(0, 0, 0, 0, 0);
        Names names = new Names(companyId);
        if (caller != null) {
            DecisionPresenter.Decider decider = presenter.decider(access, userId);
            Set<UUID> acting = decider.acting().triggerIds();
            List<DecisionStore.DecisionRow> open = decisions.openFor(caller, decider.groupIds(), acting,
                    decider.admin(), LIMIT);
            List<Decision> presented = presenter.decisions(companyId, open, decider);
            // A decision's conversation, as the other items name theirs, when the caller sees it.
            ConversationsImpl.Caller viewer = conversations.caller(access, userId);
            Map<UUID, Boolean> seen = new HashMap<>();
            for (int i = 0; i < open.size(); i++) {
                UUID conversationId = open.get(i).conversationId();
                boolean sees = seen.computeIfAbsent(conversationId, id -> conversationStore.find(companyId, id)
                    .map(row -> viewer.sees(row.info()))
                    .orElse(false));
                items.add(new Item(open.get(i).createdAt(), new InboxItem(InboxItemKind.DECISION,
                        utc(open.get(i).createdAt())).decision(presented.get(i))
                    .conversation(sees ? names.conversation(conversationId) : null)));
            }
            for (InboxStore.WaitingBinding binding : store.bindings(caller, LIMIT)) {
                // What the Connect card needs, without a second call: the grants offered, the kind, the sign-in.
                SystemInfo system = names.info(binding.systemId());
                InboxBinding dto = new InboxBinding(binding.id(), binding.systemId(),
                        InboxBinding.ReasonEnum.fromValue(binding.reason()), binding.choices())
                    .systemName(system != null ? system.name() : null)
                    .grantChoices(texts.choices(companyId, binding.choices()))
                    .systemKind(system != null ? SystemKind.fromValue(system.kind().name()) : null)
                    .oauthAvailable(system != null ? calls.oauthAvailable(system) : null);
                items.add(new Item(binding.since(), new InboxItem(InboxItemKind.BINDING, utc(binding.since()))
                    .conversation(names.conversation(binding.conversationId()))
                    .binding(dto)));
            }
            for (InboxStore.ConversationRow failed : store.failed(caller, acting, LIMIT)) {
                items.add(new Item(failed.since(), new InboxItem(InboxItemKind.FAILED, utc(failed.since()))
                    .conversation(names.conversation(failed))
                    .turn(new InboxTurn("FAILED").runId(failed.lastRunId())
                        .failureClass(failed.failureClass())
                        .failureCause(failed.failureCause()))));
            }
            for (InboxStore.ConversationRow waiting : store.waiting(caller, LIMIT)) {
                RunInfo run = waiting.activeRunId() != null ? runs.find(companyId, waiting.activeRunId()).orElse(null)
                        : null;
                items.add(new Item(waiting.since(), new InboxItem(InboxItemKind.WAITING, utc(waiting.since()))
                    .conversation(names.conversation(waiting))
                    .turn(new InboxTurn(waiting.turnStatus() != null ? waiting.turnStatus() : "QUEUED")
                        .runId(waiting.activeRunId())
                        .waitingReason(waiting.waitingReason())
                        .waitingText(waiting.activeRunId() != null
                                ? runs.queueReasonText(companyId, waiting.activeRunId()).orElse(null) : null)
                        .retryAt(run != null && run.retryAt() != null ? utc(run.retryAt()) : null))));
            }
            counts.decisions(decisions.countOpenFor(caller, decider.groupIds(), acting, decider.admin()))
                .bindings(store.countBindings(caller))
                .failed(store.countFailed(caller, acting))
                .waiting(store.countWaiting(caller));
        }
        if (access.viaInstanceAdmin() || access.role() == CompanyRole.OWNER || access.role() == CompanyRole.ADMIN) {
            List<InboxStore.WaitingAccount> accounts = store.accounts(LIMIT);
            Map<UUID, String> holders = principals.displayNames(companyId, accounts.stream()
                .map(InboxStore.WaitingAccount::holderPrincipalId)
                .filter(java.util.Objects::nonNull)
                .toList());
            for (InboxStore.WaitingAccount account : accounts) {
                items.add(new Item(account.since(), new InboxItem(InboxItemKind.ACCOUNT, utc(account.since()))
                    .account(new InboxAccount(account.systemId(), InboxAccount.ReasonEnum.fromValue(account.reason()),
                            account.conversations())
                        .systemName(names.system(account.systemId()))
                        .credential(account.accountId() != null ? credentials.find(companyId, account.accountId())
                            .filter(AccountInfo::credential)
                            .map(InboxService::credentialRef)
                            .orElse(null) : null)
                        .bot(account.accountId() != null && credentials.bot(companyId, account.accountId()))
                        .holderPrincipalId(account.holderPrincipalId())
                        .holderName(account.holderPrincipalId() != null ? holders.get(account.holderPrincipalId())
                                : null))));
            }
            counts.accounts(accounts.size());
        }
        for (RetentionInbox other : others.orderedStream().toList()) {
            for (InboxItem item : other.items(access, userId, caller)) {
                items.add(new Item(item.getSince().toInstant(), item));
            }
        }
        InboxItems.Caller who = new InboxItems.Caller(companyId, userId, caller, admin(access));
        // The caller's dismissed notices leave their inbox and its count while their keys stand.
        Set<String> dismissed = caller != null ? dismissals.keys(companyId, caller) : Set.of();
        int notices = 0;
        for (InboxItems.Item other : notices(who)) {
            String key = other.item().getNoticeKey();
            if (key != null && dismissed.contains(key)) {
                continue;
            }
            items.add(new Item(other.since(), other.item()));
            notices++;
        }
        counts.notices(notices);
        items.sort(Comparator.comparing(Item::since));
        return new Inbox(items.stream().map(Item::item).toList(), counts);
    }

    /**
     * {@code PUT /inbox/dismissals/{noticeKey}}: the caller dismisses a notice of their inbox now, for themselves. A key
     * that is none of their notices is {@code 404}, as for a caller without a principal in the company (an instance
     * admin who is not a member); a notice they are the one to act on is {@code 409 NOTICE_NOT_DISMISSIBLE}. Dismissing
     * it again changes nothing. Dismissals of notices that left the inbox stay until the daily sweep: the notices read here
     * are at most {@value #LIMIT} of each kind, so a notice missing from them may still stand.
     */
    @Transactional
    public void dismiss(UUID companyId, String noticeKey) {
        CompanyAccess access = companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        UUID userId = CurrentActor.requireUser().id();
        UUID caller = dismisser(companyId, userId, noticeKey);
        List<InboxItems.Item> notices = notices(new InboxItems.Caller(companyId, userId, caller, admin(access)));
        InboxItem notice = notices.stream()
            .map(InboxItems.Item::item)
            .filter(item -> noticeKey.equals(item.getNoticeKey()))
            .findFirst()
            .orElseThrow(() -> ApiException.notFound("No notice of your inbox has this key"));
        if (!Boolean.TRUE.equals(notice.getDismissible())) {
            throw ApiException.conflict(ProblemCode.NOTICE_NOT_DISMISSIBLE,
                    "This notice asks you to act: it leaves your inbox once that is done");
        }
        dismissals.dismiss(companyId, caller, noticeKey, time.now());
    }

    /**
     * {@code DELETE /inbox/dismissals/{noticeKey}}: the caller shows a notice they dismissed again, while it stands.
     * A key they did not dismiss changes nothing.
     */
    @Transactional
    public void restore(UUID companyId, String noticeKey) {
        companyAccess.require(companyId, CompanyPermission.VIEW_COMPANY);
        UUID userId = CurrentActor.requireUser().id();
        dismissals.restore(companyId, dismisser(companyId, userId, noticeKey), noticeKey);
    }

    /** A member who left reads no inbox: their dismissals go in the leaving transaction. */
    @EventListener
    void personLeft(PrincipalEvents.PrincipalLeft event) {
        dismissals.forget(event.principalId());
    }

    /** The caller's principal, who dismisses for themselves; {@code 404} without one, {@code 400} for a bad key. */
    private UUID dismisser(UUID companyId, UUID userId, String noticeKey) {
        if (!InboxItems.KEY.matcher(noticeKey).matches()) {
            throw ApiException.invalid("A notice's key has 1 to 200 letters, digits and the characters _ . : -",
                    "noticeKey");
        }
        return principals.ofMember(companyId, userId)
            .map(PrincipalInfo::id)
            .orElseThrow(() -> ApiException.notFound("No notice of your inbox has this key"));
    }

    /**
     * The higher modules' items (the port {@link InboxItems}), one per key. Only a member with a principal in the
     * company dismisses, and only a notice with a key.
     */
    private List<InboxItems.Item> notices(InboxItems.Caller who) {
        Map<String, InboxItems.Item> byKey = new LinkedHashMap<>();
        List<InboxItems.Item> unkeyed = new ArrayList<>();
        for (InboxItems source : sources.orderedStream().toList()) {
            for (InboxItems.Item notice : source.itemsFor(who, LIMIT)) {
                String key = notice.item().getNoticeKey();
                if (who.principalId() == null || key == null) {
                    notice.item().dismissible(false);
                }
                if (key == null) {
                    unkeyed.add(notice);
                }
                else {
                    byKey.putIfAbsent(key, notice);
                }
            }
        }
        List<InboxItems.Item> notices = new ArrayList<>(byKey.values());
        notices.addAll(unkeyed);
        return notices;
    }

    private static boolean admin(CompanyAccess access) {
        return access.viaInstanceAdmin() || access.role() == CompanyRole.OWNER || access.role() == CompanyRole.ADMIN;
    }

    private record Item(Instant since, InboxItem item) {
    }

    private static java.time.OffsetDateTime utc(Instant instant) {
        return instant.atOffset(ZoneOffset.UTC);
    }

    /** The names an inbox shows, each read once. */
    private final class Names {

        private final UUID companyId;

        private final Map<UUID, @Nullable SystemInfo> systemsById = new HashMap<>();

        private final Map<UUID, @Nullable String> agentNames = new HashMap<>();

        private final Map<UUID, InboxConversation> conversations = new HashMap<>();

        private final Set<UUID> missing = new HashSet<>();

        Names(UUID companyId) {
            this.companyId = companyId;
        }

        @Nullable String system(UUID systemId) {
            SystemInfo system = info(systemId);
            return system != null ? system.name() : null;
        }

        @Nullable SystemInfo info(UUID systemId) {
            return systemsById.computeIfAbsent(systemId, id -> systems.find(companyId, id).orElse(null));
        }

        InboxConversation conversation(InboxStore.ConversationRow row) {
            return conversations.computeIfAbsent(row.id(), id -> new InboxConversation(row.id(), row.key(),
                    row.title(), row.agentId()).agentName(agent(row.agentId())));
        }

        @Nullable InboxConversation conversation(UUID conversationId) {
            if (missing.contains(conversationId)) {
                return null;
            }
            InboxConversation known = conversations.get(conversationId);
            if (known != null) {
                return known;
            }
            InboxConversation found = store.conversation(conversationId)
                .map(row -> new InboxConversation(row.id(), row.key(), row.title(), row.agentId())
                    .agentName(agent(row.agentId())))
                .orElse(null);
            if (found == null) {
                missing.add(conversationId);
                return null;
            }
            conversations.put(conversationId, found);
            return found;
        }

        private @Nullable String agent(UUID agentId) {
            return agentNames.computeIfAbsent(agentId, id -> agents.find(companyId, id).map(AgentInfo::name)
                .orElse(null));
        }
    }

    /** A credential as the inbox names it: what an admin does to it follows from its kind, status and state. */
    private static SystemCredentialRef credentialRef(AccountInfo credential) {
        return new SystemCredentialRef(credential.id(), java.util.Objects.requireNonNull(credential.name()),
                SystemCredentialRef.KindEnum.fromValue(credential.kind().name()),
                SystemCredentialRef.StatusEnum.fromValue(credential.status().name()), credential.enabled())
            .login(credential.externalLogin());
    }
}
