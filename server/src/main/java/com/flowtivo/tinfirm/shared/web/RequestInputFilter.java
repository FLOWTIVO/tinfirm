package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.jspecify.annotations.Nullable;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.shared.tx.FailedAfterCommit;

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
 * It also hands on the request as {@link NulRefusingRequest}, which refuses U+0000 in a parameter, a part's name
 * and a JSON body's strings when they are read: one rule at the boundary instead of one check per endpoint. Two paths
 * keep their JSON bodies unscanned, because they take them as bytes on purpose: the webhook inbox, which refuses
 * U+0000 itself and audits the delivery it refuses ({@code trigger.internal.WebhookInbox}), and the model relay, whose
 * bodies go to the model gateway as they are and never into SQL ({@code model.internal.ModelRelayImpl}).
 * <p>
 * The filter reads nothing itself, so a body stays unread until its handler reads it (a multipart is resolved
 * lazily, after authentication). Its cost per request is the wrapper, a scan of each parameter value when it is read,
 * and one pass over a JSON body's bytes as its reader reads them.
 */
final class RequestInputFilter extends OncePerRequestFilter {

    /** The paths whose JSON bodies are not scanned for U+0000 (above). */
    private static final List<String> RAW_BODIES = List.of("/api/hooks/", "/api/runner/v1/model/");

    private final HandlerExceptionResolver resolver;

    RequestInputFilter(HandlerExceptionResolver resolver) {
        this.resolver = resolver;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        // The decoded and normalized path: the servlet's path, and the rest of it under a servlet mapped by prefix.
        String path = request.getServletPath() + (request.getPathInfo() != null ? request.getPathInfo() : "");
        boolean scanBody = RAW_BODIES.stream().noneMatch(path::startsWith);
        try {
            chain.doFilter(new NulRefusingRequest(request, scanBody), response);
        }
        catch (IOException | ServletException | RuntimeException ex) {
            if (response.isCommitted()) {
                throw ex;
            }
            Exception refusal = refusal(ex);
            ServerBusy.Answer answer = refusal == null ? ServerBusy.decide(ex, request.getMethod())
                    : ServerBusy.Answer.NONE;
            if (refusal == null && answer == ServerBusy.Answer.NONE) {
                throw ex;
            }
            answer(request, response, refusal != null ? refusal : ServerBusy.exception(answer, ex));
        }
        catch (Error error) {
            // An Error a step after a saved change threw is still answered as such, and then thrown as it is.
            ServerBusy.Answer answer = ServerBusy.decide(error, request.getMethod());
            if (answer == ServerBusy.Answer.SAVED_THEN_FAILED && !response.isCommitted()) {
                answer(request, response, ServerBusy.exception(answer, error));
                response.flushBuffer();
            }
            throw error;
        }
    }

    private void answer(HttpServletRequest request, HttpServletResponse response, Exception refusal)
            throws IOException {
        response.resetBuffer();
        if (resolver.resolveException(request, response, null, refusal) == null) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);
        }
    }

    /**
     * The cause that says the request's input cannot be read, or null: Tomcat's, a NUL in a JSON body, or the
     * {@link ApiException} of a NUL in a parameter, which a filter's {@code getParameter} may throw. The answers for
     * want of a database connection and after a commit are {@link ServerBusy}'s.
     */
    private static @Nullable Exception refusal(Throwable thrown) {
        int depth = 0;
        for (Throwable cause = thrown; cause != null && depth < 32; cause = cause.getCause(), depth++) {
            if (cause instanceof FailedAfterCommit) {
                return null;
            }
            if (cause instanceof InvalidParameterException || cause instanceof ApiException) {
                return (Exception) cause;
            }
            if (cause instanceof NulCharacterException) {
                return ApiException.validation(NulCharacterException.DETAIL);
            }
        }
        return null;
    }
}
