package com.flowtivo.tinfirm.company.internal;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.flowtivo.tinfirm.company.MemberAccounts;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.api.model.MemberAccount;
import com.flowtivo.tinfirm.shared.api.model.MemberAccountList;
import com.flowtivo.tinfirm.shared.api.model.SystemKind;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * A member's accounts on the Members page (the package 1 contract, I4): OWNER and ADMIN see on which company systems a
 * member holds an account and whether it works, never more ({@link MemberAccounts}); only the member manages them.
 */
@Service
class MemberAccountsReader {

    private final MembershipRepository memberships;

    private final CompanyAccessGuard companyAccess;

    private final MemberAccounts accounts;

    MemberAccountsReader(MembershipRepository memberships, CompanyAccessGuard companyAccess,
            MemberAccounts accounts) {
        this.memberships = memberships;
        this.companyAccess = companyAccess;
        this.accounts = accounts;
    }

    /** The member's accounts on company systems, by system name; 404 for a user who is not a member. */
    @Transactional(readOnly = true)
    public MemberAccountList list(UUID companyId, UUID userId) {
        companyAccess.require(companyId, CompanyPermission.MANAGE_MEMBERS);
        if (!memberships.existsByCompanyIdAndUserId(companyId, userId)) {
            throw ApiException.notFound("Member not found");
        }
        return new MemberAccountList(accounts.accountsOf(companyId, userId)
            .stream()
            .map(held -> new MemberAccount(held.systemId(), held.systemName(),
                    MemberAccount.StatusEnum.fromValue(held.status()))
                .systemKind(SystemKind.fromValue(held.systemKind())))
            .toList());
    }
}
