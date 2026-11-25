package com.flowtivo.tinfirm.shared.web;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.Nullable;
import org.springframework.core.Ordered;
import org.springframework.session.web.http.SessionRepositoryFilter;
import org.springframework.web.servlet.HandlerExceptionResolver;
import org.springframework.web.servlet.ModelAndView;

import com.flowtivo.tinfirm.shared.tx.AfterTransaction;

/**
 * The first of the dispatcher's exception resolvers: a request with a session whose failure is the pool's timeout, and
 * which the rule of the busy answer answers ({@link ServerBusy}), is answered by {@link RequestInputFilter}, outside
 * Spring Session's filter, never by the API's handler inside it.
 * <p>
 * An answer the handler writes inside Spring Session's filter commits the session when it is committed. After an
 * earlier commit of this request failed (the session's save when the answer was about to be committed), Spring Session
 * has forgotten the requested session and looks it up again, and that lookup fails at once
 * ({@code shared.tx.SessionTransactions}): the handler's answer failed, Spring logged the failure of the handler, and
 * the outermost filter answered and logged a second time. Here the request is marked and the original failure thrown
 * on, which Spring neither handles further nor logs; Spring Session's commit at the end of its filter skips its save
 * or fails at once ({@code SessionTransactions}); the outermost filter answers once, on the plain response, with the
 * original failure ({@link #FAILURE}), and logs it once. A request without a session (SCIM, a bearer token, the runner
 * hosts) is answered by the handlers, in its own form.
 */
final class BusyAnswerOutsideTheSession implements HandlerExceptionResolver, Ordered {

    /** The request attribute that holds the failure the outermost filter answers. */
    static final String FAILURE = BusyAnswerOutsideTheSession.class.getName() + ".failure";

    /**
     * The request attribute of the session Spring Session's filter holds for the request (its own name, which
     * {@code SessionBookkeepingAfterTheAnswerIntegrationTest} pins through what it shows).
     */
    private static final String CURRENT_SESSION = SessionRepositoryFilter.SESSION_REPOSITORY_ATTR + ".CURRENT_SESSION";

    @Override
    public @Nullable ModelAndView resolveException(HttpServletRequest request, HttpServletResponse response,
            @Nullable Object handler, Exception ex) {
        if (request.getAttribute(CURRENT_SESSION) == null || response.isCommitted() || !ServerBusy.timedOut(ex)
                || ServerBusy.decide(ex, request.getMethod()) == ServerBusy.Answer.NONE) {
            return null;
        }
        request.setAttribute(FAILURE, ex);
        AfterTransaction.answeredBusy(true);
        throw BusyAnswerOutsideTheSession.<RuntimeException>onward(ex);
    }

    /** The failure thrown on as it is, a checked one included: the dispatcher throws it on itself when unresolved. */
    @SuppressWarnings("unchecked")
    private static <T extends Throwable> T onward(Throwable thrown) throws T {
        throw (T) thrown;
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
