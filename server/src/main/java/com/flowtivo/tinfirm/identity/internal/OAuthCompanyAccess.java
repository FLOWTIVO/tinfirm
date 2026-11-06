package com.flowtivo.tinfirm.identity.internal;

import java.util.Optional;
import java.util.UUID;

import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.flowtivo.tinfirm.identity.MembershipDirectory;
import com.flowtivo.tinfirm.shared.access.CompanyAccess;
import com.flowtivo.tinfirm.shared.access.CompanyAccessGuard;
import com.flowtivo.tinfirm.shared.access.CompanyPermission;
import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;
import com.flowtivo.tinfirm.shared.web.ApiException;

/**
 * Whether a user may authorize a client for a company (ADR-0045): an active member may, as the company's access guard
 * decides for every company-scoped request of the web app; anyone else may not, without learning whether the company
 * exists. An instance admin who is not a member may not either: there is no break-glass through a client, which is a
 * personal space acting as a member (the package 6 contract, G9, §5.10), so they are refused before the guard, which
 * would record a break-glass. A suspended member is refused too (open point 5): the guard's
 * {@code requireActiveMember} answers them as a non-member, and nothing is recorded. The authorization server's
 * requests carry only the user's id, so the check runs here as that user, loaded fresh.
 */
@Component
class OAuthCompanyAccess {

    private final UserRepository users;

    private final CompanyAccessGuard guard;

    private final TransactionTemplate transactions;

    private final MembershipDirectory memberships;

    OAuthCompanyAccess(UserRepository users, CompanyAccessGuard guard, MembershipDirectory memberships,
            PlatformTransactionManager transactionManager) {
        this.users = users;
        this.guard = guard;
        this.memberships = memberships;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** The active user of a principal name (a user id), loaded fresh. */
    Optional<AuthenticatedUser> activeUser(String principalName) {
        try {
            return users.findById(UUID.fromString(principalName))
                .filter(UserEntity::isActive)
                .map(UserEntity::toAuthenticatedUser);
        }
        catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
    }

    boolean mayAuthorize(String principalName, UUID companyId) {
        Optional<AuthenticatedUser> user = activeUser(principalName);
        if (user.isEmpty()) {
            return false;
        }
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext acting = SecurityContextHolder.createEmptyContext();
        acting.setAuthentication(new ActorAuthentication(user.get()));
        SecurityContextHolder.setContext(acting);
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.get().id(), user.get().instanceAdmin()))) {
            if (user.get().instanceAdmin() && memberships.membershipsOf(user.get().id())
                .stream()
                .noneMatch(membership -> membership.companyId().equals(companyId))) {
                return false;
            }
            transactions.executeWithoutResult(status -> {
                CompanyAccess access = guard.requireActiveMember(companyId, CompanyPermission.VIEW_COMPANY);
                if (access.role() == null) {
                    throw ApiException.notFound("Company not found");
                }
            });
            return true;
        }
        catch (ApiException ex) {
            return false;
        }
        finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
