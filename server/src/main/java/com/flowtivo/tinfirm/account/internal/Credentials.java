package com.flowtivo.tinfirm.account.internal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.account.AccountEvents;
import com.flowtivo.tinfirm.account.CredentialUses;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.audit.AuditAction;
import com.flowtivo.tinfirm.audit.AuditLog;
import com.flowtivo.tinfirm.audit.AuditRecord;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.AccountAccess;
import com.flowtivo.tinfirm.shared.api.model.BorrowedCredential;
import com.flowtivo.tinfirm.shared.api.model.BorrowedCredentialList;
import com.flowtivo.tinfirm.shared.api.model.CredentialGrant;
import com.flowtivo.tinfirm.shared.api.model.CredentialLendRequest;
import com.flowtivo.tinfirm.shared.api.model.CredentialLending;
import com.flowtivo.tinfirm.shared.api.model.CredentialOpenTrigger;
import com.flowtivo.tinfirm.shared.api.model.MemberAccountCount;
import com.flowtivo.tinfirm.shared.api.model.SignInStart;
import com.flowtivo.tinfirm.shared.api.model.SystemCredential;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialClientCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialCreateRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialList;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialRef;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialSignInRequest;
import com.flowtivo.tinfirm.shared.api.model.SystemCredentialUpdateRequest;
import com.flowtivo.tinfirm.shared.security.Actor;
import com.flowtivo.tinfirm.shared.security.ActorType;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.sql.Constraints;
import com.flowtivo.tinfirm.shared.time.TimeSource;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.shared.web.ProblemCode;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * A system's credentials (ADR-0072): the company's tokens and sign-ins on a company system, several on one, which OWNER
 * and ADMIN add, sign in again, rename, disable and remove on the system's page ({@code MANAGE_SYSTEMS}), and lend to
 * service accounts, each borrowing at most one per system. On Jira Cloud a credential may also be an OAuth client's own
 * credentials ({@code CLIENT}): an Atlassian service account's, which mints its own access tokens. Grants lend them to
 * groups ({@link Grants}). Connecting, signing in and removing go through {@link AccountService}, as a member's own
 * account does; what changes how a credential may be used (disabling it, enabling it, a lending) is announced as
 * {@link AccountEvents.CredentialChanged}, so that what uses it is checked again. A chat system's bot is a credential
 * too, changed only with the bot ({@code BOT_ACCOUNT_IN_USE}).
 */
@Service
class Credentials {

    private final AccountStore store;

    private final AccountService accounts;

    private final Principals principals;

    private final Systems systems;

    private final GroupMembership groups;

    private final CompanyAccessGuard guard;

    private final AuditLog auditLog;

    private final ApplicationEventPublisher events;

    private final TimeSource time;

    private final ChatSystems chatSystems;

    private final AccountMapper mapper;

    private final TransactionTemplate transactions;

    private final List<CredentialUses> uses;

    Credentials(AccountStore store, AccountService accounts, Principals principals, Systems systems,
            GroupMembership groups, CompanyAccessGuard guard, AuditLog auditLog, ApplicationEventPublisher events,
            TimeSource time, ChatSystems chatSystems, AccountMapper mapper,
            PlatformTransactionManager transactionManager, List<CredentialUses> uses) {
        this.uses = List.copyOf(uses);
        this.store = store;
        this.accounts = accounts;
        this.principals = principals;
        this.systems = systems;
        this.groups = groups;
        this.guard = guard;
        this.auditLog = auditLog;
        this.events = events;
        this.time = time;
        this.chatSystems = chatSystems;
        this.mapper = mapper;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    // ---- Reads -----------------------------------------------------------------------------------------------------

    @Transactional(readOnly = true)
    public SystemCredentialList list(UUID companyId, UUID systemId) {
        accounts.credentialSystem(companyId, systemId);
        int[] members = store.memberAccountCounts(companyId, systemId);
        return new SystemCredentialList(views(companyId, systemId, store.credentialsOn(companyId, systemId)),
                new MemberAccountCount(members[0], members[1]));
    }

    /** What a service account borrows, by system name (OWNER, ADMIN). */
    @Transactional(readOnly = true)
    public BorrowedCredentialList borrowed(UUID companyId, UUID serviceIdentityId) {
        guard.require(companyId, CompanyPermission.MANAGE_SERVICE_IDENTITIES);
        principals.find(companyId, serviceIdentityId)
            .filter(PrincipalInfo::service)
            .orElseThrow(Credentials::serviceAccountNotFound);
        List<AccountStore.LendingRow> lendings = store.lendingsTo(companyId, serviceIdentityId);
        Map<UUID, SystemInfo> found = systems.findAll(companyId,
                lendings.stream().map(AccountStore.LendingRow::systemId).toList());
        Map<CredentialUses.Lending, Integer> triggers = liveTriggers(companyId,
                lendings.stream().map(AccountStore.LendingRow::accountId).toList());
        Map<UUID, List<CredentialUses.OpenTrigger>> open = openTriggers(companyId,
                lendings.stream().map(AccountStore.LendingRow::accountId).toList());
        Map<UUID, String> serviceNames = principals.displayNames(companyId, List.of(serviceIdentityId));
        List<BorrowedCredential> items = new ArrayList<>();
        for (AccountStore.LendingRow lending : lendings) {
            SystemInfo system = found.get(lending.systemId());
            Optional<AccountStore.AccountRow> credential = store.find(companyId, lending.accountId());
            if (system == null || credential.isEmpty()) {
                continue;
            }
            AccountStore.AccountRow row = credential.get();
            items.add(new BorrowedCredential(
                    mapper.toDto(new AccountMapper.SystemRefDetails(system.id(), system.name(), system.handle(),
                            system.kind(), system.personal())),
                    new SystemCredentialRef(row.id(), Objects.requireNonNull(row.name()),
                            SystemCredentialRef.KindEnum.fromValue(row.kind().name()),
                            SystemCredentialRef.StatusEnum.fromValue(row.status().name()), row.enabled())
                        .login(row.externalLogin()),
                    access(lending.access()),
                    triggers.getOrDefault(new CredentialUses.Lending(row.id(), serviceIdentityId), 0),
                    AccountStore.ts(lending.createdAt()), AccountStore.ts(lending.updatedAt()))
                .bot(bot(row))
                .openTriggers(openTriggers(open.getOrDefault(row.id(), List.of()).stream()
                    .filter(trigger -> trigger.servicePrincipalId().equals(serviceIdentityId))
                    .toList(), serviceNames)));
        }
        items.sort(Comparator.comparing((BorrowedCredential item) -> item.getSystem().getName(),
                String.CASE_INSENSITIVE_ORDER));
        return new BorrowedCredentialList(items);
    }

    // ---- Adding and connecting again --------------------------------------------------------------------------------

    /** A token as a new credential, after the system's "who am I" accepted it. */
    public SystemCredential add(UUID companyId, UUID systemId, SystemCredentialCreateRequest input) {
        AccountStore.AccountRow row = accounts.storeToken(
                () -> accounts.credential(companyId, systemId, null, input.getName()), input.getToken());
        return view(row);
    }

    /** A new token for the credential, which keeps its name, grants and lendings. */
    public SystemCredential storeToken(UUID companyId, UUID systemId, UUID credentialId, String token) {
        AccountStore.AccountRow row = accounts.storeToken(
                () -> accounts.credential(companyId, systemId, credentialId, null), token);
        return view(row);
    }

    /** An OAuth client's own credentials as a new credential, after the client minted a token and the site named it. */
    public SystemCredential addClient(UUID companyId, UUID systemId, SystemCredentialClientCreateRequest input) {
        AccountStore.AccountRow row = accounts.storeClient(
                () -> accounts.credential(companyId, systemId, null, input.getName()), input.getClientId(),
                input.getClientSecret());
        return view(row);
    }

    /** New client credentials for the credential, which keeps its name, grants and lendings. */
    public SystemCredential storeClient(UUID companyId, UUID systemId, UUID credentialId, String clientId,
            String clientSecret) {
        AccountStore.AccountRow row = accounts.storeClient(
                () -> accounts.credential(companyId, systemId, credentialId, null), clientId, clientSecret);
        return view(row);
    }

    @Transactional
    public SignInStart startSignIn(UUID companyId, UUID systemId, SystemCredentialSignInRequest input,
            String sessionId) {
        return accounts.startSignIn(accounts.credential(companyId, systemId, null, input.getName()),
                input.getReturnTo(), sessionId);
    }

    @Transactional
    public SignInStart startSignInAgain(UUID companyId, UUID systemId, UUID credentialId, String returnTo,
            String sessionId) {
        return accounts.startSignIn(accounts.credential(companyId, systemId, credentialId, null), returnTo,
                sessionId);
    }

    // ---- Changing and removing ----------------------------------------------------------------------------------------

    /** Renames it, or disables or enables it ({@code credential.changed}); the bot changes only with the bot (C9). */
    @Transactional
    public SystemCredential update(UUID companyId, UUID systemId, UUID credentialId,
            SystemCredentialUpdateRequest input) {
        AccountService.Holder holder = accounts.credential(companyId, systemId, credentialId, null);
        AccountStore.AccountRow current = store.lock(companyId, credentialId)
            .orElseThrow(AccountService::credentialNotFound);
        if (input.getName() == null && input.getEnabled() == null) {
            throw ApiException.validation("Name a new name, or whether the credential is enabled");
        }
        accounts.requireNotBot(holder);
        List<String> changed = new ArrayList<>();
        Instant now = time.now();
        String name = Objects.requireNonNull(current.name());
        if (input.getName() != null) {
            String requested = AccountService.credentialName(input.getName());
            if (!requested.equals(name)) {
                if (store.credentialNameTaken(companyId, systemId, requested, credentialId)) {
                    throw AccountService.duplicateCredentialName(holder.system(), requested);
                }
                try {
                    store.rename(companyId, credentialId, requested, now);
                }
                catch (DataIntegrityViolationException ex) {
                    // Another credential took the name meanwhile.
                    if (Constraints.violated(ex, AccountService.NAME_INDEX)) {
                        throw AccountService.duplicateCredentialName(holder.system(), requested);
                    }
                    throw ex;
                }
                name = requested;
                changed.add("name");
            }
        }
        boolean enabled = current.enabled();
        if (input.getEnabled() != null && input.getEnabled() != current.enabled()) {
            enabled = input.getEnabled();
            store.enable(companyId, credentialId, enabled, now);
            changed.add("enabled");
        }
        if (!changed.isEmpty()) {
            auditLog.record(AuditRecord.company(companyId, AuditAction.CREDENTIAL_CHANGED, CurrentActor.actor())
                .target(AccountService.TARGET, credentialId)
                .detail("systemId", systemId.toString())
                .detail("name", name)
                .detail("changed", changed)
                .detail("enabled", enabled)
                .build());
        }
        if (changed.contains("enabled")) {
            events.publishEvent(new AccountEvents.CredentialChanged(companyId, credentialId, systemId, null));
        }
        return view(store.find(companyId, credentialId).orElseThrow());
    }

    /**
     * Removes it: its grants and lendings, then the credential with its secret, its OAuth grant revoked where the
     * system offers revocation ({@link AccountService#disconnect}).
     */
    public void remove(UUID companyId, UUID systemId, UUID credentialId) {
        accounts.disconnect(() -> {
            AccountService.Holder holder = accounts.credential(companyId, systemId, credentialId, null);
            accounts.requireNotBot(holder);
            return store.find(companyId, credentialId).orElseThrow(AccountService::credentialNotFound);
        });
    }

    // ---- Lending to service accounts ----------------------------------------------------------------------------------

    /**
     * Lends the credential to the service account, replacing what it borrowed on the system, or changes its access
     * ({@code credential.lent}). Its triggers that act with the credential it replaces follow it: their rows name this
     * one, their open conversations bind it at their next check, and their authors are told. The bot is lent only with
     * the bot.
     */
    @Transactional
    public SystemCredential lend(UUID companyId, UUID systemId, UUID credentialId, UUID serviceIdentityId,
            CredentialLendRequest input) {
        AccountService.Holder holder = accounts.credential(companyId, systemId, credentialId, null);
        accounts.requireNotBot(holder);
        PrincipalInfo service = principals.find(companyId, serviceIdentityId)
            .filter(PrincipalInfo::service)
            .orElseThrow(Credentials::serviceAccountNotFound);
        GrantInfo.Access access = GrantInfo.Access.valueOf(input.getAccess().name());
        Optional<AccountStore.LendingRow> before = store.lending(companyId, service.id(), systemId, true);
        if (before.isPresent() && chatSystems.botOf(companyId, before.get().accountId()).isPresent()) {
            throw ApiException.conflict(ProblemCode.BOT_ACCOUNT_IN_USE, service.name() + " borrows the system's bot: "
                    + "remove the bot on the system's page first");
        }
        lendTo(companyId, systemId, credentialId, service.id(), access, before);
        return view(store.find(companyId, credentialId).orElseThrow());
    }

    /**
     * Lends the credential to the service account in the caller's transaction, replacing what it borrowed on the
     * system: audited as {@code credential.lent} and announced, a replacement in this transaction
     * ({@link AccountEvents.LendingReplaced}); nothing when it borrows it already with this access.
     */
    void lendTo(UUID companyId, UUID systemId, UUID credentialId, UUID servicePrincipalId, GrantInfo.Access access,
            Optional<AccountStore.LendingRow> before) {
        if (before.isPresent() && before.get().accountId().equals(credentialId)
                && before.get().access() == access) {
            return;
        }
        Instant now = time.now();
        Actor actor = CurrentActor.actor();
        store.lend(new AccountStore.LendingRow(companyId, servicePrincipalId, systemId, credentialId, access,
                before.map(AccountStore.LendingRow::createdAt).orElse(now), now),
                actor.type() == ActorType.USER ? actor.id() : null);
        AuditRecord.Builder audit = AuditRecord.company(companyId, AuditAction.CREDENTIAL_LENT, actor)
            .target(AccountService.TARGET, credentialId)
            .detail("accountId", credentialId.toString())
            .detail("systemId", systemId.toString())
            .detail("serviceAccountId", servicePrincipalId.toString())
            .detail("access", access.name());
        UUID replaced = before.map(AccountStore.LendingRow::accountId)
            .filter(previous -> !previous.equals(credentialId))
            .orElse(null);
        if (replaced != null) {
            audit.detail("replaced", replaced.toString());
        }
        auditLog.record(audit.build());
        if (replaced != null) {
            events.publishEvent(new AccountEvents.LendingReplaced(companyId, systemId, servicePrincipalId, replaced,
                    credentialId));
        }
        events.publishEvent(new AccountEvents.CredentialChanged(companyId, credentialId, systemId,
                servicePrincipalId));
        if (replaced != null) {
            events.publishEvent(new AccountEvents.CredentialChanged(companyId, replaced, systemId,
                    servicePrincipalId));
        }
    }

    /** The service account no longer borrows the credential ({@code credential.withdrawn}). */
    @Transactional
    public void withdraw(UUID companyId, UUID systemId, UUID credentialId, UUID serviceIdentityId) {
        AccountService.Holder holder = accounts.credential(companyId, systemId, credentialId, null);
        accounts.requireNotBot(holder);
        AccountStore.LendingRow lending = store.lending(companyId, serviceIdentityId, systemId, true)
            .filter(row -> row.accountId().equals(credentialId))
            .orElseThrow(() -> ApiException.notFound("The service account does not borrow this credential"));
        withdrawn(lending, null);
    }

    /** Withdraws the lending, already locked, in the caller's transaction: audited and announced. */
    void withdrawn(AccountStore.LendingRow lending, @Nullable String cause) {
        store.withdraw(lending.companyId(), lending.servicePrincipalId(), lending.systemId());
        AuditRecord.Builder audit = AuditRecord
            .company(lending.companyId(), AuditAction.CREDENTIAL_WITHDRAWN, CurrentActor.actor())
            .target(AccountService.TARGET, lending.accountId())
            .detail("accountId", lending.accountId().toString())
            .detail("systemId", lending.systemId().toString())
            .detail("serviceAccountId", lending.servicePrincipalId().toString())
            .detail("access", lending.access().name());
        if (cause != null) {
            audit.detail("cause", cause);
        }
        auditLog.record(audit.build());
        events.publishEvent(new AccountEvents.CredentialChanged(lending.companyId(), lending.accountId(),
                lending.systemId(), lending.servicePrincipalId()));
    }

    // ---- Views -----------------------------------------------------------------------------------------------------

    private SystemCredential view(AccountStore.AccountRow row) {
        return Objects.requireNonNull(transactions.execute(status -> views(row.companyId(), row.systemId(),
                List.of(store.find(row.companyId(), row.id()).orElseThrow(AccountService::credentialNotFound)))
            .getFirst()));
    }

    /**
     * The credentials as the system's page shows them: with the groups they are lent to by group name, the service
     * accounts that borrow them by name, who connected them last, and whether one is the chat system's bot; a fixed
     * number of queries whatever the system holds.
     */
    private List<SystemCredential> views(UUID companyId, UUID systemId, List<AccountStore.AccountRow> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<UUID, List<AccountStore.GrantRow>> grantsOf = new HashMap<>();
        store.grantsOn(companyId, systemId)
            .forEach(grant -> grantsOf.computeIfAbsent(grant.info().accountId(), id -> new ArrayList<>()).add(grant));
        Map<UUID, List<AccountStore.LendingRow>> lendingsOf = new HashMap<>();
        store.lendingsOn(companyId, systemId)
            .forEach(lending -> lendingsOf.computeIfAbsent(lending.accountId(), id -> new ArrayList<>()).add(lending));
        Map<UUID, String> groupNames = groups.groupNames(companyId, grantsOf.values().stream()
            .flatMap(List::stream)
            .map(grant -> grant.info().groupId())
            .toList());
        Map<UUID, PrincipalInfo> services = principals.findAll(companyId, lendingsOf.values().stream()
            .flatMap(List::stream)
            .map(AccountStore.LendingRow::servicePrincipalId)
            .toList());
        Map<UUID, String> connectedBy = connectedByNames(companyId, rows);
        Map<CredentialUses.Lending, Integer> triggers = liveTriggers(companyId,
                rows.stream().map(AccountStore.AccountRow::id).toList());
        Map<UUID, List<CredentialUses.OpenTrigger>> open = openTriggers(companyId,
                rows.stream().map(AccountStore.AccountRow::id).toList());
        Map<UUID, String> serviceNames = new HashMap<>();
        services.forEach((id, service) -> serviceNames.put(id, service.name()));
        List<SystemCredential> views = new ArrayList<>();
        for (AccountStore.AccountRow row : rows) {
            List<CredentialGrant> grants = grantsOf.getOrDefault(row.id(), List.of()).stream()
                .map(grant -> new CredentialGrant(grant.info().id(), grant.info().groupId(),
                        Objects.requireNonNullElse(groupNames.get(grant.info().groupId()), ""),
                        access(grant.info().access()), AccountStore.ts(grant.createdAt()),
                        AccountStore.ts(grant.updatedAt())))
                .sorted(Comparator.comparing(CredentialGrant::getGroupName, String.CASE_INSENSITIVE_ORDER))
                .toList();
            List<CredentialLending> lent = lendingsOf.getOrDefault(row.id(), List.of()).stream()
                .filter(lending -> services.containsKey(lending.servicePrincipalId()))
                .map(lending -> {
                    PrincipalInfo service = services.get(lending.servicePrincipalId());
                    return new CredentialLending(service.id(), Objects.requireNonNullElse(service.name(), ""),
                            service.active() ? CredentialLending.StatusEnum.ACTIVE
                                    : CredentialLending.StatusEnum.SUSPENDED,
                            access(lending.access()),
                            triggers.getOrDefault(new CredentialUses.Lending(row.id(), service.id()), 0),
                            AccountStore.ts(lending.createdAt()), AccountStore.ts(lending.updatedAt()));
                })
                .sorted(Comparator.comparing(CredentialLending::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
            views.add(new SystemCredential(row.id(), row.systemId(), Objects.requireNonNull(row.name()),
                    SystemCredential.KindEnum.fromValue(row.kind().name()),
                    SystemCredential.StatusEnum.fromValue(row.status().name()), row.enabled(),
                    AccountStore.ts(row.connectedAt()), bot(row), grants, lent)
                .login(row.externalLogin())
                .lastError(row.lastError())
                .scopes(row.scopes())
                .connectedByName(row.connectedBy() != null ? connectedBy.get(row.connectedBy()) : null)
                .lastUsedAt(AccountStore.ts(row.lastUsedAt()))
                .openTriggers(openTriggers(open.getOrDefault(row.id(), List.of()), serviceNames)));
        }
        return views;
    }

    /** The live triggers that act with the credentials through their service accounts, where a module keeps them. */
    private Map<CredentialUses.Lending, Integer> liveTriggers(UUID companyId, List<UUID> credentialIds) {
        Map<CredentialUses.Lending, Integer> counts = new HashMap<>();
        uses.forEach(use -> use.liveTriggers(companyId, credentialIds).forEach((lending, count) -> counts.merge(
                lending, count, Integer::sum)));
        return counts;
    }

    /**
     * The live triggers open to anyone that act with the credentials through their service accounts, by credential,
     * where a module keeps them (the security review of 2026-10-27, D2).
     */
    private Map<UUID, List<CredentialUses.OpenTrigger>> openTriggers(UUID companyId, List<UUID> credentialIds) {
        Map<UUID, List<CredentialUses.OpenTrigger>> open = new HashMap<>();
        uses.forEach(use -> use.openTriggers(companyId, credentialIds).forEach((credential, triggers) -> open
            .computeIfAbsent(credential, id -> new ArrayList<>()).addAll(triggers)));
        return open;
    }

    /** The open triggers as the API shows them, each with its service account's name. */
    private static List<CredentialOpenTrigger> openTriggers(List<CredentialUses.OpenTrigger> triggers,
            Map<UUID, String> serviceNames) {
        return new ArrayList<>(triggers.stream()
            .map(trigger -> new CredentialOpenTrigger(trigger.triggerId(), trigger.name(),
                    com.flowtivo.tinfirm.shared.api.model.TriggerSource.fromValue(trigger.source()),
                    trigger.servicePrincipalId())
                .serviceIdentityName(serviceNames.get(trigger.servicePrincipalId())))
            .toList());
    }

    /** The display names of who connected the credentials last, by user id, as their member principals say them. */
    private Map<UUID, String> connectedByNames(UUID companyId, List<AccountStore.AccountRow> rows) {
        Set<UUID> users = new HashSet<>();
        rows.forEach(row -> {
            if (row.connectedBy() != null) {
                users.add(row.connectedBy());
            }
        });
        Map<UUID, UUID> principalOf = new HashMap<>();
        for (UUID user : users) {
            principals.ofMember(companyId, user).ifPresent(member -> principalOf.put(member.id(), user));
        }
        Map<UUID, String> names = principals.displayNames(companyId, principalOf.keySet());
        Map<UUID, String> byUser = new HashMap<>();
        names.forEach((principalId, name) -> byUser.put(principalOf.get(principalId), name));
        return byUser;
    }

    private boolean bot(AccountStore.AccountRow row) {
        return chatSystems.botOf(row.companyId(), row.id()).isPresent();
    }

    private static AccountAccess access(GrantInfo.Access access) {
        return AccountAccess.fromValue(access.name());
    }

    static ApiException serviceAccountNotFound() {
        return ApiException.notFound("Service account not found");
    }
}
