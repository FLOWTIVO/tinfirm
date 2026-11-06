package com.flowtivo.tinfirm.shared.security;

/**
 * The browser session behind a long-lived response (an event stream), as it stood when the response opened. A stream
 * asks it at every poll and ends once it says so: the request that opened the stream was the last check of the session
 * the servlet container made, so logout, a password change, the idle timeout and the absolute lifetime would not reach
 * it otherwise.
 */
@FunctionalInterface
public interface SessionWatch {

    /** For a caller without a browser session (a bearer token): there is nothing to watch. */
    SessionWatch NONE = () -> false;

    /** Whether the session no longer exists or its absolute lifetime has passed. */
    boolean ended();
}
