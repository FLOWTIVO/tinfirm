package com.flowtivo.tinfirm.account.internal;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.flowtivo.tinfirm.company.MemberAccounts;
import com.flowtivo.tinfirm.principal.Principals;
import com.flowtivo.tinfirm.system.SystemInfo;
import com.flowtivo.tinfirm.system.Systems;

/**
 * {@link MemberAccounts} over the account store (the package 1 contract, I4): a member's accounts on company systems
 * with their status, for the admins' Members page; nothing of a personal MCP server, nor who the account is there.
 */
@Component
class AccountsOfMembers implements MemberAccounts {

    private final AccountStore store;

    private final Principals principals;

    private final Systems systems;

    AccountsOfMembers(AccountStore store, Principals principals, Systems systems) {
        this.store = store;
        this.principals = principals;
        this.systems = systems;
    }

    @Override
    public List<Held> accountsOf(UUID companyId, UUID userId) {
        return principals.ofMember(companyId, userId).map(member -> {
            List<AccountStore.AccountRow> rows = store.ofPrincipal(companyId, member.id());
            Map<UUID, SystemInfo> found = systems.findAll(companyId,
                    rows.stream().map(AccountStore.AccountRow::systemId).toList());
            return rows.stream()
                .filter(row -> found.containsKey(row.systemId()) && !found.get(row.systemId()).personal())
                .map(row -> new Held(row.systemId(), found.get(row.systemId()).name(),
                        found.get(row.systemId()).kind().name(), row.status().name()))
                .sorted(Comparator.comparing(Held::systemName, String.CASE_INSENSITIVE_ORDER)
                    .thenComparing(Held::systemId))
                .toList();
        }).orElse(List.of());
    }
}
