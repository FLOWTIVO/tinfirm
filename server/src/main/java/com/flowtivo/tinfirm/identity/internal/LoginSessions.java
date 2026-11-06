package com.flowtivo.tinfirm.identity.internal;

import java.util.UUID;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.context.SecurityContextHolderStrategy;
import org.springframework.security.web.authentication.session.SessionAuthenticationStrategy;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.security.web.csrf.CsrfTokenRepository;

/** Starts and ends server-side sessions for the JSON login and logout endpoints. */
final class LoginSessions {

    private final SecurityContextRepository contextRepository;

    private final SessionAuthenticationStrategy sessionStrategy;

    private final CsrfTokenRepository csrfTokenRepository;

    private final SecurityContextHolderStrategy holder = SecurityContextHolder.getContextHolderStrategy();

    LoginSessions(SecurityContextRepository contextRepository, SessionAuthenticationStrategy sessionStrategy,
            CsrfTokenRepository csrfTokenRepository) {
        this.contextRepository = contextRepository;
        this.sessionStrategy = sessionStrategy;
        this.csrfTokenRepository = csrfTokenRepository;
    }

    /**
     * Rotates the session id (session fixation) and the CSRF token, then stores the authentication in the session.
     * The response carries the new session cookie and a new {@code XSRF-TOKEN} cookie.
     */
    void establish(UUID userId, HttpServletRequest request, HttpServletResponse response) {
        Authentication authentication = UsernamePasswordAuthenticationToken.authenticated(new SessionPrincipal(userId),
                null, AuthorityUtils.NO_AUTHORITIES);
        sessionStrategy.onAuthentication(authentication, request, response);
        SecurityContext context = holder.createEmptyContext();
        context.setAuthentication(authentication);
        holder.setContext(context);
        contextRepository.saveContext(context, request, response);
        csrfTokenRepository.saveToken(csrfTokenRepository.generateToken(request), request, response);
    }

    /**
     * Deletes the session from the database (Spring Session then expires the {@code TINFIRM_SESSION} cookie) and
     * clears the {@code XSRF-TOKEN} cookie.
     */
    void end(HttpServletRequest request, HttpServletResponse response) {
        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }
        holder.clearContext();
        csrfTokenRepository.saveToken(null, request, response);
    }
}
