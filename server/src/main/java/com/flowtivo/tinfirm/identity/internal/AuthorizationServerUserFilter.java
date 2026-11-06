package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.web.filter.OncePerRequestFilter;

import com.flowtivo.tinfirm.shared.security.ActorAuthentication;
import com.flowtivo.tinfirm.shared.security.AuthenticatedUser;
import com.flowtivo.tinfirm.shared.tenant.TenantContext;
import com.flowtivo.tinfirm.shared.tenant.TenantContextHolder;

/**
 * The session's user on the authorization server's chain (ADR-0045), as {@link ActiveUserFilter} does for the web
 * app: loaded fresh, a deactivated user loses the session and continues as anonymous, and so does a session whose
 * principal is not a Tinfirm user ({@link SessionUser}), whose name must never be taken for a user id (M17 of the
 * security review of 2026-10-27). The request's authentication
 * is a plain {@link UsernamePasswordAuthenticationToken} named by the user id, never the session's principal: Spring
 * stores the authentication of an authorization in {@code oauth2_authorization} as JSON, and that must hold no
 * personal data and only types Spring's JSON allow-list reads back. It is never written to the session.
 */
final class AuthorizationServerUserFilter extends OncePerRequestFilter {

    private final OAuthCompanyAccess users;

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    AuthorizationServerUserFilter(OAuthCompanyAccess users) {
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
        Optional<AuthenticatedUser> user = users.activeUser(sessionUser.userId().toString());
        if (user.isEmpty()) {
            endSession(request);
            chain.doFilter(request, response);
            return;
        }
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(UsernamePasswordAuthenticationToken.authenticated(user.get().id().toString(), null,
                List.of(new SimpleGrantedAuthority(ActorAuthentication.ROLE_USER))));
        holder.setContext(context);
        try (TenantContextHolder.Scope ignored = TenantContextHolder
            .open(TenantContext.forUser(user.get().id(), user.get().instanceAdmin()))) {
            chain.doFilter(request, response);
        }
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
