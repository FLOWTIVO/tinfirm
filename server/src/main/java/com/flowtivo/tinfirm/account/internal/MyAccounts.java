package com.flowtivo.tinfirm.account.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.account.AccountBinding;
import com.flowtivo.tinfirm.company.GroupMembership;
import com.flowtivo.tinfirm.principal.PrincipalInfo;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.Account;
import com.flowtivo.tinfirm.shared.api.model.MyAccount;
import com.flowtivo.tinfirm.shared.api.model.MyAccountList;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.web.ApiException;
import com.flowtivo.tinfirm.system.SystemCalls;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.SystemKind;
import com.flowtivo.tinfirm.system.Systems;

/**
 * The caller's accounts page (the package 1 contract, §6 and §10): per system they see, their own account, every
 * usable grant (the one a conversation takes first), and what a conversation would bind now. Shows whether another
 * principal holds an account nowhere: a member sees only their own (I4). A webhook system is left out: it takes
 * deliveries under its signing secret and holds no one's account ({@code 400 SYSTEM_KIND_INVALID} on a connect).
 */
@Service
class MyAccounts {

    private final AccountStore store;

    private final AccountsImpl accounts;

    private final Principals principals;

    private final Systems systems;

    private final SystemCalls calls;

    private final GroupMembership groups;

    private final CompanyAccessGuard guard;

    private final AccountMapper mapper;

    MyAccounts(AccountStore store, AccountsImpl accounts, Principals principals, Systems systems, SystemCalls calls,
            GroupMembership groups, CompanyAccessGuard guard, AccountMapper mapper) {
        this.store = store;
        this.accounts = accounts;
        this.principals = principals;
        this.systems = systems;
        this.calls = calls;
        this.groups = groups;
        this.guard = guard;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public MyAccountList list(UUID companyId) {
        guard.require(companyId, CompanyPermission.VIEW_SYSTEMS);
        UUID userId = CurrentActor.requireUser().id();
        PrincipalInfo member = principals.ofMember(companyId, userId)
            .orElseThrow(() -> ApiException.notFound("Only members hold accounts"));
        List<MyAccount> items = new ArrayList<>();
        for (SystemInfo system : systems.visibleTo(companyId, member.id())) {
            if (system.kind() == SystemKind.WEBHOOK) {
                continue;
            }
            List<AccountsImpl.Candidate> usable = system.personal() ? List.of()
                    : accounts.candidates(system, userId).stream().filter(AccountsImpl.Candidate::usable).toList();
            Map<UUID, String> groupNames = groups.groupNames(companyId,
                    usable.stream().map(candidate -> candidate.grant().groupId()).toList());
            List<AccountMapper.GrantChoiceDetails> choices = usable.stream()
                .map(candidate -> new AccountMapper.GrantChoiceDetails(candidate.grant().id(),
                        candidate.account().id(), Objects.requireNonNullElse(candidate.account().name(), ""),
                        candidate.grant().groupId(),
                        Objects.requireNonNullElse(groupNames.get(candidate.grant().groupId()), ""),
                        candidate.grant().access()))
                .toList();
            AccountMapper.AccountDetails own = store.of(companyId, system.id(), member.id(), false)
                .map(AccountMapper.AccountDetails::of)
                .orElse(null);
            String binding = binding(accounts.bind(new AccountBinding.Personal(companyId, member.id()), system.id()));
            items.add(mapper.toDto(new AccountMapper.MyAccountDetails(new AccountMapper.SystemRefDetails(system.id(),
                    system.name(), system.handle(), system.kind(), system.personal()), own, choices, binding,
                    calls.oauthAvailable(system))));
        }
        return new MyAccountList(items);
    }

    /**
     * What a conversation of the caller would bind now on the system, as {@link #list} names it: the answer of a token
     * stored as their own carries it.
     */
    @Transactional(readOnly = true)
    public Account.BindingEnum bindingOn(UUID companyId, UUID systemId) {
        guard.require(companyId, CompanyPermission.VIEW_SYSTEMS);
        UUID userId = CurrentActor.requireUser().id();
        PrincipalInfo member = principals.ofMember(companyId, userId)
            .orElseThrow(() -> ApiException.notFound("Only members hold accounts"));
        return Account.BindingEnum.fromValue(binding(accounts.bind(new AccountBinding.Personal(companyId,
                member.id()), systemId)));
    }

    /** The outcome of the binding rule, as the page names it. */
    private static String binding(AccountBinding.Outcome outcome) {
        return switch (outcome) {
            case AccountBinding.Own own -> "OWN";
            case AccountBinding.Granted granted -> "GRANTED";
            case AccountBinding.Choose choose -> "CHOOSE";
            case AccountBinding.Fixed fixed -> "OWN";
            case AccountBinding.NotEntitled notEntitled -> "UNAVAILABLE";
            case AccountBinding.Open open -> "NOT_NEEDED";
            case AccountBinding.ConnectRequired connect -> switch (connect.reason()) {
                case NONE -> "CONNECT";
                case RECONNECT_YOURS -> "RECONNECT_YOURS";
                case RECONNECT_COMPANY -> "RECONNECT_COMPANY";
                case CHOOSE -> "RECONNECT_OR_GRANT";
            };
        };
    }
}
