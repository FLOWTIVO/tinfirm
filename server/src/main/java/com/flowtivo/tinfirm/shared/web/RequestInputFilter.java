package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

/**
 * The first filter of every request, before Spring Security's chains and the MCP endpoint's filter. When the
 * request's input cannot be read, it answers the problem that {@link ApiExceptionHandler} gives in the dispatcher,
 * whichever filter read the input first.
 * <p>
 * Tomcat parses the parameters when something first asks for one, and from Servlet 6.1 on it throws
 * {@link InvalidParameterException} from {@code getParameter} when it cannot: a value that is not valid UTF-8, a
 * malformed escape, too many parameters, a form body that is too large. That can be Spring Security's CSRF filter,
 * the authorization server's filters or the OIDC login's, before any controller. Without this filter such a request
 * reached Spring Boot's error page (400, its own JSON without a {@code code}); in the dispatcher it was an unhandled
 * 500.
 * <p>
 * The filter reads nothing itself, so a body stays unread until its handler reads it (a multipart is resolved
 * lazily, after authentication).
 */
final class RequestInputFilter extends OncePerRequestFilter {

    private final HandlerExceptionResolver resolver;

    RequestInputFilter(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        try {
            chain.doFilter(request, response);
        }
        catch (IOException | ServletException | RuntimeException ex) {
            Exception refusal = refusal(ex);
            if (refusal == null || response.isCommitted()) {
                throw ex;
            }
            response.resetBuffer();
            if (resolver.resolveException(request, response, null, refusal) == null) {
                response.sendError(HttpServletResponse.SC_BAD_REQUEST);
            }
        }
    }

    /** The cause that says the request's input cannot be read, or null. */
    private static @Nullable Exception refusal(Throwable thrown) {
        for (Throwable cause = thrown; cause != null; cause = cause.getCause()) {
            if (cause instanceof InvalidParameterException invalid) {
                return invalid;
            }
        }
        return null;
    }
}
