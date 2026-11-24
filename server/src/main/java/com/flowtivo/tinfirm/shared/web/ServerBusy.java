package com.flowtivo.tinfirm.shared.web;

import java.sql.SQLTransientConnectionException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;

import com.flowtivo.tinfirm.shared.tx.AfterTransaction;
import com.flowtivo.tinfirm.shared.tx.FailedAfterCommit;

/**
 * What a request that failed for want of a database connection, or after a step that followed a commit, is told. One
 * rule, decided by what the server knows: the request's method, and whether a transaction that was not read-only, and
 * not bookkeeping (a lookup, a mark of last use, a record of an access: {@link AfterTransaction#BOOKKEEPING}),
 * committed in the request ({@link AfterTransaction#savedInThisWork}): it knows that such a transaction committed, not
 * which rows changed.
 * <table>
 * <caption>The answers</caption>
 * <tr><th>Method</th><th>Such a commit in the request</th><th>Failure</th><th>Answer</th></tr>
 * <tr><td>GET, HEAD, OPTIONS</td><td>any</td><td>the pool's timeout</td><td>503 {@code SERVER_BUSY},
 * {@code Retry-After}</td></tr>
 * <tr><td>GET, HEAD, OPTIONS</td><td>any</td><td>a step after a commit</td><td>500, as a failed read: nothing
 * returned</td></tr>
 * <tr><td>other</td><td>none</td><td>the pool's timeout</td><td>503 {@code SERVER_BUSY}, {@code Retry-After}:
 * nothing of it can be saved</td></tr>
 * <tr><td>other</td><td>none</td><td>a step after a commit</td><td>500, as any failure</td></tr>
 * <tr><td>other</td><td>yes</td><td>the pool's timeout, or a step after a commit</td><td>500 with
 * {@link #SAVED_THEN_FAILED}, no {@code Retry-After}, never 503</td></tr>
 * </table>
 * The pool gives up after {@code SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT} with an
 * {@link SQLTransientConnectionException}, which Spring wraps in its own exceptions; a step after a commit fails as a
 * {@link FailedAfterCommit}, or as an {@link Error} with one suppressed in it. Every other failure, after such a commit
 * too, is answered as it was before this rule. Outside a request nothing changes: the exceptions are what they are. An
 * MCP tool answers {@link #SAVED_THEN_FAILED} in its error where this rule says so ({@code mcp.internal.ToolInvoker}).
 */
public final class ServerBusy {

    private static final Logger log = LoggerFactory.getLogger(ServerBusy.class);

    /** How long the client waits before it asks again. */
    public static final Duration RETRY_AFTER = Duration.ofSeconds(2);

    /**
     * The detail of a request in which a transaction that was not read-only, and not bookkeeping, committed before it
     * failed: the server knows that, not which rows changed.
     */
    public static final String SAVED_THEN_FAILED = "The request did not finish, but a change of it may already be "
            + "saved. Look at the result before you send it again.";

    /** The detail of a request that failed otherwise: the API's own. */
    static final String FAILED = "Internal server error";

    /** The most exceptions looked through: causes and suppressed ones, a cycle among them included. */
    private static final int MAX_EXCEPTIONS = 64;

    private ServerBusy() {
    }

    /** What the request is told. */
    public enum Answer {

        /** 503 {@code SERVER_BUSY} with {@code Retry-After}. */
        BUSY,

        /** 500 with {@link #SAVED_THEN_FAILED}. */
        SAVED_THEN_FAILED,

        /** 500, as a failure the server did not foresee. */
        FAILED,

        /** None of this rule's: the failure is answered as it would be. */
        NONE
    }

    /**
     * The answer to the failure of a request with this method ({@code null} outside a request: {@link Answer#NONE}).
     * Decides only; {@link #exception} logs and makes the answer.
     */
    public static Answer decide(Throwable thrown, @org.jspecify.annotations.Nullable String method) {
        if (method == null) {
            return Answer.NONE;
        }
        boolean timedOut = false;
        boolean afterCommit = false;
        for (Throwable each : chain(thrown)) {
            timedOut |= each instanceof SQLTransientConnectionException;
            afterCommit |= each instanceof FailedAfterCommit;
        }
        boolean safe = "GET".equals(method) || "HEAD".equals(method) || "OPTIONS".equals(method);
        if (safe || !AfterTransaction.savedInThisWork()) {
            return timedOut ? Answer.BUSY : afterCommit ? Answer.FAILED : Answer.NONE;
        }
        return timedOut || afterCommit ? Answer.SAVED_THEN_FAILED : Answer.NONE;
    }

    /** Whether the failure is the pool's: no free database connection in time, among its causes or suppressed ones. */
    public static boolean timedOut(Throwable thrown) {
        return chain(thrown).stream().anyMatch(SQLTransientConnectionException.class::isInstance);
    }

    /** The exception, its causes and its suppressed exceptions, each once, at most {@link #MAX_EXCEPTIONS}. */
    private static Set<Throwable> chain(Throwable thrown) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        Deque<Throwable> next = new ArrayDeque<>();
        next.add(thrown);
        while (!next.isEmpty() && seen.size() < MAX_EXCEPTIONS) {
            Throwable each = next.poll();
            if (!seen.add(each)) {
                continue;
            }
            if (each.getCause() != null) {
                next.add(each.getCause());
            }
            Collections.addAll(next, each.getSuppressed());
        }
        return seen;
    }

    /**
     * The answer as an {@link ApiException}, logged once here: the pool's timeout with the innermost cause's class and
     * message (a database that is down shows as such), a failure with its stack.
     */
    public static ApiException exception(Answer answer, Throwable thrown) {
        // Answered for want of a connection: Spring Session asks no more of the pool (shared.tx.SessionTransactions).
        AfterTransaction.answeredBusy(answer != Answer.NONE && timedOut(thrown));
        return switch (answer) {
            case BUSY -> {
                Throwable innermost = thrown;
                int depth = 0;
                while (innermost.getCause() != null && innermost.getCause() != innermost && depth++ < MAX_EXCEPTIONS) {
                    innermost = innermost.getCause();
                }
                log.warn("A request found no free database connection in time and was answered 503 SERVER_BUSY: "
                        + "{}: {}", innermost.getClass().getName(), innermost.getMessage());
                yield ApiException.serverBusy(RETRY_AFTER);
            }
            case SAVED_THEN_FAILED -> {
                log.error("A request failed after a change of it was committed", thrown);
                yield new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, null, SAVED_THEN_FAILED);
            }
            case FAILED, NONE -> {
                log.error("Unhandled exception", thrown);
                yield new ApiException(HttpStatus.INTERNAL_SERVER_ERROR, null, FAILED);
            }
        };
    }
}
