package com.flowtivo.tinfirm.mcp.internal;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Predicate;

import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.security.CurrentActor;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Whether the bearer call's user is an active member of the company, as every tool call's access check answers it
 * (G6, G9): a non-member, a suspended member and an instance admin who is not a member are not, and nothing is
 * recorded. {@link McpRequestFilter} asks it before it answers the Outlook add-in's {@code EDITION_REQUIRED} (ADR-0077,
 * decision 4), so that a caller who may not reach the company gets the endpoint's usual answer.
 */
@Component
class McpMembers implements Predicate<UUID> {

    private final CompanyAccessGuard guard;

    private final MembershipDirectory memberships;

    private final TransactionTemplate transactions;

    McpMembers(CompanyAccessGuard guard, MembershipDirectory memberships, PlatformTransactionManager transactionManager) {
        this.guard = guard;
        this.memberships = memberships;
        this.transactions = new TransactionTemplate(transactionManager);
        this.transactions.setReadOnly(true);
    }

    @Override
    public boolean test(UUID companyId) {
        Optional<AuthenticatedUser> user = CurrentActor.user();
        if (user.isEmpty()) {
            return false;
        }
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.get().id(), user.get().instanceAdmin()))) {
            if (user.get().instanceAdmin() && memberships.membershipsOf(user.get().id())
                .stream()
                .noneMatch(membership -> membership.companyId().equals(companyId))) {
                return false;
            }
            return Boolean.TRUE.equals(transactions.execute(status -> guard
                .requireActiveMember(companyId, CompanyPermission.VIEW_COMPANY)
                .role() != null));
        }
        catch (ApiException ex) {
            return false;
        }
    }
}
