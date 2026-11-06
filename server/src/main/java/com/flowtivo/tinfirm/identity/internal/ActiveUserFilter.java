package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;

/**
 * Turns the session principal (a user id) into the request's authentication, loaded fresh from the database:
 * <ul>
 * <li>a deactivated or missing user loses the session at once (it is deleted) and continues as anonymous;</li>
 * <li>so does a session whose principal is not a Tinfirm user ({@link SessionUser}), such as one a sign-in that
 * skipped the provisioning would leave: only Tinfirm's own sign-ins name a session's user (M17 of the security
 * review of 2026-10-27);</li>
 * <li>instance-admin changes take effect on the next request;</li>
 * <li>the tenant context gets the user id and instance-admin flag for every transaction of the request.</li>
 * </ul>
 * The per-request authentication is never written back to the session.
 */
final class ActiveUserFilter extends OncePerRequestFilter {

    private final UserRepository users;

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    ActiveUserFilter(UserRepository users) {
        this.users = users;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = holder.getContext().getAuthentication();
        if (authentication == null || authentication instanceof AnonymousAuthenticationToken) {
            chain.doFilter(request, response);
            return;
        }
        if (!(authentication.getPrincipal() instanceof SessionUser sessionUser)) {
            endSession(request);
            chain.doFilter(request, response);
            return;
        }

        Optional<AuthenticatedUser> user = findActive(sessionUser.userId());
        if (user.isEmpty()) {
            endSession(request);
            chain.doFilter(request, response);
            return;
        }

        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(new ActorAuthentication(user.get()));
        holder.setContext(context);
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.get().id(), user.get().instanceAdmin()))) {
            chain.doFilter(request, response);
        }
    }

    private Optional<AuthenticatedUser> findActive(UUID userId) {
        return users.findById(userId).filter(UserEntity::isActive).map(UserEntity::toAuthenticatedUser);
    }

    /** The session is deleted and the request continues as anonymous. */
    private void endSession(HttpServletRequest request) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
    }
}
