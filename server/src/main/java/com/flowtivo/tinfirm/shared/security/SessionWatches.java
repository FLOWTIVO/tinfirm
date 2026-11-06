package com.flowtivo.tinfirm.shared.security;

/** Opens a {@link SessionWatch} on the browser session of the request being handled. */
public interface SessionWatches {

    /**
     * Watches the current request's HTTP session, from the request's own thread; {@link SessionWatch#NONE} when the
     * request has none.
     */
    SessionWatch open();
}
