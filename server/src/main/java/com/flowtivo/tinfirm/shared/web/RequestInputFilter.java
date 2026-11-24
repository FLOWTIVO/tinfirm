package com.flowtivo.tinfirm.shared.web;

import java.io.IOException;
import java.util.List;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.apache.tomcat.util.http.InvalidParameterException;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;

import com.flowtivo.tinfirm.shared.tx.AfterTransaction;
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
 * A failure for want of a database connection from Spring Session's commit at the end of its filter, once the chain
 * inside it returned ({@link SessionAnswered}), is not thrown on: when the request was answered by the rule of the busy
 * answer, the response holds that answer, already logged, and nothing more is said; else, when the answer was
 * committed whole, a warning is logged, since thrown on, the failure would make the container cut the answer. Not
 * committed, it is answered by the rule. A failure inside that chain after the commit is thrown on as before: its
 * answer is not complete, and the connection must end.
 * <p>
 * It also answers a request with a session whose failure is the pool's timeout, which the dispatcher leaves to it
 * ({@link BusyAnswerOutsideTheSession}): written here, on the plain response, the answer commits nothing of the
 * session.
 * <p>
 * The filter reads nothing itself, so a body stays unread until its handler reads it (a multipart is resolved
 * lazily, after authentication). Its cost per request is the wrapper, a scan of each parameter value when it is read,
 * and one pass over a JSON body's bytes as its reader reads them.
 */
final class RequestInputFilter extends OncePerRequestFilter {

    /** The paths whose JSON bodies are not scanned for U+0000 (above). */
    private static final List<String> RAW_BODIES = List.of("/api/hooks/", "/api/runner/v1/model/");

    private static final Logger log = LoggerFactory.getLogger(RequestInputFilter.class);

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
            if (request.getAttribute(SessionAnswered.ANSWERED) != null && ServerBusy.timedOut(ex)
                    && AfterTransaction.answeredBusy()) {
                // The chain inside Spring Session's filter returned with the request answered by the rule of the busy
                // answer, which logged it: the response holds that answer, committed or not, and Spring Session's
                // bookkeeping after it failed for want of a connection as the request did. Nothing more to say.
                return;
            }
            if (response.isCommitted()) {
                if (request.getAttribute(SessionAnswered.ANSWERED) != null && ServerBusy.timedOut(ex)) {
                    // The chain inside Spring Session's filter returned: the answer is whole and committed. Spring
                    // Session saved the session when the answer was committed; at the end of its filter it commits
                    // again: it reads the requested session once more (its first commit forgot it) to compare the
                    // session's id, and saves the session, which writes only what changed after the answer was
                    // committed. That is what is lost here, with the session's id in a cookie that a committed answer
                    // can no longer carry anyway. Thrown on, the failure would make the container cut the answer.
                    log.warn("The session's bookkeeping after an answered request found no free database connection;"
                            + " the answer is complete: {}", ex.toString());
                    return;
                }
                throw ex;
            }
            // A failure the dispatcher left to this filter (BusyAnswerOutsideTheSession) is answered as itself, also
            // when Spring Session's commit at the end of its filter failed after it and took its place.
            Throwable failure = request.getAttribute(BusyAnswerOutsideTheSession.FAILURE) instanceof Exception left
                    ? left : ex;
            Exception refusal = failure == ex ? refusal(ex) : null;
            ServerBusy.Answer answer = refusal == null ? ServerBusy.decide(failure, request.getMethod())
                    : ServerBusy.Answer.NONE;
            if (refusal == null && answer == ServerBusy.Answer.NONE) {
                throw ex;
            }
            answer(request, response, refusal != null ? refusal : ServerBusy.exception(answer, failure));
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
