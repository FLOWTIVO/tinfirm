package com.flowtivo.tinfirm.model.internal;

import java.io.Serial;

/**
 * A call to LiteLLM's admin API that did not do what was asked: LiteLLM answered with an error ({@link #status()} its
 * HTTP status), or could not be reached ({@code status} 0). The message is for people: LiteLLM's own reason, cut
 * short, or why the gateway was not reached. It never holds a key, a URL with credentials or a request body.
 */
class LiteLlmException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    private final int status;

    LiteLlmException(int status, String message) {
        super(message, null, false, false);
        this.status = status;
    }

    static LiteLlmException unreachable(String why) {
        return new LiteLlmException(0, why);
    }

    static LiteLlmException refused(String why) {
        return new LiteLlmException(0, why);
    }

    /** LiteLLM's HTTP status, or 0 when it did not answer. */
    int status() {
        return status;
    }

    /** Whether LiteLLM answered that it does not know the key or team (404). */
    boolean notFound() {
        return status == 404;
    }
}
