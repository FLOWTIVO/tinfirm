package com.flowtivo.tinfirm.account.internal;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.account.AccountInfo;
import com.flowtivo.tinfirm.account.Accounts;
import com.flowtivo.tinfirm.account.GrantInfo;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.system.ChatSystems;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * {@link Accounts} over the account store, in the caller's tenant context: the binding rule of the package 1 contract,
 * §5, and its check at every use, in the caller's transaction; the reveal of a credential outside any, in short ones
 * of its own ({@link AccountCredentials}).
 */
@Component
class AccountsImpl implements Accounts {

    private final AccountStore store;

    private final AccountCredentials credentials;

    private final Principals principals;

    private final Systems systems;

    private final GroupMembership groups;

    private final ChatSystems chatSystems;

    AccountsImpl(AccountStore store, AccountCredentials credentials, Principals principals, Systems systems,
            GroupMembership groups, ChatSystems chatSystems) {
        this.store = store;
        this.credentials = credentials;
        this.principals = principals;
        this.systems = systems;
        this.groups = groups;
        this.chatSystems = chatSystems;
    }

    // ---- The binding rule ------------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AccountBinding.Outcome bind(AccountBinding.Context context, UUID systemId) {
        UUID companyId = context.companyId();
        Optional<SystemInfo> found = systems.find(companyId, systemId);
        if (found.isEmpty()) {
            return new AccountBinding.NotEntitled(systemId, AccountBinding.Refusal.NO_SYSTEM);
        }
        SystemInfo system = found.get();
        return switch (context) {
            case AccountBinding.Personal personal -> personal(system, personal.memberPrincipalId());
            case AccountBinding.Trigger trigger -> trigger(system, trigger.authorPrincipalId(),
                    trigger.holderPrincipalId(), trigger.accountId());
        };
    }

    private AccountBinding.Outcome personal(SystemInfo system, UUID memberPrincipalId) {
        UUID companyId = system.companyId();
        PrincipalInfo member = principals.find(companyId, memberPrincipalId).filter(PrincipalInfo::member)
            .orElse(null);
        if (member == null || !member.active()) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.PRINCIPAL_INACTIVE);
        }
        AccountBinding.@Nullable NotEntitled personalRefusal = personalRefusal(system, member.id());
        if (personalRefusal != null) {
            return personalRefusal;
        }
        if (!system.accountsRequired()) {
            // An MCP server that needs no account (ADR-0071): whatever account the member holds there, none is used.
            return new AccountBinding.Open(system.id());
        }
        // A chat link holds no credential: it is no account to act with (the package 5 contract, open point 13).
        Optional<AccountStore.AccountRow> own = store.of(companyId, system.id(), member.id(), false)
            .filter(account -> account.kind() != AccountInfo.Kind.LINK);
        if (own.isPresent() && own.get().status() == AccountInfo.Status.OK) {
            return new AccountBinding.Own(own.get().info());
        }
        List<Candidate> candidates = system.personal() ? List.of()
                : candidates(system, Objects.requireNonNull(member.userId()));
        List<Candidate> usable = candidates.stream().filter(Candidate::usable).toList();
        if (own.isPresent()) {
            // I10: one's own broken account never falls back to a grant silently.
            return usable.isEmpty()
                    ? new AccountBinding.ConnectRequired(system.id(), AccountBinding.ConnectReason.RECONNECT_YOURS,
                            List.of())
                    : new AccountBinding.ConnectRequired(system.id(), AccountBinding.ConnectReason.CHOOSE,
                            usable.stream().map(Candidate::grant).toList());
        }
        if (usable.size() == 1) {
            return new AccountBinding.Granted(usable.getFirst().grant(), usable.getFirst().account().info());
        }
        if (usable.size() > 1) {
            return new AccountBinding.Choose(usable.stream().map(Candidate::grant).toList());
        }
        return new AccountBinding.ConnectRequired(system.id(), candidates.isEmpty()
                ? AccountBinding.ConnectReason.NONE : AccountBinding.ConnectReason.RECONNECT_COMPANY, List.of());
    }

    private AccountBinding.Outcome trigger(SystemInfo system, UUID authorPrincipalId, UUID holderPrincipalId,
            UUID accountId) {
        UUID companyId = system.companyId();
        PrincipalInfo author = principals.find(companyId, authorPrincipalId).filter(PrincipalInfo::member)
            .orElse(null);
        if (author == null || !author.active()) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.PRINCIPAL_INACTIVE);
        }
        AccountBinding.@Nullable NotEntitled personalRefusal = personalRefusal(system, author.id());
        if (personalRefusal != null) {
            return personalRefusal;
        }
        AccountStore.AccountRow account = store.find(companyId, accountId)
            .filter(row -> row.systemId().equals(system.id()))
            .orElse(null);
        if (account == null || account.kind() == AccountInfo.Kind.LINK) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.ACCOUNT_NOT_USABLE);
        }
        if (bot(account)) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.BOT_ACCOUNT);
        }
        if (!account.credential()) {
            // The author's own account, acting through the author.
            if (!author.id().equals(account.principalId()) || !author.id().equals(holderPrincipalId)) {
                return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.ACCOUNT_NOT_USABLE);
            }
            if (account.status() != AccountInfo.Status.OK) {
                return new AccountBinding.ConnectRequired(system.id(), AccountBinding.ConnectReason.RECONNECT_YOURS,
                        List.of());
            }
            return new AccountBinding.Fixed(account.info(), author.id());
        }
        // A credential, through the service account that borrows it on the system and that the author may use.
        PrincipalInfo holder = principals.find(companyId, holderPrincipalId).filter(PrincipalInfo::service)
            .orElse(null);
        if (holder == null || !principals.mayUse(companyId, Objects.requireNonNull(author.userId()), holder.id())
                || !store.lending(companyId, holder.id(), system.id(), false)
                    .map(lending -> lending.accountId().equals(account.id()))
                    .orElse(false)) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.ACCOUNT_NOT_USABLE);
        }
        if (!holder.active()) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.PRINCIPAL_INACTIVE);
        }
        if (account.status() != AccountInfo.Status.OK || !account.enabled()) {
            return new AccountBinding.ConnectRequired(system.id(), AccountBinding.ConnectReason.RECONNECT_COMPANY,
                    List.of());
        }
        return new AccountBinding.Fixed(account.info(), holder.id());
    }

    /** Whether the account is a chat system's bot (C9). */
    @Override
    @Transactional(readOnly = true)
    public boolean bot(UUID companyId, UUID accountId) {
        return store.find(companyId, accountId).map(this::bot).orElse(false);
    }

    private boolean bot(AccountStore.AccountRow account) {
        return account.credential() && chatSystems.botOf(account.companyId(), account.id()).isPresent();
    }

    /** A personal server binds for its owner only, and for nobody while the company switched them off. */
    private AccountBinding.@Nullable NotEntitled personalRefusal(SystemInfo system, UUID memberPrincipalId) {
        if (!system.personal()) {
            return null;
        }
        if (!system.ownedBy(memberPrincipalId)) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.NOT_OWNER);
        }
        if (!systems.personalSystemsOn(system.companyId())) {
            return new AccountBinding.NotEntitled(system.id(), AccountBinding.Refusal.PERSONAL_SYSTEMS_OFF);
        }
        return null;
    }

    /**
     * A grant to one of the member's groups on the system, with the credential it lends.
     *
     * @param account the credential; usable when it is {@code OK} and enabled (ADR-0072)
     */
    record Candidate(GrantInfo grant, AccountStore.AccountRow account) {

        boolean usable() {
            return account.status() == AccountInfo.Status.OK && account.enabled();
        }
    }

    /**
     * The grants on the system to the user's groups, in the order a conversation takes them: {@code WRITE} before
     * {@code READ}, then the oldest grant first.
     */
    List<Candidate> candidates(SystemInfo system, UUID userId) {
        UUID companyId = system.companyId();
        Set<UUID> groupIds = groups.groupsOf(companyId, userId);
        List<AccountStore.GrantRow> rows = store.grantsTo(companyId, system.id(), groupIds);
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Candidate> candidates = new ArrayList<>();
        Set<UUID> accounts = new HashSet<>();
        for (AccountStore.GrantRow row : rows) {
            // The same credential lent to two of the member's groups is one choice, through its first grant.
            if (!accounts.add(row.info().accountId())) {
                continue;
            }
            store.find(companyId, row.info().accountId())
                .ifPresent(account -> candidates.add(new Candidate(row.info(), account)));
        }
        candidates.sort(Comparator.comparing(
                (Candidate candidate) -> candidate.grant().access() != GrantInfo.Access.WRITE));
        return candidates;
    }

    // ---- The check at every use --------------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public AccountBinding.Check check(AccountBinding.Stored binding) {
        UUID companyId = binding.companyId();
        Optional<SystemInfo> system = systems.find(companyId, binding.systemId());
        if (system.isEmpty()) {
            return stopped(AccountBinding.StopReason.SYSTEM_GONE);
        }
        if (system.get().personal() && !systems.personalSystemsOn(companyId)) {
            return stopped(AccountBinding.StopReason.PERSONAL_SYSTEMS_OFF);
        }
        if (system.get().paused()) {
            return stopped(AccountBinding.StopReason.SYSTEM_PAUSED);
        }
        List<UUID> involved = binding.holderPrincipalId() != null
                ? List.of(binding.actingPrincipalId(), binding.holderPrincipalId())
                : List.of(binding.actingPrincipalId());
        Map<UUID, PrincipalInfo> found = principals.findAll(companyId, involved);
        PrincipalInfo acting = found.get(binding.actingPrincipalId());
        PrincipalInfo holder = binding.holderPrincipalId() != null ? found.get(binding.holderPrincipalId()) : null;
        if (acting == null || !acting.active() || (binding.holderPrincipalId() != null
                && (holder == null || !holder.active()))) {
            return stopped(AccountBinding.StopReason.PRINCIPAL_INACTIVE);
        }
        if (binding.grantId() != null) {
            return granted(binding, acting);
        }
        if (holder == null) {
            return stopped(AccountBinding.StopReason.RECONNECT_COMPANY);
        }
        if (holder.service()) {
            return lent(binding, acting, holder);
        }
        // One's own account, found by its holder: connecting it anew makes another, of the same identity.
        boolean own = holder.id().equals(acting.id());
        AccountBinding.StopReason reconnect = own ? AccountBinding.StopReason.RECONNECT_YOURS
                : AccountBinding.StopReason.RECONNECT_COMPANY;
        Optional<AccountStore.AccountRow> account = store.of(companyId, binding.systemId(), holder.id(), false);
        if (account.isEmpty()) {
            return stopped(reconnect);
        }
        if (binding.externalId() != null && !binding.externalId().equals(account.get().externalId())) {
            return stopped(AccountBinding.StopReason.IDENTITY_CHANGED);
        }
        if (account.get().status() != AccountInfo.Status.OK || account.get().kind() == AccountInfo.Kind.LINK) {
            // A link acts with nothing: as an account to connect again.
            return stopped(reconnect);
        }
        return new AccountBinding.Usable(account.get().info(), GrantInfo.Access.WRITE);
    }

    /** A credential a grant lends: still there, usable, the grant still there and the acting member in its group. */
    private AccountBinding.Check granted(AccountBinding.Stored binding, PrincipalInfo acting) {
        UUID companyId = binding.companyId();
        AccountBinding.@Nullable Check broken = brokenCredential(binding);
        if (broken != null) {
            return broken;
        }
        AccountStore.AccountRow account = store.find(companyId, binding.accountId()).orElseThrow();
        Optional<AccountStore.GrantRow> grant = store.grant(companyId, Objects.requireNonNull(binding.grantId()), false)
            .filter(row -> row.info().accountId().equals(account.id()));
        if (grant.isEmpty()) {
            return stopped(AccountBinding.StopReason.GRANT_GONE);
        }
        if (acting.userId() == null
                || !groups.groupsOf(companyId, acting.userId()).contains(grant.get().info().groupId())) {
            return stopped(AccountBinding.StopReason.NOT_IN_GROUP);
        }
        return new AccountBinding.Usable(account.info(), grant.get().info().access());
    }

    /**
     * A credential a trigger's service account borrows: the author may still use the service account, which still
     * borrows this credential (one it borrows in its place is the conversation's to follow), and the credential is
     * usable.
     */
    private AccountBinding.Check lent(AccountBinding.Stored binding, PrincipalInfo acting, PrincipalInfo holder) {
        UUID companyId = binding.companyId();
        if (acting.userId() == null || !principals.mayUse(companyId, acting.userId(), holder.id())) {
            return stopped(AccountBinding.StopReason.USE_RIGHT_GONE);
        }
        Optional<AccountStore.LendingRow> lending = store.lending(companyId, holder.id(), binding.systemId(), false)
            .filter(row -> row.accountId().equals(binding.accountId()));
        if (lending.isEmpty()) {
            return stopped(AccountBinding.StopReason.USE_RIGHT_GONE);
        }
        AccountBinding.@Nullable Check broken = brokenCredential(binding);
        if (broken != null) {
            return broken;
        }
        AccountStore.AccountRow account = store.find(companyId, binding.accountId()).orElseThrow();
        return new AccountBinding.Usable(account.info(), lending.get().access());
    }

    /**
     * Why the bound credential cannot act now: gone, of another identity, not {@code OK}, disabled, or a chat
     * system's bot (C9), all for an admin; null when it can.
     */
    private AccountBinding.@Nullable Check brokenCredential(AccountBinding.Stored binding) {
        Optional<AccountStore.AccountRow> account = store.find(binding.companyId(), binding.accountId())
            .filter(row -> row.systemId().equals(binding.systemId()) && row.credential());
        if (account.isEmpty()) {
            return stopped(AccountBinding.StopReason.RECONNECT_COMPANY);
        }
        if (binding.externalId() != null && !binding.externalId().equals(account.get().externalId())) {
            return stopped(AccountBinding.StopReason.IDENTITY_CHANGED);
        }
        if (account.get().status() != AccountInfo.Status.OK || !account.get().enabled() || bot(account.get())) {
            return stopped(AccountBinding.StopReason.RECONNECT_COMPANY);
        }
        return null;
    }

    @Override
    @Transactional(readOnly = true)
    public AccountBinding.Check open(UUID companyId, UUID systemId, UUID actingPrincipalId) {
        Optional<SystemInfo> found = systems.find(companyId, systemId);
        if (found.isEmpty()) {
            return stopped(AccountBinding.StopReason.SYSTEM_GONE);
        }
        SystemInfo system = found.get();
        if (system.personal() && !systems.personalSystemsOn(companyId)) {
            return stopped(AccountBinding.StopReason.PERSONAL_SYSTEMS_OFF);
        }
        if (system.paused()) {
            return stopped(AccountBinding.StopReason.SYSTEM_PAUSED);
        }
        PrincipalInfo acting = principals.find(companyId, actingPrincipalId).filter(PrincipalInfo::member)
            .orElse(null);
        if (acting == null || !acting.active() || (system.personal() && !system.ownedBy(acting.id()))) {
            return stopped(AccountBinding.StopReason.PRINCIPAL_INACTIVE);
        }
        if (system.accountsRequired()) {
            return stopped(AccountBinding.StopReason.ACCOUNT_REQUIRED);
        }
        return new AccountBinding.Open(system.id());
    }

    private static AccountBinding.Stopped stopped(AccountBinding.StopReason reason) {
        return new AccountBinding.Stopped(reason);
    }

    // ---- Lookups and the credential ----------------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountInfo> find(UUID companyId, UUID accountId) {
        return store.find(companyId, accountId).map(AccountStore.AccountRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AccountInfo> of(UUID companyId, UUID systemId, UUID principalId) {
        return store.of(companyId, systemId, principalId, false).map(AccountStore.AccountRow::info);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountInfo> credentialsConnectedBy(UUID companyId, UUID userId) {
        return store.credentialsConnectedBy(companyId, userId).stream().map(AccountStore.AccountRow::info).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<AccountInfo> ofPrincipal(UUID companyId, UUID principalId) {
        return store.ofPrincipal(companyId, principalId).stream().map(AccountStore.AccountRow::info).toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Lent> lentTo(UUID companyId, UUID systemId, UUID servicePrincipalId) {
        return store.lending(companyId, servicePrincipalId, systemId, false).flatMap(this::lent);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<UUID> lentBy(UUID companyId, UUID systemId, UUID servicePrincipalId) {
        return store.lentBy(companyId, servicePrincipalId, systemId);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Lent> borrowedBy(UUID companyId, UUID servicePrincipalId) {
        return store.lendingsTo(companyId, servicePrincipalId).stream()
            .map(this::lent)
            .flatMap(Optional::stream)
            .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public GrantInfo.Access fixedAccess(UUID companyId, UUID systemId, UUID holderPrincipalId, UUID accountId) {
        boolean credential = store.find(companyId, accountId).map(AccountStore.AccountRow::credential).orElse(true);
        if (!credential) {
            return GrantInfo.Access.WRITE;
        }
        return store.lending(companyId, holderPrincipalId, systemId, false)
            .filter(lending -> lending.accountId().equals(accountId))
            .map(AccountStore.LendingRow::access)
            .orElse(GrantInfo.Access.READ);
    }

    @Override
    @Transactional(readOnly = true)
    public List<UUID> borrowers(UUID companyId, UUID credentialId) {
        return store.lendingsOf(companyId, credentialId).stream()
            .map(AccountStore.LendingRow::servicePrincipalId)
            .toList();
    }

    private Optional<Lent> lent(AccountStore.LendingRow lending) {
        return store.find(lending.companyId(), lending.accountId())
            .map(account -> new Lent(account.info(), lending.servicePrincipalId(), lending.access()));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<GrantInfo> grant(UUID companyId, UUID grantId) {
        return store.grant(companyId, grantId, false).map(AccountStore.GrantRow::info);
    }

    @Override
    public Optional<RevealedCredential> reveal(UUID companyId, UUID accountId, @Nullable UUID actingPrincipalId,
            String purpose) {
        return credentials.reveal(companyId, accountId, actingPrincipalId, purpose);
    }

    @Override
    @Transactional
    public boolean refused(UUID companyId, UUID accountId, String reason) {
        return credentials.refused(companyId, accountId, reason);
    }
}
