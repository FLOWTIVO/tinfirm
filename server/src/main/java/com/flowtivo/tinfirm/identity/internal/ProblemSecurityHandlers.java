package com.flowtivo.tinfirm.identity.internal;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * JSON 401 and 403 for {@code /api/**} (never a redirect): delegates to the MVC exception handling, so the body is
 * the same problem detail format as every other API error.
 */
final class ProblemSecurityHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final HandlerExceptionResolver resolver;

    ProblemSecurityHandlers(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
            AuthenticationException exception) throws IOException {
        if (resolver.resolveException(request, response, null, exception) == null) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED);
        }
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response, AccessDeniedException exception)
            throws IOException {
        if (resolver.resolveException(request, response, null, exception) == null) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);
        }
    }
}
